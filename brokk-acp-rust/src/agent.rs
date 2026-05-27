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
/// (Zed, vscode) can drive model selection through one canonical name.
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

// Backend-credential / endpoint keys handled by `apply_config_option`.
// These mutate global server state (not per-session) but are surfaced
// through `/configure` so users on minimal clients have a single
// text-driven surface for every knob.
const OPENROUTER_API_KEY_CONFIG_ID: &str = "openrouter.api_key";
const OPENROUTER_HTTP_REFERER_CONFIG_ID: &str = "openrouter.http_referer";
const OPENROUTER_X_TITLE_CONFIG_ID: &str = "openrouter.x_title";
const CODEX_AUTH_CONFIG_ID: &str = "codex.auth";
const OLLAMA_ENDPOINT_CONFIG_ID: &str = "ollama.endpoint";
/// Per-session LLM SSE idle timeout. Folds the standalone `/idle-timeout`
/// slash into `/configure` so future operational knobs have one home.
const IDLE_TIMEOUT_CONFIG_ID: &str = "idle_timeout";

/// Sentinel values that clear a string-valued configuration. Accepted
/// for `openrouter.*` / `codex.auth` / `ollama.endpoint` so the wire
/// shape stays uniform with the per-session dropdown knobs.
const CONFIG_CLEAR_VALUES: &[&str] = &["", "default", "clear"];

/// Wire ids accepted by `apply_config_option`. Kept in a single slice so
/// every caller (the `setSessionConfigOption` request handler and the
/// `/configure` slash command) reports identical "supported keys" lists.
///
/// The first four are also advertised structurally via `all_config_options`
/// (they fit the ACP `SessionConfigOption::select(...)` shape).
///
/// The remaining six (added by #3610) are **text-only on the `/configure`
/// surface**. The ACP `SessionConfigOption` schema in agent-client-protocol
/// 0.12 only stably supports `Select`; there is no free-form text or
/// numeric input variant (the upstream comment in the crate explicitly
/// says "a future freeform text option would get its own variant" — i.e.
/// it doesn't exist yet). A client that knows the wire id can still drive
/// them through the `setSessionConfigOption` RPC (the apply path accepts
/// them), but they will not appear in the `ConfigOptionUpdate` notification
/// the apply path emits — only the four `Select`-shaped knobs do.
const CONFIGURE_KNOWN_KEYS: &[&str] = &[
    BEHAVIOR_CONFIG_ID,
    PERMISSION_CONFIG_ID,
    MODEL_CONFIG_ID,
    REASONING_EFFORT_CONFIG_ID,
    OPENROUTER_API_KEY_CONFIG_ID,
    OPENROUTER_HTTP_REFERER_CONFIG_ID,
    OPENROUTER_X_TITLE_CONFIG_ID,
    CODEX_AUTH_CONFIG_ID,
    OLLAMA_ENDPOINT_CONFIG_ID,
    IDLE_TIMEOUT_CONFIG_ID,
];

/// Returns true when `value` (already trimmed by the caller) is a
/// sentinel that clears a string-valued configuration key.
fn is_config_clear(value: &str) -> bool {
    let lowered = value.to_ascii_lowercase();
    CONFIG_CLEAR_VALUES.iter().any(|v| *v == lowered)
}

/// Read the active OpenRouter key with the canonical env > file
/// precedence. Returns `None` when no usable key is available.
///
/// Single source of truth for "where does the key come from": shared by
/// `/configure openrouter.http_referer` / `openrouter.x_title` (which
/// rebuild the backend so an attribution change takes effect without a
/// restart), the `render_configure_dump` length probe, and the startup
/// `build_openrouter_backend` in `main.rs` so all three converge on the
/// same env-empty / file-empty / file-missing handling.
pub(crate) fn current_openrouter_key() -> Option<String> {
    if let Ok(raw) = std::env::var(crate::discovery::OPENROUTER_API_KEY_ENV) {
        let trimmed = raw.trim();
        if !trimmed.is_empty() {
            return Some(trimmed.to_string());
        }
    }
    crate::openrouter_auth::read()
        .ok()
        .flatten()
        .map(|a| a.api_key.trim().to_string())
        .filter(|k| !k.is_empty())
}

/// Redact secret-valued keys for `/configure` echo and `tracing::info!`
/// lines. Mirrors the `key length={}` shape used by the startup logger
/// in `main.rs` so logs are consistent regardless of how the key got
/// set. Non-secret keys are passed through verbatim.
fn redact_config_value(key: &str, value: &str) -> String {
    match key {
        OPENROUTER_API_KEY_CONFIG_ID | CODEX_AUTH_CONFIG_ID => {
            let trimmed = value.trim();
            if trimmed.is_empty() {
                "(cleared)".to_string()
            } else {
                format!("(length={})", trimmed.len())
            }
        }
        _ => format!("`{value}`"),
    }
}

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
///
/// `llm` and `refresh_lock` are threaded through so backend-credential
/// keys (`openrouter.*`, `codex.auth`, `ollama.endpoint`) can install /
/// uninstall the in-memory backend and re-trigger discovery, mirroring
/// the `/codex-login` and `/openrouter-login` post-install path.
async fn apply_config_option(
    sessions: &SessionStore,
    llm: &Arc<MultiBackend>,
    refresh_lock: &Arc<tokio::sync::Mutex<()>>,
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
        OPENROUTER_API_KEY_CONFIG_ID => {
            // Env owns the credential lifecycle when OPENROUTER_API_KEY is
            // set: refuse to mutate, mirroring `/openrouter-login` so the
            // two surfaces stay in agreement (and a mid-session set can't
            // pretend to override what `main.rs` baked in at startup).
            if crate::openrouter_auth::CredentialState::snapshot().env_owns() {
                return Err(ConfigApplyError::InvalidValue {
                    reason: openrouter_env_owned_explanation(),
                    supported: Vec::new(),
                });
            }
            let trimmed = value.trim();
            if is_config_clear(trimmed) {
                if let Err(e) = crate::openrouter_auth::logout() {
                    return Err(ConfigApplyError::PersistFailed {
                        details: format!("{e:#}"),
                    });
                }
                llm.uninstall_openrouter();
                tracing::info!(
                    session_id = %session_id,
                    "OpenRouter credentials cleared via /configure; backend uninstalled"
                );
                spawn_throttled_refresh(refresh_lock.clone(), llm.clone(), sessions.clone());
            } else {
                if let Err(e) =
                    crate::openrouter_auth::write(&crate::openrouter_auth::OpenRouterAuth {
                        api_key: trimmed.to_string(),
                    })
                {
                    return Err(ConfigApplyError::PersistFailed {
                        details: format!("{e:#}"),
                    });
                }
                let attribution = llm.openrouter_attribution_snapshot();
                match crate::openrouter_backend_from_key(trimmed, &attribution) {
                    Some(backend) => {
                        llm.install_openrouter(backend);
                        tracing::info!(
                            session_id = %session_id,
                            key_length = trimmed.len(),
                            "OpenRouter backend installed via /configure"
                        );
                        spawn_throttled_refresh(
                            refresh_lock.clone(),
                            llm.clone(),
                            sessions.clone(),
                        );
                    }
                    None => {
                        // `openrouter_backend_from_key` only returns None
                        // for an empty key (filtered above) or an invalid
                        // header value (attribution was validated when set).
                        // Hitting this arm means a defensive guardrail
                        // tripped; back out the on-disk write to keep state
                        // consistent and surface a clear error. If the
                        // rollback itself fails (rare: perm changed under
                        // us, filesystem race), say so explicitly so the
                        // user knows to clean up manually.
                        let rollback_msg = match crate::openrouter_auth::logout() {
                            Ok(()) => String::new(),
                            Err(e) => format!(
                                " Additionally, the on-disk credential could not \
                                 be rolled back: {e:#}. You may need to manually \
                                 remove the OpenRouter credential file."
                            ),
                        };
                        return Err(ConfigApplyError::InvalidValue {
                            reason: format!(
                                "OpenRouter backend rejected the supplied key \
                                 (empty after trim or invalid attribution header).{rollback_msg}"
                            ),
                            supported: Vec::new(),
                        });
                    }
                }
            }
        }
        OPENROUTER_HTTP_REFERER_CONFIG_ID => {
            apply_openrouter_attribution_change(
                llm,
                refresh_lock,
                sessions,
                AttributionField::HttpReferer,
                value,
            )?;
        }
        OPENROUTER_X_TITLE_CONFIG_ID => {
            apply_openrouter_attribution_change(
                llm,
                refresh_lock,
                sessions,
                AttributionField::XTitle,
                value,
            )?;
        }
        CODEX_AUTH_CONFIG_ID => {
            // The sign-in flow still lives in `/codex-login` (it owns the
            // OAuth callback server and PKCE state); `/configure codex.auth`
            // is the clear/invalidate side of that contract.
            if !is_config_clear(value.trim()) {
                return Err(ConfigApplyError::InvalidValue {
                    reason: format!(
                        "unsupported `codex.auth` value `{value}`; pass `clear` (or empty / `default`) \
                         to invalidate ~/.codex/auth.json, then run `/codex-login` to sign back in"
                    ),
                    supported: CONFIG_CLEAR_VALUES
                        .iter()
                        .map(|s| (*s).to_string())
                        .collect(),
                });
            }
            if let Err(e) = crate::codex_auth::logout() {
                return Err(ConfigApplyError::PersistFailed {
                    details: format!("{e:#}"),
                });
            }
            llm.uninstall_codex();
            tracing::info!(
                session_id = %session_id,
                "Codex credentials cleared via /configure; backend uninstalled"
            );
            spawn_throttled_refresh(refresh_lock.clone(), llm.clone(), sessions.clone());
        }
        OLLAMA_ENDPOINT_CONFIG_ID => {
            let trimmed = value.trim();
            // Empty / "default" resets to the discovery probe URL, which
            // is the same as the server-startup behaviour. Any other
            // value must look like an http(s) URL -- we don't fully parse
            // it (the discovery client will error loudly on a bad URL on
            // the next probe), but the prefix check catches obvious typos
            // before they take the picker offline. We also reject control
            // characters so a user-supplied URL can't smuggle CRLF into
            // the structured log line below.
            let new_url = if is_config_clear(trimmed) {
                crate::discovery::OLLAMA_DEFAULT_URL.to_string()
            } else if trimmed.contains(|c: char| c.is_control()) {
                return Err(ConfigApplyError::InvalidValue {
                    reason: "Ollama endpoint URL must not contain control characters \
                             (newline, tab, etc.)"
                        .to_string(),
                    supported: Vec::new(),
                });
            } else if trimmed.starts_with("http://") || trimmed.starts_with("https://") {
                trimmed.trim_end_matches('/').to_string()
            } else {
                return Err(ConfigApplyError::InvalidValue {
                    reason: format!(
                        "`{trimmed}` is not a valid Ollama endpoint (expected an http:// or https:// URL)"
                    ),
                    supported: Vec::new(),
                });
            };
            llm.set_ollama_config(crate::multi_backend::OllamaConfig {
                url: new_url.clone(),
            });
            llm.install_ollama(crate::build_ollama_backend(&new_url));
            tracing::info!(
                session_id = %session_id,
                url = %new_url,
                "Ollama endpoint set via /configure"
            );
            spawn_throttled_refresh(refresh_lock.clone(), llm.clone(), sessions.clone());
        }
        IDLE_TIMEOUT_CONFIG_ID => {
            let secs = parse_idle_timeout_config_value(value).map_err(|reason| {
                ConfigApplyError::InvalidValue {
                    reason,
                    supported: Vec::new(),
                }
            })?;
            if !sessions.set_idle_timeout_secs(session_id, secs).await {
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

/// Which side of the OpenRouter attribution pair a `/configure` call is
/// touching. Kept private since the only caller is
/// `apply_openrouter_attribution_change`.
#[derive(Debug, Clone, Copy)]
enum AttributionField {
    HttpReferer,
    XTitle,
}

/// Mutate one side of the OpenRouter attribution pair and rebuild the
/// installed backend so the new header takes effect on the next request.
///
/// The attribution values are baked into the `reqwest::Client` defaults
/// at `OpenAiClient` construction, so we cannot just update the cell and
/// hope -- the live backend would keep sending the old headers until a
/// restart. We pre-validate the new value as a header to keep a typo
/// from quietly taking the backend down, mutate the cell, and (when a
/// key is available) build a fresh backend and `install_openrouter` it.
fn apply_openrouter_attribution_change(
    llm: &Arc<MultiBackend>,
    refresh_lock: &Arc<tokio::sync::Mutex<()>>,
    sessions: &SessionStore,
    field: AttributionField,
    value: &str,
) -> Result<(), ConfigApplyError> {
    let mut attribution = llm.openrouter_attribution_snapshot();
    let trimmed = value.trim();
    let new_value = if is_config_clear(trimmed) {
        match field {
            AttributionField::HttpReferer => {
                crate::multi_backend::OpenRouterAttribution::DEFAULT_HTTP_REFERER.to_string()
            }
            AttributionField::XTitle => {
                crate::multi_backend::OpenRouterAttribution::DEFAULT_X_TITLE.to_string()
            }
        }
    } else {
        trimmed.to_string()
    };
    if reqwest::header::HeaderValue::from_str(&new_value).is_err() {
        return Err(ConfigApplyError::InvalidValue {
            reason: format!("`{new_value}` is not a valid HTTP header value"),
            supported: Vec::new(),
        });
    }
    let field_name = match field {
        AttributionField::HttpReferer => {
            attribution.http_referer = new_value.clone();
            "http_referer"
        }
        AttributionField::XTitle => {
            attribution.x_title = new_value.clone();
            "x_title"
        }
    };
    llm.set_openrouter_attribution(attribution.clone());
    tracing::info!(
        field = field_name,
        new_value = %new_value,
        "OpenRouter attribution updated via /configure"
    );
    // Rebuild and reinstall if a key is currently available, so the new
    // header takes effect without a restart. When no key is configured
    // the next install (via /openrouter-login or /configure openrouter.api_key)
    // will pick up the updated attribution naturally.
    if let Some(key) = current_openrouter_key() {
        if let Some(backend) = crate::openrouter_backend_from_key(&key, &attribution) {
            llm.install_openrouter(backend);
            spawn_throttled_refresh(refresh_lock.clone(), llm.clone(), sessions.clone());
        } else {
            tracing::debug!(
                "OpenRouter backend rebuild skipped after attribution update: \
                 key resolved but `openrouter_backend_from_key` returned None"
            );
        }
    } else {
        tracing::debug!(
            "OpenRouter backend rebuild skipped after attribution update: no key configured"
        );
    }
    Ok(())
}

/// Validate a numeric idle-timeout candidate against the shared bounds.
/// Single source of truth for the bounds + "out of range" error
/// wording, shared by `/configure idle_timeout` and the legacy
/// `/idle-timeout` slash command so the two surfaces don't drift.
///
/// Caller is responsible for handling clear sentinels and dispatching on
/// the parse result -- the helper only deals with the numeric core.
fn validate_idle_timeout_secs(token: &str) -> Result<u64, String> {
    let min = crate::llm_client::MIN_IDLE_CHUNK_TIMEOUT_SECS;
    let max = crate::llm_client::MAX_IDLE_CHUNK_TIMEOUT_SECS;
    match token.parse::<u64>() {
        Ok(secs) if (min..=max).contains(&secs) => Ok(secs),
        Ok(out_of_range) => Err(format!(
            "Value `{out_of_range}` is out of range. Pick a value between \
             {min}s and {max}s, or use `default` to clear the override."
        )),
        Err(_) => Err(format!(
            "Unknown value `{token}`. Pick a number between {min}s and {max}s, \
             or use `default` to clear the override."
        )),
    }
}

/// Parse the value side of `/configure idle_timeout <value>`. Returns
/// `Ok(None)` for clear sentinels (empty / "default" / "clear") and
/// `Ok(Some(secs))` for a valid in-range numeric. Bounds and error
/// wording come from `validate_idle_timeout_secs` so the new surface
/// matches `/idle-timeout` even after that slash is removed.
fn parse_idle_timeout_config_value(value: &str) -> Result<Option<u64>, String> {
    let trimmed = value.trim();
    if is_config_clear(trimmed) {
        return Ok(None);
    }
    validate_idle_timeout_secs(trimmed).map(Some)
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
        "DEPRECATED: use `/configure idle_timeout <secs>` instead",
    ));
    commands.push(AvailableCommand::new(
        "configure",
        "Show or change session settings (e.g. `/configure model_selection gpt-5`)",
    ));
    commands.push(AvailableCommand::new(
        "pr-create",
        "Create a GitHub pull request from the current branch (e.g. `/pr-create [title]`)",
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
        "pr-create",
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

/// Defer the `available_commands_update` notification so the client has
/// time to register the freshly-issued session id before the
/// notification references it.
///
/// History: #3611 fixed the same symptom by responding to `session/new`
/// *before* sending this notification, relying on the
/// agent-client-protocol crate's single FIFO outbound channel. That
/// ordered the two messages correctly on the wire. The bug has come
/// back because of how Zed dispatches incoming traffic: its
/// `new_session` handler (zed `crates/agent_servers/src/acp.rs`) inserts
/// the session into `self.sessions` only *after* the `session/new`
/// response future resolves and follow-up work runs (default
/// `SetSessionMode` / `SetSessionModel` RPCs, default-config-option
/// application, `AcpThread::new`). The response and any notification on
/// the same session arrive on Zed as two independent dispatch tasks;
/// the notification handler can be polled in the window between the
/// response future resolving and `sessions.borrow_mut().insert(...)`,
/// and is dropped with `Received session notification for unknown
/// session`. Symptom: the command palette stays empty even though the
/// wire order matches #3611.
///
/// `session/load` and `session/resume` go through Zed's
/// `open_or_create_session`, which *pre*-registers the session id before
/// awaiting the RPC (the client knows the id up front on those paths).
/// `session/new` cannot pre-register because the id is issued by the
/// server in the response, so it stays exposed to this race.
///
/// Wire-order alone is not enough. We send the notification from a
/// short-delay tokio task so it lands on Zed *after* Zed's post-response
/// bookkeeping has run and the session id is in the map. ~100ms is
/// invisible to a human at the command palette and well above the
/// post-response sync work measured locally. Applied symmetrically to
/// new/load/resume so a future Zed refactor that reshapes a
/// load/resume path can't silently re-introduce the regression.
fn spawn_delayed_available_commands_update(
    cx: ConnectionTo<Client>,
    session_id: String,
    skills: Arc<crate::skills::SkillRegistry>,
) {
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(100)).await;
        send_available_commands_update(&cx, &session_id, &skills);
    });
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
    // pile up redundant probes against /v1/models and /codex/models. We
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
    // `setSessionConfigOption` now mutates backend slots on `MultiBackend`
    // (openrouter.api_key, codex.auth, ollama.endpoint, openrouter.http_referer,
    // openrouter.x_title) and triggers discovery refresh, so it needs
    // dedicated clones of the backend handle and the refresh throttle.
    let llm_cfg = llm.clone();
    let refresh_lock_cfg = refresh_lock.clone();

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

                // Respond first so the client receives the session id, then
                // schedule the available-commands notification on a short
                // delay so it lands on Zed *after* its `new_session` handler
                // has inserted the session id into its sessions map. See
                // `spawn_delayed_available_commands_update` for the full
                // rationale (FIFO wire order alone is not enough on the
                // session/new path).
                let result = responder.respond(response);
                spawn_delayed_available_commands_update(
                    cx.clone(),
                    session.id.clone(),
                    session.skills.clone(),
                );
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
                spawn_delayed_available_commands_update(
                    cx.clone(),
                    session_id.clone(),
                    session.skills.clone(),
                );
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
                        spawn_delayed_available_commands_update(
                            cx.clone(),
                            session_id.clone(),
                            session.skills.clone(),
                        );
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
                    let report = handle_configure(
                        &cx,
                        &prompt_text,
                        &session_id,
                        &sessions_prompt,
                        &llm_login,
                        &refresh_lock_login,
                        snap.idle_timeout_secs,
                        default_idle_timeout_secs,
                    )
                    .await;
                    send_message(&cx, &session_id, &report);
                    return responder.respond(PromptResponse::new(StopReason::EndTurn));
                }

                if is_slash_command(&prompt_text, "pr-create") {
                    let permission_mode = sessions_prompt
                        .permission_mode(&session_id)
                        .await
                        .unwrap_or(PermissionMode::Default);
                    // Reuse the per-session ToolRegistry so shell calls
                    // route through the same `runShellCommand` dispatch
                    // (env scrub, sandbox, rlimits) the LLM tool path
                    // uses. The registry is created on demand if this is
                    // the session's first prompt.
                    let registry = sessions_prompt
                        .get_or_create_registry(
                            &session_id,
                            snap.cwd.clone(),
                            bifrost_binary_prompt.as_deref(),
                        )
                        .await;
                    let report =
                        handle_pr_create(&prompt_text, &registry, permission_mode).await;
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
                // Route the value through the redactor so a setConfigOption
                // RPC carrying `openrouter.api_key` or `codex.auth` does not
                // dump the secret into stderr / journald / log aggregators.
                // The redactor is a no-op for non-secret keys, so the log
                // line is unchanged for behavior_mode / permission_mode /
                // model_selection / reasoning_effort / idle_timeout / etc.
                tracing::info!(
                    "ACP set_config_option session={session_id} config={config_id} value={}",
                    redact_config_value(&config_id, &value)
                );

                let outcome = match apply_config_option(
                    &sessions_perm,
                    &llm_cfg,
                    &refresh_lock_cfg,
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
                Ok(()) => match crate::openrouter_backend_from_key(
                    &key,
                    &llm.openrouter_attribution_snapshot(),
                ) {
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

    match arg.as_str() {
        "" => Ok(IdleTimeoutAction::Show),
        "default" => Ok(IdleTimeoutAction::Clear),
        other => {
            // Numeric token: route through the shared bounds helper so
            // `/idle-timeout 999999` and `/configure idle_timeout 999999`
            // emit the identical out-of-range message. Non-numeric junk
            // keeps the historical "Unknown subcommand" wording (the
            // /configure surface treats it as a value, not a subcommand).
            if other.parse::<u64>().is_ok() {
                validate_idle_timeout_secs(other).map(IdleTimeoutAction::Set)
            } else {
                Err(format!(
                    "Unknown subcommand `{other}`. Try: /idle-timeout | \
                     /idle-timeout <secs> | /idle-timeout default"
                ))
            }
        }
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
    // Emit a server-side warn so operators can measure how many sessions
    // still hit the deprecated slash before flipping the removal switch.
    // Cheap (one log per invocation) and lets us avoid guessing about
    // adoption when the time comes.
    tracing::warn!(
        session_id = %session_id,
        "deprecated /idle-timeout invoked; use /configure idle_timeout instead"
    );
    let action = match parse_idle_timeout_arg(prompt_text) {
        Ok(action) => action,
        Err(msg) => return msg,
    };
    // Deprecation notice prepended to every successful path so users
    // pivot to `/configure idle_timeout` while the slash still works.
    // Removal is scheduled for the next release; the apply path is now
    // also reachable through `apply_config_option` so the two surfaces
    // can be flipped without changing session-store wiring.
    let deprecation =
        "Note: `/idle-timeout` is deprecated; use `/configure idle_timeout <secs>` instead.\n\n";
    match action {
        IdleTimeoutAction::Show => match current_session_override {
            Some(secs) => format!(
                "{deprecation}LLM idle timeout: {secs}s (session override).\n\
                 Server default is {default_secs}s. Use `/configure idle_timeout default` \
                 to clear, or `/configure idle_timeout <secs>` to change."
            ),
            None => format!(
                "{deprecation}LLM idle timeout: {default_secs}s (server default).\n\
                 Use `/configure idle_timeout <secs>` to override for this session only, \
                 or restart with `--llm-idle-timeout-secs` / `BROKK_ACP_LLM_IDLE_TIMEOUT_SECS` \
                 to change the default."
            ),
        },
        IdleTimeoutAction::Clear => {
            if sessions.set_idle_timeout_secs(session_id, None).await {
                format!(
                    "{deprecation}Cleared session override. LLM idle timeout is back to the \
                     server default ({default_secs}s)."
                )
            } else {
                format!("{deprecation}Error: unknown session.")
            }
        }
        IdleTimeoutAction::Set(secs) => {
            if sessions.set_idle_timeout_secs(session_id, Some(secs)).await {
                format!(
                    "{deprecation}LLM idle timeout set to {secs}s for this session. \
                     In-memory only -- reload or restart resets to the server \
                     default ({default_secs}s)."
                )
            } else {
                format!("{deprecation}Error: unknown session.")
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
// `handle_configure` is the orchestration point for the `/configure`
// slash command and unavoidably stitches together every collaborator
// the apply / render paths touch: the ACP connection, the session store
// (for per-session knobs), the backend handle and refresh lock (for the
// backend-credential keys added in #3610), plus current/default idle
// timeout values that drive the `idle_timeout` row in the dump. Bundling
// them into a context record would just push the boilerplate one layer
// away without making the call sites clearer.
#[allow(clippy::too_many_arguments)]
async fn handle_configure(
    cx: &ConnectionTo<Client>,
    prompt_text: &str,
    session_id: &str,
    sessions: &SessionStore,
    llm: &Arc<MultiBackend>,
    refresh_lock: &Arc<tokio::sync::Mutex<()>>,
    current_idle_timeout_secs: Option<u64>,
    default_idle_timeout_secs: u64,
) -> String {
    let trimmed = slash_command_args(prompt_text);

    // No args, or a single token: show current state.
    if trimmed.is_empty() || trimmed.split_whitespace().count() == 1 {
        let fallback_cwd = std::env::current_dir().unwrap_or_default();
        let Some(session) = sessions.get_session(session_id, &fallback_cwd).await else {
            return "Error: unknown session.".to_string();
        };
        if trimmed.is_empty() {
            return render_configure_dump(
                &session,
                llm,
                current_idle_timeout_secs,
                default_idle_timeout_secs,
            );
        }
        return render_configure_single(
            &trimmed,
            &session,
            llm,
            current_idle_timeout_secs,
            default_idle_timeout_secs,
        );
    }

    let mut parts = trimmed.splitn(2, char::is_whitespace);
    let key = parts.next().unwrap_or("");
    let value = parts.next().unwrap_or("").trim();

    match apply_config_option(sessions, llm, refresh_lock, session_id, key, value).await {
        Ok(outcome) => {
            let notification = SessionNotification::new(
                session_id.to_string(),
                SessionUpdate::ConfigOptionUpdate(ConfigOptionUpdate::new(outcome.updated_options)),
            );
            if let Err(e) = cx.send_notification(notification) {
                tracing::warn!("failed to send config_option_update from /configure: {e}");
            }
            let mut msg = format!("Set `{key}` to {}.", redact_config_value(key, value));
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

/// Trimmed args for a slash command. Returns the empty string when the
/// prompt is not a slash command at all, or when the command has no
/// trailing args. Shared between `handle_configure` and
/// `parse_pr_create_arg` -- both want "args after the command name,
/// trimmed of surrounding whitespace".
fn slash_command_args(prompt_text: &str) -> String {
    parse_slash_command(prompt_text)
        .map(|(_, a)| a)
        .unwrap_or_default()
        .trim()
        .to_string()
}

/// Parse the optional title from `/pr-create [title]`. Whitespace-only
/// arguments collapse to `None` so `gh pr create --fill` derives the title
/// from commit messages instead.
fn parse_pr_create_arg(prompt_text: &str) -> Option<String> {
    let trimmed = slash_command_args(prompt_text);
    if trimmed.is_empty() {
        None
    } else {
        Some(trimmed)
    }
}

/// Quote a string for `sh -c` by wrapping in single quotes and
/// escaping any embedded single quote via the standard `'\''` trick.
/// `runShellCommand` invokes `sh -c` with a single argv element, so
/// command parts that come from user input (PR title) or external
/// lookups (default branch name) need shell-safe quoting.
fn shell_single_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}

/// Per-shell-call timeout for slash-command-driven `runShellCommand`
/// invocations. Generous enough for `gh pr create` over a slow link
/// without leaving a stuck child for minutes.
const HANDLER_SHELL_TIMEOUT_SECS: u64 = 60;

/// Run `cmd` via `runShellCommand` on the per-session `ToolRegistry`
/// and return its stdout/stderr blob on success, or a pre-formatted
/// `Error: ...` string on failure. `label` is the short command name
/// shown in the error message.
async fn run_or_report(
    registry: &crate::tools::ToolRegistry,
    cmd: &str,
    label: &str,
    policy: crate::tools::sandbox::SandboxPolicy,
) -> Result<String, String> {
    let result = registry
        .execute(
            "runShellCommand",
            serde_json::json!({ "command": cmd, "timeoutSeconds": HANDLER_SHELL_TIMEOUT_SECS }),
            policy,
        )
        .await;
    if matches!(result.status, crate::tools::ToolStatus::Success) {
        Ok(result.output)
    } else {
        Err(format!("Error: `{label}` failed.\n\n{}", result.output))
    }
}

/// Handle the `/pr-create` slash command. Creates a GitHub pull request
/// from the current branch by shelling out to `gh pr create`.
///
/// Flow (each step short-circuits with a user-facing error on failure):
///   1. Refuse on `PermissionMode::ReadOnly` -- git push won't be allowed
///      under the resulting sandbox tier.
///   2. Refuse if `git status --porcelain` is non-empty so we never push
///      with uncommitted state.
///   3. Refuse if the branch has no upstream and instruct the user to
///      push manually. We deliberately do NOT auto-push: the choice of
///      which remote to push to is meaningful in fork-based workflows
///      (`origin` may be the user's personal fork OR the upstream repo)
///      and a server-side handler should not make that call silently.
///   4. Detect the repository's default branch via `gh repo view` and
///      pass it explicitly to `--base`.
///   5. Invoke `gh pr create --base <default> --fill [--title <user-arg>]`
///      and surface the resulting PR URL.
///
/// All shell calls go through `ToolRegistry::execute("runShellCommand")`
/// so they share the LLM tool path's env scrubbing, sandbox policy,
/// rlimits, and output truncation. The user typed `/pr-create`, so the
/// `consult_gate` step the LLM path requires is unnecessary -- the
/// slash command itself is the user's consent.
///
/// Notes:
///   - `gh` falls back to `~/.config/gh/hosts.yml` for auth; `GH_TOKEN`
///     and `GITHUB_TOKEN` are scrubbed from the child env, so users who
///     rely on env-var auth must `gh auth login` first.
async fn handle_pr_create(
    prompt_text: &str,
    registry: &crate::tools::ToolRegistry,
    permission_mode: PermissionMode,
) -> String {
    if matches!(permission_mode, PermissionMode::ReadOnly) {
        return "Error: `/pr-create` is disabled in read-only permission mode. \
                Switch to `default`, `acceptEdits`, or `bypassPermissions` to \
                create PRs."
            .to_string();
    }

    let policy = crate::tools::sandbox::SandboxPolicy::from_permission_mode(permission_mode);

    let status = match run_or_report(
        registry,
        "git status --porcelain",
        "git status --porcelain",
        policy,
    )
    .await
    {
        Ok(o) => o,
        Err(e) => return e,
    };
    let dirty = status.trim();
    if !dirty.is_empty() {
        return format!(
            "Error: working tree is dirty. Commit or stash these paths before \
             running `/pr-create`:\n\n{dirty}"
        );
    }

    // No-upstream check. Failure of `git rev-parse @{u}` is the trigger
    // for the "no upstream" branch -- it can also fire for unrelated
    // git errors (detached HEAD, corrupt refs), but the user-facing
    // remediation is the same: push manually and re-run.
    let upstream = registry
        .execute(
            "runShellCommand",
            serde_json::json!({
                "command": "git rev-parse --abbrev-ref --symbolic-full-name @{u}",
                "timeoutSeconds": HANDLER_SHELL_TIMEOUT_SECS,
            }),
            policy,
        )
        .await;
    if !matches!(upstream.status, crate::tools::ToolStatus::Success) {
        let remotes = run_or_report(registry, "git remote -v", "git remote -v", policy)
            .await
            .unwrap_or_else(|e| e);
        return format!(
            "Error: this branch has no upstream. Push it manually and re-run \
             `/pr-create` -- the choice of remote is yours, not the server's.\n\n\
             Try: `git push -u <remote> HEAD`\n\n\
             Detected remotes:\n{remotes}"
        );
    }

    let base = match run_or_report(
        registry,
        "gh repo view --json defaultBranchRef --jq .defaultBranchRef.name",
        "gh repo view",
        policy,
    )
    .await
    {
        Ok(o) => o,
        Err(e) => {
            return format!("{e}\n\nIs `gh` installed and authenticated (`gh auth login`)?");
        }
    };
    let base_branch = base.trim();
    if base_branch.is_empty() {
        return "Error: `gh repo view` returned an empty default branch name.".to_string();
    }

    let title_arg = match parse_pr_create_arg(prompt_text) {
        Some(t) => format!(" --title {}", shell_single_quote(&t)),
        None => String::new(),
    };
    let cmd = format!(
        "gh pr create --base {} --fill{title_arg}",
        shell_single_quote(base_branch)
    );
    match run_or_report(registry, &cmd, "gh pr create", policy).await {
        Ok(output) => {
            // `gh pr create` prints the PR URL on stdout. Surface it
            // prominently; combined output may also contain a "Creating
            // pull request..." line on stderr that we keep below.
            let url = output
                .lines()
                .map(str::trim)
                .find(|l| l.starts_with("https://") && l.contains("/pull/"))
                .unwrap_or("");
            if url.is_empty() {
                format!(
                    "Pull request created against `{base_branch}`, but the URL \
                     could not be parsed from `gh`'s output. Raw output:\n\n{output}"
                )
            } else {
                format!("Pull request created against `{base_branch}`:\n\n{url}")
            }
        }
        Err(e) => e,
    }
}

/// Resolve the active OpenRouter key length without ever moving the
/// raw secret out of this function. Used by the `/configure` dump and
/// per-key render to print `length=N` / `(none)` without exposing the
/// key itself -- mirrors the redaction shape used by the startup
/// logger in `main.rs`.
fn openrouter_active_key_length() -> Option<usize> {
    current_openrouter_key().map(|k| k.trim().len())
}

/// Brief, non-secret summary of the on-disk Codex auth state. Mirrors
/// `/codex-login status` at a coarser granularity so the dump stays
/// scannable -- the full status surface lives on its own slash command.
fn codex_auth_state_summary() -> String {
    match crate::codex_auth::read_auth_dot_json() {
        Ok(Some(auth)) => {
            let mode = auth.auth_mode.as_deref().unwrap_or("(unset)");
            let has_key = auth.openai_api_key.is_some();
            let acct = auth
                .tokens
                .as_ref()
                .map(|t| t.account_id.as_str())
                .unwrap_or("(none)");
            format!("present (auth_mode={mode}, api_key={has_key}, account_id={acct})")
        }
        Ok(None) => "absent".to_string(),
        Err(e) => format!("error reading ~/.codex/auth.json: {e:#}"),
    }
}

/// Format the current value for a single config key as a markdown line.
/// Returns `None` for unknown keys (the per-key path turns that into
/// the same error message `apply_config_option` would produce).
fn format_config_value_line(
    key: &str,
    session: &Session,
    llm: &Arc<MultiBackend>,
    current_idle_timeout_secs: Option<u64>,
    default_idle_timeout_secs: u64,
) -> Option<String> {
    let attribution = llm.openrouter_attribution_snapshot();
    let ollama_cfg = llm.ollama_config_snapshot();
    let or_state = crate::openrouter_auth::CredentialState::snapshot();
    let line = match key {
        BEHAVIOR_CONFIG_ID => format!("- `{key}`: `{}`", session.mode.as_str()),
        PERMISSION_CONFIG_ID => format!("- `{key}`: `{}`", session.permission_mode.as_str()),
        MODEL_CONFIG_ID => format!("- `{key}`: `{}`", session.model),
        REASONING_EFFORT_CONFIG_ID => format!(
            "- `{key}`: `{}`",
            session
                .selected_reasoning_effort
                .clone()
                .unwrap_or_else(|| REASONING_EFFORT_DEFAULT_VALUE.to_string())
        ),
        OPENROUTER_API_KEY_CONFIG_ID => {
            let owner = if or_state.env_owns() {
                " (env-owned: set via OPENROUTER_API_KEY)"
            } else {
                ""
            };
            let length = openrouter_active_key_length()
                .map(|n| format!("length={n}"))
                .unwrap_or_else(|| "absent".to_string());
            format!(
                "- `{key}`: {length} (source={}){owner}",
                or_state.active_source()
            )
        }
        OPENROUTER_HTTP_REFERER_CONFIG_ID => {
            format!("- `{key}`: `{}`", attribution.http_referer)
        }
        OPENROUTER_X_TITLE_CONFIG_ID => {
            format!("- `{key}`: `{}`", attribution.x_title)
        }
        CODEX_AUTH_CONFIG_ID => format!("- `{key}`: {}", codex_auth_state_summary()),
        OLLAMA_ENDPOINT_CONFIG_ID => format!("- `{key}`: `{}`", ollama_cfg.url),
        IDLE_TIMEOUT_CONFIG_ID => match current_idle_timeout_secs {
            Some(secs) => format!(
                "- `{key}`: `{secs}s` (session override; server default {default_idle_timeout_secs}s)"
            ),
            None => format!(
                "- `{key}`: `{default_idle_timeout_secs}s` (server default; no session override)"
            ),
        },
        _ => return None,
    };
    Some(line)
}

/// Render the `/configure` dump of every known knob. Mirrors
/// `CONFIGURE_KNOWN_KEYS` exactly so the text view never drifts from
/// the structured view.
///
/// Secret keys (`openrouter.api_key`, `codex.auth`) are summarised with
/// state + key-length / auth_mode, never the raw value -- matches the
/// redaction in `tracing::info!` and `handle_configure`'s success reply
/// so a `/configure` dump in a shared transcript stays safe.
fn render_configure_dump(
    session: &Session,
    llm: &Arc<MultiBackend>,
    current_idle_timeout_secs: Option<u64>,
    default_idle_timeout_secs: u64,
) -> String {
    let mut out = String::from("**Configuration**\n\n");
    for key in CONFIGURE_KNOWN_KEYS {
        if let Some(line) = format_config_value_line(
            key,
            session,
            llm,
            current_idle_timeout_secs,
            default_idle_timeout_secs,
        ) {
            out.push_str(&line);
            out.push('\n');
        }
    }
    let or_state = crate::openrouter_auth::CredentialState::snapshot();
    let login_hint = if or_state.env_owns() {
        "`/openrouter-login` and `/configure openrouter.api_key` are both \
         disabled because `OPENROUTER_API_KEY` owns the credential lifecycle. \
         Unset the env var and restart the server to manage credentials from \
         a session."
    } else {
        "Manage credentials with `/configure openrouter.api_key <key>` | `clear`, \
         or with `/openrouter-login` for parity with older flows."
    };
    out.push('\n');
    out.push_str(&format!(
        "Set a value with `/configure <key> <value>`. Supported keys: {}.\n\n{login_hint}",
        CONFIGURE_KNOWN_KEYS.join(", ")
    ));
    out
}

/// Render a single key's current value. Unknown keys return the same
/// error string `apply_config_option` would produce so the user gets
/// consistent feedback whether they're reading or writing.
fn render_configure_single(
    key: &str,
    session: &Session,
    llm: &Arc<MultiBackend>,
    current_idle_timeout_secs: Option<u64>,
    default_idle_timeout_secs: u64,
) -> String {
    format_config_value_line(
        key,
        session,
        llm,
        current_idle_timeout_secs,
        default_idle_timeout_secs,
    )
    .unwrap_or_else(|| {
        format!(
            "Error: unknown config key '{key}'. Supported: {}",
            CONFIGURE_KNOWN_KEYS.join(", ")
        )
    })
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
    fn parse_pr_create_arg_returns_none_when_bare() {
        assert_eq!(parse_pr_create_arg("/pr-create"), None);
        assert_eq!(parse_pr_create_arg("  /pr-create  "), None);
        assert_eq!(parse_pr_create_arg("/pr-create   "), None);
    }

    #[test]
    fn parse_pr_create_arg_returns_title_when_present() {
        assert_eq!(
            parse_pr_create_arg("/pr-create Fix the thing"),
            Some("Fix the thing".to_string())
        );
    }

    #[test]
    fn parse_pr_create_arg_trims_surrounding_whitespace() {
        assert_eq!(
            parse_pr_create_arg("/pr-create   Fix the thing   "),
            Some("Fix the thing".to_string())
        );
    }

    #[test]
    fn parse_pr_create_arg_preserves_internal_punctuation_and_case() {
        // Conventional-commit prefixes, parens, colons and mixed case
        // must round-trip verbatim into the title.
        assert_eq!(
            parse_pr_create_arg("/pr-create feat(api): Add NewThing"),
            Some("feat(api): Add NewThing".to_string())
        );
    }

    #[test]
    fn is_slash_command_matches_pr_create_variants() {
        assert!(is_slash_command("/pr-create", "pr-create"));
        assert!(is_slash_command("  /pr-create  ", "pr-create"));
        assert!(is_slash_command("/pr-create my title", "pr-create"));
        // Case-insensitive matching, like other slash commands.
        assert!(is_slash_command("/PR-Create", "pr-create"));
        assert!(is_slash_command("/PR-CREATE", "pr-create"));
        // Hyphen-prefix collisions must not match.
        assert!(!is_slash_command("/pr-create-extra", "pr-create"));
    }

    #[test]
    fn builtin_commands_includes_pr_create() {
        // The advertised list must surface `/pr-create` so editors put
        // it in autocomplete; the collision set must reserve the name
        // so a same-named skill cannot shadow the built-in dispatcher.
        let cmds = builtin_commands();
        assert!(
            cmds.iter().any(|c| c.name == "pr-create"),
            "builtin_commands() missing pr-create; got: {:?}",
            cmds.iter().map(|c| &c.name).collect::<Vec<_>>()
        );
        assert!(
            builtin_command_names().contains("pr-create"),
            "builtin_command_names() missing pr-create"
        );
    }

    #[test]
    fn shell_single_quote_escapes_embedded_quote() {
        assert_eq!(shell_single_quote("hello"), "'hello'");
        assert_eq!(shell_single_quote(""), "''");
        // The standard `'\''` escape: close, escaped quote, reopen.
        assert_eq!(shell_single_quote("it's"), "'it'\\''s'");
        // Backticks/$/" are harmless inside single quotes -- preserved as-is.
        assert_eq!(shell_single_quote("$x `y` \"z\""), "'$x `y` \"z\"'");
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
                "pr-create",
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
    /// Env-owned case: `OPENROUTER_API_KEY` set. Dump's openrouter.api_key
    /// row reports `source=env`, surfaces an `env-owned` marker so users
    /// know `/configure openrouter.api_key` will refuse, and the hint
    /// mentions the env var as the credential lifecycle owner.
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
        let (llm, _lock) = test_llm_and_refresh_lock();
        let dump = render_configure_dump(&session, &llm, None, 600);

        assert!(
            dump.contains("source=env"),
            "dump must report env as active source; got:\n{dump}"
        );
        assert!(
            dump.contains("env-owned"),
            "dump must mark env-owned credentials so users know /configure refuses; got:\n{dump}"
        );
        assert!(
            dump.contains("OPENROUTER_API_KEY"),
            "hint must point at the env var when env owns: {dump}"
        );
    }

    /// File-owned case: env unset, file has a key. The dump reports
    /// `source=file` with the key length and an `/configure` hint that
    /// doesn't surface the env-ownership warning.
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
        let (llm, _lock) = test_llm_and_refresh_lock();
        let dump = render_configure_dump(&session, &llm, None, 600);

        assert!(
            dump.contains("source=file"),
            "dump must report file as active source; got:\n{dump}"
        );
        assert!(
            !dump.contains("env-owned"),
            "dump must not mark file-owned credentials as env-owned: {dump}"
        );
        assert!(
            dump.contains("/configure openrouter.api_key"),
            "hint must mention the /configure surface when env doesn't own: {dump}"
        );
    }

    /// No credentials at all: dump still renders, reports
    /// `source=none`, and the hint still points at `/configure` so the
    /// user knows how to add a key.
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
        let (llm, _lock) = test_llm_and_refresh_lock();
        let dump = render_configure_dump(&session, &llm, None, 600);

        assert!(dump.contains("source=none"), "dump:\n{dump}");
        assert!(
            dump.contains("absent"),
            "openrouter.api_key row must mark length absent: {dump}"
        );
        assert!(
            !dump.contains("env-owned"),
            "dump must not mark missing credentials as env-owned: {dump}"
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

    /// Build the backend handle + refresh lock `apply_config_option` now
    /// requires. Tests that exercise only the per-session knobs
    /// (behavior, permission, model, reasoning_effort) don't touch the
    /// backend cells, so an empty `MultiBackend` is sufficient.
    fn test_llm_and_refresh_lock() -> (
        std::sync::Arc<MultiBackend>,
        std::sync::Arc<tokio::sync::Mutex<()>>,
    ) {
        (
            std::sync::Arc::new(MultiBackend::new(None, None, None)),
            std::sync::Arc::new(tokio::sync::Mutex::new(())),
        )
    }

    #[tokio::test]
    async fn apply_config_option_sets_permission_mode() {
        let (store, id) = make_store_with_session("m").await;
        let (llm, lock) = test_llm_and_refresh_lock();
        let outcome = apply_config_option(
            &store,
            &llm,
            &lock,
            &id,
            PERMISSION_CONFIG_ID,
            "acceptEdits",
        )
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
        let (llm, lock) = test_llm_and_refresh_lock();
        apply_config_option(&store, &llm, &lock, &id, BEHAVIOR_CONFIG_ID, "PLAN")
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
        let (llm, lock) = test_llm_and_refresh_lock();
        // Empty catalog must accept any id so a manually-configured
        // backend still works.
        apply_config_option(&store, &llm, &lock, &id, MODEL_CONFIG_ID, "custom/model")
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
        let (llm, lock) = test_llm_and_refresh_lock();
        store
            .set_available_models(vec![
                ModelMetadata::id_only("known-1"),
                ModelMetadata::id_only("known-2"),
            ])
            .await;
        let err = apply_config_option(&store, &llm, &lock, &id, MODEL_CONFIG_ID, "ghost")
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
        let (llm, lock) = test_llm_and_refresh_lock();
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
        apply_config_option(&store, &llm, &lock, &id, REASONING_EFFORT_CONFIG_ID, "high")
            .await
            .expect("set reasoning effort");
        let outcome = apply_config_option(&store, &llm, &lock, &id, MODEL_CONFIG_ID, "model-b")
            .await
            .expect("swap model");
        assert_eq!(outcome.cleared_reasoning.as_deref(), Some("high"));
    }

    #[tokio::test]
    async fn apply_config_option_rejects_reasoning_effort_for_model_without_presets() {
        let (store, id) = make_store_with_session("plain-model").await;
        let (llm, lock) = test_llm_and_refresh_lock();
        store
            .set_available_models(vec![ModelMetadata::id_only("plain-model")])
            .await;

        let err = apply_config_option(&store, &llm, &lock, &id, REASONING_EFFORT_CONFIG_ID, "high")
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
        let (llm, lock) = test_llm_and_refresh_lock();
        let err = apply_config_option(&store, &llm, &lock, &id, PERMISSION_CONFIG_ID, "bogus")
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
        let (llm, lock) = test_llm_and_refresh_lock();
        let err = apply_config_option(&store, &llm, &lock, &id, "no_such_knob", "value")
            .await
            .expect_err("unknown key");
        assert!(matches!(err, ConfigApplyError::UnknownConfigId));
    }

    #[tokio::test]
    async fn apply_config_option_reports_unknown_session() {
        let store = SessionStore::new("m".into());
        let (llm, lock) = test_llm_and_refresh_lock();
        let err = apply_config_option(
            &store,
            &llm,
            &lock,
            "no-session",
            PERMISSION_CONFIG_ID,
            "default",
        )
        .await
        .expect_err("session does not exist");
        assert!(matches!(err, ConfigApplyError::UnknownSession));
    }

    #[tokio::test]
    async fn render_configure_dump_contains_all_keys() {
        // env-owned ownership doesn't matter for this test; we only need
        // a stable, env-independent dump. Lock the guard so we don't race
        // against env mutations elsewhere.
        use crate::openrouter_auth::test_support::{ENV_GUARD, EnvScope};
        let _lock = ENV_GUARD.lock().await;
        let tmp_cfg = tempfile::tempdir().unwrap();
        let _brokk = EnvScope::set("BROKK_CONFIG_HOME", tmp_cfg.path());
        let _env = EnvScope::remove("OPENROUTER_API_KEY");

        let session = Session::new(
            "id".into(),
            std::path::PathBuf::from("/tmp"),
            "model-x".into(),
            "name".into(),
        );
        let (llm, _lock) = test_llm_and_refresh_lock();
        let dump = render_configure_dump(&session, &llm, None, 600);
        for key in CONFIGURE_KNOWN_KEYS {
            assert!(dump.contains(key), "dump missing key `{key}`: {dump}");
        }
        assert!(dump.contains("model-x"));
    }

    #[tokio::test]
    async fn render_configure_single_unknown_key_lists_supported() {
        use crate::openrouter_auth::test_support::{ENV_GUARD, EnvScope};
        let _lock = ENV_GUARD.lock().await;
        let tmp_cfg = tempfile::tempdir().unwrap();
        let _brokk = EnvScope::set("BROKK_CONFIG_HOME", tmp_cfg.path());
        let _env = EnvScope::remove("OPENROUTER_API_KEY");

        let session = Session::new(
            "id".into(),
            std::path::PathBuf::from("/tmp"),
            "model-x".into(),
            "name".into(),
        );
        let (llm, _lock) = test_llm_and_refresh_lock();
        let out = render_configure_single("bogus", &session, &llm, None, 600);
        assert!(out.starts_with("Error:"));
        for key in CONFIGURE_KNOWN_KEYS {
            assert!(out.contains(key), "missing key `{key}` in error: {out}");
        }
    }

    // ----- #3610: backend-credential / endpoint keys ----------------

    /// Secret keys never echo their value into either the success
    /// message or `tracing::info!` -- assert the helper used by both
    /// strips the value and only reveals length.
    #[test]
    fn redact_config_value_hides_secrets_but_reports_length() {
        let or = redact_config_value(OPENROUTER_API_KEY_CONFIG_ID, "sk-or-abc123");
        assert!(!or.contains("sk-or-abc123"), "must not leak the key: {or}");
        assert!(or.contains("length=12"), "must report length: {or}");

        let cleared = redact_config_value(OPENROUTER_API_KEY_CONFIG_ID, "");
        assert!(cleared.contains("cleared"), "{cleared}");

        // Non-secret keys pass through verbatim so dumps stay readable.
        let plain = redact_config_value(BEHAVIOR_CONFIG_ID, "PLAN");
        assert_eq!(plain, "`PLAN`");
    }

    #[test]
    fn parse_idle_timeout_config_value_handles_clear_and_range() {
        // Clear sentinels.
        assert_eq!(parse_idle_timeout_config_value("").unwrap(), None);
        assert_eq!(parse_idle_timeout_config_value("default").unwrap(), None);
        assert_eq!(parse_idle_timeout_config_value("CLEAR").unwrap(), None);

        // In-range numeric.
        assert_eq!(parse_idle_timeout_config_value("600").unwrap(), Some(600));

        // Out of range still references the helpful `default` keyword
        // so the UX matches `/idle-timeout`'s historical error wording.
        let err = parse_idle_timeout_config_value("0").unwrap_err();
        assert!(err.contains("out of range"), "{err}");
        assert!(err.contains("default"), "{err}");

        let err = parse_idle_timeout_config_value("banana").unwrap_err();
        assert!(err.contains("Unknown value"), "{err}");
    }

    #[tokio::test]
    async fn apply_config_option_idle_timeout_sets_session_override() {
        let (store, id) = make_store_with_session("m").await;
        let (llm, lock) = test_llm_and_refresh_lock();
        apply_config_option(&store, &llm, &lock, &id, IDLE_TIMEOUT_CONFIG_ID, "600")
            .await
            .expect("set");
        let snap = store
            .snapshot(&id, &std::env::temp_dir())
            .await
            .expect("session present");
        assert_eq!(snap.idle_timeout_secs, Some(600));

        // Clearing routes through the same arm and drops the override.
        apply_config_option(&store, &llm, &lock, &id, IDLE_TIMEOUT_CONFIG_ID, "default")
            .await
            .expect("clear");
        let snap = store
            .snapshot(&id, &std::env::temp_dir())
            .await
            .expect("session present");
        assert_eq!(snap.idle_timeout_secs, None);
    }

    #[tokio::test]
    async fn apply_config_option_idle_timeout_rejects_out_of_range() {
        let (store, id) = make_store_with_session("m").await;
        let (llm, lock) = test_llm_and_refresh_lock();
        let err = apply_config_option(&store, &llm, &lock, &id, IDLE_TIMEOUT_CONFIG_ID, "0")
            .await
            .expect_err("zero is out of range");
        assert!(matches!(err, ConfigApplyError::InvalidValue { .. }));
    }

    #[tokio::test]
    async fn apply_config_option_ollama_endpoint_updates_config_cell() {
        let (store, id) = make_store_with_session("m").await;
        let (llm, lock) = test_llm_and_refresh_lock();
        apply_config_option(
            &store,
            &llm,
            &lock,
            &id,
            OLLAMA_ENDPOINT_CONFIG_ID,
            "http://10.0.0.5:11434/",
        )
        .await
        .expect("set custom endpoint");
        // Trailing slash is normalised out -- discovery expects an exact
        // base so `format!("{base}/api/tags")` doesn't double-slash.
        assert_eq!(llm.ollama_config_snapshot().url, "http://10.0.0.5:11434");

        // Clearing falls back to the canonical default.
        apply_config_option(
            &store,
            &llm,
            &lock,
            &id,
            OLLAMA_ENDPOINT_CONFIG_ID,
            "default",
        )
        .await
        .expect("clear endpoint");
        assert_eq!(
            llm.ollama_config_snapshot().url,
            crate::discovery::OLLAMA_DEFAULT_URL
        );
    }

    #[tokio::test]
    async fn apply_config_option_ollama_endpoint_rejects_non_url() {
        let (store, id) = make_store_with_session("m").await;
        let (llm, lock) = test_llm_and_refresh_lock();
        let err = apply_config_option(
            &store,
            &llm,
            &lock,
            &id,
            OLLAMA_ENDPOINT_CONFIG_ID,
            "not-a-url",
        )
        .await
        .expect_err("garbage value must reject");
        match err {
            ConfigApplyError::InvalidValue { reason, .. } => {
                assert!(reason.contains("not-a-url"), "{reason}");
            }
            other => panic!("expected InvalidValue, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn apply_config_option_openrouter_attribution_updates_cell_and_rebuilds() {
        // No key configured: setting attribution must still update the
        // cell so a future install picks up the new headers.
        use crate::openrouter_auth::test_support::{ENV_GUARD, EnvScope};
        let _lock_env = ENV_GUARD.lock().await;
        let tmp_cfg = tempfile::tempdir().unwrap();
        let _brokk = EnvScope::set("BROKK_CONFIG_HOME", tmp_cfg.path());
        let _env = EnvScope::remove("OPENROUTER_API_KEY");

        let (store, id) = make_store_with_session("m").await;
        let (llm, lock) = test_llm_and_refresh_lock();
        apply_config_option(
            &store,
            &llm,
            &lock,
            &id,
            OPENROUTER_HTTP_REFERER_CONFIG_ID,
            "https://example.com/app",
        )
        .await
        .expect("set referer");
        assert_eq!(
            llm.openrouter_attribution_snapshot().http_referer,
            "https://example.com/app"
        );

        // Clearing reverts to the brokk default, not to an empty string.
        apply_config_option(
            &store,
            &llm,
            &lock,
            &id,
            OPENROUTER_HTTP_REFERER_CONFIG_ID,
            "default",
        )
        .await
        .expect("clear referer");
        assert_eq!(
            llm.openrouter_attribution_snapshot().http_referer,
            crate::multi_backend::OpenRouterAttribution::DEFAULT_HTTP_REFERER
        );
    }

    #[tokio::test]
    async fn apply_config_option_openrouter_attribution_rejects_invalid_header() {
        use crate::openrouter_auth::test_support::{ENV_GUARD, EnvScope};
        let _lock_env = ENV_GUARD.lock().await;
        let tmp_cfg = tempfile::tempdir().unwrap();
        let _brokk = EnvScope::set("BROKK_CONFIG_HOME", tmp_cfg.path());
        let _env = EnvScope::remove("OPENROUTER_API_KEY");

        let (store, id) = make_store_with_session("m").await;
        let (llm, lock) = test_llm_and_refresh_lock();
        // Control characters / newlines are not valid HTTP header values.
        let err = apply_config_option(
            &store,
            &llm,
            &lock,
            &id,
            OPENROUTER_X_TITLE_CONFIG_ID,
            "bad\nvalue",
        )
        .await
        .expect_err("newline-bearing value must reject");
        assert!(matches!(err, ConfigApplyError::InvalidValue { .. }));
    }

    #[tokio::test]
    async fn apply_config_option_openrouter_api_key_refuses_when_env_owned() {
        use crate::openrouter_auth::test_support::{ENV_GUARD, EnvScope};
        let _lock_env = ENV_GUARD.lock().await;
        let tmp_cfg = tempfile::tempdir().unwrap();
        let _brokk = EnvScope::set("BROKK_CONFIG_HOME", tmp_cfg.path());
        let _env = EnvScope::set("OPENROUTER_API_KEY", "sk-or-from-env");

        let (store, id) = make_store_with_session("m").await;
        let (llm, lock) = test_llm_and_refresh_lock();
        let err = apply_config_option(
            &store,
            &llm,
            &lock,
            &id,
            OPENROUTER_API_KEY_CONFIG_ID,
            "sk-or-replacement",
        )
        .await
        .expect_err("env-owned must refuse mutation");
        match err {
            ConfigApplyError::InvalidValue { reason, .. } => {
                assert!(
                    reason.contains("OPENROUTER_API_KEY"),
                    "reason must explain env ownership: {reason}"
                );
            }
            other => panic!("expected InvalidValue, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn apply_config_option_openrouter_api_key_writes_and_installs() {
        use crate::openrouter_auth::test_support::{ENV_GUARD, EnvScope};
        let _lock_env = ENV_GUARD.lock().await;
        let tmp_cfg = tempfile::tempdir().unwrap();
        let _brokk = EnvScope::set("BROKK_CONFIG_HOME", tmp_cfg.path());
        let _env = EnvScope::remove("OPENROUTER_API_KEY");

        let (store, id) = make_store_with_session("m").await;
        let (llm, lock) = test_llm_and_refresh_lock();
        apply_config_option(
            &store,
            &llm,
            &lock,
            &id,
            OPENROUTER_API_KEY_CONFIG_ID,
            "sk-or-from-config",
        )
        .await
        .expect("write + install");

        // Persisted to the disk redirect set above.
        let on_disk = crate::openrouter_auth::read()
            .unwrap()
            .expect("key written")
            .api_key;
        assert_eq!(on_disk, "sk-or-from-config");

        // Now clear it -- the file goes away and the backend disappears.
        apply_config_option(
            &store,
            &llm,
            &lock,
            &id,
            OPENROUTER_API_KEY_CONFIG_ID,
            "clear",
        )
        .await
        .expect("clear");
        assert!(crate::openrouter_auth::read().unwrap().is_none());
    }

    #[tokio::test]
    async fn apply_config_option_codex_auth_rejects_non_clear() {
        let (store, id) = make_store_with_session("m").await;
        let (llm, lock) = test_llm_and_refresh_lock();
        let err = apply_config_option(
            &store,
            &llm,
            &lock,
            &id,
            CODEX_AUTH_CONFIG_ID,
            "sign-in-please",
        )
        .await
        .expect_err("codex.auth only supports clear");
        match err {
            ConfigApplyError::InvalidValue { reason, .. } => {
                assert!(
                    reason.contains("codex-login"),
                    "must redirect to /codex-login for sign-in: {reason}"
                );
            }
            other => panic!("expected InvalidValue, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn apply_config_option_codex_auth_clear_is_idempotent_when_absent() {
        // Redirect CODEX_HOME to a temp dir so we never touch the
        // developer's real ~/.codex/auth.json. The logout path is
        // idempotent for a missing file, so this exercises the
        // happy-path uninstall without needing a real auth.json fixture.
        use crate::openrouter_auth::test_support::{ENV_GUARD, EnvScope};
        let _lock_env = ENV_GUARD.lock().await;
        let tmp_home = tempfile::tempdir().unwrap();
        let _codex = EnvScope::set("CODEX_HOME", tmp_home.path());

        let (store, id) = make_store_with_session("m").await;
        let (llm, lock) = test_llm_and_refresh_lock();
        apply_config_option(&store, &llm, &lock, &id, CODEX_AUTH_CONFIG_ID, "clear")
            .await
            .expect("clear on absent auth.json succeeds");
    }

    #[test]
    fn current_openrouter_key_prefers_env_over_file() {
        use crate::openrouter_auth::test_support::{ENV_GUARD, EnvScope};
        let _lock = ENV_GUARD.blocking_lock();
        let tmp_cfg = tempfile::tempdir().unwrap();
        let _brokk = EnvScope::set("BROKK_CONFIG_HOME", tmp_cfg.path());
        let _env = EnvScope::set("OPENROUTER_API_KEY", "sk-or-env-wins");
        crate::openrouter_auth::write(&crate::openrouter_auth::OpenRouterAuth {
            api_key: "sk-or-file-loses".into(),
        })
        .unwrap();
        assert_eq!(current_openrouter_key().as_deref(), Some("sk-or-env-wins"));
    }

    /// Bounds helper is the single source of truth for the out-of-range
    /// message that both `/idle-timeout` and `/configure idle_timeout`
    /// surface. Verify the bounds match the constants and the message
    /// shape stays stable.
    #[test]
    fn validate_idle_timeout_secs_enforces_shared_bounds() {
        let min = crate::llm_client::MIN_IDLE_CHUNK_TIMEOUT_SECS;
        let max = crate::llm_client::MAX_IDLE_CHUNK_TIMEOUT_SECS;
        assert_eq!(validate_idle_timeout_secs(&min.to_string()), Ok(min));
        assert_eq!(validate_idle_timeout_secs(&max.to_string()), Ok(max));
        let err =
            validate_idle_timeout_secs(&(max + 1).to_string()).expect_err("above max must reject");
        assert!(err.contains("out of range"), "{err}");
    }

    /// Ollama endpoint must reject control characters so the user-supplied
    /// URL can't smuggle CRLF into the structured tracing line. Covers the
    /// SSRF-adjacent log-injection vector flagged in DevOps review.
    #[tokio::test]
    async fn apply_config_option_ollama_endpoint_rejects_control_chars() {
        let (store, id) = make_store_with_session("m").await;
        let (llm, lock) = test_llm_and_refresh_lock();
        let err = apply_config_option(
            &store,
            &llm,
            &lock,
            &id,
            OLLAMA_ENDPOINT_CONFIG_ID,
            "http://example.com\r\nX-Injected: yes",
        )
        .await
        .expect_err("control chars must reject");
        match err {
            ConfigApplyError::InvalidValue { reason, .. } => {
                assert!(
                    reason.contains("control characters"),
                    "reason must explain why: {reason}"
                );
            }
            other => panic!("expected InvalidValue, got {other:?}"),
        }
    }

    /// Every `/idle-timeout` success path now prepends a deprecation
    /// notice pointing users at `/configure idle_timeout`. Asserting on
    /// all three branches (Show / Set / Clear) protects the migration
    /// against a future change silently dropping the prefix on one path.
    #[tokio::test]
    async fn handle_idle_timeout_prepends_deprecation_notice_on_every_path() {
        let (store, id) = make_store_with_session("m").await;

        // Show
        let show = handle_idle_timeout("/idle-timeout", &id, &store, None, 600).await;
        assert!(
            show.contains("deprecated"),
            "Show path missing deprecation: {show}"
        );
        assert!(show.contains("/configure idle_timeout"), "{show}");

        // Set
        let set = handle_idle_timeout("/idle-timeout 120", &id, &store, None, 600).await;
        assert!(
            set.contains("deprecated"),
            "Set path missing deprecation: {set}"
        );
        assert!(set.contains("/configure idle_timeout"), "{set}");

        // Clear
        let clear = handle_idle_timeout("/idle-timeout default", &id, &store, Some(120), 600).await;
        assert!(
            clear.contains("deprecated"),
            "Clear path missing deprecation: {clear}"
        );
        assert!(clear.contains("/configure idle_timeout"), "{clear}");
    }

    /// Regression for the security-review HIGH finding: the
    /// `setSessionConfigOption` RPC handler used to log the raw `value`
    /// field at `info`, which would dump `openrouter.api_key` secrets
    /// into stderr / journald. The fix routes that log through
    /// `redact_config_value`, so re-asserting at the helper level here
    /// keeps the redaction contract pinned even if the call site moves.
    #[test]
    fn redact_config_value_handles_all_secret_keys() {
        for secret_key in [OPENROUTER_API_KEY_CONFIG_ID, CODEX_AUTH_CONFIG_ID] {
            let out = redact_config_value(secret_key, "very-sensitive-value");
            assert!(
                !out.contains("very-sensitive-value"),
                "{secret_key} leaked value: {out}"
            );
        }
    }

    /// The dump-hint must not promise behaviour the apply path doesn't
    /// deliver. When env owns the credential, `/configure
    /// openrouter.api_key clear` returns InvalidValue, so the hint must
    /// NOT mention "(no-op while env-owned)".
    #[tokio::test]
    async fn render_configure_dump_hint_does_not_promise_clear_no_op_when_env_owned() {
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
        let (llm, _lock) = test_llm_and_refresh_lock();
        let dump = render_configure_dump(&session, &llm, None, 600);
        assert!(
            !dump.contains("no-op while env-owned"),
            "hint must not claim clear is a no-op (the apply path returns InvalidValue): {dump}"
        );
        assert!(
            dump.contains("are both disabled"),
            "hint must explain both surfaces are gated: {dump}"
        );
    }
}
