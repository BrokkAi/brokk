package jobs

import (
	"bufio"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type JobStore struct {
	jobsDir        string
	idempotencyDir string

	mu               sync.Mutex
	sequenceCounters map[string]*atomic.Int64
}

var atomicWriteMu sync.Mutex

func NewJobStore(storeDir string) (*JobStore, error) {
	jobsDir := filepath.Join(storeDir, "jobs")
	idempotencyDir := filepath.Join(storeDir, "idempotency")
	if err := os.MkdirAll(jobsDir, 0o755); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(idempotencyDir, 0o755); err != nil {
		return nil, err
	}

	return &JobStore{
		jobsDir:          jobsDir,
		idempotencyDir:   idempotencyDir,
		sequenceCounters: map[string]*atomic.Int64{},
	}, nil
}

func (s *JobStore) CreateOrGetJob(idempotencyKey string, spec JobSpec) (JobCreateResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	hash := hashIdempotencyKey(idempotencyKey)
	idempotencyFile := filepath.Join(s.idempotencyDir, hash+".json")
	if _, err := os.Stat(idempotencyFile); err == nil {
		var entry idempotencyEntry
		if err := readJSONFile(idempotencyFile, &entry); err != nil {
			return JobCreateResult{}, err
		}
		return JobCreateResult{JobID: entry.JobID, IsNewJob: false}, nil
	}

	jobID, err := newUUID()
	if err != nil {
		return JobCreateResult{}, err
	}
	jobDir := filepath.Join(s.jobsDir, jobID)
	if err := os.MkdirAll(filepath.Join(jobDir, "artifacts"), 0o755); err != nil {
		return JobCreateResult{}, err
	}

	specForPersistence := spec
	specForPersistence.Tags = spec.RedactedTags()
	if err := atomicWriteJSON(filepath.Join(jobDir, "meta.json"), specForPersistence); err != nil {
		return JobCreateResult{}, err
	}

	initialStatus := QueuedStatus(jobID)
	if err := atomicWriteJSON(filepath.Join(jobDir, "status.json"), initialStatus); err != nil {
		return JobCreateResult{}, err
	}

	entry := idempotencyEntry{JobID: jobID, CreatedAt: time.Now().UnixMilli()}
	if err := atomicWriteJSON(idempotencyFile, entry); err != nil {
		return JobCreateResult{}, err
	}

	counter := &atomic.Int64{}
	s.sequenceCounters[jobID] = counter
	return JobCreateResult{JobID: jobID, IsNewJob: true}, nil
}

func (s *JobStore) AppendEvent(jobID string, event JobEvent) (int64, error) {
	counter := s.sequenceCounter(jobID)
	seq := counter.Add(1)
	event.Seq = seq

	jobDir := filepath.Join(s.jobsDir, jobID)
	if err := os.MkdirAll(jobDir, 0o755); err != nil {
		return 0, err
	}

	file, err := os.OpenFile(filepath.Join(jobDir, "events.jsonl"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return 0, err
	}
	defer file.Close()

	line, err := json.Marshal(event)
	if err != nil {
		return 0, err
	}
	if _, err := file.Write(append(line, '\n')); err != nil {
		return 0, err
	}

	return seq, nil
}

func (s *JobStore) UpdateStatus(jobID string, status JobStatus) error {
	return atomicWriteJSON(filepath.Join(s.jobsDir, jobID, "status.json"), status)
}

func (s *JobStore) LoadStatus(jobID string) (*JobStatus, error) {
	path := filepath.Join(s.jobsDir, jobID, "status.json")
	if _, err := os.Stat(path); err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}

	var status JobStatus
	if err := readJSONFile(path, &status); err != nil {
		return nil, err
	}
	return &status, nil
}

func (s *JobStore) ReadEvents(jobID string, afterSeq int64, limit int) ([]JobEvent, error) {
	path := filepath.Join(s.jobsDir, jobID, "events.jsonl")
	if _, err := os.Stat(path); err != nil {
		if os.IsNotExist(err) {
			return []JobEvent{}, nil
		}
		return nil, err
	}

	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	result := make([]JobEvent, 0)
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		var event JobEvent
		if err := json.Unmarshal([]byte(line), &event); err != nil {
			return nil, err
		}
		if event.Seq > afterSeq {
			result = append(result, event)
		}
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}

	sort.Slice(result, func(i, j int) bool {
		return result[i].Seq < result[j].Seq
	})
	if limit > 0 && len(result) > limit {
		return result[:limit], nil
	}
	return result, nil
}

func (s *JobStore) LoadSpec(jobID string) (*JobSpec, error) {
	path := filepath.Join(s.jobsDir, jobID, "meta.json")
	if _, err := os.Stat(path); err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}

	var spec JobSpec
	if err := readJSONFile(path, &spec); err != nil {
		return nil, err
	}
	return &spec, nil
}

func (s *JobStore) WriteArtifact(jobID string, artifactName string, content []byte) error {
	path := filepath.Join(s.jobsDir, jobID, "artifacts", artifactName)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return atomicWriteBytes(path, content)
}

func (s *JobStore) ReadArtifact(jobID string, artifactName string) ([]byte, error) {
	path := filepath.Join(s.jobsDir, jobID, "artifacts", artifactName)
	if _, err := os.Stat(path); err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}
	return os.ReadFile(path)
}

func (s *JobStore) ListJobSummaries(limit int) ([]JobSummary, error) {
	entries, err := os.ReadDir(s.jobsDir)
	if err != nil {
		return nil, err
	}

	summaries := make([]JobSummary, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}

		jobID := entry.Name()
		status, err := s.LoadStatus(jobID)
		if err != nil || status == nil {
			continue
		}

		spec, err := s.LoadSpec(jobID)
		if err != nil || spec == nil {
			continue
		}

		mode := strings.ToUpper(strings.TrimSpace(spec.Tags["mode"]))
		if mode == "" {
			mode = "ARCHITECT"
		}

		summaries = append(summaries, JobSummary{
			JobID:     jobID,
			State:     status.State,
			StartTime: status.StartTime,
			EndTime:   status.EndTime,
			Mode:      mode,
			TaskInput: spec.TaskInput,
		})
	}

	sort.Slice(summaries, func(i, j int) bool {
		if summaries[i].StartTime != summaries[j].StartTime {
			return summaries[i].StartTime > summaries[j].StartTime
		}
		return summaries[i].JobID > summaries[j].JobID
	})
	if limit > 0 && len(summaries) > limit {
		return summaries[:limit], nil
	}
	return summaries, nil
}

func (s *JobStore) GetJobDir(jobID string) string {
	return filepath.Join(s.jobsDir, jobID)
}

func (s *JobStore) GetLastSeq(jobID string) int64 {
	counter := s.sequenceCounter(jobID)
	val := counter.Load()
	if val == 0 {
		return -1
	}
	return val
}

func (s *JobStore) sequenceCounter(jobID string) *atomic.Int64 {
	s.mu.Lock()
	defer s.mu.Unlock()

	counter, ok := s.sequenceCounters[jobID]
	if ok {
		return counter
	}

	counter = &atomic.Int64{}
	counter.Store(s.loadSequenceCounter(jobID))
	s.sequenceCounters[jobID] = counter
	return counter
}

func (s *JobStore) loadSequenceCounter(jobID string) int64 {
	path := filepath.Join(s.jobsDir, jobID, "events.jsonl")
	file, err := os.Open(path)
	if err != nil {
		return 0
	}
	defer file.Close()

	var last string
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line != "" {
			last = line
		}
	}
	if last == "" {
		return 0
	}

	var event JobEvent
	if err := json.Unmarshal([]byte(last), &event); err != nil {
		return 0
	}
	return event.Seq
}

func readJSONFile(path string, target any) error {
	var lastErr error
	for attempt := 0; attempt < 3; attempt++ {
		bytes, err := os.ReadFile(path)
		if err != nil {
			lastErr = err
			time.Sleep(5 * time.Millisecond)
			continue
		}
		if err := json.Unmarshal(bytes, target); err != nil {
			lastErr = err
			time.Sleep(5 * time.Millisecond)
			continue
		}
		return nil
	}
	return lastErr
}

func atomicWriteJSON(path string, payload any) error {
	bytes, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	return atomicWriteBytes(path, bytes)
}

func atomicWriteBytes(path string, bytes []byte) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}

	atomicWriteMu.Lock()
	defer atomicWriteMu.Unlock()
	return os.WriteFile(path, bytes, 0o644)
}

func hashIdempotencyKey(key string) string {
	sum := sha256.Sum256([]byte(key))
	return hex.EncodeToString(sum[:])
}

func newUUID() (string, error) {
	bytes := make([]byte, 16)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}

	bytes[6] = (bytes[6] & 0x0f) | 0x40
	bytes[8] = (bytes[8] & 0x3f) | 0x80
	hexValue := hex.EncodeToString(bytes)
	return fmt.Sprintf("%s-%s-%s-%s-%s", hexValue[0:8], hexValue[8:12], hexValue[12:16], hexValue[16:20], hexValue[20:32]), nil
}

type idempotencyEntry struct {
	JobID     string `json:"jobId"`
	CreatedAt int64  `json:"createdAt"`
}
