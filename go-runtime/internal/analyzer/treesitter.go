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
	nodeType     string
	receiverType string
	supertypes   []string
	insideObject bool
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

type treeSitterDroppedRange struct {
	start int
	end   int
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
		requireImportText := ""
		var requireCallNode *tree_sitter.Node
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
			case "module.require_call":
				node := capture.Node
				requireCallNode = &node
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
		if requireCallNode != nil {
			requireImportText = treeSitterRequireImportText(*requireCallNode, source)
		}
		if treeSitterLooksLikeRequireImport(requireImportText) {
			imports = appendTreeSitterImportSnippet(imports, seenImports, requireImportText)
		}
		if hierarchyStart >= 0 && hierarchyEnd > hierarchyStart && definition.name != "" && len(matchSupertypes) > 0 {
			hierarchyHints = append(hierarchyHints, treeSitterHierarchyHint{
				name:       definition.name,
				start:      hierarchyStart,
				end:        hierarchyEnd,
				supertypes: dedupeSortedStrings(matchSupertypes),
			})
		}
		if definition.name == "" {
			definition.name = treeSitterDefaultDefinitionName(spec.languageName, definition.kind, definition.nodeType)
		}
		if definition.kind == "" || definition.name == "" {
			continue
		}
		definition.supertypes = dedupeSortedStrings(matchSupertypes)
		definitions = append(definitions, definition)
	}
	definitions = collapseWrappedTreeSitterDefinitions(definitions)
	definitions = pruneTreeSitterDeclarationMerges(spec.languageName, definitions)
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
			packageName:  jsLikePackageNameFromPath,
		}, true
	case ".ts":
		return treeSitterSpec{
			language:     treeSitterTypeScriptLanguage,
			query:        treeSitterTypeScriptQuery,
			languageName: "typescript",
			packageName:  jsLikePackageNameFromPath,
		}, true
	case ".tsx":
		return treeSitterSpec{
			language:     treeSitterTSXLanguage,
			query:        treeSitterTypeScriptQuery,
			languageName: "typescript",
			packageName:  jsLikePackageNameFromPath,
		}, true
	default:
		return treeSitterSpec{}, false
	}
}

func populateTreeSitterDefinition(definition *treeSitterDefinition, kind string, node tree_sitter.Node, content string) {
	originalStart := int(node.StartByte())
	definition.kind = kind
	definition.nodeType = node.Kind()
	definition.start = expandTreeSitterCommentStart(content, originalStart, kind)
	definition.end = int(node.EndByte())
	definition.startLine = lineNumberAt(content, definition.start)
	definition.endLine = int(node.EndPosition().Row) + 1
	rawSnippet := strings.TrimSpace(node.Utf8Text([]byte(content)))
	definition.snippet = strings.TrimSpace(content[definition.start:definition.end])
	definition.signature = extractSignature(content, originalStart)
	definition.hasBody = treeSitterDefinitionHasBody(kind, rawSnippet)
	definition.insideObject = treeSitterDefinitionInsideObjectLiteral(node)
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

func pruneTreeSitterDeclarationMerges(language string, definitions []treeSitterDefinition) []treeSitterDefinition {
	if language != "typescript" || len(definitions) == 0 {
		return definitions
	}

	functionNames := map[string]struct{}{}
	for i, definition := range definitions {
		if definition.kind != "function" {
			continue
		}
		if hasEnclosingTreeSitterDefinition(definitions, i) {
			continue
		}
		functionNames[definition.name] = struct{}{}
	}

	dropped := make([]treeSitterDroppedRange, 0, 4)
	results := make([]treeSitterDefinition, 0, len(definitions))
	for i, definition := range definitions {
		if treeSitterIsTypescriptNamespaceDefinition(definition) && !hasEnclosingTreeSitterDefinition(definitions, i) {
			if _, ok := functionNames[definition.name]; ok {
				dropped = append(dropped, treeSitterDroppedRange{start: definition.start, end: definition.end})
				continue
			}
		}
		if treeSitterDefinitionWithinDroppedRange(definition, dropped) {
			continue
		}
		results = append(results, definition)
	}
	return results
}

func treeSitterIsTypescriptNamespaceDefinition(definition treeSitterDefinition) bool {
	if definition.kind != "class" {
		return false
	}
	if definition.nodeType == "internal_module" {
		return true
	}
	signature := strings.TrimSpace(definition.signature)
	snippet := strings.TrimSpace(definition.snippet)
	return strings.Contains(signature, "namespace "+definition.name) ||
		strings.Contains(signature, "module "+definition.name) ||
		strings.Contains(snippet, "namespace "+definition.name) ||
		strings.Contains(snippet, "module "+definition.name)
}

func hasEnclosingTreeSitterDefinition(definitions []treeSitterDefinition, index int) bool {
	definition := definitions[index]
	for i, candidate := range definitions {
		if i == index {
			continue
		}
		if candidate.start > definition.start || candidate.end < definition.end {
			continue
		}
		if candidate.start < definition.start || candidate.end > definition.end {
			return true
		}
	}
	return false
}

func treeSitterDefinitionWithinDroppedRange(definition treeSitterDefinition, dropped []treeSitterDroppedRange) bool {
	for _, candidate := range dropped {
		if candidate.start <= definition.start && candidate.end >= definition.end {
			return true
		}
	}
	return false
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

	classParents := make([]int, len(classes))
	for i, definition := range classes {
		classParents[i] = findEnclosingTreeSitterClass(classes, i, definition.start)
	}

	classSymbols := make([]Symbol, len(classes))
	for i, definition := range classes {
		parentIndex := classParents[i]
		shortName := definition.name
		parentFQName := ""
		symbolPackage := packageName
		if language == "typescript" {
			namespacePath, classParentIndex := resolveTypeScriptNamespaceContext(classes, classParents, parentIndex)
			switch {
			case classParentIndex >= 0:
				shortName = classSymbols[classParentIndex].ShortName + "." + definition.name
				parentFQName = classSymbols[classParentIndex].FQName
				symbolPackage = classSymbols[classParentIndex].PackageName
			case namespacePath != "":
				symbolPackage = namespacePath
			case parentIndex >= 0:
				shortName = classSymbols[parentIndex].ShortName + "." + definition.name
				parentFQName = classSymbols[parentIndex].FQName
			}
		} else if parentIndex >= 0 {
			shortName = classSymbols[parentIndex].ShortName + "." + definition.name
			parentFQName = classSymbols[parentIndex].FQName
		}
		classSymbols[i] = Symbol{
			Kind:          "class",
			Language:      language,
			FQName:        joinName(symbolPackage, shortName),
			ShortName:     shortName,
			Identifier:    shortName,
			PackageName:   symbolPackage,
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
	results = append(results, buildImplicitTreeSitterConstructors(language, packageName, relativePath, classes, classSymbols, functions)...)

	for _, definition := range functions {
		if hasEnclosingTreeSitterFunction(functions, definition) {
			continue
		}
		parentSymbol := resolveTreeSitterParentClassSymbol(classes, classSymbols, definition)
		symbolPackage := packageName
		parentFQName := ""
		if language == "typescript" {
			namespacePath, classParentIndex := resolveTypeScriptNamespaceContextForDefinition(classes, classParents, definition)
			switch {
			case classParentIndex >= 0:
				parentSymbol = &classSymbols[classParentIndex]
				parentFQName = parentSymbol.FQName
				symbolPackage = parentSymbol.PackageName
			case namespacePath != "":
				parentSymbol = nil
				symbolPackage = namespacePath
			case parentSymbol != nil:
				parentFQName = parentSymbol.FQName
			}
		} else if parentSymbol != nil {
			parentFQName = parentSymbol.FQName
		}
		if shouldSkipTreeSitterFunction(language, parentSymbol, definition) {
			continue
		}
		shortName := definition.name
		fqName := joinName(symbolPackage, shortName)
		if parentFQName != "" {
			fqName = parentFQName + "." + definition.name
		}
		fqName = enhanceTypeScriptMemberFQName(language, parentSymbol, definition, fqName)
		results = append(results, Symbol{
			Kind:         "function",
			Language:     language,
			FQName:       fqName,
			ShortName:    shortName,
			Identifier:   shortName,
			PackageName:  symbolPackage,
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
		parentSymbol := resolveTreeSitterParentClassSymbol(classes, classSymbols, definition)
		symbolPackage := packageName
		parentFQName := ""
		namespacePath := ""
		if language == "typescript" {
			namespacePath, classParentIndex := resolveTypeScriptNamespaceContextForDefinition(classes, classParents, definition)
			switch {
			case classParentIndex >= 0:
				parentSymbol = &classSymbols[classParentIndex]
				parentFQName = parentSymbol.FQName
				symbolPackage = parentSymbol.PackageName
			case namespacePath != "":
				parentSymbol = nil
				symbolPackage = namespacePath
			case parentSymbol != nil:
				parentFQName = parentSymbol.FQName
			}
		} else if parentSymbol != nil {
			parentFQName = parentSymbol.FQName
		}
		shortName := definition.name
		fqName := joinName(symbolPackage, shortName)
		if parentFQName != "" {
			fqName = parentFQName + "." + definition.name
		}
		shortName, fqName = enhanceTypeScriptNamespaceFieldName(language, relativePath, packageName, symbolPackage, parentSymbol, namespacePath, definition, shortName, fqName)
		fqName = enhanceTypeScriptMemberFQName(language, parentSymbol, definition, fqName)
		results = append(results, Symbol{
			Kind:         "field",
			Language:     language,
			FQName:       fqName,
			ShortName:    shortName,
			Identifier:   shortName,
			PackageName:  symbolPackage,
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

func buildImplicitTreeSitterConstructors(language string, packageName string, relativePath string, classes []treeSitterDefinition, classSymbols []Symbol, functions []treeSitterDefinition) []Symbol {
	if language != "java" || len(classes) == 0 {
		return nil
	}

	results := make([]Symbol, 0, len(classes))
	for i, classDefinition := range classes {
		if !treeSitterJavaNeedsImplicitConstructor(classDefinition) {
			continue
		}
		if treeSitterJavaHasExplicitConstructor(classes, classSymbols, functions, i) {
			continue
		}

		classSymbol := classSymbols[i]
		results = append(results, Symbol{
			Kind:         "function",
			Language:     language,
			FQName:       classSymbol.FQName + "." + classDefinition.name,
			ShortName:    classDefinition.name,
			Identifier:   classDefinition.name,
			PackageName:  packageName,
			ParentFQName: classSymbol.FQName,
			RelativePath: relativePath,
			StartLine:    classDefinition.startLine,
			EndLine:      classDefinition.endLine,
			TopLevel:     false,
			HasBody:      false,
		})
	}
	return results
}

func treeSitterJavaNeedsImplicitConstructor(definition treeSitterDefinition) bool {
	return definition.nodeType == "class_declaration"
}

func treeSitterJavaHasExplicitConstructor(classes []treeSitterDefinition, classSymbols []Symbol, functions []treeSitterDefinition, classIndex int) bool {
	classDefinition := classes[classIndex]
	classSymbol := classSymbols[classIndex]
	for _, function := range functions {
		if function.nodeType != "constructor_declaration" {
			continue
		}
		if function.name != classDefinition.name {
			continue
		}
		parent := resolveTreeSitterParentClassSymbol(classes, classSymbols, function)
		if parent == nil || parent.FQName != classSymbol.FQName {
			continue
		}
		return true
	}
	return false
}

func normalizeTreeSitterName(name string) string {
	trimmed := strings.TrimSpace(name)
	trimmed = strings.TrimPrefix(trimmed, "#")
	if len(trimmed) >= 2 {
		switch {
		case (strings.HasPrefix(trimmed, "\"") && strings.HasSuffix(trimmed, "\"")) ||
			(strings.HasPrefix(trimmed, "'") && strings.HasSuffix(trimmed, "'")) ||
			(strings.HasPrefix(trimmed, "`") && strings.HasSuffix(trimmed, "`")):
			trimmed = trimmed[1 : len(trimmed)-1]
		case strings.HasPrefix(trimmed, "[") && strings.HasSuffix(trimmed, "]"):
			trimmed = strings.TrimSpace(trimmed[1 : len(trimmed)-1])
		}
	}
	return trimmed
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

func treeSitterDefaultDefinitionName(language string, kind string, nodeType string) string {
	if language != "typescript" {
		return ""
	}

	switch {
	case kind == "function" && nodeType == "construct_signature":
		return "new"
	case kind == "function" && nodeType == "call_signature":
		return "[call]"
	case kind == "field" && nodeType == "index_signature":
		return "[index]"
	default:
		return ""
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
		lower := strings.ToLower(strings.TrimPrefix(trimmed, "@"))
		return strings.HasPrefix(lower, "test_") || strings.HasPrefix(lower, "pytest.mark")
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

func appendTreeSitterImportSnippet(imports []string, seen map[string]struct{}, snippet string) []string {
	trimmed := strings.TrimSpace(snippet)
	if trimmed == "" {
		return imports
	}
	if _, ok := seen[trimmed]; ok {
		return imports
	}
	seen[trimmed] = struct{}{}
	return append(imports, trimmed)
}

func treeSitterRequireImportText(node tree_sitter.Node, source []byte) string {
	nodeToCapture := node
	parent := node.Parent()
	if parent != nil && parent.Kind() == "variable_declarator" {
		declaration := parent.Parent()
		if declaration != nil {
			switch declaration.Kind() {
			case "lexical_declaration", "variable_declaration":
				nodeToCapture = *declaration
			}
		}
	}
	return strings.TrimSpace(nodeToCapture.Utf8Text(source))
}

func treeSitterLooksLikeRequireImport(text string) bool {
	trimmed := strings.TrimSpace(text)
	return strings.Contains(trimmed, "require(") || strings.Contains(trimmed, "require (")
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

func findEnclosingTreeSitterClassForDefinition(classes []treeSitterDefinition, definition treeSitterDefinition) int {
	best := -1
	for i := range classes {
		if definition.start < classes[i].start || definition.end > classes[i].end {
			continue
		}
		if best < 0 || classes[i].start > classes[best].start {
			best = i
		}
	}
	return best
}

func resolveTreeSitterParentClassSymbol(classes []treeSitterDefinition, classSymbols []Symbol, definition treeSitterDefinition) *Symbol {
	if definition.receiverType != "" {
		for i := range classSymbols {
			classSymbol := &classSymbols[i]
			if classSymbol.ShortName == definition.receiverType || strings.HasSuffix(classSymbol.FQName, "."+definition.receiverType) {
				return classSymbol
			}
		}
	}

	best := -1
	for i := range classes {
		if definition.start < classes[i].start || definition.end > classes[i].end {
			continue
		}
		if best < 0 || classes[i].start > classes[best].start {
			best = i
		}
	}
	if best >= 0 {
		return &classSymbols[best]
	}
	return nil
}

func resolveTypeScriptNamespaceContext(classes []treeSitterDefinition, classParents []int, parentIndex int) (string, int) {
	if parentIndex < 0 {
		return "", -1
	}

	parts := make([]string, 0, 4)
	current := parentIndex
	for current >= 0 {
		definition := classes[current]
		if !treeSitterIsTypescriptNamespaceDefinition(definition) {
			return "", current
		}
		namespaceParts := splitTypeScriptNamespaceParts(definition.name)
		for i := len(namespaceParts) - 1; i >= 0; i-- {
			parts = append([]string{namespaceParts[i]}, parts...)
		}
		current = classParents[current]
	}

	return strings.Join(parts, "."), -1
}

func resolveTypeScriptNamespaceContextForDefinition(classes []treeSitterDefinition, classParents []int, definition treeSitterDefinition) (string, int) {
	return resolveTypeScriptNamespaceContext(classes, classParents, findEnclosingTreeSitterClassForDefinition(classes, definition))
}

func splitTypeScriptNamespaceParts(name string) []string {
	raw := strings.TrimSpace(name)
	if raw == "" {
		return nil
	}

	parts := strings.Split(raw, ".")
	results := make([]string, 0, len(parts))
	for _, part := range parts {
		trimmed := strings.TrimSpace(part)
		if trimmed == "" {
			continue
		}
		results = append(results, trimmed)
	}
	return results
}

func enhanceTypeScriptMemberFQName(language string, parentSymbol *Symbol, definition treeSitterDefinition, fqName string) string {
	if language != "typescript" || parentSymbol == nil {
		return fqName
	}
	if treeSitterIsTypescriptNamespaceSignature(parentSymbol.Signature) {
		return fqName
	}

	signature := strings.TrimSpace(definition.signature)
	if signature == "" {
		signature = strings.TrimSpace(definition.snippet)
	}
	switch {
	case strings.HasPrefix(signature, "get "), strings.HasPrefix(signature, "abstract get "):
		return fqName + "$get"
	case strings.HasPrefix(signature, "set "), strings.HasPrefix(signature, "abstract set "):
		return fqName + "$set"
	case treeSitterHasTypeScriptStaticModifier(signature):
		return fqName + "$static"
	default:
		return fqName
	}
}

func enhanceTypeScriptNamespaceFieldName(language string, relativePath string, basePackage string, symbolPackage string, parentSymbol *Symbol, namespacePath string, definition treeSitterDefinition, shortName string, fqName string) (string, string) {
	if language != "typescript" || definition.kind != "field" {
		return shortName, fqName
	}
	if parentSymbol != nil && !treeSitterIsTypescriptNamespaceSignature(parentSymbol.Signature) {
		return shortName, fqName
	}
	if parentSymbol == nil && namespacePath == "" && (symbolPackage == "" || symbolPackage == basePackage) {
		return shortName, fqName
	}

	fileName := filepath.Base(filepath.ToSlash(relativePath))
	if fileName == "" {
		return shortName, fqName
	}
	shortName = fileName + "." + definition.name
	if parentSymbol != nil {
		fqName = parentSymbol.FQName + "." + shortName
	} else {
		fqName = joinName(firstNonEmpty(namespacePath, symbolPackage), shortName)
	}
	return shortName, fqName
}

func shouldSkipTreeSitterFunction(language string, parentSymbol *Symbol, definition treeSitterDefinition) bool {
	if language != "typescript" {
		return false
	}
	if definition.nodeType != "method_definition" {
		return false
	}
	return definition.insideObject || parentSymbol == nil
}

func treeSitterIsTypescriptNamespaceSignature(signature string) bool {
	trimmed := strings.TrimSpace(signature)
	return strings.Contains(trimmed, "namespace ") || strings.Contains(trimmed, "module ")
}

func treeSitterHasTypeScriptStaticModifier(signature string) bool {
	trimmed := strings.TrimSpace(signature)
	return strings.HasPrefix(trimmed, "static ") || strings.Contains(trimmed, "\nstatic ")
}

func treeSitterDefinitionInsideObjectLiteral(node tree_sitter.Node) bool {
	parent := node.Parent()
	for parent != nil {
		switch parent.Kind() {
		case "class_body":
			return false
		case "object":
			return true
		}
		parent = parent.Parent()
	}
	return false
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
