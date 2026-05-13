use super::{ToolResult, ToolStatus, safe_resolve, safe_resolve_for_write};
use std::path::Path;

/// Hard cap on individual file size scanned by `search_file_contents`.
/// Files larger than this are skipped to keep memory bounded on big repos.
const SEARCH_MAX_FILE_BYTES: u64 = 1_048_576; // 1 MiB
/// Total-bytes-scanned budget across the whole walk. Together with
/// the per-file cap this bounds the worst case the sandbox has to
/// chew through for a single `searchFileContents` call.
const SEARCH_MAX_TOTAL_BYTES: u64 = 256 * 1024 * 1024;

pub fn read_file(cwd: &Path, path: &str) -> ToolResult {
    let resolved = match safe_resolve(cwd, path) {
        Ok(p) => p,
        Err(e) => {
            return ToolResult {
                status: ToolStatus::RequestError,
                output: e,
            };
        }
    };
    match std::fs::read_to_string(&resolved) {
        Ok(content) => ToolResult {
            status: ToolStatus::Success,
            output: content,
        },
        Err(e) => ToolResult {
            status: ToolStatus::RequestError,
            output: format!("Failed to read '{}': {}", path, e),
        },
    }
}

pub fn write_file(cwd: &Path, path: &str, content: &str) -> ToolResult {
    let resolved = match safe_resolve_for_write(cwd, path) {
        Ok(p) => p,
        Err(e) => {
            return ToolResult {
                status: ToolStatus::RequestError,
                output: e,
            };
        }
    };
    // Create parent directories if needed
    if let Some(parent) = resolved.parent()
        && let Err(e) = std::fs::create_dir_all(parent)
    {
        return ToolResult {
            status: ToolStatus::InternalError,
            output: format!("Failed to create directories for '{}': {}", path, e),
        };
    }
    match std::fs::write(&resolved, content) {
        Ok(()) => ToolResult {
            status: ToolStatus::Success,
            output: format!("Written {} bytes to '{}'", content.len(), path),
        },
        Err(e) => ToolResult {
            status: ToolStatus::RequestError,
            output: format!("Failed to write '{}': {}", path, e),
        },
    }
}

pub fn list_directory(cwd: &Path, path: &str) -> ToolResult {
    let resolved = match safe_resolve(cwd, path) {
        Ok(p) => p,
        Err(e) => {
            return ToolResult {
                status: ToolStatus::RequestError,
                output: e,
            };
        }
    };
    let entries = match std::fs::read_dir(&resolved) {
        Ok(e) => e,
        Err(e) => {
            return ToolResult {
                status: ToolStatus::RequestError,
                output: format!("Failed to list '{}': {}", path, e),
            };
        }
    };

    let mut lines: Vec<String> = Vec::new();
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        let is_dir = entry.file_type().map(|t| t.is_dir()).unwrap_or(false);
        if is_dir {
            lines.push(format!("{}/", name));
        } else {
            lines.push(name);
        }
    }
    lines.sort();
    ToolResult {
        status: ToolStatus::Success,
        output: lines.join("\n"),
    }
}

/// `searchFileContents` tool, routed through `SandboxBackend` so the
/// user-controlled regex runs inside the wasm sandbox by default.
/// The `regex` crate is engineered to be linear-time, but a future
/// engine bug or accidental enabling of a backtracking feature
/// shouldn't be able to hang the agent -- the wasm fuel cap is the
/// definitive backstop.
pub fn search_file_contents(
    cwd: &Path,
    pattern: &str,
    glob_filter: Option<&str>,
    max_results: usize,
) -> ToolResult {
    let outcome = match crate::sandbox_backend::global().search_file_contents(
        cwd,
        pattern,
        glob_filter,
        max_results as u64,
        SEARCH_MAX_FILE_BYTES,
        SEARCH_MAX_TOTAL_BYTES,
    ) {
        Ok(o) => o,
        Err(brokk_acp_sandbox::SearchError::InvalidRegex(msg)) => {
            return ToolResult {
                status: ToolStatus::RequestError,
                output: format!("Invalid regex '{}': {}", pattern, msg),
            };
        }
        Err(brokk_acp_sandbox::SearchError::InvalidGlob(msg)) => {
            return ToolResult {
                status: ToolStatus::RequestError,
                output: format!("Invalid glob: {msg}"),
            };
        }
        Err(brokk_acp_sandbox::SearchError::Walk(msg)) => {
            return ToolResult {
                status: ToolStatus::InternalError,
                output: msg,
            };
        }
    };

    if outcome.matches.is_empty() {
        return ToolResult {
            status: ToolStatus::Success,
            output: format!("No matches found for '{}'", pattern),
        };
    }

    let mut lines: Vec<String> = outcome
        .matches
        .iter()
        .map(|m| format!("{}:{}: {}", m.path, m.line_num, m.line))
        .collect();
    if outcome.truncated {
        lines.push(format!("... truncated at {} results", max_results));
    }
    ToolResult {
        status: ToolStatus::Success,
        output: lines.join("\n"),
    }
}

// `is_binary_file` and `BINARY_SNIFF_BYTES` moved to the shared
// `brokk_acp_sandbox::search` module so the native and wasm-sandboxed
// backends classify binary files identically.

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    /// Allocate a fresh empty directory under the system temp dir for one test
    /// to scribble in. Caller is responsible for cleaning it up.
    fn fresh_tmp_dir(label: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "brokk-acp-rust-fs-{}-{}",
            label,
            uuid::Uuid::new_v4()
        ));
        std::fs::create_dir_all(&dir).expect("create tmp dir");
        dir
    }

    /// Round-trip: write then read returns the same content. Verifies both
    /// dispatch paths land on the same on-disk file via the cwd-relative
    /// resolver.
    #[test]
    fn write_then_read_round_trips() {
        let cwd = fresh_tmp_dir("rw");
        let w = write_file(&cwd, "hello.txt", "world");
        assert!(matches!(w.status, ToolStatus::Success));
        assert!(w.output.contains("Written 5 bytes"));

        let r = read_file(&cwd, "hello.txt");
        assert!(matches!(r.status, ToolStatus::Success));
        assert_eq!(r.output, "world");

        std::fs::remove_dir_all(&cwd).ok();
    }

    /// `write_file` creates intermediate directories that don't exist yet
    /// (mkdir -p semantics) -- otherwise the LLM would have to chain a
    /// `runShellCommand mkdir` for every nested write.
    #[test]
    fn write_file_creates_missing_parent_directories() {
        let cwd = fresh_tmp_dir("mkdir-p");
        let w = write_file(&cwd, "a/b/c/note.md", "ok");
        assert!(matches!(w.status, ToolStatus::Success), "{}", w.output);
        assert!(cwd.join("a/b/c/note.md").exists());
        std::fs::remove_dir_all(&cwd).ok();
    }

    /// `read_file` for a missing path is a `RequestError` (the LLM can
    /// recover by listing the directory), not an `InternalError`. The
    /// rejection comes from `safe_resolve`'s canonicalize step, which
    /// requires the target to exist.
    #[test]
    fn read_file_missing_path_is_request_error() {
        let cwd = fresh_tmp_dir("missing");
        let r = read_file(&cwd, "nope.txt");
        assert!(matches!(r.status, ToolStatus::RequestError));
        assert!(
            r.output.contains("nope.txt"),
            "expected error to mention path, got: {}",
            r.output
        );
        std::fs::remove_dir_all(&cwd).ok();
    }

    /// Path-traversal: any attempt to escape cwd via `..` must be rejected
    /// before we touch the filesystem. Coverage for the safe_resolve gate.
    #[test]
    fn read_file_rejects_escape_via_dotdot() {
        let cwd = fresh_tmp_dir("escape-read");
        let r = read_file(&cwd, "../../../etc/passwd");
        assert!(matches!(r.status, ToolStatus::RequestError));
        std::fs::remove_dir_all(&cwd).ok();
    }

    #[test]
    fn write_file_rejects_escape_via_dotdot() {
        let cwd = fresh_tmp_dir("escape-write");
        let w = write_file(&cwd, "../escaped.txt", "x");
        assert!(matches!(w.status, ToolStatus::RequestError));
        // The error message either mentions escape or unsupported `..`.
        assert!(
            w.output.contains("escapes") || w.output.contains(".."),
            "expected traversal error, got: {}",
            w.output
        );
        std::fs::remove_dir_all(&cwd).ok();
    }

    /// `list_directory` sorts entries alphabetically and suffixes
    /// directories with `/` so the LLM can distinguish them without an
    /// extra round-trip.
    #[test]
    fn list_directory_sorts_and_marks_subdirs() {
        let cwd = fresh_tmp_dir("ls");
        std::fs::create_dir_all(cwd.join("zdir")).unwrap();
        std::fs::write(cwd.join("a.txt"), "").unwrap();
        std::fs::write(cwd.join("m.txt"), "").unwrap();

        let r = list_directory(&cwd, ".");
        assert!(matches!(r.status, ToolStatus::Success));
        let lines: Vec<&str> = r.output.lines().collect();
        assert_eq!(lines, vec!["a.txt", "m.txt", "zdir/"]);
        std::fs::remove_dir_all(&cwd).ok();
    }

    /// `list_directory` on a missing path is a `RequestError`.
    #[test]
    fn list_directory_missing_is_request_error() {
        let cwd = fresh_tmp_dir("ls-missing");
        let r = list_directory(&cwd, "no-such-dir");
        assert!(matches!(r.status, ToolStatus::RequestError));
        std::fs::remove_dir_all(&cwd).ok();
    }

    /// Invalid regex must surface as a `RequestError` so the LLM can fix
    /// the pattern, not an `InternalError`.
    #[test]
    fn search_file_contents_invalid_regex_is_request_error() {
        let cwd = fresh_tmp_dir("bad-regex");
        let r = search_file_contents(&cwd, "(unclosed", None, 100);
        assert!(matches!(r.status, ToolStatus::RequestError));
        assert!(r.output.contains("Invalid regex"));
        std::fs::remove_dir_all(&cwd).ok();
    }

    /// Search returns matches in `path:line: snippet` format, with a
    /// "No matches" message when nothing hits.
    #[test]
    fn search_file_contents_finds_matching_lines_and_reports_empty() {
        let cwd = fresh_tmp_dir("search-hit");
        std::fs::write(cwd.join("a.txt"), "alpha\nbeta\ngamma\n").unwrap();
        std::fs::write(cwd.join("b.txt"), "delta\n").unwrap();

        let hit = search_file_contents(&cwd, "beta", None, 100);
        assert!(matches!(hit.status, ToolStatus::Success));
        assert!(
            hit.output.contains("a.txt:2: beta"),
            "expected match line, got: {}",
            hit.output
        );

        let miss = search_file_contents(&cwd, "no-such-token", None, 100);
        assert!(matches!(miss.status, ToolStatus::Success));
        assert!(miss.output.contains("No matches found"));
        std::fs::remove_dir_all(&cwd).ok();
    }

    /// Glob filter must restrict the walk to matching files; non-matching
    /// files are not searched even if they contain the pattern.
    #[test]
    fn search_file_contents_glob_filter_limits_search() {
        let cwd = fresh_tmp_dir("search-glob");
        std::fs::write(cwd.join("keep.rs"), "needle\n").unwrap();
        std::fs::write(cwd.join("skip.txt"), "needle\n").unwrap();

        let r = search_file_contents(&cwd, "needle", Some("*.rs"), 100);
        assert!(matches!(r.status, ToolStatus::Success));
        assert!(r.output.contains("keep.rs"));
        assert!(
            !r.output.contains("skip.txt"),
            "glob *.rs must exclude skip.txt, got: {}",
            r.output
        );
        std::fs::remove_dir_all(&cwd).ok();
    }

    /// `max_results` must cap output and append a `... truncated` marker so
    /// the LLM knows there are more matches it didn't see.
    #[test]
    fn search_file_contents_truncates_at_max_results() {
        let cwd = fresh_tmp_dir("search-cap");
        let body: String = (0..20).map(|_| "needle\n").collect();
        std::fs::write(cwd.join("a.txt"), body).unwrap();

        let r = search_file_contents(&cwd, "needle", None, 3);
        assert!(matches!(r.status, ToolStatus::Success));
        // 3 matches + 1 truncation marker = 4 lines.
        assert_eq!(r.output.lines().count(), 4);
        assert!(r.output.contains("... truncated at 3 results"));
        std::fs::remove_dir_all(&cwd).ok();
    }

    /// Files containing a NUL byte in the first sniff window must be
    /// classified as binary and skipped, even if they would otherwise match
    /// the regex.
    #[test]
    fn search_file_contents_skips_binary_files() {
        let cwd = fresh_tmp_dir("binary");
        // NUL early so the sniff catches it; "needle" appears literally
        // after the NUL but the file should never be opened.
        let mut bytes = vec![b'h', b'i', 0u8, b'\n'];
        bytes.extend_from_slice(b"needle\n");
        std::fs::write(cwd.join("data.bin"), bytes).unwrap();
        std::fs::write(cwd.join("real.txt"), "needle\n").unwrap();

        let r = search_file_contents(&cwd, "needle", None, 100);
        assert!(matches!(r.status, ToolStatus::Success));
        assert!(r.output.contains("real.txt"));
        assert!(
            !r.output.contains("data.bin"),
            "binary file must be skipped, got: {}",
            r.output
        );
        std::fs::remove_dir_all(&cwd).ok();
    }

    /// Hidden directories (`.git`), `node_modules`, `target`, and
    /// `__pycache__` are pruned by `filter_entry` so the walk doesn't
    /// drown in transient build output.
    #[test]
    fn search_file_contents_skips_well_known_noise_directories() {
        let cwd = fresh_tmp_dir("noise");
        for noisy in [".git", "node_modules", "target", "__pycache__"] {
            std::fs::create_dir_all(cwd.join(noisy)).unwrap();
            std::fs::write(cwd.join(noisy).join("hit.txt"), "needle\n").unwrap();
        }
        std::fs::write(cwd.join("real.txt"), "needle\n").unwrap();

        let r = search_file_contents(&cwd, "needle", None, 100);
        assert!(matches!(r.status, ToolStatus::Success));
        assert!(r.output.contains("real.txt"));
        for noisy in [".git", "node_modules", "target", "__pycache__"] {
            assert!(
                !r.output.contains(noisy),
                "noise dir '{}' must be pruned, got: {}",
                noisy,
                r.output
            );
        }
        std::fs::remove_dir_all(&cwd).ok();
    }

    // The binary-sniff test moved to `brokk-acp-sandbox::search` along
    // with `is_binary_file` itself; the host fn is gone and the test
    // would now be redundant with the sandbox unit test.
}
