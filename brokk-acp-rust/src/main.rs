use std::path::PathBuf;
use std::sync::Arc;

use anyhow::Result;
use clap::Parser;

mod agent;
mod bifrost_client;
mod codex_auth;
mod codex_client;
mod llm_client;
mod session;
mod tool_loop;
mod tools;

/// Brokk ACP Server -- Rust-based Agent Client Protocol server
/// with Ollama/OpenAI-compatible LLM backend.
#[derive(Parser)]
#[command(name = "brokk-acp", version, about)]
struct Args {
    /// Base URL for the OpenAI-compatible endpoint
    #[arg(long, default_value = "http://localhost:11434/v1")]
    endpoint_url: String,

    /// API key for the endpoint (optional, most local LLMs don't need one).
    /// Prefer the BROKK_ENDPOINT_API_KEY env var over the CLI flag.
    #[arg(long, env = "BROKK_ENDPOINT_API_KEY")]
    api_key: Option<String>,

    /// Authenticate against OpenAI using "Sign in with ChatGPT" credentials
    /// stored at `~/.codex/auth.json` (cross-compatible with Codex CLI).
    ///
    /// When `auth.json` is in `chatgpt` mode (the default after
    /// `/codex-login`), prompts are routed to
    /// `https://chatgpt.com/backend-api/codex/responses` using the
    /// OAuth `access_token` + `ChatGPT-Account-ID` headers, so usage
    /// counts against your ChatGPT Plus/Pro/Enterprise subscription.
    ///
    /// When `auth.json` is in `apikey` mode, prompts fall back to
    /// `https://api.openai.com/v1` with the stored `OPENAI_API_KEY`,
    /// which is billed as standard API usage. Run `/codex-login` from
    /// an ACP session to authenticate if no credentials are present
    /// yet.
    #[arg(long, conflicts_with = "api_key")]
    use_codex: bool,

    /// Default model to use if not specified by the client
    #[arg(long, default_value = "")]
    default_model: String,

    /// Maximum number of tool-calling turns per prompt before the server forces a final text response.
    #[arg(long, default_value_t = 25)]
    max_turns: usize,

    /// Maximum number of sessions to keep resident in memory before the
    /// least-recently-used session is evicted (the on-disk zip is unaffected
    /// and can be reloaded). Set to `0` to disable the cap.
    #[arg(long, default_value_t = 50)]
    max_sessions: usize,

    /// Maximum number of conversation turns retained per session in memory
    /// (sliding window). Older turns are dropped from memory once the cap is
    /// exceeded; the persisted zip retains the full history. Set to `0` to
    /// disable the cap.
    #[arg(long, default_value_t = 50)]
    max_history_turns: usize,

    /// Path to the bifrost binary (or just "bifrost" to look it up on $PATH).
    /// When unset, code-intelligence tools (search_symbols, etc.) are disabled.
    #[arg(long, env = "BROKK_BIFROST_BINARY")]
    bifrost_binary: Option<PathBuf>,
}

// Manual Debug to avoid leaking api_key in logs or process listings.
impl std::fmt::Debug for Args {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Args")
            .field("endpoint_url", &self.endpoint_url)
            .field("api_key", &self.api_key.as_ref().map(|_| "[REDACTED]"))
            .field("use_codex", &self.use_codex)
            .field("default_model", &self.default_model)
            .field("max_turns", &self.max_turns)
            .field("max_sessions", &self.max_sessions)
            .field("max_history_turns", &self.max_history_turns)
            .field("bifrost_binary", &self.bifrost_binary)
            .finish()
    }
}

#[tokio::main]
async fn main() -> Result<()> {
    // Configure tracing to stderr only (stdout is reserved for JSON-RPC)
    tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    let args = Args::parse();

    // Codex login takes precedence over the explicit --endpoint-url/--api-key
    // flags. We branch on the stored `auth_mode`:
    //   * "chatgpt" -> route through CodexClient (Responses API on
    //     chatgpt.com, billed against the user's ChatGPT subscription).
    //   * anything else (typically "apikey") -> fall back to OpenAiClient
    //     against api.openai.com using the stored OPENAI_API_KEY, which
    //     is billed as standard API usage.
    // Refresh stale credentials proactively so the first prompt doesn't
    // burn a 401 round-trip. If no auth.json exists yet we still start --
    // the user can run `/codex-login` from a session to authenticate.
    let llm: Arc<dyn llm_client::LlmBackend> = if args.use_codex {
        match codex_auth::read_auth_dot_json()? {
            Some(mut auth) => {
                if let Err(e) = codex_auth::refresh_if_stale(&mut auth).await {
                    tracing::warn!("codex credential refresh failed: {e:#}");
                }
                if matches!(auth.auth_mode.as_deref(), Some("chatgpt")) && auth.tokens.is_some() {
                    tracing::info!(
                        "brokk-acp starting in ChatGPT subscription mode (Responses API on chatgpt.com)"
                    );
                    Arc::new(codex_client::CodexClient::new())
                } else {
                    // We land here when auth_mode != "chatgpt" OR
                    // tokens are missing. Falling back to the API key
                    // path is the only meaningful thing we can do --
                    // but if there's no key either, the first prompt
                    // will 401 and the user needs to re-run login.
                    let key = auth.openai_api_key.clone();
                    if key.is_none() {
                        tracing::warn!(
                            "~/.codex/auth.json is not usable: not in chatgpt mode AND no OPENAI_API_KEY; \
                             run /codex-login from a session to authenticate"
                        );
                    }
                    tracing::info!(
                        "brokk-acp starting in OPENAI_API_KEY mode (api.openai.com), auth_mode={:?}",
                        auth.auth_mode
                    );
                    Arc::new(llm_client::OpenAiClient::new(
                        "https://api.openai.com/v1".to_string(),
                        key,
                    ))
                }
            }
            None => {
                tracing::warn!(
                    "no ~/.codex/auth.json found; run /codex-login from a session to authenticate"
                );
                Arc::new(llm_client::OpenAiClient::new(
                    "https://api.openai.com/v1".to_string(),
                    None,
                ))
            }
        }
    } else {
        tracing::info!("brokk-acp starting, endpoint={}", args.endpoint_url);
        Arc::new(llm_client::OpenAiClient::new(
            args.endpoint_url.clone(),
            args.api_key.clone(),
        ))
    };
    // SessionStore uses internal Arc -- no outer Arc needed
    let limits = session::SessionLimits {
        max_sessions: args.max_sessions,
        max_history_turns: args.max_history_turns,
    };
    let sessions = session::SessionStore::with_limits(args.default_model, limits);

    let max_turns = args.max_turns.max(1);
    agent::run_agent(llm, sessions, max_turns, args.bifrost_binary)
        .await
        .map_err(|e| {
            tracing::error!("agent error: {e}");
            anyhow::anyhow!("agent error: {e}")
        })
}
