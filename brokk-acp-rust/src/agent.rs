use std::path::{Path, PathBuf};
use std::sync::Arc;

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

use crate::llm_client::{ChatMessage, LlmBackend};
use crate::session::{ConversationTurn, PermissionMode, SessionMode, SessionStore};

/// Stable ids for our `SessionConfigOption` selectors. We expose both
/// dropdowns via configOptions because the ACP spec says clients SHOULD
/// ignore the legacy `modes` channel when configOptions is present (Zed
/// does), so once we expose any configOption we have to expose all of them.
const PERMISSION_CONFIG_ID: &str = "permission_mode";
const BEHAVIOR_CONFIG_ID: &str = "behavior_mode";
/// Mirrors the Java executor's wire id so cross-implementation clients
/// (Zed, brokk-code) can drive model selection through one canonical name.
const MODEL_CONFIG_ID: &str = "model_selection";

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

/// All configOption selectors we expose, in display order. The model
/// selector is appended only when the LLM catalog is known; clients that
/// drive model selection through the meta extension still see the current
/// model via `meta.brokk.modelId`.
fn all_config_options(
    behavior: SessionMode,
    permission: PermissionMode,
    current_model: &str,
    available_models: &[String],
) -> Vec<SessionConfigOption> {
    let mut opts = vec![
        behavior_config_option(behavior),
        permission_config_option(permission),
    ];
    if let Some(model_opt) = model_config_option(current_model, available_models) {
        opts.push(model_opt);
    }
    opts
}

/// Slash commands advertised to clients via `available_commands_update`.
/// Mirrors the Java executor's `/context` command (other Java commands are
/// intentionally omitted -- they depend on the live workspace context that
/// the Rust agent does not yet model).
fn available_commands() -> Vec<AvailableCommand> {
    vec![AvailableCommand::new(
        "context",
        "Show current session context snapshot",
    )]
}

fn send_available_commands_update(cx: &ConnectionTo<Client>, session_id: &str) {
    let update =
        SessionUpdate::AvailableCommandsUpdate(AvailableCommandsUpdate::new(available_commands()));
    let notification = SessionNotification::new(session_id.to_string(), update);
    if let Err(e) = cx.send_notification(notification) {
        tracing::warn!("failed to send available_commands_update: {e}");
    }
}

/// Build and run the ACP agent over stdio.
pub async fn run_agent(
    llm: Arc<dyn LlmBackend>,
    sessions: SessionStore,
    max_turns: usize,
    bifrost_binary: Option<PathBuf>,
) -> agent_client_protocol::Result<()> {
    let llm_init = llm.clone();
    let sessions_init = sessions.clone();

    let sessions_new = sessions.clone();

    let sessions_load = sessions.clone();
    let sessions_resume = sessions.clone();
    let sessions_list = sessions.clone();

    let llm_prompt = llm.clone();
    let sessions_prompt = sessions.clone();
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
                let models = match llm_init.list_models().await {
                    Ok(m) => m,
                    Err(e) => {
                        tracing::warn!("model discovery failed during init: {e}");
                        vec![]
                    }
                };
                if let Some(first) = models.first() {
                    sessions_init.set_default_model(first.clone()).await;
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

                // Reuse the cached list populated at init; fall back to the session's own model.
                let mut models = sessions_new.available_models().await;
                if models.is_empty() && !session.model.is_empty() {
                    models = vec![session.model.clone()];
                }

                let meta_value = serde_json::json!({
                    "brokk": {
                        "modelId": session.model,
                        "availableModels": models,
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
                        &models,
                    ))
                    .meta(meta_map);

                send_available_commands_update(&cx, &session.id);

                responder.respond(response)
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

                let models = sessions_load.available_models().await;
                send_available_commands_update(&cx, &session_id);

                responder.respond(
                    LoadSessionResponse::new()
                        .modes(mode_state(session.mode.as_str()))
                        .config_options(all_config_options(
                            session.mode,
                            session.permission_mode,
                            &session.model,
                            &models,
                        )),
                )
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
                        let models = sessions_resume.available_models().await;
                        send_available_commands_update(&cx, &session_id);
                        responder.respond(
                            ResumeSessionResponse::new()
                                .modes(mode_state(session.mode.as_str()))
                                .config_options(all_config_options(
                                    session.mode,
                                    session.permission_mode,
                                    &session.model,
                                    &models,
                                )),
                        )
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

                // Validate model is configured
                if snap.model.is_empty() {
                    send_message(&cx, &session_id, "Error: no model configured. Start the server with --default-model or ensure the LLM endpoint is reachable for model discovery.");
                    return responder.respond(PromptResponse::new(StopReason::EndTurn));
                }

                let mut messages = Vec::with_capacity(snap.history.len() * 2 + 2);
                messages.push(ChatMessage::system(build_system_prompt(&snap.mode, &snap.cwd)));
                for turn in snap.history {
                    messages.push(ChatMessage::user(turn.user_prompt));
                    messages.push(ChatMessage::assistant(turn.agent_response));
                }
                messages.push(ChatMessage::user(prompt_text.clone()));

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
                let llm_for_loop = llm_prompt.clone();
                let sessions_for_loop = sessions_prompt.clone();
                let cx_for_loop = cx.clone();
                let session_id_for_loop = session_id.clone();
                let prompt_text_for_turn = prompt_text;
                let model_for_loop = snap.model;

                cx.spawn(async move {
                    use futures::FutureExt;
                    use std::panic::AssertUnwindSafe;

                    let cx_text = cx_for_loop.clone();
                    let sid_text = session_id_for_loop.clone();

                    // Text tokens stream to the client in real time via this shared sink.
                    let text_sink: crate::tool_loop::TextSink = std::sync::Arc::new(
                        std::sync::Mutex::new(move |token: &str| {
                            send_message(&cx_text, &sid_text, token);
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
                        messages,
                        max_turns,
                        cancel,
                        text_sink,
                        spawned_cx,
                        session_id_for_loop.clone(),
                        sessions_for_loop.clone(),
                    ))
                    .catch_unwind()
                    .await;

                    let response_text = match loop_result {
                        Ok(text) => text,
                        Err(panic) => {
                            tracing::error!(
                                session_id = %session_id_for_loop,
                                "tool loop panicked: {:?}",
                                panic
                            );
                            "Error: agent loop panicked. See server logs.".to_string()
                        }
                    };

                    // Persist the conversation turn BEFORE finish_prompt so the
                    // per-session cancel token is held during the rewrite -- this
                    // is the locking that makes `add_turn`'s rollback safe (see
                    // the concurrency note on `SessionStore::add_turn`). On
                    // failure, surface the error to the client so the user
                    // knows their last turn isn't on disk.
                    let persist_result = sessions_for_loop
                        .add_turn(
                            &session_id_for_loop,
                            ConversationTurn {
                                user_prompt: prompt_text_for_turn,
                                agent_response: response_text,
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

                match config_id.as_str() {
                    PERMISSION_CONFIG_ID => {
                        let Some(permission_mode) = PermissionMode::parse(&value) else {
                            return responder.respond_with_error(
                                agent_client_protocol::Error::invalid_params().data(
                                    serde_json::json!({
                                        "reason": format!("unknown permission mode '{value}'"),
                                        "supported": [
                                            "default",
                                            "acceptEdits",
                                            "readOnly",
                                            "bypassPermissions",
                                        ],
                                    }),
                                ),
                            );
                        };
                        if !sessions_perm
                            .set_permission_mode(&session_id, permission_mode)
                            .await
                        {
                            return responder.respond_with_error(
                                agent_client_protocol::Error::invalid_params().data(
                                    serde_json::json!({
                                        "reason": format!("unknown session '{session_id}'"),
                                    }),
                                ),
                            );
                        }
                    }
                    BEHAVIOR_CONFIG_ID => {
                        let Some(behavior_mode) = SessionMode::parse(&value) else {
                            return responder.respond_with_error(
                                agent_client_protocol::Error::invalid_params().data(
                                    serde_json::json!({
                                        "reason": format!("unknown behavior mode '{value}'"),
                                        "supported": ["LUTZ", "CODE", "ASK", "PLAN"],
                                    }),
                                ),
                            );
                        };
                        match sessions_perm.set_mode(&session_id, behavior_mode).await {
                            Ok(true) => {}
                            Ok(false) => {
                                return responder.respond_with_error(
                                    agent_client_protocol::Error::invalid_params().data(
                                        serde_json::json!({
                                            "reason": format!("unknown session '{session_id}'"),
                                        }),
                                    ),
                                );
                            }
                            Err(e) => {
                                return responder.respond_with_error(
                                    agent_client_protocol::Error::internal_error().data(
                                        serde_json::json!({
                                            "reason": "failed to persist session mode",
                                            "details": format!("{e:#}"),
                                        }),
                                    ),
                                );
                            }
                        }
                    }
                    MODEL_CONFIG_ID => {
                        if value.is_empty() {
                            return responder.respond_with_error(
                                agent_client_protocol::Error::invalid_params().data(
                                    serde_json::json!({
                                        "reason": "model id must be a non-empty string",
                                    }),
                                ),
                            );
                        }
                        // Reject ids that drift out of the catalog when one is known.
                        // An empty catalog means model discovery never succeeded;
                        // accept anything in that case so the user can still drive
                        // the agent against a manually-configured backend.
                        let known = sessions_perm.available_models().await;
                        if !known.is_empty() && !known.iter().any(|m| m == &value) {
                            return responder.respond_with_error(
                                agent_client_protocol::Error::invalid_params().data(
                                    serde_json::json!({
                                        "reason": format!("unknown model '{value}'"),
                                        "supported": known,
                                    }),
                                ),
                            );
                        }
                        if !sessions_perm.set_model(&session_id, value.clone()).await {
                            return responder.respond_with_error(
                                agent_client_protocol::Error::invalid_params().data(
                                    serde_json::json!({
                                        "reason": format!("unknown session '{session_id}'"),
                                    }),
                                ),
                            );
                        }
                    }
                    other => {
                        return responder.respond_with_error(
                            agent_client_protocol::Error::invalid_params().data(
                                serde_json::json!({
                                    "reason": format!("unknown configOption '{other}'"),
                                    "supported": [
                                        BEHAVIOR_CONFIG_ID,
                                        PERMISSION_CONFIG_ID,
                                        MODEL_CONFIG_ID,
                                    ],
                                }),
                            ),
                        );
                    }
                }

                // Re-fetch the session so the returned options reflect the
                // latest values for *all* selectors (the spec says the
                // response carries the full updated set, not just the one we
                // changed).
                let fallback_cwd = std::env::current_dir().unwrap_or_default();
                let Some(session) = sessions_perm.get_session(&session_id, &fallback_cwd).await
                else {
                    return responder.respond_with_error(
                        agent_client_protocol::Error::invalid_params().data(serde_json::json!({
                            "reason": format!("unknown session '{session_id}'"),
                        })),
                    );
                };
                let models = sessions_perm.available_models().await;
                let updated_options = all_config_options(
                    session.mode,
                    session.permission_mode,
                    &session.model,
                    &models,
                );

                let notification = SessionNotification::new(
                    session_id.clone(),
                    SessionUpdate::ConfigOptionUpdate(ConfigOptionUpdate::new(
                        updated_options.clone(),
                    )),
                );
                if let Err(e) = cx.send_notification(notification) {
                    tracing::warn!("failed to send config_option_update: {e}");
                }

                responder.respond(SetSessionConfigOptionResponse::new(updated_options))
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

/// Build a system prompt based on the current session mode and working directory.
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
            }],
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
        };
        let report = render_context_report(&snap, PermissionMode::Default, &[]);
        assert!(report.contains("Model: `(none)`"));
        assert!(report.contains("(0 known in catalog)"));
        assert!(report.contains("Conversation turns: 0"));
    }
}
