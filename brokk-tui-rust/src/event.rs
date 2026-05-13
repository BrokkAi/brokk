//! Types crossing the boundary between the ACP runtime and the UI task.
//!
//! The ACP runtime owns the JSON-RPC dispatch loop and must never block on
//! terminal I/O; the UI task owns the terminal and must never block on
//! network I/O. They communicate over two unbounded mpsc channels.

use agent_client_protocol::schema::{
    ContentBlock, PermissionOption, SessionUpdate, StopReason, ToolCallUpdate,
};
use tokio::sync::oneshot;

/// Events flowing from the ACP runtime into the UI task.
#[derive(Debug)]
pub enum UiEvent {
    /// Agent finished initialization handshake; UI can flip out of the
    /// "connecting" splash.
    Connected {
        agent_name: Option<String>,
        agent_version: Option<String>,
    },
    /// A new session has been opened; future updates carry this session id.
    SessionStarted { session_id: String },
    /// A streaming or status update from the agent. We forward the raw
    /// `SessionUpdate` enum and let the UI state machine decide how to
    /// fold each variant into the transcript.
    SessionUpdate(SessionUpdate),
    /// `session/request_permission` from the agent. The UI is expected to
    /// render a modal and answer through `responder` exactly once.
    PermissionRequest(PermissionPrompt),
    /// The prompt turn completed (PromptRequest returned). UI can re-enable
    /// the input prompt.
    PromptDone { stop_reason: StopReason },
    /// A non-fatal error from the runtime (e.g. transport hiccup we
    /// recovered from). Shown in the status line.
    Warning(String),
    /// Fatal error; the runtime is shutting down. UI should display the
    /// message and exit.
    Fatal(String),
}

/// A pending permission request. The UI owns `responder` until the user
/// picks an option or cancels with Esc.
#[derive(Debug)]
pub struct PermissionPrompt {
    pub tool_call: ToolCallUpdate,
    pub options: Vec<PermissionOption>,
    /// One-shot to the ACP runtime. Sending `Some(option_id)` selects an
    /// option; `None` cancels. Dropping the sender is treated as cancel.
    pub responder: oneshot::Sender<PermissionDecision>,
}

#[derive(Debug, Clone)]
pub enum PermissionDecision {
    Selected(String),
    Cancelled,
}

/// Commands flowing from the UI task into the ACP runtime.
#[derive(Debug)]
pub enum UiCommand {
    /// Send a user prompt for the current session.
    SendPrompt { text: String },
    /// Cancel the in-flight prompt turn (Ctrl-C while streaming).
    CancelPrompt,
    /// Tear down: kill the agent child and exit.
    Shutdown,
}

/// Convenience: pull plain text out of a content block for rendering.
/// Non-text blocks are summarized so the user knows something was sent.
pub fn content_block_text(block: &ContentBlock) -> String {
    match block {
        ContentBlock::Text(t) => t.text.clone(),
        ContentBlock::Image(_) => "[image]".to_string(),
        ContentBlock::Audio(_) => "[audio]".to_string(),
        ContentBlock::ResourceLink(link) => format!("[link {}]", link.uri),
        ContentBlock::Resource(_) => "[resource]".to_string(),
        _ => "[unknown content]".to_string(),
    }
}
