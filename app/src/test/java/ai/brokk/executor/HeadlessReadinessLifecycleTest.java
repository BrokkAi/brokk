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
    void testReadinessReportsReadyWithActiveSession() throws Exception {
        // The current headless stub exposes a ready session via ContextManager at startup.
        // Verify /health/ready returns 200 and a payload with status="ready" and a non-blank sessionId.
        var readyUrl = URI.create(baseUrl + "/health/ready").toURL();
        var conn = (HttpURLConnection) readyUrl.openConnection();
        conn.setRequestMethod("GET");
        try {
            assertEquals(200, conn.getResponseCode());
            var readyBody = MAPPER.readValue(conn.getInputStream(), Map.class);
            assertEquals("ready", readyBody.get("status"));
            Object readySessionIdObj = readyBody.get("sessionId");
            assertNotNull(readySessionIdObj);
            String readySessionId = String.valueOf(readySessionIdObj);
            assertTrue(!readySessionId.isBlank(), "Expected non-empty sessionId in readiness response");
        } finally {
            conn.disconnect();
        }
    }
}
