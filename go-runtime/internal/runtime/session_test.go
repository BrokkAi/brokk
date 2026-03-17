package runtime

import (
	"archive/zip"
	"bytes"
	"testing"
)

func TestSessionStoreCreateAndDownload(t *testing.T) {
	store, err := NewSessionStore(t.TempDir())
	if err != nil {
		t.Fatalf("NewSessionStore returned error: %v", err)
	}
	session, err := store.Create("Test Session")
	if err != nil {
		t.Fatalf("Create returned error: %v", err)
	}
	raw, err := store.Download(session.Manifest.ID)
	if err != nil {
		t.Fatalf("Download returned error: %v", err)
	}
	reader, err := zip.NewReader(bytes.NewReader(raw), int64(len(raw)))
	if err != nil {
		t.Fatalf("zip.NewReader returned error: %v", err)
	}
	names := map[string]bool{}
	for _, file := range reader.File {
		names[file.Name] = true
	}
	for _, expected := range []string{"manifest.json", "contexts.jsonl", "fragments-v4.json"} {
		if !names[expected] {
			t.Fatalf("expected %s in session zip", expected)
		}
	}
}

func TestSessionImportRoundTrip(t *testing.T) {
	store, err := NewSessionStore(t.TempDir())
	if err != nil {
		t.Fatalf("NewSessionStore returned error: %v", err)
	}
	created, err := store.Create("Created")
	if err != nil {
		t.Fatalf("Create returned error: %v", err)
	}
	raw, err := store.Download(created.Manifest.ID)
	if err != nil {
		t.Fatalf("Download returned error: %v", err)
	}
	imported, err := store.Import("imported-id", raw)
	if err != nil {
		t.Fatalf("Import returned error: %v", err)
	}
	if imported.Manifest.ID != "imported-id" {
		t.Fatalf("expected imported session id override, got %s", imported.Manifest.ID)
	}
}
