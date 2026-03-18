//go:build !cgo

package analyzer

type treeSitterFileAnalysis struct{}

func parseTreeSitterSymbols(relativePath string, content string) ([]Symbol, bool) {
	return nil, false
}

func parseTreeSitterFile(relativePath string, content string) (treeSitterFileAnalysis, bool) {
	return treeSitterFileAnalysis{}, false
}

func treeSitterEnabled() bool {
	return false
}
