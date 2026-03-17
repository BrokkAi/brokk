package reviewruntime

import (
	"context"
	"errors"
	"strings"

	"github.com/brokk/brokk/go-runtime/internal/review"
)

type Request struct {
	Owner          string
	Repo           string
	PRNumber       string
	Model          string
	Prompt         string
	AnnotatedDiff  string
	ContextSummary string
	MinSeverity    review.Severity
	MaxComments    int
}

type Result struct {
	Provider    string
	RawResponse string
}

type Generator interface {
	Generate(context.Context, Request) (Result, error)
}

type HeuristicGenerator struct{}

func NewHeuristicGenerator() *HeuristicGenerator {
	return &HeuristicGenerator{}
}

func (g *HeuristicGenerator) Generate(_ context.Context, req Request) (Result, error) {
	response := review.GenerateHeuristicResponse(
		req.Owner,
		req.Repo,
		req.PRNumber,
		req.AnnotatedDiff,
		req.MinSeverity,
		req.MaxComments,
	)
	rawResponse, err := review.RenderResponse(response)
	if err != nil {
		return Result{}, err
	}
	return Result{
		Provider:    "heuristic",
		RawResponse: rawResponse,
	}, nil
}

type FallbackGenerator struct {
	primary  Generator
	fallback Generator
}

func NewFallbackGenerator(primary Generator, fallback Generator) *FallbackGenerator {
	return &FallbackGenerator{
		primary:  primary,
		fallback: fallback,
	}
}

func (g *FallbackGenerator) Generate(ctx context.Context, req Request) (Result, error) {
	if g.primary == nil {
		return Result{}, errors.New("primary generator is required")
	}
	if g.fallback == nil {
		return Result{}, errors.New("fallback generator is required")
	}

	primaryResult, err := g.primary.Generate(ctx, req)
	if err == nil {
		return primaryResult, nil
	}

	fallbackResult, fallbackErr := g.fallback.Generate(ctx, req)
	if fallbackErr != nil {
		return Result{}, fallbackErr
	}
	reason := sanitizeReason(err.Error())
	if reason != "" {
		fallbackResult.Provider = fallbackResult.Provider + " (fallback after " + reason + ")"
	}
	return fallbackResult, nil
}

func sanitizeReason(reason string) string {
	reason = strings.Join(strings.Fields(strings.TrimSpace(reason)), " ")
	if len(reason) > 120 {
		reason = reason[:120]
	}
	return reason
}
