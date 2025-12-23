package ai.brokk.util;

import ai.brokk.util.sandbox.SandboxConfig;
import ai.brokk.util.sandbox.SandboxManager;
import ai.brokk.util.sandbox.SandboxUtils;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SandboxBridgeTest {

    @Test
    void testFilesystemConfigMarksProjectRootAsWritable(@TempDir Path tempDir) {
        SandboxBridge bridge = new SandboxBridge(tempDir, false, null);

        SandboxConfig config = bridge.buildSandboxConfig();

        Assertions.assertNotNull(config.filesystem());
        Assertions.assertTrue(
                config.filesystem().allowWrite().contains(tempDir.toAbsolutePath().normalize().toString()));
    }

    @Test
    void testDangerousPathsAppliedToDenyRead(@TempDir Path tempDir) {
        SandboxBridge bridge = new SandboxBridge(tempDir, false, null);

        SandboxConfig config = bridge.buildSandboxConfig();
        List<String> denyRead = config.filesystem().denyRead();

        for (String fileName : SandboxUtils.DANGEROUS_FILES) {
            Assertions.assertTrue(
                    denyRead.contains("**/" + fileName),
                    "denyRead should contain glob deny pattern for file: " + fileName);
        }

        for (String dirName : SandboxUtils.getDangerousDirectories()) {
            Assertions.assertTrue(
                    denyRead.contains("**/" + dirName + "/**"),
                    "denyRead should contain glob deny pattern for dir: " + dirName);
        }

        Assertions.assertTrue(denyRead.contains("**/.git/hooks/**"));
    }

    @Test
    void testIsAvailableReturnsFalseWhenDependenciesMissing(@TempDir Path tempDir) {
        SandboxManager.CommandRunner runner =
                command -> new SandboxManager.CommandResult(1, "", "");

        SandboxBridge bridge = new SandboxBridge(tempDir, false, null, runner);

        Assertions.assertFalse(bridge.isAvailable());
    }

    @Test
    void testIsAvailableReturnsTrueWhenDependenciesPresent(@TempDir Path tempDir) {
        SandboxManager.CommandRunner runner =
                command -> new SandboxManager.CommandResult(0, "", "");

        SandboxBridge bridge = new SandboxBridge(tempDir, false, null, runner);

        Assertions.assertTrue(bridge.isAvailable());
    }
}
