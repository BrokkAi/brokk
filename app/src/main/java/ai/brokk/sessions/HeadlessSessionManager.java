package ai.brokk.sessions;

import static java.util.Objects.requireNonNull;

import ai.brokk.util.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Manages lifecycle of headless executor processes.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Spawn HeadlessExecutorMain as a separate JVM process
 *   <li>Assign dynamic port and generate auth token
 *   <li>Parse stdout to discover actual bound port
 *   <li>Health check via HTTP ping
 *   <li>Shutdown and cleanup on session delete
 * </ul>
 */
public class HeadlessSessionManager {
    private static final Logger logger = LogManager.getLogger(HeadlessSessionManager.class);
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SHUTDOWN_GRACE_PERIOD = Duration.ofSeconds(5);
    private static final Pattern PORT_PATTERN = Pattern.compile("Listening on port (\\d+)");

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Spawns a new headless executor process for the given session.
     *
     * @param sessionId unique session identifier
     * @param worktreePath path to the worktree directory
     * @param sessionsDir directory for session storage (e.g., <worktree>/.brokk/sessions)
     * @return SessionInfo with process handle and assigned port
     * @throws IOException if process spawn fails
     * @throws InterruptedException if interrupted while waiting for startup
     */
    public SessionInfo spawnExecutor(UUID sessionId, Path worktreePath, Path sessionsDir)
            throws IOException, InterruptedException {

        var authToken = generateAuthToken();
        var javaHome = System.getProperty("java.home");
        var javaBin = Path.of(javaHome, "bin", "java").toString();
        var classpath = System.getProperty("java.class.path");

        requireNonNull(classpath, "java.class.path system property is null");

        var command =
                List.of(
                        javaBin,
                        "-cp",
                        classpath,
                        "ai.brokk.executor.HeadlessExecutorMain");

        var processBuilder = new ProcessBuilder(command);
        processBuilder.environment().put("EXEC_ID", sessionId.toString());
        processBuilder.environment().put("LISTEN_ADDR", "0.0.0.0:0");
        processBuilder.environment().put("AUTH_TOKEN", authToken);
        processBuilder.environment().put("WORKSPACE_DIR", worktreePath.toAbsolutePath().toString());
        processBuilder.environment().put("SESSIONS_DIR", sessionsDir.toAbsolutePath().toString());

        processBuilder.redirectErrorStream(true);

        logger.info(
                "Spawning executor for session {} with worktree {} and sessions dir {}",
                sessionId,
                worktreePath,
                sessionsDir);

        var process = processBuilder.start();

        try {
            var port = waitForPort(process);
            var now = System.currentTimeMillis();

            var sessionInfo =
                    new SessionInfo(
                            sessionId,
                            "Session " + sessionId.toString().substring(0, 8),
                            worktreePath,
                            "session/" + sessionId.toString().substring(0, 8),
                            port,
                            authToken,
                            process,
                            now,
                            now);

            healthCheck(sessionInfo);

            logger.info("Executor started successfully for session {} on port {}", sessionId, port);
            return sessionInfo;

        } catch (Exception e) {
            logger.error("Failed to start executor for session {}", sessionId, e);
            process.destroyForcibly();
            throw e;
        }
    }

    /**
     * Waits for the executor to start and parses the port from stdout.
     *
     * @param process the executor process
     * @return the port number the executor bound to
     * @throws IOException if reading stdout fails
     * @throws InterruptedException if interrupted while waiting
     */
    private int waitForPort(Process process) throws IOException, InterruptedException {
        var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        var future =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    logger.debug("Executor output: {}", line);
                                    var matcher = PORT_PATTERN.matcher(line);
                                    if (matcher.find()) {
                                        return Integer.parseInt(matcher.group(1));
                                    }
                                }
                                throw new IOException("Executor terminated without reporting port");
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });

        try {
            return future.get(STARTUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            throw new IOException("Failed to read port from executor within timeout", e);
        }
    }

    /**
     * Performs a health check by pinging the executor's /health/ready endpoint.
     *
     * @param sessionInfo the session to health check
     * @throws IOException if health check fails
     */
    private void healthCheck(SessionInfo sessionInfo) throws IOException {
        var url = "http://127.0.0.1:" + sessionInfo.port() + "/health/ready";
        var uri = URI.create(url);

        logger.debug("Health checking session {} at {}", sessionInfo.id(), url);

        var startTime = System.currentTimeMillis();
        var deadline = startTime + HEALTH_CHECK_TIMEOUT.toMillis();

        IOException lastException = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                var connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout((int) HEALTH_CHECK_TIMEOUT.toMillis());
                connection.setReadTimeout((int) HEALTH_CHECK_TIMEOUT.toMillis());

                var responseCode = connection.getResponseCode();
                if (responseCode == 200) {
                    logger.debug("Health check passed for session {}", sessionInfo.id());
                    return;
                }
                lastException = new IOException("Health check returned status " + responseCode);
            } catch (IOException e) {
                lastException = e;
                logger.debug(
                        "Health check attempt failed for session {}: {}",
                        sessionInfo.id(),
                        e.getMessage());
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during health check", e);
            }
        }

        throw new IOException(
                "Health check failed for session " + sessionInfo.id(), lastException);
    }

    /**
     * Shuts down an executor process gracefully, then forcibly if needed.
     *
     * @param sessionInfo the session to shut down
     */
    public void shutdownExecutor(SessionInfo sessionInfo) {
        var process = sessionInfo.process();
        if (!process.isAlive()) {
            logger.info("Executor for session {} already terminated", sessionInfo.id());
            return;
        }

        logger.info("Shutting down executor for session {}", sessionInfo.id());

        process.destroy();

        try {
            var terminated = process.waitFor(SHUTDOWN_GRACE_PERIOD.toMillis(), TimeUnit.MILLISECONDS);
            if (!terminated) {
                logger.warn(
                        "Executor for session {} did not terminate gracefully, forcing",
                        sessionInfo.id());
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while waiting for executor shutdown", e);
            process.destroyForcibly();
        }

        logger.info("Executor for session {} terminated", sessionInfo.id());
    }

    /**
     * Generates a random authentication token.
     *
     * @return a base64-encoded random token
     */
    private String generateAuthToken() {
        var bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
