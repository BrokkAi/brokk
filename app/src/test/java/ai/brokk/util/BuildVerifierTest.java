package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.TestProject;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildVerifierTest {

    @Test
    void testVerifySuccessfulCommand() {
        // Use a simple cross-platform echo command that always succeeds
        String command = "echo 'Hello from BuildVerifier'";
        TestProject project = createStubProject();

        BuildVerifier.Result result = BuildVerifier.verify(project, command, null);

        assertTrue(result.success(), "Echo command should succeed");
        assertEquals(0, result.exitCode(), "Exit code should be 0");
        assertNotNull(result.outputTail(), "Output tail should not be null");
        assertTrue(
                result.outputTail().contains("Hello from BuildVerifier"),
                "Output should contain the echo message");
    }

    @Test
    void testVerifyFailingCommand() {
        // Use a simple command that fails on all platforms
        String command = "false";
        TestProject project = createStubProject();

        BuildVerifier.Result result = BuildVerifier.verify(project, command, null);

        assertFalse(result.success(), "False command should fail");
        assertNotEquals(0, result.exitCode(), "Exit code should be non-zero");
        assertNotNull(result.outputTail(), "Output tail should not be null");
    }

    @Test
    void testVerifyWithEnvironmentVariables() {
        // Test that environment variables are passed through
        String command = "echo test";
        TestProject project = createStubProject();
        Map<String, String> envVars = Map.of("TEST_VAR", "test_value");

        // Should not throw; verifies that env vars are accepted
        BuildVerifier.Result result = BuildVerifier.verify(project, command, envVars);

        assertTrue(result.success(), "Echo command should succeed with env vars");
        assertEquals(0, result.exitCode(), "Exit code should be 0");
    }

    /** Creates a TestProject stub for testing. */
    private TestProject createStubProject() {
        return new TestProject(Path.of(".").toAbsolutePath());
    }
}
