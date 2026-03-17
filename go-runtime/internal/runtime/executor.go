package runtime

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
)

type Executor struct {
	cfg        Config
	sessions   *SessionStore
	jobs       *JobStore
	fragmentID atomic.Int64
	server     *http.Server
}

func NewExecutor(cfg Config) (*Executor, error) {
	sessions, err := NewSessionStore(cfg.WorkspaceDir)
	if err != nil {
		return nil, err
	}
	executor := &Executor{cfg: cfg, sessions: sessions, jobs: NewJobStore()}
	executor.server = &http.Server{Handler: executor.routes()}
	return executor, nil
}

func (e *Executor) Serve(listener net.Listener) error {
	defer e.server.Shutdown(context.Background())
	return e.server.Serve(listener)
}

func (e *Executor) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health/live", e.handleHealthLive)
	mux.HandleFunc("/health/ready", e.handleHealthReady)
	mux.HandleFunc("/v1/executor", e.handleExecutorInfo)
	mux.Handle("/v1/sessions", e.auth(http.HandlerFunc(e.handleSessions)))
	mux.Handle("/v1/sessions/", e.auth(http.HandlerFunc(e.handleSessionByID)))
	mux.Handle("/v1/jobs", e.auth(http.HandlerFunc(e.handleJobs)))
	mux.Handle("/v1/jobs/issue", e.auth(http.HandlerFunc(e.handleJobs)))
	mux.Handle("/v1/jobs/pr-review", e.auth(http.HandlerFunc(e.handleJobs)))
	mux.Handle("/v1/jobs/", e.auth(http.HandlerFunc(e.handleJobByID)))
	mux.Handle("/v1/context", e.auth(http.HandlerFunc(e.handleContext)))
	mux.Handle("/v1/context/", e.auth(http.HandlerFunc(e.handleContextSubroutes)))
	mux.Handle("/v1/tasklist", e.auth(http.HandlerFunc(e.handleTaskList)))
	mux.Handle("/v1/models", e.auth(http.HandlerFunc(e.handleModels)))
	mux.Handle("/v1/completions", e.auth(http.HandlerFunc(e.handleCompletions)))
	mux.Handle("/v1/activity", e.auth(http.HandlerFunc(e.handleActivity)))
	mux.Handle("/v1/activity/", e.auth(http.HandlerFunc(e.handleActivity)))
	mux.Handle("/v1/favorites", e.auth(http.HandlerFunc(e.handleFavorites)))
	return mux
}

func (e *Executor) auth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		expected := "Bearer " + e.cfg.AuthToken
		if r.Header.Get("Authorization") != expected {
			writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "Missing or invalid bearer token")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (e *Executor) handleHealthLive(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeMethodNotAllowed(w, http.MethodGet)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"execId": e.cfg.ExecID, "version": ServerVersion, "protocolVersion": ProtocolVersion})
}

func (e *Executor) handleHealthReady(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeMethodNotAllowed(w, http.MethodGet)
		return
	}
	if !e.sessions.HasLoadedSession() {
		writeError(w, http.StatusServiceUnavailable, "NOT_READY", "No session loaded")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "ready", "sessionId": e.sessions.CurrentSessionID()})
}

func (e *Executor) handleExecutorInfo(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeMethodNotAllowed(w, http.MethodGet)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"execId": e.cfg.ExecID, "version": ServerVersion, "protocolVersion": ProtocolVersion})
}

func (e *Executor) handleSessions(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		manifests := e.sessions.List()
		items := make([]map[string]any, 0, len(manifests))
		for _, manifest := range manifests {
			items = append(items, map[string]any{
				"id":       manifest.ID,
				"name":     manifest.Name,
				"created":  manifest.Created,
				"modified": manifest.Modified,
			})
		}
		writeJSON(w, http.StatusOK, map[string]any{"sessions": items, "currentSessionId": e.sessions.CurrentSessionID()})
	case http.MethodPost:
		if strings.HasSuffix(r.URL.Path, "/switch") {
			var payload struct {
				SessionID string `json:"sessionId"`
			}
			if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
				writeError(w, http.StatusBadRequest, "BAD_REQUEST", "Invalid JSON body")
				return
			}
			session, err := e.sessions.Switch(strings.TrimSpace(payload.SessionID))
			if errors.Is(err, os.ErrNotExist) {
				writeError(w, http.StatusBadRequest, "BAD_REQUEST", "Invalid sessionId")
				return
			}
			if err != nil {
				writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
				return
			}
			writeJSON(w, http.StatusOK, map[string]any{"status": "ok", "sessionId": session.Manifest.ID})
			return
		}
		var payload struct {
			Name string `json:"name"`
		}
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			writeError(w, http.StatusBadRequest, "BAD_REQUEST", "Invalid JSON body")
			return
		}
		name := strings.TrimSpace(payload.Name)
		if name == "" {
			writeError(w, http.StatusBadRequest, "VALIDATION_ERROR", "Session name is required and must not be blank")
			return
		}
		if len(name) > 200 {
			writeError(w, http.StatusBadRequest, "VALIDATION_ERROR", "Session name must not exceed 200 characters")
			return
		}
		session, err := e.sessions.Create(name)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
			return
		}
		writeJSON(w, http.StatusCreated, map[string]any{"sessionId": session.Manifest.ID, "name": session.Manifest.Name})
	case http.MethodPut:
		raw, err := io.ReadAll(r.Body)
		if err != nil {
			writeError(w, http.StatusBadRequest, "BAD_REQUEST", err.Error())
			return
		}
		sessionID := strings.TrimSpace(r.Header.Get("X-Session-Id"))
		session, err := e.sessions.Import(sessionID, raw)
		if err != nil {
			writeError(w, http.StatusBadRequest, "BAD_REQUEST", err.Error())
			return
		}
		writeJSON(w, http.StatusCreated, map[string]any{"sessionId": session.Manifest.ID})
	default:
		writeMethodNotAllowed(w, http.MethodGet, http.MethodPost, http.MethodPut)
	}
}

func (e *Executor) handleSessionByID(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/v1/sessions/")
	if path == "current" {
		if r.Method != http.MethodGet {
			writeMethodNotAllowed(w, http.MethodGet)
			return
		}
		session, ok := e.sessions.GetCurrent()
		if !ok {
			writeError(w, http.StatusServiceUnavailable, "NOT_READY", "No session loaded")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"id":       session.Manifest.ID,
			"name":     session.Manifest.Name,
			"created":  session.Manifest.Created,
			"modified": session.Manifest.Modified,
		})
		return
	}
	if path == "switch" {
		e.handleSessions(w, r)
		return
	}
	if r.Method != http.MethodGet {
		writeMethodNotAllowed(w, http.MethodGet)
		return
	}
	sessionID := path
	raw, err := e.sessions.Download(sessionID)
	if err != nil {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "Session zip not found")
		return
	}
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=\"%s.zip\"", sessionID))
	_, _ = w.Write(raw)
}

func (e *Executor) handleJobs(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeMethodNotAllowed(w, http.MethodPost)
		return
	}
	idempotencyKey := strings.TrimSpace(r.Header.Get("Idempotency-Key"))
	if idempotencyKey == "" {
		writeError(w, http.StatusBadRequest, "VALIDATION_ERROR", "Idempotency-Key header is required")
		return
	}
	if strings.HasSuffix(r.URL.Path, "/issue") {
		e.handleIssueJob(w, r)
		return
	}
	if strings.HasSuffix(r.URL.Path, "/pr-review") {
		e.handlePrReviewJob(w, r)
		return
	}
	var spec JobSpec
	if err := json.NewDecoder(r.Body).Decode(&spec); err != nil {
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", "Invalid JSON body")
		return
	}
	if spec.Tags == nil {
		spec.Tags = map[string]string{}
	}
	if githubToken := strings.TrimSpace(r.Header.Get("X-Github-Token")); githubToken != "" && spec.Tags["github_token"] == "" {
		spec.Tags["github_token"] = githubToken
	}
	e.enqueueJob(w, spec, idempotencyKey)
}

func (e *Executor) handleJobByID(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/v1/jobs/")
	parts := strings.Split(path, "/")
	if len(parts) == 0 || strings.TrimSpace(parts[0]) == "" {
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", "Invalid job path")
		return
	}
	job, ok := e.jobs.Get(parts[0])
	if !ok {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "Job not found")
		return
	}
	if len(parts) == 1 && r.Method == http.MethodGet {
		writeJSON(w, http.StatusOK, job.StatusPayload())
		return
	}
	if len(parts) < 2 {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "Unsupported job endpoint")
		return
	}
	switch {
	case parts[1] == "events" && r.Method == http.MethodGet:
		after, _ := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		events, nextAfter, state := job.Snapshot(after, limit)
		writeJSON(w, http.StatusOK, map[string]any{"jobId": job.ID, "state": state, "events": events, "nextAfter": nextAfter})
	case parts[1] == "cancel" && r.Method == http.MethodPost:
		job.Cancel()
		w.WriteHeader(http.StatusAccepted)
	case parts[1] == "diff" && r.Method == http.MethodGet:
		w.Header().Set("Content-Type", "text/plain; charset=UTF-8")
		_, _ = w.Write([]byte(job.Diff))
	default:
		writeError(w, http.StatusNotFound, "NOT_FOUND", "Unsupported job endpoint")
	}
}

func (e *Executor) handleContext(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeMethodNotAllowed(w, http.MethodGet)
		return
	}
	session, ok := e.sessions.GetCurrent()
	if !ok {
		writeError(w, http.StatusServiceUnavailable, "NOT_READY", "No session loaded")
		return
	}
	tokenCount := 0
	for _, fragment := range session.Fragments {
		tokenCount += len(fragment.Text) / 4
	}
	writeJSON(w, http.StatusOK, map[string]any{"sessionId": session.Manifest.ID, "fragments": session.Fragments, "tasklist": session.TaskList, "tokens": map[string]any{"workspace": tokenCount}})
}

func (e *Executor) handleContextSubroutes(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/v1/context/")
	switch {
	case strings.HasPrefix(path, "fragments/") && r.Method == http.MethodGet:
		e.handleContextFragment(w, strings.TrimPrefix(path, "fragments/"))
	case path == "conversation" && r.Method == http.MethodGet:
		e.handleConversation(w)
	case path == "drop" && r.Method == http.MethodPost:
		e.handleDropContext(w, r)
	case path == "pin" && r.Method == http.MethodPost:
		e.handleToggleFragment(w, r, func(fragment *Fragment, value bool) { fragment.Pinned = value }, "pinned")
	case path == "readonly" && r.Method == http.MethodPost:
		e.handleToggleFragment(w, r, func(fragment *Fragment, value bool) { fragment.Readonly = value }, "readonly")
	case path == "compress-history" && r.Method == http.MethodPost:
		writeJSON(w, http.StatusOK, map[string]any{"status": "accepted"})
	case path == "clear-history" && r.Method == http.MethodPost:
		_, err := e.sessions.UpdateCurrentWithAction(func(session *Session) error { session.Conversation = nil; return nil }, "Cleared history", "")
		if err != nil {
			writeError(w, http.StatusServiceUnavailable, "NOT_READY", "No session loaded")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"status": "cleared"})
	case path == "drop-all" && r.Method == http.MethodPost:
		_, err := e.sessions.UpdateCurrentWithAction(func(session *Session) error { session.Fragments = nil; return nil }, "Dropped all context", "")
		if err != nil {
			writeError(w, http.StatusServiceUnavailable, "NOT_READY", "No session loaded")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"status": "cleared"})
	case path == "files" && r.Method == http.MethodPost:
		e.handleAddFiles(w, r)
	case path == "text" && r.Method == http.MethodPost:
		e.handleAddText(w, r)
	case path == "classes" && r.Method == http.MethodPost:
		e.handleAddSyntheticFragments(w, r, "classNames", "class")
	case path == "methods" && r.Method == http.MethodPost:
		e.handleAddSyntheticFragments(w, r, "methodNames", "method")
	default:
		writeError(w, http.StatusNotFound, "NOT_FOUND", "Unsupported context endpoint")
	}
}

func (e *Executor) handleContextFragment(w http.ResponseWriter, fragmentID string) {
	session, ok := e.sessions.GetCurrent()
	if !ok {
		writeError(w, http.StatusServiceUnavailable, "NOT_READY", "No session loaded")
		return
	}
	for _, fragment := range session.Fragments {
		if fragment.ID == fragmentID {
			writeJSON(w, http.StatusOK, fragment)
			return
		}
	}
	writeError(w, http.StatusNotFound, "NOT_FOUND", "Fragment not found")
}

func (e *Executor) handleDropContext(w http.ResponseWriter, r *http.Request) {
	var payload struct {
		FragmentIDs []string `json:"fragmentIds"`
	}
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", "Invalid JSON body")
		return
	}
	_, err := e.sessions.UpdateCurrent(func(session *Session) error {
		drop := make(map[string]struct{}, len(payload.FragmentIDs))
		for _, id := range payload.FragmentIDs {
			drop[id] = struct{}{}
		}
		filtered := session.Fragments[:0]
		for _, fragment := range session.Fragments {
			if _, ok := drop[fragment.ID]; !ok {
				filtered = append(filtered, fragment)
			}
		}
		session.Fragments = filtered
		return nil
	})
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "NOT_READY", "No session loaded")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"fragmentIds": payload.FragmentIDs})
}

func (e *Executor) handleToggleFragment(w http.ResponseWriter, r *http.Request, update func(*Fragment, bool), field string) {
	var payload struct {
		FragmentID string `json:"fragmentId"`
		Pinned     bool   `json:"pinned"`
		Readonly   bool   `json:"readonly"`
	}
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", "Invalid JSON body")
		return
	}
	value := payload.Pinned
	if field == "readonly" {
		value = payload.Readonly
	}
	_, err := e.sessions.UpdateCurrent(func(session *Session) error {
		for index := range session.Fragments {
			if session.Fragments[index].ID == payload.FragmentID {
				update(&session.Fragments[index], value)
				return nil
			}
		}
		return os.ErrNotExist
	})
	if errors.Is(err, os.ErrNotExist) {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "Fragment not found")
		return
	}
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "NOT_READY", "No session loaded")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"fragmentId": payload.FragmentID, field: value})
}

func (e *Executor) handleAddFiles(w http.ResponseWriter, r *http.Request) {
	var payload struct {
		RelativePaths []string `json:"relativePaths"`
	}
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", "Invalid JSON body")
		return
	}
	var added []string
	_, err := e.sessions.UpdateCurrent(func(session *Session) error {
		for _, rel := range payload.RelativePaths {
			absPath, pathErr := resolveWorkspacePath(e.cfg.WorkspaceDir, rel)
			if pathErr != nil {
				continue
			}
			info, statErr := os.Stat(absPath)
			if statErr != nil || info.IsDir() {
				continue
			}
			text := summarizeFile(absPath, 200)
			added = append(added, filepath.ToSlash(rel))
			session.Fragments = append(session.Fragments, Fragment{ID: e.newFragmentID(), Kind: "file", Description: filepath.ToSlash(rel), Text: text, Path: filepath.ToSlash(rel)})
		}
		return nil
	})
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "NOT_READY", "No session loaded")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"added": added})
}

func (e *Executor) handleAddSyntheticFragments(w http.ResponseWriter, r *http.Request, field, kind string) {
	var payload map[string][]string
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", "Invalid JSON body")
		return
	}
	values := payload[field]
	var added []string
	_, err := e.sessions.UpdateCurrent(func(session *Session) error {
		for _, value := range values {
			trimmed := strings.TrimSpace(value)
			if trimmed == "" {
				continue
			}
			added = append(added, trimmed)
			session.Fragments = append(session.Fragments, Fragment{ID: e.newFragmentID(), Kind: kind, Description: trimmed, Text: fmt.Sprintf("Go runtime bootstrap %s fragment for %s", kind, trimmed)})
		}
		return nil
	})
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "NOT_READY", "No session loaded")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"added": added})
}

func (e *Executor) handleTaskList(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		session, ok := e.sessions.GetCurrent()
		if !ok {
			writeError(w, http.StatusServiceUnavailable, "NOT_READY", "No session loaded")
			return
		}
		writeJSON(w, http.StatusOK, session.TaskList)
	case http.MethodPost:
		var taskList TaskList
		if err := json.NewDecoder(r.Body).Decode(&taskList); err != nil {
			writeError(w, http.StatusBadRequest, "BAD_REQUEST", "Invalid JSON body")
			return
		}
		if taskList.Tasks == nil {
			taskList.Tasks = []TaskListTask{}
		}
		_, err := e.sessions.UpdateCurrent(func(session *Session) error { session.TaskList = taskList; return nil })
		if err != nil {
			writeError(w, http.StatusServiceUnavailable, "NOT_READY", "No session loaded")
			return
		}
		writeJSON(w, http.StatusOK, taskList)
	default:
		writeMethodNotAllowed(w, http.MethodGet, http.MethodPost)
	}
}

func (e *Executor) handleModels(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeMethodNotAllowed(w, http.MethodGet)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"models": []map[string]any{{"name": "gpt-5.2", "location": "gpt-5.2", "supportsReasoningEffort": true, "supportsReasoningDisable": true}, {"name": "gemini-3-flash-preview", "location": "gemini-3-flash-preview", "supportsReasoningEffort": false, "supportsReasoningDisable": false}}})
}

func (e *Executor) handleCompletions(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeMethodNotAllowed(w, http.MethodGet)
		return
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	completions, err := completionsForQuery(e.cfg.WorkspaceDir, r.URL.Query().Get("query"), limit)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"completions": completions})
}

func (e *Executor) newFragmentID() string { return NewIdentifier("frag") }

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]any{"error": map[string]any{"code": code, "message": message}})
}

func writeMethodNotAllowed(w http.ResponseWriter, methods ...string) {
	if len(methods) > 0 {
		w.Header().Set("Allow", strings.Join(methods, ", "))
	}
	writeError(w, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "Method not allowed")
}
