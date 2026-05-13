package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebugLogPathTest {
    private String originalDebugLogPath;
    private String originalFilePattern;
    private String originalDeleteBasePath;
    private String originalDeleteRegex;

    @BeforeEach
    void saveProperties() {
        originalDebugLogPath = System.getProperty(DebugLogPath.SYSTEM_PROPERTY);
        originalFilePattern = System.getProperty(DebugLogPath.FILE_PATTERN_PROPERTY);
        originalDeleteBasePath = System.getProperty(DebugLogPath.DELETE_BASE_PATH_PROPERTY);
        originalDeleteRegex = System.getProperty(DebugLogPath.DELETE_REGEX_PROPERTY);
    }

    @AfterEach
    void restoreProperties() {
        restoreProperty(DebugLogPath.SYSTEM_PROPERTY, originalDebugLogPath);
        restoreProperty(DebugLogPath.FILE_PATTERN_PROPERTY, originalFilePattern);
        restoreProperty(DebugLogPath.DELETE_BASE_PATH_PROPERTY, originalDeleteBasePath);
        restoreProperty(DebugLogPath.DELETE_REGEX_PROPERTY, originalDeleteRegex);
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
        assertEquals(
                Pattern.quote(debugLogPath.getFileName().toString()) + "\\.\\d{4}-\\d{2}-\\d{2}",
                System.getProperty(DebugLogPath.DELETE_REGEX_PROPERTY));
    }

    @Test
    void deleteRegexQuotesConfiguredFileName(@TempDir Path tempDir) throws IOException {
        var debugLogPath = tempDir.resolve("scan-123").resolve("debug(1).log");

        DebugLogPath.configureHeadless(Map.of(DebugLogPath.ARG, debugLogPath.toString()));

        var deleteRegex = System.getProperty(DebugLogPath.DELETE_REGEX_PROPERTY);
        assertEquals(Pattern.quote("debug(1).log") + "\\.\\d{4}-\\d{2}-\\d{2}", deleteRegex);
        var pattern = Pattern.compile(deleteRegex);
        assertTrue(pattern.matcher("debug(1).log.2026-05-13").matches());
        assertFalse(pattern.matcher("debug1.log.2026-05-13").matches());
        assertFalse(pattern.matcher("debug.log.backup").matches());
    }

    private static void restoreProperty(String key, String value) {
        if (value != null) {
            System.setProperty(key, value);
        } else {
            System.clearProperty(key);
        }
    }
}
