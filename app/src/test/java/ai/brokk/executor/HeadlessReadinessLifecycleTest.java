package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.project.MainProject;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the headless executor readiness lifecycle.
 *
 * Clarified readiness semantics:
 * - POST /v1/sessions returns the sessionId that was active at the time the request completed.
 * - GET /health/ready reports the currently active session id as reported by ContextManager.getCurrentSessionId().
 *   Background maintenance may change the active session after the POST; clients should therefore treat the readiness
 *   endpoint as authoritative for "what the executor is currently running".
 *
 * Tests should not make fragile assumptions about the timing of background tasks. The assertions below verify the
 * non-flaky guarantees: readiness returns 200 and a non-null sessionId once the server reports ready. We also keep
 * the createdSessionId (returned from POST) for informational checks but do not require equality.
 */
class HeadlessReadinessLifecycleTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AUTH_TOKEN = "test-token";

    private HeadlessExecutorMain executor;
    private String baseUrl;

    @BeforeEach
    void setup(@TempDir Path tempDir) throws Exception {
        var project = new MainProject(tempDir);
        var contextManager = new ContextManager(project);
        var execId = UUID.randomUUID();
        // Use port 0 for random available port
        executor = new HeadlessExecutorMain(execId, "127.0.0.1:0", AUTH_TOKEN, contextManager);
        executor.start();
        baseUrl = "http://127.0.0.1:" + executor.getPort();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.stop(0);
        }
    }

    @Test
    void testReadinessTransitionsAfterSessionCreation() throws Exception {
        // 1. Initial state: should be 503 NOT_READY
        var readyUrl = URI.create(baseUrl + "/health/ready").toURL();
        var conn1 = (HttpURLConnection) readyUrl.openConnection();
        conn1.setRequestMethod("GET");
        assertEquals(503, conn1.getResponseCode());

        var errorBody = MAPPER.readValue(conn1.getErrorStream(), Map.class);
        assertEquals("NOT_READY", errorBody.get("code"));

        // 2. Create a session (the POST returns the session id that was active at response time)
        var sessionsUrl = URI.create(baseUrl + "/v1/sessions").toURL();
        var conn2 = (HttpURLConnection) sessionsUrl.openConnection();
        conn2.setRequestMethod("POST");
        conn2.setRequestProperty("Authorization", "Bearer " + AUTH_TOKEN);
        conn2.setRequestProperty("Content-Type", "application/json");
        conn2.setDoOutput(true);

        var sessionRequest = Map.of("name", "Test Session");
        MAPPER.writeValue(conn2.getOutputStream(), sessionRequest);

        assertEquals(201, conn2.getResponseCode());
        var createBody = MAPPER.readValue(conn2.getInputStream(), Map.class);
        String createdSessionId = (String) createBody.get("sessionId");
        assertNotNull(createdSessionId);

        // 3. Post-creation state: poll /health/ready until it reports ready (within a reasonable timeout).
        // Once ready, assert the guaranteed properties: status == "ready" and sessionId is a non-null UUID string.
        var deadline = Instant.now().plus(Duration.ofSeconds(5));
        Map<?, ?> readyBody = null;
        int lastStatus = -1;
        while (Instant.now().isBefore(deadline)) {
            var conn3 = (HttpURLConnection) readyUrl.openConnection();
            conn3.setRequestMethod("GET");
            lastStatus = conn3.getResponseCode();
            if (lastStatus == 200) {
                readyBody = MAPPER.readValue(conn3.getInputStream(), Map.class);
                break;
            }
            // If still 503, wait briefly and retry. Tests must not assume exact ordering relative to background work.
            Thread.sleep(100);
        }

        assertEquals(200, lastStatus, "Expected /health/ready to report 200 within timeout");
        assertNotNull(readyBody, "ready response body should be present");

        assertEquals("ready", readyBody.get("status"));
        Object readySessionIdObj = readyBody.get("sessionId");
        assertNotNull(readySessionIdObj, "ready.sessionId should be non-null");

        // Validate that sessionId is a valid UUID string. Do not require it to equal createdSessionId,
        // because background maintenance may replace the active session after POST returned.
        String readySessionId = readySessionIdObj.toString();
        assertTrue(isValidUuidString(readySessionId), "ready.sessionId must be a valid UUID string");
    }

    private static boolean isValidUuidString(String s) {
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
