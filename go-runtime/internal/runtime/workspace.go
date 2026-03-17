package runtime

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

type Completion struct {
	Value string `json:"value"`
	Type  string `json:"type"`
}

type BuildConfig struct {
	BuildOnlyCmd string `json:"buildOnlyCmd"`
	TestSomeCmd  string `json:"testSomeCmd"`
}

func relWorkspacePath(root, target string) (string, error) {
	absTarget := filepath.Clean(target)
	absRoot := filepath.Clean(root)
	rel, err := filepath.Rel(absRoot, absTarget)
	if err != nil {
		return "", err
	}
	if rel == "." || strings.HasPrefix(rel, "..") {
		return "", fmt.Errorf("path %q escapes workspace", target)
	}
	return filepath.ToSlash(rel), nil
}

func resolveWorkspacePath(root, relative string) (string, error) {
	cleaned := filepath.Clean(relative)
	if cleaned == "." || cleaned == "" {
		return "", errors.New("blank path")
	}
	path := filepath.Join(root, cleaned)
	rel, err := filepath.Rel(root, path)
	if err != nil {
		return "", err
	}
	if strings.HasPrefix(rel, "..") {
		return "", fmt.Errorf("path %q escapes workspace", relative)
	}
	return path, nil
}

func listWorkspaceFiles(root string, limit int) ([]string, error) {
	var files []string
	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		name := d.Name()
		if d.IsDir() {
			if name == ".git" || name == "node_modules" || name == ".gradle" || name == "build" {
				return filepath.SkipDir
			}
			return nil
		}
		rel, relErr := relWorkspacePath(root, path)
		if relErr == nil {
			files = append(files, rel)
		}
		if limit > 0 && len(files) >= limit {
			return io.EOF
		}
		return nil
	})
	if err != nil && !errors.Is(err, io.EOF) {
		return nil, err
	}
	sort.Strings(files)
	return files, nil
}

func searchWorkspace(root, query string, limit int) ([]string, error) {
	query = strings.TrimSpace(strings.ToLower(query))
	if query == "" {
		return []string{}, nil
	}
	files, err := listWorkspaceFiles(root, 0)
	if err != nil {
		return nil, err
	}
	var matches []string
	seen := map[string]struct{}{}
	for _, file := range files {
		if strings.Contains(strings.ToLower(file), query) {
			if _, ok := seen[file]; !ok {
				matches = append(matches, file)
				seen[file] = struct{}{}
			}
		}
		if limit > 0 && len(matches) >= limit {
			return matches, nil
		}
		absPath, pathErr := resolveWorkspacePath(root, file)
		if pathErr != nil {
			continue
		}
		data, readErr := os.ReadFile(absPath)
		if readErr != nil {
			continue
		}
		if bytes.Contains(bytes.ToLower(data), []byte(query)) {
			if _, ok := seen[file]; !ok {
				matches = append(matches, file)
				seen[file] = struct{}{}
			}
		}
		if limit > 0 && len(matches) >= limit {
			return matches, nil
		}
	}
	return matches, nil
}

func summarizeFile(path string, maxLines int) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return fmt.Sprintf("Could not read %s: %v", path, err)
	}
	lines := strings.Split(strings.ReplaceAll(string(data), "\r\n", "\n"), "\n")
	if len(lines) > maxLines {
		lines = lines[:maxLines]
	}
	return strings.Join(lines, "\n")
}

func completionsForQuery(root, query string, limit int) ([]Completion, error) {
	matches, err := searchWorkspace(root, query, limit)
	if err != nil {
		return nil, err
	}
	completions := make([]Completion, 0, len(matches))
	for _, match := range matches {
		completions = append(completions, Completion{Value: match, Type: "file"})
	}
	return completions, nil
}

func buildConfigPath(root string) string {
	return filepath.Join(root, ".brokk-go", "build.json")
}

func loadBuildConfig(root string) (BuildConfig, error) {
	data, err := os.ReadFile(buildConfigPath(root))
	if err != nil {
		return BuildConfig{}, err
	}
	var cfg BuildConfig
	if err := json.Unmarshal(data, &cfg); err != nil {
		return BuildConfig{}, err
	}
	return cfg, nil
}

func saveBuildConfig(root string, cfg BuildConfig) error {
	path := buildConfigPath(root)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o644)
}

func runCommand(root, command string, timeout time.Duration) (string, error) {
	if strings.TrimSpace(command) == "" {
		return "", errors.New("command is empty")
	}
	cmd := exec.Command("cmd", "/C", command)
	cmd.Dir = root
	var output bytes.Buffer
	cmd.Stdout = &output
	cmd.Stderr = &output

	timer := time.AfterFunc(timeout, func() {
		_ = cmd.Process.Kill()
	})
	defer timer.Stop()

	err := cmd.Run()
	return strings.TrimSpace(output.String()), err
}
