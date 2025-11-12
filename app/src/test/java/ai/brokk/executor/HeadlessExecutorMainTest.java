package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class HeadlessExecutorMainTest {

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
}
