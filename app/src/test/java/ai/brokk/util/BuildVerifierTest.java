package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.project.IProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildVerifierTest {

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

    @Test
    void testVerifySuccess() {
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            outputConsumer.accept("Build successful");
            outputConsumer.accept("All tests passed");
            return "Build successful\nAll tests passed";
        };

        var project = createTestProject();
        var result = BuildVerifier.verify(project, "echo hello");

        assertTrue(result.success());
        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Build successful"));
    }

    @Test
    void testVerifyFailure() {
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            throw new Environment.FailureException(
                    "Command failed", "Error: compilation failed\nLine 42: syntax error", 1);
        };

        var project = createTestProject();
        var result = BuildVerifier.verify(project, "make build");

        assertFalse(result.success());
        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("compilation failed"));
    }

    @Test
    void testVerifyStartupError() {
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            throw new Environment.StartupException("Command not found", "bash: nonexistent: command not found");
        };

        var project = createTestProject();
        var result = BuildVerifier.verify(project, "nonexistent");

        assertFalse(result.success());
        assertEquals(-1, result.exitCode());
        assertTrue(result.output().toLowerCase().contains("not found"));
    }

    @Test
    void testOutputBounding() {
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            for (int i = 1; i <= 100; i++) {
                String line = "Line " + i;
                outputConsumer.accept(line);
            }
            return "ignored";
        };

        var project = createTestProject();
        var result = BuildVerifier.verify(project, "generate-output");

        assertTrue(result.success());
        assertTrue(result.output().contains("Line 100"));
        assertTrue(result.output().contains("Line 21"));
        assertFalse(result.output().contains("Line 20\n"));
        assertFalse(result.output().startsWith("Line 1"));
    }

    @Test
    void testExtraEnvVars() {
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            outputConsumer.accept("OK");
            return "OK";
        };

        var project = createTestProject();
        var result = BuildVerifier.verify(project, "echo $JAVA_HOME", Map.of("JAVA_HOME", "/usr/lib/jvm/java-21"));

        assertTrue(result.success());
        assertEquals(0, result.exitCode());
    }

    @Test
    void testBuildEnvironmentJdkSentinel() {
        var project = new TestProjectWrapper(tempDir, EnvironmentJava.JAVA_HOME_SENTINEL);
        var env = BuildVerifier.buildEnvironmentForCommand(project, Map.of("FOO", "BAR"));
        assertFalse(env.containsKey("JAVA_HOME"));
        assertEquals("BAR", env.get("FOO"));
    }

    @Test
    void testBuildEnvironmentRelativeJdkPath() throws IOException {
        Path jdkDir = tempDir.resolve("jdks/myjdk");
        Files.createDirectories(jdkDir.resolve("bin"));
        Files.createFile(jdkDir.resolve("bin/java"));
        Files.createFile(jdkDir.resolve("bin/javac"));

        var project = new TestProjectWrapper(tempDir, "jdks/myjdk");
        var env = BuildVerifier.buildEnvironmentForCommand(project, null);

        assertEquals(jdkDir.toAbsolutePath().toString(), env.get("JAVA_HOME"));
    }

    @Test
    void testBuildEnvironmentInvalidJdkPath() throws IOException {
        Path notAJdk = tempDir.resolve("not-a-jdk");
        Files.createDirectories(notAJdk);

        var project = new TestProjectWrapper(tempDir, "not-a-jdk");
        var env = BuildVerifier.buildEnvironmentForCommand(project, null);

        assertFalse(env.containsKey("JAVA_HOME"));
    }

    @Test
    void testBuildEnvironmentMacOsBundle() throws IOException {
        Path bundleDir = tempDir.resolve("JavaApp.jdk");
        Path homeDir = bundleDir.resolve("Contents/Home");
        Files.createDirectories(homeDir.resolve("bin"));
        Files.createFile(homeDir.resolve("bin/java"));
        Files.createFile(homeDir.resolve("bin/javac"));

        var project = new TestProjectWrapper(tempDir, "JavaApp.jdk");
        var env = BuildVerifier.buildEnvironmentForCommand(project, null);

        assertEquals(homeDir.toAbsolutePath().toString(), env.get("JAVA_HOME"));
    }

    private IProject createTestProject() {
        return new TestProjectWrapper(tempDir, null);
    }

    private static class TestProjectWrapper implements IProject {
        private final Path root;
        private final String jdk;

        TestProjectWrapper(Path root, String jdk) {
            this.root = root;
            this.jdk = jdk;
        }

        @Override
        public Path getRoot() {
            return root;
        }

        @Override
        public String getJdk() {
            return jdk;
        }

        @Override
        public void close() {}
    }
}
