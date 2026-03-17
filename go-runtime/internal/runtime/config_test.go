package runtime

import (
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadConfigFromArgsAndEnv(t *testing.T) {
	tempDir := t.TempDir()
	t.Setenv("EXEC_ID", "env-exec")
	t.Setenv("LISTEN_ADDR", "127.0.0.1:0")
	t.Setenv("AUTH_TOKEN", "secret")
	t.Setenv("WORKSPACE_DIR", tempDir)
	t.Setenv("EXIT_ON_STDIN_EOF", "true")

	cfg, err := LoadConfig(nil)
	if err != nil {
		t.Fatalf("LoadConfig returned error: %v", err)
	}
	if cfg.ExecID != "env-exec" {
		t.Fatalf("unexpected exec id: %s", cfg.ExecID)
	}
	if cfg.WorkspaceDir != filepath.Clean(tempDir) {
		t.Fatalf("unexpected workspace dir: %s", cfg.WorkspaceDir)
	}
	if !cfg.ExitOnStdinEOF {
		t.Fatal("expected EXIT_ON_STDIN_EOF to be true")
	}
}

func TestLoadConfigArgOverridesEnv(t *testing.T) {
	tempDir := t.TempDir()
	t.Setenv("EXEC_ID", "env-exec")
	t.Setenv("LISTEN_ADDR", "127.0.0.1:9000")
	t.Setenv("AUTH_TOKEN", "env-token")
	t.Setenv("WORKSPACE_DIR", tempDir)

	cfg, err := LoadConfig([]string{"--exec-id", "arg-exec", "--listen-addr=127.0.0.1:0", "--auth-token", "arg-token", "--workspace-dir", tempDir})
	if err != nil {
		t.Fatalf("LoadConfig returned error: %v", err)
	}
	if cfg.ExecID != "arg-exec" || cfg.AuthToken != "arg-token" {
		t.Fatalf("args did not override env: %+v", cfg)
	}
}

func TestLoadConfigHelp(t *testing.T) {
	_, err := LoadConfig([]string{"--help"})
	if err != ErrHelpRequested {
		t.Fatalf("expected ErrHelpRequested, got %v", err)
	}
}

func TestLoadConfigRejectsUnknownArgs(t *testing.T) {
	_, err := LoadConfig([]string{"--wat"})
	if err == nil || !strings.Contains(err.Error(), "unknown argument") {
		t.Fatalf("expected unknown arg error, got %v", err)
	}
}

func TestLoadConfigRejectsInvalidBoolean(t *testing.T) {
	tempDir := t.TempDir()
	_, err := LoadConfig([]string{"--exec-id", "exec-1", "--listen-addr", "127.0.0.1:0", "--auth-token", "token", "--workspace-dir", tempDir, "--exit-on-stdin-eof", "maybe"})
	if err == nil || !strings.Contains(err.Error(), "invalid boolean value") {
		t.Fatalf("expected invalid boolean error, got %v", err)
	}
}

func TestLoadConfigRejectsInvalidListenAddr(t *testing.T) {
	tempDir := t.TempDir()
	_, err := LoadConfig([]string{"--exec-id", "exec-1", "--listen-addr", "broken", "--auth-token", "token", "--workspace-dir", tempDir})
	if err == nil || !strings.Contains(err.Error(), "LISTEN_ADDR must be in format") {
		t.Fatalf("expected invalid listen addr error, got %v", err)
	}
}
