package ai.brokk.util;

import ai.brokk.IProject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/** Configuration for a custom command executor (shell, interpreter, etc.) */
public record ExecutorConfig(String executable, List<String> args) {

    public static @Nullable ExecutorConfig fromProject(IProject project) {
        String executor = project.getCommandExecutor();
        String argsStr = project.getExecutorArgs();

        if (executor == null || executor.isBlank()) {
            return null;
        }

        List<String> args;
        if (argsStr == null || argsStr.isBlank()) {
            // Use platform-aware default arguments
            args = getDefaultArgsForExecutor(executor);
        } else {
            args = Arrays.asList(argsStr.split("\\s+"));
        }

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

            // If it's an absolute path, check directly
            if (execPath.isAbsolute()) {
                return Files.exists(execPath) && Files.isExecutable(execPath);
            }

            // For relative paths or bare executables, check if it exists as-is first
            if (Files.exists(execPath) && Files.isExecutable(execPath)) {
                return true;
            }

            // If not found locally, search in PATH
            return isExecutableOnPath(executable);

        } catch (Exception e) {
            return false;
        }
    }

    /** Check if an executable can be found on the system PATH */
    private static boolean isExecutableOnPath(String executable) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) {
            return false;
        }

        // Use manual path separation to avoid errorprone warnings
        List<String> pathDirsList = new ArrayList<>();
        int start = 0;
        int pos;
        while ((pos = pathEnv.indexOf(File.pathSeparator, start)) != -1) {
            if (pos > start) {
                pathDirsList.add(pathEnv.substring(start, pos));
            }
            start = pos + File.pathSeparator.length();
        }
        if (start < pathEnv.length()) {
            pathDirsList.add(pathEnv.substring(start));
        }
        String[] pathDirs = pathDirsList.toArray(new String[0]);
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        boolean isWindows = osName.contains("windows");

        for (String pathDir : pathDirs) {
            try {
                Path dirPath = Path.of(pathDir);
                if (!Files.isDirectory(dirPath)) {
                    continue;
                }

                // On Windows, try with and without .exe extension
                if (isWindows) {
                    Path exePath = dirPath.resolve(executable);
                    if (Files.exists(exePath) && Files.isExecutable(exePath)) {
                        return true;
                    }
                    if (!executable.toLowerCase(Locale.ROOT).endsWith(".exe")) {
                        Path exePathWithExt = dirPath.resolve(executable + ".exe");
                        if (Files.exists(exePathWithExt) && Files.isExecutable(exePathWithExt)) {
                            return true;
                        }
                    }
                } else {
                    // Unix-like systems
                    Path execPath = dirPath.resolve(executable);
                    if (Files.exists(execPath) && Files.isExecutable(execPath)) {
                        return true;
                    }
                }
            } catch (Exception e) {
                // Skip this directory and continue
            }
        }

        return false;
    }

    /** Get platform-appropriate default arguments for a given executor */
    private static List<String> getDefaultArgsForExecutor(String executable) {
        String displayName = getDisplayNameFromExecutable(executable).toLowerCase(Locale.ROOT);

        // PowerShell needs -Command, not -c
        if (displayName.equals("powershell.exe") || displayName.equals("powershell")) {
            return List.of("-Command");
        } else if (displayName.equals("pwsh.exe") || displayName.equals("pwsh")) {
            // PowerShell Core
            return List.of("-Command");
        } else if (displayName.equals("cmd.exe") || displayName.equals("cmd")) {
            return List.of("/c");
        } else {
            // Unix shells and others default to -c
            return List.of("-c");
        }
    }

    /** Helper to extract display name from executable path */
    private static String getDisplayNameFromExecutable(String executable) {
        String name = executable;
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return lastSlash >= 0 ? name.substring(lastSlash + 1) : name;
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
        String displayName = getDisplayName().toLowerCase(Locale.ROOT);

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
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
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
