use std::sync::{Arc, Mutex};
use std::time::Duration;

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

/// Cap on how long we wait for the user to respond to `session/request_permission`.
/// Without a bound, an unattended IDE leaves the tool loop, cancellation token,
/// and spawned task parked indefinitely with no operator-visible signal.
const PERMISSION_REQUEST_TIMEOUT: Duration = Duration::from_secs(900);

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

/// Witness type proving the holder is executing inside a `cx.spawn(...)` body.
///
/// `block_task()` (used by `request_user_permission`) deadlocks the dispatch
/// loop if invoked directly from a request handler -- it must run on a task
/// spawned via `ConnectionTo::spawn`. By threading `SpawnedCx<'_>` through
/// every `block_task` caller, we move the rule from scattered SAFETY comments
/// to a single named constructor that anyone violating it has to read first.
///
/// The constructor is `pub(crate)` and intentionally undocumented as
/// constructable elsewhere -- there is no compile-time check that it is only
/// called inside `cx.spawn`, but the doc here is the choke point for review.
pub(crate) struct SpawnedCx<'a> {
    cx: &'a ConnectionTo<Client>,
}

impl<'a> SpawnedCx<'a> {
    /// Construct only inside a `cx.spawn(async move { ... })` future.
    /// Calling this from a request handler and then invoking `block_task`
    /// downstream will deadlock the dispatch loop.
    pub(crate) fn new(cx: &'a ConnectionTo<Client>) -> Self {
        Self { cx }
    }

    fn cx(&self) -> &ConnectionTo<Client> {
        self.cx
    }
}

/// Result of the non-prompting portion of the gate. Pure (no I/O) so the
/// state-machine matrix can be unit-tested without a live ACP `cx` or store.
#[derive(Debug, PartialEq, Eq)]
enum PureGateDecision {
    Allow,
    Reject(String),
    Prompt,
}

/// Pure permission-gate logic. Given the snapshot of mode + kind + name +
/// always-allow membership, decide whether to allow, reject, or escalate to
/// the user. Kept separate from `consult_gate` so it can be tested in
/// isolation.
fn pure_gate_decision(
    mode: PermissionMode,
    kind: ToolKind,
    tool_name: &str,
    is_always_allowed: bool,
) -> PureGateDecision {
    // bypassPermissions: trust everything. Explicit user opt-out of the gate.
    if matches!(mode, PermissionMode::BypassPermissions) {
        return PureGateDecision::Allow;
    }

    // read-only: only allow strictly informational kinds, regardless of the
    // always-allow set. `Other` (Bifrost-loaded tools we haven't classified)
    // is refused so the user-visible "Refuse every edit, deletion, move, or
    // shell command" promise actually holds.
    if matches!(mode, PermissionMode::ReadOnly)
        && !matches!(
            kind,
            ToolKind::Read | ToolKind::Search | ToolKind::Think | ToolKind::Fetch
        )
    {
        return PureGateDecision::Reject(
            "Tool use denied: read-only mode forbids edits, deletions, moves, shell execution, \
             and any tool not classified as read/search/think/fetch. \
             Switch the Permission menu to 'default' or 'acceptEdits' to run this tool."
                .to_string(),
        );
    }

    // Mode-independent auto-allow: pure-info kinds never mutate.
    let auto_allow = match kind {
        ToolKind::Read | ToolKind::Search | ToolKind::Think | ToolKind::Fetch => true,
        ToolKind::Edit if matches!(mode, PermissionMode::AcceptEdits) => true,
        _ => false,
    };
    if auto_allow {
        return PureGateDecision::Allow;
    }

    // In-session "Always allow". Skipped for tools where one approval would
    // be carte blanche (currently `runShellCommand`).
    if !requires_per_call_prompt(tool_name) && is_always_allowed {
        return PureGateDecision::Allow;
    }

    PureGateDecision::Prompt
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
    mut on_tool_event: impl FnMut(&str),
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
                        &spawned_cx,
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
    spawned_cx: &SpawnedCx<'_>,
    cancel: &CancellationToken,
    tool_name: &str,
    kind: ToolKind,
    tool_call_id: &str,
    raw_input: &str,
) -> GateDecision {
    let mode = match sessions.permission_mode(session_id).await {
        Some(m) => m,
        None => {
            tracing::warn!(
                session_id,
                tool_name,
                "permission gate: session not found; refusing tool"
            );
            return GateDecision::Reject(
                "Tool use denied: session is no longer registered. \
                 Start a new prompt to continue."
                    .to_string(),
            );
        }
    };
    let is_always_allowed = sessions.is_always_allowed(session_id, tool_name).await;

    match pure_gate_decision(mode, kind, tool_name, is_always_allowed) {
        PureGateDecision::Allow => GateDecision::Allow,
        PureGateDecision::Reject(msg) => GateDecision::Reject(msg),
        PureGateDecision::Prompt => {
            match request_user_permission(
                spawned_cx,
                cancel,
                session_id,
                tool_name,
                kind,
                tool_call_id,
                raw_input,
            )
            .await
            {
                Ok(allow_always) => {
                    // Even if a (stale or buggy) client returns allow_always for a
                    // per-call-prompt tool, refuse to persist it. Awaited inline so
                    // the next tool call in the same batch sees the updated set.
                    if allow_always && !requires_per_call_prompt(tool_name) {
                        sessions.add_always_allow(session_id, tool_name).await;
                    }
                    GateDecision::Allow
                }
                Err(reason) => GateDecision::Reject(reason),
            }
        }
    }
}

/// Send `session/request_permission` to the client and await the outcome.
/// Returns `Ok(allow_always)` if the user approved (with or without remembering),
/// or `Err(reason)` describing the rejection or transport failure.
async fn request_user_permission(
    spawned_cx: &SpawnedCx<'_>,
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
        .title(format!(
            "{} ({})",
            ToolRegistry::headline(tool_name),
            tool_name
        ))
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
    // ACP 0.11's `SentRequest` has no per-request cancel API. When our cancel
    // token fires, we drop the future on our side; the client is expected to
    // dismiss any stale permission modal on receipt of `session/cancel` (the
    // notification it sent us in the first place). If a buggy client leaves
    // the modal up and the user later clicks Allow, we'll log the orphan
    // arrival but otherwise ignore it -- the gate has already moved on.
    let response = tokio::select! {
        biased;
        _ = cancel.cancelled() => {
            tracing::warn!(
                session_id,
                tool_name,
                "permission request abandoned due to session cancel; client should dismiss the modal"
            );
            return Err("Tool use denied: the prompt was cancelled before the user responded.".to_string());
        }
        _ = tokio::time::sleep(PERMISSION_REQUEST_TIMEOUT) => {
            tracing::warn!(
                session_id,
                tool_name,
                "permission request timed out after {:?}",
                PERMISSION_REQUEST_TIMEOUT
            );
            return Err("Tool use denied: permission request timed out.".to_string());
        }
        r = spawned_cx.cx().send_request(request).block_task() => r,
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
            RequestPermissionOutcome::Cancelled => Err(
                "Tool use denied: the prompt was cancelled before the user responded.".to_string(),
            ),
            // Future-proof: schema is #[non_exhaustive].
            _ => Err("Tool use denied: unknown permission outcome.".to_string()),
        },
        Err(err) => {
            tracing::warn!("request_permission transport error: {err}");
            Err(format!(
                "Tool use denied: permission request failed ({err})."
            ))
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

#[cfg(test)]
mod tests {
    use super::*;

    fn decide(
        mode: PermissionMode,
        kind: ToolKind,
        tool_name: &str,
        allowed: bool,
    ) -> PureGateDecision {
        pure_gate_decision(mode, kind, tool_name, allowed)
    }

    #[test]
    fn bypass_allows_everything_without_consulting_always_allow() {
        for kind in [
            ToolKind::Read,
            ToolKind::Edit,
            ToolKind::Delete,
            ToolKind::Move,
            ToolKind::Execute,
            ToolKind::Other,
        ] {
            assert_eq!(
                decide(PermissionMode::BypassPermissions, kind, "anything", false),
                PureGateDecision::Allow,
                "bypass should allow {:?} regardless of always-allow",
                kind
            );
        }
    }

    #[test]
    fn read_only_allows_only_info_kinds() {
        for kind in [
            ToolKind::Read,
            ToolKind::Search,
            ToolKind::Think,
            ToolKind::Fetch,
        ] {
            assert_eq!(
                decide(PermissionMode::ReadOnly, kind, "anything", false),
                PureGateDecision::Allow,
                "read-only should allow info kind {:?}",
                kind
            );
        }
    }

    #[test]
    fn read_only_rejects_mutating_kinds_even_when_always_allowed() {
        // This is the regression we just fixed: ReadOnly must override
        // a prior "Always allow" for any non-info kind, including `Other`.
        for kind in [
            ToolKind::Edit,
            ToolKind::Delete,
            ToolKind::Move,
            ToolKind::Execute,
            ToolKind::Other,
        ] {
            assert!(
                matches!(
                    decide(PermissionMode::ReadOnly, kind, "any", true),
                    PureGateDecision::Reject(_)
                ),
                "read-only should reject {:?} even when always-allowed",
                kind
            );
        }
    }

    #[test]
    fn default_auto_allows_info_kinds_without_always_allow() {
        for kind in [
            ToolKind::Read,
            ToolKind::Search,
            ToolKind::Think,
            ToolKind::Fetch,
        ] {
            assert_eq!(
                decide(PermissionMode::Default, kind, "anything", false),
                PureGateDecision::Allow
            );
        }
    }

    #[test]
    fn default_prompts_for_edit_without_always_allow() {
        assert_eq!(
            decide(PermissionMode::Default, ToolKind::Edit, "writeFile", false),
            PureGateDecision::Prompt
        );
    }

    #[test]
    fn default_uses_always_allow_for_edit() {
        assert_eq!(
            decide(PermissionMode::Default, ToolKind::Edit, "writeFile", true),
            PureGateDecision::Allow
        );
    }

    #[test]
    fn accept_edits_auto_allows_edit_without_prior_approval() {
        assert_eq!(
            decide(
                PermissionMode::AcceptEdits,
                ToolKind::Edit,
                "writeFile",
                false
            ),
            PureGateDecision::Allow
        );
    }

    #[test]
    fn accept_edits_still_prompts_for_execute() {
        assert_eq!(
            decide(
                PermissionMode::AcceptEdits,
                ToolKind::Execute,
                "runShellCommand",
                false
            ),
            PureGateDecision::Prompt
        );
    }

    #[test]
    fn shell_command_never_uses_always_allow() {
        // runShellCommand is excluded from sticky approval -- even if the
        // session's always-allow set somehow contained it, every call must
        // re-prompt. Test both Default and AcceptEdits.
        for mode in [PermissionMode::Default, PermissionMode::AcceptEdits] {
            assert_eq!(
                decide(mode, ToolKind::Execute, "runShellCommand", true),
                PureGateDecision::Prompt,
                "runShellCommand must prompt in {:?} even when always-allowed",
                mode
            );
        }
    }

    #[test]
    fn permission_mode_round_trip() {
        for mode in [
            PermissionMode::Default,
            PermissionMode::AcceptEdits,
            PermissionMode::ReadOnly,
            PermissionMode::BypassPermissions,
        ] {
            assert_eq!(
                PermissionMode::parse(mode.as_str()),
                Some(mode),
                "round trip failed for {:?}",
                mode
            );
        }
    }
}
