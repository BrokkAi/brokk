//! brokk-tui: an interactive terminal client for an ACP-speaking agent.
//!
//! Spawns the agent as a child process, talks JSON-RPC over its stdio,
//! and renders the session in a ratatui chat UI.

mod acp;
mod app;
mod event;
mod ui;

use std::path::PathBuf;

use anyhow::{Context, Result};
use clap::Parser;
use tokio::sync::mpsc;

#[derive(Parser)]
#[command(name = "brokk-tui", version, about = "Interactive ACP chat TUI")]
struct Cli {
    /// Command to spawn the ACP agent. Parsed with shell-words so quoted
    /// arguments are honored. Defaults to `brokk-acp` on PATH.
    #[arg(short, long, default_value = "brokk-acp")]
    command: String,

    /// Working directory used when opening a new session. Defaults to
    /// the current directory.
    #[arg(long)]
    cwd: Option<PathBuf>,

    /// Path to a log file. When unset, logging is disabled because the
    /// TUI owns the terminal and stderr would corrupt the screen.
    #[arg(long, env = "BROKK_TUI_LOG")]
    log_file: Option<PathBuf>,

    /// Capture the agent subprocess's stderr to this file. When unset
    /// the agent's stderr is discarded via `Stdio::null()` (/dev/null on
    /// Unix, NUL on Windows) so it doesn't scribble over the TUI.
    #[arg(long, env = "BROKK_TUI_AGENT_STDERR")]
    agent_stderr: Option<PathBuf>,
}

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();
    init_logging(cli.log_file.as_deref())?;

    let (command, args) = parse_command(&cli.command)?;
    let cwd = match cli.cwd {
        Some(p) => p,
        None => std::env::current_dir().context("current dir")?,
    };

    let (event_tx, event_rx) = mpsc::unbounded_channel();
    let (cmd_tx, cmd_rx) = mpsc::unbounded_channel();

    let runtime_cfg = acp::AcpRuntimeConfig {
        command,
        args,
        cwd,
        agent_stderr: cli.agent_stderr,
    };

    // Drive the ACP runtime on its own task so the UI can own the
    // current task's stdio (ratatui draws through stdout while ACP
    // talks to the agent's stdout/stdin, which are separate file
    // descriptors).
    let acp_handle = tokio::spawn(async move {
        if let Err(e) = acp::run(runtime_cfg, event_tx, cmd_rx).await {
            tracing::error!("acp runtime error: {e:#}");
        }
    });

    let ui_result = ui::run(cmd_tx, event_rx).await;

    // UI exited. `cmd_tx` was moved into `ui::run` and is now dropped,
    // which causes `drive_session` to see `None` on its `recv()` and
    // return, after which `acp::run` calls `child.kill().await` and
    // cleans up. Wait for that natural shutdown for a bounded window so
    // the agent process is reaped, and only abort as a last resort if
    // the runtime is wedged (e.g. blocked on a `block_task().await` for
    // a never-arriving response).
    let abort_handle = acp_handle.abort_handle();
    match tokio::time::timeout(std::time::Duration::from_secs(2), acp_handle).await {
        Ok(join_res) => {
            if let Err(e) = join_res {
                tracing::warn!("acp task join: {e}");
            }
        }
        Err(_elapsed) => {
            tracing::warn!(
                "acp runtime did not exit within 2s; aborting (child may not be reaped)"
            );
            abort_handle.abort();
        }
    }

    ui_result
}

fn parse_command(s: &str) -> Result<(PathBuf, Vec<String>)> {
    let parts = shell_words::split(s).context("split command string")?;
    let mut iter = parts.into_iter();
    let program = iter.next().context("empty command string")?;
    Ok((PathBuf::from(program), iter.collect()))
}

fn init_logging(path: Option<&std::path::Path>) -> Result<()> {
    use tracing_subscriber::{EnvFilter, fmt};

    let Some(path) = path else {
        return Ok(());
    };
    let parent = path.parent().filter(|p| !p.as_os_str().is_empty());
    if let Some(parent) = parent {
        std::fs::create_dir_all(parent).with_context(|| format!("create log dir {parent:?}"))?;
    }
    let file = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
        .with_context(|| format!("open log {path:?}"))?;
    let filter =
        EnvFilter::try_from_env("BROKK_TUI_LOG_LEVEL").unwrap_or_else(|_| EnvFilter::new("info"));
    fmt()
        .with_writer(file)
        .with_env_filter(filter)
        .with_ansi(false)
        .init();
    Ok(())
}
