package app

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/brokk/brokk/go-runtime/internal/config"
	"github.com/brokk/brokk/go-runtime/internal/httpserver"
)

func TestHealthLiveUnauthenticated(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	req := httptest.NewRequest(http.MethodGet, "/health/live", nil)
	rec := httptest.NewRecorder()

	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
}

func TestHealthReadyStartsNotReady(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	req := httptest.NewRequest(http.MethodGet, "/health/ready", nil)
	rec := httptest.NewRecorder()

	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", rec.Code)
	}

	var payload httpserver.ErrorPayload
	if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	if payload.Code != "NOT_READY" {
		t.Fatalf("code = %q, want NOT_READY", payload.Code)
	}
}

func TestJobsRequireAuth(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	req := httptest.NewRequest(http.MethodPost, "/v1/jobs", strings.NewReader(`{}`))
	rec := httptest.NewRecorder()

	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
}

func TestPostJobsCreatesQueuedJob(t *testing.T) {
	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "Jobs")
	req := httptest.NewRequest(http.MethodPost, "/v1/jobs", strings.NewReader(`{"taskInput":"test task","plannerModel":"gpt-5"}`))
	req.Header.Set("Authorization", "Bearer test-token")
	req.Header.Set("Idempotency-Key", "test-key")
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()

	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201", rec.Code)
	}

	var payload map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	jobID, ok := payload["jobId"].(string)
	if !ok || jobID == "" {
		t.Fatalf("jobId = %v, want non-empty string", payload["jobId"])
	}
	if payload["state"] != "QUEUED" {
		t.Fatalf("state = %v, want QUEUED", payload["state"])
	}

	getReq := httptest.NewRequest(http.MethodGet, "/v1/jobs/"+jobID, nil)
	getReq.Header.Set("Authorization", "Bearer test-token")
	getRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(getRec, getReq)

	if getRec.Code != http.StatusOK {
		t.Fatalf("GET status = %d, want 200", getRec.Code)
	}

	waitForTerminalJob(t, testApp, jobID)
}

func TestPostJobsIdempotencyReturnsExistingJob(t *testing.T) {
	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "Jobs")
	body := `{"taskInput":"test task","plannerModel":"gpt-5"}`

	req1 := httptest.NewRequest(http.MethodPost, "/v1/jobs", strings.NewReader(body))
	req1.Header.Set("Authorization", "Bearer test-token")
	req1.Header.Set("Idempotency-Key", "test-key")
	req1.Header.Set("Content-Type", "application/json")
	rec1 := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec1, req1)

	req2 := httptest.NewRequest(http.MethodPost, "/v1/jobs", strings.NewReader(body))
	req2.Header.Set("Authorization", "Bearer test-token")
	req2.Header.Set("Idempotency-Key", "test-key")
	req2.Header.Set("Content-Type", "application/json")
	rec2 := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec2, req2)

	if rec2.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec2.Code)
	}

	var payload map[string]any
	if err := json.Unmarshal(rec2.Body.Bytes(), &payload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	jobID, ok := payload["jobId"].(string)
	if !ok || jobID == "" {
		t.Fatalf("jobId = %v, want non-empty string", payload["jobId"])
	}

	waitForTerminalJob(t, testApp, jobID)
}

func TestPostJobsInvalidReasoningLevelCodeReturns400(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	req := httptest.NewRequest(http.MethodPost, "/v1/jobs", strings.NewReader(`{"taskInput":"test task","plannerModel":"gpt-5","reasoningLevelCode":"INVALID_LEVEL"}`))
	req.Header.Set("Authorization", "Bearer test-token")
	req.Header.Set("Idempotency-Key", "test-key")
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()

	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestPostJobsInvalidTemperatureCodeReturns400(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	req := httptest.NewRequest(http.MethodPost, "/v1/jobs", strings.NewReader(`{"taskInput":"test task","plannerModel":"gpt-5","temperatureCode":5.0}`))
	req.Header.Set("Authorization", "Bearer test-token")
	req.Header.Set("Idempotency-Key", "test-key")
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()

	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestCreateSessionAndCurrentSession(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	createReq := httptest.NewRequest(http.MethodPost, "/v1/sessions", strings.NewReader(`{"name":"My New Session"}`))
	createReq.Header.Set("Authorization", "Bearer test-token")
	createReq.Header.Set("Content-Type", "application/json")
	createRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(createRec, createReq)

	if createRec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201", createRec.Code)
	}

	var created map[string]any
	if err := json.Unmarshal(createRec.Body.Bytes(), &created); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	sessionID := created["sessionId"].(string)

	currentReq := httptest.NewRequest(http.MethodGet, "/v1/sessions/current", nil)
	currentReq.Header.Set("Authorization", "Bearer test-token")
	currentRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(currentRec, currentReq)

	if currentRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", currentRec.Code)
	}

	var current map[string]any
	if err := json.Unmarshal(currentRec.Body.Bytes(), &current); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	if current["id"] != sessionID {
		t.Fatalf("id = %v, want %s", current["id"], sessionID)
	}

	readyReq := httptest.NewRequest(http.MethodGet, "/health/ready", nil)
	readyRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(readyRec, readyReq)
	if readyRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", readyRec.Code)
	}
}

func TestListAndSwitchSessions(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	firstID := createSessionViaAPI(t, testApp, "First")
	secondID := createSessionViaAPI(t, testApp, "Second")

	listReq := httptest.NewRequest(http.MethodGet, "/v1/sessions", nil)
	listReq.Header.Set("Authorization", "Bearer test-token")
	listRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(listRec, listReq)

	if listRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", listRec.Code)
	}

	var listPayload map[string]any
	if err := json.Unmarshal(listRec.Body.Bytes(), &listPayload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	if listPayload["currentSessionId"] != secondID {
		t.Fatalf("currentSessionId = %v, want %s", listPayload["currentSessionId"], secondID)
	}

	switchReq := httptest.NewRequest(http.MethodPost, "/v1/sessions/switch", strings.NewReader(`{"sessionId":"`+firstID+`"}`))
	switchReq.Header.Set("Authorization", "Bearer test-token")
	switchReq.Header.Set("Content-Type", "application/json")
	switchRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(switchRec, switchReq)

	if switchRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", switchRec.Code)
	}
}

func TestImportAndDownloadSessionZip(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	sessionID := "550e8400-e29b-41d4-a716-446655440000"
	zipBytes := buildSessionZip(t, sessionID, "Imported Session")

	importReq := httptest.NewRequest(http.MethodPut, "/v1/sessions", bytes.NewReader(zipBytes))
	importReq.Header.Set("Authorization", "Bearer test-token")
	importReq.Header.Set("X-Session-Id", sessionID)
	importRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(importRec, importReq)

	if importRec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201", importRec.Code)
	}

	downloadReq := httptest.NewRequest(http.MethodGet, "/v1/sessions/"+sessionID, nil)
	downloadReq.Header.Set("Authorization", "Bearer test-token")
	downloadRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(downloadRec, downloadReq)

	if downloadRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", downloadRec.Code)
	}
	if got := downloadRec.Header().Get("Content-Type"); got != "application/zip" {
		t.Fatalf("Content-Type = %q, want application/zip", got)
	}
}

func TestGetTaskListEmptyAndReplace(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "Tasks")

	getReq := httptest.NewRequest(http.MethodGet, "/v1/tasklist", nil)
	getReq.Header.Set("Authorization", "Bearer test-token")
	getRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(getRec, getReq)

	if getRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", getRec.Code)
	}

	var emptyPayload map[string]any
	if err := json.Unmarshal(getRec.Body.Bytes(), &emptyPayload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	if emptyPayload["bigPicture"] != nil {
		t.Fatalf("bigPicture = %v, want nil", emptyPayload["bigPicture"])
	}

	postReq := httptest.NewRequest(http.MethodPost, "/v1/tasklist", strings.NewReader(`{"bigPicture":"Updated Goal","tasks":[{"title":"First","text":"First","done":false},{"title":"Second","text":"Second","done":true}]}`))
	postReq.Header.Set("Authorization", "Bearer test-token")
	postReq.Header.Set("Content-Type", "application/json")
	postRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(postRec, postReq)

	if postRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", postRec.Code)
	}
}

func TestPostTaskListNullTasksReturns400(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "Tasks")

	req := httptest.NewRequest(http.MethodPost, "/v1/tasklist", strings.NewReader(`{"bigPicture":"Goal","tasks":null}`))
	req.Header.Set("Authorization", "Bearer test-token")
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestGetContextAndTextFragment(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "Context")

	postReq := httptest.NewRequest(http.MethodPost, "/v1/context/text", strings.NewReader(`{"text":"hello from chip"}`))
	postReq.Header.Set("Authorization", "Bearer test-token")
	postReq.Header.Set("Content-Type", "application/json")
	postRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(postRec, postReq)

	if postRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", postRec.Code)
	}

	var created map[string]any
	if err := json.Unmarshal(postRec.Body.Bytes(), &created); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	fragmentID := created["id"].(string)

	getReq := httptest.NewRequest(http.MethodGet, "/v1/context?tokens=true", nil)
	getReq.Header.Set("Authorization", "Bearer test-token")
	getRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(getRec, getReq)

	if getRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", getRec.Code)
	}

	fragmentReq := httptest.NewRequest(http.MethodGet, "/v1/context/fragments/"+fragmentID, nil)
	fragmentReq.Header.Set("Authorization", "Bearer test-token")
	fragmentRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(fragmentRec, fragmentReq)

	if fragmentRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", fragmentRec.Code)
	}
}

func TestDropPinReadonlyContextFragment(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "Context")

	postReq := httptest.NewRequest(http.MethodPost, "/v1/context/text", strings.NewReader(`{"text":"hello"}`))
	postReq.Header.Set("Authorization", "Bearer test-token")
	postReq.Header.Set("Content-Type", "application/json")
	postRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(postRec, postReq)

	var created map[string]any
	if err := json.Unmarshal(postRec.Body.Bytes(), &created); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	fragmentID := created["id"].(string)

	pinReq := httptest.NewRequest(http.MethodPost, "/v1/context/pin", strings.NewReader(`{"fragmentId":"`+fragmentID+`","pinned":true}`))
	pinReq.Header.Set("Authorization", "Bearer test-token")
	pinReq.Header.Set("Content-Type", "application/json")
	pinRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(pinRec, pinReq)
	if pinRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", pinRec.Code)
	}

	readonlyReq := httptest.NewRequest(http.MethodPost, "/v1/context/readonly", strings.NewReader(`{"fragmentId":"`+fragmentID+`","readonly":true}`))
	readonlyReq.Header.Set("Authorization", "Bearer test-token")
	readonlyReq.Header.Set("Content-Type", "application/json")
	readonlyRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(readonlyRec, readonlyReq)
	if readonlyRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", readonlyRec.Code)
	}

	dropReq := httptest.NewRequest(http.MethodPost, "/v1/context/drop", strings.NewReader(`{"fragmentIds":["`+fragmentID+`"]}`))
	dropReq.Header.Set("Authorization", "Bearer test-token")
	dropReq.Header.Set("Content-Type", "application/json")
	dropRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(dropRec, dropReq)
	if dropRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", dropRec.Code)
	}
}

func TestPostContextFilesAllPathsInvalidReturns400(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "Files")

	outsideWorkspace := filepath.Join(filepath.Dir(testApp.cfg.WorkspaceDir), "outside-workspace")
	req := httptest.NewRequest(http.MethodPost, "/v1/context/files", strings.NewReader(`{"relativePaths":["`+filepath.ToSlash(outsideWorkspace)+`","../outside/workspace","nonexistent.txt"]}`))
	req.Header.Set("Authorization", "Bearer test-token")
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestPostContextFilesAddsWorkspaceFile(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "Files")

	targetFile := filepath.Join(testApp.cfg.WorkspaceDir, "notes.txt")
	if err := os.WriteFile(targetFile, []byte("hello file"), 0o644); err != nil {
		t.Fatalf("os.WriteFile() error = %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/v1/context/files", strings.NewReader(`{"relativePaths":["notes.txt"]}`))
	req.Header.Set("Authorization", "Bearer test-token")
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
}

func TestPostContextClassesAddsMatchingClass(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "Classes")

	targetDir := filepath.Join(testApp.cfg.WorkspaceDir, "src", "main", "java", "com", "example", "service")
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		t.Fatalf("os.MkdirAll() error = %v", err)
	}
	targetFile := filepath.Join(targetDir, "UserService.java")
	content := "package com.example.service;\n\npublic class UserService {\n    public String findUserById(String id) {\n        return id;\n    }\n}\n"
	if err := os.WriteFile(targetFile, []byte(content), 0o644); err != nil {
		t.Fatalf("os.WriteFile() error = %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/v1/context/classes", strings.NewReader(`{"classNames":["com.example.service.UserService"]}`))
	req.Header.Set("Authorization", "Bearer test-token")
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}

	var payload map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	added, ok := payload["added"].([]any)
	if !ok || len(added) != 1 {
		t.Fatalf("added = %v, want single added class", payload["added"])
	}
	item, ok := added[0].(map[string]any)
	if !ok || item["className"] != "com.example.service.UserService" {
		t.Fatalf("added[0] = %v, want className", added[0])
	}
}

func TestPostContextMethodsAddsMatchingMethod(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "Methods")

	targetDir := filepath.Join(testApp.cfg.WorkspaceDir, "src", "main", "java", "com", "example", "service")
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		t.Fatalf("os.MkdirAll() error = %v", err)
	}
	targetFile := filepath.Join(targetDir, "UserService.java")
	content := "package com.example.service;\n\npublic class UserService {\n    public String findUserById(String id) {\n        return id;\n    }\n}\n"
	if err := os.WriteFile(targetFile, []byte(content), 0o644); err != nil {
		t.Fatalf("os.WriteFile() error = %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/v1/context/methods", strings.NewReader(`{"methodNames":["com.example.service.UserService.findUserById"]}`))
	req.Header.Set("Authorization", "Bearer test-token")
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}

	var payload map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	added, ok := payload["added"].([]any)
	if !ok || len(added) != 1 {
		t.Fatalf("added = %v, want single added method", payload["added"])
	}
	item, ok := added[0].(map[string]any)
	if !ok || item["methodName"] != "com.example.service.UserService.findUserById" {
		t.Fatalf("added[0] = %v, want methodName", added[0])
	}
}

func TestGetModelsReturnsModelsArray(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	req := httptest.NewRequest(http.MethodGet, "/v1/models", nil)
	req.Header.Set("Authorization", "Bearer test-token")
	rec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}

	var payload map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	if _, ok := payload["models"].([]any); !ok {
		t.Fatalf("models = %T, want []any", payload["models"])
	}
}

func TestPostModelsReturnsMethodNotAllowed(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	req := httptest.NewRequest(http.MethodPost, "/v1/models", nil)
	req.Header.Set("Authorization", "Bearer test-token")
	rec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want 405", rec.Code)
	}
}

func TestGetFavoritesReturnsFavoritesArray(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	req := httptest.NewRequest(http.MethodGet, "/v1/favorites", nil)
	req.Header.Set("Authorization", "Bearer test-token")
	rec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
}

func TestGetCompletionsEmptyQueryReturnsEmpty(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	req := httptest.NewRequest(http.MethodGet, "/v1/completions", nil)
	req.Header.Set("Authorization", "Bearer test-token")
	rec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
}

func TestGetCompletionsReturnsWorkspaceFileMatches(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	targetDir := filepath.Join(testApp.cfg.WorkspaceDir, "src")
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		t.Fatalf("os.MkdirAll() error = %v", err)
	}
	targetFile := filepath.Join(targetDir, "MainActivity.java")
	if err := os.WriteFile(targetFile, []byte("class MainActivity {}"), 0o644); err != nil {
		t.Fatalf("os.WriteFile() error = %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/v1/completions?query=activity", nil)
	req.Header.Set("Authorization", "Bearer test-token")
	rec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}

	var payload map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	completions, ok := payload["completions"].([]any)
	if !ok || len(completions) == 0 {
		t.Fatalf("completions = %v, want non-empty array", payload["completions"])
	}
}

func TestGetCompletionsReturnsSymbolMatches(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	targetDir := filepath.Join(testApp.cfg.WorkspaceDir, "src", "main", "java", "com", "example", "service")
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		t.Fatalf("os.MkdirAll() error = %v", err)
	}
	targetFile := filepath.Join(targetDir, "UserService.java")
	content := "package com.example.service;\n\npublic class UserService {\n    public String findUserById(String id) {\n        return id;\n    }\n}\n"
	if err := os.WriteFile(targetFile, []byte(content), 0o644); err != nil {
		t.Fatalf("os.WriteFile() error = %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/v1/completions?query=UserSer&limit=20", nil)
	req.Header.Set("Authorization", "Bearer test-token")
	rec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}

	var payload map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	completions, ok := payload["completions"].([]any)
	if !ok || len(completions) == 0 {
		t.Fatalf("completions = %v, want non-empty array", payload["completions"])
	}
	first, ok := completions[0].(map[string]any)
	if !ok {
		t.Fatalf("completions[0] = %T, want map[string]any", completions[0])
	}
	if first["type"] != "class" {
		t.Fatalf("type = %v, want class", first["type"])
	}
	if first["detail"] != "com.example.service.UserService" {
		t.Fatalf("detail = %v, want symbol fqName", first["detail"])
	}
}

func TestGetActivityReturnsEnvelope(t *testing.T) {
	t.Parallel()

	testApp := newTestApp(t)
	req := httptest.NewRequest(http.MethodGet, "/v1/activity", nil)
	req.Header.Set("Authorization", "Bearer test-token")
	rec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}

	var payload map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	if _, ok := payload["groups"]; !ok {
		t.Fatal("groups missing")
	}
}

func TestGetActivityIncludesRecentJobAndDiff(t *testing.T) {
	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "Activity")

	jobID := createJobViaAPI(t, testApp, `{"taskInput":"Design the Go executor architecture","plannerModel":"gpt-5","tags":{"mode":"ARCHITECT"}}`, "activity-job-key")
	status := waitForTerminalJob(t, testApp, jobID)
	if status["state"] != "COMPLETED" {
		t.Fatalf("state = %v, want COMPLETED", status["state"])
	}

	req := httptest.NewRequest(http.MethodGet, "/v1/activity", nil)
	req.Header.Set("Authorization", "Bearer test-token")
	rec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}

	var payload map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	groups, ok := payload["groups"].([]any)
	if !ok || len(groups) == 0 {
		t.Fatalf("groups = %v, want non-empty array", payload["groups"])
	}

	group, ok := groups[0].(map[string]any)
	if !ok {
		t.Fatalf("groups[0] = %T, want map[string]any", groups[0])
	}
	entries, ok := group["entries"].([]any)
	if !ok || len(entries) == 0 {
		t.Fatalf("entries = %v, want non-empty array", group["entries"])
	}
	entry, ok := entries[0].(map[string]any)
	if !ok {
		t.Fatalf("entries[0] = %T, want map[string]any", entries[0])
	}
	if entry["contextId"] != jobID {
		t.Fatalf("contextId = %v, want %s", entry["contextId"], jobID)
	}

	diffReq := httptest.NewRequest(http.MethodGet, "/v1/activity/diff?contextId="+jobID, nil)
	diffReq.Header.Set("Authorization", "Bearer test-token")
	diffRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(diffRec, diffReq)
	if diffRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", diffRec.Code)
	}
	if !strings.Contains(diffRec.Body.String(), "BROKK_ARCHITECT_OUTPUT.md") {
		t.Fatalf("diff = %q, want architect output file", diffRec.Body.String())
	}
}

func TestAskJobCompletesAndEmitsEvents(t *testing.T) {
	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "Ask")

	contextReq := httptest.NewRequest(http.MethodPost, "/v1/context/text", strings.NewReader(`{"text":"hello from chip"}`))
	contextReq.Header.Set("Authorization", "Bearer test-token")
	contextReq.Header.Set("Content-Type", "application/json")
	contextRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(contextRec, contextReq)
	if contextRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", contextRec.Code)
	}

	jobID := createJobViaAPI(t, testApp, `{"taskInput":"What context do I have?","plannerModel":"gpt-5","tags":{"mode":"ASK"}}`, "ask-key")
	status := waitForTerminalJob(t, testApp, jobID)
	if status["state"] != "COMPLETED" {
		t.Fatalf("state = %v, want COMPLETED", status["state"])
	}

	result, ok := status["result"].(map[string]any)
	if !ok {
		t.Fatalf("result = %T, want map[string]any", status["result"])
	}
	output, _ := result["output"].(string)
	if !strings.Contains(output, "hello from chip") {
		t.Fatalf("output = %q, want context text", output)
	}

	eventsReq := httptest.NewRequest(http.MethodGet, "/v1/jobs/"+jobID+"/events", nil)
	eventsReq.Header.Set("Authorization", "Bearer test-token")
	eventsRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(eventsRec, eventsReq)
	if eventsRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", eventsRec.Code)
	}

	var eventsPayload map[string]any
	if err := json.Unmarshal(eventsRec.Body.Bytes(), &eventsPayload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	events, ok := eventsPayload["events"].([]any)
	if !ok || len(events) == 0 {
		t.Fatalf("events = %v, want non-empty array", eventsPayload["events"])
	}
}

func TestSearchJobCompletesAgainstWorkspaceFiles(t *testing.T) {
	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "Search")

	targetDir := filepath.Join(testApp.cfg.WorkspaceDir, "src")
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		t.Fatalf("os.MkdirAll() error = %v", err)
	}
	targetFile := filepath.Join(targetDir, "search-target.txt")
	if err := os.WriteFile(targetFile, []byte("this file contains needle text"), 0o644); err != nil {
		t.Fatalf("os.WriteFile() error = %v", err)
	}

	jobID := createJobViaAPI(t, testApp, `{"taskInput":"needle","plannerModel":"gpt-5","tags":{"mode":"SEARCH"}}`, "search-key")
	status := waitForTerminalJob(t, testApp, jobID)
	if status["state"] != "COMPLETED" {
		t.Fatalf("state = %v, want COMPLETED", status["state"])
	}

	result, ok := status["result"].(map[string]any)
	if !ok {
		t.Fatalf("result = %T, want map[string]any", status["result"])
	}
	output, _ := result["output"].(string)
	if !strings.Contains(strings.ToLower(output), "needle") && !strings.Contains(strings.ToLower(output), "search-target") {
		t.Fatalf("output = %q, want search findings", output)
	}
}

func TestSearchJobReturnsSymbolMatches(t *testing.T) {
	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "SearchSymbols")

	targetDir := filepath.Join(testApp.cfg.WorkspaceDir, "src", "main", "java", "com", "example", "service")
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		t.Fatalf("os.MkdirAll() error = %v", err)
	}
	targetFile := filepath.Join(targetDir, "UserService.java")
	content := "package com.example.service;\n\npublic class UserService {\n    public String findUserById(String id) {\n        return id;\n    }\n}\n"
	if err := os.WriteFile(targetFile, []byte(content), 0o644); err != nil {
		t.Fatalf("os.WriteFile() error = %v", err)
	}

	jobID := createJobViaAPI(t, testApp, `{"taskInput":"findUserById","plannerModel":"gpt-5","tags":{"mode":"SEARCH"}}`, "search-symbol-key")
	status := waitForTerminalJob(t, testApp, jobID)
	if status["state"] != "COMPLETED" {
		t.Fatalf("state = %v, want COMPLETED", status["state"])
	}

	result, ok := status["result"].(map[string]any)
	if !ok {
		t.Fatalf("result = %T, want map[string]any", status["result"])
	}
	output, _ := result["output"].(string)
	if !strings.Contains(output, "Method match: src/main/java/com/example/service/UserService.java") {
		t.Fatalf("output = %q, want symbol file match", output)
	}
	if !strings.Contains(output, "com.example.service.UserService.findUserById") {
		t.Fatalf("output = %q, want symbol fqName", output)
	}
}

func TestPlanJobCompletesAndUpdatesTaskList(t *testing.T) {
	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "Plan")

	contextReq := httptest.NewRequest(http.MethodPost, "/v1/context/text", strings.NewReader(`{"text":"executor routes and job store"}`))
	contextReq.Header.Set("Authorization", "Bearer test-token")
	contextReq.Header.Set("Content-Type", "application/json")
	contextRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(contextRec, contextReq)
	if contextRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", contextRec.Code)
	}

	jobID := createJobViaAPI(t, testApp, `{"taskInput":"Port the planning flow","plannerModel":"gpt-5","tags":{"mode":"PLAN"}}`, "plan-key")
	status := waitForTerminalJob(t, testApp, jobID)
	if status["state"] != "COMPLETED" {
		t.Fatalf("state = %v, want COMPLETED", status["state"])
	}

	result, ok := status["result"].(map[string]any)
	if !ok {
		t.Fatalf("result = %T, want map[string]any", status["result"])
	}
	output, _ := result["output"].(string)
	if !strings.Contains(output, "Port the planning flow") {
		t.Fatalf("output = %q, want plan query", output)
	}

	taskListReq := httptest.NewRequest(http.MethodGet, "/v1/tasklist", nil)
	taskListReq.Header.Set("Authorization", "Bearer test-token")
	taskListRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(taskListRec, taskListReq)
	if taskListRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", taskListRec.Code)
	}

	var taskList map[string]any
	if err := json.Unmarshal(taskListRec.Body.Bytes(), &taskList); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	if taskList["bigPicture"] != "Plan for: Port the planning flow" {
		t.Fatalf("bigPicture = %v, want plan big picture", taskList["bigPicture"])
	}
	tasks, ok := taskList["tasks"].([]any)
	if !ok || len(tasks) < 3 {
		t.Fatalf("tasks = %v, want at least 3 tasks", taskList["tasks"])
	}
}

func TestArchitectJobWritesDiffArtifactAndWorkspaceFile(t *testing.T) {
	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "Architect")

	jobID := createJobViaAPI(t, testApp, `{"taskInput":"Design the Go executor architecture","plannerModel":"gpt-5","tags":{"mode":"ARCHITECT"}}`, "architect-key")
	status := waitForTerminalJob(t, testApp, jobID)
	if status["state"] != "COMPLETED" {
		t.Fatalf("state = %v, want COMPLETED", status["state"])
	}

	outputFile := filepath.Join(testApp.cfg.WorkspaceDir, "BROKK_ARCHITECT_OUTPUT.md")
	content, err := os.ReadFile(outputFile)
	if err != nil {
		t.Fatalf("os.ReadFile() error = %v", err)
	}
	if !strings.Contains(string(content), "Design the Go executor architecture") {
		t.Fatalf("content = %q, want task input", string(content))
	}

	diffReq := httptest.NewRequest(http.MethodGet, "/v1/jobs/"+jobID+"/diff", nil)
	diffReq.Header.Set("Authorization", "Bearer test-token")
	diffRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(diffRec, diffReq)
	if diffRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", diffRec.Code)
	}
	diffText := diffRec.Body.String()
	if !strings.Contains(diffText, "BROKK_ARCHITECT_OUTPUT.md") {
		t.Fatalf("diff = %q, want output file path", diffText)
	}
	if !strings.Contains(diffText, "Design the Go executor architecture") {
		t.Fatalf("diff = %q, want task input", diffText)
	}
}

func TestCodeJobUpdatesAttachedFileAndDiff(t *testing.T) {
	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "Code")

	targetDir := filepath.Join(testApp.cfg.WorkspaceDir, "src")
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		t.Fatalf("os.MkdirAll() error = %v", err)
	}
	targetFile := filepath.Join(targetDir, "main.go")
	if err := os.WriteFile(targetFile, []byte("package main\n\nfunc main() {}\n"), 0o644); err != nil {
		t.Fatalf("os.WriteFile() error = %v", err)
	}

	attachReq := httptest.NewRequest(http.MethodPost, "/v1/context/files", strings.NewReader(`{"relativePaths":["src/main.go"]}`))
	attachReq.Header.Set("Authorization", "Bearer test-token")
	attachReq.Header.Set("Content-Type", "application/json")
	attachRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(attachRec, attachReq)
	if attachRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", attachRec.Code)
	}

	jobID := createJobViaAPI(t, testApp, `{"taskInput":"Implement filename sanitization helper","plannerModel":"gpt-5","codeModel":"gpt-5-mini","tags":{"mode":"CODE"}}`, "code-key")
	status := waitForTerminalJob(t, testApp, jobID)
	if status["state"] != "COMPLETED" {
		t.Fatalf("state = %v, want COMPLETED", status["state"])
	}

	result, ok := status["result"].(map[string]any)
	if !ok {
		t.Fatalf("result = %T, want map[string]any", status["result"])
	}
	output, _ := result["output"].(string)
	if !strings.Contains(output, "gpt-5-mini") {
		t.Fatalf("output = %q, want code model", output)
	}

	content, err := os.ReadFile(targetFile)
	if err != nil {
		t.Fatalf("os.ReadFile() error = %v", err)
	}
	if !strings.Contains(string(content), "Implement filename sanitization helper") {
		t.Fatalf("content = %q, want task input", string(content))
	}

	diffReq := httptest.NewRequest(http.MethodGet, "/v1/jobs/"+jobID+"/diff", nil)
	diffReq.Header.Set("Authorization", "Bearer test-token")
	diffRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(diffRec, diffReq)
	if diffRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", diffRec.Code)
	}
	diffText := diffRec.Body.String()
	if !strings.Contains(diffText, "src/main.go") {
		t.Fatalf("diff = %q, want file path", diffText)
	}
	if !strings.Contains(diffText, "gpt-5-mini") {
		t.Fatalf("diff = %q, want code model marker", diffText)
	}
}

func TestLutzJobPlansExecutesAndPersistsTaskList(t *testing.T) {
	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "Lutz")

	targetDir := filepath.Join(testApp.cfg.WorkspaceDir, "src")
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		t.Fatalf("os.MkdirAll() error = %v", err)
	}
	targetFile := filepath.Join(targetDir, "workflow.md")
	if err := os.WriteFile(targetFile, []byte("# Workflow\n"), 0o644); err != nil {
		t.Fatalf("os.WriteFile() error = %v", err)
	}

	attachReq := httptest.NewRequest(http.MethodPost, "/v1/context/files", strings.NewReader(`{"relativePaths":["src/workflow.md"]}`))
	attachReq.Header.Set("Authorization", "Bearer test-token")
	attachReq.Header.Set("Content-Type", "application/json")
	attachRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(attachRec, attachReq)
	if attachRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", attachRec.Code)
	}

	jobID := createJobViaAPI(t, testApp, `{"taskInput":"Refactor the authentication workflow and add verification notes","plannerModel":"gpt-5","codeModel":"gpt-5-mini","tags":{"mode":"LUTZ"}}`, "lutz-key")
	status := waitForTerminalJob(t, testApp, jobID)
	if status["state"] != "COMPLETED" {
		t.Fatalf("state = %v, want COMPLETED", status["state"])
	}

	taskListReq := httptest.NewRequest(http.MethodGet, "/v1/tasklist", nil)
	taskListReq.Header.Set("Authorization", "Bearer test-token")
	taskListRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(taskListRec, taskListReq)
	if taskListRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", taskListRec.Code)
	}

	var taskList map[string]any
	if err := json.Unmarshal(taskListRec.Body.Bytes(), &taskList); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	if taskList["bigPicture"] != "LUTZ plan for: Refactor the authentication workflow and add verification notes" {
		t.Fatalf("bigPicture = %v, want LUTZ big picture", taskList["bigPicture"])
	}
	tasks, ok := taskList["tasks"].([]any)
	if !ok || len(tasks) != 3 {
		t.Fatalf("tasks = %v, want 3 tasks", taskList["tasks"])
	}
	for _, rawTask := range tasks {
		task, ok := rawTask.(map[string]any)
		if !ok {
			t.Fatalf("task = %T, want map[string]any", rawTask)
		}
		if task["done"] != true {
			t.Fatalf("task done = %v, want true", task["done"])
		}
	}

	content, err := os.ReadFile(targetFile)
	if err != nil {
		t.Fatalf("os.ReadFile() error = %v", err)
	}
	if !strings.Contains(string(content), "LUTZ Task 1/3") || !strings.Contains(string(content), "LUTZ Task 3/3") {
		t.Fatalf("content = %q, want LUTZ task sections", string(content))
	}

	diffReq := httptest.NewRequest(http.MethodGet, "/v1/jobs/"+jobID+"/diff", nil)
	diffReq.Header.Set("Authorization", "Bearer test-token")
	diffRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(diffRec, diffReq)
	if diffRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", diffRec.Code)
	}
	diffText := diffRec.Body.String()
	if !strings.Contains(diffText, "src/workflow.md") {
		t.Fatalf("diff = %q, want file path", diffText)
	}
	if !strings.Contains(diffText, "LUTZ Task 2/3") {
		t.Fatalf("diff = %q, want task content", diffText)
	}

	eventsReq := httptest.NewRequest(http.MethodGet, "/v1/jobs/"+jobID+"/events", nil)
	eventsReq.Header.Set("Authorization", "Bearer test-token")
	eventsRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(eventsRec, eventsReq)
	if eventsRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", eventsRec.Code)
	}

	var eventsPayload map[string]any
	if err := json.Unmarshal(eventsRec.Body.Bytes(), &eventsPayload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	events, ok := eventsPayload["events"].([]any)
	if !ok || len(events) == 0 {
		t.Fatalf("events = %v, want non-empty array", eventsPayload["events"])
	}
	foundTaskListNotice := false
	for _, rawEvent := range events {
		event, ok := rawEvent.(map[string]any)
		if !ok {
			continue
		}
		if event["type"] != "NOTIFICATION" {
			continue
		}
		data, ok := event["data"].(map[string]any)
		if !ok {
			continue
		}
		message, _ := data["message"].(string)
		if strings.Contains(message, "Task list generated with 3 subtasks") {
			foundTaskListNotice = true
			break
		}
	}
	if !foundTaskListNotice {
		t.Fatal("expected LUTZ planning notification in event stream")
	}
}

func TestPrReviewJobCompletesAndWritesReviewArtifacts(t *testing.T) {
	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "Review")

	targetDir := filepath.Join(testApp.cfg.WorkspaceDir, "src")
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		t.Fatalf("os.MkdirAll() error = %v", err)
	}
	targetFile := filepath.Join(targetDir, "review_target.go")
	content := "package main\n\nfunc reviewTarget() {\n\tfmt.Println(\"debug\")\n\t// TODO: remove debug output\n}\n"
	if err := os.WriteFile(targetFile, []byte(content), 0o644); err != nil {
		t.Fatalf("os.WriteFile() error = %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/v1/jobs/pr-review", strings.NewReader(`{"owner":"brokk","repo":"brokk","prNumber":42,"githubToken":"ghp_test","plannerModel":"gpt-5"}`))
	req.Header.Set("Authorization", "Bearer test-token")
	req.Header.Set("Idempotency-Key", "pr-review-key")
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec, req)
	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201", rec.Code)
	}

	var createPayload map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &createPayload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	jobID := createPayload["jobId"].(string)

	status := waitForTerminalJob(t, testApp, jobID)
	if status["state"] != "COMPLETED" {
		t.Fatalf("state = %v, want COMPLETED", status["state"])
	}

	result, ok := status["result"].(map[string]any)
	if !ok {
		t.Fatalf("result = %T, want map[string]any", status["result"])
	}
	if result["mode"] != "REVIEW" {
		t.Fatalf("mode = %v, want REVIEW", result["mode"])
	}
	output, _ := result["output"].(string)
	if !strings.Contains(output, "Pull request: brokk/brokk#42") {
		t.Fatalf("output = %q, want pull request identifier", output)
	}
	if !strings.Contains(output, "Inline comments prepared: 2") {
		t.Fatalf("output = %q, want inline comments count", output)
	}
	if !strings.Contains(output, "Review provider: heuristic") {
		t.Fatalf("output = %q, want review provider", output)
	}
	if !strings.Contains(output, "Artifacts: review-prompt.txt, review-context.md, review-context.json, review-response.json, review.md, review.json, diff.txt, annotated-diff.txt") {
		t.Fatalf("output = %q, want review artifacts", output)
	}

	promptArtifact := filepath.Join(testApp.cfg.WorkspaceDir, ".brokk", "jobs", jobID, "artifacts", "review-prompt.txt")
	promptText, err := os.ReadFile(promptArtifact)
	if err != nil {
		t.Fatalf("os.ReadFile() error = %v", err)
	}
	if !strings.Contains(string(promptText), "OUTPUT ONLY THE JSON OBJECT") {
		t.Fatalf("review-prompt.txt = %q, want prompt contract", string(promptText))
	}
	if !strings.Contains(string(promptText), "WORKSPACE CONTEXT") {
		t.Fatalf("review-prompt.txt = %q, want workspace context block", string(promptText))
	}

	contextArtifact := filepath.Join(testApp.cfg.WorkspaceDir, ".brokk", "jobs", jobID, "artifacts", "review-context.json")
	contextJSON, err := os.ReadFile(contextArtifact)
	if err != nil {
		t.Fatalf("os.ReadFile() error = %v", err)
	}
	if !strings.Contains(string(contextJSON), "\"summaryMarkdown\"") || !strings.Contains(string(contextJSON), "\"items\"") {
		t.Fatalf("review-context.json = %q, want summaryMarkdown and items", string(contextJSON))
	}

	reviewArtifact := filepath.Join(testApp.cfg.WorkspaceDir, ".brokk", "jobs", jobID, "artifacts", "review.json")
	reviewJSON, err := os.ReadFile(reviewArtifact)
	if err != nil {
		t.Fatalf("os.ReadFile() error = %v", err)
	}
	if !strings.Contains(string(reviewJSON), "\"summaryMarkdown\"") {
		t.Fatalf("review.json = %q, want summaryMarkdown", string(reviewJSON))
	}
	if !strings.Contains(string(reviewJSON), "\"bodyMarkdown\"") {
		t.Fatalf("review.json = %q, want bodyMarkdown", string(reviewJSON))
	}
	if !strings.Contains(string(reviewJSON), "\"severity\": \"HIGH\"") {
		t.Fatalf("review.json = %q, want severity", string(reviewJSON))
	}
	if !strings.Contains(string(reviewJSON), "## Brokk PR Review") {
		t.Fatalf("review.json = %q, want Brokk PR Review heading", string(reviewJSON))
	}

	diffReq := httptest.NewRequest(http.MethodGet, "/v1/jobs/"+jobID+"/diff", nil)
	diffReq.Header.Set("Authorization", "Bearer test-token")
	diffRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(diffRec, diffReq)
	if diffRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", diffRec.Code)
	}
	if !strings.Contains(diffRec.Body.String(), "review_target.go") {
		t.Fatalf("diff = %q, want review target path", diffRec.Body.String())
	}

	eventsReq := httptest.NewRequest(http.MethodGet, "/v1/jobs/"+jobID+"/events", nil)
	eventsReq.Header.Set("Authorization", "Bearer test-token")
	eventsRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(eventsRec, eventsReq)
	if eventsRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", eventsRec.Code)
	}
	if !strings.Contains(eventsRec.Body.String(), "reviewReady") {
		t.Fatalf("events = %q, want reviewReady state hint", eventsRec.Body.String())
	}
	if !strings.Contains(eventsRec.Body.String(), "Brokk Context Engine: analyzing repository context for PR review...") {
		t.Fatalf("events = %q, want review context start notification", eventsRec.Body.String())
	}
	if !strings.Contains(eventsRec.Body.String(), "Review generation provider: heuristic") {
		t.Fatalf("events = %q, want review provider notification", eventsRec.Body.String())
	}
}

func TestPrReviewJobPostsSummaryAndInlineCommentsToGitHub(t *testing.T) {
	workspaceDir := filepath.Join(t.TempDir(), "workspace")
	runGit(t, filepath.Dir(workspaceDir), "init", "--initial-branch=main", workspaceDir)
	runGit(t, workspaceDir, "config", "user.name", "Test User")
	runGit(t, workspaceDir, "config", "user.email", "test@example.com")

	testApp := newTestAppForWorkspace(t, workspaceDir)
	createSessionViaAPI(t, testApp, "ReviewGitHub")

	targetDir := filepath.Join(testApp.cfg.WorkspaceDir, "src")
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		t.Fatalf("os.MkdirAll() error = %v", err)
	}
	targetFile := filepath.Join(targetDir, "review_target.go")
	content := "package main\n\nfunc reviewTarget() {\n\tfmt.Println(\"debug\")\n\t// TODO: remove debug output\n}\n"
	if err := os.WriteFile(targetFile, []byte(content), 0o644); err != nil {
		t.Fatalf("os.WriteFile() error = %v", err)
	}
	runGit(t, testApp.cfg.WorkspaceDir, "add", ".")
	runGit(t, testApp.cfg.WorkspaceDir, "commit", "-m", "seed review target")

	type inlinePayload struct {
		Body     string
		CommitID string
		Path     string
		Line     int
	}
	var mu sync.Mutex
	var issueBodies []string
	var inlinePosts []inlinePayload

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/repos/brokk/brokk/pulls/42":
			httpserver.WriteJSON(w, http.StatusOK, map[string]any{
				"title": "Review title",
				"body":  "Review body",
				"base":  map[string]any{"ref": "main"},
				"head":  map[string]any{"sha": "abc123", "ref": "feature/review"},
			})
		case r.Method == http.MethodGet && r.URL.Path == "/repos/brokk/brokk/pulls/42/comments":
			httpserver.WriteJSON(w, http.StatusOK, []map[string]any{
				{"path": "src/review_target.go", "line": 4},
			})
		case r.Method == http.MethodPost && r.URL.Path == "/repos/brokk/brokk/issues/42/comments":
			var payload map[string]any
			if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
				t.Fatalf("Decode() error = %v", err)
			}
			mu.Lock()
			issueBodies = append(issueBodies, payload["body"].(string))
			mu.Unlock()
			httpserver.WriteJSON(w, http.StatusCreated, map[string]any{"id": 1})
		case r.Method == http.MethodPost && r.URL.Path == "/repos/brokk/brokk/pulls/42/comments":
			var payload map[string]any
			if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
				t.Fatalf("Decode() error = %v", err)
			}
			mu.Lock()
			inlinePosts = append(inlinePosts, inlinePayload{
				Body:     payload["body"].(string),
				CommitID: payload["commit_id"].(string),
				Path:     payload["path"].(string),
				Line:     int(payload["line"].(float64)),
			})
			mu.Unlock()
			httpserver.WriteJSON(w, http.StatusCreated, map[string]any{"id": 2})
		default:
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
	}))
	defer server.Close()

	req := httptest.NewRequest(http.MethodPost, "/v1/jobs/pr-review", strings.NewReader(`{"owner":"brokk","repo":"brokk","prNumber":42,"githubToken":"ghp_test","githubApiUrl":"`+server.URL+`","plannerModel":"gpt-5"}`))
	req.Header.Set("Authorization", "Bearer test-token")
	req.Header.Set("Idempotency-Key", "pr-review-github-key")
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec, req)
	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201", rec.Code)
	}

	var createPayload map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &createPayload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	jobID := createPayload["jobId"].(string)
	status := waitForTerminalJob(t, testApp, jobID)
	if status["state"] != "COMPLETED" {
		t.Fatalf("state = %v, want COMPLETED", status["state"])
	}

	result := status["result"].(map[string]any)
	output := result["output"].(string)
	if !strings.Contains(output, "GitHub posting complete: summary posted, 1 inline comment(s) posted, 1 duplicate(s) skipped") {
		t.Fatalf("output = %q, want GitHub posting summary", output)
	}

	mu.Lock()
	defer mu.Unlock()
	if len(issueBodies) != 1 {
		t.Fatalf("len(issueBodies) = %d, want 1 summary comment", len(issueBodies))
	}
	if !strings.Contains(issueBodies[0], "## Brokk PR Review") {
		t.Fatalf("issueBodies[0] = %q, want review summary", issueBodies[0])
	}
	if len(inlinePosts) != 1 {
		t.Fatalf("len(inlinePosts) = %d, want 1 inline comment", len(inlinePosts))
	}
	if inlinePosts[0].CommitID != "abc123" {
		t.Fatalf("commitID = %q, want abc123", inlinePosts[0].CommitID)
	}
	if inlinePosts[0].Path != "src/review_target.go" || inlinePosts[0].Line != 5 {
		t.Fatalf("inline post = %+v, want line 5 comment", inlinePosts[0])
	}
}

func TestPrReviewJobFetchesRemotePrRefsForDiff(t *testing.T) {
	rootDir := t.TempDir()
	remoteDir := filepath.Join(rootDir, "remote.git")
	seedDir := filepath.Join(rootDir, "seed")
	workspaceDir := filepath.Join(rootDir, "workspace")

	runGit(t, rootDir, "init", "--bare", "--initial-branch=main", remoteDir)
	runGit(t, rootDir, "init", "--initial-branch=main", seedDir)
	runGit(t, seedDir, "config", "user.name", "Test User")
	runGit(t, seedDir, "config", "user.email", "test@example.com")
	if err := os.MkdirAll(filepath.Join(seedDir, "src"), 0o755); err != nil {
		t.Fatalf("os.MkdirAll() error = %v", err)
	}
	if err := os.WriteFile(filepath.Join(seedDir, "src", "review_target.go"), []byte("package main\n\nfunc reviewTarget() {\n\tprintln(\"clean\")\n}\n"), 0o644); err != nil {
		t.Fatalf("os.WriteFile() error = %v", err)
	}
	runGit(t, seedDir, "add", ".")
	runGit(t, seedDir, "commit", "-m", "base commit")
	runGit(t, seedDir, "remote", "add", "origin", remoteDir)
	runGit(t, seedDir, "push", "origin", "main")

	runGit(t, seedDir, "checkout", "-b", "feature/pr-42")
	if err := os.WriteFile(filepath.Join(seedDir, "src", "review_target.go"), []byte("package main\n\nfunc reviewTarget() {\n\tfmt.Println(\"debug\")\n\t// TODO: remove debug output\n}\n"), 0o644); err != nil {
		t.Fatalf("os.WriteFile() error = %v", err)
	}
	runGit(t, seedDir, "add", ".")
	runGit(t, seedDir, "commit", "-m", "feature commit")
	featureSHA := strings.TrimSpace(runGit(t, seedDir, "rev-parse", "HEAD"))
	runGit(t, seedDir, "push", "origin", "feature/pr-42")
	runGit(t, remoteDir, "update-ref", "refs/pull/42/head", featureSHA)

	runGit(t, rootDir, "init", "--initial-branch=main", workspaceDir)
	runGit(t, workspaceDir, "config", "user.name", "Test User")
	runGit(t, workspaceDir, "config", "user.email", "test@example.com")
	runGit(t, workspaceDir, "remote", "add", "origin", remoteDir)
	runGit(t, workspaceDir, "fetch", "origin", "main:refs/remotes/origin/main")
	runGit(t, workspaceDir, "checkout", "-B", "main", "origin/main")

	cfg := config.Config{
		ExecID:       "550e8400-e29b-41d4-a716-446655440000",
		ListenAddr:   "127.0.0.1:0",
		AuthToken:    "test-token",
		WorkspaceDir: workspaceDir,
	}
	testApp, err := New(cfg)
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	createSessionViaAPI(t, testApp, "ReviewRemote")

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/repos/brokk/brokk/pulls/42":
			httpserver.WriteJSON(w, http.StatusOK, map[string]any{
				"title": "Review title",
				"body":  "Review body",
				"base":  map[string]any{"ref": "main"},
				"head":  map[string]any{"sha": featureSHA, "ref": "feature/pr-42"},
			})
		case r.Method == http.MethodGet && r.URL.Path == "/repos/brokk/brokk/pulls/42/comments":
			httpserver.WriteJSON(w, http.StatusOK, []map[string]any{})
		case r.Method == http.MethodPost && (r.URL.Path == "/repos/brokk/brokk/issues/42/comments" || r.URL.Path == "/repos/brokk/brokk/pulls/42/comments"):
			httpserver.WriteJSON(w, http.StatusCreated, map[string]any{"id": 1})
		default:
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
	}))
	defer server.Close()

	req := httptest.NewRequest(http.MethodPost, "/v1/jobs/pr-review", strings.NewReader(`{"owner":"brokk","repo":"brokk","prNumber":42,"githubToken":"ghp_test","githubApiUrl":"`+server.URL+`","plannerModel":"gpt-5"}`))
	req.Header.Set("Authorization", "Bearer test-token")
	req.Header.Set("Idempotency-Key", "pr-review-remote-diff-key")
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec, req)
	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201", rec.Code)
	}

	var createPayload map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &createPayload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	jobID := createPayload["jobId"].(string)
	status := waitForTerminalJob(t, testApp, jobID)
	if status["state"] != "COMPLETED" {
		t.Fatalf("state = %v, want COMPLETED", status["state"])
	}

	diffReq := httptest.NewRequest(http.MethodGet, "/v1/jobs/"+jobID+"/diff", nil)
	diffReq.Header.Set("Authorization", "Bearer test-token")
	diffRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(diffRec, diffReq)
	if diffRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", diffRec.Code)
	}
	diffText := diffRec.Body.String()
	if !strings.Contains(diffText, "TODO: remove debug output") {
		t.Fatalf("diff = %q, want fetched PR change", diffText)
	}

	eventsReq := httptest.NewRequest(http.MethodGet, "/v1/jobs/"+jobID+"/events", nil)
	eventsReq.Header.Set("Authorization", "Bearer test-token")
	eventsRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(eventsRec, eventsReq)
	if eventsRec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", eventsRec.Code)
	}
	if !strings.Contains(eventsRec.Body.String(), "Fetching PR refs from remote 'origin'...") {
		t.Fatalf("events = %q, want remote fetch notification", eventsRec.Body.String())
	}
}

func TestCancelJobMarksStatusCancelled(t *testing.T) {
	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "Cancel")

	targetDir := filepath.Join(testApp.cfg.WorkspaceDir, "cancel-search")
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		t.Fatalf("os.MkdirAll() error = %v", err)
	}
	content := strings.Repeat("background text for cancellation search\n", 2048)
	for i := 0; i < 40; i++ {
		targetFile := filepath.Join(targetDir, "file-"+strconv.Itoa(i)+".txt")
		if err := os.WriteFile(targetFile, []byte(content), 0o644); err != nil {
			t.Fatalf("os.WriteFile() error = %v", err)
		}
	}

	jobID := createJobViaAPI(t, testApp, `{"taskInput":"needle that does not exist","plannerModel":"gpt-5","tags":{"mode":"SEARCH"}}`, "cancel-key")
	waitForJobState(t, testApp, jobID, "RUNNING")

	cancelReq := httptest.NewRequest(http.MethodPost, "/v1/jobs/"+jobID+"/cancel", nil)
	cancelReq.Header.Set("Authorization", "Bearer test-token")
	cancelRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(cancelRec, cancelReq)

	if cancelRec.Code != http.StatusAccepted {
		t.Fatalf("status = %d, want 202", cancelRec.Code)
	}

	status := waitForTerminalJob(t, testApp, jobID)
	if status["state"] != "CANCELLED" {
		t.Fatalf("state = %v, want CANCELLED", status["state"])
	}
}

func TestPostJobsReturnsConflictWhenAnotherJobIsRunning(t *testing.T) {
	testApp := newTestApp(t)
	createSessionViaAPI(t, testApp, "Reservation")

	targetDir := filepath.Join(testApp.cfg.WorkspaceDir, "reservation-search")
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		t.Fatalf("os.MkdirAll() error = %v", err)
	}
	content := strings.Repeat("background text for reservation search\n", 2048)
	for i := 0; i < 40; i++ {
		targetFile := filepath.Join(targetDir, "file-"+strconv.Itoa(i)+".txt")
		if err := os.WriteFile(targetFile, []byte(content), 0o644); err != nil {
			t.Fatalf("os.WriteFile() error = %v", err)
		}
	}

	firstJobID := createJobViaAPI(t, testApp, `{"taskInput":"needle that does not exist","plannerModel":"gpt-5","tags":{"mode":"SEARCH"}}`, "reservation-key-1")
	waitForJobState(t, testApp, firstJobID, "RUNNING")

	req := httptest.NewRequest(http.MethodPost, "/v1/jobs", strings.NewReader(`{"taskInput":"blocked job","plannerModel":"gpt-5","tags":{"mode":"ASK"}}`))
	req.Header.Set("Authorization", "Bearer test-token")
	req.Header.Set("Idempotency-Key", "reservation-key-2")
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusConflict {
		t.Fatalf("status = %d, want 409", rec.Code)
	}

	var payload map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	if payload["code"] != "JOB_IN_PROGRESS" {
		t.Fatalf("code = %v, want JOB_IN_PROGRESS", payload["code"])
	}

	cancelReq := httptest.NewRequest(http.MethodPost, "/v1/jobs/"+firstJobID+"/cancel", nil)
	cancelReq.Header.Set("Authorization", "Bearer test-token")
	cancelRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(cancelRec, cancelReq)
	if cancelRec.Code != http.StatusAccepted {
		t.Fatalf("status = %d, want 202", cancelRec.Code)
	}
	waitForTerminalJob(t, testApp, firstJobID)
}

func newTestApp(t *testing.T) *App {
	t.Helper()

	return newTestAppForWorkspace(t, filepath.Join(t.TempDir(), "workspace"))
}

func newTestAppForWorkspace(t *testing.T, workspaceDir string) *App {
	t.Helper()

	cfg := config.Config{
		ExecID:       "550e8400-e29b-41d4-a716-446655440000",
		ListenAddr:   "127.0.0.1:0",
		AuthToken:    "test-token",
		WorkspaceDir: workspaceDir,
	}
	testApp, err := New(cfg)
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	return testApp
}

func createSessionViaAPI(t *testing.T, testApp *App, name string) string {
	t.Helper()

	req := httptest.NewRequest(http.MethodPost, "/v1/sessions", strings.NewReader(`{"name":"`+name+`"}`))
	req.Header.Set("Authorization", "Bearer test-token")
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201", rec.Code)
	}

	var payload map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	return payload["sessionId"].(string)
}

func createJobViaAPI(t *testing.T, testApp *App, body string, key string) string {
	t.Helper()

	req := httptest.NewRequest(http.MethodPost, "/v1/jobs", strings.NewReader(body))
	req.Header.Set("Authorization", "Bearer test-token")
	req.Header.Set("Idempotency-Key", key)
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusCreated && rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 201/200", rec.Code)
	}

	var payload map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	return payload["jobId"].(string)
}

func waitForTerminalJob(t *testing.T, testApp *App, jobID string) map[string]any {
	t.Helper()

	deadline := time.Now().Add(20 * time.Second)
	var lastPayload map[string]any
	for time.Now().Before(deadline) {
		req := httptest.NewRequest(http.MethodGet, "/v1/jobs/"+jobID, nil)
		req.Header.Set("Authorization", "Bearer test-token")
		rec := httptest.NewRecorder()
		testApp.Handler().ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200", rec.Code)
		}

		var payload map[string]any
		if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
			t.Fatalf("json.Unmarshal() error = %v", err)
		}
		lastPayload = payload
		state, _ := payload["state"].(string)
		if state == "COMPLETED" || state == "FAILED" || state == "CANCELLED" {
			return payload
		}
		time.Sleep(25 * time.Millisecond)
	}

	eventsReq := httptest.NewRequest(http.MethodGet, "/v1/jobs/"+jobID+"/events", nil)
	eventsReq.Header.Set("Authorization", "Bearer test-token")
	eventsRec := httptest.NewRecorder()
	testApp.Handler().ServeHTTP(eventsRec, eventsReq)
	t.Fatalf("job %s did not reach terminal state; last status=%v; events=%s", jobID, lastPayload, eventsRec.Body.String())
	return nil
}

func runGit(t *testing.T, workdir string, args ...string) string {
	t.Helper()

	cmd := exec.Command("git", args...)
	cmd.Dir = workdir
	output, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("git %s failed: %v\n%s", strings.Join(args, " "), err, string(output))
	}
	return string(output)
}

func waitForJobState(t *testing.T, testApp *App, jobID string, wantState string) map[string]any {
	t.Helper()

	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		req := httptest.NewRequest(http.MethodGet, "/v1/jobs/"+jobID, nil)
		req.Header.Set("Authorization", "Bearer test-token")
		rec := httptest.NewRecorder()
		testApp.Handler().ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200", rec.Code)
		}

		var payload map[string]any
		if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
			t.Fatalf("json.Unmarshal() error = %v", err)
		}
		state, _ := payload["state"].(string)
		if state == wantState {
			return payload
		}
		if state == "COMPLETED" || state == "FAILED" || state == "CANCELLED" {
			t.Fatalf("job %s reached terminal state %s before %s", jobID, state, wantState)
		}
		time.Sleep(10 * time.Millisecond)
	}

	t.Fatalf("job %s did not reach state %s", jobID, wantState)
	return nil
}

func buildSessionZip(t *testing.T, sessionID string, name string) []byte {
	t.Helper()

	buf := &bytes.Buffer{}
	zw := zip.NewWriter(buf)
	f, err := zw.Create("manifest.json")
	if err != nil {
		t.Fatalf("Create() error = %v", err)
	}

	payload, err := json.Marshal(map[string]any{
		"id":       sessionID,
		"name":     name,
		"created":  1,
		"modified": 2,
		"version":  "4.0",
	})
	if err != nil {
		t.Fatalf("json.Marshal() error = %v", err)
	}
	if _, err := f.Write(payload); err != nil {
		t.Fatalf("Write() error = %v", err)
	}
	if err := zw.Close(); err != nil {
		t.Fatalf("Close() error = %v", err)
	}
	return buf.Bytes()
}
