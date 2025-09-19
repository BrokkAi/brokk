package io.github.jbellis.brokk.gui.util;

import io.github.jbellis.brokk.MainProject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Utility to update the per-user JDeploy argument file (e.g. ~/.brokk/jdeploy.args) with JVM memory settings. */
public class JDeploySettingsUtil {
    private static final Logger logger = LogManager.getLogger(JDeploySettingsUtil.class);

    // Argfile we maintain in the user's home directory
    private static final String ARGFILE_NAME = "jdeploy.args";

    private JDeploySettingsUtil() {
        // utility
    }

    /**
     * Update the per-user argfile with the provided JVM memory settings.
     *
     * <p>Behavior: - Ensures the user's ~/.brokk directory exists. - Reads existing ~/.brokk/jdeploy.args if present,
     * preserving all non -Xmx lines. - Removes any existing -Xmx* lines. - If settings.automatic() is false and
     * manualMb() > 0, appends a single -XmxNNNm line.
     */
    public static void updateJvmMemorySettings(MainProject.JvmMemorySettings settings) {
        Objects.requireNonNull(settings, "settings");

        var home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            logger.warn("Cannot determine user.home; skipping JDeploy user-argfile update");
            return;
        }

        try {
            updateUserJdeployArgs(settings);
        } catch (Exception e) {
            logger.warn("Failed to update user jdeploy argfile", e);
        }
    }

    /**
     * Update the per-user jdeploy args file at ~/.brokk/jdeploy.args so a centralized per-user argfile may be used.
     * This preserves all non -Xmx lines, removes any existing -Xmx lines, and appends a single -XmxNNNm when manual
     * memory is selected with a positive size. If the file or directory do not exist they will be created.
     */
    private static void updateUserJdeployArgs(MainProject.JvmMemorySettings settings) {
        var home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            logger.warn("Cannot determine user.home; skipping user jdeploy args update");
            return;
        }

        var jdeployDir = Path.of(home, ".jdeploy", "gh-packages");
        try {
            Files.createDirectories(jdeployDir);
        } catch (IOException e) {
            logger.warn("Failed to create user .jdeploy directory {}: {}", jdeployDir, e.getMessage());
            return;
        }

        Optional<Path> maybeAppDir;
        try (var childStream = Files.list(jdeployDir)) {
            maybeAppDir = childStream
                    .filter(Files::isDirectory)
                    .filter(dir -> dir.getFileName().toString().endsWith(".brokk"))
                    .findFirst();
            if (maybeAppDir.isEmpty())
                throw new RuntimeException(
                        "Unable to find Brokk JDeploy directory! Brokk must not be running via JDeploy.");
        } catch (Exception e) {
            logger.error(
                    "Exception encountered while determining application directory! Memory settings will not be persisted.");
            return;
        }
        final Path appDir = maybeAppDir.get();

        var userArgfile = appDir.resolve(ARGFILE_NAME);
        List<String> existingLines = new ArrayList<>();
        if (Files.exists(userArgfile)) {
            try {
                existingLines = Files.readAllLines(userArgfile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                logger.warn("Failed to read existing user argfile {}: {}", userArgfile, e.getMessage());
                // fallthrough to create/overwrite below
            }
        } else {
            // Provide a minimal header so file is never empty unless we decide to write no content.
            existingLines.add("# Brokk JVM argument file (managed by Brokk)");
            existingLines.add("");
        }

        // Filter out existing -Xmx lines (trimmed)
        var preserved = new ArrayList<String>();
        for (var line : existingLines) {
            var t = line.trim();
            if (!t.startsWith("-Xmx")) {
                preserved.add(line);
            }
        }

        var sb = new StringBuilder();
        for (var line : preserved) {
            sb.append(line).append('\n');
        }

        if (!settings.automatic()) {
            int mb = settings.manualMb();
            if (mb > 0) {
                sb.append("-Xmx").append(mb).append("m").append('\n');
                logger.info("Updated user argfile {}: wrote -Xmx{}m", userArgfile, mb);
            } else {
                logger.info("Manual memory was non-positive ({} MB); not adding -Xmx to {}", mb, userArgfile);
            }
        } else {
            logger.info("Automatic memory selected; ensuring no -Xmx in {}", userArgfile);
        }

        try {
            Files.writeString(userArgfile, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("Failed to write user argfile {}: {}", userArgfile, e.getMessage());
        }
    }
}
