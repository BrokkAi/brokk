package ai.brokk.util;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.brokk.util.sandbox.Platform;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SandboxExecutionIntegrationTest {

    @TempDir
    Path tempDir;

    private Path cleanupPath;

    private static boolean isSandboxAvailable(Path projectRoot) {
        return new SandboxBridge(projectRoot, false, null).isAvailable();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (cleanupPath != null) {
            Files.deleteIfExists(cleanupPath);
            cleanupPath = null;
        }
    }

    @Test
    void testSandboxedCommandOutputCaptured_Linux() throws Exception {
        assumeTrue(Platform.getPlatform() == Platform.LINUX);
        assumeTrue(isSandboxAvailable(tempDir));

        var lines = new ArrayList<String>();
        String output = Environment.instance.runShellCommand(
                "echo \"hello sandbox\"", tempDir, true, lines::add, Environment.DEFAULT_TIMEOUT);

        Assertions.assertTrue(output.contains("hello sandbox"));
    }

    @Test
    void testSandboxedWriteOutsideProjectRootFails_Linux() throws Exception {
        assumeTrue(Platform.getPlatform() == Platform.LINUX);
        assumeTrue(isSandboxAvailable(tempDir));

        cleanupPath = Path.of("/tmp", "sandbox-test-" + UUID.randomUUID() + ".txt");

        var lines = new ArrayList<String>();
        try {
            Environment.instance.runShellCommand(
                    "touch " + cleanupPath, tempDir, true, lines::add, Environment.DEFAULT_TIMEOUT);
            Assertions.fail("Expected sandboxed write outside project root to fail");
        } catch (Environment.FailureException expected) {
            // expected
        } finally {
            Files.deleteIfExists(cleanupPath);
        }
    }

    @Test
    void testSandboxedCommandOutputCaptured_MacOS() throws Exception {
        assumeTrue(Platform.getPlatform() == Platform.MACOS);
        assumeTrue(isSandboxAvailable(tempDir));

        var lines = new ArrayList<String>();
        String output = Environment.instance.runShellCommand(
                "echo \"hello sandbox\"", tempDir, true, lines::add, Environment.DEFAULT_TIMEOUT);

        Assertions.assertTrue(output.contains("hello sandbox"));
    }

    @Test
    void testSandboxedWriteOutsideProjectRootFails_MacOS() throws Exception {
        assumeTrue(Platform.getPlatform() == Platform.MACOS);
        assumeTrue(isSandboxAvailable(tempDir));

        cleanupPath = Path.of("/tmp", "sandbox-test-" + UUID.randomUUID() + ".txt");

        var lines = new ArrayList<String>();
        try {
            Environment.instance.runShellCommand(
                    "touch " + cleanupPath, tempDir, true, lines::add, Environment.DEFAULT_TIMEOUT);
            Assertions.fail("Expected sandboxed write outside project root to fail");
        } catch (Environment.FailureException expected) {
            // expected
        } finally {
            Files.deleteIfExists(cleanupPath);
        }
    }
}
