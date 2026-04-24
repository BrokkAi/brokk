mod filesystem;
mod shell;

use crate::llm_client::{FunctionDef, ToolDefinition};
use serde_json::json;
use std::path::{Path, PathBuf};

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

/// Unified tool registry: filesystem tools + shell + think.
/// Bifrost (brokk_analyzer) will be added here when integrated as a dependency.
pub struct ToolRegistry {
    cwd: PathBuf,
}

impl ToolRegistry {
    pub fn new(cwd: PathBuf) -> Self {
        Self { cwd }
    }

    /// All tool definitions for the OpenAI tools parameter.
    pub fn tool_definitions(&self) -> Vec<ToolDefinition> {
        vec![
            tool_def("think", "Use this tool to think through a problem step by step before acting. The input is not used for anything -- it is just a scratchpad for your thoughts.", json!({
                "type": "object",
                "properties": {
                    "thought": {
                        "type": "string",
                        "description": "Your reasoning or thought process."
                    }
                },
                "required": ["thought"]
            })),
            tool_def("readFile", "Read the contents of a file. Path is relative to the working directory.", json!({
                "type": "object",
                "properties": {
                    "path": {
                        "type": "string",
                        "description": "Relative path to the file to read."
                    }
                },
                "required": ["path"]
            })),
            tool_def("writeFile", "Write content to a file, creating it if it does not exist. Path is relative to the working directory.", json!({
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
            })),
            tool_def("listDirectory", "List files and directories at the given path. Path is relative to the working directory.", json!({
                "type": "object",
                "properties": {
                    "path": {
                        "type": "string",
                        "description": "Relative path to the directory to list. Use '.' for the working directory root."
                    }
                },
                "required": ["path"]
            })),
            tool_def("searchFileContents", "Search for a regex pattern across files in the working directory. Returns matching lines with file paths and line numbers.", json!({
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
            })),
            tool_def("runShellCommand", "Execute a shell command in the working directory. Returns stdout and stderr. Use for build, test, git, or other CLI operations.", json!({
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
            })),
        ]
    }

    /// Execute a tool by name with JSON arguments.
    pub async fn execute(&self, name: &str, args: serde_json::Value) -> ToolResult {
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
                let max_results = args.get("maxResults").and_then(|v| v.as_u64()).unwrap_or(50) as usize;
                filesystem::search_file_contents(&self.cwd, pattern, glob, max_results)
            }
            "runShellCommand" => {
                let command = args.get("command").and_then(|v| v.as_str()).unwrap_or("");
                let timeout = args.get("timeoutSeconds").and_then(|v| v.as_u64()).unwrap_or(60);
                shell::run_shell_command(&self.cwd, command, timeout).await
            }
            _ => ToolResult {
                status: ToolStatus::RequestError,
                output: format!("Unknown tool: {name}"),
            },
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
            "get_symbol_sources" => "Fetching symbol source",
            "get_file_summaries" => "Getting file summaries",
            "skim_files" => "Skimming files",
            "most_relevant_files" => "Finding related files",
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
        return Err(format!("Path '{}' escapes the working directory", requested));
    }
    Ok(resolved)
}

/// Like safe_resolve but allows the path to not exist yet (for writes).
pub fn safe_resolve_for_write(cwd: &Path, requested: &str) -> Result<PathBuf, String> {
    let joined = cwd.join(requested);
    // Canonicalize the parent to check it's under cwd
    let parent = joined.parent().ok_or("Invalid path")?;
    if parent.exists() {
        let parent_canonical = parent
            .canonicalize()
            .map_err(|e| format!("Cannot resolve parent of '{}': {}", requested, e))?;
        let cwd_canonical = cwd
            .canonicalize()
            .map_err(|e| format!("Cannot resolve cwd: {}", e))?;
        if !parent_canonical.starts_with(&cwd_canonical) {
            return Err(format!("Path '{}' escapes the working directory", requested));
        }
    }
    Ok(joined)
}
