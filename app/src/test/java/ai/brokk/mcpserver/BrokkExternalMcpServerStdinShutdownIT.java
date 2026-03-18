package ai.brokk.mcpserver;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
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

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            Callable<String> waitForBanner = () -> {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("Brokk MCP Server starting")) {
                        return line;
                    }
                }
                return null;
            };

            Future<String> bannerFuture = executor.submit(waitForBanner);
            String bannerLine;
            try {
                bannerLine = bannerFuture.get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor();
                }
                fail("Timed out waiting for MCP server starting banner: " + e.getMessage());
                return;
            }

            assertNotNull(bannerLine, "MCP server process exited before printing starting banner");

            // Close stdin to simulate parent death.
            try {
                process.getOutputStream().close();
            } catch (Exception e) {
                // best-effort
            }

            boolean exited = process.waitFor(8, TimeUnit.SECONDS);
            if (!exited) {
                process.destroy();
                process.waitFor(2, TimeUnit.SECONDS);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }

            assertTrue(exited, "MCP server process did not exit after stdin was closed");
            assertEquals(0, process.exitValue(), "MCP server exited with unexpected code after stdin closure");

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
