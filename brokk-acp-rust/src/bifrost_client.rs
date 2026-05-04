use std::fmt;
use std::path::Path;
use std::process::Stdio;
use std::sync::atomic::{AtomicI64, Ordering};

use serde_json::{Value, json};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, ChildStdout, Command};
use tokio::sync::Mutex;

const PROTOCOL_VERSION: &str = "2025-11-25";

#[derive(Debug)]
pub enum BifrostError {
    Spawn(String),
    Io(String),
    Protocol(String),
    JsonRpc { code: i64, message: String },
}

impl fmt::Display for BifrostError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            BifrostError::Spawn(s) => write!(f, "spawn failed: {s}"),
            BifrostError::Io(s) => write!(f, "io error: {s}"),
            BifrostError::Protocol(s) => write!(f, "protocol error: {s}"),
            BifrostError::JsonRpc { code, message } => {
                write!(f, "jsonrpc error {code}: {message}")
            }
        }
    }
}

impl std::error::Error for BifrostError {}

#[derive(Debug, Clone)]
pub struct BifrostToolDef {
    pub name: String,
    pub description: String,
    pub input_schema: Value,
}

/// JSON-RPC client for a long-lived `bifrost --server searchtools` subprocess.
///
/// Holds the child process for the lifetime of the client; the process is
/// killed when the client is dropped (`kill_on_drop(true)`).
///
/// The MCP stdio protocol is one JSON message per line. Reads and writes are
/// serialized through a single mutex because the existing tool loop dispatches
/// tool calls sequentially within a session.
pub struct BifrostClient {
    _child: Mutex<Child>,
    io: Mutex<BifrostIo>,
    next_id: AtomicI64,
    tools: Vec<BifrostToolDef>,
}

struct BifrostIo {
    writer: ChildStdin,
    reader: BufReader<ChildStdout>,
}

impl BifrostClient {
    pub async fn spawn(binary: &Path, cwd: &Path) -> Result<Self, BifrostError> {
        let mut child = Command::new(binary)
            .arg("--root")
            .arg(cwd)
            .arg("--server")
            .arg("searchtools")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .kill_on_drop(true)
            .spawn()
            .map_err(|e| BifrostError::Spawn(format!("{}: {e}", binary.display())))?;

        let writer = child
            .stdin
            .take()
            .ok_or_else(|| BifrostError::Spawn("missing stdin pipe".into()))?;
        let reader = BufReader::new(
            child
                .stdout
                .take()
                .ok_or_else(|| BifrostError::Spawn("missing stdout pipe".into()))?,
        );

        let mut io = BifrostIo { writer, reader };
        let next_id = AtomicI64::new(1);

        let init_id = next_id.fetch_add(1, Ordering::SeqCst);
        write_request(
            &mut io,
            init_id,
            "initialize",
            json!({
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {
                    "name": "brokk-acp-rust",
                    "version": env!("CARGO_PKG_VERSION"),
                },
            }),
        )
        .await?;
        let _init = read_response(&mut io, init_id).await?;

        write_notification(&mut io, "notifications/initialized", json!({})).await?;

        let list_id = next_id.fetch_add(1, Ordering::SeqCst);
        write_request(&mut io, list_id, "tools/list", json!({})).await?;
        let list = read_response(&mut io, list_id).await?;
        let tools = parse_tool_list(list)?;

        tracing::info!(
            binary = %binary.display(),
            cwd = %cwd.display(),
            tool_count = tools.len(),
            "bifrost subprocess ready"
        );

        Ok(Self {
            _child: Mutex::new(child),
            io: Mutex::new(io),
            next_id,
            tools,
        })
    }

    pub fn tools(&self) -> &[BifrostToolDef] {
        &self.tools
    }

    pub async fn call_tool(&self, name: &str, args: Value) -> Result<Value, BifrostError> {
        let id = self.next_id.fetch_add(1, Ordering::SeqCst);
        let mut io = self.io.lock().await;
        write_request(
            &mut io,
            id,
            "tools/call",
            json!({ "name": name, "arguments": args }),
        )
        .await?;
        let result = read_response(&mut io, id).await?;

        if result
            .get("isError")
            .and_then(Value::as_bool)
            .unwrap_or(false)
        {
            let msg = result
                .get("content")
                .and_then(|c| c.get(0))
                .and_then(|m| m.get("text"))
                .and_then(Value::as_str)
                .unwrap_or("Unknown bifrost tool error")
                .to_string();
            return Err(BifrostError::Protocol(msg));
        }

        if let Some(structured) = result.get("structuredContent") {
            return Ok(structured.clone());
        }
        if let Some(text) = result
            .get("content")
            .and_then(|c| c.get(0))
            .and_then(|m| m.get("text"))
        {
            return Ok(text.clone());
        }
        Ok(result)
    }
}

async fn write_request(
    io: &mut BifrostIo,
    id: i64,
    method: &str,
    params: Value,
) -> Result<(), BifrostError> {
    write_message(
        io,
        &json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": method,
            "params": params,
        }),
    )
    .await
}

async fn write_notification(
    io: &mut BifrostIo,
    method: &str,
    params: Value,
) -> Result<(), BifrostError> {
    write_message(
        io,
        &json!({
            "jsonrpc": "2.0",
            "method": method,
            "params": params,
        }),
    )
    .await
}

async fn write_message(io: &mut BifrostIo, msg: &Value) -> Result<(), BifrostError> {
    let mut bytes =
        serde_json::to_vec(msg).map_err(|e| BifrostError::Io(format!("serialize: {e}")))?;
    bytes.push(b'\n');
    io.writer
        .write_all(&bytes)
        .await
        .map_err(|e| BifrostError::Io(format!("write: {e}")))?;
    io.writer
        .flush()
        .await
        .map_err(|e| BifrostError::Io(format!("flush: {e}")))?;
    Ok(())
}

async fn read_response(io: &mut BifrostIo, expected_id: i64) -> Result<Value, BifrostError> {
    loop {
        let mut line = String::new();
        let n = io
            .reader
            .read_line(&mut line)
            .await
            .map_err(|e| BifrostError::Io(format!("read: {e}")))?;
        if n == 0 {
            return Err(BifrostError::Io("bifrost subprocess closed stdout".into()));
        }
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        let value: Value = serde_json::from_str(trimmed)
            .map_err(|e| BifrostError::Protocol(format!("parse: {e} (line: {trimmed})")))?;
        if value.get("id").and_then(Value::as_i64) != Some(expected_id) {
            tracing::debug!(?value, "skipping bifrost message with unexpected id");
            continue;
        }
        if let Some(error) = value.get("error") {
            let code = error.get("code").and_then(Value::as_i64).unwrap_or(0);
            let message = error
                .get("message")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_string();
            return Err(BifrostError::JsonRpc { code, message });
        }
        return value
            .get("result")
            .cloned()
            .ok_or_else(|| BifrostError::Protocol("response missing result".into()));
    }
}

fn parse_tool_list(result: Value) -> Result<Vec<BifrostToolDef>, BifrostError> {
    let tools_array = result
        .get("tools")
        .and_then(Value::as_array)
        .ok_or_else(|| BifrostError::Protocol("tools/list missing 'tools' array".into()))?;
    tools_array
        .iter()
        .map(|tool| {
            let name = tool
                .get("name")
                .and_then(Value::as_str)
                .ok_or_else(|| BifrostError::Protocol("tool missing name".into()))?
                .to_string();
            let description = tool
                .get("description")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_string();
            let input_schema = tool
                .get("inputSchema")
                .cloned()
                .unwrap_or_else(|| json!({ "type": "object" }));
            Ok(BifrostToolDef {
                name,
                description,
                input_schema,
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::{Path, PathBuf};

    /// Bifrost release the handshake test pins. Bumping bifrost is a deliberate
    /// edit here, not whatever happens to be on a contributor's `$PATH`.
    /// Must stay in sync with `BUNDLED_BIFROST_VERSION` in
    /// `brokk-code/brokk_code/rust_acp_install.py`.
    const TEST_BIFROST_VERSION: &str = "0.1.3";

    const TEST_BIFROST_RELEASE_BASE: &str = "https://github.com/BrokkAi/bifrost/releases/download";

    #[cfg(all(target_os = "macos", target_arch = "aarch64"))]
    const TRIPLE: &str = "aarch64-apple-darwin";
    #[cfg(all(target_os = "linux", target_arch = "x86_64"))]
    const TRIPLE: &str = "x86_64-unknown-linux-gnu";
    #[cfg(all(target_os = "linux", target_arch = "aarch64"))]
    const TRIPLE: &str = "aarch64-unknown-linux-gnu";
    #[cfg(all(target_os = "windows", target_arch = "x86_64"))]
    const TRIPLE: &str = "x86_64-pc-windows-msvc";
    #[cfg(all(target_os = "windows", target_arch = "aarch64"))]
    const TRIPLE: &str = "aarch64-pc-windows-msvc";

    #[cfg(not(any(
        all(target_os = "macos", target_arch = "aarch64"),
        all(target_os = "linux", target_arch = "x86_64"),
        all(target_os = "linux", target_arch = "aarch64"),
        all(target_os = "windows", target_arch = "x86_64"),
        all(target_os = "windows", target_arch = "aarch64"),
    )))]
    compile_error!(
        "bifrost releases only ship binaries for arm64 macOS, x86_64/aarch64 Linux, \
         and x86_64/aarch64 Windows; this test cannot run on other targets"
    );

    #[cfg(target_os = "windows")]
    const ARCHIVE_EXT: &str = "zip";
    #[cfg(not(target_os = "windows"))]
    const ARCHIVE_EXT: &str = "tar.gz";

    #[cfg(target_os = "windows")]
    const BINARY_NAME: &str = "bifrost.exe";
    #[cfg(not(target_os = "windows"))]
    const BINARY_NAME: &str = "bifrost";

    /// Resolve the bifrost binary used by the handshake test.
    ///
    /// Resolution order:
    /// 1. `BROKK_BIFROST_BINARY` env var (override for testing against an
    ///    in-tree bifrost build).
    /// 2. The cached pinned-version binary under
    ///    `target/test-fixtures/bifrost/<version>/<triple>/`.
    /// 3. Download the pinned release into the cache, then return its path.
    ///
    /// We deliberately do NOT consult `which bifrost`: that coupled the test
    /// to whatever happened to be installed locally, which dragged the test
    /// behavior into "depends on which version of bifrost the contributor
    /// happens to have on PATH" -- the bug this helper exists to remove.
    async fn ensure_test_bifrost_binary() -> PathBuf {
        if let Ok(override_path) = std::env::var("BROKK_BIFROST_BINARY") {
            let p = PathBuf::from(&override_path);
            assert!(
                p.is_file(),
                "BROKK_BIFROST_BINARY={override_path} is not a regular file"
            );
            return p;
        }

        let cache_dir = test_fixture_cache_dir();
        let binary = cache_dir.join(BINARY_NAME);
        if binary.is_file() {
            return binary;
        }

        download_and_extract_bifrost(&cache_dir).await;
        assert!(
            binary.is_file(),
            "expected bifrost at {binary:?} after download+extract"
        );
        binary
    }

    fn test_fixture_cache_dir() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("target")
            .join("test-fixtures")
            .join("bifrost")
            .join(TEST_BIFROST_VERSION)
            .join(TRIPLE)
    }

    async fn download_and_extract_bifrost(cache_dir: &Path) {
        use sha2::{Digest, Sha256};

        std::fs::create_dir_all(cache_dir).expect("create test-fixtures cache");

        let asset = format!("bifrost-v{TEST_BIFROST_VERSION}-{TRIPLE}.{ARCHIVE_EXT}");
        let url = format!("{TEST_BIFROST_RELEASE_BASE}/v{TEST_BIFROST_VERSION}/{asset}");
        let sha256_url = format!("{url}.sha256");

        eprintln!("downloading bifrost test fixture: {url}");
        let bytes = reqwest::get(&url)
            .await
            .expect("bifrost release download failed (network/proxy?)")
            .error_for_status()
            .expect("bifrost release returned non-200")
            .bytes()
            .await
            .expect("read bifrost archive bytes");

        // Integrity check: verify against the publisher's `.sha256` sidecar
        // before extraction. `bifrost --version` is unreliable as a pin
        // (the v0.1.3 binary still self-reports `0.1.2` due to an upstream
        // Cargo.toml miss); the sha256 is content-addressable so a
        // mislabeled or swapped binary is caught here. Mirrors the check
        // already done by `brokk-code/brokk_code/rust_acp_install.py` on
        // the production install path.
        eprintln!("verifying bifrost archive against {sha256_url}");
        let sidecar = reqwest::get(&sha256_url)
            .await
            .expect("bifrost .sha256 sidecar download failed")
            .error_for_status()
            .expect("bifrost .sha256 sidecar returned non-200")
            .text()
            .await
            .expect("read .sha256 sidecar text");
        let expected_hex = sidecar
            .split_whitespace()
            .next()
            .expect("bifrost .sha256 sidecar is empty")
            .to_lowercase();
        let mut hasher = Sha256::new();
        hasher.update(&bytes);
        let actual_hex = format!("{:x}", hasher.finalize());
        assert_eq!(
            actual_hex, expected_hex,
            "bifrost archive sha256 mismatch for {url}: got {actual_hex}, expected {expected_hex} (refuse to extract)"
        );

        let archive_path = cache_dir.join(&asset);
        std::fs::write(&archive_path, &bytes).expect("write archive to cache");

        // `tar -xf` auto-detects the format and handles both .tar.gz and .zip
        // on modern macOS, Linux, and Windows 10+ runners.
        let status = std::process::Command::new("tar")
            .arg("-xf")
            .arg(&archive_path)
            .arg("-C")
            .arg(cache_dir)
            .status()
            .expect("invoke tar to extract bifrost archive");
        assert!(
            status.success(),
            "tar extraction of {archive_path:?} failed"
        );

        // Archive extracts to `bifrost-v<ver>-<triple>/bifrost(.exe)`.
        let inner_dir = cache_dir.join(format!("bifrost-v{TEST_BIFROST_VERSION}-{TRIPLE}"));
        let inner_binary = inner_dir.join(BINARY_NAME);
        assert!(
            inner_binary.is_file(),
            "expected extracted binary at {inner_binary:?}"
        );

        let target = cache_dir.join(BINARY_NAME);
        std::fs::rename(&inner_binary, &target)
            .or_else(|_| std::fs::copy(&inner_binary, &target).map(|_| ()))
            .expect("place bifrost binary at cache root");

        let _ = std::fs::remove_file(&archive_path);
        let _ = std::fs::remove_dir_all(&inner_dir);

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mut perms = std::fs::metadata(&target)
                .expect("stat extracted binary")
                .permissions();
            perms.set_mode(0o755);
            std::fs::set_permissions(&target, perms).expect("chmod 755 on extracted binary");
        }
    }

    /// Smoke test: spawn the real bifrost subprocess (pinned release,
    /// downloaded into `target/test-fixtures/`), run the MCP handshake,
    /// confirm a stable subset of search tools is exposed, and round-trip
    /// two distinct tool calls. We deliberately do NOT pin the exact tool
    /// count or full tool list -- bifrost adds tools faster than this test
    /// gets updated, and the handshake's job is to verify the protocol
    /// path works, not to enumerate the surface.
    #[tokio::test]
    async fn handshake_and_call_search_tools() {
        let binary = ensure_test_bifrost_binary().await;
        let cwd = std::env::current_dir()
            .expect("cwd")
            .canonicalize()
            .expect("canonicalize");

        let client = BifrostClient::spawn(&binary, &cwd)
            .await
            .expect("bifrost subprocess should start");

        let names: Vec<&str> = client.tools().iter().map(|t| t.name.as_str()).collect();

        // Floor on total tool count -- catches a wholesale regression where
        // bifrost drops most of its tools (e.g. a misconfigured server arg).
        // The exact count drifts as bifrost adds tools, so we don't pin it.
        assert!(
            client.tools().len() >= 5,
            "expected at least 5 tools, got {} -- {names:?}",
            client.tools().len()
        );

        for expected in ["search_symbols", "list_symbols", "get_summaries"] {
            assert!(
                names.contains(&expected),
                "missing tool {expected} in {names:?}"
            );
        }

        // Anti-drift: every tool bifrost advertises must have a row in
        // `tools::TOOLS`. Without one, `tool_kind` falls back to
        // `Other` (refused in `readOnly`, prompts unnecessarily in
        // `default`) and `display_name` falls back to "Executing
        // tool" in the UI. If this assertion fires, bifrost likely
        // added or renamed a tool -- update `TOOLS` in
        // `tools/mod.rs` to match.
        for tool_name in &names {
            assert!(
                crate::tools::is_known_tool(tool_name),
                "bifrost advertises '{tool_name}' but it is not in the TOOLS metadata table; \
                 add a ToolMeta row in tools/mod.rs (current bifrost surface: {names:?})"
            );
        }

        // Round-trip two distinct tool calls so we exercise back-to-back use
        // of the JSON-RPC reader/writer mutex (id correlation, sequential
        // dispatch, response-shape branching) -- not just one-shot dispatch.
        let result = client
            .call_tool("search_symbols", json!({ "patterns": ["BifrostClient"] }))
            .await
            .expect("search_symbols call should succeed");
        eprintln!(
            "search_symbols result: {}",
            serde_json::to_string_pretty(&result).unwrap_or_default()
        );

        let result = client
            .call_tool(
                "list_symbols",
                json!({ "file_patterns": ["brokk-acp-rust/src/bifrost_client.rs"] }),
            )
            .await
            .expect("list_symbols call should succeed");
        eprintln!(
            "list_symbols result: {}",
            serde_json::to_string_pretty(&result).unwrap_or_default()
        );
    }
}
