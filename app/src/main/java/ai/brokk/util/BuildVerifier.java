package ai.brokk.util;

import ai.brokk.project.IProject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Utility for verifying shell commands (e.g., build/lint commands) in the context of a project.
 * Executes a command and returns success status, exit code, and the tail of the output.
 */
public class BuildVerifier {
    private static final Logger logger = LogManager.getLogger(BuildVerifier.class);

    /** Maximum number of output lines to retain in the result tail. */
    private static final int MAX_OUTPUT_LINES = 80;

    /**
     * Result of a build command verification.
     *
     * @param success true if the command completed successfully (exit code 0)
     * @param exitCode the exit code returned by the command
     * @param outputTail the last ~80 lines of stdout+stderr combined
     */
    public record Result(boolean success, int exitCode, String outputTail) {}

    /**
     * Verifies a shell command by executing it with the project's executor configuration.
     *
     * @param project the project providing executor config and timeout
     * @param command the shell command to verify
     * @param environmentVariables optional environment variables to merge with the system environment
     * @return a Result containing success status, exit code, and output tail
     */
    public static Result verify(
            IProject project, String command, @Nullable Map<String, String> environmentVariables) {
        try {
            var root = project.getRoot();
            var execCfg = ExecutorConfig.fromProject(project);
            Duration timeout;
            try {
                var mp = project.getMainProject();
                timeout = (mp != null)
                        ? Duration.ofSeconds(mp.getRunCommandTimeoutSeconds())
                        : Environment.DEFAULT_TIMEOUT;
            } catch (Exception e) {
                timeout = Environment.DEFAULT_TIMEOUT;
            }

            var outputLines = new ArrayList<String>();
            var outputCollector = new StringBuilder();

            try {
                Environment.instance.runShellCommand(
                        command,
                        root,
                        line -> {
                            outputLines.add(line);
                            outputCollector.append(line).append("\n");
                        },
                        timeout,
                        execCfg,
                        environmentVariables != null ? environmentVariables : Map.of());

                // Command succeeded (exit code 0)
                String tail = getTail(outputLines);
                return new Result(true, 0, tail);

            } catch (Environment.FailureException ex) {
                // Command failed but completed (non-zero exit code)
                int exitCode = parseExitCode(ex);
                String tail = getTail(outputLines);
                logger.debug(
                        "Build command verification failed with exit code {}: {}",
                        exitCode,
                        ex.getMessage());
                return new Result(false, exitCode, tail);

            } catch (Environment.SubprocessException ex) {
                // Command execution error (e.g., executable not found, I/O error)
                logger.warn("Build command verification error: {}", ex.getMessage());
                String output = ex.getOutput();
                String tail;
                if (output != null && !output.isEmpty()) {
                    var lines = output.lines().toList();
                    tail = getTail(lines);
                } else {
                    tail = ex.getMessage() != null ? ex.getMessage() : "";
                }
                return new Result(false, -1, tail);
            }

        } catch (Exception ex) {
            logger.error("Unexpected error during build verification", ex);
            return new Result(false, -1, "Error: " + ex.getMessage());
        }
    }

    /**
     * Extracts the tail of the output lines (last ~80 lines).
     *
     * @param lines the full list of output lines
     * @return a string containing the last ~80 lines joined with newlines
     */
    private static String getTail(List<String> lines) {
        int startIdx = Math.max(0, lines.size() - MAX_OUTPUT_LINES);
        StringBuilder sb = new StringBuilder();
        for (int i = startIdx; i < lines.size(); i++) {
            if (i > startIdx) {
                sb.append("\n");
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    /**
     * Parses the exit code from a FailureException if available.
     *
     * @param ex the FailureException
     * @return the exit code, or -1 if unknown
     */
    private static int parseExitCode(Environment.FailureException ex) {
        // The message format is typically "Command exited with code: X"
        String msg = ex.getMessage();
        if (msg != null && msg.contains("code")) {
            try {
                String[] parts = msg.split(":");
                if (parts.length > 1) {
                    return Integer.parseInt(parts[parts.length - 1].trim());
                }
            } catch (NumberFormatException e) {
                // Fall through to return -1
            }
        }
        return -1;
    }
}
