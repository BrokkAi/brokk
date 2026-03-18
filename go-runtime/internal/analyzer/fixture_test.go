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
	Name                       string            `json:"name"`
	RelativePath               string            `json:"relativePath"`
	Content                    string            `json:"content"`
	AdditionalFiles            map[string]string `json:"additionalFiles"`
	Classes                    []string          `json:"classes"`
	Methods                    []string          `json:"methods"`
	Fields                     []string          `json:"fields"`
	Imports                    []string          `json:"imports"`
	ContainsTests              bool              `json:"containsTests"`
	RawSupertypes              []string          `json:"rawSupertypes"`
	ResolveClassesInput        []string          `json:"resolveClassesInput"`
	ResolveClasses             []string          `json:"resolveClasses"`
	ResolveMethodsInput        []string          `json:"resolveMethodsInput"`
	ResolveMethods             []string          `json:"resolveMethods"`
	CompleteQuery              string            `json:"completeQuery"`
	CompleteLimit              int               `json:"completeLimit"`
	Complete                   []string          `json:"complete"`
	SkeletonFQName             string            `json:"skeletonFQName"`
	SkeletonContains           []string          `json:"skeletonContains"`
	RenderSymbolFQName         string            `json:"renderSymbolFQName"`
	RenderSymbolKind           string            `json:"renderSymbolKind"`
	RenderSymbolContains       []string          `json:"renderSymbolContains"`
	DefinitionGroupFQName      string            `json:"definitionGroupFQName"`
	DefinitionGroupKind        string            `json:"definitionGroupKind"`
	DefinitionGroupContains    []string          `json:"definitionGroupContains"`
	DefinitionGroupSymbolCount int               `json:"definitionGroupSymbolCount"`
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

	workspace := t.TempDir()
	writeAnalyzerFile(t, workspace, fixture.RelativePath, fixture.Content)
	for relativePath, content := range fixture.AdditionalFiles {
		writeAnalyzerFile(t, workspace, relativePath, content)
	}

	service := New(workspace)
	if len(fixture.ResolveClassesInput) > 0 {
		if got := sortedFQNames(service.ResolveClasses(fixture.ResolveClassesInput)); !slices.Equal(got, sortedStrings(fixture.ResolveClasses)) {
			t.Fatalf("ResolveClasses() = %#v, want %#v", got, sortedStrings(fixture.ResolveClasses))
		}
	}
	if len(fixture.ResolveMethodsInput) > 0 {
		if got := sortedFQNames(service.ResolveMethods(fixture.ResolveMethodsInput)); !slices.Equal(got, sortedStrings(fixture.ResolveMethods)) {
			t.Fatalf("ResolveMethods() = %#v, want %#v", got, sortedStrings(fixture.ResolveMethods))
		}
	}
	if fixture.CompleteQuery != "" {
		limit := fixture.CompleteLimit
		if limit <= 0 {
			limit = 10
		}
		if got := sortedCompletionDetails(service.CompleteSymbols(fixture.CompleteQuery, limit)); !slices.Equal(got, sortedStrings(fixture.Complete)) {
			t.Fatalf("CompleteSymbols() = %#v, want %#v", got, sortedStrings(fixture.Complete))
		}
	}
	if fixture.SkeletonFQName != "" {
		header, ok := service.SkeletonHeader(fixture.SkeletonFQName)
		if !ok {
			t.Fatalf("SkeletonHeader(%q) = false, want true", fixture.SkeletonFQName)
		}
		for _, expected := range fixture.SkeletonContains {
			if !strings.Contains(header, expected) {
				t.Fatalf("SkeletonHeader(%q) = %q, want to contain %q", fixture.SkeletonFQName, header, expected)
			}
		}
	}
	if fixture.RenderSymbolFQName != "" && fixture.RenderSymbolKind != "" {
		symbols := filterByKind(service.Definitions(fixture.RenderSymbolFQName), fixture.RenderSymbolKind)
		if len(symbols) == 0 {
			t.Fatalf("Definitions(%q) had no %q symbol to render", fixture.RenderSymbolFQName, fixture.RenderSymbolKind)
		}
		rendered := service.RenderSymbol(symbols[0])
		for _, expected := range fixture.RenderSymbolContains {
			if !strings.Contains(rendered, expected) {
				t.Fatalf("RenderSymbol(%q) = %q, want to contain %q", fixture.RenderSymbolFQName, rendered, expected)
			}
		}
	}
	if fixture.DefinitionGroupFQName != "" && fixture.DefinitionGroupKind != "" {
		group, ok := service.DefinitionGroup(fixture.DefinitionGroupFQName, fixture.DefinitionGroupKind)
		if !ok {
			t.Fatalf("DefinitionGroup(%q, %q) = false, want true", fixture.DefinitionGroupFQName, fixture.DefinitionGroupKind)
		}
		if fixture.DefinitionGroupSymbolCount > 0 && len(group.Symbols) != fixture.DefinitionGroupSymbolCount {
			t.Fatalf("len(DefinitionGroup(%q).Symbols) = %d, want %d", fixture.DefinitionGroupFQName, len(group.Symbols), fixture.DefinitionGroupSymbolCount)
		}
		rendered := service.RenderDefinitionGroup(group)
		for _, expected := range fixture.DefinitionGroupContains {
			if !strings.Contains(rendered, expected) {
				t.Fatalf("RenderDefinitionGroup(%q) = %q, want to contain %q", fixture.DefinitionGroupFQName, rendered, expected)
			}
		}
	}
	if service.ContainsTests(fixture.RelativePath) != fixture.ContainsTests {
		t.Fatalf("ContainsTests(%q) = %v, want %v", fixture.RelativePath, service.ContainsTests(fixture.RelativePath), fixture.ContainsTests)
	}
	if len(fixture.RawSupertypes) > 0 && len(fixture.Classes) > 0 {
		if got := sortedStrings(service.RawSupertypes(fixture.Classes[0])); !slices.Equal(got, sortedStrings(fixture.RawSupertypes)) {
			t.Fatalf("RawSupertypes(%q) = %#v, want %#v", fixture.Classes[0], got, sortedStrings(fixture.RawSupertypes))
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

func sortedCompletionDetails(completions []Completion) []string {
	values := make([]string, 0, len(completions))
	for _, completion := range completions {
		values = append(values, completion.Detail)
	}
	return sortedStrings(values)
}
