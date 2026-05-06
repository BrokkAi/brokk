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
/// much* it can consume (memory, processes, fds, file size, CPU, core
/// dumps). They apply even with `SandboxPolicy::None` and on platforms
/// where the sandbox is unavailable, so a fork bomb or `yes > /tmp/x`
/// from an unsandboxed shell can't take the host down.
///
/// Each can be overridden per-process via the matching env var; the value
/// `unlimited` (or empty) lifts that specific cap (`RLIM_INFINITY`). An
/// invalid env value falls back to the default and emits a warning at
/// parse time (before pre_exec, so `tracing` is safe).
///
/// The `BROKK_ACP_RLIMIT_*` env vars are read from the **parent agent's
/// environment** at spawn time, not the child's: the child's env is
/// scrubbed via `env_clear()` plus an explicit whitelist (see
/// `ENV_WHITELIST` in `sandbox.rs`), so even if these names were leaked
/// into a malicious child, the values inside the sandbox cannot widen
/// the parent's caps.
///
/// Defaults are deliberately generous to accommodate JVM tooling
/// (`mvn`, `gradle`), Go binaries (which reserve large virtual ranges),
/// `rustc` LTO builds, and shared dev/CI hosts where `RLIMIT_NPROC` is
/// counted per-UID across all sessions. Values are clamped to the
/// parent's hard limit at spawn time, so requests above what the host
/// permits land at the host limit (with a warning) instead of failing
/// silently with EPERM inside `pre_exec`.
#[cfg(unix)]
const DEFAULT_RLIMIT_AS_BYTES: u64 = 32 * 1024 * 1024 * 1024; // 32 GiB virtual address space per child
#[cfg(unix)]
const DEFAULT_RLIMIT_NPROC: u64 = 8192; // user-wide process count (Linux: per real UID across all sessions)
#[cfg(unix)]
const DEFAULT_RLIMIT_NOFILE: u64 = 4096; // open file descriptors per child
#[cfg(unix)]
const DEFAULT_RLIMIT_FSIZE_BYTES: u64 = 1024 * 1024 * 1024; // 1 GiB max single-file write per child
#[cfg(unix)]
const DEFAULT_RLIMIT_CPU_SECONDS: u64 = 1800; // 30 minutes wall-equivalent CPU time per child
#[cfg(unix)]
const DEFAULT_RLIMIT_CORE_BYTES: u64 = 0; // disable core dumps (info-leak vector, can fill disk)

#[cfg(unix)]
const RLIMIT_AS_ENV: &str = "BROKK_ACP_RLIMIT_AS_BYTES";
#[cfg(unix)]
const RLIMIT_NPROC_ENV: &str = "BROKK_ACP_RLIMIT_NPROC";
#[cfg(unix)]
const RLIMIT_NOFILE_ENV: &str = "BROKK_ACP_RLIMIT_NOFILE";
#[cfg(unix)]
const RLIMIT_FSIZE_ENV: &str = "BROKK_ACP_RLIMIT_FSIZE_BYTES";
#[cfg(unix)]
const RLIMIT_CPU_ENV: &str = "BROKK_ACP_RLIMIT_CPU_SECONDS";
#[cfg(unix)]
const RLIMIT_CORE_ENV: &str = "BROKK_ACP_RLIMIT_CORE_BYTES";

#[cfg(unix)]
#[derive(Debug, Clone, Copy)]
struct RlimitConfig {
    as_bytes: u64,
    nproc: u64,
    nofile: u64,
    fsize_bytes: u64,
    cpu_seconds: u64,
    core_bytes: u64,
}

#[cfg(unix)]
impl RlimitConfig {
    fn from_env() -> Self {
        Self {
            as_bytes: parse_rlimit_env(RLIMIT_AS_ENV, DEFAULT_RLIMIT_AS_BYTES),
            nproc: parse_rlimit_env(RLIMIT_NPROC_ENV, DEFAULT_RLIMIT_NPROC),
            nofile: parse_rlimit_env(RLIMIT_NOFILE_ENV, DEFAULT_RLIMIT_NOFILE),
            fsize_bytes: parse_rlimit_env(RLIMIT_FSIZE_ENV, DEFAULT_RLIMIT_FSIZE_BYTES),
            cpu_seconds: parse_rlimit_env(RLIMIT_CPU_ENV, DEFAULT_RLIMIT_CPU_SECONDS),
            core_bytes: parse_rlimit_env(RLIMIT_CORE_ENV, DEFAULT_RLIMIT_CORE_BYTES),
        }
    }

    /// Clamp each requested value to the parent's current *hard* limit and
    /// warn the operator when a clamp occurs. Run once per spawn in the
    /// parent, before `pre_exec`, so `tracing` is safe. The child inherits
    /// the parent's hard limits across `fork()` unchanged, so by the time
    /// `setrlimit` runs in the child the requested value will be `<= hard`
    /// and won't EPERM. This makes the closure body's EPERM swallow a
    /// belt-and-suspenders check for racy limit changes rather than the
    /// primary path: configured caps either land or surface as warnings.
    fn clamp_to_parent_hard_limits(self) -> Self {
        Self {
            as_bytes: clamp_to_hard_limit(RLIMIT_AS_ENV, libc::RLIMIT_AS, self.as_bytes),
            nproc: clamp_to_hard_limit(RLIMIT_NPROC_ENV, libc::RLIMIT_NPROC, self.nproc),
            nofile: clamp_to_hard_limit(RLIMIT_NOFILE_ENV, libc::RLIMIT_NOFILE, self.nofile),
            fsize_bytes: clamp_to_hard_limit(
                RLIMIT_FSIZE_ENV,
                libc::RLIMIT_FSIZE,
                self.fsize_bytes,
            ),
            cpu_seconds: clamp_to_hard_limit(RLIMIT_CPU_ENV, libc::RLIMIT_CPU, self.cpu_seconds),
            core_bytes: clamp_to_hard_limit(RLIMIT_CORE_ENV, libc::RLIMIT_CORE, self.core_bytes),
        }
    }
}

#[cfg(unix)]
fn clamp_to_hard_limit<R>(name: &str, resource: R, requested: u64) -> u64
where
    R: TryInto<libc::c_int>,
{
    let res: libc::c_int = match resource.try_into() {
        Ok(r) => r,
        Err(_) => return requested,
    };
    let mut rlim = libc::rlimit {
        rlim_cur: 0,
        rlim_max: 0,
    };
    // SAFETY: getrlimit is async-signal-safe and only reads.
    let ret = unsafe { libc::getrlimit(res as _, &mut rlim) };
    if ret != 0 {
        return requested;
    }
    // rlim_t is u64 on tier-1 64-bit Unixes and u32 on 32-bit Linux;
    // cast to u64 to match the rest of the rlimit plumbing without
    // truncating on the small-int side.
    #[allow(clippy::unnecessary_cast)]
    let hard = rlim.rlim_max as u64;
    if hard == libc::RLIM_INFINITY {
        return requested;
    }
    if requested == libc::RLIM_INFINITY || requested > hard {
        tracing::warn!(
            var = name,
            requested,
            hard,
            "rlimit request exceeds parent's hard limit; clamping to hard limit"
        );
        hard
    } else {
        requested
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

/// Apply `setrlimit` for AS, NPROC, NOFILE, FSIZE, CPU, CORE on the
/// calling process.
///
/// Intended to run inside `Command::pre_exec`, i.e. between `fork()` and
/// `exec()`. Must remain async-signal-safe -- no allocation, no `tracing`,
/// no locking. Errors round-trip back to the parent via the `io::Result`
/// pre_exec contract, which aborts the spawn.
///
/// Each `set_rlimit` call only lowers the soft cap and leaves the
/// inherited hard cap unchanged (see comment in `set_rlimit`); EPERM
/// and EINVAL are swallowed so a single problematic resource doesn't
/// abort the whole spawn, while operator-config-vs-host mismatches
/// still surface as `tracing::warn!` from the parent-side
/// `clamp_to_parent_hard_limits`.
#[cfg(unix)]
fn apply_rlimits(config: &RlimitConfig) -> std::io::Result<()> {
    set_rlimit(libc::RLIMIT_AS, config.as_bytes)?;
    set_rlimit(libc::RLIMIT_NPROC, config.nproc)?;
    set_rlimit(libc::RLIMIT_NOFILE, config.nofile)?;
    set_rlimit(libc::RLIMIT_FSIZE, config.fsize_bytes)?;
    set_rlimit(libc::RLIMIT_CPU, config.cpu_seconds)?;
    set_rlimit(libc::RLIMIT_CORE, config.core_bytes)?;
    Ok(())
}

#[cfg(unix)]
fn set_rlimit<R>(resource: R, value: u64) -> std::io::Result<()>
where
    R: TryInto<libc::c_int>,
{
    // `libc::RLIMIT_*` are `__rlimit_resource_t` (u32) on Linux and
    // `c_int` on macOS; coerce to whatever `setrlimit` takes on this
    // platform. Failure here is a programming/libc-binding error: every
    // `RLIMIT_*` constant in use is a small non-negative integer that
    // fits in `c_int` on every Unix we ship for. Fail closed with an
    // explicit io::Error so the spawn aborts loudly rather than
    // silently dropping a cap if a future libc bump changes the type.
    let res: libc::c_int = resource.try_into().map_err(|_| {
        std::io::Error::new(
            std::io::ErrorKind::InvalidInput,
            "rlimit resource constant did not fit in c_int (libc-binding error)",
        )
    })?;
    // Read the inherited limits and only lower the *soft* cap; never
    // touch the hard cap. Two consequences:
    //   1. We can't EPERM on "raising hard limit" (Linux without
    //      CAP_SYS_RESOURCE).
    //   2. We can't EINVAL on "rlim_cur > rlim_max" (macOS, where
    //      setrlimit's accepted-value envelope is narrower than what
    //      getrlimit's report suggests -- e.g. RLIMIT_RSS/RLIMIT_AS is
    //      deprecated, RLIMIT_NPROC is bounded by `kern.maxprocperuid`,
    //      and RLIM_INFINITY operands round-trip differently).
    // Tradeoff: a child can `setrlimit` its own rlim_cur back up to the
    // inherited rlim_max. That is acceptable for this code's role: it
    // is a safety net against accidental runaways (fork bombs, `dd
    // if=/dev/zero`), not the primary security boundary -- the OS
    // sandbox (bwrap/Seatbelt) is. The parent-side
    // `clamp_to_parent_hard_limits` already warns the operator when a
    // requested cap can't be enforced past the host's own ceiling.
    let mut current = libc::rlimit {
        rlim_cur: 0,
        rlim_max: 0,
    };
    // SAFETY: getrlimit is async-signal-safe and only reads.
    if unsafe { libc::getrlimit(res as _, &mut current) } != 0 {
        return Err(std::io::Error::last_os_error());
    }
    let want = value as libc::rlim_t;
    let new_cur = if current.rlim_max == libc::RLIM_INFINITY {
        want
    } else if want == libc::RLIM_INFINITY {
        // "Unlimited" can't go above what we already inherited.
        current.rlim_max
    } else {
        std::cmp::min(want, current.rlim_max)
    };
    // No-op if the soft cap is already where we want it. macOS in
    // particular can EINVAL on identity calls where rlim_cur and
    // rlim_max are at their reported values, so we'd rather not poke
    // setrlimit unless we're actually changing something.
    if new_cur == current.rlim_cur {
        return Ok(());
    }
    let rlim = libc::rlimit {
        rlim_cur: new_cur,
        rlim_max: current.rlim_max,
    };
    // SAFETY: `setrlimit` is async-signal-safe and is called only from
    // `pre_exec`, which is the canonical place to apply per-child caps
    // on Unix. The pointer is to a stack-local that outlives the call.
    let ret = unsafe { libc::setrlimit(res as _, &rlim) };
    if ret == 0 {
        Ok(())
    } else {
        let err = std::io::Error::last_os_error();
        // We never raise rlim_max and never set rlim_cur > rlim_max, so
        // EPERM/EINVAL here means a libc/kernel divergence we have no
        // recourse for from inside pre_exec (no allocation, no
        // tracing). Swallow rather than aborting the spawn -- the
        // parent has already emitted any clamp warnings via
        // `tracing::warn!`, and the other rlimits in this batch may
        // still apply.
        match err.raw_os_error() {
            Some(libc::EPERM) | Some(libc::EINVAL) => Ok(()),
            _ => Err(err),
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
        .stderr(std::process::Stdio::piped())
        // If the wall-clock timeout fires below, dropping `cmd.output()`'s
        // future tears down the Child; without `kill_on_drop`, tokio leaves
        // a runaway/CPU-spinning child alive past timeout, holding the
        // rlimit budget and consuming CPU until reaped some other way.
        .kill_on_drop(true);
    for key in ENV_WHITELIST {
        if let Some(value) = std::env::var_os(key) {
            cmd.env(key, value);
        }
    }

    // Apply per-process rlimits via `pre_exec` so AS/NPROC/NOFILE/FSIZE/
    // CPU/CORE caps land on the child (and any sandbox wrapper, which
    // inherits them) without affecting the parent agent. Read env once
    // here and clamp to the parent's hard limits so the closure body
    // stays async-signal-safe and the child's setrlimit calls can't
    // EPERM on "asked for more than hard limit" -- a clamp-with-warning
    // in the parent (where `tracing` is safe) is strictly more visible
    // than a silent EPERM swallow inside pre_exec.
    #[cfg(unix)]
    {
        let rlimits = RlimitConfig::from_env().clamp_to_parent_hard_limits();
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
    use std::path::PathBuf;
    use tokio::sync::Mutex;

    /// Serializes tests that mutate process-wide env vars. `cargo test`
    /// runs `#[test]`/`#[tokio::test]` cases on parallel threads inside a
    /// single test binary, so env reads/writes against
    /// `BROKK_ACP_RLIMIT_*` must be funneled through this lock or one
    /// test's setup races against another's `from_env()`.
    ///
    /// `tokio::sync::Mutex` because the guard is held across `await`
    /// points (the test invokes `run_shell_command(...).await`); a
    /// `std::sync::Mutex` here would trigger `clippy::await_holding_lock`.
    static ENV_LOCK: Mutex<()> = Mutex::const_new(());

    /// Restores a single env var on Drop. Pair with `ENV_LOCK` so the
    /// drop order is well-defined.
    struct EnvGuard {
        var: &'static str,
    }

    impl EnvGuard {
        fn set(var: &'static str, value: &str) -> Self {
            // SAFETY: the caller holds ENV_LOCK, which serializes env
            // mutation across this crate's tests. Outside test code,
            // `std::env::set_var` is unsafe in Rust 2024 because it
            // races with concurrent reads from other threads.
            unsafe {
                std::env::set_var(var, value);
            }
            Self { var }
        }
    }

    impl Drop for EnvGuard {
        fn drop(&mut self) {
            // SAFETY: same as set() -- guarded by ENV_LOCK.
            unsafe {
                std::env::remove_var(self.var);
            }
        }
    }

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
    fn clamp_to_hard_limit_passes_through_when_request_below_hard() {
        // RLIMIT_NOFILE hard limit is at least 1024 on every realistic
        // host; 64 is far below and should pass through unchanged.
        assert_eq!(
            clamp_to_hard_limit(RLIMIT_NOFILE_ENV, libc::RLIMIT_NOFILE, 64),
            64
        );
    }

    #[test]
    fn clamp_to_hard_limit_caps_request_above_hard() {
        // Read the current hard NOFILE; ask for hard+1 and expect hard.
        // If the host has no hard cap (RLIM_INFINITY), there's nothing
        // to clamp against and the test is a no-op -- which is the
        // documented behavior of clamp_to_hard_limit.
        let mut current = libc::rlimit {
            rlim_cur: 0,
            rlim_max: 0,
        };
        // SAFETY: getrlimit is async-signal-safe and only reads.
        let ret = unsafe { libc::getrlimit(libc::RLIMIT_NOFILE as _, &mut current) };
        assert_eq!(ret, 0);
        // Same `as u64` rationale as in `clamp_to_hard_limit`: rlim_t is
        // u32 on 32-bit Linux and u64 elsewhere.
        #[allow(clippy::unnecessary_cast)]
        let hard = current.rlim_max as u64;
        if hard == libc::RLIM_INFINITY {
            return;
        }
        let clamped = clamp_to_hard_limit(
            RLIMIT_NOFILE_ENV,
            libc::RLIMIT_NOFILE,
            hard.saturating_add(1),
        );
        assert_eq!(clamped, hard);
        let clamped_inf =
            clamp_to_hard_limit(RLIMIT_NOFILE_ENV, libc::RLIMIT_NOFILE, libc::RLIM_INFINITY);
        assert_eq!(clamped_inf, hard);
    }

    /// End-to-end: a tight `BROKK_ACP_RLIMIT_FSIZE_BYTES` actually kills
    /// `dd` when it tries to write past the cap. This is the regression
    /// test the reviewer flagged as missing -- a future refactor that
    /// drops `cmd.pre_exec`, breaks the env-var read, or wires the cap
    /// to the wrong syscall would silently disable the sandbox feature
    /// without this assertion failing.
    #[tokio::test]
    async fn rlimit_fsize_actually_kills_oversized_writes() {
        let _guard = ENV_LOCK.lock().await;
        // 1 KiB cap; the dd block size below is 8 KiB so the very first
        // write exceeds the cap -> SIGXFSZ -> non-zero exit.
        let _env = EnvGuard::set(RLIMIT_FSIZE_ENV, "1024");

        let dir = std::env::temp_dir();
        let target: PathBuf = dir.join(format!("brokk-rlimit-fsize-{}", std::process::id()));
        let _ = std::fs::remove_file(&target);
        let cmd = format!(
            "dd if=/dev/zero of='{}' bs=8192 count=1 2>&1",
            target.display()
        );

        let result = run_shell_command(&dir, &cmd, 30, SandboxPolicy::None).await;
        let written = std::fs::metadata(&target).map(|m| m.len()).unwrap_or(0);
        let _ = std::fs::remove_file(&target);

        assert!(
            !matches!(result.status, ToolStatus::Success),
            "RLIMIT_FSIZE=1024 should have killed dd; got success with output: {}",
            result.output
        );
        assert!(
            written <= 1024,
            "RLIMIT_FSIZE=1024 should have stopped writes at 1 KiB but file grew to {} bytes",
            written
        );
    }

    /// End-to-end: lifting a cap via `unlimited` makes `dd` succeed at a
    /// size that the default (1 GiB) also permits. Pairs with the test
    /// above: together they show the env-var path actually reaches the
    /// child and the limit is what governs success/failure.
    #[tokio::test]
    async fn rlimit_fsize_unlimited_allows_writes() {
        let _guard = ENV_LOCK.lock().await;
        let _env = EnvGuard::set(RLIMIT_FSIZE_ENV, "unlimited");

        let dir = std::env::temp_dir();
        let target: PathBuf = dir.join(format!("brokk-rlimit-fsize-ok-{}", std::process::id()));
        let _ = std::fs::remove_file(&target);
        let cmd = format!(
            "dd if=/dev/zero of='{}' bs=8192 count=1 2>&1",
            target.display()
        );

        let result = run_shell_command(&dir, &cmd, 30, SandboxPolicy::None).await;
        let _ = std::fs::remove_file(&target);

        assert!(
            matches!(result.status, ToolStatus::Success),
            "unlimited FSIZE should allow an 8 KiB write; got non-success with output: {}",
            result.output
        );
    }
}
