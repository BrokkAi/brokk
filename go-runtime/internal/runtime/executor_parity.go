package runtime

import (
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"strconv"
	"strings"
)

func validateJobSpec(spec JobSpec) error {
	if strings.TrimSpace(spec.PlannerModel) == "" {
		return errors.New("plannerModel is required")
	}

	allowedReasoning := map[string]struct{}{"": {}, "DEFAULT": {}, "LOW": {}, "MEDIUM": {}, "HIGH": {}, "DISABLE": {}}
	if _, ok := allowedReasoning[strings.ToUpper(strings.TrimSpace(spec.ReasoningLevel))]; !ok {
		return errors.New("reasoningLevel must be one of: DEFAULT, LOW, MEDIUM, HIGH, DISABLE")
	}
	if _, ok := allowedReasoning[strings.ToUpper(strings.TrimSpace(spec.ReasoningLevelCode))]; !ok {
		return errors.New("reasoningLevelCode must be one of: DEFAULT, LOW, MEDIUM, HIGH, DISABLE")
	}
	if spec.Temperature != nil && (*spec.Temperature < 0.0 || *spec.Temperature > 2.0) {
		return errors.New("temperature must be between 0.0 and 2.0")
	}
	if spec.TemperatureCode != nil && (*spec.TemperatureCode < 0.0 || *spec.TemperatureCode > 2.0) {
		return errors.New("temperatureCode must be between 0.0 and 2.0")
	}

	mode := normalizeMode(spec)
	switch mode {
	case "REVIEW":
		if strings.TrimSpace(spec.Tags["github_token"]) == "" {
			return errors.New("githubToken is required")
		}
		if strings.TrimSpace(spec.Tags["repo_owner"]) == "" {
			return errors.New("owner is required")
		}
		if strings.TrimSpace(spec.Tags["repo_name"]) == "" {
			return errors.New("repo is required")
		}
		if value, _ := strconv.Atoi(spec.Tags["pr_number"]); value <= 0 {
			return errors.New("prNumber must be a positive integer")
		}
	case "ISSUE":
		if strings.TrimSpace(spec.Tags["github_token"]) == "" {
			return errors.New("githubToken is required")
		}
		if strings.TrimSpace(spec.Tags["repo_owner"]) == "" {
			return errors.New("owner is required")
		}
		if strings.TrimSpace(spec.Tags["repo_name"]) == "" {
			return errors.New("repo is required")
		}
		if value, _ := strconv.Atoi(spec.Tags["issue_number"]); value <= 0 {
			return errors.New("valid issueNumber is required")
		}
		if spec.MaxIssueFixAttempts != nil && *spec.MaxIssueFixAttempts <= 0 {
			return errors.New("maxIssueFixAttempts must be a positive integer")
		}
	case "ISSUE_DIAGNOSE":
		if value, _ := strconv.Atoi(spec.Tags["issue_number"]); value <= 0 {
			return errors.New("valid issueNumber is required")
		}
	case "ISSUE_WRITER":
		if strings.TrimSpace(spec.Tags["github_token"]) == "" {
			return errors.New("githubToken is required")
		}
		if strings.TrimSpace(spec.Tags["repo_owner"]) == "" {
			return errors.New("owner is required")
		}
		if strings.TrimSpace(spec.Tags["repo_name"]) == "" {
			return errors.New("repo is required")
		}
	}
	return nil
}

func (e *Executor) handleIssueJob(w http.ResponseWriter, r *http.Request) {
	var payload struct {
		Owner               string `json:"owner"`
		Repo                string `json:"repo"`
		IssueNumber         int    `json:"issueNumber"`
		GithubToken         string `json:"githubToken"`
		PlannerModel        string `json:"plannerModel"`
		CodeModel           string `json:"codeModel"`
		SkipVerification    bool   `json:"skipVerification"`
		MaxIssueFixAttempts *int   `json:"maxIssueFixAttempts"`
	}
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", "Invalid JSON body")
		return
	}
	spec := JobSpec{
		PlannerModel:        payload.PlannerModel,
		CodeModel:           payload.CodeModel,
		SkipVerification:    payload.SkipVerification,
		MaxIssueFixAttempts: payload.MaxIssueFixAttempts,
		Tags: map[string]string{
			"mode":         "ISSUE",
			"repo_owner":   payload.Owner,
			"repo_name":    payload.Repo,
			"issue_number": strconv.Itoa(payload.IssueNumber),
			"github_token": payload.GithubToken,
		},
	}
	e.enqueueJob(w, spec, strings.TrimSpace(r.Header.Get("Idempotency-Key")))
}

func (e *Executor) handlePrReviewJob(w http.ResponseWriter, r *http.Request) {
	var payload struct {
		Owner        string `json:"owner"`
		Repo         string `json:"repo"`
		PRNumber     int    `json:"prNumber"`
		GithubToken  string `json:"githubToken"`
		PlannerModel string `json:"plannerModel"`
	}
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", "Invalid JSON body")
		return
	}
	spec := JobSpec{
		PlannerModel: payload.PlannerModel,
		Tags: map[string]string{
			"mode":         "REVIEW",
			"repo_owner":   payload.Owner,
			"repo_name":    payload.Repo,
			"pr_number":    strconv.Itoa(payload.PRNumber),
			"github_token": payload.GithubToken,
		},
	}
	e.enqueueJob(w, spec, strings.TrimSpace(r.Header.Get("Idempotency-Key")))
}

func (e *Executor) enqueueJob(w http.ResponseWriter, spec JobSpec, idempotencyKey string) {
	if spec.Tags == nil {
		spec.Tags = map[string]string{}
	}
	if err := validateJobSpec(spec); err != nil {
		writeError(w, http.StatusBadRequest, "VALIDATION_ERROR", err.Error())
		return
	}
	job, existing, err := e.jobs.CreateWithKey(spec, idempotencyKey)
	if errors.Is(err, errAnotherJobRunning) {
		writeError(w, http.StatusConflict, "JOB_CONFLICT", err.Error())
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	if !existing {
		go job.RunBootstrapWorkflowWithStore(e.sessions, e.jobs)
	}
	status := http.StatusCreated
	if existing {
		status = http.StatusOK
	}
	writeJSON(w, status, map[string]any{"jobId": job.ID, "state": job.State})
}

func (e *Executor) handleConversation(w http.ResponseWriter) {
	session, ok := e.sessions.GetCurrent()
	if !ok {
		writeError(w, http.StatusServiceUnavailable, "NOT_READY", "No session loaded")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"entries": session.Conversation})
}

func (e *Executor) handleAddText(w http.ResponseWriter, r *http.Request) {
	var payload struct {
		Text string `json:"text"`
	}
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", "Invalid JSON body")
		return
	}
	if strings.TrimSpace(payload.Text) == "" {
		writeError(w, http.StatusBadRequest, "VALIDATION_ERROR", "text must not be blank")
		return
	}
	if len([]byte(payload.Text)) > 1024*1024 {
		writeError(w, http.StatusBadRequest, "VALIDATION_ERROR", "text exceeds maximum size of 1 MiB")
		return
	}
	fragmentID := e.newFragmentID()
	_, err := e.sessions.UpdateCurrentWithAction(func(session *Session) error {
		session.Fragments = append(session.Fragments, Fragment{ID: fragmentID, Kind: "text", Description: "Pasted text", Text: payload.Text})
		return nil
	}, "Added text to context", "")
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "NOT_READY", "No session loaded")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"id": fragmentID, "chars": len(payload.Text)})
}

func (e *Executor) handleActivity(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/v1/activity")
	switch {
	case path == "" && r.Method == http.MethodGet:
		payload, err := e.sessions.Activity()
		if err != nil {
			writeError(w, http.StatusServiceUnavailable, "NOT_READY", "No session loaded")
			return
		}
		writeJSON(w, http.StatusOK, payload)
	case path == "/diff" && r.Method == http.MethodGet:
		contextID := strings.TrimSpace(r.URL.Query().Get("contextId"))
		if contextID == "" {
			writeError(w, http.StatusBadRequest, "VALIDATION_ERROR", "contextId is required")
			return
		}
		payload, err := e.sessions.Diff(contextID)
		if errors.Is(err, os.ErrNotExist) {
			writeError(w, http.StatusBadRequest, "VALIDATION_ERROR", "Context not found")
			return
		}
		if err != nil {
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
			return
		}
		writeJSON(w, http.StatusOK, payload)
	case path == "/undo" && r.Method == http.MethodPost:
		e.handleActivityMutation(w, r, func(contextID, name string) (map[string]any, error) {
			return map[string]any{"status": "ok"}, e.sessions.UndoToContext(contextID)
		})
	case path == "/undo-step" && r.Method == http.MethodPost:
		if err := e.sessions.UndoStep(); err != nil {
			writeError(w, http.StatusServiceUnavailable, "NOT_READY", "No session loaded")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"status": "ok"})
	case path == "/redo" && r.Method == http.MethodPost:
		if err := e.sessions.RedoStep(); err != nil {
			writeError(w, http.StatusServiceUnavailable, "NOT_READY", "No session loaded")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"status": "ok"})
	case path == "/copy-context" && r.Method == http.MethodPost:
		e.handleActivityMutation(w, r, func(contextID, name string) (map[string]any, error) {
			return map[string]any{"status": "ok"}, e.sessions.CopyContext(contextID)
		})
	case path == "/copy-context-history" && r.Method == http.MethodPost:
		e.handleActivityMutation(w, r, func(contextID, name string) (map[string]any, error) {
			return map[string]any{"status": "ok"}, e.sessions.CopyContextHistory(contextID)
		})
	case path == "/new-session" && r.Method == http.MethodPost:
		e.handleActivityMutation(w, r, func(contextID, name string) (map[string]any, error) {
			session, err := e.sessions.NewSessionFromContext(contextID, name)
			if err != nil {
				return nil, err
			}
			return map[string]any{"sessionId": session.Manifest.ID, "name": session.Manifest.Name}, nil
		})
	default:
		writeMethodNotAllowed(w, http.MethodGet, http.MethodPost)
	}
}

func (e *Executor) handleActivityMutation(w http.ResponseWriter, r *http.Request, action func(contextID, name string) (map[string]any, error)) {
	var payload struct {
		ContextID string `json:"contextId"`
		Name      string `json:"name"`
	}
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", "Invalid JSON body")
		return
	}
	if strings.TrimSpace(payload.ContextID) == "" {
		writeError(w, http.StatusBadRequest, "VALIDATION_ERROR", "contextId is required")
		return
	}
	response, err := action(strings.TrimSpace(payload.ContextID), strings.TrimSpace(payload.Name))
	if errors.Is(err, os.ErrNotExist) {
		writeError(w, http.StatusBadRequest, "VALIDATION_ERROR", "Context not found")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, response)
}

func (e *Executor) handleFavorites(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeMethodNotAllowed(w, http.MethodGet)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"favorites": []map[string]any{}})
}
