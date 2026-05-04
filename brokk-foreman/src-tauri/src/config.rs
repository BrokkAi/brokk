//! Foreman configuration on disk. Layout decisions and concrete loader land
//! in a follow-up — for now this module exists so the rest of the tree can
//! reference `crate::config` without breaking.

#![allow(dead_code)]

use std::path::PathBuf;

use directories::ProjectDirs;

/// Resolve the per-user data directory for foreman, e.g.
/// `~/Library/Application Support/ai.brokk.foreman/` on macOS.
///
/// We return `None` if the platform's user dirs cannot be resolved (very
/// unusual; happens on bare CI containers without `$HOME`).
pub fn data_dir() -> Option<PathBuf> {
    ProjectDirs::from("ai", "brokk", "foreman").map(|p| p.data_dir().to_path_buf())
}
