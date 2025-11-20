package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.MainProject;
import ai.brokk.executor.IssueFixRequest;
import ai.brokk.executor.jobs.ErrorPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.Nullable;

class HeadlessExecutorMainTest {
    @Nullable private HeadlessExecutorMain currentExecutor;

    @BeforeEach
    void setUp() {
        currentExecutor = null;
    }

    @AfterEach
    void tearDown() {
        if (currentExecutor != null) {
            try {
                currentExecutor.stop(1);
            } catch (Exception e) {
                // Ignore cleanup errors in tests; NullPointerException is expected if analyzer wasn't initialized
            }
            currentExecutor = null;
        }
    }

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

    // ============================================================================
    // Tests for createIssueBranchName
    // ============================================================================

    @Test
    void testCreateIssueBranchName_simpleTitle() {
        var executor = createTestExecutor();
        var result = executor.createIssueBranchName(123, "Fix the button");
        assertEquals("fix/123-fix-the-button", result);
    }

    @Test
    void testCreateIssueBranchName_withSpecialChars() {
        var executor = createTestExecutor();
        var result = executor.createIssueBranchName(456, "Fix: Button doesn't work!");
        // Special characters and apostrophes are replaced with hyphens, then deduplicated
        assertTrue(result.startsWith("fix/456-"), "Branch should start with fix/456-");
        assertTrue(result.contains("fix") && result.contains("button") && result.contains("work"),
                "Branch should contain key words from title");
    }

    @Test
    void testCreateIssueBranchName_longTitleIsTruncated() {
        var executor = createTestExecutor();
        var longTitle = "a".repeat(100);
        var result = executor.createIssueBranchName(789, longTitle);
        
        // Extract the sanitized part after the issue number and hyphen
        var parts = result.split("-", 2);
        assertEquals("fix/789", parts[0]);
        // The sanitized title should be at most 50 characters
        assertTrue(parts[1].length() <= 50, "Sanitized title exceeds 50 characters: " + parts[1]);
    }

    @Test
    void testCreateIssueBranchName_withExtraSpaces() {
        var executor = createTestExecutor();
        var result = executor.createIssueBranchName(101, "  Fix   the   spacing  ");
        assertEquals("fix/101-fix-the-spacing", result);
    }

    // ============================================================================
    // Tests for validateIssueFixRequest
    // ============================================================================

    @Test
    void testValidateIssueFixRequest_validRequest() {
        var executor = createTestExecutor();
        var request = new IssueFixRequest(
                "owner",
                "repo",
                42,
                "token123",
                false,
                false,
                "gpt-5",
                null,
                null);
        var errors = executor.validateIssueFixRequest(request, 42);
        
        assertTrue(errors.isEmpty(), "Valid request should have no errors");
    }

    @Test
    void testValidateIssueFixRequest_missingOwner() {
        var executor = createTestExecutor();
        var request = new IssueFixRequest(
                "",
                "repo",
                42,
                "token123",
                false,
                false,
                "gpt-5",
                null,
                null);
        var errors = executor.validateIssueFixRequest(request, 42);
        
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Repository owner is required"));
    }

    @Test
    void testValidateIssueFixRequest_missingRepo() {
        var executor = createTestExecutor();
        var request = new IssueFixRequest(
                "owner",
                "",
                42,
                "token123",
                false,
                false,
                "gpt-5",
                null,
                null);
        var errors = executor.validateIssueFixRequest(request, 42);
        
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Repository name is required"));
    }

    @Test
    void testValidateIssueFixRequest_invalidIssueNumber() {
        var executor = createTestExecutor();
        var request = new IssueFixRequest(
                "owner",
                "repo",
                0,
                "token123",
                false,
                false,
                "gpt-5",
                null,
                null);
        var errors = executor.validateIssueFixRequest(request, 0);
        
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Issue number must be positive"));
    }

    @Test
    void testValidateIssueFixRequest_missingPlannerModel() {
        var executor = createTestExecutor();
        var request = new IssueFixRequest(
                "owner",
                "repo",
                42,
                "token123",
                false,
                false,
                "",
                null,
                null);
        var errors = executor.validateIssueFixRequest(request, 42);
        
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("plannerModel is required"));
    }

    @Test
    void testValidateIssueFixRequest_missingGitHubToken() {
        var executor = createTestExecutor();
        var request = new IssueFixRequest(
                "owner",
                "repo",
                42,
                "",
                false,
                false,
                "gpt-5",
                null,
                null);
        var errors = executor.validateIssueFixRequest(request, 42);
        
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("GitHub token is required"));
    }

    @Test
    void testValidateIssueFixRequest_multipleErrors() {
        var executor = createTestExecutor();
        var request = new IssueFixRequest(
                "",
                "",
                -1,
                "",
                false,
                false,
                "",
                null,
                null);
        var errors = executor.validateIssueFixRequest(request, -1);
        
        assertEquals(5, errors.size());
        assertTrue(errors.get(0).contains("Repository owner"));
        assertTrue(errors.get(1).contains("Repository name"));
        assertTrue(errors.get(2).contains("Issue number"));
        assertTrue(errors.get(3).contains("plannerModel"));
        assertTrue(errors.get(4).contains("GitHub token"));
    }


    // ============================================================================
    // Helper method to create a test executor instance
    // ============================================================================

    /**
     * Creates a minimal HeadlessExecutorMain instance for testing helper methods.
     * Uses a dummy UUID and context manager just to satisfy construction requirements.
     * Binds to port 0 (ephemeral) to avoid port conflicts between tests.
     */
    private HeadlessExecutorMain createTestExecutor() {
        try {
            // Use port 0 for ephemeral port assignment to avoid "Address already in use" errors
            var executor = new HeadlessExecutorMain(
                    java.util.UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                    "localhost:0",
                    "test-token",
                    createDummyContextManager());
            currentExecutor = executor;
            return executor;
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to create test executor", e);
        }
    }

    /**
     * Creates a minimal ContextManager instance for testing.
     */
    private ai.brokk.ContextManager createDummyContextManager() {
        try {
            var tempDir = java.nio.file.Files.createTempDirectory("test-executor");
            var project = new ai.brokk.MainProject(tempDir);
            return new ai.brokk.ContextManager(project);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create dummy context manager", e);
        }
    }
}
