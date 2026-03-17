package ai.brokk.tools;

import ai.brokk.project.IProject;
import ai.brokk.util.BuildVerifier;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Blocking;

public final class HostCommandTool {
    private final IProject project;

    public HostCommandTool(IProject project) {
        this.project = project;
    }

    @Tool("Run a shell command in the project root and return a concise bounded summary of the result.")
    @Blocking
    public ToolOutput runBashCommand(@P("Shell command to execute") String command) {
        String trimmed = command.trim();
        String displayedCommand = trimmed.isEmpty() ? "(blank)" : trimmed;
        if (trimmed.isEmpty()) {
            return new CommandOutput(formatResult("invalid", displayedCommand, -1, "Command is blank."));
        }

        var verification = BuildVerifier.verify(project, trimmed, Map.of());
        String status = verification.success() ? "ok" : "failed";
        return new CommandOutput(formatResult(status, displayedCommand, verification.exitCode(), verification.output()));
    }

    public record CommandOutput(String llmText) implements ToolOutput {}

    private static String formatResult(String status, String command, int exitCode, String output) {
        List<String> lines = new ArrayList<>();
        lines.add("runBashCommand: " + status);
        lines.add("command: " + command);
        if (exitCode >= 0) {
            lines.add("exit_code: " + exitCode);
        }

        String normalizedOutput = output.strip();
        if (!normalizedOutput.isBlank()) {
            lines.add("output:\n" + normalizedOutput);
        }

        return String.join("\n", lines);
    }
}
