package io.github.jbellis.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class EnvironmentTest {
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
        String output = Environment.instance.runShellCommand(
                "echo hello > test.txt && cat test.txt",
                tmpRoot,
                true,
                s -> {}, // no-op consumer
                Environment.UNLIMITED_TIMEOUT);
        assertEquals("hello", output.trim());

        String fileContents = Files.readString(tmpRoot.resolve("test.txt"), StandardCharsets.UTF_8)
                .trim();
        assertEquals("hello", fileContents);
    }

    @Test
    void cannotWriteOutsideSandbox() throws Exception {
        Assumptions.assumeTrue(
                Environment.isSandboxAvailable(), "Required sandboxing tool not available on this platform");
        Assumptions.assumeFalse(Environment.isWindows(), "Sandboxing not supported on Windows for this test");

        Path tmpRoot = Files.createTempDirectory(Environment.getHomePath(), "brokk-sandbox-test");
        Path outsideTarget = Environment.getHomePath().resolve("brokk-outside-test-" + System.nanoTime() + ".txt");

        String cmd = "echo fail > '" + outsideTarget + "'";
        assertThrows(
                Environment.FailureException.class,
                () -> Environment.instance.runShellCommand(cmd, tmpRoot, true, s -> {}, Environment.UNLIMITED_TIMEOUT));

        assertFalse(Files.exists(outsideTarget), "File should not have been created outside sandbox");
    }

    @Test
    void testNegativeTimeoutThrowsException() {
        Path tmpRoot = Path.of(System.getProperty("java.io.tmpdir"));

        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    Environment.instance.runShellCommand(
                            "echo test", tmpRoot, s -> {}, java.time.Duration.ofSeconds(-1));
                },
                "Negative timeout should throw IllegalArgumentException");
    }
}
