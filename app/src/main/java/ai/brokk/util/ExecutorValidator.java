package ai.brokk.util;

import ai.brokk.util.sandbox.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/** Utility class for validating and testing custom command executors */
public class ExecutorValidator {
    private static final Logger logger = LogManager.getLogger(ExecutorValidator.class);
    private static final int TEST_TIMEOUT_SECONDS = 5;

    /**
     * Validates an executor by running a simple test command
     *
     * @param config The executor configuration to test
     * @return ValidationResult with success status and details
     */
    public static ValidationResult validateExecutor(@Nullable ExecutorConfig config) {
        if (config == null) {
            return new ValidationResult(false, "Executor config is null");
        }

        // First check if executable exists
        if (!config.isValid()) {
            return new ValidationResult(false, "Executable not found or not executable: " + config.executable());
        }

        // Test with a platform-appropriate simple command
        String testCommand = getTestCommandForExecutor(config);

        try {
            String[] command = config.buildCommand(testCommand);
            logger.debug("Testing executor with command: {}", Arrays.toString(command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new ValidationResult(false, "Test command timed out after " + TEST_TIMEOUT_SECONDS + " seconds");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return new ValidationResult(false, "Test command failed with exit code: " + exitCode);
            }

            return new ValidationResult(true, "Executor validation successful");

        } catch (IOException e) {
            logger.debug("IOException during executor validation", e);
            return new ValidationResult(false, "Failed to start process: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ValidationResult(false, "Validation interrupted");
        } catch (Exception e) {
            logger.debug("Unexpected error during executor validation", e);
            return new ValidationResult(false, "Unexpected error: " + e.getMessage());
        }
    }

    /** Gets a user-friendly error message for common executor issues */
    public static String getHelpMessage(@Nullable ExecutorConfig config) {
        if (config == null) {
            return "No executor configured.";
        }

        Path execPath = Path.of(config.executable());

        if (!Files.exists(execPath)) {
            return String.format(
                    "Executable '%s' not found. Please check the path and ensure the executable exists.",
                    config.executable());
        }

        if (!Files.isExecutable(execPath)) {
            return String.format(
                    "File '%s' exists but is not executable. Check file permissions.", config.executable());
        }

        String displayName = config.getDisplayName().toLowerCase(Locale.ROOT);
        if (displayName.contains("powershell") || displayName.contains("pwsh")) {
            return "PowerShell executor test failed. Ensure it supports '-Command' parameter or adjust executor arguments.";
        } else if (displayName.contains("cmd")) {
            return "CMD executor test failed. Ensure it supports '/c' parameter or adjust executor arguments.";
        } else {
            return "Executor appears valid but test failed. Check if the executor supports the '-c' flag or adjust executor arguments.";
        }
    }

    /** Suggests common executor paths based on the system */
    public static String[] getCommonExecutors() {
        if (isWindows()) {
            return new String[] {
                "cmd.exe",
                "powershell.exe",
                "C:\\Windows\\System32\\cmd.exe",
                "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
            };
        } else {
            return new String[] {
                "/bin/sh", "/bin/bash", "/bin/zsh", "/usr/bin/fish", "/usr/local/bin/bash", "/usr/local/bin/zsh"
            };
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
    }

    /**
     * Validates that sandbox runtime dependencies are available on the current platform. On Linux, checks for bwrap
     * (bubblewrap). On macOS, checks for sandbox-exec. On Windows, sandbox is not supported.
     *
     * @return ValidationResult with success status and details about missing dependencies
     */
    public static ValidationResult validateSandboxAvailable() {
        Platform platform = Platform.getPlatform();

        if (platform == Platform.WINDOWS) {
            return new ValidationResult(false, "Sandbox is not supported on Windows");
        }

        if (platform == Platform.UNKNOWN) {
            return new ValidationResult(false, "Sandbox is not supported on this platform");
        }

        try {
            SandboxBridge bridge = new SandboxBridge(Path.of("."), false, null);
            boolean available = bridge.isAvailable();

            if (available) {
                return new ValidationResult(true, "Sandbox runtime is available");
            }

            if (platform == Platform.LINUX) {
                return new ValidationResult(
                        false,
                        "Sandbox not available: bwrap (bubblewrap) is not installed or user namespaces are disabled. "
                                + "Install with: apt install bubblewrap (Debian/Ubuntu) or dnf install bubblewrap (Fedora)");
            } else if (platform == Platform.MACOS) {
                return new ValidationResult(false, "Sandbox not available: sandbox-exec is missing or not accessible");
            } else {
                return new ValidationResult(false, "Sandbox runtime is not available");
            }
        } catch (Exception e) {
            logger.debug("Exception during sandbox availability check", e);
            return new ValidationResult(false, "Sandbox availability check failed: " + e.getMessage());
        }
    }

    /** Get an appropriate test command for the given executor */
    private static String getTestCommandForExecutor(ExecutorConfig config) {
        String displayName = config.getDisplayName().toLowerCase(Locale.ROOT);

        // PowerShell needs special handling - echo is a cmdlet, not a command
        if (displayName.equals("powershell.exe")
                || displayName.equals("powershell")
                || displayName.equals("pwsh.exe")
                || displayName.equals("pwsh")) {
            // Use Write-Output instead of echo for PowerShell
            return "Write-Output 'test'";
        } else {
            // CMD and Unix shells both use echo
            return "echo test";
        }
    }

    /**
     * Determines if an executor is approved for use in sandbox mode AND sandbox runtime is available. Returns true
     * only if both the executor is a trusted shell in standard system locations AND the sandbox runtime (bwrap on
     * Linux, sandbox-exec on macOS) is available.
     */
    public static boolean isApprovedForSandbox(@Nullable ExecutorConfig config) {
        if (config == null) {
            return false;
        }

        String executable = config.executable();

        boolean inApprovedList = Arrays.asList(getApprovedSandboxExecutors()).contains(executable);
        if (!inApprovedList) {
            return false;
        }

        ValidationResult sandboxCheck = validateSandboxAvailable();
        return sandboxCheck.success();
    }

    /**
     * Gets list of executors approved for sandbox use on macOS. These are trusted shells in standard system locations.
     */
    public static String[] getApprovedSandboxExecutors() {
        if (isWindows()) {
            return new String[] {
                "cmd.exe",
                "C:\\Windows\\System32\\cmd.exe",
                "powershell.exe",
                "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                "pwsh.exe" // PowerShell Core
            };
        } else {
            return new String[] {
                "/bin/sh", // POSIX standard shell (always safe)
                "/bin/bash", // Bash shell
                "/bin/zsh", // Z shell (macOS default)
                "/bin/dash", // Debian Almquist shell
                "/usr/bin/ksh", // Korn shell
                "/usr/bin/fish" // Fish shell (if installed in standard location)
            };
        }
    }

    /** Gets a user-friendly message explaining sandbox executor limitations */
    public static String getSandboxLimitation(@Nullable ExecutorConfig config) {
        ValidationResult sandboxCheck = validateSandboxAvailable();
        if (!sandboxCheck.success()) {
            return sandboxCheck.message();
        }

        if (config == null) {
            return "No custom executor configured. Default shell will be used for sandboxed execution.";
        }

        String executable = config.executable();
        boolean inApprovedList = Arrays.asList(getApprovedSandboxExecutors()).contains(executable);

        if (inApprovedList) {
            return String.format("Custom executor '%s' is approved for sandbox use.", config.getDisplayName());
        }

        return String.format(
                "Custom executor '%s' is not approved for sandbox use. "
                        + "Sandbox mode will use /bin/sh instead. "
                        + "Approved executors: %s",
                config.getDisplayName(), String.join(", ", getApprovedSandboxExecutors()));
    }

    /** Result of executor validation */
    public record ValidationResult(boolean success, String message) {}
}
