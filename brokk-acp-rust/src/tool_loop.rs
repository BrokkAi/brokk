use std::sync::Arc;

use tokio_util::sync::CancellationToken;

use crate::llm_client::{ChatMessage, LlmBackend, LlmResponse, ToolDefinition};
use crate::tools::{ToolRegistry, ToolStatus};

const MAX_TOOL_RESULT_BYTES: usize = 50_000;

/// Run the agentic tool-calling loop.
///
/// Sends messages to the LLM with tool definitions. If the LLM responds with
/// tool calls, executes them, appends results, and loops. Stops when the LLM
/// responds with text only or the turn limit is reached.
///
/// `on_text` is called for each text token streamed from the LLM.
/// `on_tool_event` is called with a human-readable headline when a tool starts executing.
#[allow(clippy::too_many_arguments)]
pub async fn run(
    llm: &Arc<dyn LlmBackend>,
    registry: &ToolRegistry,
    model: &str,
    mut messages: Vec<ChatMessage>,
    max_turns: usize,
    cancel: CancellationToken,
    mut on_text: impl FnMut(&str),
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

        // We need on_text to receive tokens during streaming. Since the LlmBackend
        // takes a boxed FnMut, let's forward tokens through a shared buffer.
        let token_buf = Arc::new(std::sync::Mutex::new(Vec::<String>::new()));
        let buf_clone = token_buf.clone();
        let on_token: Box<dyn FnMut(&str) + Send> = Box::new(move |token: &str| {
            buf_clone.lock().unwrap().push(token.to_string());
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

        // Flush buffered tokens to the caller
        {
            let tokens = token_buf.lock().unwrap();
            for token in tokens.iter() {
                on_text(token);
            }
        }

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

                    let args: serde_json::Value =
                        serde_json::from_str(&call.function.arguments).unwrap_or_default();

                    tracing::info!(
                        "executing tool {} with args: {}",
                        tool_name,
                        call.function.arguments
                    );

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

                    messages.push(ChatMessage::tool_result(&call.id, tool_name, &output));
                }
            }
            Err(e) => {
                let err_msg = format!("\n**Error:** LLM request failed: {e}\n");
                on_text(&err_msg);
                full_response.push_str(&err_msg);
                break;
            }
        }
    }

    full_response
}
