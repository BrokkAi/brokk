package ai.brokk.util;

import ai.brokk.project.IProject;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Utility for verifying build/test commands by executing them and capturing bounded output.
 */
public final class BuildVerifier {
    private static final Logger logger = LogManager.getLogger(BuildVerifier.class);

    /** Maximum number of output lines to retain in the result */
    public static final int MAX_OUTPUT_LINES = 80;

    /**
     * Result of a build command verification.
     *
     * @param success true if command executed successfully (exit code 0)
     * @param exitCode the exit code, or -1 if execution failed before completion
     * @param output bounded output (last ~80 lines of stdout+stderr combined)
     */
    public record VerificationResult(boolean success, int exitCode, String output) {}

    private BuildVerifier() {}

    /**
     * Verify a build command by executing it, capturing bounded output.
     *
     * @param project the project context (used for executor config and root path)
     * @param command the shell command to execute
     * @param extraEnv optional additional environment variables (may be null or empty)
     * @return VerificationResult with success status, exit code, and bounded output
     */
    public static VerificationResult verify(IProject project, String command, @Nullable Map<String, String> extraEnv) {
        return verifyStreaming(project, command, extraEnv, null);
    }

    /**
     * Verify a build command by executing it, streaming output line-by-line while also capturing bounded output.
     *
     * @param project the project context (used for executor config and root path)
     * @param command the shell command to execute
     * @param extraEnv optional additional environment variables (may be null or empty)
     * @param outputConsumer optional consumer that receives each output line as it is produced
     * @return VerificationResult with success status, exit code, and bounded output
     */
    public static VerificationResult verifyStreaming(
            IProject project,
            String command,
            @Nullable Map<String, String> extraEnv,
            @Nullable Consumer<String> outputConsumer) {
        String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return new VerificationResult(false, -1, "Command is blank.");
        }

        ExecutorConfig execCfg = ExecutorConfig.fromProject(project);
        Path root = project.getRoot();
        Map<String, String> env = extraEnv == null || extraEnv.isEmpty() ? new java.util.HashMap<>() : new java.util.HashMap<>(extraEnv);

        String jdkHome = project.getJdk();
        if (jdkHome != null && !jdkHome.isBlank()) {
            env.put("JAVA_HOME", jdkHome);
        }

        Deque<String> lines = new ArrayDeque<>(MAX_OUTPUT_LINES);

        try {
            Environment.instance.runShellCommand(
                    trimmed,
                    root,
                    line -> {
                        synchronized (lines) {
                            appendBounded(lines, line);
                        }
                        if (outputConsumer != null) {
                            outputConsumer.accept(line);
                        }
                    },
                    Environment.DEFAULT_TIMEOUT,
                    execCfg,
                    env);

            String output;
            synchronized (lines) {
                output = joinLines(lines);
            }
            return new VerificationResult(true, 0, output);
        } catch (Environment.FailureException e) {
            String output;
            synchronized (lines) {
                output = lines.isEmpty()
                        ? boundOutput(String.join("\n", e.getMessage(), e.getOutput()))
                        : joinLines(lines);
            }
            logger.debug("Command verification failed with exit code {}: {}", e.getExitCode(), e.getMessage());
            return new VerificationResult(false, e.getExitCode(), output);
        } catch (Environment.SubprocessException e) {
            String output;
            synchronized (lines) {
                output = lines.isEmpty()
                        ? boundOutput(String.join("\n", e.getMessage(), e.getOutput()))
                        : joinLines(lines);
            }
            logger.debug("Command verification errored: {}", e.getMessage());
            return new VerificationResult(false, -1, output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String output;
            synchronized (lines) {
                output = joinLines(lines);
            }
            return new VerificationResult(false, -1, "Interrupted while running command:\n" + output);
        }
    }

    /**
     * Convenience overload without extra environment variables.
     */
    public static VerificationResult verify(IProject project, String command) {
        return verify(project, command, Map.of());
    }

    private static void appendBounded(Deque<String> lines, String line) {
        if (lines.size() == MAX_OUTPUT_LINES) {
            lines.removeFirst();
        }
        lines.addLast(line);
    }

    private static String joinLines(Deque<String> lines) {
        return lines.stream().collect(Collectors.joining("\n"));
    }

    // Private helper to bound output to last MAX_OUTPUT_LINES
    private static String boundOutput(String fullOutput) {
        if (fullOutput.isBlank()) {
            return "";
        }
        String[] split = fullOutput.split("\\R", -1);
        int start = Math.max(0, split.length - MAX_OUTPUT_LINES);
        return java.util.Arrays.stream(split, start, split.length).collect(Collectors.joining("\n"));
    }
}
