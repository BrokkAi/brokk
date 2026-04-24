use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use tokio_util::sync::CancellationToken;

/// Session modes matching Brokk's existing mode set.
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

/// A single conversation turn in session history.
#[derive(Debug, Clone)]
pub struct ConversationTurn {
    pub user_prompt: String,
    pub agent_response: String,
}

/// Per-session state.
#[derive(Debug, Clone)]
pub struct Session {
    pub id: String,
    pub mode: SessionMode,
    pub model: String,
    pub reasoning_level: String,
    pub history: Vec<ConversationTurn>,
}

impl Session {
    pub fn new(id: String, model: String) -> Self {
        Self {
            id,
            mode: SessionMode::Lutz,
            model,
            reasoning_level: "medium".to_string(),
            history: Vec::new(),
        }
    }
}

/// Thread-safe session store.
#[derive(Debug, Clone)]
pub struct SessionStore {
    sessions: Arc<RwLock<HashMap<String, Session>>>,
    default_model: Arc<RwLock<String>>,
    /// Active cancellation tokens keyed by session ID.
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

    pub async fn create_session(&self) -> Session {
        let id = uuid::Uuid::new_v4().to_string();
        let model = self.default_model.read().await.clone();
        let session = Session::new(id.clone(), model);
        self.sessions.write().await.insert(id, session.clone());
        session
    }

    pub async fn get_session(&self, id: &str) -> Option<Session> {
        self.sessions.read().await.get(id).cloned()
    }

    pub async fn set_mode(&self, id: &str, mode: SessionMode) -> bool {
        if let Some(session) = self.sessions.write().await.get_mut(id) {
            session.mode = mode;
            true
        } else {
            false
        }
    }

    pub async fn set_model(&self, id: &str, model: String) {
        if let Some(session) = self.sessions.write().await.get_mut(id) {
            session.model = model;
        }
    }

    pub async fn add_turn(&self, id: &str, turn: ConversationTurn) {
        if let Some(session) = self.sessions.write().await.get_mut(id) {
            session.history.push(turn);
        }
    }

    pub async fn set_default_model(&self, model: String) {
        *self.default_model.write().await = model;
    }

    pub async fn default_model(&self) -> String {
        self.default_model.read().await.clone()
    }

    pub async fn list_sessions(&self) -> Vec<Session> {
        self.sessions.read().await.values().cloned().collect()
    }

    /// Create a new cancellation token for an active prompt on this session.
    /// Replaces any existing token (cancelling any previous in-flight request).
    pub async fn start_prompt(&self, session_id: &str) -> CancellationToken {
        let token = CancellationToken::new();
        self.cancel_tokens
            .write()
            .await
            .insert(session_id.to_string(), token.clone());
        token
    }

    /// Cancel the active prompt for this session (if any).
    pub async fn cancel_prompt(&self, session_id: &str) {
        if let Some(token) = self.cancel_tokens.read().await.get(session_id) {
            token.cancel();
        }
    }

    /// Remove the cancellation token after prompt completes.
    pub async fn finish_prompt(&self, session_id: &str) {
        self.cancel_tokens.write().await.remove(session_id);
    }
}
