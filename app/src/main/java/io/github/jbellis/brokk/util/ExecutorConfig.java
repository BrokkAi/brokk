package io.github.jbellis.brokk.util;

import io.github.jbellis.brokk.IProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Configuration for a custom command executor (shell, interpreter, etc.)
 */
public record ExecutorConfig(String executable, List<String> args) {

    public static @Nullable ExecutorConfig fromProject(IProject project) {
        String executor = project.getCommandExecutor();
        String argsStr = project.getExecutorArgs();

        if (executor == null || executor.isBlank()) {
            return null;
        }

        List<String> args = (argsStr == null || argsStr.isBlank())
            ? List.of("-c")
            : Arrays.asList(argsStr.split("\\s+"));

        return new ExecutorConfig(executor, args);
    }

    /**
     * Build complete command array for execution
     */
    public String[] buildCommand(String userCommand) {
        String[] result = new String[args.size() + 2];
        result[0] = executable;
        for (int i = 0; i < args.size(); i++) {
            result[i + 1] = args.get(i);
        }
        result[result.length - 1] = userCommand;
        return result;
    }

    /**
     * Check if the executable exists and is executable
     */
    public boolean isValid() {
        try {
            Path execPath = Path.of(executable);
            return Files.exists(execPath) && Files.isExecutable(execPath);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get display name for UI
     */
    public String getDisplayName() {
        Path execPath = Path.of(executable);
        return execPath.getFileName().toString();
    }

    @Override
    public String toString() {
        return executable + " " + String.join(" ", args);
    }
}