use std::sync::{Arc, Mutex};

use tokio_util::sync::CancellationToken;

use crate::llm_client::{ChatMessage, LlmBackend, LlmResponse, ToolDefinition};
use crate::tools::{ToolRegistry, ToolStatus};

const MAX_TOOL_RESULT_BYTES: usize = 50_000;

/// Shared text-emit callback. Held behind `Arc<Mutex<>>` so it can be cloned
/// into each streaming turn's `Box<dyn FnMut>` without being consumed.
pub type TextSink = Arc<Mutex<dyn FnMut(&str) + Send>>;

/// Run the agentic tool-calling loop.
///
/// Sends messages to the LLM with tool definitions. If the LLM responds with
/// tool calls, executes them, appends results, and loops. Stops when the LLM
/// responds with text only or the turn limit is reached.
///
/// `on_text` is invoked for each text token streamed from the LLM, in real time.
/// `on_tool_event` is called with a human-readable headline when a tool starts executing.
#[allow(clippy::too_many_arguments)]
pub async fn run(
    llm: &Arc<dyn LlmBackend>,
    registry: &ToolRegistry,
    model: &str,
    mut messages: Vec<ChatMessage>,
    max_turns: usize,
    cancel: CancellationToken,
    on_text: TextSink,
    mut on_tool_event: impl FnMut(&str),
) -> String {
    let tools: Vec<ToolDefinition> = registry.tool_definitions();
    let mut full_response = String::new();

    for turn in 0..max_turns {
        if cancel.is_cancelled() {
            break;
        }

        // For the last turn, don't offer tools -- force a text response
        let turn_tools = if turn < max_turns - 1 {
            Some(tools.clone())
        } else {
            None
        };

        // Forward tokens straight through to the caller; no buffering.
        let sink = on_text.clone();
        let on_token: Box<dyn FnMut(&str) + Send> = Box::new(move |token: &str| {
            if let Ok(mut cb) = sink.lock() {
                cb(token);
            }
        });

        let response = llm
            .stream_chat(
                model,
                messages.clone(),
                turn_tools,
                on_token,
                cancel.clone(),
            )
            .await;

        match response {
            Ok(LlmResponse::Text(text)) => {
                full_response.push_str(&text);
                // Final text response -- we're done
                break;
            }
            Ok(LlmResponse::ToolCalls { text, calls }) => {
                // Any text emitted before tool calls
                if !text.is_empty() {
                    full_response.push_str(&text);
                }

                // Record the assistant message with tool_calls
                messages.push(ChatMessage::assistant_tool_calls(calls.clone()));

                // Execute each tool call
                for call in &calls {
                    let tool_name = &call.function.name;
                    let headline = ToolRegistry::headline(tool_name);
                    on_tool_event(&format!("_{headline}..._\n"));

                    tracing::info!(
                        "executing tool {} with args: {}",
                        tool_name,
                        call.function.arguments
                    );

                    let output =
                        match serde_json::from_str::<serde_json::Value>(&call.function.arguments) {
                            Ok(args) => {
                                let result = registry.execute(tool_name, args).await;
                                let status_prefix = match result.status {
                                    ToolStatus::Success => "",
                                    ToolStatus::RequestError => "Error: ",
                                    ToolStatus::InternalError => "Internal error: ",
                                };
                                let mut output = format!("{}{}", status_prefix, result.output);
                                if output.len() > MAX_TOOL_RESULT_BYTES {
                                    output.truncate(MAX_TOOL_RESULT_BYTES);
                                    output.push_str("\n... output truncated");
                                }
                                output
                            }
                            Err(e) => {
                                tracing::warn!(
                                    "tool {} called with invalid JSON arguments ({}): {}",
                                    tool_name,
                                    e,
                                    call.function.arguments
                                );
                                format!(
                                    "Error: tool arguments are not valid JSON ({e}). \
                                 Please retry with a valid JSON object matching the tool schema."
                                )
                            }
                        };

                    messages.push(ChatMessage::tool_result(&call.id, tool_name, &output));
                }
            }
            Err(e) => {
                let err_msg = format!("\n**Error:** LLM request failed: {e}\n");
                if let Ok(mut cb) = on_text.lock() {
                    cb(&err_msg);
                }
                full_response.push_str(&err_msg);
                break;
            }
        }
    }

    full_response
}
