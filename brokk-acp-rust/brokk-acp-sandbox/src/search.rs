//! `searchFileContents` implementation, shared between the native
//! and wasm-sandboxed backends.
//!
//! Threat surface: an LLM-driven prompt can put an arbitrary regex
//! into the `pattern` argument. The `regex` crate is engineered to
//! be linear-time (no catastrophic backtracking), but the sandbox
//! adds two further safety nets:
//!
//!   1. Wasm fuel (`StoreLimits::set_fuel`) bounds total CPU per
//!      request, so even a pathological pattern that confuses the
//!      DFA cache cannot hang the agent.
//!   2. The wasm linear-memory limit bounds the regex's compiled
//!      automaton, so a huge alternation cannot blow the host's
//!      address space.
//!
//! File reads use `std::fs`, which on wasm goes through the host's
//! WASI preopens -- the sandbox only sees the directory tree the
//! host explicitly hands it.

use std::path::{Path, PathBuf};

use regex::Regex;
use serde::{Deserialize, Serialize};

/// Hard cap on individual file size scanned. Mirrors the host's
/// `SEARCH_MAX_FILE_BYTES` (5 MiB at the time of writing). Kept here
/// so the sandbox enforces it even if the host caller forgets.
pub const SEARCH_DEFAULT_MAX_FILE_BYTES: u64 = 5 * 1024 * 1024;
/// Default total-bytes-scanned budget. A swarm of just-under-cap
/// files cannot collectively force the sandbox to allocate beyond
/// this without tripping the wasm memory limit anyway, but a smaller
/// explicit budget fails fast.
pub const SEARCH_DEFAULT_MAX_TOTAL_BYTES: u64 = 256 * 1024 * 1024;
/// Number of leading bytes we sniff for a NUL to classify a file as
/// binary. Matches the host's prior heuristic exactly -- shrinking the
/// window lets a binary file whose first NUL is past the sniff point
/// slip through and `read_to_string` does NOT reject embedded NULs, so
/// the bytes would end up in the LLM-visible match output.
const BINARY_SNIFF_BYTES: usize = 8192;

/// A single line that matched the requested pattern. Path is given
/// relative to the root the search started from.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SearchMatch {
    pub path: String,
    pub line_num: usize,
    pub line: String,
}

/// Outcome of one search. `truncated` is true when the search hit
/// `max_results` and the caller should append a `... truncated`
/// marker.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchOutcome {
    pub matches: Vec<SearchMatch>,
    pub truncated: bool,
}

/// Compile errors are surfaced as a parse-style error so the LLM can
/// fix the pattern; runtime errors (size cap, walk failure) are
/// logged-and-skipped to mirror the host's prior behaviour.
#[derive(Debug)]
pub enum SearchError {
    InvalidRegex(String),
    InvalidGlob(String),
    Walk(String),
}

impl std::fmt::Display for SearchError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidRegex(s) => write!(f, "Invalid regex: {s}"),
            Self::InvalidGlob(s) => write!(f, "Invalid glob: {s}"),
            Self::Walk(s) => write!(f, "Cannot walk search root: {s}"),
        }
    }
}

impl std::error::Error for SearchError {}

/// Walk `root`, find lines matching `pattern` (optionally constrained
/// to files whose relative path matches `glob`), return up to
/// `max_results` matches. Pure-rust + std::fs, so the same code runs
/// natively and inside the wasm sandbox; the wasm path additionally
/// gets fuel + memory + preopen isolation.
pub fn search(
    root: &Path,
    pattern: &str,
    glob: Option<&str>,
    max_results: usize,
    max_file_bytes: u64,
    max_total_bytes: u64,
) -> Result<SearchOutcome, SearchError> {
    let re = Regex::new(pattern).map_err(|e| SearchError::InvalidRegex(e.to_string()))?;
    let glob_re = match glob {
        Some(g) => Some(compile_glob(g)?),
        None => None,
    };

    // Canonicalize once so the relative-path display is stable across
    // hosts that resolve symlinks differently. On wasi the underlying
    // syscall walks through preopens, so this stays sandbox-safe.
    let root_canonical = root.canonicalize().unwrap_or_else(|_| root.to_path_buf());

    let mut matches: Vec<SearchMatch> = Vec::new();
    let mut total_scanned: u64 = 0;

    for entry in walkdir::WalkDir::new(root)
        .into_iter()
        .filter_entry(|e| {
            // Always traverse the root: walkdir invokes `filter_entry`
            // on `root` itself, and the host's test fixtures (plus
            // tempfile's `TempDir`) can land in a dir whose name
            // legitimately starts with `.` (`.tmpXXXX`). Pruning the
            // root prunes every descendant and silently returns
            // zero matches, which masked a bug for an embarrassing
            // amount of time.
            if e.depth() == 0 {
                return true;
            }
            let name = e.file_name().to_string_lossy();
            !name.starts_with('.')
                && name != "node_modules"
                && name != "target"
                && name != "__pycache__"
        })
        .flatten()
    {
        if !entry.file_type().is_file() {
            continue;
        }
        let path = entry.path();
        let rel = path
            .strip_prefix(&root_canonical)
            .or_else(|_| path.strip_prefix(root))
            .unwrap_or(path);
        let rel_str = rel.to_string_lossy();

        if let Some(g) = &glob_re
            && !g.is_match(&rel_str)
        {
            continue;
        }

        match entry.metadata() {
            Ok(md) if md.len() > max_file_bytes => continue,
            Ok(md) => {
                total_scanned = total_scanned.saturating_add(md.len());
                if total_scanned > max_total_bytes {
                    // Budget exhausted: stop walking rather than continue
                    // and let an attacker chain many just-under-cap files
                    // into an unbounded scan.
                    break;
                }
            }
            Err(_) => continue,
        }

        if is_binary_file(path) {
            continue;
        }

        let content = match std::fs::read_to_string(path) {
            Ok(c) => c,
            Err(_) => continue,
        };

        for (line_num, line) in content.lines().enumerate() {
            if re.is_match(line) {
                matches.push(SearchMatch {
                    path: rel_str.to_string(),
                    line_num: line_num + 1,
                    line: line.trim().to_string(),
                });
                if matches.len() >= max_results {
                    return Ok(SearchOutcome {
                        matches,
                        truncated: true,
                    });
                }
            }
        }
    }

    Ok(SearchOutcome {
        matches,
        truncated: false,
    })
}

/// Best-effort binary sniff: read up to BINARY_SNIFF_BYTES and treat
/// the file as binary if any of those bytes is NUL. Matches the
/// host's prior heuristic exactly so result sets do not change across
/// backends.
fn is_binary_file(path: &Path) -> bool {
    let mut buf = [0u8; BINARY_SNIFF_BYTES];
    let mut f = match std::fs::File::open(path) {
        Ok(f) => f,
        Err(_) => return true,
    };
    use std::io::Read;
    let n = match f.read(&mut buf) {
        Ok(n) => n,
        Err(_) => return true,
    };
    buf[..n].contains(&0)
}

/// Translate the host's "simple glob" syntax (`*.rs`, `**/*.java`)
/// to an anchored regex. Mirrors the host's prior conversion so the
/// observable set of matched paths is identical across backends.
fn compile_glob(g: &str) -> Result<Regex, SearchError> {
    let re_str = g
        .replace('.', "\\.")
        .replace("**", "<<GLOBSTAR>>")
        .replace('*', "[^/]*")
        .replace("<<GLOBSTAR>>", ".*");
    Regex::new(&format!("{re_str}$")).map_err(|e| SearchError::InvalidGlob(e.to_string()))
}

// `PathBuf` import is kept for callers building a root via construction;
// the search function itself works directly with `&Path`.
#[allow(dead_code)]
fn _phantom_pathbuf(p: PathBuf) -> PathBuf {
    p
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Pins the binary-sniff window at 8192 bytes (matching the
    /// pre-sandbox `tools/filesystem.rs` heuristic). A file whose
    /// pattern-matching region is preceded by 4 KiB of legitimate
    /// text but contains a NUL within the second 4 KiB must be
    /// classified as binary -- shrinking the window past the NUL
    /// would let the byte slip into `read_to_string`, and `regex`
    /// happily matches across embedded NULs, so the LLM's output
    /// would carry raw binary.
    #[test]
    fn nul_between_4k_and_8k_is_classified_as_binary() {
        let tmp = std::env::temp_dir().join(format!(
            "brokk-acp-sandbox-nul-sniff-{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&tmp).unwrap();
        let path = tmp.join("payload.bin");

        let mut body: Vec<u8> = b"a".repeat(5000);
        body.push(0);
        body.extend_from_slice(b"needle\n");
        std::fs::write(&path, &body).unwrap();

        assert!(
            is_binary_file(&path),
            "file with NUL at offset 5000 must be classified as binary"
        );

        // End-to-end: a search for `needle` must return zero matches
        // because the file should have been skipped on the binary
        // sniff, not opened and string-matched.
        let outcome = search(&tmp, "needle", None, 100, 1 << 20, 1 << 30).unwrap();
        assert!(
            outcome.matches.is_empty(),
            "binary-classified file must not produce matches, got: {:?}",
            outcome.matches
        );

        std::fs::remove_dir_all(&tmp).ok();
    }
}
