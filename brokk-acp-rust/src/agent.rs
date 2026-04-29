use std::path::{Path, PathBuf};
use std::sync::Arc;

use agent_client_protocol::schema::{
    AgentCapabilities, CancelNotification, ConfigOptionUpdate, ContentBlock, ContentChunk,
    InitializeRequest, InitializeResponse, ListSessionsRequest, ListSessionsResponse,
    LoadSessionRequest, LoadSessionResponse, NewSessionRequest, NewSessionResponse,
    PromptCapabilities, PromptRequest, PromptResponse, ResumeSessionRequest, ResumeSessionResponse,
    SessionCapabilities, SessionConfigOption, SessionConfigOptionCategory,
    SessionConfigSelectOption, SessionInfo, SessionListCapabilities, SessionMode as AcpSessionMode,
    SessionModeState, SessionNotification, SessionResumeCapabilities, SessionUpdate,
    SetSessionConfigOptionRequest, SetSessionConfigOptionResponse, SetSessionModeRequest,
    SetSessionModeResponse, StopReason, TextContent,
};
use agent_client_protocol::{
    Agent, ByteStreams, Client, ConnectionTo, Dispatch, Handled, Responder, on_receive_dispatch,
    on_receive_notification, on_receive_request,
};
use tokio_util::compat::{TokioAsyncReadCompatExt, TokioAsyncWriteCompatExt};

use crate::llm_client::LlmBackend;
use crate::session::{ConversationTurn, PermissionMode, SessionMode, SessionStore};

/// Stable ids for our `SessionConfigOption` selectors. We expose both
/// dropdowns via configOptions because the ACP spec says clients SHOULD
/// ignore the legacy `modes` channel when configOptions is present (Zed
/// does), so once we expose any configOption we have to expose all of them.
const PERMISSION_CONFIG_ID: &str = "permission_mode";
const BEHAVIOR_CONFIG_ID: &str = "behavior_mode";

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

/// All configOption selectors we expose, in display order.
fn all_config_options(
    behavior: SessionMode,
    permission: PermissionMode,
) -> Vec<SessionConfigOption> {
    vec![
        behavior_config_option(behavior),
        permission_config_option(permission),
    ]
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
                        _cx: ConnectionTo<Client>| {
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
                    .config_options(all_config_options(session.mode, session.permission_mode))
                    .meta(meta_map);

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

                responder.respond(
                    LoadSessionResponse::new()
                        .modes(mode_state(session.mode.as_str()))
                        .config_options(all_config_options(session.mode, session.permission_mode)),
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
                        responder.respond(
                            ResumeSessionResponse::new()
                                .modes(mode_state(session.mode.as_str()))
                                .config_options(all_config_options(
                                    session.mode,
                                    session.permission_mode,
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
                // Build the full prompt state -- cwd, mode, model, permission mode, and
                // the ChatMessage list including system prompt and the new user turn --
                // under one read lock, so we never clone the conversation history twice.
                let fallback_cwd = std::env::current_dir().unwrap_or_default();
                let prompt_state = match sessions_prompt
                    .build_prompt_state(
                        &session_id,
                        &fallback_cwd,
                        prompt_text.clone(),
                        |mode, cwd| build_system_prompt(&mode, cwd),
                    )
                    .await
                {
                    Some(s) => s,
                    None => {
                        send_message(&cx, &session_id, "Error: unknown session");
                        return responder.respond(PromptResponse::new(StopReason::EndTurn));
                    }
                };

                // Validate model is configured
                if prompt_state.model.is_empty() {
                    send_message(&cx, &session_id, "Error: no model configured. Start the server with --default-model or ensure the LLM endpoint is reachable for model discovery.");
                    return responder.respond(PromptResponse::new(StopReason::EndTurn));
                }

                let messages = prompt_state.messages;

                // Create a cancellation token for this prompt
                let cancel = sessions_prompt.start_prompt(&session_id).await;

                // Build the tool registry up-front so we don't pay for it inside the spawn.
                let registry = sessions_prompt
                    .get_or_create_registry(
                        &session_id,
                        prompt_state.cwd,
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
                let model_for_loop = prompt_state.model;

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

                    // Clean up cancellation token even on panic.
                    sessions_for_loop.finish_prompt(&session_id_for_loop).await;

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

                    // Save conversation turn (best effort -- logged on failure inside the store).
                    sessions_for_loop
                        .add_turn(
                            &session_id_for_loop,
                            ConversationTurn {
                                user_prompt: prompt_text_for_turn,
                                agent_response: response_text,
                            },
                        )
                        .await;

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

                if !sessions_mode.set_mode(&session_id, mode).await {
                    return responder.respond_with_error(
                        agent_client_protocol::Error::invalid_params()
                            .data(serde_json::json!({
                                "reason": format!("unknown session '{session_id}'"),
                            })),
                    );
                }

                responder.respond(SetSessionModeResponse::new())
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
                        if !sessions_perm.set_mode(&session_id, behavior_mode).await {
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
                                    "supported": [BEHAVIOR_CONFIG_ID, PERMISSION_CONFIG_ID],
                                }),
                            ),
                        );
                    }
                }

                // Re-fetch the session so the returned options reflect the
                // latest values for *both* selectors (the spec says the
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
                let updated_options = all_config_options(session.mode, session.permission_mode);

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
