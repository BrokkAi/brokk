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
    void testReadyEndpointIsDeprecatedLivenessAlias() throws Exception {
        // /health/ready should return 200 even before session creation.
        var readyUrl = URI.create(baseUrl + "/health/ready").toURL();
        var conn1 = (HttpURLConnection) readyUrl.openConnection();
        conn1.setRequestMethod("GET");
        assertEquals(200, conn1.getResponseCode());
        assertEquals("true", conn1.getHeaderField("Deprecation"));
        var warning = conn1.getHeaderField("Warning");
        assertNotNull(warning);
        assertTrue(warning.contains("Deprecated endpoint"), warning);

        var initialReadyBody = MAPPER.readValue(conn1.getInputStream(), Map.class);
        assertEquals("ready", initialReadyBody.get("status"));
        assertNotNull(initialReadyBody.get("sessionId"));

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

        // 3. Post-creation state: still 200 with ready payload.
        var conn3 = (HttpURLConnection) readyUrl.openConnection();
        conn3.setRequestMethod("GET");
        assertEquals(200, conn3.getResponseCode());

        var readyBody = MAPPER.readValue(conn3.getInputStream(), Map.class);
        assertEquals("ready", readyBody.get("status"));

        // sessionId should be present and non-empty.
        Object readySessionIdObj = readyBody.get("sessionId");
        assertNotNull(readySessionIdObj);
        String readySessionId = String.valueOf(readySessionIdObj);
        assertTrue(!readySessionId.isBlank(), "Expected non-empty sessionId in readiness response");
    }
}
