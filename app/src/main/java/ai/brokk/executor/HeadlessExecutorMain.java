package ai.brokk.executor;

import ai.brokk.BuildInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.google.common.base.Splitter;

/**
 * HTTP host that delegates all business logic to HeadlessExecutorService.
 * Keeps request parsing/validation and response writing local to the HTTP layer.
 *
 * This refactored Main delegates HTTP serving to HeadlessExecutorHost which binds
 * a HeadlessExecutorService to a SimpleHttpServer. The CLI remains a thin single-executor entrypoint.
 */
public final class HeadlessExecutorMain {
    private static final Logger logger = LogManager.getLogger(HeadlessExecutorMain.class);

    private final UUID execId;
    private final HeadlessExecutorHost host;
    private final HeadlessExecutorService service;

    /**
     * Parse command-line arguments into a map of normalized keys to values.
     * Supports both --key value and --key=value forms.
     * Normalized keys: exec-id, listen-addr, auth-token, workspace-dir, sessions-dir.
     */
    private static Map<String, String> parseArgs(String[] args) {
        var result = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            var arg = args[i];
            if (arg.startsWith("--")) {
                var withoutPrefix = arg.substring(2);
                String key;
                String value;

                if (withoutPrefix.contains("=")) {
                    // Form: --key=value
                    var parts = withoutPrefix.split("=", 2);
                    key = parts[0];
                    value = parts.length > 1 ? parts[1] : "";
                } else {
                    // Form: --key value
                    key = withoutPrefix;
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        value = args[++i];
                    } else {
                        value = "";
                    }
                }

                // Normalize the key
                result.put(key, value);
            }
        }
        return result;
    }

    /*
     * Get configuration value from either parsed args or environment variable.
     * Returns null/blank only if both are absent.
     */
    private static String getConfigValue(Map<String, String> parsedArgs, String argKey, String envVarName) {
        var argValue = parsedArgs.get(argKey);
        if (argValue != null && !argValue.isBlank()) {
            return argValue;
        }
        return System.getenv(envVarName);
    }

    public HeadlessExecutorMain(UUID execId, String listenAddr, String authToken, Path workspaceDir, Path sessionsDir)
            throws IOException {
        this.execId = execId;

        // Parse listen address
        var parts = Splitter.on(':').splitToList(listenAddr);
        if (parts.size() != 2) {
            throw new IllegalArgumentException("LISTEN_ADDR must be in format host:port, got: " + listenAddr);
        }
        var listenHost = parts.get(0);
        int port;
        try {
            port = Integer.parseInt(parts.get(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port in LISTEN_ADDR: " + parts.get(1), e);
        }

        logger.info(
                "Initializing HeadlessExecutorMain: execId={}, listen={}:{}, workspace={}",
                execId,
                listenHost,
                port,
                workspaceDir);

        // Ensure sessions directory exists
        Files.createDirectories(sessionsDir);

        // Initialize service which owns ContextManager, SessionManager, JobStore and JobRunner
        this.service = new HeadlessExecutorService(execId, workspaceDir, sessionsDir);

        // Create host that binds the service to an HTTP server
        this.host = new HeadlessExecutorHost(this.service, listenHost, port, Objects.requireNonNull(authToken), 4);

        logger.info("HeadlessExecutorMain initialized successfully");
    }

    public void start() {
        this.host.start();
        logger.info("HeadlessExecutorMain HTTP server started");
    }

    public void stop(int delaySeconds) {
        try {
            this.service.close();
        } catch (Exception e) {
            logger.warn("Error closing HeadlessExecutorService", e);
        }
        this.host.stop(delaySeconds);
        logger.info("HeadlessExecutorMain stopped");
    }

    /**
     * Return the actual port the server is bound to.
     *
     * @return the listening port number
     */
    public int getPort() {
        return this.host.getPort();
    }

    public static void main(String[] args) {
        try {
            // Parse command-line arguments
            var parsedArgs = parseArgs(args);

            // Get configuration from args or environment
            var execIdStr = getConfigValue(parsedArgs, "exec-id", "EXEC_ID");
            if (execIdStr.isBlank()) {
                throw new IllegalArgumentException(
                        "EXEC_ID must be provided via --exec-id argument or EXEC_ID environment variable");
            }
            var execId = UUID.fromString(execIdStr);

            var listenAddr = getConfigValue(parsedArgs, "listen-addr", "LISTEN_ADDR");
            if (listenAddr.isBlank()) {
                throw new IllegalArgumentException(
                        "LISTEN_ADDR must be provided via --listen-addr argument or LISTEN_ADDR environment variable");
            }

            var authToken = getConfigValue(parsedArgs, "auth-token", "AUTH_TOKEN");
            if (authToken.isBlank()) {
                throw new IllegalArgumentException(
                        "AUTH_TOKEN must be provided via --auth-token argument or AUTH_TOKEN environment variable");
            }

            var workspaceDirStr = getConfigValue(parsedArgs, "workspace-dir", "WORKSPACE_DIR");
            if (workspaceDirStr.isBlank()) {
                throw new IllegalArgumentException(
                        "WORKSPACE_DIR must be provided via --workspace-dir argument or WORKSPACE_DIR environment variable");
            }
            var workspaceDir = Path.of(workspaceDirStr);

            var sessionsDirStr = getConfigValue(parsedArgs, "sessions-dir", "SESSIONS_DIR");
            var sessionsDir = !sessionsDirStr.isBlank()
                    ? Path.of(sessionsDirStr)
                    : workspaceDir.resolve(".brokk").resolve("sessions");

            logger.info(
                    "Starting HeadlessExecutorMain with config: execId={}, listenAddr={}, workspaceDir={}, sessionsDir={}",
                    execId,
                    listenAddr,
                    workspaceDir,
                    sessionsDir);

            // Create and start executor (service + host)
            var executor = new HeadlessExecutorMain(execId, listenAddr, authToken, workspaceDir, sessionsDir);
            executor.start();

            // Add shutdown hook
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                logger.info("Shutdown signal received, stopping executor");
                                executor.stop(5);
                            },
                            "HeadlessExecutor-ShutdownHook"));

            logger.info("HeadlessExecutorMain is running");
            Thread.currentThread().join(); // Keep the main thread alive
        } catch (InterruptedException e) {
            logger.info("HeadlessExecutorMain interrupted", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Fatal error in HeadlessExecutorMain", e);
            System.exit(1);
        }
    }
}
