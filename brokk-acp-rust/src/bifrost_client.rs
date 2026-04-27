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
    use std::path::PathBuf;

    fn locate_bifrost() -> Option<PathBuf> {
        if let Ok(env) = std::env::var("BROKK_BIFROST_BINARY") {
            let p = PathBuf::from(env);
            if p.exists() {
                return Some(p);
            }
        }
        let output = std::process::Command::new("which")
            .arg("bifrost")
            .output()
            .ok()?;
        if !output.status.success() {
            return None;
        }
        let trimmed = String::from_utf8(output.stdout).ok()?.trim().to_string();
        if trimmed.is_empty() {
            None
        } else {
            Some(PathBuf::from(trimmed))
        }
    }

    /// Smoke test: spawn the real bifrost subprocess, run the MCP handshake,
    /// confirm we get the 9 search-tools, and round-trip one tool call.
    /// Skipped automatically when the binary isn't installed.
    #[tokio::test]
    async fn handshake_and_call_search_tools() {
        let Some(binary) = locate_bifrost() else {
            eprintln!("skipping: bifrost binary not on PATH and BROKK_BIFROST_BINARY unset");
            return;
        };
        let cwd = std::env::current_dir()
            .expect("cwd")
            .canonicalize()
            .expect("canonicalize");

        let client = BifrostClient::spawn(&binary, &cwd)
            .await
            .expect("bifrost subprocess should start");

        let names: Vec<&str> = client.tools().iter().map(|t| t.name.as_str()).collect();
        assert_eq!(client.tools().len(), 9, "expected 9 tools, got {names:?}");
        for expected in [
            "search_symbols",
            "get_symbol_locations",
            "get_symbol_summaries",
            "get_symbol_sources",
            "get_summaries",
            "summarize_symbols",
            "skim_files",
            "most_relevant_files",
            "refresh",
        ] {
            assert!(
                names.contains(&expected),
                "missing tool {expected} in {names:?}"
            );
        }

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
                "get_summaries",
                json!({ "targets": ["brokk-acp-rust/src/bifrost_client.rs"] }),
            )
            .await
            .expect("get_summaries call should succeed");
        eprintln!(
            "get_summaries result: {}",
            serde_json::to_string_pretty(&result).unwrap_or_default()
        );
    }
}
