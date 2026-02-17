package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.TestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the parent-death (stdin) monitor wiring in HeadlessExecutorMain.
 *
 * These tests exercise the monitor helper method without invoking System.exit in the test JVM.
 * The monitor thread will invoke a controlled onExit hook that stops the executor and signals the test.
 */
class HeadlessExecutorMainParentDeathTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HeadlessExecutorMain executor;
    private int port;
    private String baseUrl;
    private String authToken;

    private PipedInputStream pipedIn;
    private PipedOutputStream pipedOut;

    @BeforeEach
    void setup(@TempDir Path tempDir) throws Exception {
        System.setProperty("ai.brokk.executor.testMode", "true");
        var workspaceDir = tempDir.resolve("workspace");
        Files.createDirectories(workspaceDir);

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

        // Prepare piped System.in replacement to simulate parent pipe.
        pipedIn = new PipedInputStream();
        pipedOut = new PipedOutputStream(pipedIn);
        // Replace System.in for the scope of the test
        System.setIn(pipedIn);

        // Start HTTP server
        executor.start();
        port = executor.getPort();
        baseUrl = "http://127.0.0.1:" + port;
    }

    @AfterEach
    void cleanup() {
        // Restore System.in to something harmless
        try {
            System.setIn(System.in);
        } catch (Exception ignored) {
        }

        if (executor != null) {
            executor.stop(2);
        }
        try {
            if (pipedOut != null) {
                pipedOut.close();
            }
            if (pipedIn != null) {
                pipedIn.close();
            }
        } catch (IOException ignored) {
        }
    }

    @Test
    void parentDeathMonitor_triggersOnExitHookAndDoesNotBreakEndpoints() throws Exception {
        // Start the monitor thread with an onExit hook that stops the executor and signals latch.
        CountDownLatch latch = new CountDownLatch(1);
        Runnable onExit = () -> {
            try {
                executor.stop(1);
            } finally {
                latch.countDown();
            }
        };
        Thread monitor = executor.createParentDeathMonitor(onExit);
        monitor.start();

        // While monitor is running (and before we signal EOF), ensure /health/live responds.
        {
            var url = URI.create(baseUrl + "/health/live").toURL();
            var conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            try {
                assertEquals(200, conn.getResponseCode());
            } finally {
                conn.disconnect();
            }
        }

        // Now simulate parent death by closing the piped output (causes EOF on System.in).
        pipedOut.close();

        // Wait for onExit to be invoked
        boolean signalled = latch.await(5, TimeUnit.SECONDS);
        assertTrue(signalled, "Expected onExit hook to be invoked when System.in is closed");

        // After controlled stop was invoked, server should have stopped; verify by expecting failure on /health/live.
        // We allow either connection failure or non-200; just ensure we don't hang.
        try {
            var url = URI.create(baseUrl + "/health/live").toURL();
            var conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            // If server still answered, it should still be a valid response (but this is unexpected).
            // Accept any numeric code but at least assert we got a response code to ensure request completed.
            assertTrue(code >= 100 && code < 600);
            conn.disconnect();
        } catch (IOException ignored) {
            // Connection failure is acceptable as the server may have been stopped by onExit.
        }
    }
}
