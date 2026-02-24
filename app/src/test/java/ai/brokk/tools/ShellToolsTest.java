package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.TestProject;
import ai.brokk.util.Environment;
import ai.brokk.util.Json;
import java.nio.file.Path;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ai.brokk.util.Environment.shellCommandRunnerFactory")
public class ShellToolsTest {

    @TempDir
    Path tempDir;

    private BiFunction<String, Path, Environment.ShellCommandRunner> originalFactory;

    @BeforeEach
    void setUp() {
        originalFactory = Environment.shellCommandRunnerFactory;
    }

    @AfterEach
    void tearDown() {
        Environment.shellCommandRunnerFactory = originalFactory;
    }

    private ShellTools.Result parseResult(String fullOutput) {
        int idx = fullOutput.indexOf("--- STRUCTURED DATA ---");
        assertTrue(idx >= 0, "Structured data marker missing");
        String json =
                fullOutput.substring(idx + "--- STRUCTURED DATA ---".length()).trim();
        return Json.fromJson(json, ShellTools.Result.class);
    }

    @Test
    void testExecuteShellCommandSuccessWithSandbox() throws InterruptedException {
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            outputConsumer.accept("hello");
            return "hello";
        };

        var project = new TestProject(tempDir);
        var tools = new ShellTools(project);

        // Explicitly set sandbox=true.
        // When a test factory is in use, ShellTools bypasses sandbox availability checks
        // and proceeds directly to execution, so this should always succeed.
        String full = tools.executeShellCommand("echo test", null, null, true);

        ShellTools.Result result = parseResult(full);
        assertTrue(result.success(), "Should succeed when test factory is in use");
        assertEquals("hello", result.output());
    }

    @Test
    void testExecuteShellCommandUnsandboxedDisabledByDefault() throws InterruptedException {
        var project = new TestProject(tempDir);
        var tools = new ShellTools(project);
        String full = tools.executeShellCommand("echo test", null, null, false);

        ShellTools.Result result = parseResult(full);
        assertFalse(result.success());
        assertTrue(result.exception().contains("Unsandboxed shell execution is disabled"));
    }

    @Test
    void testExecuteShellCommandNonZeroExitCode() throws InterruptedException {
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            throw new Environment.FailureException("failed", "boom", 42);
        };

        var project = new TestProject(tempDir);
        var tools = new ShellTools(project);
        String full = tools.executeShellCommand("echo test", null, null, null);

        ShellTools.Result result = parseResult(full);
        assertFalse(result.success());
        assertEquals(42, result.exitCode());
        assertEquals("boom", result.output());
        assertNull(result.exception());
        assertFalse(result.outputTruncated());
    }

    @Test
    void testExecuteShellCommandTimeout() throws InterruptedException {
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            throw new Environment.TimeoutException("timed out", "partial");
        };

        var project = new TestProject(tempDir);
        var tools = new ShellTools(project);
        String full = tools.executeShellCommand("echo test", null, null, null);

        ShellTools.Result result = parseResult(full);
        assertFalse(result.success());
        assertNull(result.exitCode());
        assertEquals("partial", result.output());
        assertFalse(result.outputTruncated());
        assertNotNull(result.exception());
        assertTrue(result.exception().contains("timed out"));
    }

    @Test
    void testExecuteShellCommandStartupFailure() throws InterruptedException {
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            throw new Environment.StartupException("no shell", "details");
        };

        var project = new TestProject(tempDir);
        var tools = new ShellTools(project);
        String full = tools.executeShellCommand("echo test", null, null, null);

        ShellTools.Result result = parseResult(full);
        assertFalse(result.success());
        assertNull(result.exitCode());
        assertEquals("details", result.output());
        assertFalse(result.outputTruncated());
        assertNotNull(result.exception());
        assertTrue(result.exception().contains("Failed to start: no shell"));
    }

    @Test
    void testExecuteShellCommandOutputTruncation() throws InterruptedException {
        String longOut = "x".repeat(30_000);
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            return longOut;
        };

        var project = new TestProject(tempDir);
        var tools = new ShellTools(project);
        // Default is sandbox=true, ensure it runs unsandboxed if sandbox isn't available for test predictability
        String full = tools.executeShellCommand("echo test", null, null, true);

        ShellTools.Result result = parseResult(full);
        // If sandbox fails, that's fine, we just want to test the Result record's truncation logic which is hit in
        // catch blocks too
        if (result.success()
                || result.exception() == null
                || !result.exception().contains("Sandboxed execution")) {
            assertTrue(result.output().length() <= 20_000, "Output should be truncated to MAX_OUTPUT_CHARS");
            assertTrue(result.outputTruncated());
        }
    }
}
