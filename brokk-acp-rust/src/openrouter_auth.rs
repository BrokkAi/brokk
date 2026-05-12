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

/// Flat one-field record. OpenRouter keys are static (no refresh, no
/// expiry, no auth_mode) so there's nothing more to persist.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenRouterAuth {
    pub api_key: String,
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    // BROKK_CONFIG_HOME is process-global; serialise tests that mutate it
    // so they don't race when cargo schedules them on different threads.
    static ENV_GUARD: Mutex<()> = Mutex::new(());

    struct EnvScope {
        prev: Option<String>,
    }

    impl EnvScope {
        fn new(path: &Path) -> Self {
            let prev = std::env::var("BROKK_CONFIG_HOME").ok();
            // SAFETY: tests are serialised on ENV_GUARD; no other thread
            // is reading or writing this env var concurrently.
            unsafe {
                std::env::set_var("BROKK_CONFIG_HOME", path);
            }
            Self { prev }
        }
    }

    impl Drop for EnvScope {
        fn drop(&mut self) {
            // SAFETY: see EnvScope::new.
            unsafe {
                match &self.prev {
                    Some(v) => std::env::set_var("BROKK_CONFIG_HOME", v),
                    None => std::env::remove_var("BROKK_CONFIG_HOME"),
                }
            }
        }
    }

    #[test]
    fn round_trip_writes_then_reads_same_key() {
        let _lock = ENV_GUARD.lock().unwrap();
        let tmp = tempfile::tempdir().unwrap();
        let _scope = EnvScope::new(tmp.path());

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
        let _lock = ENV_GUARD.lock().unwrap();
        let tmp = tempfile::tempdir().unwrap();
        let _scope = EnvScope::new(tmp.path());

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
        let _lock = ENV_GUARD.lock().unwrap();
        let tmp = tempfile::tempdir().unwrap();
        let _scope = EnvScope::new(tmp.path());

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
}
