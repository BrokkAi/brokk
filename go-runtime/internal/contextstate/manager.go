package contextstate

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

type Fragment struct {
	ID               string `json:"id"`
	Type             string `json:"type"`
	ShortDescription string `json:"shortDescription"`
	Pinned           bool   `json:"pinned"`
	Readonly         bool   `json:"readonly"`
	Valid            bool   `json:"valid"`
	Editable         bool   `json:"editable"`
	Text             string `json:"text"`
	URI              string `json:"uri"`
	MimeType         string `json:"mimeType"`
}

type TaskItem struct {
	ID    string `json:"id"`
	Title string `json:"title"`
	Text  string `json:"text"`
	Done  bool   `json:"done"`
}

type TaskListData struct {
	BigPicture *string    `json:"bigPicture"`
	Tasks      []TaskItem `json:"tasks"`
}

type ConversationEntry struct {
	Sequence     int64             `json:"sequence"`
	IsCompressed bool              `json:"isCompressed"`
	Summary      *string           `json:"summary,omitempty"`
	Messages     []ConversationMsg `json:"messages,omitempty"`
}

type ConversationMsg struct {
	Role      string  `json:"role"`
	Text      string  `json:"text"`
	Reasoning *string `json:"reasoning,omitempty"`
}

type State struct {
	Fragments    []Fragment          `json:"fragments"`
	TaskList     TaskListData        `json:"taskList"`
	Conversation []ConversationEntry `json:"conversation"`
	NextSequence int64               `json:"nextSequence"`
}

type Manager struct {
	stateDir string

	mu    sync.Mutex
	cache map[string]State
}

func NewManager(storeDir string) (*Manager, error) {
	stateDir := filepath.Join(storeDir, "context")
	if err := os.MkdirAll(stateDir, 0o755); err != nil {
		return nil, err
	}

	return &Manager{
		stateDir: stateDir,
		cache:    map[string]State{},
	}, nil
}

func (m *Manager) Get(sessionID string) (State, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	state, ok := m.cache[sessionID]
	if ok {
		return copyState(state), nil
	}

	state, err := m.load(sessionID)
	if err != nil {
		return State{}, err
	}
	m.cache[sessionID] = state
	return copyState(state), nil
}

func (m *Manager) Update(sessionID string, mutate func(*State) error) (State, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	state, ok := m.cache[sessionID]
	if !ok {
		var err error
		state, err = m.load(sessionID)
		if err != nil {
			return State{}, err
		}
	}

	if err := mutate(&state); err != nil {
		return State{}, err
	}
	if err := m.persist(sessionID, state); err != nil {
		return State{}, err
	}
	m.cache[sessionID] = state
	return copyState(state), nil
}

func (m *Manager) AddTextFragment(sessionID string, text string) (Fragment, State, error) {
	var created Fragment
	state, err := m.Update(sessionID, func(state *State) error {
		id, err := newID()
		if err != nil {
			return err
		}
		short := text
		short = strings.ReplaceAll(short, "\r", " ")
		short = strings.ReplaceAll(short, "\n", " ")
		if len(short) > 80 {
			short = short[:80]
		}
		created = Fragment{
			ID:               id,
			Type:             "PASTE_TEXT",
			ShortDescription: short,
			Pinned:           false,
			Readonly:         false,
			Valid:            true,
			Editable:         true,
			Text:             text,
			URI:              "brokk://context/fragment/" + id,
			MimeType:         "text/plain",
		}
		state.Fragments = append(state.Fragments, created)
		return nil
	})
	return created, state, err
}

func (m *Manager) ReplaceTaskList(sessionID string, bigPicture *string, tasks []TaskItem) (TaskListData, error) {
	state, err := m.Update(sessionID, func(state *State) error {
		state.TaskList = TaskListData{
			BigPicture: bigPicture,
			Tasks:      tasks,
		}
		return nil
	})
	return state.TaskList, err
}

func (m *Manager) ClearHistory(sessionID string) (State, error) {
	return m.Update(sessionID, func(state *State) error {
		state.Conversation = []ConversationEntry{}
		state.NextSequence = 0
		return nil
	})
}

func (m *Manager) DropAll(sessionID string) (State, error) {
	return m.Update(sessionID, func(state *State) error {
		state.Fragments = []Fragment{}
		state.TaskList = emptyTaskList()
		return nil
	})
}

func (m *Manager) load(sessionID string) (State, error) {
	path := m.statePath(sessionID)
	bytes, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return State{
				Fragments:    []Fragment{},
				TaskList:     emptyTaskList(),
				Conversation: []ConversationEntry{},
				NextSequence: 0,
			}, nil
		}
		return State{}, err
	}

	var state State
	if err := json.Unmarshal(bytes, &state); err != nil {
		return State{}, err
	}
	if state.Fragments == nil {
		state.Fragments = []Fragment{}
	}
	if state.TaskList.Tasks == nil {
		state.TaskList.Tasks = []TaskItem{}
	}
	if state.Conversation == nil {
		state.Conversation = []ConversationEntry{}
	}
	return state, nil
}

func (m *Manager) persist(sessionID string, state State) error {
	bytes, err := json.Marshal(state)
	if err != nil {
		return err
	}
	return atomicWriteBytes(m.statePath(sessionID), bytes)
}

func (m *Manager) statePath(sessionID string) string {
	return filepath.Join(m.stateDir, sessionID+".json")
}

func emptyTaskList() TaskListData {
	return TaskListData{
		BigPicture: nil,
		Tasks:      []TaskItem{},
	}
}

func copyState(state State) State {
	fragments := append([]Fragment(nil), state.Fragments...)
	tasks := append([]TaskItem(nil), state.TaskList.Tasks...)
	conversation := append([]ConversationEntry(nil), state.Conversation...)
	return State{
		Fragments: fragments,
		TaskList: TaskListData{
			BigPicture: state.TaskList.BigPicture,
			Tasks:      tasks,
		},
		Conversation: conversation,
		NextSequence: state.NextSequence,
	}
}

func atomicWriteBytes(path string, bytes []byte) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}

	tempPath := filepath.Join(filepath.Dir(path), "."+filepath.Base(path)+".tmp")
	if err := os.WriteFile(tempPath, bytes, 0o644); err != nil {
		return err
	}
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return err
	}
	return os.Rename(tempPath, path)
}

func newID() (string, error) {
	bytes := make([]byte, 16)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}

	bytes[6] = (bytes[6] & 0x0f) | 0x40
	bytes[8] = (bytes[8] & 0x3f) | 0x80
	hexValue := hex.EncodeToString(bytes)
	return fmt.Sprintf("%s-%s-%s-%s-%s", hexValue[0:8], hexValue[8:12], hexValue[12:16], hexValue[16:20], hexValue[20:32]), nil
}
