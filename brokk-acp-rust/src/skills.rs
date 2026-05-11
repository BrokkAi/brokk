//! Agent Skills (`SKILL.md`) discovery for the ACP prompt context.
//!
//! Honors the open spec at <https://agentskills.io> (originally from
//! Anthropic, adopted by ~30 agents including opencode, gemini-cli,
//! cursor, claude code). The full client-implementation guide is at
//! <https://agentskills.io/client-implementation/adding-skills-support>.
//!
//! Discovery scans four roots in order, with **last-wins union**
//! semantics modelled on opencode
//! (`packages/opencode/src/skill/index.ts::discoverSkills`). Scan order
//! is picked so that the natural precedence emerges from the merge:
//!
//!   1. `~/.claude/skills/`                       (user, Claude compat)
//!   2. `~/.agents/skills/`                       (user, cross-client)
//!   3. `<git-root walk down to cwd>/.claude/skills/` (project, Claude compat)
//!   4. `<git-root walk down to cwd>/.agents/skills/` (project, cross-client)
//!
//! As a result: project > user, and within each scope `.agents/` overrides
//! `.claude/`. On collision the prior entry is overwritten and a
//! diagnostic is pushed (surfaced via `/context`, not the LLM catalog).
//!
//! Reading another vendor's config dir (`.claude/`) is endorsed by the
//! spec ("Some implementations also scan `.claude/skills/` for pragmatic
//! compatibility, since many existing skills are installed there"); it
//! lets users carry their existing Claude Code skills over without
//! duplicating them.
//!
//! Pure module; no LLM/session deps. Re-runs are cheap and idempotent.

use std::collections::HashMap;
use std::ffi::OsStr;
use std::io::ErrorKind;
use std::path::{Path, PathBuf};

/// Per-root cap on walked directory entries. Cheap insurance against
/// accidental scans into a `node_modules`/`target` tree if a user drops
/// `.agents/skills/` at a wrong level. opencode has no such cap; this
/// matches the spec's "max 2000 directories" suggestion.
const MAX_DIRS_PER_ROOT: usize = 2000;

/// Max walk depth under each root. Skills live one level deep
/// (`skills/<name>/SKILL.md`); 4 leaves headroom for the spec's optional
/// `scripts/`, `references/`, `assets/` subtrees if a skill is nested.
const MAX_DEPTH: usize = 4;

/// Hard cap on the body size we'll load for a single SKILL.md. The spec
/// recommends `< 5000 tokens` (~20 KB) for the body; we allow generous
/// headroom but reject pathologically large files.
const MAX_BODY_BYTES: usize = 256 * 1024;

const SKILL_FILE: &str = "SKILL.md";
const AGENTS_DIR: &str = ".agents";
const CLAUDE_DIR: &str = ".claude";
const SKILLS_SUBDIR: &str = "skills";

/// Discovered SKILL.md metadata. The body is loaded on demand by the
/// activation path (slash command or `activate_skill` tool), not eagerly,
/// so a session with 30 skills doesn't pay the I/O cost upfront.
#[derive(Debug, Clone)]
pub struct SkillMeta {
    pub name: String,
    pub description: String,
    pub location: PathBuf,
    pub skill_dir: PathBuf,
    /// Where this skill was discovered. Kept for diagnostics today and
    /// reserved for future trust-gating (project-scope skills may want
    /// a confirmation step before activation; user-scope ones don't).
    #[allow(dead_code)]
    pub scope: SkillScope,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SkillScope {
    User,
    Project,
}

/// In-memory registry. Keyed by `name`; last-wins on insert. Diagnostics
/// (collision warnings, parse errors, name/dir mismatches) are stashed
/// so `/context` can surface them without spamming the LLM catalog.
#[derive(Debug, Default, Clone)]
pub struct SkillRegistry {
    by_name: HashMap<String, SkillMeta>,
    diagnostics: Vec<String>,
}

impl SkillRegistry {
    pub fn is_empty(&self) -> bool {
        self.by_name.is_empty()
    }

    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.by_name.len()
    }

    pub fn get(&self, name: &str) -> Option<&SkillMeta> {
        self.by_name.get(name)
    }

    /// Stable-ordered iterator over discovered skills (sorted by name)
    /// so the catalog and `available_commands` output is deterministic.
    pub fn iter_sorted(&self) -> impl Iterator<Item = &SkillMeta> {
        let mut v: Vec<&SkillMeta> = self.by_name.values().collect();
        v.sort_by(|a, b| a.name.cmp(&b.name));
        v.into_iter()
    }

    /// Discovery warnings (collisions, malformed YAML, name/dir
    /// mismatches). Surfaced via `/context` to help users debug a
    /// `SKILL.md` that fails to register.
    #[cfg(test)]
    pub fn diagnostics(&self) -> &[String] {
        &self.diagnostics
    }

    /// Test-only insert that bypasses filesystem discovery. Lets
    /// integration tests in other modules (e.g. agent.rs) populate a
    /// registry with synthetic skills without writing real SKILL.md
    /// files to disk.
    #[cfg(test)]
    pub fn insert_for_test(&mut self, meta: SkillMeta) {
        self.by_name.insert(meta.name.clone(), meta);
    }

    fn add(&mut self, meta: SkillMeta) {
        if let Some(prev) = self.by_name.get(&meta.name) {
            let msg = format!(
                "duplicate skill '{}': '{}' shadowed by '{}'",
                meta.name,
                prev.location.display(),
                meta.location.display(),
            );
            tracing::warn!("{msg}");
            self.diagnostics.push(msg);
        }
        self.by_name.insert(meta.name.clone(), meta);
    }

    fn push_diagnostic(&mut self, msg: String) {
        tracing::warn!("{msg}");
        self.diagnostics.push(msg);
    }
}

/// Discover all `SKILL.md` files reachable from `cwd` and the user home
/// dir. Returns an empty registry when nothing is found.
pub fn discover(cwd: &Path) -> SkillRegistry {
    let home = dirs::home_dir();
    discover_inner(cwd, home.as_deref())
}

fn discover_inner(cwd: &Path, home: Option<&Path>) -> SkillRegistry {
    let cwd = normalize_path(cwd);
    let mut reg = SkillRegistry::default();

    // 1+2. User scope: `~/.claude/skills/` then `~/.agents/skills/`.
    //      `.agents/` is scanned last so it wins under last-wins.
    if let Some(h) = home {
        scan_root(
            &h.join(CLAUDE_DIR).join(SKILLS_SUBDIR),
            SkillScope::User,
            &mut reg,
        );
        scan_root(
            &h.join(AGENTS_DIR).join(SKILLS_SUBDIR),
            SkillScope::User,
            &mut reg,
        );
    }

    // 3+4. Project scope: walk from git root down to cwd. At each level
    //      we look for both `.claude/skills/` and `.agents/skills/`; the
    //      deeper directory and the `.agents/` variant naturally win.
    let git_root = find_git_root(&cwd);
    for dir in build_dir_chain(&cwd, git_root.as_deref()) {
        scan_root(
            &dir.join(CLAUDE_DIR).join(SKILLS_SUBDIR),
            SkillScope::Project,
            &mut reg,
        );
        scan_root(
            &dir.join(AGENTS_DIR).join(SKILLS_SUBDIR),
            SkillScope::Project,
            &mut reg,
        );
    }

    if !reg.is_empty() {
        let names: Vec<&str> = reg.by_name.keys().map(|s| s.as_str()).collect();
        tracing::info!(skills = ?names, "SKILL.md discovery");
    }
    reg
}

fn scan_root(root: &Path, scope: SkillScope, reg: &mut SkillRegistry) {
    // Empty/missing root is the common case (no `.agents/skills/`
    // anywhere). Distinguish from real errors so we don't spam warnings.
    let entries = match std::fs::read_dir(root) {
        Ok(it) => it,
        Err(e) if e.kind() == ErrorKind::NotFound => return,
        Err(e) => {
            reg.push_diagnostic(format!(
                "skills root '{}' is unreadable: {e}",
                root.display()
            ));
            return;
        }
    };

    let walker = walkdir::WalkDir::new(root)
        .max_depth(MAX_DEPTH)
        .follow_links(false)
        .into_iter()
        .filter_entry(|e| !is_excluded_dir(e));

    let mut scanned = 0usize;
    // Drop the bare `read_dir` handle now that we've decided to scan.
    drop(entries);

    for entry in walker {
        let entry = match entry {
            Ok(e) => e,
            Err(e) => {
                reg.push_diagnostic(format!("skill walk error under '{}': {e}", root.display()));
                continue;
            }
        };
        scanned += 1;
        if scanned > MAX_DIRS_PER_ROOT {
            reg.push_diagnostic(format!(
                "skill walk under '{}' exceeded {MAX_DIRS_PER_ROOT} entries; stopping",
                root.display()
            ));
            break;
        }
        if !entry.file_type().is_file() {
            continue;
        }
        if entry.file_name() != OsStr::new(SKILL_FILE) {
            continue;
        }
        load_skill(entry.path(), scope, reg);
    }
}

/// Skip `.git/`, `node_modules/`, and any other hidden directory that
/// doesn't itself name the skill root. The root entry (`skills/`) is the
/// `WalkDir` starting point and always allowed.
fn is_excluded_dir(entry: &walkdir::DirEntry) -> bool {
    if !entry.file_type().is_dir() {
        return false;
    }
    if entry.depth() == 0 {
        return false;
    }
    let name = match entry.file_name().to_str() {
        Some(s) => s,
        None => return false,
    };
    if name == ".git" || name == "node_modules" || name == "target" {
        return true;
    }
    // Hidden directories under `skills/` are not part of the spec.
    name.starts_with('.')
}

fn load_skill(path: &Path, scope: SkillScope, reg: &mut SkillRegistry) {
    let raw = match std::fs::read_to_string(path) {
        Ok(s) => s,
        Err(e) => {
            reg.push_diagnostic(format!("SKILL.md unreadable at '{}': {e}", path.display()));
            return;
        }
    };
    if raw.len() > MAX_BODY_BYTES {
        reg.push_diagnostic(format!(
            "SKILL.md at '{}' exceeds {MAX_BODY_BYTES} bytes; skipping",
            path.display()
        ));
        return;
    }

    let (front, _body) = match split_frontmatter(&raw) {
        Ok(p) => p,
        Err(e) => {
            reg.push_diagnostic(format!(
                "SKILL.md at '{}' missing or unterminated frontmatter: {e}",
                path.display()
            ));
            return;
        }
    };

    let parsed = match parse_frontmatter(front) {
        Ok(p) => p,
        Err(e) => {
            reg.push_diagnostic(format!(
                "SKILL.md at '{}' has invalid YAML frontmatter: {e}",
                path.display()
            ));
            return;
        }
    };

    let dir_name = path
        .parent()
        .and_then(|p| p.file_name())
        .and_then(|s| s.to_str())
        .unwrap_or("")
        .to_string();

    // Spec is strict on `name` but the client-implementation guide tells
    // us to be lenient: warn-don't-fail on mismatch, fall back to the
    // directory name when frontmatter omits `name`.
    let name = match parsed.name {
        Some(n) if n.trim().is_empty() => {
            reg.push_diagnostic(format!(
                "SKILL.md at '{}' has empty `name`; using directory name '{dir_name}'",
                path.display()
            ));
            dir_name.clone()
        }
        Some(n) => {
            if !dir_name.is_empty() && n != dir_name {
                reg.push_diagnostic(format!(
                    "SKILL.md at '{}' has name '{n}' that does not match parent directory '{dir_name}'; loading anyway",
                    path.display()
                ));
            }
            if n.chars().count() > 64 {
                reg.push_diagnostic(format!(
                    "SKILL.md at '{}' has name longer than 64 chars; loading anyway",
                    path.display()
                ));
            }
            n
        }
        None => {
            if dir_name.is_empty() {
                reg.push_diagnostic(format!(
                    "SKILL.md at '{}' has no `name` and no usable parent directory; skipping",
                    path.display()
                ));
                return;
            }
            dir_name.clone()
        }
    };

    let description = match parsed.description {
        Some(d) if !d.trim().is_empty() => d.trim().to_string(),
        _ => {
            // Per spec: missing/empty description -> skip the skill
            // (essential for tier-1 disclosure).
            reg.push_diagnostic(format!(
                "SKILL.md at '{}' is missing or has empty `description`; skipping",
                path.display()
            ));
            return;
        }
    };

    let skill_dir = path.parent().map(|p| p.to_path_buf()).unwrap_or_default();
    reg.add(SkillMeta {
        name,
        description,
        location: path.to_path_buf(),
        skill_dir,
        scope,
    });
}

/// Returned by `parse_frontmatter`. Only fields the registry consumes are
/// extracted; the rest of the YAML is intentionally ignored to keep
/// validation lenient across vendors.
#[derive(Debug, Default)]
struct ParsedFrontmatter {
    name: Option<String>,
    description: Option<String>,
}

/// Split a SKILL.md into `(frontmatter, body)`. Frontmatter is the block
/// between a leading `---` line and the next `---` line on its own.
fn split_frontmatter(raw: &str) -> Result<(&str, &str), &'static str> {
    let trimmed = raw.trim_start_matches('\u{feff}');
    let rest = trimmed
        .strip_prefix("---\n")
        .or_else(|| trimmed.strip_prefix("---\r\n"))
        .ok_or("file does not start with `---`")?;
    // Search for a closing `---` on its own line.
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
fn parse_frontmatter(yaml: &str) -> Result<ParsedFrontmatter, String> {
    match serde_yaml::from_str::<RawFrontmatter>(yaml) {
        Ok(r) => Ok(r.into_parsed()),
        Err(first_err) => {
            // Heuristic recovery: if the YAML scanner choked on a
            // `description:` value containing an unquoted colon, try
            // again with the value wrapped in double quotes. We don't
            // attempt this for arbitrary fields -- only `description`,
            // which is by far the most common offender in skills shipped
            // by other clients.
            if let Some(retry) = requote_description(yaml)
                && let Ok(r) = serde_yaml::from_str::<RawFrontmatter>(&retry)
            {
                return Ok(r.into_parsed());
            }
            Err(first_err.to_string())
        }
    }
}

#[derive(Debug, serde::Deserialize, Default)]
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
            // Only requote when the value isn't already quoted, isn't a
            // block scalar (`|` / `>`), and contains a colon (the trigger
            // for the YAML scanner error we're recovering from).
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

fn normalize_path(path: &Path) -> PathBuf {
    std::fs::canonicalize(path).unwrap_or_else(|_| path.to_path_buf())
}

fn find_git_root(start: &Path) -> Option<PathBuf> {
    let mut cur = Some(start);
    while let Some(p) = cur {
        if p.join(".git").exists() {
            return Some(p.to_path_buf());
        }
        cur = p.parent();
    }
    None
}

fn build_dir_chain(cwd: &Path, git_root: Option<&Path>) -> Vec<PathBuf> {
    let Some(root) = git_root else {
        return vec![cwd.to_path_buf()];
    };
    if root == cwd {
        return vec![cwd.to_path_buf()];
    }
    let Ok(rel) = cwd.strip_prefix(root) else {
        return vec![cwd.to_path_buf()];
    };
    let mut chain = vec![root.to_path_buf()];
    let mut acc = root.to_path_buf();
    for part in rel.iter() {
        acc.push(part);
        chain.push(acc.clone());
    }
    chain
}

/// Read just the body of a `SKILL.md` (frontmatter stripped). Used by
/// the activation path so the LLM sees only the instructions, not the
/// metadata it already saw in the catalog. Returns the trimmed body on
/// success; on any I/O or parse error returns the raw file contents so
/// the user still gets something useful out of `/skill-name`.
pub fn read_skill_body(path: &Path) -> std::io::Result<String> {
    let raw = std::fs::read_to_string(path)?;
    let body = match split_frontmatter(&raw) {
        Ok((_, body)) => body.trim_start_matches('\n').trim_end().to_string(),
        Err(_) => raw.trim().to_string(),
    };
    Ok(body)
}

/// List the relative paths of bundled resources under a skill directory,
/// capped at 50 entries to keep the activation payload compact. Skips
/// the SKILL.md itself and hidden files. Used by the activation path to
/// fill the `<skill_resources>` block per the spec's structured-wrapping
/// recommendation.
///
/// Paths are normalized to POSIX-style forward slashes regardless of
/// host OS. The LLM sees these paths and passes them back to `readFile`,
/// and the spec's examples use `/`; consistent separators across hosts
/// avoid teaching the model platform-specific path syntax.
pub fn list_bundled_resources(skill_dir: &Path) -> Vec<String> {
    const MAX_RESOURCES: usize = 50;
    let mut out: Vec<String> = walkdir::WalkDir::new(skill_dir)
        .max_depth(3)
        .follow_links(false)
        .into_iter()
        .filter_map(|e| e.ok())
        .filter(|e| e.file_type().is_file())
        .filter(|e| e.file_name() != OsStr::new(SKILL_FILE))
        .filter(|e| {
            e.file_name()
                .to_str()
                .map(|n| !n.starts_with('.'))
                .unwrap_or(false)
        })
        .filter_map(|e| e.path().strip_prefix(skill_dir).ok().map(to_posix_relative))
        .take(MAX_RESOURCES + 1)
        .collect();
    out.sort();
    let truncated = out.len() > MAX_RESOURCES;
    if truncated {
        out.truncate(MAX_RESOURCES);
    }
    out
}

/// Render a relative `Path` as a POSIX-style string (`/`-separated). On
/// Unix this is just `to_string_lossy`; on Windows it swaps `\` for `/`.
fn to_posix_relative(rel: &Path) -> String {
    rel.components()
        .map(|c| c.as_os_str().to_string_lossy().into_owned())
        .collect::<Vec<_>>()
        .join("/")
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::TempDir;

    fn write(path: &Path, content: &str) {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).unwrap();
        }
        fs::write(path, content).unwrap();
    }

    fn touch_git(root: &Path) {
        fs::create_dir_all(root.join(".git")).unwrap();
    }

    fn skill_at(root: &Path, vendor_dir: &str, name: &str, body: &str) -> PathBuf {
        let p = root
            .join(vendor_dir)
            .join(SKILLS_SUBDIR)
            .join(name)
            .join(SKILL_FILE);
        write(&p, body);
        p
    }

    /// macOS canonicalizes `/var/folders/...` to `/private/var/folders/...`,
    /// so comparing the registry's resolved path against a raw `TempDir`
    /// path fails on darwin even when discovery is correct. Both sides go
    /// through `canonicalize` so the test is platform-agnostic.
    fn canonical(p: &Path) -> PathBuf {
        std::fs::canonicalize(p).unwrap_or_else(|_| p.to_path_buf())
    }

    fn minimal(name: &str, desc: &str) -> String {
        format!("---\nname: {name}\ndescription: {desc}\n---\n\nBody")
    }

    #[test]
    fn returns_empty_when_no_files_present() {
        let project = TempDir::new().unwrap();
        let home = TempDir::new().unwrap();
        let reg = discover_inner(project.path(), Some(home.path()));
        assert!(reg.is_empty());
    }

    #[test]
    fn valid_frontmatter_with_required_fields() {
        let project = TempDir::new().unwrap();
        touch_git(project.path());
        skill_at(
            project.path(),
            AGENTS_DIR,
            "hello",
            &minimal("hello", "say hi"),
        );

        let home = TempDir::new().unwrap();
        let reg = discover_inner(project.path(), Some(home.path()));
        assert_eq!(reg.len(), 1);
        let meta = reg.get("hello").unwrap();
        assert_eq!(meta.description, "say hi");
        assert_eq!(meta.scope, SkillScope::Project);
    }

    #[test]
    fn missing_description_skipped_with_warning() {
        let project = TempDir::new().unwrap();
        touch_git(project.path());
        skill_at(
            project.path(),
            AGENTS_DIR,
            "broken",
            "---\nname: broken\n---\nBody",
        );

        let home = TempDir::new().unwrap();
        let reg = discover_inner(project.path(), Some(home.path()));
        assert!(reg.is_empty());
        let diag = reg
            .diagnostics()
            .iter()
            .find(|d| d.contains("description"))
            .unwrap_or_else(|| {
                panic!(
                    "expected description diagnostic, got: {:?}",
                    reg.diagnostics()
                )
            });
        // Compare on the skill folder name only -- the absolute path in
        // the diagnostic differs by canonicalization on darwin
        // (`/var` -> `/private/var`) and uses `\` on windows.
        assert!(
            diag.contains("broken"),
            "diagnostic should reference the skill folder name: {diag}"
        );
        assert!(diag.contains(SKILL_FILE));
    }

    #[test]
    fn name_dir_mismatch_loads_with_warning() {
        let project = TempDir::new().unwrap();
        touch_git(project.path());
        skill_at(
            project.path(),
            AGENTS_DIR,
            "expected-dir",
            &minimal("different-name", "x"),
        );

        let home = TempDir::new().unwrap();
        let reg = discover_inner(project.path(), Some(home.path()));
        assert_eq!(reg.len(), 1);
        assert!(reg.get("different-name").is_some());
        assert!(
            reg.diagnostics()
                .iter()
                .any(|d| d.contains("does not match parent directory")),
            "expected mismatch diagnostic, got: {:?}",
            reg.diagnostics()
        );
    }

    #[test]
    fn unquoted_colon_in_description_recovers() {
        let project = TempDir::new().unwrap();
        touch_git(project.path());
        // Note the bare colon inside the description, which trips the
        // YAML scanner -- the requote_description fallback should
        // recover.
        let body =
            "---\nname: colon\ndescription: Use this skill when: the user mentions PDFs\n---\nBody";
        skill_at(project.path(), AGENTS_DIR, "colon", body);

        let home = TempDir::new().unwrap();
        let reg = discover_inner(project.path(), Some(home.path()));
        let meta = reg
            .get("colon")
            .unwrap_or_else(|| panic!("recovery failed; diagnostics: {:?}", reg.diagnostics()));
        assert!(meta.description.contains("Use this skill when"));
        assert!(meta.description.contains("PDFs"));
    }

    #[test]
    fn project_agents_overrides_project_claude() {
        let project = TempDir::new().unwrap();
        touch_git(project.path());
        skill_at(
            project.path(),
            CLAUDE_DIR,
            "foo",
            &minimal("foo", "from claude"),
        );
        let agents_path = skill_at(
            project.path(),
            AGENTS_DIR,
            "foo",
            &minimal("foo", "from agents"),
        );

        let home = TempDir::new().unwrap();
        let reg = discover_inner(project.path(), Some(home.path()));
        let meta = reg.get("foo").unwrap();
        assert_eq!(meta.description, "from agents");
        assert_eq!(canonical(&meta.location), canonical(&agents_path));
        assert!(
            reg.diagnostics()
                .iter()
                .any(|d| d.contains("duplicate skill 'foo'")),
            "expected collision diagnostic; got: {:?}",
            reg.diagnostics()
        );
    }

    #[test]
    fn project_skill_overrides_user_skill() {
        let project = TempDir::new().unwrap();
        touch_git(project.path());
        let home = TempDir::new().unwrap();

        skill_at(home.path(), AGENTS_DIR, "x", &minimal("x", "from user"));
        let project_path = skill_at(
            project.path(),
            AGENTS_DIR,
            "x",
            &minimal("x", "from project"),
        );

        let reg = discover_inner(project.path(), Some(home.path()));
        let meta = reg.get("x").unwrap();
        assert_eq!(meta.description, "from project");
        assert_eq!(canonical(&meta.location), canonical(&project_path));
        assert_eq!(meta.scope, SkillScope::Project);
    }

    #[test]
    fn user_agents_overrides_user_claude() {
        let home = TempDir::new().unwrap();
        skill_at(home.path(), CLAUDE_DIR, "ua", &minimal("ua", "claude user"));
        let agents_path = skill_at(home.path(), AGENTS_DIR, "ua", &minimal("ua", "agents user"));

        let project = TempDir::new().unwrap();
        let reg = discover_inner(project.path(), Some(home.path()));
        let meta = reg.get("ua").unwrap();
        assert_eq!(meta.description, "agents user");
        assert_eq!(canonical(&meta.location), canonical(&agents_path));
        assert_eq!(meta.scope, SkillScope::User);
    }

    #[test]
    fn collision_pushes_diagnostic_with_both_paths() {
        let project = TempDir::new().unwrap();
        touch_git(project.path());
        skill_at(project.path(), CLAUDE_DIR, "dup", &minimal("dup", "first"));
        skill_at(project.path(), AGENTS_DIR, "dup", &minimal("dup", "second"));

        let home = TempDir::new().unwrap();
        let reg = discover_inner(project.path(), Some(home.path()));
        let diag = reg
            .diagnostics()
            .iter()
            .find(|d| d.contains("dup"))
            .expect("expected duplicate diagnostic");
        // Diagnostic carries both vendor-dir paths; assert on the vendor
        // and skill-folder names only -- absolute paths differ by darwin
        // canonicalization and by `\` vs `/` on windows.
        assert!(
            diag.contains(CLAUDE_DIR) && diag.contains("dup"),
            "diag missing claude path: {diag}"
        );
        assert!(
            diag.contains(AGENTS_DIR) && diag.contains("dup"),
            "diag missing agents path: {diag}"
        );
    }

    #[test]
    fn skips_dotgit_and_node_modules() {
        let project = TempDir::new().unwrap();
        touch_git(project.path());
        // Plant a SKILL.md inside node_modules to make sure we never
        // walk into it from the skills root.
        let nested = project
            .path()
            .join(AGENTS_DIR)
            .join(SKILLS_SUBDIR)
            .join("real")
            .join("node_modules")
            .join("evil");
        fs::create_dir_all(&nested).unwrap();
        write(
            &nested.join(SKILL_FILE),
            &minimal("evil", "should not load"),
        );
        // Plant the real skill under a valid sibling dir.
        skill_at(project.path(), AGENTS_DIR, "real", &minimal("real", "ok"));

        let home = TempDir::new().unwrap();
        let reg = discover_inner(project.path(), Some(home.path()));
        assert!(reg.get("real").is_some());
        assert!(reg.get("evil").is_none());
    }

    #[cfg(unix)]
    #[test]
    fn does_not_follow_symlinks_out_of_root() {
        use std::os::unix::fs::symlink;
        // Plant a real skill outside the project tree, then symlink it
        // into the project's skills root. With follow_links(false), the
        // symlinked SKILL.md is not walked through to.
        let outside = TempDir::new().unwrap();
        let outside_skill_dir = outside.path().join("evil");
        fs::create_dir_all(&outside_skill_dir).unwrap();
        write(
            &outside_skill_dir.join(SKILL_FILE),
            &minimal("evil", "should not load"),
        );

        let project = TempDir::new().unwrap();
        touch_git(project.path());
        let skills_root = project.path().join(AGENTS_DIR).join(SKILLS_SUBDIR);
        fs::create_dir_all(&skills_root).unwrap();
        symlink(&outside_skill_dir, skills_root.join("evil")).unwrap();

        let home = TempDir::new().unwrap();
        let reg = discover_inner(project.path(), Some(home.path()));
        assert!(reg.get("evil").is_none(), "symlink traversal must be off");
    }

    #[test]
    fn body_is_stripped_of_frontmatter() {
        let project = TempDir::new().unwrap();
        touch_git(project.path());
        let p = skill_at(
            project.path(),
            AGENTS_DIR,
            "b",
            "---\nname: b\ndescription: x\n---\n# Heading\n\nBody text\n",
        );
        let body = read_skill_body(&p).unwrap();
        assert!(body.starts_with("# Heading"));
        assert!(body.contains("Body text"));
        assert!(!body.contains("---"));
    }

    #[test]
    fn list_bundled_resources_omits_skill_md_and_dotfiles() {
        let project = TempDir::new().unwrap();
        touch_git(project.path());
        let p = skill_at(
            project.path(),
            AGENTS_DIR,
            "withres",
            &minimal("withres", "x"),
        );
        let skill_dir = p.parent().unwrap();
        write(&skill_dir.join("scripts").join("run.sh"), "#!/bin/sh\n");
        write(&skill_dir.join("references").join("note.md"), "n");
        write(&skill_dir.join(".hidden"), "h");

        let resources = list_bundled_resources(skill_dir);
        assert!(resources.contains(&"scripts/run.sh".to_string()));
        assert!(resources.contains(&"references/note.md".to_string()));
        assert!(!resources.iter().any(|r| r.contains("SKILL.md")));
        assert!(!resources.iter().any(|r| r.contains(".hidden")));
    }

    #[test]
    fn iter_sorted_is_alphabetical() {
        let project = TempDir::new().unwrap();
        touch_git(project.path());
        skill_at(project.path(), AGENTS_DIR, "zebra", &minimal("zebra", "z"));
        skill_at(project.path(), AGENTS_DIR, "apple", &minimal("apple", "a"));
        skill_at(project.path(), AGENTS_DIR, "mango", &minimal("mango", "m"));

        let home = TempDir::new().unwrap();
        let reg = discover_inner(project.path(), Some(home.path()));
        let names: Vec<&str> = reg.iter_sorted().map(|m| m.name.as_str()).collect();
        assert_eq!(names, vec!["apple", "mango", "zebra"]);
    }
}
