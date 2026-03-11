package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JobSpecTest {

    @Test
    void testOfPrReview_CreatesJobWithCorrectTags() {
        var spec = JobSpec.ofPrReview("gpt-4", "ghp_token123", "octocat", "hello-world", 42);

        assertEquals("", spec.taskInput());
        assertFalse(spec.autoCommit());
        assertFalse(spec.autoCompress());
        assertFalse(spec.preScan());
        assertEquals("gpt-4", spec.plannerModel());
        assertNull(spec.scanModel());
        assertNull(spec.codeModel());
        assertNull(spec.sourceBranch());
        assertNull(spec.targetBranch());

        assertEquals("REVIEW", spec.tags().get("mode"));
        assertEquals("ghp_token123", spec.tags().get("github_token"));
        assertEquals("octocat", spec.tags().get("repo_owner"));
        assertEquals("hello-world", spec.tags().get("repo_name"));
        assertEquals("42", spec.tags().get("pr_number"));
    }

    @Test
    void testAccessors_ReturnNullWhenTagsMissing() {
        var spec = JobSpec.of("task", "model");

        assertNull(spec.getGithubToken());
        assertNull(spec.getRepoOwner());
        assertNull(spec.getRepoName());
        assertNull(spec.getPrNumber());
    }

    @Test
    void testGetPrNumber_ReturnsNullForInvalidNumber() {
        var spec = JobSpec.of(
                "task", true, true, "model", null, null, false, Map.of("pr_number", "not-a-number"), null, null, null);

        assertNull(spec.getPrNumber());
    }

    @Test
    void testGetPrNumber_ReturnsNullForEmptyString() {
        var spec =
                JobSpec.of("task", true, true, "model", null, null, false, Map.of("pr_number", ""), null, null, null);

        assertNull(spec.getPrNumber());
    }

    @Test
    void testReasoningLevelCode_StoredAndAccessible() {
        var spec = JobSpec.of("task", true, true, "model", null, null, false, Map.of(), "rlc-123");

        assertNull(spec.reasoningLevel());
        assertEquals("rlc-123", spec.reasoningLevelCode());
        assertNull(spec.temperature());
    }

    @Test
    void testReasoningLevelCode_FromOverrides() {
        var overrides = new JobSpec.ModelOverrides("HIGH", "rlc-xyz", 0.2, null);

        var spec = JobSpec.of("task", true, true, "model", null, null, false, Map.of(), overrides);

        assertEquals("HIGH", spec.reasoningLevel());
        assertEquals("rlc-xyz", spec.reasoningLevelCode());
        assertEquals(0.2, spec.temperature());
    }

    @Test
    void testTagsNullNormalizedByCanonicalConstructor() {
        // Construct a JobSpec directly with tags == null to simulate deserialization/caller passing null.
        var spec = new JobSpec(
                "task-input",
                true,
                true,
                "planner-model",
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);

        // tags() must never be null; canonical constructor should normalize to empty immutable map.
        assertTrue(
                spec.tags() != null && spec.tags().isEmpty(),
                "tags() must be non-null and empty when constructed with null");
        // redactedTags should not throw and should be empty
        var redacted = spec.redactedTags();
        assertTrue(
                redacted != null && redacted.isEmpty(),
                "redactedTags() must be non-null and empty when tags() was null");
    }

    @Test
    void testIssueDeliveryEnabledHandlesNullTags() {
        var spec = new JobSpec(
                "task-input",
                true,
                true,
                "planner-model",
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);

        // Should not throw and default to true when no tag is provided.
        assertTrue(IssueService.issueDeliveryEnabled(spec));
    }

    @Test
    void testSkipVerification_DefaultsToFalse() {
        var spec = JobSpec.of("task", "model");
        assertFalse(spec.skipVerification());
    }

    @Test
    void testOfIssue_DefaultSkipVerificationFalse() {
        var spec = JobSpec.ofIssue("planner", null, "token", "owner", "repo", 123, null);
        assertFalse(spec.skipVerification());
    }

    @Test
    void testOfIssue_CanSetSkipVerificationTrue() {
        var spec = JobSpec.ofIssue(
                "planner", null, "token", "owner", "repo", 123, null, JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS, true);
        assertTrue(spec.skipVerification());
    }
}
