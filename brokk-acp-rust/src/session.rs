use std::collections::{HashMap, HashSet};
use std::io::{Read as IoRead, Write as IoWrite};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use serde::{Deserialize, Serialize};
use tokio::sync::RwLock;
use tokio_util::sync::CancellationToken;

use crate::tools::ToolRegistry;

// ---------------------------------------------------------------------------
// Store limits
// ---------------------------------------------------------------------------

/// Bounds the in-memory `SessionStore` so a long-running server doesn't
/// accumulate sessions or per-session conversation history without limit.
/// Disk persistence is unaffected: evicted sessions can be re-loaded from
/// their on-disk zip on demand, and history is trimmed only in memory.
#[derive(Debug, Clone, Copy)]
pub struct SessionLimits {
    /// Maximum number of sessions kept resident in memory. When the cap is
    /// exceeded, the least-recently-used session(s) are dropped from memory
    /// (but remain on disk). `0` disables the cap.
    pub max_sessions: usize,
    /// Maximum number of conversation turns retained per session in memory.
    /// When the cap is exceeded, the oldest turns are dropped (sliding
    /// window). `0` disables the cap.
    pub max_history_turns: usize,
}

impl Default for SessionLimits {
    fn default() -> Self {
        Self {
            max_sessions: 50,
            max_history_turns: 50,
        }
    }
}

/// Drop oldest turns until `history.len() <= max`. `max == 0` disables.
fn trim_history(history: &mut Vec<ConversationTurn>, max: usize) {
    if max > 0 && history.len() > max {
        let drain_to = history.len() - max;
        history.drain(0..drain_to);
    }
}

// ---------------------------------------------------------------------------
// Session modes
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum SessionMode {
    Lutz,
    Code,
    Ask,
    Plan,
}

impl SessionMode {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Lutz => "LUTZ",
            Self::Code => "CODE",
            Self::Ask => "ASK",
            Self::Plan => "PLAN",
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        match s.to_uppercase().as_str() {
            "LUTZ" => Some(Self::Lutz),
            "CODE" => Some(Self::Code),
            "ASK" => Some(Self::Ask),
            "PLAN" => Some(Self::Plan),
            _ => None,
        }
    }
}

// ---------------------------------------------------------------------------
// Permission mode
// ---------------------------------------------------------------------------

/// Per-session permission policy, mirroring the four reference modes that
/// `claude-agent-acp` exposes (default / acceptEdits / plan / bypassPermissions).
/// Surfaced to clients as a `SessionConfigOption` (its own dropdown), independent
/// of `SessionMode`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PermissionMode {
    Default,
    AcceptEdits,
    /// Hard read-only: refuses every Edit / Delete / Move / Execute tool call.
    /// Renamed from the reference's "plan" to avoid colliding with Brokk's
    /// PLAN behavior mode (LUTZ/CODE/ASK/PLAN), which is a separate dropdown.
    ReadOnly,
    BypassPermissions,
}

impl PermissionMode {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Default => "default",
            Self::AcceptEdits => "acceptEdits",
            Self::ReadOnly => "readOnly",
            Self::BypassPermissions => "bypassPermissions",
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "default" => Some(Self::Default),
            "acceptEdits" => Some(Self::AcceptEdits),
            "readOnly" => Some(Self::ReadOnly),
            "bypassPermissions" => Some(Self::BypassPermissions),
            _ => None,
        }
    }
}

// ---------------------------------------------------------------------------
// Conversation history
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Default)]
pub struct ConversationTurn {
    pub user_prompt: String,
    pub agent_response: String,
    /// Tool calls executed during this turn, paired 1:1 with their results
    /// in chronological order. Empty for text-only turns.
    ///
    /// Persisted to and read back from the session zip so a `session/load`
    /// reconstructs the same context the LLM had when it produced
    /// `agent_response` -- without this, the model sees only the final
    /// answer and may repeat searches/reads/writes it already performed
    /// in the prior turn (#3409).
    pub tool_exchanges: Vec<ToolExchange>,
}

/// One tool invocation and its result, captured during a turn so the next
/// `session/prompt` after a load can re-feed the LLM the full context.
///
/// Kept transport-agnostic (no `ChatMessage` dependency) so `session.rs`
/// stays decoupled from the LLM client. Conversion to `ChatMessage`
/// happens at the agent's prompt-replay call site.
#[derive(Debug, Clone, Default)]
pub struct ToolExchange {
    /// Provider-supplied id (`call.id` in the OpenAI tool-calling shape)
    /// used to pair the assistant's tool_call with its tool_result message
    /// when replaying. Required for OpenAI-compat clients.
    pub call_id: String,
    pub tool_name: String,
    /// Raw JSON arguments string the LLM emitted, kept verbatim so a
    /// re-fed assistant_tool_calls message reproduces the original turn.
    pub arguments: String,
    /// Output the tool returned, post-truncation to `MAX_TOOL_RESULT_BYTES`,
    /// matching what was fed to the LLM during the original turn.
    pub result: String,
}

// ---------------------------------------------------------------------------
// Executor-compatible manifest (manifest.json inside the zip)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SessionManifest {
    pub id: String,
    pub name: String,
    pub created: u64,
    pub modified: u64,
    #[serde(default = "default_version")]
    pub version: String,
    /// Brokk ACP-specific: session mode, persisted so it survives reload.
    /// Absent in manifests produced by the Java executor.
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "brokkMode")]
    pub mode: Option<String>,
    /// Brokk ACP-specific: last selected model for this session.
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "brokkModel"
    )]
    pub model: Option<String>,
}

fn default_version() -> String {
    "4.0".to_string()
}

// ---------------------------------------------------------------------------
// Per-session state (in-memory)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct Session {
    pub id: String,
    pub cwd: PathBuf,
    pub mode: SessionMode,
    pub model: String,
    pub history: Vec<ConversationTurn>,
    pub manifest: SessionManifest,
    pub permission_mode: PermissionMode,
    /// Tool names the user has chosen "Always allow" for this session.
    /// In-memory only (matches `claude-agent-acp` behavior).
    pub always_allow_tools: HashSet<String>,
}

impl Session {
    pub fn new(id: String, cwd: PathBuf, model: String, name: String) -> Self {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        let mode = SessionMode::Lutz;
        let permission_mode = PermissionMode::Default;
        let manifest = SessionManifest {
            id: id.clone(),
            name,
            created: now,
            modified: now,
            version: "4.0".to_string(),
            mode: Some(mode.as_str().to_string()),
            model: if model.is_empty() {
                None
            } else {
                Some(model.clone())
            },
        };
        Self {
            id,
            cwd,
            mode,
            model,
            history: Vec::new(),
            manifest,
            permission_mode,
            always_allow_tools: HashSet::new(),
        }
    }

    /// Construct a `Session` from data loaded off disk.
    ///
    /// SECURITY: transient fields that intentionally do NOT survive a reload
    /// (`permission_mode`, `always_allow_tools`) are reset here, mirroring
    /// `claude-agent-acp`. Going through this constructor guarantees a stale
    /// or tampered manifest cannot silently auto-allow tool calls on launch,
    /// and that any future "reset on reload" field is added in one place.
    ///
    /// Also rejects a mismatch between `id` (the caller's requested id, used
    /// to locate the zip and to key the in-memory map) and `manifest.id`
    /// (read from inside the zip). A mismatch indicates either a stale or
    /// tampered zip, or a logic error mapping ids to zip paths -- continuing
    /// silently would route subsequent writes against a different zip than
    /// the one we resumed from.
    pub fn from_persisted(
        id: String,
        cwd: PathBuf,
        mode: SessionMode,
        model: String,
        history: Vec<ConversationTurn>,
        manifest: SessionManifest,
    ) -> Result<Self, SessionIdMismatch> {
        if manifest.id != id {
            return Err(SessionIdMismatch {
                requested: id,
                loaded: manifest.id,
            });
        }
        Ok(Self {
            id,
            cwd,
            mode,
            model,
            history,
            manifest,
            permission_mode: PermissionMode::Default,
            always_allow_tools: HashSet::new(),
        })
    }
}

/// Returned by `Session::from_persisted` when the requested session id
/// doesn't match the id stored in the manifest read from disk.
#[derive(Debug)]
pub struct SessionIdMismatch {
    pub requested: String,
    pub loaded: String,
}

impl std::fmt::Display for SessionIdMismatch {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "session id mismatch: caller requested '{}' but manifest in zip says '{}'",
            self.requested, self.loaded
        )
    }
}

impl std::error::Error for SessionIdMismatch {}

/// Snapshot of the per-session data needed to start a prompt turn. The
/// conversation history is cloned exactly once under the read lock; callers
/// consume it (via `.into_iter()`) when constructing protocol-specific
/// message types, so no further string clones happen on the prompt path.
///
/// This intentionally exposes raw `ConversationTurn` rather than a
/// pre-built `Vec<ChatMessage>` so `session.rs` doesn't depend on the LLM
/// transport layer — message assembly belongs at the call site.
#[derive(Debug)]
pub struct SessionSnapshot {
    pub cwd: PathBuf,
    pub mode: SessionMode,
    pub model: String,
    pub history: Vec<ConversationTurn>,
}

// ---------------------------------------------------------------------------
// Zip I/O: read/write executor-compatible session zips
// ---------------------------------------------------------------------------

fn sessions_dir(cwd: &Path) -> PathBuf {
    cwd.join(".brokk").join("sessions")
}

fn session_zip_path(cwd: &Path, id: &str) -> PathBuf {
    sessions_dir(cwd).join(format!("{id}.zip"))
}

/// Read manifest.json from a session zip. Returns None if the zip or manifest is unreadable.
fn read_manifest_from_zip(zip_path: &Path) -> Option<SessionManifest> {
    let file = std::fs::File::open(zip_path).ok()?;
    let mut archive = zip::ZipArchive::new(file).ok()?;
    let mut manifest_entry = archive.by_name("manifest.json").ok()?;
    let mut buf = String::new();
    manifest_entry.read_to_string(&mut buf).ok()?;
    serde_json::from_str(&buf).ok()
}

/// Read conversation history from a session zip.
/// Reads TaskFragmentDto entries from fragments-v4.json and resolves their
/// markdownContentId / messages[].contentId against content/*.txt files.
fn read_history_from_zip(zip_path: &Path) -> Vec<ConversationTurn> {
    let file = match std::fs::File::open(zip_path) {
        Ok(f) => f,
        Err(_) => return vec![],
    };
    let mut archive = match zip::ZipArchive::new(file) {
        Ok(a) => a,
        Err(_) => return vec![],
    };

    // 1. Read all content/*.txt files into a map: content_id -> text
    let mut content_map: HashMap<String, String> = HashMap::new();
    for i in 0..archive.len() {
        let mut entry = match archive.by_index(i) {
            Ok(e) => e,
            Err(_) => continue,
        };
        let name = entry.name().to_string();
        if let Some(content_id) = name
            .strip_prefix("content/")
            .and_then(|s| s.strip_suffix(".txt"))
        {
            let mut buf = String::new();
            if entry.read_to_string(&mut buf).is_ok() {
                content_map.insert(content_id.to_string(), buf);
            }
        }
    }

    // 2. Read fragments-v4.json to find task fragments with conversation content
    let fragments_json = {
        let mut entry = match archive.by_name("fragments-v4.json") {
            Ok(e) => e,
            Err(_) => return vec![],
        };
        let mut buf = String::new();
        if entry.read_to_string(&mut buf).is_err() {
            return vec![];
        }
        buf
    };

    let fragments: serde_json::Value = match serde_json::from_str(&fragments_json) {
        Ok(v) => v,
        Err(_) => return vec![],
    };

    // 3. Extract conversation from task fragments
    //    Each task fragment may have:
    //    - messages: [{role, contentId, ...}] -- includes "tool_call" and
    //      "tool_result" entries when the turn used tools (#3409)
    //    - markdownContentId: points to the rendered final response
    let mut turns = Vec::new();
    if let Some(tasks) = fragments.get("task").and_then(|t| t.as_object()) {
        for (_id, task) in tasks {
            // Walk the messages array first to pick up any tool exchanges,
            // even when markdownContentId is the source of agent_response.
            // Tool exchanges are persisted in `messages` regardless of the
            // markdown shortcut (which only stores the final text).
            let exchanges = read_tool_exchanges_from_messages(task, &content_map);

            // Try markdownContentId first (newer format: pre-rendered markdown)
            if let Some(content_id) = task.get("markdownContentId").and_then(|v| v.as_str())
                && let Some(text) = content_map.get(content_id)
            {
                let description = task
                    .get("taskDescription")
                    .and_then(|v| v.as_str())
                    .unwrap_or("");
                let user_prompt = if description.is_empty() {
                    // Older zips put the user text under a `role: "user"` entry
                    // instead of taskDescription -- pull it from there as a
                    // last resort so the user side isn't lost.
                    read_first_role_text(task, "user", &content_map)
                } else {
                    description.to_string()
                };
                turns.push(ConversationTurn {
                    user_prompt,
                    agent_response: text.clone(),
                    tool_exchanges: exchanges,
                });
                continue;
            }

            // Fall back to messages array (older format)
            if let Some(messages) = task.get("messages").and_then(|v| v.as_array()) {
                let mut user_text = String::new();
                let mut assistant_text = String::new();
                for msg in messages {
                    let role = msg.get("role").and_then(|v| v.as_str()).unwrap_or("");
                    let content_id = msg.get("contentId").and_then(|v| v.as_str()).unwrap_or("");
                    let text = content_map.get(content_id).cloned().unwrap_or_default();
                    match role {
                        "user" => user_text.push_str(&text),
                        "ai" => assistant_text.push_str(&text),
                        // tool_call / tool_result entries are folded into
                        // `exchanges` above; ignore them here so they don't
                        // contaminate user_text / assistant_text.
                        _ => {}
                    }
                }
                if !assistant_text.is_empty() {
                    turns.push(ConversationTurn {
                        user_prompt: user_text,
                        agent_response: assistant_text,
                        tool_exchanges: exchanges,
                    });
                }
            }
        }
    }

    turns
}

/// Walk a task fragment's `messages` array and pair `tool_call` entries
/// with the matching `tool_result` by `toolCallId`. Order in the returned
/// `Vec` follows the order of the `tool_call` entries; results without a
/// matching call are dropped (and vice versa) since a half-recorded
/// exchange would mislead the model on replay more than omitting it.
fn read_tool_exchanges_from_messages(
    task: &serde_json::Value,
    content_map: &HashMap<String, String>,
) -> Vec<ToolExchange> {
    let Some(messages) = task.get("messages").and_then(|v| v.as_array()) else {
        return Vec::new();
    };

    // First pass: collect tool_results keyed by toolCallId so we can pair
    // them up regardless of the order calls and results appear in (the
    // OpenAI shape interleaves: call, call, result, result).
    let mut results: HashMap<String, String> = HashMap::new();
    for msg in messages {
        if msg.get("role").and_then(|v| v.as_str()) != Some("tool_result") {
            continue;
        }
        let Some(call_id) = msg.get("toolCallId").and_then(|v| v.as_str()) else {
            continue;
        };
        let content_id = msg.get("contentId").and_then(|v| v.as_str()).unwrap_or("");
        let result = content_map.get(content_id).cloned().unwrap_or_default();
        results.insert(call_id.to_string(), result);
    }

    // Second pass: emit exchanges in the order of the tool_call entries.
    let mut exchanges = Vec::new();
    for msg in messages {
        if msg.get("role").and_then(|v| v.as_str()) != Some("tool_call") {
            continue;
        }
        let call_id = msg
            .get("toolCallId")
            .and_then(|v| v.as_str())
            .unwrap_or_default();
        let tool_name = msg
            .get("toolName")
            .and_then(|v| v.as_str())
            .unwrap_or_default();
        let arguments_id = msg
            .get("argumentsContentId")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        let arguments = content_map.get(arguments_id).cloned().unwrap_or_default();
        let Some(result) = results.remove(call_id) else {
            // No result recorded for this call; skip rather than feed the
            // LLM a tool_call without a matching tool_result (the OpenAI
            // wire format requires the pair).
            continue;
        };
        exchanges.push(ToolExchange {
            call_id: call_id.to_string(),
            tool_name: tool_name.to_string(),
            arguments,
            result,
        });
    }
    exchanges
}

/// Look up the first message in `task.messages` whose `role` matches and
/// return its dereferenced content text. Used as a fallback when
/// `taskDescription` is null but the user prompt is still recoverable from
/// the messages array.
fn read_first_role_text(
    task: &serde_json::Value,
    role: &str,
    content_map: &HashMap<String, String>,
) -> String {
    task.get("messages")
        .and_then(|v| v.as_array())
        .and_then(|msgs| {
            msgs.iter().find_map(|m| {
                if m.get("role").and_then(|v| v.as_str()) == Some(role) {
                    let cid = m.get("contentId").and_then(|v| v.as_str())?;
                    content_map.get(cid).cloned()
                } else {
                    None
                }
            })
        })
        .unwrap_or_default()
}

/// Run `populate` against a fresh `<zip_path>.tmp`, finalize the writer, and atomically
/// rename it over `zip_path`. On any failure the temp file is cleaned up and the original
/// zip (if any) is left untouched, so callers get all-or-nothing semantics.
fn with_temp_zip_writer<F>(zip_path: &Path, populate: F) -> anyhow::Result<()>
where
    F: FnOnce(
        &mut zip::ZipWriter<std::fs::File>,
        zip::write::SimpleFileOptions,
    ) -> anyhow::Result<()>,
{
    use anyhow::Context;

    let dir = zip_path.parent().with_context(|| {
        format!(
            "session zip path has no parent directory: {}",
            zip_path.display()
        )
    })?;
    std::fs::create_dir_all(dir)
        .with_context(|| format!("creating sessions dir {}", dir.display()))?;

    let tmp = zip_path.with_extension("tmp");
    let file = std::fs::File::create(&tmp)
        .with_context(|| format!("creating temp zip {}", tmp.display()))?;

    let mut writer = zip::ZipWriter::new(file);
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);

    if let Err(e) = populate(&mut writer, options) {
        // Drop the writer (closes its file handle) before unlinking the temp.
        drop(writer);
        let _ = std::fs::remove_file(&tmp);
        return Err(e);
    }

    writer
        .finish()
        .with_context(|| format!("finalizing temp zip {}", tmp.display()))?;
    std::fs::rename(&tmp, zip_path)
        .with_context(|| format!("renaming {} to {}", tmp.display(), zip_path.display()))?;
    Ok(())
}

/// Copy every entry from `archive` into `writer` whose name does not match `skip`.
/// Per-entry I/O failures bubble up; callers in `with_temp_zip_writer` will discard
/// the half-written temp zip.
fn copy_zip_entries_except<F>(
    archive: &mut zip::ZipArchive<std::fs::File>,
    writer: &mut zip::ZipWriter<std::fs::File>,
    options: zip::write::SimpleFileOptions,
    skip: F,
) -> anyhow::Result<()>
where
    F: Fn(&str) -> bool,
{
    use anyhow::Context;
    for i in 0..archive.len() {
        let mut entry = archive
            .by_index(i)
            .with_context(|| format!("reading zip entry at index {i}"))?;
        let name = entry.name().to_string();
        if skip(&name) {
            continue;
        }
        let mut buf = Vec::new();
        entry
            .read_to_end(&mut buf)
            .with_context(|| format!("reading zip entry {name}"))?;
        writer
            .start_file(&name, options)
            .with_context(|| format!("starting zip entry {name}"))?;
        writer
            .write_all(&buf)
            .with_context(|| format!("writing zip entry {name}"))?;
    }
    Ok(())
}

/// Write a new empty session zip compatible with the executor.
fn write_new_session_zip(zip_path: &Path, manifest: &SessionManifest) -> anyhow::Result<()> {
    use anyhow::Context;

    with_temp_zip_writer(zip_path, |zip, options| {
        // manifest.json
        let manifest_json =
            serde_json::to_string_pretty(manifest).context("serializing session manifest")?;
        zip.start_file("manifest.json", options)?;
        zip.write_all(manifest_json.as_bytes())?;

        // Empty context (one initial context entry)
        let ctx_id = uuid::Uuid::new_v4().to_string();
        let context_line = serde_json::json!({
            "id": ctx_id,
            "editable": [],
            "readonly": [],
            "virtuals": [],
            "pinned": [],
            "tasks": [],
            "parsedOutputId": null
        });
        zip.start_file("contexts.jsonl", options)?;
        zip.write_all(serde_json::to_string(&context_line)?.as_bytes())?;
        zip.write_all(b"\n")?;

        // Empty fragments
        let fragments =
            serde_json::json!({"version": 4, "referenced": {}, "virtual": {}, "task": {}});
        zip.start_file("fragments-v4.json", options)?;
        zip.write_all(serde_json::to_string(&fragments)?.as_bytes())?;

        // Empty content metadata
        zip.start_file("content_metadata.json", options)?;
        zip.write_all(b"{}")?;

        // Empty group info
        let group_info = serde_json::json!({"contextToGroupId": {}, "groupLabels": {}});
        zip.start_file("group_info.json", options)?;
        zip.write_all(serde_json::to_string(&group_info)?.as_bytes())?;

        Ok(())
    })
}

/// Update manifest.json and add a conversation turn to an existing session zip.
///
/// All framing failures (open, archive read, per-entry copy, finalize, rename) propagate
/// to the caller so `add_turn` can roll back and keep `memory == disk`. The atomic
/// temp-then-rename in `with_temp_zip_writer` guarantees the on-disk zip is unchanged
/// on any failure path.
fn append_turn_to_zip(
    zip_path: &Path,
    manifest: &SessionManifest,
    turn: &ConversationTurn,
) -> anyhow::Result<()> {
    use anyhow::Context;

    let file = std::fs::File::open(zip_path)
        .with_context(|| format!("opening session zip {} for update", zip_path.display()))?;
    let mut archive = zip::ZipArchive::new(file)
        .with_context(|| format!("reading session zip {}", zip_path.display()))?;

    // Pre-read the entries we plan to rewrite. A missing or unparseable entry falls back
    // to a known-empty default, matching prior behavior; a stale corruption shouldn't
    // abort persistence of the new turn.
    let mut existing_fragments: serde_json::Value =
        serde_json::json!({"version": 4, "referenced": {}, "virtual": {}, "task": {}});
    if let Ok(mut e) = archive.by_name("fragments-v4.json") {
        let mut buf = String::new();
        if e.read_to_string(&mut buf).is_ok()
            && let Ok(v) = serde_json::from_str(&buf)
        {
            existing_fragments = v;
        }
    }
    let mut existing_contexts = String::new();
    if let Ok(mut e) = archive.by_name("contexts.jsonl") {
        let _ = e.read_to_string(&mut existing_contexts);
    }
    let mut existing_content_metadata: serde_json::Value = serde_json::json!({});
    if let Ok(mut e) = archive.by_name("content_metadata.json") {
        let mut buf = String::new();
        if e.read_to_string(&mut buf).is_ok()
            && let Ok(v) = serde_json::from_str(&buf)
        {
            existing_content_metadata = v;
        }
    }
    let mut existing_group_info: serde_json::Value =
        serde_json::json!({"contextToGroupId": {}, "groupLabels": {}});
    if let Ok(mut e) = archive.by_name("group_info.json") {
        let mut buf = String::new();
        if e.read_to_string(&mut buf).is_ok()
            && let Ok(v) = serde_json::from_str(&buf)
        {
            existing_group_info = v;
        }
    }

    let user_content_id = uuid::Uuid::new_v4().to_string();
    let response_content_id = uuid::Uuid::new_v4().to_string();
    let task_fragment_id = uuid::Uuid::new_v4().to_string();
    let new_context_id = uuid::Uuid::new_v4().to_string();

    // Build the messages array: user message, then tool_call/tool_result
    // pairs (one of each per exchange) interleaved per the OpenAI wire
    // shape, then the final ai message. Each tool_call's argument string
    // and each tool_result's output get their own content/*.txt entry so
    // the messages JSON stays compact even for large outputs.
    //
    // `tool_exchange_content` collects (content_id, text) pairs to write
    // after the manifest/fragments rewrite below; we accumulate now so
    // we don't have to walk the exchanges twice.
    let mut messages_json = vec![serde_json::json!(
        {"role": "user", "contentId": user_content_id}
    )];
    let mut tool_exchange_content: Vec<(String, String)> = Vec::new();
    for exchange in &turn.tool_exchanges {
        let arguments_content_id = uuid::Uuid::new_v4().to_string();
        let result_content_id = uuid::Uuid::new_v4().to_string();
        messages_json.push(serde_json::json!({
            "role": "tool_call",
            "toolCallId": exchange.call_id,
            "toolName": exchange.tool_name,
            "argumentsContentId": arguments_content_id,
        }));
        messages_json.push(serde_json::json!({
            "role": "tool_result",
            "toolCallId": exchange.call_id,
            "toolName": exchange.tool_name,
            "contentId": result_content_id,
        }));
        tool_exchange_content.push((arguments_content_id, exchange.arguments.clone()));
        tool_exchange_content.push((result_content_id, exchange.result.clone()));
    }
    messages_json.push(serde_json::json!(
        {"role": "ai", "contentId": response_content_id}
    ));

    if let Some(tasks) = existing_fragments
        .get_mut("task")
        .and_then(|t| t.as_object_mut())
    {
        tasks.insert(
            task_fragment_id.clone(),
            serde_json::json!({
                "id": task_fragment_id,
                "messages": messages_json,
                "taskDescription": null,
                "markdownContentId": response_content_id,
                "escapeHtml": false
            }),
        );
    }

    let new_context = serde_json::json!({
        "id": new_context_id,
        "editable": [],
        "readonly": [],
        "virtuals": [task_fragment_id],
        "pinned": [],
        "tasks": [{
            "sequence": 0,
            "description": null,
            "logId": task_fragment_id,
            "llmLogId": null,
            "summaryContentId": null,
            "taskType": null,
            "primaryModelName": null,
            "primaryModelReasoning": null
        }],
        "parsedOutputId": null
    });
    let mut contexts = existing_contexts;
    contexts.push_str(&serde_json::to_string(&new_context).unwrap_or_default());
    contexts.push('\n');

    const REWRITTEN: &[&str] = &[
        "manifest.json",
        "fragments-v4.json",
        "contexts.jsonl",
        "content_metadata.json",
        "group_info.json",
    ];

    with_temp_zip_writer(zip_path, |writer, options| {
        copy_zip_entries_except(&mut archive, writer, options, |n| REWRITTEN.contains(&n))?;

        let manifest_json =
            serde_json::to_string_pretty(manifest).context("serializing session manifest")?;
        writer.start_file("manifest.json", options)?;
        writer.write_all(manifest_json.as_bytes())?;

        writer.start_file("fragments-v4.json", options)?;
        writer.write_all(serde_json::to_string(&existing_fragments)?.as_bytes())?;

        writer.start_file("contexts.jsonl", options)?;
        writer.write_all(contexts.as_bytes())?;

        writer.start_file("content_metadata.json", options)?;
        writer.write_all(serde_json::to_string(&existing_content_metadata)?.as_bytes())?;

        writer.start_file("group_info.json", options)?;
        writer.write_all(serde_json::to_string(&existing_group_info)?.as_bytes())?;

        writer.start_file(format!("content/{user_content_id}.txt"), options)?;
        writer.write_all(turn.user_prompt.as_bytes())?;

        writer.start_file(format!("content/{response_content_id}.txt"), options)?;
        writer.write_all(turn.agent_response.as_bytes())?;

        // Tool-exchange args + results: one content/*.txt file per blob,
        // keyed by the ids we assigned in `messages_json` above. Failures
        // here propagate up and the half-written temp zip is discarded.
        for (content_id, text) in &tool_exchange_content {
            writer.start_file(format!("content/{content_id}.txt"), options)?;
            writer.write_all(text.as_bytes())?;
        }

        Ok(())
    })
}

/// Replace manifest.json in an existing session zip, copying all other entries as-is.
///
/// Atomic: any failure leaves the on-disk zip untouched, so callers can roll back
/// in-memory mutations and keep `memory == disk`.
fn rewrite_manifest_in_zip(zip_path: &Path, manifest: &SessionManifest) -> anyhow::Result<()> {
    use anyhow::Context;

    let file = std::fs::File::open(zip_path).with_context(|| {
        format!(
            "opening session zip {} for manifest rewrite",
            zip_path.display()
        )
    })?;
    let mut archive = zip::ZipArchive::new(file)
        .with_context(|| format!("reading session zip {}", zip_path.display()))?;

    with_temp_zip_writer(zip_path, |writer, options| {
        copy_zip_entries_except(&mut archive, writer, options, |n| n == "manifest.json")?;
        let manifest_json =
            serde_json::to_string_pretty(manifest).context("serializing session manifest")?;
        writer.start_file("manifest.json", options)?;
        writer.write_all(manifest_json.as_bytes())?;
        Ok(())
    })
}

/// List all session manifests from the executor's sessions directory.
fn list_manifests_from_disk(cwd: &Path) -> Vec<SessionManifest> {
    let dir = sessions_dir(cwd);
    let entries = match std::fs::read_dir(&dir) {
        Ok(e) => e,
        Err(_) => return vec![],
    };
    entries
        .filter_map(|entry| {
            let entry = entry.ok()?;
            let path = entry.path();
            if path.extension().and_then(|e| e.to_str()) != Some("zip") {
                return None;
            }
            read_manifest_from_zip(&path)
        })
        .collect()
}

// ---------------------------------------------------------------------------
// Thread-safe session store
// ---------------------------------------------------------------------------

#[derive(Clone)]
pub struct SessionStore {
    sessions: Arc<RwLock<HashMap<String, Session>>>,
    default_model: Arc<RwLock<String>>,
    /// Last-known list of models, populated by `set_available_models` from the LLM endpoint.
    /// Used to fulfil `session/new` without re-fetching on every call.
    available_models: Arc<RwLock<Vec<String>>>,
    cancel_tokens: Arc<RwLock<HashMap<String, CancellationToken>>>,
    /// One ToolRegistry per session, kept warm across turns so any bifrost
    /// subprocess survives. Populated lazily on first prompt.
    registries: Arc<RwLock<HashMap<String, Arc<ToolRegistry>>>>,
    /// Per-session monotonic access counter used for LRU eviction. Held
    /// behind a sync `Mutex` because every touch is a fast in-memory bump
    /// and must not require holding a tokio lock across `.await` points.
    last_accessed: Arc<std::sync::Mutex<HashMap<String, u64>>>,
    next_access: Arc<AtomicU64>,
    limits: SessionLimits,
}

impl SessionStore {
    /// Build a `SessionStore` with `SessionLimits::default()`. Test-only:
    /// production code goes through `with_limits` so CLI flags drive the
    /// caps. Gated on `cfg(test)` because in a binary crate `dead_code` is
    /// evaluated separately per build target, and the bin target never
    /// calls this constructor.
    #[cfg(test)]
    pub fn new(default_model: String) -> Self {
        Self::with_limits(default_model, SessionLimits::default())
    }

    pub fn with_limits(default_model: String, limits: SessionLimits) -> Self {
        Self {
            sessions: Arc::new(RwLock::new(HashMap::new())),
            default_model: Arc::new(RwLock::new(default_model)),
            available_models: Arc::new(RwLock::new(Vec::new())),
            cancel_tokens: Arc::new(RwLock::new(HashMap::new())),
            registries: Arc::new(RwLock::new(HashMap::new())),
            last_accessed: Arc::new(std::sync::Mutex::new(HashMap::new())),
            next_access: Arc::new(AtomicU64::new(0)),
            limits,
        }
    }

    /// Bump the LRU "last accessed" counter for `id`. Cheap: a single
    /// `AtomicU64::fetch_add` + a `HashMap::insert` under a sync mutex held
    /// for one statement.
    fn touch(&self, id: &str) {
        let counter = self.next_access.fetch_add(1, Ordering::Relaxed);
        self.last_accessed
            .lock()
            .expect("last_accessed mutex poisoned")
            .insert(id.to_string(), counter);
    }

    /// If `sessions.len()` exceeds `limits.max_sessions`, evict the least
    /// recently used session(s) from memory. Sessions with an in-flight
    /// prompt (cancel token registered via `start_prompt`) are skipped --
    /// evicting them would remove the in-memory state the running prompt is
    /// still mutating.
    ///
    /// Eviction is in-memory only. The on-disk zip is untouched, so a
    /// subsequent `session/load` re-hydrates the session unchanged.
    async fn enforce_session_cap(&self) {
        let max = self.limits.max_sessions;
        if max == 0 {
            return;
        }
        let to_evict: Vec<String> = {
            let sessions = self.sessions.read().await;
            if sessions.len() <= max {
                return;
            }
            let in_flight = self.cancel_tokens.read().await;
            let last_accessed = self
                .last_accessed
                .lock()
                .expect("last_accessed mutex poisoned");
            let excess = sessions.len() - max;
            let mut candidates: Vec<(String, u64)> = sessions
                .keys()
                .filter(|id| !in_flight.contains_key(id.as_str()))
                .map(|id| (id.clone(), last_accessed.get(id).copied().unwrap_or(0)))
                .collect();
            candidates.sort_by_key(|(_, c)| *c);
            candidates
                .into_iter()
                .take(excess)
                .map(|(id, _)| id)
                .collect()
        };
        if to_evict.is_empty() {
            return;
        }
        {
            let mut sessions = self.sessions.write().await;
            let mut registries = self.registries.write().await;
            let mut last_accessed = self
                .last_accessed
                .lock()
                .expect("last_accessed mutex poisoned");
            for id in &to_evict {
                sessions.remove(id);
                registries.remove(id);
                last_accessed.remove(id);
            }
        }
        tracing::info!(
            evicted = to_evict.len(),
            "evicted least-recently-used sessions from memory: {to_evict:?}"
        );
    }

    /// Return the cached ToolRegistry for `session_id`, or build one and cache it.
    /// `bifrost_binary` is consulted only on the first call for a given session.
    pub async fn get_or_create_registry(
        &self,
        session_id: &str,
        cwd: PathBuf,
        bifrost_binary: Option<&Path>,
    ) -> Arc<ToolRegistry> {
        if let Some(existing) = self.registries.read().await.get(session_id).cloned() {
            return existing;
        }
        let registry = Arc::new(ToolRegistry::new(cwd, bifrost_binary).await);
        self.registries
            .write()
            .await
            .insert(session_id.to_string(), registry.clone());
        registry
    }

    pub async fn set_available_models(&self, models: Vec<String>) {
        *self.available_models.write().await = models;
    }

    pub async fn available_models(&self) -> Vec<String> {
        self.available_models.read().await.clone()
    }

    /// Create a new session and write it to disk as a zip.
    pub async fn create_session(&self, cwd: PathBuf) -> Session {
        let id = uuid::Uuid::new_v4().to_string();
        let model = self.default_model.read().await.clone();
        let session = Session::new(id.clone(), cwd.clone(), model, "New Session".to_string());

        // Write to disk on a blocking worker so we don't stall the tokio runtime.
        // Persistence failures are logged but not surfaced: `create_session` returns
        // an in-memory session today, and changing the API to `Result` would ripple
        // through every `session/new` caller. Reload after a write failure simply
        // returns no manifest from disk.
        let zip_path = session_zip_path(&cwd, &id);
        let manifest = session.manifest.clone();
        match tokio::task::spawn_blocking(move || write_new_session_zip(&zip_path, &manifest)).await
        {
            Ok(Ok(())) => {}
            Ok(Err(e)) => {
                tracing::warn!(session_id = %id, "failed to write new session zip: {e:#}")
            }
            Err(e) => tracing::warn!(session_id = %id, "session zip writer task panicked: {e}"),
        }

        self.sessions
            .write()
            .await
            .insert(id.clone(), session.clone());
        self.touch(&id);
        self.enforce_session_cap().await;
        session
    }

    /// Get session from memory, or load from disk if it exists.
    ///
    /// Note: this clones the full `Session` (including the conversation history
    /// vec). Callers on the hot prompt path should prefer `build_prompt_state`,
    /// which constructs the message list under the read lock without copying
    /// the history twice.
    pub async fn get_session(&self, id: &str, cwd: &Path) -> Option<Session> {
        if !self.load_into_memory_if_cold(id, cwd).await {
            return None;
        }
        let cloned = self.sessions.read().await.get(id).cloned();
        if cloned.is_some() {
            self.touch(id);
        }
        cloned
    }

    /// Ensure the session is in memory, loading it from disk if needed.
    /// Returns true iff the session ends up loaded.
    ///
    /// Note: if a session is already in memory, this is a no-op and the
    /// disk copy is NOT re-read. Live in-memory state (e.g. an updated
    /// `permission_mode` or pushed history turn) takes precedence over
    /// whatever is on disk.
    async fn load_into_memory_if_cold(&self, id: &str, cwd: &Path) -> bool {
        if self.sessions.read().await.contains_key(id) {
            return true;
        }
        let zip_path = session_zip_path(cwd, id);
        let loaded = tokio::task::spawn_blocking(move || {
            let manifest = read_manifest_from_zip(&zip_path)?;
            let history = read_history_from_zip(&zip_path);
            Some((manifest, history))
        })
        .await
        .ok()
        .flatten();
        let Some((manifest, mut history)) = loaded else {
            return false;
        };

        // Apply the in-memory sliding window before constructing the session,
        // so `Session.history.len()` never exceeds `max_history_turns`.
        // Disk is unaffected: the persisted zip still contains every turn.
        trim_history(&mut history, self.limits.max_history_turns);

        // Prefer persisted mode/model; fall back to server defaults.
        let mode = manifest
            .mode
            .as_deref()
            .and_then(SessionMode::parse)
            .unwrap_or(SessionMode::Lutz);
        let model = match manifest.model.clone() {
            Some(m) if !m.is_empty() => m,
            _ => self.default_model.read().await.clone(),
        };

        let session = match Session::from_persisted(
            id.to_string(),
            cwd.to_path_buf(),
            mode,
            model,
            history,
            manifest,
        ) {
            Ok(s) => s,
            Err(e) => {
                tracing::warn!(error = %e, "rejecting persisted session");
                return false;
            }
        };
        let inserted = {
            let mut sessions = self.sessions.write().await;
            // Race window: another task may have inserted under the same id while
            // we read from disk. `or_insert` keeps the existing in-memory entry
            // (which may carry mutations not yet persisted, like a newer
            // `permission_mode` or pushed turn) and silently drops our freshly
            // loaded copy. The on-disk zip is read-only on this path so no
            // information is lost.
            let len_before = sessions.len();
            sessions.entry(id.to_string()).or_insert(session);
            sessions.len() > len_before
        };
        if inserted {
            self.touch(id);
            self.enforce_session_cap().await;
        }
        true
    }

    /// Snapshot the per-session data needed to start a prompt turn,
    /// cloning the conversation history exactly once (under the read lock).
    /// Callers consume `history` to build protocol-specific message types
    /// without further string copies.
    pub async fn snapshot(&self, id: &str, fallback_cwd: &Path) -> Option<SessionSnapshot> {
        if !self.load_into_memory_if_cold(id, fallback_cwd).await {
            return None;
        }
        let snap = {
            let sessions = self.sessions.read().await;
            let s = sessions.get(id)?;
            SessionSnapshot {
                cwd: s.cwd.clone(),
                mode: s.mode,
                model: s.model.clone(),
                history: s.history.clone(),
            }
        };
        self.touch(id);
        Some(snap)
    }

    pub async fn update_cwd(&self, id: &str, cwd: PathBuf) {
        if let Some(session) = self.sessions.write().await.get_mut(id) {
            session.cwd = cwd;
        }
    }

    /// Update the session's permission mode. Returns false if the session is unknown.
    /// Permission mode is intentionally session-only (not persisted to the manifest).
    pub async fn set_permission_mode(&self, id: &str, permission_mode: PermissionMode) -> bool {
        let mut sessions = self.sessions.write().await;
        match sessions.get_mut(id) {
            Some(session) => {
                session.permission_mode = permission_mode;
                true
            }
            None => false,
        }
    }

    /// Read the current permission_mode for a session. Returns None if unknown.
    pub async fn permission_mode(&self, id: &str) -> Option<PermissionMode> {
        self.sessions
            .read()
            .await
            .get(id)
            .map(|s| s.permission_mode)
    }

    /// True if the session has previously chosen "Always allow" for `tool_name`.
    pub async fn is_always_allowed(&self, id: &str, tool_name: &str) -> bool {
        self.sessions
            .read()
            .await
            .get(id)
            .map(|s| s.always_allow_tools.contains(tool_name))
            .unwrap_or(false)
    }

    /// Add `tool_name` to the session's in-memory always-allow set.
    pub async fn add_always_allow(&self, id: &str, tool_name: &str) {
        if let Some(session) = self.sessions.write().await.get_mut(id) {
            session.always_allow_tools.insert(tool_name.to_string());
        }
    }

    /// Update the session's behavior mode and persist the new manifest.
    ///
    /// Returns `Ok(true)` on success, `Ok(false)` if the session is unknown
    /// (no-op), or `Err` if persistence failed -- in that case the in-memory
    /// mutation is rolled back so `memory == disk`, mirroring `add_turn`.
    pub async fn set_mode(&self, id: &str, mode: SessionMode) -> anyhow::Result<bool> {
        // Update in-memory state and snapshot what we need for persistence.
        // Capture the pre-mutation mode so we can reverse it on failure.
        let snapshot = {
            let mut sessions = self.sessions.write().await;
            match sessions.get_mut(id) {
                Some(session) => {
                    let prev_mode = session.mode;
                    let prev_manifest_mode = session.manifest.mode.clone();
                    session.mode = mode;
                    session.manifest.mode = Some(mode.as_str().to_string());
                    Some((
                        session.cwd.clone(),
                        session.manifest.clone(),
                        prev_mode,
                        prev_manifest_mode,
                    ))
                }
                None => None,
            }
        };
        let Some((cwd, manifest, prev_mode, prev_manifest_mode)) = snapshot else {
            return Ok(false);
        };

        let zip_path = session_zip_path(&cwd, id);
        let join_result =
            tokio::task::spawn_blocking(move || rewrite_manifest_in_zip(&zip_path, &manifest))
                .await;

        let persist_result = match join_result {
            Ok(r) => r,
            Err(join_err) => Err(anyhow::anyhow!(
                "session persistence task panicked: {join_err}"
            )),
        };

        if let Err(e) = persist_result {
            tracing::error!(
                session_id = %id,
                "failed to persist session mode; rolling back in-memory state: {e:#}"
            );
            if let Some(session) = self.sessions.write().await.get_mut(id) {
                session.mode = prev_mode;
                session.manifest.mode = prev_manifest_mode;
            }
            return Err(e);
        }
        Ok(true)
    }

    /// Update the per-session LLM model. Returns false if the session is
    /// unknown. Persists the new model into the session manifest so it
    /// survives a reload, mirroring `set_mode`.
    pub async fn set_model(&self, id: &str, model: String) -> bool {
        let snapshot = {
            let mut sessions = self.sessions.write().await;
            match sessions.get_mut(id) {
                Some(session) => {
                    session.model = model.clone();
                    session.manifest.model = if model.is_empty() { None } else { Some(model) };
                    Some((session.cwd.clone(), session.manifest.clone()))
                }
                None => None,
            }
        };
        match snapshot {
            Some((cwd, manifest)) => {
                let zip_path = session_zip_path(&cwd, id);
                let _ = tokio::task::spawn_blocking(move || {
                    rewrite_manifest_in_zip(&zip_path, &manifest)
                })
                .await;
                true
            }
            None => false,
        }
    }

    pub async fn set_default_model(&self, model: String) {
        *self.default_model.write().await = model;
    }

    /// Add a conversation turn and persist it to the session zip.
    ///
    /// Returns `Ok(())` on successful persistence, or `Err` if the zip
    /// rewrite failed. On error the in-memory turn is rolled back so memory
    /// and disk stay in sync: the next prompt either succeeds (with both
    /// written) or surfaces the same error again deterministically. The
    /// atomic temp-then-rename in `append_turn_to_zip` guarantees the
    /// on-disk zip is unchanged on any failure path.
    ///
    /// Concurrency: callers must serialize `add_turn` per session. The agent
    /// achieves this by holding the per-session cancellation token via
    /// `start_prompt`/`finish_prompt` for the entire prompt-plus-persistence
    /// window — `finish_prompt` runs *after* `add_turn` returns. Without
    /// that ordering, a second `session/prompt` could push a new turn
    /// between this turn's push and its rollback `pop()`, removing the
    /// wrong entry.
    pub async fn add_turn(&self, id: &str, turn: ConversationTurn) -> anyhow::Result<()> {
        // Mutate in-memory state first, then release the lock BEFORE blocking I/O.
        // Capture pre-mutation `modified` so we can reverse the bump on failure.
        let snapshot = {
            let mut sessions = self.sessions.write().await;
            match sessions.get_mut(id) {
                Some(session) => {
                    let prev_modified = session.manifest.modified;
                    session.history.push(turn.clone());
                    let now = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .unwrap_or_default()
                        .as_millis() as u64;
                    session.manifest.modified = now;
                    Some((
                        session_zip_path(&session.cwd, &session.id),
                        session.manifest.clone(),
                        prev_modified,
                    ))
                }
                None => None,
            }
        };
        let Some((zip_path, manifest, prev_modified)) = snapshot else {
            // Session is unknown -- no in-memory state to roll back, nothing
            // to persist. Treat as a no-op success.
            return Ok(());
        };

        let turn_for_zip = turn.clone();
        let join_result = tokio::task::spawn_blocking(move || {
            append_turn_to_zip(&zip_path, &manifest, &turn_for_zip)
        })
        .await;

        let persist_result = match join_result {
            Ok(r) => r,
            Err(join_err) => Err(anyhow::anyhow!(
                "session persistence task panicked: {join_err}"
            )),
        };

        if let Err(e) = persist_result {
            tracing::error!(
                session_id = %id,
                "failed to persist conversation turn; rolling back in-memory state: {e:#}"
            );
            if let Some(session) = self.sessions.write().await.get_mut(id) {
                session.history.pop();
                session.manifest.modified = prev_modified;
            }
            return Err(e);
        }

        // Persistence succeeded. Apply the in-memory sliding window so
        // history length stays bounded. Trimming AFTER successful persist
        // keeps the on-disk zip authoritative -- a future reload re-reads
        // the full history and trims it again on its way into memory.
        // The trim has no rollback partner because we've already committed
        // to disk; if trimming pushed something out of memory, the only
        // copy now lives in the zip, which is the design intent.
        let max_history = self.limits.max_history_turns;
        if max_history > 0
            && let Some(session) = self.sessions.write().await.get_mut(id)
        {
            trim_history(&mut session.history, max_history);
        }

        self.touch(id);
        Ok(())
    }

    /// List sessions from disk, filtered by cwd.
    pub async fn list_sessions_from_disk(&self, cwd: &Path) -> Vec<SessionManifest> {
        let cwd = cwd.to_path_buf();
        tokio::task::spawn_blocking(move || list_manifests_from_disk(&cwd))
            .await
            .unwrap_or_default()
    }

    pub async fn start_prompt(&self, session_id: &str) -> CancellationToken {
        let token = CancellationToken::new();
        self.cancel_tokens
            .write()
            .await
            .insert(session_id.to_string(), token.clone());
        token
    }

    pub async fn cancel_prompt(&self, session_id: &str) {
        if let Some(token) = self.cancel_tokens.read().await.get(session_id) {
            token.cancel();
        }
    }

    pub async fn finish_prompt(&self, session_id: &str) {
        self.cancel_tokens.write().await.remove(session_id);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Build an in-memory session that has NEVER been written to disk, then
    /// call `add_turn`. The append step must fail (because the zip file
    /// doesn't exist), and the in-memory state must be rolled back so
    /// `history` is empty and `manifest.modified` matches the pre-call value.
    #[tokio::test]
    async fn add_turn_rolls_back_on_persistence_failure() {
        let store = SessionStore::with_limits("test-model".to_string(), SessionLimits::default());

        // Hand-construct a session and inject it into the in-memory map without
        // ever calling `create_session` (which would write a zip on disk).
        let id = "rollback-test".to_string();
        let cwd = std::env::temp_dir().join(format!("brokk-acp-rust-rollback-{}", id));
        let session = Session::new(id.clone(), cwd, "test-model".to_string(), "test".into());
        let pre_modified = session.manifest.modified;
        store.sessions.write().await.insert(id.clone(), session);

        let result = store
            .add_turn(
                &id,
                ConversationTurn {
                    user_prompt: "hello".into(),
                    agent_response: "world".into(),
                    ..Default::default()
                },
            )
            .await;

        assert!(
            result.is_err(),
            "add_turn should fail when the session zip doesn't exist on disk"
        );

        let sessions = store.sessions.read().await;
        let s = sessions.get(&id).expect("session still in memory");
        assert!(
            s.history.is_empty(),
            "rollback should have popped the optimistically-pushed turn, got {:?}",
            s.history
        );
        assert_eq!(
            s.manifest.modified, pre_modified,
            "rollback should restore the pre-call manifest.modified timestamp"
        );
    }

    /// `set_mode` must roll back the in-memory mode change when the zip rewrite
    /// fails, mirroring `add_turn`. Otherwise memory drifts away from disk and
    /// the next reload silently downgrades the user's selection.
    #[tokio::test]
    async fn set_mode_rolls_back_on_persistence_failure() {
        let store = SessionStore::new("test-model".to_string());

        let id = "set-mode-rollback".to_string();
        let cwd = std::env::temp_dir().join(format!("brokk-acp-rust-set-mode-{}", id));
        let session = Session::new(id.clone(), cwd, "test-model".to_string(), "test".into());
        let pre_mode = session.mode;
        let pre_manifest_mode = session.manifest.mode.clone();
        store.sessions.write().await.insert(id.clone(), session);

        let result = store.set_mode(&id, SessionMode::Plan).await;
        assert!(
            result.is_err(),
            "set_mode should fail when the session zip doesn't exist on disk"
        );

        let sessions = store.sessions.read().await;
        let s = sessions.get(&id).expect("session still in memory");
        assert_eq!(
            s.mode, pre_mode,
            "rollback should restore the previous in-memory mode"
        );
        assert_eq!(
            s.manifest.mode, pre_manifest_mode,
            "rollback should restore the previous manifest.mode"
        );
    }

    /// `set_mode` on an unknown session id is `Ok(false)` (no-op), distinct
    /// from `Err`, so callers can return a precise "unknown session" error.
    #[tokio::test]
    async fn set_mode_unknown_session_returns_ok_false() {
        let store = SessionStore::new("test-model".to_string());
        let result = store.set_mode("no-such-session", SessionMode::Code).await;
        assert!(matches!(result, Ok(false)));
    }

    /// Sanity check that `add_turn` on an unknown session id is a no-op
    /// success rather than an error -- callers may race between session
    /// removal (none today, but reserved) and turn persistence.
    #[tokio::test]
    async fn add_turn_unknown_session_is_noop_success() {
        let store = SessionStore::with_limits("test-model".to_string(), SessionLimits::default());
        let result = store
            .add_turn(
                "no-such-session",
                ConversationTurn {
                    user_prompt: "x".into(),
                    agent_response: "y".into(),
                    ..Default::default()
                },
            )
            .await;
        assert!(result.is_ok());
    }

    /// `Session::from_persisted` must always reset the transient security
    /// fields (`permission_mode`, `always_allow_tools`), even when the
    /// caller's data was reconstructed from a manifest that may be stale or
    /// tampered. Persisted-side fields (id/cwd/mode/model/history/manifest)
    /// must round-trip unchanged when ids match.
    #[test]
    fn from_persisted_resets_transient_security_fields() {
        let manifest = SessionManifest {
            id: "abc".into(),
            name: "n".into(),
            created: 1,
            modified: 2,
            version: "4.0".into(),
            mode: Some("CODE".into()),
            model: Some("m".into()),
        };
        let history = vec![ConversationTurn {
            user_prompt: "u".into(),
            agent_response: "a".into(),
            ..Default::default()
        }];

        let session = Session::from_persisted(
            "abc".into(),
            PathBuf::from("/tmp/x"),
            SessionMode::Code,
            "m".into(),
            history.clone(),
            manifest.clone(),
        )
        .expect("matching ids should succeed");

        assert_eq!(session.permission_mode, PermissionMode::Default);
        assert!(session.always_allow_tools.is_empty());

        assert_eq!(session.id, "abc");
        assert_eq!(session.cwd, PathBuf::from("/tmp/x"));
        assert_eq!(session.mode, SessionMode::Code);
        assert_eq!(session.model, "m");
        assert_eq!(session.history.len(), 1);
        assert_eq!(session.manifest.id, manifest.id);
    }

    /// `trim_history` is a sliding window: when the cap is exceeded, the
    /// oldest entries go and the most-recent `max` are kept. `0` is a
    /// sentinel for "unbounded".
    #[test]
    fn trim_history_keeps_most_recent_when_capped() {
        let mut h: Vec<ConversationTurn> = (0..5)
            .map(|i| ConversationTurn {
                user_prompt: format!("u{i}"),
                agent_response: format!("a{i}"),
                ..Default::default()
            })
            .collect();

        trim_history(&mut h, 0);
        assert_eq!(h.len(), 5, "max=0 must disable the cap");

        trim_history(&mut h, 3);
        assert_eq!(h.len(), 3);
        assert_eq!(h[0].user_prompt, "u2");
        assert_eq!(h[2].user_prompt, "u4");

        trim_history(&mut h, 10);
        assert_eq!(h.len(), 3, "below the cap is a no-op");
    }

    /// `add_turn` must enforce `max_history_turns` in memory after a
    /// successful persist. We exercise the full path: a real zip on disk
    /// (so `append_turn_to_zip` succeeds), four sequential `add_turn`
    /// calls, and a memory cap of 2 -- the in-memory history must be the
    /// last two turns, while the zip on disk retains everything.
    #[tokio::test]
    async fn add_turn_enforces_history_window_in_memory() {
        let store = SessionStore::with_limits(
            "test-model".to_string(),
            SessionLimits {
                max_sessions: 0,
                max_history_turns: 2,
            },
        );
        let cwd = std::env::temp_dir().join(format!(
            "brokk-acp-rust-history-window-{}",
            uuid::Uuid::new_v4()
        ));
        let session = store.create_session(cwd.clone()).await;
        let id = session.id.clone();

        for i in 0..4 {
            store
                .add_turn(
                    &id,
                    ConversationTurn {
                        user_prompt: format!("u{i}"),
                        agent_response: format!("a{i}"),
                        ..Default::default()
                    },
                )
                .await
                .expect("persist should succeed");
        }

        let in_mem = store.sessions.read().await.get(&id).cloned().unwrap();
        assert_eq!(in_mem.history.len(), 2, "memory must respect the cap");
        assert_eq!(in_mem.history[0].user_prompt, "u2");
        assert_eq!(in_mem.history[1].user_prompt, "u3");

        // Disk-side: the zip carries every turn we appended, regardless of
        // what we kept in memory.
        let on_disk = read_history_from_zip(&session_zip_path(&cwd, &id));
        assert_eq!(
            on_disk.len(),
            4,
            "disk history must be untouched by the in-memory window"
        );

        let _ = std::fs::remove_dir_all(&cwd);
    }

    /// When `sessions.len()` exceeds `max_sessions`, the LRU sessions are
    /// evicted from memory. The session whose access counter was bumped most
    /// recently must survive; the oldest must be dropped.
    #[tokio::test]
    async fn lru_eviction_drops_oldest_session() {
        let store = SessionStore::with_limits(
            "test-model".to_string(),
            SessionLimits {
                max_sessions: 2,
                max_history_turns: 0,
            },
        );

        // Inject three sessions directly into the map without going through
        // create_session (which would write zips). Touch each one so the
        // last_accessed counter reflects insertion order: a < b < c.
        for id in ["a", "b", "c"] {
            let s = Session::new(
                id.into(),
                std::env::temp_dir().join(format!("brokk-acp-rust-lru-{id}")),
                "test-model".into(),
                "t".into(),
            );
            store.sessions.write().await.insert(id.into(), s);
            store.touch(id);
        }

        // Bump "b" so it's now most-recent: order becomes a (oldest), c, b.
        store.touch("b");

        store.enforce_session_cap().await;

        let sessions = store.sessions.read().await;
        assert_eq!(sessions.len(), 2);
        assert!(!sessions.contains_key("a"), "oldest LRU must be evicted");
        assert!(sessions.contains_key("b"), "recently-touched must survive");
        assert!(sessions.contains_key("c"));
    }

    /// In-flight prompts (those holding a cancellation token via
    /// `start_prompt`) must not be evicted: the running task is mutating the
    /// in-memory state and dropping it would lose the partially-completed
    /// turn.
    #[tokio::test]
    async fn lru_eviction_skips_in_flight_sessions() {
        let store = SessionStore::with_limits(
            "test-model".to_string(),
            SessionLimits {
                max_sessions: 1,
                max_history_turns: 0,
            },
        );

        for id in ["old", "fresh"] {
            let s = Session::new(
                id.into(),
                std::env::temp_dir().join(format!("brokk-acp-rust-inflight-{id}")),
                "test-model".into(),
                "t".into(),
            );
            store.sessions.write().await.insert(id.into(), s);
            store.touch(id);
        }
        // "old" is the LRU candidate, but mark it in-flight so it's pinned.
        let _token = store.start_prompt("old").await;

        store.enforce_session_cap().await;

        let sessions = store.sessions.read().await;
        // We can only evict non-in-flight sessions, so "fresh" goes even
        // though it was the most-recent. "old" stays pinned.
        assert!(sessions.contains_key("old"), "in-flight session is pinned");
        assert!(
            !sessions.contains_key("fresh"),
            "the only evictable session must be dropped"
        );
    }

    /// `max_sessions = 0` must disable the cap entirely: no eviction runs
    /// even when the in-memory map grows large.
    #[tokio::test]
    async fn lru_eviction_disabled_when_max_is_zero() {
        let store = SessionStore::with_limits(
            "test-model".to_string(),
            SessionLimits {
                max_sessions: 0,
                max_history_turns: 0,
            },
        );
        for i in 0..5 {
            let id = format!("s{i}");
            let s = Session::new(
                id.clone(),
                std::env::temp_dir().join("brokk-acp-rust-uncapped"),
                "test-model".into(),
                "t".into(),
            );
            store.sessions.write().await.insert(id.clone(), s);
            store.touch(&id);
        }
        store.enforce_session_cap().await;
        assert_eq!(store.sessions.read().await.len(), 5);
    }

    /// A zip whose manifest reports a different id than the one the caller
    /// asked for must be rejected: continuing would let the in-memory map
    /// key drift away from `Session.id`, so subsequent writes would target
    /// a different zip than the one we resumed from.
    #[test]
    fn from_persisted_rejects_id_mismatch() {
        let manifest = SessionManifest {
            id: "loaded-id".into(),
            name: "n".into(),
            created: 1,
            modified: 2,
            version: "4.0".into(),
            mode: None,
            model: None,
        };

        let err = Session::from_persisted(
            "requested-id".into(),
            PathBuf::from("/tmp/x"),
            SessionMode::Lutz,
            "m".into(),
            Vec::new(),
            manifest,
        )
        .expect_err("mismatched ids must be rejected");

        assert_eq!(err.requested, "requested-id");
        assert_eq!(err.loaded, "loaded-id");
    }

    /// `set_model` should update the in-memory `Session.model` and
    /// `manifest.model` so a subsequent reload from disk picks up the new
    /// value. Persistence itself is exercised by `rewrite_manifest_in_zip`
    /// (shared with `set_mode`); this test verifies wiring only.
    #[tokio::test]
    async fn set_model_updates_session_and_manifest() {
        let store = SessionStore::new("initial-model".to_string());

        // Use a unique tmp cwd so concurrent test runs don't clobber.
        let cwd =
            std::env::temp_dir().join(format!("brokk-acp-rust-set-model-{}", uuid::Uuid::new_v4()));
        let session = store.create_session(cwd).await;
        let id = session.id.clone();

        assert!(store.set_model(&id, "next-model".to_string()).await);
        let sessions = store.sessions.read().await;
        let s = sessions.get(&id).expect("session still in memory");
        assert_eq!(s.model, "next-model");
        assert_eq!(s.manifest.model.as_deref(), Some("next-model"));
    }

    #[tokio::test]
    async fn set_model_returns_false_for_unknown_session() {
        let store = SessionStore::new("initial-model".to_string());
        assert!(
            !store
                .set_model("no-such-session", "next-model".into())
                .await
        );
    }

    /// `SessionMode::parse` round-trips every variant via its wire id, with
    /// the canonical (uppercase) spelling.
    #[test]
    fn session_mode_parse_round_trip() {
        for mode in [
            SessionMode::Lutz,
            SessionMode::Code,
            SessionMode::Ask,
            SessionMode::Plan,
        ] {
            assert_eq!(
                SessionMode::parse(mode.as_str()),
                Some(mode),
                "round-trip failed for {mode:?}"
            );
        }
    }

    /// Clients sometimes lowercase or mixed-case the wire id; `parse` must
    /// accept any casing because the wire form is documented as
    /// case-insensitive.
    #[test]
    fn session_mode_parse_is_case_insensitive() {
        assert_eq!(SessionMode::parse("lutz"), Some(SessionMode::Lutz));
        assert_eq!(SessionMode::parse("Code"), Some(SessionMode::Code));
        assert_eq!(SessionMode::parse("aSk"), Some(SessionMode::Ask));
        assert_eq!(SessionMode::parse("plan"), Some(SessionMode::Plan));
    }

    /// Unknown / empty / whitespace-only ids must return `None` rather than
    /// silently mapping to a default mode.
    #[test]
    fn session_mode_parse_rejects_unknown() {
        assert_eq!(SessionMode::parse(""), None);
        assert_eq!(SessionMode::parse("EDIT"), None);
        assert_eq!(SessionMode::parse("LUT"), None);
        assert_eq!(SessionMode::parse(" LUTZ "), None);
    }

    /// `PermissionMode::parse` round-trips every variant. Wire ids are
    /// camelCase here (mirrors `claude-agent-acp`) and case-sensitive,
    /// unlike `SessionMode`.
    #[test]
    fn permission_mode_parse_round_trip_all_variants() {
        for mode in [
            PermissionMode::Default,
            PermissionMode::AcceptEdits,
            PermissionMode::ReadOnly,
            PermissionMode::BypassPermissions,
        ] {
            assert_eq!(
                PermissionMode::parse(mode.as_str()),
                Some(mode),
                "round-trip failed for {mode:?}"
            );
        }
        assert_eq!(PermissionMode::parse(""), None);
        assert_eq!(PermissionMode::parse("ACCEPTEDITS"), None);
        assert_eq!(PermissionMode::parse("accept-edits"), None);
    }

    /// `create_session` writes a manifest.json that round-trips through
    /// `read_manifest_from_zip` -- the disk persistence format must stay
    /// stable, otherwise a session created today won't load tomorrow.
    #[tokio::test]
    async fn create_session_persists_manifest_to_disk() {
        let store = SessionStore::new("initial-model".to_string());
        let cwd =
            std::env::temp_dir().join(format!("brokk-acp-rust-create-{}", uuid::Uuid::new_v4()));
        let session = store.create_session(cwd.clone()).await;

        let zip_path = session_zip_path(&cwd, &session.id);
        assert!(zip_path.exists(), "session zip must be written to disk");

        let manifest = read_manifest_from_zip(&zip_path).expect("manifest must round-trip");
        assert_eq!(manifest.id, session.id);
        assert_eq!(manifest.mode.as_deref(), Some("LUTZ"));
        assert_eq!(manifest.model.as_deref(), Some("initial-model"));

        let _ = std::fs::remove_dir_all(&cwd);
    }

    /// `get_session` for an unknown id (with no on-disk zip either) must
    /// return None, not panic or allocate a session under the wrong id.
    #[tokio::test]
    async fn get_session_unknown_returns_none() {
        let store = SessionStore::new("m".to_string());
        let cwd = std::env::temp_dir().join(format!(
            "brokk-acp-rust-get-unknown-{}",
            uuid::Uuid::new_v4()
        ));
        std::fs::create_dir_all(&cwd).ok();
        assert!(store.get_session("does-not-exist", &cwd).await.is_none());
        let _ = std::fs::remove_dir_all(&cwd);
    }

    /// Cold path: a session that's been evicted from memory must reload
    /// from its on-disk zip on the next `get_session`. Mode and model
    /// survive the round-trip via the manifest.
    #[tokio::test]
    async fn get_session_loads_from_disk_when_cold() {
        let store = SessionStore::new("m".to_string());
        let cwd =
            std::env::temp_dir().join(format!("brokk-acp-rust-cold-{}", uuid::Uuid::new_v4()));
        let created = store.create_session(cwd.clone()).await;
        let id = created.id.clone();

        // Persist a non-default mode so we can verify it survives the reload.
        store
            .set_mode(&id, SessionMode::Plan)
            .await
            .expect("set_mode persists");

        // Evict from memory; the on-disk zip is unaffected.
        store.sessions.write().await.remove(&id);
        store.registries.write().await.remove(&id);

        let reloaded = store
            .get_session(&id, &cwd)
            .await
            .expect("session must reload from disk");
        assert_eq!(reloaded.id, id);
        assert_eq!(reloaded.mode, SessionMode::Plan);
        assert_eq!(reloaded.permission_mode, PermissionMode::Default);
        assert!(reloaded.always_allow_tools.is_empty());

        let _ = std::fs::remove_dir_all(&cwd);
    }

    /// Permission-mode setters/getters round-trip, default to `Default`,
    /// and report `false` for unknown sessions instead of silently
    /// inserting an entry.
    #[tokio::test]
    async fn permission_mode_setter_and_getter_round_trip() {
        let store = SessionStore::new("m".to_string());
        let cwd =
            std::env::temp_dir().join(format!("brokk-acp-rust-perm-{}", uuid::Uuid::new_v4()));
        let session = store.create_session(cwd.clone()).await;
        let id = session.id.clone();

        // Defaults out of the box.
        assert_eq!(
            store.permission_mode(&id).await,
            Some(PermissionMode::Default)
        );

        assert!(
            store
                .set_permission_mode(&id, PermissionMode::AcceptEdits)
                .await
        );
        assert_eq!(
            store.permission_mode(&id).await,
            Some(PermissionMode::AcceptEdits)
        );

        // Unknown session: false, not Ok with a phantom insert.
        assert!(
            !store
                .set_permission_mode("no-such", PermissionMode::ReadOnly)
                .await
        );
        assert_eq!(store.permission_mode("no-such").await, None);

        let _ = std::fs::remove_dir_all(&cwd);
    }

    /// `add_always_allow` is in-memory only and survives across queries
    /// for the same session id.
    #[tokio::test]
    async fn always_allow_set_is_session_scoped() {
        let store = SessionStore::new("m".to_string());
        let cwd = std::env::temp_dir().join(format!(
            "brokk-acp-rust-always-allow-{}",
            uuid::Uuid::new_v4()
        ));
        let s = store.create_session(cwd.clone()).await;

        assert!(!store.is_always_allowed(&s.id, "writeFile").await);
        store.add_always_allow(&s.id, "writeFile").await;
        assert!(store.is_always_allowed(&s.id, "writeFile").await);
        // Different tool name, same session: still false.
        assert!(!store.is_always_allowed(&s.id, "runShellCommand").await);
        // Unknown session never reports allowed.
        assert!(!store.is_always_allowed("no-such", "writeFile").await);

        let _ = std::fs::remove_dir_all(&cwd);
    }

    /// `start_prompt` registers a token, `cancel_prompt` flips it,
    /// `finish_prompt` clears the registry. Together this is the gate
    /// `add_turn` relies on to serialize per-session writes.
    #[tokio::test]
    async fn cancel_prompt_lifecycle() {
        let store = SessionStore::new("m".to_string());
        let token = store.start_prompt("s1").await;
        assert!(!token.is_cancelled());

        store.cancel_prompt("s1").await;
        assert!(token.is_cancelled(), "cancel_prompt should fire the token");

        store.finish_prompt("s1").await;
        // After finish, cancel becomes a no-op (no token registered).
        store.cancel_prompt("s1").await;
    }

    /// `cancel_prompt` for a session with no in-flight prompt must be a
    /// silent no-op rather than a panic.
    #[tokio::test]
    async fn cancel_prompt_unknown_session_is_noop() {
        let store = SessionStore::new("m".to_string());
        store.cancel_prompt("never-started").await;
    }

    /// `set_default_model` flows into the next `create_session`. The
    /// previous default (set by the constructor) must NOT be sticky.
    #[tokio::test]
    async fn set_default_model_drives_subsequent_create_session() {
        let store = SessionStore::new("first".to_string());
        store.set_default_model("second".into()).await;
        let cwd =
            std::env::temp_dir().join(format!("brokk-acp-rust-default-{}", uuid::Uuid::new_v4()));
        let s = store.create_session(cwd.clone()).await;
        assert_eq!(s.model, "second");
        let _ = std::fs::remove_dir_all(&cwd);
    }

    /// `set_available_models` round-trips through `available_models` --
    /// this is the cache the agent populates on init and reuses for every
    /// `session/new` and `session/set_config_option` validation.
    #[tokio::test]
    async fn available_models_round_trip() {
        let store = SessionStore::new("m".to_string());
        assert!(store.available_models().await.is_empty());

        let models = vec!["a".to_string(), "b".to_string()];
        store.set_available_models(models.clone()).await;
        assert_eq!(store.available_models().await, models);

        // Setting an empty list is allowed (model discovery cleared).
        store.set_available_models(vec![]).await;
        assert!(store.available_models().await.is_empty());
    }

    /// `list_sessions_from_disk` ignores non-zip files in the sessions
    /// directory (e.g. half-written `.tmp` files from a crashed write,
    /// or stray editor backups). Otherwise the list could surface entries
    /// that fail to load on resume.
    #[tokio::test]
    async fn list_sessions_from_disk_filters_non_zip() {
        let store = SessionStore::new("m".to_string());
        let cwd =
            std::env::temp_dir().join(format!("brokk-acp-rust-list-{}", uuid::Uuid::new_v4()));
        let s = store.create_session(cwd.clone()).await;

        // Drop a non-zip file alongside the real session zip.
        let dir = sessions_dir(&cwd);
        std::fs::write(dir.join("not-a-session.txt"), "hello").unwrap();
        // And a stray .tmp from a crashed write.
        std::fs::write(dir.join("garbage.tmp"), "junk").unwrap();

        let listed = store.list_sessions_from_disk(&cwd).await;
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].id, s.id);

        let _ = std::fs::remove_dir_all(&cwd);
    }

    /// Concurrent `load_into_memory_if_cold` calls for the same id may
    /// both read the zip from disk; the `or_insert` race-keeper must keep
    /// exactly one entry in `sessions`. Regression coverage for the
    /// "race window" comment in `load_into_memory_if_cold`.
    #[tokio::test]
    async fn concurrent_cold_loads_dedupe() {
        let store = SessionStore::new("m".to_string());
        let cwd =
            std::env::temp_dir().join(format!("brokk-acp-rust-race-{}", uuid::Uuid::new_v4()));
        let created = store.create_session(cwd.clone()).await;
        let id = created.id.clone();

        // Evict the in-memory entry so both calls take the cold path.
        store.sessions.write().await.remove(&id);

        let store2 = store.clone();
        let id2 = id.clone();
        let cwd2 = cwd.clone();
        let h1 = tokio::spawn(async move { store2.get_session(&id2, &cwd2).await });
        let store3 = store.clone();
        let id3 = id.clone();
        let cwd3 = cwd.clone();
        let h2 = tokio::spawn(async move { store3.get_session(&id3, &cwd3).await });

        let r1 = h1.await.unwrap();
        let r2 = h2.await.unwrap();
        assert!(r1.is_some() && r2.is_some());

        let len = store.sessions.read().await.len();
        assert_eq!(len, 1, "both racers must collapse to a single entry");

        let _ = std::fs::remove_dir_all(&cwd);
    }

    /// `update_cwd` rewrites the in-memory cwd; subsequent prompts use the
    /// new directory when computing zip paths. Persistence side is tested
    /// implicitly via `add_turn`/`set_mode`.
    #[tokio::test]
    async fn update_cwd_changes_in_memory_cwd() {
        let store = SessionStore::new("m".to_string());
        let cwd1 =
            std::env::temp_dir().join(format!("brokk-acp-rust-cwd1-{}", uuid::Uuid::new_v4()));
        let s = store.create_session(cwd1.clone()).await;

        let cwd2 = std::env::temp_dir().join("brokk-acp-rust-cwd2-replacement");
        store.update_cwd(&s.id, cwd2.clone()).await;
        let after = store.sessions.read().await.get(&s.id).cloned().unwrap();
        assert_eq!(after.cwd, cwd2);

        let _ = std::fs::remove_dir_all(&cwd1);
    }

    /// Round-trip a full conversation history through the zip: write four
    /// turns with `add_turn`, then re-read with `read_history_from_zip`
    /// and verify both the user prompt and the agent response survive.
    #[tokio::test]
    async fn add_turn_round_trips_history_through_zip() {
        let store = SessionStore::with_limits(
            "m".to_string(),
            SessionLimits {
                max_sessions: 0,
                max_history_turns: 0,
            },
        );
        let cwd =
            std::env::temp_dir().join(format!("brokk-acp-rust-history-{}", uuid::Uuid::new_v4()));
        let s = store.create_session(cwd.clone()).await;

        for i in 0..4 {
            store
                .add_turn(
                    &s.id,
                    ConversationTurn {
                        user_prompt: format!("user-{i}"),
                        agent_response: format!("agent-{i}"),
                        ..Default::default()
                    },
                )
                .await
                .expect("persist must succeed");
        }

        let on_disk = read_history_from_zip(&session_zip_path(&cwd, &s.id));
        assert_eq!(on_disk.len(), 4);
        // Persisted format uses `markdownContentId` so the user prompt is
        // re-derived from `taskDescription` (null) -- only the agent
        // response is round-tripped on this path. Iteration order is the
        // serde_json object's (BTreeMap-keyed by random UUID), so compare
        // as a set rather than positionally.
        let actual: std::collections::HashSet<String> =
            on_disk.iter().map(|t| t.agent_response.clone()).collect();
        let expected: std::collections::HashSet<String> =
            (0..4).map(|i| format!("agent-{i}")).collect();
        assert_eq!(actual, expected);

        let _ = std::fs::remove_dir_all(&cwd);
    }

    /// Tool calls and results must round-trip through the session zip
    /// (#3409). Without this, `session/load` fed the LLM only the final
    /// agent_response and the model would repeat searches/reads it had
    /// already performed in the prior turn.
    #[tokio::test]
    async fn add_turn_round_trips_tool_exchanges_through_zip() {
        let store = SessionStore::with_limits(
            "m".to_string(),
            SessionLimits {
                max_sessions: 0,
                max_history_turns: 0,
            },
        );
        let cwd = std::env::temp_dir().join(format!(
            "brokk-acp-rust-tool-exchanges-{}",
            uuid::Uuid::new_v4()
        ));
        let s = store.create_session(cwd.clone()).await;

        let exchanges = vec![
            ToolExchange {
                call_id: "call_abc".into(),
                tool_name: "readFile".into(),
                arguments: r#"{"path":"src/lib.rs"}"#.into(),
                result: "fn main() {}\n".into(),
            },
            ToolExchange {
                call_id: "call_xyz".into(),
                tool_name: "searchFileContents".into(),
                arguments: r#"{"pattern":"TODO"}"#.into(),
                result: "no matches".into(),
            },
        ];

        store
            .add_turn(
                &s.id,
                ConversationTurn {
                    user_prompt: "explore the repo".into(),
                    agent_response: "found one file".into(),
                    tool_exchanges: exchanges.clone(),
                },
            )
            .await
            .expect("persist must succeed");

        let on_disk = read_history_from_zip(&session_zip_path(&cwd, &s.id));
        assert_eq!(on_disk.len(), 1);
        let turn = &on_disk[0];
        assert_eq!(turn.agent_response, "found one file");
        assert_eq!(turn.tool_exchanges.len(), 2);

        // Pair-up by call_id since the messages JSON ordering inside the
        // task fragment is preserved but we don't want the test to depend
        // on iteration order if that ever changes.
        let by_id: std::collections::HashMap<&str, &ToolExchange> = turn
            .tool_exchanges
            .iter()
            .map(|e| (e.call_id.as_str(), e))
            .collect();

        let abc = by_id.get("call_abc").expect("readFile exchange present");
        assert_eq!(abc.tool_name, "readFile");
        assert_eq!(abc.arguments, r#"{"path":"src/lib.rs"}"#);
        assert_eq!(abc.result, "fn main() {}\n");

        let xyz = by_id.get("call_xyz").expect("search exchange present");
        assert_eq!(xyz.tool_name, "searchFileContents");
        assert_eq!(xyz.arguments, r#"{"pattern":"TODO"}"#);
        assert_eq!(xyz.result, "no matches");

        let _ = std::fs::remove_dir_all(&cwd);
    }

    /// Reading an old-format zip (no tool_call / tool_result messages)
    /// must yield turns with empty `tool_exchanges` -- not panic, not
    /// drop the turn entirely. Backward-compat guarantee for sessions
    /// written before #3409 landed.
    #[tokio::test]
    async fn read_history_returns_empty_exchanges_for_legacy_zips() {
        let store = SessionStore::with_limits(
            "m".to_string(),
            SessionLimits {
                max_sessions: 0,
                max_history_turns: 0,
            },
        );
        let cwd = std::env::temp_dir().join(format!(
            "brokk-acp-rust-legacy-zip-{}",
            uuid::Uuid::new_v4()
        ));
        let s = store.create_session(cwd.clone()).await;

        // No tool exchanges -- mimics every turn ever written before
        // #3409: messages array contains only user + ai entries.
        store
            .add_turn(
                &s.id,
                ConversationTurn {
                    user_prompt: "hi".into(),
                    agent_response: "hello".into(),
                    ..Default::default()
                },
            )
            .await
            .expect("persist must succeed");

        let on_disk = read_history_from_zip(&session_zip_path(&cwd, &s.id));
        assert_eq!(on_disk.len(), 1);
        assert!(on_disk[0].tool_exchanges.is_empty());

        let _ = std::fs::remove_dir_all(&cwd);
    }
}
