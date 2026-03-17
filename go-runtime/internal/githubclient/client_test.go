package githubclient

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestPostLineCommentFallsBackToIssueCommentOn422(t *testing.T) {
	t.Parallel()

	var issueBodies []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/repos/o/r/pulls/42/comments":
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUnprocessableEntity)
			_, _ = w.Write([]byte(`{"message":"line not in diff"}`))
		case r.Method == http.MethodPost && r.URL.Path == "/repos/o/r/issues/42/comments":
			var payload map[string]any
			if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
				t.Fatalf("Decode() error = %v", err)
			}
			issueBodies = append(issueBodies, payload["body"].(string))
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"id":1}`))
		default:
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
	}))
	defer server.Close()

	client := New(server.URL, "ghp_test")
	err := client.PostLineComment(context.Background(), "o", "r", 42, "src/main.go", 7, "Please fix this.", "abc123")
	if err != nil {
		t.Fatalf("PostLineComment() error = %v", err)
	}
	if len(issueBodies) != 1 {
		t.Fatalf("len(issueBodies) = %d, want 1", len(issueBodies))
	}
	if !strings.Contains(issueBodies[0], "**Comment on `src/main.go` line 7:**") {
		t.Fatalf("issueBodies[0] = %q, want fallback heading", issueBodies[0])
	}
}
