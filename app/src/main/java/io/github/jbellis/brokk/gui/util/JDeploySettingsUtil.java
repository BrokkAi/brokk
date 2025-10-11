package io.github.jbellis.brokk.gui.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.util.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility to update the per-user JDeploy JSON config file (e.g. ~/.config/brokk/jdeploy.json) with JVM memory settings.
 */
public class JDeploySettingsUtil {
    private static final Logger logger = LogManager.getLogger(JDeploySettingsUtil.class);

    // JSON config file we maintain in the user's config directory
    private static final String CONFIG_FILE_NAME = "jdeploy.json";

    private JDeploySettingsUtil() {
        // utility
    }

    /**
     * Update the per-user JSON config file with the provided JVM memory settings.
     *
     * <p>Behavior: - Ensures the user's ~/.config/brokk directory exists. - Reads existing ~/.config/brokk/jdeploy.json
     * if present, preserving all non -Xmx args. - Removes any existing -Xmx* args. - If settings.automatic() is false
     * and manualMb() > 0, appends a single -XmxNNNm arg.
     */
    public static void updateJvmMemorySettings(MainProject.JvmMemorySettings settings) {
        var home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            logger.warn("Cannot determine user.home; skipping JDeploy config update");
            return;
        }

        try {
            updateUserJdeployConfig(settings);
        } catch (Exception e) {
            logger.warn("Failed to update user jdeploy config file", e);
        }
    }

    /**
     * Update the per-user jdeploy config file at ~/.config/brokk/jdeploy.json. This preserves all non -Xmx args,
     * removes any existing -Xmx args, and appends a single -XmxNNNm when manual memory is selected with a positive
     * size. If the file or directory do not exist they will be created.
     */
    private static void updateUserJdeployConfig(MainProject.JvmMemorySettings settings) {
        var home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            logger.warn("Cannot determine user.home; skipping user jdeploy config update");
            return;
        }

        var configDir = Path.of(home, ".config", "brokk");
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            logger.warn("Failed to create user config directory {}: {}", configDir, e.getMessage());
            return;
        }

        var configFile = configDir.resolve(CONFIG_FILE_NAME);
        ObjectNode config;

        // Read existing config or create new one
        if (Files.exists(configFile)) {
            try {
                String content = Files.readString(configFile, StandardCharsets.UTF_8);
                JsonNode root = Json.getMapper().readTree(content);
                config = root.isObject() ? (ObjectNode) root : Json.getMapper().createObjectNode();
            } catch (Exception e) {
                logger.warn("Failed to read existing config file {}: {}", configFile, e.getMessage());
                config = Json.getMapper().createObjectNode();
            }
        } else {
            config = Json.getMapper().createObjectNode();
        }

        // Get existing args array or create new one
        ArrayNode args;
        if (config.has("args") && config.get("args").isArray()) {
            args = (ArrayNode) config.get("args");
        } else {
            args = Json.getMapper().createArrayNode();
        }

        // Filter out existing -Xmx args
        ArrayNode filteredArgs = Json.getMapper().createArrayNode();
        for (JsonNode arg : args) {
            String argStr = arg.asText();
            if (!argStr.trim().startsWith("-Xmx")) {
                filteredArgs.add(argStr);
            }
        }

        // Add new -Xmx arg if manual memory is set
        if (!settings.automatic()) {
            int mb = settings.manualMb();
            if (mb > 0) {
                filteredArgs.add("-Xmx" + mb + "m");
                logger.info("Updated user config {}: wrote -Xmx{}m", configFile, mb);
            } else {
                logger.info("Manual memory was non-positive ({} MB); not adding -Xmx to {}", mb, configFile);
            }
        } else {
            logger.info("Automatic memory selected; ensuring no -Xmx in {}", configFile);
        }

        // Update config and write to file
        config.set("args", filteredArgs);

        try {
            String json = Json.toJson(config);
            Files.writeString(configFile, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("Failed to write user config file {}: {}", configFile, e.getMessage());
        }
    }
}
