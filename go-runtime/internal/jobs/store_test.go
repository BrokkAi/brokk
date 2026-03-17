package jobs

import "testing"

func TestCreateOrGetJobIdempotency(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	store, err := NewJobStore(dir)
	if err != nil {
		t.Fatalf("NewJobStore() error = %v", err)
	}

	spec := MinimalJobSpec("test task", "gpt-5")
	result1, err := store.CreateOrGetJob("idem-key-1", spec)
	if err != nil {
		t.Fatalf("CreateOrGetJob() error = %v", err)
	}
	result2, err := store.CreateOrGetJob("idem-key-1", spec)
	if err != nil {
		t.Fatalf("CreateOrGetJob() error = %v", err)
	}

	if !result1.IsNewJob {
		t.Fatal("first result should be new")
	}
	if result2.IsNewJob {
		t.Fatal("second result should reuse job")
	}
	if result1.JobID != result2.JobID {
		t.Fatalf("JobID mismatch: %q != %q", result1.JobID, result2.JobID)
	}
}

func TestAppendEventMonotonicSequence(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	store, err := NewJobStore(dir)
	if err != nil {
		t.Fatalf("NewJobStore() error = %v", err)
	}

	result, err := store.CreateOrGetJob("idem-key-1", MinimalJobSpec("task", "gpt-5"))
	if err != nil {
		t.Fatalf("CreateOrGetJob() error = %v", err)
	}

	seq1, err := store.AppendEvent(result.JobID, NewEvent("event1", "data1"))
	if err != nil {
		t.Fatalf("AppendEvent() error = %v", err)
	}
	seq2, err := store.AppendEvent(result.JobID, NewEvent("event2", "data2"))
	if err != nil {
		t.Fatalf("AppendEvent() error = %v", err)
	}

	if seq1 != 1 || seq2 != 2 {
		t.Fatalf("sequences = %d, %d; want 1, 2", seq1, seq2)
	}
}

func TestSequenceCounterPersistsAcrossRestart(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	store, err := NewJobStore(dir)
	if err != nil {
		t.Fatalf("NewJobStore() error = %v", err)
	}

	result, err := store.CreateOrGetJob("idem-key-1", MinimalJobSpec("task", "gpt-5"))
	if err != nil {
		t.Fatalf("CreateOrGetJob() error = %v", err)
	}
	if _, err := store.AppendEvent(result.JobID, NewEvent("event1", "data1")); err != nil {
		t.Fatalf("AppendEvent() error = %v", err)
	}
	if _, err := store.AppendEvent(result.JobID, NewEvent("event2", "data2")); err != nil {
		t.Fatalf("AppendEvent() error = %v", err)
	}

	store2, err := NewJobStore(dir)
	if err != nil {
		t.Fatalf("NewJobStore() error = %v", err)
	}
	seq, err := store2.AppendEvent(result.JobID, NewEvent("event3", "data3"))
	if err != nil {
		t.Fatalf("AppendEvent() error = %v", err)
	}

	if seq != 3 {
		t.Fatalf("sequence = %d, want 3", seq)
	}
}
