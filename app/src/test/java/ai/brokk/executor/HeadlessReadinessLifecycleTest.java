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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadlessReadinessLifecycleTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AUTH_TOKEN = "test-token";

    private HeadlessExecutorMain executor;
    private String baseUrl;
    private Path projectRoot;

    @BeforeEach
    void setup(@TempDir Path tempDir) {
        projectRoot = tempDir;
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.stop(0);
        }
    }

    private void startExecutor(@org.jetbrains.annotations.Nullable String startupSessionName) throws Exception {
        var project = new MainProject(projectRoot);
        var contextManager = new ContextManager(project);
        var execId = UUID.randomUUID();
        executor = new HeadlessExecutorMain(execId, "127.0.0.1:0", AUTH_TOKEN, contextManager, startupSessionName);
        executor.start();
        baseUrl = "http://127.0.0.1:" + executor.getPort();
    }

    @Test
    void testReadinessTransitionsAfterSessionCreation() throws Exception {
        startExecutor(null);
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
        // Note: The ContextManager may quarantine a newly-created session and create a replacement session
        // asynchronously. Therefore readiness only guarantees that some session is loaded, not that it is
        // necessarily the exact same sessionId returned by the POST above.
        var conn3 = (HttpURLConnection) readyUrl.openConnection();
        conn3.setRequestMethod("GET");
        assertEquals(200, conn3.getResponseCode());

        var readyBody = MAPPER.readValue(conn3.getInputStream(), Map.class);
        assertEquals("ready", readyBody.get("status"));

        // sessionId should be present and non-empty, but it may differ from the originally-created session id
        Object readySessionIdObj = readyBody.get("sessionId");
        assertNotNull(readySessionIdObj);
        String readySessionId = String.valueOf(readySessionIdObj);
        assertTrue(!readySessionId.isBlank(), "Expected non-empty sessionId in readiness response");
    }

    @Test
    void testReadinessReadyOnStartupWithSessionName() throws Exception {
        startExecutor("Bootstrap Session");

        var readyUrl = URI.create(baseUrl + "/health/ready").toURL();
        var conn = (HttpURLConnection) readyUrl.openConnection();
        conn.setRequestMethod("GET");
        assertEquals(200, conn.getResponseCode());

        var readyBody = MAPPER.readValue(conn.getInputStream(), Map.class);
        assertEquals("ready", readyBody.get("status"));
        assertNotNull(readyBody.get("sessionId"));
    }

    @Test
    void testReadinessReusesExistingNamedSessionOnRestart() throws Exception {
        startExecutor("Persistent Session");

        var readyUrl1 = URI.create(baseUrl + "/health/ready").toURL();
        var conn1 = (HttpURLConnection) readyUrl1.openConnection();
        conn1.setRequestMethod("GET");
        assertEquals(200, conn1.getResponseCode());
        var readyBody1 = MAPPER.readValue(conn1.getInputStream(), Map.class);
        String firstSessionId = String.valueOf(readyBody1.get("sessionId"));
        assertTrue(!firstSessionId.isBlank());

        executor.stop(0);
        executor = null;

        startExecutor("Persistent Session");

        var readyUrl2 = URI.create(baseUrl + "/health/ready").toURL();
        var conn2 = (HttpURLConnection) readyUrl2.openConnection();
        conn2.setRequestMethod("GET");
        assertEquals(200, conn2.getResponseCode());
        var readyBody2 = MAPPER.readValue(conn2.getInputStream(), Map.class);
        String secondSessionId = String.valueOf(readyBody2.get("sessionId"));
        assertEquals(firstSessionId, secondSessionId);
    }
}
