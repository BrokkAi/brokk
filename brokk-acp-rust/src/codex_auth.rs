//! "Sign in with ChatGPT" support using OpenAI's PKCE OAuth flow.
//!
//! On-disk format (`~/.codex/auth.json`) is intentionally compatible with
//! Codex CLI: a user who logs in here can use `codex` in the same terminal
//! and vice versa.
//!
//! Trust posture: we use `oauth2` (32M+ downloads, depended on by Servo
//! among others) for the standard PKCE + refresh state machine. The one
//! step `oauth2` doesn't cover -- RFC 8693 token exchange that converts
//! the ChatGPT id_token into a regular `OPENAI_API_KEY` -- is a single
//! POST written by hand below. JWT decoding for `chatgpt_account_id`
//! is also inline (~15 lines): we trust the token because we just
//! received it over HTTPS from auth.openai.com, and we only need an
//! unverified claim.

use std::path::{Path, PathBuf};
use std::sync::mpsc;
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use base64::Engine;
use chrono::{DateTime, Utc};
// oauth2 supplies the PKCE + CSRF primitives only -- the actual token
// exchanges are issued via `reqwest` below so we don't have to dance
// with `oauth2::ExtraTokenFields` generics just to read `id_token`.
use oauth2::{CsrfToken, PkceCodeChallenge};
use serde::{Deserialize, Serialize};

const CLIENT_ID: &str = "app_EMoamEEZ73f0CkXaXp7hrann";
const AUTH_URL: &str = "https://auth.openai.com/oauth/authorize";
const TOKEN_URL: &str = "https://auth.openai.com/oauth/token";
const CALLBACK_PORT: u16 = 1455;
const REDIRECT_URI: &str = "http://localhost:1455/auth/callback";

/// OAuth scopes Codex CLI requests during ChatGPT login. The two
/// `api.connectors.*` scopes plus `id_token_add_organizations` /
/// `codex_cli_simplified_flow` flags below are what flips the consent
/// page from the legacy "API organization access" screen (the one that
/// says "Codex will receive a token to generate API keys") to the
/// ChatGPT-subscription consent screen. Drop any of them and OpenAI
/// silently routes us back to the API-key flow.
const SCOPES: &[&str] = &[
    "openid",
    "profile",
    "email",
    "offline_access",
    "api.connectors.read",
    "api.connectors.invoke",
];

/// Identifies our requests as Codex CLI to OpenAI's auth and Responses
/// servers. The ChatGPT-subscription consent UI and the
/// `chatgpt.com/backend-api/codex/responses` gateway both gate on this
/// value -- using anything else (or omitting it) is what produced the
/// "API organization access" screen reported in #3540's review.
const CODEX_ORIGINATOR: &str = "codex_cli_rs";

/// Refresh proactively if the stored credentials are older than this.
/// Codex CLI uses an 8-day window; we follow suit.
const REFRESH_AFTER: chrono::Duration = chrono::Duration::days(8);

/// Wait at most this long for the user to complete sign-in in the browser.
const CALLBACK_TIMEOUT: Duration = Duration::from_secs(5 * 60);

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

/// Standard OAuth2 token endpoint response, with the OIDC `id_token`
/// extension that ChatGPT's auth server returns alongside the regular
/// access/refresh tokens.
#[derive(Debug, Deserialize)]
struct OidcTokenResponse {
    access_token: String,
    refresh_token: Option<String>,
    id_token: Option<String>,
}

fn build_authorize_url(challenge: &PkceCodeChallenge, state: &str) -> String {
    // OAuth2 RFC 6749 + PKCE RFC 7636 plus three Codex-specific flags
    // that Codex CLI passes verbatim:
    //   * `id_token_add_organizations=true` -> the auth server includes
    //     the user's organization claim in the issued id_token, which
    //     downstream code uses to derive `chatgpt_account_id`.
    //   * `codex_cli_simplified_flow=true` -> tells OpenAI to render the
    //     ChatGPT-subscription consent screen ("Sign in with ChatGPT")
    //     instead of the legacy API-organization screen. Omitting this
    //     was the bug behind the "Codex requests access to your API
    //     organization" page users were seeing.
    //   * `originator=codex_cli_rs` -> identifies us as Codex CLI both
    //     to the consent UI and to the chatgpt.com/backend-api gateway.
    // Scopes already cover `api.connectors.*`; see SCOPES above for why
    // those are required for the simplified flow.
    let scopes_encoded = SCOPES
        .iter()
        .map(|s| urlencode(s))
        .collect::<Vec<_>>()
        .join("%20");
    format!(
        "{AUTH_URL}?response_type=code\
         &client_id={CLIENT_ID}\
         &redirect_uri={redirect}\
         &scope={scopes_encoded}\
         &code_challenge={code_challenge}\
         &code_challenge_method=S256\
         &id_token_add_organizations=true\
         &codex_cli_simplified_flow=true\
         &state={state}\
         &originator={originator}",
        redirect = urlencode(REDIRECT_URI),
        state = urlencode(state),
        code_challenge = challenge.as_str(),
        originator = urlencode(CODEX_ORIGINATOR),
    )
}

/// Minimal percent-encoder for the handful of characters that show up
/// in our query values (`/`, `:`, `=`, etc.). Avoids pulling in `url`
/// just to call `Url::parse`/`form_urlencoded::byte_serialize`.
fn urlencode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

fn http_client() -> Result<reqwest::Client> {
    reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .context("building reqwest client")
}

async fn exchange_code_for_tokens(
    http: &reqwest::Client,
    code: &str,
    verifier: &str,
) -> Result<OidcTokenResponse> {
    post_token_form(
        http,
        &[
            ("grant_type", "authorization_code"),
            ("client_id", CLIENT_ID),
            ("code", code),
            ("code_verifier", verifier),
            ("redirect_uri", REDIRECT_URI),
        ],
    )
    .await
}

async fn exchange_refresh_token(
    http: &reqwest::Client,
    refresh_token: &str,
) -> Result<OidcTokenResponse> {
    post_token_form(
        http,
        &[
            ("grant_type", "refresh_token"),
            ("client_id", CLIENT_ID),
            ("refresh_token", refresh_token),
            // Ask the server to keep issuing an id_token alongside the
            // new access/refresh pair so we can re-derive the API key.
            ("scope", &SCOPES.join(" ")),
        ],
    )
    .await
}

async fn post_token_form(
    http: &reqwest::Client,
    form: &[(&str, &str)],
) -> Result<OidcTokenResponse> {
    let resp = http
        .post(TOKEN_URL)
        .form(form)
        .send()
        .await
        .context("token endpoint POST failed")?;
    let status = resp.status();
    if !status.is_success() {
        let body = resp.text().await.unwrap_or_default();
        bail!("token endpoint returned HTTP {status}: {body}");
    }
    resp.json::<OidcTokenResponse>()
        .await
        .context("parsing token endpoint response")
}

/// Run the full ChatGPT login flow: PKCE in the browser, capture the
/// callback locally, exchange the `id_token` for an API key, and persist.
pub async fn interactive_login() -> Result<AuthDotJson> {
    let (challenge, verifier) = PkceCodeChallenge::new_random_sha256();
    let csrf = CsrfToken::new_random();
    let auth_url = build_authorize_url(&challenge, csrf.secret());

    if let Err(err) = webbrowser::open(&auth_url) {
        eprintln!(
            "Could not open a browser automatically ({err}). Visit:\n  {}",
            auth_url
        );
    } else {
        eprintln!("Opened browser for ChatGPT sign-in. Waiting for callback...");
    }

    let expected_state = csrf.secret().clone();
    let code = tokio::task::spawn_blocking(move || {
        loopback_capture_code(CALLBACK_PORT, expected_state, CALLBACK_TIMEOUT)
    })
    .await
    .context("loopback capture task panicked")??;

    let http = http_client()?;
    let token = exchange_code_for_tokens(&http, &code, verifier.secret()).await?;

    let id_token = token
        .id_token
        .clone()
        .ok_or_else(|| anyhow!("OAuth response missing id_token; cannot derive API key"))?;
    let refresh_token = token
        .refresh_token
        .clone()
        .ok_or_else(|| anyhow!("OAuth response missing refresh_token"))?;

    // Token-exchange (RFC 8693 id_token -> sk-...) is best-effort: a
    // ChatGPT-subscription user with no associated API organization
    // gets `Invalid ID token: missing organization_id` here, which is
    // expected -- they don't *have* an API key to derive. Codex CLI's
    // own `obtain_api_key(...).await.ok()` does the same thing.
    // Subscription routing only needs the access_token + account_id we
    // already have; the API key is stored only as a convenience for
    // users who later run `codex` itself in apikey mode.
    let api_key = match token_exchange_id_token(&http, TOKEN_URL, CLIENT_ID, &id_token).await {
        Ok(key) => Some(key),
        Err(e) => {
            tracing::info!(
                "skipping API key derivation (typical for ChatGPT-only accounts): {e:#}"
            );
            None
        }
    };
    let account_id = extract_chatgpt_account_id(&token.access_token)?;

    let auth = AuthDotJson {
        auth_mode: Some("chatgpt".to_string()),
        openai_api_key: api_key,
        tokens: Some(TokenData {
            id_token,
            access_token: token.access_token,
            refresh_token,
            account_id,
        }),
        last_refresh: Some(Utc::now()),
    };
    write_auth_dot_json(&auth)?;
    Ok(auth)
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
    let http = http_client()?;
    let refreshed = exchange_refresh_token(&http, &tokens.refresh_token).await?;

    let id_token = refreshed
        .id_token
        .clone()
        .ok_or_else(|| anyhow!("refresh response missing id_token"))?;
    let refresh_token = refreshed
        .refresh_token
        .clone()
        .unwrap_or_else(|| tokens.refresh_token.clone());

    // Same best-effort posture as `interactive_login`: refresh stays
    // successful even if the user's account can't mint an OPENAI_API_KEY.
    let api_key = match token_exchange_id_token(&http, TOKEN_URL, CLIENT_ID, &id_token).await {
        Ok(key) => Some(key),
        Err(e) => {
            tracing::debug!("token-exchange skipped during refresh: {e:#}");
            // Preserve any previously-stored key rather than wiping
            // it -- a transient failure shouldn't drop an apikey-mode
            // user's working credentials.
            auth.openai_api_key.clone()
        }
    };
    let account_id = extract_chatgpt_account_id(&refreshed.access_token)?;

    *auth = AuthDotJson {
        auth_mode: Some("chatgpt".to_string()),
        openai_api_key: api_key,
        tokens: Some(TokenData {
            id_token,
            access_token: refreshed.access_token,
            refresh_token,
            account_id,
        }),
        last_refresh: Some(Utc::now()),
    };
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

/// RFC 8693 token exchange: convert an OpenID id_token into a regular
/// `OPENAI_API_KEY`. This is the same call Codex CLI makes after OAuth
/// completes; the response's `access_token` is the `sk-...` we want.
async fn token_exchange_id_token(
    http: &reqwest::Client,
    token_url: &str,
    client_id: &str,
    id_token: &str,
) -> Result<String> {
    #[derive(Deserialize)]
    struct Resp {
        access_token: String,
    }
    let resp = http
        .post(token_url)
        .form(&[
            (
                "grant_type",
                "urn:ietf:params:oauth:grant-type:token-exchange",
            ),
            ("client_id", client_id),
            ("requested_token", "openai-api-key"),
            ("subject_token", id_token),
            (
                "subject_token_type",
                "urn:ietf:params:oauth:token-type:id_token",
            ),
        ])
        .send()
        .await
        .context("token-exchange POST failed")?;
    let status = resp.status();
    if !status.is_success() {
        let body = resp.text().await.unwrap_or_default();
        bail!("token-exchange failed (HTTP {status}): {body}");
    }
    let parsed: Resp = resp
        .json()
        .await
        .context("parsing token-exchange response")?;
    Ok(parsed.access_token)
}

/// Pull the `chatgpt_account_id` claim out of an access_token JWT
/// without verifying the signature. We trust the token because we just
/// received it over HTTPS from auth.openai.com; the only thing we need
/// is an opaque account identifier.
pub fn extract_chatgpt_account_id(access_token: &str) -> Result<String> {
    let payload = access_token
        .split('.')
        .nth(1)
        .ok_or_else(|| anyhow!("access_token is not a JWT"))?;
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(payload.as_bytes())
        .or_else(|_| base64::engine::general_purpose::URL_SAFE.decode(payload.as_bytes()))
        .context("base64-decoding JWT payload")?;
    let claims: serde_json::Value =
        serde_json::from_slice(&bytes).context("parsing JWT payload as JSON")?;
    claims
        .get("https://api.openai.com/auth")
        .and_then(|v| v.get("chatgpt_account_id"))
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
        .ok_or_else(|| anyhow!("JWT missing https://api.openai.com/auth.chatgpt_account_id claim"))
}

/// Spin up `tiny_http` on `port`, wait for `GET /auth/callback?code=...&state=...`,
/// validate `state`, return the `code`. Times out after `timeout`.
fn loopback_capture_code(port: u16, expected_state: String, timeout: Duration) -> Result<String> {
    let server = tiny_http::Server::http(("127.0.0.1", port))
        .map_err(|e| anyhow!("binding loopback port {port}: {e}"))?;
    let (tx, rx) = mpsc::channel::<Result<String>>();

    std::thread::spawn(move || {
        // One callback is all we expect; pull the first request only and
        // drop the server when the closure returns.
        if let Some(req) = server.incoming_requests().next() {
            let url = req.url().to_string();
            let result = parse_callback_url(&url, &expected_state);
            let body = match &result {
                Ok(_) => "Sign-in complete. You can close this tab and return to brokk.",
                Err(e) => {
                    tracing::warn!("codex-login callback rejected: {e:#}");
                    "Sign-in failed. Check the brokk console for details."
                }
            };
            let _ = req.respond(tiny_http::Response::from_string(body));
            let _ = tx.send(result);
        }
    });

    rx.recv_timeout(timeout)
        .map_err(|_| anyhow!("OAuth callback timed out after {timeout:?}"))?
}

/// Parse `/auth/callback?code=...&state=...` (or `?error=...`) by hand
/// to avoid pulling in the `url` crate just for query-string splitting.
fn parse_callback_url(url: &str, expected_state: &str) -> Result<String> {
    let query = url.split('?').nth(1).unwrap_or("");
    let mut code: Option<String> = None;
    let mut state: Option<String> = None;
    let mut error: Option<String> = None;
    for pair in query.split('&') {
        if pair.is_empty() {
            continue;
        }
        let (raw_k, raw_v) = pair.split_once('=').unwrap_or((pair, ""));
        let v = url_decode(raw_v);
        match raw_k {
            "code" => code = Some(v),
            "state" => state = Some(v),
            "error" => error = Some(v),
            _ => {}
        }
    }
    if let Some(err) = error {
        bail!("authorization server returned error: {err}");
    }
    let state = state.ok_or_else(|| anyhow!("callback missing state param"))?;
    if state != expected_state {
        bail!("callback state did not match (CSRF guard)");
    }
    code.ok_or_else(|| anyhow!("callback missing code param"))
}

/// Inverse of `urlencode`: decode `%XX` escapes and `+` to space.
fn url_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out: Vec<u8> = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            b'%' if i + 2 < bytes.len() => {
                let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).unwrap_or("");
                if let Ok(byte) = u8::from_str_radix(hex, 16) {
                    out.push(byte);
                    i += 3;
                } else {
                    out.push(bytes[i]);
                    i += 1;
                }
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
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

    #[test]
    fn extract_chatgpt_account_id_pulls_nested_claim() {
        // Synthesize a JWT with the OpenAI namespace claim. We don't need a
        // valid signature -- the production code skips verification too.
        let payload = serde_json::json!({
            "sub": "user_xyz",
            "https://api.openai.com/auth": {
                "chatgpt_account_id": "acct_test_123",
                "chatgpt_plan_type": "plus"
            }
        });
        let payload_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .encode(serde_json::to_vec(&payload).unwrap());
        let token = format!("eyJhbGciOiJSUzI1NiJ9.{payload_b64}.fake-sig");
        assert_eq!(extract_chatgpt_account_id(&token).unwrap(), "acct_test_123");
    }

    #[test]
    fn extract_chatgpt_account_id_errors_on_missing_claim() {
        let payload = serde_json::json!({"sub": "user_xyz"});
        let payload_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .encode(serde_json::to_vec(&payload).unwrap());
        let token = format!("eyJhbGciOiJSUzI1NiJ9.{payload_b64}.fake-sig");
        assert!(extract_chatgpt_account_id(&token).is_err());
    }

    #[test]
    fn parse_callback_url_validates_state_and_returns_code() {
        let url = "/auth/callback?code=abc123&state=xyz";
        assert_eq!(parse_callback_url(url, "xyz").unwrap(), "abc123");
    }

    #[test]
    fn parse_callback_url_rejects_state_mismatch() {
        let url = "/auth/callback?code=abc123&state=evil";
        let err = parse_callback_url(url, "expected").unwrap_err().to_string();
        assert!(err.contains("CSRF"));
    }

    #[test]
    fn parse_callback_url_surfaces_authorization_server_error() {
        let url = "/auth/callback?error=access_denied&state=xyz";
        let err = parse_callback_url(url, "xyz").unwrap_err().to_string();
        assert!(err.contains("access_denied"));
    }

    #[test]
    fn authorize_url_carries_codex_simplified_flow_flags() {
        // These three params + the connectors scopes are exactly what
        // flips OpenAI's consent screen from "API organization access"
        // to "Sign in with ChatGPT". Regression-guard them: dropping
        // any one silently routes the user back to the API-key flow.
        let (challenge, _verifier) = oauth2::PkceCodeChallenge::new_random_sha256();
        let url = build_authorize_url(&challenge, "test-state");
        assert!(
            url.contains("codex_cli_simplified_flow=true"),
            "missing codex_cli_simplified_flow flag in {url}"
        );
        assert!(
            url.contains("id_token_add_organizations=true"),
            "missing id_token_add_organizations flag in {url}"
        );
        assert!(
            url.contains("originator=codex_cli_rs"),
            "missing originator=codex_cli_rs in {url}"
        );
        assert!(
            url.contains("api.connectors.read"),
            "missing api.connectors.read scope in {url}"
        );
        assert!(
            url.contains("api.connectors.invoke"),
            "missing api.connectors.invoke scope in {url}"
        );
        // Sanity: still PKCE + CSRF.
        assert!(url.contains("code_challenge="));
        assert!(url.contains("code_challenge_method=S256"));
        assert!(url.contains("state=test-state"));
    }
}
