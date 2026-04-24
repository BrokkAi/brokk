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
