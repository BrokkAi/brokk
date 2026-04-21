package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ai.brokk.util.Environment.shellCommandRunnerFactory")
class EnvironmentTest {
    @BeforeEach
    void setUp() {
        // Ensure the factory is reset to default before each test
        Environment.shellCommandRunnerFactory = Environment.DEFAULT_SHELL_COMMAND_RUNNER_FACTORY;
    }

    @AfterEach
    void tearDown() {
        // Restore to default after each test to avoid affecting other tests
        Environment.shellCommandRunnerFactory = Environment.DEFAULT_SHELL_COMMAND_RUNNER_FACTORY;
    }

    @Test
    void helloWorld() {
        System.out.println("Hello, World!");
    }

    @Test
    void helloWorldChroot() throws Exception {
        // Skip when the underlying sandboxing tool is not present
        Assumptions.assumeTrue(
                Environment.isSandboxAvailable(), "Required sandboxing tool not available on this platform");

        Path tmpRoot = Files.createTempDirectory(Environment.getHomePath(), "brokk-sandbox-test");
        try {
            String output = Environment.instance.runShellCommand(
                    "echo hello > test.txt && cat test.txt",
                    tmpRoot,
                    SandboxPolicy.WORKSPACE_WRITE,
                    s -> {}, // no-op consumer
                    Environment.UNLIMITED_TIMEOUT);
            assertEquals("hello", output.trim());

            String fileContents = Files.readString(tmpRoot.resolve("test.txt"), StandardCharsets.UTF_8)
                    .trim();
            assertEquals("hello", fileContents);
        } finally {
            FileUtil.deleteRecursively(tmpRoot);
        }
    }

    @Test
    void cannotWriteOutsideSandbox() throws Exception {
        Assumptions.assumeTrue(
                Environment.isSandboxAvailable(), "Required sandboxing tool not available on this platform");
        Assumptions.assumeFalse(Environment.isWindows(), "Sandboxing not supported on Windows for this test");

        Path tmpRoot = Files.createTempDirectory(Environment.getHomePath(), "brokk-sandbox-test");
        try {
            Path outsideTarget = Environment.getHomePath().resolve("brokk-outside-test-" + System.nanoTime() + ".txt");

            String cmd = "echo fail > '" + outsideTarget + "'";
            assertThrows(
                    Environment.FailureException.class,
                    () -> Environment.instance.runShellCommand(
                            cmd, tmpRoot, SandboxPolicy.WORKSPACE_WRITE, s -> {}, Environment.UNLIMITED_TIMEOUT));

            assertFalse(Files.exists(outsideTarget), "File should not have been created outside sandbox");
        } finally {
            FileUtil.deleteRecursively(tmpRoot);
        }
    }

    @Test
    void readOnlySandboxBlocksAllWrites() throws Exception {
        Assumptions.assumeTrue(
                Environment.isSandboxAvailable(), "Required sandboxing tool not available on this platform");
        Assumptions.assumeFalse(Environment.isWindows(), "Sandboxing not supported on Windows for this test");

        Path tmpRoot = Files.createTempDirectory(Environment.getHomePath(), "brokk-sandbox-test");
        try {
            // In READ_ONLY mode, even writes inside the workspace root should be blocked
            String cmd = "echo fail > '" + tmpRoot.resolve("inside.txt") + "'";
            assertThrows(
                    Environment.FailureException.class,
                    () -> Environment.instance.runShellCommand(
                            cmd, tmpRoot, SandboxPolicy.READ_ONLY, s -> {}, Environment.UNLIMITED_TIMEOUT));

            assertFalse(
                    Files.exists(tmpRoot.resolve("inside.txt")),
                    "File should not have been created in READ_ONLY sandbox");
        } finally {
            FileUtil.deleteRecursively(tmpRoot);
        }
    }

    @Test
    void testNegativeTimeoutThrowsException(@TempDir Path tmpRoot) {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    Environment.instance.runShellCommand("echo test", tmpRoot, s -> {}, Duration.ofSeconds(-1));
                },
                "Negative timeout should throw IllegalArgumentException");
    }

    @Test
    void isBooleanFlagEnabled_requiresExplicitTrue() {
        assertTrue(Environment.isBooleanFlagEnabled("true"));
        assertTrue(Environment.isBooleanFlagEnabled(" TRUE "));
        assertFalse(Environment.isBooleanFlagEnabled("false"));
        assertFalse(Environment.isBooleanFlagEnabled("0"));
        assertFalse(Environment.isBooleanFlagEnabled(""));
        assertFalse(Environment.isBooleanFlagEnabled(null));
    }
}
