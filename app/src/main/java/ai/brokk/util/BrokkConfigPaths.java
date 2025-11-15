package ai.brokk.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Central utility for resolving Brokk's global configuration directory and handling
 * migration from legacy paths.
 *
 * <p>Provides platform-appropriate paths:
 * - Windows: %APPDATA%/Brokk (fallback: ~/AppData/Roaming/Brokk)
 * - macOS: ~/Library/Application Support/Brokk
 * - Linux: $XDG_CONFIG_HOME/Brokk (fallback: ~/.config/Brokk)
 *
 * <p>All global configuration files (ui.properties, brokk.properties, projects.properties)
 * should reside in this directory to ensure consistency across the application.
 *
 * <p>This class also handles one-time migration of config files from the legacy lowercase
 * "brokk" directory to the unified platform-appropriate "Brokk" directory. This migration
 * is necessary because MainProject previously used a hardcoded ~/.config/brokk directory
 * on all platforms, while GlobalUiSettings used the platform-appropriate path with capital
 * "Brokk". On case-sensitive filesystems (Linux), these were different directories.
 */
public final class BrokkConfigPaths {
    private static final Logger logger = LogManager.getLogger(BrokkConfigPaths.class);

    private static final List<String> FILES_TO_MIGRATE = List.of("brokk.properties", "projects.properties", "oom.flag");

    private BrokkConfigPaths() {}

    /**
     * Returns the platform-appropriate global configuration directory for Brokk.
     *
     * <p>The directory name "Brokk" (capital B) is used consistently across all platforms.
     *
     * @return the global config directory path
     */
    public static Path getGlobalConfigDir() {
        return getGlobalConfigDir(Optional.empty());
    }

    /**
     * Returns the platform-appropriate global configuration directory for Brokk.
     *
     * <p>The directory name "Brokk" (capital B) is used consistently across all platforms.
     *
     * @param configDirOverride optional override for the config directory (for testing)
     * @return the global config directory path
     */
    static Path getGlobalConfigDir(Optional<String> configDirOverride) {
        return configDirOverride
                .filter(s -> !s.isBlank())
                .flatMap(override -> {
                    try {
                        return Optional.of(Path.of(override));
                    } catch (Exception e) {
                        logger.warn("Invalid override for config dir='{}': {}", override, e.getMessage());
                        return Optional.empty();
                    }
                })
                .orElseGet(() -> {
                    var os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
                    if (os.contains("win")) {
                        var appData = System.getenv("APPDATA");
                        Path base = (appData != null && !appData.isBlank())
                                ? Path.of(appData)
                                : Path.of(System.getProperty("user.home"), "AppData", "Roaming");
                        return base.resolve("Brokk");
                    } else if (os.contains("mac")) {
                        return Path.of(System.getProperty("user.home"), "Library", "Application Support", "Brokk");
                    } else {
                        var xdg = System.getenv("XDG_CONFIG_HOME");
                        Path base = (xdg != null && !xdg.isBlank())
                                ? Path.of(xdg)
                                : Path.of(System.getProperty("user.home"), ".config");
                        return base.resolve("Brokk");
                    }
                });
    }

    /**
     * Returns the legacy global configuration directory (lowercase "brokk") that was
     * incorrectly used by MainProject before the fix.
     *
     * <p>This is used for migration purposes only. On case-sensitive filesystems (Linux),
     * this will be a different directory than getGlobalConfigDir().
     *
     * @return the legacy config directory path (~/.config/brokk)
     */
    static Path getLegacyConfigDir() {
        return Path.of(System.getProperty("user.home"), ".config", "brokk");
    }

    /**
     * Attempts to migrate config files from legacy directory to unified directory.
     * This method is idempotent and safe to call multiple times.
     *
     * <p>Migration logic:
     * - Migrates files individually from legacy to new directory
     * - Skips files that already exist in the target directory (never overwrites)
     * - If legacy directory doesn't exist: no action needed
     *
     * @return true if any files were migrated, false otherwise
     */
    public static boolean attemptMigration() {
        return attemptMigration(Optional.empty());
    }

    /**
     * Attempts to migrate config files from legacy directory to unified directory.
     * This method is idempotent and safe to call multiple times.
     *
     * <p>Migration logic:
     * - Migrates files individually from legacy to new directory
     * - Skips files that already exist in the target directory (never overwrites)
     * - If legacy directory doesn't exist: no action needed
     *
     * @param configDirOverride optional override for the config directory (for testing)
     * @return true if any files were migrated, false otherwise
     */
    static boolean attemptMigration(Optional<String> configDirOverride) {
        Path newConfigDir = getGlobalConfigDir(configDirOverride);
        Path legacyConfigDir = getLegacyConfigDir();

        // If directories are the same (shouldn't happen, but be safe)
        if (newConfigDir.equals(legacyConfigDir)) {
            logger.debug("Config directories are identical, no migration needed");
            return false;
        }

        if (!Files.exists(legacyConfigDir)) {
            logger.debug("Legacy config directory does not exist, no migration needed");
            return false;
        }

        // Proceed with file-by-file migration
        try {
            logger.debug("Migrating config files from {} to {}", legacyConfigDir, newConfigDir);

            // Create new directory if it doesn't exist
            Files.createDirectories(newConfigDir);

            int migratedCount = 0;
            for (String fileName : FILES_TO_MIGRATE) {
                Path legacyFile = legacyConfigDir.resolve(fileName);
                Path newFile = newConfigDir.resolve(fileName);

                if (Files.exists(legacyFile) && !Files.exists(newFile)) {
                    Files.copy(legacyFile, newFile, StandardCopyOption.COPY_ATTRIBUTES);
                    logger.debug("Migrated config file: {}", fileName);
                    migratedCount++;

                    // Backup the original file
                    try {
                        Path backupFile = legacyFile.resolveSibling(fileName + ".bak");
                        // Move on the same file system is safe
                        Files.move(legacyFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
                        logger.debug("Backed up original config file to: {}", backupFile);
                    } catch (IOException e) {
                        logger.warn("Failed to backup original file {}: {}", fileName, e.getMessage());
                    }
                } else if (Files.exists(legacyFile) && Files.exists(newFile)) {
                    logger.debug("Skipping {}, already exists in target directory", fileName);
                }
            }

            if (migratedCount > 0) {
                logger.debug(
                        "Config migration completed: {} file(s) migrated from {} to {}",
                        migratedCount,
                        legacyConfigDir,
                        newConfigDir);
                return true;
            } else {
                logger.debug("No config files found to migrate in {}", legacyConfigDir);
                return false;
            }
        } catch (IOException e) {
            logger.error("Failed to migrate config directory: {}", e.getMessage(), e);
            return false;
        }
    }
}
