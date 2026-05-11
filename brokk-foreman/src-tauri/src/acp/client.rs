//! ACP session manager.
//!
//! Wraps a single live ACP session: spawns the agent process (via
//! `launcher`), drives the `agent-client-protocol` connection on a tokio
//! task, forwards inbound `SessionUpdate` notifications and
//! `requestPermission` calls to the frontend as Tauri events, and exposes
//! a typed surface (`start`, `prompt`, `cancel`, `stop`, `respond_permission`)
//! to be called from `#[tauri::command]` handlers.
//!
//! Pattern mirrors the `yolo_one_shot_client.rs` example from the
//! `agent-client-protocol` crate, with a long-lived `mpsc::Receiver` loop
//! replacing the single-prompt body so the UI can drive multiple prompts.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;

use agent_client_protocol::schema::{
    CancelNotification, ContentBlock, InitializeRequest, NewSessionRequest, PromptRequest,
    ProtocolVersion, RequestPermissionOutcome, RequestPermissionRequest, RequestPermissionResponse,
    SelectedPermissionOutcome, SessionId, SessionNotification, TextContent,
};
use agent_client_protocol::{ByteStreams, Client, ConnectionTo};
use tauri::{AppHandle, Emitter};
use thiserror::Error;
use tokio::sync::{Mutex, mpsc, oneshot};
use tokio_util::compat::{TokioAsyncReadCompatExt, TokioAsyncWriteCompatExt};

use crate::acp::agents::{AgentRecord, launch_spec};
use crate::acp::launcher::{self, SpawnedAgent};
use crate::events::{
    ACP_PERMISSION_CHANNEL, ACP_SESSION_STATUS_CHANNEL, ACP_STDERR_CHANNEL, ACP_UPDATE_CHANNEL,
    AcpStderr, AcpUpdate, PermissionRequest, SessionStatus,
};

#[derive(Debug, Error)]
pub enum ClientError {
    #[error("a session is already running for agent {0:?}; stop it first")]
    AlreadyRunning(String),
    #[error("no active session")]
    NoActiveSession,
    #[error("session command channel is closed (driver task ended)")]
    ChannelClosed,
    #[error("launcher error: {0}")]
    Launcher(#[from] launcher::LauncherError),
    #[error("agent error: {0}")]
    Agent(#[from] crate::acp::agents::AgentsError),
    #[error("unknown permission request id {0:?}")]
    UnknownPermission(String),
}

/// Per-process state managed by Tauri. Holds at most one live session at a
/// time (v1 constraint).
pub struct SessionManager {
    inner: Mutex<Option<ActiveSession>>,
    pending_permissions: Arc<Mutex<HashMap<String, oneshot::Sender<RequestPermissionResponse>>>>,
}

struct ActiveSession {
    agent_id: String,
    cmd_tx: mpsc::Sender<SessionCommand>,
    /// Set once the driver task has handshaked and received a session id.
    session_id: Arc<Mutex<Option<String>>>,
    join: tokio::task::JoinHandle<()>,
}

#[derive(Debug)]
enum SessionCommand {
    Prompt(String),
    Cancel,
    Stop,
}

impl Default for SessionManager {
    fn default() -> Self {
        Self::new()
    }
}

impl SessionManager {
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(None),
            pending_permissions: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    /// Start a session against `record` with `cwd = repo_path`.
    pub async fn start(
        &self,
        record: AgentRecord,
        repo_path: PathBuf,
        app: AppHandle,
    ) -> Result<(), ClientError> {
        let mut slot = self.inner.lock().await;
        if let Some(active) = slot.as_ref() {
            return Err(ClientError::AlreadyRunning(active.agent_id.clone()));
        }

        let agent_id = record.id.clone();
        let _ = app.emit(
            ACP_SESSION_STATUS_CHANNEL,
            SessionStatus::Starting {
                agent_id: agent_id.clone(),
            },
        );

        let spec = launch_spec(&record)?;
        let spawned = launcher::spawn(&spec, &repo_path)?;

        let (cmd_tx, cmd_rx) = mpsc::channel::<SessionCommand>(32);
        let session_id_slot: Arc<Mutex<Option<String>>> = Arc::new(Mutex::new(None));

        let task = tokio::spawn(run_session(RunArgs {
            spawned,
            repo_path,
            agent_id: agent_id.clone(),
            app: app.clone(),
            permissions: self.pending_permissions.clone(),
            session_id_slot: session_id_slot.clone(),
            cmd_rx,
        }));

        *slot = Some(ActiveSession {
            agent_id,
            cmd_tx,
            session_id: session_id_slot,
            join: task,
        });
        Ok(())
    }

    pub async fn send_prompt(&self, text: String) -> Result<(), ClientError> {
        let slot = self.inner.lock().await;
        let active = slot.as_ref().ok_or(ClientError::NoActiveSession)?;
        active
            .cmd_tx
            .send(SessionCommand::Prompt(text))
            .await
            .map_err(|_| ClientError::ChannelClosed)
    }

    pub async fn cancel(&self) -> Result<(), ClientError> {
        let slot = self.inner.lock().await;
        let active = slot.as_ref().ok_or(ClientError::NoActiveSession)?;
        active
            .cmd_tx
            .send(SessionCommand::Cancel)
            .await
            .map_err(|_| ClientError::ChannelClosed)
    }

    pub async fn stop(&self) -> Result<(), ClientError> {
        let mut slot = self.inner.lock().await;
        if let Some(active) = slot.take() {
            let _ = active.cmd_tx.send(SessionCommand::Stop).await;
            active.join.abort();
            // Best-effort await — abort is enough to release the spawned
            // child via kill_on_drop, and we don't want to block the
            // caller indefinitely if the driver is wedged on a send.
            let _ = tokio::time::timeout(std::time::Duration::from_secs(2), async move {
                let _ = active.join.await;
            })
            .await;
        }
        Ok(())
    }

    /// Surface the frontend's decision for an outstanding permission
    /// prompt. `accept = true` selects the first available option,
    /// `false` resolves with `Cancelled`. v1 keeps the UX binary; refining
    /// "accept" into the agent's per-option ids is a v2 enhancement.
    pub async fn respond_permission(
        &self,
        request_id: &str,
        accept: bool,
        selected_option_id: Option<String>,
    ) -> Result<(), ClientError> {
        let mut pending = self.pending_permissions.lock().await;
        let tx = pending
            .remove(request_id)
            .ok_or_else(|| ClientError::UnknownPermission(request_id.into()))?;
        let response = if accept {
            let id = selected_option_id.unwrap_or_else(|| "allow".into());
            RequestPermissionResponse::new(RequestPermissionOutcome::Selected(
                SelectedPermissionOutcome::new(id),
            ))
        } else {
            RequestPermissionResponse::new(RequestPermissionOutcome::Cancelled)
        };
        let _ = tx.send(response);
        Ok(())
    }

    pub async fn current_session_id(&self) -> Option<String> {
        let slot = self.inner.lock().await;
        let active = slot.as_ref()?;
        active.session_id.lock().await.clone()
    }
}

struct RunArgs {
    spawned: SpawnedAgent,
    repo_path: PathBuf,
    agent_id: String,
    app: AppHandle,
    permissions: Arc<Mutex<HashMap<String, oneshot::Sender<RequestPermissionResponse>>>>,
    session_id_slot: Arc<Mutex<Option<String>>>,
    cmd_rx: mpsc::Receiver<SessionCommand>,
}

async fn run_session(args: RunArgs) {
    let RunArgs {
        spawned,
        repo_path,
        agent_id,
        app,
        permissions,
        session_id_slot,
        cmd_rx,
    } = args;

    let SpawnedAgent {
        child: _child,
        stdin,
        stdout,
        stderr,
    } = spawned;

    // Forward stderr as Tauri events so the agents page can show launch
    // errors. The task ends when the child closes its stderr pipe.
    let stderr_app = app.clone();
    let stderr_agent_id = agent_id.clone();
    tokio::spawn(async move {
        use tokio::io::{AsyncBufReadExt, BufReader};
        let mut lines = BufReader::new(stderr).lines();
        while let Ok(Some(line)) = lines.next_line().await {
            let _ = stderr_app.emit(
                ACP_STDERR_CHANNEL,
                AcpStderr {
                    agent_id: stderr_agent_id.clone(),
                    line,
                },
            );
        }
    });

    let transport = ByteStreams::new(stdin.compat_write(), stdout.compat());

    let app_for_notif = app.clone();
    let app_for_perm = app.clone();
    let permissions_for_handler = permissions.clone();

    let result = Client
        .builder()
        .on_receive_notification(
            async move |notification: SessionNotification, _cx| {
                let payload = AcpUpdate {
                    session_id: notification.session_id.to_string(),
                    update: serde_json::to_value(&notification.update).unwrap_or_default(),
                };
                if let Err(e) = app_for_notif.emit(ACP_UPDATE_CHANNEL, payload) {
                    tracing::warn!("emit {ACP_UPDATE_CHANNEL} failed: {e}");
                }
                Ok(())
            },
            agent_client_protocol::on_receive_notification!(),
        )
        .on_receive_request(
            async move |req: RequestPermissionRequest, responder, _cx| {
                let request_id = uuid::Uuid::new_v4().to_string();
                let (tx, rx) = oneshot::channel::<RequestPermissionResponse>();
                permissions_for_handler
                    .lock()
                    .await
                    .insert(request_id.clone(), tx);

                let payload = PermissionRequest {
                    request_id: request_id.clone(),
                    session_id: req.session_id.to_string(),
                    tool_call: serde_json::to_value(&req.tool_call).unwrap_or_default(),
                    options: serde_json::to_value(&req.options).unwrap_or_default(),
                };
                if let Err(e) = app_for_perm.emit(ACP_PERMISSION_CHANNEL, payload) {
                    tracing::warn!("emit {ACP_PERMISSION_CHANNEL} failed: {e}");
                    // Without a UI we cannot resolve the prompt; cancel
                    // so the agent isn't stuck waiting.
                    permissions_for_handler.lock().await.remove(&request_id);
                    return responder.respond(RequestPermissionResponse::new(
                        RequestPermissionOutcome::Cancelled,
                    ));
                }

                match rx.await {
                    Ok(response) => responder.respond(response),
                    Err(_) => responder.respond(RequestPermissionResponse::new(
                        RequestPermissionOutcome::Cancelled,
                    )),
                }
            },
            agent_client_protocol::on_receive_request!(),
        )
        .connect_with(transport, |connection: ConnectionTo<_>| async move {
            drive_session(
                connection,
                repo_path,
                agent_id,
                app,
                session_id_slot,
                cmd_rx,
            )
            .await
        })
        .await;

    if let Err(e) = result {
        tracing::warn!("ACP session driver ended with error: {e}");
    }
}

async fn drive_session(
    connection: ConnectionTo<agent_client_protocol::Agent>,
    repo_path: PathBuf,
    agent_id: String,
    app: AppHandle,
    session_id_slot: Arc<Mutex<Option<String>>>,
    mut cmd_rx: mpsc::Receiver<SessionCommand>,
) -> agent_client_protocol::Result<()> {
    tracing::info!(%agent_id, "ACP initialize");
    let init_resp = connection
        .send_request(InitializeRequest::new(ProtocolVersion::V1))
        .block_task()
        .await?;
    tracing::info!(?init_resp.agent_info, "ACP initialize complete");

    tracing::info!(repo_path = %repo_path.display(), "ACP session/new");
    let new_sess = connection
        .send_request(NewSessionRequest::new(repo_path))
        .block_task()
        .await?;
    let session_id: SessionId = new_sess.session_id;
    *session_id_slot.lock().await = Some(session_id.to_string());
    let _ = app.emit(
        ACP_SESSION_STATUS_CHANNEL,
        SessionStatus::Ready {
            agent_id: agent_id.clone(),
            session_id: session_id.to_string(),
        },
    );

    while let Some(cmd) = cmd_rx.recv().await {
        match cmd {
            SessionCommand::Prompt(text) => {
                let req = PromptRequest::new(
                    session_id.clone(),
                    vec![ContentBlock::Text(TextContent::new(text))],
                );
                if let Err(e) = connection.send_request(req).block_task().await {
                    tracing::warn!("prompt request failed: {e}");
                    let _ = app.emit(
                        ACP_SESSION_STATUS_CHANNEL,
                        SessionStatus::Failed {
                            agent_id: agent_id.clone(),
                            error: format!("prompt failed: {e}"),
                        },
                    );
                    break;
                }
            }
            SessionCommand::Cancel => {
                if let Err(e) =
                    connection.send_notification(CancelNotification::new(session_id.clone()))
                {
                    tracing::warn!("cancel notification failed: {e}");
                }
            }
            SessionCommand::Stop => break,
        }
    }

    let _ = app.emit(
        ACP_SESSION_STATUS_CHANNEL,
        SessionStatus::Stopped {
            agent_id,
            reason: "session ended".into(),
        },
    );
    Ok(())
}
