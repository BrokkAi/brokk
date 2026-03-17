package execution

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/brokk/brokk/go-runtime/internal/analyzer"
	"github.com/brokk/brokk/go-runtime/internal/contextstate"
	"github.com/brokk/brokk/go-runtime/internal/githubclient"
	"github.com/brokk/brokk/go-runtime/internal/jobs"
	"github.com/brokk/brokk/go-runtime/internal/review"
	"github.com/brokk/brokk/go-runtime/internal/reviewruntime"
	"github.com/brokk/brokk/go-runtime/internal/sessions"
)

type Runner struct {
	store     *jobs.JobStore
	sessions  *sessions.Manager
	contexts  *contextstate.Manager
	analyzer  *analyzer.Service
	workspace string
	reviews   reviewruntime.Generator

	mu            sync.Mutex
	cancels       map[string]context.CancelFunc
	reservedJobID string
}

type searchMatch struct {
	path string
	kind string
	line string
}

var identifierPattern = regexp.MustCompile(`[A-Za-z_][A-Za-z0-9_]{3,}`)

func NewRunner(store *jobs.JobStore, sessions *sessions.Manager, contexts *contextstate.Manager, analyzerService *analyzer.Service, workspace string) *Runner {
	return NewRunnerWithReviewGenerator(store, sessions, contexts, analyzerService, workspace, reviewruntime.NewHeuristicGenerator())
}

func NewRunnerWithReviewGenerator(
	store *jobs.JobStore,
	sessions *sessions.Manager,
	contexts *contextstate.Manager,
	analyzerService *analyzer.Service,
	workspace string,
	reviewGenerator reviewruntime.Generator,
) *Runner {
	if reviewGenerator == nil {
		reviewGenerator = reviewruntime.NewHeuristicGenerator()
	}
	return &Runner{
		store:     store,
		sessions:  sessions,
		contexts:  contexts,
		analyzer:  analyzerService,
		workspace: workspace,
		reviews:   reviewGenerator,
		cancels:   map[string]context.CancelFunc{},
	}
}

func (r *Runner) RunAsync(jobID string, spec jobs.JobSpec) {
	ctx, cancel := context.WithCancel(context.Background())

	r.mu.Lock()
	r.cancels[jobID] = cancel
	r.mu.Unlock()

	go func() {
		defer func() {
			r.mu.Lock()
			delete(r.cancels, jobID)
			if r.reservedJobID == jobID {
				r.reservedJobID = ""
			}
			r.mu.Unlock()
		}()

		_ = r.run(ctx, jobID, spec)
	}()
}

func (r *Runner) TryReserve(jobID string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.reservedJobID != "" {
		return false
	}
	r.reservedJobID = jobID
	return true
}

func (r *Runner) ReleaseIfOwner(jobID string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.reservedJobID != jobID {
		return false
	}
	r.reservedJobID = ""
	return true
}

func (r *Runner) Cancel(jobID string) error {
	r.mu.Lock()
	cancel, ok := r.cancels[jobID]
	r.mu.Unlock()
	if ok {
		cancel()
	}
	return r.markCancelled(jobID, true)
}

func (r *Runner) run(ctx context.Context, jobID string, spec jobs.JobSpec) error {
	status, err := r.store.LoadStatus(jobID)
	if err != nil {
		return err
	}
	if status == nil {
		queued := jobs.QueuedStatus(jobID)
		status = &queued
	}
	if errors.Is(ctx.Err(), context.Canceled) {
		_ = r.markCancelled(jobID, false)
		return nil
	}
	if isTerminalState(status.State) {
		return nil
	}
	status.State = "RUNNING"
	if err := r.store.UpdateStatus(jobID, *status); err != nil {
		return err
	}

	r.appendNotification(jobID, "INFO", "Job started: "+jobID)

	mode := parseMode(spec)
	var output string

	switch mode {
	case "ASK":
		output, err = r.executeAsk(ctx, spec)
	case "SEARCH":
		output, err = r.executeSearch(ctx, spec)
	case "REVIEW":
		output, err = r.executeReview(ctx, jobID, spec)
	case "PLAN":
		output, err = r.executePlan(ctx, spec)
	case "ARCHITECT":
		output, err = r.executeArchitect(ctx, jobID, spec)
	case "CODE":
		output, err = r.executeCode(ctx, jobID, spec)
	case "LUTZ":
		output, err = r.executeLutz(ctx, jobID, spec)
	default:
		err = errors.New("mode not yet implemented in Go runtime: " + mode)
	}

	if errors.Is(ctx.Err(), context.Canceled) {
		_ = r.markCancelled(jobID, false)
		return nil
	}

	if err != nil {
		errorText := err.Error()
		status.State = "FAILED"
		status.EndTime = time.Now().UnixMilli()
		status.Error = &errorText
		status.Result = nil
		_ = r.store.UpdateStatus(jobID, *status)
		_, _ = r.store.AppendEvent(jobID, jobs.NewEvent("ERROR", map[string]any{
			"title":   "Job error",
			"message": errorText,
		}))
		return nil
	}

	r.appendText(jobID, output)
	r.appendNotification(jobID, "INFO", "Job completed: "+jobID)
	status.State = "COMPLETED"
	status.EndTime = time.Now().UnixMilli()
	status.ProgressPercent = 100
	status.Result = map[string]any{
		"mode":   mode,
		"output": output,
	}
	status.Error = nil
	return r.store.UpdateStatus(jobID, *status)
}

func (r *Runner) executePlan(ctx context.Context, spec jobs.JobSpec) (string, error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}

	sessionID := r.sessions.CurrentSessionID()
	if sessionID == "" {
		return "", errors.New("no session loaded")
	}
	state, err := r.contexts.Get(sessionID)
	if err != nil {
		return "", err
	}

	query := strings.TrimSpace(spec.TaskInput)
	if query == "" {
		query = "Plan the next executor task"
	}

	bigPicture := "Plan for: " + query
	taskTexts := make([]string, 0, 3)
	taskTexts = append(taskTexts, "Restate the request and note success conditions for: "+query)

	contextSummary := fmt.Sprintf("Review %d context fragments and %d existing task items.", len(state.Fragments), len(state.TaskList.Tasks))
	if len(state.Fragments) > 0 {
		contextSummary += " Start with: " + state.Fragments[0].ShortDescription
	}
	taskTexts = append(taskTexts, contextSummary)
	taskTexts = append(taskTexts, "Carry out the next implementation step and verify the result.")

	tasks := make([]contextstate.TaskItem, 0, len(taskTexts))
	for i, text := range taskTexts {
		tasks = append(tasks, contextstate.TaskItem{
			ID:    fmt.Sprintf("plan-%d", i+1),
			Title: planTaskTitle(i),
			Text:  text,
			Done:  false,
		})
	}

	planned, err := r.contexts.ReplaceTaskList(sessionID, &bigPicture, tasks)
	if err != nil {
		return "", err
	}

	lines := []string{
		"PLAN response (Go runtime preview)",
		"Goal: " + query,
		"Big picture: " + bigPicture,
		"Tasks:",
	}
	for i, task := range planned.Tasks {
		lines = append(lines, fmt.Sprintf("%d. %s - %s", i+1, task.Title, task.Text))
	}
	return strings.Join(lines, "\n"), nil
}

func (r *Runner) executeArchitect(ctx context.Context, jobID string, spec jobs.JobSpec) (string, error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}

	sessionID := r.sessions.CurrentSessionID()
	if sessionID == "" {
		return "", errors.New("no session loaded")
	}
	state, err := r.contexts.Get(sessionID)
	if err != nil {
		return "", err
	}

	targetRelativePath := "BROKK_ARCHITECT_OUTPUT.md"
	targetPath := filepath.Join(r.workspace, targetRelativePath)
	oldContent, err := os.ReadFile(targetPath)
	if err != nil && !os.IsNotExist(err) {
		return "", err
	}

	newContent := renderArchitectContent(spec.TaskInput, state, string(oldContent))
	if err := os.MkdirAll(filepath.Dir(targetPath), 0o755); err != nil {
		return "", err
	}
	if err := os.WriteFile(targetPath, []byte(newContent), 0o644); err != nil {
		return "", err
	}

	if err := r.store.WriteArtifact(jobID, "diff.txt", []byte(buildUnifiedDiff(targetRelativePath, string(oldContent), newContent))); err != nil {
		return "", err
	}
	if err := r.syncWorkspaceFragment(sessionID, targetRelativePath, targetPath, newContent, "text/markdown"); err != nil {
		return "", err
	}

	r.appendStateHint(jobID, "workspaceUpdated", true, targetRelativePath)
	r.appendNotification(jobID, "INFO", "Updated workspace file: "+targetRelativePath)

	lines := []string{
		"ARCHITECT response (Go runtime preview)",
		"Request: " + strings.TrimSpace(spec.TaskInput),
		"Updated file: " + targetRelativePath,
		"Diff artifact: diff.txt",
	}
	if len(state.Fragments) > 0 {
		lines = append(lines, "Context fragments consulted: "+itoa(len(state.Fragments)))
	}
	return strings.Join(lines, "\n"), nil
}

func (r *Runner) executeCode(ctx context.Context, jobID string, spec jobs.JobSpec) (string, error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}

	sessionID := r.sessions.CurrentSessionID()
	if sessionID == "" {
		return "", errors.New("no session loaded")
	}
	state, err := r.contexts.Get(sessionID)
	if err != nil {
		return "", err
	}

	targetRelativePath, targetPath := r.selectCodeTarget(state)
	oldContent, err := os.ReadFile(targetPath)
	if err != nil && !os.IsNotExist(err) {
		return "", err
	}

	newContent := renderCodeContent(spec, targetRelativePath, string(oldContent))
	if err := os.MkdirAll(filepath.Dir(targetPath), 0o755); err != nil {
		return "", err
	}
	if err := os.WriteFile(targetPath, []byte(newContent), 0o644); err != nil {
		return "", err
	}

	if err := r.store.WriteArtifact(jobID, "diff.txt", []byte(buildUnifiedDiff(targetRelativePath, string(oldContent), newContent))); err != nil {
		return "", err
	}
	if err := r.syncWorkspaceFragment(sessionID, targetRelativePath, targetPath, newContent, detectMimeType(targetPath)); err != nil {
		return "", err
	}

	r.appendStateHint(jobID, "workspaceUpdated", true, targetRelativePath)
	r.appendNotification(jobID, "INFO", "Updated code file: "+targetRelativePath)

	codeModel := "(default code model)"
	if spec.CodeModel != nil && strings.TrimSpace(*spec.CodeModel) != "" {
		codeModel = strings.TrimSpace(*spec.CodeModel)
	}
	lines := []string{
		"CODE response (Go runtime preview)",
		"Request: " + strings.TrimSpace(spec.TaskInput),
		"Updated file: " + targetRelativePath,
		"Code model: " + codeModel,
		"Diff artifact: diff.txt",
	}
	return strings.Join(lines, "\n"), nil
}

func (r *Runner) executeLutz(ctx context.Context, jobID string, spec jobs.JobSpec) (string, error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}

	sessionID := r.sessions.CurrentSessionID()
	if sessionID == "" {
		return "", errors.New("no session loaded")
	}
	state, err := r.contexts.Get(sessionID)
	if err != nil {
		return "", err
	}

	query := strings.TrimSpace(spec.TaskInput)
	if query == "" {
		query = "Execute the current multi-step objective"
	}
	plannedTasks := buildLutzTasks(query, state)
	bigPicture := "LUTZ plan for: " + query
	plannedTaskList, err := r.contexts.ReplaceTaskList(sessionID, &bigPicture, plannedTasks)
	if err != nil {
		return "", err
	}

	r.appendText(jobID, "Planning phase: Analyzing objective and generating tasks.")
	r.appendNotification(jobID, "INFO", fmt.Sprintf("Task list generated with %d subtasks", len(plannedTaskList.Tasks)))

	targetRelativePath, targetPath := r.selectTarget(state, "BROKK_LUTZ_OUTPUT.md")
	oldContent, err := os.ReadFile(targetPath)
	if err != nil && !os.IsNotExist(err) {
		return "", err
	}

	newContent := string(oldContent)
	for i, task := range plannedTaskList.Tasks {
		if err := ctx.Err(); err != nil {
			return "", err
		}

		r.appendText(jobID, fmt.Sprintf("Executing task %d/%d: %s", i+1, len(plannedTaskList.Tasks), task.Title))
		newContent = renderLutzContent(spec, targetRelativePath, newContent, task, i+1, len(plannedTaskList.Tasks))

		if err := r.markTaskDone(sessionID, task.ID); err != nil {
			return "", err
		}

		progress := ((i + 1) * 100) / len(plannedTaskList.Tasks)
		if err := r.updateProgress(jobID, progress); err != nil {
			return "", err
		}
		r.appendNotification(jobID, "INFO", fmt.Sprintf("Task %d completed, progress: %d%%", i+1, progress))
	}

	if err := os.MkdirAll(filepath.Dir(targetPath), 0o755); err != nil {
		return "", err
	}
	if err := os.WriteFile(targetPath, []byte(newContent), 0o644); err != nil {
		return "", err
	}

	if err := r.store.WriteArtifact(jobID, "diff.txt", []byte(buildUnifiedDiff(targetRelativePath, string(oldContent), newContent))); err != nil {
		return "", err
	}
	if err := r.syncWorkspaceFragment(sessionID, targetRelativePath, targetPath, newContent, detectMimeType(targetPath)); err != nil {
		return "", err
	}

	r.appendStateHint(jobID, "workspaceUpdated", true, targetRelativePath)
	r.appendNotification(jobID, "INFO", "Updated LUTZ workspace file: "+targetRelativePath)

	lines := []string{
		"LUTZ response (Go runtime preview)",
		"Goal: " + query,
		"Generated tasks: " + itoa(len(plannedTaskList.Tasks)),
		"Updated file: " + targetRelativePath,
		"Diff artifact: diff.txt",
	}
	return strings.Join(lines, "\n"), nil
}

func (r *Runner) executeReview(ctx context.Context, jobID string, spec jobs.JobSpec) (string, error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}

	requiredTags := []string{"github_token", "repo_owner", "repo_name", "pr_number"}
	for _, key := range requiredTags {
		if strings.TrimSpace(spec.Tags[key]) == "" {
			return "", errors.New("REVIEW requires " + key + " in tags")
		}
	}

	var state contextstate.State
	sessionID := r.sessions.CurrentSessionID()
	if sessionID != "" {
		loadedState, err := r.contexts.Get(sessionID)
		if err == nil {
			state = loadedState
		}
	}

	r.appendNotification(jobID, "INFO", "PR review: gathering candidate files")
	candidates, err := r.collectReviewCandidates(ctx, state)
	if err != nil {
		return "", err
	}

	owner := strings.TrimSpace(spec.Tags["repo_owner"])
	repo := strings.TrimSpace(spec.Tags["repo_name"])
	prNumber := strings.TrimSpace(spec.Tags["pr_number"])
	diffText, diffSource, err := r.buildReviewDiff(ctx, jobID, spec, candidates)
	if err != nil {
		return "", err
	}
	annotatedDiff := review.AnnotateDiffWithLineNumbers(diffText)
	prTitle, prDescription := r.reviewPromptMetadata(ctx, spec)
	r.appendNotification(jobID, "INFO", "Brokk Context Engine: analyzing repository context for PR review...")
	contextReport := r.buildReviewContext(state, candidates, annotatedDiff)
	r.appendNotification(jobID, "INFO", "Brokk Context Engine: complete - contextual insights added to Workspace.")

	promptText := review.BuildPrompt(annotatedDiff, contextReport.SummaryMarkdown, review.High, 3, prTitle, prDescription)
	generated, err := r.reviews.Generate(ctx, reviewruntime.Request{
		Owner:          owner,
		Repo:           repo,
		PRNumber:       prNumber,
		Model:          strings.TrimSpace(spec.PlannerModel),
		Prompt:         promptText,
		AnnotatedDiff:  annotatedDiff,
		ContextSummary: contextReport.SummaryMarkdown,
		MinSeverity:    review.High,
		MaxComments:    3,
	})
	if err != nil {
		return "", err
	}
	rawResponse := generated.RawResponse
	parsedResponse := review.ParseResponse(rawResponse)
	if parsedResponse == nil {
		return "", errors.New("PR review response was not valid JSON. Expected JSON object with 'summaryMarkdown' field")
	}
	summary := parsedResponse.SummaryMarkdown
	comments := parsedResponse.Comments
	reviewJSON, err := json.MarshalIndent(parsedResponse, "", "  ")
	if err != nil {
		return "", err
	}

	if err := r.store.WriteArtifact(jobID, "review-prompt.txt", []byte(promptText)); err != nil {
		return "", err
	}
	contextJSON, err := json.MarshalIndent(contextReport, "", "  ")
	if err != nil {
		return "", err
	}
	if err := r.store.WriteArtifact(jobID, "review-context.md", []byte(contextReport.SummaryMarkdown+"\n")); err != nil {
		return "", err
	}
	if err := r.store.WriteArtifact(jobID, "review-context.json", append(contextJSON, '\n')); err != nil {
		return "", err
	}
	if err := r.store.WriteArtifact(jobID, "review-response.json", []byte(rawResponse+"\n")); err != nil {
		return "", err
	}
	if err := r.store.WriteArtifact(jobID, "review.md", []byte(summary+"\n")); err != nil {
		return "", err
	}
	if err := r.store.WriteArtifact(jobID, "review.json", append(reviewJSON, '\n')); err != nil {
		return "", err
	}
	if err := r.store.WriteArtifact(jobID, "diff.txt", []byte(diffText)); err != nil {
		return "", err
	}
	if err := r.store.WriteArtifact(jobID, "annotated-diff.txt", []byte(annotatedDiff)); err != nil {
		return "", err
	}

	postingSummary := "GitHub posting skipped (no materialized git repo available for GitHub review integration)"
	if r.canAttemptGitHubReview(spec) {
		postResult, err := r.postReviewToGitHub(ctx, spec, defaultGitHubAPIURL(spec), summary, comments)
		if err != nil {
			return "", err
		}
		postingSummary = postResult
	}

	r.appendNotification(jobID, "INFO", fmt.Sprintf("PR review prepared with %d inline comment(s)", len(comments)))
	r.appendNotification(jobID, "INFO", diffSource)
	r.appendNotification(jobID, "INFO", "Review generation provider: "+generated.Provider)
	r.appendNotification(jobID, "INFO", postingSummary)
	r.appendStateHint(jobID, "reviewReady", true, fmt.Sprintf("%s/%s#%s", owner, repo, prNumber))

	lines := []string{
		"REVIEW response (Go runtime preview)",
		fmt.Sprintf("Pull request: %s/%s#%s", owner, repo, prNumber),
		fmt.Sprintf("Planner model: %s", strings.TrimSpace(spec.PlannerModel)),
		fmt.Sprintf("Files reviewed: %d", len(candidates)),
		fmt.Sprintf("Inline comments prepared: %d", len(comments)),
		fmt.Sprintf("Diff source: %s", diffSource),
		fmt.Sprintf("Review provider: %s", generated.Provider),
		postingSummary,
		"Artifacts: review-prompt.txt, review-context.md, review-context.json, review-response.json, review.md, review.json, diff.txt, annotated-diff.txt",
		"",
		summary,
	}
	return strings.Join(lines, "\n"), nil
}

func (r *Runner) executeAsk(ctx context.Context, spec jobs.JobSpec) (string, error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}

	sessionID := r.sessions.CurrentSessionID()
	if sessionID == "" {
		return "", errors.New("no session loaded")
	}
	state, err := r.contexts.Get(sessionID)
	if err != nil {
		return "", err
	}

	lines := []string{
		"ASK response (Go runtime preview)",
		"Query: " + strings.TrimSpace(spec.TaskInput),
	}

	if len(state.Fragments) == 0 && len(state.TaskList.Tasks) == 0 {
		lines = append(lines, "No context is currently loaded in this session.")
	} else {
		lines = append(lines, "Current session context summary:")
		lines = append(lines, "Fragments: "+itoa(len(state.Fragments)))
		for i, fragment := range state.Fragments {
			if i >= 3 {
				lines = append(lines, "More fragments omitted.")
				break
			}
			text := strings.TrimSpace(fragment.Text)
			if len(text) > 120 {
				text = text[:120]
			}
			lines = append(lines, "- ["+fragment.Type+"] "+fragment.ShortDescription)
			if text != "" {
				lines = append(lines, "  Text: "+text)
			}
		}

		if state.TaskList.BigPicture != nil || len(state.TaskList.Tasks) > 0 {
			if state.TaskList.BigPicture != nil {
				lines = append(lines, "Task list: "+*state.TaskList.BigPicture)
			} else {
				lines = append(lines, "Task list present with no big picture.")
			}
			for i, task := range state.TaskList.Tasks {
				if i >= 5 {
					lines = append(lines, "More tasks omitted.")
					break
				}
				done := "todo"
				if task.Done {
					done = "done"
				}
				lines = append(lines, "- Task "+task.Title+" ["+done+"]")
			}
		}
	}

	return strings.Join(lines, "\n"), nil
}

func (r *Runner) executeSearch(ctx context.Context, spec jobs.JobSpec) (string, error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}
	query := strings.TrimSpace(spec.TaskInput)
	if query == "" {
		return "SEARCH results (Go runtime preview)\nNo query provided.", nil
	}

	matches := make([]searchMatch, 0, 10)
	normalized := strings.ToLower(query)
	if !strings.Contains(query, "/") && !strings.Contains(query, "\\") && r.analyzer != nil {
		for _, symbol := range r.analyzer.SearchSymbols(query, 6) {
			line := strings.TrimSpace(symbol.Snippet)
			if len(line) > 160 {
				line = line[:160]
			}
			matches = append(matches, searchMatch{
				path: symbol.RelativePath,
				kind: symbol.Kind,
				line: symbol.FQName + " :: " + line,
			})
			if len(matches) >= 10 {
				break
			}
		}
	}
	walkErr := filepath.WalkDir(r.workspace, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if d.IsDir() {
			name := strings.ToLower(d.Name())
			if name == ".git" || name == ".brokk" {
				return filepath.SkipDir
			}
			return nil
		}

		rel, err := filepath.Rel(r.workspace, path)
		if err != nil {
			return nil
		}
		rel = filepath.ToSlash(rel)
		if strings.Contains(strings.ToLower(rel), normalized) && !hasMatch(matches, "path", rel, "") {
			matches = append(matches, searchMatch{path: rel, kind: "path"})
		}

		if len(matches) >= 10 {
			return errSearchLimit
		}

		content, err := os.ReadFile(path)
		if err != nil {
			return nil
		}
		for _, line := range strings.Split(string(content), "\n") {
			if strings.Contains(strings.ToLower(line), normalized) {
				trimmed := strings.TrimSpace(line)
				if len(trimmed) > 160 {
					trimmed = trimmed[:160]
				}
				if !hasMatch(matches, "content", rel, trimmed) {
					matches = append(matches, searchMatch{path: rel, kind: "content", line: trimmed})
				}
				break
			}
			if len(matches) >= 10 {
				return errSearchLimit
			}
		}
		return nil
	})
	if walkErr != nil && !errors.Is(walkErr, errSearchLimit) && !errors.Is(walkErr, context.Canceled) {
		return "", walkErr
	}
	if errors.Is(ctx.Err(), context.Canceled) {
		return "", ctx.Err()
	}

	lines := []string{
		"SEARCH results (Go runtime preview)",
		"Query: " + query,
	}
	if len(matches) == 0 {
		lines = append(lines, "No path or content matches were found in the workspace.")
		return strings.Join(lines, "\n"), nil
	}
	for _, m := range matches {
		if m.kind == "path" {
			lines = append(lines, "- Path match: "+m.path)
		} else if m.kind == "class" {
			lines = append(lines, "- Class match: "+m.path)
			if m.line != "" {
				lines = append(lines, "  "+m.line)
			}
		} else if m.kind == "function" {
			lines = append(lines, "- Method match: "+m.path)
			if m.line != "" {
				lines = append(lines, "  "+m.line)
			}
		} else {
			lines = append(lines, "- Content match: "+m.path)
			if m.line != "" {
				lines = append(lines, "  "+m.line)
			}
		}
	}
	return strings.Join(lines, "\n"), nil
}

func hasMatch(matches []searchMatch, kind string, path string, line string) bool {
	for _, match := range matches {
		if match.kind == kind && match.path == path {
			if line == "" || match.line == line {
				return true
			}
		}
	}
	return false
}

func (r *Runner) appendNotification(jobID string, level string, message string) {
	_, _ = r.store.AppendEvent(jobID, jobs.NewEvent("NOTIFICATION", map[string]any{
		"level":   level,
		"message": message,
	}))
}

func (r *Runner) appendStateHint(jobID string, name string, value any, details string) {
	data := map[string]any{
		"name":  name,
		"value": value,
	}
	if strings.TrimSpace(details) != "" {
		data["details"] = details
	}
	_, _ = r.store.AppendEvent(jobID, jobs.NewEvent("STATE_HINT", data))
}

func (r *Runner) updateProgress(jobID string, progress int) error {
	status, err := r.store.LoadStatus(jobID)
	if err != nil {
		return err
	}
	if status == nil || isTerminalState(status.State) {
		return nil
	}
	status.ProgressPercent = progress
	return r.store.UpdateStatus(jobID, *status)
}

func (r *Runner) appendText(jobID string, text string) {
	chunks := strings.Fields(text)
	if len(chunks) == 0 {
		_, _ = r.store.AppendEvent(jobID, jobs.NewEvent("LLM_TOKEN", map[string]any{
			"token":        "",
			"messageType":  "AI",
			"isNewMessage": true,
			"isReasoning":  false,
			"isTerminal":   true,
		}))
		return
	}

	for i, chunk := range chunks {
		token := chunk
		if i < len(chunks)-1 {
			token += " "
		}
		_, _ = r.store.AppendEvent(jobID, jobs.NewEvent("LLM_TOKEN", map[string]any{
			"token":        token,
			"messageType":  "AI",
			"isNewMessage": i == 0,
			"isReasoning":  false,
			"isTerminal":   false,
		}))
	}
	_, _ = r.store.AppendEvent(jobID, jobs.NewEvent("LLM_TOKEN", map[string]any{
		"token":        "",
		"messageType":  "AI",
		"isNewMessage": false,
		"isReasoning":  false,
		"isTerminal":   true,
	}))
}

func parseMode(spec jobs.JobSpec) string {
	mode := strings.ToUpper(strings.TrimSpace(spec.Tags["mode"]))
	if mode == "" {
		return "ARCHITECT"
	}
	return mode
}

func (r *Runner) markCancelled(jobID string, emitNotification bool) error {
	status, err := r.store.LoadStatus(jobID)
	if err != nil {
		return err
	}
	if status == nil {
		return os.ErrNotExist
	}
	if isTerminalState(status.State) {
		return nil
	}

	status.State = "CANCELLED"
	status.EndTime = time.Now().UnixMilli()
	status.Result = nil
	status.Error = nil
	if err := r.store.UpdateStatus(jobID, *status); err != nil {
		return err
	}
	if emitNotification {
		r.appendNotification(jobID, "INFO", "Job cancelled: "+jobID)
	}
	return nil
}

func isTerminalState(state string) bool {
	return state == "COMPLETED" || state == "FAILED" || state == "CANCELLED"
}

func planTaskTitle(index int) string {
	switch index {
	case 0:
		return "Clarify request"
	case 1:
		return "Inspect current context"
	default:
		return "Execute next step"
	}
}

func renderArchitectContent(taskInput string, state contextstate.State, existing string) string {
	query := strings.TrimSpace(taskInput)
	if query == "" {
		query = "No task input provided"
	}

	lines := []string{
		"# Brokk ARCHITECT Preview",
		"",
		"## Request",
		query,
		"",
		"## Workspace Context",
		"- Fragments loaded: " + itoa(len(state.Fragments)),
		"- Task items loaded: " + itoa(len(state.TaskList.Tasks)),
	}
	if len(state.Fragments) > 0 {
		lines = append(lines, "- First fragment: "+state.Fragments[0].ShortDescription)
	}
	lines = append(lines,
		"",
		"## Proposed Change",
		"- This Go runtime preview created a concrete workspace artifact for the requested ARCHITECT job.",
		"- Later slices should replace this placeholder with real multi-step planning and code editing.",
	)

	section := strings.Join(lines, "\n")
	if strings.TrimSpace(existing) == "" {
		return section + "\n"
	}
	if strings.Contains(existing, section) {
		return existing
	}
	return strings.TrimRight(existing, "\n") + "\n\n---\n\n" + section + "\n"
}

func buildUnifiedDiff(relativePath string, oldContent string, newContent string) string {
	oldLines := splitLines(oldContent)
	newLines := splitLines(newContent)

	if oldContent == "" {
		lines := []string{
			"diff --git a/" + relativePath + " b/" + relativePath,
			"new file mode 100644",
			"--- /dev/null",
			"+++ b/" + relativePath,
			fmt.Sprintf("@@ -0,0 +1,%d @@", len(newLines)),
		}
		for _, line := range newLines {
			lines = append(lines, "+"+line)
		}
		return strings.Join(lines, "\n") + "\n"
	}

	lines := []string{
		"diff --git a/" + relativePath + " b/" + relativePath,
		"--- a/" + relativePath,
		"+++ b/" + relativePath,
		fmt.Sprintf("@@ -1,%d +1,%d @@", len(oldLines), len(newLines)),
	}
	for _, line := range oldLines {
		lines = append(lines, "-"+line)
	}
	for _, line := range newLines {
		lines = append(lines, "+"+line)
	}
	return strings.Join(lines, "\n") + "\n"
}

func splitLines(value string) []string {
	if value == "" {
		return []string{}
	}
	trimmed := strings.TrimSuffix(value, "\n")
	if trimmed == "" {
		return []string{""}
	}
	return strings.Split(trimmed, "\n")
}

func (r *Runner) syncWorkspaceFragment(sessionID string, relativePath string, absolutePath string, text string, mimeType string) error {
	_, err := r.contexts.Update(sessionID, func(state *contextstate.State) error {
		uri := "file://" + filepath.ToSlash(absolutePath)
		for i := range state.Fragments {
			if state.Fragments[i].Type == "FILE" && state.Fragments[i].ShortDescription == relativePath {
				state.Fragments[i].Text = text
				state.Fragments[i].URI = uri
				state.Fragments[i].MimeType = mimeType
				state.Fragments[i].Valid = true
				return nil
			}
		}

		id, err := sessions.NewImportID()
		if err != nil {
			return err
		}
		state.Fragments = append(state.Fragments, contextstate.Fragment{
			ID:               id,
			Type:             "FILE",
			ShortDescription: relativePath,
			Pinned:           false,
			Readonly:         false,
			Valid:            true,
			Editable:         true,
			Text:             text,
			URI:              uri,
			MimeType:         mimeType,
		})
		return nil
	})
	return err
}

func (r *Runner) selectCodeTarget(state contextstate.State) (string, string) {
	return r.selectTarget(state, "BROKK_CODE_OUTPUT.md")
}

func (r *Runner) selectTarget(state contextstate.State, fallback string) (string, string) {
	for _, fragment := range state.Fragments {
		if fragment.Type != "FILE" || !fragment.Editable {
			continue
		}
		relativePath := filepath.ToSlash(strings.TrimSpace(fragment.ShortDescription))
		if relativePath == "" || strings.HasPrefix(relativePath, "/") {
			continue
		}
		return relativePath, filepath.Join(r.workspace, filepath.FromSlash(relativePath))
	}

	relativePath := fallback
	return relativePath, filepath.Join(r.workspace, relativePath)
}

func renderCodeContent(spec jobs.JobSpec, targetRelativePath string, existing string) string {
	query := strings.TrimSpace(spec.TaskInput)
	if query == "" {
		query = "No task input provided"
	}

	codeModel := "default"
	if spec.CodeModel != nil && strings.TrimSpace(*spec.CodeModel) != "" {
		codeModel = strings.TrimSpace(*spec.CodeModel)
	}

	switch strings.ToLower(filepath.Ext(targetRelativePath)) {
	case ".go", ".java", ".js", ".ts", ".tsx", ".jsx", ".kt", ".c", ".cc", ".cpp", ".cs", ".rs", ".swift":
		return appendSection(existing, []string{
			"// Brokk CODE Preview",
			"// Request: " + query,
			"// Code model: " + codeModel,
			"// This preview mutation stands in for single-shot code generation.",
		})
	case ".py", ".sh", ".rb", ".yml", ".yaml", ".toml", ".ini":
		return appendSection(existing, []string{
			"# Brokk CODE Preview",
			"# Request: " + query,
			"# Code model: " + codeModel,
			"# This preview mutation stands in for single-shot code generation.",
		})
	case ".md":
		return appendSection(existing, []string{
			"## Brokk CODE Preview",
			"",
			"- Request: " + query,
			"- Code model: " + codeModel,
			"- This preview mutation stands in for single-shot code generation.",
		})
	default:
		return appendSection(existing, []string{
			"Brokk CODE Preview",
			"Request: " + query,
			"Code model: " + codeModel,
			"This preview mutation stands in for single-shot code generation.",
		})
	}
}

func renderLutzContent(spec jobs.JobSpec, targetRelativePath string, existing string, task contextstate.TaskItem, index int, total int) string {
	query := strings.TrimSpace(spec.TaskInput)
	if query == "" {
		query = "No task input provided"
	}

	codeModel := "default"
	if spec.CodeModel != nil && strings.TrimSpace(*spec.CodeModel) != "" {
		codeModel = strings.TrimSpace(*spec.CodeModel)
	}

	switch strings.ToLower(filepath.Ext(targetRelativePath)) {
	case ".go", ".java", ".js", ".ts", ".tsx", ".jsx", ".kt", ".c", ".cc", ".cpp", ".cs", ".rs", ".swift":
		return appendSection(existing, []string{
			fmt.Sprintf("// LUTZ task %d/%d", index, total),
			"// Goal: " + query,
			"// Task: " + task.Title,
			"// Detail: " + task.Text,
			"// Code model: " + codeModel,
		})
	case ".py", ".sh", ".rb", ".yml", ".yaml", ".toml", ".ini":
		return appendSection(existing, []string{
			fmt.Sprintf("# LUTZ task %d/%d", index, total),
			"# Goal: " + query,
			"# Task: " + task.Title,
			"# Detail: " + task.Text,
			"# Code model: " + codeModel,
		})
	case ".md":
		return appendSection(existing, []string{
			fmt.Sprintf("## LUTZ Task %d/%d", index, total),
			"",
			"- Goal: " + query,
			"- Task: " + task.Title,
			"- Detail: " + task.Text,
			"- Code model: " + codeModel,
		})
	default:
		return appendSection(existing, []string{
			fmt.Sprintf("LUTZ task %d/%d", index, total),
			"Goal: " + query,
			"Task: " + task.Title,
			"Detail: " + task.Text,
			"Code model: " + codeModel,
		})
	}
}

func appendSection(existing string, lines []string) string {
	section := strings.Join(lines, "\n")
	if strings.TrimSpace(existing) == "" {
		return section + "\n"
	}
	if strings.Contains(existing, section) {
		return existing
	}
	return strings.TrimRight(existing, "\n") + "\n\n" + section + "\n"
}

func buildLutzTasks(query string, state contextstate.State) []contextstate.TaskItem {
	taskTexts := []string{
		"Break down the objective and identify the first concrete implementation step for: " + query,
		"Apply the requested change using the current workspace context and attached files.",
		"Verify the resulting change and summarize any remaining follow-up work.",
	}
	if len(state.Fragments) > 0 {
		taskTexts[1] += " Start with context from: " + state.Fragments[0].ShortDescription
	}

	tasks := make([]contextstate.TaskItem, 0, len(taskTexts))
	for i, text := range taskTexts {
		tasks = append(tasks, contextstate.TaskItem{
			ID:    fmt.Sprintf("lutz-%d", i+1),
			Title: lutzTaskTitle(i),
			Text:  text,
			Done:  false,
		})
	}
	return tasks
}

func lutzTaskTitle(index int) string {
	switch index {
	case 0:
		return "Plan work"
	case 1:
		return "Execute change"
	default:
		return "Verify result"
	}
}

type reviewCandidate struct {
	relativePath string
	absolutePath string
}

func (r *Runner) buildReviewContext(state contextstate.State, candidates []reviewCandidate, annotatedDiff string) review.ContextReport {
	items := make([]review.ContextItem, 0, 12)
	seen := map[string]struct{}{}

	addItem := func(item review.ContextItem) {
		item.Path = filepath.ToSlash(strings.TrimSpace(item.Path))
		item.Name = strings.TrimSpace(item.Name)
		item.Snippet = strings.TrimSpace(item.Snippet)
		if item.Path == "" || item.Name == "" {
			return
		}
		key := item.Path + "|" + item.Kind + "|" + item.Name
		if _, ok := seen[key]; ok {
			return
		}
		seen[key] = struct{}{}
		items = append(items, item)
	}

	touchedPaths := touchedReviewPaths(annotatedDiff)
	for _, path := range touchedPaths {
		addItem(review.ContextItem{
			Path:    path,
			Kind:    "diff-file",
			Name:    path,
			Snippet: "File changed in this pull request diff.",
		})
	}

	for _, fragment := range state.Fragments {
		if len(items) >= 10 {
			break
		}
		kind := strings.ToLower(strings.TrimSpace(fragment.Type))
		if kind != "file" && kind != "class" && kind != "method" && kind != "text" {
			continue
		}
		addItem(review.ContextItem{
			Path:    fragment.ShortDescription,
			Kind:    kind,
			Name:    fragment.ShortDescription,
			Snippet: fragment.Text,
		})
	}

	if r.analyzer != nil {
		for _, identifier := range extractReviewIdentifiers(annotatedDiff) {
			if len(items) >= 10 {
				break
			}
			for _, symbol := range r.analyzer.SearchSymbols(identifier, 4) {
				if len(items) >= 10 {
					break
				}
				if len(touchedPaths) > 0 && !containsString(touchedPaths, symbol.RelativePath) {
					continue
				}
				addItem(review.ContextItem{
					Path:    symbol.RelativePath,
					Kind:    symbol.Kind,
					Name:    symbol.FQName,
					Snippet: symbol.Snippet,
				})
			}
		}
	}

	for _, candidate := range candidates {
		if len(items) >= 10 {
			break
		}
		addItem(review.ContextItem{
			Path:    candidate.relativePath,
			Kind:    "candidate",
			Name:    candidate.relativePath,
			Snippet: "Candidate file selected for review coverage.",
		})
	}

	lines := []string{
		"## Brokk Review Context",
		"",
		fmt.Sprintf("Collected %d context item(s) to support the diff review.", len(items)),
	}
	if len(items) == 0 {
		lines = append(lines, "No additional repository context was resolved for this review pass.")
	} else {
		for i, item := range items {
			if i >= 10 {
				break
			}
			line := fmt.Sprintf("- [%s] %s", item.Kind, item.Name)
			if item.Path != "" && item.Path != item.Name {
				line += " (" + item.Path + ")"
			}
			lines = append(lines, line)
			if item.Snippet != "" {
				lines = append(lines, "  "+singleLinePreview(item.Snippet, 180))
			}
		}
	}

	return review.ContextReport{
		SummaryMarkdown: strings.Join(lines, "\n"),
		Items:           items,
	}
}

func (r *Runner) collectReviewCandidates(ctx context.Context, state contextstate.State) ([]reviewCandidate, error) {
	candidates := make([]reviewCandidate, 0, 8)
	seen := map[string]struct{}{}

	for _, fragment := range state.Fragments {
		if fragment.Type != "FILE" {
			continue
		}
		relativePath := filepath.ToSlash(strings.TrimSpace(fragment.ShortDescription))
		if relativePath == "" || strings.HasPrefix(relativePath, "/") {
			continue
		}
		absolutePath := filepath.Join(r.workspace, filepath.FromSlash(relativePath))
		info, err := os.Stat(absolutePath)
		if err != nil || info.IsDir() {
			continue
		}
		seen[relativePath] = struct{}{}
		candidates = append(candidates, reviewCandidate{
			relativePath: relativePath,
			absolutePath: absolutePath,
		})
	}
	if len(candidates) > 0 {
		return candidates, nil
	}

	walkErr := filepath.WalkDir(r.workspace, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if d.IsDir() {
			name := strings.ToLower(d.Name())
			if name == ".git" || name == ".brokk" {
				return filepath.SkipDir
			}
			return nil
		}
		rel, err := filepath.Rel(r.workspace, path)
		if err != nil {
			return nil
		}
		rel = filepath.ToSlash(rel)
		if !isReviewablePath(rel) {
			return nil
		}
		if _, ok := seen[rel]; ok {
			return nil
		}
		candidates = append(candidates, reviewCandidate{
			relativePath: rel,
			absolutePath: path,
		})
		seen[rel] = struct{}{}
		if len(candidates) >= 12 {
			return errSearchLimit
		}
		return nil
	})
	if walkErr != nil && !errors.Is(walkErr, errSearchLimit) && !errors.Is(walkErr, context.Canceled) {
		return nil, walkErr
	}
	if errors.Is(ctx.Err(), context.Canceled) {
		return nil, ctx.Err()
	}
	return candidates, nil
}

func touchedReviewPaths(annotatedDiff string) []string {
	paths := make([]string, 0, 8)
	seen := map[string]struct{}{}
	for _, line := range strings.Split(annotatedDiff, "\n") {
		if !strings.HasPrefix(line, "+++ b/") {
			continue
		}
		path := strings.TrimSpace(strings.TrimPrefix(line, "+++ b/"))
		if path == "" {
			continue
		}
		if _, ok := seen[path]; ok {
			continue
		}
		seen[path] = struct{}{}
		paths = append(paths, path)
	}
	return paths
}

func extractReviewIdentifiers(annotatedDiff string) []string {
	seen := map[string]struct{}{}
	identifiers := make([]string, 0, 8)
	for _, line := range strings.Split(annotatedDiff, "\n") {
		if !strings.Contains(line, "[OLD:") {
			continue
		}
		for _, match := range identifierPattern.FindAllString(line, -1) {
			trimmed := strings.TrimSpace(match)
			if len(trimmed) < 4 {
				continue
			}
			switch trimmed {
			case "TODO", "fmt", "true", "false", "null", "this", "func", "package", "return":
				continue
			}
			if _, ok := seen[trimmed]; ok {
				continue
			}
			seen[trimmed] = struct{}{}
			identifiers = append(identifiers, trimmed)
			if len(identifiers) >= 8 {
				return identifiers
			}
		}
	}
	return identifiers
}

func containsString(values []string, target string) bool {
	for _, value := range values {
		if value == target {
			return true
		}
	}
	return false
}

func singleLinePreview(text string, limit int) string {
	trimmed := strings.Join(strings.Fields(strings.TrimSpace(text)), " ")
	if len(trimmed) <= limit {
		return trimmed
	}
	return trimmed[:limit]
}

func isReviewablePath(path string) bool {
	switch strings.ToLower(filepath.Ext(path)) {
	case ".go", ".java", ".kt", ".py", ".js", ".ts", ".tsx", ".jsx", ".md", ".yml", ".yaml", ".json":
		return true
	default:
		return false
	}
}

func (r *Runner) buildReviewDiff(ctx context.Context, jobID string, spec jobs.JobSpec, candidates []reviewCandidate) (string, string, error) {
	diffText, source, err := r.tryRemoteGitReviewDiff(ctx, jobID, spec)
	if err == nil && strings.TrimSpace(diffText) != "" {
		return diffText, source, nil
	}

	diffText, source, err = r.tryGitReviewDiff(ctx, spec)
	if err == nil && strings.TrimSpace(diffText) != "" {
		return diffText, source, nil
	}

	synthetic, synthErr := r.buildSyntheticReviewDiff(candidates)
	if synthErr != nil {
		if err != nil {
			return "", "", err
		}
		return "", "", synthErr
	}
	return synthetic, "synthetic workspace diff", nil
}

func (r *Runner) tryRemoteGitReviewDiff(ctx context.Context, jobID string, spec jobs.JobSpec) (string, string, error) {
	if _, err := os.Stat(filepath.Join(r.workspace, ".git")); err != nil {
		return "", "", err
	}
	if !hasGitHubReviewIdentity(spec) {
		return "", "", errors.New("github review not configured")
	}

	owner := strings.TrimSpace(spec.Tags["repo_owner"])
	repo := strings.TrimSpace(spec.Tags["repo_name"])
	prNumber, err := strconv.Atoi(strings.TrimSpace(spec.Tags["pr_number"]))
	if err != nil {
		return "", "", errors.New("invalid pr_number in tags")
	}

	client := githubclient.New(defaultGitHubAPIURL(spec), strings.TrimSpace(spec.Tags["github_token"]))
	details, err := client.FetchPRDetails(ctx, owner, repo, prNumber)
	if err != nil {
		return "", "", err
	}

	remoteName, err := r.getOriginRemoteNameWithFallback(ctx)
	if err != nil {
		return "", "", err
	}
	if remoteName == "" {
		return "", "", errors.New("PR review requires a configured git remote (no remote found; expected 'origin' or a fallback remote)")
	}

	r.appendNotification(jobID, "INFO", "Fetching PR refs from remote '"+remoteName+"'...")
	if _, err := r.gitOutput(ctx, "fetch", remoteName, fmt.Sprintf("pull/%d/head:refs/remotes/%s/pr/%d", prNumber, remoteName, prNumber)); err != nil {
		return "", "", fmt.Errorf("failed to fetch PR ref for PR #%d from remote '%s': %w", prNumber, remoteName, err)
	}
	if _, err := r.gitOutput(ctx, "fetch", remoteName, fmt.Sprintf("%s:refs/remotes/%s/%s", details.BaseBranch, remoteName, details.BaseBranch)); err != nil {
		return "", "", fmt.Errorf("failed to fetch base branch '%s' for PR #%d from remote '%s': %w", details.BaseBranch, prNumber, remoteName, err)
	}

	baseRef := remoteName + "/" + details.BaseBranch
	prRef := fmt.Sprintf("%s/pr/%d", remoteName, prNumber)
	mergeBase, err := r.gitOutput(ctx, "merge-base", baseRef, prRef)
	if err != nil {
		return "", "", err
	}
	mergeBase = strings.TrimSpace(mergeBase)
	if mergeBase == "" {
		return "", "", errors.New("remote merge-base unavailable")
	}

	diffText, err := r.gitOutput(ctx, "diff", "--no-ext-diff", mergeBase, prRef, "--")
	if err != nil {
		return "", "", err
	}
	if strings.TrimSpace(diffText) == "" {
		return "", "", errors.New("remote diff empty")
	}
	return diffText, fmt.Sprintf("remote git diff %s..%s", mergeBase, prRef), nil
}

func (r *Runner) tryGitReviewDiff(ctx context.Context, spec jobs.JobSpec) (string, string, error) {
	if _, err := os.Stat(filepath.Join(r.workspace, ".git")); err != nil {
		return "", "", err
	}

	headRef := "HEAD"
	if spec.SourceBranch != nil && strings.TrimSpace(*spec.SourceBranch) != "" {
		headRef = strings.TrimSpace(*spec.SourceBranch)
	}

	baseCandidates := make([]string, 0, 3)
	if spec.TargetBranch != nil && strings.TrimSpace(*spec.TargetBranch) != "" {
		baseCandidates = append(baseCandidates, strings.TrimSpace(*spec.TargetBranch))
	}
	baseCandidates = append(baseCandidates, "HEAD~1", "HEAD^")

	for _, baseRef := range baseCandidates {
		mergeBase, err := r.gitOutput(ctx, "merge-base", baseRef, headRef)
		if err != nil {
			continue
		}
		mergeBase = strings.TrimSpace(mergeBase)
		if mergeBase == "" {
			continue
		}

		diffText, err := r.gitOutput(ctx, "diff", "--no-ext-diff", mergeBase, headRef, "--")
		if err != nil {
			continue
		}
		if strings.TrimSpace(diffText) == "" {
			continue
		}
		return diffText, fmt.Sprintf("local git diff %s..%s", mergeBase, headRef), nil
	}

	return "", "", errors.New("git diff unavailable")
}

func (r *Runner) getOriginRemoteNameWithFallback(ctx context.Context) (string, error) {
	output, err := r.gitOutput(ctx, "remote")
	if err != nil {
		return "", err
	}

	remotes := make([]string, 0)
	for _, line := range strings.Split(output, "\n") {
		remote := strings.TrimSpace(line)
		if remote != "" {
			remotes = append(remotes, remote)
		}
	}
	if len(remotes) == 0 {
		return "", nil
	}
	for _, remote := range remotes {
		if remote == "origin" {
			return remote, nil
		}
	}
	return remotes[0], nil
}

func (r *Runner) gitOutput(ctx context.Context, args ...string) (string, error) {
	cmd := exec.CommandContext(ctx, "git", args...)
	cmd.Dir = r.workspace
	output, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("git %s failed: %w", strings.Join(args, " "), err)
	}
	return string(output), nil
}

func defaultGitHubAPIURL(spec jobs.JobSpec) string {
	if apiURL := strings.TrimSpace(spec.Tags["github_api_url"]); apiURL != "" {
		return apiURL
	}
	return "https://api.github.com"
}

func hasGitHubReviewIdentity(spec jobs.JobSpec) bool {
	return strings.TrimSpace(spec.Tags["github_token"]) != "" &&
		strings.TrimSpace(spec.Tags["repo_owner"]) != "" &&
		strings.TrimSpace(spec.Tags["repo_name"]) != "" &&
		strings.TrimSpace(spec.Tags["pr_number"]) != ""
}

func (r *Runner) canAttemptGitHubReview(spec jobs.JobSpec) bool {
	if !hasGitHubReviewIdentity(spec) {
		return false
	}
	if _, err := os.Stat(filepath.Join(r.workspace, ".git")); err != nil {
		return false
	}
	return true
}

func (r *Runner) reviewPromptMetadata(ctx context.Context, spec jobs.JobSpec) (string, string) {
	if !r.canAttemptGitHubReview(spec) {
		return "", ""
	}

	owner := strings.TrimSpace(spec.Tags["repo_owner"])
	repo := strings.TrimSpace(spec.Tags["repo_name"])
	prNumber, err := strconv.Atoi(strings.TrimSpace(spec.Tags["pr_number"]))
	if err != nil {
		return "", ""
	}

	client := githubclient.New(defaultGitHubAPIURL(spec), strings.TrimSpace(spec.Tags["github_token"]))
	details, err := client.FetchPRDetails(ctx, owner, repo, prNumber)
	if err != nil {
		return "", ""
	}
	return details.Title, details.Body
}

func (r *Runner) buildSyntheticReviewDiff(candidates []reviewCandidate) (string, error) {
	if len(candidates) == 0 {
		return buildUnifiedDiff("BROKK_REVIEW_EMPTY.txt", "", "No reviewable files were found.\n"), nil
	}

	parts := make([]string, 0, len(candidates))
	for i, candidate := range candidates {
		if i >= 6 {
			break
		}
		content, err := os.ReadFile(candidate.absolutePath)
		if err != nil {
			return "", err
		}
		parts = append(parts, strings.TrimSuffix(buildUnifiedDiff(candidate.relativePath, "", string(content)), "\n"))
	}
	return strings.Join(parts, "\n") + "\n", nil
}

func (r *Runner) postReviewToGitHub(ctx context.Context, spec jobs.JobSpec, apiURL string, summary string, comments []review.InlineComment) (string, error) {
	owner := strings.TrimSpace(spec.Tags["repo_owner"])
	repo := strings.TrimSpace(spec.Tags["repo_name"])
	prNumber, err := strconv.Atoi(strings.TrimSpace(spec.Tags["pr_number"]))
	if err != nil {
		return "", errors.New("invalid pr_number in tags")
	}

	client := githubclient.New(apiURL, strings.TrimSpace(spec.Tags["github_token"]))
	if !client.Enabled() {
		return "GitHub posting skipped (client not enabled)", nil
	}

	details, err := client.FetchPRDetails(ctx, owner, repo, prNumber)
	if err != nil {
		return "", err
	}
	if err := client.PostIssueComment(ctx, owner, repo, prNumber, summary); err != nil {
		return "", err
	}

	existingComments, err := client.ListReviewComments(ctx, owner, repo, prNumber)
	if err != nil {
		return "", err
	}
	existing := map[string]struct{}{}
	for _, comment := range existingComments {
		existing[fmt.Sprintf("%s:%d", comment.Path, comment.Line)] = struct{}{}
	}

	posted := 0
	skipped := 0
	for _, comment := range comments {
		key := fmt.Sprintf("%s:%d", comment.Path, comment.Line)
		if _, ok := existing[key]; ok {
			skipped++
			continue
		}
		if err := client.PostLineComment(ctx, owner, repo, prNumber, comment.Path, comment.Line, comment.BodyMarkdown, details.HeadSHA); err != nil {
			return "", err
		}
		posted++
		existing[key] = struct{}{}
	}

	return fmt.Sprintf("GitHub posting complete: summary posted, %d inline comment(s) posted, %d duplicate(s) skipped", posted, skipped), nil
}

func (r *Runner) markTaskDone(sessionID string, taskID string) error {
	_, err := r.contexts.Update(sessionID, func(state *contextstate.State) error {
		for i := range state.TaskList.Tasks {
			if state.TaskList.Tasks[i].ID == taskID {
				state.TaskList.Tasks[i].Done = true
				break
			}
		}
		return nil
	})
	return err
}

func detectMimeType(path string) string {
	switch strings.ToLower(filepath.Ext(path)) {
	case ".md":
		return "text/markdown"
	case ".go":
		return "text/x-go"
	case ".java":
		return "text/x-java-source"
	case ".py":
		return "text/x-python"
	default:
		return "text/plain"
	}
}

var errSearchLimit = errors.New("search limit reached")

func itoa(value int) string {
	return strconv.Itoa(value)
}
