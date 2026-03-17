package runtime

import (
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"
)

type Event struct {
	Seq       int64  `json:"seq"`
	Timestamp int64  `json:"timestamp"`
	Type      string `json:"type"`
	Data      any    `json:"data"`
}

type JobSpec struct {
	TaskInput           string            `json:"taskInput"`
	SessionID           string            `json:"sessionId,omitempty"`
	AutoCommit          bool              `json:"autoCommit"`
	AutoCompress        bool              `json:"autoCompress"`
	PlannerModel        string            `json:"plannerModel"`
	ScanModel           string            `json:"scanModel,omitempty"`
	CodeModel           string            `json:"codeModel,omitempty"`
	PreScan             bool              `json:"preScan,omitempty"`
	Tags                map[string]string `json:"tags,omitempty"`
	SourceBranch        string            `json:"sourceBranch,omitempty"`
	TargetBranch        string            `json:"targetBranch,omitempty"`
	ReasoningLevel      string            `json:"reasoningLevel,omitempty"`
	ReasoningLevelCode  string            `json:"reasoningLevelCode,omitempty"`
	Temperature         *float64          `json:"temperature,omitempty"`
	TemperatureCode     *float64          `json:"temperatureCode,omitempty"`
	SkipVerification    bool              `json:"skipVerification,omitempty"`
	MaxIssueFixAttempts *int              `json:"maxIssueFixAttempts,omitempty"`
}

type Job struct {
	ID              string         `json:"jobId"`
	State           string         `json:"state"`
	Spec            JobSpec        `json:"spec"`
	StartTime       int64          `json:"startTime,omitempty"`
	EndTime         int64          `json:"endTime,omitempty"`
	ProgressPercent int            `json:"progressPercent,omitempty"`
	Result          any            `json:"result,omitempty"`
	Error           any            `json:"error,omitempty"`
	Metadata        map[string]any `json:"metadata,omitempty"`
	Diff            string         `json:"-"`
	idempotencyKey  string
	events          []Event
	nextSeq         int64
	cancelled       bool
	mu              sync.RWMutex
}

type JobStore struct {
	mu          sync.RWMutex
	jobs        map[string]*Job
	jobsByKey   map[string]*Job
	nextID      int64
	activeJobID string
}

var errAnotherJobRunning = errors.New("another job is already running")

func NewJobStore() *JobStore {
	return &JobStore{
		jobs:      make(map[string]*Job),
		jobsByKey: make(map[string]*Job),
	}
}

func (s *JobStore) CreateWithKey(spec JobSpec, idempotencyKey string) (*Job, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if existing, ok := s.jobsByKey[idempotencyKey]; ok && idempotencyKey != "" {
		return existing, true, nil
	}
	if s.activeJobID != "" {
		active := s.jobs[s.activeJobID]
		if active != nil {
			state := strings.ToUpper(strings.TrimSpace(active.State))
			if state == "RUNNING" || state == "QUEUED" {
				return nil, false, errAnotherJobRunning
			}
		}
	}
	s.nextID++
	job := &Job{
		ID:       fmt.Sprintf("job-%d", s.nextID),
		State:    "QUEUED",
		Spec:     spec,
		Metadata: map[string]any{},
	}
	s.jobs[job.ID] = job
	if idempotencyKey != "" {
		job.idempotencyKey = idempotencyKey
		s.jobsByKey[idempotencyKey] = job
	}
	s.activeJobID = job.ID
	return job, false, nil
}

func (s *JobStore) Create(spec JobSpec) *Job {
	job, _, _ := s.CreateWithKey(spec, "")
	return job
}
func (s *JobStore) Release(jobID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.activeJobID == jobID {
		s.activeJobID = ""
	}
}

func (s *JobStore) Get(id string) (*Job, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	job, ok := s.jobs[id]
	return job, ok
}

func (j *Job) SetState(state string) {
	j.mu.Lock()
	defer j.mu.Unlock()
	j.State = state
	if state == "RUNNING" && j.StartTime == 0 {
		j.StartTime = time.Now().UnixMilli()
	}
	if state == "COMPLETED" || state == "FAILED" || state == "CANCELLED" {
		j.EndTime = time.Now().UnixMilli()
	}
}

func (j *Job) SetProgress(progress int) {
	j.mu.Lock()
	defer j.mu.Unlock()
	if progress < 0 {
		progress = 0
	}
	if progress > 100 {
		progress = 100
	}
	j.ProgressPercent = progress
}

func (j *Job) SetResult(result any) {
	j.mu.Lock()
	defer j.mu.Unlock()
	j.Result = result
}

func (j *Job) SetError(code string, message string) {
	j.mu.Lock()
	defer j.mu.Unlock()
	j.Error = map[string]any{"code": code, "message": message}
}

func (j *Job) StatusPayload() map[string]any {
	j.mu.RLock()
	defer j.mu.RUnlock()
	return map[string]any{
		"jobId":           j.ID,
		"state":           j.State,
		"startTime":       j.StartTime,
		"endTime":         j.EndTime,
		"progressPercent": j.ProgressPercent,
		"result":          j.Result,
		"error":           j.Error,
		"metadata":        j.Metadata,
	}
}

func (j *Job) Cancel() {
	j.mu.Lock()
	alreadyCancelled := j.cancelled
	j.cancelled = true
	j.State = "CANCELLED"
	j.EndTime = time.Now().UnixMilli()
	j.mu.Unlock()
	if !alreadyCancelled {
		j.AppendEvent("NOTIFICATION", map[string]any{"level": "WARNING", "message": "Job cancelled"})
		j.AppendEvent("STATE_HINT", map[string]any{"name": "taskInProgress", "value": false})
	}
}

func (j *Job) Cancelled() bool {
	j.mu.RLock()
	defer j.mu.RUnlock()
	return j.cancelled
}

func (j *Job) AppendEvent(eventType string, data any) {
	j.mu.Lock()
	defer j.mu.Unlock()
	j.nextSeq++
	j.events = append(j.events, Event{Seq: j.nextSeq, Timestamp: time.Now().UnixMilli(), Type: eventType, Data: data})
}

func (j *Job) Snapshot(after int64, limit int) ([]Event, int64, string) {
	j.mu.RLock()
	defer j.mu.RUnlock()
	if limit <= 0 {
		limit = 100
	}
	events := make([]Event, 0, limit)
	nextAfter := after
	for _, event := range j.events {
		if event.Seq <= after {
			continue
		}
		events = append(events, event)
		nextAfter = event.Seq
		if len(events) >= limit {
			break
		}
	}
	return events, nextAfter, j.State
}

func normalizeMode(spec JobSpec) string {
	if spec.Tags == nil {
		return "ARCHITECT"
	}
	mode := strings.TrimSpace(spec.Tags["mode"])
	if mode == "" {
		return "ARCHITECT"
	}
	return strings.ToUpper(mode)
}

func (j *Job) runBootstrapWorkflow(sessionStore *SessionStore, store *JobStore) {
	defer store.Release(j.ID)

	mode := normalizeMode(j.Spec)
	taskInput := strings.TrimSpace(j.Spec.TaskInput)
	if taskInput == "" {
		taskInput = defaultTaskInputForMode(mode)
	}

	j.SetState("RUNNING")
	j.SetProgress(5)
	j.AppendEvent("NOTIFICATION", map[string]any{"level": "INFO", "message": fmt.Sprintf("Starting %s job", mode)})
	j.AppendEvent("STATE_HINT", map[string]any{"name": "taskInProgress", "value": true})
	j.AppendEvent("STATE_HINT", map[string]any{"name": "actionButtonsEnabled", "value": false})
	j.AppendEvent("CONTEXT_BASELINE", map[string]any{"count": 0, "snippet": ""})

	if mode == "SEARCH" || j.Spec.PreScan {
		j.AppendEvent("TOOL_CALL", map[string]any{"id": "tool-1", "name": "scan", "arguments": fmt.Sprintf(`{"goal":%q}`, taskInput)})
		j.AppendEvent("TOOL_OUTPUT", map[string]any{"id": "tool-1", "name": "scan", "status": "SUCCESS", "resultText": "Bootstrap scan complete"})
	}

	tokens := strings.Fields(taskInput)
	if len(tokens) == 0 {
		tokens = []string{"No", "task", "input", "provided."}
	}
	for index, token := range tokens {
		if j.Cancelled() {
			return
		}
		j.AppendEvent("LLM_TOKEN", map[string]any{
			"token":        token + " ",
			"messageType":  "AI",
			"isNewMessage": index == 0,
			"isReasoning":  false,
			"isTerminal":   false,
		})
		j.SetProgress(10 + ((index + 1) * 70 / len(tokens)))
		time.Sleep(5 * time.Millisecond)
	}
	j.AppendEvent("LLM_TOKEN", map[string]any{
		"token":        "",
		"messageType":  "AI",
		"isNewMessage": false,
		"isReasoning":  false,
		"isTerminal":   true,
	})

	if sessionStore != nil && sessionStore.HasLoadedSession() {
		answer := strings.TrimSpace(taskInput)
		_, _ = sessionStore.UpdateCurrentWithAction(func(session *Session) error {
			sequence := len(session.Conversation) + 1
			session.Conversation = append(session.Conversation, ConversationEntry{
				Sequence: sequence,
				TaskType: mode,
				Messages: []ConversationMessage{
					{Role: "user", Text: taskInput},
					{Role: "ai", Text: fmt.Sprintf("Bootstrap %s response for %s", strings.ToLower(mode), answer)},
				},
			})
			return nil
		}, fmt.Sprintf("%s response", mode), mode)
	}

	if !j.Spec.SkipVerification && (mode == "ARCHITECT" || mode == "CODE" || mode == "ISSUE") {
		j.AppendEvent("COMMAND_RESULT", map[string]any{
			"stage":      "verification",
			"command":    "",
			"skipped":    true,
			"success":    true,
			"output":     "",
			"skipReason": "Bootstrap runtime does not execute verification yet",
		})
	}

	j.AppendEvent("NOTIFICATION", map[string]any{"level": "INFO", "message": fmt.Sprintf("%s job completed", mode)})
	j.AppendEvent("STATE_HINT", map[string]any{"name": "taskInProgress", "value": false})
	j.AppendEvent("STATE_HINT", map[string]any{"name": "actionButtonsEnabled", "value": true})
	j.SetProgress(100)
	j.SetResult(map[string]any{"mode": mode})
	j.SetState("COMPLETED")
}

func (j *Job) RunBootstrapWorkflow() {
	j.runBootstrapWorkflow(nil, &JobStore{})
}

func (j *Job) RunBootstrapWorkflowWithStore(sessionStore *SessionStore, store *JobStore) {
	j.runBootstrapWorkflow(sessionStore, store)
}
func defaultTaskInputForMode(mode string) string {
	switch mode {
	case "SEARCH":
		return "Describe the relevant code paths in this workspace."
	case "REVIEW":
		return "Review the pull request and summarize issues."
	case "ISSUE", "ISSUE_DIAGNOSE":
		return "Investigate the GitHub issue and propose a remediation."
	case "ISSUE_WRITER":
		return "Gather evidence and draft a GitHub issue."
	default:
		return "Complete the requested task."
	}
}
