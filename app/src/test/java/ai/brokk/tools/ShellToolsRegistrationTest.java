package ai.brokk.tools;

import ai.brokk.ContextManager;
import ai.brokk.testutil.TestProject;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ShellToolsRegistrationTest {

    @TempDir
    Path tempDir;

    @Test
    void testShellToolsIsRegisteredInGlobalToolRegistry() {
        var project = new TestProject(tempDir);
        var cm = new ContextManager(project);
        ToolRegistry registry = cm.getToolRegistry();
        Assertions.assertTrue(registry.isRegistered("executeShellCommand"));
    }
}
