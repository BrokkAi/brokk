package ai.brokk.executor;

import ai.brokk.BuildInfo;
import ai.brokk.ContextManager;
import ai.brokk.MainProject;
import ai.brokk.executor.http.SimpleHttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class HeadlessExecutorMain {
  private static final Logger logger = LogManager.getLogger(HeadlessExecutorMain.class);

  private final UUID execId;
  private final SimpleHttpServer server;
  private final ContextManager contextManager;
  private final Path workspaceDir;
  private final Path sessionsDir;

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

  /**
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
    this.workspaceDir = workspaceDir;
    this.sessionsDir = sessionsDir;

    // Parse listen address
    var parts = listenAddr.split(":");
    if (parts.length != 2) {
      throw new IllegalArgumentException("LISTEN_ADDR must be in format host:port, got: " + listenAddr);
    }
    var host = parts[0];
    int port;
    try {
      port = Integer.parseInt(parts[1]);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid port in LISTEN_ADDR: " + parts[1], e);
    }

    logger.info("Initializing HeadlessExecutorMain: execId={}, listen={}:{}, workspace={}", execId, host, port, workspaceDir);

    // Initialize ContextManager
    var project = new MainProject(workspaceDir);
    this.contextManager = new ContextManager(project);
    this.contextManager.createHeadless();

    // Create HTTP server with authentication
    this.server = new SimpleHttpServer(host, port, authToken, 4);

    // Register endpoints
    this.server.registerUnauthenticatedContext("/health/live", this::handleHealthLive);
    this.server.registerAuthenticatedContext("/v1/executor", this::handleExecutor);

    logger.info("HeadlessExecutorMain initialized successfully");
  }

  private void handleHealthLive(HttpExchange exchange) throws IOException {
    if (!exchange.getRequestMethod().equals("GET")) {
      SimpleHttpServer.sendErrorResponse(exchange, 405, "Method not allowed");
      return;
    }

    var response = Map.of(
        "execId", this.execId.toString(),
        "version", BuildInfo.version,
        "protocolVersion", 1);

    SimpleHttpServer.sendJsonResponse(exchange, response);
  }

  private void handleExecutor(HttpExchange exchange) throws IOException {
    if (!exchange.getRequestMethod().equals("GET")) {
      SimpleHttpServer.sendErrorResponse(exchange, 405, "Method not allowed");
      return;
    }

    var response = Map.of(
        "execId", this.execId.toString(),
        "version", BuildInfo.version,
        "protocolVersion", 1);

    SimpleHttpServer.sendJsonResponse(exchange, response);
  }

  public void start() {
    this.server.start();
    logger.info("HTTP server started");
  }

  public void stop(int delaySeconds) {
    try {
      this.contextManager.close();
    } catch (Exception e) {
      logger.warn("Error closing ContextManager", e);
    }
    this.server.stop(delaySeconds);
    logger.info("HeadlessExecutorMain stopped");
  }

  public static void main(String[] args) {
    try {
      // Parse command-line arguments
      var parsedArgs = parseArgs(args);

      // Get configuration from args or environment
      var execIdStr = getConfigValue(parsedArgs, "exec-id", "EXEC_ID");
      if (execIdStr == null || execIdStr.isBlank()) {
        throw new IllegalArgumentException("EXEC_ID must be provided via --exec-id argument or EXEC_ID environment variable");
      }
      var execId = UUID.fromString(execIdStr);

      var listenAddr = getConfigValue(parsedArgs, "listen-addr", "LISTEN_ADDR");
      if (listenAddr == null || listenAddr.isBlank()) {
        throw new IllegalArgumentException("LISTEN_ADDR must be provided via --listen-addr argument or LISTEN_ADDR environment variable");
      }

      var authToken = getConfigValue(parsedArgs, "auth-token", "AUTH_TOKEN");
      if (authToken == null || authToken.isBlank()) {
        throw new IllegalArgumentException("AUTH_TOKEN must be provided via --auth-token argument or AUTH_TOKEN environment variable");
      }

      var workspaceDirStr = getConfigValue(parsedArgs, "workspace-dir", "WORKSPACE_DIR");
      if (workspaceDirStr == null || workspaceDirStr.isBlank()) {
        throw new IllegalArgumentException("WORKSPACE_DIR must be provided via --workspace-dir argument or WORKSPACE_DIR environment variable");
      }
      var workspaceDir = Path.of(workspaceDirStr);

      var sessionsDirStr = getConfigValue(parsedArgs, "sessions-dir", "SESSIONS_DIR");
      var sessionsDir = sessionsDirStr != null && !sessionsDirStr.isBlank()
          ? Path.of(sessionsDirStr)
          : workspaceDir.resolve(".brokk").resolve("sessions");

      logger.info("Starting HeadlessExecutorMain with config: execId={}, listenAddr={}, workspaceDir={}, sessionsDir={}",
          execId, listenAddr, workspaceDir, sessionsDir);

      // Create and start executor
      var executor = new HeadlessExecutorMain(execId, listenAddr, authToken, workspaceDir, sessionsDir);
      executor.start();

      // Add shutdown hook
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
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
