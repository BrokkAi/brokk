package runtime

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

var lookupExecutable = exec.LookPath

type mcpRequest struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      any             `json:"id,omitempty"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params,omitempty"`
}

type mcpResponse struct {
	JSONRPC string `json:"jsonrpc"`
	ID      any    `json:"id,omitempty"`
	Result  any    `json:"result,omitempty"`
	Error   any    `json:"error,omitempty"`
}

type mcpTool struct {
	Name        string
	Description string
	InputSchema map[string]any
	Call        func(map[string]any) (string, bool)
}

type MCPServer struct {
	root      string
	tools     map[string]mcpTool
	toolOrder []string
	history   *MCPHistoryWriter
}

func NewMCPServer(root string) (*MCPServer, error) {
	absRoot, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	server := &MCPServer{root: absRoot, tools: map[string]mcpTool{}}
	if history, historyErr := NewMCPHistoryWriter(absRoot); historyErr == nil {
		server.history = history
	}
	for _, tool := range server.availableTools() {
		server.tools[tool.Name] = tool
		server.toolOrder = append(server.toolOrder, tool.Name)
	}
	return server, nil
}

func (s *MCPServer) Run(input io.Reader, output io.Writer) error {
	reader := bufio.NewReader(input)
	for {
		payload, err := readMCPMessage(reader)
		if err != nil {
			if err == io.EOF {
				return nil
			}
			return err
		}
		var request mcpRequest
		if err := json.Unmarshal(payload, &request); err != nil {
			return writeMCPMessage(output, mcpResponse{JSONRPC: "2.0", Error: map[string]any{"code": -32700, "message": "parse error"}})
		}
		response := s.handle(request)
		if request.ID == nil {
			continue
		}
		if err := writeMCPMessage(output, response); err != nil {
			return err
		}
	}
}

func (s *MCPServer) handle(request mcpRequest) mcpResponse {
	switch request.Method {
	case "initialize":
		return mcpResponse{JSONRPC: "2.0", ID: request.ID, Result: map[string]any{"protocolVersion": "2024-11-05", "capabilities": map[string]any{"tools": map[string]any{}}, "serverInfo": map[string]any{"name": "Brokk Go MCP Server", "version": ServerVersion}}}
	case "ping":
		return mcpResponse{JSONRPC: "2.0", ID: request.ID, Result: map[string]any{}}
	case "tools/list":
		tools := make([]map[string]any, 0, len(s.toolOrder))
		for _, name := range s.toolOrder {
			tool := s.tools[name]
			tools = append(tools, map[string]any{"name": tool.Name, "description": tool.Description, "inputSchema": tool.InputSchema})
		}
		return mcpResponse{JSONRPC: "2.0", ID: request.ID, Result: map[string]any{"tools": tools}}
	case "tools/call":
		var params struct {
			Name      string         `json:"name"`
			Arguments map[string]any `json:"arguments"`
		}
		_ = json.Unmarshal(request.Params, &params)
		tool, ok := s.tools[params.Name]
		if !ok {
			return mcpResponse{JSONRPC: "2.0", ID: request.ID, Result: map[string]any{"content": []map[string]any{{"type": "text", "text": "Unknown tool"}}, "isError": true}}
		}

		logPath := ""
		if s.history != nil {
			logPath = s.history.WriteRequest(params.Name, request)
			s.history.AppendProgress(logPath, 0.0, "Starting "+params.Name)
		}

		text, isError := tool.Call(params.Arguments)
		if s.history != nil {
			status := "SUCCESS"
			if isError {
				status = "ERROR"
			}
			s.history.AppendResult(logPath, status, text)
		}
		return mcpResponse{JSONRPC: "2.0", ID: request.ID, Result: map[string]any{"content": []map[string]any{{"type": "text", "text": text}}, "isError": isError}}
	default:
		return mcpResponse{JSONRPC: "2.0", ID: request.ID, Error: map[string]any{"code": -32601, "message": "method not found"}}
	}
}

func (s *MCPServer) availableTools() []mcpTool {
	textSchema := func(name, description string) map[string]any {
		return map[string]any{"type": "object", "properties": map[string]any{name: map[string]any{"type": "string", "description": description}}}
	}

	tools := []mcpTool{
		{Name: "scan", Description: "Agentic scan for relevant files and classes.", InputSchema: map[string]any{"type": "object", "properties": map[string]any{"goal": map[string]any{"type": "string"}, "includeTests": map[string]any{"type": "boolean"}}}, Call: s.callScan},
		{Name: "callCodeAgent", Description: "Bootstrap placeholder for code changes.", InputSchema: map[string]any{"type": "object", "properties": map[string]any{"instructions": map[string]any{"type": "string"}, "editFiles": map[string]any{"type": "array", "items": map[string]any{"type": "string"}}, "deferBuild": map[string]any{"type": "boolean"}, "testFiles": map[string]any{"type": "array", "items": map[string]any{"type": "string"}}}}, Call: func(args map[string]any) (string, bool) {
			return "Go runtime bootstrap does not implement code-editing parity yet. Keep using the Java code path for write-heavy flows while the port is completed.", true
		}},
		{Name: "runBuild", Description: "Run build verification.", InputSchema: map[string]any{"type": "object", "properties": map[string]any{}}, Call: s.callRunBuild},
		{Name: "configureBuild", Description: "Configure build commands.", InputSchema: map[string]any{"type": "object", "properties": map[string]any{"buildOnlyCmd": map[string]any{"type": "string"}, "testSomeCmd": map[string]any{"type": "string"}}}, Call: s.callConfigureBuild},
		{Name: "merge", Description: "Detect merge conflict markers.", InputSchema: map[string]any{"type": "object", "properties": map[string]any{}}, Call: s.callMerge},
		{Name: "searchSymbols", Description: "Search files and contents for a symbol.", InputSchema: textSchema("query", "Symbol query"), Call: s.callSearchSymbols},
		{Name: "scanUsages", Description: "Search usages by substring.", InputSchema: textSchema("symbol", "Usage query"), Call: s.callScanUsages},
		{Name: "skimDirectory", Description: "List workspace files.", InputSchema: map[string]any{"type": "object", "properties": map[string]any{}}, Call: s.callSkimDirectory},
		{Name: "getFileSummaries", Description: "Return file content summaries.", InputSchema: map[string]any{"type": "object", "properties": map[string]any{"paths": map[string]any{"type": "array", "items": map[string]any{"type": "string"}}}}, Call: s.callGetFileSummaries},
		{Name: "getClassSkeletons", Description: "Return bootstrap class skeletons.", InputSchema: textSchema("className", "Class name"), Call: s.callGetClassSkeletons},
		{Name: "getClassSources", Description: "Return bootstrap class source matches.", InputSchema: textSchema("className", "Class name"), Call: s.callGetClassSources},
		{Name: "getMethodSources", Description: "Return bootstrap method source matches.", InputSchema: textSchema("methodName", "Method name"), Call: s.callGetMethodSources},
		{Name: "getSymbolLocations", Description: "Find symbol locations.", InputSchema: textSchema("query", "Symbol query"), Call: s.callGetSymbolLocations},
	}

	if s.hasFileExtension(".xml") {
		tools = append(tools, mcpTool{Name: "xpathQuery", Description: "Search XML files for XPath-related matches.", InputSchema: map[string]any{"type": "object", "properties": map[string]any{"query": map[string]any{"type": "string"}, "pathGlobs": map[string]any{"type": "array", "items": map[string]any{"type": "string"}}}}, Call: s.callXPathQuery})
	}
	if s.hasFileExtension(".json") && !s.isJQOnPath() {
		tools = append(tools, mcpTool{Name: "jq", Description: "Inspect JSON files when jq is unavailable on PATH.", InputSchema: map[string]any{"type": "object", "properties": map[string]any{"query": map[string]any{"type": "string"}, "pathGlobs": map[string]any{"type": "array", "items": map[string]any{"type": "string"}}}}, Call: s.callJQ})
	}
	return tools
}

func (s *MCPServer) hasFileExtension(ext string) bool {
	files, err := listWorkspaceFiles(s.root, 0)
	if err != nil {
		return false
	}
	for _, file := range files {
		if strings.EqualFold(filepath.Ext(file), ext) {
			return true
		}
	}
	return false
}

func (s *MCPServer) isJQOnPath() bool {
	_, err := lookupExecutable("jq")
	return err == nil
}

func readMCPMessage(reader *bufio.Reader) ([]byte, error) {
	contentLength := 0
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			return nil, err
		}
		line = strings.TrimSpace(line)
		if line == "" {
			break
		}
		if strings.HasPrefix(strings.ToLower(line), "content-length:") {
			fmt.Sscanf(line, "Content-Length: %d", &contentLength)
		}
	}
	if contentLength <= 0 {
		return nil, io.EOF
	}
	payload := make([]byte, contentLength)
	if _, err := io.ReadFull(reader, payload); err != nil {
		return nil, err
	}
	return payload, nil
}

func writeMCPMessage(writer io.Writer, response mcpResponse) error {
	data, err := json.Marshal(response)
	if err != nil {
		return err
	}
	if _, err := fmt.Fprintf(writer, "Content-Length: %d\r\n\r\n", len(data)); err != nil {
		return err
	}
	_, err = writer.Write(data)
	return err
}

func (s *MCPServer) callScan(args map[string]any) (string, bool) {
	goal, _ := args["goal"].(string)
	matches, err := searchWorkspace(s.root, goal, 15)
	if err != nil {
		return err.Error(), true
	}
	if len(matches) == 0 {
		return "No matching files found.", false
	}
	return "Relevant files:\n- " + strings.Join(matches, "\n- "), false
}

func (s *MCPServer) callRunBuild(args map[string]any) (string, bool) {
	cfg, err := loadBuildConfig(s.root)
	if err != nil {
		return "Build is not configured yet. Call configureBuild first.", true
	}
	output, runErr := runCommand(s.root, cfg.BuildOnlyCmd, 30*time.Second)
	if runErr != nil {
		return strings.TrimSpace(output), true
	}
	if output == "" {
		output = "Build successful"
	}
	return output, false
}

func (s *MCPServer) callConfigureBuild(args map[string]any) (string, bool) {
	buildOnlyCmd, _ := args["buildOnlyCmd"].(string)
	testSomeCmd, _ := args["testSomeCmd"].(string)
	if strings.TrimSpace(buildOnlyCmd) == "" {
		return "buildOnlyCmd is required", true
	}
	if err := saveBuildConfig(s.root, BuildConfig{BuildOnlyCmd: buildOnlyCmd, TestSomeCmd: testSomeCmd}); err != nil {
		return err.Error(), true
	}
	return "Build configuration saved.", false
}

func (s *MCPServer) callMerge(args map[string]any) (string, bool) {
	files, err := listWorkspaceFiles(s.root, 0)
	if err != nil {
		return err.Error(), true
	}
	var conflicts []string
	for _, rel := range files {
		absPath, pathErr := resolveWorkspacePath(s.root, rel)
		if pathErr != nil {
			continue
		}
		data, readErr := os.ReadFile(absPath)
		if readErr == nil && strings.Contains(string(data), "<<<<<<<") {
			conflicts = append(conflicts, rel)
		}
	}
	if len(conflicts) == 0 {
		return "Error: Repository is not in a merge conflict state.", true
	}
	return "Conflict markers detected in:\n- " + strings.Join(conflicts, "\n- "), false
}

func (s *MCPServer) callSearchSymbols(args map[string]any) (string, bool) {
	query, _ := args["query"].(string)
	return s.callGetSymbolLocations(map[string]any{"query": query})
}

func (s *MCPServer) callScanUsages(args map[string]any) (string, bool) {
	query, _ := args["symbol"].(string)
	matches, err := searchWorkspace(s.root, query, 20)
	if err != nil {
		return err.Error(), true
	}
	if len(matches) == 0 {
		return "No usages found.", false
	}
	return "Usage matches:\n- " + strings.Join(matches, "\n- "), false
}

func (s *MCPServer) callSkimDirectory(args map[string]any) (string, bool) {
	files, err := listWorkspaceFiles(s.root, 100)
	if err != nil {
		return err.Error(), true
	}
	if len(files) == 0 {
		return "Workspace is empty.", false
	}
	return "Workspace files:\n- " + strings.Join(files, "\n- "), false
}

func (s *MCPServer) callGetFileSummaries(args map[string]any) (string, bool) {
	values, _ := args["paths"].([]any)
	if len(values) == 0 {
		return "No file paths provided.", true
	}
	var sections []string
	for _, raw := range values {
		path, _ := raw.(string)
		absPath, err := resolveWorkspacePath(s.root, path)
		if err != nil {
			continue
		}
		sections = append(sections, "## "+path+"\n"+summarizeFile(absPath, 40))
	}
	if len(sections) == 0 {
		return "No readable files found.", true
	}
	return strings.Join(sections, "\n\n"), false
}

func (s *MCPServer) callGetClassSkeletons(args map[string]any) (string, bool) {
	className, _ := args["className"].(string)
	return fmt.Sprintf("Bootstrap class skeleton lookup for %s.\nUse searchSymbols/getClassSources for richer parity while the Go port is completed.", className), false
}

func (s *MCPServer) callGetClassSources(args map[string]any) (string, bool) {
	className, _ := args["className"].(string)
	return s.callGetSymbolLocations(map[string]any{"query": className})
}

func (s *MCPServer) callGetMethodSources(args map[string]any) (string, bool) {
	methodName, _ := args["methodName"].(string)
	return s.callGetSymbolLocations(map[string]any{"query": methodName})
}

func (s *MCPServer) callGetSymbolLocations(args map[string]any) (string, bool) {
	query, _ := args["query"].(string)
	matches, err := searchWorkspace(s.root, query, 20)
	if err != nil {
		return err.Error(), true
	}
	if len(matches) == 0 {
		return "No matching symbols or files found.", false
	}
	return "Matches:\n- " + strings.Join(matches, "\n- "), false
}

func (s *MCPServer) callXPathQuery(args map[string]any) (string, bool) {
	query, _ := args["query"].(string)
	matches, err := s.searchFilesWithExtensions(query, []string{".xml"}, 20)
	if err != nil {
		return err.Error(), true
	}
	if len(matches) == 0 {
		return "No matching XML files found.", false
	}
	return "XML matches:\n- " + strings.Join(matches, "\n- "), false
}

func (s *MCPServer) callJQ(args map[string]any) (string, bool) {
	query, _ := args["query"].(string)
	matches, err := s.searchFilesWithExtensions(query, []string{".json"}, 20)
	if err != nil {
		return err.Error(), true
	}
	if len(matches) == 0 {
		return "No matching JSON files found.", false
	}
	return "JSON matches:\n- " + strings.Join(matches, "\n- "), false
}

func (s *MCPServer) searchFilesWithExtensions(query string, extensions []string, limit int) ([]string, error) {
	files, err := listWorkspaceFiles(s.root, 0)
	if err != nil {
		return nil, err
	}
	allow := map[string]struct{}{}
	for _, ext := range extensions {
		allow[strings.ToLower(ext)] = struct{}{}
	}
	var matches []string
	for _, file := range files {
		if _, ok := allow[strings.ToLower(filepath.Ext(file))]; !ok {
			continue
		}
		absPath, err := resolveWorkspacePath(s.root, file)
		if err != nil {
			continue
		}
		data, readErr := os.ReadFile(absPath)
		if readErr != nil {
			continue
		}
		if query == "" || strings.Contains(strings.ToLower(file), strings.ToLower(query)) || strings.Contains(strings.ToLower(string(data)), strings.ToLower(query)) {
			matches = append(matches, file)
		}
		if limit > 0 && len(matches) >= limit {
			break
		}
	}
	sort.Strings(matches)
	return matches, nil
}
