package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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

            List<String> outputLines = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch bannerSeen = new CountDownLatch(1);
            Future<?> outputPump = executor.submit(() -> {
                try (var reader =
                        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        outputLines.add(line);
                        if (line.contains("Executor listening on http://")) {
                            bannerSeen.countDown();
                        }
                    }
                }
                return null;
            });

            long bannerDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            boolean sawBanner = false;
            while (System.nanoTime() < bannerDeadlineNanos) {
                if (bannerSeen.await(250, TimeUnit.MILLISECONDS)) {
                    sawBanner = true;
                    break;
                }
                if (!process.isAlive() || outputPump.isDone()) {
                    break;
                }
            }
            if (!sawBanner) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor();
                }
                try {
                    outputPump.get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
                fail("Timed out waiting for executor listening banner. Output:%n" + formatOutput(outputLines));
            }
            assertTrue(process.isAlive(), "Executor process exited before printing listening banner");

            // Close the child's stdin to simulate parent death / EOF.
            try {
                process.getOutputStream().close();
            } catch (Exception e) {
                // best-effort; continue to waiting below
            }

            // Wait for the process to exit within a reasonable timeout.
            boolean exited = process.waitFor(60, TimeUnit.SECONDS);
            if (!exited) {
                process.destroy();
                process.waitFor(2, TimeUnit.SECONDS);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }

            try {
                outputPump.get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }

            assertTrue(exited, "Process did not exit after stdin was closed. Output:%n" + formatOutput(outputLines));

            int exitCode = process.exitValue();
            assertEquals(
                    0,
                    exitCode,
                    "Executor process exited with unexpected code after stdin closure. Output:%n"
                            + formatOutput(outputLines));

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

    private static String formatOutput(List<String> outputLines) {
        List<String> snapshot;
        synchronized (outputLines) {
            snapshot = new ArrayList<>(outputLines);
        }
        return snapshot.isEmpty() ? "(no output)" : String.join(System.lineSeparator(), snapshot);
    }
}
