use anyhow::{Context, Result};
use futures::StreamExt;
use futures::future::BoxFuture;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::time::Duration;
use tokio_util::sync::CancellationToken;

// ---------------------------------------------------------------------------
// Tool calling types (OpenAI-compatible)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolDefinition {
    pub r#type: String,
    pub function: FunctionDef,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FunctionDef {
    pub name: String,
    pub description: String,
    pub parameters: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolCall {
    pub id: String,
    pub r#type: String,
    pub function: FunctionCall,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FunctionCall {
    pub name: String,
    pub arguments: String,
}

/// What the LLM returned: either final text or tool calls to execute.
#[derive(Debug)]
pub enum LlmResponse {
    Text(String),
    ToolCalls { text: String, calls: Vec<ToolCall> },
}

// ---------------------------------------------------------------------------
// Chat message (extended for tool calling)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatMessage {
    pub role: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub content: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_calls: Option<Vec<ToolCall>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_call_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
}

impl ChatMessage {
    pub fn system(content: impl Into<String>) -> Self {
        Self {
            role: "system".to_string(),
            content: Some(content.into()),
            tool_calls: None,
            tool_call_id: None,
            name: None,
        }
    }

    pub fn user(content: impl Into<String>) -> Self {
        Self {
            role: "user".to_string(),
            content: Some(content.into()),
            tool_calls: None,
            tool_call_id: None,
            name: None,
        }
    }

    pub fn assistant(content: impl Into<String>) -> Self {
        Self {
            role: "assistant".to_string(),
            content: Some(content.into()),
            tool_calls: None,
            tool_call_id: None,
            name: None,
        }
    }

    pub fn assistant_tool_calls(calls: Vec<ToolCall>) -> Self {
        Self {
            role: "assistant".to_string(),
            content: None,
            tool_calls: Some(calls),
            tool_call_id: None,
            name: None,
        }
    }

    pub fn tool_result(
        tool_call_id: impl Into<String>,
        name: impl Into<String>,
        content: impl Into<String>,
    ) -> Self {
        Self {
            role: "tool".to_string(),
            content: Some(content.into()),
            tool_calls: None,
            tool_call_id: Some(tool_call_id.into()),
            name: Some(name.into()),
        }
    }
}

// ---------------------------------------------------------------------------
// LLM backend trait
// ---------------------------------------------------------------------------

pub trait LlmBackend: Send + Sync {
    fn list_models(&self) -> BoxFuture<'_, Result<Vec<String>>>;

    fn stream_chat(
        &self,
        model: &str,
        messages: Vec<ChatMessage>,
        tools: Option<Vec<ToolDefinition>>,
        on_token: Box<dyn FnMut(&str) + Send>,
        cancel: CancellationToken,
    ) -> BoxFuture<'_, Result<LlmResponse>>;
}

// ---------------------------------------------------------------------------
// Request/response types for the OpenAI-compatible API
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize)]
struct ChatCompletionRequest {
    model: String,
    messages: Vec<ChatMessage>,
    stream: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    temperature: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    max_tokens: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    tools: Option<Vec<ToolDefinition>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    tool_choice: Option<String>,
}

#[derive(Debug, Deserialize)]
struct ModelEntry {
    id: String,
}

#[derive(Debug, Deserialize)]
struct ModelsResponse {
    data: Vec<ModelEntry>,
}

// SSE chunk types for streaming (extended for tool calls)

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
    #[serde(default)]
    tool_calls: Option<Vec<ChunkToolCall>>,
}

#[derive(Debug, Deserialize)]
struct ChunkToolCall {
    index: usize,
    #[serde(default)]
    id: Option<String>,
    #[serde(default)]
    function: Option<ChunkFunctionCall>,
}

#[derive(Debug, Deserialize)]
struct ChunkFunctionCall {
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    arguments: Option<String>,
}

/// Accumulator for tool calls arriving as SSE fragments.
#[derive(Default)]
struct ToolCallAccumulator {
    calls: HashMap<usize, (String, String, String)>, // index -> (id, name, arguments)
}

impl ToolCallAccumulator {
    fn push(&mut self, chunk: &ChunkToolCall) {
        let entry = self
            .calls
            .entry(chunk.index)
            .or_insert_with(|| (String::new(), String::new(), String::new()));
        if let Some(id) = &chunk.id {
            entry.0 = id.clone();
        }
        if let Some(func) = &chunk.function {
            if let Some(name) = &func.name {
                entry.1 = name.clone();
            }
            if let Some(args) = &func.arguments {
                entry.2.push_str(args);
            }
        }
    }

    fn is_empty(&self) -> bool {
        self.calls.is_empty()
    }

    fn into_tool_calls(self) -> Vec<ToolCall> {
        let mut entries: Vec<_> = self.calls.into_iter().collect();
        entries.sort_by_key(|(idx, _)| *idx);
        entries
            .into_iter()
            .map(|(_, (id, name, arguments))| ToolCall {
                id,
                r#type: "function".to_string(),
                function: FunctionCall { name, arguments },
            })
            .collect()
    }
}

// ---------------------------------------------------------------------------
// OpenAI-compatible client
// ---------------------------------------------------------------------------

#[derive(Clone)]
pub struct OpenAiClient {
    base_url: String,
    api_key: Option<String>,
    http: reqwest::Client,
}

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
        let base_url = base_url.trim_end_matches('/').to_string();
        Self {
            base_url,
            api_key,
            http,
        }
    }

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
        tools: Option<Vec<ToolDefinition>>,
        on_token: Box<dyn FnMut(&str) + Send>,
        cancel: CancellationToken,
    ) -> BoxFuture<'_, Result<LlmResponse>> {
        let model = model.to_string();
        Box::pin(self.stream_chat_impl(model, messages, tools, on_token, cancel))
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

        let models: ModelsResponse = resp
            .json()
            .await
            .context("failed to parse models response")?;
        Ok(models.data.into_iter().map(|m| m.id).collect())
    }

    async fn stream_chat_impl(
        &self,
        model: String,
        messages: Vec<ChatMessage>,
        tools: Option<Vec<ToolDefinition>>,
        mut on_token: Box<dyn FnMut(&str) + Send>,
        cancel: CancellationToken,
    ) -> Result<LlmResponse> {
        let url = self.api_url("/chat/completions");

        let tool_choice = tools.as_ref().map(|_| "auto".to_string());

        let body = ChatCompletionRequest {
            model,
            messages,
            stream: true,
            temperature: None,
            max_tokens: None,
            tools,
            tool_choice,
        };

        let mut req = self.http.post(&url).json(&body);
        if let Some(key) = &self.api_key {
            req = req.bearer_auth(key);
        }

        let resp = req.send().await.context("failed to send chat request")?;
        let status = resp.status();
        if !status.is_success() {
            let body_text = resp.text().await.unwrap_or_default();
            anyhow::bail!("chat completion failed (HTTP {status}): {body_text}");
        }

        let mut full_text = String::new();
        let mut tool_acc = ToolCallAccumulator::default();
        let mut stream = resp.bytes_stream();
        let mut raw_buf: Vec<u8> = Vec::new();

        loop {
            tokio::select! {
                _ = cancel.cancelled() => {
                    tracing::info!("streaming cancelled by client");
                    break;
                }
                chunk = stream.next() => {
                    let Some(chunk) = chunk else { break; };
                    let chunk = chunk.context("stream read error")?;
                    raw_buf.extend_from_slice(&chunk);

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
                            if tool_acc.is_empty() {
                                return Ok(LlmResponse::Text(full_text));
                            }
                            return Ok(LlmResponse::ToolCalls {
                                text: full_text,
                                calls: tool_acc.into_tool_calls(),
                            });
                        }

                        match serde_json::from_str::<ChatCompletionChunk>(data) {
                            Ok(chunk) => {
                                for choice in &chunk.choices {
                                    // Accumulate text content
                                    if let Some(content) = &choice.delta.content {
                                        on_token(content);
                                        full_text.push_str(content);
                                    }
                                    // Accumulate tool call fragments
                                    if let Some(tc_chunks) = &choice.delta.tool_calls {
                                        for tc in tc_chunks {
                                            tool_acc.push(tc);
                                        }
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

        // If we exited the loop via cancellation, tool call fragments may be incomplete
        // (arguments JSON truncated mid-stream). Return only the text we've already
        // streamed to the caller to avoid dispatching malformed tool calls.
        if cancel.is_cancelled() {
            return Ok(LlmResponse::Text(full_text));
        }

        if tool_acc.is_empty() {
            Ok(LlmResponse::Text(full_text))
        } else {
            Ok(LlmResponse::ToolCalls {
                text: full_text,
                calls: tool_acc.into_tool_calls(),
            })
        }
    }
}
