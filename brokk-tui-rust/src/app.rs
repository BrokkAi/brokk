//! UI state machine.
//!
//! Holds the transcript, current tool-call table, input buffer, and the
//! at-most-one pending permission prompt. Every incoming ACP event is
//! folded in through `apply_event`; ratatui then renders from this state.

use std::collections::HashMap;

use agent_client_protocol::schema::{
    AvailableCommand, Plan, PlanEntry, SessionUpdate, StopReason, ToolCall, ToolCallContent,
    ToolCallStatus, ToolCallUpdate, ToolKind,
};

use crate::event::{PermissionPrompt, UiEvent, content_block_text};

/// One entry in the scrolling transcript.
#[derive(Debug, Clone)]
pub enum Entry {
    /// Plain user prompt (echoed locally as soon as it is sent).
    UserPrompt(String),
    /// Streaming agent reply. Mutated in place as chunks arrive.
    AgentMessage(String),
    /// Streaming agent reasoning ("thoughts").
    AgentThought(String),
    /// A tool call slot identified by id. The body is rendered from
    /// `tool_calls[id]`; we keep an entry pointer so it shows up in order.
    ToolCall(String),
    /// Latest plan posted by the agent.
    Plan(Vec<PlanEntry>),
    /// System-level note (errors, warnings, mode changes).
    System(String),
}

#[derive(Debug, Clone)]
pub struct ToolCallView {
    pub title: String,
    pub kind: ToolKind,
    pub status: ToolCallStatus,
    pub body: Vec<String>,
}

impl ToolCallView {
    fn from_tool_call(tc: &ToolCall) -> Self {
        let mut v = Self {
            title: tc.title.clone(),
            kind: tc.kind,
            status: tc.status,
            body: Vec::new(),
        };
        v.set_content(&tc.content);
        v
    }

    fn apply_update(&mut self, u: &ToolCallUpdate) {
        if let Some(t) = &u.fields.title {
            self.title = t.clone();
        }
        if let Some(k) = u.fields.kind {
            self.kind = k;
        }
        if let Some(s) = u.fields.status {
            self.status = s;
        }
        if let Some(c) = &u.fields.content {
            self.set_content(c);
        }
    }

    fn set_content(&mut self, content: &[ToolCallContent]) {
        self.body.clear();
        for c in content {
            match c {
                ToolCallContent::Content(block) => {
                    self.body.push(content_block_text(&block.content));
                }
                ToolCallContent::Diff(d) => {
                    self.body.push(format!("[diff {}]", d.path.display()));
                }
                ToolCallContent::Terminal(t) => {
                    self.body.push(format!("[terminal {}]", t.terminal_id));
                }
                _ => self.body.push("[unsupported tool content]".to_string()),
            }
        }
    }
}

/// Status of the current prompt turn.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TurnState {
    /// No prompt in flight; user can type and send.
    Idle,
    /// We sent a PromptRequest and are waiting for chunks.
    Streaming,
}

#[derive(Debug)]
pub struct AppState {
    pub agent_label: String,
    pub session_id: Option<String>,
    pub connection_status: String,
    pub current_mode: Option<String>,
    pub available_commands: Vec<AvailableCommand>,
    pub transcript: Vec<Entry>,
    pub tool_calls: HashMap<String, ToolCallView>,
    pub input: String,
    pub turn: TurnState,
    pub pending_permission: Option<PendingPermission>,
    /// Scroll offset measured in rendered lines from the bottom of the
    /// transcript. `0` keeps the view pinned to the newest line.
    pub scroll_offset: u16,
    pub should_quit: bool,
    /// Transient status line (cleared on next event).
    pub status_line: Option<String>,
}

#[derive(Debug)]
pub struct PendingPermission {
    pub prompt: PermissionPrompt,
    pub selected: usize,
}

impl AppState {
    pub fn new() -> Self {
        Self {
            agent_label: String::new(),
            session_id: None,
            connection_status: "connecting...".to_string(),
            current_mode: None,
            available_commands: Vec::new(),
            transcript: Vec::new(),
            tool_calls: HashMap::new(),
            input: String::new(),
            turn: TurnState::Idle,
            pending_permission: None,
            scroll_offset: 0,
            should_quit: false,
            status_line: None,
        }
    }

    /// Push a user prompt into the transcript immediately, before the
    /// command reaches the runtime. Keeps the UI responsive.
    pub fn record_user_prompt(&mut self, text: String) {
        self.transcript.push(Entry::UserPrompt(text));
        self.turn = TurnState::Streaming;
        self.scroll_offset = 0;
    }

    pub fn apply_event(&mut self, event: UiEvent) {
        match event {
            UiEvent::Connected {
                agent_name,
                agent_version,
            } => {
                self.agent_label = match (agent_name, agent_version) {
                    (Some(n), Some(v)) => format!("{n} {v}"),
                    (Some(n), None) => n,
                    _ => "agent".to_string(),
                };
                self.connection_status = format!("connected to {}", self.agent_label);
            }
            UiEvent::SessionStarted { session_id } => {
                self.session_id = Some(session_id);
                self.connection_status = format!("session ready ({})", self.agent_label);
            }
            UiEvent::SessionUpdate(u) => self.apply_session_update(u),
            UiEvent::PermissionRequest(prompt) => {
                self.pending_permission = Some(PendingPermission {
                    prompt,
                    selected: 0,
                });
            }
            UiEvent::PromptDone { stop_reason } => {
                self.turn = TurnState::Idle;
                self.status_line = Some(format!("turn done: {stop_reason:?}"));
            }
            UiEvent::Warning(msg) => {
                self.status_line = Some(msg);
            }
            UiEvent::Fatal(msg) => {
                self.transcript.push(Entry::System(format!("fatal: {msg}")));
                self.connection_status = "disconnected".to_string();
                self.turn = TurnState::Idle;
            }
        }
    }

    fn apply_session_update(&mut self, update: SessionUpdate) {
        self.scroll_offset = 0;
        match update {
            SessionUpdate::UserMessageChunk(c) => {
                // During an active prompt turn (`Streaming`), the user's
                // message was already echoed locally via
                // `record_user_prompt` for immediate feedback. The agent
                // may replay the same text as a `UserMessageChunk`;
                // suppressing it here keeps the transcript from showing
                // the prompt twice. When the session is `Idle`, this
                // chunk is part of a session replay (e.g. from
                // `session/load`) and the only source of that user
                // message, so we render it.
                if self.turn == TurnState::Streaming {
                    return;
                }
                let text = content_block_text(&c.content);
                append_or_start(&mut self.transcript, EntryKind::User, text);
            }
            SessionUpdate::AgentMessageChunk(c) => {
                let text = content_block_text(&c.content);
                append_or_start(&mut self.transcript, EntryKind::Agent, text);
            }
            SessionUpdate::AgentThoughtChunk(c) => {
                let text = content_block_text(&c.content);
                append_or_start(&mut self.transcript, EntryKind::Thought, text);
            }
            SessionUpdate::ToolCall(tc) => {
                let id = tc.tool_call_id.to_string();
                self.tool_calls
                    .insert(id.clone(), ToolCallView::from_tool_call(&tc));
                self.transcript.push(Entry::ToolCall(id));
            }
            SessionUpdate::ToolCallUpdate(u) => {
                let id = u.tool_call_id.to_string();
                if let Some(view) = self.tool_calls.get_mut(&id) {
                    view.apply_update(&u);
                } else {
                    // Update before create; synthesize a placeholder.
                    let mut view = ToolCallView {
                        title: u.fields.title.clone().unwrap_or_else(|| "tool".to_string()),
                        kind: u.fields.kind.unwrap_or(ToolKind::Other),
                        status: u.fields.status.unwrap_or(ToolCallStatus::Pending),
                        body: Vec::new(),
                    };
                    if let Some(content) = &u.fields.content {
                        view.set_content(content);
                    }
                    self.tool_calls.insert(id.clone(), view);
                    self.transcript.push(Entry::ToolCall(id));
                }
            }
            SessionUpdate::Plan(Plan { entries, .. }) => {
                // Replace the most recent Plan entry if present, else push.
                if let Some(Entry::Plan(existing)) = self
                    .transcript
                    .iter_mut()
                    .rev()
                    .find(|e| matches!(e, Entry::Plan(_)))
                {
                    *existing = entries;
                } else {
                    self.transcript.push(Entry::Plan(entries));
                }
            }
            SessionUpdate::AvailableCommandsUpdate(u) => {
                self.available_commands = u.available_commands;
            }
            SessionUpdate::CurrentModeUpdate(u) => {
                let mode = u.current_mode_id.to_string();
                self.current_mode = Some(mode.clone());
                self.transcript.push(Entry::System(format!("mode: {mode}")));
            }
            SessionUpdate::ConfigOptionUpdate(_) => {
                // Config options are rendered through the available list;
                // we surface the raw event as a system note for now.
                self.transcript
                    .push(Entry::System("config option updated".to_string()));
            }
            SessionUpdate::SessionInfoUpdate(info) => {
                if let Some(title) = info.title.value() {
                    self.transcript
                        .push(Entry::System(format!("session title: {title}")));
                }
            }
            _ => {
                self.transcript
                    .push(Entry::System("unsupported session update".to_string()));
            }
        }
    }
}

#[derive(PartialEq, Eq)]
enum EntryKind {
    User,
    Agent,
    Thought,
}

/// Append `text` to the trailing entry of the same kind, or start a new
/// entry. Streaming chunks for the same logical message land in one entry.
fn append_or_start(transcript: &mut Vec<Entry>, kind: EntryKind, text: String) {
    if let Some(last) = transcript.last_mut() {
        match (&kind, last) {
            (EntryKind::User, Entry::UserPrompt(s))
            | (EntryKind::Agent, Entry::AgentMessage(s))
            | (EntryKind::Thought, Entry::AgentThought(s)) => {
                s.push_str(&text);
                return;
            }
            _ => {}
        }
    }
    transcript.push(match kind {
        EntryKind::User => Entry::UserPrompt(text),
        EntryKind::Agent => Entry::AgentMessage(text),
        EntryKind::Thought => Entry::AgentThought(text),
    });
}

/// Format a permission option label for the modal. Returned strings are
/// printable without further processing.
pub fn permission_kind_label(
    kind: agent_client_protocol::schema::PermissionOptionKind,
) -> &'static str {
    use agent_client_protocol::schema::PermissionOptionKind as K;
    match kind {
        K::AllowOnce => "allow once",
        K::AllowAlways => "allow always",
        K::RejectOnce => "reject once",
        K::RejectAlways => "reject always",
        _ => "other",
    }
}

/// Pretty-print a `StopReason` for the status bar.
pub fn stop_reason_label(reason: StopReason) -> &'static str {
    match reason {
        StopReason::EndTurn => "end_turn",
        StopReason::MaxTokens => "max_tokens",
        StopReason::MaxTurnRequests => "max_turn_requests",
        StopReason::Refusal => "refusal",
        StopReason::Cancelled => "cancelled",
        _ => "other",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use agent_client_protocol::schema::{ContentBlock, ContentChunk, TextContent};

    fn text_chunk(s: &str) -> ContentChunk {
        ContentChunk::new(ContentBlock::Text(TextContent::new(s)))
    }

    #[test]
    fn streaming_agent_chunks_coalesce() {
        let mut s = AppState::new();
        s.apply_event(UiEvent::SessionUpdate(SessionUpdate::AgentMessageChunk(
            text_chunk("hello "),
        )));
        s.apply_event(UiEvent::SessionUpdate(SessionUpdate::AgentMessageChunk(
            text_chunk("world"),
        )));
        assert_eq!(s.transcript.len(), 1);
        match &s.transcript[0] {
            Entry::AgentMessage(s) => assert_eq!(s, "hello world"),
            other => panic!("unexpected entry: {other:?}"),
        }
    }

    #[test]
    fn tool_call_update_merges() {
        let mut s = AppState::new();
        let tc = ToolCall::new("call-1", "running ls");
        s.apply_event(UiEvent::SessionUpdate(SessionUpdate::ToolCall(tc)));
        let mut fields = agent_client_protocol::schema::ToolCallUpdateFields::default();
        fields.status = Some(ToolCallStatus::Completed);
        let update = ToolCallUpdate::new("call-1", fields);
        s.apply_event(UiEvent::SessionUpdate(SessionUpdate::ToolCallUpdate(
            update,
        )));
        let view = s.tool_calls.get("call-1").expect("view");
        assert_eq!(view.status, ToolCallStatus::Completed);
        assert_eq!(view.title, "running ls");
    }

    #[test]
    fn prompt_done_returns_to_idle() {
        let mut s = AppState::new();
        s.record_user_prompt("test".to_string());
        assert_eq!(s.turn, TurnState::Streaming);
        s.apply_event(UiEvent::PromptDone {
            stop_reason: StopReason::EndTurn,
        });
        assert_eq!(s.turn, TurnState::Idle);
    }

    #[test]
    fn user_chunk_suppressed_during_streaming_but_kept_on_replay() {
        // While a prompt is in flight, the local echo from
        // `record_user_prompt` is the source of truth -- any
        // `UserMessageChunk` the agent sends back is a duplicate and
        // must be dropped.
        let mut s = AppState::new();
        s.record_user_prompt("hello".to_string());
        assert_eq!(s.transcript.len(), 1);
        s.apply_event(UiEvent::SessionUpdate(SessionUpdate::UserMessageChunk(
            text_chunk("hello"),
        )));
        assert_eq!(
            s.transcript.len(),
            1,
            "agent echo must not double the user prompt while streaming"
        );

        // When the session is idle (e.g. mid-`session/load` replay), the
        // same chunk is the only source of truth for the user message
        // and must be rendered.
        let mut s = AppState::new();
        assert_eq!(s.turn, TurnState::Idle);
        s.apply_event(UiEvent::SessionUpdate(SessionUpdate::UserMessageChunk(
            text_chunk("replayed"),
        )));
        assert_eq!(s.transcript.len(), 1);
        match &s.transcript[0] {
            Entry::UserPrompt(t) => assert_eq!(t, "replayed"),
            other => panic!("unexpected: {other:?}"),
        }
    }
}
