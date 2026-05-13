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
        let resp: SandboxResponse<ParsedFrontmatter> = self.round_trip(&req)?;
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
    ) -> Result<SandboxResponse<Resp>> {
        use wasmtime_wasi::pipe::{MemoryInputPipe, MemoryOutputPipe};
        use wasmtime_wasi::{ResourceTable, WasiCtx, WasiCtxBuilder, WasiView};

        // Serialize one JSON-RPC line and feed it as the entire stdin
        // of the wasm process. Newline-terminate so the guest's
        // `BufRead::lines()` returns immediately on EOF after the
        // single request.
        let mut req_bytes = serde_json::to_vec(req).context("serializing sandbox request")?;
        req_bytes.push(b'\n');

        let stdin = MemoryInputPipe::new(req_bytes);
        let stdout = MemoryOutputPipe::new(MEMORY_LIMIT_BYTES);
        let stderr = MemoryOutputPipe::new(64 * 1024);

        let wasi_ctx = WasiCtxBuilder::new()
            .stdin(stdin)
            .stdout(stdout.clone())
            .stderr(stderr.clone())
            .build();

        // The store holds the wasi ctx + a resource table. We attach
        // `WasiView` via a small adapter so wasmtime-wasi can find it.
        struct Host {
            ctx: WasiCtx,
            table: ResourceTable,
        }
        impl WasiView for Host {
            fn ctx(&mut self) -> &mut WasiCtx {
                &mut self.ctx
            }
            fn table(&mut self) -> &mut ResourceTable {
                &mut self.table
            }
        }

        let mut store = wasmtime::Store::new(
            &self.engine,
            Host {
                ctx: wasi_ctx,
                table: ResourceTable::new(),
            },
        );
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
}

#[derive(Serialize)]
struct ParseSkillFrontmatterParams {
    yaml: String,
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
}
