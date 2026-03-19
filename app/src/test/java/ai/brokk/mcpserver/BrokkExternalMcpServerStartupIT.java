package ai.brokk.mcpserver;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BrokkExternalMcpServerStartupIT {

    @Test
    void mcpServerFailsFastForInvalidProjectRoot() throws Exception {
        String classpath = System.getProperty("java.class.path");
        Path invalidRoot = Files.createTempDirectory("mcp-invalid-root").resolve("missing-root");
        Path argFile = Files.createTempFile("java-cp-argfile-", ".txt");
        Process process = null;

        try {
            Files.writeString(
                    argFile,
                    "-Djava.awt.headless=true\n" + "-Dapple.awt.UIElement=true\n"
                            + "-cp\n"
                            + "\""
                            + classpath.replace("\\", "\\\\") + "\"\n");

            process = new ProcessBuilder(
                            "java",
                            "@" + argFile.toAbsolutePath(),
                            "ai.brokk.mcpserver.BrokkExternalMcpServer",
                            "--project-root",
                            invalidRoot.toString())
                    .redirectErrorStream(true)
                    .start();

            boolean exited = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(exited, "MCP server did not exit for an invalid project root");
            assertTrue(process.exitValue() != 0, "MCP server exited successfully for an invalid project root");

            String output = readAll(process);
            assertNotNull(output);
            assertTrue(output.contains("Invalid project root"), output);
            assertTrue(output.contains(invalidRoot.toString()), output);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
            Files.deleteIfExists(argFile);
        }
    }

    private static String readAll(Process process) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            return reader.lines().collect(Collectors.joining(System.lineSeparator()));
        }
    }
}
