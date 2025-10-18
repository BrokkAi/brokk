package io.github.jbellis.brokk.gui.util;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.util.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JDeploySettingsUtilTest {

    @TempDir
    Path tempHome;

    /** Helper to invoke updateJvmMemorySettings with a custom user.home */
    private void updateWithCustomHome(MainProject.JvmMemorySettings settings, Path customHome) {
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", customHome.toString());
            JDeploySettingsUtil.updateJvmMemorySettings(settings);
        } finally {
            if (originalHome != null) {
                System.setProperty("user.home", originalHome);
            }
        }
    }

    /** Helper to read and parse the jdeploy.json file */
    private JsonNode readConfig(Path home) throws Exception {
        Path configFile = home.resolve(".config/brokk/jdeploy.json");
        assertTrue(Files.exists(configFile), "Config file should exist");
        String content = Files.readString(configFile);
        return Json.getMapper().readTree(content);
    }

    @Test
    void createsConfigFileWhenItDoesNotExist() throws Exception {
        var settings = new MainProject.JvmMemorySettings(false, 2048);
        updateWithCustomHome(settings, tempHome);

        JsonNode config = readConfig(tempHome);
        assertTrue(config.has("args"), "Config should have args array");
        JsonNode args = config.get("args");
        assertTrue(args.isArray(), "args should be an array");
        assertEquals(1, args.size(), "args should have one element");
        assertEquals("-Xmx2048m", args.get(0).asText());
    }

    @Test
    void automaticModeRemovesXmxArgs() throws Exception {
        // First create a config with manual memory
        var manualSettings = new MainProject.JvmMemorySettings(false, 2048);
        updateWithCustomHome(manualSettings, tempHome);

        JsonNode config = readConfig(tempHome);
        assertEquals(1, config.get("args").size(), "Should have one arg after manual update");

        // Now switch to automatic mode
        var autoSettings = new MainProject.JvmMemorySettings(true, 0);
        updateWithCustomHome(autoSettings, tempHome);

        config = readConfig(tempHome);
        JsonNode args = config.get("args");
        assertEquals(0, args.size(), "Automatic mode should remove -Xmx args");
    }

    @Test
    void preservesNonXmxArgs() throws Exception {
        // Manually create a config file with various args
        Path configDir = tempHome.resolve(".config/brokk");
        Files.createDirectories(configDir);
        Path configFile = configDir.resolve("jdeploy.json");

        String initialConfig =
                """
                {
                  "args": [
                    "-Dfoo=bar",
                    "-Xmx1024m",
                    "-XX:+UseG1GC",
                    "-Xmx512m"
                  ]
                }
                """;
        Files.writeString(configFile, initialConfig);

        // Update with new memory settings
        var settings = new MainProject.JvmMemorySettings(false, 4096);
        updateWithCustomHome(settings, tempHome);

        JsonNode config = readConfig(tempHome);
        JsonNode args = config.get("args");
        assertEquals(3, args.size(), "Should preserve non-Xmx args plus new Xmx");

        // Check that non-Xmx args are preserved
        assertEquals("-Dfoo=bar", args.get(0).asText());
        assertEquals("-XX:+UseG1GC", args.get(1).asText());
        assertEquals("-Xmx4096m", args.get(2).asText());
    }

    @Test
    void replacesExistingXmxArg() throws Exception {
        // Create initial config with one Xmx setting
        var settings1 = new MainProject.JvmMemorySettings(false, 1024);
        updateWithCustomHome(settings1, tempHome);

        JsonNode config = readConfig(tempHome);
        assertEquals("-Xmx1024m", config.get("args").get(0).asText());

        // Update with different memory setting
        var settings2 = new MainProject.JvmMemorySettings(false, 8192);
        updateWithCustomHome(settings2, tempHome);

        config = readConfig(tempHome);
        JsonNode args = config.get("args");
        assertEquals(1, args.size(), "Should have exactly one arg");
        assertEquals("-Xmx8192m", args.get(0).asText());
    }

    @Test
    void manualModeWithZeroMbDoesNotAddXmx() throws Exception {
        var settings = new MainProject.JvmMemorySettings(false, 0);
        updateWithCustomHome(settings, tempHome);

        JsonNode config = readConfig(tempHome);
        JsonNode args = config.get("args");
        assertEquals(0, args.size(), "Zero MB should not add -Xmx arg");
    }

    @Test
    void manualModeWithNegativeMbDoesNotAddXmx() throws Exception {
        var settings = new MainProject.JvmMemorySettings(false, -100);
        updateWithCustomHome(settings, tempHome);

        JsonNode config = readConfig(tempHome);
        JsonNode args = config.get("args");
        assertEquals(0, args.size(), "Negative MB should not add -Xmx arg");
    }

    @Test
    void handlesCorruptedJsonGracefully() throws Exception {
        // Create a corrupted JSON file
        Path configDir = tempHome.resolve(".config/brokk");
        Files.createDirectories(configDir);
        Path configFile = configDir.resolve("jdeploy.json");
        Files.writeString(configFile, "{corrupted json");

        // Should still work - creates new config
        var settings = new MainProject.JvmMemorySettings(false, 2048);
        updateWithCustomHome(settings, tempHome);

        JsonNode config = readConfig(tempHome);
        assertEquals(1, config.get("args").size());
        assertEquals("-Xmx2048m", config.get("args").get(0).asText());
    }

    @Test
    void handlesNonArrayArgsField() throws Exception {
        // Create config with args as non-array
        Path configDir = tempHome.resolve(".config/brokk");
        Files.createDirectories(configDir);
        Path configFile = configDir.resolve("jdeploy.json");
        Files.writeString(configFile, "{\"args\": \"not-an-array\"}");

        var settings = new MainProject.JvmMemorySettings(false, 3072);
        updateWithCustomHome(settings, tempHome);

        JsonNode config = readConfig(tempHome);
        assertTrue(config.get("args").isArray(), "args should be converted to array");
        assertEquals(1, config.get("args").size());
        assertEquals("-Xmx3072m", config.get("args").get(0).asText());
    }

    @Test
    void preservesOtherConfigFields() throws Exception {
        // Create config with additional fields
        Path configDir = tempHome.resolve(".config/brokk");
        Files.createDirectories(configDir);
        Path configFile = configDir.resolve("jdeploy.json");

        String initialConfig =
                """
                {
                  "version": "1.0",
                  "args": ["-Xmx1024m"],
                  "otherField": "value"
                }
                """;
        Files.writeString(configFile, initialConfig);

        var settings = new MainProject.JvmMemorySettings(false, 2048);
        updateWithCustomHome(settings, tempHome);

        JsonNode config = readConfig(tempHome);
        assertEquals("1.0", config.get("version").asText(), "Should preserve version field");
        assertEquals("value", config.get("otherField").asText(), "Should preserve otherField");
        assertEquals("-Xmx2048m", config.get("args").get(0).asText());
    }

    @Test
    void multipleXmxArgsAreAllRemoved() throws Exception {
        // Create config with multiple Xmx args
        Path configDir = tempHome.resolve(".config/brokk");
        Files.createDirectories(configDir);
        Path configFile = configDir.resolve("jdeploy.json");

        String initialConfig =
                """
                {
                  "args": [
                    "-Xmx512m",
                    "-Dfoo=bar",
                    "-Xmx1024m",
                    "-XX:+UseG1GC",
                    "-Xmx2048m"
                  ]
                }
                """;
        Files.writeString(configFile, initialConfig);

        var settings = new MainProject.JvmMemorySettings(false, 4096);
        updateWithCustomHome(settings, tempHome);

        JsonNode config = readConfig(tempHome);
        JsonNode args = config.get("args");
        assertEquals(3, args.size(), "Should have 2 non-Xmx args + 1 new Xmx");

        // Verify all old Xmx args are gone and only new one remains
        long xmxCount = 0;
        for (JsonNode arg : args) {
            String argStr = arg.asText();
            if (argStr.startsWith("-Xmx")) {
                xmxCount++;
                assertEquals("-Xmx4096m", argStr);
            }
        }
        assertEquals(1, xmxCount, "Should have exactly one -Xmx arg");
    }
}
