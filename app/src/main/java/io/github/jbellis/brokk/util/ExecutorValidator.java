package io.github.jbellis.brokk.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

/**
 * Utility class for validating and testing custom command executors
 */
public class ExecutorValidator {
    private static final Logger logger = LogManager.getLogger(ExecutorValidator.class);
    private static final int TEST_TIMEOUT_SECONDS = 5;

    /**
     * Validates an executor by running a simple test command
     * @param config The executor configuration to test
     * @return ValidationResult with success status and details
     */
    public static ValidationResult validateExecutor(@Nullable ExecutorConfig config) {
        if (config == null) {
            return new ValidationResult(false, "Executor config is null");
        }

        // First check if executable exists
        if (!config.isValid()) {
            return new ValidationResult(false,
                "Executable not found or not executable: " + config.executable());
        }

        // Test with a simple command that should work on all platforms
        String testCommand = "echo test";

        try {
            String[] command = config.buildCommand(testCommand);
            logger.debug("Testing executor with command: {}", Arrays.toString(command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new ValidationResult(false,
                    "Test command timed out after " + TEST_TIMEOUT_SECONDS + " seconds");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return new ValidationResult(false,
                    "Test command failed with exit code: " + exitCode);
            }

            return new ValidationResult(true, "Executor validation successful");

        } catch (IOException e) {
            logger.debug("IOException during executor validation", e);
            return new ValidationResult(false,
                "Failed to start process: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ValidationResult(false,
                "Validation interrupted");
        } catch (Exception e) {
            logger.debug("Unexpected error during executor validation", e);
            return new ValidationResult(false,
                "Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Gets a user-friendly error message for common executor issues
     */
    public static String getHelpMessage(@Nullable ExecutorConfig config) {
        if (config == null) {
            return "No executor configured.";
        }

        Path execPath = Path.of(config.executable());

        if (!Files.exists(execPath)) {
            return String.format(
                "Executable '%s' not found. Please check the path and ensure the executable exists.",
                config.executable()
            );
        }

        if (!Files.isExecutable(execPath)) {
            return String.format(
                "File '%s' exists but is not executable. Check file permissions.",
                config.executable()
            );
        }

        return "Executor appears valid but test failed. Check if the executor supports the '-c' flag or adjust executor arguments.";
    }

    /**
     * Suggests common executor paths based on the system
     */
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
                "/bin/sh",
                "/bin/bash",
                "/bin/zsh",
                "/usr/bin/fish",
                "/usr/local/bin/bash",
                "/usr/local/bin/zsh"
            };
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
    }

    /**
     * Result of executor validation
     */
    public record ValidationResult(boolean success, String message) {}
}