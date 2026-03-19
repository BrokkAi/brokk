package ai.brokk.mcpserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BrokkExternalMcpServerRootResolutionTest {

    @Test
    void resolveProjectRoot_defaultsToCwd() throws Exception {
        Path cwd = Files.createTempDirectory("mcp-root-cwd");

        var resolution = BrokkExternalMcpServer.resolveProjectRoot(new String[0], Map.of(), cwd);

        assertEquals(cwd.toAbsolutePath().normalize(), resolution.projectRoot());
        assertIterableEquals(List.of(), resolution.remainingArgs());
    }

    @Test
    void resolveProjectRoot_usesEnvWhenCliOptionMissing() throws Exception {
        Path cwd = Files.createTempDirectory("mcp-root-cwd");
        Path envRoot = Files.createTempDirectory("mcp-root-env");

        var resolution = BrokkExternalMcpServer.resolveProjectRoot(
                new String[0], Map.of("BROKK_PROJECT_ROOT", envRoot.toString()), cwd);

        assertEquals(envRoot.toAbsolutePath().normalize(), resolution.projectRoot());
        assertIterableEquals(List.of(), resolution.remainingArgs());
    }

    @Test
    void resolveProjectRoot_cliOverridesEnvAndPreservesOtherArgs() throws Exception {
        Path cwd = Files.createTempDirectory("mcp-root-cwd");
        Path cliRoot = Files.createTempDirectory("mcp-root-cli");
        Path envRoot = Files.createTempDirectory("mcp-root-env");

        var resolution = BrokkExternalMcpServer.resolveProjectRoot(
                new String[] {"--project-root", cliRoot.toString(), "--help"},
                Map.of("BROKK_PROJECT_ROOT", envRoot.toString()),
                cwd);

        assertEquals(cliRoot.toAbsolutePath().normalize(), resolution.projectRoot());
        assertIterableEquals(List.of("--help"), resolution.remainingArgs());
    }

    @Test
    void resolveProjectRoot_rejectsInvalidOverride() throws Exception {
        Path cwd = Files.createTempDirectory("mcp-root-cwd");
        Path invalidRoot = cwd.resolve("missing-root");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> BrokkExternalMcpServer.resolveProjectRoot(
                        new String[] {"--project-root", invalidRoot.toString()}, Map.of(), cwd));

        assertTrue(exception.getMessage().contains(invalidRoot.toString()));
    }
}
