package config

import (
	"fmt"
	"regexp"
	"slices"
	"strconv"
	"strings"
)

var validArgs = []string{
	"exec-id",
	"listen-addr",
	"auth-token",
	"workspace-dir",
	"brokk-api-key",
	"proxy-setting",
	"vendor",
	"exit-on-stdin-eof",
	"help",
}

var uuidPattern = regexp.MustCompile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

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

type UsageError struct {
	Message  string
	ExitCode int
}

func (e *UsageError) Error() string {
	return strings.TrimSpace(e.Message)
}

type parseArgsResult struct {
	args        map[string]string
	invalidKeys []string
}

func Load(args []string, getenv func(string) string) (Config, error) {
	parsed := parseArgs(args)
	if len(parsed.invalidKeys) > 0 {
		return Config{}, &UsageError{
			Message:  fmt.Sprintf("Error: Unknown argument(s): %s\n\n%s", strings.Join(prefixKeys(parsed.invalidKeys), ", "), usageText()),
			ExitCode: 1,
		}
	}

	if _, ok := parsed.args["help"]; ok {
		return Config{}, &UsageError{Message: usageText(), ExitCode: 0}
	}

	cfg := Config{
		ExecID:       getConfigValue(parsed.args, "exec-id", "EXEC_ID", getenv),
		ListenAddr:   getConfigValue(parsed.args, "listen-addr", "LISTEN_ADDR", getenv),
		AuthToken:    getConfigValue(parsed.args, "auth-token", "AUTH_TOKEN", getenv),
		WorkspaceDir: getConfigValue(parsed.args, "workspace-dir", "WORKSPACE_DIR", getenv),
		BrokkAPIKey:  getConfigValue(parsed.args, "brokk-api-key", "BROKK_API_KEY", getenv),
		ProxySetting: getConfigValue(parsed.args, "proxy-setting", "PROXY_SETTING", getenv),
		Vendor:       getConfigValue(parsed.args, "vendor", "", getenv),
	}

	exitOnStdinEOF, err := getBooleanConfigValue(parsed.args, "exit-on-stdin-eof", "EXIT_ON_STDIN_EOF", false, getenv)
	if err != nil {
		return Config{}, err
	}
	cfg.ExitOnStdinEOF = exitOnStdinEOF

	if !LooksLikeUUID(cfg.ExecID) {
		return Config{}, fmt.Errorf("EXEC_ID must be a valid UUID")
	}
	if err := validateListenAddr(cfg.ListenAddr); err != nil {
		return Config{}, err
	}
	if strings.TrimSpace(cfg.AuthToken) == "" {
		return Config{}, fmt.Errorf("AUTH_TOKEN is required")
	}
	if strings.TrimSpace(cfg.WorkspaceDir) == "" {
		return Config{}, fmt.Errorf("WORKSPACE_DIR is required")
	}

	return cfg, nil
}

func LooksLikeUUID(value string) bool {
	return uuidPattern.MatchString(strings.TrimSpace(value))
}

func parseArgs(args []string) parseArgsResult {
	result := make(map[string]string)
	var invalid []string

	for i := 0; i < len(args); i++ {
		arg := args[i]
		if !strings.HasPrefix(arg, "--") {
			continue
		}

		withoutPrefix := strings.TrimPrefix(arg, "--")
		key := withoutPrefix
		value := ""

		if strings.Contains(withoutPrefix, "=") {
			parts := strings.SplitN(withoutPrefix, "=", 2)
			key = parts[0]
			if len(parts) > 1 {
				value = parts[1]
			}
		} else if i+1 < len(args) && !strings.HasPrefix(args[i+1], "--") {
			i++
			value = args[i]
		}

		if !slices.Contains(validArgs, key) {
			invalid = append(invalid, key)
			continue
		}
		result[key] = value
	}

	return parseArgsResult{args: result, invalidKeys: invalid}
}

func getConfigValue(parsedArgs map[string]string, argKey string, envVarName string, getenv func(string) string) string {
	if value, ok := parsedArgs[argKey]; ok && strings.TrimSpace(value) != "" {
		return value
	}
	if envVarName == "" {
		return ""
	}
	return getenv(envVarName)
}

func parseBooleanValue(rawValue string, sourceName string) (bool, error) {
	normalized := strings.ToLower(strings.TrimSpace(rawValue))
	switch normalized {
	case "", "1", "true", "yes", "on":
		return true, nil
	case "0", "false", "no", "off":
		return false, nil
	default:
		return false, fmt.Errorf("Invalid boolean value for %s: '%s'. Expected one of true/false, 1/0, yes/no, on/off.", sourceName, rawValue)
	}
}

func getBooleanConfigValue(parsedArgs map[string]string, argKey string, envVarName string, defaultValue bool, getenv func(string) string) (bool, error) {
	if value, ok := parsedArgs[argKey]; ok {
		return parseBooleanValue(value, "--"+argKey)
	}

	envValue := getenv(envVarName)
	if strings.TrimSpace(envValue) != "" {
		return parseBooleanValue(envValue, envVarName)
	}

	return defaultValue, nil
}

func validateListenAddr(listenAddr string) error {
	parts := strings.Split(listenAddr, ":")
	if len(parts) != 2 {
		return fmt.Errorf("LISTEN_ADDR must be in format host:port, got: %s", listenAddr)
	}
	if _, err := strconv.Atoi(parts[1]); err != nil {
		return fmt.Errorf("Invalid port in LISTEN_ADDR: %s", parts[1])
	}
	return nil
}

func prefixKeys(keys []string) []string {
	result := make([]string, 0, len(keys))
	for _, key := range keys {
		result = append(result, "--"+key)
	}
	return result
}

func usageText() string {
	return strings.TrimLeft(`
Usage: headless-executor [options]

Options:
  --exec-id <uuid>           Executor UUID (required)
  --listen-addr <host:port>  Address to listen on (required)
  --auth-token <token>       Authentication token (required)
  --workspace-dir <path>     Path to workspace directory (required)
  --brokk-api-key <key>      Brokk API key override (optional)
  --proxy-setting <setting>  LLM proxy: BROKK, LOCALHOST, STAGING (optional)
  --vendor <vendor>          Other-models vendor override (optional)
  --exit-on-stdin-eof[=bool] Exit when stdin closes/errors (default: false; env EXIT_ON_STDIN_EOF)
  --help                     Show this help message

Arguments can also be provided via environment variables:
  EXEC_ID, LISTEN_ADDR, AUTH_TOKEN, WORKSPACE_DIR, BROKK_API_KEY, PROXY_SETTING, EXIT_ON_STDIN_EOF
`, "\n")
}
