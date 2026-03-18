//go:build cgo

package analyzer

import (
	"embed"
	"fmt"
	"path/filepath"
	"sort"
	"strings"

	tree_sitter "github.com/tree-sitter/go-tree-sitter"
	tree_sitter_go "github.com/tree-sitter/tree-sitter-go/bindings/go"
	tree_sitter_java "github.com/tree-sitter/tree-sitter-java/bindings/go"
	tree_sitter_javascript "github.com/tree-sitter/tree-sitter-javascript/bindings/go"
	tree_sitter_python "github.com/tree-sitter/tree-sitter-python/bindings/go"
	tree_sitter_typescript "github.com/tree-sitter/tree-sitter-typescript/bindings/go"
)

type treeSitterSpec struct {
	language     *tree_sitter.Language
	query        string
	languageName string
	packageName  func(string) string
}

type treeSitterDefinition struct {
	kind         string
	name         string
	receiverType string
	supertypes   []string
	hasBody      bool
	start        int
	end          int
	startLine    int
	endLine      int
	signature    string
	snippet      string
}

type treeSitterFileAnalysis struct {
	symbols       []Symbol
	imports       []string
	containsTests bool
}

type treeSitterHierarchyHint struct {
	name       string
	start      int
	end        int
	supertypes []string
}

//go:embed queries/*.scm
var treeSitterQueryFiles embed.FS

var (
	treeSitterJavaLanguage       = tree_sitter.NewLanguage(tree_sitter_java.Language())
	treeSitterGoLanguage         = tree_sitter.NewLanguage(tree_sitter_go.Language())
	treeSitterPythonLanguage     = tree_sitter.NewLanguage(tree_sitter_python.Language())
	treeSitterJavaScriptLanguage = tree_sitter.NewLanguage(tree_sitter_javascript.Language())
	treeSitterTypeScriptLanguage = tree_sitter.NewLanguage(tree_sitter_typescript.LanguageTypescript())
	treeSitterTSXLanguage        = tree_sitter.NewLanguage(tree_sitter_typescript.LanguageTSX())
	treeSitterJavaQuery          = mustReadTreeSitterQuery("queries/java.scm")
	treeSitterGoQuery            = mustReadTreeSitterQuery("queries/go.scm")
	treeSitterPythonQuery        = mustReadTreeSitterQuery("queries/python.scm")
	treeSitterJavaScriptQuery    = mustReadTreeSitterQuery("queries/javascript.scm")
	treeSitterTypeScriptQuery    = mustReadTreeSitterQuery("queries/typescript.scm")
)

func mustReadTreeSitterQuery(path string) string {
	query, err := treeSitterQueryFiles.ReadFile(path)
	if err != nil {
		panic(fmt.Sprintf("read tree-sitter query %s: %v", path, err))
	}
	return string(query)
}

func parseTreeSitterSymbols(relativePath string, content string) ([]Symbol, bool) {
	analysis, ok := parseTreeSitterFile(relativePath, content)
	if !ok {
		return nil, false
	}
	return analysis.symbols, true
}

func parseTreeSitterFile(relativePath string, content string) (treeSitterFileAnalysis, bool) {
	spec, ok := treeSitterSpecForPath(relativePath)
	if !ok {
		return treeSitterFileAnalysis{}, false
	}

	parser := tree_sitter.NewParser()
	defer parser.Close()
	if err := parser.SetLanguage(spec.language); err != nil {
		return treeSitterFileAnalysis{}, false
	}

	source := []byte(content)
	tree := parser.Parse(source, nil)
	if tree == nil {
		return treeSitterFileAnalysis{}, false
	}
	defer tree.Close()

	query, queryErr := tree_sitter.NewQuery(spec.language, spec.query)
	if queryErr != nil {
		return treeSitterFileAnalysis{}, false
	}
	defer query.Close()

	cursor := tree_sitter.NewQueryCursor()
	defer cursor.Close()

	packageName := spec.packageName(relativePath)
	definitions := make([]treeSitterDefinition, 0, 16)
	hierarchyHints := make([]treeSitterHierarchyHint, 0, 8)
	imports := make([]string, 0, 8)
	seenImports := map[string]struct{}{}
	containsTests := false
	captureNames := query.CaptureNames()
	matches := cursor.Matches(query, tree.RootNode(), source)
	for match := matches.Next(); match != nil; match = matches.Next() {
		definition := treeSitterDefinition{}
		matchSupertypes := make([]string, 0, 4)
		matchHasTestMarker := false
		testCandidateName := ""
		testCandidateParams := ""
		hierarchyStart := -1
		hierarchyEnd := -1
		for _, capture := range match.Captures {
			name := captureNames[capture.Index]
			text := strings.TrimSpace(capture.Node.Utf8Text(source))
			switch name {
			case "package.name":
				if text != "" {
					packageName = text
				}
			case "import.declaration", "import_declaration":
				if text != "" {
					if _, ok := seenImports[text]; !ok {
						seenImports[text] = struct{}{}
						imports = append(imports, text)
					}
				}
			case "type.super":
				if text != "" {
					matchSupertypes = append(matchSupertypes, normalizeTreeSitterTypeName(text))
				}
			case "type.decl":
				hierarchyStart = int(capture.Node.StartByte())
				hierarchyEnd = int(capture.Node.EndByte())
			case "test_marker":
				matchHasTestMarker = treeSitterContainsTestMarker(spec.languageName, text)
			case "test_candidate.name":
				testCandidateName = normalizeTreeSitterName(text)
			case "test_candidate.params":
				testCandidateParams = text
			default:
				if kind, ok := treeSitterCaptureKind(name); ok {
					populateTreeSitterDefinition(&definition, kind, capture.Node, content)
				} else if treeSitterNameCapture(name) {
					if definition.name == "" {
						definition.name = normalizeTreeSitterName(text)
					}
				} else if treeSitterReceiverCapture(name) {
					if definition.receiverType == "" {
						definition.receiverType = normalizeTreeSitterTypeName(text)
					}
				}
			}
		}
		if !containsTests {
			containsTests = matchHasTestMarker || treeSitterContainsTestCandidate(spec.languageName, testCandidateName, testCandidateParams)
		}
		if hierarchyStart >= 0 && hierarchyEnd > hierarchyStart && definition.name != "" && len(matchSupertypes) > 0 {
			hierarchyHints = append(hierarchyHints, treeSitterHierarchyHint{
				name:       definition.name,
				start:      hierarchyStart,
				end:        hierarchyEnd,
				supertypes: dedupeSortedStrings(matchSupertypes),
			})
		}
		if definition.kind == "" || definition.name == "" {
			continue
		}
		definition.supertypes = dedupeSortedStrings(matchSupertypes)
		definitions = append(definitions, definition)
	}
	definitions = collapseWrappedTreeSitterDefinitions(definitions)
	definitions = applyTreeSitterHierarchyHints(definitions, hierarchyHints)

	return treeSitterFileAnalysis{
		symbols:       buildTreeSitterSymbols(relativePath, spec.languageName, packageName, definitions),
		imports:       imports,
		containsTests: containsTests,
	}, true
}

func treeSitterEnabled() bool {
	return true
}

func treeSitterSpecForPath(relativePath string) (treeSitterSpec, bool) {
	switch strings.ToLower(filepath.Ext(relativePath)) {
	case ".java":
		return treeSitterSpec{
			language:     treeSitterJavaLanguage,
			query:        treeSitterJavaQuery,
			languageName: "java",
			packageName:  func(string) string { return "" },
		}, true
	case ".go":
		return treeSitterSpec{
			language:     treeSitterGoLanguage,
			query:        treeSitterGoQuery,
			languageName: "go",
			packageName:  func(string) string { return "" },
		}, true
	case ".py":
		return treeSitterSpec{
			language:     treeSitterPythonLanguage,
			query:        treeSitterPythonQuery,
			languageName: "python",
			packageName:  pythonModuleNameFromPath,
		}, true
	case ".js", ".mjs", ".cjs", ".jsx":
		return treeSitterSpec{
			language:     treeSitterJavaScriptLanguage,
			query:        treeSitterJavaScriptQuery,
			languageName: "javascript",
			packageName:  moduleNameFromPath,
		}, true
	case ".ts":
		return treeSitterSpec{
			language:     treeSitterTypeScriptLanguage,
			query:        treeSitterTypeScriptQuery,
			languageName: "typescript",
			packageName:  moduleNameFromPath,
		}, true
	case ".tsx":
		return treeSitterSpec{
			language:     treeSitterTSXLanguage,
			query:        treeSitterTypeScriptQuery,
			languageName: "typescript",
			packageName:  moduleNameFromPath,
		}, true
	default:
		return treeSitterSpec{}, false
	}
}

func populateTreeSitterDefinition(definition *treeSitterDefinition, kind string, node tree_sitter.Node, content string) {
	originalStart := int(node.StartByte())
	definition.kind = kind
	definition.start = expandTreeSitterCommentStart(content, originalStart, kind)
	definition.end = int(node.EndByte())
	definition.startLine = lineNumberAt(content, definition.start)
	definition.endLine = int(node.EndPosition().Row) + 1
	rawSnippet := strings.TrimSpace(node.Utf8Text([]byte(content)))
	definition.snippet = strings.TrimSpace(content[definition.start:definition.end])
	definition.signature = extractSignature(content, originalStart)
	definition.hasBody = treeSitterDefinitionHasBody(kind, rawSnippet)
	if definition.kind == "field" && definition.signature == "" {
		definition.signature = rawSnippet
	}
}

func treeSitterCaptureKind(name string) (string, bool) {
	switch name {
	case "class.definition", "interface.definition", "record.definition", "enum.definition", "annotation.definition",
		"type.definition", "typealias.definition", "trait.definition", "object.definition", "module.definition",
		"namespace.definition":
		return "class", true
	case "function.definition", "method.definition", "constructor.definition", "arrow_function.definition",
		"interface.method.definition":
		return "function", true
	case "field.definition", "variable.definition", "constant.definition", "value.definition", "struct.field.definition":
		return "field", true
	default:
		return "", false
	}
}

func treeSitterNameCapture(name string) bool {
	if name == "package.name" {
		return false
	}
	return strings.HasSuffix(name, ".name")
}

func treeSitterReceiverCapture(name string) bool {
	return strings.HasSuffix(name, ".receiver.type")
}

func collapseWrappedTreeSitterDefinitions(definitions []treeSitterDefinition) []treeSitterDefinition {
	results := make([]treeSitterDefinition, 0, len(definitions))
	for i, definition := range definitions {
		wrapped := false
		for j, candidate := range definitions {
			if i == j {
				continue
			}
			if definition.kind != candidate.kind || definition.name != candidate.name {
				continue
			}
			if candidate.start <= definition.start && candidate.end >= definition.end {
				if candidate.start < definition.start || candidate.end > definition.end {
					wrapped = true
					break
				}
			}
		}
		if !wrapped {
			results = append(results, definition)
		}
	}
	return results
}

func applyTreeSitterHierarchyHints(definitions []treeSitterDefinition, hints []treeSitterHierarchyHint) []treeSitterDefinition {
	if len(definitions) == 0 || len(hints) == 0 {
		return definitions
	}

	for i := range definitions {
		if definitions[i].kind != "class" {
			continue
		}
		if len(definitions[i].supertypes) > 0 {
			continue
		}
		for _, hint := range hints {
			if definitions[i].name != hint.name {
				continue
			}
			if definitions[i].start != hint.start || definitions[i].end != hint.end {
				continue
			}
			definitions[i].supertypes = append([]string(nil), hint.supertypes...)
			break
		}
	}
	return definitions
}

func buildTreeSitterSymbols(relativePath string, language string, packageName string, definitions []treeSitterDefinition) []Symbol {
	if len(definitions) == 0 {
		return []Symbol{}
	}

	classes := make([]treeSitterDefinition, 0, len(definitions))
	functions := make([]treeSitterDefinition, 0, len(definitions))
	fields := make([]treeSitterDefinition, 0, len(definitions))
	for _, definition := range definitions {
		switch definition.kind {
		case "class":
			classes = append(classes, definition)
		case "function":
			functions = append(functions, definition)
		case "field":
			fields = append(fields, definition)
		}
	}

	sort.SliceStable(classes, func(i, j int) bool {
		if classes[i].start != classes[j].start {
			return classes[i].start < classes[j].start
		}
		return classes[i].end > classes[j].end
	})

	classSymbols := make([]Symbol, len(classes))
	for i, definition := range classes {
		parentIndex := findEnclosingTreeSitterClass(classes, i, definition.start)
		shortName := definition.name
		parentFQName := ""
		if parentIndex >= 0 {
			shortName = classSymbols[parentIndex].ShortName + "." + definition.name
			parentFQName = classSymbols[parentIndex].FQName
		}
		classSymbols[i] = Symbol{
			Kind:          "class",
			Language:      language,
			FQName:        joinName(packageName, shortName),
			ShortName:     shortName,
			Identifier:    shortName,
			PackageName:   packageName,
			ParentFQName:  parentFQName,
			RelativePath:  relativePath,
			Signature:     definition.signature,
			Snippet:       definition.snippet,
			StartLine:     definition.startLine,
			EndLine:       definition.endLine,
			TopLevel:      parentIndex < 0,
			HasBody:       definition.hasBody,
			RawSupertypes: append([]string(nil), definition.supertypes...),
		}
	}

	results := make([]Symbol, 0, len(definitions))
	results = append(results, classSymbols...)

	for _, definition := range functions {
		if hasEnclosingTreeSitterFunction(functions, definition) {
			continue
		}
		parentFQName := resolveTreeSitterParentClass(classes, classSymbols, definition)
		shortName := definition.name
		fqName := joinName(packageName, shortName)
		if parentFQName != "" {
			fqName = parentFQName + "." + definition.name
		}
		results = append(results, Symbol{
			Kind:         "function",
			Language:     language,
			FQName:       fqName,
			ShortName:    shortName,
			Identifier:   shortName,
			PackageName:  packageName,
			ParentFQName: parentFQName,
			RelativePath: relativePath,
			Signature:    definition.signature,
			Snippet:      definition.snippet,
			StartLine:    definition.startLine,
			EndLine:      definition.endLine,
			TopLevel:     parentFQName == "",
			HasBody:      definition.hasBody,
		})
	}

	for _, definition := range fields {
		parentFQName := resolveTreeSitterParentClass(classes, classSymbols, definition)
		shortName := definition.name
		fqName := joinName(packageName, shortName)
		if parentFQName != "" {
			fqName = parentFQName + "." + definition.name
		}
		results = append(results, Symbol{
			Kind:         "field",
			Language:     language,
			FQName:       fqName,
			ShortName:    shortName,
			Identifier:   shortName,
			PackageName:  packageName,
			ParentFQName: parentFQName,
			RelativePath: relativePath,
			Signature:    definition.signature,
			Snippet:      definition.snippet,
			StartLine:    definition.startLine,
			EndLine:      definition.endLine,
			TopLevel:     parentFQName == "",
			HasBody:      definition.hasBody,
		})
	}

	return dedupeSymbols(results)
}

func normalizeTreeSitterName(name string) string {
	return strings.TrimPrefix(strings.TrimSpace(name), "#")
}

func normalizeTreeSitterTypeName(name string) string {
	trimmed := strings.TrimSpace(name)
	trimmed = strings.TrimPrefix(trimmed, "*")
	return strings.TrimSpace(trimmed)
}

func treeSitterDefinitionHasBody(kind string, snippet string) bool {
	trimmed := strings.TrimSpace(snippet)
	if trimmed == "" {
		return false
	}

	switch kind {
	case "function", "class":
		return strings.Contains(trimmed, "{") || strings.Contains(trimmed, ":\n") || strings.Contains(trimmed, ":\r\n")
	default:
		return false
	}
}

func expandTreeSitterCommentStart(content string, start int, kind string) int {
	if start <= 0 || start > len(content) {
		return start
	}
	if kind != "class" && kind != "function" && kind != "field" {
		return start
	}

	lineStart := treeSitterLineStart(content, start)
	expanded := lineStart
	for expanded > 0 {
		prevEnd := expanded - 1
		if content[prevEnd] == '\n' {
			prevEnd--
		}
		if prevEnd < 0 {
			break
		}

		prevStart := treeSitterLineStart(content, prevEnd+1)
		trimmed := strings.TrimSpace(content[prevStart : prevEnd+1])
		if trimmed == "" {
			break
		}
		if !isTreeSitterCommentLine(trimmed) {
			break
		}
		expanded = prevStart
	}
	return expanded
}

func treeSitterLineStart(content string, offset int) int {
	if offset <= 0 {
		return 0
	}
	if offset > len(content) {
		offset = len(content)
	}
	for i := offset - 1; i >= 0; i-- {
		if content[i] == '\n' {
			return i + 1
		}
	}
	return 0
}

func isTreeSitterCommentLine(trimmed string) bool {
	switch {
	case strings.HasPrefix(trimmed, "//"):
		return true
	case strings.HasPrefix(trimmed, "/*"):
		return true
	case strings.HasPrefix(trimmed, "*"):
		return true
	case strings.HasPrefix(trimmed, "*/"):
		return true
	case strings.HasPrefix(trimmed, "#"):
		return true
	case strings.HasPrefix(trimmed, "--"):
		return true
	default:
		return false
	}
}

func treeSitterContainsTestMarker(language string, marker string) bool {
	trimmed := strings.TrimSpace(marker)
	if trimmed == "" {
		return false
	}

	switch language {
	case "java":
		name := strings.TrimPrefix(trimmed, "@")
		switch name {
		case "Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate", "Theory":
			return true
		default:
			return false
		}
	case "python":
		lower := strings.ToLower(trimmed)
		return strings.HasPrefix(lower, "test") || strings.Contains(lower, "pytest") || strings.Contains(lower, "unittest")
	default:
		return false
	}
}

func treeSitterContainsTestCandidate(language string, candidateName string, candidateParams string) bool {
	if language != "go" {
		return false
	}
	if !strings.HasPrefix(candidateName, "Test") {
		return false
	}
	params := strings.ReplaceAll(strings.TrimSpace(candidateParams), " ", "")
	return strings.Contains(params, "*testing.T") || strings.Contains(params, "testing.T")
}

func dedupeSortedStrings(values []string) []string {
	if len(values) == 0 {
		return nil
	}

	seen := map[string]struct{}{}
	results := make([]string, 0, len(values))
	for _, value := range values {
		trimmed := strings.TrimSpace(value)
		if trimmed == "" {
			continue
		}
		if _, ok := seen[trimmed]; ok {
			continue
		}
		seen[trimmed] = struct{}{}
		results = append(results, trimmed)
	}
	return results
}

func findEnclosingTreeSitterClass(classes []treeSitterDefinition, current int, offset int) int {
	best := -1
	for i := range classes {
		if i == current {
			continue
		}
		if offset <= classes[i].start || offset > classes[i].end {
			continue
		}
		if best < 0 || classes[i].start > classes[best].start {
			best = i
		}
	}
	return best
}

func resolveTreeSitterParentClass(classes []treeSitterDefinition, classSymbols []Symbol, definition treeSitterDefinition) string {
	if definition.receiverType != "" {
		for i, classSymbol := range classSymbols {
			if classSymbol.ShortName == definition.receiverType || strings.HasSuffix(classSymbol.FQName, "."+definition.receiverType) {
				return classSymbols[i].FQName
			}
		}
	}

	best := -1
	for i := range classes {
		if definition.start <= classes[i].start || definition.start > classes[i].end {
			continue
		}
		if best < 0 || classes[i].start > classes[best].start {
			best = i
		}
	}
	if best >= 0 {
		return classSymbols[best].FQName
	}
	return ""
}

func hasEnclosingTreeSitterFunction(functions []treeSitterDefinition, definition treeSitterDefinition) bool {
	for _, candidate := range functions {
		if candidate.start == definition.start && candidate.end == definition.end {
			continue
		}
		if definition.start > candidate.start && definition.start < candidate.end {
			return true
		}
	}
	return false
}
