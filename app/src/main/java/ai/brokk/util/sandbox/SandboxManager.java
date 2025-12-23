package ai.brokk.util.sandbox;

import ai.brokk.util.sandbox.linux.LinuxSandbox;
import ai.brokk.util.sandbox.macos.MacosSandbox;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class SandboxManager {

    public interface CommandRunner {
        CommandResult run(List<String> command) throws IOException, InterruptedException;
    }

    public record CommandResult(int exitCode, String stdout, String stderr) {}

    private final CommandRunner commandRunner;
    private final String osName;
    private SandboxConfig config;

    public SandboxManager(CommandRunner commandRunner) {
        this(commandRunner, System.getProperty("os.name", ""));
    }

    public SandboxManager(CommandRunner commandRunner, String osName) {
        this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner");
        this.osName = osName == null ? "" : osName;
    }

    public void initialize(SandboxConfig config) {
        Objects.requireNonNull(config, "config");
        SandboxConfig validated = config.validate();

        String platform = getPlatform();
        if (!isSupportedPlatform(platform)) {
            throw new IllegalStateException("Sandbox configuration is not supported on platform: " + platform);
        }

        this.config = validated;
    }

    public boolean checkDependencies() {
        String platform = getPlatform();
        if (!isSupportedPlatform(platform)) {
            return false;
        }

        return switch (platform) {
            case "macos" -> hasCommand("sandbox-exec");
            case "linux" -> hasCommand("bwrap");
            default -> false;
        };
    }

    public String wrapWithSandbox(String command, String binShell) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must not be null/blank");
        }
        if (config == null) {
            throw new IllegalStateException("SandboxManager is not initialized");
        }

        String platform = getPlatform();
        if (!isSupportedPlatform(platform)) {
            throw new IllegalStateException("Sandbox configuration is not supported on platform: " + platform);
        }

        SandboxConfig.FilesystemConfig fsConfig = config.filesystem();
        boolean allowGitConfig = fsConfig != null && fsConfig.allowGitConfig();

        return switch (platform) {
            case "macos" -> MacosSandbox.wrapCommand(command, fsConfig, allowGitConfig, binShell);
            case "linux" -> LinuxSandbox.wrapCommand(command, fsConfig, config.linuxOptions(), binShell);
            default -> throw new IllegalStateException("Sandbox configuration is not supported on platform: " + platform);
        };
    }

    private boolean hasCommand(String commandName) {
        try {
            CommandResult r =
                    commandRunner.run(
                            List.of(
                                    "sh",
                                    "-lc",
                                    "command -v " + shellEscape(commandName) + " >/dev/null 2>&1"));
            return r.exitCode() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static String shellEscape(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private String getPlatform() {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return "macos";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        return "unknown";
    }

    private static boolean isSupportedPlatform(String platform) {
        return "macos".equals(platform) || "linux".equals(platform);
    }
}
