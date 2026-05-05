//! Per-tool-call permission policy: classify, consult the in-session
//! "always allow" set, and -- if neither auto-allow nor auto-reject
//! applies -- escalate to the client via `session/request_permission`.
//!
//! Kept separate from the LLM turn loop in `tool_loop.rs`: this gate is
//! orthogonal to message streaming and would apply identically to any
//! other tool-running orchestrator.
//!
//! The `pure_gate_decision` helper is the no-I/O state machine and is
//! what the unit tests exercise; `consult` is the I/O wrapper that
//! reads the session, asks the user when needed, and persists
//! "always allow" approvals.

use std::time::Duration;

use agent_client_protocol::schema::{
    PermissionOption, PermissionOptionId, PermissionOptionKind, RequestPermissionOutcome,
    RequestPermissionRequest, ToolCallId, ToolCallStatus, ToolCallUpdate, ToolCallUpdateFields,
    ToolKind,
};
use agent_client_protocol::{Client, ConnectionTo};
use serde_json::Value;
use tokio_util::sync::CancellationToken;

use crate::session::{PermissionMode, SessionStore};

use super::announce;

/// Cap on how long we wait for the user to respond to `session/request_permission`.
/// Without a bound, an unattended IDE leaves the tool loop, cancellation token,
/// and spawned task parked indefinitely with no operator-visible signal.
const PERMISSION_REQUEST_TIMEOUT: Duration = Duration::from_secs(900);

/// Tools that must NEVER pick up an in-session "Always allow" -- every call
/// re-prompts. Today this is just the shell; even with prefix-scoped allow
/// keys the danger surface is too wide without an OS sandbox (tracked in
/// https://github.com/BrokkAi/brokk/issues/3390). Once that's wired, revisit.
fn requires_per_call_prompt(tool_name: &str) -> bool {
    tool_name == "runShellCommand"
}

/// Outcome of consulting the permission gate before executing a tool.
pub(super) enum GateDecision {
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

    pub(super) fn cx(&self) -> &ConnectionTo<Client> {
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
/// the user. Kept separate from `consult` so it can be tested in isolation.
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

/// Apply the per-call permission policy. Returns `Allow` if the tool should
/// execute, or `Reject(msg)` to feed the LLM a denial message instead.
#[allow(clippy::too_many_arguments)]
pub(super) async fn consult(
    sessions: &SessionStore,
    session_id: &str,
    spawned_cx: &SpawnedCx<'_>,
    cancel: &CancellationToken,
    tool_name: &str,
    kind: ToolKind,
    tool_call_id: &str,
    raw_input: &Value,
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
    raw_input: &Value,
) -> Result<bool, String> {
    // The permission modal needs to show *what* is being approved, not just
    // the tool kind. Reuse the same title-builder the standalone tool-call
    // card uses so e.g. ``Run `cargo test` `` appears in the prompt.
    let fields = ToolCallUpdateFields::new()
        .kind(kind)
        .status(ToolCallStatus::Pending)
        .title(announce::tool_title(tool_name, raw_input))
        .raw_input(raw_input.clone());
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
    // on `tool_loop::run`. We also race against cancellation so a `session/cancel`
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
