package sessions

import (
	"archive/zip"
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/brokk/brokk/go-runtime/internal/config"
)

const formatVersion = "4.0"

type SessionInfo struct {
	ID       string  `json:"id"`
	Name     string  `json:"name"`
	Created  int64   `json:"created"`
	Modified int64   `json:"modified"`
	Version  *string `json:"version,omitempty"`
}

type Manager struct {
	sessionsDir   string
	currentIDPath string

	mu        sync.RWMutex
	sessions  map[string]SessionInfo
	currentID string
}

func NewManager(storeDir string) (*Manager, error) {
	sessionsDir := filepath.Join(storeDir, "sessions")
	if err := os.MkdirAll(sessionsDir, 0o755); err != nil {
		return nil, err
	}

	m := &Manager{
		sessionsDir:   sessionsDir,
		currentIDPath: filepath.Join(storeDir, "current_session_id"),
		sessions:      map[string]SessionInfo{},
	}

	if err := m.loadSessions(); err != nil {
		return nil, err
	}
	m.loadCurrentID()
	return m, nil
}

func (m *Manager) SessionsDir() string {
	return m.sessionsDir
}

func (m *Manager) ListSessions() []SessionInfo {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make([]SessionInfo, 0, len(m.sessions))
	for _, session := range m.sessions {
		result = append(result, session)
	}
	sort.Slice(result, func(i, j int) bool {
		return result[i].Modified > result[j].Modified
	})
	return result
}

func (m *Manager) CurrentSessionID() string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.currentID
}

func (m *Manager) HasCurrentSession() bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.currentID != ""
}

func (m *Manager) CreateSession(name string) (SessionInfo, error) {
	sessionID, err := NewImportID()
	if err != nil {
		return SessionInfo{}, err
	}
	now := time.Now().UnixMilli()
	version := formatVersion
	info := SessionInfo{
		ID:       sessionID,
		Name:     name,
		Created:  now,
		Modified: now,
		Version:  &version,
	}

	if err := writeZipWithManifest(m.sessionZipPath(sessionID), info); err != nil {
		return SessionInfo{}, err
	}

	m.mu.Lock()
	m.sessions[sessionID] = info
	m.currentID = sessionID
	m.mu.Unlock()
	if err := m.persistCurrentID(sessionID); err != nil {
		return SessionInfo{}, err
	}

	return info, nil
}

func (m *Manager) SwitchSession(sessionID string) error {
	if !config.LooksLikeUUID(sessionID) {
		return errors.New("invalid sessionId")
	}

	m.mu.RLock()
	_, ok := m.sessions[sessionID]
	m.mu.RUnlock()
	if !ok {
		if _, err := os.Stat(m.sessionZipPath(sessionID)); err != nil {
			return os.ErrNotExist
		}
	}

	m.mu.Lock()
	m.currentID = sessionID
	m.mu.Unlock()
	return m.persistCurrentID(sessionID)
}

func (m *Manager) ImportSession(sessionID string, contents []byte) (SessionInfo, error) {
	if !config.LooksLikeUUID(sessionID) {
		return SessionInfo{}, errors.New("invalid sessionId")
	}

	targetPath := m.sessionZipPath(sessionID)
	if err := atomicWriteBytes(targetPath, contents); err != nil {
		return SessionInfo{}, err
	}

	info, ok := readManifestFromZipBytes(contents)
	if !ok {
		now := time.Now().UnixMilli()
		version := formatVersion
		info = SessionInfo{
			ID:       sessionID,
			Name:     "Session",
			Created:  now,
			Modified: 0,
			Version:  &version,
		}
	} else {
		info.ID = sessionID
	}

	fileInfo, err := os.Stat(targetPath)
	if err == nil {
		info.Modified = fileInfo.ModTime().UnixMilli()
	}

	m.mu.Lock()
	m.sessions[sessionID] = info
	m.currentID = sessionID
	m.mu.Unlock()
	if err := m.persistCurrentID(sessionID); err != nil {
		return SessionInfo{}, err
	}

	return info, nil
}

func (m *Manager) ReadSessionZip(sessionID string) ([]byte, error) {
	return os.ReadFile(m.sessionZipPath(sessionID))
}

func (m *Manager) CurrentSessionInfo() (SessionInfo, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	if m.currentID == "" {
		return SessionInfo{}, false
	}
	info, ok := m.sessions[m.currentID]
	if ok {
		return info, true
	}
	return SessionInfo{ID: m.currentID, Name: "Session", Created: 0, Modified: 0}, true
}

func (m *Manager) loadSessions() error {
	entries, err := os.ReadDir(m.sessionsDir)
	if err != nil {
		return err
	}

	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".zip") {
			continue
		}

		sessionID := strings.TrimSuffix(entry.Name(), ".zip")
		if !config.LooksLikeUUID(sessionID) {
			continue
		}

		path := filepath.Join(m.sessionsDir, entry.Name())
		info, ok := readManifestFromZipPath(path)
		if !ok {
			stat, statErr := os.Stat(path)
			if statErr != nil {
				continue
			}
			version := formatVersion
			info = SessionInfo{
				ID:       sessionID,
				Name:     "Session",
				Created:  stat.ModTime().UnixMilli(),
				Modified: stat.ModTime().UnixMilli(),
				Version:  &version,
			}
		}
		info.ID = sessionID
		m.sessions[sessionID] = info
	}

	return nil
}

func (m *Manager) loadCurrentID() {
	bytes, err := os.ReadFile(m.currentIDPath)
	if err != nil {
		return
	}
	currentID := strings.TrimSpace(string(bytes))
	if currentID == "" || !config.LooksLikeUUID(currentID) {
		return
	}

	m.mu.Lock()
	m.currentID = currentID
	m.mu.Unlock()
}

func (m *Manager) persistCurrentID(sessionID string) error {
	return atomicWriteBytes(m.currentIDPath, []byte(sessionID))
}

func (m *Manager) sessionZipPath(sessionID string) string {
	return filepath.Join(m.sessionsDir, sessionID+".zip")
}

func writeZipWithManifest(path string, info SessionInfo) error {
	buffer := &bytes.Buffer{}
	zw := zip.NewWriter(buffer)

	file, err := zw.Create("manifest.json")
	if err != nil {
		return err
	}

	payload, err := json.Marshal(info)
	if err != nil {
		return err
	}
	if _, err := file.Write(payload); err != nil {
		return err
	}
	if err := zw.Close(); err != nil {
		return err
	}

	return atomicWriteBytes(path, buffer.Bytes())
}

func readManifestFromZipPath(path string) (SessionInfo, bool) {
	bytes, err := os.ReadFile(path)
	if err != nil {
		return SessionInfo{}, false
	}
	return readManifestFromZipBytes(bytes)
}

func readManifestFromZipBytes(contents []byte) (SessionInfo, bool) {
	reader, err := zip.NewReader(bytes.NewReader(contents), int64(len(contents)))
	if err != nil {
		return SessionInfo{}, false
	}

	for _, file := range reader.File {
		if file.Name != "manifest.json" {
			continue
		}
		rc, err := file.Open()
		if err != nil {
			return SessionInfo{}, false
		}
		defer rc.Close()

		payload, err := io.ReadAll(rc)
		if err != nil {
			return SessionInfo{}, false
		}

		var info SessionInfo
		if err := json.Unmarshal(payload, &info); err != nil {
			return SessionInfo{}, false
		}
		return info, true
	}

	return SessionInfo{}, false
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

func NewImportID() (string, error) {
	bytes := make([]byte, 16)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}

	bytes[6] = (bytes[6] & 0x0f) | 0x40
	bytes[8] = (bytes[8] & 0x3f) | 0x80
	hexValue := hex.EncodeToString(bytes)
	return fmt.Sprintf("%s-%s-%s-%s-%s", hexValue[0:8], hexValue[8:12], hexValue[12:16], hexValue[16:20], hexValue[20:32]), nil
}
