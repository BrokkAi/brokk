package analyzer

import (
	"encoding/json"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

type analyzerFixture struct {
	Name          string   `json:"name"`
	RelativePath  string   `json:"relativePath"`
	Content       string   `json:"content"`
	Classes       []string `json:"classes"`
	Methods       []string `json:"methods"`
	Fields        []string `json:"fields"`
	Imports       []string `json:"imports"`
	ContainsTests bool     `json:"containsTests"`
	RawSupertypes []string `json:"rawSupertypes"`
}

func TestAnalyzerFixtures(t *testing.T) {
	entries, err := os.ReadDir(filepath.Join("testdata"))
	if err != nil {
		t.Fatalf("os.ReadDir(testdata) error = %v", err)
	}

	for _, entry := range entries {
		if entry.IsDir() || filepath.Ext(entry.Name()) != ".json" {
			continue
		}

		entry := entry
		t.Run(strings.TrimSuffix(entry.Name(), ".json"), func(t *testing.T) {
			runAnalyzerFixture(t, filepath.Join("testdata", entry.Name()))
		})
	}
}

func runAnalyzerFixture(t *testing.T, fixturePath string) {
	t.Helper()

	raw, err := os.ReadFile(fixturePath)
	if err != nil {
		t.Fatalf("os.ReadFile(%q) error = %v", fixturePath, err)
	}

	var fixture analyzerFixture
	if err := json.Unmarshal(raw, &fixture); err != nil {
		t.Fatalf("json.Unmarshal(%q) error = %v", fixturePath, err)
	}

	analysis, ok := parseTreeSitterFile(fixture.RelativePath, fixture.Content)
	if !ok {
		t.Fatalf("parseTreeSitterFile(%q) = false, want true", fixture.RelativePath)
	}

	if got := sortedFQNames(filterByKind(analysis.symbols, "class")); !slices.Equal(got, sortedStrings(fixture.Classes)) {
		t.Fatalf("class fqNames = %#v, want %#v", got, sortedStrings(fixture.Classes))
	}
	if got := sortedFQNames(filterByKind(analysis.symbols, "function")); !slices.Equal(got, sortedStrings(fixture.Methods)) {
		t.Fatalf("method fqNames = %#v, want %#v", got, sortedStrings(fixture.Methods))
	}
	if got := sortedFQNames(filterByKind(analysis.symbols, "field")); !slices.Equal(got, sortedStrings(fixture.Fields)) {
		t.Fatalf("field fqNames = %#v, want %#v", got, sortedStrings(fixture.Fields))
	}
	if got := sortedStrings(analysis.imports); !slices.Equal(got, sortedStrings(fixture.Imports)) {
		t.Fatalf("imports = %#v, want %#v", got, sortedStrings(fixture.Imports))
	}
	if analysis.containsTests != fixture.ContainsTests {
		t.Fatalf("containsTests = %v, want %v", analysis.containsTests, fixture.ContainsTests)
	}

	if len(fixture.RawSupertypes) > 0 {
		classes := filterByKind(analysis.symbols, "class")
		if len(classes) == 0 {
			t.Fatalf("raw supertypes expected %#v, but no classes were produced", fixture.RawSupertypes)
		}
		if got := sortedStrings(classes[0].RawSupertypes); !slices.Equal(got, sortedStrings(fixture.RawSupertypes)) {
			t.Fatalf("raw supertypes = %#v, want %#v", got, sortedStrings(fixture.RawSupertypes))
		}
	}
}

func sortedFQNames(symbols []Symbol) []string {
	values := make([]string, 0, len(symbols))
	for _, symbol := range symbols {
		values = append(values, symbol.FQName)
	}
	return sortedStrings(values)
}

func sortedStrings(values []string) []string {
	cloned := append([]string(nil), values...)
	slices.Sort(cloned)
	return cloned
}
