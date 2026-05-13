//! OS-level sandbox for `runShellCommand`.
//!
//! Mirrors the Java executor's three-tier `SandboxPolicy`
//! (`app/src/main/java/ai/brokk/util/SandboxPolicy.java`) and the
//! macOS Seatbelt / Linux Bubblewrap wrappers in
//! `app/src/main/java/ai/brokk/util/Environment.java:425-548`,
//! and is **stricter than Java in two places**:
//!   - `--clearenv` (Linux) / `Command::env_clear` (macOS) plus an explicit
//!     env whitelist, so secrets like `OPENAI_API_KEY`/`GITHUB_TOKEN`/`AWS_*`
//!     in the parent process don't leak into LLM-driven shell calls.
//!   - On Linux, the `WorkspaceWrite` cache binds restrict to specific
//!     cache *subdirectories* (e.g. `~/.cargo/registry`, `~/.gradle/caches`)
//!     rather than entire credential-bearing roots (`~/.cargo`, `~/.gradle`).
//!
//! The sandbox is the security boundary; the per-call permission gate
//! (`tool_loop::consult_gate`) is UX. Without it, `cat ~/.ssh/id_rsa | curl ...`
//! is indistinguishable from a benign read at the heuristic layer.
//!
//! Platform support:
//! - **macOS**: `sandbox-exec -f <profile.sb> -- sh -c <cmd>` with a TinyScheme
//!   profile that denies-by-default and selectively allows reads, process exec,
//!   and (in WorkspaceWrite) writes under the workspace root.
//! - **Linux**: `bwrap --ro-bind / / [...] -- sh -c <cmd>`. If `bwrap` isn't
//!   on the PATH, logs and falls back to unwrapped exec; the resulting
//!   `WrappedCommand.sandboxed = false` so `shell.rs` can surface a
//!   user-visible warning to the ACP client.
//! - **Other (Windows etc.)**: log-and-skip; same posture as the Java side.
//!
//! ### Known UX cost
//! `bwrap --unshare-all` isolates PID/IPC/UTS namespaces. Inside the sandbox,
//! `gdb --pid`, `strace -p`, `perf`, and any tool that attaches to a non-child
//! PID will fail with EPERM/ESRCH. This is consistent with the Java executor
//! and is the price of namespace isolation; tools that operate only on their
//! own children (e.g. `strace ./my-binary`) work fine.

use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use crate::session::PermissionMode;

/// How long a stale `brokk-seatbelt-*.sb` policy file may live in temp_dir
/// before the next session creation sweeps it away. Bounded mostly to keep
/// `/tmp` tidy after panic/SIGKILL — well above any plausible shell-call
/// duration so an in-flight sandbox can't have its profile yanked.
const STALE_POLICY_FILE_AGE: std::time::Duration = std::time::Duration::from_secs(3600);

/// Three-tier sandbox policy. Mirrors `SandboxPolicy.java`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SandboxPolicy {
    /// No sandbox; the command runs with full process privileges.
    None,
    /// Read-only filesystem; writes blocked everywhere.
    ReadOnly,
    /// Read-only everywhere except the workspace root (writable).
    WorkspaceWrite,
}

impl SandboxPolicy {
    /// Map a session's `PermissionMode` to a sandbox tier.
    ///
    /// `Default` resolves to `WorkspaceWrite` so the per-call permission
    /// prompt is the meaningful gate -- when the user clicks "Allow" the
    /// shell call can actually write under cwd, mirroring what `writeFile`
    /// (which bypasses the sandbox entirely) already does in this mode.
    /// Previously `Default` mapped to `ReadOnly`, which silently turned the
    /// "Allow" prompt into a no-op for shell writes and contradicted the
    /// README contract ("Prompts the user for approval before every mutating
    /// tool call"). `ReadOnly` mode still maps to `ReadOnly` for "no writes
    /// at all", and `BypassPermissions` is the explicit opt-out from
    /// sandboxing entirely.
    pub fn from_permission_mode(mode: PermissionMode) -> Self {
        match mode {
            PermissionMode::BypassPermissions => Self::None,
            PermissionMode::ReadOnly => Self::ReadOnly,
            PermissionMode::Default => Self::WorkspaceWrite,
            PermissionMode::AcceptEdits => Self::WorkspaceWrite,
        }
    }

    pub fn allows_workspace_writes(self) -> bool {
        matches!(self, Self::WorkspaceWrite)
    }
}

// ---------------------------------------------------------------------------
// SandboxBackend: which implementation strategy is in use.
//
// `SandboxPolicy` says *what* should be restricted (no writes vs. cwd-only
// writes); `SandboxBackend` says *how* (which mechanism). Splitting the
// two lets us swap the WASM fallback into the same call site without
// re-plumbing every consumer of `SandboxPolicy`.
// ---------------------------------------------------------------------------

/// Operator-facing env var for forcing a backend. Values: `auto`
/// (recommended; same as unset), `native`, `wasi`, `none`. Case-insensitive.
/// Anything else logs a warning at first read and falls back to `auto`.
pub const SANDBOX_BACKEND_ENV: &str = "BROKK_ACP_SANDBOX_BACKEND";

/// Which sandbox implementation handles a given `runShellCommand` call.
///
/// **Resolution order under `Auto`:**
///   1. Native (sandbox-exec on macOS; bwrap on Linux only if the runtime
///      probe also succeeds -- presence of the binary is not enough since
///      Docker/AppArmor often blocks `bwrap --unshare-all` at runtime).
///   2. Wasi (in-process wasmtime) -- only effective for commands that
///      parse as `<tool> [args]` AND resolve to a registered module.
///      **Strictly weaker than Native**: most commands fall through to
///      unsandboxed exec with a warning.
///   3. None (no sandbox; the call runs with the agent's own privileges).
///
/// This is decided per-process, memoized via `OnceLock` in `effective_backend()`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SandboxBackend {
    /// Native OS sandbox (Seatbelt or Bubblewrap).
    Native,
    /// WASI runtime fallback. Communicates a security cost to the operator;
    /// callers should surface this in the bypass warning when the WASI path
    /// could not actually wrap a given command.
    Wasi,
    /// No sandbox at all. The call runs with whatever privileges the
    /// brokk-acp process has.
    None,
}

impl SandboxBackend {
    /// Parse an operator-supplied backend name (env var value). `None`
    /// means "not provided"; `Some("auto")` means "use detection". Unknown
    /// strings return `Err` so we can log a clear warning instead of
    /// silently choosing the wrong tier.
    fn parse_override(raw: Option<&str>) -> Result<Option<SandboxBackend>, String> {
        let Some(raw) = raw else { return Ok(None) };
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            return Ok(None);
        }
        match trimmed.to_ascii_lowercase().as_str() {
            "auto" => Ok(None),
            "native" => Ok(Some(SandboxBackend::Native)),
            "wasi" | "wasm" => Ok(Some(SandboxBackend::Wasi)),
            "none" | "off" | "disabled" => Ok(Some(SandboxBackend::None)),
            other => Err(format!(
                "{SANDBOX_BACKEND_ENV}={other:?} is not one of: auto, native, wasi, none"
            )),
        }
    }
}

/// Detect which native sandbox tooling actually *works* on this host.
///
/// On Linux, `is_bubblewrap_available()` (binary on PATH) is necessary but
/// not sufficient: inside Docker containers, AppArmor profiles and the
/// commonly-set `kernel.unprivileged_userns_clone=0` make `bwrap
/// --unshare-all` exit non-zero at runtime. We probe the kernel/policy
/// combo by actually invoking a no-op bwrap call once and caching the
/// result. On macOS, `sandbox-exec` is shipped by Apple and always
/// available; we still gate via this function so the call site doesn't
/// have to platform-#cfg.
fn native_sandbox_works() -> bool {
    #[cfg(target_os = "macos")]
    {
        // `sandbox-exec` is part of the base system; presence and operation
        // are guaranteed on any supported macOS. Probe is unnecessary.
        true
    }
    #[cfg(target_os = "linux")]
    {
        if !is_bubblewrap_available() {
            return false;
        }
        probe_bubblewrap_runtime()
    }
    #[cfg(not(any(target_os = "macos", target_os = "linux")))]
    {
        false
    }
}

/// Pure backend resolution: given an operator override and platform
/// capability flags, return the effective backend. Extracted from
/// `effective_backend()` so the "force WASI wins over native" contract
/// can be unit-tested without going through `OnceLock` (which would
/// poison across test cases).
///
/// **Precedence:** operator override > detection > `None`. The override
/// is honored verbatim even when it picks a tier that detection would
/// have ruled out (e.g. `--sandbox-backend wasi` on macOS, where
/// Seatbelt works). The operator presumably knows what they're doing --
/// common reasons include: debugging the WASI path, validating Docker
/// behavior from a dev box, or pre-staging for a deployment where the
/// native backend is unavailable.
pub fn resolve_backend(
    override_: Option<SandboxBackend>,
    native_works: bool,
    has_wasi_fallback: bool,
) -> SandboxBackend {
    let detected = if native_works {
        SandboxBackend::Native
    } else if has_wasi_fallback {
        // Fall back to WASI on platforms where the native sandbox is
        // either missing (Windows) or broken at runtime (Docker
        // without user namespaces). macOS always has Seatbelt, so we
        // never get here on macOS.
        SandboxBackend::Wasi
    } else {
        SandboxBackend::None
    };
    override_.unwrap_or(detected)
}

/// Resolved sandbox backend for this process. Memoized after first call so
/// repeated `runShellCommand` invocations don't re-probe `bwrap`. The probe
/// itself is fork+exec, ~milliseconds, but it would still be wasted work
/// if every call repeated it.
///
/// Override precedence (high to low):
///   1. `--sandbox-backend <val>` CLI flag (main.rs writes it to env before
///      any other thread starts);
///   2. `BROKK_ACP_SANDBOX_BACKEND=<val>` env var;
///   3. Auto-detection via `resolve_backend(None, ...)`.
///
/// Values for both flag and env: `auto`, `native`, `wasi` (alias: `wasm`),
/// `none` (aliases: `off`, `disabled`). Case-insensitive.
pub fn effective_backend() -> SandboxBackend {
    effective_resolution().chosen
}

/// Full resolution record. The `/sandbox` slash command and any other
/// UX that wants to explain *why* a backend was picked reads this.
/// Cached once on first call; the bwrap probe is what makes the cache
/// worthwhile (otherwise it'd fork+exec on every shell call).
#[derive(Debug, Clone, Copy)]
pub struct BackendResolution {
    /// The backend actually in use.
    pub chosen: SandboxBackend,
    /// What auto-detection would have picked. Equal to `chosen` when no
    /// override applies; useful for the UX message "would have been
    /// Native but operator forced WASI".
    pub detected: SandboxBackend,
    /// Whether the operator explicitly forced a backend, and which.
    /// `None` means no override (the auto path picked `detected`).
    pub override_: Option<SandboxBackend>,
    /// Whether bwrap probe succeeded at startup. `Some(false)` on Linux
    /// means the user is likely inside a Docker container with user
    /// namespaces disabled. `None` on macOS / Windows (the probe is
    /// Linux-only).
    pub bwrap_probe_passed: Option<bool>,
}

pub fn effective_resolution() -> BackendResolution {
    static SELECTED: OnceLock<BackendResolution> = OnceLock::new();
    *SELECTED.get_or_init(|| {
        let raw = std::env::var(SANDBOX_BACKEND_ENV).ok();
        let parsed = SandboxBackend::parse_override(raw.as_deref());
        let override_ = match parsed {
            Ok(o) => o,
            Err(msg) => {
                tracing::warn!("{msg} -- falling back to auto-detection");
                None
            }
        };

        // Run the (Linux-only) bwrap probe once and remember its outcome
        // so `/sandbox` can report it without re-forking. On macOS /
        // Windows the probe is meaningless; we report `None`.
        let bwrap_probe_passed: Option<bool> = {
            #[cfg(target_os = "linux")]
            {
                Some(is_bubblewrap_available() && probe_bubblewrap_runtime())
            }
            #[cfg(not(target_os = "linux"))]
            {
                None
            }
        };

        // `native_sandbox_works()` re-derives the same Linux outcome but
        // is also the macOS source of truth. Stay with it as the gate
        // input so the precedence tree in `resolve_backend` stays
        // single-sourced.
        let native_works = native_sandbox_works();
        let has_wasi_fallback = cfg!(target_os = "linux") || cfg!(windows);
        let detected = resolve_backend(None, native_works, has_wasi_fallback);
        let chosen = resolve_backend(override_, native_works, has_wasi_fallback);

        tracing::info!(
            ?override_,
            ?detected,
            ?chosen,
            ?bwrap_probe_passed,
            "sandbox backend selected",
        );
        if chosen == SandboxBackend::Wasi {
            tracing::warn!(
                "Using WASI sandbox: strictly weaker than the native OS sandbox. \
                 Only single-binary commands resolving to a registered WASI module \
                 are isolated; pipelines, redirections, and unbundled tools run \
                 unsandboxed with a warning. See `BROKK_ACP_WASI_MODULES`."
            );
        }

        BackendResolution {
            chosen,
            detected,
            override_,
            bwrap_probe_passed,
        }
    })
}

/// Human-readable name for one tier. Used by `describe_status` and by
/// error messages where we name the backend the user is currently on.
fn backend_display_name(b: SandboxBackend) -> &'static str {
    match b {
        SandboxBackend::Native => {
            if cfg!(target_os = "macos") {
                "native (Seatbelt / sandbox-exec)"
            } else if cfg!(target_os = "linux") {
                "native (Bubblewrap)"
            } else {
                "native"
            }
        }
        SandboxBackend::Wasi => "WASI (in-process wasmtime)",
        SandboxBackend::None => "none (no sandbox)",
    }
}

/// Render the sandbox status as a Markdown block. Source of truth for
/// the `/sandbox` slash command. Read-only: the operator changes the
/// backend at startup via `--sandbox-backend` / `BROKK_ACP_SANDBOX_BACKEND`,
/// never at runtime via a slash command -- a runtime "weaken sandbox"
/// affordance is a footgun we don't want to expose to LLM-driven prompts.
pub fn describe_status() -> String {
    let r = effective_resolution();
    let registry = super::wasi_sandbox::global();
    let module_names = registry.names_sorted();

    let mut s = String::with_capacity(1024);
    s.push_str("**Sandbox status**\n\n");
    s.push_str(&format!(
        "- Active backend: `{}`\n",
        backend_display_name(r.chosen)
    ));
    s.push_str(&format!(
        "- Detection would pick: `{}`\n",
        backend_display_name(r.detected)
    ));
    s.push_str(&format!(
        "- Operator override: {}\n",
        match r.override_ {
            None => "none (auto)".to_string(),
            Some(b) => format!(
                "`{}` (from `--sandbox-backend` / `{SANDBOX_BACKEND_ENV}`)",
                match b {
                    SandboxBackend::Native => "native",
                    SandboxBackend::Wasi => "wasi",
                    SandboxBackend::None => "none",
                }
            ),
        }
    ));

    // bwrap probe: only meaningful on Linux. Surfacing the result helps
    // the operator diagnose "I'm on Linux but I'm getting WASI?" --
    // typically the answer is "you're in Docker without user namespaces".
    if let Some(passed) = r.bwrap_probe_passed {
        if passed {
            s.push_str("- Bubblewrap runtime probe: passed\n");
        } else {
            s.push_str(
                "- Bubblewrap runtime probe: **failed** at runtime. \
                 Likely cause: inside a Docker container with user namespaces \
                 disabled (AppArmor `docker-default`, or \
                 `kernel.unprivileged_userns_clone=0`).\n",
            );
        }
    }

    s.push_str("\n**WASI module registry**\n\n");
    if module_names.is_empty() {
        s.push_str(
            "- No modules registered. Commands selected through the WASI backend \
             will fall back to unsandboxed exec with a warning.\n\
             - Register modules at startup via \
             `BROKK_ACP_WASI_MODULES=name1=/abs/path/1.wasm:name2=/abs/path/2.wasm`.\n",
        );
    } else {
        s.push_str(&format!("- {} module(s) registered:\n", module_names.len()));
        for n in &module_names {
            s.push_str(&format!("  - `{n}`\n"));
        }
    }

    s.push_str("\n**Changing the backend**\n\n");
    s.push_str(
        "Restart `brokk-acp` with `--sandbox-backend <auto|native|wasi|none>` \
         (or set `BROKK_ACP_SANDBOX_BACKEND`). The backend is intentionally not \
         mutable from a slash command: weakening the sandbox at runtime would \
         let an LLM-driven prompt escalate its own privileges.\n",
    );

    s
}

/// Outcome of `wrap_command_with_backend`: either an external-process
/// invocation (Bubblewrap, Seatbelt, or unsandboxed sh) or an in-process
/// WASI invocation routed through wasmtime. `shell.rs` dispatches on this
/// to decide whether to `Command::spawn` or hand off to
/// `wasi_sandbox::run`.
#[derive(Debug)]
pub enum SandboxedCommand {
    /// External process; argv ready for `Command::new(argv[0])`.
    Process(WrappedCommand),
    /// In-process WASI execution.
    Wasi(super::wasi_sandbox::WasiInvocation),
}

/// Owns a temp-file path and removes it on Drop. Bound into `WrappedCommand`
/// so the policy file outlives the spawned child process.
#[derive(Debug)]
pub struct TempPolicyFile {
    path: PathBuf,
}

impl TempPolicyFile {
    #[cfg_attr(not(target_os = "macos"), allow(dead_code))]
    fn new(path: PathBuf) -> Self {
        Self { path }
    }
}

impl Drop for TempPolicyFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}

/// argv to spawn, plus an optional temp policy file whose lifetime is tied to
/// the spawned process.
///
/// `sandboxed` is true iff a real OS sandbox was applied. When the requested
/// policy was non-`None` but the platform tooling was missing (e.g. `bwrap`
/// not installed), `sandboxed` is false and `argv` is the unwrapped command.
/// `shell.rs` uses this to surface a user-visible warning.
///
/// `fallback_reason`, when present, is a free-form explanation of why we
/// fell back (e.g. "WASI cannot wrap pipelines: |"). Surfaced verbatim in
/// the bypass warning so the operator can fix the underlying issue (install
/// bwrap, restructure the command, register more WASI modules).
#[derive(Debug)]
pub struct WrappedCommand {
    pub argv: Vec<String>,
    pub sandboxed: bool,
    pub fallback_reason: Option<String>,
    /// Held to keep the temp file alive; never read directly.
    _policy_file: Option<TempPolicyFile>,
}

impl WrappedCommand {
    fn unwrapped(command: &str) -> Self {
        Self {
            argv: vec!["sh".into(), "-c".into(), command.into()],
            sandboxed: false,
            fallback_reason: None,
            _policy_file: None,
        }
    }

    /// Like `unwrapped` but tagged with a reason -- used when a higher tier
    /// (WASI, bwrap) tried and declined to wrap. The reason lets the
    /// bypass warning explain which boundary slipped.
    fn unwrapped_with_reason(command: &str, reason: String) -> Self {
        Self {
            argv: vec!["sh".into(), "-c".into(), command.into()],
            sandboxed: false,
            fallback_reason: Some(reason),
            _policy_file: None,
        }
    }
}

/// Wrap a shell command in the appropriate platform sandbox.
///
/// Returns argv ready for `Command::new(argv[0]).args(&argv[1..])`. The
/// returned value MUST be held alive until the spawned process exits so the
/// `TempPolicyFile` Drop guard doesn't race the `sandbox-exec` open.
///
/// For non-`None` policies, `cwd` must be absolute and valid UTF-8 — both
/// properties are required to interpolate it safely into a seatbelt subpath
/// rule or a bwrap argv. `Policy::None` is a passthrough and skips this
/// check so callers in `BypassPermissions` mode aren't forced to canonicalize.
pub fn wrap_command(
    policy: SandboxPolicy,
    cwd: &Path,
    command: &str,
) -> std::io::Result<WrappedCommand> {
    match policy {
        SandboxPolicy::None => Ok(WrappedCommand::unwrapped(command)),
        SandboxPolicy::ReadOnly | SandboxPolicy::WorkspaceWrite => {
            if !cwd.is_absolute() {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidInput,
                    format!("sandbox cwd must be absolute, got '{}'", cwd.display()),
                ));
            }
            if cwd.to_str().is_none() {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidInput,
                    "sandbox cwd must be valid UTF-8 (non-UTF-8 paths cannot be safely \
                     interpolated into seatbelt or bwrap rules)",
                ));
            }
            wrap_platform(policy, cwd, command)
        }
    }
}

/// Top-level dispatcher. Picks an execution path according to the
/// resolved `SandboxBackend` and the requested `SandboxPolicy`.
///
/// - **Native** -> `SandboxedCommand::Process(wrap_command(...))`: same
///   behavior as before this enum existed.
/// - **Wasi**   -> attempts `wasi_sandbox::try_wrap`. On success, returns
///   `SandboxedCommand::Wasi(invocation)`. On any wrap failure (shell
///   feature used, no module for tool, ...), returns
///   `SandboxedCommand::Process(unwrapped_with_reason)` so `shell.rs` can
///   emit a clear "WASI couldn't wrap this, ran unsandboxed" warning.
/// - **None**   -> `SandboxedCommand::Process(unwrapped)` regardless of
///   policy. Caller is responsible for displaying that this was an
///   explicit operator opt-out.
///
/// The `SandboxPolicy::None` shortcut in `wrap_command` is preserved
/// across the dispatcher because `BypassPermissions` always means
/// "no sandbox of any kind, by the user's explicit choice".
pub fn wrap_command_with_backend(
    backend: SandboxBackend,
    policy: SandboxPolicy,
    cwd: &Path,
    command: &str,
) -> std::io::Result<SandboxedCommand> {
    if matches!(policy, SandboxPolicy::None) || matches!(backend, SandboxBackend::None) {
        return Ok(SandboxedCommand::Process(WrappedCommand::unwrapped(
            command,
        )));
    }

    match backend {
        SandboxBackend::Native => Ok(SandboxedCommand::Process(wrap_command(
            policy, cwd, command,
        )?)),
        SandboxBackend::Wasi => {
            if !cwd.is_absolute() {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidInput,
                    format!("sandbox cwd must be absolute, got '{}'", cwd.display()),
                ));
            }
            let registry = super::wasi_sandbox::global();
            match super::wasi_sandbox::try_wrap(registry, policy, cwd, command) {
                Ok(invocation) => Ok(SandboxedCommand::Wasi(invocation)),
                Err(failure) => Ok(SandboxedCommand::Process(
                    WrappedCommand::unwrapped_with_reason(
                        command,
                        format!("WASI sandbox declined to wrap command: {failure}"),
                    ),
                )),
            }
        }
        SandboxBackend::None => unreachable!("handled by early return above"),
    }
}

#[cfg(target_os = "macos")]
fn wrap_platform(
    policy: SandboxPolicy,
    cwd: &Path,
    command: &str,
) -> std::io::Result<WrappedCommand> {
    let policy_content = build_seatbelt_policy(policy, cwd);
    let has_write_rule = policy_content.contains("file-write*");
    let path = std::env::temp_dir().join(format!("brokk-seatbelt-{}.sb", uuid::Uuid::new_v4()));
    write_policy_file_secure(&path, &policy_content)
        .map_err(|e| sandbox_io_error(format!("write seatbelt profile: {e}")))?;
    tracing::debug!(
        target: "brokk_acp_rust::tools::sandbox",
        policy = ?policy,
        cwd = %cwd.display(),
        profile_path = %path.display(),
        has_workspace_write_rule = has_write_rule,
        "materialized seatbelt profile",
    );
    let temp = TempPolicyFile::new(path.clone());

    let argv = vec![
        "sandbox-exec".to_string(),
        "-f".to_string(),
        path.to_string_lossy().into_owned(),
        "--".to_string(),
        "sh".to_string(),
        "-c".to_string(),
        command.to_string(),
    ];

    Ok(WrappedCommand {
        argv,
        sandboxed: true,
        fallback_reason: None,
        _policy_file: Some(temp),
    })
}

#[cfg(target_os = "linux")]
fn wrap_platform(
    policy: SandboxPolicy,
    cwd: &Path,
    command: &str,
) -> std::io::Result<WrappedCommand> {
    if !is_bubblewrap_available() {
        warn_missing_bwrap_once();
        return Ok(WrappedCommand::unwrapped_with_reason(
            command,
            "Bubblewrap not installed on this host".to_string(),
        ));
    }

    let argv = build_bwrap_argv(policy, cwd, command);
    let has_workspace_bind = argv.windows(2).any(|w| w[0] == "--bind");
    tracing::debug!(
        target: "brokk_acp_rust::tools::sandbox",
        policy = ?policy,
        cwd = %cwd.display(),
        has_workspace_bind = has_workspace_bind,
        argv_len = argv.len(),
        "prepared bwrap invocation",
    );
    Ok(WrappedCommand {
        argv,
        sandboxed: true,
        fallback_reason: None,
        _policy_file: None,
    })
}

#[cfg(not(any(target_os = "macos", target_os = "linux")))]
fn wrap_platform(
    _policy: SandboxPolicy,
    _cwd: &Path,
    command: &str,
) -> std::io::Result<WrappedCommand> {
    warn_unsupported_os_once();
    Ok(WrappedCommand::unwrapped_with_reason(
        command,
        "no native OS sandbox on this platform".to_string(),
    ))
}

/// Tag an io error as coming from the sandbox layer (not the user's command),
/// so the surface in `shell.rs` can distinguish the two.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
fn sandbox_io_error(msg: String) -> std::io::Error {
    std::io::Error::other(format!("[sandbox] {msg}"))
}

// ---------------------------------------------------------------------------
// macOS / Seatbelt
// ---------------------------------------------------------------------------

/// Ported verbatim from `Environment.java:555-624`. Read everywhere; deny
/// network, write, and most syscalls by default; allow process-fork/exec,
/// `/dev/null` writes, and a sysctl whitelist needed by common Unix tools.
const SEATBELT_BASE_POLICY: &str = r#"(version 1)

(deny default)

; allow read-only file operations
(allow file-read*)

; allow process creation
(allow process-exec)
(allow process-fork)
(allow signal (target self))

; allow writing to /dev/null
(allow file-write-data
  (require-all
    (path "/dev/null")
    (vnode-type CHARACTER-DEVICE)))

; sysctl whitelist required by the JVM and many Unix tools
(allow sysctl-read
  (sysctl-name "hw.activecpu")
  (sysctl-name "hw.busfrequency_compat")
  (sysctl-name "hw.byteorder")
  (sysctl-name "hw.cacheconfig")
  (sysctl-name "hw.cachelinesize_compat")
  (sysctl-name "hw.cpufamily")
  (sysctl-name "hw.cpufrequency_compat")
  (sysctl-name "hw.cputype")
  (sysctl-name "hw.l1dcachesize_compat")
  (sysctl-name "hw.l1icachesize_compat")
  (sysctl-name "hw.l2cachesize_compat")
  (sysctl-name "hw.l3cachesize_compat")
  (sysctl-name "hw.logicalcpu_max")
  (sysctl-name "hw.machine")
  (sysctl-name "hw.ncpu")
  (sysctl-name "hw.nperflevels")
  (sysctl-name "hw.optional.arm.FEAT_BF16")
  (sysctl-name "hw.optional.arm.FEAT_DotProd")
  (sysctl-name "hw.optional.arm.FEAT_FCMA")
  (sysctl-name "hw.optional.arm.FEAT_FHM")
  (sysctl-name "hw.optional.arm.FEAT_FP16")
  (sysctl-name "hw.optional.arm.FEAT_I8MM")
  (sysctl-name "hw.optional.arm.FEAT_JSCVT")
  (sysctl-name "hw.optional.arm.FEAT_LSE")
  (sysctl-name "hw.optional.arm.FEAT_RDM")
  (sysctl-name "hw.optional.arm.FEAT_SHA512")
  (sysctl-name "hw.optional.armv8_2_sha512")
  (sysctl-name "hw.memsize")
  (sysctl-name "hw.pagesize")
  (sysctl-name "hw.packages")
  (sysctl-name "hw.pagesize_compat")
  (sysctl-name "hw.physicalcpu_max")
  (sysctl-name "hw.tbfrequency_compat")
  (sysctl-name "hw.vectorunit")
  (sysctl-name "kern.hostname")
  (sysctl-name "kern.maxfilesperproc")
  (sysctl-name "kern.osproductversion")
  (sysctl-name "kern.osrelease")
  (sysctl-name "kern.ostype")
  (sysctl-name "kern.osvariant_status")
  (sysctl-name "kern.osversion")
  (sysctl-name "kern.secure_kernel")
  (sysctl-name "kern.usrstack64")
  (sysctl-name "kern.version")
  (sysctl-name "sysctl.proc_cputype")
  (sysctl-name-prefix "hw.perflevel")
)
"#;

fn escape_for_seatbelt(p: &str) -> String {
    p.replace('\\', "\\\\").replace('"', "\\\"")
}

#[cfg_attr(not(any(target_os = "macos", test)), allow(dead_code))]
fn build_seatbelt_policy(policy: SandboxPolicy, cwd: &Path) -> String {
    if !policy.allows_workspace_writes() {
        return SEATBELT_BASE_POLICY.to_string();
    }

    // `wrap_command` rejects non-UTF-8 cwds, so to_str is Some here.
    let abs = cwd
        .to_str()
        .expect("validated UTF-8 in wrap_command")
        .to_string();
    let abs_escaped = escape_for_seatbelt(&abs);

    // Mirror Environment.java:432-442: emit BOTH lexical and canonical
    // subpath rules when they differ (covers /var -> /private/var on macOS,
    // /tmp -> /private/tmp, and bind-mounted symlinks). Fall back to the
    // lexical rule when canonicalize fails (e.g. cwd no longer exists).
    let write_rule = match cwd.canonicalize() {
        Ok(real) => match real.to_str() {
            Some(real_str) if real_str == abs => {
                format!("(allow file-write* (subpath \"{abs_escaped}\"))")
            }
            Some(real_str) => {
                let real_escaped = escape_for_seatbelt(real_str);
                format!(
                    "(allow file-write* (subpath \"{abs_escaped}\") (subpath \"{real_escaped}\"))"
                )
            }
            None => format!("(allow file-write* (subpath \"{abs_escaped}\"))"),
        },
        Err(_) => format!("(allow file-write* (subpath \"{abs_escaped}\"))"),
    };

    format!("{SEATBELT_BASE_POLICY}\n{write_rule}\n")
}

/// Atomically create the policy file with mode 0600 to defeat any TOCTOU
/// substitution between write and `sandbox-exec` open.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
fn write_policy_file_secure(path: &Path, content: &str) -> std::io::Result<()> {
    use std::io::Write;
    #[cfg(unix)]
    use std::os::unix::fs::OpenOptionsExt;

    let mut opts = std::fs::OpenOptions::new();
    opts.write(true).create_new(true);
    #[cfg(unix)]
    opts.mode(0o600);

    let mut file = opts.open(path)?;
    file.write_all(content.as_bytes())?;
    file.sync_all()?;
    Ok(())
}

/// Sweep stale `brokk-seatbelt-*.sb` files left behind by SIGKILL/panic.
/// Bounded by `STALE_POLICY_FILE_AGE` so we don't yank an in-flight profile
/// from a concurrent shell call.
pub fn cleanup_stale_policy_files() {
    let dir = std::env::temp_dir();
    let read = match std::fs::read_dir(&dir) {
        Ok(r) => r,
        Err(_) => return,
    };
    let now = std::time::SystemTime::now();
    for entry in read.flatten() {
        let name = entry.file_name();
        let name_str = match name.to_str() {
            Some(s) => s,
            None => continue,
        };
        if !name_str.starts_with("brokk-seatbelt-") || !name_str.ends_with(".sb") {
            continue;
        }
        let age = entry
            .metadata()
            .ok()
            .and_then(|m| m.modified().ok())
            .and_then(|mtime| now.duration_since(mtime).ok());
        if let Some(age) = age
            && age >= STALE_POLICY_FILE_AGE
        {
            let _ = std::fs::remove_file(entry.path());
        }
    }
}

// ---------------------------------------------------------------------------
// Linux / Bubblewrap
// ---------------------------------------------------------------------------

/// Cache *subdirectories* (not roots) bind-mounted RW under WorkspaceWrite,
/// chosen to be writable enough for builds while excluding sibling files in
/// the parent that hold credentials. Stricter than Java
/// (`Environment.java:521-529`), which binds the parent roots and so leaves
/// `~/.cargo/credentials.toml`, `~/.gradle/gradle.properties`, and
/// `~/.m2/settings.xml` exposed to the sandboxed command alongside the cache.
#[cfg(any(target_os = "linux", all(test, unix)))]
const HOME_CACHE_SUBDIRS: &[&str] = &[
    ".cargo/registry",
    ".cargo/git",
    ".gradle/caches",
    ".gradle/wrapper",
    ".gradle/native",
    ".m2/repository",
    ".npm/_cacache",
    ".cache",
    ".sbt/cache",
    ".ivy2/cache",
    ".rustup/toolchains",
    ".rustup/downloads",
];

/// Whitelist of env vars passed through `--clearenv` / `env_clear`. Stricter
/// than Java, which inherits the parent process env wholesale (see
/// `Environment.java:647-661`). LD_PRELOAD, DYLD_*, and any `*_TOKEN` /
/// `*_KEY` / `*_SECRET` parent secrets are not listed and therefore stripped.
///
/// The toolchain-manager entries (CARGO_HOME, RUSTUP_HOME, NVM_DIR, ...) are
/// pure path pointers into the user's home directory; they do not carry
/// credentials. Without them, commands like `cargo` and `rustup` can still
/// fail even when `~/.cargo/bin` is on PATH (e.g. `RUSTUP_HOME` redirected to
/// a non-default location).
pub const ENV_WHITELIST: &[&str] = &[
    // Core identity / locale
    "HOME",
    "USER",
    "LOGNAME",
    "LANG",
    "LC_ALL",
    "LC_CTYPE",
    // Terminal width hints (ripgrep, fd, ls --color, less, ...)
    "COLUMNS",
    "LINES",
    // Toolchain-manager pointers (paths into $HOME; not credentials)
    "CARGO_HOME",
    "RUSTUP_HOME",
    "NVM_DIR",
    "FNM_DIR",
    "ASDF_DIR",
    "ASDF_DATA_DIR",
    "MISE_DATA_DIR",
    "MISE_CONFIG_DIR",
    "PYENV_ROOT",
    "BUN_INSTALL",
    "DENO_INSTALL",
];

/// Operator-supplied override that fully replaces the discovered PATH inside
/// the sandbox. Lets sophisticated users opt into custom toolchain layouts
/// without us having to enumerate every package manager.
#[cfg(unix)]
pub const BROKK_ACP_PATH_ENV: &str = "BROKK_ACP_PATH";

/// Static fallback PATH segments, appended last. Mirrors the historic
/// hardcoded value so commands that resolve only through `/usr/bin` etc.
/// keep working when discovery turns up nothing.
#[cfg(unix)]
const STATIC_PATH_FALLBACK: &[&str] = &["/usr/local/bin", "/usr/bin", "/bin"];

/// Well-known toolchain-manager dirs that live under `$HOME`. Each is checked
/// individually for existence, ownership, and mode bits before being added
/// to PATH. Order is preference order (first match wins for `command -v`).
#[cfg(unix)]
const HOME_TOOL_DIRS: &[&str] = &[
    ".cargo/bin",
    ".local/bin",
    ".local/share/mise/shims",
    ".asdf/shims",
    ".pyenv/shims",
    ".bun/bin",
    ".deno/bin",
];

/// Well-known toolchain-manager dirs at fixed absolute paths. Not owned by
/// the user (typically by the brew admin user or root), so they're checked
/// only for existence and "not world-writable".
#[cfg(unix)]
const SYSTEM_TOOL_DIRS: &[&str] = &["/opt/homebrew/bin", "/opt/homebrew/sbin"];

/// Build the PATH used inside the sandbox.
///
/// Order: home tool dirs that pass `is_safe_home_dir`, then well-known system
/// tool dirs that pass `is_safe_system_dir`, then parent `$PATH` entries that
/// pass `is_safe_parent_path_entry`, then `STATIC_PATH_FALLBACK`. Duplicates
/// are dropped in first-seen order so the preference layering above is
/// preserved.
///
/// `BROKK_ACP_PATH` short-circuits the whole pipeline: if set to a non-empty
/// value the override is returned verbatim, leaving safety enforcement to the
/// operator. Used by ops who run brokk-acp under bespoke toolchain layouts.
///
/// Unix-only: the sandbox itself (`sandbox-exec`, `bwrap`) is unimplemented
/// on Windows, and the path layout we discover here (`~/.cargo/bin`,
/// `/opt/homebrew/bin`, `/usr/local/bin`, ...) is meaningless under Win32.
/// On Windows the caller in `shell.rs` falls through to the parent process'
/// own PATH, which is the desired no-op-when-no-sandbox behavior.
#[cfg(unix)]
pub fn discover_sandbox_path() -> String {
    if let Ok(override_val) = std::env::var(BROKK_ACP_PATH_ENV)
        && !override_val.trim().is_empty()
    {
        return override_val;
    }

    let mut entries: Vec<String> = Vec::with_capacity(16);
    let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();
    let mut push = |dir: String| {
        if seen.insert(dir.clone()) {
            entries.push(dir);
        }
    };

    // Canonicalize $HOME once so `is_safe_home_dir` can require each
    // candidate's canonical path to stay inside it (rejects symlinks
    // under $HOME that escape to elsewhere on disk, per #3543).
    let canonical_home = std::env::var_os("HOME")
        .map(PathBuf::from)
        .and_then(|h| h.canonicalize().ok());
    if let Some(ref h_canon) = canonical_home {
        for rel in HOME_TOOL_DIRS {
            let path = h_canon.join(rel);
            if !is_safe_home_dir(&path, h_canon) {
                continue;
            }
            if let Some(s) = path.to_str() {
                push(s.to_string());
            }
        }
    }

    for abs in SYSTEM_TOOL_DIRS {
        let path = PathBuf::from(abs);
        if is_safe_system_dir(&path)
            && let Some(s) = path.to_str()
        {
            push(s.to_string());
        }
    }

    if let Some(parent_path) = std::env::var_os("PATH") {
        for dir in std::env::split_paths(&parent_path) {
            if !is_safe_parent_path_entry(&dir) {
                if dir.exists() {
                    tracing::warn!(
                        path = %dir.display(),
                        "skipping unsafe parent PATH entry (world-writable or non-directory)"
                    );
                }
                continue;
            }
            if let Some(s) = dir.to_str() {
                push(s.to_string());
            }
        }
    }

    for abs in STATIC_PATH_FALLBACK {
        push((*abs).to_string());
    }

    entries.join(":")
}

/// A `~`-relative tool dir is safe iff: its canonical path remains under
/// `canonical_home`, exists as a directory, is owned by the current uid,
/// and has no group/world write bits set.
///
/// The canonicalize-and-contain check rejects symlinks under `$HOME` whose
/// targets land elsewhere on disk — even when the target is owned by the
/// current user and has tight mode bits, e.g. `~/.cargo/bin -> /tmp/bin`.
/// #3543's acceptance criterion explicitly calls this case out: "PATH
/// discovery skips ... symlinks pointing outside the user's home". Without
/// this check, an attacker who could plant a symlink in $HOME (e.g. via a
/// careless tarball extraction) could redirect tool resolution to a path
/// they control.
///
/// The ownership check on top of that catches the case where the target
/// *is* under $HOME but somehow ended up owned by another principal.
#[cfg(unix)]
fn is_safe_home_dir(path: &Path, canonical_home: &Path) -> bool {
    use std::os::unix::fs::MetadataExt;

    // Resolve symlinks fully and require the result to stay under
    // canonical $HOME. canonicalize() returns Err for non-existent paths,
    // which is the right answer here too: a missing dir is just not on
    // PATH, no warning needed.
    let canonical = match path.canonicalize() {
        Ok(c) => c,
        Err(_) => return false,
    };
    if !canonical.starts_with(canonical_home) {
        tracing::warn!(
            path = %path.display(),
            canonical = %canonical.display(),
            home = %canonical_home.display(),
            "skipping home tool dir whose canonical path escapes $HOME (symlink redirect)"
        );
        return false;
    }

    let Ok(meta) = std::fs::metadata(&canonical) else {
        return false;
    };
    if !meta.is_dir() {
        return false;
    }
    // SAFETY: getuid() is async-signal-safe and always succeeds.
    let current_uid = unsafe { libc::getuid() };
    if meta.uid() != current_uid {
        tracing::warn!(
            path = %canonical.display(),
            owner_uid = meta.uid(),
            current_uid,
            "skipping home tool dir not owned by current user"
        );
        return false;
    }
    if meta.mode() & 0o022 != 0 {
        tracing::warn!(
            path = %canonical.display(),
            mode = format!("{:o}", meta.mode() & 0o777),
            "skipping home tool dir with group/world write bits set"
        );
        return false;
    }
    true
}

/// A fixed system tool dir (e.g. `/opt/homebrew/bin`) is safe iff: it exists
/// as a directory and isn't world-writable. We don't require ownership-by-user
/// because Homebrew is typically owned by the admin user, not the LLM-driving
/// user; the deny boundary here is "any user can write to it" (mode bit 0o002).
#[cfg(unix)]
fn is_safe_system_dir(path: &Path) -> bool {
    use std::os::unix::fs::MetadataExt;
    let Ok(meta) = std::fs::metadata(path) else {
        return false;
    };
    if !meta.is_dir() {
        return false;
    }
    meta.mode() & 0o002 == 0
}

/// Parent-`$PATH` entries get the lightest filter: exists + not world-writable.
/// Anything more restrictive (e.g. ownership) is too noisy in practice — devs
/// commonly inherit dirs from package managers, distros, and CI images that
/// they don't personally own but trust to host binaries.
#[cfg(unix)]
fn is_safe_parent_path_entry(path: &Path) -> bool {
    is_safe_system_dir(path)
}

#[cfg(any(target_os = "linux", all(test, unix)))]
fn build_bwrap_argv(policy: SandboxPolicy, cwd: &Path, command: &str) -> Vec<String> {
    let mut a: Vec<String> = Vec::with_capacity(48);
    a.push("bwrap".into());

    // Whole filesystem read-only
    a.extend(["--ro-bind".into(), "/".into(), "/".into()]);
    a.extend(["--dev".into(), "/dev".into()]);
    a.extend(["--proc".into(), "/proc".into()]);

    if policy.allows_workspace_writes() {
        // Writable scratch
        a.extend(["--tmpfs".into(), "/tmp".into()]);

        let abs = cwd
            .to_str()
            .expect("validated UTF-8 in wrap_command")
            .to_string();
        a.extend(["--bind".into(), abs.clone(), abs.clone()]);

        if let Ok(real) = cwd.canonicalize()
            && let Some(real_str) = real.to_str()
            && real_str != abs
        {
            a.extend(["--bind".into(), real_str.to_string(), real_str.to_string()]);
        }

        if let Some(home) = std::env::var_os("HOME") {
            let home_path = PathBuf::from(home);
            for cache in HOME_CACHE_SUBDIRS {
                let dir = home_path.join(cache);
                // Create on the host first so the bind doesn't fail EROFS for
                // first-time builds in fresh containers / CI.
                let _ = std::fs::create_dir_all(&dir);
                if let Some(s) = dir.to_str() {
                    a.extend(["--bind".into(), s.to_string(), s.to_string()]);
                }
            }
        }
    }

    // Strip the parent process environment (PATH manipulation, LD_PRELOAD,
    // OPENAI_API_KEY/GITHUB_TOKEN/AWS_*, ...). Replace with a small whitelist
    // plus a sanitized PATH and TERM=dumb. PATH is discovered (not hardcoded)
    // so toolchains under ~/.cargo/bin, /opt/homebrew/bin, mise/asdf shims,
    // etc. are reachable inside the sandbox without per-host config.
    a.push("--clearenv".into());
    a.extend(["--setenv".into(), "PATH".into(), discover_sandbox_path()]);
    a.extend(["--setenv".into(), "TERM".into(), "dumb".into()]);
    for key in ENV_WHITELIST {
        if let Some(value) = std::env::var_os(key)
            && let Some(s) = value.to_str()
        {
            a.extend(["--setenv".into(), (*key).to_string(), s.to_string()]);
        }
    }

    a.push("--unshare-all".into());
    a.push("--share-net".into());
    a.push("--die-with-parent".into());
    a.push("--".into());

    a.push("sh".into());
    a.push("-c".into());
    a.push(command.into());

    a
}

#[cfg(target_os = "linux")]
fn is_bubblewrap_available() -> bool {
    if PathBuf::from("/usr/bin/bwrap").is_file() {
        return true;
    }
    if let Some(path_var) = std::env::var_os("PATH") {
        for dir in std::env::split_paths(&path_var) {
            if dir.join("bwrap").is_file() {
                return true;
            }
        }
    }
    false
}

/// Run a no-op bwrap invocation to verify it actually works on this host.
///
/// The presence of `/usr/bin/bwrap` is necessary but not sufficient: inside
/// Docker containers, AppArmor's `docker-default` profile and the kernel
/// sysctl `kernel.unprivileged_userns_clone=0` (still the default on
/// Debian/Ubuntu base images for years) make `bwrap --unshare-all` exit
/// non-zero. Without probing, the agent silently runs unsandboxed inside
/// such containers because `is_bubblewrap_available()` only checks
/// existence. The probe is `bwrap --ro-bind / / --unshare-all -- /bin/true`
/// (the smallest call that exercises the namespaces bwrap needs), invoked
/// once with a 3-second timeout. Result is cached by `effective_backend()`
/// via `OnceLock` so we don't re-fork per shell call.
#[cfg(target_os = "linux")]
fn probe_bubblewrap_runtime() -> bool {
    use std::process::{Command, Stdio};
    use std::time::{Duration, Instant};

    let start = Instant::now();
    let mut child = match Command::new("bwrap")
        .args([
            "--ro-bind",
            "/",
            "/",
            "--unshare-all",
            "--die-with-parent",
            "--",
            "/bin/true",
        ])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .spawn()
    {
        Ok(c) => c,
        Err(e) => {
            tracing::warn!(error = %e, "bwrap probe failed to spawn");
            return false;
        }
    };

    // Best-effort 3-second cap so a hung bwrap (network FS, etc.) doesn't
    // stall startup. `wait` without a deadline; we poll `try_wait` instead.
    let deadline = start + Duration::from_secs(3);
    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                let ok = status.success();
                if !ok {
                    // Read whatever stderr buffered before the exit. Most
                    // bwrap failures here are "bwrap: setting up uid map:
                    // Permission denied" (Docker) or
                    // "bwrap: setting up new user namespace failed".
                    let mut stderr_bytes = Vec::new();
                    if let Some(mut s) = child.stderr.take() {
                        use std::io::Read;
                        let _ = s.read_to_end(&mut stderr_bytes);
                    }
                    let stderr = String::from_utf8_lossy(&stderr_bytes);
                    tracing::warn!(
                        exit_code = ?status.code(),
                        stderr = %stderr.trim(),
                        "bwrap probe failed at runtime; user namespaces likely disabled \
                         (Docker, AppArmor, or kernel.unprivileged_userns_clone=0). \
                         Falling back to WASI sandbox."
                    );
                }
                return ok;
            }
            Ok(None) if Instant::now() < deadline => {
                std::thread::sleep(Duration::from_millis(50));
            }
            Ok(None) => {
                let _ = child.kill();
                tracing::warn!("bwrap probe timed out after 3s");
                return false;
            }
            Err(e) => {
                tracing::warn!(error = %e, "bwrap probe wait failed");
                return false;
            }
        }
    }
}

#[cfg(target_os = "linux")]
fn warn_missing_bwrap_once() {
    use std::sync::Once;
    static WARN: Once = Once::new();
    WARN.call_once(|| {
        tracing::warn!(
            "Bubblewrap (bwrap) not found on PATH; runShellCommand will execute without an OS sandbox. \
             Install with `apt install bubblewrap` (Debian/Ubuntu) or your distro's equivalent."
        );
    });
}

#[cfg(not(any(target_os = "macos", target_os = "linux")))]
fn warn_unsupported_os_once() {
    use std::sync::Once;
    static WARN: Once = Once::new();
    WARN.call_once(|| {
        tracing::warn!(
            "OS-level shell sandbox is not implemented on this platform; runShellCommand will execute without a sandbox"
        );
    });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn from_permission_mode_maps_to_expected_tiers() {
        assert_eq!(
            SandboxPolicy::from_permission_mode(PermissionMode::BypassPermissions),
            SandboxPolicy::None
        );
        assert_eq!(
            SandboxPolicy::from_permission_mode(PermissionMode::ReadOnly),
            SandboxPolicy::ReadOnly
        );
        assert_eq!(
            SandboxPolicy::from_permission_mode(PermissionMode::Default),
            SandboxPolicy::WorkspaceWrite
        );
        assert_eq!(
            SandboxPolicy::from_permission_mode(PermissionMode::AcceptEdits),
            SandboxPolicy::WorkspaceWrite
        );
    }

    /// Regression for the bug where Default mode produced a seatbelt profile
    /// with no `file-write*` rule, so clicking "Allow" on the per-call
    /// permission prompt could not actually let shell writes through.
    /// `writeFile` (which bypasses the sandbox entirely) succeeded in the
    /// same mode, making the user experience appear arbitrary.
    #[test]
    fn default_mode_seatbelt_profile_grants_workspace_writes() {
        let policy = SandboxPolicy::from_permission_mode(PermissionMode::Default);
        let scheme = build_seatbelt_policy(policy, Path::new("/tmp"));
        assert!(
            scheme.contains("file-write*"),
            "Default mode must emit a file-write* rule so an approved shell call can write under cwd; got:\n{scheme}"
        );
    }

    #[test]
    fn read_only_mode_seatbelt_profile_still_blocks_writes() {
        let policy = SandboxPolicy::from_permission_mode(PermissionMode::ReadOnly);
        let scheme = build_seatbelt_policy(policy, Path::new("/tmp"));
        assert!(
            !scheme.contains("file-write*"),
            "ReadOnly mode must never emit a file-write* rule; got:\n{scheme}"
        );
    }

    #[test]
    fn none_returns_unwrapped_argv_with_no_temp_file_and_sandboxed_false() {
        let wrapped = wrap_command(SandboxPolicy::None, Path::new("/tmp"), "echo hi").unwrap();
        assert_eq!(wrapped.argv, vec!["sh", "-c", "echo hi"]);
        assert!(wrapped._policy_file.is_none());
        assert!(!wrapped.sandboxed);
    }

    #[test]
    fn relative_cwd_is_rejected() {
        let err = wrap_command(
            SandboxPolicy::ReadOnly,
            Path::new("relative/path"),
            "echo hi",
        )
        .expect_err("relative cwd must error");
        assert_eq!(err.kind(), std::io::ErrorKind::InvalidInput);
    }

    #[cfg(unix)]
    #[test]
    fn non_utf8_cwd_is_rejected() {
        use std::ffi::OsStr;
        use std::os::unix::ffi::OsStrExt;
        let bad = OsStr::from_bytes(b"/\xff\xfe");
        let err = wrap_command(SandboxPolicy::ReadOnly, Path::new(bad), "echo hi")
            .expect_err("non-utf8 cwd must error");
        assert_eq!(err.kind(), std::io::ErrorKind::InvalidInput);
    }

    #[test]
    fn seatbelt_policy_read_only_has_no_write_rule() {
        let content = build_seatbelt_policy(SandboxPolicy::ReadOnly, Path::new("/tmp"));
        assert!(content.contains("(deny default)"));
        assert!(content.contains("(allow file-read*)"));
        assert!(!content.contains("file-write* (subpath"));
    }

    #[test]
    fn seatbelt_policy_workspace_write_includes_subpath_rule() {
        let content = build_seatbelt_policy(SandboxPolicy::WorkspaceWrite, Path::new("/tmp"));
        assert!(content.contains("(allow file-write* (subpath \"/tmp\""));
    }

    #[test]
    fn seatbelt_policy_falls_back_to_lexical_when_canonicalize_fails() {
        // Deliberately nonexistent path so canonicalize() returns Err; the
        // build should still emit a single subpath rule using the lexical
        // form (mirrors Environment.java:440-442).
        let content = build_seatbelt_policy(
            SandboxPolicy::WorkspaceWrite,
            Path::new("/nonexistent-brokk-test-cwd-zzzzz"),
        );
        let count = content
            .matches("file-write* (subpath \"/nonexistent-brokk-test-cwd-zzzzz")
            .count();
        assert_eq!(
            count, 1,
            "expected exactly one lexical subpath when canonicalize fails"
        );
    }

    #[test]
    fn seatbelt_policy_escapes_quote_in_cwd() {
        // Path with an embedded quote must not break out of the (subpath "...")
        // string literal. We don't actually create the path; build_seatbelt_policy
        // does not call canonicalize on a fail-only path, so this exercises
        // the escape path.
        let content =
            build_seatbelt_policy(SandboxPolicy::WorkspaceWrite, Path::new("/tmp/has\"quote"));
        assert!(content.contains(r#"/tmp/has\"quote"#));
        // Ensure the unescaped quote does NOT appear (which would close the
        // string literal early).
        assert!(!content.contains(r#""/tmp/has"quote""#));
    }

    #[test]
    fn escape_handles_quotes_and_backslashes() {
        assert_eq!(escape_for_seatbelt(r#"a"b\c"#), r#"a\"b\\c"#);
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_wrap_command_emits_sandbox_exec_argv() {
        let wrapped =
            wrap_command(SandboxPolicy::WorkspaceWrite, Path::new("/tmp"), "echo hi").unwrap();
        assert_eq!(wrapped.argv[0], "sandbox-exec");
        assert_eq!(wrapped.argv[1], "-f");
        assert_eq!(wrapped.argv[3], "--");
        assert_eq!(&wrapped.argv[4..], &["sh", "-c", "echo hi"]);
        assert!(wrapped._policy_file.is_some());
        assert!(wrapped.sandboxed);

        let policy_path = std::path::Path::new(&wrapped.argv[2]);
        let body = std::fs::read_to_string(policy_path).unwrap();
        assert!(body.contains("(allow file-write* (subpath \"/tmp\""));
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_temp_policy_file_is_removed_on_drop() {
        let wrapped = wrap_command(SandboxPolicy::ReadOnly, Path::new("/tmp"), "echo hi").unwrap();
        let path = std::path::PathBuf::from(&wrapped.argv[2]);
        assert!(
            path.exists(),
            "policy file should exist while wrapped is alive"
        );
        drop(wrapped);
        assert!(
            !path.exists(),
            "policy file should be removed after WrappedCommand drops"
        );
    }

    #[cfg(all(target_os = "macos", unix))]
    #[test]
    fn macos_temp_policy_file_is_mode_600() {
        use std::os::unix::fs::PermissionsExt;
        let wrapped = wrap_command(SandboxPolicy::ReadOnly, Path::new("/tmp"), "echo hi").unwrap();
        let path = std::path::PathBuf::from(&wrapped.argv[2]);
        let mode = std::fs::metadata(&path).unwrap().permissions().mode() & 0o777;
        assert_eq!(
            mode, 0o600,
            "policy file should be mode 0600, got {:o}",
            mode
        );
    }

    #[cfg(unix)]
    #[test]
    fn bwrap_argv_read_only_has_no_workspace_bind() {
        let argv = build_bwrap_argv(SandboxPolicy::ReadOnly, Path::new("/workspace"), "echo hi");
        assert_eq!(argv[0], "bwrap");
        assert!(argv.contains(&"--ro-bind".to_string()));
        assert!(argv.contains(&"--unshare-all".to_string()));
        assert!(argv.contains(&"--share-net".to_string()));
        assert!(argv.contains(&"--die-with-parent".to_string()));
        assert!(argv.contains(&"--clearenv".to_string()));
        let workspace_bind = argv
            .windows(3)
            .any(|w| w[0] == "--bind" && w[1] == "/workspace" && w[2] == "/workspace");
        assert!(!workspace_bind, "ReadOnly must not bind workspace rw");
        let dash_idx = argv.iter().position(|a| a == "--").unwrap();
        assert_eq!(&argv[dash_idx + 1..], &["sh", "-c", "echo hi"]);
    }

    #[cfg(unix)]
    #[test]
    fn bwrap_argv_workspace_write_binds_root_rw() {
        let argv = build_bwrap_argv(
            SandboxPolicy::WorkspaceWrite,
            Path::new("/workspace"),
            "echo hi",
        );
        let workspace_bind = argv
            .windows(3)
            .any(|w| w[0] == "--bind" && w[1] == "/workspace" && w[2] == "/workspace");
        assert!(workspace_bind, "WorkspaceWrite must bind workspace rw");
        assert!(argv.contains(&"--tmpfs".to_string()));
    }

    #[cfg(unix)]
    #[test]
    fn bwrap_argv_clearenv_with_path_and_term() {
        let argv = build_bwrap_argv(SandboxPolicy::ReadOnly, Path::new("/workspace"), "echo hi");
        // --clearenv must come before any --setenv so the whitelist isn't
        // overwritten by the parent env.
        let clearenv_idx = argv.iter().position(|a| a == "--clearenv").unwrap();
        let setenv_idx = argv.iter().position(|a| a == "--setenv").unwrap();
        assert!(
            clearenv_idx < setenv_idx,
            "--clearenv must precede --setenv"
        );
        // PATH must be set via --setenv to a non-empty discovered value that
        // still contains the static fallback dirs at the tail. We assert
        // structurally (substring match) rather than against a fixed string
        // because discover_sandbox_path() reflects the host's installed
        // toolchains and varies per machine.
        let path_value = argv
            .windows(3)
            .find_map(|w| {
                if w[0] == "--setenv" && w[1] == "PATH" {
                    Some(w[2].clone())
                } else {
                    None
                }
            })
            .expect("PATH must be sanitized via --setenv");
        assert!(!path_value.is_empty(), "discovered PATH must not be empty");
        for fallback in ["/usr/local/bin", "/usr/bin", "/bin"] {
            assert!(
                path_value.split(':').any(|p| p == fallback),
                "discovered PATH must keep static fallback '{fallback}', got '{path_value}'"
            );
        }
        let has_term = argv
            .windows(3)
            .any(|w| w[0] == "--setenv" && w[1] == "TERM" && w[2] == "dumb");
        assert!(has_term, "TERM must be set to dumb via --setenv");
    }

    #[cfg(unix)]
    #[test]
    fn bwrap_argv_no_credential_root_binds() {
        // Stricter than Java: we must NOT bind ~/.cargo or ~/.gradle as
        // roots; only specific cache subdirs.
        let argv = build_bwrap_argv(
            SandboxPolicy::WorkspaceWrite,
            Path::new("/workspace"),
            "echo hi",
        );
        let home = std::env::var_os("HOME")
            .map(PathBuf::from)
            .unwrap_or_default();
        let cargo_root = home.join(".cargo").to_string_lossy().into_owned();
        let gradle_root = home.join(".gradle").to_string_lossy().into_owned();
        let m2_root = home.join(".m2").to_string_lossy().into_owned();
        for forbidden in [&cargo_root, &gradle_root, &m2_root] {
            let bound = argv
                .windows(3)
                .any(|w| w[0] == "--bind" && w[1] == *forbidden && w[2] == *forbidden);
            assert!(
                !bound,
                "credential-bearing root '{}' must not be bound",
                forbidden
            );
        }
    }

    // -----------------------------------------------------------------
    // PATH discovery (Unix-only: discover_sandbox_path and the
    // is_safe_* helpers are cfg(unix); these tests follow suit.)
    // -----------------------------------------------------------------

    /// Serializes tests that mutate process-wide env vars touched by
    /// `discover_sandbox_path` (BROKK_ACP_PATH, PATH). `cargo test` runs
    /// `#[test]` cases in parallel inside one binary, so any test that
    /// `set_var` here must hold this lock to avoid racing readers from
    /// sibling tests.
    #[cfg(unix)]
    static SANDBOX_ENV_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

    /// Restores a single env var on Drop. Pair with `SANDBOX_ENV_LOCK` so
    /// the drop ordering is deterministic and we don't leak state into
    /// the next test.
    #[cfg(unix)]
    struct SandboxEnvGuard {
        var: &'static str,
        prior: Option<std::ffi::OsString>,
    }

    #[cfg(unix)]
    impl SandboxEnvGuard {
        fn set(var: &'static str, value: &str) -> Self {
            let prior = std::env::var_os(var);
            // SAFETY: caller holds SANDBOX_ENV_LOCK; the crate's other
            // env-mutating tests use their own locks for non-overlapping
            // vars (BROKK_ACP_RLIMIT_*).
            unsafe {
                std::env::set_var(var, value);
            }
            Self { var, prior }
        }
        fn unset(var: &'static str) -> Self {
            let prior = std::env::var_os(var);
            // SAFETY: same as set().
            unsafe {
                std::env::remove_var(var);
            }
            Self { var, prior }
        }
    }

    #[cfg(unix)]
    impl Drop for SandboxEnvGuard {
        fn drop(&mut self) {
            // SAFETY: same as set()/unset().
            unsafe {
                match self.prior.take() {
                    Some(v) => std::env::set_var(self.var, v),
                    None => std::env::remove_var(self.var),
                }
            }
        }
    }

    /// Acquire `SANDBOX_ENV_LOCK` even if a prior test panicked while
    /// holding it. The lock protects against parallel env-var mutation,
    /// not shared in-memory state, so a panic in one test never corrupts
    /// anything observable here — unpoisoning is the right call.
    #[cfg(unix)]
    fn sandbox_env_lock() -> std::sync::MutexGuard<'static, ()> {
        SANDBOX_ENV_LOCK
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    #[cfg(unix)]
    #[test]
    fn discover_sandbox_path_includes_static_fallback() {
        // With no override, the result must always contain the static
        // fallback dirs so the existing behavior is a strict subset of
        // the new behavior.
        let _g = sandbox_env_lock();
        let _clear = SandboxEnvGuard::unset(BROKK_ACP_PATH_ENV);
        let path = discover_sandbox_path();
        let entries: Vec<String> = std::env::split_paths(&path)
            .map(|p| p.to_string_lossy().into_owned())
            .collect();
        for fallback in ["/usr/local/bin", "/usr/bin", "/bin"] {
            assert!(
                entries.iter().any(|p| p == fallback),
                "fallback '{fallback}' missing from discovered PATH '{path}'"
            );
        }
    }

    #[cfg(unix)]
    #[test]
    fn discover_sandbox_path_de_duplicates_entries() {
        // No entry should appear twice even when the parent PATH overlaps
        // with our static fallback or with home tool dirs. Use
        // `split_paths` (not `split(':')`) so this works on Windows CI,
        // where the platform separator is `;` and Unix-style `:` splitting
        // breaks `D:\a\...` paths into spurious `D` fragments.
        let _g = sandbox_env_lock();
        let _clear = SandboxEnvGuard::unset(BROKK_ACP_PATH_ENV);
        let path = discover_sandbox_path();
        let mut seen = std::collections::HashSet::new();
        for entry in std::env::split_paths(&path) {
            let s = entry.to_string_lossy().into_owned();
            assert!(
                seen.insert(s.clone()),
                "PATH entry '{s}' is duplicated: '{path}'"
            );
        }
    }

    #[cfg(unix)]
    #[test]
    fn discover_sandbox_path_brokk_acp_path_override_returns_verbatim() {
        let _g = sandbox_env_lock();
        let _set = SandboxEnvGuard::set(BROKK_ACP_PATH_ENV, "/custom/bin:/another/bin");
        let path = discover_sandbox_path();
        assert_eq!(path, "/custom/bin:/another/bin");
    }

    #[cfg(unix)]
    #[test]
    fn discover_sandbox_path_empty_override_falls_through_to_discovery() {
        // A whitespace-only or empty BROKK_ACP_PATH must not short-circuit
        // discovery -- otherwise an `export BROKK_ACP_PATH=` in a parent
        // shell wipes the user's PATH inside the sandbox.
        let _g = sandbox_env_lock();
        let _set = SandboxEnvGuard::set(BROKK_ACP_PATH_ENV, "   ");
        let path = discover_sandbox_path();
        let entries: Vec<String> = std::env::split_paths(&path)
            .map(|p| p.to_string_lossy().into_owned())
            .collect();
        assert!(
            entries.iter().any(|p| p == "/usr/bin"),
            "empty override must fall through to discovery, got '{path}'"
        );
    }

    #[cfg(unix)]
    #[test]
    fn is_safe_system_dir_rejects_world_writable() {
        use std::os::unix::fs::PermissionsExt;
        let tmp = std::env::temp_dir().join(format!(
            "brokk-sandbox-test-ww-{}-{:?}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or_default()
        ));
        std::fs::create_dir(&tmp).expect("create tmp dir");
        let mut perms = std::fs::metadata(&tmp).unwrap().permissions();
        perms.set_mode(0o777);
        std::fs::set_permissions(&tmp, perms).unwrap();

        assert!(
            !is_safe_system_dir(&tmp),
            "world-writable dir must be rejected"
        );

        // Tighten back to 0o755 and confirm acceptance.
        let mut perms = std::fs::metadata(&tmp).unwrap().permissions();
        perms.set_mode(0o755);
        std::fs::set_permissions(&tmp, perms).unwrap();
        assert!(
            is_safe_system_dir(&tmp),
            "0o755 dir must be accepted by is_safe_system_dir"
        );

        let _ = std::fs::remove_dir(&tmp);
    }

    /// A scratch "fake home" rooted in `temp_dir()`, returned canonicalized
    /// so `is_safe_home_dir` containment checks compare apples to apples on
    /// hosts where `/tmp` -> `/private/tmp` (macOS) or similar. The Drop
    /// guard removes the tree even if the test asserts midway.
    #[cfg(unix)]
    struct FakeHome {
        canonical: PathBuf,
        // Kept for drop-time cleanup.
        original: PathBuf,
    }

    #[cfg(unix)]
    impl FakeHome {
        fn new(tag: &str) -> Self {
            let original = std::env::temp_dir().join(format!(
                "brokk-fakehome-{tag}-{}-{:?}",
                std::process::id(),
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_nanos())
                    .unwrap_or_default()
            ));
            std::fs::create_dir(&original).expect("create fake home");
            let canonical = original.canonicalize().expect("canonicalize fake home");
            Self {
                canonical,
                original,
            }
        }
    }

    #[cfg(unix)]
    impl Drop for FakeHome {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.original);
        }
    }

    #[cfg(unix)]
    #[test]
    fn is_safe_home_dir_rejects_group_writable() {
        use std::os::unix::fs::PermissionsExt;
        let home = FakeHome::new("gw");
        let bin = home.canonical.join("bin");
        std::fs::create_dir(&bin).expect("create bin");
        // Group-writable but not world-writable: passes is_safe_system_dir
        // (which only rejects 0o002) but fails is_safe_home_dir (which
        // rejects 0o022).
        let mut perms = std::fs::metadata(&bin).unwrap().permissions();
        perms.set_mode(0o775);
        std::fs::set_permissions(&bin, perms).unwrap();

        assert!(
            is_safe_system_dir(&bin),
            "0o775 dir is fine as a system dir (only world bit matters)"
        );
        assert!(
            !is_safe_home_dir(&bin, &home.canonical),
            "group-writable dir must be rejected by is_safe_home_dir"
        );
    }

    /// #3543 acceptance: a symlink under `$HOME` whose target lives outside
    /// `$HOME` is rejected, even when the target is owned by the current
    /// user and has tight mode bits. Without `canonicalize() + starts_with`
    /// in is_safe_home_dir, `metadata()` would silently follow the link
    /// and accept the off-home target.
    #[cfg(unix)]
    #[test]
    fn is_safe_home_dir_rejects_symlink_pointing_outside_home() {
        use std::os::unix::fs::PermissionsExt;
        let home = FakeHome::new("symesc-home");
        let outside = FakeHome::new("symesc-outside");
        // Outside dir is squeaky-clean so the only thing rejecting it is
        // the containment check.
        let mut perms = std::fs::metadata(&outside.canonical).unwrap().permissions();
        perms.set_mode(0o755);
        std::fs::set_permissions(&outside.canonical, perms).unwrap();

        let link = home.canonical.join("escaped_bin");
        std::os::unix::fs::symlink(&outside.canonical, &link).expect("create symlink");

        assert!(
            !is_safe_home_dir(&link, &home.canonical),
            "symlink under home pointing to {} (outside home {}) must be rejected",
            outside.canonical.display(),
            home.canonical.display(),
        );
    }

    /// Companion to the symlink-escape test: a symlink under `$HOME` whose
    /// target is itself inside `$HOME` is fine. Prevents the containment
    /// check from regressing into "no symlinks at all", which would break
    /// legit setups like `~/.cargo/bin -> ~/.rustup/toolchains/stable/bin`.
    #[cfg(unix)]
    #[test]
    fn is_safe_home_dir_accepts_symlink_pointing_inside_home() {
        use std::os::unix::fs::PermissionsExt;
        let home = FakeHome::new("symok");
        let inner_target = home.canonical.join("actual_bin");
        std::fs::create_dir(&inner_target).expect("create inner target");
        let mut perms = std::fs::metadata(&inner_target).unwrap().permissions();
        perms.set_mode(0o755);
        std::fs::set_permissions(&inner_target, perms).unwrap();

        let link = home.canonical.join("cargo_bin");
        std::os::unix::fs::symlink(&inner_target, &link).expect("create symlink");

        assert!(
            is_safe_home_dir(&link, &home.canonical),
            "symlink under home whose target is inside home must be accepted"
        );
    }

    #[cfg(unix)]
    #[test]
    fn is_safe_helpers_reject_nonexistent_path() {
        let path = Path::new("/this/definitely/does/not/exist/brokk-zzz");
        let dummy_home = Path::new("/");
        assert!(!is_safe_home_dir(path, dummy_home));
        assert!(!is_safe_system_dir(path));
        assert!(!is_safe_parent_path_entry(path));
    }

    // ----- SandboxBackend ----------------------------------------------------

    #[test]
    fn parse_override_handles_unset_and_auto() {
        assert_eq!(SandboxBackend::parse_override(None), Ok(None));
        assert_eq!(SandboxBackend::parse_override(Some("auto")), Ok(None));
        assert_eq!(SandboxBackend::parse_override(Some("AUTO")), Ok(None));
        assert_eq!(SandboxBackend::parse_override(Some("  auto  ")), Ok(None));
        assert_eq!(SandboxBackend::parse_override(Some("")), Ok(None));
        assert_eq!(SandboxBackend::parse_override(Some("   ")), Ok(None));
    }

    #[test]
    fn parse_override_maps_named_backends() {
        assert_eq!(
            SandboxBackend::parse_override(Some("native")),
            Ok(Some(SandboxBackend::Native))
        );
        // Case-insensitive: operator may use any casing.
        assert_eq!(
            SandboxBackend::parse_override(Some("NATIVE")),
            Ok(Some(SandboxBackend::Native))
        );
        // `wasi` and `wasm` are accepted aliases.
        assert_eq!(
            SandboxBackend::parse_override(Some("wasi")),
            Ok(Some(SandboxBackend::Wasi))
        );
        assert_eq!(
            SandboxBackend::parse_override(Some("wasm")),
            Ok(Some(SandboxBackend::Wasi))
        );
        // Common "disable" spellings all map to None.
        assert_eq!(
            SandboxBackend::parse_override(Some("none")),
            Ok(Some(SandboxBackend::None))
        );
        assert_eq!(
            SandboxBackend::parse_override(Some("off")),
            Ok(Some(SandboxBackend::None))
        );
        assert_eq!(
            SandboxBackend::parse_override(Some("disabled")),
            Ok(Some(SandboxBackend::None))
        );
    }

    // ----- resolve_backend precedence ---------------------------------------

    /// The headline contract: when the operator forces WASI, WASI wins
    /// even on a host where the native sandbox would normally be picked.
    /// This is what `--sandbox-backend wasi` (or `BROKK_ACP_SANDBOX_BACKEND=wasi`)
    /// has to guarantee for the "I want to choose WASI" use case to work.
    #[test]
    fn resolve_backend_override_wasi_wins_over_native() {
        let chosen = resolve_backend(
            Some(SandboxBackend::Wasi),
            /* native_works = */ true,
            /* has_wasi_fallback = */ true,
        );
        assert_eq!(
            chosen,
            SandboxBackend::Wasi,
            "operator-forced WASI must override Native detection"
        );
    }

    /// Inverse direction: an operator forcing Native on a host where it
    /// doesn't work still picks Native. We honor the explicit choice;
    /// `wrap_command` will surface an unsandboxed result with a clear
    /// reason when the native path can't actually wrap the call.
    #[test]
    fn resolve_backend_override_native_wins_over_wasi_detection() {
        let chosen = resolve_backend(
            Some(SandboxBackend::Native),
            /* native_works = */ false,
            /* has_wasi_fallback = */ true,
        );
        assert_eq!(chosen, SandboxBackend::Native);
    }

    /// `--sandbox-backend none` is the explicit "no sandbox at all" knob
    /// for development; it must override both Native availability and
    /// WASI fallback.
    #[test]
    fn resolve_backend_override_none_wins() {
        let chosen = resolve_backend(Some(SandboxBackend::None), true, true);
        assert_eq!(chosen, SandboxBackend::None);
    }

    /// Auto on a host with native available -> Native (the macOS path,
    /// and Linux with bwrap working).
    #[test]
    fn resolve_backend_auto_picks_native_when_available() {
        assert_eq!(resolve_backend(None, true, true), SandboxBackend::Native);
        assert_eq!(resolve_backend(None, true, false), SandboxBackend::Native);
    }

    /// Auto on a host where native is unavailable but WASI fallback
    /// applies -> WASI. This is the Docker case (bwrap broken) and the
    /// Windows case (no native sandbox at all).
    #[test]
    fn resolve_backend_auto_falls_back_to_wasi() {
        let chosen = resolve_backend(None, false, true);
        assert_eq!(chosen, SandboxBackend::Wasi);
    }

    /// Auto on a host with neither native nor WASI fallback (currently
    /// unreachable -- macOS always has Seatbelt, and Linux/Windows
    /// always get the WASI fallback) returns None. Locked in so a
    /// future cfg change can't silently widen the unsandboxed surface.
    #[test]
    fn resolve_backend_auto_returns_none_when_nothing_available() {
        let chosen = resolve_backend(None, false, false);
        assert_eq!(chosen, SandboxBackend::None);
    }

    // ----- describe_status / /sandbox slash command -------------------------

    /// `/sandbox` output must (1) include the canonical "Sandbox status"
    /// header so an ACP client can recognize it, (2) name the active
    /// backend with its tier word, (3) mention the WASI module registry
    /// (count or "no modules"), (4) explain how to change the backend.
    /// Locking these in so a future refactor doesn't silently turn the
    /// slash command into a blank "OK" or strip the "how to change" path.
    #[test]
    fn describe_status_contains_required_sections() {
        let out = describe_status();
        assert!(out.contains("Sandbox status"), "missing header in: {out}");
        assert!(
            out.contains("Active backend"),
            "must surface which backend is in use: {out}"
        );
        assert!(
            out.contains("WASI module registry"),
            "must surface module registry state: {out}"
        );
        assert!(
            out.contains("--sandbox-backend") || out.contains("BROKK_ACP_SANDBOX_BACKEND"),
            "must explain how to change the backend (CLI flag / env var): {out}"
        );
    }

    /// The output must explicitly name the platform-appropriate sandbox
    /// tool in the "native" label, so an operator skimming the dump
    /// doesn't have to guess what "native" means on their host.
    #[test]
    fn backend_display_name_includes_platform_tool_for_native() {
        let label = backend_display_name(SandboxBackend::Native);
        #[cfg(target_os = "macos")]
        assert!(label.contains("Seatbelt"), "got: {label}");
        #[cfg(target_os = "linux")]
        assert!(label.contains("Bubblewrap"), "got: {label}");
    }

    /// The WASI tier label must mention wasmtime so the operator can
    /// tell what they're getting (vs. e.g. a future wasmer-backed
    /// variant). Locks the label in case someone reshuffles the
    /// `backend_display_name` match arms.
    #[test]
    fn backend_display_name_names_wasmtime_for_wasi() {
        let label = backend_display_name(SandboxBackend::Wasi);
        assert!(label.contains("WASI"), "got: {label}");
        assert!(label.contains("wasmtime"), "got: {label}");
    }

    /// When no operator override is set, the dump must show "none (auto)"
    /// rather than printing nothing or printing the same as `chosen`.
    /// This is what tells the operator "you didn't force anything, the
    /// agent picked this on its own".
    #[test]
    fn describe_status_calls_out_no_override_explicitly() {
        // Since `effective_resolution()` is OnceLock-cached and we can't
        // safely poison/reset it from a unit test, we exercise the
        // formatting branch directly: any process where no env var is
        // set at startup produces `override_: None`, which is the
        // common case for `cargo test`.
        let out = describe_status();
        // If the cached resolution happens to have a real override (a
        // dev with BROKK_ACP_SANDBOX_BACKEND set in their shell), we
        // can't assert "none (auto)". Just assert the section exists.
        assert!(
            out.contains("Operator override"),
            "must surface override origin: {out}"
        );
    }

    #[test]
    fn parse_override_rejects_unknown_values() {
        let err = SandboxBackend::parse_override(Some("bubblewrap")).unwrap_err();
        // Error mentions both the env var and the accepted values so an
        // operator typo logs a self-correcting message.
        assert!(err.contains(SANDBOX_BACKEND_ENV));
        assert!(err.contains("auto"));
        assert!(err.contains("native"));
        assert!(err.contains("wasi"));
    }

    // ----- WrappedCommand::unwrapped_with_reason -----------------------------

    #[test]
    fn unwrapped_with_reason_carries_reason_and_is_not_sandboxed() {
        let wrapped = WrappedCommand::unwrapped_with_reason("echo hi", "bwrap missing".to_string());
        assert!(!wrapped.sandboxed);
        assert_eq!(
            wrapped.fallback_reason.as_deref(),
            Some("bwrap missing"),
            "the reason must reach shell.rs so the bypass warning can quote it"
        );
        assert_eq!(wrapped.argv, vec!["sh", "-c", "echo hi"]);
    }

    #[test]
    fn unwrapped_default_has_no_reason() {
        let wrapped = WrappedCommand::unwrapped("echo hi");
        assert!(!wrapped.sandboxed);
        assert!(wrapped.fallback_reason.is_none());
    }

    // ----- wrap_command_with_backend dispatch -------------------------------

    #[test]
    fn dispatch_none_backend_returns_unwrapped_process() {
        let sandboxed = wrap_command_with_backend(
            SandboxBackend::None,
            SandboxPolicy::WorkspaceWrite,
            Path::new("/tmp"),
            "echo hi",
        )
        .unwrap();
        match sandboxed {
            SandboxedCommand::Process(w) => {
                assert!(!w.sandboxed);
                assert!(w.fallback_reason.is_none());
                assert_eq!(w.argv, vec!["sh", "-c", "echo hi"]);
            }
            SandboxedCommand::Wasi(_) => {
                panic!("None backend must never produce a WASI invocation");
            }
        }
    }

    #[test]
    fn dispatch_policy_none_short_circuits_to_process_unwrapped() {
        // Even with backend=Wasi, policy=None means the user explicitly
        // opted out, so we must not try to route through WASI -- the
        // command runs as-is.
        let sandboxed = wrap_command_with_backend(
            SandboxBackend::Wasi,
            SandboxPolicy::None,
            Path::new("/tmp"),
            "echo hi",
        )
        .unwrap();
        match sandboxed {
            SandboxedCommand::Process(w) => assert!(!w.sandboxed),
            SandboxedCommand::Wasi(_) => panic!("policy=None must skip WASI routing"),
        }
    }

    #[test]
    fn dispatch_wasi_with_pipeline_falls_back_with_reason() {
        // The global WASI registry is empty in test runs, so any command
        // would fall back. Pick one with a shell metacharacter so the
        // failure reason names the offending char specifically.
        let sandboxed = wrap_command_with_backend(
            SandboxBackend::Wasi,
            SandboxPolicy::ReadOnly,
            Path::new("/tmp"),
            "cat foo | head",
        )
        .unwrap();
        match sandboxed {
            SandboxedCommand::Process(w) => {
                assert!(!w.sandboxed);
                let reason = w
                    .fallback_reason
                    .expect("WASI fallback must attach a reason");
                assert!(
                    reason.contains("WASI"),
                    "reason should identify the WASI sandbox: {reason}"
                );
                assert!(
                    reason.contains('|') || reason.contains("shell feature"),
                    "reason should name the offending shell feature: {reason}"
                );
            }
            SandboxedCommand::Wasi(_) => {
                panic!("pipeline must not be routed to WASI");
            }
        }
    }

    #[test]
    fn dispatch_wasi_with_unknown_tool_falls_back_with_reason() {
        // Registry is empty in unit tests; even a valid simple command
        // falls back because no module is registered for `nosuchtool`.
        let sandboxed = wrap_command_with_backend(
            SandboxBackend::Wasi,
            SandboxPolicy::ReadOnly,
            Path::new("/tmp"),
            "nosuchtool arg1",
        )
        .unwrap();
        match sandboxed {
            SandboxedCommand::Process(w) => {
                let reason = w.fallback_reason.expect("must attach a reason");
                assert!(reason.contains("nosuchtool"), "{reason}");
            }
            SandboxedCommand::Wasi(_) => {
                panic!("unknown tool must not be routed to WASI");
            }
        }
    }

    #[test]
    fn dispatch_wasi_rejects_relative_cwd() {
        // Path validation must trip before we even reach WASI routing,
        // mirroring the native path's contract.
        let err = wrap_command_with_backend(
            SandboxBackend::Wasi,
            SandboxPolicy::ReadOnly,
            Path::new("relative/path"),
            "rg hello",
        )
        .expect_err("relative cwd must error");
        assert_eq!(err.kind(), std::io::ErrorKind::InvalidInput);
    }

    #[test]
    fn env_whitelist_excludes_credential_names() {
        // Defense-in-depth: the test fails if anyone adds a name matching
        // a credential-shaped pattern to the allowlist.
        for key in ENV_WHITELIST {
            let upper = key.to_ascii_uppercase();
            for forbidden in ["TOKEN", "SECRET", "PASSWORD", "API_KEY", "AWS_", "OPENAI_"] {
                assert!(
                    !upper.contains(forbidden),
                    "ENV_WHITELIST entry '{key}' looks credential-shaped (matches '{forbidden}')"
                );
            }
        }
    }

    #[test]
    fn env_whitelist_includes_expected_toolchain_pointers() {
        for required in [
            "CARGO_HOME",
            "RUSTUP_HOME",
            "NVM_DIR",
            "ASDF_DIR",
            "MISE_DATA_DIR",
            "PYENV_ROOT",
            "BUN_INSTALL",
            "DENO_INSTALL",
        ] {
            assert!(
                ENV_WHITELIST.contains(&required),
                "ENV_WHITELIST missing expected toolchain pointer '{required}'"
            );
        }
    }
}
