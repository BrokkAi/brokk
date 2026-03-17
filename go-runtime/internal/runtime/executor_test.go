package runtime

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestExecutorSessionAndJobFlow(t *testing.T) {
	cfg := Config{ExecID: "exec-1", ListenAddr: "127.0.0.1:0", AuthToken: "token", WorkspaceDir: t.TempDir()}
	executor, err := NewExecutor(cfg)
	if err != nil {
		t.Fatalf("NewExecutor returned error: %v", err)
	}
	server := httptest.NewServer(executor.routes())
	defer server.Close()

	client := server.Client()
	req, _ := http.NewRequest(http.MethodPost, server.URL+"/v1/sessions", bytes.NewBufferString(`{"name":"Test"}`))
	req.Header.Set("Authorization", "Bearer token")
	req.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("create session request failed: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("unexpected status: %d", resp.StatusCode)
	}

	readyResp, err := client.Get(server.URL + "/health/ready")
	if err != nil {
		t.Fatalf("health ready request failed: %v", err)
	}
	if readyResp.StatusCode != http.StatusOK {
		t.Fatalf("unexpected health ready status: %d", readyResp.StatusCode)
	}

	req, _ = http.NewRequest(http.MethodPost, server.URL+"/v1/jobs", bytes.NewBufferString(`{"taskInput":"hello world","plannerModel":"gpt-5.2","tags":{"mode":"ASK"}}`))
	req.Header.Set("Authorization", "Bearer token")
	req.Header.Set("Idempotency-Key", "test-key")
	req.Header.Set("Content-Type", "application/json")
	jobResp, err := client.Do(req)
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
		t.Fatal("expected jobId in response")
	}

	time.Sleep(50 * time.Millisecond)

	req, _ = http.NewRequest(http.MethodGet, server.URL+"/v1/jobs/"+created.JobID+"/events?after=-1&limit=100", nil)
	req.Header.Set("Authorization", "Bearer token")
	eventsResp, err := client.Do(req)
	if err != nil {
		t.Fatalf("events request failed: %v", err)
	}
	defer eventsResp.Body.Close()
	var payload struct {
		Events []Event `json:"events"`
		State  string  `json:"state"`
	}
	if err := json.NewDecoder(eventsResp.Body).Decode(&payload); err != nil {
		t.Fatalf("decode events response: %v", err)
	}
	if len(payload.Events) == 0 {
		t.Fatal("expected events")
	}
	if payload.State == "" {
		t.Fatal("expected state in events response")
	}
}
