package io.github.jbellis.brokk.util;

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
     * Determines if an executor is approved for use in macOS sandbox mode. Only allows common, trusted shell
     * executables in standard system locations.
     */
    public static boolean isApprovedForSandbox(@Nullable ExecutorConfig config) {
        if (config == null) {
            return false;
        }

        String executable = config.executable();

        // Check against approved sandbox executors list
        return Arrays.asList(getApprovedSandboxExecutors()).contains(executable);
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
        if (config == null) {
            return "No custom executor configured for sandbox use.";
        }

        if (isApprovedForSandbox(config)) {
            return String.format("Custom executor '%s' is approved for sandbox use.", config.getDisplayName());
        }

        return String.format(
                "Custom executor '%s' is not approved for sandbox use. " + "Sandbox mode will use /bin/sh instead. "
                        + "Approved executors: %s",
                config.getDisplayName(), String.join(", ", getApprovedSandboxExecutors()));
    }

    /** Result of executor validation */
    public record ValidationResult(boolean success, String message) {}
}
