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
    /// `Default` resolves to `ReadOnly` because in default mode shell calls
    /// still hit the per-call permission prompt; the sandbox bounds what
    /// "Allow" actually grants. `BypassPermissions` is the explicit opt-out.
    pub fn from_permission_mode(mode: PermissionMode) -> Self {
        match mode {
            PermissionMode::BypassPermissions => Self::None,
            PermissionMode::ReadOnly => Self::ReadOnly,
            PermissionMode::Default => Self::ReadOnly,
            PermissionMode::AcceptEdits => Self::WorkspaceWrite,
        }
    }

    pub fn allows_workspace_writes(self) -> bool {
        matches!(self, Self::WorkspaceWrite)
    }
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
#[derive(Debug)]
pub struct WrappedCommand {
    pub argv: Vec<String>,
    pub sandboxed: bool,
    /// Held to keep the temp file alive; never read directly.
    _policy_file: Option<TempPolicyFile>,
}

impl WrappedCommand {
    fn unwrapped(command: &str) -> Self {
        Self {
            argv: vec!["sh".into(), "-c".into(), command.into()],
            sandboxed: false,
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

#[cfg(target_os = "macos")]
fn wrap_platform(
    policy: SandboxPolicy,
    cwd: &Path,
    command: &str,
) -> std::io::Result<WrappedCommand> {
    let policy_content = build_seatbelt_policy(policy, cwd);
    let path = std::env::temp_dir().join(format!("brokk-seatbelt-{}.sb", uuid::Uuid::new_v4()));
    write_policy_file_secure(&path, &policy_content)
        .map_err(|e| sandbox_io_error(format!("write seatbelt profile: {e}")))?;
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
        return Ok(WrappedCommand::unwrapped(command));
    }

    let argv = build_bwrap_argv(policy, cwd, command);
    Ok(WrappedCommand {
        argv,
        sandboxed: true,
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
    Ok(WrappedCommand::unwrapped(command))
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
#[cfg_attr(not(any(target_os = "linux", test)), allow(dead_code))]
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
pub const ENV_WHITELIST: &[&str] = &["HOME", "USER", "LOGNAME", "LANG", "LC_ALL"];

#[cfg_attr(not(any(target_os = "linux", test)), allow(dead_code))]
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
    // plus a sanitized PATH and TERM=dumb.
    a.push("--clearenv".into());
    a.extend([
        "--setenv".into(),
        "PATH".into(),
        "/usr/local/bin:/usr/bin:/bin".into(),
    ]);
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
            SandboxPolicy::ReadOnly
        );
        assert_eq!(
            SandboxPolicy::from_permission_mode(PermissionMode::AcceptEdits),
            SandboxPolicy::WorkspaceWrite
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
        // PATH and TERM must be set explicitly.
        let has_path = argv.windows(3).any(|w| {
            w[0] == "--setenv" && w[1] == "PATH" && w[2] == "/usr/local/bin:/usr/bin:/bin"
        });
        let has_term = argv
            .windows(3)
            .any(|w| w[0] == "--setenv" && w[1] == "TERM" && w[2] == "dumb");
        assert!(has_path, "PATH must be sanitized via --setenv");
        assert!(has_term, "TERM must be set to dumb via --setenv");
    }

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
}
