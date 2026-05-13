package ai.brokk.util;

import ai.brokk.project.AbstractProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

public final class DebugLogPath {
    public static final String ARG = "debug-log-path";
    public static final String ENV_VAR = "BROKK_DEBUG_LOG_PATH";
    public static final String SYSTEM_PROPERTY = "brokk.debugLogPath";
    public static final String FILE_PATTERN_PROPERTY = "brokk.debugLogFilePattern";
    public static final String DELETE_BASE_PATH_PROPERTY = "brokk.debugLogDeleteBasePath";
    public static final String DELETE_GLOB_PROPERTY = "brokk.debugLogDeleteGlob";

    private static final String ROLLOVER_DATE_PATTERN = ".%d{yyyy-MM-dd}";

    private DebugLogPath() {}

    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"), AbstractProject.BROKK_DIR, AbstractProject.DEBUG_LOG_FILE);
    }

    public static Path currentPath() {
        var configured = System.getProperty(SYSTEM_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return defaultPath();
    }

    public static Path configureHeadless(Map<String, String> parsedArgs) throws IOException {
        var configuredPath = resolveHeadlessPath(parsedArgs);
        return configureHeadless(configuredPath);
    }

    public static Path configureHeadless(String[] args) throws IOException {
        var configuredPath = resolveHeadlessPath(args);
        return configureHeadless(configuredPath);
    }

    private static Path configureHeadless(@Nullable Path configuredPath) throws IOException {
        var debugLogPath = configuredPath != null ? configuredPath : defaultPath();
        configureLog4jProperties(debugLogPath);
        return debugLogPath;
    }

    @Nullable
    public static Path resolveHeadlessPath(Map<String, String> parsedArgs) {
        return resolveHeadlessPath(parsedArgs, System.getenv());
    }

    @TestOnly
    static @Nullable Path resolveHeadlessPath(Map<String, String> parsedArgs, Map<String, String> env) {
        var argValue = parsedArgs.get(ARG);
        if (argValue != null && !argValue.isBlank()) {
            return normalizePath(argValue);
        }

        var envValue = env.get(ENV_VAR);
        return resolveEnvOrProperty(envValue);
    }

    @Nullable
    private static Path resolveHeadlessPath(String[] args) {
        return resolveHeadlessPath(args, System.getenv());
    }

    @TestOnly
    static @Nullable Path resolveHeadlessPath(String[] args, Map<String, String> env) {
        var prefix = "--" + ARG;
        for (int i = 0; i < args.length; i++) {
            var arg = args[i];
            if (arg.equals(prefix) && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                var value = args[i + 1];
                if (!value.isBlank()) {
                    return normalizePath(value);
                }
            } else if (arg.startsWith(prefix + "=")) {
                var value = arg.substring(prefix.length() + 1);
                if (!value.isBlank()) {
                    return normalizePath(value);
                }
            }
        }

        return resolveEnvOrProperty(env.get(ENV_VAR));
    }

    private static @Nullable Path resolveEnvOrProperty(@Nullable String envValue) {
        if (envValue != null && !envValue.isBlank()) {
            return normalizePath(envValue);
        }

        var propertyValue = System.getProperty(SYSTEM_PROPERTY);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return normalizePath(propertyValue);
        }

        return null;
    }

    private static Path normalizePath(String rawPath) {
        return Path.of(rawPath).toAbsolutePath().normalize();
    }

    private static void configureLog4jProperties(Path rawDebugLogPath) throws IOException {
        var debugLogPath = rawDebugLogPath.toAbsolutePath().normalize();
        var fileName = debugLogPath.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("Debug log path must include a file name: " + debugLogPath);
        }

        var parent = debugLogPath.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Debug log path must include a parent directory: " + debugLogPath);
        }

        Files.createDirectories(parent);
        System.setProperty(SYSTEM_PROPERTY, debugLogPath.toString());
        System.setProperty(FILE_PATTERN_PROPERTY, debugLogPath + ROLLOVER_DATE_PATTERN);
        System.setProperty(DELETE_BASE_PATH_PROPERTY, parent.toString());
        System.setProperty(DELETE_GLOB_PROPERTY, fileName + ".*");
    }
}
