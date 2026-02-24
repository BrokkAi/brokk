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
    void testExecuteShellCommandSuccess() throws InterruptedException {
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            outputConsumer.accept("hello");
            return "hello";
        };

        var project = new TestProject(tempDir);
        var tools = new ShellTools(project);
        String full = tools.executeShellCommand("echo test", null, null, null);

        ShellTools.Result result = parseResult(full);
        assertTrue(result.success());
        assertEquals(0, result.exitCode());
        assertEquals("hello", result.output());
        assertFalse(result.outputTruncated());
        assertNull(result.exception());
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
        assertTrue(result.exception().contains("Failed to start"));
    }

    @Test
    void testExecuteShellCommandOutputTruncation() throws InterruptedException {
        String longOut = "x".repeat(50_000);
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            outputConsumer.accept(longOut);
            return longOut;
        };

        var project = new TestProject(tempDir);
        var tools = new ShellTools(project);
        String full = tools.executeShellCommand("echo test", null, null, null);

        ShellTools.Result result = parseResult(full);
        assertTrue(result.success());
        assertEquals(0, result.exitCode());
        assertTrue(result.output().length() < longOut.length());
        assertTrue(result.output().length() > 0);
        assertTrue(result.outputTruncated());
        assertNull(result.exception());
    }
}
