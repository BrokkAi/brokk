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
    /// Read a file the host has exposed via a preopened directory, up
    /// to `max_bytes`. `guest_path` is the absolute path the host
    /// preopened (e.g. `/d/AGENTS.md`); a path the host did not
    /// preopen will fail to open. Returns the contents as a UTF-8
    /// string if valid UTF-8 and within the cap; an error otherwise.
    /// The byte cap is the second line of defense after the wasm
    /// memory limit -- a multi-gigabyte file traps the guest on
    /// linear-memory growth before we even see the bytes.
    ReadFileBounded { guest_path: String, max_bytes: u64 },
    /// Read a single entry by name out of a zip archive that the host
    /// has exposed via a preopened directory. Returns the entry's
    /// decompressed UTF-8 contents (assumes text). `max_bytes` caps
    /// both the file size pre-check on the archive itself and the
    /// decompressed entry size, so a zip bomb cannot grow the guest
    /// past the wasm memory limit. Returns the same `Ok(None)`
    /// signal as `ReadFileBounded` when the archive exists but does
    /// not contain `entry_name`.
    ReadZipEntryText {
        guest_path: String,
        entry_name: String,
        max_archive_bytes: u64,
        max_entry_bytes: u64,
    },
    /// Read every text entry whose name starts with `prefix` from the
    /// zip archive at `guest_path`. Used by the history reader to
    /// batch the `content/*.txt` pulls into a single sandbox boot
    /// instead of one per turn. `max_total_bytes` is a separate
    /// budget over the sum of decompressed payloads so a swarm of
    /// small bomb entries cannot collectively blow the wasm memory
    /// limit.
    ReadZipEntriesWithPrefix {
        guest_path: String,
        prefix: String,
        max_archive_bytes: u64,
        max_entry_bytes: u64,
        max_total_bytes: u64,
    },
    /// List every entry name in the zip archive at `guest_path`. Cheap
    /// next to `ReadZipEntryText` because no entry is decompressed; the
    /// host uses this to stream entries one-at-a-time when rewriting
    /// session zips, keeping peak memory at one entry instead of the
    /// whole archive.
    ListZipEntryNames {
        guest_path: String,
        max_archive_bytes: u64,
    },
    /// Walk the directory the host has preopened at `guest_root` and
    /// return lines that match `pattern`, optionally restricted to
    /// files whose relative path matches `glob`. The user-supplied
    /// regex is the threat surface here: the wasm fuel cap is the
    /// final backstop in case `regex`'s linear-time guarantee is
    /// ever broken by a future engine bug or a feature flag.
    SearchFileContents {
        guest_root: String,
        pattern: String,
        glob: Option<String>,
        max_results: u64,
        max_file_bytes: u64,
        max_total_bytes: u64,
    },
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

#[derive(Serialize)]
struct ReadResult {
    content: String,
}

#[derive(Serialize)]
struct EntriesResult {
    entries: std::collections::HashMap<String, String>,
}

#[derive(Serialize)]
struct NamesResult {
    names: Vec<String>,
}

/// Read at most `max_bytes` bytes from the file at `guest_path` (which
/// must resolve through a host-provided WASI preopen). Errors when:
///   - the file does not exist or the preopen does not cover its dir,
///   - the file is larger than `max_bytes`,
///   - the bytes are not valid UTF-8.
///
/// The size pre-check is on `metadata().len()` to fail fast without
/// streaming gigabytes through the guest's linear memory; the wasm
/// memory cap (`StoreLimits::memory_size`) is the backstop if the FS
/// metadata is unreliable.
fn read_bounded(guest_path: &str, max_bytes: u64) -> Result<String, std::io::Error> {
    let meta = std::fs::metadata(guest_path)?;
    if !meta.is_file() {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidInput,
            format!("not a regular file: {guest_path}"),
        ));
    }
    if meta.len() > max_bytes {
        return Err(std::io::Error::new(
            std::io::ErrorKind::FileTooLarge,
            format!(
                "{guest_path} is {} bytes, exceeds cap of {max_bytes}",
                meta.len()
            ),
        ));
    }
    std::fs::read_to_string(guest_path)
}

/// Open a zip archive through a host preopen and extract one named
/// entry as text. The whole archive is read into linear memory first
/// (bounded by `max_archive_bytes`) so the minimal parser in
/// `zip_reader.rs` can scan offsets without needing seek support --
/// `wasm32-wasip2`'s `std::fs::File` does not implement `ReadAt`,
/// which is why we cannot use either the `zip` crate or
/// `rc-zip-sync` here. `max_entry_bytes` caps the decompressed entry
/// independently; with that and the wasm `StoreLimits::memory_size`
/// in the host, a zip bomb traps the guest before reaching the host.
fn read_zip_entry_in_sandbox(
    guest_path: &str,
    entry_name: &str,
    max_archive_bytes: u64,
    max_entry_bytes: u64,
) -> Result<Option<String>, anyhow::Error> {
    let bytes = read_archive_bounded(guest_path, max_archive_bytes)?;
    let parsed = brokk_acp_sandbox::read_zip_entry_text(&bytes, entry_name, max_entry_bytes)?;
    Ok(parsed)
}

fn list_zip_entry_names_in_sandbox(
    guest_path: &str,
    max_archive_bytes: u64,
) -> Result<Vec<String>, anyhow::Error> {
    let bytes = read_archive_bounded(guest_path, max_archive_bytes)?;
    let names = brokk_acp_sandbox::list_zip_entry_names(&bytes)?;
    Ok(names)
}

fn read_zip_entries_with_prefix_in_sandbox(
    guest_path: &str,
    prefix: &str,
    max_archive_bytes: u64,
    max_entry_bytes: u64,
    max_total_bytes: u64,
) -> Result<std::collections::HashMap<String, String>, anyhow::Error> {
    let bytes = read_archive_bounded(guest_path, max_archive_bytes)?;
    let entries = brokk_acp_sandbox::read_zip_entries_with_prefix(
        &bytes,
        prefix,
        max_entry_bytes,
        max_total_bytes,
    )?;
    Ok(entries)
}

fn read_archive_bounded(
    guest_path: &str,
    max_archive_bytes: u64,
) -> Result<Vec<u8>, anyhow::Error> {
    let meta = std::fs::metadata(guest_path)?;
    if !meta.is_file() {
        anyhow::bail!("not a regular file: {guest_path}");
    }
    if meta.len() > max_archive_bytes {
        anyhow::bail!(
            "{guest_path} is {} bytes, archive exceeds cap of {max_archive_bytes}",
            meta.len()
        );
    }
    Ok(std::fs::read(guest_path)?)
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
            Request::SplitSkillFrontmatter { raw } => {
                match brokk_acp_sandbox::split_frontmatter(&raw) {
                    Ok((front, body)) => {
                        let payload = SplitResult {
                            frontmatter: front.to_string(),
                            body: body.to_string(),
                        };
                        serde_json::to_string(&OkResponse { id, ok: &payload })?
                    }
                    Err(err) => serde_json::to_string(&ErrResponse { id, err })?,
                }
            }
            Request::ReadFileBounded {
                guest_path,
                max_bytes,
            } => match read_bounded(&guest_path, max_bytes) {
                Ok(content) => {
                    let payload = ReadResult { content };
                    serde_json::to_string(&OkResponse { id, ok: &payload })?
                }
                Err(err) => {
                    let body = err.to_string();
                    serde_json::to_string(&ErrResponse { id, err: &body })?
                }
            },
            Request::ReadZipEntryText {
                guest_path,
                entry_name,
                max_archive_bytes,
                max_entry_bytes,
            } => match read_zip_entry_in_sandbox(
                &guest_path,
                &entry_name,
                max_archive_bytes,
                max_entry_bytes,
            ) {
                Ok(Some(content)) => {
                    let payload = ReadResult { content };
                    serde_json::to_string(&OkResponse { id, ok: &payload })?
                }
                Ok(None) => {
                    // Tag the absent-entry case so the host can map it
                    // back to `Ok(None)` rather than a hard error.
                    serde_json::to_string(&ErrResponse {
                        id,
                        err: "zip entry not found",
                    })?
                }
                Err(err) => {
                    let body = err.to_string();
                    serde_json::to_string(&ErrResponse { id, err: &body })?
                }
            },
            Request::ReadZipEntriesWithPrefix {
                guest_path,
                prefix,
                max_archive_bytes,
                max_entry_bytes,
                max_total_bytes,
            } => match read_zip_entries_with_prefix_in_sandbox(
                &guest_path,
                &prefix,
                max_archive_bytes,
                max_entry_bytes,
                max_total_bytes,
            ) {
                Ok(entries) => {
                    let payload = EntriesResult { entries };
                    serde_json::to_string(&OkResponse { id, ok: &payload })?
                }
                Err(err) => {
                    let body = err.to_string();
                    serde_json::to_string(&ErrResponse { id, err: &body })?
                }
            },
            Request::ListZipEntryNames {
                guest_path,
                max_archive_bytes,
            } => match list_zip_entry_names_in_sandbox(&guest_path, max_archive_bytes) {
                Ok(names) => {
                    let payload = NamesResult { names };
                    serde_json::to_string(&OkResponse { id, ok: &payload })?
                }
                Err(err) => {
                    let body = err.to_string();
                    serde_json::to_string(&ErrResponse { id, err: &body })?
                }
            },
            Request::SearchFileContents {
                guest_root,
                pattern,
                glob,
                max_results,
                max_file_bytes,
                max_total_bytes,
            } => match brokk_acp_sandbox::search_file_contents(
                std::path::Path::new(&guest_root),
                &pattern,
                glob.as_deref(),
                max_results as usize,
                max_file_bytes,
                max_total_bytes,
            ) {
                Ok(outcome) => serde_json::to_string(&OkResponse { id, ok: &outcome })?,
                Err(err) => {
                    let body = err.to_string();
                    serde_json::to_string(&ErrResponse { id, err: &body })?
                }
            },
        };
        writeln!(out, "{response_line}")?;
        out.flush()?;
    }
    Ok(())
}
