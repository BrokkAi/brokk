//! Builders that turn a tool name + parsed JSON args into the ACP
//! `ToolCall` / `ToolCallUpdate` payloads our tool loop sends to the client.
//!
//! Kept side-effect-free so the title/location logic is unit-testable
//! without an ACP connection. The actual `cx.send_notification(...)` calls
//! live in `tool_loop.rs`, which decides *when* to emit each lifecycle
//! event (Pending -> InProgress -> Completed/Failed).

use std::path::PathBuf;

use agent_client_protocol::schema::{
    Content, ContentBlock, Diff, TextContent, ToolCall, ToolCallContent, ToolCallId,
    ToolCallLocation, ToolCallStatus, ToolCallUpdate, ToolCallUpdateFields, ToolKind,
};
use serde_json::Value;

use crate::tools::ToolRegistry;

/// Cap for inline text content we put on a Completed/Failed update. Keeps
/// the wire payload bounded; the LLM-facing `raw_output` is bounded
/// separately by `tool_loop::MAX_TOOL_RESULT_BYTES`.
const MAX_INLINE_OUTPUT_BYTES: usize = 50_000;

/// Build the initial `Pending` tool call -- the card the client renders
/// before we run the permission gate.
pub(super) fn initial_tool_call(
    tool_call_id: &str,
    tool_name: &str,
    kind: ToolKind,
    raw_input: &Value,
) -> ToolCall {
    ToolCall::new(
        ToolCallId::new(tool_call_id.to_string()),
        tool_title(tool_name, raw_input),
    )
    .kind(kind)
    .status(ToolCallStatus::Pending)
    .raw_input(raw_input.clone())
    .locations(tool_locations(tool_name, raw_input))
}

/// Mark the tool as actively running. Sent once the gate clears.
pub(super) fn update_in_progress(tool_call_id: &str) -> ToolCallUpdate {
    let fields = ToolCallUpdateFields::new().status(ToolCallStatus::InProgress);
    ToolCallUpdate::new(ToolCallId::new(tool_call_id.to_string()), fields)
}

/// Terminal `Failed` update -- denial messages, internal errors, etc.
/// `reason` is shown inline in the card; `raw_output` (when available)
/// preserves the full tool error for clients that surface it.
pub(super) fn update_failed(
    tool_call_id: &str,
    reason: &str,
    raw_output: Option<Value>,
) -> ToolCallUpdate {
    let mut fields = ToolCallUpdateFields::new()
        .status(ToolCallStatus::Failed)
        .content(vec![text_content(reason)]);
    if let Some(raw) = raw_output {
        fields = fields.raw_output(raw);
    }
    ToolCallUpdate::new(ToolCallId::new(tool_call_id.to_string()), fields)
}

/// Terminal `Completed` update. Pass `Some(diff)` for `writeFile` to
/// render an inline diff; otherwise the `output` is shown as text.
pub(super) fn update_completed(
    tool_call_id: &str,
    output: &str,
    diff: Option<Diff>,
) -> ToolCallUpdate {
    let content = match diff {
        Some(diff) => vec![ToolCallContent::Diff(diff)],
        None => vec![text_content(&truncate(output))],
    };
    let fields = ToolCallUpdateFields::new()
        .status(ToolCallStatus::Completed)
        .content(content)
        .raw_output(Value::String(output.to_string()));
    ToolCallUpdate::new(ToolCallId::new(tool_call_id.to_string()), fields)
}

/// Human-friendly card title that shows *what* the tool is doing,
/// not just *which* tool it is. Falls back to the static display name
/// for tools we don't introspect (Bifrost, unknown).
pub(super) fn tool_title(tool_name: &str, raw_input: &Value) -> String {
    let display = ToolRegistry::display_name(tool_name);
    let path = raw_input.get("path").and_then(Value::as_str);
    let pattern = raw_input.get("pattern").and_then(Value::as_str);
    let command = raw_input.get("command").and_then(Value::as_str);

    match tool_name {
        "readFile" => path
            .map(|p| format!("Read `{p}`"))
            .unwrap_or_else(|| display.to_string()),
        "writeFile" => path
            .map(|p| format!("Write `{p}`"))
            .unwrap_or_else(|| display.to_string()),
        "listDirectory" => path
            .map(|p| format!("List `{p}`"))
            .unwrap_or_else(|| display.to_string()),
        "searchFileContents" => pattern
            .map(|p| format!("Search `{p}`"))
            .unwrap_or_else(|| display.to_string()),
        "runShellCommand" => command
            .map(|c| format!("Run `{}`", first_line(c)))
            .unwrap_or_else(|| display.to_string()),
        "think" => "Think".to_string(),
        _ => {
            // Bifrost or anything we don't special-case: append a brief
            // input summary so the user sees more than just "Searching for
            // symbols". Falls back to the bare display name when no input
            // string can be picked.
            let summary = brief_input_summary(raw_input);
            if summary.is_empty() {
                display.to_string()
            } else {
                format!("{display}: {summary}")
            }
        }
    }
}

/// File locations affected by this call, used by clients for follow-along.
/// v1: only the obvious `path` arg on filesystem tools. Bifrost JSON
/// outputs may carry locations, but parsing them is out of scope here.
pub(super) fn tool_locations(tool_name: &str, raw_input: &Value) -> Vec<ToolCallLocation> {
    if matches!(tool_name, "readFile" | "writeFile" | "listDirectory")
        && let Some(path) = raw_input.get("path").and_then(Value::as_str)
    {
        return vec![ToolCallLocation::new(PathBuf::from(path))];
    }
    Vec::new()
}

fn truncate(s: &str) -> String {
    if s.len() <= MAX_INLINE_OUTPUT_BYTES {
        s.to_string()
    } else {
        // Truncate on a UTF-8 char boundary so we don't ship invalid
        // strings; floor-search backwards from the cap.
        let mut cut = MAX_INLINE_OUTPUT_BYTES;
        while !s.is_char_boundary(cut) {
            cut -= 1;
        }
        let mut out = s[..cut].to_string();
        out.push_str("\n... output truncated");
        out
    }
}

fn first_line(s: &str) -> &str {
    s.lines().next().unwrap_or(s)
}

fn text_content(s: &str) -> ToolCallContent {
    ToolCallContent::Content(Content::new(ContentBlock::Text(TextContent::new(s))))
}

/// Pull the first non-empty string value out of a JSON object, capped
/// at ~80 chars. Used to give Bifrost calls a "where am I" hint in the
/// card title (e.g. `search_symbols` argv -> "main").
fn brief_input_summary(raw_input: &Value) -> String {
    let Some(obj) = raw_input.as_object() else {
        return String::new();
    };
    for (_, v) in obj {
        if let Some(s) = v.as_str() {
            let s = s.trim();
            if !s.is_empty() {
                let mut out = s.to_string();
                if out.len() > 80 {
                    out.truncate(80);
                    out.push_str("...");
                }
                return out;
            }
        }
        if let Some(arr) = v.as_array()
            && let Some(first) = arr.first().and_then(Value::as_str)
        {
            let s = first.trim();
            if !s.is_empty() {
                let mut out = s.to_string();
                if out.len() > 80 {
                    out.truncate(80);
                    out.push_str("...");
                }
                return out;
            }
        }
    }
    String::new()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn read_file_title_shows_path() {
        let title = tool_title("readFile", &json!({"path": "src/lib.rs"}));
        assert_eq!(title, "Read `src/lib.rs`");
    }

    #[test]
    fn write_file_title_shows_path() {
        let title = tool_title("writeFile", &json!({"path": "a/b.txt", "content": "x"}));
        assert_eq!(title, "Write `a/b.txt`");
    }

    #[test]
    fn list_directory_title_shows_path() {
        let title = tool_title("listDirectory", &json!({"path": "src"}));
        assert_eq!(title, "List `src`");
    }

    #[test]
    fn search_title_shows_pattern() {
        let title = tool_title("searchFileContents", &json!({"pattern": "TODO"}));
        assert_eq!(title, "Search `TODO`");
    }

    #[test]
    fn run_shell_title_shows_first_line() {
        let title = tool_title(
            "runShellCommand",
            &json!({"command": "cargo test\n# extra junk"}),
        );
        assert_eq!(title, "Run `cargo test`");
    }

    #[test]
    fn think_title_is_constant() {
        let title = tool_title("think", &json!({"thought": "..."}));
        assert_eq!(title, "Think");
    }

    #[test]
    fn unknown_tool_falls_back_to_display_name_with_summary() {
        // search_symbols isn't special-cased here; it goes through
        // ToolRegistry::display_name + brief_input_summary.
        let title = tool_title("search_symbols", &json!({"patterns": ["main"]}));
        assert!(title.starts_with("Searching for symbols"));
        assert!(title.ends_with("main"));
    }

    #[test]
    fn unknown_tool_without_string_input_uses_display_name() {
        let title = tool_title("search_symbols", &json!({"limit": 10}));
        assert_eq!(title, "Searching for symbols");
    }

    #[test]
    fn missing_path_uses_display_name() {
        let title = tool_title("readFile", &json!({}));
        assert_eq!(title, "Reading file");
    }

    #[test]
    fn locations_include_path_for_filesystem_tools() {
        let locs = tool_locations("readFile", &json!({"path": "src/lib.rs"}));
        assert_eq!(locs.len(), 1);
        assert_eq!(locs[0].path, PathBuf::from("src/lib.rs"));

        let locs = tool_locations("writeFile", &json!({"path": "a.txt", "content": ""}));
        assert_eq!(locs.len(), 1);
        assert_eq!(locs[0].path, PathBuf::from("a.txt"));

        let locs = tool_locations("listDirectory", &json!({"path": "."}));
        assert_eq!(locs.len(), 1);
        assert_eq!(locs[0].path, PathBuf::from("."));
    }

    #[test]
    fn locations_empty_for_non_filesystem_tools() {
        assert!(tool_locations("runShellCommand", &json!({"command": "ls"})).is_empty());
        assert!(tool_locations("searchFileContents", &json!({"pattern": "x"})).is_empty());
        assert!(tool_locations("think", &json!({"thought": "..."})).is_empty());
        assert!(tool_locations("search_symbols", &json!({"patterns": ["x"]})).is_empty());
    }

    #[test]
    fn truncate_respects_char_boundary() {
        // Build a string just past the cap that ends in a multi-byte char,
        // and verify we don't slice through the middle of it.
        let mut s = "a".repeat(MAX_INLINE_OUTPUT_BYTES - 1);
        s.push('\u{1F600}'); // 4-byte UTF-8
        s.push_str("tail");
        let out = truncate(&s);
        assert!(out.ends_with("output truncated"));
        // No panic and the output is still valid UTF-8 (truncate already
        // returned a String).
        assert!(out.is_char_boundary(out.len()));
    }
}
