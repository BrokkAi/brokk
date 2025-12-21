package ai.brokk.util;

import ai.brokk.project.IProject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Configuration for a custom command executor (shell, interpreter, etc.) */
public record ShellConfig(String executable, List<String> args) {
    private static final int TEST_TIMEOUT_SECONDS = 1;
    private static final Logger logger = LogManager.getLogger(ShellConfig.class);

    /** Get the system default shell configuration based on the current OS */
    public static ShellConfig basic() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (osName.contains("windows")) {
            return new ShellConfig("powershell.exe", List.of("-Command"));
        } else {
            return new ShellConfig("/bin/sh", List.of("-c"));
        }
    }

    public static ShellConfig fromProject(IProject project) {
        String executor = project.getCommandShell();
        String argsStr = project.getShellArgs();

        if (executor == null || executor.isBlank()) {
            return basic();
        }

        List<String> args;
        if (argsStr == null || argsStr.isBlank()) {
            args = getDefaultArgsForExecutor(executor);
        } else {
            args = Arrays.asList(argsStr.split("\\s+"));
        }

        return new ShellConfig(executor, args);
    }

    /**
     * Validates an executor by running a simple test command
     *
     * @return ValidationResult with success status and details
     */
    public ValidationResult validate() {
        // First check if executable exists
        if (!isValid()) {
            return new ValidationResult(false, "Executable not found or not executable: " + executable());
        }

        // Test with a platform-appropriate simple command
        String testCommand = getTestCommand();

        try {
            String[] command = buildCommand(testCommand);
            logger.debug("Testing executor with command: {}", Arrays.toString(command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new ValidationResult(false, "Test command timed out after " + TEST_TIMEOUT_SECONDS + " seconds");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return new ValidationResult(false, "Test command failed with exit code: " + exitCode);
            }

            return new ValidationResult(true, "Executor validation successful");

        } catch (IOException e) {
            logger.debug("IOException during executor validation", e);
            return new ValidationResult(false, "Failed to start process: " + e.getMessage());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /** Get an appropriate test command for the given executor */
    String getTestCommand() {
        String displayName = getDisplayName().toLowerCase(Locale.ROOT);

        // PowerShell needs special handling - echo is a cmdlet, not a command
        if (displayName.equals("powershell.exe")
                || displayName.equals("powershell")
                || displayName.equals("pwsh.exe")
                || displayName.equals("pwsh")) {
            // Use Write-Output instead of echo for PowerShell
            return "Write-Output 'test'";
        } else {
            // CMD and Unix shells both use echo
            return "echo test";
        }
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
        if (executable.isBlank() || executable.contains("\0")) {
            return false;
        }
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
        }

        return false;
    }

    /** Get platform-appropriate default arguments for a given executor */
    public static List<String> getDefaultArgsForExecutor(String executable) {
        String displayName = getDisplayNameFromExecutable(executable).toLowerCase(Locale.ROOT);

        return switch (displayName) {
            case "powershell.exe", "powershell", "pwsh.exe", "pwsh" -> List.of("-Command");
            case "cmd.exe", "cmd" -> List.of("/c");
            case "bash", "zsh" -> List.of("-lc");
            case "fish" -> List.of("-ic");
            default -> List.of("-c");
        };
    }

    /** Helper to extract display name from executable path */
    private static String getDisplayNameFromExecutable(String executable) {
        int lastSlash = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
        return lastSlash >= 0 ? executable.substring(lastSlash + 1) : executable;
    }

    /** Get display name for UI */
    public String getDisplayName() {
        // Use manual parsing to ensure cross-platform compatibility
        String name = executable;
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return lastSlash >= 0 ? name.substring(lastSlash + 1) : name;
    }

    /** Get shell language name for Markdown code blocks */
    public String getMarkdownLanguage() {
        String displayName = getDisplayName().toLowerCase(Locale.ROOT);

        // Map common executables to appropriate Markdown language identifiers
        return switch (displayName) {
            case "cmd.exe", "cmd" -> "cmd";
            case "powershell.exe", "powershell" -> "powershell";
            case "fish" -> "fish";
            case "zsh" -> "zsh";
            case "bash" -> "bash";
            case "sh", "dash" -> "sh";
            case "ksh" -> "ksh";
            default ->
                // Use "unknown" for unrecognized executors to be more accurate
                "unknown";
        };
    }

    /** Get shell language name for Markdown code blocks from project configuration */
    public static String getShellLanguageFromProject(IProject project) {
        ShellConfig config = ShellConfig.fromProject(project);
        if (config.isValid()) {
            return config.getMarkdownLanguage();
        }

        return ShellConfig.basic().getMarkdownLanguage();
    }

    @Override
    public String toString() {
        return executable + " " + String.join(" ", args);
    }

    /** Result of executor validation */
    public record ValidationResult(boolean success, String message) {}

    private static final List<ShellConfig> WINDOWS_COMMON_SHELLS = List.of(
            new ShellConfig(
                    Objects.requireNonNullElse(System.getenv("ComSpec"), "C:\\Windows\\System32\\cmd.exe"),
                    List.of("/c")),
            new ShellConfig("C:\\Windows\\System32\\cmd.exe", List.of("/c")),
            new ShellConfig("C:\\Windows\\Sysnative\\cmd.exe", List.of("/c")),
            new ShellConfig("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", List.of("-Command")),
            new ShellConfig("C:\\Windows\\Sysnative\\WindowsPowerShell\\v1.0\\powershell.exe", List.of("-Command")),
            new ShellConfig("C:\\Program Files\\PowerShell\\7\\pwsh.exe", List.of("-Command")),
            new ShellConfig("C:\\Program Files (x86)\\PowerShell\\7\\pwsh.exe", List.of("-Command")));

    private static final List<ShellConfig> UNIX_COMMON_SHELLS = List.of(
            new ShellConfig("/bin/sh", List.of("-c")),
            new ShellConfig("/usr/bin/sh", List.of("-c")),
            new ShellConfig("/bin/bash", List.of("-lc")),
            new ShellConfig("/usr/bin/bash", List.of("-lc")),
            new ShellConfig("/usr/local/bin/bash", List.of("-lc")),
            new ShellConfig("/opt/homebrew/bin/bash", List.of("-lc")),
            new ShellConfig("/bin/zsh", List.of("-lc")),
            new ShellConfig("/usr/bin/zsh", List.of("-lc")),
            new ShellConfig("/usr/local/bin/zsh", List.of("-lc")),
            new ShellConfig("/opt/homebrew/bin/zsh", List.of("-lc")),
            new ShellConfig("/usr/bin/fish", List.of("-ic")),
            new ShellConfig("/usr/local/bin/fish", List.of("-ic")),
            new ShellConfig("/opt/homebrew/bin/fish", List.of("-ic")));

    /** Suggests common executor paths based on the system, filtered by existence on disk */
    public static ShellConfig[] getCommonExecutors() {
        List<ShellConfig> baseExecutors = Environment.isWindows() ? WINDOWS_COMMON_SHELLS : UNIX_COMMON_SHELLS;
        return baseExecutors.stream()
                .filter(cfg -> Files.exists(Path.of(cfg.executable())))
                .distinct()
                .toArray(ShellConfig[]::new);
    }
}
