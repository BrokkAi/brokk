use super::{ToolResult, ToolStatus};
use std::path::Path;
use std::time::Duration;
use tokio::process::Command;

const MAX_OUTPUT_BYTES: usize = 100_000; // 100KB

pub async fn run_shell_command(cwd: &Path, command: &str, timeout_seconds: u64) -> ToolResult {
    if command.trim().is_empty() {
        return ToolResult {
            status: ToolStatus::RequestError,
            output: "Command must not be empty".to_string(),
        };
    }

    let result = tokio::time::timeout(
        Duration::from_secs(timeout_seconds),
        Command::new("sh")
            .arg("-c")
            .arg(command)
            .current_dir(cwd)
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .output(),
    )
    .await;

    match result {
        Ok(Ok(output)) => {
            let mut combined = String::new();

            let stdout = String::from_utf8_lossy(&output.stdout);
            if !stdout.is_empty() {
                combined.push_str(&stdout);
            }

            let stderr = String::from_utf8_lossy(&output.stderr);
            if !stderr.is_empty() {
                if !combined.is_empty() {
                    combined.push_str("\n--- stderr ---\n");
                }
                combined.push_str(&stderr);
            }

            // Truncate if too large
            if combined.len() > MAX_OUTPUT_BYTES {
                combined.truncate(MAX_OUTPUT_BYTES);
                combined.push_str("\n... output truncated");
            }

            let exit_code = output.status.code().unwrap_or(-1);
            if !output.status.success() {
                combined.push_str(&format!("\n\nExit code: {exit_code}"));
            }

            ToolResult {
                status: if output.status.success() {
                    ToolStatus::Success
                } else {
                    ToolStatus::RequestError
                },
                output: if combined.is_empty() {
                    format!("Command completed with exit code {exit_code}")
                } else {
                    combined
                },
            }
        }
        Ok(Err(e)) => ToolResult {
            status: ToolStatus::InternalError,
            output: format!("Failed to execute command: {e}"),
        },
        Err(_) => ToolResult {
            status: ToolStatus::RequestError,
            output: format!("Command timed out after {timeout_seconds}s"),
        },
    }
}
