package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.IContextManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;
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
        // Use a minimal stub IContextManager that always returns a fixed session ID.
        var fixedSessionId = UUID.randomUUID();
        IContextManager stubCm = new IContextManager() {
            @Override
            public @Nullable UUID getCurrentSessionId() {
                return fixedSessionId;
            }

            @Override
            public CompletableFuture<Void> createSessionAsync(String name) {
                return CompletableFuture.completedFuture(null);
            }
        };

        var execId = UUID.randomUUID();
        executor = new HeadlessExecutorMain(execId, "127.0.0.1:0", AUTH_TOKEN, stubCm);
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
    void testReadinessReportsNotReadyWithoutSession() throws Exception {
        // Create a new executor with a context manager that has no session.
        IContextManager noSessionCm = new IContextManager() {
            @Override
            public @Nullable UUID getCurrentSessionId() {
                return null;
            }
        };
        var noSessionExecutor = new HeadlessExecutorMain(UUID.randomUUID(), "127.0.0.1:0", AUTH_TOKEN, noSessionCm);
        noSessionExecutor.start();
        String noSessionBaseUrl = "http://127.0.0.1:" + noSessionExecutor.getPort();

        try {
            var readyUrl = URI.create(noSessionBaseUrl + "/health/ready").toURL();
            var conn = (HttpURLConnection) readyUrl.openConnection();
            conn.setRequestMethod("GET");
            try {
                assertEquals(503, conn.getResponseCode(), "Expected 503 when no session exists");
            } finally {
                conn.disconnect();
            }
        } finally {
            noSessionExecutor.stop(0);
        }
    }

    @Test
    void testReadinessReportsReadyWithActiveSession() throws Exception {
        // The stub context manager configured in setup() always returns a session ID.
        var readyUrl = URI.create(baseUrl + "/health/ready").toURL();
        var conn = (HttpURLConnection) readyUrl.openConnection();
        conn.setRequestMethod("GET");
        try {
            assertEquals(200, conn.getResponseCode());
            var readyBody = MAPPER.readValue(conn.getInputStream(), Map.class);
            assertEquals("ready", readyBody.get("status"));
        } finally {
            conn.disconnect();
        }
    }
}
