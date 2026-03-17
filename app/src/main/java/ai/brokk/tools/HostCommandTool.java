package ai.brokk.tools;

import ai.brokk.project.IProject;
import ai.brokk.util.BuildVerifier;
import ai.brokk.util.Environment;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

public final class HostCommandTool {
    private static final int MAX_OUTPUT_LINES = BuildVerifier.MAX_OUTPUT_LINES;

    private final IProject project;

    public HostCommandTool(IProject project) {
        this.project = project;
    }

    @Tool("Run a shell command in the project root and return bounded diagnostic output.")
    @Blocking
    public ToolOutput runBashCommand(@P("Shell command to execute") String command) {
        String trimmed = command.trim();
        String displayedCommand = trimmed.isEmpty() ? "(blank)" : trimmed;
        if (trimmed.isEmpty()) {
            return new CommandOutput(formatResult("Startup failure", displayedCommand, "Command is blank.", null, ""));
        }

        Deque<String> lines = new ArrayDeque<>(MAX_OUTPUT_LINES);
        try {
            String output = Environment.instance.runShellCommand(
                    trimmed,
                    project.getRoot(),
                    line -> appendBounded(lines, line),
                    Environment.DEFAULT_TIMEOUT,
                    project.getShellConfig(),
                    Map.of());
            return new CommandOutput(formatResult(
                    "Success", displayedCommand, null, null, chooseSuccessOutput(lines, output)));
        } catch (Environment.FailureException e) {
            OutputCapture capture = chooseFailureOutput(lines, e.getMessage(), e.getOutput());
            return new CommandOutput(formatResult(
                    "Non-zero exit status",
                    displayedCommand,
                    capture.includesDetails() ? null : safe(e.getMessage()),
                    e.getExitCode(),
                    capture.text()));
        } catch (Environment.StartupException e) {
            OutputCapture capture = chooseFailureOutput(lines, e.getMessage(), e.getOutput());
            return new CommandOutput(formatResult(
                    "Startup failure",
                    displayedCommand,
                    capture.includesDetails() ? null : safe(e.getMessage()),
                    null,
                    capture.text()));
        } catch (Environment.TimeoutException e) {
            OutputCapture capture = chooseFailureOutput(lines, e.getMessage(), e.getOutput());
            return new CommandOutput(formatResult(
                    "Timeout failure",
                    displayedCommand,
                    capture.includesDetails() ? null : safe(e.getMessage()),
                    null,
                    capture.text()));
        } catch (Environment.SubprocessException e) {
            OutputCapture capture = chooseFailureOutput(lines, e.getMessage(), e.getOutput());
            return new CommandOutput(formatResult(
                    "Subprocess failure",
                    displayedCommand,
                    capture.includesDetails() ? null : safe(e.getMessage()),
                    null,
                    capture.text()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandOutput(formatResult(
                    "Interrupted execution",
                    displayedCommand,
                    "Thread was interrupted while waiting for the command to finish.",
                    null,
                    chooseSuccessOutput(lines, "")));
        }
    }

    public record CommandOutput(String llmText) implements ToolOutput {}

    private record OutputCapture(String text, boolean includesDetails) {}

    private static void appendBounded(Deque<String> lines, String line) {
        if (lines.size() == MAX_OUTPUT_LINES) {
            lines.removeFirst();
        }
        lines.addLast(line);
    }

    private static String chooseSuccessOutput(Deque<String> lines, @Nullable String fallbackOutput) {
        String streamedOutput = joinLines(lines);
        return streamedOutput.isBlank() ? boundText(safe(fallbackOutput)) : streamedOutput;
    }

    private static OutputCapture chooseFailureOutput(
            Deque<String> lines, @Nullable String failureMessage, @Nullable String fallbackOutput) {
        String streamedOutput = joinLines(lines);
        if (!streamedOutput.isBlank()) {
            return new OutputCapture(streamedOutput, false);
        }
        return new OutputCapture(boundJoined(failureMessage, fallbackOutput), true);
    }

    private static String joinLines(Deque<String> lines) {
        return lines.stream().collect(Collectors.joining("\n"));
    }

    private static String formatResult(
            String heading, String command, @Nullable String details, @Nullable Integer exitCode, String output) {
        List<String> metadata = new ArrayList<>();
        if (exitCode != null) {
            metadata.add("- Exit code: " + exitCode);
        }
        if (!safe(details).isBlank()) {
            metadata.add("- Details: " + safe(details));
        }

        String metadataSection = metadata.isEmpty() ? "" : "\n" + String.join("\n", metadata);
        String boundedOutput = boundText(output);
        String outputSection = boundedOutput.isBlank() ? "" : "\n\nOutput:\n```\n" + boundedOutput + "\n```";

        return "### " + heading + "\nCommand:\n```\n" + command + "\n```" + metadataSection + outputSection;
    }

    private static String boundJoined(@Nullable String first, @Nullable String second) {
        List<String> parts = new ArrayList<>(2);
        if (!safe(first).isBlank()) {
            parts.add(safe(first));
        }
        if (!safe(second).isBlank()) {
            parts.add(safe(second));
        }
        return boundText(String.join("\n", parts));
    }

    private static String boundText(String text) {
        String normalized = safe(text).trim();
        if (normalized.isBlank()) {
            return "";
        }
        String[] split = normalized.split("\\R", -1);
        int start = Math.max(0, split.length - MAX_OUTPUT_LINES);
        return Arrays.stream(split, start, split.length).collect(Collectors.joining("\n"));
    }

    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }
}
