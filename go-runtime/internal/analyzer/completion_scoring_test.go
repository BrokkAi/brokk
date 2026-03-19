package analyzer

import (
	"math"
	"testing"
)

func TestFuzzyMatcherExactMatchOutranksPrefix(t *testing.T) {
	t.Parallel()

	matcher := newFuzzyMatcher("fuzzymatcher")
	if better, worse := matcher.score("FuzzyMatcher"), matcher.score("FuzzyMatcherUtil"); !(better < worse) {
		t.Fatalf("exact score = %d, prefix score = %d, want exact < prefix", better, worse)
	}
}

func TestFuzzyMatcherPrefixOutranksSubstring(t *testing.T) {
	t.Parallel()

	matcher := newFuzzyMatcher("ipan")
	if better, worse := matcher.score("InstructionsPanel"), matcher.score("GitPanel"); !(better < worse) {
		t.Fatalf("prefix score = %d, substring score = %d, want prefix < substring", better, worse)
	}
}

func TestFuzzyMatcherSeparatorBoundaryOutranksMidWord(t *testing.T) {
	t.Parallel()

	matcher := newFuzzyMatcher("bar")
	if better, worse := matcher.score("foo_bar"), matcher.score("foobar"); !(better < worse) {
		t.Fatalf("separator score = %d, mid-word score = %d, want separator < mid-word", better, worse)
	}
}

func TestFuzzyMatcherCamelHumpAbbreviationMatches(t *testing.T) {
	t.Parallel()

	matcher := newFuzzyMatcher("cman")
	if !matcher.matches("ContextManager") {
		t.Fatal("cman should match ContextManager")
	}
}

func TestFuzzyMatcherTighterCamelHumpScoresBetter(t *testing.T) {
	t.Parallel()

	matcher := newFuzzyMatcher("cm")
	if better, worse := matcher.score("ConsumeMessages"), matcher.score("CamelCaseMatcher"); !(better < worse) {
		t.Fatalf("tight camel score = %d, loose camel score = %d, want tight < loose", better, worse)
	}
}

func TestFuzzyMatcherEmptyPattern(t *testing.T) {
	t.Parallel()

	matcher := newFuzzyMatcher("")
	if score := matcher.score(""); score != 0 {
		t.Fatalf("empty pattern score = %d, want 0 for empty text", score)
	}
	if score := matcher.score("abc"); score != math.MaxInt {
		t.Fatalf("empty pattern score = %d, want MaxInt for non-empty text", score)
	}
}

func TestFuzzyMatcherLowercaseMatchesFilenameStem(t *testing.T) {
	t.Parallel()

	matcher := newFuzzyMatcher("chrome")
	if score := matcher.score("chrome.ts"); score == math.MaxInt {
		t.Fatal("chrome.ts score = MaxInt, want match")
	}
	if score := matcher.score("Chrome.java"); score == math.MaxInt {
		t.Fatal("Chrome.java score = MaxInt, want match")
	}
}

func TestFuzzyMatcherSimpleLowercaseDoesNotCrossSeparator(t *testing.T) {
	t.Parallel()

	matcher := newFuzzyMatcher("do")
	if score := matcher.score("Do.Re"); score != math.MaxInt {
		t.Fatalf("score = %d, want MaxInt for Do.Re", score)
	}
}
