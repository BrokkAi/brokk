package runtime

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type MCPHistoryWriter struct {
	mu         sync.Mutex
	runDir     string
	nextIndex  int
	wroteHeads map[string]bool
}

func NewMCPHistoryWriter(root string) (*MCPHistoryWriter, error) {
	runDir := filepath.Join(root, ".brokk", "mcp-history", time.Now().UTC().Format("20060102-150405-000"))
	if err := os.MkdirAll(runDir, 0o755); err != nil {
		return nil, err
	}
	return &MCPHistoryWriter{runDir: runDir, wroteHeads: map[string]bool{}}, nil
}

func (w *MCPHistoryWriter) WriteRequest(toolName string, request any) string {
	w.mu.Lock()
	defer w.mu.Unlock()
	path := filepath.Join(w.runDir, fmt.Sprintf("%03d-%s.log", w.nextIndex, sanitizeLogName(toolName)))
	w.nextIndex++
	pretty := prettyJSON(request)
	content := fmt.Sprintf("# Request @%s\n\n%s\n", time.Now().UTC().Format("15:04:05.000"), pretty)
	_ = os.WriteFile(path, []byte(content), 0o644)
	return path
}

func (w *MCPHistoryWriter) AppendProgress(path string, progress float64, message string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	prefix := ""
	if !w.wroteHeads[path] {
		prefix = "\n# Progress\n"
		w.wroteHeads[path] = true
	}
	line := fmt.Sprintf("%s: %.1f%% = %s\n", time.Now().UTC().Format("15:04:05.000"), progress*100.0, message)
	_ = appendString(path, prefix+line)
}

func (w *MCPHistoryWriter) AppendResult(path string, status string, body string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	content := fmt.Sprintf("\n# Response @%s\n\n## Status: %s\n\n## Body\n\n%s\n", time.Now().UTC().Format("15:04:05.000"), status, body)
	_ = appendString(path, content)
}

func appendString(path string, value string) error {
	fh, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	defer fh.Close()
	_, err = fh.WriteString(value)
	return err
}

func prettyJSON(value any) string {
	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return "{}"
	}
	return string(data)
}

func sanitizeLogName(value string) string {
	if strings.TrimSpace(value) == "" {
		return "tool"
	}
	var builder strings.Builder
	for _, r := range value {
		switch {
		case r >= 'a' && r <= 'z':
			builder.WriteRune(r)
		case r >= 'A' && r <= 'Z':
			builder.WriteRune(r)
		case r >= '0' && r <= '9':
			builder.WriteRune(r)
		case r == '.', r == '_', r == '-':
			builder.WriteRune(r)
		default:
			builder.WriteByte('_')
		}
	}
	return builder.String()
}
