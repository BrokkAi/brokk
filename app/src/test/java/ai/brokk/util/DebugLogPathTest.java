package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebugLogPathTest {
    private String originalDebugLogPath;
    private String originalFilePattern;
    private String originalDeleteBasePath;
    private String originalDeleteGlob;

    @BeforeEach
    void saveProperties() {
        originalDebugLogPath = System.getProperty(DebugLogPath.SYSTEM_PROPERTY);
        originalFilePattern = System.getProperty(DebugLogPath.FILE_PATTERN_PROPERTY);
        originalDeleteBasePath = System.getProperty(DebugLogPath.DELETE_BASE_PATH_PROPERTY);
        originalDeleteGlob = System.getProperty(DebugLogPath.DELETE_GLOB_PROPERTY);
    }

    @AfterEach
    void restoreProperties() {
        restoreProperty(DebugLogPath.SYSTEM_PROPERTY, originalDebugLogPath);
        restoreProperty(DebugLogPath.FILE_PATTERN_PROPERTY, originalFilePattern);
        restoreProperty(DebugLogPath.DELETE_BASE_PATH_PROPERTY, originalDeleteBasePath);
        restoreProperty(DebugLogPath.DELETE_GLOB_PROPERTY, originalDeleteGlob);
    }

    @Test
    void resolvesArgumentBeforeEnvAndSystemProperty(@TempDir Path tempDir) {
        var argPath = tempDir.resolve("arg").resolve("debug.log");
        var envPath = tempDir.resolve("env").resolve("debug.log");
        var propertyPath = tempDir.resolve("property").resolve("debug.log");
        System.setProperty(DebugLogPath.SYSTEM_PROPERTY, propertyPath.toString());

        var result = DebugLogPath.resolveHeadlessPath(
                Map.of(DebugLogPath.ARG, argPath.toString()), Map.of(DebugLogPath.ENV_VAR, envPath.toString()));

        assertEquals(argPath.toAbsolutePath().normalize(), result);
    }

    @Test
    void resolvesEqualsArgumentBeforeEnvAndSystemProperty(@TempDir Path tempDir) {
        var argPath = tempDir.resolve("arg").resolve("debug.log");
        var envPath = tempDir.resolve("env").resolve("debug.log");
        var propertyPath = tempDir.resolve("property").resolve("debug.log");
        System.setProperty(DebugLogPath.SYSTEM_PROPERTY, propertyPath.toString());

        var result = DebugLogPath.resolveHeadlessPath(
                new String[] {"--" + DebugLogPath.ARG + "=" + argPath},
                Map.of(DebugLogPath.ENV_VAR, envPath.toString()));

        assertEquals(argPath.toAbsolutePath().normalize(), result);
    }

    @Test
    void resolvesEnvBeforeSystemProperty(@TempDir Path tempDir) {
        var envPath = tempDir.resolve("env").resolve("debug.log");
        var propertyPath = tempDir.resolve("property").resolve("debug.log");
        System.setProperty(DebugLogPath.SYSTEM_PROPERTY, propertyPath.toString());

        var result = DebugLogPath.resolveHeadlessPath(Map.of(), Map.of(DebugLogPath.ENV_VAR, envPath.toString()));

        assertEquals(envPath.toAbsolutePath().normalize(), result);
    }

    @Test
    void resolvesSystemPropertyWhenNoArgumentOrEnv(@TempDir Path tempDir) {
        var propertyPath = tempDir.resolve("property").resolve("debug.log");
        System.setProperty(DebugLogPath.SYSTEM_PROPERTY, propertyPath.toString());

        var result = DebugLogPath.resolveHeadlessPath(Map.of(), Map.of());

        assertEquals(propertyPath.toAbsolutePath().normalize(), result);
    }

    @Test
    void configureHeadlessCreatesParentAndSetsLog4jProperties(@TempDir Path tempDir) throws IOException {
        var debugLogPath = tempDir.resolve("scan-123").resolve("debug.log");

        var result = DebugLogPath.configureHeadless(Map.of(DebugLogPath.ARG, debugLogPath.toString()));

        assertEquals(debugLogPath.toAbsolutePath().normalize(), result);
        var parent = Objects.requireNonNull(debugLogPath.getParent());
        assertTrue(Files.isDirectory(parent));
        assertEquals(debugLogPath.toString(), System.getProperty(DebugLogPath.SYSTEM_PROPERTY));
        assertEquals(debugLogPath + ".%d{yyyy-MM-dd}", System.getProperty(DebugLogPath.FILE_PATTERN_PROPERTY));
        assertEquals(parent.toString(), System.getProperty(DebugLogPath.DELETE_BASE_PATH_PROPERTY));
        assertEquals("debug.log.*", System.getProperty(DebugLogPath.DELETE_GLOB_PROPERTY));
    }

    private static void restoreProperty(String key, String value) {
        if (value != null) {
            System.setProperty(key, value);
        } else {
            System.clearProperty(key);
        }
    }
}
