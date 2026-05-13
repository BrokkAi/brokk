//! Dispatch layer between the native parsing fallback and the
//! wasmtime-hosted sandbox.
//!
//! `SandboxBackend::Native` calls into `brokk_acp_sandbox` directly --
//! same process, full speed, but a panic or pathological input in a
//! third-party parser (YAML bomb, ReDoS, etc.) takes down the agent.
//!
//! `SandboxBackend::Wasm` spawns a wasmtime store running the embedded
//! `brokk-acp-sandbox.wasm` artifact, exchanges newline-delimited JSON
//! over the wasm module's stdin/stdout, and tears the store down after
//! each batch. The host imposes a per-call fuel budget (CPU bound), a
//! memory cap, and no preopens beyond stdio so the parser cannot touch
//! the filesystem even if it tries.
//!
//! Switching between the two is controlled by `--no-wasm-sandbox` /
//! `BROKK_ACP_NO_WASM_SANDBOX` at startup; the rest of the codebase
//! sees only the trait object.
//!
//! Failure policy: the wasm backend treats sandbox infrastructure
//! errors (wasmtime instantiation failure, fuel exhaustion, panic
//! propagated from the guest) as parse errors with a clear `[sandbox]`
//! prefix so the caller's existing error path lights up. We do not
//! transparently fall back to the native parser on sandbox failure --
//! that would defeat the point of having a sandbox at all.

use std::io::{BufRead, BufReader};
use std::sync::Arc;
use std::sync::Mutex;
use std::sync::OnceLock;

use anyhow::{Context, Result, anyhow};
use brokk_acp_sandbox::ParsedFrontmatter;
use serde::{Deserialize, Serialize};

/// Process-wide singleton, initialized once in `main` from CLI flags
/// and read by everything that needs to parse untrusted input
/// (`skills`, `agents_md`, `session`, `tools/filesystem`). We keep a
/// global rather than threading the backend through every Session /
/// SessionStore / ToolRegistry constructor because (a) wasmtime
/// Engines and Components are designed to be shared across threads
/// and stores, (b) there is no legitimate reason for two parts of the
/// same process to disagree about whether the sandbox is enabled,
/// and (c) avoiding the global would touch dozens of constructors.
static GLOBAL: OnceLock<SandboxBackend> = OnceLock::new();

/// Install the process-wide backend. Called once from `main` before
/// any session is created. Subsequent calls are a no-op (the first
/// install wins) so a test harness can pre-install a native backend
/// without `main` having to be threaded through it.
pub fn install_global(backend: SandboxBackend) {
    let _ = GLOBAL.set(backend);
}

/// Access the global backend. Falls back to `Native` if `install_global`
/// was never called (unit tests, `cargo run --example`, etc.). The
/// fallback is deliberately silent: callers that need the wasm path
/// to have actually started should check the install path in `main`.
pub fn global() -> &'static SandboxBackend {
    GLOBAL.get_or_init(|| SandboxBackend::Native)
}

/// Single entry point for parsers in this crate. Pick `Native` for
/// raw speed, `Wasm` for isolated execution.
#[derive(Clone)]
pub enum SandboxBackend {
    Native,
    Wasm(Arc<WasmSandbox>),
}

impl SandboxBackend {
    /// Construct the wasm backend, embedding the bytes produced by
    /// `build.rs`. Returns the native fallback (with a logged warning)
    /// if wasmtime initialization fails; this keeps a misconfigured
    /// host from making the agent unusable.
    pub fn wasm_or_native() -> Self {
        match WasmSandbox::new() {
            Ok(w) => Self::Wasm(Arc::new(w)),
            Err(err) => {
                tracing::warn!(
                    %err,
                    "wasm sandbox failed to initialize; falling back to native parsers \
                     (lose memory/crash/CPU isolation). Set BROKK_ACP_NO_WASM_SANDBOX=1 to \
                     silence this warning if running natively is intentional."
                );
                Self::Native
            }
        }
    }

    /// Parse the YAML frontmatter of a SKILL.md. Native dispatch is a
    /// direct library call; wasm dispatch sends one JSON-RPC request.
    pub fn parse_skill_frontmatter(&self, yaml: &str) -> Result<ParsedFrontmatter, String> {
        match self {
            Self::Native => brokk_acp_sandbox::parse_frontmatter(yaml),
            Self::Wasm(w) => w.parse_skill_frontmatter(yaml).map_err(|e| e.to_string()),
        }
    }

    /// Read a file as a UTF-8 string with a host-imposed byte cap.
    /// `path` must be absolute on every platform (callers in this
    /// crate canonicalize before calling).
    ///
    /// Native dispatch checks `fs::metadata().len()` before issuing
    /// `read_to_string`, so a multi-gigabyte file fails fast with
    /// `FileTooLarge` instead of OOM-ing the agent.
    ///
    /// Wasm dispatch preopens `path.parent()` read-only, invokes the
    /// guest with the leaf name, and lets the guest enforce both the
    /// pre-read size cap and the wasm linear-memory limit. The host
    /// never sees the bytes if either limit trips.
    ///
    /// `None` is returned for `NotFound` / `not a regular file` so
    /// callers can keep their "missing is normal" code path; all
    /// other errors are returned verbatim.
    pub fn read_file_bounded(
        &self,
        path: &std::path::Path,
        max_bytes: u64,
    ) -> std::io::Result<Option<String>> {
        match self {
            Self::Native => read_file_bounded_native(path, max_bytes),
            Self::Wasm(w) => w.read_file_bounded(path, max_bytes),
        }
    }

    /// Read a single text entry out of a zip archive, with separate
    /// caps for the archive size on disk and the decompressed entry
    /// size. This is the load-bearing primitive for `session.rs`
    /// readers: session zips are untrusted bytes on disk, and a
    /// crafted archive (zip bomb, malformed header, lying central
    /// directory) must not be able to OOM or panic the agent.
    ///
    /// Native dispatch reuses the `zip` crate directly with bounded
    /// reads. Wasm dispatch hands the archive bytes to the in-sandbox
    /// minimal zip parser (`brokk_acp_sandbox::zip_reader`), so the
    /// host process never decompresses the input.
    ///
    /// `Ok(None)` means "archive exists but the named entry is not in
    /// it" -- callers can keep their existing missing-entry path.
    pub fn read_zip_entry_text(
        &self,
        zip_path: &std::path::Path,
        entry_name: &str,
        max_archive_bytes: u64,
        max_entry_bytes: u64,
    ) -> std::io::Result<Option<String>> {
        match self {
            Self::Native => {
                read_zip_entry_text_native(zip_path, entry_name, max_archive_bytes, max_entry_bytes)
            }
            Self::Wasm(w) => {
                w.read_zip_entry_text(zip_path, entry_name, max_archive_bytes, max_entry_bytes)
            }
        }
    }

    /// Bulk variant: read every text entry whose name starts with
    /// `prefix` from a zip archive. The history reader uses this to
    /// fetch `content/*.txt` in one round-trip instead of paying the
    /// wasm boot cost per turn. `max_total_bytes` is the budget over
    /// the sum of decompressed payloads; a swarm of small bombs
    /// cannot collectively exceed it.
    pub fn read_zip_entries_with_prefix(
        &self,
        zip_path: &std::path::Path,
        prefix: &str,
        max_archive_bytes: u64,
        max_entry_bytes: u64,
        max_total_bytes: u64,
    ) -> std::io::Result<std::collections::HashMap<String, String>> {
        match self {
            Self::Native => read_zip_entries_with_prefix_native(
                zip_path,
                prefix,
                max_archive_bytes,
                max_entry_bytes,
                max_total_bytes,
            ),
            Self::Wasm(w) => w.read_zip_entries_with_prefix(
                zip_path,
                prefix,
                max_archive_bytes,
                max_entry_bytes,
                max_total_bytes,
            ),
        }
    }

    /// Run `searchFileContents` (regex match across a directory tree)
    /// through the sandbox. The user-controlled regex pattern is the
    /// load-bearing threat: the `regex` crate is linear-time by
    /// design, but the wasm fuel cap + linear-memory limit are a
    /// second line of defense in case a future engine bug or feature
    /// flag breaks the linear-time guarantee.
    ///
    /// `glob` follows the same simple-glob syntax (`*.rs`, `**/*.java`)
    /// as the host's prior implementation; an invalid pattern is
    /// returned as a `SearchError::InvalidGlob` for the caller to
    /// surface to the LLM.
    pub fn search_file_contents(
        &self,
        root: &std::path::Path,
        pattern: &str,
        glob: Option<&str>,
        max_results: u64,
        max_file_bytes: u64,
        max_total_bytes: u64,
    ) -> Result<brokk_acp_sandbox::SearchOutcome, brokk_acp_sandbox::SearchError> {
        match self {
            Self::Native => brokk_acp_sandbox::search_file_contents(
                root,
                pattern,
                glob,
                max_results as usize,
                max_file_bytes,
                max_total_bytes,
            ),
            Self::Wasm(w) => w.search_file_contents(
                root,
                pattern,
                glob,
                max_results,
                max_file_bytes,
                max_total_bytes,
            ),
        }
    }
}

/// Native implementation of `read_zip_entry_text`. Reads the whole
/// archive into memory first (bounded by `max_archive_bytes`) and
/// then uses the same minimal parser that ships in the wasm sandbox.
/// Going through the shared parser keeps the failure modes identical
/// across backends so the `native_and_wasm_agree_on_...` parity test
/// can pin the contract.
fn read_zip_entry_text_native(
    zip_path: &std::path::Path,
    entry_name: &str,
    max_archive_bytes: u64,
    max_entry_bytes: u64,
) -> std::io::Result<Option<String>> {
    let bytes = match read_archive_bounded_native(zip_path, max_archive_bytes)? {
        Some(b) => b,
        None => return Ok(None),
    };
    brokk_acp_sandbox::read_zip_entry_text(&bytes, entry_name, max_entry_bytes)
        .map_err(|e| std::io::Error::other(format!("{e}")))
}

fn read_zip_entries_with_prefix_native(
    zip_path: &std::path::Path,
    prefix: &str,
    max_archive_bytes: u64,
    max_entry_bytes: u64,
    max_total_bytes: u64,
) -> std::io::Result<std::collections::HashMap<String, String>> {
    let bytes = match read_archive_bounded_native(zip_path, max_archive_bytes)? {
        Some(b) => b,
        None => return Ok(std::collections::HashMap::new()),
    };
    brokk_acp_sandbox::read_zip_entries_with_prefix(
        &bytes,
        prefix,
        max_entry_bytes,
        max_total_bytes,
    )
    .map_err(|e| std::io::Error::other(format!("{e}")))
}

/// Shared helper: read the archive bytes off disk with a size cap.
/// Returns `None` if the path is missing or not a regular file so
/// callers can keep their "no zip here" path.
fn read_archive_bounded_native(
    zip_path: &std::path::Path,
    max_archive_bytes: u64,
) -> std::io::Result<Option<Vec<u8>>> {
    use std::io::ErrorKind;
    let meta = match std::fs::metadata(zip_path) {
        Ok(m) => m,
        Err(e) if e.kind() == ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(e),
    };
    if !meta.is_file() {
        return Ok(None);
    }
    if meta.len() > max_archive_bytes {
        return Err(std::io::Error::new(
            ErrorKind::FileTooLarge,
            format!(
                "{} archive is {} bytes, exceeds cap of {max_archive_bytes}",
                zip_path.display(),
                meta.len()
            ),
        ));
    }
    Ok(Some(std::fs::read(zip_path)?))
}

/// Native implementation of `read_file_bounded`. Returns `Ok(None)`
/// when the path is missing or not a regular file (callers treat that
/// as "no file here, move on"); propagates `FileTooLarge` and other
/// errors so the caller can log and skip.
fn read_file_bounded_native(
    path: &std::path::Path,
    max_bytes: u64,
) -> std::io::Result<Option<String>> {
    let meta = match std::fs::metadata(path) {
        Ok(m) => m,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(e),
    };
    if !meta.is_file() {
        return Ok(None);
    }
    if meta.len() > max_bytes {
        return Err(std::io::Error::new(
            std::io::ErrorKind::FileTooLarge,
            format!(
                "{} is {} bytes, exceeds cap of {max_bytes}",
                path.display(),
                meta.len()
            ),
        ));
    }
    Ok(Some(std::fs::read_to_string(path)?))
}

// ---------------------------------------------------------------------------
// Wasm backend
// ---------------------------------------------------------------------------

/// Bytes of the wasm artifact, produced by `build.rs` running
/// `cargo build --target wasm32-wasip2` on the `brokk-acp-sandbox`
/// crate. Embedded so the host binary is self-contained -- no extra
/// file to install, no version skew between binary and wasm.
const SANDBOX_WASM: &[u8] = include_bytes!(env!("BROKK_ACP_SANDBOX_WASM"));

/// Fuel budget per single parse request. Cranelift's "fuel" is roughly
/// "wasm instructions" -- 50M is generous for a YAML parse on a
/// reasonably sized SKILL.md (<10KB) but small enough to cap a
/// billion-laughs YAML bomb in milliseconds.
const FUEL_PER_REQUEST: u64 = 50_000_000;

/// Memory cap in bytes for the sandboxed module. Generous enough to
/// hold a SKILL.md plus parser intermediates, snug enough that an
/// expansion attack fails fast.
const MEMORY_LIMIT_BYTES: usize = 64 * 1024 * 1024;

/// Wasmtime-backed sandbox. Holds a precompiled component and serves
/// requests sequentially through a single guest instance per call --
/// each parse boots a fresh wasm store so memory state cannot leak
/// across requests. Boot cost is amortized by `Module::serialize`
/// caching in wasmtime's on-disk cache.
pub struct WasmSandbox {
    engine: wasmtime::Engine,
    component: wasmtime::component::Component,
    next_id: Mutex<u64>,
}

impl WasmSandbox {
    fn new() -> Result<Self> {
        let mut config = wasmtime::Config::new();
        config.consume_fuel(true);
        config.async_support(false);
        config.wasm_component_model(true);

        let engine = wasmtime::Engine::new(&config).context("creating wasmtime engine")?;
        let component = wasmtime::component::Component::from_binary(&engine, SANDBOX_WASM)
            .context("loading sandbox wasm component")?;

        Ok(Self {
            engine,
            component,
            next_id: Mutex::new(1),
        })
    }

    fn next_id(&self) -> u64 {
        let mut g = self.next_id.lock().expect("sandbox id counter poisoned");
        let id = *g;
        *g = g.checked_add(1).unwrap_or(1);
        id
    }

    /// Read `path` through the sandbox: preopen its parent dir, ask
    /// the guest to read just the basename, return the contents (or
    /// `None` if the file is missing). The sandbox enforces both the
    /// pre-read size cap and the wasm linear-memory limit, so a
    /// pathological huge file cannot OOM the host.
    fn read_file_bounded(
        &self,
        path: &std::path::Path,
        max_bytes: u64,
    ) -> std::io::Result<Option<String>> {
        let parent = match path.parent() {
            Some(p) if !p.as_os_str().is_empty() => p,
            _ => {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidInput,
                    format!("path {} has no parent directory", path.display()),
                ));
            }
        };
        let file_name = match path.file_name().and_then(|s| s.to_str()) {
            Some(n) => n.to_string(),
            None => {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidInput,
                    format!(
                        "path {} has no UTF-8 basename (wasi cannot route non-UTF-8 paths)",
                        path.display()
                    ),
                ));
            }
        };
        // The guest sees the parent dir mounted at `/d`; constructing
        // `/d/<basename>` keeps the request encoding trivial and never
        // exposes the host absolute path to the guest.
        let guest_path = format!("/d/{file_name}");
        let id = self.next_id();
        let req = SandboxRequest {
            id,
            kind: SandboxRequestKind::ReadFileBounded(ReadFileBoundedParams {
                guest_path,
                max_bytes,
            }),
        };
        let resp: SandboxResponse<ReadFileResult> = self
            .round_trip(&req, Some((parent, "/d")))
            .map_err(|e| std::io::Error::other(format!("[sandbox] {e}")))?;
        if resp.id != id {
            return Err(std::io::Error::other(format!(
                "[sandbox] response id mismatch: sent {id}, got {}",
                resp.id
            )));
        }
        match resp.body {
            SandboxBody::Ok(r) => Ok(Some(r.content)),
            SandboxBody::Err(msg) => {
                // Mirror the native fallback: a missing file is `Ok(None)`
                // so callers do not have to special-case "no file here".
                if msg.contains("No such file or directory")
                    || msg.contains("file not found")
                    || msg.contains("ENOENT")
                {
                    return Ok(None);
                }
                if msg.contains("exceeds cap of") {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::FileTooLarge,
                        format!("[sandbox] {msg}"),
                    ));
                }
                Err(std::io::Error::other(format!("[sandbox] {msg}")))
            }
        }
    }

    /// Run `searchFileContents` through the sandbox. Preopen the
    /// search root read-only, ask the guest to walk it and apply the
    /// pattern, return the structured outcome. The guest's wasm
    /// memory cap and fuel budget bound the worst case for a
    /// pathological regex or a huge tree.
    fn search_file_contents(
        &self,
        root: &std::path::Path,
        pattern: &str,
        glob: Option<&str>,
        max_results: u64,
        max_file_bytes: u64,
        max_total_bytes: u64,
    ) -> Result<brokk_acp_sandbox::SearchOutcome, brokk_acp_sandbox::SearchError> {
        let id = self.next_id();
        let req = SandboxRequest {
            id,
            kind: SandboxRequestKind::SearchFileContents(SearchFileContentsParams {
                guest_root: "/d".to_string(),
                pattern: pattern.to_string(),
                glob: glob.map(|g| g.to_string()),
                max_results,
                max_file_bytes,
                max_total_bytes,
            }),
        };
        let resp: SandboxResponse<brokk_acp_sandbox::SearchOutcome> =
            match self.round_trip(&req, Some((root, "/d"))) {
                Ok(r) => r,
                Err(e) => {
                    return Err(brokk_acp_sandbox::SearchError::Walk(format!(
                        "[sandbox] {e}"
                    )));
                }
            };
        if resp.id != id {
            return Err(brokk_acp_sandbox::SearchError::Walk(format!(
                "[sandbox] response id mismatch: sent {id}, got {}",
                resp.id
            )));
        }
        match resp.body {
            SandboxBody::Ok(outcome) => Ok(outcome),
            SandboxBody::Err(msg) => {
                // Surface the original kind back to the caller so the
                // LLM still sees "Invalid regex: ..." rather than a
                // generic sandbox-wrapped error. Best effort: match
                // the display strings produced by `SearchError`.
                if let Some(rest) = msg.strip_prefix("Invalid regex: ") {
                    Err(brokk_acp_sandbox::SearchError::InvalidRegex(rest.to_string()))
                } else if let Some(rest) = msg.strip_prefix("Invalid glob: ") {
                    Err(brokk_acp_sandbox::SearchError::InvalidGlob(rest.to_string()))
                } else {
                    Err(brokk_acp_sandbox::SearchError::Walk(format!(
                        "[sandbox] {msg}"
                    )))
                }
            }
        }
    }

    /// Bulk prefix variant of `read_zip_entry_text`. One sandbox boot
    /// returns every matching entry as a name-to-contents map, so the
    /// history reader pays the wasm overhead once for the whole
    /// `content/` set instead of N times.
    fn read_zip_entries_with_prefix(
        &self,
        zip_path: &std::path::Path,
        prefix: &str,
        max_archive_bytes: u64,
        max_entry_bytes: u64,
        max_total_bytes: u64,
    ) -> std::io::Result<std::collections::HashMap<String, String>> {
        let parent = match zip_path.parent() {
            Some(p) if !p.as_os_str().is_empty() => p,
            _ => {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidInput,
                    format!("zip path {} has no parent directory", zip_path.display()),
                ));
            }
        };
        let file_name = match zip_path.file_name().and_then(|s| s.to_str()) {
            Some(n) => n.to_string(),
            None => {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidInput,
                    format!("zip path {} has no UTF-8 basename", zip_path.display()),
                ));
            }
        };
        let guest_path = format!("/d/{file_name}");
        let id = self.next_id();
        let req = SandboxRequest {
            id,
            kind: SandboxRequestKind::ReadZipEntriesWithPrefix(ReadZipEntriesWithPrefixParams {
                guest_path,
                prefix: prefix.to_string(),
                max_archive_bytes,
                max_entry_bytes,
                max_total_bytes,
            }),
        };
        let resp: SandboxResponse<ReadEntriesResult> = self
            .round_trip(&req, Some((parent, "/d")))
            .map_err(|e| std::io::Error::other(format!("[sandbox] {e}")))?;
        if resp.id != id {
            return Err(std::io::Error::other(format!(
                "[sandbox] response id mismatch: sent {id}, got {}",
                resp.id
            )));
        }
        match resp.body {
            SandboxBody::Ok(r) => Ok(r.entries),
            SandboxBody::Err(msg) => {
                if msg.contains("No such file or directory")
                    || msg.contains("file not found")
                    || msg.contains("ENOENT")
                {
                    return Ok(std::collections::HashMap::new());
                }
                if msg.contains("exceeds cap of") {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::FileTooLarge,
                        format!("[sandbox] {msg}"),
                    ));
                }
                Err(std::io::Error::other(format!("[sandbox] {msg}")))
            }
        }
    }

    /// Read a single text entry out of a zip archive through the
    /// sandbox. Mirrors `read_file_bounded`: preopen the parent dir
    /// of the archive read-only, ask the guest to open the archive
    /// at its leaf name, decompress only the requested entry inside
    /// the wasm memory limit.
    fn read_zip_entry_text(
        &self,
        zip_path: &std::path::Path,
        entry_name: &str,
        max_archive_bytes: u64,
        max_entry_bytes: u64,
    ) -> std::io::Result<Option<String>> {
        let parent = match zip_path.parent() {
            Some(p) if !p.as_os_str().is_empty() => p,
            _ => {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidInput,
                    format!("zip path {} has no parent directory", zip_path.display()),
                ));
            }
        };
        let file_name = match zip_path.file_name().and_then(|s| s.to_str()) {
            Some(n) => n.to_string(),
            None => {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidInput,
                    format!("zip path {} has no UTF-8 basename", zip_path.display()),
                ));
            }
        };
        let guest_path = format!("/d/{file_name}");
        let id = self.next_id();
        let req = SandboxRequest {
            id,
            kind: SandboxRequestKind::ReadZipEntryText(ReadZipEntryTextParams {
                guest_path,
                entry_name: entry_name.to_string(),
                max_archive_bytes,
                max_entry_bytes,
            }),
        };
        let resp: SandboxResponse<ReadFileResult> = self
            .round_trip(&req, Some((parent, "/d")))
            .map_err(|e| std::io::Error::other(format!("[sandbox] {e}")))?;
        if resp.id != id {
            return Err(std::io::Error::other(format!(
                "[sandbox] response id mismatch: sent {id}, got {}",
                resp.id
            )));
        }
        match resp.body {
            SandboxBody::Ok(r) => Ok(Some(r.content)),
            SandboxBody::Err(msg) => {
                // The guest sends this sentinel for "archive exists,
                // entry not present" so we can keep parity with the
                // native fallback's `Ok(None)` return path.
                if msg == "zip entry not found" {
                    return Ok(None);
                }
                if msg.contains("No such file or directory")
                    || msg.contains("file not found")
                    || msg.contains("ENOENT")
                {
                    return Ok(None);
                }
                if msg.contains("exceeds cap of") {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::FileTooLarge,
                        format!("[sandbox] {msg}"),
                    ));
                }
                Err(std::io::Error::other(format!("[sandbox] {msg}")))
            }
        }
    }

    /// Run one round-trip through a freshly-instantiated sandbox: send
    /// a JSON request line on stdin, read one JSON response line back
    /// from stdout, tear the store down.
    fn parse_skill_frontmatter(&self, yaml: &str) -> Result<ParsedFrontmatter> {
        let id = self.next_id();
        let req = SandboxRequest {
            id,
            kind: SandboxRequestKind::ParseSkillFrontmatter(ParseSkillFrontmatterParams {
                yaml: yaml.to_string(),
            }),
        };
        let resp: SandboxResponse<ParsedFrontmatter> = self.round_trip(&req, None)?;
        if resp.id != id {
            return Err(anyhow!(
                "[sandbox] response id mismatch: sent {id}, got {}",
                resp.id
            ));
        }
        match resp.body {
            SandboxBody::Ok(p) => Ok(p),
            SandboxBody::Err(e) => Err(anyhow!("[sandbox] {e}")),
        }
    }

    fn round_trip<Resp: serde::de::DeserializeOwned>(
        &self,
        req: &SandboxRequest,
        preopen: Option<(&std::path::Path, &str)>,
    ) -> Result<SandboxResponse<Resp>> {
        use wasmtime::{StoreLimits, StoreLimitsBuilder};
        use wasmtime_wasi::pipe::{MemoryInputPipe, MemoryOutputPipe};
        use wasmtime_wasi::{
            DirPerms, FilePerms, ResourceTable, WasiCtx, WasiCtxBuilder, WasiView,
        };

        // Serialize one JSON-RPC line and feed it as the entire stdin
        // of the wasm process. Newline-terminate so the guest's
        // `BufRead::lines()` returns immediately on EOF after the
        // single request.
        let mut req_bytes = serde_json::to_vec(req).context("serializing sandbox request")?;
        req_bytes.push(b'\n');

        let stdin = MemoryInputPipe::new(req_bytes);
        let stdout = MemoryOutputPipe::new(MEMORY_LIMIT_BYTES);
        let stderr = MemoryOutputPipe::new(64 * 1024);

        let mut wasi_builder = WasiCtxBuilder::new();
        wasi_builder
            .stdin(stdin)
            .stdout(stdout.clone())
            .stderr(stderr.clone());
        // Optional per-call preopen. The host hands us a host path and the
        // guest mount point; the guest then reads files using just the
        // basename relative to that mount point, so it never sees the host
        // absolute path. Read-only by design: the sandbox only consumes
        // bytes for parsing, it never writes.
        if let Some((host_dir, guest_mount)) = preopen {
            wasi_builder
                .preopened_dir(host_dir, guest_mount, DirPerms::READ, FilePerms::READ)
                .with_context(|| {
                    format!(
                        "preopening sandbox dir '{}' as '{guest_mount}'",
                        host_dir.display()
                    )
                })?;
        }
        let wasi_ctx = wasi_builder.build();

        // The store holds the wasi ctx + a resource table + a memory
        // limiter. `StoreLimits` caps the wasm linear memory so a
        // pathological input (huge file, expansion bomb) traps the
        // guest instead of growing the host's address space.
        struct Host {
            ctx: WasiCtx,
            table: ResourceTable,
            limits: StoreLimits,
        }
        impl WasiView for Host {
            fn ctx(&mut self) -> &mut WasiCtx {
                &mut self.ctx
            }
            fn table(&mut self) -> &mut ResourceTable {
                &mut self.table
            }
        }

        let limits = StoreLimitsBuilder::new()
            .memory_size(MEMORY_LIMIT_BYTES)
            .build();
        let mut store = wasmtime::Store::new(
            &self.engine,
            Host {
                ctx: wasi_ctx,
                table: ResourceTable::new(),
                limits,
            },
        );
        store.limiter(|h| &mut h.limits);
        store
            .set_fuel(FUEL_PER_REQUEST)
            .context("setting sandbox fuel")?;

        let mut linker = wasmtime::component::Linker::<Host>::new(&self.engine);
        wasmtime_wasi::add_to_linker_sync(&mut linker)
            .context("wiring wasi imports into sandbox linker")?;

        // Instantiate as a CLI command component (the binary's
        // `_start` is the entry point we want to run end-to-end).
        let command = wasmtime_wasi::bindings::sync::Command::instantiate(
            &mut store,
            &self.component,
            &linker,
        )
        .context("instantiating sandbox component")?;

        let run_result = command
            .wasi_cli_run()
            .call_run(&mut store)
            .context("invoking wasi:cli/run on sandbox component")?;
        if run_result.is_err() {
            let stderr_bytes = stderr.contents();
            let stderr_text = String::from_utf8_lossy(&stderr_bytes);
            return Err(anyhow!(
                "[sandbox] guest exited non-zero. stderr: {stderr_text}"
            ));
        }

        let out = stdout.contents();
        let mut reader = BufReader::new(out.as_ref());
        let mut line = String::new();
        reader
            .read_line(&mut line)
            .context("reading first response line from sandbox stdout")?;
        if line.trim().is_empty() {
            return Err(anyhow!("[sandbox] empty response from guest"));
        }
        serde_json::from_str::<SandboxResponse<Resp>>(line.trim_end())
            .with_context(|| format!("decoding sandbox response: {line}"))
    }
}

// ---------------------------------------------------------------------------
// Wire format -- mirrors `brokk-acp-sandbox/src/bin/sandbox.rs`
// ---------------------------------------------------------------------------

#[derive(Serialize)]
#[serde(tag = "method", content = "params", rename_all = "camelCase")]
enum SandboxRequestKind {
    ParseSkillFrontmatter(ParseSkillFrontmatterParams),
    ReadFileBounded(ReadFileBoundedParams),
    ReadZipEntryText(ReadZipEntryTextParams),
    ReadZipEntriesWithPrefix(ReadZipEntriesWithPrefixParams),
    SearchFileContents(SearchFileContentsParams),
}

#[derive(Serialize)]
struct ParseSkillFrontmatterParams {
    yaml: String,
}

#[derive(Serialize)]
struct ReadFileBoundedParams {
    // Field names stay snake_case to match the guest deserializer in
    // `brokk-acp-sandbox/src/main.rs`. Only the enum variant names are
    // camelCased (for the `method` field on the wire); inner params are
    // plain snake_case.
    guest_path: String,
    max_bytes: u64,
}

#[derive(Serialize)]
struct ReadZipEntryTextParams {
    guest_path: String,
    entry_name: String,
    max_archive_bytes: u64,
    max_entry_bytes: u64,
}

#[derive(Serialize)]
struct ReadZipEntriesWithPrefixParams {
    guest_path: String,
    prefix: String,
    max_archive_bytes: u64,
    max_entry_bytes: u64,
    max_total_bytes: u64,
}

#[derive(Serialize)]
struct SearchFileContentsParams {
    guest_root: String,
    pattern: String,
    glob: Option<String>,
    max_results: u64,
    max_file_bytes: u64,
    max_total_bytes: u64,
}

#[derive(Deserialize)]
struct ReadEntriesResult {
    entries: std::collections::HashMap<String, String>,
}

#[derive(Deserialize)]
struct ReadFileResult {
    content: String,
}

/// Newtype wrapper so we can carry the request id alongside the
/// tagged enum body (serde's adjacently-tagged enum encoding does not
/// mix with extra sibling fields cleanly).
#[derive(Serialize)]
struct SandboxRequest {
    id: u64,
    #[serde(flatten)]
    kind: SandboxRequestKind,
}

#[derive(Deserialize)]
struct SandboxResponse<T> {
    id: u64,
    #[serde(flatten)]
    body: SandboxBody<T>,
}

#[derive(Deserialize)]
#[serde(rename_all = "lowercase")]
enum SandboxBody<T> {
    Ok(T),
    Err(String),
}

#[cfg(test)]
mod tests {
    use super::*;

    /// End-to-end smoke test: the wasm sandbox boots, parses a valid
    /// SKILL.md frontmatter through the JSON-RPC wire, and returns
    /// the expected `ParsedFrontmatter` to the host. This is the
    /// canonical test that the build pipeline (build.rs producing
    /// `.wasm`, host embedding via `include_bytes!`, wasmtime hosting
    /// the component) all line up.
    #[test]
    fn wasm_backend_parses_valid_frontmatter() {
        let sandbox = WasmSandbox::new().expect("wasm sandbox should initialize");
        let yaml = "name: example\ndescription: a tiny skill for tests\n";
        let parsed = sandbox
            .parse_skill_frontmatter(yaml)
            .expect("guest returned a parse error for valid input");
        assert_eq!(parsed.name.as_deref(), Some("example"));
        assert_eq!(
            parsed.description.as_deref(),
            Some("a tiny skill for tests")
        );
    }

    /// Malformed YAML should come back as a structured error from the
    /// sandbox, not a host-side panic. This is the failure mode the
    /// wasm path exists to absorb safely.
    #[test]
    fn wasm_backend_reports_yaml_parse_error() {
        let sandbox = WasmSandbox::new().expect("wasm sandbox should initialize");
        // Unterminated bracket -- guaranteed YAML scanner error.
        let yaml = "name: [unterminated";
        let err = sandbox
            .parse_skill_frontmatter(yaml)
            .expect_err("invalid YAML should not parse successfully");
        let msg = err.to_string();
        assert!(
            msg.starts_with("[sandbox]"),
            "expected sandbox-prefixed error, got: {msg}"
        );
    }

    /// `SandboxBackend::Native` and `SandboxBackend::Wasm` must produce
    /// identical results for valid input. Pins the wire contract --
    /// if the wasm guest evolves and the JSON shape drifts, this test
    /// flags the mismatch before the production parser disagrees with
    /// its sandbox shadow.
    #[test]
    fn native_and_wasm_agree_on_valid_frontmatter() {
        let yaml = "name: agree\ndescription: same parse on both backends\n";
        let native_result = SandboxBackend::Native.parse_skill_frontmatter(yaml);
        let wasm_sandbox = WasmSandbox::new().expect("wasm sandbox should initialize");
        let wasm = SandboxBackend::Wasm(Arc::new(wasm_sandbox));
        let wasm_result = wasm.parse_skill_frontmatter(yaml);
        assert_eq!(
            native_result.as_ref().ok().map(|p| (p.name.clone(), p.description.clone())),
            wasm_result.as_ref().ok().map(|p| (p.name.clone(), p.description.clone())),
            "native vs wasm disagreement: native={native_result:?} wasm={wasm_result:?}"
        );
    }

    /// Happy path for the wasm-backed reader: host preopens the parent,
    /// guest reads the file, contents come back through stdout.
    #[test]
    fn wasm_read_file_bounded_returns_contents() {
        let tmp = tempfile::TempDir::new().unwrap();
        let path = tmp.path().join("hello.txt");
        std::fs::write(&path, "wasm-roundtrip\n").unwrap();

        let wasm = WasmSandbox::new().expect("wasm sandbox should initialize");
        let backend = SandboxBackend::Wasm(Arc::new(wasm));
        let content = backend
            .read_file_bounded(&path, 1024)
            .expect("read should succeed")
            .expect("file should be present");
        assert_eq!(content, "wasm-roundtrip\n");
    }

    /// A file over the byte cap must come back as `FileTooLarge`
    /// without ever streaming through the host. This is the load-bearing
    /// guarantee for callers like `agents_md` that read user-controlled
    /// files in a project tree -- a 10 GB AGENTS.md must not OOM the
    /// agent. Use a tight cap so the fixture stays small.
    #[test]
    fn wasm_read_file_bounded_rejects_oversize() {
        let tmp = tempfile::TempDir::new().unwrap();
        let path = tmp.path().join("big.txt");
        let body = "x".repeat(8 * 1024);
        std::fs::write(&path, &body).unwrap();

        let wasm = WasmSandbox::new().expect("wasm sandbox should initialize");
        let backend = SandboxBackend::Wasm(Arc::new(wasm));
        let err = backend
            .read_file_bounded(&path, 1024)
            .expect_err("oversize file must be rejected");
        assert_eq!(
            err.kind(),
            std::io::ErrorKind::FileTooLarge,
            "expected FileTooLarge, got: {err}"
        );
    }

    /// Missing file -> `Ok(None)` so callers can keep the "no file here,
    /// move on" code path that the native fallback uses.
    #[test]
    fn wasm_read_file_bounded_missing_returns_none() {
        let tmp = tempfile::TempDir::new().unwrap();
        let path = tmp.path().join("does-not-exist.txt");

        let wasm = WasmSandbox::new().expect("wasm sandbox should initialize");
        let backend = SandboxBackend::Wasm(Arc::new(wasm));
        let result = backend.read_file_bounded(&path, 1024);
        assert!(
            matches!(result, Ok(None)),
            "missing file should be Ok(None), got: {result:?}"
        );
    }

    /// Parity between Native and Wasm on the same fixture, mirroring
    /// the frontmatter parity test. Pins the contract so a future
    /// guest change cannot silently disagree with the native fallback.
    #[test]
    fn native_and_wasm_agree_on_read_file_bounded() {
        let tmp = tempfile::TempDir::new().unwrap();
        let path = tmp.path().join("agree.txt");
        std::fs::write(&path, "same content on both backends\n").unwrap();

        let native = SandboxBackend::Native.read_file_bounded(&path, 1024).unwrap();
        let wasm_sandbox = WasmSandbox::new().expect("wasm sandbox should initialize");
        let wasm = SandboxBackend::Wasm(Arc::new(wasm_sandbox))
            .read_file_bounded(&path, 1024)
            .unwrap();
        assert_eq!(native, wasm);
    }

    /// Build a tiny session-style zip with `zip` (Deflated) and read it
    /// back through both backends. Anchors the wire contract for the
    /// zip-entry primitive and the parity between the host's `zip` crate
    /// (used to build the fixture) and the sandbox's minimal parser
    /// (used to read it inside wasm).
    fn build_session_fixture_zip(path: &std::path::Path, entries: &[(&str, &str)]) {
        let file = std::fs::File::create(path).unwrap();
        let mut writer = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        for (name, body) in entries {
            writer.start_file(*name, options).unwrap();
            std::io::Write::write_all(&mut writer, body.as_bytes()).unwrap();
        }
        writer.finish().unwrap();
    }

    #[test]
    fn native_and_wasm_agree_on_read_zip_entry_text() {
        let tmp = tempfile::TempDir::new().unwrap();
        let zip_path = tmp.path().join("session.zip");
        build_session_fixture_zip(
            &zip_path,
            &[
                ("manifest.json", r#"{"id":"abc","name":"demo"}"#),
                ("content/hello.txt", "hello from inside the zip"),
            ],
        );

        let native = SandboxBackend::Native
            .read_zip_entry_text(&zip_path, "manifest.json", 1 << 20, 1 << 20)
            .unwrap();
        let wasm_sandbox = WasmSandbox::new().expect("wasm sandbox should initialize");
        let wasm = SandboxBackend::Wasm(Arc::new(wasm_sandbox))
            .read_zip_entry_text(&zip_path, "manifest.json", 1 << 20, 1 << 20)
            .unwrap();
        assert_eq!(native, wasm);
        assert_eq!(
            native.as_deref(),
            Some(r#"{"id":"abc","name":"demo"}"#),
            "manifest bytes should round-trip through the zip parser"
        );
    }

    #[test]
    fn wasm_read_zip_entry_missing_returns_none() {
        let tmp = tempfile::TempDir::new().unwrap();
        let zip_path = tmp.path().join("session.zip");
        build_session_fixture_zip(&zip_path, &[("manifest.json", "{}")]);

        let wasm = WasmSandbox::new().expect("wasm sandbox should initialize");
        let backend = SandboxBackend::Wasm(Arc::new(wasm));
        let result = backend
            .read_zip_entry_text(&zip_path, "missing.json", 1 << 20, 1 << 20)
            .expect("read should not error for a missing entry");
        assert!(
            result.is_none(),
            "missing entry should be Ok(None), got: {result:?}"
        );
    }

    /// Happy path: a known fixture tree returns the same match set
    /// via both backends, pinning the contract that the sandbox does
    /// not silently drop or reorder hits.
    #[test]
    fn native_and_wasm_agree_on_search_file_contents() {
        let tmp = tempfile::TempDir::new().unwrap();
        std::fs::write(tmp.path().join("a.txt"), "hello\nworld\nfoo bar\n").unwrap();
        std::fs::write(tmp.path().join("b.md"), "# title\nworld peace\n").unwrap();
        let native = SandboxBackend::Native
            .search_file_contents(tmp.path(), "world", None, 100, 1 << 20, 1 << 30)
            .expect("native search should succeed");
        let wasm_sandbox = WasmSandbox::new().expect("wasm sandbox should initialize");
        let wasm = SandboxBackend::Wasm(Arc::new(wasm_sandbox))
            .search_file_contents(tmp.path(), "world", None, 100, 1 << 20, 1 << 30)
            .expect("wasm search should succeed");
        let mut native_paths: Vec<_> =
            native.matches.iter().map(|m| (m.path.clone(), m.line_num)).collect();
        let mut wasm_paths: Vec<_> =
            wasm.matches.iter().map(|m| (m.path.clone(), m.line_num)).collect();
        native_paths.sort();
        wasm_paths.sort();
        assert_eq!(native_paths, wasm_paths);
        assert_eq!(native.matches.len(), 2, "should find 'world' in two files");
    }

    /// An invalid regex must come back as `InvalidRegex`, not a
    /// generic sandbox error. The host's `search_file_contents` tool
    /// converts that into a `RequestError` so the LLM gets a clear
    /// "fix the pattern" message rather than an opaque crash.
    #[test]
    fn wasm_search_file_contents_invalid_regex_is_surfaced() {
        let tmp = tempfile::TempDir::new().unwrap();
        let wasm = WasmSandbox::new().expect("wasm sandbox should initialize");
        let backend = SandboxBackend::Wasm(Arc::new(wasm));
        let err = backend
            .search_file_contents(tmp.path(), "(unclosed", None, 100, 1 << 20, 1 << 30)
            .expect_err("invalid regex must fail");
        assert!(
            matches!(err, brokk_acp_sandbox::SearchError::InvalidRegex(_)),
            "expected InvalidRegex, got: {err}"
        );
    }

    /// A regex that would catastrophically backtrack in PCRE is
    /// linear-time in the `regex` crate, so the sandbox returns
    /// promptly even on a worst-case input. This test pins the
    /// behaviour so a future engine swap that re-introduces
    /// backtracking will surface as a fuel-exhaustion failure here
    /// rather than as a wedged agent in production.
    #[test]
    fn wasm_search_file_contents_redos_is_bounded() {
        let tmp = tempfile::TempDir::new().unwrap();
        // Classic ReDoS bait: `(a+)+b` over a long all-`a` input.
        let body = "a".repeat(8 * 1024) + "x\n";
        std::fs::write(tmp.path().join("payload.txt"), &body).unwrap();
        let wasm = WasmSandbox::new().expect("wasm sandbox should initialize");
        let backend = SandboxBackend::Wasm(Arc::new(wasm));
        let outcome = backend
            .search_file_contents(tmp.path(), "(a+)+b", None, 100, 1 << 20, 1 << 30)
            .expect("regex crate is linear-time; this must not hang");
        // No `b` in the payload, so we expect zero matches.
        assert!(outcome.matches.is_empty());
    }

    #[test]
    fn wasm_read_zip_entry_rejects_oversize_entry() {
        let tmp = tempfile::TempDir::new().unwrap();
        let zip_path = tmp.path().join("session.zip");
        // 16 KiB entry, but cap is 1 KiB. The wasm parser must reject
        // before allocating the full decompressed buffer.
        let body = "x".repeat(16 * 1024);
        build_session_fixture_zip(&zip_path, &[("big.txt", &body)]);

        let wasm = WasmSandbox::new().expect("wasm sandbox should initialize");
        let backend = SandboxBackend::Wasm(Arc::new(wasm));
        let err = backend
            .read_zip_entry_text(&zip_path, "big.txt", 1 << 20, 1024)
            .expect_err("oversize entry must be rejected");
        assert_eq!(
            err.kind(),
            std::io::ErrorKind::FileTooLarge,
            "expected FileTooLarge, got: {err}"
        );
    }
}
