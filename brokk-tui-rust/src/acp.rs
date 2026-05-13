//! ACP client runtime: spawns the agent subprocess, wires JSON-RPC over
//! stdio, and bridges UI commands/events through two mpsc channels.

use std::path::PathBuf;

use agent_client_protocol::schema::{
    CancelNotification, ClientCapabilities, ContentBlock, FileSystemCapabilities,
    InitializeRequest, NewSessionRequest, PromptRequest, ProtocolVersion, RequestPermissionOutcome,
    RequestPermissionRequest, RequestPermissionResponse, SelectedPermissionOutcome, SessionId,
    SessionNotification, TextContent,
};
use agent_client_protocol::{Agent, ByteStreams, Client, ConnectTo, ConnectionTo};
use anyhow::{Context, Result};
use tokio::process::{Child, Command};
use tokio::sync::{mpsc, oneshot};
use tokio_util::compat::{TokioAsyncReadCompatExt, TokioAsyncWriteCompatExt};

use crate::event::{PermissionDecision, PermissionPrompt, UiCommand, UiEvent};

pub struct AcpRuntimeConfig {
    pub command: PathBuf,
    pub args: Vec<String>,
    pub cwd: PathBuf,
    /// Where the agent's stderr should go. `None` discards it (via
    /// `Stdio::null()`, which maps to /dev/null on Unix and NUL on
    /// Windows) so the agent's logs don't bleed into the TUI. Pass a
    /// path to capture for debugging.
    pub agent_stderr: Option<PathBuf>,
}

/// Spawn the agent subprocess and run the ACP client to completion.
/// Pumps `ui_rx` for `UiCommand`s and emits `UiEvent`s onto `ui_tx`.
///
/// Returns once the connection is closed or the user requests shutdown.
pub async fn run(
    cfg: AcpRuntimeConfig,
    ui_tx: mpsc::UnboundedSender<UiEvent>,
    ui_rx: mpsc::UnboundedReceiver<UiCommand>,
) -> Result<()> {
    let (mut child, child_stdin, child_stdout) =
        spawn_agent(&cfg.command, &cfg.args, cfg.agent_stderr.as_deref())?;
    let transport = ByteStreams::new(child_stdin.compat_write(), child_stdout.compat());

    let result = drive_client(transport, cfg.cwd.clone(), ui_tx.clone(), ui_rx).await;

    let kill = child.kill().await;
    if let Err(e) = kill {
        tracing::warn!("kill child: {e}");
    }
    if let Err(e) = &result {
        let _ = ui_tx.send(UiEvent::Fatal(format!("acp: {e}")));
    }
    result
}

/// Run the full ACP client state machine over an arbitrary transport.
/// Factored out of `run` so integration tests can plug in an in-process
/// duplex stream and drive a mock agent without spawning a subprocess.
pub async fn drive_client<T>(
    transport: T,
    cwd: PathBuf,
    ui_tx: mpsc::UnboundedSender<UiEvent>,
    mut ui_rx: mpsc::UnboundedReceiver<UiCommand>,
) -> Result<()>
where
    T: ConnectTo<Client>,
{
    // Channel for permission prompts that the UI needs to answer.
    // The on_receive_request closure forwards (req, responder) here and
    // returns immediately so the JSON-RPC dispatch loop stays unblocked.
    let perm_ui_tx = ui_tx.clone();
    let notif_ui_tx = ui_tx.clone();
    let session_ui_tx = ui_tx.clone();
    let result = Client
        .builder()
        .on_receive_notification(
            async move |notification: SessionNotification, _cx| {
                let _ = notif_ui_tx.send(UiEvent::SessionUpdate(notification.update));
                Ok(())
            },
            agent_client_protocol::on_receive_notification!(),
        )
        .on_receive_request(
            async move |request: RequestPermissionRequest, responder, _cx| {
                let (tx, rx) = oneshot::channel::<PermissionDecision>();
                let prompt = PermissionPrompt {
                    tool_call: request.tool_call,
                    options: request.options,
                    responder: tx,
                };
                if perm_ui_tx.send(UiEvent::PermissionRequest(prompt)).is_err() {
                    return responder.respond(RequestPermissionResponse::new(
                        RequestPermissionOutcome::Cancelled,
                    ));
                }
                let outcome = match rx.await {
                    Ok(PermissionDecision::Selected(id)) => {
                        RequestPermissionOutcome::Selected(SelectedPermissionOutcome::new(id))
                    }
                    _ => RequestPermissionOutcome::Cancelled,
                };
                responder.respond(RequestPermissionResponse::new(outcome))
            },
            agent_client_protocol::on_receive_request!(),
        )
        .connect_with(transport, |conn: ConnectionTo<Agent>| async move {
            if let Err(e) = drive_session(conn, cwd, &session_ui_tx, &mut ui_rx).await {
                let msg = format!("{e:#}");
                let _ = session_ui_tx.send(UiEvent::Fatal(msg.clone()));
                return Err(agent_client_protocol::Error::internal_error()
                    .data(serde_json::Value::String(msg)));
            }
            Ok(())
        })
        .await;

    result.map_err(|e| anyhow::anyhow!("acp client error: {e}"))?;
    Ok(())
}

/// Initialize the agent, open a session, then loop forwarding prompts and
/// cancellations until the UI requests shutdown or the agent closes the
/// connection.
async fn drive_session(
    conn: ConnectionTo<Agent>,
    cwd: PathBuf,
    ui_tx: &mpsc::UnboundedSender<UiEvent>,
    ui_rx: &mut mpsc::UnboundedReceiver<UiCommand>,
) -> Result<()> {
    // Advertise our client capabilities. We do not yet implement
    // `fs/read_text_file` or `fs/write_text_file`, so we declare both as
    // false; same for terminals. Permission prompts always work.
    let init_req = InitializeRequest::new(ProtocolVersion::V1).client_capabilities(
        ClientCapabilities::new()
            .fs(FileSystemCapabilities::new()
                .read_text_file(false)
                .write_text_file(false))
            .terminal(false),
    );
    let init_resp = conn
        .send_request(init_req)
        .block_task()
        .await
        .context("initialize")?;
    let _ = ui_tx.send(UiEvent::Connected {
        agent_name: init_resp.agent_info.as_ref().map(|i| i.name.clone()),
        agent_version: init_resp.agent_info.as_ref().map(|i| i.version.clone()),
    });

    let session = conn
        .send_request(NewSessionRequest::new(cwd))
        .block_task()
        .await
        .context("new_session")?;
    let session_id: SessionId = session.session_id;
    let _ = ui_tx.send(UiEvent::SessionStarted {
        session_id: session_id.to_string(),
    });

    while let Some(cmd) = ui_rx.recv().await {
        match cmd {
            UiCommand::SendPrompt { text } => {
                let req = PromptRequest::new(
                    session_id.clone(),
                    vec![ContentBlock::Text(TextContent::new(text))],
                );
                match conn.send_request(req).block_task().await {
                    Ok(resp) => {
                        let _ = ui_tx.send(UiEvent::PromptDone {
                            stop_reason: resp.stop_reason,
                        });
                    }
                    Err(e) => {
                        let _ = ui_tx.send(UiEvent::Warning(format!("prompt failed: {e}")));
                    }
                }
            }
            UiCommand::CancelPrompt => {
                if let Err(e) = conn.send_notification(CancelNotification::new(session_id.clone()))
                {
                    let _ = ui_tx.send(UiEvent::Warning(format!("cancel failed: {e}")));
                }
            }
            UiCommand::Shutdown => break,
        }
    }
    Ok(())
}

fn spawn_agent(
    command: &PathBuf,
    args: &[String],
    stderr_path: Option<&std::path::Path>,
) -> Result<(
    Child,
    tokio::process::ChildStdin,
    tokio::process::ChildStdout,
)> {
    let mut cmd = Command::new(command);
    cmd.args(args);
    cmd.stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped());
    match stderr_path {
        Some(path) => {
            let file = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(path)
                .with_context(|| format!("open agent stderr {path:?}"))?;
            cmd.stderr(std::process::Stdio::from(file));
        }
        None => {
            cmd.stderr(std::process::Stdio::null());
        }
    }
    let mut child = cmd
        .spawn()
        .with_context(|| format!("spawning agent {command:?}"))?;
    let stdin = child.stdin.take().context("child stdin not piped")?;
    let stdout = child.stdout.take().context("child stdout not piped")?;
    Ok((child, stdin, stdout))
}

#[cfg(test)]
mod tests {
    use super::*;
    use agent_client_protocol::Agent as AgentRole;
    use agent_client_protocol::schema::{
        ContentBlock, ContentChunk, InitializeResponse, NewSessionResponse, PromptResponse,
        SessionId, SessionNotification, SessionUpdate, StopReason, TextContent,
    };
    use std::time::Duration;
    use tokio::io::split;

    /// Spawn a minimal in-process ACP agent over a duplex stream. The
    /// agent answers Initialize/NewSession/Prompt, streams one chunk back
    /// on every prompt, and reports EndTurn.
    async fn run_mock_agent(stream: tokio::io::DuplexStream) {
        let (r, w) = split(stream);
        let transport = ByteStreams::new(w.compat_write(), r.compat());
        let _ = AgentRole
            .builder()
            .on_receive_request(
                async move |_req: agent_client_protocol::schema::InitializeRequest,
                            responder,
                            _cx| {
                    responder.respond(InitializeResponse::new(
                        agent_client_protocol::schema::ProtocolVersion::V1,
                    ))
                },
                agent_client_protocol::on_receive_request!(),
            )
            .on_receive_request(
                async move |_req: agent_client_protocol::schema::NewSessionRequest,
                            responder,
                            _cx| {
                    responder.respond(NewSessionResponse::new(SessionId::new("test-session")))
                },
                agent_client_protocol::on_receive_request!(),
            )
            .on_receive_request(
                async move |req: agent_client_protocol::schema::PromptRequest,
                            responder,
                            cx: ConnectionTo<agent_client_protocol::Client>| {
                    let session_id = req.session_id.clone();
                    // Stream one chunk so the client sees a SessionUpdate
                    // before the prompt resolves.
                    let _ = cx.send_notification(SessionNotification::new(
                        session_id,
                        SessionUpdate::AgentMessageChunk(ContentChunk::new(ContentBlock::Text(
                            TextContent::new("ack"),
                        ))),
                    ));
                    responder.respond(PromptResponse::new(StopReason::EndTurn))
                },
                agent_client_protocol::on_receive_request!(),
            )
            .connect_with(transport, |_cx| async move {
                // Keep the agent alive until the client side closes.
                futures::future::pending::<()>().await;
                Ok(())
            })
            .await;
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn full_prompt_turn_against_mock_agent() {
        let (client_side, agent_side) = tokio::io::duplex(64 * 1024);
        let (cr, cw) = split(client_side);
        let client_transport = ByteStreams::new(cw.compat_write(), cr.compat());

        let agent_task = tokio::spawn(run_mock_agent(agent_side));

        let (ui_tx, mut ui_rx) = mpsc::unbounded_channel::<UiEvent>();
        let (cmd_tx, cmd_rx) = mpsc::unbounded_channel::<UiCommand>();

        let client_task = tokio::spawn(drive_client(
            client_transport,
            std::env::temp_dir(),
            ui_tx,
            cmd_rx,
        ));

        // Pull Connected + SessionStarted.
        let mut saw_connected = false;
        let mut saw_session = false;
        while !(saw_connected && saw_session) {
            let ev = tokio::time::timeout(Duration::from_secs(5), ui_rx.recv())
                .await
                .expect("timeout waiting for handshake")
                .expect("channel closed");
            match ev {
                UiEvent::Connected { .. } => saw_connected = true,
                UiEvent::SessionStarted { .. } => saw_session = true,
                UiEvent::Warning(_) | UiEvent::Fatal(_) => panic!("unexpected: {ev:?}"),
                _ => {}
            }
        }

        cmd_tx
            .send(UiCommand::SendPrompt {
                text: "hello".to_string(),
            })
            .expect("send prompt");

        let mut saw_update = false;
        let mut saw_done = false;
        while !(saw_update && saw_done) {
            let ev = tokio::time::timeout(Duration::from_secs(5), ui_rx.recv())
                .await
                .expect("timeout waiting for prompt turn")
                .expect("channel closed");
            match ev {
                UiEvent::SessionUpdate(SessionUpdate::AgentMessageChunk(c)) => {
                    if let ContentBlock::Text(t) = &c.content {
                        assert_eq!(t.text, "ack");
                    }
                    saw_update = true;
                }
                UiEvent::PromptDone { stop_reason } => {
                    assert!(matches!(stop_reason, StopReason::EndTurn));
                    saw_done = true;
                }
                UiEvent::Warning(_) | UiEvent::Fatal(_) => panic!("unexpected: {ev:?}"),
                _ => {}
            }
        }

        cmd_tx.send(UiCommand::Shutdown).expect("shutdown");
        let _ = tokio::time::timeout(Duration::from_secs(2), client_task).await;
        agent_task.abort();
    }

    /// Dropping the command channel must drive `drive_client` to a clean
    /// return promptly -- this is the graceful shutdown path the main
    /// binary relies on (UI exits, `cmd_tx` is dropped, the ACP task
    /// joins within the timeout instead of needing `abort()`).
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn drive_client_returns_when_command_channel_drops() {
        let (client_side, agent_side) = tokio::io::duplex(64 * 1024);
        let (cr, cw) = split(client_side);
        let client_transport = ByteStreams::new(cw.compat_write(), cr.compat());

        let agent_task = tokio::spawn(run_mock_agent(agent_side));

        let (ui_tx, mut ui_rx) = mpsc::unbounded_channel::<UiEvent>();
        let (cmd_tx, cmd_rx) = mpsc::unbounded_channel::<UiCommand>();

        let client_task = tokio::spawn(drive_client(
            client_transport,
            std::env::temp_dir(),
            ui_tx,
            cmd_rx,
        ));

        // Wait for the handshake so we know the loop is actually inside
        // its `recv()` waiting on commands.
        let mut saw_session = false;
        while !saw_session {
            let ev = tokio::time::timeout(Duration::from_secs(5), ui_rx.recv())
                .await
                .expect("handshake timeout")
                .expect("channel closed");
            if matches!(ev, UiEvent::SessionStarted { .. }) {
                saw_session = true;
            }
        }

        // Drop the sender side. drive_session sees `None` on its
        // `recv()` and must return; drive_client must then resolve.
        drop(cmd_tx);

        let join = tokio::time::timeout(Duration::from_secs(2), client_task)
            .await
            .expect("drive_client did not return after cmd channel drop");
        join.expect("client task panicked")
            .expect("drive_client returned error");
        agent_task.abort();
    }
}
