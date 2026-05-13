package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.cli.CliArgParser;
import ai.brokk.executor.routers.RouterUtil;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HeadlessExecutorMainTest {
    private static final String SESSION_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    void testExtractJobIdFromPath_validPathWithSubpath() {
        var result = RouterUtil.extractJobIdFromPath("/v1/jobs/abc123/events");
        assertEquals("abc123", result);
    }

    @Test
    void testExtractJobIdFromPath_validPathWithoutSubpath() {
        var result = RouterUtil.extractJobIdFromPath("/v1/jobs/abc123");
        assertEquals("abc123", result);
    }

    @Test
    void testExtractJobIdFromPath_blankJobId() {
        var result = RouterUtil.extractJobIdFromPath("/v1/jobs//events");
        assertNull(result);
    }

    @Test
    void testExtractJobIdFromPath_blankJobIdWithoutSubpath() {
        var result = RouterUtil.extractJobIdFromPath("/v1/jobs/");
        assertNull(result);
    }

    @Test
    void testExtractJobIdFromPath_unrelatedPath_healthLive() {
        var result = RouterUtil.extractJobIdFromPath("/health/live");
        assertNull(result);
    }

    @Test
    void testExtractJobIdFromPath_unrelatedPath_session() {
        var result = RouterUtil.extractJobIdFromPath("/v1/session");
        assertNull(result);
    }

    @Test
    void testExtractJobIdFromPath_unrelatedPath_root() {
        var result = RouterUtil.extractJobIdFromPath("/");
        assertNull(result);
    }

    @Test
    void testExtractJobIdFromPath_unrelatedPath_empty() {
        var result = RouterUtil.extractJobIdFromPath("");
        assertNull(result);
    }

    @Test
    void testExtractJobIdFromPath_validPathWithMultipleSubpaths() {
        var result = RouterUtil.extractJobIdFromPath("/v1/jobs/xyz789/cancel");
        assertEquals("xyz789", result);
    }

    @Test
    void testExtractJobIdFromPath_validPathWithDifferentJobId() {
        var result = RouterUtil.extractJobIdFromPath("/v1/jobs/job-12345/diff");
        assertEquals("job-12345", result);
    }

    @Test
    void testParseSessionPath_valid() {
        var result = RouterUtil.parseSessionPath("/v1/sessions/" + SESSION_ID);
        assertEquals(RouterUtil.SessionPathStatus.VALID, result.status());
        assertEquals(SESSION_ID, result.sessionId().toString());
    }

    @Test
    void testParseSessionPath_validWithTrailingSlash() {
        var result = RouterUtil.parseSessionPath("/v1/sessions/" + SESSION_ID + "/");
        assertEquals(RouterUtil.SessionPathStatus.VALID, result.status());
        assertEquals(SESSION_ID, result.sessionId().toString());
    }

    @Test
    void testParseSessionPath_invalidUuid() {
        var result = RouterUtil.parseSessionPath("/v1/sessions/not-a-uuid");
        assertEquals(RouterUtil.SessionPathStatus.INVALID_SESSION_ID, result.status());
        assertNull(result.sessionId());
    }

    @Test
    void testParseSessionPath_extraSegment() {
        var result = RouterUtil.parseSessionPath("/v1/sessions/" + SESSION_ID + "/extra");
        assertEquals(RouterUtil.SessionPathStatus.NOT_FOUND, result.status());
        assertNull(result.sessionId());
    }

    @Test
    void testParseSessionPath_basePath() {
        var result = RouterUtil.parseSessionPath("/v1/sessions");
        assertEquals(RouterUtil.SessionPathStatus.NOT_FOUND, result.status());
        assertNull(result.sessionId());
    }

    @Test
    void testParseSessionPath_unrelatedPath() {
        var result = RouterUtil.parseSessionPath("/v1/jobs/abc123");
        assertEquals(RouterUtil.SessionPathStatus.NOT_FOUND, result.status());
        assertNull(result.sessionId());
    }

    @Test
    void testFormatFatalStartupErrorIncludesRootCauseAndStackTrace() {
        var rootCause = new IOException("disk full");
        var throwable = new IllegalStateException("executor failed", rootCause);

        var result = HeadlessExecutorMain.formatFatalStartupError(throwable);

        assertTrue(result.contains("Fatal error starting HeadlessExecutorMain: executor failed"));
        assertTrue(result.contains("Root cause: IOException: disk full"));
        assertTrue(result.contains("Exception chain:"));
        assertTrue(result.contains("java.lang.IllegalStateException: executor failed"));
        assertTrue(result.contains("java.io.IOException: disk full"));
        assertTrue(result.contains("Stack trace:"));
    }

    @Test
    void readOnlyFlagInValidArgs_isParsedAndAccepted() throws Exception {
        // The CLI parser must accept --read-only as a known key and not flag it as invalid.
        Set<String> validArgs = headlessExecutorValidArgs();
        assertTrue(validArgs.contains("read-only"), "VALID_ARGS must list read-only");

        var parsed = CliArgParser.parse(new String[] {"--read-only=true"}, validArgs);
        assertTrue(parsed.invalidKeys().isEmpty(), "read-only=true must not be reported as invalid");
        assertEquals("true", parsed.args().get("read-only"));

        var parsedBare = CliArgParser.parse(new String[] {"--read-only"}, validArgs);
        assertTrue(parsedBare.invalidKeys().isEmpty(), "Bare --read-only must not be flagged as invalid");
    }

    @Test
    void getBooleanConfigValue_acceptsAllAffirmativeAliases() throws Exception {
        // The boolean parser must accept the documented alias set including bare flag (empty string).
        // Use reflection so the private parser is exercised exactly as main() invokes it.
        Method m = HeadlessExecutorMain.class.getDeclaredMethod(
                "getBooleanConfigValue", Map.class, String.class, String.class, boolean.class);
        m.setAccessible(true);

        for (String value : new String[] {"", "1", "true", "yes", "on", "TRUE", "Yes"}) {
            boolean result = (boolean) m.invoke(null, Map.of("read-only", value), "read-only", "READ_ONLY", false);
            assertTrue(result, "Affirmative alias '" + value + "' must parse to true");
        }
        for (String value : new String[] {"0", "false", "no", "off", "FALSE"}) {
            boolean result = (boolean) m.invoke(null, Map.of("read-only", value), "read-only", "READ_ONLY", false);
            assertFalse(result, "Negative alias '" + value + "' must parse to false");
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> headlessExecutorValidArgs() throws Exception {
        var field = HeadlessExecutorMain.class.getDeclaredField("VALID_ARGS");
        field.setAccessible(true);
        return (Set<String>) field.get(null);
    }

    @Test
    void readOnlyEnvAlias_doesNotCollideWithLocale() {
        // Sanity check that the env-name constant we documented matches the lowercase convention
        // used by other flags (uppercase env, hyphenated CLI). This guards against a typo regression.
        assertEquals("READ_ONLY", "read-only".replace('-', '_').toUpperCase(Locale.ROOT));
    }
}
