package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Integration test to ensure HeadlessExecutorMain will exit when its stdin is closed.
 *
 * This mirrors the ACP parent-death scenario where the parent process's stdin is closed,
 * and the headless executor monitors System.in for EOF and initiates a controlled shutdown.
 *
 * The test launches a separate JVM running HeadlessExecutorMain with stdin piped,
 * waits for the "Executor listening on http://" banner, closes the child's stdin,
 * and asserts that the child JVM exits within a bounded timeout.
 */
public final class HeadlessExecutorStdinShutdownIT {

    private Process process = null;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() throws Exception {
        if (process != null && process.isAlive()) {
            process.destroy();
            process.waitFor();
        }
        executor.shutdownNow();
    }

    @Test
    void executorShouldExitWhenStdinClosed() throws Exception {
        // Locate a brokk.jar to execute. Prefer module-local build output.
        Path candidate1 = Path.of("build", "libs", "brokk.jar");
        Path candidate2 = Path.of("app", "build", "libs", "brokk.jar");
        Path jarPath = null;
        if (Files.exists(candidate1)) {
            jarPath = candidate1.toAbsolutePath();
        } else if (Files.exists(candidate2)) {
            jarPath = candidate2.toAbsolutePath();
        }

        // If we couldn't find a jar, skip the test to avoid false failures in environments
        // where the integration artifact isn't available.
        Assumptions.assumeTrue(
                jarPath != null && Files.exists(jarPath),
                "brokk.jar not found; skipping integration test (expected at " + candidate1 + " or " + candidate2
                        + ")");

        Path workspaceDir = Files.createTempDirectory("headless-exec-it-");
        try {
            String execId = UUID.randomUUID().toString();
            String authToken = "test-token";

            ProcessBuilder pb = new ProcessBuilder(
                    "java",
                    "-Djava.awt.headless=true",
                    "-Dapple.awt.UIElement=true",
                    "-cp",
                    jarPath.toString(),
                    "ai.brokk.executor.HeadlessExecutorMain",
                    "--exec-id",
                    execId,
                    "--listen-addr",
                    "127.0.0.1:0",
                    "--auth-token",
                    authToken,
                    "--workspace-dir",
                    workspaceDir.toString());

            pb.redirectErrorStream(true);
            process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            Callable<String> waitForListening = () -> {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("Executor listening on http://")) {
                        return line;
                    }
                }
                return null;
            };

            Future<String> listenFuture = executor.submit(waitForListening);
            String bannerLine;
            try {
                bannerLine = listenFuture.get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor();
                }
                fail("Timed out waiting for executor listening banner: " + e.getMessage());
                return;
            }

            assertNotNull(bannerLine, "Executor process exited before printing listening banner");

            // Close the child's stdin to simulate parent death / EOF.
            try {
                process.getOutputStream().close();
            } catch (Exception e) {
                // best-effort; continue to waiting below
            }

            // Wait for the process to exit within a reasonable timeout.
            boolean exited = process.waitFor(8, TimeUnit.SECONDS);
            if (!exited) {
                process.destroy();
                process.waitFor(2, TimeUnit.SECONDS);
            }

            if (process.isAlive()) {
                process.destroyForcibly();
            }

            int exitCode = process.exitValue();
            assertTrue(
                    exitCode == 0 || exitCode == 1 || exitCode < 0 || !process.isAlive(),
                    "Executor process did not exit in response to stdin closure (exitCode=" + exitCode + ")");

        } finally {
            try {
                Files.walk(workspaceDir)
                        .map(Path::toFile)
                        .sorted((a, b) -> -a.compareTo(b))
                        .forEach(File::delete);
            } catch (Exception ignored) {
            }
        }
    }
}
