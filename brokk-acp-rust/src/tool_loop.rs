mod announce;
mod gate;

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use agent_client_protocol::schema::{Diff, SessionNotification, SessionUpdate};
use agent_client_protocol::{Client, ConnectionTo};
use serde_json::Value;
use tokio_util::sync::CancellationToken;

use crate::llm_client::{ChatMessage, LlmBackend, LlmResponse, ToolDefinition};
use crate::session::SessionStore;
use crate::tools::sandbox::SandboxPolicy;
use crate::tools::{ToolRegistry, ToolStatus, safe_resolve_for_write};

use gate::GateDecision;

pub(crate) use gate::SpawnedCx;

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
/// Tool-call lifecycle is reported to the client via `SessionUpdate::ToolCall`
/// and `SessionUpdate::ToolCallUpdate` notifications (Pending -> InProgress ->
/// Completed/Failed).
///
/// Each tool call is gated through the session's permission policy: depending on
/// the session's `PermissionMode` and the tool's `ToolKind`, a call is auto-allowed,
/// auto-rejected, or escalated to the client via `session/request_permission`.
///
/// SAFETY: this function calls `SentRequest::block_task().await`, which is only
/// safe inside `ConnectionTo::spawn`. The `SpawnedCx<'_>` parameter encodes
/// that requirement -- callers must construct it inside a spawned task.
#[allow(clippy::too_many_arguments)]
pub(crate) async fn run(
    llm: &Arc<dyn LlmBackend>,
    registry: &ToolRegistry,
    model: &str,
    mut messages: Vec<ChatMessage>,
    max_turns: usize,
    cancel: CancellationToken,
    on_text: TextSink,
    spawned_cx: SpawnedCx<'_>,
    session_id: String,
    sessions: SessionStore,
) -> String {
    let tools: Vec<ToolDefinition> = registry.tool_definitions();
    let mut full_response = String::new();

    'outer: for turn in 0..max_turns {
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

        // Wall-clock bound on this stream is enforced by the reqwest client's
        // own `.timeout(...)` (see `OpenAiClient::new`). Per-chunk idle
        // inactivity (the case in #3366 / #3453: streams that drip
        // occasional bytes and would defeat wall-clock) is enforced inside
        // `OpenAiClient::stream_chat_impl` via `IDLE_CHUNK_TIMEOUT`.
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
                    if cancel.is_cancelled() {
                        // The user cancelled mid-batch; stop issuing more
                        // permission prompts and tool executions.
                        break 'outer;
                    }

                    let tool_name = call.function.name.clone();
                    let kind = ToolRegistry::tool_kind(&tool_name);

                    // Parse the LLM's serialized arguments up front so the
                    // tool-call card can pull `path` / `command` / `pattern`
                    // out for the title, and so an arg-parse failure becomes
                    // a Failed card rather than a silent fallback.
                    let parsed_input = match serde_json::from_str::<Value>(&call.function.arguments)
                    {
                        Ok(v) => v,
                        Err(e) => {
                            let reason = format!(
                                "Error: tool arguments are not valid JSON ({e}). \
                                 Please retry with a valid JSON object matching the tool schema."
                            );
                            // Render the card anyway so the user sees what
                            // the agent tried to invoke -- raw_input falls
                            // back to the unparsed string.
                            send_session_update(
                                spawned_cx.cx(),
                                &session_id,
                                SessionUpdate::ToolCall(announce::initial_tool_call(
                                    &call.id,
                                    &tool_name,
                                    kind,
                                    &Value::String(call.function.arguments.clone()),
                                )),
                            );
                            send_session_update(
                                spawned_cx.cx(),
                                &session_id,
                                SessionUpdate::ToolCallUpdate(announce::update_failed(
                                    &call.id,
                                    &reason,
                                    Some(Value::String(reason.clone())),
                                )),
                            );
                            messages.push(ChatMessage::tool_result(&call.id, &tool_name, &reason));
                            continue;
                        }
                    };

                    // Pending -- emit the card before the gate runs so the
                    // permission modal (which reuses this id) renders against
                    // a card that already shows path / command / etc.
                    send_session_update(
                        spawned_cx.cx(),
                        &session_id,
                        SessionUpdate::ToolCall(announce::initial_tool_call(
                            &call.id,
                            &tool_name,
                            kind,
                            &parsed_input,
                        )),
                    );

                    // Consult the gate before announcing or executing the call.
                    let decision = gate::consult(
                        &sessions,
                        &session_id,
                        &spawned_cx,
                        &cancel,
                        &tool_name,
                        kind,
                        &call.id,
                        &parsed_input,
                    )
                    .await;

                    let output = match decision {
                        GateDecision::Reject(message) => {
                            // Failed terminal update so the card reflects the
                            // denial and doesn't sit at Pending forever.
                            send_session_update(
                                spawned_cx.cx(),
                                &session_id,
                                SessionUpdate::ToolCallUpdate(announce::update_failed(
                                    &call.id,
                                    &message,
                                    Some(Value::String(message.clone())),
                                )),
                            );
                            message
                        }
                        GateDecision::Allow => {
                            send_session_update(
                                spawned_cx.cx(),
                                &session_id,
                                SessionUpdate::ToolCallUpdate(announce::update_in_progress(
                                    &call.id,
                                )),
                            );

                            // Capture pre-write content so writeFile gets a
                            // real Diff card. Outer None == not a writeFile,
                            // or prior content unavailable (binary, missing
                            // parent dir we can't resolve, etc) -- in either
                            // case we fall back to text content. Inner None
                            // (per ACP `Diff.old_text` schema) == new file.
                            let pre_write: Option<Option<String>> = if tool_name == "writeFile" {
                                capture_pre_write_text(registry.cwd(), &parsed_input)
                            } else {
                                None
                            };

                            // Resolve the sandbox tier from the session's permission mode.
                            // If the session disappeared between gate-accept and exec
                            // (race), fail safe to ReadOnly: the gate already cleared
                            // the call but we no longer trust the mode lookup.
                            let policy = match sessions.permission_mode(&session_id).await {
                                Some(mode) => SandboxPolicy::from_permission_mode(mode),
                                None => {
                                    tracing::warn!(
                                        session_id,
                                        tool_name,
                                        "session vanished between gate-accept and exec; falling back to ReadOnly sandbox"
                                    );
                                    SandboxPolicy::ReadOnly
                                }
                            };

                            tracing::info!(
                                "executing tool {} with args: {} (sandbox={:?})",
                                tool_name,
                                call.function.arguments,
                                policy
                            );

                            let exec =
                                execute_tool(registry, &tool_name, parsed_input.clone(), policy)
                                    .await;

                            // Build the terminal update -- Completed (with a
                            // Diff for writeFile when we have prior content)
                            // or Failed (for tool-reported errors).
                            let update = if exec.failed {
                                announce::update_failed(
                                    &call.id,
                                    &exec.output,
                                    Some(Value::String(exec.output.clone())),
                                )
                            } else {
                                let diff = pre_write
                                    .and_then(|prior| build_write_diff(&parsed_input, prior));
                                announce::update_completed(&call.id, &exec.output, diff)
                            };
                            send_session_update(
                                spawned_cx.cx(),
                                &session_id,
                                SessionUpdate::ToolCallUpdate(update),
                            );
                            exec.output
                        }
                    };

                    messages.push(ChatMessage::tool_result(&call.id, &tool_name, &output));
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

/// Outcome of executing a tool, formatted for both the LLM (via `output`)
/// and the client card (`failed` -> `ToolCallStatus::Failed`).
struct ToolExecution {
    output: String,
    failed: bool,
}

/// Run the tool against the registry and format the result for the LLM.
/// Arg-parse failure is handled in the caller so it can render a Failed
/// card; this function only sees already-parsed inputs.
async fn execute_tool(
    registry: &ToolRegistry,
    tool_name: &str,
    args: Value,
    policy: SandboxPolicy,
) -> ToolExecution {
    let result = registry.execute(tool_name, args, policy).await;
    let (status_prefix, failed) = match result.status {
        ToolStatus::Success => ("", false),
        ToolStatus::RequestError => ("Error: ", true),
        ToolStatus::InternalError => ("Internal error: ", true),
    };
    let mut output = format!("{}{}", status_prefix, result.output);
    if output.len() > MAX_TOOL_RESULT_BYTES {
        // Truncate on a UTF-8 char boundary; otherwise an emoji or accented
        // byte sequence could leave the slice mid-codepoint.
        let mut cut = MAX_TOOL_RESULT_BYTES;
        while !output.is_char_boundary(cut) {
            cut -= 1;
        }
        output.truncate(cut);
        output.push_str("\n... output truncated");
    }
    ToolExecution { output, failed }
}

/// Send a `SessionNotification` and log on failure -- there is nothing
/// useful we can do if the channel to the client is broken.
fn send_session_update(cx: &ConnectionTo<Client>, session_id: &str, update: SessionUpdate) {
    let notification = SessionNotification::new(session_id.to_string(), update);
    if let Err(e) = cx.send_notification(notification) {
        tracing::warn!("failed to send session update: {e}");
    }
}

/// Read the existing file content for a `writeFile` call so the diff
/// card can show before/after text.
///
/// Returns `Some(Some(text))` when we read the prior content, `Some(None)`
/// when the file is new (per the ACP `Diff.old_text` schema, `None` is
/// the "no prior content" sentinel), and `None` when prior content is
/// unavailable -- e.g. binary file, unreadable, or path can't be resolved
/// against cwd. The outer `None` tells the caller to fall back to text
/// content for the card.
fn capture_pre_write_text(cwd: &Path, parsed_input: &Value) -> Option<Option<String>> {
    let path = parsed_input.get("path").and_then(Value::as_str)?;
    let resolved = safe_resolve_for_write(cwd, path).ok()?;
    if !resolved.exists() {
        return Some(None);
    }
    match std::fs::read_to_string(&resolved) {
        Ok(text) => Some(Some(text)),
        Err(_) => None,
    }
}

/// Assemble a `Diff` block for a successful `writeFile` from the parsed
/// args plus the captured prior content. Returns `None` if we couldn't
/// pull the path/content (in which case the caller falls back to text).
fn build_write_diff(parsed_input: &Value, prior: Option<String>) -> Option<Diff> {
    let path = parsed_input.get("path").and_then(Value::as_str)?;
    let new_text = parsed_input
        .get("content")
        .and_then(Value::as_str)
        .unwrap_or("");
    let mut diff = Diff::new(PathBuf::from(path), new_text);
    diff.old_text = prior;
    Some(diff)
}
