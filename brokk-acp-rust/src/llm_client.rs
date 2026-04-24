use anyhow::{Context, Result};
use futures::future::BoxFuture;
use futures::StreamExt;
use serde::{Deserialize, Serialize};
use std::time::Duration;
use tokio_util::sync::CancellationToken;

/// Trait abstracting an LLM backend. Allows swapping OpenAiClient for
/// a Bifrost-backed implementation or a mock for testing.
pub trait LlmBackend: Send + Sync {
    fn list_models(&self) -> BoxFuture<'_, Result<Vec<String>>>;

    fn stream_chat(
        &self,
        model: &str,
        messages: Vec<ChatMessage>,
        on_token: Box<dyn FnMut(&str) + Send>,
        cancel: CancellationToken,
    ) -> BoxFuture<'_, Result<String>>;
}

/// OpenAI-compatible chat message.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatMessage {
    pub role: String,
    pub content: String,
}

/// Request body for POST /v1/chat/completions.
#[derive(Debug, Serialize)]
struct ChatCompletionRequest {
    model: String,
    messages: Vec<ChatMessage>,
    stream: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    temperature: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    max_tokens: Option<u32>,
}

/// A single model entry from GET /v1/models.
#[derive(Debug, Deserialize)]
struct ModelEntry {
    id: String,
}

/// Response from GET /v1/models.
#[derive(Debug, Deserialize)]
struct ModelsResponse {
    data: Vec<ModelEntry>,
}

/// A single SSE chunk from streaming chat completions.
#[derive(Debug, Deserialize)]
struct ChatCompletionChunk {
    choices: Vec<ChunkChoice>,
}

#[derive(Debug, Deserialize)]
struct ChunkChoice {
    delta: ChunkDelta,
}

#[derive(Debug, Deserialize)]
struct ChunkDelta {
    #[serde(default)]
    content: Option<String>,
}

/// Client for OpenAI-compatible endpoints (Ollama, LM Studio, etc.).
#[derive(Clone)]
pub struct OpenAiClient {
    base_url: String,
    api_key: Option<String>,
    http: reqwest::Client,
}

// Manual Debug to avoid leaking the API key in logs.
impl std::fmt::Debug for OpenAiClient {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("OpenAiClient")
            .field("base_url", &self.base_url)
            .field("api_key", &self.api_key.as_ref().map(|_| "[REDACTED]"))
            .finish()
    }
}

impl OpenAiClient {
    pub fn new(base_url: String, api_key: Option<String>) -> Self {
        let http = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(600))
            .build()
            .expect("failed to build HTTP client");
        // Normalize: strip trailing slash
        let base_url = base_url.trim_end_matches('/').to_string();
        Self {
            base_url,
            api_key,
            http,
        }
    }

    /// Build the full API URL for a given path (handles /v1 normalization).
    fn api_url(&self, path: &str) -> String {
        if self.base_url.ends_with("/v1") {
            format!("{}{}", self.base_url, path)
        } else {
            format!("{}/v1{}", self.base_url, path)
        }
    }
}

impl LlmBackend for OpenAiClient {
    fn list_models(&self) -> BoxFuture<'_, Result<Vec<String>>> {
        Box::pin(self.list_models_impl())
    }

    fn stream_chat(
        &self,
        model: &str,
        messages: Vec<ChatMessage>,
        on_token: Box<dyn FnMut(&str) + Send>,
        cancel: CancellationToken,
    ) -> BoxFuture<'_, Result<String>> {
        let model = model.to_string();
        Box::pin(self.stream_chat_impl(model, messages, on_token, cancel))
    }
}

impl OpenAiClient {
    async fn list_models_impl(&self) -> Result<Vec<String>> {
        let url = self.api_url("/models");

        let mut req = self.http.get(&url);
        if let Some(key) = &self.api_key {
            req = req.bearer_auth(key);
        }

        let resp = req.send().await.context("failed to fetch models")?;
        let status = resp.status();
        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            tracing::warn!("model discovery failed (HTTP {status}): {body}");
            anyhow::bail!("model discovery failed (HTTP {status})");
        }

        let models: ModelsResponse =
            resp.json().await.context("failed to parse models response")?;
        Ok(models.data.into_iter().map(|m| m.id).collect())
    }

    async fn stream_chat_impl(
        &self,
        model: String,
        messages: Vec<ChatMessage>,
        mut on_token: Box<dyn FnMut(&str) + Send>,
        cancel: CancellationToken,
    ) -> Result<String> {
        let url = self.api_url("/chat/completions");

        let body = ChatCompletionRequest {
            model,
            messages,
            stream: true,
            temperature: None,
            max_tokens: None,
        };

        let mut req = self.http.post(&url).json(&body);
        if let Some(key) = &self.api_key {
            req = req.bearer_auth(key);
        }

        let resp = req.send().await.context("failed to send chat request")?;
        let status = resp.status();
        if !status.is_success() {
            anyhow::bail!("chat completion failed (HTTP {})", status);
        }

        let mut full_response = String::new();
        let mut stream = resp.bytes_stream();
        // Buffer raw bytes to avoid corrupting multi-byte UTF-8 characters
        // that may be split across TCP chunks.
        let mut raw_buf: Vec<u8> = Vec::new();

        loop {
            tokio::select! {
                _ = cancel.cancelled() => {
                    tracing::info!("streaming cancelled by client");
                    return Ok(full_response);
                }
                chunk = stream.next() => {
                    let Some(chunk) = chunk else {
                        break;
                    };
                    let chunk = chunk.context("stream read error")?;
                    raw_buf.extend_from_slice(&chunk);

                    // Process complete lines (newline-delimited) from the byte buffer.
                    // Only convert to String once we have a full line.
                    while let Some(pos) = raw_buf.iter().position(|&b| b == b'\n') {
                        let line_bytes = raw_buf.drain(..=pos).collect::<Vec<_>>();
                        let line = String::from_utf8_lossy(&line_bytes).trim().to_string();

                        if line.is_empty() || line.starts_with(':') {
                            continue;
                        }

                        let data = if let Some(stripped) = line.strip_prefix("data: ") {
                            stripped.trim()
                        } else {
                            continue;
                        };

                        if data == "[DONE]" {
                            return Ok(full_response);
                        }

                        match serde_json::from_str::<ChatCompletionChunk>(data) {
                            Ok(chunk) => {
                                for choice in &chunk.choices {
                                    if let Some(content) = &choice.delta.content {
                                        on_token(content);
                                        full_response.push_str(content);
                                    }
                                }
                            }
                            Err(e) => {
                                tracing::debug!("skipping unparseable SSE chunk: {e}");
                            }
                        }
                    }
                }
            }
        }

        Ok(full_response)
    }
}
