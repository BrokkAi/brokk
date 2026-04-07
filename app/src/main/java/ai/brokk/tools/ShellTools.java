package ai.brokk.tools;

import ai.brokk.IContextManager;
import ai.brokk.util.Environment;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

public class ShellTools {

    private static final int MAX_OUTPUT_LINES = 200;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    private final IContextManager cm;

    public ShellTools(IContextManager cm) {
        this.cm = cm;
    }

    @Blocking
    @Tool(
            """
        Run a shell command in the project root directory and return its output (stdout and stderr combined).
        Use this for build commands, test runners, linters, or any CLI tool available in the project environment.
        The command runs with a 120-second timeout. Returns the combined output, or an error message if the command fails.
        """)
    public String runShellCommand(
            @P("The shell command to execute, e.g. 'mvn compile' or 'ls -la src/'") String command,
            @P("Why you are running this command") @Nullable String reasoning)
            throws InterruptedException {
        var project = cm.getProject();
        var root = project.getRoot();
        var shellConfig = project.getShellConfig();

        Deque<String> lines = new ArrayDeque<>(MAX_OUTPUT_LINES);

        try {
            Environment.instance.runShellCommand(
                    command,
                    root,
                    line -> {
                        synchronized (lines) {
                            if (lines.size() >= MAX_OUTPUT_LINES) {
                                lines.removeFirst();
                            }
                            lines.addLast(line);
                        }
                    },
                    DEFAULT_TIMEOUT,
                    shellConfig,
                    Map.of());

            synchronized (lines) {
                return String.join("\n", lines);
            }
        } catch (Environment.FailureException e) {
            synchronized (lines) {
                var output = lines.isEmpty() ? e.getOutput() : String.join("\n", lines);
                return "Command failed (exit code %d):\n%s".formatted(e.getExitCode(), output);
            }
        } catch (Environment.TimeoutException e) {
            synchronized (lines) {
                var output = lines.isEmpty() ? e.getOutput() : String.join("\n", lines);
                return "Command timed out after %d seconds:\n%s".formatted(DEFAULT_TIMEOUT.toSeconds(), output);
            }
        } catch (Environment.SubprocessException e) {
            return "Command error: %s\n%s".formatted(e.getMessage(), e.getOutput());
        }
    }
}
