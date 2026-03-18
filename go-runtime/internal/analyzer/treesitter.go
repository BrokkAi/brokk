//go:build cgo

package analyzer

import (
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
	start        int
	end          int
	startLine    int
	endLine      int
	signature    string
	snippet      string
}

var (
	treeSitterJavaLanguage       = tree_sitter.NewLanguage(tree_sitter_java.Language())
	treeSitterGoLanguage         = tree_sitter.NewLanguage(tree_sitter_go.Language())
	treeSitterPythonLanguage     = tree_sitter.NewLanguage(tree_sitter_python.Language())
	treeSitterJavaScriptLanguage = tree_sitter.NewLanguage(tree_sitter_javascript.Language())
	treeSitterTypeScriptLanguage = tree_sitter.NewLanguage(tree_sitter_typescript.LanguageTypescript())
	treeSitterTSXLanguage        = tree_sitter.NewLanguage(tree_sitter_typescript.LanguageTSX())
)

const treeSitterJavaQuery = `
(package_declaration
  [(identifier) (scoped_identifier)] @package.name)

(class_declaration
  name: (identifier) @class.name) @class.definition

(interface_declaration
  name: (identifier) @class.name) @class.definition

(record_declaration
  name: (identifier) @class.name) @class.definition

(enum_declaration
  name: (identifier) @class.name) @class.definition

(annotation_type_declaration
  name: (identifier) @class.name) @class.definition

(method_declaration
  name: (identifier) @function.name) @function.definition

(constructor_declaration
  name: (identifier) @function.name) @function.definition

(field_declaration
  (variable_declarator
    name: (identifier) @field.name)
) @field.definition

(constant_declaration
  (variable_declarator
    name: (identifier) @field.name)
) @field.definition

(enum_constant
  name: (identifier) @field.name) @field.definition
`

const treeSitterGoQuery = `
(package_clause
  (package_identifier) @package.name)

(type_declaration
  (type_spec
    name: (type_identifier) @class.name)
) @class.definition

(type_declaration
  (type_alias
    name: (type_identifier) @class.name)
) @class.definition

(function_declaration
  name: (identifier) @function.name) @function.definition

(method_declaration
  receiver: (parameter_list
    (parameter_declaration
      type: [
        (type_identifier) @receiver.type
        (pointer_type (type_identifier) @receiver.type)
      ]
    )
  )
  name: (field_identifier) @function.name
) @function.definition

(struct_type
  (field_declaration_list
    (field_declaration
      name: (field_identifier) @field.name
    ) @field.definition
  )
)

(interface_type
  (method_elem
    name: (field_identifier) @function.name
  ) @function.definition
)
`

const treeSitterPythonQuery = `
(class_definition
  name: (identifier) @class.name) @class.definition

(function_definition
  name: (identifier) @function.name) @function.definition

(import_statement) @import.declaration
(import_from_statement) @import.declaration

(expression_statement
  (assignment
    left: (identifier) @field.name)
) @field.definition
`

const treeSitterJavaScriptQuery = `
(class_declaration
  name: (identifier) @class.name) @class.definition

(function_declaration
  name: (identifier) @function.name) @function.definition

(method_definition
  name: [
    (property_identifier)
    (private_property_identifier)
    (identifier)
  ] @function.name
) @function.definition

(field_definition
  property: [
    (property_identifier)
    (private_property_identifier)
    (identifier)
  ] @field.name
) @field.definition

(variable_declarator
  name: (identifier) @function.name
  value: (arrow_function)
) @function.definition

(import_statement) @import.declaration
`

const treeSitterTypeScriptQuery = `
(class_declaration
  name: [(identifier) (type_identifier)] @class.name) @class.definition

(abstract_class_declaration
  name: (type_identifier) @class.name) @class.definition

(interface_declaration
  name: (type_identifier) @class.name) @class.definition

(enum_declaration
  name: (identifier) @class.name) @class.definition

(type_alias_declaration
  name: (type_identifier) @class.name) @class.definition

(internal_module
  name: (_) @class.name) @class.definition

(function_declaration
  name: (identifier) @function.name) @function.definition

(function_signature
  name: (identifier) @function.name) @function.definition

(method_definition
  name: [
    (property_identifier)
    (private_property_identifier)
    (identifier)
  ] @function.name
) @function.definition

(method_signature
  name: [
    (property_identifier)
    (identifier)
  ] @function.name
) @function.definition

(public_field_definition
  name: [
    (property_identifier)
    (private_property_identifier)
    (identifier)
  ] @field.name
) @field.definition

(field_definition
  property: [
    (property_identifier)
    (private_property_identifier)
    (identifier)
  ] @field.name
) @field.definition

(property_signature
  name: [
    (property_identifier)
    (identifier)
  ] @field.name
) @field.definition

(variable_declarator
  name: (identifier) @function.name
  value: (arrow_function)
) @function.definition

(import_statement) @import.declaration
`

func parseTreeSitterSymbols(relativePath string, content string) ([]Symbol, bool) {
	spec, ok := treeSitterSpecForPath(relativePath)
	if !ok {
		return nil, false
	}

	parser := tree_sitter.NewParser()
	defer parser.Close()
	if err := parser.SetLanguage(spec.language); err != nil {
		return nil, false
	}

	source := []byte(content)
	tree := parser.Parse(source, nil)
	if tree == nil {
		return nil, false
	}
	defer tree.Close()

	query, queryErr := tree_sitter.NewQuery(spec.language, spec.query)
	if queryErr != nil {
		return nil, false
	}
	defer query.Close()

	cursor := tree_sitter.NewQueryCursor()
	defer cursor.Close()

	packageName := spec.packageName(relativePath)
	definitions := make([]treeSitterDefinition, 0, 16)
	captureNames := query.CaptureNames()
	matches := cursor.Matches(query, tree.RootNode(), source)
	for match := matches.Next(); match != nil; match = matches.Next() {
		definition := treeSitterDefinition{}
		for _, capture := range match.Captures {
			name := captureNames[capture.Index]
			text := strings.TrimSpace(capture.Node.Utf8Text(source))
			switch name {
			case "package.name":
				if text != "" {
					packageName = text
				}
			case "class.definition":
				populateTreeSitterDefinition(&definition, "class", capture.Node, content)
			case "function.definition":
				populateTreeSitterDefinition(&definition, "function", capture.Node, content)
			case "field.definition":
				populateTreeSitterDefinition(&definition, "field", capture.Node, content)
			case "class.name", "function.name", "field.name":
				if definition.name == "" {
					definition.name = normalizeTreeSitterName(text)
				}
			case "receiver.type":
				if definition.receiverType == "" {
					definition.receiverType = normalizeTreeSitterTypeName(text)
				}
			}
		}
		if definition.kind == "" || definition.name == "" {
			continue
		}
		definitions = append(definitions, definition)
	}

	return buildTreeSitterSymbols(relativePath, spec.languageName, packageName, definitions), true
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
			packageName:  moduleNameFromPath,
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

func populateTreeSitterDefinition(definition *treeSitterDefinition, kind string, node *tree_sitter.Node, content string) {
	definition.kind = kind
	definition.start = int(node.StartByte())
	definition.end = int(node.EndByte())
	definition.startLine = int(node.StartPosition().Row) + 1
	definition.endLine = int(node.EndPosition().Row) + 1
	definition.snippet = strings.TrimSpace(node.Utf8Text([]byte(content)))
	definition.signature = extractSignature(content, definition.start)
	if definition.kind == "field" && definition.signature == "" {
		definition.signature = definition.snippet
	}
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
			Kind:         "class",
			Language:     language,
			FQName:       joinName(packageName, shortName),
			ShortName:    shortName,
			Identifier:   shortName,
			PackageName:  packageName,
			ParentFQName: parentFQName,
			RelativePath: relativePath,
			Signature:    definition.signature,
			Snippet:      definition.snippet,
			StartLine:    definition.startLine,
			EndLine:      definition.endLine,
			TopLevel:     parentIndex < 0,
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
