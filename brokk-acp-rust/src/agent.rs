use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use agent_client_protocol::schema::{
    AgentCapabilities, AvailableCommand, AvailableCommandsUpdate, CancelNotification,
    ConfigOptionUpdate, ContentBlock, ContentChunk, InitializeRequest, InitializeResponse,
    ListSessionsRequest, ListSessionsResponse, LoadSessionRequest, LoadSessionResponse,
    NewSessionRequest, NewSessionResponse, PromptCapabilities, PromptRequest, PromptResponse,
    ResumeSessionRequest, ResumeSessionResponse, SessionCapabilities, SessionConfigOption,
    SessionConfigOptionCategory, SessionConfigSelectOption, SessionInfo, SessionListCapabilities,
    SessionMode as AcpSessionMode, SessionModeState, SessionNotification,
    SessionResumeCapabilities, SessionUpdate, SetSessionConfigOptionRequest,
    SetSessionConfigOptionResponse, SetSessionModeRequest, SetSessionModeResponse, StopReason,
    TextContent,
};
use agent_client_protocol::{
    Agent, ByteStreams, Client, ConnectionTo, Dispatch, Handled, Responder, on_receive_dispatch,
    on_receive_notification, on_receive_request,
};
use tokio_util::compat::{TokioAsyncReadCompatExt, TokioAsyncWriteCompatExt};

use crate::llm_client::{ChatMessage, LlmBackend, ModelMetadata};
use crate::multi_backend::MultiBackend;
use crate::session::{
    ConversationTurn, PermissionMode, Session, SessionMode, SessionSnapshot, SessionStore,
};

/// Stable ids for our `SessionConfigOption` selectors. We expose both
/// dropdowns via configOptions because the ACP spec says clients SHOULD
/// ignore the legacy `modes` channel when configOptions is present (Zed
/// does), so once we expose any configOption we have to expose all of them.
const PERMISSION_CONFIG_ID: &str = "permission_mode";
const BEHAVIOR_CONFIG_ID: &str = "behavior_mode";
/// Mirrors the Java executor's wire id so cross-implementation clients
/// (Zed, brokk-code) can drive model selection through one canonical name.
const MODEL_CONFIG_ID: &str = "model_selection";
/// Per-session reasoning-effort knob, scoped to the Codex/ChatGPT backend.
/// Empty string in the wire payload clears the user's pick (back to the
/// model's `default_reasoning_level`).
const REASONING_EFFORT_CONFIG_ID: &str = "reasoning_effort";
/// Sentinel value the client sends to clear the user's pick. We accept
/// either an empty string or this token so editor implementations that
/// strip-trim selection ids still work.
const REASONING_EFFORT_DEFAULT_VALUE: &str = "(default)";

/// Available session modes exposed to ACP clients.
fn available_modes() -> Vec<AcpSessionMode> {
    vec![
        AcpSessionMode::new("LUTZ", "LUTZ").description("Agentic loop with task list"),
        AcpSessionMode::new("CODE", "CODE").description("Code changes only"),
        AcpSessionMode::new("ASK", "ASK").description("Question answering"),
        AcpSessionMode::new("PLAN", "PLAN").description("Planning only"),
    ]
}

fn mode_state(current: &str) -> SessionModeState {
    SessionModeState::new(current.to_string(), available_modes())
}

/// Build the permission-mode `SessionConfigOption` reflecting `current`.
fn permission_config_option(current: PermissionMode) -> SessionConfigOption {
    let options = vec![
        SessionConfigSelectOption::new("default", "Default")
            .description("Ask for permission before each tool call"),
        SessionConfigSelectOption::new("acceptEdits", "Accept Edits")
            .description("Auto-allow edits; ask for everything else"),
        SessionConfigSelectOption::new("readOnly", "Read-only")
            .description("Refuse every edit, deletion, move, or shell command"),
        SessionConfigSelectOption::new("bypassPermissions", "Bypass Permissions")
            .description("Allow all tool calls without prompting (use with care)"),
    ];
    SessionConfigOption::select(
        PERMISSION_CONFIG_ID,
        "Permission",
        current.as_str(),
        options,
    )
    .description("Controls which tool calls require user approval.")
    .category(SessionConfigOptionCategory::Mode)
}

/// Build the behavior-mode `SessionConfigOption` reflecting `current`. This
/// is the configOptions-channel counterpart to the legacy `SessionMode` menu
/// and drives system-prompt selection.
fn behavior_config_option(current: SessionMode) -> SessionConfigOption {
    let options = vec![
        SessionConfigSelectOption::new("LUTZ", "LUTZ").description("Agentic loop with task list"),
        SessionConfigSelectOption::new("CODE", "CODE").description("Code changes only"),
        SessionConfigSelectOption::new("ASK", "ASK").description("Question answering"),
        SessionConfigSelectOption::new("PLAN", "PLAN").description("Planning only"),
    ];
    SessionConfigOption::select(BEHAVIOR_CONFIG_ID, "Mode", current.as_str(), options)
        .description("Controls Brokk's overall behavior style for this session.")
        .category(SessionConfigOptionCategory::Mode)
}

/// Build the model `SessionConfigOption` reflecting `current` against the
/// cached `available_models` catalog. Returns `None` when the catalog is
/// empty, in which case the dropdown is omitted entirely (per ACP, a select
/// with zero options is not useful and some clients reject it).
fn model_config_option(current: &str, available_models: &[String]) -> Option<SessionConfigOption> {
    if available_models.is_empty() {
        return None;
    }
    // `SessionConfigSelectOption::new` stores its arguments owned, so the
    // closure must hand it owned Strings -- borrowing from `available_models`
    // would tie the option's lifetime to the slice and fail E0521.
    let options: Vec<SessionConfigSelectOption> = available_models
        .iter()
        .map(|m| SessionConfigSelectOption::new(m.clone(), m.clone()))
        .collect();
    // Fall back to the first catalog entry when `current` is empty or has
    // drifted out of the catalog -- otherwise some clients refuse to render
    // a select whose value is not in `options`.
    let current_value = if !current.is_empty() && available_models.iter().any(|m| m == current) {
        current.to_string()
    } else {
        available_models[0].clone()
    };
    Some(
        SessionConfigOption::select(MODEL_CONFIG_ID, "Model", current_value, options)
            .description("Selects the LLM model used for this session.")
            .category(SessionConfigOptionCategory::Model),
    )
}

/// Build the reasoning-effort `SessionConfigOption` for the active model.
/// Returns `None` when the model exposes no presets (e.g. an Ollama model
/// or a Codex slug whose `supported_reasoning_levels` is empty) -- the
/// dropdown is omitted entirely in that case rather than shown empty.
///
/// Layout: an explicit "(default)" entry at the head represents "no user
/// pick, server uses `default_reasoning_level`". The user's stored pick
/// (`current`) selects whichever option matches; when no pick exists, the
/// default entry is selected so the picker reflects actual intent.
fn reasoning_effort_config_option(
    current: Option<&str>,
    catalog: &[ModelMetadata],
    current_model: &str,
) -> Option<SessionConfigOption> {
    let model = catalog.iter().find(|m| m.id == current_model)?;
    if model.supported_reasoning_levels.is_empty() {
        return None;
    }
    let default_label = match &model.default_reasoning_level {
        Some(d) => format!("Default ({d})"),
        None => "Default".to_string(),
    };
    let mut options = vec![
        SessionConfigSelectOption::new(REASONING_EFFORT_DEFAULT_VALUE, default_label)
            .description("Use the model's default reasoning effort."),
    ];
    options.extend(model.supported_reasoning_levels.iter().map(|preset| {
        let opt = SessionConfigSelectOption::new(preset.effort.clone(), preset.effort.clone());
        if preset.description.is_empty() {
            opt
        } else {
            opt.description(preset.description.clone())
        }
    }));
    // Coerce out-of-catalog picks (e.g. stale from before a slug bump)
    // to the default sentinel so the picker always renders against an
    // entry it advertises.
    let current_value = match current {
        Some(eff)
            if model
                .supported_reasoning_levels
                .iter()
                .any(|p| p.effort == eff) =>
        {
            eff.to_string()
        }
        _ => REASONING_EFFORT_DEFAULT_VALUE.to_string(),
    };
    Some(
        SessionConfigOption::select(
            REASONING_EFFORT_CONFIG_ID,
            "Reasoning effort",
            current_value,
            options,
        )
        .description(
            "Controls how much chain-of-thought the model spends on each turn. \
             Higher levels are deeper but slower and cost more against your plan's quota.",
        )
        .category(SessionConfigOptionCategory::Model),
    )
}

/// All configOption selectors we expose, in display order. The model
/// selector is appended only when the LLM catalog is known; clients that
/// drive model selection through the meta extension still see the current
/// model via `meta.brokk.modelId`. The reasoning-effort selector is appended
/// only when the active model publishes presets (Codex/ChatGPT models do,
/// Ollama models don't).
fn all_config_options(
    behavior: SessionMode,
    permission: PermissionMode,
    current_model: &str,
    available_models: &[ModelMetadata],
    current_reasoning_effort: Option<&str>,
) -> Vec<SessionConfigOption> {
    let model_ids: Vec<String> = available_models.iter().map(|m| m.id.clone()).collect();
    let mut opts = vec![
        behavior_config_option(behavior),
        permission_config_option(permission),
    ];
    if let Some(model_opt) = model_config_option(current_model, &model_ids) {
        opts.push(model_opt);
    }
    if let Some(re_opt) =
        reasoning_effort_config_option(current_reasoning_effort, available_models, current_model)
    {
        opts.push(re_opt);
    }
    opts
}

/// Wire ids accepted by `apply_config_option`. Kept in a single slice so
/// every caller (the `setSessionConfigOption` request handler and the
/// `/configure` slash command) reports identical "supported keys" lists.
const CONFIGURE_KNOWN_KEYS: &[&str] = &[
    BEHAVIOR_CONFIG_ID,
    PERMISSION_CONFIG_ID,
    MODEL_CONFIG_ID,
    REASONING_EFFORT_CONFIG_ID,
];

/// Outcome of a successful `apply_config_option` call. Carries the full
/// re-derived option list so the caller can re-emit a `ConfigOptionUpdate`
/// notification with the spec-required complete state.
#[derive(Debug)]
struct ConfigApplyOutcome {
    updated_options: Vec<SessionConfigOption>,
    /// Set only by the `model` arm when the previous reasoning_effort pick
    /// is not in the new model's supported set and the store dropped it.
    /// Both callers surface this to the user.
    cleared_reasoning: Option<String>,
}

/// Validation / dispatch errors from `apply_config_option`. The request
/// handler maps these into JSON error data; the slash command formats them
/// into a one-line user message via `human_message`.
#[derive(Debug)]
enum ConfigApplyError {
    UnknownConfigId,
    InvalidValue {
        reason: String,
        supported: Vec<String>,
    },
    UnknownSession,
    PersistFailed {
        details: String,
    },
}

impl ConfigApplyError {
    fn human_message(&self) -> String {
        match self {
            ConfigApplyError::UnknownConfigId => format!(
                "unknown config key. Supported: {}",
                CONFIGURE_KNOWN_KEYS.join(", ")
            ),
            ConfigApplyError::InvalidValue { reason, supported } => {
                if supported.is_empty() {
                    reason.clone()
                } else {
                    format!("{reason}. Supported: {}", supported.join(", "))
                }
            }
            ConfigApplyError::UnknownSession => "unknown session".to_string(),
            ConfigApplyError::PersistFailed { details } => {
                format!("failed to persist setting: {details}")
            }
        }
    }
}

/// Apply a single `configOptions` change. Single source of truth shared by
/// the `setSessionConfigOption` ACP request and the `/configure` slash
/// command: validates the value, mutates session state, and returns the
/// full re-derived options list so the caller can emit a
/// `ConfigOptionUpdate` notification with the spec-required complete state.
async fn apply_config_option(
    sessions: &SessionStore,
    session_id: &str,
    config_id: &str,
    value: &str,
) -> Result<ConfigApplyOutcome, ConfigApplyError> {
    let mut cleared_reasoning: Option<String> = None;

    match config_id {
        PERMISSION_CONFIG_ID => {
            let Some(permission_mode) = PermissionMode::parse(value) else {
                return Err(ConfigApplyError::InvalidValue {
                    reason: format!("unknown permission mode '{value}'"),
                    supported: vec![
                        "default".to_string(),
                        "acceptEdits".to_string(),
                        "readOnly".to_string(),
                        "bypassPermissions".to_string(),
                    ],
                });
            };
            if !sessions
                .set_permission_mode(session_id, permission_mode)
                .await
            {
                return Err(ConfigApplyError::UnknownSession);
            }
        }
        BEHAVIOR_CONFIG_ID => {
            let Some(behavior_mode) = SessionMode::parse(value) else {
                return Err(ConfigApplyError::InvalidValue {
                    reason: format!("unknown behavior mode '{value}'"),
                    supported: vec![
                        "LUTZ".to_string(),
                        "CODE".to_string(),
                        "ASK".to_string(),
                        "PLAN".to_string(),
                    ],
                });
            };
            match sessions.set_mode(session_id, behavior_mode).await {
                Ok(true) => {}
                Ok(false) => return Err(ConfigApplyError::UnknownSession),
                Err(e) => {
                    return Err(ConfigApplyError::PersistFailed {
                        details: format!("{e:#}"),
                    });
                }
            }
        }
        MODEL_CONFIG_ID => {
            if value.is_empty() {
                return Err(ConfigApplyError::InvalidValue {
                    reason: "model id must be a non-empty string".to_string(),
                    supported: Vec::new(),
                });
            }
            // Reject ids that drift out of the catalog when one is known.
            // An empty catalog means model discovery never succeeded;
            // accept anything in that case so the user can still drive
            // the agent against a manually-configured backend.
            let known = sessions.available_models().await;
            if !known.is_empty() && !known.iter().any(|m| m == value) {
                return Err(ConfigApplyError::InvalidValue {
                    reason: format!("unknown model '{value}'"),
                    supported: known,
                });
            }
            let (ok, cleared) = sessions.set_model(session_id, value.to_string()).await;
            if !ok {
                return Err(ConfigApplyError::UnknownSession);
            }
            cleared_reasoning = cleared;
        }
        REASONING_EFFORT_CONFIG_ID => {
            // Empty string or the "(default)" sentinel both mean "clear my
            // pick, use the model default".
            let effort = if value.is_empty() || value == REASONING_EFFORT_DEFAULT_VALUE {
                None
            } else {
                Some(value.to_string())
            };
            // Validate against the active model's published levels when
            // one is known. An unknown catalog (e.g. discovery never
            // finished) accepts any string so a manually-configured
            // backend still works.
            if let Some(eff) = &effort {
                let fallback_cwd = std::env::current_dir().unwrap_or_default();
                let active_model = sessions
                    .get_session(session_id, &fallback_cwd)
                    .await
                    .map(|s| s.model);
                let catalog = sessions.available_model_metadata().await;
                if let Some(model_id) = active_model
                    && let Some(meta) = catalog.iter().find(|m| m.id == model_id)
                {
                    if meta.supported_reasoning_levels.is_empty() {
                        return Err(ConfigApplyError::InvalidValue {
                            reason: format!(
                                "model '{model_id}' does not support configurable reasoning effort"
                            ),
                            supported: Vec::new(),
                        });
                    }
                    if !meta
                        .supported_reasoning_levels
                        .iter()
                        .any(|p| &p.effort == eff)
                    {
                        let supported: Vec<String> = meta
                            .supported_reasoning_levels
                            .iter()
                            .map(|p| p.effort.clone())
                            .collect();
                        return Err(ConfigApplyError::InvalidValue {
                            reason: format!(
                                "reasoning effort '{eff}' is not supported by model '{model_id}'"
                            ),
                            supported,
                        });
                    }
                }
            }
            if !sessions.set_reasoning_effort(session_id, effort).await {
                return Err(ConfigApplyError::UnknownSession);
            }
        }
        _ => return Err(ConfigApplyError::UnknownConfigId),
    }

    // Re-fetch the session so the returned options reflect the latest
    // values for *all* selectors. The spec says the response carries the
    // full updated set, not just the one we changed.
    let fallback_cwd = std::env::current_dir().unwrap_or_default();
    let Some(session) = sessions.get_session(session_id, &fallback_cwd).await else {
        return Err(ConfigApplyError::UnknownSession);
    };
    let catalog = sessions.available_model_metadata().await;
    let updated_options = all_config_options(
        session.mode,
        session.permission_mode,
        &session.model,
        &catalog,
        session.selected_reasoning_effort.as_deref(),
    );

    Ok(ConfigApplyOutcome {
        updated_options,
        cleared_reasoning,
    })
}

/// Slash commands advertised to clients via `available_commands_update`.
/// Mirrors the Java executor's `/context` command (other Java commands are
/// intentionally omitted -- they depend on the live workspace context that
/// the Rust agent does not yet model). `/codex-login` is published so it
/// shows up in editor autocomplete (Zed, JetBrains ACP) -- without this
/// the slash command works when typed but is invisible to discovery.
fn builtin_commands() -> Vec<AvailableCommand> {
    let mut commands = vec![
        AvailableCommand::new("context", "Show current session context snapshot"),
        AvailableCommand::new(
            "codex-login",
            "Sign in with ChatGPT (or `status` / `disconnect`)",
        ),
    ];
    // `/openrouter-login` is advertised only when the env var does not
    // own the credential lifecycle. When `OPENROUTER_API_KEY` is set
    // in the process environment, the key cannot be mutated from a
    // session (the server reads env once at startup and a `disconnect`
    // can't undo that), so showing the slash would be misleading. The
    // slash is still kept in `builtin_command_names()` unconditionally
    // so a skill can't claim the name -- typing it manually dispatches
    // to the handler, which then explains why the command is disabled.
    // `/configure` exposes the credential status either way so users
    // can still see where the active key comes from.
    if !crate::openrouter_auth::CredentialState::snapshot().env_owns() {
        commands.push(AvailableCommand::new(
            "openrouter-login",
            "Save an OpenRouter API key (or `status` / `disconnect`)",
        ));
    }
    commands.push(AvailableCommand::new(
        "idle-timeout",
        "Show or set the LLM SSE idle timeout for this session (e.g. `/idle-timeout 600`)",
    ));
    commands.push(AvailableCommand::new(
        "configure",
        "Show or change session settings (e.g. `/configure model_selection gpt-5`)",
    ));
    commands.push(AvailableCommand::new(
        "sandbox",
        "Show the active runShellCommand sandbox backend, WASI module registry, and bwrap probe result (read-only)",
    ));
    commands
}

/// Set of built-in slash command names, used to detect collisions with
/// skill names so the built-in always wins (matches the spec's "Hide
/// filtered skills entirely" guidance: don't expose a slash that won't
/// actually dispatch to the skill).
fn builtin_command_names() -> std::collections::HashSet<&'static str> {
    [
        "context",
        "codex-login",
        "openrouter-login",
        "idle-timeout",
        "configure",
        "sandbox",
    ]
    .into_iter()
    .collect()
}

/// Build the full command list advertised to the client: built-ins plus
/// one entry per discovered skill. Skill commands whose names collide
/// with a built-in are dropped (with a warning) so the user doesn't see
/// ambiguous autocomplete -- the skill remains reachable to the model
/// via the `activate_skill` tool.
fn available_commands(registry: &crate::skills::SkillRegistry) -> Vec<AvailableCommand> {
    let mut commands = builtin_commands();
    if registry.is_empty() {
        return commands;
    }
    let builtins = builtin_command_names();
    for meta in registry.iter_sorted() {
        if builtins.contains(meta.name.as_str()) {
            tracing::warn!(
                skill = %meta.name,
                location = %meta.location.display(),
                "skill name collides with a built-in slash command; hiding from autocomplete"
            );
            continue;
        }
        commands.push(AvailableCommand::new(
            meta.name.clone(),
            shorten_for_autocomplete(&meta.description),
        ));
    }
    commands
}

/// Editor autocomplete widgets render the command description inline,
/// so wrap long descriptions to keep the dropdown legible. The spec
/// caps descriptions at 1024 chars; ~200 chars is plenty for a tooltip.
fn shorten_for_autocomplete(s: &str) -> String {
    const MAX: usize = 200;
    let trimmed = s.trim();
    if trimmed.chars().count() <= MAX {
        return trimmed.to_string();
    }
    let mut acc = String::with_capacity(MAX + 3);
    for (i, ch) in trimmed.chars().enumerate() {
        if i >= MAX - 1 {
            break;
        }
        acc.push(ch);
    }
    acc.push('…');
    acc
}

fn send_available_commands_update(
    cx: &ConnectionTo<Client>,
    session_id: &str,
    registry: &crate::skills::SkillRegistry,
) {
    let update = SessionUpdate::AvailableCommandsUpdate(AvailableCommandsUpdate::new(
        available_commands(registry),
    ));
    let notification = SessionNotification::new(session_id.to_string(), update);
    if let Err(e) = cx.send_notification(notification) {
        tracing::warn!("failed to send available_commands_update: {e}");
    }
}

/// Spawn a background discovery refresh that updates the session
/// store's cached model catalog. Throttled by `refresh_lock`: if a
/// previous refresh is still in flight the call is a no-op (the
/// in-flight one will seed the cache anyway). Shared by `session/new`
/// and the `/codex-login` post-install path so the two can never race.
fn spawn_throttled_refresh(
    refresh_lock: Arc<tokio::sync::Mutex<()>>,
    llm: Arc<MultiBackend>,
    sessions: SessionStore,
) {
    if let Ok(guard) = refresh_lock.try_lock_owned() {
        tokio::spawn(async move {
            // Hold the guard for the duration of the refresh so the
            // next try_lock observes "in flight". Drop is implicit at
            // the end of the spawned future.
            let _refresh_guard = guard;
            match llm.list_model_metadata().await {
                Ok(models) => {
                    tracing::debug!(
                        count = models.len(),
                        "background model-catalog refresh complete"
                    );
                    sessions.set_available_models(models).await;
                }
                Err(e) => {
                    tracing::debug!("background model-catalog refresh failed: {e:#}");
                }
            }
        });
    } else {
        tracing::trace!(
            "skipping background model-catalog refresh: another refresh is already in flight"
        );
    }
}

/// Build and run the ACP agent over stdio.
pub async fn run_agent(
    llm: Arc<MultiBackend>,
    sessions: SessionStore,
    max_turns: usize,
    default_idle_timeout_secs: u64,
    bifrost_binary: Option<PathBuf>,
) -> agent_client_protocol::Result<()> {
    let llm_init = llm.clone();
    let sessions_init = sessions.clone();

    let llm_new = llm.clone();
    let sessions_new = sessions.clone();
    // Throttle background discovery refreshes so a burst of session/new
    // calls (e.g. an editor reconnecting and re-creating sessions) doesn't
    // pile up redundant probes against /api/tags and /codex/models. We
    // hold this owned Mutex via try_lock_owned: when a refresh is already
    // in flight, the next try_lock returns None and we skip the spawn.
    //
    // The same lock is shared with the `/codex-login` post-install
    // refresh below so an immediate session/new after login doesn't race
    // a second probe through the discovery path.
    let refresh_lock = Arc::new(tokio::sync::Mutex::new(()));
    let refresh_lock_new = refresh_lock.clone();
    let refresh_lock_login = refresh_lock.clone();

    let sessions_load = sessions.clone();
    let sessions_resume = sessions.clone();
    let sessions_list = sessions.clone();

    let llm_prompt = llm.clone();
    let llm_login = llm.clone();
    let sessions_prompt = sessions.clone();
    let sessions_login = sessions.clone();
    let bifrost_binary_prompt = bifrost_binary.clone();

    let sessions_cancel = sessions.clone();
    let sessions_mode = sessions.clone();
    let sessions_perm = sessions.clone();

    Agent
        .builder()
        .name("brokk-acp-rust")
        // Handle initialize
        .on_receive_request(
            async move |req: InitializeRequest,
                        responder: Responder<InitializeResponse>,
                        _cx: ConnectionTo<Client>| {
                tracing::info!("ACP initialize");

                // Try to discover models at startup and cache them for session/new.
                let models = match llm_init.list_model_metadata().await {
                    Ok(m) => m,
                    Err(e) => {
                        tracing::warn!("model discovery failed during init: {e}");
                        vec![]
                    }
                };
                if let Some(first) = models.first() {
                    sessions_init.set_default_model(first.id.clone()).await;
                }
                sessions_init.set_available_models(models).await;

                let capabilities = AgentCapabilities::new()
                    .load_session(true)
                    .prompt_capabilities(PromptCapabilities::new().embedded_context(true))
                    .session_capabilities(
                        SessionCapabilities::new()
                            .list(SessionListCapabilities::new())
                            .resume(SessionResumeCapabilities::new()),
                    );

                responder.respond(
                    InitializeResponse::new(req.protocol_version)
                        .agent_capabilities(capabilities),
                )
            },
            on_receive_request!(),
        )
        // Handle session/new
        .on_receive_request(
            async move |req: NewSessionRequest,
                        responder: Responder<NewSessionResponse>,
                        cx: ConnectionTo<Client>| {
                let cwd = req.cwd.clone();
                tracing::info!("ACP session/new, cwd={}", cwd.display());
                let session = sessions_new.create_session(cwd).await;

                // Re-discover in the background so the next `session/new` picks up
                // models the user added/removed since startup (e.g. they ran
                // `ollama pull` or signed into Codex). The current session/new
                // returns immediately with the cached list -- the refresh seeds
                // the cache for the next call so we don't pay the discovery RTT
                // here on the synchronous path.
                spawn_throttled_refresh(
                    refresh_lock_new.clone(),
                    llm_new.clone(),
                    sessions_new.clone(),
                );

                // Use the cached catalog populated at init; fall back to a
                // single-entry catalog from the session's own model so the
                // dropdown still renders something on a fresh discovery miss.
                let mut catalog = sessions_new.available_model_metadata().await;
                if catalog.is_empty() && !session.model.is_empty() {
                    catalog = vec![ModelMetadata::id_only(&session.model)];
                }
                let model_ids: Vec<String> = catalog.iter().map(|m| m.id.clone()).collect();

                let meta_value = serde_json::json!({
                    "brokk": {
                        "modelId": session.model,
                        "availableModels": model_ids,
                    }
                });
                let meta_map = match meta_value {
                    serde_json::Value::Object(m) => m,
                    _ => serde_json::Map::new(),
                };

                let response = NewSessionResponse::new(session.id.clone())
                    .modes(mode_state(session.mode.as_str()))
                    .config_options(all_config_options(
                        session.mode,
                        session.permission_mode,
                        &session.model,
                        &catalog,
                        session.selected_reasoning_effort.as_deref(),
                    ))
                    .meta(meta_map);

                // Respond first so the client registers the session ID before
                // notifications referencing it; ACP uses a single FIFO outbound
                // channel, so enqueue order is wire order. Notifying first made
                // Zed drop AvailableCommandsUpdate as "unknown session".
                let result = responder.respond(response);
                send_available_commands_update(&cx, &session.id, &session.skills);
                result
            },
            on_receive_request!(),
        )
        // Handle session/load
        .on_receive_request(
            async move |req: LoadSessionRequest,
                        responder: Responder<LoadSessionResponse>,
                        cx: ConnectionTo<Client>| {
                let session_id = req.session_id.to_string();
                let cwd = req.cwd.clone();
                tracing::info!("ACP session/load session={session_id}, cwd={}", cwd.display());

                // Look up the session from memory or disk
                let session = match sessions_load.get_session(&session_id, &cwd).await {
                    Some(s) => {
                        sessions_load.update_cwd(&session_id, cwd).await;
                        s
                    }
                    None => {
                        tracing::warn!("session/load: unknown session {session_id}");
                        send_message(&cx, &session_id, "Error: unknown session");
                        return responder.respond(LoadSessionResponse::new());
                    }
                };

                // Replay conversation history as session updates (both sides).
                for turn in &session.history {
                    if !turn.user_prompt.is_empty() {
                        send_user_message(&cx, &session_id, &turn.user_prompt);
                    }
                    if !turn.agent_response.is_empty() {
                        send_message(&cx, &session_id, &turn.agent_response);
                    }
                }

                let catalog = sessions_load.available_model_metadata().await;
                let result = responder.respond(
                    LoadSessionResponse::new()
                        .modes(mode_state(session.mode.as_str()))
                        .config_options(all_config_options(
                            session.mode,
                            session.permission_mode,
                            &session.model,
                            &catalog,
                            session.selected_reasoning_effort.as_deref(),
                        )),
                );
                send_available_commands_update(&cx, &session_id, &session.skills);
                result
            },
            on_receive_request!(),
        )
        // Handle session/resume
        .on_receive_request(
            async move |req: ResumeSessionRequest,
                        responder: Responder<ResumeSessionResponse>,
                        cx: ConnectionTo<Client>| {
                let session_id = req.session_id.to_string();
                let cwd = req.cwd.clone();
                tracing::info!("ACP session/resume session={session_id}, cwd={}", cwd.display());

                match sessions_resume.get_session(&session_id, &cwd).await {
                    Some(session) => {
                        sessions_resume.update_cwd(&session_id, cwd).await;
                        let catalog = sessions_resume.available_model_metadata().await;
                        let result = responder.respond(
                            ResumeSessionResponse::new()
                                .modes(mode_state(session.mode.as_str()))
                                .config_options(all_config_options(
                                    session.mode,
                                    session.permission_mode,
                                    &session.model,
                                    &catalog,
                                    session.selected_reasoning_effort.as_deref(),
                                )),
                        );
                        send_available_commands_update(&cx, &session_id, &session.skills);
                        result
                    }
                    None => {
                        tracing::warn!("session/resume: unknown session {session_id}");
                        send_message(&cx, &session_id, "Error: unknown session");
                        responder.respond(ResumeSessionResponse::new())
                    }
                }
            },
            on_receive_request!(),
        )
        // Handle session/list
        .on_receive_request(
            async move |req: ListSessionsRequest,
                        responder: Responder<ListSessionsResponse>,
                        _cx: ConnectionTo<Client>| {
                tracing::info!("ACP session/list, cwd filter={:?}", req.cwd);

                let infos: Vec<SessionInfo> = if let Some(cwd) = &req.cwd {
                    sessions_list
                        .list_sessions_from_disk(cwd)
                        .await
                        .into_iter()
                        .map(|m| SessionInfo::new(m.id, cwd.clone()))
                        .collect()
                } else {
                    vec![]
                };

                responder.respond(ListSessionsResponse::new(infos))
            },
            on_receive_request!(),
        )
        // Handle session/prompt
        .on_receive_request(
            async move |req: PromptRequest,
                        responder: Responder<PromptResponse>,
                        cx: ConnectionTo<Client>| {
                let session_id = req.session_id.to_string();
                tracing::info!("ACP session/prompt session={session_id}");

                // Extract prompt text from content blocks
                let prompt_text = extract_prompt_text(&req.prompt);
                if prompt_text.is_empty() {
                    send_message(&cx, &session_id, "Error: empty prompt");
                    return responder.respond(PromptResponse::new(StopReason::EndTurn));
                }

                // Get session state (prompt doesn't carry cwd, so use current dir as fallback).
                // The snapshot clones the conversation history exactly once under the
                // read lock; we then consume it via `.into_iter()` to build ChatMessages
                // without further string copies.
                let fallback_cwd = std::env::current_dir().unwrap_or_default();
                let snap = match sessions_prompt
                    .snapshot(&session_id, &fallback_cwd)
                    .await
                {
                    Some(s) => s,
                    None => {
                        send_message(&cx, &session_id, "Error: unknown session");
                        return responder.respond(PromptResponse::new(StopReason::EndTurn));
                    }
                };

                // Slash commands run locally and short-circuit the LLM round-trip.
                // They are not persisted as conversation turns -- the response is
                // purely informational and replaying it on the next session load
                // would mislead the model about prior dialog. Mirrors the Java
                // executor's `handleSlashCommand` path.
                if is_slash_command(&prompt_text, "context") {
                    let permission_mode = sessions_prompt
                        .permission_mode(&session_id)
                        .await
                        .unwrap_or(PermissionMode::Default);
                    let available_models = sessions_prompt.available_models().await;
                    let report = render_context_report(&snap, permission_mode, &available_models);
                    send_message(&cx, &session_id, &report);
                    return responder.respond(PromptResponse::new(StopReason::EndTurn));
                }

                if is_slash_command(&prompt_text, "codex-login") {
                    let report = handle_codex_login(
                        &prompt_text,
                        &llm_login,
                        &sessions_login,
                        &refresh_lock_login,
                    )
                    .await;
                    send_message(&cx, &session_id, &report);
                    return responder.respond(PromptResponse::new(StopReason::EndTurn));
                }

                if is_slash_command(&prompt_text, "openrouter-login") {
                    let report = handle_openrouter_login(
                        &prompt_text,
                        &llm_login,
                        &sessions_login,
                        &refresh_lock_login,
                    )
                    .await;
                    send_message(&cx, &session_id, &report);
                    return responder.respond(PromptResponse::new(StopReason::EndTurn));
                }

                if is_slash_command(&prompt_text, "idle-timeout") {
                    let report = handle_idle_timeout(
                        &prompt_text,
                        &session_id,
                        &sessions_prompt,
                        snap.idle_timeout_secs,
                        default_idle_timeout_secs,
                    )
                    .await;
                    send_message(&cx, &session_id, &report);
                    return responder.respond(PromptResponse::new(StopReason::EndTurn));
                }

                if is_slash_command(&prompt_text, "configure") {
                    let report =
                        handle_configure(&cx, &prompt_text, &session_id, &sessions_prompt).await;
                    send_message(&cx, &session_id, &report);
                    return responder.respond(PromptResponse::new(StopReason::EndTurn));
                }

                if is_slash_command(&prompt_text, "sandbox") {
                    // Read-only status dump. The sandbox backend cannot
                    // be changed from a slash command on purpose: allowing
                    // an LLM-driven prompt to weaken its own sandbox would
                    // be a privilege-escalation footgun. Operators change
                    // the backend at startup via `--sandbox-backend` /
                    // `BROKK_ACP_SANDBOX_BACKEND`.
                    let report = crate::tools::sandbox::describe_status();
                    send_message(&cx, &session_id, &report);
                    return responder.respond(PromptResponse::new(StopReason::EndTurn));
                }

                // User-explicit skill activation. Unlike the built-in
                // short-circuit commands above, a skill slash IS the LLM
                // round-trip: the SKILL.md body becomes the user's
                // message for this turn (with any args after the command
                // appended), so it persists into history and replays
                // correctly. Built-ins are checked first so a skill
                // that happens to name itself e.g. `context` or
                // `idle-timeout` can never shadow them.
                let prompt_text = if let Some((name, args)) = parse_slash_command(&prompt_text)
                    && let Some(meta) = snap.skills.get(&name)
                {
                    tracing::info!(skill = %name, "slash-command activating skill");
                    sessions_prompt
                        .mark_skill_activated(&session_id, &name)
                        .await;
                    let body = build_skill_payload(meta);
                    if args.is_empty() {
                        body
                    } else {
                        format!("{body}\n\nUser input: {args}")
                    }
                } else {
                    prompt_text
                };

                // Validate model is configured
                if snap.model.is_empty() {
                    send_message(&cx, &session_id, "Error: no model configured. Start the server with --default-model or ensure the LLM endpoint is reachable for model discovery.");
                    return responder.respond(PromptResponse::new(StopReason::EndTurn));
                }

                let messages = build_prompt_messages(&snap, &prompt_text);

                // Create a cancellation token for this prompt
                let cancel = sessions_prompt.start_prompt(&session_id).await;

                // Build the tool registry up-front so we don't pay for it inside the spawn.
                let registry = sessions_prompt
                    .get_or_create_registry(
                        &session_id,
                        snap.cwd,
                        bifrost_binary_prompt.as_deref(),
                    )
                    .await;

                // Capture everything the spawned task needs before we move into it.
                // The tool loop calls `block_task()` to await `session/request_permission`,
                // which is only safe when run inside `cx.spawn` (per the ACP SDK docs --
                // calling it directly from a request handler can deadlock the dispatch loop).
                //
                // The tool loop only needs the trait, so coerce the
                // concrete `Arc<MultiBackend>` here -- keeping the
                // multi-backend specific surface (e.g. `install_codex`)
                // out of the generic chat path.
                let llm_for_loop: Arc<dyn crate::llm_client::LlmBackend> = llm_prompt.clone();
                let sessions_for_loop = sessions_prompt.clone();
                let cx_for_loop = cx.clone();
                let session_id_for_loop = session_id.clone();
                let prompt_text_for_turn = prompt_text;
                let model_for_loop = snap.model;
                let reasoning_effort_for_loop = snap.reasoning_effort;
                // Resolve per-turn idle timeout: the session override wins,
                // otherwise fall back to the binary-wide default from
                // `--llm-idle-timeout-secs` / `BROKK_ACP_LLM_IDLE_TIMEOUT_SECS`.
                let idle_timeout_for_loop = Duration::from_secs(
                    snap.idle_timeout_secs
                        .unwrap_or(default_idle_timeout_secs)
                        .max(1),
                );

                cx.spawn(async move {
                    use futures::FutureExt;
                    use std::panic::AssertUnwindSafe;

                    let cx_text = cx_for_loop.clone();
                    let sid_text = session_id_for_loop.clone();
                    let cx_thought = cx_for_loop.clone();
                    let sid_thought = session_id_for_loop.clone();

                    // Text tokens stream to the client in real time via this shared sink.
                    let text_sink: crate::tool_loop::TextSink = std::sync::Arc::new(
                        std::sync::Mutex::new(move |token: &str| {
                            send_message(&cx_text, &sid_text, token);
                        }),
                    );
                    // Reasoning deltas stream into the dedicated ACP
                    // thought channel so the client can render them as
                    // a collapsible block separate from the final answer.
                    let thought_sink: crate::tool_loop::TextSink = std::sync::Arc::new(
                        std::sync::Mutex::new(move |token: &str| {
                            send_thought(&cx_thought, &sid_thought, token);
                        }),
                    );

                    // Catch panics so cleanup (finish_prompt, respond) always runs.
                    // Without this, a panic inside the tool loop would leak the cancel
                    // token in `cancel_tokens` and leave the dispatcher waiting on a
                    // PromptResponse that never arrives.
                    //
                    // SpawnedCx is constructed here, inside the cx.spawn body -- the
                    // tool loop's `block_task` calls require this proof of context.
                    let cx_for_gate = cx_for_loop.clone();
                    let spawned_cx = crate::tool_loop::SpawnedCx::new(&cx_for_gate);
                    let loop_result = AssertUnwindSafe(crate::tool_loop::run(
                        &llm_for_loop,
                        &registry,
                        &model_for_loop,
                        reasoning_effort_for_loop.as_deref(),
                        messages,
                        max_turns,
                        idle_timeout_for_loop,
                        cancel,
                        text_sink,
                        thought_sink,
                        spawned_cx,
                        session_id_for_loop.clone(),
                        sessions_for_loop.clone(),
                    ))
                    .catch_unwind()
                    .await;

                    let (response_text, tool_exchanges) = match loop_result {
                        Ok((text, exchanges)) => (text, exchanges),
                        Err(panic) => {
                            tracing::error!(
                                session_id = %session_id_for_loop,
                                "tool loop panicked: {:?}",
                                panic
                            );
                            (
                                "Error: agent loop panicked. See server logs.".to_string(),
                                Vec::new(),
                            )
                        }
                    };

                    // Persist the conversation turn BEFORE finish_prompt so the
                    // per-session cancel token is held during the rewrite -- this
                    // is the locking that makes `add_turn`'s rollback safe (see
                    // the concurrency note on `SessionStore::add_turn`). On
                    // failure, surface the error to the client so the user
                    // knows their last turn isn't on disk.
                    //
                    // Tool exchanges are persisted alongside the turn so a
                    // session/load can re-feed the LLM the same tool context
                    // it had when it produced response_text (#3409).
                    let persist_result = sessions_for_loop
                        .add_turn(
                            &session_id_for_loop,
                            ConversationTurn {
                                user_prompt: prompt_text_for_turn,
                                agent_response: response_text,
                                tool_exchanges,
                            },
                        )
                        .await;

                    if let Err(e) = persist_result {
                        send_message(
                            &cx_for_loop,
                            &session_id_for_loop,
                            &format!(
                                "\n**Warning:** failed to save this conversation turn to disk; \
                                 it will not survive a session reload: {e}\n"
                            ),
                        );
                    }

                    // Clean up cancellation token even on panic / persistence failure.
                    sessions_for_loop.finish_prompt(&session_id_for_loop).await;

                    if let Err(e) = responder.respond(PromptResponse::new(StopReason::EndTurn)) {
                        tracing::warn!(
                            session_id = %session_id_for_loop,
                            "failed to deliver PromptResponse: {e}"
                        );
                    }
                    Ok(())
                })?;

                Ok(())
            },
            on_receive_request!(),
        )
        // Handle session/cancel
        .on_receive_notification(
            async move |notification: CancelNotification, _cx: ConnectionTo<Client>| -> agent_client_protocol::Result<()> {
                let session_id = notification.session_id.to_string();
                tracing::info!("ACP cancel session={session_id}");
                sessions_cancel.cancel_prompt(&session_id).await;
                Ok(())
            },
            on_receive_notification!(),
        )
        // Handle session/set_mode
        .on_receive_request(
            async move |req: SetSessionModeRequest,
                        responder: Responder<SetSessionModeResponse>,
                        _cx: ConnectionTo<Client>| {
                let session_id = req.session_id.to_string();
                let mode_id = req.mode_id.to_string();
                tracing::info!("ACP set_mode session={session_id} mode={mode_id}");

                let Some(mode) = SessionMode::parse(&mode_id) else {
                    return responder.respond_with_error(
                        agent_client_protocol::Error::invalid_params()
                            .data(serde_json::json!({
                                "reason": format!("unknown mode '{mode_id}'"),
                                "supported": available_modes()
                                    .iter()
                                    .map(|m| m.id.to_string())
                                    .collect::<Vec<_>>(),
                            })),
                    );
                };

                match sessions_mode.set_mode(&session_id, mode).await {
                    Ok(true) => responder.respond(SetSessionModeResponse::new()),
                    Ok(false) => responder.respond_with_error(
                        agent_client_protocol::Error::invalid_params().data(
                            serde_json::json!({
                                "reason": format!("unknown session '{session_id}'"),
                            }),
                        ),
                    ),
                    Err(e) => responder.respond_with_error(
                        agent_client_protocol::Error::internal_error().data(serde_json::json!({
                            "reason": "failed to persist session mode",
                            "details": format!("{e:#}"),
                        })),
                    ),
                }
            },
            on_receive_request!(),
        )
        // Handle session/set_config_option
        .on_receive_request(
            async move |req: SetSessionConfigOptionRequest,
                        responder: Responder<SetSessionConfigOptionResponse>,
                        cx: ConnectionTo<Client>| {
                let session_id = req.session_id.to_string();
                let config_id = req.config_id.to_string();
                let value = req.value.to_string();
                tracing::info!(
                    "ACP set_config_option session={session_id} config={config_id} value={value}"
                );

                let outcome = match apply_config_option(
                    &sessions_perm,
                    &session_id,
                    &config_id,
                    &value,
                )
                .await
                {
                    Ok(out) => out,
                    Err(ConfigApplyError::UnknownConfigId) => {
                        return responder.respond_with_error(
                            agent_client_protocol::Error::invalid_params().data(
                                serde_json::json!({
                                    "reason": format!("unknown configOption '{config_id}'"),
                                    "supported": CONFIGURE_KNOWN_KEYS,
                                }),
                            ),
                        );
                    }
                    Err(ConfigApplyError::InvalidValue { reason, supported }) => {
                        return responder.respond_with_error(
                            agent_client_protocol::Error::invalid_params().data(
                                serde_json::json!({
                                    "reason": reason,
                                    "supported": supported,
                                }),
                            ),
                        );
                    }
                    Err(ConfigApplyError::UnknownSession) => {
                        return responder.respond_with_error(
                            agent_client_protocol::Error::invalid_params().data(
                                serde_json::json!({
                                    "reason": format!("unknown session '{session_id}'"),
                                }),
                            ),
                        );
                    }
                    Err(ConfigApplyError::PersistFailed { details }) => {
                        return responder.respond_with_error(
                            agent_client_protocol::Error::internal_error().data(
                                serde_json::json!({
                                    "reason": "failed to persist session mode",
                                    "details": details,
                                }),
                            ),
                        );
                    }
                };

                // Auto-fallback notice: when changing the model dropped a
                // now-unsupported reasoning_effort pick, surface a
                // one-line system note so the silent change isn't
                // mysterious next time the user wonders why thoughts
                // shortened.
                if let Some(prev) = &outcome.cleared_reasoning {
                    send_message(
                        &cx,
                        &session_id,
                        &format!(
                            "Reasoning effort reset: `{prev}` is not supported by `{value}`. \
                             Using model default until you pick a level."
                        ),
                    );
                }

                let notification = SessionNotification::new(
                    session_id.clone(),
                    SessionUpdate::ConfigOptionUpdate(ConfigOptionUpdate::new(
                        outcome.updated_options.clone(),
                    )),
                );
                if let Err(e) = cx.send_notification(notification) {
                    tracing::warn!("failed to send config_option_update: {e}");
                }

                responder.respond(SetSessionConfigOptionResponse::new(outcome.updated_options))
            },
            on_receive_request!(),
        )
        // Fallback: return unhandled for unknown messages
        .on_receive_dispatch(
            async move |message: Dispatch, _cx: ConnectionTo<Client>| {
                tracing::debug!("unhandled dispatch: {}", message.method());
                Ok(Handled::No {
                    message,
                    retry: false,
                })
            },
            on_receive_dispatch!(),
        )
        .connect_to(ByteStreams::new(
            tokio::io::stdout().compat_write(),
            tokio::io::stdin().compat(),
        ))
        .await
}

/// Extract text content from ACP content blocks.
fn extract_prompt_text(blocks: &[ContentBlock]) -> String {
    blocks
        .iter()
        .filter_map(|block| match block {
            ContentBlock::Text(t) => Some(t.text.as_str()),
            _ => None,
        })
        .collect::<Vec<_>>()
        .join("\n")
}

/// Send an agent_message_chunk session update to the client.
fn send_message(cx: &ConnectionTo<Client>, session_id: &str, text: &str) {
    let chunk = ContentChunk::new(ContentBlock::Text(TextContent::new(text)));
    let update = SessionUpdate::AgentMessageChunk(chunk);
    let notification = SessionNotification::new(session_id.to_string(), update);
    if let Err(e) = cx.send_notification(notification) {
        tracing::warn!("failed to send session update: {e}");
    }
}

/// Send a user_message_chunk session update to the client (used when replaying history).
fn send_user_message(cx: &ConnectionTo<Client>, session_id: &str, text: &str) {
    let chunk = ContentChunk::new(ContentBlock::Text(TextContent::new(text)));
    let update = SessionUpdate::UserMessageChunk(chunk);
    let notification = SessionNotification::new(session_id.to_string(), update);
    if let Err(e) = cx.send_notification(notification) {
        tracing::warn!("failed to send user session update: {e}");
    }
}

/// Send an agent_thought_chunk session update to the client. Mirrors
/// `send_message` but routes through ACP 0.12's `AgentThoughtChunk`
/// variant so the client renders reasoning text as a distinct,
/// typically-collapsible block instead of interleaving it with the
/// final answer.
fn send_thought(cx: &ConnectionTo<Client>, session_id: &str, text: &str) {
    let chunk = ContentChunk::new(ContentBlock::Text(TextContent::new(text)));
    let update = SessionUpdate::AgentThoughtChunk(chunk);
    let notification = SessionNotification::new(session_id.to_string(), update);
    if let Err(e) = cx.send_notification(notification) {
        tracing::warn!("failed to send thought session update: {e}");
    }
}

/// Build a system prompt based on the current session mode and working directory.
/// Build the `Vec<ChatMessage>` to send to the LLM for a fresh prompt:
/// system prompt, optional project instruction context, then replayed
/// history (with tool exchanges, #3409), then the new user prompt.
/// Pure -- exposed for unit testing the
/// replay shape without spinning up an LLM.
fn build_prompt_messages(snap: &SessionSnapshot, new_prompt: &str) -> Vec<ChatMessage> {
    let mut messages = Vec::with_capacity(snap.history.len() * 2 + 3);
    messages.push(ChatMessage::system(build_system_prompt(
        &snap.mode, &snap.cwd,
    )));
    if !snap.project_instructions.is_empty() {
        messages.push(ChatMessage::user(format!(
            "# AGENTS.md instructions for {}\n\n<INSTRUCTIONS>\n{}\n</INSTRUCTIONS>",
            snap.cwd.display(),
            snap.project_instructions
        )));
    }
    // Tier-1 disclosure: list each discovered skill's name+description
    // so the model can decide to auto-activate via `activate_skill`.
    // Skipped entirely when the registry is empty -- per the spec, an
    // empty `<available_skills/>` block would just confuse the model.
    if let Some(catalog) = build_skills_catalog(&snap.skills) {
        messages.push(ChatMessage::user(catalog));
    }
    for turn in &snap.history {
        messages.push(ChatMessage::user(turn.user_prompt.clone()));

        // If the prior turn used tools, replay them as a single
        // assistant_tool_calls message followed by one tool_result per call
        // -- enough for the LLM to see the calls it made and what came back,
        // so it doesn't redo the same searches or writes (#3409).
        //
        // FIXME(#3409 follow-up): multi-round tool sequences within the same
        // turn (text₀ + calls₀ → results → text₁ + calls₁ → results → final)
        // collapse into a single `assistant_tool_calls` batch here, with all
        // intermediate text concatenated into `agent_response` and replayed
        // *after* the tool_results. For models that condition heavily on
        // order-of-reasoning this is a faithfulness loss compared to the
        // original turn. Acceptable today (the LLM still sees the calls and
        // their results) but worth revisiting if we observe model-quality
        // regressions on resumed multi-round turns. A faithful replay would
        // require persisting `Vec<Vec<ToolExchange>>` plus per-round
        // assistant text, doubling the on-disk schema cost.
        if !turn.tool_exchanges.is_empty() {
            let calls: Vec<crate::llm_client::ToolCall> = turn
                .tool_exchanges
                .iter()
                .map(|e| crate::llm_client::ToolCall {
                    id: e.call_id.clone(),
                    r#type: "function".to_string(),
                    function: crate::llm_client::FunctionCall {
                        name: e.tool_name.clone(),
                        arguments: e.arguments.clone(),
                    },
                })
                .collect();
            messages.push(ChatMessage::assistant_tool_calls(calls));
            for exchange in &turn.tool_exchanges {
                messages.push(ChatMessage::tool_result(
                    &exchange.call_id,
                    &exchange.tool_name,
                    &exchange.result,
                ));
            }
        }

        // Skip the trailing assistant message when the turn ended without
        // any final text (e.g. tool_loop exhausted max_turns, or the last
        // LLM call failed/was cancelled): `agent_response == ""`. Several
        // OpenAI-compatible providers (Mistral, some local-LLM proxies,
        // Anthropic's tool-use shape) reject an `assistant` message that
        // is both empty-content and non-tool_calls; even when accepted it
        // wastes a slot and may confuse the model on long replays. If the
        // turn used tools, the tool_results above already terminate it
        // coherently. If it didn't use tools and produced no text either,
        // there is nothing to replay -- emitting "" would be misleading.
        if !turn.agent_response.is_empty() {
            messages.push(ChatMessage::assistant(turn.agent_response.clone()));
        }
    }
    messages.push(ChatMessage::user(new_prompt.to_string()));
    messages
}

fn build_system_prompt(mode: &SessionMode, cwd: &Path) -> String {
    let cwd_context = format!(
        "The user's working directory is: {}\n\
         All file paths should be interpreted relative to this directory.\n\n",
        cwd.display()
    );

    let mode_prompt = match mode {
        SessionMode::Lutz => {
            "You are Brokk, an AI coding assistant. You help users with software engineering tasks \
             using an agentic approach: break complex tasks into steps, execute them, and report \
             results. When appropriate, create a task list to track progress."
        }
        SessionMode::Code => {
            "You are Brokk, an AI coding assistant focused on code changes. Generate code \
             modifications, refactors, and implementations. Be concise and focus on the code."
        }
        SessionMode::Ask => {
            "You are Brokk, an AI coding assistant. Answer questions about code, architecture, \
             and software engineering concepts. Be thorough but concise."
        }
        SessionMode::Plan => {
            "You are Brokk, an AI coding assistant focused on planning. Analyze requirements, \
             design solutions, and create implementation plans. Do not write code directly."
        }
    };

    format!("{cwd_context}{mode_prompt}")
}

/// Returns true when `prompt_text` invokes the slash command `name`,
/// matching `/name` exactly or `/name <args>`. Whitespace and case are
/// normalized so clients that uppercase auto-complete entries still hit.
fn is_slash_command(prompt_text: &str, name: &str) -> bool {
    let stripped = prompt_text.trim();
    let Some(rest) = stripped.strip_prefix('/') else {
        return false;
    };
    let head = rest
        .split_whitespace()
        .next()
        .unwrap_or("")
        .to_ascii_lowercase();
    head == name
}

/// Parse `/<name> <args...>` out of a prompt. Returns `None` when the
/// prompt isn't a slash command. The `name` is lowercased for
/// case-insensitive lookup; the `args` slice preserves the original
/// casing/whitespace after the command head.
fn parse_slash_command(prompt_text: &str) -> Option<(String, String)> {
    let stripped = prompt_text.trim();
    let rest = stripped.strip_prefix('/')?;
    if rest.is_empty() {
        return None;
    }
    let (head, tail) = match rest.find(char::is_whitespace) {
        Some(i) => (&rest[..i], rest[i..].trim_start()),
        None => (rest, ""),
    };
    if head.is_empty() {
        return None;
    }
    Some((head.to_ascii_lowercase(), tail.to_string()))
}

/// Build the `<available_skills>` tier-1 disclosure block for the system
/// prompt. Returns `None` when the registry is empty so the caller can
/// skip the injection entirely (per the spec's "When no skills are
/// available" guidance: never emit an empty block).
fn build_skills_catalog(registry: &crate::skills::SkillRegistry) -> Option<String> {
    if registry.is_empty() {
        return None;
    }
    let mut out = String::from("<available_skills>\n");
    for meta in registry.iter_sorted() {
        out.push_str("  <skill>\n");
        out.push_str(&format!("    <name>{}</name>\n", xml_escape(&meta.name)));
        out.push_str(&format!(
            "    <description>{}</description>\n",
            xml_escape(&meta.description)
        ));
        out.push_str(&format!(
            "    <location>{}</location>\n",
            xml_escape(&meta.location.display().to_string())
        ));
        out.push_str("  </skill>\n");
    }
    out.push_str("</available_skills>\n\n");
    out.push_str(
        "The skills above provide specialized instructions for specific tasks. \
        When a task matches a skill's description, call the `activate_skill` tool \
        with the skill's name to load its full instructions. Users can also invoke \
        a skill directly by typing `/<skill-name>` as a slash command.",
    );
    Some(out)
}

/// Build the structured-wrapping payload sent to the LLM when a skill is
/// activated (whether via slash command or the `activate_skill` tool).
/// Format follows the spec's recommended "Structured wrapping" example:
/// the skill body inside `<skill_content name="...">` tags, with the
/// skill directory and a `<skill_resources>` listing so the model can
/// pull bundled scripts/references with its existing file-read tool.
pub(crate) fn build_skill_payload(meta: &crate::skills::SkillMeta) -> String {
    let body = match crate::skills::read_skill_body(&meta.location) {
        Ok(b) => b,
        Err(e) => {
            tracing::warn!(
                path = %meta.location.display(),
                "SKILL.md became unreadable between discovery and activation: {e}"
            );
            return format!(
                "<skill_content name=\"{}\">\n[skill file {} could not be read: {e}]\n</skill_content>",
                xml_escape(&meta.name),
                meta.location.display()
            );
        }
    };
    let resources = crate::skills::list_bundled_resources(&meta.skill_dir);
    let mut out = format!("<skill_content name=\"{}\">\n", xml_escape(&meta.name));
    out.push_str(&body);
    if !body.ends_with('\n') {
        out.push('\n');
    }
    out.push('\n');
    out.push_str(&format!("Skill directory: {}\n", meta.skill_dir.display()));
    out.push_str("Relative paths inside this skill resolve against the skill directory.\n");
    if !resources.is_empty() {
        out.push_str("\n<skill_resources>\n");
        for rel in &resources {
            out.push_str(&format!("  <file>{}</file>\n", xml_escape(rel)));
        }
        out.push_str("</skill_resources>\n");
    }
    out.push_str("</skill_content>");
    out
}

fn xml_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            _ => out.push(c),
        }
    }
    out
}

/// Handle the `/codex-login` slash command and its subcommands.
/// Subcommands: bare = start interactive login, `status` = report what's
/// stored, `disconnect` = wipe the local credentials.
///
/// On a successful bare login we install the freshly-built Codex
/// backend into `MultiBackend` so the next `session/new` (and any
/// subsequent `codex::*` route) picks it up without a server restart.
/// Without this, the empty-at-startup `Option` captured at
/// construction would remain `None` forever and the new credentials
/// would be unreachable until restart -- the behaviour issue #3555
/// reported.
async fn handle_codex_login(
    prompt_text: &str,
    llm: &Arc<MultiBackend>,
    sessions: &SessionStore,
    refresh_lock: &Arc<tokio::sync::Mutex<()>>,
) -> String {
    let arg = prompt_text
        .trim()
        .strip_prefix('/')
        .unwrap_or("")
        .split_whitespace()
        .nth(1)
        .unwrap_or("")
        .to_ascii_lowercase();

    match arg.as_str() {
        "status" => match crate::codex_auth::read_auth_dot_json() {
            Ok(Some(auth)) => {
                let mode = auth.auth_mode.as_deref().unwrap_or("(unset)");
                let has_key = auth.openai_api_key.is_some();
                let acct = auth
                    .tokens
                    .as_ref()
                    .map(|t| t.account_id.as_str())
                    .unwrap_or("(none)");
                let last = auth
                    .last_refresh
                    .map(|ts| ts.to_rfc3339())
                    .unwrap_or_else(|| "(unknown)".to_string());
                let routing = match mode {
                    "chatgpt" => "ChatGPT subscription (Responses API on chatgpt.com)",
                    "apikey" => "OPENAI_API_KEY (api.openai.com, billed as API usage)",
                    _ => "unknown",
                };
                // ChatGPT-only accounts don't get an OPENAI_API_KEY
                // because they have no API organization to mint one
                // against. Surface that explicitly so users don't read
                // "MISSING" as a broken login.
                let api_key_label = match (mode, has_key) {
                    (_, true) => "present",
                    ("chatgpt", false) => {
                        "n/a (ChatGPT-only account; subscription routing does not need one)"
                    }
                    (_, false) => "MISSING",
                };
                format!(
                    "Codex login status:\n  auth_mode: {mode}\n  routing: {routing}\n  api_key: {api_key_label}\n  account_id: {acct}\n  last_refresh: {last}"
                )
            }
            Ok(None) => {
                "No Codex credentials found. Run `/codex-login` to authenticate.".to_string()
            }
            Err(e) => format!("Failed to read ~/.codex/auth.json: {e:#}"),
        },
        "disconnect" => match crate::codex_auth::logout() {
            Ok(()) => {
                // Drop the in-memory backend so subsequent `codex::*`
                // routes fail loudly (and identically to a no-auth
                // startup) instead of firing requests against now-missing
                // credentials. Refresh the cached catalog so the picker
                // stops offering Codex models.
                llm.uninstall_codex();
                spawn_throttled_refresh(refresh_lock.clone(), llm.clone(), sessions.clone());
                "Codex credentials cleared and the in-memory backend was unloaded; \
                 the picker will only show Ollama models until you re-run `/codex-login`."
                    .to_string()
            }
            Err(e) => format!("Failed to remove ~/.codex/auth.json: {e:#}"),
        },
        "" => match crate::codex_auth::interactive_login().await {
            Ok(auth) => {
                let acct = auth
                    .tokens
                    .as_ref()
                    .map(|t| t.account_id.as_str())
                    .unwrap_or("(unknown)");
                // Install the new backend so this session (and any
                // future ones) can route `codex::*` and bare model ids
                // immediately. We only install when the auth payload
                // resolves to a usable backend -- a malformed auth.json
                // (e.g. apikey mode with no key) leaves the slot empty
                // and the user-facing message stays honest about it.
                match crate::codex_backend_from_auth(&auth) {
                    Some(backend) => {
                        llm.install_codex(backend);
                        // Refresh the cached model catalog in the
                        // background so the picker picks Codex up on
                        // the next `session/new` without waiting for
                        // an unrelated discovery trigger. Shares the
                        // same throttle as `session/new` so an
                        // immediate session creation right after login
                        // doesn't race a second probe.
                        spawn_throttled_refresh(
                            refresh_lock.clone(),
                            llm.clone(),
                            sessions.clone(),
                        );
                        format!(
                            "Codex login complete (account_id: {acct}). \
                             Codex is now active -- create a new session \
                             (or wait for the next discovery refresh) and \
                             pick a `codex::*` model from the picker; \
                             prompts route through your ChatGPT subscription \
                             via https://chatgpt.com/backend-api/codex/responses."
                        )
                    }
                    None => format!(
                        "Codex login completed but the saved credentials are not usable \
                         (auth_mode={:?}, no OPENAI_API_KEY). Re-run `/codex-login` or \
                         inspect ~/.codex/auth.json.",
                        auth.auth_mode
                    ),
                }
            }
            Err(e) => format!("Codex login failed: {e:#}"),
        },
        other => format!(
            "Unknown subcommand `{other}`. Try: /codex-login | /codex-login status | /codex-login disconnect"
        ),
    }
}

/// User-facing explanation returned when `OPENROUTER_API_KEY` is set
/// in the process environment. Single source of truth for the message
/// so the slash handler, future status surfaces, and any test stay in
/// agreement on the wording (and on the pointer to `/configure` for
/// the actual state dump).
fn openrouter_env_owned_explanation() -> String {
    "OpenRouter credentials are owned by the OPENROUTER_API_KEY environment \
     variable, so /openrouter-login is disabled. The server reads \
     OPENROUTER_API_KEY once at startup; unset it (and restart the server) if \
     you want to manage credentials via /openrouter-login. Run /configure to \
     see the current credential state (env_set / file_present / active_source)."
        .to_string()
}

/// Handle the `/openrouter-login` slash command and its subcommands.
/// Subcommands: bare = help text (no OAuth flow), `<key>` = save key and
/// install backend, `status` = report what's stored and where it came
/// from, `disconnect` = wipe the local credentials.
///
/// Unlike Codex, OpenRouter has no browser flow -- the user pastes a
/// static `sk-or-...` key inline. That key lands in the session
/// transcript, so the help text and the success message both warn the
/// user to rotate the key if the transcript is shared.
///
/// **Credential-ownership contract**: when `OPENROUTER_API_KEY` is set
/// in the process environment, the env owns the credential lifecycle
/// and this handler short-circuits with an explanation for every
/// subcommand. The slash is hidden from autocomplete in that mode too
/// (see `builtin_commands`), but the handler still runs when typed
/// manually so users can't get "command not found" with no hint.
/// Diagnostic state (env_set, file_present, active_source) stays
/// available via `/configure` regardless of which mode is active.
async fn handle_openrouter_login(
    prompt_text: &str,
    llm: &Arc<MultiBackend>,
    sessions: &SessionStore,
    refresh_lock: &Arc<tokio::sync::Mutex<()>>,
) -> String {
    if crate::openrouter_auth::CredentialState::snapshot().env_owns() {
        return openrouter_env_owned_explanation();
    }
    // Take the entire argument tail (everything after the command), not
    // just the first whitespace-delimited token: OpenRouter keys are
    // ASCII with no spaces in practice, but we trim defensively so a
    // user who pasted with trailing spaces doesn't see a "key was empty"
    // bounce. `status` and `disconnect` are case-insensitive to match
    // the `/codex-login` ergonomics.
    let after_cmd = prompt_text
        .trim()
        .strip_prefix('/')
        .unwrap_or("")
        .split_once(char::is_whitespace)
        .map(|(_, tail)| tail)
        .unwrap_or("")
        .trim();

    let lowered = after_cmd.to_ascii_lowercase();
    match lowered.as_str() {
        "" => format!(
            "Usage: `/openrouter-login <key>` | `/openrouter-login status` | \
             `/openrouter-login disconnect`. Get a key at \
             https://openrouter.ai/keys. Note: the key appears in this session's \
             transcript, so rotate it at openrouter.ai if you share the log. \
             Credentials are persisted to {}.",
            crate::openrouter_auth::auth_path()
                .map(|p| p.display().to_string())
                .unwrap_or_else(|_| "the OS config directory".to_string())
        ),
        "status" => {
            // env_owns short-circuits the whole handler at the top, so
            // we only reach this arm when the env var is unset. The
            // snapshot's env_set is therefore always false here -- we
            // include it in the output anyway for self-contained
            // diagnostics so users don't have to cross-reference
            // /configure to confirm the env is clear.
            let state = crate::openrouter_auth::CredentialState::snapshot();
            let file_key = match crate::openrouter_auth::read() {
                Ok(Some(auth)) => Some(auth.api_key.trim().to_string()).filter(|s| !s.is_empty()),
                Ok(None) => None,
                Err(e) => {
                    return format!("Failed to read OpenRouter credential file: {e:#}");
                }
            };
            let active_len = file_key
                .as_deref()
                .map(str::len)
                .map(|n| n.to_string())
                .unwrap_or_else(|| "n/a".to_string());
            let path = crate::openrouter_auth::auth_path()
                .map(|p| p.display().to_string())
                .unwrap_or_else(|_| "<unresolved>".to_string());
            format!(
                "OpenRouter login status:\n  active_source: {}\n  \
                 active_key_length: {active_len}\n  base_url: {}\n  \
                 credential_file: {path}\n  file_present: {}\n  env_set: {}",
                state.active_source(),
                crate::discovery::OPENROUTER_BASE_URL,
                state.file_present,
                state.env_set,
            )
        }
        "disconnect" => match crate::openrouter_auth::logout() {
            Ok(()) => {
                llm.uninstall_openrouter();
                spawn_throttled_refresh(refresh_lock.clone(), llm.clone(), sessions.clone());
                "OpenRouter credentials cleared and the in-memory backend was unloaded; \
                 the picker will only show models from other configured backends until \
                 you re-run `/openrouter-login <key>`."
                    .to_string()
            }
            Err(e) => format!("Failed to remove OpenRouter credential file: {e:#}"),
        },
        _ => {
            // Anything else is treated as a candidate API key. Reject
            // obvious junk (whitespace-only after trim is handled above;
            // empty is the "" arm); accept everything else and let the
            // first request 401 if the key is malformed. We don't gate
            // on the `sk-or-` prefix because OpenRouter has historically
            // issued keys with other shapes and we'd rather not hardcode
            // a check that ages out.
            let key = after_cmd.to_string();
            match crate::openrouter_auth::write(&crate::openrouter_auth::OpenRouterAuth {
                api_key: key.clone(),
            }) {
                Ok(()) => match crate::openrouter_backend_from_key(&key) {
                    Some(backend) => {
                        llm.install_openrouter(backend);
                        spawn_throttled_refresh(
                            refresh_lock.clone(),
                            llm.clone(),
                            sessions.clone(),
                        );
                        let path = crate::openrouter_auth::auth_path()
                            .map(|p| p.display().to_string())
                            .unwrap_or_else(|_| "<unresolved>".to_string());
                        format!(
                            "OpenRouter login complete (key length: {}). \
                             Credentials saved to {} (chmod 0600). The picker will \
                             show `openrouter::*` models after the next discovery \
                             refresh; create a new session or wait briefly. \
                             Reminder: the key was sent inline and is recorded in \
                             this session's transcript -- rotate it at \
                             https://openrouter.ai/keys if the transcript is shared.",
                            key.len(),
                            path,
                        )
                    }
                    None => {
                        // Defensive: write() rejects empty input upstream
                        // via the "" arm, so reaching None here means the
                        // key became empty after trim somewhere -- still
                        // surface a clear error rather than installing a
                        // broken backend.
                        let _ = crate::openrouter_auth::logout();
                        "OpenRouter login failed: provided key was empty after trimming".to_string()
                    }
                },
                Err(e) => format!("OpenRouter login failed: could not save key: {e:#}"),
            }
        }
    }
}

/// Pure parser for `/idle-timeout` arguments. Returns either a successful
/// action to apply, or a user-facing error string. Factored out from
/// `handle_idle_timeout` so it can be unit-tested without standing up a
/// real `SessionStore`. Bounds are shared with the `--llm-idle-timeout-secs`
/// CLI flag (see `llm_client::{MIN,MAX}_IDLE_CHUNK_TIMEOUT_SECS`).
#[derive(Debug, PartialEq, Eq)]
enum IdleTimeoutAction {
    /// `/idle-timeout` -- caller should render the current value.
    Show,
    /// `/idle-timeout default` -- clear the session override.
    Clear,
    /// `/idle-timeout <secs>` with a valid value.
    Set(u64),
}

fn parse_idle_timeout_arg(prompt_text: &str) -> Result<IdleTimeoutAction, String> {
    let arg = prompt_text
        .trim()
        .strip_prefix('/')
        .unwrap_or("")
        .split_whitespace()
        .nth(1)
        .unwrap_or("")
        .to_ascii_lowercase();

    let min = crate::llm_client::MIN_IDLE_CHUNK_TIMEOUT_SECS;
    let max = crate::llm_client::MAX_IDLE_CHUNK_TIMEOUT_SECS;

    match arg.as_str() {
        "" => Ok(IdleTimeoutAction::Show),
        "default" => Ok(IdleTimeoutAction::Clear),
        other => match other.parse::<u64>() {
            Ok(secs) if (min..=max).contains(&secs) => Ok(IdleTimeoutAction::Set(secs)),
            Ok(out_of_range) => Err(format!(
                "Value `{out_of_range}` is out of range. Pick a value between \
                 {min}s and {max}s, or use `default` to clear the override."
            )),
            Err(_) => Err(format!(
                "Unknown subcommand `{other}`. Try: /idle-timeout | \
                 /idle-timeout <secs> | /idle-timeout default"
            )),
        },
    }
}

/// Handle the `/idle-timeout` slash command. Reads/sets the per-session
/// LLM SSE idle timeout (in seconds). The session override is in-memory
/// only -- it does not survive a session reload or a server restart.
///
/// Subcommands:
///   `/idle-timeout`           -> report the active value and where it came from
///   `/idle-timeout <secs>`    -> set the session override (1..=86_400)
///   `/idle-timeout default`   -> clear the session override
async fn handle_idle_timeout(
    prompt_text: &str,
    session_id: &str,
    sessions: &SessionStore,
    current_session_override: Option<u64>,
    default_secs: u64,
) -> String {
    let action = match parse_idle_timeout_arg(prompt_text) {
        Ok(action) => action,
        Err(msg) => return msg,
    };
    match action {
        IdleTimeoutAction::Show => match current_session_override {
            Some(secs) => format!(
                "LLM idle timeout: {secs}s (session override).\n\
                 Server default is {default_secs}s. Use `/idle-timeout default` to clear, \
                 or `/idle-timeout <secs>` to change."
            ),
            None => format!(
                "LLM idle timeout: {default_secs}s (server default).\n\
                 Use `/idle-timeout <secs>` to override for this session only, \
                 or restart with `--llm-idle-timeout-secs` / `BROKK_ACP_LLM_IDLE_TIMEOUT_SECS` \
                 to change the default."
            ),
        },
        IdleTimeoutAction::Clear => {
            if sessions.set_idle_timeout_secs(session_id, None).await {
                format!(
                    "Cleared session override. LLM idle timeout is back to the server \
                     default ({default_secs}s)."
                )
            } else {
                "Error: unknown session.".to_string()
            }
        }
        IdleTimeoutAction::Set(secs) => {
            if sessions.set_idle_timeout_secs(session_id, Some(secs)).await {
                format!(
                    "LLM idle timeout set to {secs}s for this session. \
                     In-memory only -- reload or restart resets to the server \
                     default ({default_secs}s)."
                )
            } else {
                "Error: unknown session.".to_string()
            }
        }
    }
}

/// Handle the `/configure` slash command. Thin façade over
/// `apply_config_option` so users on clients without the
/// `configOptions` dropdown UI can drive the same four session knobs
/// (behavior_mode, permission_mode, model_selection, reasoning_effort)
/// via text. New keys are added explicitly to `CONFIGURE_KNOWN_KEYS`,
/// never inferred -- the goal is to mirror the structured surface, not
/// open a parallel state channel.
///
/// Subcommands:
///   `/configure`                 -> dump current values for every key
///   `/configure <key>`           -> dump the current value of <key>
///   `/configure <key> <value>`   -> set <key> to <value>, re-emit
///                                   `ConfigOptionUpdate` so dropdown UIs
///                                   stay in sync.
async fn handle_configure(
    cx: &ConnectionTo<Client>,
    prompt_text: &str,
    session_id: &str,
    sessions: &SessionStore,
) -> String {
    let args = parse_slash_command(prompt_text)
        .map(|(_, a)| a)
        .unwrap_or_default();
    let trimmed = args.trim();

    // No args, or a single token: show current state.
    if trimmed.is_empty() || trimmed.split_whitespace().count() == 1 {
        let fallback_cwd = std::env::current_dir().unwrap_or_default();
        let Some(session) = sessions.get_session(session_id, &fallback_cwd).await else {
            return "Error: unknown session.".to_string();
        };
        if trimmed.is_empty() {
            return render_configure_dump(&session);
        }
        return render_configure_single(trimmed, &session);
    }

    let mut parts = trimmed.splitn(2, char::is_whitespace);
    let key = parts.next().unwrap_or("");
    let value = parts.next().unwrap_or("").trim();

    match apply_config_option(sessions, session_id, key, value).await {
        Ok(outcome) => {
            let notification = SessionNotification::new(
                session_id.to_string(),
                SessionUpdate::ConfigOptionUpdate(ConfigOptionUpdate::new(outcome.updated_options)),
            );
            if let Err(e) = cx.send_notification(notification) {
                tracing::warn!("failed to send config_option_update from /configure: {e}");
            }
            let mut msg = format!("Set `{key}` to `{value}`.");
            if let Some(prev) = outcome.cleared_reasoning {
                msg.push_str(&format!(
                    "\nReasoning effort reset: `{prev}` is not supported by the new model. \
                     Using model default until you pick a level."
                ));
            }
            msg
        }
        Err(e) => format!("Error: {}", e.human_message()),
    }
}

/// Render the `/configure` dump of all four session knobs as a single
/// readable block. Mirrors `all_config_options` field-for-field so the
/// text view never drifts from the structured view.
///
/// The OpenRouter credential block at the bottom is **read-only** -- it
/// reports where the active key comes from (env / file / none) and
/// whether `/openrouter-login` is available, so users can see the
/// credential state even when the env owns the lifecycle and the slash
/// is hidden from autocomplete. Setting credentials is intentionally
/// not routed through `/configure`: a global secret has a different
/// lifecycle than per-session knobs, and the dedicated
/// `/openrouter-login` keeps that distinction sharp.
fn render_configure_dump(session: &Session) -> String {
    let effort = session
        .selected_reasoning_effort
        .clone()
        .unwrap_or_else(|| REASONING_EFFORT_DEFAULT_VALUE.to_string());
    let or_state = crate::openrouter_auth::CredentialState::snapshot();
    let login_available = !or_state.env_owns();
    format!(
        "**Configuration**\n\n\
         - `{BEHAVIOR_CONFIG_ID}`: `{behavior}`\n\
         - `{PERMISSION_CONFIG_ID}`: `{permission}`\n\
         - `{MODEL_CONFIG_ID}`: `{model}`\n\
         - `{REASONING_EFFORT_CONFIG_ID}`: `{effort}`\n\n\
         Set a value with `/configure <key> <value>`. \
         Supported keys: {keys}.\n\n\
         **OpenRouter credentials** (read-only)\n\n\
         - `active_source`: `{active_source}`\n\
         - `env_set`: `{env_set}`\n\
         - `file_present`: `{file_present}`\n\
         - `login_command_available`: `{login_available}`\n\n\
         {login_hint}",
        behavior = session.mode.as_str(),
        permission = session.permission_mode.as_str(),
        model = session.model,
        keys = CONFIGURE_KNOWN_KEYS.join(", "),
        active_source = or_state.active_source(),
        env_set = or_state.env_set,
        file_present = or_state.file_present,
        login_available = login_available,
        login_hint = if login_available {
            "Manage the key with `/openrouter-login <key>` | `status` | `disconnect`."
        } else {
            "`/openrouter-login` is disabled because `OPENROUTER_API_KEY` owns \
             the credential lifecycle. Unset the env var and restart the server \
             to manage the key from a session."
        },
    )
}

/// Render a single key's current value. Unknown keys return the same
/// error string `apply_config_option` would produce so the user gets
/// consistent feedback whether they're reading or writing.
fn render_configure_single(key: &str, session: &Session) -> String {
    match key {
        BEHAVIOR_CONFIG_ID => format!("`{key}`: `{}`", session.mode.as_str()),
        PERMISSION_CONFIG_ID => format!("`{key}`: `{}`", session.permission_mode.as_str()),
        MODEL_CONFIG_ID => format!("`{key}`: `{}`", session.model),
        REASONING_EFFORT_CONFIG_ID => format!(
            "`{key}`: `{}`",
            session
                .selected_reasoning_effort
                .clone()
                .unwrap_or_else(|| REASONING_EFFORT_DEFAULT_VALUE.to_string())
        ),
        other => format!(
            "Error: unknown config key '{other}'. Supported: {}",
            CONFIGURE_KNOWN_KEYS.join(", ")
        ),
    }
}

/// Render the `/context` snapshot. Mirrors the Java executor's report at a
/// coarser granularity -- the Rust agent does not yet model
/// editable/readonly/virtual fragments, so the table reports the
/// conversation history instead, which is what actually drives token
/// pressure on the LLM today.
fn render_context_report(
    snap: &crate::session::SessionSnapshot,
    permission_mode: PermissionMode,
    available_models: &[String],
) -> String {
    // Char/4 is the same back-of-the-envelope estimate the Java side uses
    // for non-tokenizer-aware approximations. Good enough for a snapshot
    // dump; not load-bearing.
    let approx_tokens = |s: &str| s.chars().count() / 4;
    let mut user_tokens = 0usize;
    let mut agent_tokens = 0usize;
    for turn in &snap.history {
        user_tokens += approx_tokens(&turn.user_prompt);
        agent_tokens += approx_tokens(&turn.agent_response);
    }
    let total_tokens = user_tokens + agent_tokens;
    let model_display = if snap.model.is_empty() {
        "(none)".to_string()
    } else {
        snap.model.clone()
    };
    let catalog_size = available_models.len();

    let mut out = String::new();
    out.push_str("**Session context**\n\n");
    out.push_str(&format!("- Working directory: `{}`\n", snap.cwd.display()));
    out.push_str(&format!("- Mode: `{}`\n", snap.mode.as_str()));
    out.push_str(&format!(
        "- Permission mode: `{}`\n",
        permission_mode.as_str()
    ));
    out.push_str(&format!(
        "- Model: `{model_display}` ({catalog_size} known in catalog)\n"
    ));
    out.push_str(&format!(
        "- Conversation turns: {} (~{} tokens user / ~{} tokens agent / ~{} total)\n",
        snap.history.len(),
        user_tokens,
        agent_tokens,
        total_tokens
    ));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn is_slash_command_matches_bare_and_with_args() {
        assert!(is_slash_command("/context", "context"));
        assert!(is_slash_command("  /context  ", "context"));
        assert!(is_slash_command("/context with extra args", "context"));
        // Case-insensitive: clients sometimes uppercase auto-complete entries.
        assert!(is_slash_command("/Context", "context"));
        assert!(is_slash_command("/CONTEXT", "context"));
    }

    #[test]
    fn parse_idle_timeout_arg_routes_to_show_when_bare() {
        assert_eq!(
            parse_idle_timeout_arg("/idle-timeout"),
            Ok(IdleTimeoutAction::Show)
        );
        assert_eq!(
            parse_idle_timeout_arg("  /idle-timeout  "),
            Ok(IdleTimeoutAction::Show)
        );
    }

    #[test]
    fn parse_idle_timeout_arg_clears_on_default_keyword() {
        assert_eq!(
            parse_idle_timeout_arg("/idle-timeout default"),
            Ok(IdleTimeoutAction::Clear)
        );
        // Case-insensitive keyword.
        assert_eq!(
            parse_idle_timeout_arg("/idle-timeout DEFAULT"),
            Ok(IdleTimeoutAction::Clear)
        );
    }

    #[test]
    fn parse_idle_timeout_arg_accepts_numeric_in_range() {
        assert_eq!(
            parse_idle_timeout_arg("/idle-timeout 600"),
            Ok(IdleTimeoutAction::Set(600))
        );
        // Bounds inclusive.
        assert_eq!(
            parse_idle_timeout_arg("/idle-timeout 1"),
            Ok(IdleTimeoutAction::Set(1))
        );
        assert_eq!(
            parse_idle_timeout_arg("/idle-timeout 86400"),
            Ok(IdleTimeoutAction::Set(86_400))
        );
    }

    #[test]
    fn parse_idle_timeout_arg_rejects_out_of_range() {
        // 0 would mean "abort instantly" -- the lower bound is 1.
        let err = parse_idle_timeout_arg("/idle-timeout 0").expect_err("zero must reject");
        assert!(err.contains("out of range"), "got: {err}");
        // Above the 24h ceiling.
        let err = parse_idle_timeout_arg("/idle-timeout 999999").expect_err("huge must reject");
        assert!(err.contains("out of range"), "got: {err}");
    }

    #[test]
    fn parse_idle_timeout_arg_rejects_non_numeric_junk() {
        let err = parse_idle_timeout_arg("/idle-timeout banana").expect_err("junk must reject");
        assert!(err.contains("Unknown subcommand"), "got: {err}");
    }

    #[test]
    fn is_slash_command_rejects_non_matches() {
        // Plain text is never a command, even if the word "context" appears.
        assert!(!is_slash_command("context please", "context"));
        // Missing leading slash.
        assert!(!is_slash_command("context", "context"));
        // Different command sharing a prefix must not match.
        assert!(!is_slash_command("/contextual", "context"));
        // Empty input.
        assert!(!is_slash_command("", "context"));
        assert!(!is_slash_command("/", "context"));
    }

    #[test]
    fn model_config_option_omitted_when_catalog_empty() {
        // No discovery results means we can't offer a meaningful dropdown.
        assert!(model_config_option("anything", &[]).is_none());
    }

    #[test]
    fn model_config_option_present_when_catalog_known() {
        let models = vec!["model-a".to_string(), "model-b".to_string()];
        // Spot-check that the option is actually built. Field shapes are
        // covered by the `agent-client-protocol` crate; we just need to know
        // the helper produced *something*.
        assert!(model_config_option("model-a", &models).is_some());
        // Out-of-catalog current value still produces an option (we fall
        // back to the first catalog entry); tested implicitly via the
        // is_some assertion plus the no-panic contract.
        assert!(model_config_option("model-zzz", &models).is_some());
        assert!(model_config_option("", &models).is_some());
    }

    /// `extract_prompt_text` joins text blocks with newlines and silently
    /// drops blocks that are not text -- images, embedded resources, etc.
    /// don't get fed to the chat-completions endpoint.
    #[test]
    fn extract_prompt_text_joins_text_blocks_with_newlines() {
        let blocks = vec![
            ContentBlock::Text(TextContent::new("hello")),
            ContentBlock::Text(TextContent::new("world")),
        ];
        assert_eq!(extract_prompt_text(&blocks), "hello\nworld");
    }

    #[test]
    fn extract_prompt_text_returns_empty_for_no_text_blocks() {
        // Empty input is the simplest case `session/prompt` rejects with
        // "Error: empty prompt" -- the helper itself just yields "".
        assert_eq!(extract_prompt_text(&[]), "");
    }

    /// A prompt with mixed blocks (e.g. text plus an image) must keep the
    /// text and silently drop the rest. Today the agent doesn't advertise
    /// image support, but well-behaved clients can still send mixed prompts
    /// when speaking to multiple agents through a single session.
    #[test]
    fn extract_prompt_text_filters_non_text_blocks() {
        use agent_client_protocol::schema::ImageContent;
        let blocks = vec![
            ContentBlock::Text(TextContent::new("before")),
            ContentBlock::Image(ImageContent::new("base64data", "image/png")),
            ContentBlock::Text(TextContent::new("after")),
        ];
        assert_eq!(extract_prompt_text(&blocks), "before\nafter");
    }

    /// All four behavior modes embed the cwd into the system prompt so
    /// the model can resolve relative paths, and each mode picks a
    /// distinct mode-specific paragraph.
    #[test]
    fn build_system_prompt_includes_cwd_and_mode_specific_text() {
        let cwd = std::path::Path::new("/tmp/some-cwd");
        for (mode, marker) in [
            (SessionMode::Lutz, "agentic approach"),
            (SessionMode::Code, "focused on code changes"),
            (SessionMode::Ask, "Answer questions about code"),
            (SessionMode::Plan, "focused on planning"),
        ] {
            let prompt = build_system_prompt(&mode, cwd);
            assert!(
                prompt.contains("/tmp/some-cwd") || prompt.contains("\\tmp\\some-cwd"),
                "system prompt for {mode:?} must embed the cwd, got: {prompt}"
            );
            assert!(
                prompt.contains(marker),
                "system prompt for {mode:?} must mention '{marker}', got: {prompt}"
            );
        }
    }

    /// `render_context_report` is the body of the `/context` slash command.
    /// It should surface the mode, permission mode, model, conversation
    /// turn count, and token estimate -- enough that the user can debug
    /// "why does the model think X" without a separate inspector.
    #[test]
    fn render_context_report_lists_session_facts() {
        use crate::session::{ConversationTurn, SessionSnapshot};
        let snap = SessionSnapshot {
            cwd: std::path::PathBuf::from("/tmp/cwd"),
            mode: SessionMode::Code,
            model: "gpt-99".into(),
            history: vec![ConversationTurn {
                user_prompt: "hi".repeat(8), // 16 chars -> ~4 tokens
                agent_response: "ok".repeat(8),
                ..Default::default()
            }],
            reasoning_effort: None,
            idle_timeout_secs: None,
            project_instructions: String::new(),
            skills: std::sync::Arc::new(crate::skills::SkillRegistry::default()),
        };
        let report = render_context_report(&snap, PermissionMode::AcceptEdits, &["gpt-99".into()]);

        assert!(report.contains("Mode: `CODE`"));
        assert!(report.contains("Permission mode: `acceptEdits`"));
        assert!(report.contains("Model: `gpt-99`"));
        assert!(report.contains("(1 known in catalog)"));
        assert!(report.contains("Conversation turns: 1"));
        // Token estimate rolls user + agent into the total.
        assert!(report.contains("~"));
    }

    /// When no model is set, `/context` shows `(none)` rather than the
    /// empty string so the user notices the misconfig.
    #[test]
    fn render_context_report_shows_none_when_model_empty() {
        use crate::session::SessionSnapshot;
        let snap = SessionSnapshot {
            cwd: std::path::PathBuf::from("/tmp/cwd"),
            mode: SessionMode::Lutz,
            model: String::new(),
            history: vec![],
            reasoning_effort: None,
            idle_timeout_secs: None,
            project_instructions: String::new(),
            skills: std::sync::Arc::new(crate::skills::SkillRegistry::default()),
        };
        let report = render_context_report(&snap, PermissionMode::Default, &[]);
        assert!(report.contains("Model: `(none)`"));
        assert!(report.contains("(0 known in catalog)"));
        assert!(report.contains("Conversation turns: 0"));
    }

    /// `build_prompt_messages` for a turn that used no tools must produce
    /// the historical user/assistant pair plus the new user prompt -- no
    /// tool_call or tool messages snuck in.
    #[test]
    fn build_prompt_messages_text_only_history() {
        use crate::session::{ConversationTurn, SessionSnapshot};
        let snap = SessionSnapshot {
            cwd: std::path::PathBuf::from("/tmp/cwd"),
            mode: SessionMode::Code,
            model: "m".into(),
            history: vec![ConversationTurn {
                user_prompt: "what is rust?".into(),
                agent_response: "a language".into(),
                ..Default::default()
            }],
            reasoning_effort: None,
            idle_timeout_secs: None,
            project_instructions: String::new(),
            skills: std::sync::Arc::new(crate::skills::SkillRegistry::default()),
        };
        let msgs = build_prompt_messages(&snap, "follow up");
        // system + user(history) + assistant(history) + user(new)
        assert_eq!(msgs.len(), 4);
        assert_eq!(msgs[0].role, "system");
        assert_eq!(msgs[1].role, "user");
        assert_eq!(msgs[1].content.as_deref(), Some("what is rust?"));
        assert_eq!(msgs[2].role, "assistant");
        assert_eq!(msgs[2].content.as_deref(), Some("a language"));
        assert_eq!(msgs[3].role, "user");
        assert_eq!(msgs[3].content.as_deref(), Some("follow up"));
    }

    /// History with tool_exchanges must replay as user → assistant_tool_calls
    /// → N tool_results → final assistant text → new user. This is the
    /// regression #3409 fixes: without it, a session/load fed the LLM
    /// only the final answer and the model would repeat searches/reads.
    #[test]
    fn build_prompt_messages_replays_tool_exchanges() {
        use crate::session::{ConversationTurn, SessionSnapshot, ToolExchange};
        let snap = SessionSnapshot {
            cwd: std::path::PathBuf::from("/tmp/cwd"),
            mode: SessionMode::Code,
            model: "m".into(),
            history: vec![ConversationTurn {
                user_prompt: "find TODOs".into(),
                agent_response: "found 3 in src/lib.rs".into(),
                tool_exchanges: vec![
                    ToolExchange {
                        call_id: "c1".into(),
                        tool_name: "searchFileContents".into(),
                        arguments: r#"{"pattern":"TODO"}"#.into(),
                        result: "src/lib.rs:42: // TODO".into(),
                    },
                    ToolExchange {
                        call_id: "c2".into(),
                        tool_name: "readFile".into(),
                        arguments: r#"{"path":"src/lib.rs"}"#.into(),
                        result: "fn main() {}".into(),
                    },
                ],
            }],
            reasoning_effort: None,
            idle_timeout_secs: None,
            project_instructions: String::new(),
            skills: std::sync::Arc::new(crate::skills::SkillRegistry::default()),
        };
        let msgs = build_prompt_messages(&snap, "now fix them");

        // Expected flow: system, user, assistant(tool_calls), tool, tool,
        // assistant(text), user.
        assert_eq!(msgs.len(), 7);
        assert_eq!(msgs[0].role, "system");
        assert_eq!(msgs[1].role, "user");
        assert_eq!(msgs[1].content.as_deref(), Some("find TODOs"));

        // assistant_tool_calls: no content, tool_calls present, both calls
        // bundled into a single batch (the conservative collapse).
        assert_eq!(msgs[2].role, "assistant");
        assert!(msgs[2].content.is_none());
        let calls = msgs[2].tool_calls.as_ref().expect("tool_calls present");
        assert_eq!(calls.len(), 2);
        assert_eq!(calls[0].id, "c1");
        assert_eq!(calls[0].function.name, "searchFileContents");
        assert_eq!(calls[1].id, "c2");
        assert_eq!(calls[1].function.name, "readFile");

        // tool_result messages, paired by call_id and in original order.
        assert_eq!(msgs[3].role, "tool");
        assert_eq!(msgs[3].tool_call_id.as_deref(), Some("c1"));
        assert_eq!(msgs[3].content.as_deref(), Some("src/lib.rs:42: // TODO"));
        assert_eq!(msgs[4].role, "tool");
        assert_eq!(msgs[4].tool_call_id.as_deref(), Some("c2"));
        assert_eq!(msgs[4].content.as_deref(), Some("fn main() {}"));

        // Final assistant text and new user prompt.
        assert_eq!(msgs[5].role, "assistant");
        assert_eq!(msgs[5].content.as_deref(), Some("found 3 in src/lib.rs"));
        assert_eq!(msgs[6].role, "user");
        assert_eq!(msgs[6].content.as_deref(), Some("now fix them"));
    }

    /// Empty history: just system + the new user prompt. Establishes the
    /// `with_capacity(history.len() * 2 + 2)` lower bound.
    #[test]
    fn build_prompt_messages_empty_history() {
        use crate::session::SessionSnapshot;
        let snap = SessionSnapshot {
            cwd: std::path::PathBuf::from("/tmp/cwd"),
            mode: SessionMode::Lutz,
            model: "m".into(),
            history: vec![],
            reasoning_effort: None,
            idle_timeout_secs: None,
            project_instructions: String::new(),
            skills: std::sync::Arc::new(crate::skills::SkillRegistry::default()),
        };
        let msgs = build_prompt_messages(&snap, "hi");
        assert_eq!(msgs.len(), 2);
        assert_eq!(msgs[0].role, "system");
        assert_eq!(msgs[1].role, "user");
        assert_eq!(msgs[1].content.as_deref(), Some("hi"));
    }

    #[test]
    fn build_prompt_messages_puts_project_instructions_in_user_context() {
        use crate::session::SessionSnapshot;
        let snap = SessionSnapshot {
            cwd: std::path::PathBuf::from("/tmp/cwd"),
            mode: SessionMode::Code,
            model: "m".into(),
            history: vec![],
            reasoning_effort: None,
            idle_timeout_secs: None,
            project_instructions: "Use the local style.".into(),
            skills: std::sync::Arc::new(crate::skills::SkillRegistry::default()),
        };

        let msgs = build_prompt_messages(&snap, "hi");

        assert_eq!(msgs.len(), 3);
        assert_eq!(msgs[0].role, "system");
        assert!(
            !msgs[0]
                .content
                .as_deref()
                .expect("system prompt")
                .contains("Use the local style."),
            "project-controlled AGENTS.md content must not be system instructions"
        );
        assert_eq!(msgs[1].role, "user");
        let project_context = msgs[1].content.as_deref().expect("project context");
        assert!(project_context.starts_with("# AGENTS.md instructions for "));
        assert!(project_context.contains("<INSTRUCTIONS>\nUse the local style.\n</INSTRUCTIONS>"));
        assert_eq!(msgs[2].role, "user");
        assert_eq!(msgs[2].content.as_deref(), Some("hi"));
    }

    /// A turn that ended without final assistant text (e.g. tool_loop hit
    /// max_turns mid-tools, or the final LLM call was cancelled) must NOT
    /// emit an empty `assistant("")` message on replay -- several
    /// providers reject an assistant message that is both empty-content
    /// and not a tool_calls message, and even when accepted it wastes a
    /// slot. The tool_results from this turn already terminate it
    /// coherently for the LLM (#3409 review MED).
    #[test]
    fn build_prompt_messages_skips_empty_assistant_after_tools() {
        use crate::session::{ConversationTurn, SessionSnapshot, ToolExchange};
        let snap = SessionSnapshot {
            cwd: std::path::PathBuf::from("/tmp/cwd"),
            mode: SessionMode::Code,
            model: "m".into(),
            history: vec![ConversationTurn {
                user_prompt: "search".into(),
                // Empty: turn ended without final assistant text.
                agent_response: String::new(),
                tool_exchanges: vec![ToolExchange {
                    call_id: "c1".into(),
                    tool_name: "searchFileContents".into(),
                    arguments: r#"{"pattern":"x"}"#.into(),
                    result: "no matches".into(),
                }],
            }],
            reasoning_effort: None,
            idle_timeout_secs: None,
            project_instructions: String::new(),
            skills: std::sync::Arc::new(crate::skills::SkillRegistry::default()),
        };
        let msgs = build_prompt_messages(&snap, "next");

        // Expected: system, user, assistant_tool_calls, tool, user(new).
        // No trailing `assistant("")`.
        assert_eq!(msgs.len(), 5);
        assert_eq!(msgs[0].role, "system");
        assert_eq!(msgs[1].role, "user");
        assert_eq!(msgs[2].role, "assistant");
        assert!(msgs[2].content.is_none());
        assert!(msgs[2].tool_calls.is_some());
        assert_eq!(msgs[3].role, "tool");
        assert_eq!(msgs[4].role, "user");
        assert_eq!(msgs[4].content.as_deref(), Some("next"));
    }

    // ---------------------------------------------------------------
    // Agent Skills integration (catalog injection, slash dispatch,
    // built-in collision precedence, command merging, payload format).
    // ---------------------------------------------------------------

    use crate::skills::{SkillMeta, SkillRegistry, SkillScope};
    use std::path::PathBuf as TestPathBuf;

    fn make_registry(skills: Vec<(&str, &str)>) -> std::sync::Arc<SkillRegistry> {
        let tmp = tempfile::TempDir::new().unwrap();
        let mut reg = SkillRegistry::default();
        for (name, description) in skills {
            // Write a real SKILL.md so `build_skill_payload` can read it.
            let skill_dir = tmp.path().join(name);
            std::fs::create_dir_all(&skill_dir).unwrap();
            let location = skill_dir.join("SKILL.md");
            std::fs::write(
                &location,
                format!("---\nname: {name}\ndescription: {description}\n---\nBody for {name}"),
            )
            .unwrap();
            reg.insert_for_test(SkillMeta {
                name: name.to_string(),
                description: description.to_string(),
                location: location.clone(),
                skill_dir: skill_dir.clone(),
                scope: SkillScope::Project,
            });
        }
        // Leak the TempDir so files survive the test (we don't manage
        // lifetime here; the worker thread cleans up the system tmpdir).
        std::mem::forget(tmp);
        std::sync::Arc::new(reg)
    }

    #[test]
    fn build_prompt_messages_injects_catalog_when_skills_present() {
        use crate::session::SessionSnapshot;
        let snap = SessionSnapshot {
            cwd: TestPathBuf::from("/tmp/cwd"),
            mode: SessionMode::Code,
            model: "m".into(),
            history: vec![],
            reasoning_effort: None,
            idle_timeout_secs: None,
            project_instructions: String::new(),
            skills: make_registry(vec![
                ("hello-world", "Greet the user with a single short line."),
                ("pdf-processing", "Extract text from PDFs."),
            ]),
        };
        let msgs = build_prompt_messages(&snap, "hi");
        // system, catalog (user context), user(new) -> 3
        assert_eq!(msgs.len(), 3);
        let catalog = msgs[1]
            .content
            .as_ref()
            .expect("catalog message has content");
        assert!(catalog.contains("<available_skills>"));
        assert!(catalog.contains("<name>hello-world</name>"));
        assert!(catalog.contains("<name>pdf-processing</name>"));
        // Sorted: hello-world before pdf-processing.
        let hw = catalog.find("<name>hello-world</name>").unwrap();
        let pdf = catalog.find("<name>pdf-processing</name>").unwrap();
        assert!(hw < pdf, "catalog must be alphabetically sorted");
        // Behavioral instruction tells the model to call activate_skill.
        assert!(catalog.contains("activate_skill"));
    }

    #[test]
    fn build_prompt_messages_skips_catalog_when_empty() {
        use crate::session::SessionSnapshot;
        let snap = SessionSnapshot {
            cwd: TestPathBuf::from("/tmp/cwd"),
            mode: SessionMode::Code,
            model: "m".into(),
            history: vec![],
            reasoning_effort: None,
            idle_timeout_secs: None,
            project_instructions: String::new(),
            skills: std::sync::Arc::new(SkillRegistry::default()),
        };
        let msgs = build_prompt_messages(&snap, "hi");
        // Just system + the user prompt -- no catalog message.
        assert_eq!(msgs.len(), 2);
        for m in &msgs {
            if let Some(c) = m.content.as_deref() {
                assert!(
                    !c.contains("<available_skills>"),
                    "empty registry must not emit an empty catalog block"
                );
            }
        }
    }

    #[test]
    fn available_commands_merges_builtins_and_skills() {
        use crate::openrouter_auth::test_support::{ENV_GUARD, EnvScope};
        // The expected ordering includes /openrouter-login, which is
        // gated on OPENROUTER_API_KEY being unset; without this guard
        // the test would fail on dev machines that happen to have the
        // env var exported.
        let _lock = ENV_GUARD.blocking_lock();
        let _env = EnvScope::remove("OPENROUTER_API_KEY");
        let registry = make_registry(vec![("zebra", "Z skill"), ("apple", "A skill")]);
        let cmds = available_commands(&registry);
        let names: Vec<&str> = cmds.iter().map(|c| c.name.as_str()).collect();
        // Built-ins come first in their declared order; skills follow,
        // sorted alphabetically.
        assert_eq!(
            names,
            vec![
                "context",
                "codex-login",
                "openrouter-login",
                "idle-timeout",
                "configure",
                "sandbox",
                "apple",
                "zebra",
            ]
        );
    }

    #[test]
    fn slash_collision_with_builtin_keeps_builtin_warns() {
        // A skill named `context` must NOT shadow the `/context` builtin
        // in autocomplete (the dispatcher checks built-ins first, so the
        // slash still hits the builtin, but the duplicate command entry
        // would confuse the user).
        let registry = make_registry(vec![
            ("context", "this should be hidden"),
            ("ok-skill", "this should show"),
        ]);
        let cmds = available_commands(&registry);
        let names: Vec<&str> = cmds.iter().map(|c| c.name.as_str()).collect();
        // Built-in `context` exactly once; skill `context` dropped.
        assert_eq!(names.iter().filter(|n| **n == "context").count(), 1);
        // Non-colliding skill still appears.
        assert!(names.contains(&"ok-skill"));
    }

    /// When `OPENROUTER_API_KEY` is set, the env owns the credential
    /// lifecycle and `/openrouter-login` must not appear in
    /// autocomplete -- it would be misleading because the server can't
    /// swap the env-sourced backend at runtime. The slash is still
    /// reserved in `builtin_command_names()` so a skill can't capture
    /// it; typing it manually still dispatches to the handler, which
    /// short-circuits with an explanation.
    #[test]
    fn available_commands_omits_openrouter_login_when_env_set() {
        use crate::openrouter_auth::test_support::{ENV_GUARD, EnvScope};
        let _lock = ENV_GUARD.blocking_lock();
        let _env = EnvScope::set("OPENROUTER_API_KEY", "sk-or-from-env");

        let registry = make_registry(vec![]);
        let cmds = available_commands(&registry);
        let names: Vec<&str> = cmds.iter().map(|c| c.name.as_str()).collect();

        assert!(
            !names.contains(&"openrouter-login"),
            "openrouter-login must be hidden when env owns credentials; got {names:?}"
        );
        // The other built-ins are unaffected.
        assert!(names.contains(&"context"));
        assert!(names.contains(&"codex-login"));
        assert!(names.contains(&"idle-timeout"));
        assert!(names.contains(&"configure"));
    }

    /// Sanity check the inverse: with the env unset, the slash IS
    /// advertised. Pairs with the env-set test so a future refactor
    /// can't accidentally hide the command unconditionally.
    #[test]
    fn available_commands_includes_openrouter_login_when_env_unset() {
        use crate::openrouter_auth::test_support::{ENV_GUARD, EnvScope};
        let _lock = ENV_GUARD.blocking_lock();
        let _env = EnvScope::remove("OPENROUTER_API_KEY");

        let registry = make_registry(vec![]);
        let cmds = available_commands(&registry);
        let names: Vec<&str> = cmds.iter().map(|c| c.name.as_str()).collect();
        assert!(
            names.contains(&"openrouter-login"),
            "openrouter-login must be advertised when env is unset; got {names:?}"
        );
    }

    /// The `/configure` dump surfaces OpenRouter credential state as a
    /// read-only block so it stays discoverable even when
    /// `/openrouter-login` is hidden. Env-owned case: env_set=true,
    /// login_command_available=false, hint mentions the env var.
    #[tokio::test]
    async fn render_configure_dump_reports_env_owned_credentials() {
        use crate::openrouter_auth::test_support::{ENV_GUARD, EnvScope};
        let _lock = ENV_GUARD.lock().await;
        let tmp_cfg = tempfile::tempdir().unwrap();
        let _brokk = EnvScope::set("BROKK_CONFIG_HOME", tmp_cfg.path());
        let _env = EnvScope::set("OPENROUTER_API_KEY", "sk-or-from-env");

        let (store, id) = make_store_with_session("m").await;
        let session = store
            .get_session(&id, &std::env::temp_dir())
            .await
            .expect("session present");
        let dump = render_configure_dump(&session);

        assert!(
            dump.contains("`active_source`: `env`"),
            "dump must report env as active source; got:\n{dump}"
        );
        assert!(dump.contains("`env_set`: `true`"), "dump:\n{dump}");
        assert!(dump.contains("`file_present`: `false`"), "dump:\n{dump}");
        assert!(
            dump.contains("`login_command_available`: `false`"),
            "dump:\n{dump}"
        );
        assert!(
            dump.contains("OPENROUTER_API_KEY"),
            "hint must point at the env var when env owns: {dump}"
        );
    }

    /// File-owned case: env unset, file has a key. active_source=file,
    /// login_command_available=true, hint points at `/openrouter-login`.
    #[tokio::test]
    async fn render_configure_dump_reports_file_owned_credentials() {
        use crate::openrouter_auth::test_support::{ENV_GUARD, EnvScope};
        let _lock = ENV_GUARD.lock().await;
        let tmp_cfg = tempfile::tempdir().unwrap();
        let _brokk = EnvScope::set("BROKK_CONFIG_HOME", tmp_cfg.path());
        let _env = EnvScope::remove("OPENROUTER_API_KEY");
        crate::openrouter_auth::write(&crate::openrouter_auth::OpenRouterAuth {
            api_key: "sk-or-on-disk".to_string(),
        })
        .unwrap();

        let (store, id) = make_store_with_session("m").await;
        let session = store
            .get_session(&id, &std::env::temp_dir())
            .await
            .expect("session present");
        let dump = render_configure_dump(&session);

        assert!(
            dump.contains("`active_source`: `file`"),
            "dump must report file as active source; got:\n{dump}"
        );
        assert!(dump.contains("`env_set`: `false`"), "dump:\n{dump}");
        assert!(dump.contains("`file_present`: `true`"), "dump:\n{dump}");
        assert!(
            dump.contains("`login_command_available`: `true`"),
            "dump:\n{dump}"
        );
        assert!(
            dump.contains("/openrouter-login"),
            "hint must mention the slash when login is available: {dump}"
        );
    }

    /// No credentials at all: dump still renders, reports
    /// active_source=none, login is still available (so the user knows
    /// they CAN add a key via the slash).
    #[tokio::test]
    async fn render_configure_dump_reports_no_credentials() {
        use crate::openrouter_auth::test_support::{ENV_GUARD, EnvScope};
        let _lock = ENV_GUARD.lock().await;
        let tmp_cfg = tempfile::tempdir().unwrap();
        let _brokk = EnvScope::set("BROKK_CONFIG_HOME", tmp_cfg.path());
        let _env = EnvScope::remove("OPENROUTER_API_KEY");

        let (store, id) = make_store_with_session("m").await;
        let session = store
            .get_session(&id, &std::env::temp_dir())
            .await
            .expect("session present");
        let dump = render_configure_dump(&session);

        assert!(dump.contains("`active_source`: `none`"), "dump:\n{dump}");
        assert!(dump.contains("`env_set`: `false`"), "dump:\n{dump}");
        assert!(dump.contains("`file_present`: `false`"), "dump:\n{dump}");
        // Login is still possible, just no key registered yet.
        assert!(
            dump.contains("`login_command_available`: `true`"),
            "dump:\n{dump}"
        );
    }

    /// The handler short-circuits with the env-owned explanation for
    /// every subcommand when `OPENROUTER_API_KEY` is set. We assert the
    /// bare and `<key>` paths -- they're the ones that would mutate
    /// state if the early-return ever regressed. Status/disconnect are
    /// covered transitively by the same short-circuit.
    #[tokio::test]
    async fn handle_openrouter_login_short_circuits_when_env_owns() {
        use crate::openrouter_auth::test_support::{ENV_GUARD, EnvScope};
        let _lock = ENV_GUARD.lock().await;
        let tmp_cfg = tempfile::tempdir().unwrap();
        let _brokk = EnvScope::set("BROKK_CONFIG_HOME", tmp_cfg.path());
        let _env = EnvScope::set("OPENROUTER_API_KEY", "sk-or-from-env");

        let store = SessionStore::new("m".into());
        let llm = std::sync::Arc::new(crate::multi_backend::MultiBackend::new(None, None, None));
        let refresh = std::sync::Arc::new(tokio::sync::Mutex::new(()));

        let bare = handle_openrouter_login("/openrouter-login", &llm, &store, &refresh).await;
        let with_key =
            handle_openrouter_login("/openrouter-login sk-or-rotated", &llm, &store, &refresh)
                .await;

        for (label, msg) in [("bare", bare), ("with key", with_key)] {
            assert!(
                msg.contains("OPENROUTER_API_KEY"),
                "{label} response must explain env ownership: {msg}"
            );
            assert!(
                msg.contains("/configure"),
                "{label} response must point at /configure for diagnostics: {msg}"
            );
        }
        // And critically: no file was written despite the candidate key.
        let path = crate::openrouter_auth::auth_path().unwrap();
        assert!(
            !path.exists(),
            "env-owned mode must not persist a key on disk; file at {path:?} should not exist"
        );
    }

    #[test]
    fn build_skill_payload_wraps_body_with_resources_listing() {
        let tmp = tempfile::TempDir::new().unwrap();
        let skill_dir = tmp.path().join("demo");
        std::fs::create_dir_all(skill_dir.join("scripts")).unwrap();
        std::fs::create_dir_all(skill_dir.join("references")).unwrap();
        let location = skill_dir.join("SKILL.md");
        std::fs::write(
            &location,
            "---\nname: demo\ndescription: demo skill\n---\nDo a thing.\n",
        )
        .unwrap();
        std::fs::write(skill_dir.join("scripts").join("run.sh"), "#!/bin/sh\n").unwrap();
        std::fs::write(skill_dir.join("references").join("notes.md"), "n").unwrap();

        let meta = SkillMeta {
            name: "demo".into(),
            description: "demo skill".into(),
            location,
            skill_dir: skill_dir.clone(),
            scope: SkillScope::Project,
        };
        let payload = build_skill_payload(&meta);
        assert!(payload.starts_with("<skill_content name=\"demo\">"));
        assert!(payload.contains("Do a thing."));
        // Frontmatter must be stripped.
        assert!(!payload.contains("---\nname:"));
        // Resources listed.
        assert!(payload.contains("<file>scripts/run.sh</file>"));
        assert!(payload.contains("<file>references/notes.md</file>"));
        // Skill directory + relative-path hint present.
        assert!(payload.contains(&format!("Skill directory: {}", skill_dir.display())));
        assert!(payload.ends_with("</skill_content>"));
    }

    #[test]
    fn parse_slash_command_splits_name_and_args() {
        assert_eq!(
            parse_slash_command("/hello world"),
            Some(("hello".into(), "world".into()))
        );
        assert_eq!(
            parse_slash_command("/hello"),
            Some(("hello".into(), String::new()))
        );
        assert_eq!(
            parse_slash_command("/Hello   foo bar"),
            Some(("hello".into(), "foo bar".into()))
        );
        assert_eq!(parse_slash_command("hello"), None);
        assert_eq!(parse_slash_command("/"), None);
        assert_eq!(parse_slash_command(""), None);
    }

    /// Build a `SessionStore` with one session for the apply/render tests
    /// below. The cwd is randomized so concurrent test runs don't clobber.
    async fn make_store_with_session(default_model: &str) -> (SessionStore, String) {
        let store = SessionStore::new(default_model.to_string());
        let cwd =
            std::env::temp_dir().join(format!("brokk-acp-configure-{}", uuid::Uuid::new_v4()));
        let session = store.create_session(cwd).await;
        (store, session.id)
    }

    #[tokio::test]
    async fn apply_config_option_sets_permission_mode() {
        let (store, id) = make_store_with_session("m").await;
        let outcome = apply_config_option(&store, &id, PERMISSION_CONFIG_ID, "acceptEdits")
            .await
            .expect("permission mode update");
        assert!(outcome.cleared_reasoning.is_none());
        let pm = store.permission_mode(&id).await.expect("session present");
        assert_eq!(pm, PermissionMode::AcceptEdits);
        // updated_options must reflect the new value.
        assert!(!outcome.updated_options.is_empty());
    }

    #[tokio::test]
    async fn apply_config_option_sets_behavior_mode() {
        let (store, id) = make_store_with_session("m").await;
        apply_config_option(&store, &id, BEHAVIOR_CONFIG_ID, "PLAN")
            .await
            .expect("behavior mode update");
        let snap = store
            .snapshot(&id, &std::env::temp_dir())
            .await
            .expect("session present");
        assert_eq!(snap.mode, SessionMode::Plan);
    }

    #[tokio::test]
    async fn apply_config_option_sets_model_when_catalog_empty() {
        let (store, id) = make_store_with_session("initial").await;
        // Empty catalog must accept any id so a manually-configured
        // backend still works.
        apply_config_option(&store, &id, MODEL_CONFIG_ID, "custom/model")
            .await
            .expect("model update");
        let snap = store
            .snapshot(&id, &std::env::temp_dir())
            .await
            .expect("session present");
        assert_eq!(snap.model, "custom/model");
    }

    #[tokio::test]
    async fn apply_config_option_rejects_unknown_model_when_catalog_known() {
        let (store, id) = make_store_with_session("initial").await;
        store
            .set_available_models(vec![
                ModelMetadata::id_only("known-1"),
                ModelMetadata::id_only("known-2"),
            ])
            .await;
        let err = apply_config_option(&store, &id, MODEL_CONFIG_ID, "ghost")
            .await
            .expect_err("ghost model is not in the catalog");
        match err {
            ConfigApplyError::InvalidValue { supported, .. } => {
                assert_eq!(
                    supported,
                    vec!["known-1".to_string(), "known-2".to_string()]
                );
            }
            other => panic!("expected InvalidValue, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn apply_config_option_clears_reasoning_when_model_drops_it() {
        use crate::llm_client::ReasoningLevelPreset;
        let (store, id) = make_store_with_session("model-a").await;
        // model-a publishes a "high" preset; model-b publishes nothing,
        // so swapping to it forces the store to drop the user's pick.
        store
            .set_available_models(vec![
                ModelMetadata {
                    id: "model-a".into(),
                    default_reasoning_level: Some("high".into()),
                    supported_reasoning_levels: vec![ReasoningLevelPreset {
                        effort: "high".into(),
                        description: "High".into(),
                    }],
                },
                ModelMetadata::id_only("model-b"),
            ])
            .await;
        apply_config_option(&store, &id, REASONING_EFFORT_CONFIG_ID, "high")
            .await
            .expect("set reasoning effort");
        let outcome = apply_config_option(&store, &id, MODEL_CONFIG_ID, "model-b")
            .await
            .expect("swap model");
        assert_eq!(outcome.cleared_reasoning.as_deref(), Some("high"));
    }

    #[tokio::test]
    async fn apply_config_option_rejects_reasoning_effort_for_model_without_presets() {
        let (store, id) = make_store_with_session("plain-model").await;
        store
            .set_available_models(vec![ModelMetadata::id_only("plain-model")])
            .await;

        let err = apply_config_option(&store, &id, REASONING_EFFORT_CONFIG_ID, "high")
            .await
            .expect_err("known model without presets cannot accept reasoning effort");

        match err {
            ConfigApplyError::InvalidValue { reason, supported } => {
                assert!(reason.contains("plain-model"));
                assert!(supported.is_empty());
            }
            other => panic!("expected InvalidValue, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn apply_config_option_rejects_invalid_permission_mode() {
        let (store, id) = make_store_with_session("m").await;
        let err = apply_config_option(&store, &id, PERMISSION_CONFIG_ID, "bogus")
            .await
            .expect_err("bogus is not a permission mode");
        match err {
            ConfigApplyError::InvalidValue { reason, supported } => {
                assert!(reason.contains("bogus"));
                assert!(supported.contains(&"acceptEdits".to_string()));
            }
            other => panic!("expected InvalidValue, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn apply_config_option_rejects_unknown_key() {
        let (store, id) = make_store_with_session("m").await;
        let err = apply_config_option(&store, &id, "no_such_knob", "value")
            .await
            .expect_err("unknown key");
        assert!(matches!(err, ConfigApplyError::UnknownConfigId));
    }

    #[tokio::test]
    async fn apply_config_option_reports_unknown_session() {
        let store = SessionStore::new("m".into());
        let err = apply_config_option(&store, "no-session", PERMISSION_CONFIG_ID, "default")
            .await
            .expect_err("session does not exist");
        assert!(matches!(err, ConfigApplyError::UnknownSession));
    }

    #[test]
    fn render_configure_dump_contains_all_keys() {
        let session = Session::new(
            "id".into(),
            std::path::PathBuf::from("/tmp"),
            "model-x".into(),
            "name".into(),
        );
        let dump = render_configure_dump(&session);
        for key in CONFIGURE_KNOWN_KEYS {
            assert!(dump.contains(key), "dump missing key `{key}`: {dump}");
        }
        assert!(dump.contains("model-x"));
    }

    #[test]
    fn render_configure_single_unknown_key_lists_supported() {
        let session = Session::new(
            "id".into(),
            std::path::PathBuf::from("/tmp"),
            "model-x".into(),
            "name".into(),
        );
        let out = render_configure_single("bogus", &session);
        assert!(out.starts_with("Error:"));
        for key in CONFIGURE_KNOWN_KEYS {
            assert!(out.contains(key), "missing key `{key}` in error: {out}");
        }
    }
}
