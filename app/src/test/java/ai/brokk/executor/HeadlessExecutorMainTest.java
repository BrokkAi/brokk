package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class HeadlessExecutorMainTest {
    private static final String SESSION_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    void testExtractJobIdFromPath_validPathWithSubpath() {
        var result = HeadlessExecutorMain.extractJobIdFromPath("/v1/jobs/abc123/events");
        assertEquals("abc123", result);
    }

    @Test
    void testExtractJobIdFromPath_validPathWithoutSubpath() {
        var result = HeadlessExecutorMain.extractJobIdFromPath("/v1/jobs/abc123");
        assertEquals("abc123", result);
    }

    @Test
    void testExtractJobIdFromPath_blankJobId() {
        var result = HeadlessExecutorMain.extractJobIdFromPath("/v1/jobs//events");
        assertNull(result);
    }

    @Test
    void testExtractJobIdFromPath_blankJobIdWithoutSubpath() {
        var result = HeadlessExecutorMain.extractJobIdFromPath("/v1/jobs/");
        assertNull(result);
    }

    @Test
    void testExtractJobIdFromPath_unrelatedPath_healthLive() {
        var result = HeadlessExecutorMain.extractJobIdFromPath("/health/live");
        assertNull(result);
    }

    @Test
    void testExtractJobIdFromPath_unrelatedPath_session() {
        var result = HeadlessExecutorMain.extractJobIdFromPath("/v1/session");
        assertNull(result);
    }

    @Test
    void testExtractJobIdFromPath_unrelatedPath_root() {
        var result = HeadlessExecutorMain.extractJobIdFromPath("/");
        assertNull(result);
    }

    @Test
    void testExtractJobIdFromPath_unrelatedPath_empty() {
        var result = HeadlessExecutorMain.extractJobIdFromPath("");
        assertNull(result);
    }

    @Test
    void testExtractJobIdFromPath_validPathWithMultipleSubpaths() {
        var result = HeadlessExecutorMain.extractJobIdFromPath("/v1/jobs/xyz789/cancel");
        assertEquals("xyz789", result);
    }

    @Test
    void testExtractJobIdFromPath_validPathWithDifferentJobId() {
        var result = HeadlessExecutorMain.extractJobIdFromPath("/v1/jobs/job-12345/diff");
        assertEquals("job-12345", result);
    }

    @Test
    void testParseSessionPath_valid() {
        var result = HeadlessExecutorMain.parseSessionPath("/v1/sessions/" + SESSION_ID);
        assertEquals(HeadlessExecutorMain.SessionPathStatus.VALID, result.status());
        assertEquals(SESSION_ID, result.sessionId().toString());
    }

    @Test
    void testParseSessionPath_validWithTrailingSlash() {
        var result = HeadlessExecutorMain.parseSessionPath("/v1/sessions/" + SESSION_ID + "/");
        assertEquals(HeadlessExecutorMain.SessionPathStatus.VALID, result.status());
        assertEquals(SESSION_ID, result.sessionId().toString());
    }

    @Test
    void testParseSessionPath_invalidUuid() {
        var result = HeadlessExecutorMain.parseSessionPath("/v1/sessions/not-a-uuid");
        assertEquals(HeadlessExecutorMain.SessionPathStatus.INVALID_SESSION_ID, result.status());
        assertNull(result.sessionId());
    }

    @Test
    void testParseSessionPath_extraSegment() {
        var result = HeadlessExecutorMain.parseSessionPath("/v1/sessions/" + SESSION_ID + "/extra");
        assertEquals(HeadlessExecutorMain.SessionPathStatus.NOT_FOUND, result.status());
        assertNull(result.sessionId());
    }

    @Test
    void testParseSessionPath_basePath() {
        var result = HeadlessExecutorMain.parseSessionPath("/v1/sessions");
        assertEquals(HeadlessExecutorMain.SessionPathStatus.NOT_FOUND, result.status());
        assertNull(result.sessionId());
    }

    @Test
    void testParseSessionPath_unrelatedPath() {
        var result = HeadlessExecutorMain.parseSessionPath("/v1/jobs/abc123");
        assertEquals(HeadlessExecutorMain.SessionPathStatus.NOT_FOUND, result.status());
        assertNull(result.sessionId());
    }
}
