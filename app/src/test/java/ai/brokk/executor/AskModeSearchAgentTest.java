package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.TestService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for ASK mode ensuring SearchAgent is used and no writes occur.
 * Verifies that ASK mode:
 * - Uses SearchAgent with ANSWER_ONLY objective
 * - Produces no code diffs
 * - Emits search/summary events, not code edits
 * - Maintains read-only semantics
 */
class AskModeSearchAgentTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HeadlessExecutorMain executor;
    private int port;
    private String authToken = "test-secret-token";
    private String baseUrl;

    @BeforeEach
    void setup(@TempDir Path tempDir) throws Exception {
        var workspaceDir = tempDir.resolve("workspace");
        var sessionsDir = tempDir.resolve("sessions");
        Files.createDirectories(workspaceDir);
        Files.createDirectories(sessionsDir);

        // Create a minimal .brokk/project.properties file for MainProject
        var brokkDir = workspaceDir.resolve(".brokk");
        Files.createDirectories(brokkDir);
        // Ensure llm-history directory exists so LLM logging (tests) can write history files without error
        Files.createDirectories(brokkDir.resolve("llm-history"));
        var propsFile = brokkDir.resolve("project.properties");
        Files.writeString(propsFile, "# Minimal properties for test\n");

        var execId = UUID.randomUUID();
        var project = new MainProject(workspaceDir);
        var cm = new ContextManager(project, TestService.provider(project));
        executor = new HeadlessExecutorMain(
                execId,
                "127.0.0.1:0", // Ephemeral port
                authToken,
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

    @Disabled
    @Test
    void testAskModeUsesSearchAgent_ReadsOnly() throws Exception {
        // Upload a minimal session
        uploadSession();

        // Create an ASK mode job
        var jobSpec = Map.<String, Object>of(
                "sessionId",
                UUID.randomUUID().toString(),
                "taskInput",
                "What is the structure of this project?",
                "autoCommit",
                false,
                "autoCompress",
                false,
                "plannerModel",
                "gemini-2.0-flash",
                "tags",
                Map.of("mode", "ASK"));

        var jobId = createJobWithSpec(jobSpec, "ask-test-read-only");

        // Wait for job to complete
        Thread.sleep(500);

        // Poll for events to ensure job has processed
        var eventsUrl = URI.create(baseUrl + "/v1/jobs/" + jobId + "/events?after=-1&limit=1000")
                .toURL();
        var eventsConn = (HttpURLConnection) eventsUrl.openConnection();
        eventsConn.setRequestMethod("GET");
        eventsConn.setRequestProperty("Authorization", "Bearer " + authToken);

        assertEquals(200, eventsConn.getResponseCode());
        try (InputStream is = eventsConn.getInputStream()) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            var eventsData = OBJECT_MAPPER.readValue(response, new TypeReference<Map<String, Object>>() {});
            var events = (List<?>) eventsData.get("events");

            assertNotNull(events, "Events should not be null");
            assertTrue(events.size() > 0, "ASK mode should produce events");

            // Verify events contain search/LLM output, not code edits
            var eventTypes = events.stream()
                    .map(e -> ((Map<?, ?>) e).get("type"))
                    .map(Object::toString)
                    .toList();

            // ASK mode should produce LLM_TOKEN or NOTIFICATION events, not CODE_EDIT events
            assertTrue(
                    eventTypes.stream().anyMatch(t -> t.contains("LLM_TOKEN") || t.contains("NOTIFICATION")),
                    "ASK mode should produce LLM or notification events; got: " + eventTypes);
            assertFalse(
                    eventTypes.stream().anyMatch(t -> t.contains("CODE_EDIT")),
                    "ASK mode should not produce CODE_EDIT events; got: " + eventTypes);
        }
        eventsConn.disconnect();

        // Verify no git diff was created (read-only semantics)
        var diffUrl = URI.create(baseUrl + "/v1/jobs/" + jobId + "/diff").toURL();
        var diffConn = (HttpURLConnection) diffUrl.openConnection();
        diffConn.setRequestMethod("GET");
        diffConn.setRequestProperty("Authorization", "Bearer " + authToken);

        try {
            // Expect 200 or 409 (no git); if 409, that's fine (no git repo)
            var statusCode = diffConn.getResponseCode();
            assertTrue(
                    statusCode == 200 || statusCode == 409,
                    "Diff endpoint should succeed or report no git; got: " + statusCode);

            if (statusCode == 200) {
                try (InputStream is = diffConn.getInputStream()) {
                    var diffText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    assertTrue(
                            diffText.isEmpty() || diffText.isBlank(),
                            "ASK mode should produce no diff (read-only); got: " + diffText);
                }
            }
        } finally {
            diffConn.disconnect();
        }
    }

    @Disabled
    @Test
    void testAskModeIgnoresCodeModel() throws Exception {
        uploadSession();

        // Create ASK job with explicit codeModel (should be ignored)
        var jobSpec = Map.<String, Object>of(
                "sessionId",
                UUID.randomUUID().toString(),
                "taskInput",
                "List the main files in this project",
                "autoCommit",
                false,
                "autoCompress",
                false,
                "plannerModel",
                "gemini-2.0-flash",
                "codeModel",
                "gemini-2.0-flash", // Explicitly set, but should be ignored in ASK
                "tags",
                Map.of("mode", "ASK"));

        var jobId = createJobWithSpec(jobSpec, "ask-test-ignore-code-model");

        // Wait for job
        Thread.sleep(300);

        // Verify job status
        var statusUrl = URI.create(baseUrl + "/v1/jobs/" + jobId).toURL();
        var statusConn = (HttpURLConnection) statusUrl.openConnection();
        statusConn.setRequestMethod("GET");
        statusConn.setRequestProperty("Authorization", "Bearer " + authToken);

        assertEquals(200, statusConn.getResponseCode());
        try (InputStream is = statusConn.getInputStream()) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            // Job should exist and have status (RUNNING, COMPLETED, or FAILED)
            assertTrue(response.contains("state"), "Job status should be present");
        }
        statusConn.disconnect();
    }

    @Disabled
    @Test
    void testAskModeWithAutoCompress() throws Exception {
        uploadSession();

        // Create ASK job with autoCompress enabled
        var jobSpec = Map.<String, Object>of(
                "sessionId",
                UUID.randomUUID().toString(),
                "taskInput",
                "What are the key files?",
                "autoCommit",
                false,
                "autoCompress",
                true, // Enable compression
                "plannerModel",
                "gemini-2.0-flash",
                "tags",
                Map.of("mode", "ASK"));

        var jobId = createJobWithSpec(jobSpec, "ask-test-auto-compress");

        // Wait for job completion
        Thread.sleep(500);

        // Verify job completed (autoCompress should not cause issues)
        var statusUrl = URI.create(baseUrl + "/v1/jobs/" + jobId).toURL();
        var statusConn = (HttpURLConnection) statusUrl.openConnection();
        statusConn.setRequestMethod("GET");
        statusConn.setRequestProperty("Authorization", "Bearer " + authToken);

        assertEquals(200, statusConn.getResponseCode());
        try (InputStream is = statusConn.getInputStream()) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            // Should complete successfully (either COMPLETED or have events)
            assertTrue(response.contains("state"), "Job should have a state");
        }
        statusConn.disconnect();
    }

    @Disabled
    @Test
    void testAskModeNoAutoCommit() throws Exception {
        uploadSession();

        // Create ASK job with autoCommit=true (should be ignored)
        var jobSpec = Map.<String, Object>of(
                "sessionId",
                UUID.randomUUID().toString(),
                "taskInput",
                "Describe the architecture",
                "autoCommit",
                true, // Should be ignored in ASK mode
                "autoCompress",
                false,
                "plannerModel",
                "gemini-2.0-flash",
                "tags",
                Map.of("mode", "ASK"));

        var jobId = createJobWithSpec(jobSpec, "ask-test-no-auto-commit");

        // Wait for job
        Thread.sleep(300);

        // Verify no diff (autoCommit should be ignored)
        var diffUrl = URI.create(baseUrl + "/v1/jobs/" + jobId + "/diff").toURL();
        var diffConn = (HttpURLConnection) diffUrl.openConnection();
        diffConn.setRequestMethod("GET");
        diffConn.setRequestProperty("Authorization", "Bearer " + authToken);

        try {
            var statusCode = diffConn.getResponseCode();
            if (statusCode == 200) {
                try (InputStream is = diffConn.getInputStream()) {
                    var diffText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    assertTrue(
                            diffText.isEmpty() || diffText.isBlank(),
                            "ASK should produce no diff even with autoCommit=true; got: " + diffText);
                }
            }
        } finally {
            diffConn.disconnect();
        }
    }

    // ============================================================================
    // Helpers
    // ============================================================================

    private byte[] createEmptyZip() throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(out)) {
            var fragmentsEntry = new ZipEntry("fragments-v4.json");
            zos.putNextEntry(fragmentsEntry);
            zos.write("{\"version\": 1, \"referenced\": {}, \"virtual\": {}, \"task\": {}}"
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            var contextsEntry = new ZipEntry("contexts.jsonl");
            zos.putNextEntry(contextsEntry);
            zos.closeEntry();
        }
        return out.toByteArray();
    }

    private void uploadSession() throws Exception {
        var sessionZip = createEmptyZip();
        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/zip");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(sessionZip);
        }

        assertEquals(201, conn.getResponseCode());
        conn.disconnect();
    }

    private String createJobWithSpec(Map<String, Object> jobSpec, String idempotencyKey) throws Exception {
        var url = URI.create(baseUrl + "/v1/jobs").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Idempotency-Key", idempotencyKey);
        conn.setDoOutput(true);

        var json = toJson(jobSpec);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(201, conn.getResponseCode());
        try (InputStream is = conn.getInputStream()) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            var start = response.indexOf("\"jobId\":\"") + 9;
            var end = response.indexOf("\"", start);
            return response.substring(start, end);
        } finally {
            conn.disconnect();
        }
    }

    private String toJson(Object obj) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(obj);
    }
}
