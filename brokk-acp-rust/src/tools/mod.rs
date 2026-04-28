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

/// Unified tool registry: filesystem tools + shell + think + (optionally) bifrost.
pub struct ToolRegistry {
    cwd: PathBuf,
    bifrost: Option<Arc<BifrostClient>>,
}

impl ToolRegistry {
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
            "search_symbols"
            | "get_symbol_locations"
            | "get_symbol_summaries"
            | "get_symbol_sources"
            | "get_summaries"
            | "summarize_symbols"
            | "skim_files"
            | "most_relevant_files"
            | "refresh" => self.execute_bifrost(name, args).await,
            _ => ToolResult {
                status: ToolStatus::RequestError,
                output: format!("Unknown tool: {name}"),
            },
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
    /// Built-in tools have hardcoded kinds; Bifrost-loaded tools fall through
    /// to `Other` until Bifrost exposes kind metadata in its descriptors.
    ///
    /// Must stay in lockstep with `execute` and `headline` above. `refresh`
    /// is intentionally `Other` rather than `Read` -- it mutates analyzer
    /// state, so we want the user prompted in `default` and refused in
    /// `readOnly`.
    pub fn tool_kind(tool_name: &str) -> ToolKind {
        match tool_name {
            "think" => ToolKind::Think,
            "readFile" | "listDirectory" | "skim_files" | "get_summaries" => ToolKind::Read,
            "searchFileContents"
            | "search_symbols"
            | "get_symbol_locations"
            | "get_symbol_summaries"
            | "get_symbol_sources"
            | "summarize_symbols"
            | "most_relevant_files" => ToolKind::Search,
            "writeFile" => ToolKind::Edit,
            "runShellCommand" => ToolKind::Execute,
            other => {
                tracing::debug!(
                    tool_name = other,
                    "tool_kind: unrecognized tool, classifying as Other"
                );
                ToolKind::Other
            }
        }
    }

    /// Human-readable headline for a tool call (shown to ACP client).
    pub fn headline(tool_name: &str) -> &'static str {
        match tool_name {
            "think" => "Thinking",
            "readFile" => "Reading file",
            "writeFile" => "Writing file",
            "listDirectory" => "Listing directory",
            "searchFileContents" => "Searching file contents",
            "runShellCommand" => "Running shell command",
            "search_symbols" => "Searching for symbols",
            "get_symbol_locations" => "Finding symbol locations",
            "get_symbol_summaries" => "Getting symbol summaries",
            "get_symbol_sources" => "Fetching symbol source",
            "get_summaries" => "Getting summaries",
            "summarize_symbols" => "Summarizing symbols",
            "skim_files" => "Skimming files",
            "most_relevant_files" => "Finding related files",
            "refresh" => "Refreshing analyzer index",
            _ => "Executing tool",
        }
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
    let mut tail: Vec<std::ffi::OsString> = Vec::new();
    let mut cursor: &Path = &joined;
    let existing = loop {
        if cursor.exists() {
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
