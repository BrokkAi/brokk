package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.ContextManager;
import ai.brokk.MainProject;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration-style health readiness test for HeadlessExecutorMain.
 *
 * Verifies that:
 *  - /health/ready returns 503 before any session exists
 *  - After creating a session, /health/ready returns 200 and includes the current session id
 */
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
        // 1) Before any session exists -> expect 503
        {
            var url = URI.create(baseUrl + "/health/ready").toURL();
            var conn = withTimeouts((HttpURLConnection) url.openConnection());
            conn.setRequestMethod("GET");
            try {
                assertEquals(503, conn.getResponseCode());
                // Body format may vary (error payload or status marker); not asserted here.
            } finally {
                conn.disconnect();
            }
        }

        // 2) Create a session via HTTP API
        String createdSessionId;
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
                var map = OBJECT_MAPPER.readValue(is, new TypeReference<Map<String, Object>>() {});
                createdSessionId = (String) map.get("sessionId");
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
                    assertEquals(createdSessionId, map.get("sessionId"));
                }
            } finally {
                conn.disconnect();
            }
        }
    }

    // Helpers

    private static HttpURLConnection withTimeouts(HttpURLConnection conn) {
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(5000);
        return conn;
    }
}
