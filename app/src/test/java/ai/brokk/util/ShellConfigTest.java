package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.project.IProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ShellConfigTest {

    @TempDir
    Path tempDir;

    Path validExecutable;
    Path invalidExecutable;

    @BeforeEach
    void setUp() throws IOException {
        // Create a valid executable file
        validExecutable = tempDir.resolve("valid_executor");
        Files.createFile(validExecutable);
        if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
            Files.setPosixFilePermissions(
                    validExecutable,
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));
        }

        // Create a non-executable file
        invalidExecutable = tempDir.resolve("invalid_executor");
        Files.createFile(invalidExecutable);
    }

    @Test
    void testFromProjectWithNullExecutor() {
        var mockProject = new TestProject(null, null);
        var config = ShellConfig.fromProject(mockProject);
        assertNotNull(config);
        assertEquals(ShellConfig.basic().executable(), config.executable());
    }

    @Test
    void testFromProjectWithBlankExecutor() {
        var mockProject = new TestProject("  ", null);
        var config = ShellConfig.fromProject(mockProject);
        assertNotNull(config);
        assertEquals(ShellConfig.basic().executable(), config.executable());
    }

    @Test
    void testFromProjectWithValidExecutor() {
        var mockProject = new TestProject("/bin/bash", null);
        var config = ShellConfig.fromProject(mockProject);
        assertNotNull(config);
        assertEquals("/bin/bash", config.executable());
        assertEquals(List.of("-lc"), config.args());
    }

    @Test
    void testFromProjectWithCustomArgs() {
        var mockProject = new TestProject("/bin/zsh", "-x -c");
        var config = ShellConfig.fromProject(mockProject);
        assertNotNull(config);
        assertEquals("/bin/zsh", config.executable());
        assertEquals(List.of("-x", "-c"), config.args());
    }

    @Test
    void testFromProjectWithBlankArgs() {
        var mockProject = new TestProject("/bin/bash", "  ");
        var config = ShellConfig.fromProject(mockProject);
        assertNotNull(config);
        assertEquals("/bin/bash", config.executable());
        assertEquals(List.of("-lc"), config.args());
    }

    @Test
    void testBuildCommand() {
        var config = new ShellConfig("/bin/bash", List.of("-c"));
        var command = config.buildCommand("echo test");
        assertArrayEquals(new String[] {"/bin/bash", "-c", "echo test"}, command);
    }

    @Test
    void testBuildCommandWithMultipleArgs() {
        var config = new ShellConfig("/usr/bin/python3", List.of("-u", "-c"));
        var command = config.buildCommand("print('hello')");
        assertArrayEquals(new String[] {"/usr/bin/python3", "-u", "-c", "print('hello')"}, command);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void testIsValidOnLinux() {
        // Test with a known valid executable
        var config = new ShellConfig("/bin/sh", List.of("-c"));
        assertTrue(config.isValid());

        // Test with non-existent executable
        var invalidConfig = new ShellConfig("/nonexistent/path", List.of("-c"));
        assertFalse(invalidConfig.isValid());
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void testIsValidOnMac() {
        // Test with a known valid executable
        var config = new ShellConfig("/bin/sh", List.of("-c"));
        assertTrue(config.isValid());

        // Test with non-existent executable
        var invalidConfig = new ShellConfig("/nonexistent/path", List.of("-c"));
        assertFalse(invalidConfig.isValid());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void testIsValidOnWindows() {
        // Test with a known valid executable
        var config = new ShellConfig("cmd.exe", List.of("/c"));
        assertTrue(config.isValid());

        // Test with non-existent executable
        var invalidConfig = new ShellConfig("nonexistent.exe", List.of("/c"));
        assertFalse(invalidConfig.isValid());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void testIsValidWithTempFiles() {
        // Test with valid executable created in temp directory
        var validConfig = new ShellConfig(validExecutable.toString(), List.of("-c"));
        assertTrue(validConfig.isValid());

        // Test with non-executable file
        var invalidConfig = new ShellConfig(invalidExecutable.toString(), List.of("-c"));
        assertFalse(invalidConfig.isValid());
    }

    @Test
    void testGetDisplayName() {
        var config = new ShellConfig("/usr/local/bin/fish", List.of("-c"));
        assertEquals("fish", config.getDisplayName());

        var windowsConfig = new ShellConfig("cmd.exe", List.of("/c"));
        assertEquals("cmd.exe", windowsConfig.getDisplayName());

        var windowsFullPathConfig = new ShellConfig("C:\\Windows\\System32\\cmd.exe", List.of("/c"));
        assertEquals("cmd.exe", windowsFullPathConfig.getDisplayName());
    }

    @Test
    void testToString() {
        var config = new ShellConfig("/bin/bash", List.of("-x", "-c"));
        assertEquals("/bin/bash -x -c", config.toString());

        var singleArgConfig = new ShellConfig("python", List.of("-c"));
        assertEquals("python -c", singleArgConfig.toString());
    }

    @Test
    void testExceptionHandlingInIsValid() {
        // Test with invalid path that might cause exceptions
        var config = new ShellConfig("\0invalid\0path", List.of("-c"));
        assertFalse(config.isValid());
    }

    /** Mock project implementation for testing */
    private static class TestProject implements IProject {
        private final String executor;
        private final String args;

        public TestProject(String executor, String args) {
            this.executor = executor;
            this.args = args;
        }

        @Override
        public String getCommandShell() {
            return executor;
        }

        @Override
        public String getShellArgs() {
            return args;
        }

        @Override
        public void close() {
            // No-op for testing
        }
    }
}
