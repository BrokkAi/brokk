use std::sync::{Arc, Mutex};

use agent_client_protocol::schema::{
    PermissionOption, PermissionOptionId, PermissionOptionKind, RequestPermissionOutcome,
    RequestPermissionRequest, ToolCallId, ToolCallStatus, ToolCallUpdate, ToolCallUpdateFields,
    ToolKind,
};
use agent_client_protocol::{Client, ConnectionTo};
use tokio_util::sync::CancellationToken;

use crate::llm_client::{ChatMessage, LlmBackend, LlmResponse, ToolDefinition};
use crate::session::{PermissionMode, SessionStore};
use crate::tools::{ToolRegistry, ToolStatus};

const MAX_TOOL_RESULT_BYTES: usize = 50_000;

/// Tools that must NEVER pick up an in-session "Always allow" — every call
/// re-prompts. Today this is just the shell; even with prefix-scoped allow
/// keys the danger surface is too wide without an OS sandbox (tracked in
/// https://github.com/BrokkAi/brokk/issues/3390). Once that's wired, revisit.
fn requires_per_call_prompt(tool_name: &str) -> bool {
    tool_name == "runShellCommand"
}

/// Shared text-emit callback. Held behind `Arc<Mutex<>>` so it can be cloned
/// into each streaming turn's `Box<dyn FnMut>` without being consumed.
pub type TextSink = Arc<Mutex<dyn FnMut(&str) + Send>>;

/// Outcome of consulting the permission gate before executing a tool.
enum GateDecision {
    /// Run the tool without prompting.
    Allow,
    /// Refuse the call; feed the LLM the given denial message instead.
    Reject(String),
}

/// Run the agentic tool-calling loop.
///
/// Sends messages to the LLM with tool definitions. If the LLM responds with
/// tool calls, executes them, appends results, and loops. Stops when the LLM
/// responds with text only or the turn limit is reached.
///
/// `on_text` is invoked for each text token streamed from the LLM, in real time.
/// `on_tool_event` is called with a human-readable headline when a tool starts executing.
///
/// Each tool call is gated through the session's permission policy: depending on
/// the session's `PermissionMode` and the tool's `ToolKind`, a call is auto-allowed,
/// auto-rejected, or escalated to the client via `session/request_permission`.
///
/// SAFETY: this function calls `SentRequest::block_task().await`, which is only
/// safe inside `ConnectionTo::spawn`. The caller (the `session/prompt` handler)
/// must therefore invoke this from a spawned task, not directly from the handler.
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
    cx: ConnectionTo<Client>,
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

                    // Consult the gate before announcing or executing the call.
                    let decision = consult_gate(
                        &sessions,
                        &session_id,
                        &cx,
                        &cancel,
                        &tool_name,
                        kind,
                        &call.id,
                        &call.function.arguments,
                    )
                    .await;

                    let output = match decision {
                        GateDecision::Reject(message) => {
                            on_tool_event(&format!("_{}_\n", message));
                            message
                        }
                        GateDecision::Allow => {
                            let headline = ToolRegistry::headline(&tool_name);
                            on_tool_event(&format!("_{headline}..._\n"));

                            tracing::info!(
                                "executing tool {} with args: {}",
                                tool_name,
                                call.function.arguments
                            );

                            execute_tool(registry, &tool_name, &call.function.arguments).await
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

/// Apply the per-call permission policy. Returns `Allow` if the tool should
/// execute, or `Reject(msg)` to feed the LLM a denial message instead.
#[allow(clippy::too_many_arguments)]
async fn consult_gate(
    sessions: &SessionStore,
    session_id: &str,
    cx: &ConnectionTo<Client>,
    cancel: &CancellationToken,
    tool_name: &str,
    kind: ToolKind,
    tool_call_id: &str,
    raw_input: &str,
) -> GateDecision {
    let mode = sessions
        .permission_mode(session_id)
        .await
        .unwrap_or(PermissionMode::Default);

    // bypassPermissions: trust everything. This is the explicit user
    // opt-out of the gate.
    if matches!(mode, PermissionMode::BypassPermissions) {
        return GateDecision::Allow;
    }

    // read-only mode: refuse anything that could mutate or shell out,
    // regardless of the always-allow set. Everything else falls through to
    // the mode-independent allow/ask logic below.
    if matches!(mode, PermissionMode::ReadOnly)
        && matches!(
            kind,
            ToolKind::Edit | ToolKind::Delete | ToolKind::Move | ToolKind::Execute
        )
    {
        return GateDecision::Reject(
            "Tool use denied: read-only mode forbids edits, deletions, moves, and shell execution. \
             Switch the Permission menu to 'default' or 'acceptEdits' to run this tool."
                .to_string(),
        );
    }

    // Non-prompting allow rules.
    let auto_allow = match kind {
        // Pure-info kinds are safe in every non-bypass mode -- they don't mutate.
        ToolKind::Read | ToolKind::Search | ToolKind::Think | ToolKind::Fetch => true,
        ToolKind::Edit if matches!(mode, PermissionMode::AcceptEdits) => true,
        _ => false,
    };
    if auto_allow {
        return GateDecision::Allow;
    }

    // In-session "Always allow" decisions. Skipped for tools where one
    // approval would be carte blanche (currently `runShellCommand`).
    if !requires_per_call_prompt(tool_name)
        && sessions.is_always_allowed(session_id, tool_name).await
    {
        return GateDecision::Allow;
    }

    // Escalate to the client.
    request_user_permission(cx, cancel, session_id, tool_name, kind, tool_call_id, raw_input)
        .await
        .map(|allow_always| {
            // Even if a (stale or buggy) client returns allow_always for a
            // per-call-prompt tool, refuse to persist it.
            if allow_always && !requires_per_call_prompt(tool_name) {
                let sessions = sessions.clone();
                let session_id = session_id.to_string();
                let tool_name = tool_name.to_string();
                tokio::spawn(async move {
                    sessions.add_always_allow(&session_id, &tool_name).await;
                });
            }
            GateDecision::Allow
        })
        .unwrap_or_else(GateDecision::Reject)
}

/// Send `session/request_permission` to the client and await the outcome.
/// Returns `Ok(allow_always)` if the user approved (with or without remembering),
/// or `Err(reason)` describing the rejection or transport failure.
async fn request_user_permission(
    cx: &ConnectionTo<Client>,
    cancel: &CancellationToken,
    session_id: &str,
    tool_name: &str,
    kind: ToolKind,
    tool_call_id: &str,
    raw_input: &str,
) -> Result<bool, String> {
    let raw_input_value = serde_json::from_str::<serde_json::Value>(raw_input).ok();

    let fields = ToolCallUpdateFields::new()
        .kind(kind)
        .status(ToolCallStatus::Pending)
        .title(format!("{} ({})", ToolRegistry::headline(tool_name), tool_name))
        .raw_input(raw_input_value);
    let tool_call = ToolCallUpdate::new(ToolCallId::new(tool_call_id.to_string()), fields);

    let mut options = Vec::with_capacity(3);
    if !requires_per_call_prompt(tool_name) {
        options.push(PermissionOption::new(
            PermissionOptionId::new("allow_always"),
            format!("Always allow {tool_name}"),
            PermissionOptionKind::AllowAlways,
        ));
    }
    options.push(PermissionOption::new(
        PermissionOptionId::new("allow"),
        "Allow",
        PermissionOptionKind::AllowOnce,
    ));
    options.push(PermissionOption::new(
        PermissionOptionId::new("reject"),
        "Reject",
        PermissionOptionKind::RejectOnce,
    ));

    let request = RequestPermissionRequest::new(session_id.to_string(), tool_call, options);

    // block_task() is only safe inside ConnectionTo::spawn; see the SAFETY note
    // on `run` above. We also race against cancellation so a `session/cancel`
    // notification doesn't leave us hanging on a prompt the user has abandoned.
    let response = tokio::select! {
        biased;
        _ = cancel.cancelled() => {
            return Err("Tool use denied: the prompt was cancelled before the user responded.".to_string());
        }
        r = cx.send_request(request).block_task() => r,
    };

    match response {
        Ok(resp) => match resp.outcome {
            RequestPermissionOutcome::Selected(selected) => {
                let id: &str = &selected.option_id.0;
                match id {
                    "allow_always" => Ok(true),
                    "allow" => Ok(false),
                    "reject" => Err("Tool use denied by user.".to_string()),
                    other => {
                        tracing::warn!(
                            "request_permission returned unknown option id '{other}'; treating as reject"
                        );
                        Err("Tool use denied (unknown option selected).".to_string())
                    }
                }
            }
            RequestPermissionOutcome::Cancelled => {
                Err("Tool use denied: the prompt was cancelled before the user responded.".to_string())
            }
            // Future-proof: schema is #[non_exhaustive].
            _ => Err("Tool use denied: unknown permission outcome.".to_string()),
        },
        Err(err) => {
            tracing::warn!("request_permission transport error: {err}");
            Err(format!("Tool use denied: permission request failed ({err})."))
        }
    }
}

/// Run the tool against the registry and format the result for the LLM.
async fn execute_tool(registry: &ToolRegistry, tool_name: &str, raw_args: &str) -> String {
    match serde_json::from_str::<serde_json::Value>(raw_args) {
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
                raw_args
            );
            format!(
                "Error: tool arguments are not valid JSON ({e}). \
                 Please retry with a valid JSON object matching the tool schema."
            )
        }
    }
}
