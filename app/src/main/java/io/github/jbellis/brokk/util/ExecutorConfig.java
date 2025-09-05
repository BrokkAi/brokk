package io.github.jbellis.brokk.util;

import io.github.jbellis.brokk.IProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/** Configuration for a custom command executor (shell, interpreter, etc.) */
public record ExecutorConfig(String executable, List<String> args) {

    public static @Nullable ExecutorConfig fromProject(IProject project) {
        String executor = project.getCommandExecutor();
        String argsStr = project.getExecutorArgs();

        if (executor == null || executor.isBlank()) {
            return null;
        }

        List<String> args =
                (argsStr == null || argsStr.isBlank()) ? List.of("-c") : Arrays.asList(argsStr.split("\\s+"));

        return new ExecutorConfig(executor, args);
    }

    /** Build complete command array for execution */
    public String[] buildCommand(String userCommand) {
        String[] result = new String[args.size() + 2];
        result[0] = executable;
        for (int i = 0; i < args.size(); i++) {
            result[i + 1] = args.get(i);
        }
        result[result.length - 1] = userCommand;
        return result;
    }

    /** Check if the executable exists and is executable */
    public boolean isValid() {
        try {
            Path execPath = Path.of(executable);
            return Files.exists(execPath) && Files.isExecutable(execPath);
        } catch (Exception e) {
            return false;
        }
    }

    /** Get display name for UI */
    public String getDisplayName() {
        // Use manual parsing to ensure cross-platform compatibility
        String name = executable;
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return lastSlash >= 0 ? name.substring(lastSlash + 1) : name;
    }

    /** Get shell language name for markdown code blocks */
    public String getShellLanguage() {
        String displayName = getDisplayName().toLowerCase(java.util.Locale.ROOT);

        // Map common executables to appropriate markdown language identifiers
        if (displayName.equals("cmd.exe") || displayName.equals("cmd")) {
            return "cmd";
        } else if (displayName.equals("powershell.exe") || displayName.equals("powershell")) {
            return "powershell";
        } else if (displayName.equals("fish")) {
            return "fish";
        } else if (displayName.equals("zsh")) {
            return "zsh";
        } else if (displayName.equals("bash")) {
            return "bash";
        } else if (displayName.equals("sh") || displayName.equals("dash")) {
            return "sh";
        } else if (displayName.equals("ksh")) {
            return "ksh";
        } else {
            // Use "unknown" for unrecognized executors to be more accurate
            return "unknown";
        }
    }

    /** Get shell language name for markdown code blocks from project configuration */
    public static String getShellLanguageFromProject(@Nullable IProject project) {
        if (project == null) {
            return getSystemDefaultShellLanguage();
        }

        ExecutorConfig config = ExecutorConfig.fromProject(project);
        if (config != null && config.isValid()) {
            return config.getShellLanguage();
        }

        return getSystemDefaultShellLanguage();
    }

    /** Get system default shell language based on OS */
    private static String getSystemDefaultShellLanguage() {
        String osName = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        if (osName.contains("windows")) {
            return "cmd";
        } else {
            return "sh"; // Unix systems default to sh (POSIX shell)
        }
    }

    @Override
    public String toString() {
        return executable + " " + String.join(" ", args);
    }
}
