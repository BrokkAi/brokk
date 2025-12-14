package ai.brokk.ctl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;

/**
 * Resolves per-user Brokk config locations:
 * - ctl key file path
 * - instances registry directory
 *
 * Default resolution is platform-aware:
 * - Linux: $XDG_CONFIG_HOME/brokk or $HOME/.config/brokk
 * - macOS: $HOME/Library/Application Support/Brokk
 * - Windows: %APPDATA%\\Brokk
 *
 * For tests and overrides use {@link #forBaseConfigDir(Path)}.
 */
public final class CtlConfigPaths {
    private final Path baseConfigDir;

    private CtlConfigPaths(Path baseConfigDir) {
        this.baseConfigDir = Objects.requireNonNull(baseConfigDir);
    }

    /**
     * Compute platform-appropriate defaults based on environment / system properties.
     */
    public static CtlConfigPaths defaults() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean isWindows = os.contains("win");
        boolean isMac = os.contains("mac") || os.contains("darwin");

        if (isWindows) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return new CtlConfigPaths(Paths.get(appData).resolve("Brokk"));
            } else {
                // fallback to user.home
                return new CtlConfigPaths(Paths.get(System.getProperty("user.home")).resolve("AppData").resolve("Roaming").resolve("Brokk"));
            }
        }

        if (isMac) {
            Path home = Paths.get(System.getProperty("user.home"));
            return new CtlConfigPaths(home.resolve("Library").resolve("Application Support").resolve("Brokk"));
        }

        // Assume linux / unix-like
        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return new CtlConfigPaths(Paths.get(xdg).resolve("brokk"));
        }

        Path home = Paths.get(System.getProperty("user.home"));
        return new CtlConfigPaths(home.resolve(".config").resolve("brokk"));
    }

    /**
     * Create a paths object anchored at a provided base configuration directory.
     * Useful for tests.
     */
    public static CtlConfigPaths forBaseConfigDir(Path baseConfigDir) {
        return new CtlConfigPaths(baseConfigDir);
    }

    /**
     * Base per-user Brokk configuration directory (e.g. ~/.config/brokk or ~/Library/Application Support/Brokk)
     */
    public Path getBaseConfigDir() {
        return baseConfigDir;
    }

    /**
     * Directory where the per-instance registry files should be stored.
     * e.g. .../brokk/instances or .../Brokk/instances
     */
    public Path getInstancesDir() {
        return baseConfigDir.resolve("instances");
    }

    /**
     * Path to the shared control key file (ctl.key).
     */
    public Path getCtlKeyPath() {
        return baseConfigDir.resolve("ctl.key");
    }

    /**
     * Ensure base config directory exists (creates parents as needed).
     */
    public Path ensureBaseDirExists() throws java.io.IOException {
        Path dir = getBaseConfigDir();
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        return dir;
    }

    /**
     * Ensure instances directory exists and return it.
     */
    public Path ensureInstancesDirExists() throws java.io.IOException {
        Path dir = getInstancesDir();
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        return dir;
    }
}
