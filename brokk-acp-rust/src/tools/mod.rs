mod filesystem;
pub mod sandbox;
mod shell;

use crate::bifrost_client::BifrostClient;
use crate::llm_client::{FunctionDef, ToolDefinition};
use agent_client_protocol::schema::ToolKind;
use sandbox::SandboxPolicy;
use serde_json::json;
use std::path::{Path, PathBuf};
use std::sync::Arc;

/// Result of executing a tool.
pub struct ToolResult {
    pub status: ToolStatus,
    pub output: String,
}

pub enum ToolStatus {
    Success,
    RequestError,
    InternalError,
}

/// Single source of truth for per-tool metadata (`ToolKind` for the
/// permission gate, `display_name` for the UI fallback). Adding a new
/// tool means adding one row here; the dispatcher in `execute` derives
/// builtin routing from the inline `match`, and bifrost-loaded tools we
/// don't recognize fall through to `ToolKind::Other` / "Executing tool".
struct ToolMeta {
    name: &'static str,
    kind: ToolKind,
    display_name: &'static str,
}

const TOOLS: &[ToolMeta] = &[
    // --- Built-in tools (executed inline in `ToolRegistry::execute`) -------
    ToolMeta {
        name: "think",
        kind: ToolKind::Think,
        display_name: "Thinking",
    },
    ToolMeta {
        name: "readFile",
        kind: ToolKind::Read,
        display_name: "Reading file",
    },
    ToolMeta {
        name: "writeFile",
        kind: ToolKind::Edit,
        display_name: "Writing file",
    },
    ToolMeta {
        name: "listDirectory",
        kind: ToolKind::Read,
        display_name: "Listing directory",
    },
    ToolMeta {
        name: "searchFileContents",
        kind: ToolKind::Search,
        display_name: "Searching file contents",
    },
    ToolMeta {
        name: "runShellCommand",
        kind: ToolKind::Execute,
        display_name: "Running shell command",
    },
    // --- Bifrost-loaded tools (dispatched via `execute_bifrost`) -----------
    // Listed here so the permission gate can classify them; their actual
    // execution is delegated to the bifrost subprocess. The cross-check
    // in `bifrost_client::tests::handshake_and_call_search_tools` keeps
    // this list in sync with what the running bifrost subprocess exposes.
    ToolMeta {
        name: "get_summaries",
        kind: ToolKind::Read,
        display_name: "Getting code summaries",
    },
    ToolMeta {
        name: "get_active_workspace",
        kind: ToolKind::Read,
        display_name: "Getting active workspace",
    },
    ToolMeta {
        name: "search_symbols",
        kind: ToolKind::Search,
        display_name: "Searching for symbols",
    },
    ToolMeta {
        name: "get_symbol_locations",
        kind: ToolKind::Search,
        display_name: "Finding symbol locations",
    },
    ToolMeta {
        name: "get_symbol_summaries",
        kind: ToolKind::Search,
        display_name: "Getting symbol summaries",
    },
    ToolMeta {
        name: "get_symbol_sources",
        kind: ToolKind::Search,
        display_name: "Fetching symbol source",
    },
    ToolMeta {
        name: "list_symbols",
        kind: ToolKind::Search,
        display_name: "Listing symbols",
    },
    ToolMeta {
        name: "most_relevant_files",
        kind: ToolKind::Search,
        display_name: "Finding related files",
    },
    // `activate_workspace` and `refresh` mutate analyzer state, so they
    // stay `Other` rather than `Read`: prompted in `default`, refused in
    // `readOnly`.
    ToolMeta {
        name: "activate_workspace",
        kind: ToolKind::Other,
        display_name: "Activating workspace",
    },
    ToolMeta {
        name: "refresh",
        kind: ToolKind::Other,
        display_name: "Refreshing analyzer index",
    },
];

fn tool_meta(name: &str) -> Option<&'static ToolMeta> {
    TOOLS.iter().find(|t| t.name == name)
}

/// `true` iff `name` has a row in the `TOOLS` metadata table. Used by
/// the bifrost handshake test to flag drift when bifrost adds or
/// renames a tool without a matching `TOOLS` entry (which would
/// otherwise silently fall back to `ToolKind::Other` / "Executing
/// tool" in the permission gate and UI).
#[cfg(test)]
pub(crate) fn is_known_tool(name: &str) -> bool {
    tool_meta(name).is_some()
}

/// Built-in tool names handled by the inline `match` in
/// `ToolRegistry::execute`. Used by tests to keep the metadata table
/// in sync with the actual builtin dispatch.
#[cfg(test)]
const BUILTIN_TOOL_NAMES: &[&str] = &[
    "think",
    "readFile",
    "writeFile",
    "listDirectory",
    "searchFileContents",
    "runShellCommand",
];

/// Unified tool registry: filesystem tools + shell + think + (optionally) bifrost.
pub struct ToolRegistry {
    cwd: PathBuf,
    bifrost: Option<Arc<BifrostClient>>,
}

impl ToolRegistry {
    /// Working directory this registry is rooted in.
    pub(crate) fn cwd(&self) -> &Path {
        &self.cwd
    }

    pub async fn new(cwd: PathBuf, bifrost_binary: Option<&Path>) -> Self {
        // Best-effort sweep of any stale seatbelt policy files left by a
        // previous SIGKILL/panic. Bounded by file age so we don't yank a
        // profile from a concurrent in-flight shell call.
        sandbox::cleanup_stale_policy_files();

        let bifrost = match bifrost_binary {
            Some(bin) => match BifrostClient::spawn(bin, &cwd).await {
                Ok(client) => Some(Arc::new(client)),
                Err(err) => {
                    tracing::warn!(
                        cwd = %cwd.display(),
                        binary = %bin.display(),
                        %err,
                        "bifrost subprocess failed to start; code-intelligence tools disabled for this session"
                    );
                    None
                }
            },
            None => {
                tracing::debug!("bifrost binary not configured; code-intelligence tools disabled");
                None
            }
        };
        Self { cwd, bifrost }
    }

    /// All tool definitions for the OpenAI tools parameter.
    pub fn tool_definitions(&self) -> Vec<ToolDefinition> {
        let mut defs = vec![
            tool_def(
                "think",
                "Use this tool to think through a problem step by step before acting. The input is not used for anything -- it is just a scratchpad for your thoughts.",
                json!({
                    "type": "object",
                    "properties": {
                        "thought": {
                            "type": "string",
                            "description": "Your reasoning or thought process."
                        }
                    },
                    "required": ["thought"]
                }),
            ),
            tool_def(
                "readFile",
                "Read the contents of a file. Path is relative to the working directory.",
                json!({
                    "type": "object",
                    "properties": {
                        "path": {
                            "type": "string",
                            "description": "Relative path to the file to read."
                        }
                    },
                    "required": ["path"]
                }),
            ),
            tool_def(
                "writeFile",
                "Write content to a file, creating it if it does not exist. Path is relative to the working directory.",
                json!({
                    "type": "object",
                    "properties": {
                        "path": {
                            "type": "string",
                            "description": "Relative path to the file to write."
                        },
                        "content": {
                            "type": "string",
                            "description": "Content to write to the file."
                        }
                    },
                    "required": ["path", "content"]
                }),
            ),
            tool_def(
                "listDirectory",
                "List files and directories at the given path. Path is relative to the working directory.",
                json!({
                    "type": "object",
                    "properties": {
                        "path": {
                            "type": "string",
                            "description": "Relative path to the directory to list. Use '.' for the working directory root."
                        }
                    },
                    "required": ["path"]
                }),
            ),
            tool_def(
                "searchFileContents",
                "Search for a regex pattern across files in the working directory. Returns matching lines with file paths and line numbers.",
                json!({
                    "type": "object",
                    "properties": {
                        "pattern": {
                            "type": "string",
                            "description": "Regex pattern to search for."
                        },
                        "glob": {
                            "type": "string",
                            "description": "Optional glob to filter files (e.g. '*.rs', '**/*.java'). Defaults to all files."
                        },
                        "maxResults": {
                            "type": "integer",
                            "default": 50,
                            "description": "Maximum number of matching lines to return."
                        }
                    },
                    "required": ["pattern"]
                }),
            ),
            tool_def(
                "runShellCommand",
                "Execute a shell command in the working directory. Returns stdout and stderr. Use for build, test, git, or other CLI operations.",
                json!({
                    "type": "object",
                    "properties": {
                        "command": {
                            "type": "string",
                            "description": "The shell command to execute (passed to sh -c)."
                        },
                        "timeoutSeconds": {
                            "type": "integer",
                            "default": 60,
                            "description": "Maximum execution time in seconds."
                        }
                    },
                    "required": ["command"]
                }),
            ),
        ];
        if let Some(client) = self.bifrost.as_ref() {
            for tool in client.tools() {
                defs.push(tool_def(
                    &tool.name,
                    &tool.description,
                    tool.input_schema.clone(),
                ));
            }
        }
        defs
    }

    /// Execute a tool by name with JSON arguments.
    ///
    /// SECURITY: callers MUST consult `tool_loop::consult_gate` first.
    /// `pub(crate)` is intentional -- this is the trust boundary; outside
    /// callers should not be able to dispatch tools without the gate.
    ///
    /// `policy` controls the OS-level sandbox applied to `runShellCommand`.
    /// Other tools ignore it (their own seams, e.g. `safe_resolve_for_write`,
    /// enforce path containment).
    pub(crate) async fn execute(
        &self,
        name: &str,
        args: serde_json::Value,
        policy: SandboxPolicy,
    ) -> ToolResult {
        match name {
            "think" => {
                let thought = args.get("thought").and_then(|v| v.as_str()).unwrap_or("");
                ToolResult {
                    status: ToolStatus::Success,
                    output: format!("Thought noted: {thought}"),
                }
            }
            "readFile" => {
                let path = args.get("path").and_then(|v| v.as_str()).unwrap_or("");
                filesystem::read_file(&self.cwd, path)
            }
            "writeFile" => {
                let path = args.get("path").and_then(|v| v.as_str()).unwrap_or("");
                let content = args.get("content").and_then(|v| v.as_str()).unwrap_or("");
                filesystem::write_file(&self.cwd, path, content)
            }
            "listDirectory" => {
                let path = args.get("path").and_then(|v| v.as_str()).unwrap_or(".");
                filesystem::list_directory(&self.cwd, path)
            }
            "searchFileContents" => {
                let pattern = args.get("pattern").and_then(|v| v.as_str()).unwrap_or("");
                let glob = args.get("glob").and_then(|v| v.as_str());
                let max_results = args
                    .get("maxResults")
                    .and_then(|v| v.as_u64())
                    .unwrap_or(50) as usize;
                filesystem::search_file_contents(&self.cwd, pattern, glob, max_results)
            }
            "runShellCommand" => {
                let command = args.get("command").and_then(|v| v.as_str()).unwrap_or("");
                let timeout = args
                    .get("timeoutSeconds")
                    .and_then(|v| v.as_u64())
                    .unwrap_or(60);
                shell::run_shell_command(&self.cwd, command, timeout, policy).await
            }
            // Any name not handled above is delegated to the bifrost
            // subprocess. This avoids a hardcoded list of bifrost tool
            // names drifting out of sync with what bifrost actually
            // exposes (`tool_definitions` already iterates bifrost tools
            // dynamically via `client.tools()`). If bifrost is not
            // running, `execute_bifrost` returns a clear error.
            _ => self.execute_bifrost(name, args).await,
        }
    }

    async fn execute_bifrost(&self, name: &str, args: serde_json::Value) -> ToolResult {
        let Some(client) = self.bifrost.clone() else {
            return ToolResult {
                status: ToolStatus::RequestError,
                output: format!(
                    "Code-intelligence tool '{name}' is unavailable: bifrost subprocess not running."
                ),
            };
        };
        match client.call_tool(name, args).await {
            Ok(value) => {
                let output = if let Some(s) = value.as_str() {
                    s.to_string()
                } else {
                    serde_json::to_string_pretty(&value)
                        .unwrap_or_else(|e| format!("<failed to serialize bifrost result: {e}>"))
                };
                ToolResult {
                    status: ToolStatus::Success,
                    output,
                }
            }
            Err(err) => ToolResult {
                status: ToolStatus::InternalError,
                output: format!("Bifrost tool '{name}' failed: {err}"),
            },
        }
    }

    /// ACP `ToolKind` for a tool, used by the permission gate to classify calls.
    /// Looked up from the `TOOLS` table; tools we don't recognize fall
    /// through to `Other`. Bifrost-loaded tools added without an entry in
    /// `TOOLS` will hit this fallback (and a debug log).
    pub fn tool_kind(tool_name: &str) -> ToolKind {
        match tool_meta(tool_name) {
            Some(t) => t.kind,
            None => {
                tracing::debug!(
                    tool_name,
                    "tool_kind: unrecognized tool, classifying as Other"
                );
                ToolKind::Other
            }
        }
    }

    /// Static display name for a tool. Used as a fallback when a richer
    /// title can't be derived from the call's input args (notably for
    /// Bifrost-loaded tools we don't introspect by name in `announce`).
    pub fn display_name(tool_name: &str) -> &'static str {
        tool_meta(tool_name)
            .map(|t| t.display_name)
            .unwrap_or("Executing tool")
    }
}

fn tool_def(name: &str, description: &str, parameters: serde_json::Value) -> ToolDefinition {
    ToolDefinition {
        r#type: "function".to_string(),
        function: FunctionDef {
            name: name.to_string(),
            description: description.to_string(),
            parameters,
        },
    }
}

/// Resolve a relative path against cwd and ensure it stays within cwd.
pub fn safe_resolve(cwd: &Path, requested: &str) -> Result<PathBuf, String> {
    let joined = cwd.join(requested);
    let resolved = joined
        .canonicalize()
        .map_err(|e| format!("Cannot resolve path '{}': {}", requested, e))?;
    let cwd_canonical = cwd
        .canonicalize()
        .map_err(|e| format!("Cannot resolve cwd: {}", e))?;
    if !resolved.starts_with(&cwd_canonical) {
        return Err(format!(
            "Path '{}' escapes the working directory",
            requested
        ));
    }
    Ok(resolved)
}

/// Like safe_resolve but allows the target (and intermediate ancestors) not to exist yet.
/// We walk up until we find an existing ancestor, canonicalize it, and verify it lies
/// under the canonical cwd. Returns the canonical cwd joined with the remaining tail,
/// which guarantees the final path resolves under cwd without relying on canonicalize
/// of the still-missing target.
pub fn safe_resolve_for_write(cwd: &Path, requested: &str) -> Result<PathBuf, String> {
    let cwd_canonical = cwd
        .canonicalize()
        .map_err(|e| format!("Cannot resolve cwd: {}", e))?;

    let joined = cwd.join(requested);

    // Walk up to the first existing ancestor (including the target itself if it exists).
    // Use symlink_metadata rather than exists(): exists() follows symlinks, so a dangling
    // symlink at the leaf would be reported as non-existent and we'd skip past it,
    // letting fs::write follow the link and write outside cwd. symlink_metadata reports
    // the symlink itself as "existing" so the canonicalize step below either resolves it
    // (rejecting if the target lies outside cwd) or errors on a dangling target.
    let mut tail: Vec<std::ffi::OsString> = Vec::new();
    let mut cursor: &Path = &joined;
    let existing = loop {
        if cursor.symlink_metadata().is_ok() {
            break cursor.to_path_buf();
        }
        match (cursor.file_name(), cursor.parent()) {
            (Some(name), Some(parent)) => {
                tail.push(name.to_os_string());
                cursor = parent;
            }
            _ => {
                return Err(format!(
                    "Cannot resolve path '{}': no existing ancestor",
                    requested
                ));
            }
        }
    };

    let existing_canonical = existing
        .canonicalize()
        .map_err(|e| format!("Cannot resolve ancestor of '{}': {}", requested, e))?;
    if !existing_canonical.starts_with(&cwd_canonical) {
        return Err(format!(
            "Path '{}' escapes the working directory",
            requested
        ));
    }

    // Reject any `..` components in the still-missing tail so an attacker
    // can't re-escape via unwritten path components.
    let mut resolved = existing_canonical;
    for component in tail.into_iter().rev() {
        if component == std::ffi::OsStr::new("..") || component == std::ffi::OsStr::new(".") {
            return Err(format!(
                "Path '{}' contains unsupported '..' or '.' components",
                requested
            ));
        }
        resolved.push(component);
    }

    Ok(resolved)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Allocate a fresh empty directory under the system temp dir for one test
    /// to scribble in. Caller is responsible for cleaning it up.
    fn fresh_tmp_dir(label: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("brokk-acp-rust-{}-{}", label, uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).expect("create tmp dir");
        dir
    }

    /// Regression: a dangling symlink at the leaf must be rejected, not silently
    /// followed by the eventual fs::write at the call site. See issue #3408.
    #[cfg(unix)]
    #[test]
    fn safe_resolve_for_write_rejects_dangling_symlink_to_outside_cwd() {
        let cwd = fresh_tmp_dir("dangling-symlink");
        let outside = fresh_tmp_dir("dangling-target").join("does-not-exist-yet");
        std::os::unix::fs::symlink(&outside, cwd.join("evil")).expect("create symlink");

        let result = safe_resolve_for_write(&cwd, "evil");
        assert!(result.is_err(), "expected rejection, got Ok({:?})", result);

        std::fs::remove_dir_all(&cwd).ok();
        std::fs::remove_dir_all(outside.parent().unwrap()).ok();
    }

    /// A symlink whose target *exists* but lies outside cwd must also be rejected.
    /// This case worked before the fix; the test pins it down so a future change
    /// doesn't regress it.
    #[cfg(unix)]
    #[test]
    fn safe_resolve_for_write_rejects_live_symlink_to_outside_cwd() {
        let cwd = fresh_tmp_dir("live-symlink");
        let outside_dir = fresh_tmp_dir("live-target");
        let outside_file = outside_dir.join("real");
        std::fs::write(&outside_file, "hello").expect("seed outside file");
        std::os::unix::fs::symlink(&outside_file, cwd.join("evil")).expect("create symlink");

        let result = safe_resolve_for_write(&cwd, "evil");
        assert!(result.is_err(), "expected rejection, got Ok({:?})", result);

        std::fs::remove_dir_all(&cwd).ok();
        std::fs::remove_dir_all(&outside_dir).ok();
    }

    /// A symlink that points back inside cwd should still be allowed: the
    /// fix must not over-restrict legitimate intra-sandbox links.
    #[cfg(unix)]
    #[test]
    fn safe_resolve_for_write_allows_symlink_pointing_inside_cwd() {
        let cwd = fresh_tmp_dir("inside-symlink");
        let real = cwd.join("real.txt");
        std::fs::write(&real, "ok").expect("seed real file");
        std::os::unix::fs::symlink(&real, cwd.join("link")).expect("create symlink");

        let resolved = safe_resolve_for_write(&cwd, "link").expect("resolve must succeed");
        let cwd_canonical = cwd.canonicalize().unwrap();
        assert!(
            resolved.starts_with(&cwd_canonical),
            "resolved {:?} must stay under cwd {:?}",
            resolved,
            cwd_canonical
        );

        std::fs::remove_dir_all(&cwd).ok();
    }

    /// An intermediate directory that is a symlink to outside cwd must be
    /// rejected even if the leaf is a not-yet-existing file.
    #[cfg(unix)]
    #[test]
    fn safe_resolve_for_write_rejects_intermediate_symlink_escape() {
        let cwd = fresh_tmp_dir("intermediate-symlink");
        let outside = fresh_tmp_dir("intermediate-target");
        std::os::unix::fs::symlink(&outside, cwd.join("escape")).expect("create symlink");

        let result = safe_resolve_for_write(&cwd, "escape/newfile.txt");
        assert!(result.is_err(), "expected rejection, got Ok({:?})", result);

        std::fs::remove_dir_all(&cwd).ok();
        std::fs::remove_dir_all(&outside).ok();
    }

    /// Happy path: writing to a not-yet-existing file in an existing,
    /// symlink-free directory still resolves under cwd.
    #[test]
    fn safe_resolve_for_write_allows_new_file_in_existing_dir() {
        let cwd = fresh_tmp_dir("new-file");

        let resolved =
            safe_resolve_for_write(&cwd, "subdir/new.txt").expect("resolve must succeed");
        let cwd_canonical = cwd.canonicalize().unwrap();
        assert!(
            resolved.starts_with(&cwd_canonical),
            "resolved {:?} must stay under cwd {:?}",
            resolved,
            cwd_canonical
        );
        assert!(resolved.ends_with("subdir/new.txt"));

        std::fs::remove_dir_all(&cwd).ok();
    }

    /// Anti-drift: every built-in tool name must (1) have a `ToolMeta` row in
    /// the `TOOLS` table (otherwise the permission gate falls through to
    /// `Other` and the UI to a generic label), and (2) be advertised by
    /// `tool_definitions()` (otherwise the LLM never sees it). If you add a
    /// new built-in dispatch arm in `execute`, also add the name to
    /// `BUILTIN_TOOL_NAMES`, the `TOOLS` table, and `tool_definitions()`.
    #[tokio::test]
    async fn builtin_tools_have_metadata_and_are_advertised() {
        let registry = ToolRegistry {
            cwd: PathBuf::from("/tmp"),
            bifrost: None,
        };
        let advertised: Vec<String> = registry
            .tool_definitions()
            .into_iter()
            .map(|d| d.function.name)
            .collect();

        for name in BUILTIN_TOOL_NAMES {
            assert!(
                TOOLS.iter().any(|t| t.name == *name),
                "built-in tool '{name}' is missing from the TOOLS metadata table"
            );
            assert!(
                advertised.iter().any(|a| a == name),
                "built-in tool '{name}' is missing from tool_definitions(); LLM will not see it"
            );
        }

        // The inverse: with bifrost disabled, advertised tools should be a
        // subset of the metadata table (no UI fallback for built-ins).
        for advertised_name in &advertised {
            assert!(
                TOOLS.iter().any(|t| t.name == advertised_name.as_str()),
                "tool_definitions() advertises '{advertised_name}' but it is missing from the TOOLS metadata table"
            );
        }
    }
}
