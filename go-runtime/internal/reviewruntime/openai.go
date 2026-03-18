package reviewruntime

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/brokk/brokk/go-runtime/internal/review"
)

type OpenAIConfig struct {
	APIKey       string
	BaseURL      string
	Vendor       string
	Organization string
	Project      string
	HTTPClient   *http.Client
}

type OpenAIGenerator struct {
	apiKey       string
	baseURL      string
	vendor       string
	organization string
	project      string
	client       *http.Client
}

func NewOpenAIGenerator(cfg OpenAIConfig) (*OpenAIGenerator, error) {
	apiKey := strings.TrimSpace(cfg.APIKey)
	if apiKey == "" {
		return nil, errors.New("API key is required")
	}
	baseURL := normalizeBaseURL(cfg.BaseURL)
	if baseURL == "" {
		return nil, errors.New("base URL is required")
	}
	client := cfg.HTTPClient
	if client == nil {
		client = &http.Client{Timeout: 60 * time.Second}
	}
	return &OpenAIGenerator{
		apiKey:       apiKey,
		baseURL:      baseURL,
		vendor:       strings.TrimSpace(cfg.Vendor),
		organization: strings.TrimSpace(cfg.Organization),
		project:      strings.TrimSpace(cfg.Project),
		client:       client,
	}, nil
}

func (g *OpenAIGenerator) Generate(ctx context.Context, req Request) (Result, error) {
	model := strings.TrimSpace(req.Model)
	if model == "" {
		return Result{}, errors.New("review model is required")
	}

	prompt := req.Prompt
	repairs := 0
	var rawResponse string
	for attempt := 1; attempt <= 3; attempt++ {
		content, err := g.sendPrompt(ctx, model, prompt)
		if err != nil {
			return Result{}, err
		}
		rawResponse = content
		if review.ParseResponse(rawResponse) != nil {
			provider := "openai-compatible"
			if repairs > 0 {
				provider = fmt.Sprintf("openai-compatible (repaired %dx)", repairs)
			}
			return Result{
				Provider:    provider,
				RawResponse: rawResponse,
				StopReason:  "SUCCESS",
				RepairCount: repairs,
			}, nil
		}
		if attempt == 3 {
			break
		}
		repairs++
		prompt = buildRepairPrompt(req.Prompt, rawResponse)
	}

	return Result{}, errors.New("provider returned malformed review JSON after repair attempts")
}

func (g *OpenAIGenerator) sendPrompt(ctx context.Context, model string, prompt string) (string, error) {
	payload := map[string]any{
		"model": model,
		"messages": []map[string]string{
			{
				"role":    "user",
				"content": prompt,
			},
		},
		"temperature": 0,
	}

	body, err := json.Marshal(payload)
	if err != nil {
		return "", err
	}

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, g.baseURL+"/chat/completions", bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	httpReq.Header.Set("Authorization", "Bearer "+g.apiKey)
	httpReq.Header.Set("Content-Type", "application/json")
	if g.vendor != "" {
		httpReq.Header.Set("X-Brokk-Vendor", g.vendor)
	}
	if g.organization != "" {
		httpReq.Header.Set("OpenAI-Organization", g.organization)
	}
	if g.project != "" {
		httpReq.Header.Set("OpenAI-Project", g.project)
	}

	resp, err := g.client.Do(httpReq)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	responseBody, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return "", fmt.Errorf("provider returned %d: %s", resp.StatusCode, strings.TrimSpace(string(responseBody)))
	}

	content, err := extractChatCompletionText(responseBody)
	if err != nil {
		return "", err
	}
	return content, nil
}

func normalizeBaseURL(baseURL string) string {
	baseURL = strings.TrimSpace(baseURL)
	baseURL = strings.TrimRight(baseURL, "/")
	if strings.HasSuffix(baseURL, "/v1") {
		return baseURL
	}
	if baseURL == "" {
		return ""
	}
	return baseURL + "/v1"
}

func ProxyBaseURL(proxySetting string) string {
	switch strings.ToUpper(strings.TrimSpace(proxySetting)) {
	case "", "BROKK":
		return "https://proxy.brokk.ai"
	case "STAGING":
		return "https://staging.brokk.ai"
	case "LOCALHOST":
		return "http://localhost:4000"
	default:
		return "https://proxy.brokk.ai"
	}
}

func extractChatCompletionText(body []byte) (string, error) {
	var payload map[string]any
	if err := json.Unmarshal(body, &payload); err != nil {
		return "", err
	}

	choices, ok := payload["choices"].([]any)
	if !ok || len(choices) == 0 {
		return "", errors.New("provider response missing choices")
	}
	choice, ok := choices[0].(map[string]any)
	if !ok {
		return "", errors.New("provider response choice malformed")
	}
	message, ok := choice["message"].(map[string]any)
	if !ok {
		return "", errors.New("provider response missing message")
	}

	switch content := message["content"].(type) {
	case string:
		return strings.TrimSpace(content), nil
	case []any:
		parts := make([]string, 0, len(content))
		for _, rawPart := range content {
			part, ok := rawPart.(map[string]any)
			if !ok {
				continue
			}
			text, ok := part["text"].(string)
			if ok && strings.TrimSpace(text) != "" {
				parts = append(parts, text)
			}
		}
		if len(parts) == 0 {
			return "", errors.New("provider response content had no text parts")
		}
		return strings.TrimSpace(strings.Join(parts, "\n")), nil
	default:
		return "", errors.New("provider response content type unsupported")
	}
}

func buildRepairPrompt(originalPrompt string, malformedResponse string) string {
	return strings.TrimSpace(`The previous response did not satisfy the required JSON contract for PR review output.

Return ONLY a single valid JSON object with this exact shape:
{
  "summaryMarkdown": "## Brokk PR Review\n\n...",
  "comments": [
    {
      "path": "relative/path.ext",
      "line": 42,
      "severity": "HIGH",
      "bodyMarkdown": "..."
    }
  ]
}

Do not include markdown fences, explanations, or any text before or after the JSON object.
Preserve the substantive review content from the malformed response where possible.

Original review prompt:
` + "\n" + originalPrompt + "\n\nMalformed response to repair:\n" + malformedResponse)
}
