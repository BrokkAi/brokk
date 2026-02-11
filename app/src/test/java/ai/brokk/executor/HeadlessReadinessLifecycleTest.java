package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.brokk.ContextManager;
import ai.brokk.project.MainProject;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the headless executor readiness lifecycle.
 *
 * Intended readiness semantics (documented to reduce flakiness and make expectations explicit):
 * - POST /v1/sessions returns the session id for the session that was created (or started) as part of the request.
 * - GET /health/ready reports the currently active session id as reported by ContextManager.getCurrentSessionId().
 *   This means that if background session maintenance (quarantine/migration/recovery) runs after the POST and
 *   replaces or switches the active session, GET /health/ready may return an id different from the id returned
 *   by the earlier POST. The readiness endpoint deliberately reports the executor's current active session, not
 *   the historical "most recently returned by POST".
 *
 * Rationale:
 * - The executor's readiness should reflect what it will actually execute against (the active session). Tests and
 *   clients that rely on POST /v1/sessions returning a session id which will remain active forever must tolerate
 *   background maintenance or explicitly reconcile by querying session state.
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

        // 2. Create a session
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

        // 3. Post-creation state: should be 200 OK
        var conn3 = (HttpURLConnection) readyUrl.openConnection();
        conn3.setRequestMethod("GET");
        assertEquals(200, conn3.getResponseCode());

        var readyBody = MAPPER.readValue(conn3.getInputStream(), Map.class);
        assertEquals("ready", readyBody.get("status"));
        assertEquals(createdSessionId, readyBody.get("sessionId"));
    }
}
