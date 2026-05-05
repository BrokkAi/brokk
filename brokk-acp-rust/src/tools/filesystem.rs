use super::{ToolResult, ToolStatus, safe_resolve, safe_resolve_for_write};
use regex::Regex;
use std::io::Read;
use std::path::Path;
use walkdir::WalkDir;

/// Hard cap on individual file size scanned by `search_file_contents`.
/// Files larger than this are skipped to keep memory bounded on big repos.
const SEARCH_MAX_FILE_BYTES: u64 = 1_048_576; // 1 MiB

/// Number of leading bytes inspected for NUL bytes to classify a file as binary.
const BINARY_SNIFF_BYTES: usize = 8192;

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

pub fn search_file_contents(
    cwd: &Path,
    pattern: &str,
    glob_filter: Option<&str>,
    max_results: usize,
) -> ToolResult {
    let re = match Regex::new(pattern) {
        Ok(r) => r,
        Err(e) => {
            return ToolResult {
                status: ToolStatus::RequestError,
                output: format!("Invalid regex '{}': {}", pattern, e),
            };
        }
    };

    let glob_re = glob_filter.and_then(|g| {
        // Convert simple glob to regex: *.rs -> .*\.rs$, **/*.java -> .*\.java$
        let re_str = g
            .replace('.', "\\.")
            .replace("**", "<<GLOBSTAR>>")
            .replace('*', "[^/]*")
            .replace("<<GLOBSTAR>>", ".*");
        Regex::new(&format!("{}$", re_str)).ok()
    });

    let cwd_canonical = match cwd.canonicalize() {
        Ok(c) => c,
        Err(e) => {
            return ToolResult {
                status: ToolStatus::InternalError,
                output: format!("Cannot resolve cwd: {}", e),
            };
        }
    };

    let mut results = Vec::new();
    for entry in WalkDir::new(cwd)
        .into_iter()
        .filter_entry(|e| {
            let name = e.file_name().to_string_lossy();
            // Skip hidden dirs and common noise
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
            .strip_prefix(&cwd_canonical)
            .or_else(|_| path.strip_prefix(cwd))
            .unwrap_or(path);
        let rel_str = rel.to_string_lossy();

        if let Some(glob_re) = &glob_re
            && !glob_re.is_match(&rel_str)
        {
            continue;
        }

        // Size cap: skip files above SEARCH_MAX_FILE_BYTES.
        match entry.metadata() {
            Ok(md) if md.len() > SEARCH_MAX_FILE_BYTES => continue,
            Err(_) => continue,
            _ => {}
        }

        // Sniff the first BINARY_SNIFF_BYTES bytes for a NUL. If present, treat as binary and skip.
        if is_binary_file(path) {
            continue;
        }

        let content = match std::fs::read_to_string(path) {
            Ok(c) => c,
            Err(_) => continue, // skip non-UTF8 or unreadable files
        };

        for (line_num, line) in content.lines().enumerate() {
            if re.is_match(line) {
                results.push(format!("{}:{}: {}", rel_str, line_num + 1, line.trim()));
                if results.len() >= max_results {
                    results.push(format!("... truncated at {} results", max_results));
                    return ToolResult {
                        status: ToolStatus::Success,
                        output: results.join("\n"),
                    };
                }
            }
        }
    }

    if results.is_empty() {
        ToolResult {
            status: ToolStatus::Success,
            output: format!("No matches found for '{}'", pattern),
        }
    } else {
        ToolResult {
            status: ToolStatus::Success,
            output: results.join("\n"),
        }
    }
}

/// Classify a file as binary if any of the first BINARY_SNIFF_BYTES bytes is NUL.
/// Files we cannot open are classified as binary so we skip them.
fn is_binary_file(path: &Path) -> bool {
    let mut file = match std::fs::File::open(path) {
        Ok(f) => f,
        Err(_) => return true,
    };
    let mut buf = [0u8; BINARY_SNIFF_BYTES];
    let n = match file.read(&mut buf) {
        Ok(n) => n,
        Err(_) => return true,
    };
    buf[..n].contains(&0)
}

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

    /// `is_binary_file` returns true for non-existent paths so the search
    /// loop's "skip and move on" semantics are preserved when a file
    /// disappears mid-walk.
    #[test]
    fn is_binary_file_treats_missing_as_binary() {
        let cwd = fresh_tmp_dir("missing-binary");
        assert!(is_binary_file(&cwd.join("does-not-exist")));
        std::fs::remove_dir_all(&cwd).ok();
    }
}
