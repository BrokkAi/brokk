package ai.brokk.executor.manager.exec;

import ai.brokk.executor.manager.provision.Provisioner;
import ai.brokk.executor.manager.provision.SessionSpec;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Manages a pool of HeadlessExecutorMain child processes, each serving a single session
 * in an isolated worktree environment.
 */
public final class ExecutorPool {
    private static final Logger logger = LogManager.getLogger(ExecutorPool.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int HEALTH_CHECK_TIMEOUT_SECONDS = 30;
    private static final int HEALTH_CHECK_POLL_INTERVAL_MS = 500;

    private final Provisioner provisioner;
    private final String executorClasspath;
    private final Map<UUID, ExecutorHandle> activeExecutors = new ConcurrentHashMap<>();

    /**
     * Create a new executor pool.
     *
     * @param provisioner the provisioner to create isolated workspaces
     * @param executorClasspath classpath for HeadlessExecutorMain (may contain multiple paths)
     */
    public ExecutorPool(Provisioner provisioner, String executorClasspath) {
        if (provisioner == null) {
            throw new IllegalArgumentException("provisioner must not be null");
        }
        if (executorClasspath == null || executorClasspath.isBlank()) {
            throw new IllegalArgumentException("executorClasspath must not be null or blank");
        }
        this.provisioner = provisioner;
        this.executorClasspath = executorClasspath;
    }

    /**
     * Spawn a new executor process for the given session.
     * This method provisions a workspace, allocates a port, generates credentials,
     * starts the process, and polls for readiness.
     *
     * @param spec the session specification for provisioning
     * @return the handle to the running executor
     * @throws ExecutorSpawnException if spawning fails
     */
    public ExecutorHandle spawn(SessionSpec spec) throws ExecutorSpawnException {
        var sessionId = spec.sessionId();

        var existing = activeExecutors.get(sessionId);
        if (existing != null) {
            logger.debug("Session {} already has an active executor at {}:{}", sessionId, existing.host(), existing.port());
            return existing;
        }

        logger.info("Spawning executor for session {}", sessionId);

        Path workspaceDir;
        try {
            workspaceDir = provisioner.provision(spec);
        } catch (Provisioner.ProvisionException e) {
            throw new ExecutorSpawnException("Failed to provision workspace for session " + sessionId, e);
        }

        var execId = UUID.randomUUID();
        var authToken = generateAuthToken();
        int port = allocateFreePort();
        var host = "127.0.0.1";
        var listenAddr = host + ":" + port;

        var command = buildCommand(execId, listenAddr, authToken, workspaceDir);

        Process process;
        try {
            var processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            process = processBuilder.start();
            logger.info("Started executor process for session {} (execId={}, pid={})", sessionId, execId, process.pid());

            consumeProcessOutput(process, sessionId);
        } catch (IOException e) {
            throw new ExecutorSpawnException("Failed to start executor process for session " + sessionId, e);
        }

        pollForReadiness(host, port, authToken, execId);

        var handle = new ExecutorHandle(sessionId, sessionId, execId, host, port, authToken, process, Instant.now());
        activeExecutors.put(sessionId, handle);

        logger.info("Executor for session {} is ready at {}:{}", sessionId, host, port);
        return handle;
    }

    /**
     * Get the handle for an active executor by session ID.
     *
     * @param sessionId the session ID
     * @return the executor handle, or null if not found
     */
    @Nullable
    public ExecutorHandle get(UUID sessionId) {
        return activeExecutors.get(sessionId);
    }

    /**
     * Update the session ID for an existing executor.
     * This is used when a temporary provision ID needs to be replaced with the actual session ID.
     *
     * @param oldSessionId the old session ID (provision ID)
     * @param newSessionId the new session ID (actual session ID)
     * @return true if the update succeeded, false if the old session ID was not found
     */
    public boolean updateSessionId(UUID oldSessionId, UUID newSessionId) {
        var handle = activeExecutors.remove(oldSessionId);
        if (handle == null) {
            logger.warn("Cannot update session ID: old session {} not found", oldSessionId);
            return false;
        }

        var updatedHandle = new ExecutorHandle(
                newSessionId,
                handle.provisionId(),
                handle.execId(),
                handle.host(),
                handle.port(),
                handle.authToken(),
                handle.process(),
                handle.lastActiveAt());

        activeExecutors.put(newSessionId, updatedHandle);
        logger.info("Updated session ID from {} to {}", oldSessionId, newSessionId);
        return true;
    }

    /**
     * Shutdown the executor for the given session.
     *
     * @param sessionId the session ID
     * @return true if an executor was shut down, false if no executor was found
     */
    public boolean shutdown(UUID sessionId) {
        var handle = activeExecutors.remove(sessionId);
        if (handle == null) {
            logger.debug("No active executor found for session {}", sessionId);
            return false;
        }

        logger.info("Shutting down executor for session {} (execId={})", sessionId, handle.execId());

        try {
            handle.process().destroy();
            if (!handle.process().waitFor(5, TimeUnit.SECONDS)) {
                logger.warn("Executor for session {} did not terminate gracefully, forcing kill", sessionId);
                handle.process().destroyForcibly();
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for executor to terminate", e);
            Thread.currentThread().interrupt();
        }

        try {
            provisioner.teardown(handle.provisionId());
        } catch (Provisioner.ProvisionException e) {
            logger.warn("Failed to tear down workspace for session {}", sessionId, e);
        }

        return true;
    }

    /**
     * Shutdown all active executors.
     */
    public void shutdownAll() {
        logger.info("Shutting down all active executors (count={})", activeExecutors.size());
        for (var sessionId : activeExecutors.keySet().stream().toList()) {
            shutdown(sessionId);
        }
    }

    /**
     * Get the count of active executors.
     *
     * @return the number of currently running executors
     */
    public int size() {
        return activeExecutors.size();
    }

    /**
     * Evict executors that have been idle for longer than the specified duration.
     *
     * @param maxIdle the maximum idle time before eviction
     * @return the number of executors evicted
     */
    public int evictIdle(Duration maxIdle) {
        var now = Instant.now();
        var threshold = now.minus(maxIdle);
        var toEvict = activeExecutors.entrySet().stream()
                .filter(entry -> threshold.isAfter(entry.getValue().lastActiveAt()))
                .map(Map.Entry::getKey)
                .toList();

        int evicted = 0;
        for (var sessionId : toEvict) {
            var handle = activeExecutors.get(sessionId);
            if (handle != null) {
                logger.info(
                        "Evicting idle executor for session {} (lastActiveAt={})",
                        sessionId,
                        handle.lastActiveAt());
                if (shutdown(sessionId)) {
                    evicted++;
                }
            }
        }

        return evicted;
    }

    /**
     * Update the lastActiveAt timestamp for the executor serving the given session.
     * This marks the executor as recently active, preventing idle eviction.
     *
     * @param sessionId the session ID
     */
    public void touch(UUID sessionId) {
        activeExecutors.computeIfPresent(sessionId, (id, handle) -> new ExecutorHandle(
                handle.sessionId(),
                handle.provisionId(),
                handle.execId(),
                handle.host(),
                handle.port(),
                handle.authToken(),
                handle.process(),
                Instant.now()));
    }

    private String generateAuthToken() {
        var bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private int allocateFreePort() throws ExecutorSpawnException {
        try (var socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new ExecutorSpawnException("Failed to allocate free port", e);
        }
    }

    private String[] buildCommand(UUID execId, String listenAddr, String authToken, Path workspaceDir) {
        return new String[] {
            "java",
            "-cp",
            executorClasspath,
            "ai.brokk.executor.HeadlessExecutorMain",
            "--exec-id",
            execId.toString(),
            "--listen-addr",
            listenAddr,
            "--auth-token",
            authToken,
            "--workspace-dir",
            workspaceDir.toString()
        };
    }

    private void consumeProcessOutput(Process process, UUID sessionId) {
        var outputReader = new Thread(
                () -> {
                    try (var reader = process.inputReader()) {
                        reader.lines().forEach(line -> logger.info("[executor:{}] {}", sessionId, line));
                    } catch (IOException e) {
                        logger.warn("Error consuming process output for session {}", sessionId, e);
                    }
                },
                "ExecutorOutput-" + sessionId);
        outputReader.setDaemon(true);
        outputReader.start();
    }

    private void pollForReadiness(String host, int port, String authToken, UUID execId)
            throws ExecutorSpawnException {
        var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        var healthUrl = URI.create("http://" + host + ":" + port + "/health/live");

        var deadline = Instant.now().plusSeconds(HEALTH_CHECK_TIMEOUT_SECONDS);

        while (Instant.now().isBefore(deadline)) {
            try {
                var request = HttpRequest.newBuilder(healthUrl)
                        .GET()
                        .timeout(Duration.ofSeconds(2))
                        .build();

                var response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    logger.info("Executor {} is ready at {}:{}", execId, host, port);
                    return;
                }

                logger.debug(
                        "Executor {} health check returned status {}, retrying...",
                        execId,
                        response.statusCode());
            } catch (IOException | InterruptedException e) {
                logger.debug("Executor {} not yet ready: {}", execId, e.getMessage());
            }

            try {
                Thread.sleep(HEALTH_CHECK_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ExecutorSpawnException("Interrupted while waiting for executor readiness", e);
            }
        }

        throw new ExecutorSpawnException("Executor " + execId + " did not become ready within "
                + HEALTH_CHECK_TIMEOUT_SECONDS + " seconds");
    }

    /**
     * Exception thrown when executor spawning fails.
     */
    public static final class ExecutorSpawnException extends Exception {
        public ExecutorSpawnException(String message) {
            super(message);
        }

        public ExecutorSpawnException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
