package runtime

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestMCPServerListsDynamicTools(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "layout.xml"), []byte("<root><item>value</item></root>"), 0o644); err != nil {
		t.Fatalf("write xml fixture: %v", err)
	}
	if err := os.WriteFile(filepath.Join(root, "config.json"), []byte(`{"name":"brokk"}`), 0o644); err != nil {
		t.Fatalf("write json fixture: %v", err)
	}

	oldLookup := lookupExecutable
	lookupExecutable = func(file string) (string, error) { return "", os.ErrNotExist }
	defer func() { lookupExecutable = oldLookup }()

	server, err := NewMCPServer(root)
	if err != nil {
		t.Fatalf("NewMCPServer returned error: %v", err)
	}

	response := server.handle(mcpRequest{JSONRPC: "2.0", ID: 1, Method: "tools/list"})
	payload, err := json.Marshal(response.Result)
	if err != nil {
		t.Fatalf("marshal tools/list result: %v", err)
	}
	var parsed struct {
		Tools []map[string]any `json:"tools"`
	}
	if err := json.Unmarshal(payload, &parsed); err != nil {
		t.Fatalf("unmarshal tools/list result: %v", err)
	}

	names := map[string]bool{}
	for _, tool := range parsed.Tools {
		if name, ok := tool["name"].(string); ok {
			names[name] = true
		}
	}
	if !names["xpathQuery"] {
		t.Fatal("expected xpathQuery to be advertised when XML files exist")
	}
	if !names["jq"] {
		t.Fatal("expected jq to be advertised when JSON files exist and jq is unavailable")
	}
}

func TestMCPServerWritesHistoryLogsForToolCalls(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "hello.txt"), []byte("hello brokk"), 0o644); err != nil {
		t.Fatalf("write fixture: %v", err)
	}

	server, err := NewMCPServer(root)
	if err != nil {
		t.Fatalf("NewMCPServer returned error: %v", err)
	}
	if server.history == nil {
		t.Fatal("expected history writer to be initialized")
	}

	params := map[string]any{
		"name": "scan",
		"arguments": map[string]any{
			"goal": "hello",
		},
	}
	rawParams, err := json.Marshal(params)
	if err != nil {
		t.Fatalf("marshal params: %v", err)
	}
	response := server.handle(mcpRequest{JSONRPC: "2.0", ID: 1, Method: "tools/call", Params: rawParams})
	if response.Error != nil {
		t.Fatalf("unexpected tools/call error: %#v", response.Error)
	}

	historyRoot := filepath.Join(root, ".brokk", "mcp-history")
	entries, err := os.ReadDir(historyRoot)
	if err != nil {
		t.Fatalf("read history root: %v", err)
	}
	if len(entries) == 0 {
		t.Fatal("expected MCP history run directory")
	}
	runDir := filepath.Join(historyRoot, entries[0].Name())
	logs, err := os.ReadDir(runDir)
	if err != nil {
		t.Fatalf("read run dir: %v", err)
	}
	if len(logs) == 0 {
		t.Fatal("expected at least one MCP history log")
	}
	content, err := os.ReadFile(filepath.Join(runDir, logs[0].Name()))
	if err != nil {
		t.Fatalf("read history log: %v", err)
	}
	text := string(content)
	if !strings.Contains(text, "# Request") || !strings.Contains(text, "# Response") {
		t.Fatalf("expected request and response sections in history log, got: %s", text)
	}
}
