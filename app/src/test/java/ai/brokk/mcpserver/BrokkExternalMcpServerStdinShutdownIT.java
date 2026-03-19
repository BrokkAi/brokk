package ai.brokk.mcpserver;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test to ensure BrokkExternalMcpServer exits when its stdin is closed.
 *
 * When the parent process (e.g. Claude Code) dies, the pipe to the MCP server's stdin
 * is closed. The server must detect this EOF and exit rather than becoming an orphan.
 *
 * The test launches a separate JVM running BrokkExternalMcpServer, waits for the
 * "Brokk MCP Server started" banner, closes the child's stdin, and asserts that
 * the child JVM exits within a bounded timeout.
 */
public final class BrokkExternalMcpServerStdinShutdownIT {

    private Process process = null;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

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
    void mcpServerShouldExitWhenStdinClosed() throws Exception {
        String classpath = System.getProperty("java.class.path");

        Path workspaceDir = Files.createTempDirectory("mcp-server-stdin-it-");
        // Write JVM args to an argfile to avoid Windows command line length limit (CreateProcess error=206)
        Path argFile = Files.createTempFile("java-cp-argfile-", ".txt");
        try {
            Files.writeString(
                    argFile,
                    "-Djava.awt.headless=true\n" + "-Dapple.awt.UIElement=true\n"
                            + "-cp\n"
                            + "\""
                            + classpath.replace("\\", "\\\\") + "\"\n");

            ProcessBuilder pb = new ProcessBuilder(
                    "java", "@" + argFile.toAbsolutePath(), "ai.brokk.mcpserver.BrokkExternalMcpServer");

            pb.directory(workspaceDir.toFile());
            pb.redirectErrorStream(true); // merge stderr (logs) into stdout for reading
            process = pb.start();

            List<String> outputLines = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch bannerSeen = new CountDownLatch(1);
            Future<?> outputPump = executor.submit(() -> {
                try (var reader =
                        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        outputLines.add(line);
                        if (line.contains("Brokk MCP Server starting")) {
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
                fail("Timed out waiting for MCP server starting banner. Output:%n" + formatOutput(outputLines));
            }
            assertTrue(process.isAlive(), "MCP server process exited before printing starting banner");

            // Close stdin to simulate parent death.
            try {
                process.getOutputStream().close();
            } catch (Exception e) {
                // best-effort
            }

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

            assertTrue(
                    exited,
                    "MCP server process did not exit after stdin was closed. Output:%n" + formatOutput(outputLines));
            assertEquals(
                    0,
                    process.exitValue(),
                    "MCP server exited with unexpected code after stdin closure. Output:%n"
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
