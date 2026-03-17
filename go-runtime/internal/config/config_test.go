package config

import (
	"errors"
	"strings"
	"testing"
)

func TestLoadUsesArgsOverEnv(t *testing.T) {
	cfg, err := Load([]string{
		"--exec-id", "550e8400-e29b-41d4-a716-446655440000",
		"--listen-addr", "127.0.0.1:8080",
		"--auth-token", "arg-token",
		"--workspace-dir", "workspace",
	}, func(key string) string {
		if key == "AUTH_TOKEN" {
			return "env-token"
		}
		return ""
	})
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}

	if cfg.AuthToken != "arg-token" {
		t.Fatalf("AuthToken = %q, want arg-token", cfg.AuthToken)
	}
}

func TestLoadRejectsUnknownArgs(t *testing.T) {
	_, err := Load([]string{"--wat", "x"}, func(string) string { return "" })
	if err == nil {
		t.Fatal("Load() expected error")
	}

	var usageErr *UsageError
	if !errors.As(err, &usageErr) {
		t.Fatalf("error = %v, want UsageError", err)
	}
	if !strings.Contains(usageErr.Message, "--wat") {
		t.Fatalf("message = %q, want mention of --wat", usageErr.Message)
	}
}

func TestLoadParsesBoolean(t *testing.T) {
	cfg, err := Load([]string{
		"--exec-id", "550e8400-e29b-41d4-a716-446655440000",
		"--listen-addr", "127.0.0.1:8080",
		"--auth-token", "token",
		"--workspace-dir", "workspace",
		"--exit-on-stdin-eof=false",
	}, func(string) string { return "" })
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}

	if cfg.ExitOnStdinEOF {
		t.Fatal("ExitOnStdinEOF = true, want false")
	}
}
