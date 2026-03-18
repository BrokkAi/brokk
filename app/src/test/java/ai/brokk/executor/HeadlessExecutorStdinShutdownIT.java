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
    private ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() throws Exception {
        if (process != null && process.isAlive()) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        }
        executor.shutdownNow();
    }

    @Test
    void executorShouldExitWhenStdinClosed() throws Exception {
        String classpath = System.getProperty("java.class.path");

        Path workspaceDir = Files.createTempDirectory("headless-exec-it-");
        // Write JVM args to an argfile to avoid Windows command line length limit (CreateProcess error=206)
        Path argFile = Files.createTempFile("java-cp-argfile-", ".txt");
        try {
            String execId = UUID.randomUUID().toString();
            String authToken = "test-token";

            Files.writeString(
                    argFile,
                    "-Djava.awt.headless=true\n" + "-Dapple.awt.UIElement=true\n"
                            + "-cp\n"
                            + "\""
                            + classpath.replace("\\", "\\\\") + "\"\n");

            ProcessBuilder pb = new ProcessBuilder(
                    "java",
                    "@" + argFile.toAbsolutePath(),
                    "ai.brokk.executor.HeadlessExecutorMain",
                    "--exec-id",
                    execId,
                    "--listen-addr",
                    "127.0.0.1:0",
                    "--auth-token",
                    authToken,
                    "--workspace-dir",
                    workspaceDir.toString(),
                    "--exit-on-stdin-eof");

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
            boolean exited = process.waitFor(30, TimeUnit.SECONDS);
            if (!exited) {
                process.destroy();
                process.waitFor(2, TimeUnit.SECONDS);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }

            assertTrue(exited, "Process did not exit after stdin was closed");

            int exitCode = process.exitValue();
            assertEquals(0, exitCode, "Executor process exited with unexpected code after stdin closure");

        } finally {
            argFile.toFile().delete();
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
