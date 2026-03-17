package runtime

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"reflect"
	"strings"
	"testing"
	"time"
)

type runningExecutor struct {
	baseURL   string
	authToken string
	shutdown  func()
}

func startGoExecutorForConformance(t *testing.T, workspace string) *runningExecutor {
	t.Helper()
	cfg := Config{
		ExecID:       "conformance-go",
		ListenAddr:   "127.0.0.1:0",
		AuthToken:    "token-go",
		WorkspaceDir: workspace,
	}
	executor, err := NewExecutor(cfg)
	if err != nil {
		t.Fatalf("NewExecutor returned error: %v", err)
	}
	listener, err := net.Listen("tcp", cfg.ListenAddr)
	if err != nil {
		t.Fatalf("listen failed: %v", err)
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		_ = executor.Serve(listener)
	}()
	return &runningExecutor{
		baseURL:   "http://" + listener.Addr().String(),
		authToken: cfg.AuthToken,
		shutdown: func() {
			_ = listener.Close()
			<-done
		},
	}
}

func startJavaExecutorForConformance(t *testing.T, workspace string) *runningExecutor {
	t.Helper()
	jarPath := strings.TrimSpace(os.Getenv("BROKK_JAVA_EXECUTOR_JAR"))
	if jarPath == "" {
		t.Skip("BROKK_JAVA_EXECUTOR_JAR not set")
	}
	if _, err := os.Stat(jarPath); err != nil {
		t.Skipf("BROKK_JAVA_EXECUTOR_JAR is not usable: %v", err)
	}

	execID := "conformance-java"
	authToken := "token-java"
	cmd := exec.Command(
		"java",
		"-Djava.awt.headless=true",
		"-Dapple.awt.UIElement=true",
		"-cp",
		jarPath,
		"ai.brokk.executor.HeadlessExecutorMain",
		"--exec-id", execID,
		"--listen-addr", "127.0.0.1:0",
		"--auth-token", authToken,
		"--workspace-dir", workspace,
	)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatalf("StdoutPipe failed: %v", err)
	}
	cmd.Stderr = cmd.Stdout
	if err := cmd.Start(); err != nil {
		t.Fatalf("failed to start java executor: %v", err)
	}

	reader := bufio.NewReader(stdout)
	deadline := time.Now().Add(60 * time.Second)
	baseURL := ""
	for time.Now().Before(deadline) {
		line, err := reader.ReadString('\n')
		if err != nil {
			break
		}
		if strings.Contains(line, "Executor listening on http://") {
			baseURL = strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(line), "Executor listening on "))
			break
		}
	}
	if baseURL == "" {
		_ = cmd.Process.Kill()
		t.Fatal("java executor did not become ready")
	}

	return &runningExecutor{
		baseURL:   baseURL,
		authToken: authToken,
		shutdown: func() {
			_ = cmd.Process.Kill()
			_, _ = cmd.Process.Wait()
		},
	}
}

func doJSONRequest(t *testing.T, exec *runningExecutor, method string, path string, body any, auth bool) (int, any) {
	t.Helper()
	var payload []byte
	var err error
	if body != nil {
		payload, err = json.Marshal(body)
		if err != nil {
			t.Fatalf("marshal request body: %v", err)
		}
	}
	req, err := http.NewRequest(method, exec.baseURL+path, bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("NewRequest failed: %v", err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if auth {
		req.Header.Set("Authorization", "Bearer "+exec.authToken)
	}
	if method == http.MethodPost && strings.HasPrefix(path, "/v1/jobs") {
		req.Header.Set("Idempotency-Key", "conformance")
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("request %s %s failed: %v", method, path, err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read response body: %v", err)
	}
	if len(raw) == 0 {
		return resp.StatusCode, nil
	}
	var decoded any
	if err := json.Unmarshal(raw, &decoded); err != nil {
		return resp.StatusCode, string(raw)
	}
	return resp.StatusCode, decoded
}

func shapeOf(value any) any {
	switch typed := value.(type) {
	case map[string]any:
		result := map[string]any{}
		for key, item := range typed {
			result[key] = shapeOf(item)
		}
		return result
	case []any:
		if len(typed) == 0 {
			return []any{}
		}
		return []any{shapeOf(typed[0])}
	case string:
		return "string"
	case bool:
		return "bool"
	case float64:
		return "number"
	case nil:
		return nil
	default:
		return fmt.Sprintf("%T", typed)
	}
}

func TestExecutorConformanceHealthAndSessions(t *testing.T) {
	workspace := t.TempDir()

	goExec := startGoExecutorForConformance(t, workspace)
	defer goExec.shutdown()

	javaExec := startJavaExecutorForConformance(t, workspace)
	defer javaExec.shutdown()

	cases := []struct {
		method string
		path   string
		body   any
		auth   bool
	}{
		{method: http.MethodGet, path: "/health/live", auth: false},
		{method: http.MethodPost, path: "/v1/sessions", auth: true, body: map[string]any{"name": "Conformance"}},
		{method: http.MethodGet, path: "/v1/sessions", auth: true},
		{method: http.MethodGet, path: "/v1/sessions/current", auth: true},
	}

	for _, tc := range cases {
		t.Run(tc.method+" "+tc.path, func(t *testing.T) {
			goStatus, goBody := doJSONRequest(t, goExec, tc.method, tc.path, tc.body, tc.auth)
			javaStatus, javaBody := doJSONRequest(t, javaExec, tc.method, tc.path, tc.body, tc.auth)

			if goStatus != javaStatus {
				t.Fatalf("status mismatch for %s %s: go=%d java=%d", tc.method, tc.path, goStatus, javaStatus)
			}
			if !reflect.DeepEqual(shapeOf(goBody), shapeOf(javaBody)) {
				t.Fatalf("shape mismatch for %s %s\ngo=%v\njava=%v", tc.method, tc.path, shapeOf(goBody), shapeOf(javaBody))
			}
		})
	}
}

func TestGoExecutorConformanceHarnessBoots(t *testing.T) {
	workspace := t.TempDir()
	exec := startGoExecutorForConformance(t, workspace)
	defer exec.shutdown()

	status, body := doJSONRequest(t, exec, http.MethodGet, "/health/live", nil, false)
	if status != http.StatusOK {
		t.Fatalf("expected 200 from /health/live, got %d", status)
	}
	if shapeOf(body) == nil {
		t.Fatal("expected non-empty response body")
	}
}
