package app

import (
	"bytes"
	"errors"
	"fmt"
	"io/fs"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"

	"github.com/brokk/brokk/go-runtime/internal/analyzer"
	"github.com/brokk/brokk/go-runtime/internal/config"
	"github.com/brokk/brokk/go-runtime/internal/contextstate"
	"github.com/brokk/brokk/go-runtime/internal/execution"
	"github.com/brokk/brokk/go-runtime/internal/httpserver"
	"github.com/brokk/brokk/go-runtime/internal/jobs"
	"github.com/brokk/brokk/go-runtime/internal/reviewruntime"
	"github.com/brokk/brokk/go-runtime/internal/sessions"
)

const (
	Version         = "dev"
	ProtocolVersion = 1
)

var allowedReasoningLevels = map[string]struct{}{
	"DEFAULT": {},
	"LOW":     {},
	"MEDIUM":  {},
	"HIGH":    {},
	"DISABLE": {},
}

type App struct {
	cfg      config.Config
	server   *http.Server
	router   *httpserver.Server
	jobStore *jobs.JobStore
	sessions *sessions.Manager
	contexts *contextstate.Manager
	analyzer *analyzer.Service
	runner   *execution.Runner

	sessionMu     sync.RWMutex
	sessionLoaded bool
}

type jobSpecRequest struct {
	SessionID          string            `json:"sessionId"`
	TaskInput          string            `json:"taskInput"`
	AutoCommit         bool              `json:"autoCommit"`
	AutoCompress       bool              `json:"autoCompress"`
	PlannerModel       *string           `json:"plannerModel"`
	ScanModel          *string           `json:"scanModel"`
	CodeModel          *string           `json:"codeModel"`
	PreScan            *bool             `json:"preScan"`
	Tags               map[string]string `json:"tags"`
	ContextText        []string          `json:"contextText"`
	Context            *contextPayload   `json:"context"`
	ReasoningLevel     *string           `json:"reasoningLevel"`
	ReasoningLevelCode *string           `json:"reasoningLevelCode"`
	Temperature        *float64          `json:"temperature"`
	TemperatureCode    *float64          `json:"temperatureCode"`
	SkipVerification   *bool             `json:"skipVerification"`
}

type contextPayload struct {
	Text []string `json:"text"`
}

type modelOverrides struct {
	ReasoningLevel     *string
	ReasoningLevelCode *string
	Temperature        *float64
	TemperatureCode    *float64
}

type prReviewJobRequest struct {
	Owner        string  `json:"owner"`
	Repo         string  `json:"repo"`
	PRNumber     int     `json:"prNumber"`
	GitHubToken  string  `json:"githubToken"`
	GitHubAPIURL *string `json:"githubApiUrl"`
	PlannerModel *string `json:"plannerModel"`
}

type createSessionRequest struct {
	Name string `json:"name"`
}

type switchSessionRequest struct {
	SessionID string `json:"sessionId"`
}

type dropFragmentsRequest struct {
	FragmentIDs []string `json:"fragmentIds"`
}

type pinFragmentRequest struct {
	FragmentID string `json:"fragmentId"`
	Pinned     bool   `json:"pinned"`
}

type readonlyFragmentRequest struct {
	FragmentID string `json:"fragmentId"`
	Readonly   bool   `json:"readonly"`
}

type addContextTextRequest struct {
	Text string `json:"text"`
}

type replaceTaskListRequest struct {
	BigPicture *string                 `json:"bigPicture"`
	Tasks      []contextstate.TaskItem `json:"tasks"`
}

type activityContextRequest struct {
	ContextID string `json:"contextId"`
}

type activityNewSessionRequest struct {
	ContextID string  `json:"contextId"`
	Name      *string `json:"name"`
}

type addContextFilesRequest struct {
	RelativePaths []string `json:"relativePaths"`
}

type addContextClassesRequest struct {
	ClassNames []string `json:"classNames"`
}

type addContextMethodsRequest struct {
	MethodNames []string `json:"methodNames"`
}

func New(cfg config.Config) (*App, error) {
	storeDir := filepath.Join(cfg.WorkspaceDir, ".brokk")
	if err := os.MkdirAll(storeDir, 0o755); err != nil {
		return nil, err
	}

	jobStore, err := jobs.NewJobStore(storeDir)
	if err != nil {
		return nil, err
	}

	sessionManager, err := sessions.NewManager(storeDir)
	if err != nil {
		return nil, err
	}

	contextManager, err := contextstate.NewManager(storeDir)
	if err != nil {
		return nil, err
	}

	router := httpserver.New(cfg.AuthToken)
	app := &App{
		cfg:      cfg,
		router:   router,
		jobStore: jobStore,
		sessions: sessionManager,
		contexts: contextManager,
		analyzer: analyzer.New(cfg.WorkspaceDir),
	}
	app.runner = execution.NewRunnerWithReviewGenerator(
		jobStore,
		sessionManager,
		contextManager,
		app.analyzer,
		cfg.WorkspaceDir,
		newReviewGenerator(cfg),
	)
	app.sessionLoaded = sessionManager.HasCurrentSession()
	app.registerRoutes()
	app.server = &http.Server{
		Addr:    cfg.ListenAddr,
		Handler: router.Handler(),
	}
	return app, nil
}

func newReviewGenerator(cfg config.Config) reviewruntime.Generator {
	heuristic := reviewruntime.NewHeuristicGenerator()
	if strings.TrimSpace(cfg.BrokkAPIKey) == "" {
		return heuristic
	}

	primary, err := reviewruntime.NewOpenAIGenerator(reviewruntime.OpenAIConfig{
		APIKey:  cfg.BrokkAPIKey,
		BaseURL: reviewruntime.ProxyBaseURL(cfg.ProxySetting),
		Vendor:  cfg.Vendor,
	})
	if err != nil {
		return heuristic
	}
	return reviewruntime.NewFallbackGenerator(primary, heuristic)
}

func (a *App) Handler() http.Handler {
	return a.router.Handler()
}

func (a *App) Start() error {
	return a.server.ListenAndServe()
}

func (a *App) Shutdown() error {
	return a.server.Close()
}

func (a *App) registerRoutes() {
	a.router.RegisterUnauthenticated("/health/live", a.handleHealthLive)
	a.router.RegisterUnauthenticated("/health/ready", a.handleHealthReady)
	a.router.RegisterUnauthenticated("/v1/executor", a.handleExecutor)
	a.router.RegisterAuthenticated("/v1/sessions", a.handleSessionsRoot)
	a.router.RegisterAuthenticated("/v1/sessions/", a.handleSessionsByPath)
	a.router.RegisterAuthenticated("/v1/context", a.handleContextRoot)
	a.router.RegisterAuthenticated("/v1/context/", a.handleContextByPath)
	a.router.RegisterAuthenticated("/v1/tasklist", a.handleTaskList)
	a.router.RegisterAuthenticated("/v1/models", a.handleModels)
	a.router.RegisterAuthenticated("/v1/favorites", a.handleFavorites)
	a.router.RegisterAuthenticated("/v1/completions", a.handleCompletions)
	a.router.RegisterAuthenticated("/v1/activity", a.handleActivityRoot)
	a.router.RegisterAuthenticated("/v1/activity/", a.handleActivityByPath)
	a.router.RegisterAuthenticated("/v1/jobs", a.handleJobsRoot)
	a.router.RegisterAuthenticated("/v1/jobs/", a.handleJobsByPath)
}

func (a *App) handleHealthLive(w http.ResponseWriter, r *http.Request) {
	if !ensureMethod(w, r, http.MethodGet) {
		return
	}

	httpserver.WriteJSON(w, http.StatusOK, map[string]any{
		"execId":          a.cfg.ExecID,
		"version":         Version,
		"protocolVersion": ProtocolVersion,
	})
}

func (a *App) handleHealthReady(w http.ResponseWriter, r *http.Request) {
	if !ensureMethod(w, r, http.MethodGet) {
		return
	}

	a.sessionMu.RLock()
	loaded := a.sessionLoaded
	a.sessionMu.RUnlock()
	if !loaded {
		httpserver.WriteJSON(w, http.StatusServiceUnavailable, httpserver.ErrorPayload{
			Code:    "NOT_READY",
			Message: "No session loaded",
		})
		return
	}

	httpserver.WriteJSON(w, http.StatusOK, map[string]any{
		"status":    "ready",
		"sessionId": a.sessions.CurrentSessionID(),
	})
}

func (a *App) handleExecutor(w http.ResponseWriter, r *http.Request) {
	if !ensureMethod(w, r, http.MethodGet) {
		return
	}

	httpserver.WriteJSON(w, http.StatusOK, map[string]any{
		"execId":          a.cfg.ExecID,
		"version":         Version,
		"protocolVersion": ProtocolVersion,
	})
}

func (a *App) handleJobsRoot(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/v1/jobs" {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ErrorPayload{
			Code:    "BAD_REQUEST",
			Message: "Invalid job path",
		})
		return
	}

	if r.Method == http.MethodPost {
		a.handlePostJobs(w, r)
		return
	}

	httpserver.WriteJSON(w, http.StatusMethodNotAllowed, httpserver.ErrorPayload{
		Code:    "METHOD_NOT_ALLOWED",
		Message: "Method not allowed",
	})
}

func (a *App) handleSessionsRoot(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		if r.URL.Path != "/v1/sessions" {
			httpserver.WriteJSON(w, http.StatusNotFound, httpserver.ErrorPayload{
				Code:    "NOT_FOUND",
				Message: "Not found",
			})
			return
		}
		a.handleListSessions(w)
	case http.MethodPost:
		if r.URL.Path != "/v1/sessions" {
			httpserver.WriteJSON(w, http.StatusNotFound, httpserver.ErrorPayload{
				Code:    "NOT_FOUND",
				Message: "Not found",
			})
			return
		}
		a.handleCreateSession(w, r)
	case http.MethodPut:
		if r.URL.Path != "/v1/sessions" {
			httpserver.WriteJSON(w, http.StatusNotFound, httpserver.ErrorPayload{
				Code:    "NOT_FOUND",
				Message: "Not found",
			})
			return
		}
		a.handleImportSession(w, r)
	default:
		httpserver.WriteJSON(w, http.StatusMethodNotAllowed, httpserver.ErrorPayload{
			Code:    "METHOD_NOT_ALLOWED",
			Message: "Method not allowed",
		})
	}
}

func (a *App) handleSessionsByPath(w http.ResponseWriter, r *http.Request) {
	normalizedPath := strings.TrimSuffix(r.URL.Path, "/")
	switch {
	case normalizedPath == "/v1/sessions/current" && r.Method == http.MethodGet:
		a.handleCurrentSession(w)
	case normalizedPath == "/v1/sessions/switch" && r.Method == http.MethodPost:
		a.handleSwitchSession(w, r)
	case strings.HasPrefix(normalizedPath, "/v1/sessions/") && r.Method == http.MethodGet:
		a.handleGetSessionZip(w, normalizedPath)
	default:
		httpserver.WriteJSON(w, http.StatusNotFound, httpserver.ErrorPayload{
			Code:    "NOT_FOUND",
			Message: "Not found",
		})
	}
}

func (a *App) handleContextRoot(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/v1/context" {
		httpserver.WriteJSON(w, http.StatusNotFound, httpserver.ErrorPayload{
			Code:    "NOT_FOUND",
			Message: "Not found",
		})
		return
	}

	if r.Method != http.MethodGet {
		httpserver.WriteJSON(w, http.StatusMethodNotAllowed, httpserver.ErrorPayload{
			Code:    "METHOD_NOT_ALLOWED",
			Message: "Method not allowed",
		})
		return
	}
	a.handleGetContext(w, r)
}

func (a *App) handleContextByPath(w http.ResponseWriter, r *http.Request) {
	normalizedPath := strings.TrimSuffix(r.URL.Path, "/")
	switch {
	case normalizedPath == "/v1/context/conversation" && r.Method == http.MethodGet:
		a.handleGetConversation(w)
	case strings.HasPrefix(normalizedPath, "/v1/context/fragments/") && r.Method == http.MethodGet:
		a.handleGetContextFragment(w, normalizedPath)
	case normalizedPath == "/v1/context/drop" && r.Method == http.MethodPost:
		a.handlePostContextDrop(w, r)
	case normalizedPath == "/v1/context/pin" && r.Method == http.MethodPost:
		a.handlePostContextPin(w, r)
	case normalizedPath == "/v1/context/readonly" && r.Method == http.MethodPost:
		a.handlePostContextReadonly(w, r)
	case normalizedPath == "/v1/context/compress-history" && r.Method == http.MethodPost:
		httpserver.WriteJSON(w, http.StatusAccepted, map[string]any{"status": "compressing"})
	case normalizedPath == "/v1/context/clear-history" && r.Method == http.MethodPost:
		a.handlePostClearHistory(w)
	case normalizedPath == "/v1/context/drop-all" && r.Method == http.MethodPost:
		a.handlePostDropAll(w)
	case normalizedPath == "/v1/context/text" && r.Method == http.MethodPost:
		a.handlePostContextText(w, r)
	case normalizedPath == "/v1/context/files" && r.Method == http.MethodPost:
		a.handlePostContextFiles(w, r)
	case normalizedPath == "/v1/context/classes" && r.Method == http.MethodPost:
		a.handlePostContextClasses(w, r)
	case normalizedPath == "/v1/context/methods" && r.Method == http.MethodPost:
		a.handlePostContextMethods(w, r)
	default:
		httpserver.WriteJSON(w, http.StatusNotFound, httpserver.ErrorPayload{
			Code:    "NOT_FOUND",
			Message: "Not found",
		})
	}
}

func (a *App) handleTaskList(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/v1/tasklist" {
		httpserver.WriteJSON(w, http.StatusNotFound, httpserver.ErrorPayload{
			Code:    "NOT_FOUND",
			Message: "Not found",
		})
		return
	}

	switch r.Method {
	case http.MethodGet:
		a.handleGetTaskList(w)
	case http.MethodPost:
		a.handlePostTaskList(w, r)
	default:
		httpserver.WriteJSON(w, http.StatusMethodNotAllowed, httpserver.ErrorPayload{
			Code:    "METHOD_NOT_ALLOWED",
			Message: "Method not allowed",
		})
	}
}

func (a *App) handleModels(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/v1/models" {
		httpserver.WriteJSON(w, http.StatusNotFound, httpserver.ErrorPayload{
			Code:    "NOT_FOUND",
			Message: "Not found",
		})
		return
	}
	if !ensureMethod(w, r, http.MethodGet) {
		return
	}

	httpserver.WriteJSON(w, http.StatusOK, map[string]any{
		"models": []map[string]any{
			{
				"name":                     "gpt-5",
				"location":                 "BROKK",
				"supportsReasoningEffort":  true,
				"supportsReasoningDisable": true,
			},
			{
				"name":                     "gpt-5-mini",
				"location":                 "BROKK",
				"supportsReasoningEffort":  true,
				"supportsReasoningDisable": true,
			},
			{
				"name":                     "claude-sonnet",
				"location":                 "OTHER",
				"supportsReasoningEffort":  false,
				"supportsReasoningDisable": false,
			},
		},
	})
}

func (a *App) handleFavorites(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/v1/favorites" {
		httpserver.WriteJSON(w, http.StatusNotFound, httpserver.ErrorPayload{
			Code:    "NOT_FOUND",
			Message: "Not found",
		})
		return
	}
	if !ensureMethod(w, r, http.MethodGet) {
		return
	}

	httpserver.WriteJSON(w, http.StatusOK, map[string]any{
		"favorites": []map[string]any{},
	})
}

func (a *App) handleCompletions(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/v1/completions" {
		httpserver.WriteJSON(w, http.StatusNotFound, httpserver.ErrorPayload{
			Code:    "NOT_FOUND",
			Message: "Not found",
		})
		return
	}
	if !ensureMethod(w, r, http.MethodGet) {
		return
	}

	query := strings.TrimSpace(r.URL.Query().Get("query"))
	if query == "" {
		httpserver.WriteJSON(w, http.StatusOK, map[string]any{"completions": []map[string]string{}})
		return
	}

	limit := 20
	if limitStr := strings.TrimSpace(r.URL.Query().Get("limit")); limitStr != "" {
		if parsed, err := strconv.Atoi(limitStr); err == nil {
			if parsed < 1 {
				limit = 1
			} else if parsed > 50 {
				limit = 50
			} else {
				limit = parsed
			}
		}
	}

	completions := make([]map[string]string, 0, limit)
	isPathQuery := strings.Contains(query, "/") || strings.Contains(query, "\\")
	if isPathQuery {
		completions = append(completions, a.collectFileCompletions(query, limit)...)
	} else {
		completions = append(completions, a.collectSymbolCompletions(query, limit)...)
		if len(completions) < limit {
			completions = append(completions, a.collectFileCompletions(query, limit-len(completions))...)
		}
	}
	httpserver.WriteJSON(w, http.StatusOK, map[string]any{"completions": completions})
}

func (a *App) handleActivityRoot(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/v1/activity" {
		httpserver.WriteJSON(w, http.StatusNotFound, httpserver.ErrorPayload{
			Code:    "NOT_FOUND",
			Message: "Not found",
		})
		return
	}
	if !ensureMethod(w, r, http.MethodGet) {
		return
	}
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}

	history, historyIndex, err := a.contexts.History(sessionID)
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to retrieve activity", err))
		return
	}

	httpserver.WriteJSON(w, http.StatusOK, map[string]any{
		"groups":  buildActivityGroups(history, historyIndex),
		"hasUndo": historyIndex > 0,
		"hasRedo": historyIndex >= 0 && historyIndex < len(history)-1,
	})
}

func (a *App) handleActivityByPath(w http.ResponseWriter, r *http.Request) {
	normalizedPath := strings.TrimSuffix(r.URL.Path, "/")
	if normalizedPath == "/v1/activity/diff" && r.Method == http.MethodGet {
		a.handleGetActivityDiff(w, r)
		return
	}
	if r.Method == http.MethodPost {
		switch normalizedPath {
		case "/v1/activity/undo":
			a.handlePostActivityUndo(w, r)
			return
		case "/v1/activity/undo-step":
			a.handlePostActivityUndoStep(w)
			return
		case "/v1/activity/redo":
			a.handlePostActivityRedo(w)
			return
		case "/v1/activity/copy-context":
			a.handlePostActivityCopyContext(w, r, false)
			return
		case "/v1/activity/copy-context-history":
			a.handlePostActivityCopyContext(w, r, true)
			return
		case "/v1/activity/new-session":
			a.handlePostActivityNewSession(w, r)
			return
		}
	}
	httpserver.WriteJSON(w, http.StatusMethodNotAllowed, httpserver.ErrorPayload{
		Code:    "METHOD_NOT_ALLOWED",
		Message: "Method not allowed",
	})
}

func (a *App) handleJobsByPath(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path == "/v1/jobs/pr-review" && r.Method == http.MethodPost {
		a.handlePostPrReviewJob(w, r)
		return
	}

	jobID, suffix, ok := extractJobPath(r.URL.Path)
	if !ok {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ErrorPayload{
			Code:    "BAD_REQUEST",
			Message: "Invalid job path",
		})
		return
	}

	switch {
	case suffix == "" && r.Method == http.MethodGet:
		a.handleGetJob(w, jobID)
	case suffix == "/events" && r.Method == http.MethodGet:
		a.handleGetJobEvents(w, r, jobID)
	case suffix == "/cancel" && r.Method == http.MethodPost:
		a.handleCancelJob(w, jobID)
	case suffix == "/diff" && r.Method == http.MethodGet:
		a.handleGetJobDiff(w, jobID)
	default:
		httpserver.WriteJSON(w, http.StatusNotFound, httpserver.ErrorPayload{
			Code:    "NOT_FOUND",
			Message: "Not found",
		})
	}
}

func (a *App) handleListSessions(w http.ResponseWriter) {
	sessionList := make([]map[string]any, 0)
	for _, session := range a.sessions.ListSessions() {
		sessionList = append(sessionList, map[string]any{
			"id":       session.ID,
			"name":     session.Name,
			"created":  session.Created,
			"modified": session.Modified,
		})
	}

	httpserver.WriteJSON(w, http.StatusOK, map[string]any{
		"sessions":         sessionList,
		"currentSessionId": a.sessions.CurrentSessionID(),
	})
}

func (a *App) handleCurrentSession(w http.ResponseWriter) {
	info, ok := a.sessions.CurrentSessionInfo()
	if !ok {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.ErrorPayload{
			Code:    "INTERNAL_ERROR",
			Message: "Failed to get current session",
		})
		return
	}

	httpserver.WriteJSON(w, http.StatusOK, map[string]any{
		"id":       info.ID,
		"name":     info.Name,
		"created":  info.Created,
		"modified": info.Modified,
	})
}

func (a *App) handleCreateSession(w http.ResponseWriter, r *http.Request) {
	var request createSessionRequest
	if err := httpserver.ParseJSON(r, &request); err != nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid JSON request body"))
		return
	}

	sessionName := strings.TrimSpace(request.Name)
	if sessionName == "" {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Session name is required and must not be blank"))
		return
	}
	if len(sessionName) > 200 {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Session name must not exceed 200 characters"))
		return
	}

	info, err := a.sessions.CreateSession(sessionName)
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to create session", err))
		return
	}
	a.markSessionLoaded()
	a.recordActivitySnapshot(info.ID, "Create session", "", false, "")

	httpserver.WriteJSON(w, http.StatusCreated, map[string]any{
		"sessionId": info.ID,
		"name":      info.Name,
	})
}

func (a *App) handleSwitchSession(w http.ResponseWriter, r *http.Request) {
	var request switchSessionRequest
	if err := httpserver.ParseJSON(r, &request); err != nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid JSON request body"))
		return
	}
	if strings.TrimSpace(request.SessionID) == "" {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("sessionId is required"))
		return
	}
	if !config.LooksLikeUUID(request.SessionID) {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid sessionId: "+request.SessionID))
		return
	}

	err := a.sessions.SwitchSession(request.SessionID)
	if errors.Is(err, os.ErrNotExist) {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.ErrorPayload{
			Code:    "INTERNAL_ERROR",
			Message: "Failed to switch session: session not found",
		})
		return
	}
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to switch session: "+err.Error(), err))
		return
	}
	a.markSessionLoaded()

	httpserver.WriteJSON(w, http.StatusOK, map[string]any{
		"status":    "ok",
		"sessionId": request.SessionID,
	})
}

func (a *App) handleImportSession(w http.ResponseWriter, r *http.Request) {
	sessionID := strings.TrimSpace(r.Header.Get("X-Session-Id"))
	if sessionID == "" {
		var err error
		sessionID, err = sessions.NewImportID()
		if err != nil {
			httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to process session upload", err))
			return
		}
	}
	if !config.LooksLikeUUID(sessionID) {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid X-Session-Id header: invalid UUID"))
		return
	}

	buf := &bytes.Buffer{}
	if _, err := buf.ReadFrom(r.Body); err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to process session upload", err))
		return
	}
	defer r.Body.Close()

	if _, err := a.sessions.ImportSession(sessionID, buf.Bytes()); err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to process session upload", err))
		return
	}
	a.markSessionLoaded()
	a.recordActivitySnapshot(sessionID, "Import session", "", false, "")

	httpserver.WriteJSON(w, http.StatusCreated, map[string]any{
		"sessionId": sessionID,
	})
}

func (a *App) handleGetSessionZip(w http.ResponseWriter, normalizedPath string) {
	sessionID := strings.TrimPrefix(normalizedPath, "/v1/sessions/")
	if !config.LooksLikeUUID(sessionID) {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid session ID in path"))
		return
	}

	contents, err := a.sessions.ReadSessionZip(sessionID)
	if errors.Is(err, os.ErrNotExist) {
		httpserver.WriteJSON(w, http.StatusNotFound, httpserver.ErrorPayload{
			Code:    "NOT_FOUND",
			Message: "Session zip not found for session " + sessionID,
		})
		return
	}
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to stream session zip", err))
		return
	}

	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", "attachment; filename=\""+sessionID+".zip\"")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(contents)
}

func (a *App) handleGetContext(w http.ResponseWriter, r *http.Request) {
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}

	state, err := a.contexts.Get(sessionID)
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to retrieve context", err))
		return
	}

	includeTokens := false
	tokensParam := r.URL.Query().Get("tokens")
	if strings.EqualFold(tokensParam, "true") || tokensParam == "1" {
		includeTokens = true
	}

	fragmentList := make([]map[string]any, 0, len(state.Fragments))
	totalUsedTokens := 0
	for _, fragment := range state.Fragments {
		tokens := 0
		if includeTokens {
			tokens = approximateTokens(fragment.Text)
		}
		totalUsedTokens += tokens
		fragmentList = append(fragmentList, map[string]any{
			"id":               fragment.ID,
			"type":             fragment.Type,
			"shortDescription": fragment.ShortDescription,
			"chipKind":         classifyChipKind(fragment),
			"pinned":           fragment.Pinned,
			"readonly":         fragment.Readonly,
			"valid":            fragment.Valid,
			"editable":         fragment.Editable,
			"tokens":           tokens,
		})
	}

	branch := "(no git)"
	if _, err := os.Stat(filepath.Join(a.cfg.WorkspaceDir, ".git")); err == nil {
		branch = "unknown"
	}

	httpserver.WriteJSON(w, http.StatusOK, map[string]any{
		"fragments":       fragmentList,
		"usedTokens":      totalUsedTokens,
		"maxTokens":       200000,
		"tokensEstimated": includeTokens,
		"branch":          branch,
	})
}

func (a *App) handleGetContextFragment(w http.ResponseWriter, normalizedPath string) {
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}

	fragmentID := strings.TrimPrefix(normalizedPath, "/v1/context/fragments/")
	if strings.TrimSpace(fragmentID) == "" {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("fragmentId is required"))
		return
	}

	state, err := a.contexts.Get(sessionID)
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to retrieve context fragment", err))
		return
	}

	for _, fragment := range state.Fragments {
		if fragment.ID == fragmentID {
			httpserver.WriteJSON(w, http.StatusOK, map[string]any{
				"id":       fragment.ID,
				"uri":      fragment.URI,
				"mimeType": fragment.MimeType,
				"text":     fragment.Text,
			})
			return
		}
	}

	httpserver.WriteJSON(w, http.StatusNotFound, httpserver.ErrorPayload{
		Code:    "NOT_FOUND",
		Message: "Fragment not found: " + fragmentID,
	})
}

func (a *App) handlePostContextDrop(w http.ResponseWriter, r *http.Request) {
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}

	var request dropFragmentsRequest
	if err := httpserver.ParseJSON(r, &request); err != nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid JSON request body"))
		return
	}
	if len(request.FragmentIDs) == 0 {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("fragmentIds must not be empty"))
		return
	}

	dropped := 0
	_, err := a.contexts.Update(sessionID, func(state *contextstate.State) error {
		idSet := make(map[string]struct{}, len(request.FragmentIDs))
		for _, id := range request.FragmentIDs {
			idSet[id] = struct{}{}
		}
		filtered := make([]contextstate.Fragment, 0, len(state.Fragments))
		for _, fragment := range state.Fragments {
			if _, ok := idSet[fragment.ID]; ok {
				dropped++
				continue
			}
			filtered = append(filtered, fragment)
		}
		state.Fragments = filtered
		return nil
	})
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to drop fragments", err))
		return
	}
	if dropped == 0 {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("No matching fragments found for the given IDs"))
		return
	}

	httpserver.WriteJSON(w, http.StatusOK, map[string]any{"dropped": dropped})
	a.recordActivitySnapshot(sessionID, "Drop context fragments", "", false, "")
}

func (a *App) handlePostContextPin(w http.ResponseWriter, r *http.Request) {
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}

	var request pinFragmentRequest
	if err := httpserver.ParseJSON(r, &request); err != nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid JSON request body"))
		return
	}
	if strings.TrimSpace(request.FragmentID) == "" {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("fragmentId is required"))
		return
	}

	found := false
	_, err := a.contexts.Update(sessionID, func(state *contextstate.State) error {
		for i, fragment := range state.Fragments {
			if fragment.ID == request.FragmentID {
				state.Fragments[i].Pinned = request.Pinned
				found = true
				return nil
			}
		}
		return nil
	})
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to update fragment", err))
		return
	}
	if !found {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Fragment not found: "+request.FragmentID))
		return
	}

	httpserver.WriteJSON(w, http.StatusOK, map[string]any{
		"fragmentId": request.FragmentID,
		"pinned":     request.Pinned,
	})
	a.recordActivitySnapshot(sessionID, "Update fragment pin", "", false, "")
}

func (a *App) handlePostContextReadonly(w http.ResponseWriter, r *http.Request) {
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}

	var request readonlyFragmentRequest
	if err := httpserver.ParseJSON(r, &request); err != nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid JSON request body"))
		return
	}
	if strings.TrimSpace(request.FragmentID) == "" {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("fragmentId is required"))
		return
	}

	found := false
	editable := false
	_, err := a.contexts.Update(sessionID, func(state *contextstate.State) error {
		for i, fragment := range state.Fragments {
			if fragment.ID == request.FragmentID {
				found = true
				editable = fragment.Editable
				if editable {
					state.Fragments[i].Readonly = request.Readonly
				}
				return nil
			}
		}
		return nil
	})
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to update fragment", err))
		return
	}
	if !found {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Fragment not found: "+request.FragmentID))
		return
	}
	if !editable {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Fragment is not editable and cannot be marked readonly"))
		return
	}

	httpserver.WriteJSON(w, http.StatusOK, map[string]any{
		"fragmentId": request.FragmentID,
		"readonly":   request.Readonly,
	})
	a.recordActivitySnapshot(sessionID, "Update fragment readonly", "", false, "")
}

func (a *App) handlePostClearHistory(w http.ResponseWriter) {
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}

	if _, err := a.contexts.ClearHistory(sessionID); err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to clear history", err))
		return
	}
	httpserver.WriteJSON(w, http.StatusOK, map[string]any{"status": "cleared"})
	a.recordActivitySnapshot(sessionID, "Clear history", "", false, "")
}

func (a *App) handlePostDropAll(w http.ResponseWriter) {
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}

	if _, err := a.contexts.DropAll(sessionID); err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to drop all context", err))
		return
	}
	httpserver.WriteJSON(w, http.StatusOK, map[string]any{"status": "dropped"})
	a.recordActivitySnapshot(sessionID, "Drop all context", "", false, "")
}

func (a *App) handlePostContextText(w http.ResponseWriter, r *http.Request) {
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}

	var request addContextTextRequest
	if err := httpserver.ParseJSON(r, &request); err != nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid JSON request body"))
		return
	}
	if strings.TrimSpace(request.Text) == "" {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("text must not be blank"))
		return
	}
	if len([]byte(request.Text)) > 1024*1024 {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("text exceeds maximum size of 1 MiB"))
		return
	}

	fragment, _, err := a.contexts.AddTextFragment(sessionID, request.Text)
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to add context text", err))
		return
	}
	httpserver.WriteJSON(w, http.StatusOK, map[string]any{
		"id":    fragment.ID,
		"chars": len(request.Text),
	})
	a.recordActivitySnapshot(sessionID, "Add text context", "", false, "")
}

func (a *App) handlePostContextFiles(w http.ResponseWriter, r *http.Request) {
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}

	var request addContextFilesRequest
	if err := httpserver.ParseJSON(r, &request); err != nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid JSON request body"))
		return
	}
	if len(request.RelativePaths) == 0 {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("relativePaths must not be empty"))
		return
	}

	added := make([]map[string]string, 0)
	invalid := make([]string, 0)
	_, err := a.contexts.Update(sessionID, func(state *contextstate.State) error {
		for _, pathStr := range request.RelativePaths {
			if strings.TrimSpace(pathStr) == "" {
				invalid = append(invalid, "(blank path)")
				continue
			}

			pathObj := filepath.Clean(pathStr)
			if filepath.IsAbs(pathStr) {
				invalid = append(invalid, pathStr+" (absolute path not allowed)")
				continue
			}
			absolutePath := filepath.Join(a.cfg.WorkspaceDir, pathObj)
			absolutePath, absErr := filepath.Abs(absolutePath)
			if absErr != nil {
				invalid = append(invalid, pathStr+" (not a regular file or does not exist)")
				continue
			}
			workspaceAbs, err := filepath.Abs(a.cfg.WorkspaceDir)
			if err != nil {
				return err
			}
			if !strings.HasPrefix(strings.ToLower(absolutePath), strings.ToLower(workspaceAbs)) {
				invalid = append(invalid, pathStr+" (escapes workspace)")
				continue
			}
			info, err := os.Stat(absolutePath)
			if err != nil || info.IsDir() {
				invalid = append(invalid, pathStr+" (not a regular file or does not exist)")
				continue
			}

			content, err := os.ReadFile(absolutePath)
			if err != nil {
				invalid = append(invalid, pathStr+" (not a regular file or does not exist)")
				continue
			}

			id, err := sessions.NewImportID()
			if err != nil {
				return err
			}
			mimeType := mime.TypeByExtension(filepath.Ext(absolutePath))
			if mimeType == "" {
				mimeType = "text/plain"
			}
			fragment := contextstate.Fragment{
				ID:               id,
				Type:             "FILE",
				ShortDescription: pathStr,
				Pinned:           false,
				Readonly:         false,
				Valid:            true,
				Editable:         true,
				Text:             string(content),
				URI:              "file://" + filepath.ToSlash(absolutePath),
				MimeType:         mimeType,
			}
			state.Fragments = append(state.Fragments, fragment)
			added = append(added, map[string]string{
				"id":           id,
				"relativePath": pathStr,
			})
		}
		return nil
	})
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to add files to context", err))
		return
	}

	if len(added) == 0 {
		msg := "No valid relative paths provided"
		if len(invalid) > 0 {
			msg += "; invalid: " + strings.Join(invalid, ", ")
		}
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError(msg))
		return
	}

	httpserver.WriteJSON(w, http.StatusOK, map[string]any{"added": added})
	a.recordActivitySnapshot(sessionID, "Add file context", "", false, "")
}

func (a *App) handlePostContextClasses(w http.ResponseWriter, r *http.Request) {
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}

	var request addContextClassesRequest
	if err := httpserver.ParseJSON(r, &request); err != nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid JSON request body"))
		return
	}
	if len(request.ClassNames) == 0 {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("classNames must not be empty"))
		return
	}

	added, err := a.addDefinitionFragments(sessionID, request.ClassNames, "CLASS", "class")
	if err == errNoValidDefinitions {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("No valid class names provided"))
		return
	}
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to add classes to context", err))
		return
	}

	httpserver.WriteJSON(w, http.StatusOK, map[string]any{"added": added})
	a.recordActivitySnapshot(sessionID, "Add class context", "", false, "")
}

func (a *App) handlePostContextMethods(w http.ResponseWriter, r *http.Request) {
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}

	var request addContextMethodsRequest
	if err := httpserver.ParseJSON(r, &request); err != nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid JSON request body"))
		return
	}
	if len(request.MethodNames) == 0 {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("methodNames must not be empty"))
		return
	}

	added, err := a.addDefinitionFragments(sessionID, request.MethodNames, "METHOD", "function")
	if err == errNoValidDefinitions {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("No valid method names provided"))
		return
	}
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to add methods to context", err))
		return
	}

	httpserver.WriteJSON(w, http.StatusOK, map[string]any{"added": added})
	a.recordActivitySnapshot(sessionID, "Add method context", "", false, "")
}

func (a *App) handleGetTaskList(w http.ResponseWriter) {
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}

	state, err := a.contexts.Get(sessionID)
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to retrieve task list", err))
		return
	}
	httpserver.WriteJSON(w, http.StatusOK, state.TaskList)
}

func (a *App) handlePostTaskList(w http.ResponseWriter, r *http.Request) {
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}

	var request replaceTaskListRequest
	if err := httpserver.ParseJSON(r, &request); err != nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid JSON request body"))
		return
	}
	if request.Tasks == nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("tasks must not be null"))
		return
	}

	for i, task := range request.Tasks {
		if strings.TrimSpace(task.ID) == "" {
			id, err := sessions.NewImportID()
			if err != nil {
				httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to replace task list", err))
				return
			}
			request.Tasks[i].ID = id
		}
	}

	taskList, err := a.contexts.ReplaceTaskList(sessionID, request.BigPicture, request.Tasks)
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to replace task list", err))
		return
	}
	httpserver.WriteJSON(w, http.StatusOK, taskList)
	a.recordActivitySnapshot(sessionID, "Replace task list", "", false, "")
}

func (a *App) handleGetConversation(w http.ResponseWriter) {
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}

	state, err := a.contexts.Get(sessionID)
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to retrieve conversation", err))
		return
	}
	httpserver.WriteJSON(w, http.StatusOK, map[string]any{"entries": state.Conversation})
}

func (a *App) handlePostJobs(w http.ResponseWriter, r *http.Request) {
	idempotencyKey := r.Header.Get("Idempotency-Key")
	if strings.TrimSpace(idempotencyKey) == "" {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Idempotency-Key header is required"))
		return
	}

	sessionID := r.Header.Get("X-Session-Id")
	if strings.TrimSpace(sessionID) != "" && !config.LooksLikeUUID(sessionID) {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid Session-Id format: must be a valid UUID"))
		return
	}

	var request jobSpecRequest
	if err := httpserver.ParseJSON(r, &request); err != nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid JSON request body"))
		return
	}

	plannerModel := strings.TrimSpace(derefString(request.PlannerModel))
	if plannerModel == "" {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("plannerModel is required"))
		return
	}

	overrides, err := validateModelOverrides(request)
	if err != nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError(err.Error()))
		return
	}

	if requestHasContextText(request) {
		valid := validateContextTexts(request)
		if len(valid) == 0 {
			httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("No valid context text provided"))
			return
		}
	}

	tags := make(map[string]string, len(request.Tags)+2)
	for key, value := range request.Tags {
		tags[key] = value
	}
	if strings.TrimSpace(sessionID) != "" {
		tags["session_id"] = sessionID
	}
	if githubToken := strings.TrimSpace(r.Header.Get("X-Github-Token")); githubToken != "" {
		tags["github_token"] = githubToken
	}

	preScan := request.PreScan != nil && *request.PreScan
	skipVerification := false
	if strings.EqualFold(tags["mode"], "ISSUE") && request.SkipVerification != nil && *request.SkipVerification {
		skipVerification = true
	}

	maxAttempts := jobs.DefaultMaxIssueFixAttempts
	jobSpec := jobs.JobSpec{
		TaskInput:           request.TaskInput,
		AutoCommit:          request.AutoCommit,
		AutoCompress:        request.AutoCompress,
		PlannerModel:        plannerModel,
		ScanModel:           request.ScanModel,
		CodeModel:           request.CodeModel,
		PreScan:             preScan,
		Tags:                tags,
		ReasoningLevel:      overrides.ReasoningLevel,
		ReasoningLevelCode:  overrides.ReasoningLevelCode,
		Temperature:         overrides.Temperature,
		TemperatureCode:     overrides.TemperatureCode,
		SkipVerification:    skipVerification,
		MaxIssueFixAttempts: &maxAttempts,
	}

	createResult, err := a.jobStore.CreateOrGetJob(idempotencyKey, jobSpec)
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to start job", err))
		return
	}
	a.writeJobCreateResponse(w, createResult, jobSpec)
}

func (a *App) handlePostPrReviewJob(w http.ResponseWriter, r *http.Request) {
	idempotencyKey := r.Header.Get("Idempotency-Key")
	if strings.TrimSpace(idempotencyKey) == "" {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Idempotency-Key header is required"))
		return
	}

	var request prReviewJobRequest
	if err := httpserver.ParseJSON(r, &request); err != nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid JSON request body"))
		return
	}

	if strings.TrimSpace(request.Owner) == "" {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("owner is required"))
		return
	}
	if strings.TrimSpace(request.Repo) == "" {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("repo is required"))
		return
	}
	if request.PRNumber <= 0 {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("prNumber must be a positive integer"))
		return
	}
	if strings.TrimSpace(request.GitHubToken) == "" {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("githubToken is required"))
		return
	}

	plannerModel := strings.TrimSpace(derefString(request.PlannerModel))
	if plannerModel == "" {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("plannerModel is required"))
		return
	}

	maxAttempts := jobs.DefaultMaxIssueFixAttempts
	tags := map[string]string{
		"mode":         "REVIEW",
		"github_token": request.GitHubToken,
		"repo_owner":   request.Owner,
		"repo_name":    request.Repo,
		"pr_number":    strconv.Itoa(request.PRNumber),
	}
	if request.GitHubAPIURL != nil && strings.TrimSpace(*request.GitHubAPIURL) != "" {
		tags["github_api_url"] = strings.TrimSpace(*request.GitHubAPIURL)
	}
	jobSpec := jobs.JobSpec{
		TaskInput:           "",
		AutoCommit:          false,
		AutoCompress:        false,
		PlannerModel:        plannerModel,
		PreScan:             false,
		Tags:                tags,
		SkipVerification:    false,
		MaxIssueFixAttempts: &maxAttempts,
	}

	createResult, err := a.jobStore.CreateOrGetJob(idempotencyKey, jobSpec)
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to start job", err))
		return
	}
	a.writeJobCreateResponse(w, createResult, jobSpec)
}

func (a *App) writeJobCreateResponse(w http.ResponseWriter, createResult jobs.JobCreateResult, jobSpec jobs.JobSpec) {
	status, err := a.jobStore.LoadStatus(createResult.JobID)
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to load job status", err))
		return
	}
	if status == nil {
		queued := jobs.QueuedStatus(createResult.JobID)
		status = &queued
	}

	response := map[string]any{
		"jobId": createResult.JobID,
		"state": status.State,
	}

	if createResult.IsNewJob {
		if !a.runner.TryReserve(createResult.JobID) {
			httpserver.WriteJSON(w, http.StatusConflict, httpserver.ErrorPayload{
				Code:    "JOB_IN_PROGRESS",
				Message: "A job is currently executing",
			})
			return
		}
		a.runner.RunAsync(createResult.JobID, jobSpec)
		httpserver.WriteJSON(w, http.StatusCreated, response)
		return
	}
	httpserver.WriteJSON(w, http.StatusOK, response)
}

func (a *App) handleGetJob(w http.ResponseWriter, jobID string) {
	status, err := a.jobStore.LoadStatus(jobID)
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to load job", err))
		return
	}
	if status == nil {
		httpserver.WriteJSON(w, http.StatusNotFound, httpserver.JobNotFound(jobID))
		return
	}
	httpserver.WriteJSON(w, http.StatusOK, status)
}

func (a *App) handleGetJobEvents(w http.ResponseWriter, r *http.Request, jobID string) {
	afterSeq := int64(-1)
	if rawAfter := r.URL.Query().Get("after"); rawAfter != "" {
		value, err := strconv.ParseInt(rawAfter, 10, 64)
		if err != nil {
			httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid 'after' parameter"))
			return
		}
		afterSeq = value
	}

	limit := 100
	if rawLimit := r.URL.Query().Get("limit"); rawLimit != "" {
		value, err := strconv.Atoi(rawLimit)
		if err != nil {
			httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid 'limit' parameter"))
			return
		}
		if value < 1000 {
			limit = value
		} else {
			limit = 1000
		}
	}

	events, err := a.jobStore.ReadEvents(jobID, afterSeq, limit)
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to read job events", err))
		return
	}

	nextAfter := afterSeq
	if len(events) > 0 {
		nextAfter = events[len(events)-1].Seq
	}
	httpserver.WriteJSON(w, http.StatusOK, map[string]any{
		"events":    events,
		"nextAfter": nextAfter,
	})
}

func (a *App) handleCancelJob(w http.ResponseWriter, jobID string) {
	err := a.runner.Cancel(jobID)
	if errors.Is(err, os.ErrNotExist) {
		httpserver.WriteJSON(w, http.StatusNotFound, httpserver.JobNotFound(jobID))
		return
	}
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to cancel job", err))
		return
	}

	w.WriteHeader(http.StatusAccepted)
}

func (a *App) handleGetJobDiff(w http.ResponseWriter, jobID string) {
	status, err := a.jobStore.LoadStatus(jobID)
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to get diff", err))
		return
	}
	if status == nil {
		httpserver.WriteJSON(w, http.StatusNotFound, httpserver.JobNotFound(jobID))
		return
	}

	diff, err := a.jobStore.ReadArtifact(jobID, "diff.txt")
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to get diff", err))
		return
	}
	if diff == nil {
		diff = []byte{}
	}
	diffText := string(diff)
	httpserver.WriteJSON(w, http.StatusOK, map[string]any{
		"diffs": buildActivityDiffEntries(diffText),
	})
}

func (a *App) handleGetActivityDiff(w http.ResponseWriter, r *http.Request) {
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}
	contextID := strings.TrimSpace(r.URL.Query().Get("contextId"))
	if contextID == "" {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("contextId is required"))
		return
	}

	history, _, err := a.contexts.History(sessionID)
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to retrieve activity diff", err))
		return
	}
	jobID := ""
	for _, entry := range history {
		if entry.ContextID == contextID {
			jobID = entry.DiffJobID
			break
		}
	}
	if jobID == "" {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Context not found: "+contextID))
		return
	}

	diff, err := a.jobStore.ReadArtifact(jobID, "diff.txt")
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to retrieve activity diff", err))
		return
	}
	if diff == nil {
		diff = []byte{}
	}
	diffText := string(diff)
	httpserver.WriteJSON(w, http.StatusOK, map[string]any{
		"diffs": []map[string]any{
			{
				"title":        "Activity Diff",
				"diff":         diffText,
				"linesAdded":   countDiffLines(diffText, "+"),
				"linesDeleted": countDiffLines(diffText, "-"),
			},
		},
	})
}

func (a *App) handlePostActivityUndo(w http.ResponseWriter, r *http.Request) {
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}
	var request activityContextRequest
	if err := httpserver.ParseJSON(r, &request); err != nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid JSON request body"))
		return
	}
	if strings.TrimSpace(request.ContextID) == "" {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("contextId is required"))
		return
	}
	if _, err := a.contexts.UndoTo(sessionID, request.ContextID); err != nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError(err.Error()))
		return
	}
	httpserver.WriteJSON(w, http.StatusOK, map[string]any{"status": "ok"})
}

func (a *App) handlePostActivityUndoStep(w http.ResponseWriter) {
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}
	if _, err := a.contexts.UndoStep(sessionID); err != nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError(err.Error()))
		return
	}
	httpserver.WriteJSON(w, http.StatusOK, map[string]any{"status": "ok"})
}

func (a *App) handlePostActivityRedo(w http.ResponseWriter) {
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}
	if _, err := a.contexts.Redo(sessionID); err != nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError(err.Error()))
		return
	}
	httpserver.WriteJSON(w, http.StatusOK, map[string]any{"status": "ok"})
}

func (a *App) handlePostActivityCopyContext(w http.ResponseWriter, r *http.Request, includeHistory bool) {
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}
	var request activityContextRequest
	if err := httpserver.ParseJSON(r, &request); err != nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid JSON request body"))
		return
	}
	if strings.TrimSpace(request.ContextID) == "" {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("contextId is required"))
		return
	}

	action := "Copy context"
	if includeHistory {
		action = "Copy context with history"
	}
	if _, _, err := a.contexts.CopyFromHistory(sessionID, request.ContextID, includeHistory, action); err != nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError(err.Error()))
		return
	}
	httpserver.WriteJSON(w, http.StatusOK, map[string]any{"status": "ok"})
}

func (a *App) handlePostActivityNewSession(w http.ResponseWriter, r *http.Request) {
	sessionID, ok := a.requireCurrentSession(w)
	if !ok {
		return
	}
	var request activityNewSessionRequest
	if err := httpserver.ParseJSON(r, &request); err != nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Invalid JSON request body"))
		return
	}
	if strings.TrimSpace(request.ContextID) == "" {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("contextId is required"))
		return
	}

	history, _, err := a.contexts.History(sessionID)
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to create new session", err))
		return
	}
	var snapshot *contextstate.HistoryState
	var taskType string
	for _, entry := range history {
		if entry.ContextID == request.ContextID {
			copy := entry.Snapshot
			snapshot = &copy
			taskType = entry.TaskType
			break
		}
	}
	if snapshot == nil {
		httpserver.WriteJSON(w, http.StatusBadRequest, httpserver.ValidationError("Context not found: "+request.ContextID))
		return
	}

	name := "Session"
	if request.Name != nil && strings.TrimSpace(*request.Name) != "" {
		name = strings.TrimSpace(*request.Name)
	}
	info, err := a.sessions.CreateSession(name)
	if err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to create new session", err))
		return
	}
	newState := contextstate.State{
		Fragments:    append([]contextstate.Fragment(nil), snapshot.Fragments...),
		TaskList:     contextstate.TaskListData{BigPicture: snapshot.TaskList.BigPicture, Tasks: append([]contextstate.TaskItem(nil), snapshot.TaskList.Tasks...)},
		Conversation: append([]contextstate.ConversationEntry(nil), snapshot.Conversation...),
		NextSequence: snapshot.NextSequence,
	}
	if _, err := a.contexts.SetState(info.ID, newState); err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to create new session", err))
		return
	}
	if _, _, err := a.contexts.RecordSnapshot(info.ID, "New session from activity", taskType, false, ""); err != nil {
		httpserver.WriteJSON(w, http.StatusInternalServerError, httpserver.InternalError("Failed to create new session", err))
		return
	}
	httpserver.WriteJSON(w, http.StatusOK, map[string]any{"status": "ok"})
}

func (a *App) recordActivitySnapshot(sessionID string, action string, taskType string, isAIResult bool, diffJobID string) {
	if strings.TrimSpace(sessionID) == "" {
		return
	}
	_, _, _ = a.contexts.RecordSnapshot(sessionID, action, taskType, isAIResult, diffJobID)
}

type activityBoundary int

const (
	activityBoundaryNone activityBoundary = iota
	activityBoundaryCutAfter
	activityBoundaryStandalone
)

func buildActivityGroups(history []contextstate.HistoryEntry, historyIndex int) []map[string]any {
	if len(history) == 0 {
		return []map[string]any{}
	}

	type segment struct {
		start int
		end   int
	}

	segments := make([]segment, 0, len(history))
	start := 0
	for i := range history {
		if activityEntryBoundary(history, i) != activityBoundaryNone {
			segments = append(segments, segment{start: start, end: i + 1})
			start = i + 1
		}
	}
	if start < len(history) {
		segments = append(segments, segment{start: start, end: len(history)})
	}

	groups := make([]map[string]any, 0, len(segments))
	for _, seg := range segments {
		for i := seg.start; i < seg.end; {
			if activityEntryBoundary(history, i) == activityBoundaryStandalone {
				groups = append(groups, buildActivityGroup(history[i:i+1], false, ""))
				i++
				continue
			}

			j := i + 1
			for j < seg.end && activityEntryBoundary(history, j) != activityBoundaryStandalone {
				j++
			}
			children := history[i:j]
			if len(children) >= 2 {
				groups = append(groups, buildActivityGroup(children, true, activityHeaderLabel(children)))
			} else {
				groups = append(groups, buildActivityGroup(children, false, ""))
			}
			i = j
		}
	}

	if len(groups) > 0 {
		groups[len(groups)-1]["isLastGroup"] = true
	}
	return groups
}

func buildActivityGroup(children []contextstate.HistoryEntry, showHeader bool, label string) map[string]any {
	entries := make([]map[string]any, 0, len(children))
	for _, entry := range children {
		entryMap := map[string]any{
			"contextId":  entry.ContextID,
			"action":     activityEntryDescription(entry),
			"isAiResult": entry.IsAIResult,
		}
		if strings.TrimSpace(entry.TaskType) != "" {
			entryMap["taskType"] = entry.TaskType
		}
		entries = append(entries, entryMap)
	}
	return map[string]any{
		"key":         children[0].ContextID,
		"showHeader":  showHeader,
		"isLastGroup": false,
		"label":       label,
		"entries":     entries,
	}
}

func activityEntryBoundary(history []contextstate.HistoryEntry, index int) activityBoundary {
	curr := history[index]
	if index > 0 {
		prev := history[index-1]
		if len(prev.Snapshot.Fragments) > 0 && len(curr.Snapshot.Fragments) == 0 {
			return activityBoundaryStandalone
		}
		if len(prev.Snapshot.Conversation) > 0 && len(curr.Snapshot.Conversation) == 0 {
			return activityBoundaryStandalone
		}
	}
	if curr.ResetFromID != "" {
		return activityBoundaryStandalone
	}
	if curr.IsAIResult {
		return activityBoundaryCutAfter
	}
	return activityBoundaryNone
}

func activityEntryDescription(entry contextstate.HistoryEntry) string {
	if entry.ResetFromID != "" {
		return "Copy From History"
	}
	return entry.Action
}

func activityHeaderLabel(children []contextstate.HistoryEntry) string {
	if len(children) == 1 {
		return activityEntryDescription(children[0])
	}
	counts := map[string]int{}
	order := make([]string, 0, len(children))
	for _, child := range children {
		word := firstWord(activityEntryDescription(child))
		if _, ok := counts[word]; !ok {
			order = append(order, word)
		}
		counts[word]++
	}
	formatted := make([]string, 0, len(order))
	for _, word := range order {
		if counts[word] > 1 {
			formatted = append(formatted, fmt.Sprintf("%s x%d", word, counts[word]))
		} else {
			formatted = append(formatted, word)
		}
	}
	if len(formatted) == 1 {
		return formatted[0]
	}
	if len(formatted) == 2 {
		return formatted[0] + " + " + formatted[1]
	}
	return formatted[0] + " + more"
}

func firstWord(text string) string {
	text = strings.TrimSpace(text)
	if text == "" {
		return ""
	}
	if idx := strings.IndexByte(text, ' '); idx >= 0 {
		return text[:idx]
	}
	return text
}

func countDiffLines(diff string, prefix string) int {
	count := 0
	for _, line := range strings.Split(diff, "\n") {
		if !strings.HasPrefix(line, prefix) {
			continue
		}
		if prefix == "+" && strings.HasPrefix(line, "+++") {
			continue
		}
		if prefix == "-" && strings.HasPrefix(line, "---") {
			continue
		}
		count++
	}
	return count
}

func buildActivityDiffEntries(diffText string) []map[string]any {
	sections := splitUnifiedDiffSections(diffText)
	if len(sections) == 0 {
		return []map[string]any{{
			"title":        "Activity Diff",
			"diff":         diffText,
			"linesAdded":   countDiffLines(diffText, "+"),
			"linesDeleted": countDiffLines(diffText, "-"),
		}}
	}

	result := make([]map[string]any, 0, len(sections))
	for _, section := range sections {
		title := diffSectionTitle(section)
		result = append(result, map[string]any{
			"title":        title,
			"diff":         section,
			"linesAdded":   countDiffLines(section, "+"),
			"linesDeleted": countDiffLines(section, "-"),
		})
	}
	return result
}

func splitUnifiedDiffSections(diffText string) []string {
	lines := strings.Split(diffText, "\n")
	sections := make([]string, 0)
	current := make([]string, 0)
	flush := func() {
		if len(current) == 0 {
			return
		}
		sections = append(sections, strings.TrimRight(strings.Join(current, "\n"), "\n"))
		current = []string{}
	}

	for _, line := range lines {
		if strings.HasPrefix(line, "diff --git ") && len(current) > 0 {
			flush()
		}
		if strings.TrimSpace(line) == "" && len(current) == 0 {
			continue
		}
		current = append(current, line)
	}
	flush()

	if len(sections) == 1 && !strings.HasPrefix(strings.TrimSpace(sections[0]), "diff --git ") {
		return nil
	}
	return sections
}

func diffSectionTitle(section string) string {
	for _, line := range strings.Split(section, "\n") {
		if strings.HasPrefix(line, "+++ b/") {
			return strings.TrimPrefix(line, "+++ b/")
		}
	}
	return "Activity Diff"
}

func ensureMethod(w http.ResponseWriter, r *http.Request, expected string) bool {
	if r.Method == expected {
		return true
	}
	httpserver.WriteJSON(w, http.StatusMethodNotAllowed, httpserver.ErrorPayload{
		Code:    "METHOD_NOT_ALLOWED",
		Message: "Method not allowed",
	})
	return false
}

func extractJobPath(path string) (jobID string, suffix string, ok bool) {
	const prefix = "/v1/jobs/"
	if !strings.HasPrefix(path, prefix) {
		return "", "", false
	}

	rest := strings.TrimPrefix(path, prefix)
	if rest == "" {
		return "", "", false
	}
	parts := strings.SplitN(rest, "/", 2)
	jobID = parts[0]
	if strings.TrimSpace(jobID) == "" {
		return "", "", false
	}
	if len(parts) == 1 {
		return jobID, "", true
	}
	return jobID, "/" + parts[1], true
}

func validateModelOverrides(request jobSpecRequest) (modelOverrides, error) {
	result := modelOverrides{}

	if request.ReasoningLevel != nil && strings.TrimSpace(*request.ReasoningLevel) != "" {
		level := strings.ToUpper(strings.TrimSpace(*request.ReasoningLevel))
		if _, ok := allowedReasoningLevels[level]; !ok {
			return modelOverrides{}, errors.New("reasoningLevel must be one of: DEFAULT, LOW, MEDIUM, HIGH, DISABLE")
		}
		result.ReasoningLevel = &level
	}

	if request.ReasoningLevelCode != nil && strings.TrimSpace(*request.ReasoningLevelCode) != "" {
		level := strings.ToUpper(strings.TrimSpace(*request.ReasoningLevelCode))
		if _, ok := allowedReasoningLevels[level]; !ok {
			return modelOverrides{}, errors.New("reasoningLevelCode must be one of: DEFAULT, LOW, MEDIUM, HIGH, DISABLE")
		}
		result.ReasoningLevelCode = &level
	}

	if request.Temperature != nil {
		if *request.Temperature < 0.0 || *request.Temperature > 2.0 {
			return modelOverrides{}, errors.New("temperature must be between 0.0 and 2.0")
		}
		result.Temperature = request.Temperature
	}

	if request.TemperatureCode != nil {
		if *request.TemperatureCode < 0.0 || *request.TemperatureCode > 2.0 {
			return modelOverrides{}, errors.New("temperatureCode must be between 0.0 and 2.0")
		}
		result.TemperatureCode = request.TemperatureCode
	}

	return result, nil
}

func requestHasContextText(request jobSpecRequest) bool {
	return len(request.ContextText) > 0 || (request.Context != nil && len(request.Context.Text) > 0)
}

func validateContextTexts(request jobSpecRequest) []string {
	raw := make([]string, 0, len(request.ContextText))
	raw = append(raw, request.ContextText...)
	if request.Context != nil {
		raw = append(raw, request.Context.Text...)
	}

	valid := make([]string, 0, len(raw))
	for _, value := range raw {
		if strings.TrimSpace(value) == "" {
			continue
		}
		if len([]byte(value)) > 1024*1024 {
			continue
		}
		valid = append(valid, value)
	}
	return valid
}

func derefString(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}

func (a *App) markSessionLoaded() {
	a.sessionMu.Lock()
	a.sessionLoaded = true
	a.sessionMu.Unlock()
}

func (a *App) requireCurrentSession(w http.ResponseWriter) (string, bool) {
	sessionID := a.sessions.CurrentSessionID()
	if sessionID == "" {
		httpserver.WriteJSON(w, http.StatusServiceUnavailable, httpserver.ErrorPayload{
			Code:    "NOT_READY",
			Message: "No session loaded",
		})
		return "", false
	}
	return sessionID, true
}

func classifyChipKind(fragment contextstate.Fragment) string {
	if !fragment.Valid {
		return "INVALID"
	}
	if fragment.Editable {
		return "EDIT"
	}
	return "OTHER"
}

func approximateTokens(text string) int {
	if strings.TrimSpace(text) == "" {
		return 0
	}
	return (len(text) + 3) / 4
}

func detectAppMimeType(path string) string {
	mimeType := mime.TypeByExtension(filepath.Ext(path))
	if mimeType == "" {
		return "text/plain"
	}
	return mimeType
}

func (a *App) collectSymbolCompletions(query string, limit int) []map[string]string {
	results := make([]map[string]string, 0, limit)
	for _, completion := range a.analyzer.CompleteSymbols(query, limit) {
		results = append(results, map[string]string{
			"type":   completion.Type,
			"name":   completion.Name,
			"detail": completion.Detail,
		})
	}
	return results
}

func (a *App) collectFileCompletions(query string, limit int) []map[string]string {
	results := make([]map[string]string, 0, limit)
	isPathQuery := strings.Contains(query, "/") || strings.Contains(query, "\\")
	normalizedQuery := strings.ToLower(filepath.ToSlash(query))

	seen := map[string]struct{}{}
	_ = filepath.WalkDir(a.cfg.WorkspaceDir, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() {
			name := strings.ToLower(d.Name())
			if name == ".git" || name == ".brokk" {
				return filepath.SkipDir
			}
			return nil
		}

		rel, err := filepath.Rel(a.cfg.WorkspaceDir, path)
		if err != nil {
			return nil
		}
		rel = filepath.ToSlash(rel)
		relLower := strings.ToLower(rel)
		nameLower := strings.ToLower(filepath.Base(rel))

		match := false
		if isPathQuery {
			match = strings.Contains(relLower, normalizedQuery)
		} else {
			match = strings.Contains(nameLower, normalizedQuery) || strings.Contains(relLower, normalizedQuery)
		}
		if !match {
			return nil
		}
		if _, ok := seen[rel]; ok {
			return nil
		}
		seen[rel] = struct{}{}
		results = append(results, map[string]string{
			"type":   "file",
			"name":   filepath.Base(rel),
			"detail": rel,
		})
		if len(results) >= limit {
			return errors.New("limit reached")
		}
		return nil
	})

	return results
}

func (a *App) addAnalyzerFragments(sessionID string, symbols []analyzer.Symbol, fragmentType string) ([]map[string]string, error) {
	added := make([]map[string]string, 0, len(symbols))
	_, err := a.contexts.Update(sessionID, func(state *contextstate.State) error {
		for _, symbol := range symbols {
			id, err := sessions.NewImportID()
			if err != nil {
				return err
			}
			fragment := contextstate.Fragment{
				ID:               id,
				Type:             fragmentType,
				ShortDescription: symbol.ShortName,
				Pinned:           false,
				Readonly:         true,
				Valid:            true,
				Editable:         false,
				Text:             a.analyzer.RenderSymbol(symbol),
				URI:              "brokk://symbol/" + strings.ToLower(fragmentType) + "/" + id,
				MimeType:         detectAppMimeType(symbol.RelativePath),
			}
			state.Fragments = append(state.Fragments, fragment)

			item := map[string]string{"id": id}
			if fragmentType == "CLASS" {
				item["className"] = symbol.FQName
			} else {
				item["methodName"] = symbol.FQName
			}
			added = append(added, item)
		}
		return nil
	})
	return added, err
}

var errNoValidDefinitions = errors.New("no valid definitions")

func (a *App) addDefinitionFragments(sessionID string, names []string, fragmentType string, symbolKind string) ([]map[string]string, error) {
	validGroups := make([]analyzer.DefinitionGroup, 0, len(names))
	for _, rawName := range names {
		trimmed := strings.TrimSpace(rawName)
		if trimmed == "" {
			continue
		}
		group, ok := a.analyzer.DefinitionGroup(trimmed, symbolKind)
		if !ok {
			continue
		}
		validGroups = append(validGroups, group)
	}
	if len(validGroups) == 0 {
		return nil, errNoValidDefinitions
	}

	added := make([]map[string]string, 0, len(validGroups))
	_, err := a.contexts.Update(sessionID, func(state *contextstate.State) error {
		for _, group := range validGroups {
			id, err := sessions.NewImportID()
			if err != nil {
				return err
			}
			fragment := contextstate.Fragment{
				ID:               id,
				Type:             fragmentType,
				ShortDescription: group.ShortName,
				Pinned:           false,
				Readonly:         true,
				Valid:            true,
				Editable:         false,
				Text:             a.analyzer.RenderDefinitionGroup(group),
				URI:              "brokk://symbol/" + strings.ToLower(fragmentType) + "/" + id,
				MimeType:         detectAppMimeType(group.RelativePath),
			}
			state.Fragments = append(state.Fragments, fragment)

			item := map[string]string{"id": id}
			if fragmentType == "CLASS" {
				item["className"] = group.FQName
			} else {
				item["methodName"] = group.FQName
			}
			added = append(added, item)
		}
		return nil
	})
	return added, err
}
