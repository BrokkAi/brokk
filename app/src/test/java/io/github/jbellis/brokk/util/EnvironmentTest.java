package io.github.jbellis.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Assumptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

class EnvironmentTest {
    @org.junit.jupiter.api.Test
    void helloWorld() {
        System.out.println("Hello, World!");
    }

    @org.junit.jupiter.api.Test
    void helloWorldChroot() throws Exception {
        // Skip when the underlying sandboxing tool is not present
        Assumptions.assumeTrue(Environment.isSandboxAvailable(),
                               "Required sandboxing tool not available on this platform");

        Path tmpRoot = Files.createTempDirectory("brokk-sandbox-test");
        String output = Environment.instance.runShellCommand(
                "echo hello > test.txt && cat test.txt",
                tmpRoot,
                true,
                s -> {}        // no-op consumer
        );
        assertEquals("hello", output.trim());

        String fileContents = Files.readString(tmpRoot.resolve("test.txt"), StandardCharsets.UTF_8).trim();
        assertEquals("hello", fileContents);
    }

    @org.junit.jupiter.api.Test
    void cannotWriteOutsideSandbox() throws Exception {
        Assumptions.assumeTrue(Environment.isSandboxAvailable(),
                               "Required sandboxing tool not available on this platform");
        Assumptions.assumeFalse(Environment.isWindows(),
                                "Sandboxing not supported on Windows for this test");

        Path tmpRoot = Files.createTempDirectory("brokk-sandbox-test");
        Path outsideTarget = Environment.getHomePath()
                                        .resolve("brokk-outside-test-" + System.nanoTime() + ".txt");

        String cmd = "echo fail > '" + outsideTarget.toString() + "'";
        assertThrows(Environment.FailureException.class, () ->
                Environment.instance.runShellCommand(cmd, tmpRoot, true, s -> {}));

        assertFalse(Files.exists(outsideTarget), "File should not have been created outside sandbox");
    }
}
