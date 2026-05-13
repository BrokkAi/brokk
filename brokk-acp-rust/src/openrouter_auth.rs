//! On-disk credential store for OpenRouter.
//!
//! Unlike Codex, OpenRouter has no OAuth flow -- the user pastes a static
//! `sk-or-...` key once and we reuse it forever (until they rotate or
//! disconnect). Persistence is opt-in: users who export
//! `OPENROUTER_API_KEY` in their shell get the existing zero-config
//! behaviour, and this file is only created when `/openrouter-login
//! <key>` is invoked from a session.
//!
//! Storage location follows OS conventions via `dirs::config_dir()`:
//! `~/.config/brokk/openrouter.json` on Linux (or `$XDG_CONFIG_HOME`),
//! `~/Library/Application Support/brokk/openrouter.json` on macOS,
//! `%APPDATA%\brokk\openrouter.json` on Windows. The file is written
//! atomically (stage `.tmp` then rename) and chmod'd to 0600 on Unix so
//! other local users can't read the key.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result, anyhow};
use serde::{Deserialize, Serialize};

use crate::discovery::OPENROUTER_API_KEY_ENV;

/// Flat one-field record. OpenRouter keys are static (no refresh, no
/// expiry, no auth_mode) so there's nothing more to persist.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenRouterAuth {
    pub api_key: String,
}

/// Snapshot of where OpenRouter credentials currently come from.
/// Single source of truth for the "env owns" contract: whenever
/// `OPENROUTER_API_KEY` is non-empty the environment owns the
/// credential lifecycle, `/openrouter-login` is hidden from
/// autocomplete, and the slash command returns an explanation rather
/// than mutating state.
///
/// Both reads (`env_set`, `file_present`) treat any failure as
/// "absent" so callers can render a consistent UI even when the file
/// is malformed or the env var is unset -- diagnostic output should
/// never panic, and a broken on-disk file is functionally equivalent
/// to no file at all.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CredentialState {
    pub env_set: bool,
    pub file_present: bool,
}

impl CredentialState {
    /// Read the current env+file state. Cheap: a single env lookup and
    /// (when needed) a small disk read; safe to call from
    /// `available_commands_update` paths and per-request handlers.
    pub fn snapshot() -> Self {
        let env_set = std::env::var(OPENROUTER_API_KEY_ENV)
            .ok()
            .map(|s| !s.trim().is_empty())
            .unwrap_or(false);
        let file_present = match read() {
            Ok(Some(auth)) => !auth.api_key.trim().is_empty(),
            _ => false,
        };
        Self {
            env_set,
            file_present,
        }
    }

    /// Where the active credential, if any, is being read from.
    /// Mirrors the precedence in `build_openrouter_backend`: env wins
    /// over file, file wins over nothing.
    pub fn active_source(&self) -> &'static str {
        if self.env_set {
            "env"
        } else if self.file_present {
            "file"
        } else {
            "none"
        }
    }

    /// True when the environment owns the credential lifecycle.
    /// Callers should hide `/openrouter-login` from autocomplete and
    /// have the handler explain rather than mutate state.
    pub fn env_owns(&self) -> bool {
        self.env_set
    }
}

/// Resolve `<config>/brokk/openrouter.json`. Honours `$BROKK_CONFIG_HOME`
/// if set so tests (and power users) can redirect the credential file
/// without touching the real one.
pub fn auth_path() -> Result<PathBuf> {
    if let Ok(custom) = std::env::var("BROKK_CONFIG_HOME") {
        return Ok(PathBuf::from(custom).join("openrouter.json"));
    }
    let base = dirs::config_dir().ok_or_else(|| {
        anyhow!("could not resolve OS config directory for OpenRouter credentials")
    })?;
    Ok(base.join("brokk").join("openrouter.json"))
}

pub fn read() -> Result<Option<OpenRouterAuth>> {
    let path = auth_path()?;
    if !path.exists() {
        return Ok(None);
    }
    let bytes = std::fs::read(&path).with_context(|| format!("reading {}", path.display()))?;
    let parsed = serde_json::from_slice::<OpenRouterAuth>(&bytes)
        .with_context(|| format!("parsing {}", path.display()))?;
    Ok(Some(parsed))
}

/// Atomic write: stage to `openrouter.json.tmp` in the same directory,
/// chmod to 0600, then rename. Mirrors `codex_auth::write_auth_dot_json`
/// so a crash mid-write never leaves a half-written credential file.
pub fn write(auth: &OpenRouterAuth) -> Result<()> {
    let path = auth_path()?;
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .with_context(|| format!("creating {}", parent.display()))?;
    }
    let tmp = path.with_extension("json.tmp");
    let bytes = serde_json::to_vec_pretty(auth).context("serializing OpenRouterAuth")?;
    std::fs::write(&tmp, &bytes).with_context(|| format!("writing {}", tmp.display()))?;
    set_user_only_perms(&tmp)?;
    std::fs::rename(&tmp, &path)
        .with_context(|| format!("renaming {} -> {}", tmp.display(), path.display()))?;
    Ok(())
}

/// Best-effort logout: delete the stored credentials. Missing file is
/// not an error -- `/openrouter-login disconnect` is idempotent.
pub fn logout() -> Result<()> {
    let path = auth_path()?;
    if path.exists() {
        std::fs::remove_file(&path).with_context(|| format!("removing {}", path.display()))?;
    }
    Ok(())
}

#[cfg(unix)]
fn set_user_only_perms(path: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))
        .with_context(|| format!("chmod 600 {}", path.display()))
}

#[cfg(not(unix))]
fn set_user_only_perms(_path: &Path) -> Result<()> {
    Ok(())
}

/// Test-only helpers shared with sibling modules so any test that
/// mutates `OPENROUTER_API_KEY` or `BROKK_CONFIG_HOME` serialises on a
/// single process-wide mutex. Env mutation in multi-threaded Rust is
/// `unsafe` (POSIX `getenv` is not atomic), so one guard for all
/// env-touching tests is the minimum-friction safe pattern.
#[cfg(test)]
pub(crate) mod test_support {
    use std::ffi::OsStr;
    use tokio::sync::Mutex;

    /// Acquire this before mutating any env var read by either
    /// `openrouter_auth` or any caller of `CredentialState::snapshot`.
    /// Holding it during the whole test (until `EnvScope` drops) keeps
    /// concurrent tests from observing partial state.
    ///
    /// Uses `tokio::sync::Mutex` instead of `std::sync::Mutex` so the
    /// guard can be held across `.await` points in `#[tokio::test]`
    /// cases without tripping `clippy::await_holding_lock`. Sync
    /// `#[test]` cases acquire it via `blocking_lock()`; async cases
    /// use `.lock().await`. The mutex is constructed via `const_new`
    /// so it fits in a plain `static`.
    pub(crate) static ENV_GUARD: Mutex<()> = Mutex::const_new(());

    /// RAII guard that sets (or removes) an env var on construction and
    /// restores the previous value on drop. Pair with a held lock on
    /// `ENV_GUARD` for cross-test safety.
    pub(crate) struct EnvScope {
        var: &'static str,
        prev: Option<String>,
    }

    impl EnvScope {
        pub(crate) fn set(var: &'static str, value: impl AsRef<OsStr>) -> Self {
            let prev = std::env::var(var).ok();
            // SAFETY: callers hold `ENV_GUARD` so no concurrent thread
            // is reading or writing this process's env table.
            unsafe {
                std::env::set_var(var, value);
            }
            Self { var, prev }
        }

        pub(crate) fn remove(var: &'static str) -> Self {
            let prev = std::env::var(var).ok();
            // SAFETY: see `set`.
            unsafe {
                std::env::remove_var(var);
            }
            Self { var, prev }
        }
    }

    impl Drop for EnvScope {
        fn drop(&mut self) {
            // SAFETY: see `EnvScope::set`.
            unsafe {
                match &self.prev {
                    Some(v) => std::env::set_var(self.var, v),
                    None => std::env::remove_var(self.var),
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::openrouter_auth::test_support::{ENV_GUARD, EnvScope};

    #[test]
    fn round_trip_writes_then_reads_same_key() {
        let _lock = ENV_GUARD.blocking_lock();
        let tmp = tempfile::tempdir().unwrap();
        let _scope = EnvScope::set("BROKK_CONFIG_HOME", tmp.path());

        assert!(read().unwrap().is_none(), "no key before write");
        write(&OpenRouterAuth {
            api_key: "sk-or-test-key".to_string(),
        })
        .unwrap();
        let got = read().unwrap().expect("key present after write");
        assert_eq!(got.api_key, "sk-or-test-key");
    }

    #[test]
    fn logout_removes_file_and_is_idempotent() {
        let _lock = ENV_GUARD.blocking_lock();
        let tmp = tempfile::tempdir().unwrap();
        let _scope = EnvScope::set("BROKK_CONFIG_HOME", tmp.path());

        write(&OpenRouterAuth {
            api_key: "sk-or-test".to_string(),
        })
        .unwrap();
        assert!(auth_path().unwrap().exists());
        logout().unwrap();
        assert!(!auth_path().unwrap().exists());
        // second call must not error
        logout().unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn write_sets_user_only_permissions() {
        use std::os::unix::fs::PermissionsExt;
        let _lock = ENV_GUARD.blocking_lock();
        let tmp = tempfile::tempdir().unwrap();
        let _scope = EnvScope::set("BROKK_CONFIG_HOME", tmp.path());

        write(&OpenRouterAuth {
            api_key: "sk-or-test".to_string(),
        })
        .unwrap();
        let perms = std::fs::metadata(auth_path().unwrap())
            .unwrap()
            .permissions();
        assert_eq!(
            perms.mode() & 0o777,
            0o600,
            "credential file must be readable only by the owner"
        );
    }

    #[test]
    fn credential_state_reports_env_when_env_set() {
        let _lock = ENV_GUARD.blocking_lock();
        let tmp = tempfile::tempdir().unwrap();
        let _brokk = EnvScope::set("BROKK_CONFIG_HOME", tmp.path());
        let _env = EnvScope::set("OPENROUTER_API_KEY", "sk-or-from-env");

        let state = CredentialState::snapshot();
        assert!(state.env_set);
        assert!(!state.file_present);
        assert!(state.env_owns());
        assert_eq!(state.active_source(), "env");
    }

    #[test]
    fn credential_state_reports_file_when_only_file_set() {
        let _lock = ENV_GUARD.blocking_lock();
        let tmp = tempfile::tempdir().unwrap();
        let _brokk = EnvScope::set("BROKK_CONFIG_HOME", tmp.path());
        let _env = EnvScope::remove("OPENROUTER_API_KEY");
        write(&OpenRouterAuth {
            api_key: "sk-or-from-file".to_string(),
        })
        .unwrap();

        let state = CredentialState::snapshot();
        assert!(!state.env_set);
        assert!(state.file_present);
        assert!(!state.env_owns());
        assert_eq!(state.active_source(), "file");
    }

    #[test]
    fn credential_state_reports_none_when_nothing_set() {
        let _lock = ENV_GUARD.blocking_lock();
        let tmp = tempfile::tempdir().unwrap();
        let _brokk = EnvScope::set("BROKK_CONFIG_HOME", tmp.path());
        let _env = EnvScope::remove("OPENROUTER_API_KEY");

        let state = CredentialState::snapshot();
        assert!(!state.env_set);
        assert!(!state.file_present);
        assert!(!state.env_owns());
        assert_eq!(state.active_source(), "none");
    }

    #[test]
    fn credential_state_treats_blank_env_as_unset() {
        let _lock = ENV_GUARD.blocking_lock();
        let tmp = tempfile::tempdir().unwrap();
        let _brokk = EnvScope::set("BROKK_CONFIG_HOME", tmp.path());
        let _env = EnvScope::set("OPENROUTER_API_KEY", "   ");

        let state = CredentialState::snapshot();
        // Trim-empty env var must NOT take ownership: matches the
        // startup parser in `build_openrouter_backend`, which falls
        // through to the file when the env is whitespace-only.
        assert!(!state.env_set);
        assert!(!state.env_owns());
    }
}
