//! Pass-through launcher for the in-progress Rust migration.
//!
//! Forwards every invocation to the legacy Python/JVM `brokk`. Discovery
//! order, in line with PLANS.md Phase 1.2:
//!
//! 1. `$BROKK_LEGACY_BIN` -- an explicit path to the legacy executable.
//! 2. A sibling `brokk-code/` source tree (dev-checkout case): run
//!    `uv run --directory <brokk-code> brokk` if `uv` is on PATH.
//! 3. `~/.local/share/brokk/legacy-brokk` -- the location the installer
//!    will populate in a later phase.
//!
//! On Unix this `execvp`s the target so the launcher process is replaced.
//! On Windows it spawns, waits, and propagates the exit code.

use std::ffi::OsString;
use std::path::PathBuf;
use std::process::{Command, ExitCode};

const EXIT_NO_LEGACY: u8 = 127;

fn main() -> ExitCode {
    let args: Vec<OsString> = std::env::args_os().skip(1).collect();

    let Some(legacy) = locate_legacy() else {
        eprintln!(
            "brokk: legacy brokk install not found. Set BROKK_LEGACY_BIN, \
             run from a checkout with `brokk-code/` reachable and `uv` on \
             PATH, or install the legacy brokk at \
             ~/.local/share/brokk/legacy-brokk."
        );
        return ExitCode::from(EXIT_NO_LEGACY);
    };

    forward(legacy, args)
}

#[derive(Debug)]
enum Legacy {
    Direct(PathBuf),
    UvRun {
        brokk_code_dir: PathBuf,
        uv: PathBuf,
    },
}

fn locate_legacy() -> Option<Legacy> {
    if let Some(path) = std::env::var_os("BROKK_LEGACY_BIN") {
        let p = PathBuf::from(path);
        if p.is_file() {
            return Some(Legacy::Direct(p));
        }
    }

    if let (Some(brokk_code_dir), Some(uv)) = (find_brokk_code_dir(), which("uv")) {
        return Some(Legacy::UvRun { brokk_code_dir, uv });
    }

    if let Some(home) = std::env::var_os("HOME") {
        let p = PathBuf::from(home).join(".local/share/brokk/legacy-brokk");
        if p.is_file() {
            return Some(Legacy::Direct(p));
        }
    }

    None
}

fn find_brokk_code_dir() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    let mut dir = exe.parent()?.to_path_buf();
    loop {
        let candidate = dir.join("brokk-code");
        if candidate.join("pyproject.toml").is_file() {
            return Some(candidate);
        }
        if !dir.pop() {
            return None;
        }
    }
}

fn which(prog: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    for dir in std::env::split_paths(&path) {
        for name in candidate_names(prog) {
            let candidate = dir.join(&name);
            if candidate.is_file() {
                return Some(candidate);
            }
        }
    }
    None
}

#[cfg(windows)]
fn candidate_names(prog: &str) -> Vec<String> {
    vec![format!("{prog}.exe"), prog.to_string()]
}

#[cfg(not(windows))]
fn candidate_names(prog: &str) -> Vec<String> {
    vec![prog.to_string()]
}

fn build_command(legacy: &Legacy) -> Command {
    match legacy {
        Legacy::Direct(path) => Command::new(path),
        Legacy::UvRun { brokk_code_dir, uv } => {
            let mut c = Command::new(uv);
            c.arg("run")
                .arg("--directory")
                .arg(brokk_code_dir)
                .arg("brokk");
            c
        }
    }
}

#[cfg(unix)]
fn forward(legacy: Legacy, args: Vec<OsString>) -> ExitCode {
    use std::os::unix::process::CommandExt;
    let mut cmd = build_command(&legacy);
    cmd.args(&args);
    let err = cmd.exec();
    eprintln!("brokk: failed to exec legacy brokk ({legacy:?}): {err}");
    ExitCode::from(EXIT_NO_LEGACY)
}

#[cfg(not(unix))]
fn forward(legacy: Legacy, args: Vec<OsString>) -> ExitCode {
    let mut cmd = build_command(&legacy);
    cmd.args(&args);
    match cmd.status() {
        Ok(status) => {
            let code = status.code().unwrap_or(1);
            std::process::exit(code);
        }
        Err(err) => {
            eprintln!("brokk: failed to spawn legacy brokk ({legacy:?}): {err}");
            ExitCode::from(EXIT_NO_LEGACY)
        }
    }
}
