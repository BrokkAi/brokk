use std::collections::HashMap;
use std::io::{Read as IoRead, Write as IoWrite};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use serde::{Deserialize, Serialize};
use tokio::sync::RwLock;
use tokio_util::sync::CancellationToken;

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
}

impl Session {
    pub fn new(id: String, cwd: PathBuf, model: String, name: String) -> Self {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        let manifest = SessionManifest {
            id: id.clone(),
            name,
            created: now,
            modified: now,
            version: "4.0".to_string(),
        };
        Self {
            id,
            cwd,
            mode: SessionMode::Lutz,
            model,
            history: Vec::new(),
            manifest,
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

#[derive(Debug, Clone)]
pub struct SessionStore {
    sessions: Arc<RwLock<HashMap<String, Session>>>,
    default_model: Arc<RwLock<String>>,
    cancel_tokens: Arc<RwLock<HashMap<String, CancellationToken>>>,
}

impl SessionStore {
    pub fn new(default_model: String) -> Self {
        Self {
            sessions: Arc::new(RwLock::new(HashMap::new())),
            default_model: Arc::new(RwLock::new(default_model)),
            cancel_tokens: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    /// Create a new session and write it to disk as a zip.
    pub async fn create_session(&self, cwd: PathBuf) -> Session {
        let id = uuid::Uuid::new_v4().to_string();
        let model = self.default_model.read().await.clone();
        let session = Session::new(id.clone(), cwd.clone(), model, "New Session".to_string());

        // Write to disk in executor-compatible format
        let zip_path = session_zip_path(&cwd, &id);
        write_new_session_zip(&zip_path, &session.manifest);

        self.sessions.write().await.insert(id, session.clone());
        session
    }

    /// Get session from memory, or load from disk if it exists.
    pub async fn get_session(&self, id: &str, cwd: &Path) -> Option<Session> {
        // Check memory first
        if let Some(s) = self.sessions.read().await.get(id).cloned() {
            return Some(s);
        }
        // Try to load from executor's session zip on disk
        let zip_path = session_zip_path(cwd, id);
        let manifest = read_manifest_from_zip(&zip_path)?;
        let history = read_history_from_zip(&zip_path);
        let model = self.default_model.read().await.clone();
        let session = Session {
            id: manifest.id.clone(),
            cwd: cwd.to_path_buf(),
            mode: SessionMode::Lutz,
            model,
            history,
            manifest,
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

    pub async fn set_mode(&self, id: &str, mode: SessionMode) -> bool {
        if let Some(session) = self.sessions.write().await.get_mut(id) {
            session.mode = mode;
            true
        } else {
            false
        }
    }

    pub async fn set_default_model(&self, model: String) {
        *self.default_model.write().await = model;
    }

    /// Add a conversation turn and persist it to the session zip.
    pub async fn add_turn(&self, id: &str, turn: ConversationTurn) {
        if let Some(session) = self.sessions.write().await.get_mut(id) {
            session.history.push(turn.clone());
            // Update modified timestamp
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64;
            session.manifest.modified = now;

            let zip_path = session_zip_path(&session.cwd, &session.id);
            append_turn_to_zip(&zip_path, &session.manifest, &turn);
        }
    }

    /// List sessions from disk, filtered by cwd.
    pub async fn list_sessions_from_disk(&self, cwd: &Path) -> Vec<SessionManifest> {
        list_manifests_from_disk(cwd)
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
