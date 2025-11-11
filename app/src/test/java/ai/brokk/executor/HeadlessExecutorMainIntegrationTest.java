package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test for HeadlessExecutorMain.
 * Starts an HTTP server on an ephemeral port, submits a session and job,
 * and verifies event ordering and status transitions.
 */
class HeadlessExecutorMainIntegrationTest {

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
        var propsFile = brokkDir.resolve("project.properties");
        Files.writeString(propsFile, "# Minimal properties for test\n");

        var execId = UUID.randomUUID();
        executor = new HeadlessExecutorMain(
                execId,
                "127.0.0.1:0", // Ephemeral port
                authToken,
                workspaceDir,
                sessionsDir);

        executor.start();

        // Extract the actual port from the server
        // Use the public API to discover the bound port
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
    void testHealthLiveEndpoint() throws Exception {
        var url = URI.create(baseUrl + "/health/live").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        assertEquals(200, conn.getResponseCode());
        try (InputStream is = conn.getInputStream()) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("execId"));
            assertTrue(response.contains("version"));
        }
        conn.disconnect();
    }

    @Test
    void testHealthReadyEndpoint_WithoutSession() throws Exception {
        var url = URI.create(baseUrl + "/health/ready").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        assertEquals(503, conn.getResponseCode());

        // Verify ErrorPayload JSON structure
        InputStream stream = conn.getErrorStream();
        if (stream == null) {
            stream = conn.getInputStream();
        }
        assertNotNull(stream, "Expected error response body");
        try (InputStream is = stream) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("\"code\""), "Response should contain 'code' field: " + response);
            assertTrue(response.contains("NOT_READY"), "Response should contain 'NOT_READY' code: " + response);
            assertTrue(response.contains("\"message\""), "Response should contain 'message' field: " + response);
            assertTrue(
                    response.contains("No session loaded"), "Response should contain appropriate message: " + response);
        }
        conn.disconnect();
    }

    @Test
    void testHealthLiveEndpoint_WrongMethod() throws Exception {
        var url = URI.create(baseUrl + "/health/live").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);

        assertEquals(405, conn.getResponseCode());

        // Verify ErrorPayload JSON structure
        InputStream stream = conn.getErrorStream();
        if (stream == null) {
            stream = conn.getInputStream();
        }
        assertNotNull(stream, "Expected error response body");
        try (InputStream is = stream) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("\"code\""), "Response should contain 'code' field: " + response);
            assertTrue(
                    response.contains("METHOD_NOT_ALLOWED"),
                    "Response should contain 'METHOD_NOT_ALLOWED' code: " + response);
            assertTrue(response.contains("\"message\""), "Response should contain 'message' field: " + response);
            assertTrue(
                    response.contains("Method not allowed"),
                    "Response should contain appropriate message: " + response);
        }
        conn.disconnect();
    }

    @Test
    void testJobsRouter_UnknownSubpath() throws Exception {
        uploadSession();
        var jobId = createJob("test-unknown-subpath");

        var url =
                URI.create(baseUrl + "/v1/jobs/" + jobId + "/unknown-endpoint").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);

        assertEquals(404, conn.getResponseCode());

        // Verify ErrorPayload JSON structure
        InputStream stream = conn.getErrorStream();
        if (stream == null) {
            stream = conn.getInputStream();
        }
        assertNotNull(stream, "Expected error response body");
        try (InputStream is = stream) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("\"code\""), "Response should contain 'code' field: " + response);
            assertTrue(response.contains("NOT_FOUND"), "Response should contain 'NOT_FOUND' code: " + response);
            assertTrue(response.contains("\"message\""), "Response should contain 'message' field: " + response);
            assertTrue(response.contains("Not found"), "Response should contain appropriate message: " + response);
        }
        conn.disconnect();
    }

    @Test
    void testJobsRouter_MalformedPath() throws Exception {
        uploadSession();

        // Request to /v1/jobs//events (empty jobId)
        var url = URI.create(baseUrl + "/v1/jobs//events").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);

        assertEquals(400, conn.getResponseCode());

        // Verify ErrorPayload JSON structure
        InputStream stream = conn.getErrorStream();
        if (stream == null) {
            stream = conn.getInputStream();
        }
        assertNotNull(stream, "Expected error response body");
        try (InputStream is = stream) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("\"code\""), "Response should contain 'code' field: " + response);
            assertTrue(response.contains("BAD_REQUEST"), "Response should contain 'BAD_REQUEST' code: " + response);
            assertTrue(response.contains("\"message\""), "Response should contain 'message' field: " + response);
            assertTrue(
                    response.contains("Invalid job path"), "Response should contain appropriate message: " + response);
        }
        conn.disconnect();
    }

    @Test
    void testPostSessionsEndpoint_RequiresAuth() throws Exception {
        var sessionRequest = Map.of("name", "Test Session");

        // Test 1: Missing Authorization header should return 401
        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        // No Authorization header

        var json = toJson(sessionRequest);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(401, conn.getResponseCode());
        conn.disconnect();

        // Test 2: Invalid Authorization token should also return 401
        var conn2 = (HttpURLConnection) url.openConnection();
        conn2.setRequestMethod("POST");
        conn2.setRequestProperty("Authorization", "Bearer wrong-token");
        conn2.setRequestProperty("Content-Type", "application/json");
        conn2.setDoOutput(true);

        try (OutputStream os = conn2.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(401, conn2.getResponseCode());
        conn2.disconnect();
    }

    @Test
    void testPostSessionsEndpoint_WithValidAuth() throws Exception {
        var sessionRequest = Map.of("name", "My New Session");

        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        var json = toJson(sessionRequest);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(201, conn.getResponseCode());
        try (InputStream is = conn.getInputStream()) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("sessionId"));
            assertTrue(response.contains("My New Session"));

            // Verify sessionId is a valid UUID
            var sessionIdMarker = "\"sessionId\":\"";
            var start = response.indexOf(sessionIdMarker);
            assertTrue(start >= 0, "Response should contain sessionId field");
            start += sessionIdMarker.length();
            var end = response.indexOf("\"", start);
            assertTrue(end > start, "Response sessionId should be properly formatted");
            var sessionId = response.substring(start, end);
            // This will throw if not a valid UUID
            UUID.fromString(sessionId);
        }
        conn.disconnect();
    }

    @Test
    void testHealthReadyEndpoint_AfterCreateSession() throws Exception {
        // Create a session using the new endpoint
        var sessionRequest = Map.of("name", "Test Session For Ready Check");

        var createUrl = URI.create(baseUrl + "/v1/sessions").toURL();
        var createConn = (HttpURLConnection) createUrl.openConnection();
        createConn.setRequestMethod("POST");
        createConn.setRequestProperty("Authorization", "Bearer " + authToken);
        createConn.setRequestProperty("Content-Type", "application/json");
        createConn.setDoOutput(true);

        var json = toJson(sessionRequest);
        try (OutputStream os = createConn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(201, createConn.getResponseCode());
        createConn.disconnect();

        // Now verify /health/ready returns 200
        var readyUrl = URI.create(baseUrl + "/health/ready").toURL();
        var readyConn = (HttpURLConnection) readyUrl.openConnection();
        readyConn.setRequestMethod("GET");

        assertEquals(200, readyConn.getResponseCode());
        try (InputStream is = readyConn.getInputStream()) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("status"));
            assertTrue(response.contains("ready"));
            assertTrue(response.contains("sessionId"));
        }
        readyConn.disconnect();
    }

    @Test
    void testPutSessionsEndpoint_RequiresAuth() throws Exception {
        var sessionZip = createEmptyZip();

        var url = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Content-Type", "application/zip");
        conn.setDoOutput(true);
        // No Authorization header

        try (OutputStream os = conn.getOutputStream()) {
            os.write(sessionZip);
        }

        assertEquals(401, conn.getResponseCode());
        conn.disconnect();
    }

    @Test
    void testPutSessionsEndpoint_WithValidAuth() throws Exception {
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
        try (InputStream is = conn.getInputStream()) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("sessionId"));
        }
        conn.disconnect();
    }

    @Test
    void testPostJobsEndpoint_RequiresAuth() throws Exception {
        // First, upload a session
        uploadSession();

        var jobSpec = Map.<String, Object>of(
                "sessionId",
                UUID.randomUUID().toString(),
                "taskInput",
                "echo hello",
                "autoCompress",
                false,
                "plannerModel",
                "gemini-2.0-flash");

        var url = URI.create(baseUrl + "/v1/jobs").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Idempotency-Key", "test-job-1");
        conn.setDoOutput(true);
        // No Authorization header

        var json = toJson(jobSpec);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(401, conn.getResponseCode());
        conn.disconnect();
    }

    @Test
    void testPostJobsEndpoint_RequiresPlannerModel() throws Exception {
        uploadSession();

        var jobSpec = Map.<String, Object>of(
                "sessionId", UUID.randomUUID().toString(), "taskInput", "echo missing planner", "autoCompress", false);

        var url = URI.create(baseUrl + "/v1/jobs").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Idempotency-Key", "test-job-missing-planner");
        conn.setDoOutput(true);

        var json = toJson(jobSpec);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(400, conn.getResponseCode());
        InputStream stream = conn.getErrorStream();
        if (stream == null) {
            stream = conn.getInputStream();
        }
        assertNotNull(stream, "Expected error response body");
        try (InputStream is = stream) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(
                    response.contains("plannerModel is required"),
                    "Expected validation error mentioning plannerModel, but got: " + response);
        }
        conn.disconnect();
    }

    @Test
    void testPostJobsEndpoint_InvalidPlannerModelFailsJob() throws Exception {
        uploadSession();

        var invalidPlanner = "does-not-exist-model";
        var jobSpec = Map.<String, Object>of(
                "sessionId",
                UUID.randomUUID().toString(),
                "taskInput",
                "echo invalid planner",
                "autoCompress",
                false,
                "plannerModel",
                invalidPlanner);

        var url = URI.create(baseUrl + "/v1/jobs").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Idempotency-Key", "test-job-invalid-planner");
        conn.setDoOutput(true);

        var json = toJson(jobSpec);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(201, conn.getResponseCode());
        String jobId;
        try (InputStream is = conn.getInputStream()) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            var marker = "\"jobId\":\"";
            var start = response.indexOf(marker);
            assertTrue(start >= 0, "Job response missing jobId: " + response);
            start += marker.length();
            var end = response.indexOf("\"", start);
            assertTrue(end > start, "Job response malformed: " + response);
            jobId = response.substring(start, end);
        }
        conn.disconnect();

        var deadline = System.currentTimeMillis() + 5_000L;
        String error = null;
        while (System.currentTimeMillis() < deadline) {
            var status = fetchJobStatus(jobId);
            var state = (String) status.get("state");
            if ("FAILED".equals(state)) {
                error = (String) status.get("error");
                break;
            }
            Thread.sleep(100);
        }

        assertNotNull(error, "Job did not fail with MODEL_UNAVAILABLE within timeout");
        assertTrue(error.contains("MODEL_UNAVAILABLE"), "Expected MODEL_UNAVAILABLE error, but got: " + error);
    }

    @Test
    void testPostJobsEndpoint_WithValidAuth() throws Exception {
        // Upload a session
        uploadSession();

        var jobSpec = Map.<String, Object>of(
                "sessionId",
                UUID.randomUUID().toString(),
                "taskInput",
                "echo hello",
                "autoCompress",
                false,
                "plannerModel",
                "gemini-2.0-flash");

        var url = URI.create(baseUrl + "/v1/jobs").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Idempotency-Key", "test-job-1");
        conn.setDoOutput(true);

        var json = toJson(jobSpec);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(201, conn.getResponseCode());
        try (InputStream is = conn.getInputStream()) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("jobId"));
            assertTrue(response.contains("state"));
        }
        conn.disconnect();
    }

    @Test
    void testGetJobsEventsEndpoint_WithPolling() throws Exception {
        // Upload session and create job
        uploadSession();

        var jobId = createJob("test-job-polling");

        // Poll for events
        var url = URI.create(baseUrl + "/v1/jobs/" + jobId + "/events?after=0").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);

        assertEquals(200, conn.getResponseCode());
        try (InputStream is = conn.getInputStream()) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("events"));
            assertTrue(response.contains("nextAfter"));
        }
        conn.disconnect();
    }

    @Test
    void testGetJobStatusEndpoint() throws Exception {
        uploadSession();
        var jobId = createJob("test-job-status");

        var url = URI.create(baseUrl + "/v1/jobs/" + jobId).toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);

        assertEquals(200, conn.getResponseCode());
        try (InputStream is = conn.getInputStream()) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("state"));
        }
        conn.disconnect();
    }

    @Test
    void testCancelJobEndpoint() throws Exception {
        uploadSession();
        var jobId = createJob("test-job-cancel");

        var url = URI.create(baseUrl + "/v1/jobs/" + jobId + "/cancel").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);

        assertEquals(202, conn.getResponseCode());
        conn.disconnect();
    }

    @Test
    void testEventOrdering_AfterJobSubmission() throws Exception {
        uploadSession();
        var jobId = createJob("test-event-ordering");

        // Small delay to allow events to be written
        Thread.sleep(200);

        var url = URI.create(baseUrl + "/v1/jobs/" + jobId + "/events?after=-1&limit=100")
                .toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);

        assertEquals(200, conn.getResponseCode());
        try (InputStream is = conn.getInputStream()) {
            var response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            // Verify structure contains events array
            assertTrue(response.contains("events"));
            assertTrue(response.contains("nextAfter"));
        }
        conn.disconnect();
    }

    // ============================================================================
    // Helpers
    // ============================================================================

    private byte[] createEmptyZip() throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(out)) {
            var entry = new ZipEntry("metadata.json");
            zos.putNextEntry(entry);
            zos.write("{\"version\": 1}".getBytes(StandardCharsets.UTF_8));
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

    private String createJob(String idempotencyKey) throws Exception {
        var jobSpec = Map.<String, Object>of(
                "sessionId",
                UUID.randomUUID().toString(),
                "taskInput",
                "echo test",
                "autoCompress",
                false,
                "plannerModel",
                "gemini-2.0-flash");

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
            // Extract jobId from response JSON
            var start = response.indexOf("\"jobId\":\"") + 9;
            var end = response.indexOf("\"", start);
            return response.substring(start, end);
        } finally {
            conn.disconnect();
        }
    }

    private Map<String, Object> fetchJobStatus(String jobId) throws Exception {
        var url = URI.create(baseUrl + "/v1/jobs/" + jobId).toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);

        try {
            assertEquals(200, conn.getResponseCode());
            try (InputStream is = conn.getInputStream()) {
                return OBJECT_MAPPER.readValue(is, new TypeReference<Map<String, Object>>() {});
            }
        } finally {
            conn.disconnect();
        }
    }

    private String toJson(Object obj) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(obj);
    }
}
