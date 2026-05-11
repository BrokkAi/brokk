//! Payload types for Tauri events emitted from the Rust backend to the
//! Svelte frontend. The channel names are stable wire identifiers.

use serde::{Deserialize, Serialize};
use serde_json::Value;

/// Channel: every ACP `SessionUpdate` notification received from the active
/// agent. v1 has at most one live session so we don't suffix the channel
/// with a session id — the frontend mirrors the same constraint.
pub const ACP_UPDATE_CHANNEL: &str = "acp:update";

/// Channel: lifecycle transitions of the active session.
pub const ACP_SESSION_STATUS_CHANNEL: &str = "acp:session_status";

/// Channel: incoming `requestPermission` from the agent. The frontend
/// surfaces a modal and calls `respond_permission(req_id, accept)` back.
pub const ACP_PERMISSION_CHANNEL: &str = "acp:permission";

/// Channel: stderr lines from the spawned agent process (best-effort tail
/// for the agents page on failure).
pub const ACP_STDERR_CHANNEL: &str = "acp:stderr";

/// One forwarded `SessionUpdate`. We pass the raw schema notification as
/// JSON so the frontend can render new update variants without a Rust
/// release.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AcpUpdate {
    pub session_id: String,
    pub update: Value,
}

/// One forwarded `requestPermission`. The frontend keys its modal by
/// `request_id` and posts the user's choice back via the
/// `respond_permission` command.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PermissionRequest {
    pub request_id: String,
    pub session_id: String,
    pub tool_call: Value,
    pub options: Value,
}

/// Lifecycle states the frontend cares about for the session pane.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case", tag = "kind")]
pub enum SessionStatus {
    Starting {
        agent_id: String,
    },
    Ready {
        agent_id: String,
        session_id: String,
    },
    AuthRequired {
        agent_id: String,
        auth_kind: String,
    },
    Stopped {
        agent_id: String,
        reason: String,
    },
    Failed {
        agent_id: String,
        error: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AcpStderr {
    pub agent_id: String,
    pub line: String,
}
