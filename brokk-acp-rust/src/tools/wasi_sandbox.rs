//! WASI sandbox fallback for `runShellCommand`.
//!
//! Lives alongside the native sandbox in `sandbox.rs` (Seatbelt / Bubblewrap)
//! and is selected when:
//!   - `bwrap` is missing OR fails the runtime probe (the Docker case:
//!     unprivileged user namespaces disabled by AppArmor, seccomp, or
//!     `kernel.unprivileged_userns_clone=0`); or
//!   - the platform has no native sandbox (Windows).
//!
//! ## Strictly weaker than the native sandboxes
//!
//! WASI cannot wrap `sh -c "<arbitrary cmd>"` because POSIX `fork`/`exec`
//! and shell-language features (pipelines `|`, redirections `>`/`<`,
//! command substitution `$(...)`/backticks, variable expansion `$VAR`,
//! globbing `*`/`?`/`[...]`, tilde `~`, history `!`, subshells `(...)`,
//! brace expansion `{...}`) are not in the WASI preview1/preview2 API
//! surface. So this module only wraps commands that:
//!   1. parse cleanly as `<tool> [arg]...` (single binary, literal args
//!      with optional quoting -- no shell metacharacters); AND
//!   2. resolve `<tool>` to a registered WASI module.
//!
//! Anything else falls back to unsandboxed exec, with a warning prepended
//! to the tool output by `shell.rs::SANDBOX_BYPASS_WARNING`. This is the
//! "weaker security" cost the operator opts into when they pick WASI as
//! the active backend (typically because the native ones are unavailable).
//!
//! ## Threat model alignment with bwrap / Seatbelt
//!
//! For commands we *do* route here, isolation matches the native tier:
//!
//! | Native (bwrap)                | WASI                                  |
//! |-------------------------------|---------------------------------------|
//! | `--ro-bind / /`               | no global preopen (deny by default)   |
//! | `--bind cwd cwd`              | preopen cwd with DirPerms::all()      |
//! | `--unshare-net`               | no socket support enabled in linker   |
//! | `--unshare-pid`               | no process/fork primitives in WASI    |
//! | `--clearenv` + whitelist      | env empty + whitelist via `WasiCtxBuilder::env` |
//! | per-process rlimits           | wasmtime epoch deadline for timeout   |
//!
//! ## Module registry
//!
//! Modules are looked up by their CLI name (`cat`, `rg`, ...) and stored
//! as `Arc<Vec<u8>>` of precompiled `wasm32-wasi` bytes. Distribution
//! channels:
//!   - **bundled** (intended path per #3613 design): `include_bytes!()` at
//!     build time. The initial framework lands with the bundled set empty;
//!     follow-up PRs add tools incrementally so each `.wasm` can be source-
//!     vetted on its own (cf. `feedback_credential_dep_vetting`).
//!   - **operator-supplied** (escape hatch): `BROKK_ACP_WASI_MODULES`
//!     env var, value is `name1=/path/1.wasm:name2=/path/2.wasm` (`;`
//!     separator on Windows). Loaded at registry init.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use wasmtime::{Config, Engine, Linker, Module, Store};
use wasmtime_wasi::p1::WasiP1Ctx;
use wasmtime_wasi::p2::pipe::MemoryOutputPipe;
use wasmtime_wasi::{DirPerms, FilePerms, WasiCtxBuilder};

use super::sandbox::{ENV_WHITELIST, SandboxPolicy};

/// Env var used by operators to register additional WASI modules at startup.
/// Format: `name1=/abs/path/1.wasm<SEP>name2=/abs/path/2.wasm`, with `<SEP>`
/// being `:` on Unix and `;` on Windows (same convention as `PATH`). Empty
/// or absent means "no operator-supplied modules"; missing files log a
/// warning and are skipped (the framework still works for whatever did load).
pub const WASI_MODULES_ENV: &str = "BROKK_ACP_WASI_MODULES";

/// Per-call cap on each of stdout/stderr captured from the guest. Mirrors
/// `shell::MAX_OUTPUT_BYTES` so a chatty WASI tool can't OOM the agent.
const MEMORY_PIPE_CAPACITY: usize = 8 * 1024 * 1024;

// ===== Module registry =====================================================

/// One WASI module registered as a sandboxed shell tool.
#[derive(Clone, Debug)]
pub struct WasiModule {
    pub name: String,
    pub bytes: Arc<Vec<u8>>,
}

/// Read-only map from CLI name to registered WASI module. Populated once at
/// startup; lookups are O(1) by name.
pub struct WasiModuleRegistry {
    modules: HashMap<String, WasiModule>,
}

impl WasiModuleRegistry {
    pub fn empty() -> Self {
        Self {
            modules: HashMap::new(),
        }
    }

    pub fn register(&mut self, module: WasiModule) {
        self.modules.insert(module.name.clone(), module);
    }

    pub fn get(&self, name: &str) -> Option<&WasiModule> {
        self.modules.get(name)
    }

    pub fn is_empty(&self) -> bool {
        self.modules.is_empty()
    }

    /// Names sorted for deterministic UX (used by `WrapFailure::UnknownTool`
    /// when listing available tools to the LLM).
    pub fn names_sorted(&self) -> Vec<String> {
        let mut names: Vec<String> = self.modules.keys().cloned().collect();
        names.sort();
        names
    }
}

static GLOBAL_REGISTRY: OnceLock<WasiModuleRegistry> = OnceLock::new();

/// Build and install the global registry. Idempotent: subsequent calls are
/// no-ops so test setup and main() don't fight. Loads:
///   1. bundled modules (via `register_bundled` -- empty at framework-MVP);
///   2. operator-supplied modules via `BROKK_ACP_WASI_MODULES`.
pub fn init_global() {
    GLOBAL_REGISTRY.get_or_init(|| {
        let mut registry = WasiModuleRegistry::empty();
        register_bundled(&mut registry);
        register_from_env(&mut registry);
        if registry.is_empty() {
            tracing::info!(
                "WASI sandbox registry is empty; no modules are bundled or operator-supplied. \
                 Commands selected through the WASI backend will fall back to unsandboxed exec \
                 with a warning. Set BROKK_ACP_WASI_MODULES=name1=/abs/path/1.wasm to register \
                 modules, or upgrade brokk-acp once bundled tools ship in a follow-up release."
            );
        } else {
            tracing::info!(
                modules = ?registry.names_sorted(),
                "WASI sandbox registry initialized",
            );
        }
        registry
    });
}

/// Accessor for the global registry. `init_global` must have been called
/// at least once; if not (e.g. a unit test that bypassed startup), returns
/// an always-empty registry rather than panicking so a missed init can't
/// crash the agent.
pub fn global() -> &'static WasiModuleRegistry {
    static EMPTY: OnceLock<WasiModuleRegistry> = OnceLock::new();
    GLOBAL_REGISTRY
        .get()
        .unwrap_or_else(|| EMPTY.get_or_init(WasiModuleRegistry::empty))
}

/// Bundled modules registered at compile time. Empty in the framework-MVP.
/// Follow-up PRs add tools here one at a time after source vetting:
///
/// ```ignore
/// registry.register(WasiModule {
///     name: "cat".to_string(),
///     bytes: Arc::new(include_bytes!("../../assets/wasi/cat.wasm").to_vec()),
/// });
/// ```
fn register_bundled(_registry: &mut WasiModuleRegistry) {
    // Intentionally empty -- see module-level docs.
}

/// Parse `BROKK_ACP_WASI_MODULES` and load each entry. Errors per-entry
/// (missing file, bad format) log and skip rather than aborting startup,
/// matching the spirit of `bwrap`-not-found falling back to unsandboxed.
fn register_from_env(registry: &mut WasiModuleRegistry) {
    let raw = match std::env::var(WASI_MODULES_ENV) {
        Ok(s) if !s.trim().is_empty() => s,
        _ => return,
    };
    let sep = if cfg!(windows) { ';' } else { ':' };
    for entry in raw.split(sep) {
        let entry = entry.trim();
        if entry.is_empty() {
            continue;
        }
        let Some((name, path_str)) = entry.split_once('=') else {
            tracing::warn!(
                entry,
                "BROKK_ACP_WASI_MODULES entry is not name=path; skipping"
            );
            continue;
        };
        let name = name.trim();
        let path = PathBuf::from(path_str.trim());
        if name.is_empty() || !is_valid_tool_name(name) {
            tracing::warn!(
                name,
                "BROKK_ACP_WASI_MODULES entry has an invalid tool name; skipping"
            );
            continue;
        }
        let bytes = match std::fs::read(&path) {
            Ok(b) => b,
            Err(e) => {
                tracing::warn!(
                    name,
                    path = %path.display(),
                    %e,
                    "BROKK_ACP_WASI_MODULES entry cannot be read; skipping"
                );
                continue;
            }
        };
        registry.register(WasiModule {
            name: name.to_string(),
            bytes: Arc::new(bytes),
        });
    }
}

/// Tool name validation: ASCII letters/digits/`-_`, at least one char. Mirrors
/// the constraint LLMs already follow when emitting CLI tool names; rejects
/// e.g. `../evil` or shell metacharacters that the parser would have rejected
/// anyway, just earlier and with a clearer error.
fn is_valid_tool_name(name: &str) -> bool {
    !name.is_empty()
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.')
}

// ===== Command parsing =====================================================

/// Reasons `try_wrap` may decline to wrap a command. Surfaced verbatim to
/// `shell.rs` for the fallback warning so the operator can tell *why* a call
/// went unsandboxed (shell feature used, unknown tool, ...).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WrapFailure {
    Empty,
    UnsupportedChar(char),
    UnterminatedQuote(char),
    UnknownTool {
        tool: String,
        available: Vec<String>,
    },
}

impl std::fmt::Display for WrapFailure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            WrapFailure::Empty => write!(f, "empty command"),
            WrapFailure::UnsupportedChar(c) => {
                write!(
                    f,
                    "command uses shell feature {:?} which WASI cannot model",
                    c
                )
            }
            WrapFailure::UnterminatedQuote(q) => {
                write!(f, "unterminated {:?} quote in command", q)
            }
            WrapFailure::UnknownTool { tool, available } => {
                if available.is_empty() {
                    write!(
                        f,
                        "no WASI module is registered for {:?} (registry is empty -- bundle or \
                         supply modules via BROKK_ACP_WASI_MODULES)",
                        tool
                    )
                } else {
                    write!(
                        f,
                        "no WASI module is registered for {:?} (registered: {})",
                        tool,
                        available.join(", ")
                    )
                }
            }
        }
    }
}

/// Shell metacharacters that, *outside quotes*, change command semantics in
/// a real shell. Any of these makes the command not safe to dispatch as a
/// single argv to a WASI module. Kept conservative on purpose -- when in
/// doubt, fall back to unsandboxed exec, which is the same posture the
/// operator already accepted by selecting the WASI backend.
const FORBIDDEN_OUTSIDE_QUOTES: &[char] = &[
    '|', '>', '<', '&', ';', '$', '`', '*', '?', '[', ']', '(', ')', '{', '}', '~', '!', '\n',
];

/// Tokenize a command into argv, honoring single and double quotes
/// (literal contents in both -- no variable expansion, no backslash-X
/// escapes inside single quotes). Rejects any command that uses shell
/// features WASI cannot model. The first token is the tool name.
fn parse_simple_command(input: &str) -> Result<Vec<String>, WrapFailure> {
    let trimmed = input.trim();
    if trimmed.is_empty() {
        return Err(WrapFailure::Empty);
    }

    let mut tokens: Vec<String> = Vec::new();
    let mut current = String::new();
    let mut in_single = false;
    let mut in_double = false;
    let mut escaped = false;

    for ch in trimmed.chars() {
        if escaped {
            current.push(ch);
            escaped = false;
            continue;
        }
        if in_single {
            if ch == '\'' {
                in_single = false;
            } else {
                current.push(ch);
            }
            continue;
        }
        if in_double {
            match ch {
                '"' => in_double = false,
                '\\' => escaped = true,
                _ => current.push(ch),
            }
            continue;
        }
        if FORBIDDEN_OUTSIDE_QUOTES.contains(&ch) {
            return Err(WrapFailure::UnsupportedChar(ch));
        }
        match ch {
            '\'' => in_single = true,
            '"' => in_double = true,
            '\\' => escaped = true,
            ' ' | '\t' => {
                if !current.is_empty() {
                    tokens.push(std::mem::take(&mut current));
                }
            }
            _ => current.push(ch),
        }
    }

    if in_single {
        return Err(WrapFailure::UnterminatedQuote('\''));
    }
    if in_double {
        return Err(WrapFailure::UnterminatedQuote('"'));
    }
    if !current.is_empty() {
        tokens.push(current);
    }
    if tokens.is_empty() {
        return Err(WrapFailure::Empty);
    }
    Ok(tokens)
}

// ===== Wrap attempt + invocation type ======================================

/// A successfully parsed command ready to run inside wasmtime.
#[derive(Debug)]
pub struct WasiInvocation {
    pub module: WasiModule,
    pub args: Vec<String>,
    pub cwd: PathBuf,
    pub policy: SandboxPolicy,
}

/// Try to wrap `command` for in-process WASI execution. Returns:
///   - `Ok(invocation)` if parsing succeeded *and* the tool name resolves
///     to a registered module;
///   - `Err(WrapFailure)` otherwise; the caller falls back to unsandboxed
///     exec and surfaces the failure reason in the bypass warning.
pub fn try_wrap(
    registry: &WasiModuleRegistry,
    policy: SandboxPolicy,
    cwd: &Path,
    command: &str,
) -> Result<WasiInvocation, WrapFailure> {
    let tokens = parse_simple_command(command)?;
    let mut iter = tokens.into_iter();
    let tool = iter.next().ok_or(WrapFailure::Empty)?;
    let args: Vec<String> = iter.collect();

    let Some(module) = registry.get(&tool) else {
        return Err(WrapFailure::UnknownTool {
            tool,
            available: registry.names_sorted(),
        });
    };

    Ok(WasiInvocation {
        module: module.clone(),
        args,
        cwd: cwd.to_path_buf(),
        policy,
    })
}

// ===== Runtime =============================================================

/// Stdout/stderr capture + exit status from one WASI invocation. Shaped to
/// be a drop-in for the `std::process::Output` the rest of `shell.rs`
/// already handles.
pub struct WasiResult {
    pub stdout: Vec<u8>,
    pub stderr: Vec<u8>,
    pub exit_code: i32,
    pub timed_out: bool,
}

/// Build a fresh wasmtime engine.
///
/// **Why per-call, not shared:** `Engine::increment_epoch` is a process-wide
/// counter on the engine. Two concurrent sessions sharing one engine would
/// race -- session A's timeout timer would bump the epoch and trap session
/// B's store as collateral. A per-call engine isolates each timeout. The
/// Cranelift init cost is non-trivial (~5-15 ms on a warm system) but
/// well below the shell-call latency budget agents already accept (~tens
/// of ms minimum for fork+exec on Unix); correctness wins. Revisit only
/// if shell-call throughput becomes the bottleneck for the WASI path.
fn fresh_engine() -> std::io::Result<Engine> {
    let mut config = Config::new();
    config.epoch_interruption(true);
    // Sync execution is the default in wasmtime 44; the older
    // `async_support(false)` knob is deprecated and a no-op now. We
    // drive everything from a tokio `spawn_blocking` and use epoch
    // interruption for timeout enforcement.
    Engine::new(&config).map_err(|e| io_err(format!("wasmtime engine init: {e}")))
}

/// Run a WASI invocation in-process, with a wall-clock timeout enforced
/// via wasmtime's epoch interruption. Returns once the guest exits, traps,
/// or hits the deadline.
pub async fn run(invocation: WasiInvocation, timeout: Duration) -> std::io::Result<WasiResult> {
    let engine = fresh_engine()?;
    let engine_for_timer = engine.clone();

    // Bump the engine's epoch after the deadline; the active store traps
    // at its next yield point. Aborted if the call finishes early.
    let timer = tokio::spawn(async move {
        tokio::time::sleep(timeout).await;
        engine_for_timer.increment_epoch();
    });

    let blocking = tokio::task::spawn_blocking(move || run_blocking(engine, invocation));
    let result = blocking
        .await
        .map_err(|e| std::io::Error::other(format!("wasi blocking task panicked: {e}")))?;
    timer.abort();
    result
}

fn run_blocking(engine: Engine, invocation: WasiInvocation) -> std::io::Result<WasiResult> {
    let module = Module::new(&engine, &invocation.module.bytes[..])
        .map_err(|e| io_err(format!("compile {}: {e}", invocation.module.name)))?;

    let mut linker: Linker<WasiP1Ctx> = Linker::new(&engine);
    wasmtime_wasi::p1::add_to_linker_sync(&mut linker, |ctx| ctx)
        .map_err(|e| io_err(format!("add WASIp1 to linker: {e}")))?;

    let stdout_pipe = MemoryOutputPipe::new(MEMORY_PIPE_CAPACITY);
    let stderr_pipe = MemoryOutputPipe::new(MEMORY_PIPE_CAPACITY);

    let mut argv = Vec::with_capacity(1 + invocation.args.len());
    argv.push(invocation.module.name.clone());
    argv.extend(invocation.args.iter().cloned());

    let mut builder = WasiCtxBuilder::new();
    builder
        .args(&argv)
        .stdout(stdout_pipe.clone())
        .stderr(stderr_pipe.clone())
        .env("TERM", "dumb");

    // Whitelisted env vars only -- matches the bwrap path's `--clearenv`
    // + explicit `--setenv` pattern so secrets in the parent process
    // (OPENAI_API_KEY, AWS_*, GH tokens, LD_PRELOAD/DYLD_*) cannot leak
    // into the guest.
    for key in ENV_WHITELIST {
        if let Some(val) = std::env::var_os(key).and_then(|v| v.into_string().ok()) {
            builder.env(*key, &val);
        }
    }

    let (dir_perms, file_perms) = if invocation.policy.allows_workspace_writes() {
        (DirPerms::all(), FilePerms::all())
    } else {
        (DirPerms::READ, FilePerms::READ)
    };
    builder
        .preopened_dir(&invocation.cwd, "/cwd", dir_perms, file_perms)
        .map_err(|e| io_err(format!("preopen {}: {e}", invocation.cwd.display())))?;

    let wasi_ctx: WasiP1Ctx = builder.build_p1();
    let mut store = Store::new(&engine, wasi_ctx);
    // One epoch bump => trap; the timer task in `run` bumps once after
    // the timeout window, which is the only way the guest can be torn
    // down deterministically (we cannot kill a blocking thread otherwise).
    store.set_epoch_deadline(1);

    let instance = linker
        .instantiate(&mut store, &module)
        .map_err(|e| io_err(format!("instantiate {}: {e}", invocation.module.name)))?;
    let start = instance
        .get_typed_func::<(), ()>(&mut store, "_start")
        .map_err(|e| io_err(format!("{} missing _start: {e}", invocation.module.name)))?;

    let call_result = start.call(&mut store, ());
    drop(store);

    let (exit_code, timed_out) = match call_result {
        Ok(()) => (0, false),
        Err(err) => classify_trap(&err),
    };

    Ok(WasiResult {
        stdout: stdout_pipe.contents().to_vec(),
        stderr: stderr_pipe.contents().to_vec(),
        exit_code,
        timed_out,
    })
}

/// Map a wasmtime error to `(exit_code, timed_out)`. `proc_exit(n)` shows
/// up as `I32Exit(n)` and is the normal-termination path; `Trap::Interrupt`
/// is the epoch-deadline path (treated as a timeout, exit 124 to mirror
/// `timeout(1)`). Anything else is a guest crash (exit 1, no timeout).
///
/// `wasmtime::Error` in v44 is a distinct type from `anyhow::Error` but
/// exposes the same `downcast_ref` surface, so the classification is
/// otherwise identical.
fn classify_trap(err: &wasmtime::Error) -> (i32, bool) {
    if let Some(exit) = err.downcast_ref::<wasmtime_wasi::I32Exit>() {
        return (exit.0, false);
    }
    if let Some(trap) = err.downcast_ref::<wasmtime::Trap>()
        && matches!(trap, wasmtime::Trap::Interrupt)
    {
        return (124, true);
    }
    (1, false)
}

fn io_err(msg: String) -> std::io::Error {
    std::io::Error::other(format!("[wasi-sandbox] {msg}"))
}

// ===== Tests ===============================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_rejects_empty() {
        assert_eq!(parse_simple_command(""), Err(WrapFailure::Empty));
        assert_eq!(parse_simple_command("   "), Err(WrapFailure::Empty));
        assert_eq!(parse_simple_command("\t\n"), Err(WrapFailure::Empty));
    }

    #[test]
    fn parse_simple_argv() {
        assert_eq!(
            parse_simple_command("cat foo bar").unwrap(),
            vec!["cat", "foo", "bar"]
        );
    }

    #[test]
    fn parse_quoted_args_preserve_spaces() {
        assert_eq!(
            parse_simple_command("rg 'hello world' src").unwrap(),
            vec!["rg", "hello world", "src"]
        );
        assert_eq!(
            parse_simple_command(r#"rg "hello world" src"#).unwrap(),
            vec!["rg", "hello world", "src"]
        );
    }

    #[test]
    fn parse_rejects_pipeline() {
        assert_eq!(
            parse_simple_command("cat foo | head"),
            Err(WrapFailure::UnsupportedChar('|'))
        );
    }

    #[test]
    fn parse_rejects_redirect() {
        assert_eq!(
            parse_simple_command("cat foo > out"),
            Err(WrapFailure::UnsupportedChar('>'))
        );
        assert_eq!(
            parse_simple_command("rg pattern < input"),
            Err(WrapFailure::UnsupportedChar('<'))
        );
    }

    #[test]
    fn parse_rejects_command_substitution() {
        assert_eq!(
            parse_simple_command("cat $(echo foo)"),
            Err(WrapFailure::UnsupportedChar('$'))
        );
        assert_eq!(
            parse_simple_command("cat `echo foo`"),
            Err(WrapFailure::UnsupportedChar('`'))
        );
    }

    #[test]
    fn parse_rejects_variable_expansion() {
        assert_eq!(
            parse_simple_command("cat $HOME/foo"),
            Err(WrapFailure::UnsupportedChar('$'))
        );
    }

    #[test]
    fn parse_rejects_tilde_and_globs() {
        assert_eq!(
            parse_simple_command("ls ~/foo"),
            Err(WrapFailure::UnsupportedChar('~'))
        );
        assert_eq!(
            parse_simple_command("ls *.rs"),
            Err(WrapFailure::UnsupportedChar('*'))
        );
        assert_eq!(
            parse_simple_command("ls foo?bar"),
            Err(WrapFailure::UnsupportedChar('?'))
        );
    }

    #[test]
    fn parse_rejects_unterminated_quotes() {
        assert_eq!(
            parse_simple_command("cat 'foo"),
            Err(WrapFailure::UnterminatedQuote('\''))
        );
        assert_eq!(
            parse_simple_command(r#"cat "foo"#),
            Err(WrapFailure::UnterminatedQuote('"'))
        );
    }

    #[test]
    fn parse_allows_metachars_inside_quotes() {
        // Shell features *inside* quotes are literal text to the WASI
        // tool. `rg '|'` is a search for a pipe character, not a pipeline.
        assert_eq!(
            parse_simple_command("rg '|' file").unwrap(),
            vec!["rg", "|", "file"]
        );
        assert_eq!(
            parse_simple_command(r#"rg "$HOME" file"#).unwrap(),
            vec!["rg", "$HOME", "file"]
        );
    }

    #[test]
    fn try_wrap_reports_unknown_tool_with_available_list() {
        let mut registry = WasiModuleRegistry::empty();
        registry.register(WasiModule {
            name: "cat".to_string(),
            bytes: Arc::new(vec![]),
        });
        registry.register(WasiModule {
            name: "rg".to_string(),
            bytes: Arc::new(vec![]),
        });
        let err = try_wrap(
            &registry,
            SandboxPolicy::ReadOnly,
            Path::new("/tmp"),
            "fd pattern",
        )
        .unwrap_err();
        match err {
            WrapFailure::UnknownTool { tool, available } => {
                assert_eq!(tool, "fd");
                assert_eq!(available, vec!["cat".to_string(), "rg".to_string()]);
            }
            other => panic!("expected UnknownTool, got {other:?}"),
        }
    }

    #[test]
    fn try_wrap_returns_invocation_for_registered_tool() {
        let mut registry = WasiModuleRegistry::empty();
        registry.register(WasiModule {
            name: "cat".to_string(),
            bytes: Arc::new(vec![1, 2, 3]),
        });
        let inv = try_wrap(
            &registry,
            SandboxPolicy::WorkspaceWrite,
            Path::new("/tmp"),
            "cat foo bar",
        )
        .expect("must wrap");
        assert_eq!(inv.module.name, "cat");
        assert_eq!(inv.args, vec!["foo", "bar"]);
        assert_eq!(inv.cwd, Path::new("/tmp"));
        assert_eq!(inv.policy, SandboxPolicy::WorkspaceWrite);
    }

    #[test]
    fn is_valid_tool_name_accepts_typical_cli_names() {
        for ok in ["cat", "rg", "ripgrep", "head_2", "tool-name", "v1.2"] {
            assert!(is_valid_tool_name(ok), "expected {ok:?} to be valid");
        }
    }

    #[test]
    fn is_valid_tool_name_rejects_path_and_meta() {
        for bad in ["", "../evil", "cat foo", "a|b", "a;b", "a/b"] {
            assert!(!is_valid_tool_name(bad), "expected {bad:?} to be invalid");
        }
    }

    #[test]
    fn registry_names_sorted_returns_sorted() {
        let mut r = WasiModuleRegistry::empty();
        for n in ["rg", "cat", "fd"] {
            r.register(WasiModule {
                name: n.to_string(),
                bytes: Arc::new(vec![]),
            });
        }
        assert_eq!(r.names_sorted(), vec!["cat", "fd", "rg"]);
    }

    #[test]
    fn wrap_failure_display_lists_available_when_empty() {
        let f = WrapFailure::UnknownTool {
            tool: "fd".to_string(),
            available: vec![],
        };
        let msg = format!("{f}");
        assert!(msg.contains("fd"));
        assert!(msg.contains("BROKK_ACP_WASI_MODULES"));
    }

    #[test]
    fn wrap_failure_display_lists_available_when_populated() {
        let f = WrapFailure::UnknownTool {
            tool: "fd".to_string(),
            available: vec!["cat".to_string(), "rg".to_string()],
        };
        let msg = format!("{f}");
        assert!(msg.contains("cat"));
        assert!(msg.contains("rg"));
    }
}
