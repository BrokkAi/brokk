package runtime

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

const sessionFormatVersion = "4.0"

type SessionManifest struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Created  int64  `json:"created"`
	Modified int64  `json:"modified"`
	Version  string `json:"version"`
}

type Fragment struct {
	ID          string `json:"id"`
	Kind        string `json:"kind"`
	Description string `json:"description"`
	Text        string `json:"text,omitempty"`
	Path        string `json:"path,omitempty"`
	Pinned      bool   `json:"pinned"`
	Readonly    bool   `json:"readonly"`
}

type TaskListTask struct {
	ID    string `json:"id,omitempty"`
	Title string `json:"title"`
	Text  string `json:"text,omitempty"`
	Done  bool   `json:"done"`
}

type TaskList struct {
	BigPicture string         `json:"bigPicture,omitempty"`
	Tasks      []TaskListTask `json:"tasks"`
}

type ConversationMessage struct {
	Role      string `json:"role"`
	Text      string `json:"text"`
	Reasoning string `json:"reasoning,omitempty"`
}

type ConversationEntry struct {
	Sequence     int                   `json:"sequence"`
	IsCompressed bool                  `json:"isCompressed"`
	TaskType     string                `json:"taskType,omitempty"`
	Messages     []ConversationMessage `json:"messages,omitempty"`
	Summary      string                `json:"summary,omitempty"`
}

type ContextSnapshot struct {
	ContextID    string
	Action       string
	TaskType     string
	Timestamp    int64
	Fragments    []Fragment
	TaskList     TaskList
	Conversation []ConversationEntry
	IsAIResult   bool
}

type Session struct {
	Manifest     SessionManifest
	Fragments    []Fragment
	TaskList     TaskList
	Conversation []ConversationEntry
	RawZip       []byte
	Dirty        bool
	History      []ContextSnapshot
	HistoryIndex int
}

type SessionStore struct {
	mu          sync.RWMutex
	root        string
	sessionsDir string
	sessions    map[string]*Session
	currentID   string
}

func NewSessionStore(workspaceDir string) (*SessionStore, error) {
	sessionsDir := filepath.Join(workspaceDir, ".brokk", "sessions")
	if err := os.MkdirAll(sessionsDir, 0o755); err != nil {
		return nil, err
	}
	store := &SessionStore{
		root:        workspaceDir,
		sessionsDir: sessionsDir,
		sessions:    make(map[string]*Session),
	}
	if err := store.loadExisting(); err != nil {
		return nil, err
	}
	return store, nil
}

func (s *SessionStore) loadExisting() error {
	entries, err := os.ReadDir(s.sessionsDir)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(strings.ToLower(entry.Name()), ".zip") {
			continue
		}
		raw, readErr := os.ReadFile(filepath.Join(s.sessionsDir, entry.Name()))
		if readErr != nil {
			continue
		}
		session, decodeErr := decodeSessionZip(raw)
		if decodeErr != nil {
			continue
		}
		session.RawZip = raw
		session.Dirty = false
		s.sessions[session.Manifest.ID] = session
	}
	return nil
}

func (s *SessionStore) HasLoadedSession() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.currentID != ""
}

func (s *SessionStore) CurrentSessionID() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.currentID
}

func (s *SessionStore) GetCurrent() (*Session, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.currentID == "" {
		return nil, false
	}
	session, ok := s.sessions[s.currentID]
	return session, ok
}

func (s *SessionStore) Get(id string) (*Session, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	session, ok := s.sessions[id]
	return session, ok
}

func (s *SessionStore) List() []SessionManifest {
	s.mu.RLock()
	defer s.mu.RUnlock()
	items := make([]SessionManifest, 0, len(s.sessions))
	for _, session := range s.sessions {
		items = append(items, session.Manifest)
	}
	sort.Slice(items, func(i, j int) bool {
		if items[i].Modified == items[j].Modified {
			return items[i].Created > items[j].Created
		}
		return items[i].Modified > items[j].Modified
	})
	return items
}

func (s *SessionStore) Create(name string) (*Session, error) {
	now := time.Now().UnixMilli()
	session := &Session{
		Manifest: SessionManifest{
			ID:       NewIdentifier("session"),
			Name:     strings.TrimSpace(name),
			Created:  now,
			Modified: now,
			Version:  sessionFormatVersion,
		},
		TaskList: TaskList{Tasks: []TaskListTask{}},
		Dirty:    true,
	}
	if session.Manifest.Name == "" {
		session.Manifest.Name = "Session"
	}
	initializeHistory(session, "Session created", "")
	rawZip, err := encodeSessionZip(session)
	if err != nil {
		return nil, err
	}
	session.RawZip = rawZip

	s.mu.Lock()
	defer s.mu.Unlock()
	s.sessions[session.Manifest.ID] = session
	s.currentID = session.Manifest.ID
	return session, s.persistLocked(session)
}

func (s *SessionStore) Import(preferredID string, raw []byte) (*Session, error) {
	session, err := decodeSessionZip(raw)
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(preferredID) != "" {
		session.Manifest.ID = strings.TrimSpace(preferredID)
	}
	session.RawZip = raw
	session.Dirty = false
	initializeHistory(session, "Session imported", "")
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sessions[session.Manifest.ID] = session
	s.currentID = session.Manifest.ID
	return session, s.persistLocked(session)
}

func (s *SessionStore) Switch(id string) (*Session, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	session, ok := s.sessions[id]
	if !ok {
		return nil, os.ErrNotExist
	}
	s.currentID = id
	return session, nil
}

func (s *SessionStore) Download(id string) ([]byte, error) {
	s.mu.RLock()
	session, ok := s.sessions[id]
	s.mu.RUnlock()
	if !ok {
		return nil, os.ErrNotExist
	}
	if !session.Dirty && len(session.RawZip) > 0 {
		return session.RawZip, nil
	}
	return encodeSessionZip(session)
}

func (s *SessionStore) UpdateCurrent(mutator func(*Session) error) (*Session, error) {
	return s.UpdateCurrentWithAction(mutator, "Context updated", "")
}

func (s *SessionStore) UpdateCurrentWithAction(mutator func(*Session) error, action string, taskType string) (*Session, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.currentID == "" {
		return nil, os.ErrNotExist
	}
	session := s.sessions[s.currentID]
	if err := mutator(session); err != nil {
		return nil, err
	}
	session.Manifest.Modified = time.Now().UnixMilli()
	recordSnapshot(session, action, taskType)
	session.Dirty = true
	raw, err := encodeSessionZip(session)
	if err != nil {
		return nil, err
	}
	session.RawZip = raw
	return session, s.persistLocked(session)
}

func (s *SessionStore) Activity() (map[string]any, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.currentID == "" {
		return nil, os.ErrNotExist
	}
	session := s.sessions[s.currentID]
	groups := make([]map[string]any, 0, len(session.History))
	for idx, snapshot := range session.History {
		entry := map[string]any{
			"contextId":  snapshot.ContextID,
			"action":     snapshot.Action,
			"isAiResult": snapshot.IsAIResult,
		}
		if snapshot.TaskType != "" {
			entry["taskType"] = snapshot.TaskType
		}
		groups = append(groups, map[string]any{
			"key":         snapshot.ContextID,
			"showHeader":  idx == 0,
			"isLastGroup": idx == len(session.History)-1,
			"label":       time.UnixMilli(snapshot.Timestamp).UTC().Format(time.RFC3339),
			"entries":     []map[string]any{entry},
		})
	}
	return map[string]any{
		"groups":  groups,
		"hasUndo": session.HistoryIndex > 0,
		"hasRedo": session.HistoryIndex >= 0 && session.HistoryIndex < len(session.History)-1,
	}, nil
}

func (s *SessionStore) Diff(contextID string) (map[string]any, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.currentID == "" {
		return nil, os.ErrNotExist
	}
	session := s.sessions[s.currentID]
	snapshot, ok := findSnapshot(session.History, contextID)
	if !ok {
		return nil, os.ErrNotExist
	}
	current := snapshotForSession(session, "", "")
	return map[string]any{
		"contextId": contextID,
		"currentId": current.ContextID,
		"fragments": diffSnapshots(snapshot, current),
	}, nil
}

func (s *SessionStore) UndoToContext(contextID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.currentID == "" {
		return os.ErrNotExist
	}
	session := s.sessions[s.currentID]
	for idx, snapshot := range session.History {
		if snapshot.ContextID == contextID {
			applySnapshot(session, snapshot)
			session.HistoryIndex = idx
			session.Manifest.Modified = time.Now().UnixMilli()
			session.Dirty = true
			raw, err := encodeSessionZip(session)
			if err != nil {
				return err
			}
			session.RawZip = raw
			return s.persistLocked(session)
		}
	}
	return os.ErrNotExist
}

func (s *SessionStore) UndoStep() error {
	s.mu.RLock()
	if s.currentID == "" {
		s.mu.RUnlock()
		return os.ErrNotExist
	}
	session := s.sessions[s.currentID]
	target := session.HistoryIndex - 1
	s.mu.RUnlock()
	if target < 0 {
		return nil
	}
	return s.UndoToContext(session.History[target].ContextID)
}

func (s *SessionStore) RedoStep() error {
	s.mu.RLock()
	if s.currentID == "" {
		s.mu.RUnlock()
		return os.ErrNotExist
	}
	session := s.sessions[s.currentID]
	target := session.HistoryIndex + 1
	s.mu.RUnlock()
	if target >= len(session.History) {
		return nil
	}
	return s.UndoToContext(session.History[target].ContextID)
}

func (s *SessionStore) CopyContext(contextID string) error {
	return s.updateCurrentWithContext(contextID, false)
}

func (s *SessionStore) CopyContextHistory(contextID string) error {
	return s.updateCurrentWithContext(contextID, true)
}

func (s *SessionStore) updateCurrentWithContext(contextID string, includeHistory bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.currentID == "" {
		return os.ErrNotExist
	}
	session := s.sessions[s.currentID]
	var snapshot ContextSnapshot
	var idx int
	var ok bool
	for idx = range session.History {
		if session.History[idx].ContextID == contextID {
			snapshot = session.History[idx]
			ok = true
			break
		}
	}
	if !ok {
		return os.ErrNotExist
	}
	applySnapshot(session, snapshot)
	if includeHistory {
		session.History = cloneSnapshots(session.History[:idx+1])
		session.HistoryIndex = len(session.History) - 1
	} else {
		recordSnapshot(session, "Copied context", snapshot.TaskType)
	}
	session.Manifest.Modified = time.Now().UnixMilli()
	session.Dirty = true
	raw, err := encodeSessionZip(session)
	if err != nil {
		return err
	}
	session.RawZip = raw
	return s.persistLocked(session)
}

func (s *SessionStore) NewSessionFromContext(contextID, name string) (*Session, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.currentID == "" {
		return nil, os.ErrNotExist
	}
	current := s.sessions[s.currentID]
	snapshot, ok := findSnapshot(current.History, contextID)
	if !ok {
		return nil, os.ErrNotExist
	}
	now := time.Now().UnixMilli()
	session := &Session{
		Manifest: SessionManifest{
			ID:       NewIdentifier("session"),
			Name:     firstNonBlank(strings.TrimSpace(name), "Session"),
			Created:  now,
			Modified: now,
			Version:  sessionFormatVersion,
		},
		Fragments:    cloneFragments(snapshot.Fragments),
		TaskList:     cloneTaskList(snapshot.TaskList),
		Conversation: cloneConversation(snapshot.Conversation),
		Dirty:        true,
	}
	initializeHistory(session, "Session created from snapshot", snapshot.TaskType)
	raw, err := encodeSessionZip(session)
	if err != nil {
		return nil, err
	}
	session.RawZip = raw
	s.sessions[session.Manifest.ID] = session
	s.currentID = session.Manifest.ID
	return session, s.persistLocked(session)
}

func (s *SessionStore) persistLocked(session *Session) error {
	return os.WriteFile(filepath.Join(s.sessionsDir, session.Manifest.ID+".zip"), session.RawZip, 0o644)
}

func initializeHistory(session *Session, action string, taskType string) {
	session.History = []ContextSnapshot{snapshotForSession(session, action, taskType)}
	session.HistoryIndex = 0
}

func recordSnapshot(session *Session, action string, taskType string) {
	snapshot := snapshotForSession(session, action, taskType)
	if session.HistoryIndex < len(session.History)-1 {
		session.History = cloneSnapshots(session.History[:session.HistoryIndex+1])
	}
	session.History = append(session.History, snapshot)
	session.HistoryIndex = len(session.History) - 1
}

func snapshotForSession(session *Session, action string, taskType string) ContextSnapshot {
	return ContextSnapshot{
		ContextID:    NewIdentifier("ctx"),
		Action:       firstNonBlank(strings.TrimSpace(action), "Context updated"),
		TaskType:     strings.TrimSpace(taskType),
		Timestamp:    time.Now().UnixMilli(),
		Fragments:    cloneFragments(session.Fragments),
		TaskList:     cloneTaskList(session.TaskList),
		Conversation: cloneConversation(session.Conversation),
		IsAIResult:   len(session.Conversation) > 0,
	}
}

func applySnapshot(session *Session, snapshot ContextSnapshot) {
	session.Fragments = cloneFragments(snapshot.Fragments)
	session.TaskList = cloneTaskList(snapshot.TaskList)
	session.Conversation = cloneConversation(snapshot.Conversation)
}

func findSnapshot(items []ContextSnapshot, contextID string) (ContextSnapshot, bool) {
	for _, item := range items {
		if item.ContextID == contextID {
			return item, true
		}
	}
	return ContextSnapshot{}, false
}

func cloneSnapshots(items []ContextSnapshot) []ContextSnapshot {
	out := make([]ContextSnapshot, 0, len(items))
	for _, item := range items {
		out = append(out, ContextSnapshot{
			ContextID:    item.ContextID,
			Action:       item.Action,
			TaskType:     item.TaskType,
			Timestamp:    item.Timestamp,
			Fragments:    cloneFragments(item.Fragments),
			TaskList:     cloneTaskList(item.TaskList),
			Conversation: cloneConversation(item.Conversation),
			IsAIResult:   item.IsAIResult,
		})
	}
	return out
}

func cloneFragments(items []Fragment) []Fragment {
	if len(items) == 0 {
		return nil
	}
	out := make([]Fragment, len(items))
	copy(out, items)
	return out
}

func cloneTaskList(taskList TaskList) TaskList {
	out := TaskList{
		BigPicture: taskList.BigPicture,
		Tasks:      make([]TaskListTask, len(taskList.Tasks)),
	}
	copy(out.Tasks, taskList.Tasks)
	return out
}

func cloneConversation(entries []ConversationEntry) []ConversationEntry {
	if len(entries) == 0 {
		return nil
	}
	out := make([]ConversationEntry, 0, len(entries))
	for _, entry := range entries {
		cloned := ConversationEntry{
			Sequence:     entry.Sequence,
			IsCompressed: entry.IsCompressed,
			TaskType:     entry.TaskType,
			Summary:      entry.Summary,
			Messages:     make([]ConversationMessage, len(entry.Messages)),
		}
		copy(cloned.Messages, entry.Messages)
		out = append(out, cloned)
	}
	return out
}

func diffSnapshots(oldSnapshot, currentSnapshot ContextSnapshot) []map[string]any {
	oldByID := make(map[string]Fragment, len(oldSnapshot.Fragments))
	for _, fragment := range oldSnapshot.Fragments {
		oldByID[fragment.ID] = fragment
	}
	currentByID := make(map[string]Fragment, len(currentSnapshot.Fragments))
	for _, fragment := range currentSnapshot.Fragments {
		currentByID[fragment.ID] = fragment
	}
	keys := make([]string, 0, len(oldByID)+len(currentByID))
	seen := map[string]struct{}{}
	for key := range oldByID {
		seen[key] = struct{}{}
		keys = append(keys, key)
	}
	for key := range currentByID {
		if _, ok := seen[key]; !ok {
			keys = append(keys, key)
		}
	}
	sort.Strings(keys)
	changes := make([]map[string]any, 0, len(keys))
	for _, key := range keys {
		oldFragment, hadOld := oldByID[key]
		newFragment, hadNew := currentByID[key]
		status := "UNCHANGED"
		switch {
		case hadOld && !hadNew:
			status = "REMOVED"
		case !hadOld && hadNew:
			status = "ADDED"
		case oldFragment.Text != newFragment.Text || oldFragment.Description != newFragment.Description:
			status = "MODIFIED"
		}
		if status == "UNCHANGED" {
			continue
		}
		change := map[string]any{
			"id":          key,
			"status":      status,
			"description": firstNonBlank(newFragment.Description, oldFragment.Description),
		}
		if hadOld {
			change["oldText"] = oldFragment.Text
		}
		if hadNew {
			change["newText"] = newFragment.Text
		}
		changes = append(changes, change)
	}
	return changes
}

func encodeSessionZip(session *Session) ([]byte, error) {
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)

	writeJSON := func(name string, value any) error {
		writer, err := zw.Create(name)
		if err != nil {
			return err
		}
		data, err := json.Marshal(value)
		if err != nil {
			return err
		}
		_, err = writer.Write(data)
		return err
	}

	contextLine, err := json.Marshal(map[string]any{
		"id":       session.Manifest.ID,
		"editable": fragmentIDs(session.Fragments, func(f Fragment) bool { return !f.Readonly }),
		"readonly": fragmentIDs(session.Fragments, func(f Fragment) bool { return f.Readonly }),
		"pinned":   fragmentIDs(session.Fragments, func(f Fragment) bool { return f.Pinned }),
		"virtuals": []string{},
		"tasks":    []map[string]any{},
	})
	if err != nil {
		return nil, err
	}

	if err := writeJSON("manifest.json", session.Manifest); err != nil {
		return nil, err
	}
	contextsWriter, err := zw.Create("contexts.jsonl")
	if err != nil {
		return nil, err
	}
	if _, err := contextsWriter.Write(append(contextLine, '\n')); err != nil {
		return nil, err
	}
	if err := writeJSON("fragments-v4.json", map[string]any{
		"referenced": []any{},
		"virtual":    session.Fragments,
		"tasks":      []any{},
	}); err != nil {
		return nil, err
	}
	if err := writeJSON("content_metadata.json", map[string]any{}); err != nil {
		return nil, err
	}
	if err := writeJSON("git_states.json", []any{}); err != nil {
		return nil, err
	}
	if err := writeJSON("entry_infos.json", map[string]any{}); err != nil {
		return nil, err
	}
	if err := writeJSON("group_info.json", map[string]any{}); err != nil {
		return nil, err
	}
	if err := writeJSON("reset_edges.json", []any{}); err != nil {
		return nil, err
	}
	if err := writeJSON("tasklist.json", session.TaskList); err != nil {
		return nil, err
	}
	if err := zw.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func decodeSessionZip(raw []byte) (*Session, error) {
	reader, err := zip.NewReader(bytes.NewReader(raw), int64(len(raw)))
	if err != nil {
		return nil, err
	}
	session := &Session{
		TaskList: TaskList{Tasks: []TaskListTask{}},
	}
	for _, file := range reader.File {
		rc, openErr := file.Open()
		if openErr != nil {
			return nil, openErr
		}
		data, readErr := io.ReadAll(rc)
		_ = rc.Close()
		if readErr != nil {
			return nil, readErr
		}
		switch file.Name {
		case "manifest.json":
			if err := json.Unmarshal(data, &session.Manifest); err != nil {
				return nil, err
			}
		case "tasklist.json":
			_ = json.Unmarshal(data, &session.TaskList)
		case "fragments-v4.json":
			var payload struct {
				Virtual []Fragment `json:"virtual"`
			}
			if err := json.Unmarshal(data, &payload); err == nil {
				session.Fragments = payload.Virtual
			}
		}
	}
	if session.Manifest.ID == "" {
		return nil, fmt.Errorf("manifest.json missing or invalid")
	}
	if session.Manifest.Version == "" {
		session.Manifest.Version = sessionFormatVersion
	}
	initializeHistory(session, "Loaded session", "")
	return session, nil
}

func fragmentIDs(fragments []Fragment, predicate func(Fragment) bool) []string {
	ids := make([]string, 0, len(fragments))
	for _, fragment := range fragments {
		if predicate(fragment) {
			ids = append(ids, fragment.ID)
		}
	}
	return ids
}
