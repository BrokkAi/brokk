package ai.brokk.tools;

import ai.brokk.project.IProject;
import ai.brokk.util.Environment;
import ai.brokk.util.Json;
import ai.brokk.util.ShellConfig;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Provides tools for executing shell commands within the project environment.
 */
public class ShellTools {
    private static final int MAX_OUTPUT_CHARS = 20_000;

    private final IProject project;

    public ShellTools(IProject project) {
        this.project = project;
    }

    /**
     * Executes a shell command using the project's configured executor.
     */
    @Tool(
            "Execute a shell command using the project's configured executor. Returns the command's output, exit code, and success status.")
    @Blocking
    public String executeShellCommand(
            @P("The command text to run in the configured shell.") String command,
            @P("Optional timeout in seconds. If not provided, uses a default project timeout.") @Nullable
                    Long timeoutSeconds,
            @P("Optional map of additional environment variables to merge into the process environment.") @Nullable
                    Map<String, String> environmentVariables,
            @P("Optional flag to request sandboxed execution (macOS only). Defaults to false.") @Nullable
                    Boolean sandbox)
            throws InterruptedException {

        Duration timeout = timeoutSeconds != null && timeoutSeconds > 0
                ? Duration.ofSeconds(timeoutSeconds)
                : Environment.DEFAULT_TIMEOUT;

        Map<String, String> env = Objects.requireNonNullElse(environmentVariables, Collections.emptyMap());
        boolean useSandbox = Objects.requireNonNullElse(sandbox, false);

        ShellConfig config = project.getShellConfig();
        if (config == null || !config.isValid()) {
            config = ShellConfig.basic();
        }

        Result result;
        try {
            String output = Environment.instance.runShellCommand(
                    command,
                    project.getRoot(),
                    useSandbox,
                    line -> {}, // Output is captured by runShellCommand's return value
                    timeout,
                    config,
                    env,
                    null);

            result = Result.success(command, output);
        } catch (Environment.FailureException e) {
            result = Result.failure(command, e.getExitCode(), e.getOutput(), null);
        } catch (Environment.TimeoutException e) {
            result = Result.failure(command, null, e.getOutput(), "Command timed out: " + e.getMessage());
        } catch (Environment.StartupException e) {
            result = Result.failure(command, null, e.getOutput(), "Failed to start command: " + e.getMessage());
        } catch (Environment.SubprocessException e) {
            // Fallback for any future/unknown subclasses of SubprocessException
            result = Result.failure(command, null, e.getOutput(), e.getMessage());
        }

        return formatResult(result);
    }

    private String formatResult(Result result) {
        String status = result.success() ? "Success" : "Failed";
        String exitInfo = result.exitCode() != null ? " (exit code: " + result.exitCode() + ")" : "";

        StringBuilder sb = new StringBuilder();
        sb.append("Command: ").append(result.command()).append("\n");
        sb.append("Status: ").append(status).append(exitInfo).append("\n");

        if (result.exception() != null) {
            sb.append("Error: ").append(result.exception()).append("\n");
        }

        sb.append("\nOutput:\n");
        sb.append(result.output());
        if (result.outputTruncated()) {
            sb.append("\n[Output Truncated...]");
        }

        sb.append("\n\n--- STRUCTURED DATA ---\n");
        sb.append(Json.toJson(result));

        return sb.toString();
    }

    /**
     * Data record for shell execution results.
     */
    public record Result(
            String command,
            boolean success,
            @Nullable Integer exitCode,
            String output,
            boolean outputTruncated,
            @Nullable String exception) {

        public static Result success(String command, String output) {
            return create(command, true, 0, output, null);
        }

        public static Result failure(
                String command, @Nullable Integer exitCode, String output, @Nullable String exception) {
            return create(command, false, exitCode, output, exception);
        }

        private static Result create(
                String command,
                boolean success,
                @Nullable Integer exitCode,
                String output,
                @Nullable String exception) {
            boolean truncated = output.length() > MAX_OUTPUT_CHARS;
            String finalOutput = truncated ? output.substring(0, MAX_OUTPUT_CHARS) : output;
            return new Result(command, success, exitCode, finalOutput, truncated, exception);
        }
    }
}
