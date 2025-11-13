package ai.brokk.executor.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadlessSessionManagerIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path repoPath;
    private Path worktreeBaseDir;
    private HeadlessSessionManager manager;
    private String masterAuthToken;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        repoPath = tempDir.resolve("test-repo");
        worktreeBaseDir = tempDir.resolve("worktrees");

        Files.createDirectories(repoPath);
        Files.createDirectories(worktreeBaseDir);

        initGitRepo(repoPath);

        masterAuthToken = "test-manager-token";
        var executorClasspath = System.getProperty("java.class.path");

        manager = new HeadlessSessionManager(
                UUID.randomUUID(),
                "127.0.0.1:0",
                masterAuthToken,
                2, // Pool size
                worktreeBaseDir,
                executorClasspath,
                Duration.ofMinutes(1), // Short idle timeout for test
                Duration.ofSeconds(1) // Short eviction interval
                );

        manager.start();
        baseUrl = "http://127.0.0.1:" + manager.getPort();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop(2);
        }
    }

    private void initGitRepo(Path repoPath) throws Exception {
        exec(repoPath, "git", "init");
        exec(repoPath, "git", "config", "user.email", "test@example.com");
        exec(repoPath, "git", "config", "user.name", "Test User");

        var brokkDir = repoPath.resolve(".brokk");
        Files.createDirectories(brokkDir);
        Files.writeString(brokkDir.resolve("project.properties"), "# test project\n");

        exec(repoPath, "git", "add", ".brokk/project.properties");
        exec(repoPath, "git", "commit", "-m", "Initial commit");
    }

    private void exec(Path workingDir, String... command) throws Exception {
        var processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDir.toFile());
        processBuilder.redirectErrorStream(true);

        var process = processBuilder.start();
        var output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Command failed: " + String.join(" ", command) + "\nOutput: " + output);
        }
    }

    @Test
    void testFullLifecycle() throws Exception {
        // 1. Create a session
        var sessionRequest = Map.of("name", "Integration Test Session", "repoPath", repoPath.toString());
        var sessionResponse = postJson(baseUrl + "/v1/sessions", masterAuthToken, sessionRequest, 201);
        var sessionId = UUID.fromString((String) sessionResponse.get("sessionId"));
        var sessionToken = (String) sessionResponse.get("token");
        assertNotNull(sessionToken);

        // Verify worktree was created
        assertEquals(1, countChildDirectories(worktreeBaseDir));

        // 2. Create a job
        var jobRequest = Map.of("taskInput", "echo 'Hello from integration test'", "plannerModel", "test-model");
        var jobResponse = postJson(baseUrl + "/v1/jobs", sessionToken, jobRequest, 201, "integ-test-job-1");
        var jobId = (String) jobResponse.get("jobId");
        assertNotNull(jobId);

        // 3. Poll for completion
        var deadline = Instant.now().plusSeconds(30);
        String finalState = null;
        long lastEventSeq = -1;
        var allEvents = new java.util.ArrayList<Map<String, Object>>();
        while (Instant.now().isBefore(deadline)) {
            var statusResponse = getJson(baseUrl + "/v1/jobs/" + jobId, sessionToken, 200);
            finalState = (String) statusResponse.get("state");

            var eventsResponse =
                    getJson(baseUrl + "/v1/jobs/" + jobId + "/events?after=" + lastEventSeq, sessionToken, 200);

            @SuppressWarnings("unchecked")
            var events = (List<Map<String, Object>>) eventsResponse.get("events");

            if (events != null && !events.isEmpty()) {
                allEvents.addAll(events);
                lastEventSeq = ((Number) eventsResponse.get("nextAfter")).longValue();
            }

            if ("SUCCEEDED".equals(finalState) || "FAILED".equals(finalState)) {
                break;
            }

            Thread.sleep(500);
        }
        assertEquals("FAILED", finalState, "Job should have failed due to unavailable model");

        // Verify that the failure was due to MODEL_UNAVAILABLE
        boolean modelUnavailableFound = allEvents.stream()
                .filter(e -> "error".equals(e.get("type")))
                .map(e -> (Map<?, ?>) e.get("payload"))
                .filter(java.util.Objects::nonNull)
                .anyMatch(p -> {
                    var msg = p.get("message");
                    var details = p.get("details");
                    return (msg instanceof String s && s.contains("MODEL_UNAVAILABLE"))
                            || (details instanceof String detailsStr && detailsStr.contains("MODEL_UNAVAILABLE"));
                });
        org.junit.jupiter.api.Assertions.assertTrue(
                modelUnavailableFound, "Job failure reason should be MODEL_UNAVAILABLE");

        // 4. Teardown session
        delete(baseUrl + "/v1/sessions/" + sessionId, masterAuthToken, 204);

        // 5. Verify cleanup
        // Allow some time for async shutdown and file cleanup
        Thread.sleep(1000);
        assertEquals(0, countChildDirectories(worktreeBaseDir), "Worktree directory was not cleaned up");
    }

    // HTTP helper methods
    private Map<String, Object> postJson(String url, String token, Object body, int expectedStatus)
            throws IOException, InterruptedException {
        return postJson(url, token, body, expectedStatus, null);
    }

    private Map<String, Object> postJson(
            String url, String token, Object body, int expectedStatus, String idempotencyKey)
            throws IOException, InterruptedException {
        var client = HttpClient.newHttpClient();
        var requestBuilder = HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10));

        if (idempotencyKey != null) {
            requestBuilder.header("Idempotency-Key", idempotencyKey);
        }

        var response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), "Unexpected status code. Body: " + response.body());
        if (response.body().isEmpty()) {
            return Map.of();
        }
        return OBJECT_MAPPER.readValue(response.body(), new TypeReference<>() {});
    }

    private Map<String, Object> getJson(String url, String token, int expectedStatus)
            throws IOException, InterruptedException {
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(5))
                .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), "Unexpected status code. Body: " + response.body());
        return OBJECT_MAPPER.readValue(response.body(), new TypeReference<>() {});
    }

    private void delete(String url, String token, int expectedStatus) throws IOException, InterruptedException {
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create(url))
                .DELETE()
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(10))
                .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), "Unexpected status code. Body: " + response.body());
    }

    private int countChildDirectories(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (var stream = Files.list(dir)) {
            return (int) stream.filter(Files::isDirectory).count();
        }
    }
}
