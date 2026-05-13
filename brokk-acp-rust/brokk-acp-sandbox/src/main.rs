//! `brokk-acp-sandbox` binary.
//!
//! Compiled to `wasm32-wasip2`, run under wasmtime by the native
//! `brokk-acp` binary. Speaks newline-delimited JSON-RPC over stdin/
//! stdout: one request per line, one response per line. The wasm
//! component runs for the lifetime of a single request batch and
//! then exits when stdin is closed -- the host re-spawns it on
//! demand, so memory/state never leaks across calls.
//!
//! Request format:
//! ```json
//! {"id": 1, "method": "parseSkillFrontmatter", "params": {"yaml": "..."}}
//! ```
//! Response format:
//! ```json
//! {"id": 1, "ok": {"name": "...", "description": "..."}}
//! {"id": 1, "err": "free-form error message"}
//! ```
//!
//! The same binary, when compiled natively (e.g. for unit tests or
//! when the user disables the wasm sandbox via `--no-wasm-sandbox`),
//! can be exec'd or piped in the same JSON-RPC mode. In practice the
//! native fallback in `brokk-acp-rust` calls the library directly --
//! the binary is mostly there to give the wasm component a runnable
//! entry point.

use std::io::{BufRead, Write};

use serde::{Deserialize, Serialize};

#[derive(Deserialize)]
#[serde(tag = "method", content = "params", rename_all = "camelCase")]
enum Request {
    /// Parse a SKILL.md frontmatter block from its raw YAML body.
    ParseSkillFrontmatter { yaml: String },
    /// Split a full SKILL.md file into `(frontmatter, body)`.
    SplitSkillFrontmatter { raw: String },
}

#[derive(Deserialize)]
struct Envelope {
    id: u64,
    #[serde(flatten)]
    req: Request,
}

#[derive(Serialize)]
struct OkResponse<'a, T: Serialize> {
    id: u64,
    ok: &'a T,
}

#[derive(Serialize)]
struct ErrResponse<'a> {
    id: u64,
    err: &'a str,
}

#[derive(Serialize)]
struct SplitResult {
    frontmatter: String,
    body: String,
}

fn main() -> anyhow::Result<()> {
    let stdin = std::io::stdin();
    let stdout = std::io::stdout();
    let mut out = stdout.lock();
    for line in stdin.lock().lines() {
        let line = line?;
        if line.trim().is_empty() {
            continue;
        }
        let envelope: Envelope = match serde_json::from_str(&line) {
            Ok(e) => e,
            Err(err) => {
                let body = format!("malformed request envelope: {err}");
                writeln!(
                    out,
                    "{}",
                    serde_json::to_string(&ErrResponse { id: 0, err: &body })?
                )?;
                out.flush()?;
                continue;
            }
        };
        let id = envelope.id;
        let response_line = match envelope.req {
            Request::ParseSkillFrontmatter { yaml } => {
                match brokk_acp_sandbox::parse_frontmatter(&yaml) {
                    Ok(parsed) => serde_json::to_string(&OkResponse { id, ok: &parsed })?,
                    Err(err) => serde_json::to_string(&ErrResponse { id, err: &err })?,
                }
            }
            Request::SplitSkillFrontmatter { raw } => match brokk_acp_sandbox::split_frontmatter(&raw) {
                Ok((front, body)) => {
                    let payload = SplitResult {
                        frontmatter: front.to_string(),
                        body: body.to_string(),
                    };
                    serde_json::to_string(&OkResponse { id, ok: &payload })?
                }
                Err(err) => serde_json::to_string(&ErrResponse { id, err })?,
            },
        };
        writeln!(out, "{response_line}")?;
        out.flush()?;
    }
    Ok(())
}
