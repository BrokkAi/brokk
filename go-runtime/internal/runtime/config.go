package runtime

import (
	"crypto/rand"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

const (
	ServerVersion   = "0.1.0-go-bootstrap"
	ProtocolVersion = 1
)

var ErrHelpRequested = errors.New("help requested")

type Config struct {
	ExecID         string
	ListenAddr     string
	AuthToken      string
	WorkspaceDir   string
	BrokkAPIKey    string
	ProxySetting   string
	Vendor         string
	ExitOnStdinEOF bool
}

func ExecutorUsage() string {
	return `Usage: brokk-go-executor [options]

Options:
  --exec-id <uuid>           Executor UUID (required)
  --listen-addr <host:port>  Address to listen on (required)
  --auth-token <token>       Authentication token (required)
  --workspace-dir <path>     Path to workspace directory (required)
  --brokk-api-key <key>      Brokk API key override (optional)
  --proxy-setting <setting>  LLM proxy: BROKK, LOCALHOST, STAGING (optional)
  --vendor <vendor>          Other-models vendor: Default, Anthropic, Gemini, OpenAI, OpenAI - Codex (optional)
  --exit-on-stdin-eof[=bool] Exit when stdin closes/errors (default: false; env EXIT_ON_STDIN_EOF)
  --help                     Show this help message

Arguments can also be provided via environment variables:
  EXEC_ID, LISTEN_ADDR, AUTH_TOKEN, WORKSPACE_DIR, BROKK_API_KEY, PROXY_SETTING, EXIT_ON_STDIN_EOF
`
}

func MCPUsage() string {
	return `Brokk Go MCP Server

Provides Model Context Protocol access to a lightweight Go Brokk runtime.

Available tools:
  scan
  callCodeAgent
  runBuild
  configureBuild
  merge
  searchSymbols
  scanUsages
  skimDirectory
  getFileSummaries
  getClassSkeletons
  getClassSources
  getMethodSources
  getSymbolLocations
`
}

func LoadConfig(args []string) (Config, error) {
	parsed, invalid, err := parseArgs(args)
	if err != nil {
		return Config{}, err
	}
	if len(invalid) > 0 {
		sort.Strings(invalid)
		return Config{}, fmt.Errorf("unknown argument(s): %s", strings.Join(invalid, ", "))
	}
	if _, ok := parsed["help"]; ok {
		return Config{}, ErrHelpRequested
	}

	exitOnStdinEOF, err := getBooleanConfigValue(parsed, "exit-on-stdin-eof", "EXIT_ON_STDIN_EOF", false)
	if err != nil {
		return Config{}, err
	}

	cfg := Config{
		ExecID:         firstNonBlank(parsed["exec-id"], os.Getenv("EXEC_ID")),
		ListenAddr:     firstNonBlank(parsed["listen-addr"], os.Getenv("LISTEN_ADDR")),
		AuthToken:      firstNonBlank(parsed["auth-token"], os.Getenv("AUTH_TOKEN")),
		WorkspaceDir:   firstNonBlank(parsed["workspace-dir"], os.Getenv("WORKSPACE_DIR")),
		BrokkAPIKey:    firstNonBlank(parsed["brokk-api-key"], os.Getenv("BROKK_API_KEY")),
		ProxySetting:   firstNonBlank(parsed["proxy-setting"], os.Getenv("PROXY_SETTING")),
		Vendor:         firstNonBlank(parsed["vendor"], os.Getenv("VENDOR")),
		ExitOnStdinEOF: exitOnStdinEOF,
	}

	switch {
	case strings.TrimSpace(cfg.ExecID) == "":
		return Config{}, errors.New("EXEC_ID must be provided")
	case strings.TrimSpace(cfg.ListenAddr) == "":
		return Config{}, errors.New("LISTEN_ADDR must be provided")
	case strings.TrimSpace(cfg.AuthToken) == "":
		return Config{}, errors.New("AUTH_TOKEN must be provided")
	case strings.TrimSpace(cfg.WorkspaceDir) == "":
		return Config{}, errors.New("WORKSPACE_DIR must be provided")
	}

	if err := validateListenAddr(cfg.ListenAddr); err != nil {
		return Config{}, err
	}

	abs, err := filepath.Abs(cfg.WorkspaceDir)
	if err != nil {
		return Config{}, fmt.Errorf("resolve workspace dir: %w", err)
	}
	cfg.WorkspaceDir = filepath.Clean(abs)
	return cfg, nil
}

func WaitForStdinEOF() error {
	buf := make([]byte, 1)
	for {
		_, err := os.Stdin.Read(buf)
		if err != nil {
			if errors.Is(err, io.EOF) {
				return nil
			}
			return err
		}
	}
}

func NewIdentifier(prefix string) string {
	raw := make([]byte, 16)
	_, _ = rand.Read(raw)
	return fmt.Sprintf("%s-%x-%x-%x-%x-%x", prefix, raw[0:4], raw[4:6], raw[6:8], raw[8:10], raw[10:16])
}

func parseArgs(args []string) (map[string]string, []string, error) {
	valid := map[string]struct{}{
		"exec-id": {}, "listen-addr": {}, "auth-token": {}, "workspace-dir": {},
		"brokk-api-key": {}, "proxy-setting": {}, "vendor": {}, "exit-on-stdin-eof": {}, "help": {},
	}
	result := make(map[string]string)
	var invalid []string
	for i := 0; i < len(args); i++ {
		arg := args[i]
		if !strings.HasPrefix(arg, "--") {
			continue
		}
		key := strings.TrimPrefix(arg, "--")
		value := ""
		if strings.Contains(key, "=") {
			parts := strings.SplitN(key, "=", 2)
			key, value = parts[0], parts[1]
		} else if i+1 < len(args) && !strings.HasPrefix(args[i+1], "--") {
			value = args[i+1]
			i++
		}
		if _, ok := valid[key]; !ok {
			invalid = append(invalid, "--"+key)
			continue
		}
		result[key] = value
	}
	return result, invalid, nil
}

func parseBoolValue(raw string, sourceName string) (bool, error) {
	normalized := strings.ToLower(strings.TrimSpace(raw))
	switch normalized {
	case "", "1", "true", "yes", "on":
		return true, nil
	case "0", "false", "no", "off":
		return false, nil
	default:
		return false, fmt.Errorf("invalid boolean value for %s: %q. Expected one of true/false, 1/0, yes/no, on/off", sourceName, raw)
	}
}

func getBooleanConfigValue(parsedArgs map[string]string, argKey string, envVarName string, defaultValue bool) (bool, error) {
	if value, ok := parsedArgs[argKey]; ok {
		return parseBoolValue(value, "--"+argKey)
	}
	envValue := strings.TrimSpace(os.Getenv(envVarName))
	if envValue != "" {
		return parseBoolValue(envValue, envVarName)
	}
	return defaultValue, nil
}

func validateListenAddr(value string) error {
	parts := strings.Split(value, ":")
	if len(parts) != 2 || strings.TrimSpace(parts[0]) == "" {
		return fmt.Errorf("LISTEN_ADDR must be in format host:port, got: %s", value)
	}
	if _, err := strconv.Atoi(parts[1]); err != nil {
		return fmt.Errorf("invalid port in LISTEN_ADDR: %s", parts[1])
	}
	return nil
}

func firstNonBlank(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
}
