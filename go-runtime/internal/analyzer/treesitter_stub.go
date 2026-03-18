//go:build !cgo

package analyzer

func parseTreeSitterSymbols(relativePath string, content string) ([]Symbol, bool) {
	return nil, false
}

func treeSitterEnabled() bool {
	return false
}
