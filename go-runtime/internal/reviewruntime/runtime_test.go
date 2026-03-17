package reviewruntime

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/brokk/brokk/go-runtime/internal/review"
)

func TestHeuristicGeneratorProducesReviewJSON(t *testing.T) {
	generator := NewHeuristicGenerator()
	annotatedDiff := strings.Join([]string{
		"diff --git a/src/Main.go b/src/Main.go",
		"--- a/src/Main.go",
		"+++ b/src/Main.go",
		"@@ -3,1 +3,2 @@",
		"[OLD:- NEW:4] +fmt.Println(\"debug\")",
	}, "\n")

	result, err := generator.Generate(context.Background(), Request{
		Owner:         "brokk",
		Repo:          "brokk",
		PRNumber:      "42",
		AnnotatedDiff: annotatedDiff,
		MinSeverity:   review.High,
		MaxComments:   3,
	})
	if err != nil {
		t.Fatalf("Generate() error = %v", err)
	}
	if result.Provider != "heuristic" {
		t.Fatalf("Provider = %q, want heuristic", result.Provider)
	}
	parsed := review.ParseResponse(result.RawResponse)
	if parsed == nil {
		t.Fatal("ParseResponse() = nil, want parsed review response")
	}
	if !strings.Contains(parsed.SummaryMarkdown, "## Brokk PR Review") {
		t.Fatalf("SummaryMarkdown = %q, want review heading", parsed.SummaryMarkdown)
	}
}

type stubGenerator struct {
	result Result
	err    error
}

func (g stubGenerator) Generate(context.Context, Request) (Result, error) {
	return g.result, g.err
}

func TestFallbackGeneratorReturnsPrimaryWhenSuccessful(t *testing.T) {
	generator := NewFallbackGenerator(
		stubGenerator{result: Result{Provider: "primary", RawResponse: "{}"}},
		stubGenerator{result: Result{Provider: "fallback", RawResponse: "{}"}},
	)
	result, err := generator.Generate(context.Background(), Request{})
	if err != nil {
		t.Fatalf("Generate() error = %v", err)
	}
	if result.Provider != "primary" {
		t.Fatalf("Provider = %q, want primary", result.Provider)
	}
}

func TestFallbackGeneratorReturnsFallbackWhenPrimaryFails(t *testing.T) {
	generator := NewFallbackGenerator(
		stubGenerator{err: errors.New("provider unavailable")},
		stubGenerator{result: Result{Provider: "heuristic", RawResponse: "{}"}},
	)
	result, err := generator.Generate(context.Background(), Request{})
	if err != nil {
		t.Fatalf("Generate() error = %v", err)
	}
	if !strings.Contains(result.Provider, "heuristic") || !strings.Contains(result.Provider, "fallback after provider unavailable") {
		t.Fatalf("Provider = %q, want fallback provider details", result.Provider)
	}
}
