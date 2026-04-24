use std::path::Path;
use std::sync::Arc;

use agent_client_protocol::schema::{
    AgentCapabilities, CancelNotification, ContentBlock, ContentChunk, InitializeRequest,
    InitializeResponse, ListSessionsRequest, ListSessionsResponse, LoadSessionRequest,
    LoadSessionResponse, NewSessionRequest, NewSessionResponse, PromptCapabilities, PromptRequest,
    PromptResponse, ResumeSessionRequest, ResumeSessionResponse, SessionCapabilities, SessionInfo,
    SessionListCapabilities, SessionMode as AcpSessionMode, SessionModeState, SessionNotification,
    SessionResumeCapabilities, SessionUpdate, SetSessionModeRequest, SetSessionModeResponse,
    StopReason, TextContent,
};
use agent_client_protocol::{
    Agent, ByteStreams, Client, ConnectionTo, Dispatch, Handled, Responder, on_receive_dispatch,
    on_receive_notification, on_receive_request,
};
use tokio_util::compat::{TokioAsyncReadCompatExt, TokioAsyncWriteCompatExt};

use crate::llm_client::{ChatMessage, LlmBackend};
use crate::session::{ConversationTurn, SessionMode, SessionStore};
use crate::tools::ToolRegistry;

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

/// Build and run the ACP agent over stdio.
pub async fn run_agent(
    llm: Arc<dyn LlmBackend>,
    sessions: SessionStore,
) -> agent_client_protocol::Result<()> {
    let llm_init = llm.clone();
    let sessions_init = sessions.clone();

    let llm_new = llm.clone();
    let sessions_new = sessions.clone();

    let sessions_load = sessions.clone();
    let sessions_resume = sessions.clone();
    let sessions_list = sessions.clone();

    let llm_prompt = llm.clone();
    let sessions_prompt = sessions.clone();

    let sessions_cancel = sessions.clone();
    let sessions_mode = sessions.clone();

    Agent
        .builder()
        .name("brokk-acp-rust")
        // Handle initialize
        .on_receive_request(
            async move |req: InitializeRequest,
                        responder: Responder<InitializeResponse>,
                        _cx: ConnectionTo<Client>| {
                tracing::info!("ACP initialize");

                // Try to discover models at startup
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

                // Discover models for the response
                let models = match llm_new.list_models().await {
                    Ok(m) => m,
                    Err(e) => {
                        tracing::warn!("model discovery failed: {e}");
                        vec![session.model.clone()]
                    }
                };

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

                // Replay conversation history as session updates
                for turn in &session.history {
                    send_message(&cx, &session_id, &turn.agent_response);
                }

                responder.respond(
                    LoadSessionResponse::new()
                        .modes(mode_state(session.mode.as_str())),
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
                                .modes(mode_state(session.mode.as_str())),
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

                // Get session state (prompt doesn't carry cwd, so use current dir as fallback)
                let fallback_cwd = std::env::current_dir().unwrap_or_default();
                let session = match sessions_prompt.get_session(&session_id, &fallback_cwd).await {
                    Some(s) => s,
                    None => {
                        send_message(&cx, &session_id, "Error: unknown session");
                        return responder.respond(PromptResponse::new(StopReason::EndTurn));
                    }
                };

                // Validate model is configured
                if session.model.is_empty() {
                    send_message(&cx, &session_id, "Error: no model configured. Start the server with --default-model or ensure the LLM endpoint is reachable for model discovery.");
                    return responder.respond(PromptResponse::new(StopReason::EndTurn));
                }

                // Build conversation messages from history
                let mut messages = vec![
                    ChatMessage::system(build_system_prompt(&session.mode, &session.cwd)),
                ];
                for turn in &session.history {
                    messages.push(ChatMessage::user(turn.user_prompt.clone()));
                    messages.push(ChatMessage::assistant(turn.agent_response.clone()));
                }
                messages.push(ChatMessage::user(prompt_text.clone()));

                // Create a cancellation token for this prompt
                let cancel = sessions_prompt.start_prompt(&session_id).await;

                // Run the agentic tool loop
                let registry = ToolRegistry::new(session.cwd.clone());
                let cx_text = cx.clone();
                let sid_text = session_id.clone();
                let cx_tool = cx.clone();
                let sid_tool = session_id.clone();

                let response_text = crate::tool_loop::run(
                    &llm_prompt,
                    &registry,
                    &session.model,
                    messages,
                    25, // max turns
                    cancel,
                    |token| send_message(&cx_text, &sid_text, token),
                    |headline| send_message(&cx_tool, &sid_tool, headline),
                )
                .await;

                // Clean up cancellation token
                sessions_prompt.finish_prompt(&session_id).await;

                // Save conversation turn
                sessions_prompt
                    .add_turn(
                        &session_id,
                        ConversationTurn {
                            user_prompt: prompt_text,
                            agent_response: response_text,
                        },
                    )
                    .await;

                responder.respond(PromptResponse::new(StopReason::EndTurn))
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

                if let Some(mode) = SessionMode::parse(&mode_id) {
                    sessions_mode.set_mode(&session_id, mode).await;
                }

                responder.respond(SetSessionModeResponse::new())
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
