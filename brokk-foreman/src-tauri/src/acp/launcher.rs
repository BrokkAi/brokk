//! Spawn an ACP agent as a child process and hand back the typed stdio
//! handles to the client wrapper.
//!
//! The spawn pattern (piped stdin/stdout/stderr, `kill_on_drop(true)`)
//! mirrors `brokk-acp-rust/src/bifrost_client.rs`. The actual ACP wire
//! framing is handled by `agent_client_protocol::ByteStreams` once the
//! pipes are wrapped in `tokio_util::compat`.

use std::path::Path;
use std::process::Stdio;

use thiserror::Error;
use tokio::process::{Child, ChildStderr, ChildStdin, ChildStdout, Command};

use crate::acp::agents::LaunchSpec;

#[derive(Debug, Error)]
pub enum LauncherError {
    #[error("failed to spawn agent process: {0}")]
    Spawn(#[from] std::io::Error),
    #[error("agent process did not expose stdin")]
    NoStdin,
    #[error("agent process did not expose stdout")]
    NoStdout,
    #[error("agent process did not expose stderr")]
    NoStderr,
    #[error("npx is not on PATH; install Node.js >=18 or pick a different agent")]
    NpxMissing,
    #[error("uvx is not on PATH; install uv (Astral) or pick a different agent")]
    UvxMissing,
}

/// The spawned agent and its three stdio pipes. The caller owns `child`
/// (drops it to kill the process via `kill_on_drop`) and the pipes.
pub struct SpawnedAgent {
    pub child: Child,
    pub stdin: ChildStdin,
    pub stdout: ChildStdout,
    pub stderr: ChildStderr,
}

/// Spawn an agent according to `spec` with `cwd` as the working directory
/// of the child process.
pub fn spawn(spec: &LaunchSpec, cwd: &Path) -> Result<SpawnedAgent, LauncherError> {
    use crate::acp::agents::LaunchKind;
    use which::which;

    match spec.kind {
        LaunchKind::Npx => {
            which("npx").map_err(|_| LauncherError::NpxMissing)?;
        }
        LaunchKind::Uvx => {
            which("uvx").map_err(|_| LauncherError::UvxMissing)?;
        }
        LaunchKind::Binary => {}
    }

    tracing::info!(
        program = %spec.program.display(),
        args = ?spec.args,
        cwd = %cwd.display(),
        "spawning ACP agent"
    );

    let mut cmd = Command::new(&spec.program);
    cmd.args(&spec.args)
        .envs(&spec.env)
        .current_dir(cwd)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    let mut child = cmd.spawn()?;
    let stdin = child.stdin.take().ok_or(LauncherError::NoStdin)?;
    let stdout = child.stdout.take().ok_or(LauncherError::NoStdout)?;
    let stderr = child.stderr.take().ok_or(LauncherError::NoStderr)?;
    Ok(SpawnedAgent {
        child,
        stdin,
        stdout,
        stderr,
    })
}
