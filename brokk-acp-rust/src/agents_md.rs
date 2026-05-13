//! AGENTS.md / CLAUDE.md discovery for the ACP prompt context.
//!
//! Honors the [agents.md](https://agents.md/) open convention plus the
//! global-config proposal at <https://github.com/agentsmd/agents.md/issues/91>.
//! At every slot (global + each directory from git root down to cwd) we
//! prefer `AGENTS.md`; if it is absent we fall back to `CLAUDE.md` at the
//! same slot. Results are concatenated general -> specific so deeper
//! directories naturally override.
//!
//! Walk-up is bounded by the discovered git root so a cwd without an
//! ancestor `.git` cannot accidentally pick up a stray AGENTS.md from
//! `~`, `/tmp`, or `/`.
//!
//! Pure module; no LLM/session deps. Re-runs are cheap and idempotent --
//! callers may invoke `discover` again whenever a session's cwd changes.

use std::io::ErrorKind;
use std::path::{Path, PathBuf};

pub const MAX_TOTAL_BYTES: usize = 64 * 1024;
/// Per-file ceiling enforced before any bytes are read. Generous enough
/// to fit any plausible hand-written AGENTS.md, snug enough that a
/// pathological multi-MB / multi-GB file fails fast instead of OOM-ing
/// the agent. Routed through `SandboxBackend::read_file_bounded`, so on
/// the wasm path the wasm linear-memory cap is the second backstop.
const MAX_FILE_BYTES: u64 = 1024 * 1024;
const PRIMARY: &str = "AGENTS.md";
const FALLBACK: &str = "CLAUDE.md";

/// Discover and concatenate project instructions for the given working
/// directory. Returns an empty string when nothing is found.
///
/// Order (general -> specific):
///   1. global AGENTS.md (XDG / APPDATA)
///   2. global CLAUDE.md (`~/.claude/CLAUDE.md`)  -- only if (1) is absent
///   3. AGENTS.md at the git root (else CLAUDE.md there)
///   4. AGENTS.md at each intermediate directory down to `cwd`
///   5. AGENTS.md at `cwd` itself
///
/// The total byte size is capped at [`MAX_TOTAL_BYTES`]; further reads
/// stop and the offending file is truncated on a UTF-8 char boundary
/// with a `tracing::warn!`.
pub fn discover(cwd: &Path) -> String {
    let global = read_global_default();
    discover_inner(cwd, global)
}

fn discover_inner(cwd: &Path, global: Option<(PathBuf, String)>) -> String {
    let cwd = normalize_path(cwd);
    let mut slots: Vec<(PathBuf, String)> = Vec::new();

    if let Some(slot) = global {
        slots.push(slot);
    }

    let git_root = find_git_root(&cwd);
    let dir_chain = build_dir_chain(&cwd, git_root.as_deref());
    let display_base = git_root.as_deref().unwrap_or(&cwd);
    for dir in dir_chain {
        if let Some((path, content)) = read_preferred(&dir) {
            slots.push((path, content));
        }
    }

    if slots.is_empty() {
        return String::new();
    }

    let mut out = String::new();
    let mut remaining = MAX_TOTAL_BYTES;
    let found_paths: Vec<&Path> = slots.iter().map(|(p, _)| p.as_path()).collect();
    tracing::info!(found = ?found_paths, "AGENTS.md discovery");

    for (path, content) in &slots {
        if remaining == 0 {
            tracing::warn!(
                path = %path.display(),
                "AGENTS.md discovery cap reached ({} bytes); skipping remaining files",
                MAX_TOTAL_BYTES
            );
            break;
        }

        let display = render_display_path(path, display_base);
        let header = format!("\n--- {} ---\n", display);
        let header_bytes = header.len();
        if header_bytes > remaining {
            tracing::warn!(
                path = %path.display(),
                "AGENTS.md discovery cap reached while emitting header; skipping"
            );
            break;
        }
        out.push_str(&header);
        remaining -= header_bytes;

        let (chunk, truncated) = clip_at_char_boundary(content, remaining);
        out.push_str(chunk);
        remaining = remaining.saturating_sub(chunk.len());
        if truncated {
            tracing::warn!(
                path = %path.display(),
                "AGENTS.md content truncated at {}-byte cap",
                MAX_TOTAL_BYTES
            );
            out.push_str("\n[...truncated]\n");
            remaining = 0;
        } else if !chunk.ends_with('\n') {
            // Make sure successive headers don't run onto a non-newline-
            // terminated file's last line.
            if remaining > 0 {
                out.push('\n');
                remaining -= 1;
            }
        }
    }
    out
}

/// Returns the chain of directories from the git root down to `cwd`
/// (inclusive of both ends, deduplicated when `cwd == git_root`).
/// If no git root is provided, the chain is just `[cwd]`.
fn build_dir_chain(cwd: &Path, git_root: Option<&Path>) -> Vec<PathBuf> {
    let Some(root) = git_root else {
        return vec![cwd.to_path_buf()];
    };
    if root == cwd {
        return vec![cwd.to_path_buf()];
    }
    let Ok(rel) = cwd.strip_prefix(root) else {
        // `cwd` is not under `root` (symlinks, exotic paths). Fall back
        // to a single-slot chain so we don't fabricate ancestors.
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

/// Walk up from `start` looking for an entry named `.git` (either a
/// directory in a normal repo or a file in a worktree/submodule).
/// Returns the first ancestor that contains `.git`, or `None` if none
/// is found before the filesystem root.
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

fn normalize_path(path: &Path) -> PathBuf {
    std::fs::canonicalize(path).unwrap_or_else(|_| path.to_path_buf())
}

fn read_preferred(dir: &Path) -> Option<(PathBuf, String)> {
    if let Some(hit) = read_if_exists(&dir.join(PRIMARY)) {
        return Some(hit);
    }
    read_if_exists(&dir.join(FALLBACK))
}

fn read_if_exists(path: &Path) -> Option<(PathBuf, String)> {
    // Route the read through the parser sandbox so an uncapped or
    // malicious file size cannot OOM the agent: the wasm backend
    // enforces both a metadata-size cap and a wasm linear-memory
    // limit, and the native backend honours the same `max_bytes`
    // pre-read check.
    match crate::sandbox_backend::global().read_file_bounded(path, MAX_FILE_BYTES) {
        Ok(Some(content)) => Some((path.to_path_buf(), content)),
        Ok(None) => None,
        Err(e) if e.kind() == ErrorKind::FileTooLarge => {
            tracing::warn!(
                path = %path.display(),
                "AGENTS.md candidate exceeds {MAX_FILE_BYTES}-byte cap; skipping: {e}"
            );
            None
        }
        Err(e) => {
            tracing::warn!(
                path = %path.display(),
                "AGENTS.md candidate is unreadable: {e}"
            );
            None
        }
    }
}

/// Global slot: try `AGENTS.md` at the XDG/APPDATA location first, fall
/// back to `~/.claude/CLAUDE.md` (the Claude Code convention path).
fn read_global_default() -> Option<(PathBuf, String)> {
    if let Some(p) = global_agents_path()
        && let Some(hit) = read_if_exists(&p)
    {
        return Some(hit);
    }
    if let Some(p) = global_claude_path()
        && let Some(hit) = read_if_exists(&p)
    {
        return Some(hit);
    }
    None
}

#[cfg(unix)]
fn global_agents_path() -> Option<PathBuf> {
    if let Ok(xdg) = std::env::var("XDG_CONFIG_HOME")
        && !xdg.is_empty()
    {
        return Some(PathBuf::from(xdg).join("agents").join(PRIMARY));
    }
    dirs::home_dir().map(|h| h.join(".config").join("agents").join(PRIMARY))
}

#[cfg(windows)]
fn global_agents_path() -> Option<PathBuf> {
    std::env::var("APPDATA")
        .ok()
        .filter(|s| !s.is_empty())
        .map(|s| PathBuf::from(s).join("agents").join(PRIMARY))
}

fn global_claude_path() -> Option<PathBuf> {
    dirs::home_dir().map(|h| h.join(".claude").join(FALLBACK))
}

fn render_display_path(path: &Path, base: &Path) -> String {
    if let Ok(rel) = path.strip_prefix(base) {
        if rel.as_os_str().is_empty() {
            return path.display().to_string();
        }
        return rel.display().to_string();
    }
    path.display().to_string()
}

/// Returns `(slice, was_truncated)`. When `s.len() <= max_bytes` the
/// whole string is returned and `was_truncated` is false. Otherwise the
/// slice is cut at the largest UTF-8 char boundary `<= max_bytes`.
fn clip_at_char_boundary(s: &str, max_bytes: usize) -> (&str, bool) {
    if s.len() <= max_bytes {
        return (s, false);
    }
    let mut end = max_bytes;
    while end > 0 && !s.is_char_boundary(end) {
        end -= 1;
    }
    (&s[..end], true)
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

    #[test]
    fn returns_empty_when_no_files_present() {
        let tmp = TempDir::new().unwrap();
        let out = discover_inner(tmp.path(), None);
        assert!(out.is_empty(), "expected empty discovery, got: {out:?}");
    }

    #[test]
    fn reads_agents_md_at_cwd() {
        let tmp = TempDir::new().unwrap();
        touch_git(tmp.path());
        write(&tmp.path().join(PRIMARY), "rule from agents");
        let out = discover_inner(tmp.path(), None);
        assert!(out.contains("rule from agents"));
        assert!(out.contains("AGENTS.md"));
    }

    #[test]
    fn falls_back_to_claude_md_when_agents_missing() {
        let tmp = TempDir::new().unwrap();
        touch_git(tmp.path());
        write(&tmp.path().join(FALLBACK), "rule from claude");
        let out = discover_inner(tmp.path(), None);
        assert!(out.contains("rule from claude"));
        assert!(out.contains("CLAUDE.md"));
    }

    #[cfg(unix)]
    #[test]
    fn ignores_special_agents_file_and_reads_fallback() {
        use std::ffi::CString;
        use std::os::unix::ffi::OsStrExt;

        let tmp = TempDir::new().unwrap();
        touch_git(tmp.path());
        let fifo = tmp.path().join(PRIMARY);
        let c_path = CString::new(fifo.as_os_str().as_bytes()).unwrap();
        let rc = unsafe { libc::mkfifo(c_path.as_ptr(), 0o644) };
        assert_eq!(rc, 0);
        write(&tmp.path().join(FALLBACK), "fallback rule");

        let out = discover_inner(tmp.path(), None);

        assert!(out.contains("fallback rule"));
        assert!(out.contains(FALLBACK));
        assert!(!out.contains(PRIMARY));
    }

    #[test]
    fn prefers_agents_when_both_present_in_same_dir() {
        let tmp = TempDir::new().unwrap();
        touch_git(tmp.path());
        write(&tmp.path().join(PRIMARY), "from agents");
        write(&tmp.path().join(FALLBACK), "from claude");
        let out = discover_inner(tmp.path(), None);
        assert!(out.contains("from agents"));
        assert!(
            !out.contains("from claude"),
            "CLAUDE.md should be ignored when AGENTS.md exists at same dir, got: {out}"
        );
    }

    #[test]
    fn walks_up_from_subdir_to_git_root() {
        let tmp = TempDir::new().unwrap();
        touch_git(tmp.path());
        write(&tmp.path().join(PRIMARY), "root rule");
        let sub = tmp.path().join("a").join("b");
        fs::create_dir_all(&sub).unwrap();
        write(&sub.join(PRIMARY), "leaf rule");

        let out = discover_inner(&sub, None);
        let root_pos = out.find("root rule").expect("root rule missing");
        let leaf_pos = out.find("leaf rule").expect("leaf rule missing");
        assert!(
            root_pos < leaf_pos,
            "expected root before leaf (general -> specific); got:\n{out}"
        );
    }

    #[cfg(unix)]
    #[test]
    fn canonicalizes_symlinked_cwd_before_git_root_walk() {
        use std::os::unix::fs::symlink;

        let repo = TempDir::new().unwrap();
        touch_git(repo.path());
        write(&repo.path().join(PRIMARY), "root rule");
        let real_subdir = repo.path().join("a").join("b");
        fs::create_dir_all(&real_subdir).unwrap();

        let links = TempDir::new().unwrap();
        let linked_cwd = links.path().join("linked-cwd");
        symlink(&real_subdir, &linked_cwd).unwrap();

        let out = discover_inner(&linked_cwd, None);

        assert!(
            out.contains("root rule"),
            "symlinked cwd should discover the real repo root; got:\n{out}"
        );
    }

    #[test]
    fn mixed_agents_at_root_and_claude_in_subdir() {
        let tmp = TempDir::new().unwrap();
        touch_git(tmp.path());
        write(&tmp.path().join(PRIMARY), "root agents");
        let sub = tmp.path().join("pkg");
        fs::create_dir_all(&sub).unwrap();
        write(&sub.join(FALLBACK), "pkg claude");

        let out = discover_inner(&sub, None);
        assert!(out.contains("root agents"));
        assert!(out.contains("pkg claude"));
    }

    #[test]
    fn no_git_ancestor_uses_cwd_only_no_parent_walk() {
        let tmp = TempDir::new().unwrap();
        // Sibling AGENTS.md outside the cwd subdir; if we wrongly walked
        // up to the fs root we'd pick it up.
        write(&tmp.path().join(PRIMARY), "should not appear");
        let cwd = tmp.path().join("isolated");
        fs::create_dir_all(&cwd).unwrap();
        write(&cwd.join(PRIMARY), "only this");

        let out = discover_inner(&cwd, None);
        assert!(out.contains("only this"));
        assert!(
            !out.contains("should not appear"),
            "walk-up must stop at cwd when no .git ancestor; got: {out}"
        );
    }

    #[test]
    fn global_slot_is_prepended() {
        let tmp = TempDir::new().unwrap();
        touch_git(tmp.path());
        write(&tmp.path().join(PRIMARY), "project rule");
        let fake_global = PathBuf::from("/fake/global/AGENTS.md");
        let global = Some((fake_global, "global rule".to_string()));

        let out = discover_inner(tmp.path(), global);
        let g_pos = out.find("global rule").expect("global rule missing");
        let p_pos = out.find("project rule").expect("project rule missing");
        assert!(
            g_pos < p_pos,
            "global should appear before project; got:\n{out}"
        );
    }

    #[test]
    fn cwd_equals_git_root_does_not_double_emit() {
        let tmp = TempDir::new().unwrap();
        touch_git(tmp.path());
        write(&tmp.path().join(PRIMARY), "single rule");
        let out = discover_inner(tmp.path(), None);
        assert_eq!(out.matches("single rule").count(), 1);
    }

    #[test]
    fn oversized_content_is_truncated_at_cap() {
        let tmp = TempDir::new().unwrap();
        touch_git(tmp.path());
        let big = "x".repeat(MAX_TOTAL_BYTES + 4096);
        write(&tmp.path().join(PRIMARY), &big);

        let out = discover_inner(tmp.path(), None);
        assert!(out.contains("[...truncated]"));
        assert!(
            out.len() <= MAX_TOTAL_BYTES + 64,
            "output exceeds cap with slack: {} bytes",
            out.len()
        );
    }

    #[test]
    fn truncation_preserves_utf8_char_boundary() {
        // Pick a char that crosses the cap boundary.
        let mut s = String::with_capacity(MAX_TOTAL_BYTES + 16);
        // Fill almost to cap...
        for _ in 0..(MAX_TOTAL_BYTES - 1) {
            s.push('a');
        }
        // ...then a 4-byte char that would straddle the cap.
        s.push('\u{1F600}');
        s.push_str("trailing");

        let tmp = TempDir::new().unwrap();
        touch_git(tmp.path());
        write(&tmp.path().join(PRIMARY), &s);

        let out = discover_inner(tmp.path(), None);
        // Should not panic and the emitted UTF-8 must be valid.
        assert!(out.is_char_boundary(out.len()));
    }
}
