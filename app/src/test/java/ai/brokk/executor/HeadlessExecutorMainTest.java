package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.executor.routers.RouterUtil;
import java.util.Map;
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
    void parseBooleanValue_acceptsTrueVariants() {
        assertTrue(HeadlessExecutorMain.parseBooleanValue("true", "test"));
        assertTrue(HeadlessExecutorMain.parseBooleanValue("1", "test"));
        assertTrue(HeadlessExecutorMain.parseBooleanValue("yes", "test"));
        assertTrue(HeadlessExecutorMain.parseBooleanValue("on", "test"));
        assertTrue(HeadlessExecutorMain.parseBooleanValue("", "test"));
    }

    @Test
    void parseBooleanValue_acceptsFalseVariants() {
        assertFalse(HeadlessExecutorMain.parseBooleanValue("false", "test"));
        assertFalse(HeadlessExecutorMain.parseBooleanValue("0", "test"));
        assertFalse(HeadlessExecutorMain.parseBooleanValue("no", "test"));
        assertFalse(HeadlessExecutorMain.parseBooleanValue("off", "test"));
    }

    @Test
    void parseBooleanValue_throwsOnInvalid() {
        var ex = assertThrows(
                IllegalArgumentException.class, () -> HeadlessExecutorMain.parseBooleanValue("maybe", "test-flag"));
        assertTrue(ex.getMessage().contains("test-flag"));
        assertTrue(ex.getMessage().contains("maybe"));
    }

    @Test
    void parseTlsConfig_cliOnly_enablesTlsAndMtls() {
        var args = Map.of(
                "tls-enabled", "true",
                "tls-keystore-path", "/tmp/ks.jks",
                "tls-keystore-password", "secret",
                "tls-client-ca-path", "/tmp/ca.pem",
                "mtls-required", "true");
        var config = HeadlessExecutorMain.parseTlsConfig(args, Map.of());

        assertTrue(config.enabled);
        assertTrue(config.mtlsRequired);
        assertEquals("/tmp/ks.jks", config.keystorePath);
        assertEquals("secret", config.keystorePassword);
        assertEquals("/tmp/ca.pem", config.clientCaPath);
    }

    @Test
    void parseTlsConfig_envOnly_enablesTls() {
        var env = Map.of(
                "TLS_ENABLED", "true",
                "TLS_KEYSTORE_PATH", "/etc/brokk/server.jks",
                "TLS_KEYSTORE_PASSWORD", "env-secret");
        var config = HeadlessExecutorMain.parseTlsConfig(Map.of(), env);

        assertTrue(config.enabled);
        assertFalse(config.mtlsRequired);
        assertEquals("/etc/brokk/server.jks", config.keystorePath);
        assertEquals("env-secret", config.keystorePassword);
        assertNull(config.clientCaPath);
    }

    @Test
    void parseTlsConfig_defaultsToDisabledWhenNoFlags() {
        var config = HeadlessExecutorMain.parseTlsConfig(Map.of(), Map.of());
        assertFalse(config.enabled);
        assertFalse(config.mtlsRequired);
        assertNull(config.keystorePath);
        assertNull(config.keystorePassword);
        assertNull(config.clientCaPath);
    }

    @Test
    void parseTlsConfig_missingKeystorePathThrows() {
        var args = Map.of("tls-enabled", "true");
        var ex =
                assertThrows(IllegalArgumentException.class, () -> HeadlessExecutorMain.parseTlsConfig(args, Map.of()));
        assertTrue(
                ex.getMessage().contains("TLS_KEYSTORE_PATH") || ex.getMessage().contains("--tls-keystore-path"));
    }

    @Test
    void parseTlsConfig_missingKeystorePasswordThrows() {
        var args = Map.of(
                "tls-enabled", "true",
                "tls-keystore-path", "/tmp/ks.jks");
        var ex =
                assertThrows(IllegalArgumentException.class, () -> HeadlessExecutorMain.parseTlsConfig(args, Map.of()));
        assertTrue(ex.getMessage().contains("TLS_KEYSTORE_PASSWORD"));
    }

    @Test
    void parseTlsConfig_mtlsRequiredWithoutClientCaThrows() {
        var args = Map.of(
                "tls-enabled", "true",
                "tls-keystore-path", "/tmp/ks.jks",
                "tls-keystore-password", "secret",
                "mtls-required", "true");
        var ex =
                assertThrows(IllegalArgumentException.class, () -> HeadlessExecutorMain.parseTlsConfig(args, Map.of()));
        assertTrue(ex.getMessage().contains("TLS_CLIENT_CA_PATH"));
    }

    @Test
    void parseTlsConfig_clientCaWithoutMtlsRequiredIsAllowed() {
        var args = Map.of(
                "tls-enabled", "true",
                "tls-keystore-path", "/tmp/ks.jks",
                "tls-keystore-password", "secret",
                "tls-client-ca-path", "/tmp/ca.pem");
        var config = HeadlessExecutorMain.parseTlsConfig(args, Map.of());

        assertTrue(config.enabled);
        assertFalse(config.mtlsRequired);
        assertEquals("/tmp/ca.pem", config.clientCaPath);
    }

    @Test
    void parseTlsConfig_precedenceOfArgsOverEnv() {
        var args = Map.of("tls-enabled", "true", "tls-keystore-path", "/tmp/ks.jks", "tls-keystore-password", "secret");
        var env = Map.of("TLS_ENABLED", "false");
        var config = HeadlessExecutorMain.parseTlsConfig(args, env);

        assertTrue(config.enabled);
    }
}
