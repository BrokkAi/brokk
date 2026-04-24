use std::sync::Arc;

use agent_client_protocol::schema::{
    AgentCapabilities, CancelNotification, ContentBlock, ContentChunk, InitializeRequest,
    InitializeResponse, NewSessionRequest, NewSessionResponse, PromptCapabilities, PromptRequest,
    PromptResponse, SessionMode as AcpSessionMode, SessionModeState, SessionNotification,
    SessionUpdate, SetSessionModeRequest, SetSessionModeResponse, StopReason, TextContent,
};
use agent_client_protocol::{
    on_receive_dispatch, on_receive_notification, on_receive_request, Agent, ByteStreams, Client,
    ConnectionTo, Dispatch, Handled, Responder,
};
use tokio_util::compat::{TokioAsyncReadCompatExt, TokioAsyncWriteCompatExt};

use crate::llm_client::{ChatMessage, LlmBackend};
use crate::session::{ConversationTurn, SessionMode, SessionStore};

/// Available session modes exposed to ACP clients.
fn available_modes() -> Vec<AcpSessionMode> {
    vec![
        AcpSessionMode::new("LUTZ", "LUTZ").description("Agentic loop with task list"),
        AcpSessionMode::new("CODE", "CODE").description("Code changes only"),
        AcpSessionMode::new("ASK", "ASK").description("Question answering"),
        AcpSessionMode::new("PLAN", "PLAN").description("Planning only"),
    ]
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
                    .prompt_capabilities(PromptCapabilities::new().embedded_context(true));

                responder.respond(
                    InitializeResponse::new(req.protocol_version)
                        .agent_capabilities(capabilities),
                )
            },
            on_receive_request!(),
        )
        // Handle session/new
        .on_receive_request(
            async move |_req: NewSessionRequest,
                        responder: Responder<NewSessionResponse>,
                        _cx: ConnectionTo<Client>| {
                tracing::info!("ACP session/new");
                let session = sessions_new.create_session().await;

                // Discover models for the response
                let models = match llm_new.list_models().await {
                    Ok(m) => m,
                    Err(e) => {
                        tracing::warn!("model discovery failed: {e}");
                        vec![session.model.clone()]
                    }
                };

                let mode_state =
                    SessionModeState::new(session.mode.as_str(), available_modes());

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
                    .modes(mode_state)
                    .meta(meta_map);

                responder.respond(response)
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

                // Get session state
                let session = match sessions_prompt.get_session(&session_id).await {
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
                let mut messages = vec![ChatMessage {
                    role: "system".to_string(),
                    content: build_system_prompt(&session.mode),
                }];
                for turn in &session.history {
                    messages.push(ChatMessage {
                        role: "user".to_string(),
                        content: turn.user_prompt.clone(),
                    });
                    messages.push(ChatMessage {
                        role: "assistant".to_string(),
                        content: turn.agent_response.clone(),
                    });
                }
                messages.push(ChatMessage {
                    role: "user".to_string(),
                    content: prompt_text.clone(),
                });

                // Create a cancellation token for this prompt
                let cancel = sessions_prompt.start_prompt(&session_id).await;

                // Stream LLM response, sending tokens as session updates
                let cx_stream = cx.clone();
                let sid = session_id.clone();
                let on_token: Box<dyn FnMut(&str) + Send> = Box::new(move |token: &str| {
                    send_message(&cx_stream, &sid, token);
                });

                let response_text = match llm_prompt
                    .stream_chat(&session.model, messages, on_token, cancel)
                    .await
                {
                    Ok(text) => text,
                    Err(e) => {
                        tracing::error!("LLM request failed for session {session_id}: {e}");
                        let err_msg = "\n**Error:** LLM request failed. Check server logs for details.\n";
                        send_message(&cx, &session_id, err_msg);
                        err_msg.to_string()
                    }
                };

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

/// Build a system prompt based on the current session mode.
fn build_system_prompt(mode: &SessionMode) -> String {
    match mode {
        SessionMode::Lutz => {
            "You are Brokk, an AI coding assistant. You help users with software engineering tasks \
             using an agentic approach: break complex tasks into steps, execute them, and report \
             results. When appropriate, create a task list to track progress."
                .to_string()
        }
        SessionMode::Code => {
            "You are Brokk, an AI coding assistant focused on code changes. Generate code \
             modifications, refactors, and implementations. Be concise and focus on the code."
                .to_string()
        }
        SessionMode::Ask => {
            "You are Brokk, an AI coding assistant. Answer questions about code, architecture, \
             and software engineering concepts. Be thorough but concise."
                .to_string()
        }
        SessionMode::Plan => {
            "You are Brokk, an AI coding assistant focused on planning. Analyze requirements, \
             design solutions, and create implementation plans. Do not write code directly."
                .to_string()
        }
    }
}
