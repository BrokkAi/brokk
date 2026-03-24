package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.brokk.ContextManager;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.TestService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration-style health readiness test for HeadlessExecutorMain.
 *
 * Verifies that:
 *  - /health/ready remains available as a deprecated compatibility endpoint
 *  - /health/ready mirrors liveness and includes session metadata
 */
@Disabled("Does not play nicely with async ContextFragments")
class HeadlessExecutorMainHealthTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HeadlessExecutorMain executor;
    private int port;
    private String baseUrl;
    private String authToken;

    @BeforeEach
    void setup(@TempDir Path tempDir) throws Exception {
        var workspaceDir = tempDir.resolve("workspace");
        var sessionsDir = tempDir.resolve("sessions");
        Files.createDirectories(workspaceDir);
        Files.createDirectories(sessionsDir);

        // Minimal .brokk/project.properties required by MainProject
        var brokkDir = workspaceDir.resolve(".brokk");
        Files.createDirectories(brokkDir);
        Files.writeString(brokkDir.resolve("project.properties"), "# test\n", StandardCharsets.UTF_8);

        var execId = UUID.randomUUID();
        this.authToken = "test-token"; // required for authenticated endpoints

        var project = new MainProject(workspaceDir);
        var cm = new ContextManager(project, TestService.provider(project));
        executor = new HeadlessExecutorMain(
                execId,
                "127.0.0.1:0", // Ephemeral port
                this.authToken,
                cm);

        executor.start();
        port = executor.getPort();
        baseUrl = "http://127.0.0.1:" + port;
    }

    @AfterEach
    void cleanup() {
        if (executor != null) {
            executor.stop(2);
        }
    }

    @Test
    void healthReady_beforeAndAfterSessionImport() throws Exception {
        // 1) Before any session exists -> expect 200 (compat alias of /health/live)
        {
            var url = URI.create(baseUrl + "/health/ready").toURL();
            var conn = withTimeouts((HttpURLConnection) url.openConnection());
            conn.setRequestMethod("GET");
            try {
                assertEquals(200, conn.getResponseCode());
                assertEquals("true", conn.getHeaderField("Deprecation"));
            } finally {
                conn.disconnect();
            }
        }

        // 2) Create a session via HTTP API
        {
            var url = URI.create(baseUrl + "/v1/sessions").toURL();
            var conn = withTimeouts((HttpURLConnection) url.openConnection());
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            var payload = OBJECT_MAPPER.writeValueAsString(Map.of("name", "Health Test Session"));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            assertEquals(201, conn.getResponseCode());
            try (InputStream is = conn.getInputStream()) {
                OBJECT_MAPPER.readValue(is, new TypeReference<Map<String, Object>>() {});
            } finally {
                conn.disconnect();
            }
        }

        // 3) After session is created -> expect 200 and sessionId in response
        {
            var url = URI.create(baseUrl + "/health/ready").toURL();
            var conn = withTimeouts((HttpURLConnection) url.openConnection());
            conn.setRequestMethod("GET");
            try {
                assertEquals(200, conn.getResponseCode());
                try (InputStream is = conn.getInputStream()) {
                    var map = OBJECT_MAPPER.readValue(is, new TypeReference<Map<String, Object>>() {});
                    assertEquals("ready", map.get("status"));
                    assertNotNull(map.get("sessionId"));
                }
            } finally {
                conn.disconnect();
            }
        }
    }

    @Test
    void postPrReview_validatesRequiredFields() throws Exception {
        // First create a session so the executor is ready
        {
            var url = URI.create(baseUrl + "/v1/sessions").toURL();
            var conn = withTimeouts((HttpURLConnection) url.openConnection());
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            var payload = OBJECT_MAPPER.writeValueAsString(Map.of("name", "PR Review Test Session"));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(201, conn.getResponseCode());
            conn.disconnect();
        }

        // Test missing owner
        {
            var url = URI.create(baseUrl + "/v1/jobs/pr-review").toURL();
            var conn = withTimeouts((HttpURLConnection) url.openConnection());
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Idempotency-Key", UUID.randomUUID().toString());
            conn.setDoOutput(true);
            var payload = OBJECT_MAPPER.writeValueAsString(Map.of(
                    "repo", "test-repo",
                    "prNumber", 42,
                    "githubToken", "ghp_test",
                    "plannerModel", "gpt-4"));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(400, conn.getResponseCode());
            conn.disconnect();
        }

        // Test missing Idempotency-Key header
        {
            var url = URI.create(baseUrl + "/v1/jobs/pr-review").toURL();
            var conn = withTimeouts((HttpURLConnection) url.openConnection());
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            var payload = OBJECT_MAPPER.writeValueAsString(Map.of(
                    "owner", "test-owner",
                    "repo", "test-repo",
                    "prNumber", 42,
                    "githubToken", "ghp_test",
                    "plannerModel", "gpt-4"));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(400, conn.getResponseCode());
            conn.disconnect();
        }
    }

    @Test
    void postPrReview_createsJobSuccessfully() throws Exception {
        // First create a session so the executor is ready
        {
            var url = URI.create(baseUrl + "/v1/sessions").toURL();
            var conn = withTimeouts((HttpURLConnection) url.openConnection());
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            var payload = OBJECT_MAPPER.writeValueAsString(Map.of("name", "PR Review Job Test Session"));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(201, conn.getResponseCode());
            conn.disconnect();
        }

        // Create a PR review job
        String jobId;
        {
            var url = URI.create(baseUrl + "/v1/jobs/pr-review").toURL();
            var conn = withTimeouts((HttpURLConnection) url.openConnection());
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Idempotency-Key", UUID.randomUUID().toString());
            conn.setDoOutput(true);
            var payload = OBJECT_MAPPER.writeValueAsString(Map.of(
                    "owner", "test-owner",
                    "repo", "test-repo",
                    "prNumber", 42,
                    "githubToken", "ghp_test_token",
                    "plannerModel", "gpt-4"));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(201, conn.getResponseCode());
            try (InputStream is = conn.getInputStream()) {
                var map = OBJECT_MAPPER.readValue(is, new TypeReference<Map<String, Object>>() {});
                jobId = (String) map.get("jobId");
                assertNotNull(jobId);
                assertFalse(jobId.isBlank());
            }
            conn.disconnect();
        }

        // Verify the job exists by fetching its status
        {
            var url = URI.create(baseUrl + "/v1/jobs/" + jobId).toURL();
            var conn = withTimeouts((HttpURLConnection) url.openConnection());
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
            assertEquals(200, conn.getResponseCode());
            try (InputStream is = conn.getInputStream()) {
                var map = OBJECT_MAPPER.readValue(is, new TypeReference<Map<String, Object>>() {});
                assertEquals(jobId, map.get("jobId"));
                // State should be one of queued, running, completed, or failed
                assertNotNull(map.get("state"));
            }
            conn.disconnect();
        }
    }

    @Test
    void postPrReview_idempotencyReturnsExistingJob() throws Exception {
        // First create a session so the executor is ready
        {
            var url = URI.create(baseUrl + "/v1/sessions").toURL();
            var conn = withTimeouts((HttpURLConnection) url.openConnection());
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            var payload = OBJECT_MAPPER.writeValueAsString(Map.of("name", "Idempotency Test Session"));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(201, conn.getResponseCode());
            conn.disconnect();
        }

        var idempotencyKey = UUID.randomUUID().toString();
        var requestPayload = OBJECT_MAPPER.writeValueAsString(Map.of(
                "owner", "test-owner",
                "repo", "test-repo",
                "prNumber", 123,
                "githubToken", "ghp_test",
                "plannerModel", "gpt-4"));

        // First request - should create the job
        String firstJobId;
        {
            var url = URI.create(baseUrl + "/v1/jobs/pr-review").toURL();
            var conn = withTimeouts((HttpURLConnection) url.openConnection());
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Idempotency-Key", idempotencyKey);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestPayload.getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(201, conn.getResponseCode());
            try (InputStream is = conn.getInputStream()) {
                var map = OBJECT_MAPPER.readValue(is, new TypeReference<Map<String, Object>>() {});
                firstJobId = (String) map.get("jobId");
            }
            conn.disconnect();
        }

        // Second request with same idempotency key - should return existing job
        {
            var url = URI.create(baseUrl + "/v1/jobs/pr-review").toURL();
            var conn = withTimeouts((HttpURLConnection) url.openConnection());
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Idempotency-Key", idempotencyKey);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestPayload.getBytes(StandardCharsets.UTF_8));
            }
            // Should return 200 for existing job, not 201
            assertEquals(200, conn.getResponseCode());
            try (InputStream is = conn.getInputStream()) {
                var map = OBJECT_MAPPER.readValue(is, new TypeReference<Map<String, Object>>() {});
                var secondJobId = (String) map.get("jobId");
                assertEquals(firstJobId, secondJobId, "Same idempotency key should return same job ID");
            }
            conn.disconnect();
        }
    }

    // Helpers

    private static HttpURLConnection withTimeouts(HttpURLConnection conn) {
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(5000);
        return conn;
    }
}
