package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EnvironmentCommandNormalizationTest {

    @AfterEach
    void resetShellCommandRunnerFactory() {
        Environment.shellCommandRunnerFactory = Environment.DEFAULT_SHELL_COMMAND_RUNNER_FACTORY;
    }

    @Test
    void windowsPowerShellRewritesDotSlashGradlewToDotBackslashGradlewBat() throws Exception {
        String prevOs = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");

            AtomicReference<String> seen = new AtomicReference<>();
            Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
                seen.set(cmd);
                return "";
            };

            Environment.instance.runShellCommand(
                    "./gradlew check",
                    Path.of("."),
                    s -> {},
                    Duration.ofSeconds(1),
                    new ShellConfig("powershell.exe", java.util.List.of("-Command")),
                    java.util.Map.of());

            assertEquals(".\\gradlew.bat check", seen.get());
        } finally {
            if (prevOs != null) {
                System.setProperty("os.name", prevOs);
            }
        }
    }

    @Test
    void windowsCmdRewritesDotSlashGradlewToBareGradlewBat() throws Exception {
        String prevOs = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");

            AtomicReference<String> seen = new AtomicReference<>();
            Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
                seen.set(cmd);
                return "";
            };

            Environment.instance.runShellCommand(
                    "./gradlew check",
                    Path.of("."),
                    s -> {},
                    Duration.ofSeconds(1),
                    new ShellConfig("cmd.exe", java.util.List.of("/c")),
                    java.util.Map.of());

            assertEquals("gradlew.bat check", seen.get());
        } finally {
            if (prevOs != null) {
                System.setProperty("os.name", prevOs);
            }
        }
    }

    @Test
    void nonWindowsKeepsDotSlashGradlewUnchanged() throws Exception {
        String prevOs = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Linux");

            AtomicReference<String> seen = new AtomicReference<>();
            Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
                seen.set(cmd);
                return "";
            };

            Environment.instance.runShellCommand("./gradlew check", Path.of("."), s -> {}, Duration.ofSeconds(1));

            assertEquals("./gradlew check", seen.get());
        } finally {
            if (prevOs != null) {
                System.setProperty("os.name", prevOs);
            }
        }
    }

    @Test
    void windowsCmdRewritesGradlewWhenChainedWithAndAnd() throws Exception {
        String prevOs = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");

            AtomicReference<String> seen = new AtomicReference<>();
            Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
                seen.set(cmd);
                return "";
            };

            Environment.instance.runShellCommand(
                    "echo hi && ./gradlew --quiet check",
                    Path.of("."),
                    s -> {},
                    Duration.ofSeconds(1),
                    new ShellConfig("cmd.exe", java.util.List.of("/c")),
                    java.util.Map.of());

            assertEquals("echo hi && gradlew.bat --quiet check", seen.get());
        } finally {
            if (prevOs != null) {
                System.setProperty("os.name", prevOs);
            }
        }
    }

    @Test
    void windowsCmdRewritesDotSlashGradlewWithLeadingWhitespace() throws Exception {
        String prevOs = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");

            AtomicReference<String> seen = new AtomicReference<>();
            Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
                seen.set(cmd);
                return "";
            };

            Environment.instance.runShellCommand(
                    "  ./gradlew check",
                    Path.of("."),
                    s -> {},
                    Duration.ofSeconds(1),
                    new ShellConfig("cmd.exe", java.util.List.of("/c")),
                    java.util.Map.of());

            assertEquals("  gradlew.bat check", seen.get());
        } finally {
            if (prevOs != null) {
                System.setProperty("os.name", prevOs);
            }
        }
    }
}
