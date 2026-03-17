package reviewruntime

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/brokk/brokk/go-runtime/internal/review"
)

func TestOpenAIGeneratorPostsPromptAndParsesStringContent(t *testing.T) {
	var authHeader string
	var vendorHeader string
	var bodyText string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authHeader = r.Header.Get("Authorization")
		vendorHeader = r.Header.Get("X-Brokk-Vendor")
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Fatalf("io.ReadAll() error = %v", err)
		}
		bodyText = string(body)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"{\"summaryMarkdown\":\"## Brokk PR Review\\n\\nLooks good.\",\"comments\":[]}"}}]}`))
	}))
	defer server.Close()

	generator, err := NewOpenAIGenerator(OpenAIConfig{
		APIKey:     "test-key",
		BaseURL:    server.URL,
		Vendor:     "OpenAI",
		HTTPClient: server.Client(),
	})
	if err != nil {
		t.Fatalf("NewOpenAIGenerator() error = %v", err)
	}

	result, err := generator.Generate(context.Background(), Request{
		Model:       "gpt-5",
		Prompt:      "Review this diff",
		MinSeverity: review.High,
		MaxComments: 3,
	})
	if err != nil {
		t.Fatalf("Generate() error = %v", err)
	}
	if authHeader != "Bearer test-key" {
		t.Fatalf("Authorization = %q, want bearer token", authHeader)
	}
	if vendorHeader != "OpenAI" {
		t.Fatalf("X-Brokk-Vendor = %q, want OpenAI", vendorHeader)
	}
	if !strings.Contains(bodyText, `"model":"gpt-5"`) || !strings.Contains(bodyText, "Review this diff") {
		t.Fatalf("request body = %q, want model and prompt", bodyText)
	}
	if result.Provider != "openai-compatible" {
		t.Fatalf("Provider = %q, want openai-compatible", result.Provider)
	}
	parsed := review.ParseResponse(result.RawResponse)
	if parsed == nil || !strings.Contains(parsed.SummaryMarkdown, "## Brokk PR Review") {
		t.Fatalf("RawResponse = %q, want parseable review JSON", result.RawResponse)
	}
}

func TestOpenAIGeneratorParsesArrayContent(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":[{"type":"text","text":"{\"summaryMarkdown\":\"## Brokk PR Review\\n\\nLooks good.\",\"comments\":[]}"}]}}]}`))
	}))
	defer server.Close()

	generator, err := NewOpenAIGenerator(OpenAIConfig{
		APIKey:     "test-key",
		BaseURL:    server.URL,
		HTTPClient: server.Client(),
	})
	if err != nil {
		t.Fatalf("NewOpenAIGenerator() error = %v", err)
	}

	result, err := generator.Generate(context.Background(), Request{Model: "gpt-5", Prompt: "Review this diff"})
	if err != nil {
		t.Fatalf("Generate() error = %v", err)
	}
	if review.ParseResponse(result.RawResponse) == nil {
		t.Fatalf("RawResponse = %q, want parseable review JSON", result.RawResponse)
	}
}

func TestOpenAIGeneratorRepairsMalformedResponse(t *testing.T) {
	requestCount := 0
	var secondRequestBody string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requestCount++
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Fatalf("io.ReadAll() error = %v", err)
		}
		if requestCount == 2 {
			secondRequestBody = string(body)
		}
		w.Header().Set("Content-Type", "application/json")
		if requestCount == 1 {
			_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"not json at all"}}]}`))
			return
		}
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"{\"summaryMarkdown\":\"## Brokk PR Review\\n\\nRepaired.\",\"comments\":[]}"}}]}`))
	}))
	defer server.Close()

	generator, err := NewOpenAIGenerator(OpenAIConfig{
		APIKey:     "test-key",
		BaseURL:    server.URL,
		HTTPClient: server.Client(),
	})
	if err != nil {
		t.Fatalf("NewOpenAIGenerator() error = %v", err)
	}

	result, err := generator.Generate(context.Background(), Request{
		Model:  "gpt-5",
		Prompt: "Original review prompt",
	})
	if err != nil {
		t.Fatalf("Generate() error = %v", err)
	}
	if requestCount != 2 {
		t.Fatalf("requestCount = %d, want 2", requestCount)
	}
	if !strings.Contains(secondRequestBody, "Malformed response to repair") || !strings.Contains(secondRequestBody, "Original review prompt") {
		t.Fatalf("repair request body = %q, want repair prompt", secondRequestBody)
	}
	if result.Provider != "openai-compatible (repaired 1x)" {
		t.Fatalf("Provider = %q, want repaired provider", result.Provider)
	}
}

func TestFallbackGeneratorUsesFallbackOnProviderError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "boom", http.StatusBadGateway)
	}))
	defer server.Close()

	primary, err := NewOpenAIGenerator(OpenAIConfig{
		APIKey:     "test-key",
		BaseURL:    server.URL,
		HTTPClient: server.Client(),
	})
	if err != nil {
		t.Fatalf("NewOpenAIGenerator() error = %v", err)
	}

	generator := NewFallbackGenerator(primary, NewHeuristicGenerator())
	result, err := generator.Generate(context.Background(), Request{
		Owner:         "brokk",
		Repo:          "brokk",
		PRNumber:      "42",
		Model:         "gpt-5",
		Prompt:        "Review this diff",
		AnnotatedDiff: "diff --git a/a.go b/a.go\n--- a/a.go\n+++ b/a.go\n@@ -1,0 +1,1 @@\n[OLD:- NEW:1] +fmt.Println(\"debug\")\n",
		MinSeverity:   review.High,
		MaxComments:   3,
	})
	if err != nil {
		t.Fatalf("Generate() error = %v", err)
	}
	if !strings.Contains(result.Provider, "heuristic") || !strings.Contains(result.Provider, "fallback after provider returned 502") {
		t.Fatalf("Provider = %q, want fallback details", result.Provider)
	}
}

func TestProxyBaseURL(t *testing.T) {
	if got := ProxyBaseURL(""); got != "https://proxy.brokk.ai" {
		t.Fatalf("ProxyBaseURL(\"\") = %q", got)
	}
	if got := ProxyBaseURL("STAGING"); got != "https://staging.brokk.ai" {
		t.Fatalf("ProxyBaseURL(\"STAGING\") = %q", got)
	}
	if got := ProxyBaseURL("LOCALHOST"); got != "http://localhost:4000" {
		t.Fatalf("ProxyBaseURL(\"LOCALHOST\") = %q", got)
	}
}
