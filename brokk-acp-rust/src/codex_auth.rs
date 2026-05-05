//! "Sign in with ChatGPT" support using OpenAI's PKCE OAuth flow.
//!
//! On-disk format (`~/.codex/auth.json`) is intentionally compatible with
//! Codex CLI: a user who logs in here can use `codex` in the same terminal
//! and vice versa. Once the OAuth flow completes we exchange the issued
//! `id_token` for a regular `sk-...` API key (RFC 8693 Token Exchange) so
//! that brokk-acp-rust's existing OpenAI-compatible client can talk to
//! `https://api.openai.com/v1` unchanged.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result, anyhow};
use chrono::{DateTime, Duration, Utc};
use openai_auth::{OAuthClient, OAuthConfig, TokenSet, open_browser, run_callback_server};
use serde::{Deserialize, Serialize};

/// Default port for the OAuth loopback callback. Must match the
/// `redirect_uri` advertised to the authorization server, which means it
/// has to be one of the ports allow-listed by OpenAI's Hydra config.
const CALLBACK_PORT: u16 = 1455;

/// Refresh proactively if the stored credentials are older than this.
/// Codex CLI uses an 8-day window; we follow suit.
const REFRESH_AFTER: Duration = Duration::days(8);

/// Schema of `~/.codex/auth.json`. Field names (and the `OPENAI_API_KEY`
/// SHOUTING case) are dictated by Codex CLI's storage format -- do not
/// rename without breaking cross-compat.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuthDotJson {
    #[serde(default)]
    pub auth_mode: Option<String>,
    #[serde(
        rename = "OPENAI_API_KEY",
        default,
        skip_serializing_if = "Option::is_none"
    )]
    pub openai_api_key: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tokens: Option<TokenData>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_refresh: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TokenData {
    pub id_token: String,
    pub access_token: String,
    pub refresh_token: String,
    pub account_id: String,
}

/// Resolve `~/.codex/auth.json`. Honours `$CODEX_HOME` if set, matching
/// Codex CLI's override convention.
pub fn auth_json_path() -> Result<PathBuf> {
    if let Ok(custom) = std::env::var("CODEX_HOME") {
        return Ok(PathBuf::from(custom).join("auth.json"));
    }
    let home = dirs::home_dir().ok_or_else(|| anyhow!("could not resolve home directory"))?;
    Ok(home.join(".codex").join("auth.json"))
}

pub fn read_auth_dot_json() -> Result<Option<AuthDotJson>> {
    let path = auth_json_path()?;
    if !path.exists() {
        return Ok(None);
    }
    let bytes = std::fs::read(&path).with_context(|| format!("reading {}", path.display()))?;
    let parsed = serde_json::from_slice::<AuthDotJson>(&bytes)
        .with_context(|| format!("parsing {}", path.display()))?;
    Ok(Some(parsed))
}

/// Atomic write: stage to `auth.json.tmp` in the same directory, then
/// rename. Keeps the credential file from ever being half-written if the
/// process is interrupted mid-flush.
pub fn write_auth_dot_json(auth: &AuthDotJson) -> Result<()> {
    let path = auth_json_path()?;
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .with_context(|| format!("creating {}", parent.display()))?;
    }
    let tmp = path.with_extension("json.tmp");
    let bytes = serde_json::to_vec_pretty(auth).context("serializing AuthDotJson")?;
    std::fs::write(&tmp, &bytes).with_context(|| format!("writing {}", tmp.display()))?;
    set_user_only_perms(&tmp)?;
    std::fs::rename(&tmp, &path)
        .with_context(|| format!("renaming {} -> {}", tmp.display(), path.display()))?;
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

/// Run the full ChatGPT login flow: PKCE in the browser, capture the
/// callback locally, exchange the `id_token` for an API key, and persist.
pub async fn interactive_login() -> Result<AuthDotJson> {
    let config = OAuthConfig::default();
    let client = OAuthClient::new(config).context("building OAuthClient")?;
    let flow = client.start_flow().context("starting OAuth flow")?;

    if let Err(err) = open_browser(&flow.authorization_url) {
        eprintln!(
            "Could not open a browser automatically ({err}). Visit:\n  {}",
            flow.authorization_url
        );
    } else {
        eprintln!("Opened browser for ChatGPT sign-in. Waiting for callback...");
    }

    let tokens = run_callback_server(CALLBACK_PORT, &flow.state, &client, &flow.pkce_verifier)
        .await
        .context("OAuth callback server failed")?;

    let auth = finish_login(&client, tokens).await?;
    write_auth_dot_json(&auth)?;
    Ok(auth)
}

async fn finish_login(client: &OAuthClient, tokens: TokenSet) -> Result<AuthDotJson> {
    // We need an id_token to redeem an API key (RFC 8693 token-exchange).
    let id_token = tokens
        .id_token
        .clone()
        .ok_or_else(|| anyhow!("OAuth response missing id_token; cannot derive API key"))?;
    let api_key = client
        .obtain_api_key(&id_token)
        .await
        .context("exchanging id_token for OPENAI_API_KEY")?;
    let account_id = client
        .extract_account_id(&tokens.access_token)
        .context("extracting account_id from access_token JWT")?;
    Ok(AuthDotJson {
        auth_mode: Some("chatgpt".to_string()),
        openai_api_key: Some(api_key),
        tokens: Some(TokenData {
            id_token,
            access_token: tokens.access_token,
            refresh_token: tokens.refresh_token,
            account_id,
        }),
        last_refresh: Some(Utc::now()),
    })
}

/// Refresh the API key if the stored credentials are stale. Used on
/// startup so long-running ACP sessions don't 401 mid-flight.
pub async fn refresh_if_stale(auth: &mut AuthDotJson) -> Result<bool> {
    let last = match auth.last_refresh {
        Some(ts) => ts,
        None => return Ok(false),
    };
    if Utc::now() - last < REFRESH_AFTER {
        return Ok(false);
    }
    let tokens = auth
        .tokens
        .as_ref()
        .ok_or_else(|| anyhow!("auth.json has no tokens to refresh"))?;
    let client = OAuthClient::new(OAuthConfig::default()).context("building OAuthClient")?;
    let refreshed = client
        .refresh_token(&tokens.refresh_token)
        .await
        .context("refreshing OAuth tokens")?;
    let updated = finish_login(&client, refreshed).await?;
    *auth = updated;
    write_auth_dot_json(auth)?;
    Ok(true)
}

/// Best-effort logout: delete the stored credentials. We do not (yet)
/// hit the revoke endpoint -- that's a follow-up.
pub fn logout() -> Result<()> {
    let path = auth_json_path()?;
    if path.exists() {
        std::fs::remove_file(&path).with_context(|| format!("removing {}", path.display()))?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn auth_json_round_trip_preserves_codex_field_names() {
        // Mirrors a real ~/.codex/auth.json from a logged-in Codex CLI install.
        let raw = r#"{
            "auth_mode": "chatgpt",
            "OPENAI_API_KEY": "sk-svcacct-test",
            "tokens": {
                "id_token": "eyJ.id",
                "access_token": "eyJ.acc",
                "refresh_token": "rt-test",
                "account_id": "acct_abc"
            },
            "last_refresh": "2026-05-05T12:00:00Z"
        }"#;
        let parsed: AuthDotJson = serde_json::from_str(raw).expect("parse auth.json");
        assert_eq!(parsed.auth_mode.as_deref(), Some("chatgpt"));
        assert_eq!(parsed.openai_api_key.as_deref(), Some("sk-svcacct-test"));
        let tokens = parsed.tokens.as_ref().expect("tokens");
        assert_eq!(tokens.account_id, "acct_abc");

        let reserialized = serde_json::to_string(&parsed).unwrap();
        // The shouting-snake-case key name is what Codex CLI expects;
        // serde's default would lower-case it.
        assert!(reserialized.contains("\"OPENAI_API_KEY\""));
        assert!(reserialized.contains("\"auth_mode\""));
        assert!(reserialized.contains("\"last_refresh\""));
    }

    #[test]
    fn auth_json_optional_fields_omit_cleanly() {
        let auth = AuthDotJson {
            auth_mode: Some("apikey".to_string()),
            openai_api_key: None,
            tokens: None,
            last_refresh: None,
        };
        let json = serde_json::to_string(&auth).unwrap();
        // Defaults that are None should not appear; auth_mode is always present.
        assert!(!json.contains("OPENAI_API_KEY"));
        assert!(!json.contains("tokens"));
        assert!(!json.contains("last_refresh"));
        assert!(json.contains("\"auth_mode\":\"apikey\""));
    }

    #[test]
    fn auth_json_path_honours_codex_home_override() {
        // Save and restore so we don't pollute other tests sharing the env.
        let prior = std::env::var("CODEX_HOME").ok();
        // SAFETY: tests run single-threaded for env mutations is not guaranteed
        // by cargo, but the assertion only reads what we just set, so a flake
        // would manifest as a clear mismatch rather than corruption.
        unsafe {
            std::env::set_var("CODEX_HOME", "/tmp/codex-home-test-xyz");
        }
        let p = auth_json_path().unwrap();
        assert_eq!(
            p,
            std::path::PathBuf::from("/tmp/codex-home-test-xyz/auth.json")
        );
        unsafe {
            match prior {
                Some(v) => std::env::set_var("CODEX_HOME", v),
                None => std::env::remove_var("CODEX_HOME"),
            }
        }
    }
}
