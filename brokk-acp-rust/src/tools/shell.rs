use super::sandbox::{self, ENV_WHITELIST, SandboxPolicy};
use super::{ToolResult, ToolStatus};
use std::path::Path;
use std::time::Duration;
use tokio::process::Command;

const MAX_OUTPUT_BYTES: usize = 100_000; // 100KB

const SANDBOX_BYPASS_WARNING: &str =
    "[WARNING] OS sandbox unavailable on this platform; the command above ran without one. \
     Install bubblewrap (`apt install bubblewrap`) on Linux to enable kernel-enforced isolation.\n";

pub async fn run_shell_command(
    cwd: &Path,
    command: &str,
    timeout_seconds: u64,
    policy: SandboxPolicy,
) -> ToolResult {
    if command.trim().is_empty() {
        return ToolResult {
            status: ToolStatus::RequestError,
            output: "Command must not be empty".to_string(),
        };
    }

    // Wrap once. `wrapped` owns the temp policy file (Seatbelt) and must
    // outlive the spawned child.
    let wrapped = match sandbox::wrap_command(policy, cwd, command) {
        Ok(w) => w,
        Err(e) => {
            // Sandbox-layer errors are tagged with `[sandbox]` by sandbox.rs
            // so the user can tell wrap-side failures from command-side ones.
            return ToolResult {
                status: ToolStatus::InternalError,
                output: format!("Failed to prepare sandbox: {e}"),
            };
        }
    };

    // The user requested a sandbox tier (ReadOnly / WorkspaceWrite) but the
    // platform tooling was missing. We still execute -- matching Java's
    // log-and-skip posture -- but prepend a visible warning to the output so
    // the LLM and the ACP client both know the call wasn't actually bounded.
    let bypass_warning = !matches!(policy, SandboxPolicy::None) && !wrapped.sandboxed;

    // Mirrors `Environment.createProcessBuilder` (Environment.java:647-661),
    // and stricter: we env_clear() and explicitly add only a small whitelist
    // so secrets in the parent process (OPENAI_API_KEY, AWS_*, GH tokens,
    // LD_PRELOAD/DYLD_*) cannot leak into LLM-driven shell calls. On Linux
    // the same scrubbing is applied via bwrap's `--clearenv`/`--setenv`.
    let mut cmd = Command::new(&wrapped.argv[0]);
    cmd.args(&wrapped.argv[1..])
        .current_dir(cwd)
        .env_clear()
        .env("PATH", "/usr/local/bin:/usr/bin:/bin")
        .env("TERM", "dumb")
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());
    for key in ENV_WHITELIST {
        if let Some(value) = std::env::var_os(key) {
            cmd.env(key, value);
        }
    }

    let result = tokio::time::timeout(Duration::from_secs(timeout_seconds), cmd.output()).await;

    // `wrapped` MUST stay in scope until output() resolves so the
    // TempPolicyFile Drop guard doesn't yank `sandbox-exec`'s `-f` profile
    // mid-call. The explicit drop is a tripwire for refactors that might
    // otherwise reuse `wrapped`'s name and shrink its lifetime.
    drop(wrapped);

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

            if combined.len() > MAX_OUTPUT_BYTES {
                combined.truncate(MAX_OUTPUT_BYTES);
                combined.push_str("\n... output truncated");
            }

            let exit_code = output.status.code().unwrap_or(-1);
            if !output.status.success() {
                combined.push_str(&format!("\n\nExit code: {exit_code}"));
            }

            if bypass_warning {
                combined.push('\n');
                combined.push_str(SANDBOX_BYPASS_WARNING);
            }

            ToolResult {
                status: if output.status.success() {
                    ToolStatus::Success
                } else {
                    ToolStatus::RequestError
                },
                output: if combined.is_empty() {
                    let mut s = format!("Command completed with exit code {exit_code}");
                    if bypass_warning {
                        s.push('\n');
                        s.push_str(SANDBOX_BYPASS_WARNING);
                    }
                    s
                } else {
                    combined
                },
            }
        }
        Ok(Err(e)) => ToolResult {
            status: ToolStatus::InternalError,
            output: format!("Failed to execute command: {e}"),
        },
        Err(_) => {
            let mut msg = format!("Command timed out after {timeout_seconds}s");
            if bypass_warning {
                msg.push('\n');
                msg.push_str(SANDBOX_BYPASS_WARNING);
            }
            ToolResult {
                status: ToolStatus::RequestError,
                output: msg,
            }
        }
    }
}
