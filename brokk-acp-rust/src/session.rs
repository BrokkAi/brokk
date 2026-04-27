use std::collections::{HashMap, HashSet};
use std::io::{Read as IoRead, Write as IoWrite};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use serde::{Deserialize, Serialize};
use tokio::sync::RwLock;
use tokio_util::sync::CancellationToken;

use crate::tools::ToolRegistry;

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

#[derive(Debug, Clone)]
pub struct ConversationTurn {
    pub user_prompt: String,
    pub agent_response: String,
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
    //    - messages: [{role, contentId, reasoningContentId}] (old format)
    //    - markdownContentId: points to the rendered output (new format)
    let mut turns = Vec::new();
    if let Some(tasks) = fragments.get("task").and_then(|t| t.as_object()) {
        for (_id, task) in tasks {
            // Try markdownContentId first (newer format: pre-rendered markdown)
            if let Some(content_id) = task.get("markdownContentId").and_then(|v| v.as_str())
                && let Some(text) = content_map.get(content_id)
            {
                let description = task
                    .get("taskDescription")
                    .and_then(|v| v.as_str())
                    .unwrap_or("");
                turns.push(ConversationTurn {
                    user_prompt: description.to_string(),
                    agent_response: text.clone(),
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
                        _ => {}
                    }
                }
                if !assistant_text.is_empty() {
                    turns.push(ConversationTurn {
                        user_prompt: user_text,
                        agent_response: assistant_text,
                    });
                }
            }
        }
    }

    turns
}

/// Write a new empty session zip compatible with the executor.
fn write_new_session_zip(zip_path: &Path, manifest: &SessionManifest) {
    let dir = zip_path.parent().unwrap();
    if let Err(e) = std::fs::create_dir_all(dir) {
        tracing::warn!("failed to create sessions dir {}: {e}", dir.display());
        return;
    }

    let tmp = zip_path.with_extension("tmp");
    let file = match std::fs::File::create(&tmp) {
        Ok(f) => f,
        Err(e) => {
            tracing::warn!("failed to create session zip {}: {e}", tmp.display());
            return;
        }
    };

    let mut zip = zip::ZipWriter::new(file);
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);

    // manifest.json
    let manifest_json = serde_json::to_string_pretty(manifest).unwrap_or_default();
    if zip.start_file("manifest.json", options).is_ok() {
        let _ = zip.write_all(manifest_json.as_bytes());
    }

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
    if zip.start_file("contexts.jsonl", options).is_ok() {
        let line = serde_json::to_string(&context_line).unwrap_or_default();
        let _ = zip.write_all(line.as_bytes());
        let _ = zip.write_all(b"\n");
    }

    // Empty fragments
    let fragments = serde_json::json!({
        "version": 4,
        "referenced": {},
        "virtual": {},
        "task": {}
    });
    if zip.start_file("fragments-v4.json", options).is_ok() {
        let _ = zip.write_all(
            serde_json::to_string(&fragments)
                .unwrap_or_default()
                .as_bytes(),
        );
    }

    // Empty content metadata
    if zip.start_file("content_metadata.json", options).is_ok() {
        let _ = zip.write_all(b"{}");
    }

    // Empty group info
    let group_info = serde_json::json!({"contextToGroupId": {}, "groupLabels": {}});
    if zip.start_file("group_info.json", options).is_ok() {
        let _ = zip.write_all(
            serde_json::to_string(&group_info)
                .unwrap_or_default()
                .as_bytes(),
        );
    }

    let _ = zip.finish();
    if let Err(e) = std::fs::rename(&tmp, zip_path) {
        tracing::warn!("failed to rename session zip: {e}");
    }
}

/// Update manifest.json and add a conversation turn to an existing session zip.
fn append_turn_to_zip(zip_path: &Path, manifest: &SessionManifest, turn: &ConversationTurn) {
    // Read the existing zip, rebuild with new content
    let file = match std::fs::File::open(zip_path) {
        Ok(f) => f,
        Err(e) => {
            tracing::warn!("failed to open session zip for update: {e}");
            return;
        }
    };
    let mut archive = match zip::ZipArchive::new(file) {
        Ok(a) => a,
        Err(e) => {
            tracing::warn!("failed to read session zip: {e}");
            return;
        }
    };

    let tmp = zip_path.with_extension("tmp");
    let out_file = match std::fs::File::create(&tmp) {
        Ok(f) => f,
        Err(e) => {
            tracing::warn!("failed to create temp zip: {e}");
            return;
        }
    };

    let mut zip_writer = zip::ZipWriter::new(out_file);
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);

    // Generate content IDs for this turn
    let user_content_id = uuid::Uuid::new_v4().to_string();
    let response_content_id = uuid::Uuid::new_v4().to_string();
    let task_fragment_id = uuid::Uuid::new_v4().to_string();
    let new_context_id = uuid::Uuid::new_v4().to_string();

    // Read existing fragments and contexts
    let mut existing_fragments: serde_json::Value = serde_json::json!({
        "version": 4, "referenced": {}, "virtual": {}, "task": {}
    });
    let mut existing_contexts = String::new();
    let mut existing_content_metadata: serde_json::Value = serde_json::json!({});
    let mut existing_group_info: serde_json::Value =
        serde_json::json!({"contextToGroupId": {}, "groupLabels": {}});

    // Copy existing entries (except ones we'll rewrite), and read their content
    for i in 0..archive.len() {
        let mut entry = match archive.by_index(i) {
            Ok(e) => e,
            Err(_) => continue,
        };
        let name = entry.name().to_string();

        match name.as_str() {
            "manifest.json" => { /* we'll rewrite this */ }
            "fragments-v4.json" => {
                let mut buf = String::new();
                if entry.read_to_string(&mut buf).is_ok()
                    && let Ok(v) = serde_json::from_str(&buf)
                {
                    existing_fragments = v;
                }
            }
            "contexts.jsonl" => {
                let _ = entry.read_to_string(&mut existing_contexts);
            }
            "content_metadata.json" => {
                let mut buf = String::new();
                if entry.read_to_string(&mut buf).is_ok()
                    && let Ok(v) = serde_json::from_str(&buf)
                {
                    existing_content_metadata = v;
                }
            }
            "group_info.json" => {
                let mut buf = String::new();
                if entry.read_to_string(&mut buf).is_ok()
                    && let Ok(v) = serde_json::from_str(&buf)
                {
                    existing_group_info = v;
                }
            }
            _ => {
                // Copy other entries as-is (content/*.txt, images, etc.)
                let mut buf = Vec::new();
                let _ = entry.read_to_end(&mut buf);
                if zip_writer.start_file(&name, options).is_ok() {
                    let _ = zip_writer.write_all(&buf);
                }
            }
        }
    }

    // Write updated manifest.json
    let manifest_json = serde_json::to_string_pretty(manifest).unwrap_or_default();
    if zip_writer.start_file("manifest.json", options).is_ok() {
        let _ = zip_writer.write_all(manifest_json.as_bytes());
    }

    // Add the new task fragment referencing the response content
    if let Some(tasks) = existing_fragments
        .get_mut("task")
        .and_then(|t| t.as_object_mut())
    {
        tasks.insert(
            task_fragment_id.clone(),
            serde_json::json!({
                "id": task_fragment_id,
                "messages": [
                    {"role": "user", "contentId": user_content_id},
                    {"role": "ai", "contentId": response_content_id}
                ],
                "taskDescription": null,
                "markdownContentId": response_content_id,
                "escapeHtml": false
            }),
        );
    }

    // Write updated fragments
    if zip_writer.start_file("fragments-v4.json", options).is_ok() {
        let _ = zip_writer.write_all(
            serde_json::to_string(&existing_fragments)
                .unwrap_or_default()
                .as_bytes(),
        );
    }

    // Add new context entry referencing the task fragment
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
    if zip_writer.start_file("contexts.jsonl", options).is_ok() {
        let _ = zip_writer.write_all(contexts.as_bytes());
    }

    // Write content metadata
    if zip_writer
        .start_file("content_metadata.json", options)
        .is_ok()
    {
        let _ = zip_writer.write_all(
            serde_json::to_string(&existing_content_metadata)
                .unwrap_or_default()
                .as_bytes(),
        );
    }

    // Write group info
    if zip_writer.start_file("group_info.json", options).is_ok() {
        let _ = zip_writer.write_all(
            serde_json::to_string(&existing_group_info)
                .unwrap_or_default()
                .as_bytes(),
        );
    }

    // Write new content files
    let user_content_path = format!("content/{user_content_id}.txt");
    if zip_writer.start_file(&user_content_path, options).is_ok() {
        let _ = zip_writer.write_all(turn.user_prompt.as_bytes());
    }
    let response_content_path = format!("content/{response_content_id}.txt");
    if zip_writer
        .start_file(&response_content_path, options)
        .is_ok()
    {
        let _ = zip_writer.write_all(turn.agent_response.as_bytes());
    }

    let _ = zip_writer.finish();
    if let Err(e) = std::fs::rename(&tmp, zip_path) {
        tracing::warn!("failed to rename updated session zip: {e}");
    }
}

/// Replace manifest.json in an existing session zip, copying all other entries as-is.
/// Returns true on success.
fn rewrite_manifest_in_zip(zip_path: &Path, manifest: &SessionManifest) -> bool {
    let file = match std::fs::File::open(zip_path) {
        Ok(f) => f,
        Err(e) => {
            tracing::warn!("failed to open session zip for manifest rewrite: {e}");
            return false;
        }
    };
    let mut archive = match zip::ZipArchive::new(file) {
        Ok(a) => a,
        Err(e) => {
            tracing::warn!("failed to read session zip for manifest rewrite: {e}");
            return false;
        }
    };

    let tmp = zip_path.with_extension("tmp");
    let out_file = match std::fs::File::create(&tmp) {
        Ok(f) => f,
        Err(e) => {
            tracing::warn!("failed to create temp zip for manifest rewrite: {e}");
            return false;
        }
    };

    let mut zip_writer = zip::ZipWriter::new(out_file);
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);

    for i in 0..archive.len() {
        let mut entry = match archive.by_index(i) {
            Ok(e) => e,
            Err(_) => continue,
        };
        let name = entry.name().to_string();
        if name == "manifest.json" {
            continue;
        }
        let mut buf = Vec::new();
        if entry.read_to_end(&mut buf).is_err() {
            continue;
        }
        if zip_writer.start_file(&name, options).is_ok() {
            let _ = zip_writer.write_all(&buf);
        }
    }

    let manifest_json = serde_json::to_string_pretty(manifest).unwrap_or_default();
    if zip_writer.start_file("manifest.json", options).is_ok() {
        let _ = zip_writer.write_all(manifest_json.as_bytes());
    }

    if zip_writer.finish().is_err() {
        return false;
    }
    match std::fs::rename(&tmp, zip_path) {
        Ok(()) => true,
        Err(e) => {
            tracing::warn!("failed to rename rewritten session zip: {e}");
            false
        }
    }
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
}

impl SessionStore {
    pub fn new(default_model: String) -> Self {
        Self {
            sessions: Arc::new(RwLock::new(HashMap::new())),
            default_model: Arc::new(RwLock::new(default_model)),
            available_models: Arc::new(RwLock::new(Vec::new())),
            cancel_tokens: Arc::new(RwLock::new(HashMap::new())),
            registries: Arc::new(RwLock::new(HashMap::new())),
        }
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
        let zip_path = session_zip_path(&cwd, &id);
        let manifest = session.manifest.clone();
        let _ =
            tokio::task::spawn_blocking(move || write_new_session_zip(&zip_path, &manifest)).await;

        self.sessions.write().await.insert(id, session.clone());
        session
    }

    /// Get session from memory, or load from disk if it exists.
    pub async fn get_session(&self, id: &str, cwd: &Path) -> Option<Session> {
        // Check memory first
        if let Some(s) = self.sessions.read().await.get(id).cloned() {
            return Some(s);
        }
        // Try to load from executor's session zip on disk (on a blocking worker).
        let zip_path = session_zip_path(cwd, id);
        let (manifest, history) = tokio::task::spawn_blocking(move || {
            let manifest = read_manifest_from_zip(&zip_path)?;
            let history = read_history_from_zip(&zip_path);
            Some((manifest, history))
        })
        .await
        .ok()
        .flatten()?;

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

        // Permission mode is intentionally NOT persisted across sessions: a
        // resumed session always restarts at `Default`. This mirrors
        // `claude-agent-acp` and prevents a stale or tampered manifest from
        // silently auto-allowing every tool call on launch.
        let session = Session {
            id: manifest.id.clone(),
            cwd: cwd.to_path_buf(),
            mode,
            model,
            history,
            manifest,
            permission_mode: PermissionMode::Default,
            always_allow_tools: HashSet::new(),
        };
        self.sessions
            .write()
            .await
            .insert(id.to_string(), session.clone());
        Some(session)
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
        self.sessions.read().await.get(id).map(|s| s.permission_mode)
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

    pub async fn set_mode(&self, id: &str, mode: SessionMode) -> bool {
        // Update in-memory state and snapshot what we need for persistence.
        let snapshot = {
            let mut sessions = self.sessions.write().await;
            match sessions.get_mut(id) {
                Some(session) => {
                    session.mode = mode;
                    session.manifest.mode = Some(mode.as_str().to_string());
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
    pub async fn add_turn(&self, id: &str, turn: ConversationTurn) {
        // Mutate in-memory state first, then release the lock BEFORE blocking I/O.
        let snapshot = {
            let mut sessions = self.sessions.write().await;
            match sessions.get_mut(id) {
                Some(session) => {
                    session.history.push(turn.clone());
                    let now = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .unwrap_or_default()
                        .as_millis() as u64;
                    session.manifest.modified = now;
                    Some((
                        session_zip_path(&session.cwd, &session.id),
                        session.manifest.clone(),
                    ))
                }
                None => None,
            }
        };
        if let Some((zip_path, manifest)) = snapshot {
            let _ = tokio::task::spawn_blocking(move || {
                append_turn_to_zip(&zip_path, &manifest, &turn)
            })
            .await;
        }
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
