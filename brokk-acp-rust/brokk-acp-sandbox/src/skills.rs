//! Pure SKILL.md frontmatter parser. Ported verbatim from
//! `brokk-acp-rust/src/skills.rs` so the same code can be exercised
//! natively (linked as a library) or inside a wasm sandbox.
//!
//! Anything that touches the filesystem (walking a project tree,
//! reading bytes off disk, listing bundled resources) stays in the
//! native host -- the sandbox only sees the already-loaded YAML
//! string and returns the parsed result, so the wasm boundary
//! protects the parser without forcing the host to expose preopens
//! for every skill directory.

use serde::{Deserialize, Serialize};

/// Result of parsing a SKILL.md frontmatter block.
#[derive(Debug, Default, Clone, Serialize, Deserialize)]
pub struct ParsedFrontmatter {
    pub name: Option<String>,
    pub description: Option<String>,
}

/// Split a SKILL.md into `(frontmatter, body)`. Frontmatter is the block
/// between a leading `---` line and the next `---` line on its own.
pub fn split_frontmatter(raw: &str) -> Result<(&str, &str), &'static str> {
    let trimmed = raw.trim_start_matches('\u{feff}');
    let rest = trimmed
        .strip_prefix("---\n")
        .or_else(|| trimmed.strip_prefix("---\r\n"))
        .ok_or("file does not start with `---`")?;
    let mut offset = 0usize;
    for line in rest.split_inclusive('\n') {
        let stripped = line.trim_end_matches(['\n', '\r']);
        if stripped == "---" {
            let front = &rest[..offset];
            let body_start = offset + line.len();
            let body = if body_start < rest.len() {
                &rest[body_start..]
            } else {
                ""
            };
            return Ok((front, body));
        }
        offset += line.len();
    }
    Err("no closing `---` for frontmatter")
}

/// Lenient YAML parse: extracts `name` and `description`. On a YAML
/// scanner error we retry once with the offending unquoted-colon value
/// wrapped in quotes, matching the spec's recommended fallback for
/// "Use this skill when: the user asks about PDFs" style values.
pub fn parse_frontmatter(yaml: &str) -> Result<ParsedFrontmatter, String> {
    match serde_yaml::from_str::<RawFrontmatter>(yaml) {
        Ok(r) => Ok(r.into_parsed()),
        Err(first_err) => {
            if let Some(retry) = requote_description(yaml)
                && let Ok(r) = serde_yaml::from_str::<RawFrontmatter>(&retry)
            {
                return Ok(r.into_parsed());
            }
            Err(first_err.to_string())
        }
    }
}

#[derive(Debug, Deserialize, Default)]
struct RawFrontmatter {
    #[serde(default)]
    name: Option<serde_yaml::Value>,
    #[serde(default)]
    description: Option<serde_yaml::Value>,
}

impl RawFrontmatter {
    fn into_parsed(self) -> ParsedFrontmatter {
        ParsedFrontmatter {
            name: self.name.and_then(yaml_value_to_trimmed_string),
            description: self.description.and_then(yaml_value_to_trimmed_string),
        }
    }
}

/// Coerce a YAML scalar to a trimmed `String`. Returns `None` for nulls
/// or non-scalar values (sequences, maps) -- callers treat missing values
/// the same as malformed.
fn yaml_value_to_trimmed_string(v: serde_yaml::Value) -> Option<String> {
    match v {
        serde_yaml::Value::String(s) => Some(s.trim().to_string()),
        serde_yaml::Value::Bool(b) => Some(b.to_string()),
        serde_yaml::Value::Number(n) => Some(n.to_string()),
        _ => None,
    }
}

/// Rewrite `description: <unquoted value with colon>` to wrap the value
/// in double quotes. Best-effort; only touches the first matching line.
fn requote_description(yaml: &str) -> Option<String> {
    let mut out = String::with_capacity(yaml.len() + 4);
    let mut changed = false;
    for line in yaml.split_inclusive('\n') {
        let stripped = line.trim_end_matches(['\n', '\r']);
        if !changed && let Some(rest) = stripped.strip_prefix("description:") {
            let trimmed = rest.trim_start();
            let already_safe = trimmed.starts_with('"')
                || trimmed.starts_with('\'')
                || trimmed.starts_with('|')
                || trimmed.starts_with('>')
                || trimmed.is_empty();
            if !already_safe && trimmed.contains(':') {
                let escaped = trimmed.replace('\\', "\\\\").replace('"', "\\\"");
                let newline_tail = &line[stripped.len()..line.len()];
                out.push_str(&format!("description: \"{escaped}\"{newline_tail}"));
                changed = true;
                continue;
            }
        }
        out.push_str(line);
    }
    if changed { Some(out) } else { None }
}
