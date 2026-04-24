use std::sync::Arc;

use anyhow::Result;
use clap::Parser;

mod agent;
mod llm_client;
mod session;

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

    /// Default model to use if not specified by the client
    #[arg(long, default_value = "")]
    default_model: String,
}

// Manual Debug to avoid leaking api_key in logs or process listings.
impl std::fmt::Debug for Args {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Args")
            .field("endpoint_url", &self.endpoint_url)
            .field("api_key", &self.api_key.as_ref().map(|_| "[REDACTED]"))
            .field("default_model", &self.default_model)
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
    tracing::info!(
        "brokk-acp starting, endpoint={}",
        args.endpoint_url
    );

    let llm: Arc<dyn llm_client::LlmBackend> = Arc::new(llm_client::OpenAiClient::new(
        args.endpoint_url,
        args.api_key,
    ));
    // SessionStore uses internal Arc -- no outer Arc needed
    let sessions = session::SessionStore::new(args.default_model);

    agent::run_agent(llm, sessions).await.map_err(|e| {
        tracing::error!("agent error: {e}");
        anyhow::anyhow!("agent error: {e}")
    })
}
