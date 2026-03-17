package runtime

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func newAuthedRequest(method, url, token string, body []byte) *http.Request {
	req, _ := http.NewRequest(method, url, bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+token)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	return req
}

func TestExecutorSessionsListCurrentAndSwitch(t *testing.T) {
	cfg := Config{ExecID: "exec-1", ListenAddr: "127.0.0.1:0", AuthToken: "token", WorkspaceDir: t.TempDir()}
	executor, err := NewExecutor(cfg)
	if err != nil {
		t.Fatalf("NewExecutor returned error: %v", err)
	}
	server := httptest.NewServer(executor.routes())
	defer server.Close()

	client := server.Client()
	for _, name := range []string{"One", "Two"} {
		resp, err := client.Do(newAuthedRequest(http.MethodPost, server.URL+"/v1/sessions", cfg.AuthToken, []byte(`{"name":"`+name+`"}`)))
		if err != nil {
			t.Fatalf("create session request failed: %v", err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusCreated {
			t.Fatalf("unexpected session create status: %d", resp.StatusCode)
		}
	}

	listResp, err := client.Do(newAuthedRequest(http.MethodGet, server.URL+"/v1/sessions", cfg.AuthToken, nil))
	if err != nil {
		t.Fatalf("list sessions request failed: %v", err)
	}
	defer listResp.Body.Close()
	var listed struct {
		Sessions         []map[string]any `json:"sessions"`
		CurrentSessionID string           `json:"currentSessionId"`
	}
	if err := json.NewDecoder(listResp.Body).Decode(&listed); err != nil {
		t.Fatalf("decode sessions response: %v", err)
	}
	if len(listed.Sessions) != 2 {
		t.Fatalf("expected 2 sessions, got %d", len(listed.Sessions))
	}
	if listed.CurrentSessionID == "" {
		t.Fatal("expected currentSessionId to be populated")
	}

	currentResp, err := client.Do(newAuthedRequest(http.MethodGet, server.URL+"/v1/sessions/current", cfg.AuthToken, nil))
	if err != nil {
		t.Fatalf("current session request failed: %v", err)
	}
	defer currentResp.Body.Close()
	var current map[string]any
	if err := json.NewDecoder(currentResp.Body).Decode(&current); err != nil {
		t.Fatalf("decode current session response: %v", err)
	}
	currentID, _ := current["id"].(string)
	if currentID != listed.CurrentSessionID {
		t.Fatalf("expected current session %s, got %s", listed.CurrentSessionID, currentID)
	}

	targetID, _ := listed.Sessions[1]["id"].(string)
	switchBody := []byte(`{"sessionId":"` + targetID + `"}`)
	switchResp, err := client.Do(newAuthedRequest(http.MethodPost, server.URL+"/v1/sessions/switch", cfg.AuthToken, switchBody))
	if err != nil {
		t.Fatalf("switch session request failed: %v", err)
	}
	defer switchResp.Body.Close()
	if switchResp.StatusCode != http.StatusOK {
		t.Fatalf("unexpected switch status: %d", switchResp.StatusCode)
	}
}

func TestExecutorContextTextConversationAndActivity(t *testing.T) {
	cfg := Config{ExecID: "exec-1", ListenAddr: "127.0.0.1:0", AuthToken: "token", WorkspaceDir: t.TempDir()}
	executor, err := NewExecutor(cfg)
	if err != nil {
		t.Fatalf("NewExecutor returned error: %v", err)
	}
	server := httptest.NewServer(executor.routes())
	defer server.Close()

	client := server.Client()
	createResp, err := client.Do(newAuthedRequest(http.MethodPost, server.URL+"/v1/sessions", cfg.AuthToken, []byte(`{"name":"Test"}`)))
	if err != nil {
		t.Fatalf("create session request failed: %v", err)
	}
	createResp.Body.Close()

	textResp, err := client.Do(newAuthedRequest(http.MethodPost, server.URL+"/v1/context/text", cfg.AuthToken, []byte(`{"text":"hello from pasted text"}`)))
	if err != nil {
		t.Fatalf("add text request failed: %v", err)
	}
	defer textResp.Body.Close()
	if textResp.StatusCode != http.StatusOK {
		t.Fatalf("unexpected add text status: %d", textResp.StatusCode)
	}

	jobReq := newAuthedRequest(http.MethodPost, server.URL+"/v1/jobs", cfg.AuthToken, []byte(`{"taskInput":"hello world","plannerModel":"gpt-5.2","tags":{"mode":"ASK"}}`))
	jobReq.Header.Set("Idempotency-Key", "job-1")
	jobResp, err := client.Do(jobReq)
	if err != nil {
		t.Fatalf("job request failed: %v", err)
	}
	defer jobResp.Body.Close()
	if jobResp.StatusCode != http.StatusCreated {
		t.Fatalf("unexpected job status: %d", jobResp.StatusCode)
	}

	time.Sleep(80 * time.Millisecond)

	convResp, err := client.Do(newAuthedRequest(http.MethodGet, server.URL+"/v1/context/conversation", cfg.AuthToken, nil))
	if err != nil {
		t.Fatalf("conversation request failed: %v", err)
	}
	defer convResp.Body.Close()
	var conversation struct {
		Entries []ConversationEntry `json:"entries"`
	}
	if err := json.NewDecoder(convResp.Body).Decode(&conversation); err != nil {
		t.Fatalf("decode conversation response: %v", err)
	}
	if len(conversation.Entries) == 0 {
		t.Fatal("expected conversation entries after job")
	}

	activityResp, err := client.Do(newAuthedRequest(http.MethodGet, server.URL+"/v1/activity", cfg.AuthToken, nil))
	if err != nil {
		t.Fatalf("activity request failed: %v", err)
	}
	defer activityResp.Body.Close()
	var activity struct {
		Groups []map[string]any `json:"groups"`
	}
	if err := json.NewDecoder(activityResp.Body).Decode(&activity); err != nil {
		t.Fatalf("decode activity response: %v", err)
	}
	if len(activity.Groups) == 0 {
		t.Fatal("expected activity groups")
	}
}

func TestExecutorJobValidationAndStatus(t *testing.T) {
	cfg := Config{ExecID: "exec-1", ListenAddr: "127.0.0.1:0", AuthToken: "token", WorkspaceDir: t.TempDir()}
	executor, err := NewExecutor(cfg)
	if err != nil {
		t.Fatalf("NewExecutor returned error: %v", err)
	}
	server := httptest.NewServer(executor.routes())
	defer server.Close()

	client := server.Client()
	createResp, err := client.Do(newAuthedRequest(http.MethodPost, server.URL+"/v1/sessions", cfg.AuthToken, []byte(`{"name":"Test"}`)))
	if err != nil {
		t.Fatalf("create session request failed: %v", err)
	}
	createResp.Body.Close()

	missingKeyResp, err := client.Do(newAuthedRequest(http.MethodPost, server.URL+"/v1/jobs", cfg.AuthToken, []byte(`{"taskInput":"hello","plannerModel":"gpt-5.2"}`)))
	if err != nil {
		t.Fatalf("missing key request failed: %v", err)
	}
	defer missingKeyResp.Body.Close()
	if missingKeyResp.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400 for missing idempotency key, got %d", missingKeyResp.StatusCode)
	}

	jobReq := newAuthedRequest(http.MethodPost, server.URL+"/v1/jobs", cfg.AuthToken, []byte(`{"taskInput":"hello","plannerModel":"gpt-5.2"}`))
	jobReq.Header.Set("Idempotency-Key", "job-2")
	jobResp, err := client.Do(jobReq)
	if err != nil {
		t.Fatalf("job request failed: %v", err)
	}
	defer jobResp.Body.Close()
	var created struct {
		JobID string `json:"jobId"`
	}
	if err := json.NewDecoder(jobResp.Body).Decode(&created); err != nil {
		t.Fatalf("decode job response: %v", err)
	}
	if created.JobID == "" {
		t.Fatal("expected jobId")
	}

	time.Sleep(50 * time.Millisecond)

	statusResp, err := client.Do(newAuthedRequest(http.MethodGet, server.URL+"/v1/jobs/"+created.JobID, cfg.AuthToken, nil))
	if err != nil {
		t.Fatalf("status request failed: %v", err)
	}
	defer statusResp.Body.Close()
	var status map[string]any
	if err := json.NewDecoder(statusResp.Body).Decode(&status); err != nil {
		t.Fatalf("decode status response: %v", err)
	}
	if status["state"] == "" {
		t.Fatal("expected state in status payload")
	}
	if _, ok := status["progressPercent"]; !ok {
		t.Fatal("expected progressPercent in status payload")
	}
}
