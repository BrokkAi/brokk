package analyzer

import (
	"fmt"
	"io/fs"
	"math"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
)

type Service struct {
	workspace string

	mu    sync.RWMutex
	index *indexSnapshot
}

type Symbol struct {
	Kind         string
	Language     string
	FQName       string
	ShortName    string
	Identifier   string
	PackageName  string
	ParentFQName string
	RelativePath string
	Signature    string
	Snippet      string
	StartLine    int
	EndLine      int
	TopLevel     bool
}

type Completion struct {
	Type   string
	Name   string
	Detail string
}

type scoredSymbol struct {
	symbol Symbol
	score  int
}

type fileStamp struct {
	relativePath string
	size         int64
	modUnixNano  int64
}

type indexSnapshot struct {
	stamps         []fileStamp
	symbols        []Symbol
	byFQName       map[string][]int
	membersByClass map[string][]int
}

type DefinitionGroup struct {
	FQName       string
	Kind         string
	ShortName    string
	RelativePath string
	Symbols      []Symbol
}

type javaClassDecl struct {
	name      string
	shortName string
	fqName    string
	parentFQ  string
	start     int
	end       int
	startLine int
	endLine   int
	signature string
	snippet   string
}

type javaMethodDecl struct {
	name      string
	signature string
	start     int
	end       int
	startLine int
	endLine   int
	snippet   string
}

type goTypeDecl struct {
	name      string
	fqName    string
	kind      string
	start     int
	end       int
	startLine int
	endLine   int
	signature string
	snippet   string
}

type pythonScopedDecl struct {
	name     string
	indent   int
	fqName   string
	parentFQ string
}

type blockInfo struct {
	symbol Symbol
	start  int
	end    int
}

var (
	javaPackagePattern = regexp.MustCompile(`(?m)^\s*package\s+([A-Za-z0-9_.]+)\s*;`)
	javaClassPattern   = regexp.MustCompile(`(?m)\b(class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)`)
	javaMethodPattern  = regexp.MustCompile(`(?m)^[ \t]*(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp|\s)+([A-Za-z0-9_<>\[\], ?]+)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^;\n]*)\)\s*(?:throws [^{\n]+)?\{`)
	javaCtorPattern    = regexp.MustCompile(`(?m)^[ \t]*(?:public|protected|private|\s)+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^;\n]*)\)\s*(?:throws [^{\n]+)?\{`)
	javaFieldPattern   = regexp.MustCompile(`(?m)^[ \t]*(?:public|protected|private|static|final|volatile|transient|\s)+[A-Za-z0-9_<>\[\], ?]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:=[^;\n]*)?;`)
	javaImportPattern  = regexp.MustCompile(`(?m)^\s*import\s+[^;]+;`)

	goPackagePattern = regexp.MustCompile(`(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_]*)`)
	goTypePattern    = regexp.MustCompile(`(?m)^[ \t]*type\s+([A-Za-z_][A-Za-z0-9_]*)\s+(struct|interface)\b`)
	goMethodPattern  = regexp.MustCompile(`(?m)^[ \t]*func\s*\(\s*[^)]*?\*?\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)`)
	goFuncPattern    = regexp.MustCompile(`(?m)^[ \t]*func\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)`)
	goFieldPattern   = regexp.MustCompile(`(?m)^[ \t]*([A-Za-z_][A-Za-z0-9_]*)\s+[^=\n]+$`)
	goImportBlock    = regexp.MustCompile(`(?ms)^\s*import\s*\((.*?)\)`)
	goImportLine     = regexp.MustCompile(`(?m)^\s*import\s+([^\n]+)$`)

	pythonClassPattern  = regexp.MustCompile(`^([ \t]*)class\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:\([^)]*\))?\s*:`)
	pythonFuncPattern   = regexp.MustCompile(`^([ \t]*)def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*:`)
	pythonImportPattern = regexp.MustCompile(`(?m)^\s*(?:from\s+[^\n]+\s+import\s+[^\n]+|import\s+[^\n]+)\s*$`)

	jsClassPattern     = regexp.MustCompile(`(?m)^[ \t]*(?:export\s+default\s+|export\s+)?class\s+([A-Za-z_][A-Za-z0-9_]*)`)
	jsFunctionPattern  = regexp.MustCompile(`(?m)^[ \t]*(?:export\s+default\s+|export\s+)?function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(`)
	jsMethodPattern    = regexp.MustCompile(`(?m)^[ \t]*(?:public\s+|private\s+|protected\s+|static\s+|async\s+|readonly\s+|override\s+)*(?:get\s+|set\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\([^)]*\)\s*\{`)
	jsFieldPattern     = regexp.MustCompile(`(?m)^[ \t]*(?:public\s+|private\s+|protected\s+|static\s+|readonly\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=\n]+)?(?:=\s*[^;\n]+)?;`)
	jsImportPattern    = regexp.MustCompile(`(?m)^\s*import\s+[^\n;]+;?\s*$`)
	tsInterfacePattern = regexp.MustCompile(`(?m)^[ \t]*(?:export\s+)?interface\s+([A-Za-z_][A-Za-z0-9_]*)`)
	tsTypeAliasPattern = regexp.MustCompile(`(?m)^[ \t]*(?:export\s+)?type\s+([A-Za-z_][A-Za-z0-9_]*)\s*=`)

	csharpNamespacePattern = regexp.MustCompile(`(?m)^\s*namespace\s+([A-Za-z0-9_.]+)\s*[{;]`)
	csharpClassPattern     = regexp.MustCompile(`(?m)\b(class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)`)
	csharpMethodPattern    = regexp.MustCompile(`(?m)^[ \t]*(?:public|protected|private|internal|static|virtual|override|async|sealed|abstract|\s)+[A-Za-z0-9_<>\[\], ?]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^;\n]*)\)\s*\{`)
	csharpFieldPattern     = regexp.MustCompile(`(?m)^[ \t]*(?:public|protected|private|internal|static|readonly|volatile|\s)+[A-Za-z0-9_<>\[\], ?]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:=[^;\n]*)?;`)
	csharpImportPattern    = regexp.MustCompile(`(?m)^\s*using\s+[^;]+;`)

	rustTypePattern   = regexp.MustCompile(`(?m)^[ \t]*(?:pub\s+)?(struct|enum|trait)\s+([A-Za-z_][A-Za-z0-9_]*)`)
	rustImplPattern   = regexp.MustCompile(`(?m)^[ \t]*impl(?:<[^>]+>)?\s+([A-Za-z_][A-Za-z0-9_]*)`)
	rustMethodPattern = regexp.MustCompile(`(?m)^[ \t]*(?:pub\s+)?fn\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(`)
	rustFieldPattern  = regexp.MustCompile(`(?m)^[ \t]*(?:pub\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*:\s*[^,\n]+,?$`)
	rustUsePattern    = regexp.MustCompile(`(?m)^\s*use\s+[^;]+;`)

	phpNamespacePattern = regexp.MustCompile(`(?m)^\s*namespace\s+([A-Za-z0-9_\\]+)\s*;`)
	phpClassPattern     = regexp.MustCompile(`(?m)\b(class|interface|trait|enum)\s+([A-Za-z_][A-Za-z0-9_]*)`)
	phpMethodPattern    = regexp.MustCompile(`(?m)^[ \t]*(?:public|protected|private|static|final|abstract|\s)*function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(`)
	phpFieldPattern     = regexp.MustCompile(`(?m)^[ \t]*(?:public|protected|private|static|readonly|\s)*\$([A-Za-z_][A-Za-z0-9_]*)`)
	phpUsePattern       = regexp.MustCompile(`(?m)^\s*use\s+[^;]+;`)

	scalaPackagePattern = regexp.MustCompile(`(?m)^\s*package\s+([A-Za-z0-9_.]+)`)
	scalaClassPattern   = regexp.MustCompile(`(?m)\b(class|trait|object|enum)\s+([A-Za-z_][A-Za-z0-9_]*)`)
	scalaMethodPattern  = regexp.MustCompile(`(?m)^[ \t]*(?:override\s+|private\s+|protected\s+|final\s+|implicit\s+|lazy\s+)*def\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:\[[^\]]+\])?\s*\(`)
	scalaFieldPattern   = regexp.MustCompile(`(?m)^[ \t]*(?:private\s+|protected\s+|final\s+|lazy\s+)?(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)`)
	scalaImportPattern  = regexp.MustCompile(`(?m)^\s*import\s+[^\n]+$`)

	sqlCreatePattern = regexp.MustCompile(`(?im)\bcreate\s+(?:or\s+replace\s+)?(table|view|function|procedure|trigger)\s+([A-Za-z_][A-Za-z0-9_.]*)`)
)

func New(workspace string) *Service {
	return &Service{workspace: workspace}
}

func (s *Service) CompleteSymbols(query string, limit int) []Completion {
	query = strings.TrimSpace(query)
	if query == "" || limit <= 0 || len(query) < 2 {
		return []Completion{}
	}

	index, err := s.getIndex()
	if err != nil {
		return []Completion{}
	}

	candidates := s.autocompleteDefinitions(index, query)
	if !isHierarchicalQuery(query) && len(candidates) > 0 {
		candidates = s.enhanceWithParentClasses(index, query, candidates)
	}

	scored := scoreCompletionCandidates(query, candidates)
	results := make([]Completion, 0, min(limit, len(scored)))
	seen := map[string]struct{}{}
	for _, item := range scored {
		key := item.symbol.Kind + ":" + item.symbol.FQName + ":" + item.symbol.Signature
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		results = append(results, Completion{
			Type:   item.symbol.Kind,
			Name:   item.symbol.ShortName,
			Detail: item.symbol.FQName,
		})
		if len(results) >= limit {
			break
		}
	}
	return results
}

func (s *Service) FindClasses(names []string) []Symbol {
	return s.ResolveClasses(names)
}

func (s *Service) FindMethods(names []string) []Symbol {
	return s.ResolveMethods(names)
}

func (s *Service) ResolveClasses(names []string) []Symbol {
	index, err := s.getIndex()
	if err != nil {
		return []Symbol{}
	}

	results := make([]Symbol, 0, len(names))
	seen := map[string]struct{}{}
	for _, name := range names {
		matches := s.resolveClasses(index, strings.TrimSpace(name))
		for _, match := range matches {
			key := match.Kind + ":" + match.FQName + ":" + match.Signature
			if _, ok := seen[key]; ok {
				continue
			}
			seen[key] = struct{}{}
			results = append(results, match)
		}
	}
	return results
}

func (s *Service) ResolveMethods(names []string) []Symbol {
	index, err := s.getIndex()
	if err != nil {
		return []Symbol{}
	}

	results := make([]Symbol, 0, len(names))
	seen := map[string]struct{}{}
	for _, name := range names {
		matches := s.resolveMethods(index, strings.TrimSpace(name))
		for _, match := range matches {
			key := match.Kind + ":" + match.FQName + ":" + match.Signature
			if _, ok := seen[key]; ok {
				continue
			}
			seen[key] = struct{}{}
			results = append(results, match)
		}
	}
	return results
}

func (s *Service) Definitions(fqName string) []Symbol {
	index, err := s.getIndex()
	if err != nil {
		return []Symbol{}
	}
	return s.definitions(index, strings.TrimSpace(fqName))
}

func (s *Service) HasDefinition(fqName string, kind string) bool {
	for _, symbol := range s.Definitions(fqName) {
		if symbol.Kind == kind {
			return true
		}
	}
	return false
}

func (s *Service) DefinitionGroup(fqName string, kind string) (DefinitionGroup, bool) {
	definitions := filterByKind(s.Definitions(fqName), kind)
	if len(definitions) == 0 {
		return DefinitionGroup{}, false
	}
	first := definitions[0]
	return DefinitionGroup{
		FQName:       fqName,
		Kind:         kind,
		ShortName:    first.ShortName,
		RelativePath: first.RelativePath,
		Symbols:      definitions,
	}, true
}

func (s *Service) MembersInClass(fqName string) []Symbol {
	index, err := s.getIndex()
	if err != nil {
		return []Symbol{}
	}
	return s.membersInClass(index, fqName)
}

func (s *Service) RenderSymbol(symbol Symbol) string {
	body := renderSymbolBody(symbol)
	if strings.TrimSpace(body) == "" {
		return symbol.Snippet
	}

	imports := s.collectImports(symbol)
	if len(imports) == 0 {
		return body
	}
	return "<imports>\n" + strings.Join(imports, "\n") + "\n</imports>\n\n" + body
}

func (s *Service) RenderDefinitionGroup(group DefinitionGroup) string {
	if len(group.Symbols) == 0 {
		return ""
	}

	body := renderGroupedBody(group)
	if strings.TrimSpace(body) == "" {
		return ""
	}

	imports := s.collectImports(group.Symbols[0])
	if len(imports) == 0 {
		return body
	}
	return "<imports>\n" + strings.Join(imports, "\n") + "\n</imports>\n\n" + body
}

func (s *Service) SkeletonHeader(fqName string) (string, bool) {
	index, err := s.getIndex()
	if err != nil {
		return "", false
	}

	definitions := filterByKind(s.definitions(index, fqName), "class")
	if len(definitions) == 0 {
		return "", false
	}

	parts := make([]string, 0, len(definitions))
	for _, classSymbol := range definitions {
		header := formatClassSkeleton(classSymbol, s.membersInClass(index, classSymbol.FQName))
		if strings.TrimSpace(header) != "" {
			parts = append(parts, header)
		}
	}
	if len(parts) == 0 {
		return "", false
	}
	return strings.Join(parts, "\n\n"), true
}

func (s *Service) SearchSymbols(query string, limit int) []Symbol {
	query = strings.TrimSpace(query)
	if query == "" || limit <= 0 {
		return []Symbol{}
	}

	index, err := s.getIndex()
	if err != nil {
		return []Symbol{}
	}

	candidates := s.searchDefinitions(index, query, true)
	scored := make([]scoredSymbol, 0, len(candidates))
	for _, symbol := range candidates {
		score, ok := matchScore(query, symbol)
		if ok {
			scored = append(scored, scoredSymbol{symbol: symbol, score: score})
			continue
		}
		if strings.Contains(strings.ToLower(symbol.Snippet), strings.ToLower(query)) {
			scored = append(scored, scoredSymbol{symbol: symbol, score: 6})
		}
	}

	sort.Slice(scored, func(i, j int) bool {
		if scored[i].score != scored[j].score {
			return scored[i].score < scored[j].score
		}
		if symbolKindRank(scored[i].symbol.Kind) != symbolKindRank(scored[j].symbol.Kind) {
			return symbolKindRank(scored[i].symbol.Kind) < symbolKindRank(scored[j].symbol.Kind)
		}
		if len(scored[i].symbol.FQName) != len(scored[j].symbol.FQName) {
			return len(scored[i].symbol.FQName) < len(scored[j].symbol.FQName)
		}
		return strings.ToLower(scored[i].symbol.FQName) < strings.ToLower(scored[j].symbol.FQName)
	})

	results := make([]Symbol, 0, min(limit, len(scored)))
	seen := map[string]struct{}{}
	for _, item := range scored {
		key := item.symbol.Kind + ":" + item.symbol.FQName + ":" + item.symbol.Signature
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		results = append(results, item.symbol)
		if len(results) >= limit {
			break
		}
	}
	return results
}

func (s *Service) SymbolsForPaths(paths []string, limit int) []Symbol {
	if limit <= 0 || len(paths) == 0 {
		return []Symbol{}
	}

	index, err := s.getIndex()
	if err != nil {
		return []Symbol{}
	}

	allowed := make(map[string]struct{}, len(paths))
	for _, path := range paths {
		trimmed := filepath.ToSlash(strings.TrimSpace(path))
		if trimmed != "" {
			allowed[trimmed] = struct{}{}
		}
	}

	results := make([]Symbol, 0, min(limit, len(index.symbols)))
	seen := map[string]struct{}{}
	for _, symbol := range index.symbols {
		if _, ok := allowed[symbol.RelativePath]; !ok {
			continue
		}
		key := symbol.Kind + ":" + symbol.FQName + ":" + symbol.Signature
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		results = append(results, symbol)
		if len(results) >= limit {
			break
		}
	}
	return results
}

func (s *Service) getIndex() (*indexSnapshot, error) {
	stamps, err := s.collectStamps()
	if err != nil {
		return nil, err
	}

	s.mu.RLock()
	if s.index != nil && sameStamps(s.index.stamps, stamps) {
		cached := s.index
		s.mu.RUnlock()
		return cached, nil
	}
	s.mu.RUnlock()

	index, err := s.buildIndex(stamps)
	if err != nil {
		return nil, err
	}

	s.mu.Lock()
	s.index = index
	s.mu.Unlock()
	return index, nil
}

func (s *Service) collectStamps() ([]fileStamp, error) {
	stamps := make([]fileStamp, 0, 64)
	err := filepath.WalkDir(s.workspace, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() {
			name := strings.ToLower(d.Name())
			if name == ".git" || name == ".brokk" {
				return filepath.SkipDir
			}
			return nil
		}
		if !isAnalyzableExtension(filepath.Ext(path)) {
			return nil
		}

		info, err := d.Info()
		if err != nil {
			return nil
		}
		rel, err := filepath.Rel(s.workspace, path)
		if err != nil {
			return nil
		}
		stamps = append(stamps, fileStamp{
			relativePath: filepath.ToSlash(rel),
			size:         info.Size(),
			modUnixNano:  info.ModTime().UnixNano(),
		})
		return nil
	})
	if err != nil {
		return nil, err
	}

	sort.Slice(stamps, func(i, j int) bool {
		return stamps[i].relativePath < stamps[j].relativePath
	})
	return stamps, nil
}

func (s *Service) buildIndex(stamps []fileStamp) (*indexSnapshot, error) {
	symbols := make([]Symbol, 0, len(stamps)*4)
	for _, stamp := range stamps {
		absolutePath := filepath.Join(s.workspace, filepath.FromSlash(stamp.relativePath))
		content, err := os.ReadFile(absolutePath)
		if err != nil {
			continue
		}
		if extracted, ok := parseTreeSitterSymbols(stamp.relativePath, string(content)); ok {
			symbols = append(symbols, extracted...)
			continue
		}

		switch strings.ToLower(filepath.Ext(absolutePath)) {
		case ".java":
			symbols = append(symbols, parseJavaSymbols(stamp.relativePath, string(content))...)
		case ".go":
			symbols = append(symbols, parseGoSymbols(stamp.relativePath, string(content))...)
		case ".py":
			symbols = append(symbols, parsePythonSymbols(stamp.relativePath, string(content))...)
		case ".js", ".mjs", ".cjs", ".jsx", ".ts", ".tsx":
			symbols = append(symbols, parseJSTSSymbols(stamp.relativePath, string(content))...)
		case ".cs":
			symbols = append(symbols, parseCSharpSymbols(stamp.relativePath, string(content))...)
		case ".rs":
			symbols = append(symbols, parseRustSymbols(stamp.relativePath, string(content))...)
		case ".php", ".phtml", ".php3", ".php4", ".php5", ".phps":
			symbols = append(symbols, parsePHPSymbols(stamp.relativePath, string(content))...)
		case ".scala":
			symbols = append(symbols, parseScalaSymbols(stamp.relativePath, string(content))...)
		case ".sql":
			symbols = append(symbols, parseSQLSymbols(stamp.relativePath, string(content))...)
		}
	}

	byFQName := map[string][]int{}
	membersByClass := map[string][]int{}
	for i, symbol := range symbols {
		byFQName[symbol.FQName] = append(byFQName[symbol.FQName], i)
		if symbol.ParentFQName != "" {
			membersByClass[symbol.ParentFQName] = append(membersByClass[symbol.ParentFQName], i)
		}
	}

	return &indexSnapshot{
		stamps:         stamps,
		symbols:        symbols,
		byFQName:       byFQName,
		membersByClass: membersByClass,
	}, nil
}

func (s *Service) resolveClasses(index *indexSnapshot, input string) []Symbol {
	if input == "" {
		return []Symbol{}
	}

	matches := filterByKind(s.definitions(index, input), "class")
	if len(matches) == 0 {
		matches = filterByKind(s.searchDefinitions(index, input, true), "class")
	}
	if len(matches) == 0 {
		suffix := "." + input
		dollarSuffix := "$" + input
		matches = collectMatching(index.symbols, func(symbol Symbol) bool {
			return symbol.Kind == "class" &&
				(symbol.ShortName == input ||
					symbol.FQName == input ||
					strings.HasSuffix(symbol.FQName, suffix) ||
					strings.HasSuffix(symbol.FQName, dollarSuffix))
		})
	}
	return dedupeSymbols(matches)
}

func (s *Service) resolveMethods(index *indexSnapshot, input string) []Symbol {
	if input == "" {
		return []Symbol{}
	}

	matches := filterByKind(s.definitions(index, input), "function")
	if len(matches) == 0 {
		matches = filterByKind(s.searchDefinitions(index, input, true), "function")
	}
	if len(matches) == 0 {
		matches = filterByKind(s.autocompleteDefinitions(index, input), "function")
	}
	if len(matches) == 0 {
		suffix := "." + input
		dollarSuffix := "$" + input
		matches = collectMatching(index.symbols, func(symbol Symbol) bool {
			return symbol.Kind == "function" &&
				(symbol.ShortName == input ||
					symbol.FQName == input ||
					strings.HasSuffix(symbol.FQName, suffix) ||
					strings.HasSuffix(symbol.FQName, dollarSuffix))
		})
	}
	if len(matches) == 0 {
		suffix := "." + input
		dollarSuffix := "$" + input
		for _, classSymbol := range index.symbols {
			if classSymbol.Kind != "class" {
				continue
			}
			for _, member := range s.membersInClass(index, classSymbol.FQName) {
				if member.Kind != "function" {
					continue
				}
				if member.ShortName == input || member.FQName == input || strings.HasSuffix(member.FQName, suffix) || strings.HasSuffix(member.FQName, dollarSuffix) {
					matches = append(matches, member)
				}
			}
		}
	}
	return dedupeSymbols(matches)
}

func (s *Service) definitions(index *indexSnapshot, fqName string) []Symbol {
	indexes := index.byFQName[fqName]
	results := make([]Symbol, 0, len(indexes))
	for _, idx := range indexes {
		results = append(results, index.symbols[idx])
	}
	return results
}

func (s *Service) membersInClass(index *indexSnapshot, fqName string) []Symbol {
	indexes := index.membersByClass[fqName]
	results := make([]Symbol, 0, len(indexes))
	for _, idx := range indexes {
		results = append(results, index.symbols[idx])
	}
	return results
}

func (s *Service) searchDefinitions(index *indexSnapshot, pattern string, autoQuote bool) []Symbol {
	if pattern == "" {
		return []Symbol{}
	}

	prepared := pattern
	if autoQuote {
		if strings.Contains(prepared, ".*") {
			prepared = "(?i)" + prepared
		} else {
			prepared = "(?i).*?" + regexp.QuoteMeta(prepared) + ".*?"
		}
	}
	compiled, err := regexp.Compile(prepared)
	if err != nil {
		return []Symbol{}
	}
	return collectMatching(index.symbols, func(symbol Symbol) bool {
		return compiled.MatchString(symbol.FQName)
	})
}

func (s *Service) autocompleteDefinitions(index *indexSnapshot, query string) []Symbol {
	if query == "" {
		return []Symbol{}
	}

	baseResults := s.searchDefinitions(index, "(?i).*?"+regexp.QuoteMeta(query)+".*?", false)
	fuzzyResults := []Symbol{}
	if len(query) < 5 {
		var builder strings.Builder
		builder.WriteString("(?i).*?")
		for i := range query {
			builder.WriteString(regexp.QuoteMeta(string(query[i])))
			if i < len(query)-1 {
				builder.WriteString(".*?")
			}
		}
		builder.WriteString(".*?")
		fuzzyResults = s.searchDefinitions(index, builder.String(), false)
	}

	merged := make([]Symbol, 0, len(baseResults)+len(fuzzyResults))
	merged = append(merged, baseResults...)
	merged = append(merged, fuzzyResults...)
	merged = dedupeSymbols(merged)
	sort.SliceStable(merged, func(i, j int) bool {
		if symbolKindRank(merged[i].Kind) != symbolKindRank(merged[j].Kind) {
			return symbolKindRank(merged[i].Kind) < symbolKindRank(merged[j].Kind)
		}
		left := strings.ToLower(merged[i].FQName)
		right := strings.ToLower(merged[j].FQName)
		if left != right {
			return left < right
		}
		return strings.ToLower(merged[i].Signature) < strings.ToLower(merged[j].Signature)
	})
	return merged
}

func (s *Service) enhanceWithParentClasses(index *indexSnapshot, query string, candidates []Symbol) []Symbol {
	dedup := make([]Symbol, 0, len(candidates))
	seen := map[string]struct{}{}
	appendSymbol := func(symbol Symbol) {
		key := symbol.Kind + ":" + symbol.FQName + ":" + symbol.Signature
		if _, ok := seen[key]; ok {
			return
		}
		seen[key] = struct{}{}
		dedup = append(dedup, symbol)
	}

	for _, candidate := range candidates {
		appendSymbol(candidate)
	}

	for _, candidate := range candidates {
		shortName := candidate.ShortName
		separator := firstSeparator(shortName)
		if separator < 0 {
			continue
		}
		firstSegment := shortName[:separator]
		if !strings.EqualFold(firstSegment, query) {
			continue
		}
		parentShort := firstSegment
		parentFQName := parentShort
		if candidate.PackageName != "" {
			parentFQName = candidate.PackageName + "." + parentShort
		}
		for _, parent := range s.definitions(index, parentFQName) {
			if parent.Kind == "class" {
				appendSymbol(parent)
			}
		}
	}
	return dedup
}

func parseJavaSymbols(relativePath string, content string) []Symbol {
	pkg := ""
	if match := javaPackagePattern.FindStringSubmatch(content); len(match) > 1 {
		pkg = match[1]
	}

	classMatches := javaClassPattern.FindAllStringSubmatchIndex(content, -1)
	classDecls := make([]javaClassDecl, 0, len(classMatches))
	for _, match := range classMatches {
		name := content[match[4]:match[5]]
		start, end, snippet := extractBraceBlock(content, match[0])
		classDecls = append(classDecls, javaClassDecl{
			name:      name,
			start:     start,
			end:       end,
			startLine: lineNumberAt(content, start),
			endLine:   lineNumberAt(content, end),
			signature: extractSignature(content, start),
			snippet:   snippet,
		})
	}

	for i := range classDecls {
		parent := findEnclosingJavaClass(classDecls, i)
		if parent != nil {
			classDecls[i].parentFQ = parent.fqName
			classDecls[i].shortName = parent.shortName + "." + classDecls[i].name
		} else {
			classDecls[i].shortName = classDecls[i].name
		}
		classDecls[i].fqName = joinName(pkg, classDecls[i].shortName)
	}

	results := make([]Symbol, 0, len(classDecls)*3)
	for _, classDecl := range classDecls {
		results = append(results, Symbol{
			Kind:         "class",
			Language:     "java",
			FQName:       classDecl.fqName,
			ShortName:    classDecl.shortName,
			Identifier:   classDecl.shortName,
			PackageName:  pkg,
			ParentFQName: classDecl.parentFQ,
			RelativePath: relativePath,
			Signature:    classDecl.signature,
			Snippet:      classDecl.snippet,
			StartLine:    classDecl.startLine,
			EndLine:      classDecl.endLine,
			TopLevel:     classDecl.parentFQ == "",
		})
	}

	methodMatches := collectJavaMethods(content)
	for _, method := range methodMatches {
		parent := findEnclosingJavaClassByOffset(classDecls, method.start)
		if parent == nil {
			continue
		}
		fqName := parent.fqName + "." + method.name
		results = append(results, Symbol{
			Kind:         "function",
			Language:     "java",
			FQName:       fqName,
			ShortName:    method.name,
			Identifier:   method.name,
			PackageName:  pkg,
			ParentFQName: parent.fqName,
			RelativePath: relativePath,
			Signature:    method.signature,
			Snippet:      method.snippet,
			StartLine:    method.startLine,
			EndLine:      method.endLine,
			TopLevel:     false,
		})
	}

	for _, classDecl := range classDecls {
		bodyStart := strings.Index(content[classDecl.start:classDecl.end], "{")
		if bodyStart < 0 {
			continue
		}
		classBody := content[classDecl.start+bodyStart+1 : classDecl.end-1]
		classBodyOffset := classDecl.start + bodyStart + 1
		fieldMatches := javaFieldPattern.FindAllStringSubmatchIndex(classBody, -1)
		for _, match := range fieldMatches {
			name := classBody[match[2]:match[3]]
			if isControlKeyword(name) || methodLikeField(classBody[match[0]:match[1]]) {
				continue
			}
			start := classBodyOffset + match[0]
			end := classBodyOffset + match[1]
			if rangeContainedByAny(start, end, methodMatches) || rangeContainedByNestedClass(start, end, classDecls, classDecl.fqName) {
				continue
			}
			results = append(results, Symbol{
				Kind:         "field",
				Language:     "java",
				FQName:       classDecl.fqName + "." + name,
				ShortName:    name,
				Identifier:   name,
				PackageName:  pkg,
				ParentFQName: classDecl.fqName,
				RelativePath: relativePath,
				Signature:    strings.TrimSpace(classBody[match[0]:match[1]]),
				Snippet:      strings.TrimSpace(classBody[match[0]:match[1]]),
				StartLine:    lineNumberAt(content, start),
				EndLine:      lineNumberAt(content, end),
				TopLevel:     false,
			})
		}
	}

	return dedupeSymbols(results)
}

func parseGoSymbols(relativePath string, content string) []Symbol {
	pkg := ""
	if match := goPackagePattern.FindStringSubmatch(content); len(match) > 1 {
		pkg = match[1]
	}

	results := make([]Symbol, 0, 16)
	typeMatches := goTypePattern.FindAllStringSubmatchIndex(content, -1)
	typeDecls := make([]goTypeDecl, 0, len(typeMatches))
	for _, match := range typeMatches {
		name := content[match[2]:match[3]]
		kind := content[match[4]:match[5]]
		start, end, snippet := extractBraceBlock(content, match[0])
		signature := strings.TrimSpace(extractSignature(content, match[0]))
		typeDecls = append(typeDecls, goTypeDecl{
			name:      name,
			fqName:    joinName(pkg, name),
			kind:      kind,
			start:     start,
			end:       end,
			startLine: lineNumberAt(content, start),
			endLine:   lineNumberAt(content, end),
			signature: signature,
			snippet:   snippet,
		})
	}

	for _, decl := range typeDecls {
		results = append(results, Symbol{
			Kind:         "class",
			Language:     "go",
			FQName:       decl.fqName,
			ShortName:    decl.name,
			Identifier:   decl.name,
			PackageName:  pkg,
			ParentFQName: "",
			RelativePath: relativePath,
			Signature:    decl.signature,
			Snippet:      decl.snippet,
			StartLine:    decl.startLine,
			EndLine:      decl.endLine,
			TopLevel:     true,
		})
	}

	methodMatches := goMethodPattern.FindAllStringSubmatchIndex(content, -1)
	for _, match := range methodMatches {
		receiver := content[match[2]:match[3]]
		name := content[match[4]:match[5]]
		start, end, snippet := extractBraceBlock(content, match[0])
		results = append(results, Symbol{
			Kind:         "function",
			Language:     "go",
			FQName:       joinName(pkg, receiver) + "." + name,
			ShortName:    name,
			Identifier:   name,
			PackageName:  pkg,
			ParentFQName: joinName(pkg, receiver),
			RelativePath: relativePath,
			Signature:    strings.TrimSpace(extractSignature(content, match[0])),
			Snippet:      snippet,
			StartLine:    lineNumberAt(content, start),
			EndLine:      lineNumberAt(content, end),
			TopLevel:     false,
		})
	}

	funcMatches := goFuncPattern.FindAllStringSubmatchIndex(content, -1)
	for _, match := range funcMatches {
		line := content[match[0]:min(len(content), match[1])]
		if strings.HasPrefix(strings.TrimSpace(line), "func (") {
			continue
		}
		name := content[match[2]:match[3]]
		start, end, snippet := extractBraceBlock(content, match[0])
		results = append(results, Symbol{
			Kind:         "function",
			Language:     "go",
			FQName:       joinName(pkg, name),
			ShortName:    name,
			Identifier:   name,
			PackageName:  pkg,
			ParentFQName: "",
			RelativePath: relativePath,
			Signature:    strings.TrimSpace(extractSignature(content, match[0])),
			Snippet:      snippet,
			StartLine:    lineNumberAt(content, start),
			EndLine:      lineNumberAt(content, end),
			TopLevel:     true,
		})
	}

	for _, decl := range typeDecls {
		if decl.kind != "struct" {
			continue
		}
		bodyStart := strings.Index(content[decl.start:decl.end], "{")
		if bodyStart < 0 {
			continue
		}
		typeBody := content[decl.start+bodyStart+1 : decl.end-1]
		bodyOffset := decl.start + bodyStart + 1
		fieldMatches := goFieldPattern.FindAllStringSubmatchIndex(typeBody, -1)
		for _, match := range fieldMatches {
			name := typeBody[match[2]:match[3]]
			if strings.TrimSpace(name) == "" || isGoKeyword(name) {
				continue
			}
			start := bodyOffset + match[0]
			end := bodyOffset + match[1]
			signature := strings.TrimSpace(typeBody[match[0]:match[1]])
			results = append(results, Symbol{
				Kind:         "field",
				Language:     "go",
				FQName:       decl.fqName + "." + name,
				ShortName:    name,
				Identifier:   name,
				PackageName:  pkg,
				ParentFQName: decl.fqName,
				RelativePath: relativePath,
				Signature:    signature,
				Snippet:      signature,
				StartLine:    lineNumberAt(content, start),
				EndLine:      lineNumberAt(content, end),
				TopLevel:     false,
			})
		}
	}

	return dedupeSymbols(results)
}

func parsePythonSymbols(relativePath string, content string) []Symbol {
	moduleName := moduleNameFromPath(relativePath)
	lines := strings.Split(content, "\n")
	results := make([]Symbol, 0, 16)
	stack := make([]pythonScopedDecl, 0, 8)

	for lineIndex, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "#") {
			continue
		}

		if match := pythonClassPattern.FindStringSubmatch(line); len(match) > 2 {
			indent := indentationWidth(match[1])
			stack = trimPythonStack(stack, indent)
			name := match[2]
			parentFQ := ""
			shortName := name
			if len(stack) > 0 {
				parentFQ = stack[len(stack)-1].fqName
				shortName = stack[len(stack)-1].name + "." + name
			}
			fqName := joinName(moduleName, shortName)
			snippet, endLine := pythonBlock(lines, lineIndex, indent)
			stack = append(stack, pythonScopedDecl{
				name:     shortName,
				indent:   indent,
				fqName:   fqName,
				parentFQ: parentFQ,
			})
			results = append(results, Symbol{
				Kind:         "class",
				Language:     "python",
				FQName:       fqName,
				ShortName:    shortName,
				Identifier:   shortName,
				PackageName:  moduleName,
				ParentFQName: parentFQ,
				RelativePath: relativePath,
				Signature:    trimmed,
				Snippet:      snippet,
				StartLine:    lineIndex + 1,
				EndLine:      endLine,
				TopLevel:     parentFQ == "",
			})
			continue
		}

		if match := pythonFuncPattern.FindStringSubmatch(line); len(match) > 3 {
			indent := indentationWidth(match[1])
			stack = trimPythonStack(stack, indent)
			name := match[2]
			parentFQ := ""
			if len(stack) > 0 {
				parentFQ = stack[len(stack)-1].fqName
			}
			fqName := joinName(moduleName, name)
			if parentFQ != "" {
				fqName = parentFQ + "." + name
			}
			snippet, endLine := pythonBlock(lines, lineIndex, indent)
			results = append(results, Symbol{
				Kind:         "function",
				Language:     "python",
				FQName:       fqName,
				ShortName:    name,
				Identifier:   name,
				PackageName:  moduleName,
				ParentFQName: parentFQ,
				RelativePath: relativePath,
				Signature:    trimmed,
				Snippet:      snippet,
				StartLine:    lineIndex + 1,
				EndLine:      endLine,
				TopLevel:     parentFQ == "",
			})
		}
	}

	return dedupeSymbols(results)
}

func parseJSTSSymbols(relativePath string, content string) []Symbol {
	moduleName := moduleNameFromPath(relativePath)
	language := jsLanguageForPath(relativePath)
	results := make([]Symbol, 0, 16)
	classBlocks := make([]blockInfo, 0, 8)

	for _, match := range jsClassPattern.FindAllStringSubmatchIndex(content, -1) {
		name := content[match[2]:match[3]]
		start, end, snippet := extractBraceBlock(content, match[0])
		classBlocks = append(classBlocks, blockInfo{
			symbol: Symbol{
				Kind:         "class",
				Language:     language,
				FQName:       joinName(moduleName, name),
				ShortName:    name,
				Identifier:   name,
				PackageName:  moduleName,
				RelativePath: relativePath,
				Signature:    extractSignature(content, start),
				Snippet:      snippet,
				StartLine:    lineNumberAt(content, start),
				EndLine:      lineNumberAt(content, end),
				TopLevel:     true,
			},
			start: start,
			end:   end,
		})
	}
	for _, classBlock := range classBlocks {
		results = append(results, classBlock.symbol)
	}

	for _, match := range jsFunctionPattern.FindAllStringSubmatchIndex(content, -1) {
		name := content[match[2]:match[3]]
		start, end, snippet := extractBraceBlock(content, match[0])
		results = append(results, Symbol{
			Kind:         "function",
			Language:     language,
			FQName:       joinName(moduleName, name),
			ShortName:    name,
			Identifier:   name,
			PackageName:  moduleName,
			RelativePath: relativePath,
			Signature:    extractSignature(content, start),
			Snippet:      snippet,
			StartLine:    lineNumberAt(content, start),
			EndLine:      lineNumberAt(content, end),
			TopLevel:     true,
		})
	}

	for _, match := range jsMethodPattern.FindAllStringSubmatchIndex(content, -1) {
		name := content[match[2]:match[3]]
		if isJSKeyword(name) {
			continue
		}
		block := findEnclosingBlock(classBlocks, match[0])
		if block == nil {
			continue
		}
		start, end, snippet := extractBraceBlock(content, match[0])
		results = append(results, Symbol{
			Kind:         "function",
			Language:     language,
			FQName:       block.symbol.FQName + "." + name,
			ShortName:    name,
			Identifier:   name,
			PackageName:  moduleName,
			ParentFQName: block.symbol.FQName,
			RelativePath: relativePath,
			Signature:    extractSignature(content, start),
			Snippet:      snippet,
			StartLine:    lineNumberAt(content, start),
			EndLine:      lineNumberAt(content, end),
			TopLevel:     false,
		})
	}

	for _, match := range jsFieldPattern.FindAllStringSubmatchIndex(content, -1) {
		name := content[match[2]:match[3]]
		if isJSKeyword(name) {
			continue
		}
		block := findEnclosingBlock(classBlocks, match[0])
		if block == nil {
			continue
		}
		start := strings.LastIndex(content[:match[0]], "\n") + 1
		end := match[1]
		results = append(results, Symbol{
			Kind:         "field",
			Language:     language,
			FQName:       block.symbol.FQName + "." + name,
			ShortName:    name,
			Identifier:   name,
			PackageName:  moduleName,
			ParentFQName: block.symbol.FQName,
			RelativePath: relativePath,
			Signature:    strings.TrimSpace(content[match[0]:match[1]]),
			Snippet:      strings.TrimSpace(content[match[0]:match[1]]),
			StartLine:    lineNumberAt(content, start),
			EndLine:      lineNumberAt(content, end),
			TopLevel:     false,
		})
	}

	if language == "typescript" {
		for _, match := range tsInterfacePattern.FindAllStringSubmatchIndex(content, -1) {
			name := content[match[2]:match[3]]
			start, end, snippet := extractBraceBlock(content, match[0])
			results = append(results, Symbol{
				Kind:         "class",
				Language:     language,
				FQName:       joinName(moduleName, name),
				ShortName:    name,
				Identifier:   name,
				PackageName:  moduleName,
				RelativePath: relativePath,
				Signature:    extractSignature(content, start),
				Snippet:      snippet,
				StartLine:    lineNumberAt(content, start),
				EndLine:      lineNumberAt(content, end),
				TopLevel:     true,
			})
		}
		for _, match := range tsTypeAliasPattern.FindAllStringSubmatchIndex(content, -1) {
			name := content[match[2]:match[3]]
			start := strings.LastIndex(content[:match[0]], "\n") + 1
			signature := strings.TrimSpace(extractLineWindow(content, start))
			results = append(results, Symbol{
				Kind:         "class",
				Language:     language,
				FQName:       joinName(moduleName, name),
				ShortName:    name,
				Identifier:   name,
				PackageName:  moduleName,
				RelativePath: relativePath,
				Signature:    signature,
				Snippet:      signature,
				StartLine:    lineNumberAt(content, start),
				EndLine:      lineNumberAt(content, start),
				TopLevel:     true,
			})
		}
	}

	return dedupeSymbols(results)
}

func parseCSharpSymbols(relativePath string, content string) []Symbol {
	namespace := ""
	if match := csharpNamespacePattern.FindStringSubmatch(content); len(match) > 1 {
		namespace = match[1]
	}

	classMatches := csharpClassPattern.FindAllStringSubmatchIndex(content, -1)
	classBlocks := make([]blockInfo, 0, len(classMatches))
	for _, match := range classMatches {
		name := content[match[4]:match[5]]
		start, end, snippet := extractBraceBlock(content, match[0])
		classBlocks = append(classBlocks, blockInfo{
			symbol: Symbol{
				Kind:         "class",
				Language:     "csharp",
				FQName:       joinName(namespace, name),
				ShortName:    name,
				Identifier:   name,
				PackageName:  namespace,
				RelativePath: relativePath,
				Signature:    extractSignature(content, start),
				Snippet:      snippet,
				StartLine:    lineNumberAt(content, start),
				EndLine:      lineNumberAt(content, end),
				TopLevel:     true,
			},
			start: start,
			end:   end,
		})
	}

	results := make([]Symbol, 0, len(classBlocks)*3)
	for _, classBlock := range classBlocks {
		results = append(results, classBlock.symbol)
	}

	for _, match := range csharpMethodPattern.FindAllStringSubmatchIndex(content, -1) {
		name := content[match[2]:match[3]]
		if isControlKeyword(name) {
			continue
		}
		block := findEnclosingBlock(classBlocks, match[0])
		if block == nil {
			continue
		}
		start, end, snippet := extractBraceBlock(content, match[0])
		results = append(results, Symbol{
			Kind:         "function",
			Language:     "csharp",
			FQName:       block.symbol.FQName + "." + name,
			ShortName:    name,
			Identifier:   name,
			PackageName:  namespace,
			ParentFQName: block.symbol.FQName,
			RelativePath: relativePath,
			Signature:    extractSignature(content, start),
			Snippet:      snippet,
			StartLine:    lineNumberAt(content, start),
			EndLine:      lineNumberAt(content, end),
			TopLevel:     false,
		})
	}

	for _, match := range csharpFieldPattern.FindAllStringSubmatchIndex(content, -1) {
		name := content[match[2]:match[3]]
		block := findEnclosingBlock(classBlocks, match[0])
		if block == nil {
			continue
		}
		start := strings.LastIndex(content[:match[0]], "\n") + 1
		end := match[1]
		results = append(results, Symbol{
			Kind:         "field",
			Language:     "csharp",
			FQName:       block.symbol.FQName + "." + name,
			ShortName:    name,
			Identifier:   name,
			PackageName:  namespace,
			ParentFQName: block.symbol.FQName,
			RelativePath: relativePath,
			Signature:    strings.TrimSpace(content[match[0]:match[1]]),
			Snippet:      strings.TrimSpace(content[match[0]:match[1]]),
			StartLine:    lineNumberAt(content, start),
			EndLine:      lineNumberAt(content, end),
			TopLevel:     false,
		})
	}

	return dedupeSymbols(results)
}

func parseRustSymbols(relativePath string, content string) []Symbol {
	moduleName := moduleNameFromPath(relativePath)
	results := make([]Symbol, 0, 16)
	typeBlocks := make([]blockInfo, 0, 8)

	for _, match := range rustTypePattern.FindAllStringSubmatchIndex(content, -1) {
		name := content[match[4]:match[5]]
		start, end, snippet := extractBraceBlock(content, match[0])
		typeBlocks = append(typeBlocks, blockInfo{
			symbol: Symbol{
				Kind:         "class",
				Language:     "rust",
				FQName:       joinName(moduleName, name),
				ShortName:    name,
				Identifier:   name,
				PackageName:  moduleName,
				RelativePath: relativePath,
				Signature:    extractSignature(content, start),
				Snippet:      snippet,
				StartLine:    lineNumberAt(content, start),
				EndLine:      lineNumberAt(content, end),
				TopLevel:     true,
			},
			start: start,
			end:   end,
		})
	}
	for _, typeBlock := range typeBlocks {
		results = append(results, typeBlock.symbol)
	}

	implBlocks := make([]blockInfo, 0, 8)
	for _, match := range rustImplPattern.FindAllStringSubmatchIndex(content, -1) {
		typeName := content[match[2]:match[3]]
		start, end, _ := extractBraceBlock(content, match[0])
		implBlocks = append(implBlocks, blockInfo{
			symbol: Symbol{FQName: joinName(moduleName, typeName)},
			start:  start,
			end:    end,
		})
	}

	for _, match := range rustMethodPattern.FindAllStringSubmatchIndex(content, -1) {
		name := content[match[2]:match[3]]
		start, end, snippet := extractBraceBlock(content, match[0])
		parent := findEnclosingBlock(implBlocks, match[0])
		parentFQ := ""
		if parent != nil {
			parentFQ = parent.symbol.FQName
		}
		fqName := joinName(moduleName, name)
		if parentFQ != "" {
			fqName = parentFQ + "." + name
		}
		results = append(results, Symbol{
			Kind:         "function",
			Language:     "rust",
			FQName:       fqName,
			ShortName:    name,
			Identifier:   name,
			PackageName:  moduleName,
			ParentFQName: parentFQ,
			RelativePath: relativePath,
			Signature:    extractSignature(content, start),
			Snippet:      snippet,
			StartLine:    lineNumberAt(content, start),
			EndLine:      lineNumberAt(content, end),
			TopLevel:     parentFQ == "",
		})
	}

	for _, typeBlock := range typeBlocks {
		fieldMatches := rustFieldPattern.FindAllStringSubmatchIndex(typeBlock.symbol.Snippet, -1)
		for _, match := range fieldMatches {
			name := typeBlock.symbol.Snippet[match[2]:match[3]]
			if isRustKeyword(name) {
				continue
			}
			results = append(results, Symbol{
				Kind:         "field",
				Language:     "rust",
				FQName:       typeBlock.symbol.FQName + "." + name,
				ShortName:    name,
				Identifier:   name,
				PackageName:  moduleName,
				ParentFQName: typeBlock.symbol.FQName,
				RelativePath: relativePath,
				Signature:    strings.TrimSpace(typeBlock.symbol.Snippet[match[0]:match[1]]),
				Snippet:      strings.TrimSpace(typeBlock.symbol.Snippet[match[0]:match[1]]),
				TopLevel:     false,
			})
		}
	}

	return dedupeSymbols(results)
}

func parsePHPSymbols(relativePath string, content string) []Symbol {
	namespace := ""
	if match := phpNamespacePattern.FindStringSubmatch(content); len(match) > 1 {
		namespace = strings.ReplaceAll(match[1], "\\", ".")
	}

	classMatches := phpClassPattern.FindAllStringSubmatchIndex(content, -1)
	classBlocks := make([]blockInfo, 0, len(classMatches))
	for _, match := range classMatches {
		name := content[match[4]:match[5]]
		start, end, snippet := extractBraceBlock(content, match[0])
		classBlocks = append(classBlocks, blockInfo{
			symbol: Symbol{
				Kind:         "class",
				Language:     "php",
				FQName:       joinName(namespace, name),
				ShortName:    name,
				Identifier:   name,
				PackageName:  namespace,
				RelativePath: relativePath,
				Signature:    extractSignature(content, start),
				Snippet:      snippet,
				StartLine:    lineNumberAt(content, start),
				EndLine:      lineNumberAt(content, end),
				TopLevel:     true,
			},
			start: start,
			end:   end,
		})
	}

	results := make([]Symbol, 0, len(classBlocks)*3)
	for _, classBlock := range classBlocks {
		results = append(results, classBlock.symbol)
	}

	for _, match := range phpMethodPattern.FindAllStringSubmatchIndex(content, -1) {
		name := content[match[2]:match[3]]
		block := findEnclosingBlock(classBlocks, match[0])
		if block == nil {
			continue
		}
		start, end, snippet := extractBraceBlock(content, match[0])
		results = append(results, Symbol{
			Kind:         "function",
			Language:     "php",
			FQName:       block.symbol.FQName + "." + name,
			ShortName:    name,
			Identifier:   name,
			PackageName:  namespace,
			ParentFQName: block.symbol.FQName,
			RelativePath: relativePath,
			Signature:    extractSignature(content, start),
			Snippet:      snippet,
			StartLine:    lineNumberAt(content, start),
			EndLine:      lineNumberAt(content, end),
			TopLevel:     false,
		})
	}

	for _, match := range phpFieldPattern.FindAllStringSubmatchIndex(content, -1) {
		name := content[match[2]:match[3]]
		block := findEnclosingBlock(classBlocks, match[0])
		if block == nil {
			continue
		}
		start := strings.LastIndex(content[:match[0]], "\n") + 1
		end := match[1]
		results = append(results, Symbol{
			Kind:         "field",
			Language:     "php",
			FQName:       block.symbol.FQName + "." + name,
			ShortName:    name,
			Identifier:   name,
			PackageName:  namespace,
			ParentFQName: block.symbol.FQName,
			RelativePath: relativePath,
			Signature:    strings.TrimSpace(content[match[0]:match[1]]),
			Snippet:      strings.TrimSpace(content[match[0]:match[1]]),
			StartLine:    lineNumberAt(content, start),
			EndLine:      lineNumberAt(content, end),
			TopLevel:     false,
		})
	}

	return dedupeSymbols(results)
}

func parseScalaSymbols(relativePath string, content string) []Symbol {
	pkg := ""
	if match := scalaPackagePattern.FindStringSubmatch(content); len(match) > 1 {
		pkg = match[1]
	}

	classMatches := scalaClassPattern.FindAllStringSubmatchIndex(content, -1)
	classBlocks := make([]blockInfo, 0, len(classMatches))
	for _, match := range classMatches {
		name := content[match[4]:match[5]]
		start, end, snippet := extractBraceBlock(content, match[0])
		classBlocks = append(classBlocks, blockInfo{
			symbol: Symbol{
				Kind:         "class",
				Language:     "scala",
				FQName:       joinName(pkg, name),
				ShortName:    name,
				Identifier:   name,
				PackageName:  pkg,
				RelativePath: relativePath,
				Signature:    extractSignature(content, start),
				Snippet:      snippet,
				StartLine:    lineNumberAt(content, start),
				EndLine:      lineNumberAt(content, end),
				TopLevel:     true,
			},
			start: start,
			end:   end,
		})
	}

	results := make([]Symbol, 0, len(classBlocks)*3)
	for _, classBlock := range classBlocks {
		results = append(results, classBlock.symbol)
	}

	for _, match := range scalaMethodPattern.FindAllStringSubmatchIndex(content, -1) {
		name := content[match[2]:match[3]]
		block := findEnclosingBlock(classBlocks, match[0])
		parentFQ := ""
		fqName := joinName(pkg, name)
		if block != nil {
			parentFQ = block.symbol.FQName
			fqName = parentFQ + "." + name
		}
		startLine := strings.LastIndex(content[:match[0]], "\n") + 1
		snippet := extractLineWindow(content, startLine)
		results = append(results, Symbol{
			Kind:         "function",
			Language:     "scala",
			FQName:       fqName,
			ShortName:    name,
			Identifier:   name,
			PackageName:  pkg,
			ParentFQName: parentFQ,
			RelativePath: relativePath,
			Signature:    strings.TrimSpace(firstLine(snippet)),
			Snippet:      snippet,
			StartLine:    lineNumberAt(content, startLine),
			EndLine:      lineNumberAt(content, startLine),
			TopLevel:     parentFQ == "",
		})
	}

	for _, match := range scalaFieldPattern.FindAllStringSubmatchIndex(content, -1) {
		name := content[match[2]:match[3]]
		block := findEnclosingBlock(classBlocks, match[0])
		if block == nil {
			continue
		}
		start := strings.LastIndex(content[:match[0]], "\n") + 1
		end := match[1]
		results = append(results, Symbol{
			Kind:         "field",
			Language:     "scala",
			FQName:       block.symbol.FQName + "." + name,
			ShortName:    name,
			Identifier:   name,
			PackageName:  pkg,
			ParentFQName: block.symbol.FQName,
			RelativePath: relativePath,
			Signature:    strings.TrimSpace(content[match[0]:match[1]]),
			Snippet:      strings.TrimSpace(content[match[0]:match[1]]),
			StartLine:    lineNumberAt(content, start),
			EndLine:      lineNumberAt(content, end),
			TopLevel:     false,
		})
	}

	return dedupeSymbols(results)
}

func parseSQLSymbols(relativePath string, content string) []Symbol {
	moduleName := moduleNameFromPath(relativePath)
	results := make([]Symbol, 0, 8)
	for _, match := range sqlCreatePattern.FindAllStringSubmatchIndex(content, -1) {
		kind := strings.ToLower(content[match[2]:match[3]])
		name := content[match[4]:match[5]]
		start := strings.LastIndex(content[:match[0]], "\n") + 1
		snippet := extractLineWindow(content, start)
		symbolKind := "class"
		if kind == "function" || kind == "procedure" || kind == "trigger" {
			symbolKind = "function"
		}
		results = append(results, Symbol{
			Kind:         symbolKind,
			Language:     "sql",
			FQName:       joinName(moduleName, name),
			ShortName:    name,
			Identifier:   name,
			PackageName:  moduleName,
			RelativePath: relativePath,
			Signature:    strings.TrimSpace(firstLine(snippet)),
			Snippet:      snippet,
			StartLine:    lineNumberAt(content, start),
			EndLine:      lineNumberAt(content, start),
			TopLevel:     true,
		})
	}
	return dedupeSymbols(results)
}

func collectJavaMethods(content string) []javaMethodDecl {
	results := make([]javaMethodDecl, 0, 16)

	methodMatches := javaMethodPattern.FindAllStringSubmatchIndex(content, -1)
	for _, match := range methodMatches {
		name := content[match[4]:match[5]]
		if isControlKeyword(name) {
			continue
		}
		start, end, snippet := extractBraceBlock(content, match[0])
		signature := strings.TrimSpace(extractSignature(content, match[0]))
		results = append(results, javaMethodDecl{
			name:      name,
			signature: signature,
			start:     start,
			end:       end,
			startLine: lineNumberAt(content, start),
			endLine:   lineNumberAt(content, end),
			snippet:   snippet,
		})
	}

	ctorMatches := javaCtorPattern.FindAllStringSubmatchIndex(content, -1)
	for _, match := range ctorMatches {
		name := content[match[2]:match[3]]
		if isControlKeyword(name) {
			continue
		}
		start, end, snippet := extractBraceBlock(content, match[0])
		signature := strings.TrimSpace(extractSignature(content, match[0]))
		results = append(results, javaMethodDecl{
			name:      name,
			signature: signature,
			start:     start,
			end:       end,
			startLine: lineNumberAt(content, start),
			endLine:   lineNumberAt(content, end),
			snippet:   snippet,
		})
	}

	return results
}

func rangeContainedByAny(start int, end int, methods []javaMethodDecl) bool {
	for _, method := range methods {
		if start >= method.start && end <= method.end {
			return true
		}
	}
	return false
}

func rangeContainedByNestedClass(start int, end int, classes []javaClassDecl, ownerFQName string) bool {
	for _, classDecl := range classes {
		if classDecl.parentFQ != ownerFQName {
			continue
		}
		if start >= classDecl.start && end <= classDecl.end {
			return true
		}
	}
	return false
}

func extractBraceBlock(content string, start int) (int, int, string) {
	lineStart := strings.LastIndex(content[:start], "\n") + 1
	openOffset := strings.Index(content[start:], "{")
	if openOffset == -1 {
		return lineStart, lineStart, extractLineWindow(content, lineStart)
	}
	openIndex := start + openOffset
	depth := 0
	for i := openIndex; i < len(content); i++ {
		switch content[i] {
		case '{':
			depth++
		case '}':
			depth--
			if depth == 0 {
				return lineStart, i + 1, strings.TrimSpace(content[lineStart : i+1])
			}
		}
	}
	return lineStart, len(content), extractLineWindow(content, lineStart)
}

func extractLineWindow(content string, start int) string {
	lines := strings.Split(content[start:], "\n")
	if len(lines) > 16 {
		lines = lines[:16]
	}
	return strings.TrimSpace(strings.Join(lines, "\n"))
}

func extractSignature(content string, start int) string {
	openOffset := strings.Index(content[start:], "{")
	if openOffset == -1 {
		lineEnd := strings.Index(content[start:], "\n")
		if lineEnd == -1 {
			return strings.TrimSpace(content[start:])
		}
		return strings.TrimSpace(content[start : start+lineEnd])
	}
	return strings.TrimSpace(content[start : start+openOffset])
}

func lineNumberAt(content string, offset int) int {
	if offset <= 0 {
		return 1
	}
	if offset > len(content) {
		offset = len(content)
	}
	return 1 + strings.Count(content[:offset], "\n")
}

func findEnclosingJavaClass(classes []javaClassDecl, current int) *javaClassDecl {
	return findEnclosingJavaClassByOffset(classes, classes[current].start)
}

func findEnclosingJavaClassByOffset(classes []javaClassDecl, offset int) *javaClassDecl {
	var best *javaClassDecl
	for i := range classes {
		if offset <= classes[i].start || offset > classes[i].end {
			continue
		}
		if best == nil || classes[i].start > best.start {
			best = &classes[i]
		}
	}
	return best
}

func findEnclosingBlock(blocks []blockInfo, offset int) *blockInfo {
	var best *blockInfo
	for i := range blocks {
		if offset <= blocks[i].start || offset > blocks[i].end {
			continue
		}
		if best == nil || blocks[i].start > best.start {
			best = &blocks[i]
		}
	}
	return best
}

func scoreCompletionCandidates(query string, candidates []Symbol) []scoredSymbol {
	hierarchicalQuery := isHierarchicalQuery(query)
	matcher := newFuzzyMatcher(query)
	scored := make([]scoredSymbol, 0, len(candidates))
	for _, candidate := range candidates {
		identifier := candidate.Identifier
		if hierarchicalQuery {
			identifier = candidate.FQName
		}
		identifierScore := matcher.score(identifier)
		shortNameScore := math.MaxInt
		if !hierarchicalQuery && candidate.Kind == "class" {
			shortNameScore = matcher.score(candidate.ShortName)
		}
		baseScore := identifierScore
		if shortNameScore < baseScore {
			baseScore = shortNameScore
		}
		if baseScore == math.MaxInt {
			continue
		}

		typeBonus := 0
		if candidate.Kind == "class" {
			typeBonus = -classTypeBonus
		}
		depthPenalty := strings.Count(candidate.FQName, ".") * packageDepthPenalty
		finalScore := baseScore + typeBonus + depthPenalty
		scored = append(scored, scoredSymbol{symbol: candidate, score: finalScore})
	}

	sort.SliceStable(scored, func(i, j int) bool {
		if scored[i].score != scored[j].score {
			return scored[i].score < scored[j].score
		}
		if len(scored[i].symbol.FQName) != len(scored[j].symbol.FQName) {
			return len(scored[i].symbol.FQName) < len(scored[j].symbol.FQName)
		}
		if len(scored[i].symbol.ShortName) != len(scored[j].symbol.ShortName) {
			return len(scored[i].symbol.ShortName) < len(scored[j].symbol.ShortName)
		}
		leftFQName := strings.ToLower(scored[i].symbol.FQName)
		rightFQName := strings.ToLower(scored[j].symbol.FQName)
		if leftFQName != rightFQName {
			return leftFQName < rightFQName
		}
		return strings.ToLower(scored[i].symbol.Signature) < strings.ToLower(scored[j].symbol.Signature)
	})
	return scored
}

func renderSymbolBody(symbol Symbol) string {
	fileAttr := filepath.ToSlash(symbol.RelativePath)
	switch symbol.Kind {
	case "function":
		owner := symbol.ParentFQName
		if owner == "" {
			owner = symbol.PackageName
		}
		return fmt.Sprintf("<methods class=\"%s\" file=\"%s\">\n%s\n</methods>", owner, fileAttr, strings.TrimSpace(symbol.Snippet))
	case "class":
		return fmt.Sprintf("<class file=\"%s\">\n%s\n</class>", fileAttr, strings.TrimSpace(symbol.Snippet))
	case "field":
		return fmt.Sprintf("<class file=\"%s\">\n%s\n</class>", fileAttr, strings.TrimSpace(symbol.Snippet))
	default:
		return strings.TrimSpace(symbol.Snippet)
	}
}

func renderGroupedBody(group DefinitionGroup) string {
	switch group.Kind {
	case "function":
		type methodBucket struct {
			owner string
			path  string
			parts []string
		}
		buckets := make([]methodBucket, 0, 1)
		bucketIndex := map[string]int{}
		for _, symbol := range group.Symbols {
			owner := symbol.ParentFQName
			if owner == "" {
				owner = symbol.PackageName
			}
			path := filepath.ToSlash(symbol.RelativePath)
			key := owner + "::" + path
			idx, ok := bucketIndex[key]
			if !ok {
				idx = len(buckets)
				bucketIndex[key] = idx
				buckets = append(buckets, methodBucket{owner: owner, path: path})
			}
			buckets[idx].parts = append(buckets[idx].parts, strings.TrimSpace(symbol.Snippet))
		}

		blocks := make([]string, 0, len(buckets))
		for _, bucket := range buckets {
			blocks = append(blocks, fmt.Sprintf(
				"<methods class=\"%s\" file=\"%s\">\n%s\n</methods>",
				bucket.owner,
				bucket.path,
				strings.Join(bucket.parts, "\n\n"),
			))
		}
		return strings.Join(blocks, "\n\n")
	case "class":
		blocks := make([]string, 0, len(group.Symbols))
		for _, symbol := range group.Symbols {
			blocks = append(blocks, renderSymbolBody(symbol))
		}
		return strings.Join(blocks, "\n\n")
	default:
		parts := make([]string, 0, len(group.Symbols))
		for _, symbol := range group.Symbols {
			parts = append(parts, strings.TrimSpace(symbol.Snippet))
		}
		return strings.Join(parts, "\n\n")
	}
}

func formatClassSkeleton(classSymbol Symbol, members []Symbol) string {
	switch classSymbol.Language {
	case "python":
		return formatPythonSkeleton(classSymbol, members)
	default:
		return formatBraceLanguageSkeleton(classSymbol, members)
	}
}

func firstLine(value string) string {
	if idx := strings.IndexByte(value, '\n'); idx >= 0 {
		return value[:idx]
	}
	return value
}

func formatBraceLanguageSkeleton(classSymbol Symbol, members []Symbol) string {
	opening := strings.TrimSpace(classSymbol.Signature)
	if opening == "" {
		opening = strings.TrimSpace(firstLine(classSymbol.Snippet))
	}
	if !strings.HasSuffix(opening, "{") {
		opening += " {"
	}

	lines := []string{opening}
	for _, member := range members {
		switch member.Kind {
		case "field":
			lines = append(lines, "    "+strings.TrimSpace(strings.TrimSuffix(member.Signature, "{")))
		case "class":
			nested := strings.TrimSpace(strings.TrimSuffix(member.Signature, "{"))
			if nested != "" {
				lines = append(lines, "    "+nested+" { ... }")
			}
		}
	}
	lines = append(lines, "}")
	return strings.Join(lines, "\n")
}

func formatPythonSkeleton(classSymbol Symbol, members []Symbol) string {
	opening := strings.TrimSpace(classSymbol.Signature)
	if opening == "" {
		opening = strings.TrimSpace(firstLine(classSymbol.Snippet))
	}
	lines := []string{opening}
	for _, member := range members {
		if member.Kind != "class" {
			continue
		}
		nested := strings.TrimSpace(member.Signature)
		if nested != "" {
			lines = append(lines, "    "+nested)
			lines = append(lines, "        ...")
		}
	}
	return strings.Join(lines, "\n")
}

func (s *Service) collectImports(symbol Symbol) []string {
	absolutePath := filepath.Join(s.workspace, filepath.FromSlash(symbol.RelativePath))
	content, err := os.ReadFile(absolutePath)
	if err != nil {
		return nil
	}
	switch symbol.Language {
	case "java":
		return javaImportPattern.FindAllString(strings.TrimSpace(string(content)), -1)
	case "go":
		return collectGoImports(string(content))
	case "csharp":
		return csharpImportPattern.FindAllString(strings.TrimSpace(string(content)), -1)
	case "php":
		return phpUsePattern.FindAllString(strings.TrimSpace(string(content)), -1)
	case "python":
		return pythonImportPattern.FindAllString(strings.TrimSpace(string(content)), -1)
	case "javascript", "typescript":
		return jsImportPattern.FindAllString(strings.TrimSpace(string(content)), -1)
	case "scala":
		return scalaImportPattern.FindAllString(strings.TrimSpace(string(content)), -1)
	case "rust":
		return rustUsePattern.FindAllString(strings.TrimSpace(string(content)), -1)
	default:
		return nil
	}
}

func collectGoImports(content string) []string {
	imports := make([]string, 0, 8)
	if match := goImportBlock.FindStringSubmatch(content); len(match) > 1 {
		for _, line := range strings.Split(match[1], "\n") {
			trimmed := strings.TrimSpace(line)
			if trimmed != "" {
				imports = append(imports, "import "+trimmed)
			}
		}
		return imports
	}
	for _, line := range goImportLine.FindAllString(content, -1) {
		trimmed := strings.TrimSpace(line)
		if trimmed != "" {
			imports = append(imports, trimmed)
		}
	}
	return imports
}

func fuzzyScore(query string, candidate string) int {
	return newFuzzyMatcher(query).score(candidate)
}

func matchScore(query string, symbol Symbol) (int, bool) {
	q := strings.ToLower(strings.TrimSpace(query))
	shortName := strings.ToLower(symbol.ShortName)
	fqName := strings.ToLower(symbol.FQName)
	identifier := strings.ToLower(symbol.Identifier)

	switch {
	case shortName == q || fqName == q || identifier == q:
		return 0, true
	case strings.HasPrefix(shortName, q) || strings.HasPrefix(identifier, q):
		return 1, true
	case strings.HasPrefix(fqName, q):
		return 2, true
	case strings.Contains(shortName, q) || strings.Contains(identifier, q):
		return 3, true
	case strings.Contains(fqName, q):
		return 4, true
	default:
		return 0, false
	}
}

func symbolKindRank(kind string) int {
	switch kind {
	case "class":
		return 0
	case "function":
		return 1
	case "field":
		return 2
	default:
		return 3
	}
}

func filterByKind(symbols []Symbol, kind string) []Symbol {
	return collectMatching(symbols, func(symbol Symbol) bool {
		return symbol.Kind == kind
	})
}

func collectMatching(symbols []Symbol, keep func(Symbol) bool) []Symbol {
	results := make([]Symbol, 0, len(symbols))
	for _, symbol := range symbols {
		if keep(symbol) {
			results = append(results, symbol)
		}
	}
	return results
}

func dedupeSymbols(symbols []Symbol) []Symbol {
	results := make([]Symbol, 0, len(symbols))
	seen := map[string]struct{}{}
	for _, symbol := range symbols {
		key := symbol.Kind + ":" + symbol.FQName + ":" + symbol.Signature
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		results = append(results, symbol)
	}
	return results
}

func isHierarchicalQuery(query string) bool {
	return strings.Contains(query, ".") || strings.Contains(query, "$")
}

func firstSeparator(value string) int {
	dot := strings.Index(value, ".")
	dollar := strings.Index(value, "$")
	if dot >= 0 && dollar >= 0 {
		if dot < dollar {
			return dot
		}
		return dollar
	}
	if dot >= 0 {
		return dot
	}
	return dollar
}

func sameStamps(left []fileStamp, right []fileStamp) bool {
	if len(left) != len(right) {
		return false
	}
	for i := range left {
		if left[i] != right[i] {
			return false
		}
	}
	return true
}

func isAnalyzableExtension(ext string) bool {
	switch strings.ToLower(ext) {
	case ".java", ".go", ".py", ".js", ".mjs", ".cjs", ".jsx", ".ts", ".tsx", ".cs", ".rs", ".php", ".phtml", ".php3", ".php4", ".php5", ".phps", ".scala", ".sql":
		return true
	default:
		return false
	}
}

func joinName(parts ...string) string {
	nonEmpty := make([]string, 0, len(parts))
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if part != "" {
			nonEmpty = append(nonEmpty, part)
		}
	}
	return strings.Join(nonEmpty, ".")
}

func isControlKeyword(name string) bool {
	switch name {
	case "if", "for", "while", "switch", "catch", "return", "new", "throw":
		return true
	default:
		return false
	}
}

func methodLikeField(signature string) bool {
	return strings.Contains(signature, "(")
}

func isGoKeyword(name string) bool {
	switch name {
	case "type", "func", "map", "chan", "struct", "interface", "return":
		return true
	default:
		return false
	}
}

func isJSKeyword(name string) bool {
	switch name {
	case "if", "for", "while", "switch", "catch", "constructor", "return", "new":
		return true
	default:
		return false
	}
}

func isRustKeyword(name string) bool {
	switch name {
	case "pub", "fn", "struct", "enum", "trait", "impl", "use", "mod", "self", "Self", "crate":
		return true
	default:
		return false
	}
}

func moduleNameFromPath(relativePath string) string {
	normalized := filepath.ToSlash(relativePath)
	withoutExt := strings.TrimSuffix(normalized, filepath.Ext(normalized))
	parts := strings.Split(withoutExt, "/")
	filtered := make([]string, 0, len(parts))
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if part != "" {
			filtered = append(filtered, part)
		}
	}
	return strings.Join(filtered, ".")
}

func jsLanguageForPath(relativePath string) string {
	switch strings.ToLower(filepath.Ext(relativePath)) {
	case ".ts", ".tsx":
		return "typescript"
	default:
		return "javascript"
	}
}

func indentationWidth(value string) int {
	width := 0
	for _, ch := range value {
		if ch == '\t' {
			width += 4
		} else {
			width++
		}
	}
	return width
}

func trimPythonStack(stack []pythonScopedDecl, indent int) []pythonScopedDecl {
	for len(stack) > 0 && stack[len(stack)-1].indent >= indent {
		stack = stack[:len(stack)-1]
	}
	return stack
}

func pythonBlock(lines []string, startLine int, indent int) (string, int) {
	endLine := len(lines)
	for i := startLine + 1; i < len(lines); i++ {
		trimmed := strings.TrimSpace(lines[i])
		if trimmed == "" {
			continue
		}
		lineIndent := indentationWidth(lines[i][:len(lines[i])-len(strings.TrimLeft(lines[i], " \t"))])
		if lineIndent <= indent {
			endLine = i
			break
		}
	}
	return strings.TrimSpace(strings.Join(lines[startLine:endLine], "\n")), endLine
}

func min(a int, b int) int {
	if a < b {
		return a
	}
	return b
}
