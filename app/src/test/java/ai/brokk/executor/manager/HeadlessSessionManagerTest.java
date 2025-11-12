package ai.brokk.executor.manager;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadlessSessionManagerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path repoPath;
    private Path worktreeBaseDir;
    private HeadlessSessionManager manager;
    private String authToken;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        repoPath = tempDir.resolve("test-repo");
        worktreeBaseDir = tempDir.resolve("worktrees");

        Files.createDirectories(repoPath);
        Files.createDirectories(worktreeBaseDir);

        initGitRepo(repoPath);

        authToken = "test-manager-token";
        var executorClasspath = System.getProperty("java.class.path");

        manager = new HeadlessSessionManager(
                UUID.randomUUID(), "127.0.0.1:0", authToken, 2, worktreeBaseDir, executorClasspath);

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
    void testCreateSession_Success() throws Exception {
        var requestBody = Map.of(
                "name", "Test Session",
                "repoPath", repoPath.toString());

        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(requestBody));
        }

        assertEquals(201, conn.getResponseCode());

        var response = OBJECT_MAPPER.readValue(conn.getInputStream(), new TypeReference<Map<String, Object>>() {});

        assertTrue(response.containsKey("sessionId"));
        assertTrue(response.containsKey("state"));
        assertTrue(response.containsKey("token"));

        assertEquals("ready", response.get("state"));

        var sessionId = (String) response.get("sessionId");
        assertDoesNotThrow(() -> UUID.fromString(sessionId));

        var token = (String) response.get("token");
        assertNotNull(token);
        assertFalse(token.isBlank());

        conn.disconnect();
    }

    @Test
    void testCreateSession_MissingName() throws Exception {
        var requestBody = Map.of("repoPath", repoPath.toString());

        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(requestBody));
        }

        assertEquals(400, conn.getResponseCode());
        conn.disconnect();
    }

    @Test
    void testCreateSession_MissingRepoPath() throws Exception {
        var requestBody = Map.of("name", "Test Session");

        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(requestBody));
        }

        assertEquals(400, conn.getResponseCode());
        conn.disconnect();
    }

    @Test
    void testCreateSession_PoolAtCapacity() throws Exception {
        Map<String, Object> requestBody1 = Map.of("name", "Session 1", "repoPath", repoPath.toString());
        Map<String, Object> requestBody2 = Map.of("name", "Session 2", "repoPath", repoPath.toString());
        var requestBody3 = Map.of("name", "Session 3", "repoPath", repoPath.toString());

        createSessionRequest(requestBody1);
        createSessionRequest(requestBody2);

        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(requestBody3));
        }

        assertEquals(429, conn.getResponseCode());

        var retryAfter = conn.getHeaderField("Retry-After");
        assertNotNull(retryAfter);
        assertTrue(Integer.parseInt(retryAfter) > 0);

        conn.disconnect();
    }

    @Test
    void testCreateSession_Unauthorized() throws Exception {
        var requestBody = Map.of("name", "Test Session", "repoPath", repoPath.toString());

        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(requestBody));
        }

        assertEquals(401, conn.getResponseCode());
        conn.disconnect();
    }

    private void createSessionRequest(Map<String, Object> requestBody) throws Exception {
        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(requestBody));
        }

        assertEquals(201, conn.getResponseCode());
        conn.disconnect();
    }

    @Test
    void testHealthReadyCapacity() throws Exception {
        // Initially should be ready (capacity available and provisioner healthy)
        var url = URI.create(baseUrl + "/health/ready").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        assertEquals(200, conn.getResponseCode());
        conn.disconnect();

        // Fill pool to capacity (pool size = 2 from setup)
        var requestBody1 = Map.<String, Object>of("name", "S1", "repoPath", repoPath.toString());
        var requestBody2 = Map.<String, Object>of("name", "S2", "repoPath", repoPath.toString());
        createSessionRequest(requestBody1);
        createSessionRequest(requestBody2);

        // Now readiness should report no capacity
        var conn2 = (HttpURLConnection) url.openConnection();
        conn2.setRequestMethod("GET");
        conn2.setRequestProperty("Authorization", "Bearer " + authToken);
        assertEquals(503, conn2.getResponseCode());
        conn2.disconnect();
    }

    @Test
    void testIdleEvictionPolicy() throws Exception {
        // Restart manager with very short idle timeout and eviction interval
        if (manager != null) {
            manager.stop(1);
        }
        var executorClasspath = System.getProperty("java.class.path");
        manager = new HeadlessSessionManager(
                UUID.randomUUID(),
                "127.0.0.1:0",
                authToken,
                1,
                worktreeBaseDir,
                executorClasspath,
                Duration.ofMillis(200),
                Duration.ofMillis(100));
        manager.start();
        baseUrl = "http://127.0.0.1:" + manager.getPort();

        // Create one session (fills capacity)
        var requestBody = Map.<String, Object>of("name", "Idle Evict Session", "repoPath", repoPath.toString());
        createSessionRequest(requestBody);

        // Shortly after, readiness should show 503 (no capacity)
        var readyUrl = URI.create(baseUrl + "/health/ready").toURL();
        var beforeEvict = (HttpURLConnection) readyUrl.openConnection();
        beforeEvict.setRequestMethod("GET");
        beforeEvict.setRequestProperty("Authorization", "Bearer " + authToken);
        assertEquals(503, beforeEvict.getResponseCode());
        beforeEvict.disconnect();

        // Wait for idle eviction to run
        Thread.sleep(1000);

        // After eviction, readiness should return 200 (capacity available again)
        var afterEvict = (HttpURLConnection) readyUrl.openConnection();
        afterEvict.setRequestMethod("GET");
        afterEvict.setRequestProperty("Authorization", "Bearer " + authToken);
        assertEquals(200, afterEvict.getResponseCode());
        afterEvict.disconnect();
    }

    @Test
    void testProxyJobCreation() throws Exception {
        var sessionRequestBody = Map.of("name", "Job Test Session", "repoPath", repoPath.toString());

        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(sessionRequestBody));
        }

        assertEquals(201, conn.getResponseCode());

        var sessionResponse =
                OBJECT_MAPPER.readValue(conn.getInputStream(), new TypeReference<Map<String, Object>>() {});
        var sessionToken = (String) sessionResponse.get("token");
        var sessionId = (String) sessionResponse.get("sessionId");

        conn.disconnect();

        var jobSpec = Map.<String, Object>of(
                "sessionId",
                sessionId,
                "taskInput",
                "echo test",
                "autoCommit",
                false,
                "autoCompress",
                false,
                "plannerModel",
                "gemini-2.0-flash");

        var jobUrl = URI.create(baseUrl + "/v1/jobs").toURL();
        var jobConn = (HttpURLConnection) jobUrl.openConnection();
        jobConn.setRequestMethod("POST");
        jobConn.setRequestProperty("Authorization", "Bearer " + sessionToken);
        jobConn.setRequestProperty("Content-Type", "application/json");
        jobConn.setRequestProperty("Idempotency-Key", "test-job-via-manager");
        jobConn.setDoOutput(true);

        try (OutputStream os = jobConn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(jobSpec));
        }

        assertEquals(201, jobConn.getResponseCode());

        var jobResponse = OBJECT_MAPPER.readValue(jobConn.getInputStream(), new TypeReference<Map<String, Object>>() {});

        assertTrue(jobResponse.containsKey("jobId"));
        assertTrue(jobResponse.containsKey("state"));

        var jobId = (String) jobResponse.get("jobId");
        assertNotNull(jobId);
        assertFalse(jobId.isBlank());

        jobConn.disconnect();
    }

    @Test
    void testProxyJobStatus() throws Exception {
        var sessionRequestBody = Map.of("name", "Status Test Session", "repoPath", repoPath.toString());

        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(sessionRequestBody));
        }

        assertEquals(201, conn.getResponseCode());

        var sessionResponse =
                OBJECT_MAPPER.readValue(conn.getInputStream(), new TypeReference<Map<String, Object>>() {});
        var sessionToken = (String) sessionResponse.get("token");
        var sessionId = (String) sessionResponse.get("sessionId");

        conn.disconnect();

        var jobSpec = Map.<String, Object>of(
                "sessionId",
                sessionId,
                "taskInput",
                "echo status test",
                "autoCommit",
                false,
                "autoCompress",
                false,
                "plannerModel",
                "gemini-2.0-flash");

        var jobUrl = URI.create(baseUrl + "/v1/jobs").toURL();
        var jobConn = (HttpURLConnection) jobUrl.openConnection();
        jobConn.setRequestMethod("POST");
        jobConn.setRequestProperty("Authorization", "Bearer " + sessionToken);
        jobConn.setRequestProperty("Content-Type", "application/json");
        jobConn.setRequestProperty("Idempotency-Key", "test-job-status");
        jobConn.setDoOutput(true);

        try (OutputStream os = jobConn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(jobSpec));
        }

        assertEquals(201, jobConn.getResponseCode());

        var jobResponse = OBJECT_MAPPER.readValue(jobConn.getInputStream(), new TypeReference<Map<String, Object>>() {});
        var jobId = (String) jobResponse.get("jobId");

        jobConn.disconnect();

        var statusUrl = URI.create(baseUrl + "/v1/jobs/" + jobId).toURL();
        var statusConn = (HttpURLConnection) statusUrl.openConnection();
        statusConn.setRequestMethod("GET");
        statusConn.setRequestProperty("Authorization", "Bearer " + sessionToken);

        assertEquals(200, statusConn.getResponseCode());

        var statusResponse =
                OBJECT_MAPPER.readValue(statusConn.getInputStream(), new TypeReference<Map<String, Object>>() {});

        assertTrue(statusResponse.containsKey("state"));

        statusConn.disconnect();
    }

    @Test
    void testProxyJobEvents() throws Exception {
        var sessionRequestBody = Map.of("name", "Events Test Session", "repoPath", repoPath.toString());

        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(sessionRequestBody));
        }

        assertEquals(201, conn.getResponseCode());

        var sessionResponse =
                OBJECT_MAPPER.readValue(conn.getInputStream(), new TypeReference<Map<String, Object>>() {});
        var sessionToken = (String) sessionResponse.get("token");
        var sessionId = (String) sessionResponse.get("sessionId");

        conn.disconnect();

        var jobSpec = Map.<String, Object>of(
                "sessionId",
                sessionId,
                "taskInput",
                "echo events test",
                "autoCommit",
                false,
                "autoCompress",
                false,
                "plannerModel",
                "gemini-2.0-flash");

        var jobUrl = URI.create(baseUrl + "/v1/jobs").toURL();
        var jobConn = (HttpURLConnection) jobUrl.openConnection();
        jobConn.setRequestMethod("POST");
        jobConn.setRequestProperty("Authorization", "Bearer " + sessionToken);
        jobConn.setRequestProperty("Content-Type", "application/json");
        jobConn.setRequestProperty("Idempotency-Key", "test-job-events");
        jobConn.setDoOutput(true);

        try (OutputStream os = jobConn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(jobSpec));
        }

        assertEquals(201, jobConn.getResponseCode());

        var jobResponse = OBJECT_MAPPER.readValue(jobConn.getInputStream(), new TypeReference<Map<String, Object>>() {});
        var jobId = (String) jobResponse.get("jobId");

        jobConn.disconnect();

        var eventsUrl = URI.create(baseUrl + "/v1/jobs/" + jobId + "/events?after=-1").toURL();
        var eventsConn = (HttpURLConnection) eventsUrl.openConnection();
        eventsConn.setRequestMethod("GET");
        eventsConn.setRequestProperty("Authorization", "Bearer " + sessionToken);

        assertEquals(200, eventsConn.getResponseCode());

        var eventsResponse =
                OBJECT_MAPPER.readValue(eventsConn.getInputStream(), new TypeReference<Map<String, Object>>() {});

        assertTrue(eventsResponse.containsKey("events"));
        assertTrue(eventsResponse.containsKey("nextAfter"));

        eventsConn.disconnect();
    }

    @Test
    void testProxyJobCancel() throws Exception {
        var sessionRequestBody = Map.of("name", "Cancel Test Session", "repoPath", repoPath.toString());

        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(sessionRequestBody));
        }

        assertEquals(201, conn.getResponseCode());

        var sessionResponse =
                OBJECT_MAPPER.readValue(conn.getInputStream(), new TypeReference<Map<String, Object>>() {});
        var sessionToken = (String) sessionResponse.get("token");
        var sessionId = (String) sessionResponse.get("sessionId");

        conn.disconnect();

        var jobSpec = Map.<String, Object>of(
                "sessionId",
                sessionId,
                "taskInput",
                "echo cancel test",
                "autoCommit",
                false,
                "autoCompress",
                false,
                "plannerModel",
                "gemini-2.0-flash");

        var jobUrl = URI.create(baseUrl + "/v1/jobs").toURL();
        var jobConn = (HttpURLConnection) jobUrl.openConnection();
        jobConn.setRequestMethod("POST");
        jobConn.setRequestProperty("Authorization", "Bearer " + sessionToken);
        jobConn.setRequestProperty("Content-Type", "application/json");
        jobConn.setRequestProperty("Idempotency-Key", "test-job-cancel");
        jobConn.setDoOutput(true);

        try (OutputStream os = jobConn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(jobSpec));
        }

        assertEquals(201, jobConn.getResponseCode());

        var jobResponse = OBJECT_MAPPER.readValue(jobConn.getInputStream(), new TypeReference<Map<String, Object>>() {});
        var jobId = (String) jobResponse.get("jobId");

        jobConn.disconnect();

        var cancelUrl = URI.create(baseUrl + "/v1/jobs/" + jobId + "/cancel").toURL();
        var cancelConn = (HttpURLConnection) cancelUrl.openConnection();
        cancelConn.setRequestMethod("POST");
        cancelConn.setRequestProperty("Authorization", "Bearer " + sessionToken);

        assertEquals(202, cancelConn.getResponseCode());

        cancelConn.disconnect();
    }

    @Test
    void testProxyJobWithMasterTokenRejected() throws Exception {
        var sessionRequestBody = Map.of("name", "Master Token Test", "repoPath", repoPath.toString());

        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(sessionRequestBody));
        }

        assertEquals(201, conn.getResponseCode());

        var sessionResponse =
                OBJECT_MAPPER.readValue(conn.getInputStream(), new TypeReference<Map<String, Object>>() {});
        var sessionId = (String) sessionResponse.get("sessionId");

        conn.disconnect();

        var jobSpec = Map.<String, Object>of(
                "sessionId",
                sessionId,
                "taskInput",
                "echo test",
                "autoCommit",
                false,
                "autoCompress",
                false,
                "plannerModel",
                "gemini-2.0-flash");

        var jobUrl = URI.create(baseUrl + "/v1/jobs").toURL();
        var jobConn = (HttpURLConnection) jobUrl.openConnection();
        jobConn.setRequestMethod("POST");
        jobConn.setRequestProperty("Authorization", "Bearer " + authToken);
        jobConn.setRequestProperty("Content-Type", "application/json");
        jobConn.setRequestProperty("Idempotency-Key", "test-master-token-rejected");
        jobConn.setDoOutput(true);

        try (OutputStream os = jobConn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(jobSpec));
        }

        assertEquals(403, jobConn.getResponseCode());

        jobConn.disconnect();
    }

    @Test
    void testProxyJobDiff() throws Exception {
        var sessionRequestBody = Map.of("name", "Diff Test Session", "repoPath", repoPath.toString());

        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(sessionRequestBody));
        }

        assertEquals(201, conn.getResponseCode());

        var sessionResponse =
                OBJECT_MAPPER.readValue(conn.getInputStream(), new TypeReference<Map<String, Object>>() {});
        var sessionToken = (String) sessionResponse.get("token");
        var sessionId = (String) sessionResponse.get("sessionId");

        conn.disconnect();

        var jobSpec = Map.<String, Object>of(
                "sessionId",
                sessionId,
                "taskInput",
                "echo diff test",
                "autoCommit",
                false,
                "autoCompress",
                false,
                "plannerModel",
                "gemini-2.0-flash");

        var jobUrl = URI.create(baseUrl + "/v1/jobs").toURL();
        var jobConn = (HttpURLConnection) jobUrl.openConnection();
        jobConn.setRequestMethod("POST");
        jobConn.setRequestProperty("Authorization", "Bearer " + sessionToken);
        jobConn.setRequestProperty("Content-Type", "application/json");
        jobConn.setRequestProperty("Idempotency-Key", "test-job-diff");
        jobConn.setDoOutput(true);

        try (OutputStream os = jobConn.getOutputStream()) {
            os.write(OBJECT_MAPPER.writeValueAsBytes(jobSpec));
        }

        assertEquals(201, jobConn.getResponseCode());

        var jobResponse = OBJECT_MAPPER.readValue(jobConn.getInputStream(), new TypeReference<Map<String, Object>>() {});
        var jobId = (String) jobResponse.get("jobId");

        jobConn.disconnect();

        var diffUrl = URI.create(baseUrl + "/v1/jobs/" + jobId + "/diff").toURL();
        var diffConn = (HttpURLConnection) diffUrl.openConnection();
        diffConn.setRequestMethod("GET");
        diffConn.setRequestProperty("Authorization", "Bearer " + sessionToken);

        int statusCode = diffConn.getResponseCode();

        if (statusCode == 200) {
            var contentType = diffConn.getHeaderField("Content-Type");
            assertNotNull(contentType);
            assertTrue(contentType.startsWith("text/plain"), "Expected text/plain Content-Type, got: " + contentType);
        } else if (statusCode == 409) {
            var errorResponse =
                    OBJECT_MAPPER.readValue(diffConn.getErrorStream(), new TypeReference<Map<String, Object>>() {});
            assertEquals("NO_GIT", errorResponse.get("code"));
        } else {
            fail("Expected status 200 or 409, but got: " + statusCode);
        }

        diffConn.disconnect();
    }
}
