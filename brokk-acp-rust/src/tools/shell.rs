use super::sandbox::{self, ENV_WHITELIST, SandboxPolicy};
use super::{ToolResult, ToolStatus};
use std::path::Path;
use std::time::Duration;
use tokio::process::Command;

const MAX_OUTPUT_BYTES: usize = 100_000; // 100KB

const SANDBOX_BYPASS_WARNING: &str = "[WARNING] OS sandbox unavailable on this platform; the command above ran without one. \
     Install bubblewrap (`apt install bubblewrap`) on Linux to enable kernel-enforced isolation.\n";

/// Per-process rlimit caps applied to every `runShellCommand` child on Unix.
///
/// These are a parallel safety net to the OS sandbox: the sandbox bounds
/// *what* the command can touch (filesystem, namespaces); these bound *how
/// much* it can consume (memory, processes, fds, file size). They apply
/// even with `SandboxPolicy::None` and on platforms where the sandbox is
/// unavailable, so a fork bomb or `yes > /tmp/x` from an unsandboxed shell
/// can't take the host down.
///
/// Each can be overridden per-process via the matching env var; the value
/// `unlimited` (or empty) lifts that specific cap (`RLIM_INFINITY`). An
/// invalid env value falls back to the default and emits a warning at
/// parse time (before pre_exec, so `tracing` is safe).
#[cfg(unix)]
const DEFAULT_RLIMIT_AS_BYTES: u64 = 4 * 1024 * 1024 * 1024; // 4 GiB virtual address space per child
#[cfg(unix)]
const DEFAULT_RLIMIT_NPROC: u64 = 1024; // user-wide process count (Linux: per real UID)
#[cfg(unix)]
const DEFAULT_RLIMIT_NOFILE: u64 = 4096; // open file descriptors per child
#[cfg(unix)]
const DEFAULT_RLIMIT_FSIZE_BYTES: u64 = 1024 * 1024 * 1024; // 1 GiB max file size per child

#[cfg(unix)]
const RLIMIT_AS_ENV: &str = "BROKK_ACP_RLIMIT_AS_BYTES";
#[cfg(unix)]
const RLIMIT_NPROC_ENV: &str = "BROKK_ACP_RLIMIT_NPROC";
#[cfg(unix)]
const RLIMIT_NOFILE_ENV: &str = "BROKK_ACP_RLIMIT_NOFILE";
#[cfg(unix)]
const RLIMIT_FSIZE_ENV: &str = "BROKK_ACP_RLIMIT_FSIZE_BYTES";

#[cfg(unix)]
#[derive(Debug, Clone, Copy)]
struct RlimitConfig {
    as_bytes: u64,
    nproc: u64,
    nofile: u64,
    fsize_bytes: u64,
}

#[cfg(unix)]
impl RlimitConfig {
    fn from_env() -> Self {
        Self {
            as_bytes: parse_rlimit_env(RLIMIT_AS_ENV, DEFAULT_RLIMIT_AS_BYTES),
            nproc: parse_rlimit_env(RLIMIT_NPROC_ENV, DEFAULT_RLIMIT_NPROC),
            nofile: parse_rlimit_env(RLIMIT_NOFILE_ENV, DEFAULT_RLIMIT_NOFILE),
            fsize_bytes: parse_rlimit_env(RLIMIT_FSIZE_ENV, DEFAULT_RLIMIT_FSIZE_BYTES),
        }
    }
}

/// Parse a single rlimit-override env var. Empty string and "unlimited"
/// (case-insensitive) map to `RLIM_INFINITY` (no cap). Other non-numeric
/// values log a warning and fall back to `default`. Pure -- exposed for
/// unit tests so the parsing matrix can be exercised without spawning.
#[cfg(unix)]
fn parse_rlimit_env(var: &str, default: u64) -> u64 {
    match std::env::var(var) {
        Ok(s) => parse_rlimit_value(var, &s, default),
        Err(_) => default,
    }
}

#[cfg(unix)]
fn parse_rlimit_value(var: &str, raw: &str, default: u64) -> u64 {
    let trimmed = raw.trim();
    if trimmed.is_empty() || trimmed.eq_ignore_ascii_case("unlimited") {
        return libc::RLIM_INFINITY;
    }
    match trimmed.parse::<u64>() {
        Ok(v) => v,
        Err(_) => {
            tracing::warn!(
                var,
                value = trimmed,
                "invalid rlimit env var value; falling back to default"
            );
            default
        }
    }
}

/// Apply `setrlimit` for AS, NPROC, NOFILE, FSIZE on the calling process.
///
/// Intended to run inside `Command::pre_exec`, i.e. between `fork()` and
/// `exec()`. Must remain async-signal-safe -- no allocation, no `tracing`,
/// no locking. Errors round-trip back to the parent via the `io::Result`
/// pre_exec contract, which aborts the spawn.
///
/// We deliberately swallow EPERM on individual limits: a sandbox or the
/// already-running NOFILE count may make a particular cap un-tightenable,
/// and we'd rather still spawn (with whatever caps did stick) than refuse
/// the call entirely. setrlimit only fails with EPERM when raising the
/// hard limit without `CAP_SYS_RESOURCE`; our values are always lowering.
#[cfg(unix)]
fn apply_rlimits(config: &RlimitConfig) -> std::io::Result<()> {
    set_rlimit(libc::RLIMIT_AS, config.as_bytes)?;
    set_rlimit(libc::RLIMIT_NPROC, config.nproc)?;
    set_rlimit(libc::RLIMIT_NOFILE, config.nofile)?;
    set_rlimit(libc::RLIMIT_FSIZE, config.fsize_bytes)?;
    Ok(())
}

#[cfg(unix)]
fn set_rlimit<R>(resource: R, value: u64) -> std::io::Result<()>
where
    R: TryInto<libc::c_int>,
{
    // `libc::RLIMIT_*` are `__rlimit_resource_t` (u32) on Linux and
    // `c_int` on macOS; use `as _` to coerce to whatever `setrlimit`
    // expects on this platform.
    let res = match resource.try_into() {
        Ok(r) => r,
        Err(_) => return Ok(()),
    };
    let rlim = libc::rlimit {
        rlim_cur: value as libc::rlim_t,
        rlim_max: value as libc::rlim_t,
    };
    // SAFETY: `setrlimit` is async-signal-safe and is called only from
    // `pre_exec`, which is the canonical place to apply per-child caps
    // on Unix. The pointer is to a stack-local that outlives the call.
    let ret = unsafe { libc::setrlimit(res as _, &rlim) };
    if ret == 0 {
        Ok(())
    } else {
        let err = std::io::Error::last_os_error();
        // EPERM is expected when the caller can't lower past the hard
        // limit (rare) or raise without privilege; skip rather than
        // abort the spawn so the other caps still apply.
        if err.raw_os_error() == Some(libc::EPERM) {
            Ok(())
        } else {
            Err(err)
        }
    }
}

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

    // Apply per-process rlimits via `pre_exec` so AS/NPROC/NOFILE/FSIZE
    // caps land on the child (and any sandbox wrapper, which inherits
    // them) without affecting the parent agent. Read env once here so
    // the closure body stays async-signal-safe.
    #[cfg(unix)]
    {
        let rlimits = RlimitConfig::from_env();
        // SAFETY: `apply_rlimits` only calls `libc::setrlimit`, which is
        // async-signal-safe. No allocation, no locking, no `tracing` --
        // safe to invoke between fork() and exec().
        unsafe {
            cmd.pre_exec(move || apply_rlimits(&rlimits));
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

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    #[test]
    fn parse_rlimit_value_accepts_decimal_byte_count() {
        assert_eq!(parse_rlimit_value("X", "1024", 999), 1024);
        assert_eq!(parse_rlimit_value("X", "  4096  ", 999), 4096);
    }

    #[test]
    fn parse_rlimit_value_treats_unlimited_and_empty_as_infinity() {
        assert_eq!(
            parse_rlimit_value("X", "unlimited", 999),
            libc::RLIM_INFINITY
        );
        assert_eq!(
            parse_rlimit_value("X", "UNLIMITED", 999),
            libc::RLIM_INFINITY
        );
        assert_eq!(parse_rlimit_value("X", "", 999), libc::RLIM_INFINITY);
        assert_eq!(parse_rlimit_value("X", "   ", 999), libc::RLIM_INFINITY);
    }

    #[test]
    fn parse_rlimit_value_falls_back_on_garbage() {
        // Non-numeric input should not crash and should return the default
        // -- protects against operator typos in env config.
        assert_eq!(parse_rlimit_value("X", "lots", 999), 999);
        assert_eq!(parse_rlimit_value("X", "-1", 999), 999);
        assert_eq!(parse_rlimit_value("X", "1.5", 999), 999);
    }

    #[test]
    fn apply_rlimits_succeeds_with_defaults_outside_pre_exec() {
        // Sanity check that the libc bindings, resource constants, and
        // type coercions all line up on this platform. Calling setrlimit
        // on the current process is safe: the values match or relax the
        // typical defaults, and we only lower for soft+hard equally.
        let original = current_rlimits();
        let cfg = RlimitConfig {
            as_bytes: libc::RLIM_INFINITY,
            nproc: libc::RLIM_INFINITY,
            nofile: libc::RLIM_INFINITY,
            fsize_bytes: libc::RLIM_INFINITY,
        };
        // Setting to RLIM_INFINITY may EPERM if hard limit is finite;
        // apply_rlimits swallows EPERM, so this should always Ok.
        apply_rlimits(&cfg).expect("apply_rlimits should not error on RLIM_INFINITY");

        // Restore the originals so test ordering doesn't matter.
        for (resource, rlim) in original {
            // SAFETY: same async-signal-safe call; running outside pre_exec
            // here, so no fork-state hazards.
            unsafe {
                let _ = libc::setrlimit(resource as _, &rlim);
            }
        }
    }

    /// Capture current AS/NPROC/NOFILE/FSIZE limits. Returns `i64` for
    /// the resource id so the test compiles on both Linux (where libc
    /// uses `u32` `__rlimit_resource_t`) and macOS (where it uses `i32`);
    /// callers cast back to the platform type with `as _`.
    fn current_rlimits() -> Vec<(i64, libc::rlimit)> {
        [
            libc::RLIMIT_AS as i64,
            libc::RLIMIT_NPROC as i64,
            libc::RLIMIT_NOFILE as i64,
            libc::RLIMIT_FSIZE as i64,
        ]
        .into_iter()
        .map(|r| {
            let mut rlim = libc::rlimit {
                rlim_cur: 0,
                rlim_max: 0,
            };
            // SAFETY: getrlimit is async-signal-safe and only reads.
            unsafe {
                libc::getrlimit(r as _, &mut rlim);
            }
            (r, rlim)
        })
        .collect()
    }
}
