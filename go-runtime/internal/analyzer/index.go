package analyzer

import (
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

type Service struct {
	workspace string
}

type Symbol struct {
	Kind         string
	FQName       string
	ShortName    string
	RelativePath string
	Snippet      string
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

var (
	javaPackagePattern = regexp.MustCompile(`(?m)^\s*package\s+([A-Za-z0-9_.]+)\s*;`)
	javaClassPattern   = regexp.MustCompile(`(?m)\b(class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)`)
	javaMethodPattern  = regexp.MustCompile(`(?m)^[ \t]*(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp|\s)+[A-Za-z0-9_<>\[\], ?]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^;\n]*\)\s*(?:throws [^{\n]+)?\{`)

	goPackagePattern = regexp.MustCompile(`(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_]*)`)
	goTypePattern    = regexp.MustCompile(`(?m)^[ \t]*type\s+([A-Za-z_][A-Za-z0-9_]*)\s+(?:struct|interface)\b`)
	goMethodPattern  = regexp.MustCompile(`(?m)^[ \t]*func\s*\(\s*[^)]*?\*?\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(`)
	goFuncPattern    = regexp.MustCompile(`(?m)^[ \t]*func\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(`)
)

func New(workspace string) *Service {
	return &Service{workspace: workspace}
}

func (s *Service) CompleteSymbols(query string, limit int) []Completion {
	query = strings.TrimSpace(query)
	if query == "" || limit <= 0 {
		return []Completion{}
	}

	index, err := s.buildIndex()
	if err != nil {
		return []Completion{}
	}

	scored := make([]scoredSymbol, 0, len(index))
	for _, symbol := range index {
		score, ok := matchScore(query, symbol)
		if !ok {
			continue
		}
		scored = append(scored, scoredSymbol{symbol: symbol, score: score})
	}

	sort.Slice(scored, func(i, j int) bool {
		if scored[i].score != scored[j].score {
			return scored[i].score < scored[j].score
		}
		if scored[i].symbol.Kind != scored[j].symbol.Kind {
			return scored[i].symbol.Kind < scored[j].symbol.Kind
		}
		if scored[i].symbol.ShortName != scored[j].symbol.ShortName {
			return scored[i].symbol.ShortName < scored[j].symbol.ShortName
		}
		return scored[i].symbol.FQName < scored[j].symbol.FQName
	})

	results := make([]Completion, 0, min(limit, len(scored)))
	seen := map[string]struct{}{}
	for _, item := range scored {
		key := item.symbol.Kind + ":" + item.symbol.FQName
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
	index, err := s.buildIndex()
	if err != nil {
		return []Symbol{}
	}

	byName := map[string]Symbol{}
	for _, symbol := range index {
		if symbol.Kind != "class" {
			continue
		}
		byName[symbol.FQName] = symbol
	}

	results := make([]Symbol, 0, len(names))
	for _, name := range names {
		trimmed := strings.TrimSpace(name)
		if trimmed == "" {
			continue
		}
		if symbol, ok := byName[trimmed]; ok {
			results = append(results, symbol)
		}
	}
	return results
}

func (s *Service) FindMethods(names []string) []Symbol {
	index, err := s.buildIndex()
	if err != nil {
		return []Symbol{}
	}

	byName := map[string]Symbol{}
	for _, symbol := range index {
		if symbol.Kind != "function" {
			continue
		}
		byName[symbol.FQName] = symbol
	}

	results := make([]Symbol, 0, len(names))
	for _, name := range names {
		trimmed := strings.TrimSpace(name)
		if trimmed == "" {
			continue
		}
		if symbol, ok := byName[trimmed]; ok {
			results = append(results, symbol)
		}
	}
	return results
}

func (s *Service) SearchSymbols(query string, limit int) []Symbol {
	query = strings.TrimSpace(query)
	if query == "" || limit <= 0 {
		return []Symbol{}
	}

	index, err := s.buildIndex()
	if err != nil {
		return []Symbol{}
	}

	scored := make([]scoredSymbol, 0, len(index))
	for _, symbol := range index {
		score, ok := matchScore(query, symbol)
		if ok {
			scored = append(scored, scoredSymbol{symbol: symbol, score: score})
			continue
		}
		if strings.Contains(strings.ToLower(symbol.Snippet), strings.ToLower(query)) {
			scored = append(scored, scoredSymbol{symbol: symbol, score: 5})
		}
	}

	sort.Slice(scored, func(i, j int) bool {
		if scored[i].score != scored[j].score {
			return scored[i].score < scored[j].score
		}
		if scored[i].symbol.Kind != scored[j].symbol.Kind {
			return scored[i].symbol.Kind < scored[j].symbol.Kind
		}
		return scored[i].symbol.FQName < scored[j].symbol.FQName
	})

	results := make([]Symbol, 0, min(limit, len(scored)))
	seen := map[string]struct{}{}
	for _, item := range scored {
		key := item.symbol.Kind + ":" + item.symbol.FQName
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

func (s *Service) buildIndex() ([]Symbol, error) {
	results := make([]Symbol, 0, 64)
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

		rel, err := filepath.Rel(s.workspace, path)
		if err != nil {
			return nil
		}
		rel = filepath.ToSlash(rel)

		content, err := os.ReadFile(path)
		if err != nil {
			return nil
		}

		switch strings.ToLower(filepath.Ext(path)) {
		case ".java":
			results = append(results, parseJavaSymbols(rel, string(content))...)
		case ".go":
			results = append(results, parseGoSymbols(rel, string(content))...)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return results, nil
}

type blockInfo struct {
	symbol Symbol
	start  int
	end    int
}

func parseJavaSymbols(relativePath string, content string) []Symbol {
	pkg := ""
	if match := javaPackagePattern.FindStringSubmatch(content); len(match) > 1 {
		pkg = match[1]
	}

	classBlocks := make([]blockInfo, 0)
	classMatches := javaClassPattern.FindAllStringSubmatchIndex(content, -1)
	for _, match := range classMatches {
		name := content[match[4]:match[5]]
		start, end, snippet := extractBraceBlock(content, match[0])
		fqName := joinName(pkg, name)
		classBlocks = append(classBlocks, blockInfo{
			symbol: Symbol{
				Kind:         "class",
				FQName:       fqName,
				ShortName:    name,
				RelativePath: relativePath,
				Snippet:      snippet,
			},
			start: start,
			end:   end,
		})
	}

	results := make([]Symbol, 0, len(classBlocks)*2)
	for _, classBlock := range classBlocks {
		results = append(results, classBlock.symbol)
	}

	methodMatches := javaMethodPattern.FindAllStringSubmatchIndex(content, -1)
	for _, match := range methodMatches {
		name := content[match[2]:match[3]]
		if isControlKeyword(name) {
			continue
		}
		block := findEnclosingBlock(classBlocks, match[0])
		if block == nil {
			continue
		}
		_, _, snippet := extractBraceBlock(content, match[0])
		results = append(results, Symbol{
			Kind:         "function",
			FQName:       block.symbol.FQName + "." + name,
			ShortName:    name,
			RelativePath: relativePath,
			Snippet:      snippet,
		})
	}

	return results
}

func parseGoSymbols(relativePath string, content string) []Symbol {
	pkg := ""
	if match := goPackagePattern.FindStringSubmatch(content); len(match) > 1 {
		pkg = match[1]
	}

	results := make([]Symbol, 0, 8)
	typeMatches := goTypePattern.FindAllStringSubmatchIndex(content, -1)
	for _, match := range typeMatches {
		name := content[match[2]:match[3]]
		_, _, snippet := extractBraceBlock(content, match[0])
		results = append(results, Symbol{
			Kind:         "class",
			FQName:       joinName(pkg, name),
			ShortName:    name,
			RelativePath: relativePath,
			Snippet:      snippet,
		})
	}

	methodMatches := goMethodPattern.FindAllStringSubmatchIndex(content, -1)
	for _, match := range methodMatches {
		receiver := content[match[2]:match[3]]
		name := content[match[4]:match[5]]
		_, _, snippet := extractBraceBlock(content, match[0])
		results = append(results, Symbol{
			Kind:         "function",
			FQName:       joinName(pkg, receiver) + "." + name,
			ShortName:    name,
			RelativePath: relativePath,
			Snippet:      snippet,
		})
	}

	funcMatches := goFuncPattern.FindAllStringSubmatchIndex(content, -1)
	for _, match := range funcMatches {
		name := content[match[2]:match[3]]
		if strings.HasPrefix(content[match[0]:match[1]], "func (") {
			continue
		}
		_, _, snippet := extractBraceBlock(content, match[0])
		results = append(results, Symbol{
			Kind:         "function",
			FQName:       joinName(pkg, name),
			ShortName:    name,
			RelativePath: relativePath,
			Snippet:      snippet,
		})
	}

	return results
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

func findEnclosingBlock(blocks []blockInfo, offset int) *blockInfo {
	var best *blockInfo
	for i := range blocks {
		if offset < blocks[i].start || offset > blocks[i].end {
			continue
		}
		if best == nil || blocks[i].start > best.start {
			best = &blocks[i]
		}
	}
	return best
}

func matchScore(query string, symbol Symbol) (int, bool) {
	q := strings.ToLower(strings.TrimSpace(query))
	shortName := strings.ToLower(symbol.ShortName)
	fqName := strings.ToLower(symbol.FQName)

	switch {
	case shortName == q || fqName == q:
		return 0, true
	case strings.HasPrefix(shortName, q):
		return 1, true
	case strings.HasPrefix(fqName, q):
		return 2, true
	case strings.Contains(shortName, q):
		return 3, true
	case strings.Contains(fqName, q):
		return 4, true
	default:
		return 0, false
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

func min(a int, b int) int {
	if a < b {
		return a
	}
	return b
}
