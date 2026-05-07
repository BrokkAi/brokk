package ai.brokk.cli;

import static java.util.Objects.requireNonNull;

import ai.brokk.project.MainProject;
import ai.brokk.project.ModelProperties;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Shared command-line argument parsing utilities for headless entry points
 * (HeadlessExecutorMain, AcpServerMain, DiagnosticsTimelineCli).
 */
public final class CliArgParser {
    private static final Logger logger = LogManager.getLogger(CliArgParser.class);

    private CliArgParser() {}

    /**
     * Result of parsing command-line arguments, including both parsed args and invalid keys.
     */
    public record ParseResult(Map<String, String> args, Set<String> invalidKeys) {}

    /**
     * Parse command-line arguments into a map of normalized keys to values.
     * Supports both {@code --key value} and {@code --key=value} forms.
     */
    public static ParseResult parse(String[] args, Set<String> validArgs) {
        var result = new HashMap<String, String>();
        var invalidKeys = new HashSet<String>();
        for (int i = 0; i < args.length; i++) {
            var arg = args[i];
            if (arg.startsWith("--")) {
                var withoutPrefix = arg.substring(2);
                String key;
                String value;

                if (withoutPrefix.contains("=")) {
                    var parts = withoutPrefix.split("=", 2);
                    key = parts[0];
                    value = parts.length > 1 ? parts[1] : "";
                } else {
                    key = withoutPrefix;
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        value = args[++i];
                    } else {
                        value = "";
                    }
                }

                if (validArgs.contains(key)) {
                    result.put(key, value);
                } else {
                    invalidKeys.add(key);
                }
            }
        }
        return new ParseResult(result, invalidKeys);
    }

    /**
     * Get configuration value from either parsed args or environment variable.
     * Returns null if both are absent or blank.
     */
    @Nullable
    public static String getConfigValue(Map<String, String> parsedArgs, String argKey, String envVarName) {
        var argValue = parsedArgs.get(argKey);
        if (argValue != null && !argValue.isBlank()) {
            return argValue;
        }
        var envValue = System.getenv(envVarName);
        return (envValue != null && !envValue.isBlank()) ? envValue : null;
    }

    /**
     * Create a copy of the parsed arguments map with sensitive values redacted.
     */
    public static Map<String, String> redactSensitiveArgs(Map<String, String> parsedArgs, Set<String> sensitiveKeys) {
        var redacted = new HashMap<>(parsedArgs);
        for (var key : sensitiveKeys) {
            if (redacted.containsKey(key)) {
                redacted.put(key, "[REDACTED]");
            }
        }
        return redacted;
    }

    /**
     * Format parsed args for logging (redacted).
     */
    public static String formatForLogging(Map<String, String> redactedArgs) {
        return redactedArgs.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    /**
     * Apply Brokk API key and proxy setting overrides from parsed args / environment.
     */
    public static void applyHeadlessOverrides(Map<String, String> parsedArgs) {
        var brokkApiKey = getConfigValue(parsedArgs, "brokk-api-key", "BROKK_API_KEY");
        if (brokkApiKey != null) {
            MainProject.setHeadlessBrokkApiKeyOverride(brokkApiKey);
            logger.info("Using executor-specific Brokk API key (length={})", brokkApiKey.length());
        }

        var proxySettingStr = getConfigValue(parsedArgs, "proxy-setting", "PROXY_SETTING");
        if (proxySettingStr != null) {
            var proxySetting = MainProject.LlmProxySetting.valueOf(proxySettingStr.toUpperCase(Locale.ROOT));
            MainProject.setHeadlessProxySettingOverride(proxySetting);
            logger.info("Using proxy setting: {}", proxySetting);
        }
    }

    /**
     * Apply vendor preference to a project, including model configuration and OAuth validation.
     *
     * @param vendorArg the raw vendor argument (may be null/blank)
     * @param project the project to configure
     */
    public static void applyVendorPreference(@Nullable String vendorArg, MainProject project) {
        if (vendorArg == null || vendorArg.isBlank()) return;

        var requestedVendor = vendorArg.trim();
        String canonicalVendor;
        if (ModelProperties.DEFAULT_VENDOR.equalsIgnoreCase(requestedVendor)) {
            canonicalVendor = ModelProperties.DEFAULT_VENDOR;
        } else {
            canonicalVendor = ModelProperties.getAvailableVendors().stream()
                    .filter(v -> v.equalsIgnoreCase(requestedVendor))
                    .findFirst()
                    .orElseThrow(() ->
                            new IllegalArgumentException("Invalid vendor: '" + requestedVendor + "'. Must be one of: "
                                    + ModelProperties.DEFAULT_VENDOR + ", "
                                    + String.join(", ", ModelProperties.getAvailableVendors())));
        }

        if (ModelProperties.DEFAULT_VENDOR.equals(canonicalVendor)) {
            for (var type : ModelProperties.ModelType.values()) {
                if (type != ModelProperties.ModelType.CODE && type != ModelProperties.ModelType.ARCHITECT) {
                    project.removeModelConfig(type);
                }
            }
            MainProject.setOtherModelsVendorPreference("");
            logger.info("Cleared other-models vendor preference and internal role overrides");
        } else {
            if (ModelProperties.CODEX_VENDOR.equals(canonicalVendor) && !MainProject.isOpenAiCodexOauthConnected()) {
                throw new IllegalArgumentException(
                        "OpenAI - Codex selected but Codex OAuth is not connected; connect/login first.");
            }
            var vendorModels = requireNonNull(
                    ModelProperties.getVendorModels(canonicalVendor),
                    "Vendor models unexpectedly null for " + canonicalVendor);
            vendorModels.forEach(project::setModelConfig);
            MainProject.setOtherModelsVendorPreference(canonicalVendor);
            logger.info("Applied other-models vendor preference: {}", canonicalVendor);
        }
    }
}
