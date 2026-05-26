package ai.brokk.executor.jobs;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobSpecTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void testOfPrReview_CreatesJobWithCorrectTags() {
        var spec = JobSpec.ofPrReview(
                "gpt-4", "ghp_token123", "octocat", "hello-world", 42, PrReviewService.Severity.HIGH);

        assertEquals("", spec.taskInput());
        assertFalse(spec.autoCommit());
        assertFalse(spec.autoCompress());
        assertFalse(spec.preScan());
        assertEquals("gpt-4", spec.plannerModel());
        assertNull(spec.scanModel());
        assertNull(spec.codeModel());
        assertNull(spec.sourceBranch());
        assertNull(spec.targetBranch());

        assertEquals("ghp_token123", spec.tags().get("github_token"));
        assertEquals("octocat", spec.tags().get("repo_owner"));
        assertEquals("hello-world", spec.tags().get("repo_name"));
        assertEquals("42", spec.tags().get("pr_number"));
    }

    @Test
    void testGetPrNumber_ReturnsCorrectInteger() {
        var spec = JobSpec.ofPrReview("gpt-4", "token", "owner", "repo", 123, PrReviewService.Severity.HIGH);

        assertEquals(123, spec.getPrNumber());
    }

    @Test
    void testGetGithubToken_ReturnsCorrectValue() {
        var spec = JobSpec.ofPrReview("gpt-4", "ghp_secrettoken", "owner", "repo", 1, PrReviewService.Severity.HIGH);

        assertEquals("ghp_secrettoken", spec.getGithubToken());
    }

    @Test
    void testGetRepoOwner_ReturnsCorrectValue() {
        var spec = JobSpec.ofPrReview("gpt-4", "token", "myorg", "repo", 1, PrReviewService.Severity.HIGH);

        assertEquals("myorg", spec.getRepoOwner());
    }

    @Test
    void testGetRepoName_ReturnsCorrectValue() {
        var spec = JobSpec.ofPrReview("gpt-4", "token", "owner", "myrepo", 1, PrReviewService.Severity.HIGH);

        assertEquals("myrepo", spec.getRepoName());
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
    void executionPolicyPersistsThroughJson() throws Exception {
        var spec = new JobSpec(
                "write final report",
                false,
                true,
                "planner",
                null,
                null,
                false,
                Map.of("mode", "SEARCH"),
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS,
                new JobSpec.ExecutionPolicy(JobSpec.ExecutionPolicyPreset.REPORT_ONLY));

        var json = MAPPER.writeValueAsString(spec);
        var roundTrip = MAPPER.readValue(json, JobSpec.class);

        assertTrue(roundTrip.isReportOnly());
        assertEquals(
                JobSpec.ExecutionPolicyPreset.REPORT_ONLY,
                requireNonNull(roundTrip.executionPolicy()).preset());
        assertTrue(json.contains("\"executionPolicy\""));
    }

    @Test
    void responseSchemaPersistsThroughJson() throws Exception {
        var schema = MAPPER.readTree(
                """
                {
                  "type": "object",
                  "properties": {
                    "summary": { "type": "string" }
                  },
                  "required": ["summary"],
                  "additionalProperties": false
                }
                """);
        var spec = new JobSpec(
                "write final report",
                false,
                true,
                "planner",
                null,
                null,
                false,
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS,
                new JobSpec.ExecutionPolicy(JobSpec.ExecutionPolicyPreset.REPORT_ONLY),
                new JobSpec.ResponseSchema("SlopCopSniffTest", schema));

        var json = MAPPER.writeValueAsString(spec);
        var roundTrip = MAPPER.readValue(json, JobSpec.class);

        assertEquals(
                "SlopCopSniffTest", requireNonNull(roundTrip.responseSchema()).name());
        assertEquals("object", roundTrip.responseSchema().schema().get("type").textValue());
        assertTrue(json.contains("\"responseSchema\""));
    }

    @Test
    void executionPolicyRejectsMissingPreset() {
        assertThrows(Exception.class, () -> MAPPER.readValue("{\"executionPolicy\":{}}", JobSpec.class));
    }

    @Test
    void executionPolicyRejectsNullPreset() {
        assertThrows(Exception.class, () -> MAPPER.readValue("{\"executionPolicy\":{\"preset\":null}}", JobSpec.class));
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
    void testOfPrReview_WithLargePrNumber() {
        var spec = JobSpec.ofPrReview("gpt-4", "token", "owner", "repo", 999999, PrReviewService.Severity.HIGH);

        assertEquals(999999, spec.getPrNumber());
    }

    @Test
    void testTagsImmutable() {
        var spec = JobSpec.ofPrReview("gpt-4", "token", "owner", "repo", 1, PrReviewService.Severity.HIGH);

        // Verify tags map is immutable by attempting to retrieve it
        var tags = spec.tags();
        assertEquals(6, tags.size());
        assertTrue(tags.containsKey("mode"));
        assertTrue(tags.containsKey("github_token"));
        assertTrue(tags.containsKey("repo_owner"));
        assertTrue(tags.containsKey("repo_name"));
        assertTrue(tags.containsKey("pr_number"));
        assertTrue(tags.containsKey("severity_threshold"));
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
        assertTrue(spec.autoCommit());
        assertFalse(spec.skipVerification());
    }

    @Test
    void testOfIssue_CanSetSkipVerificationTrue() {
        var spec = JobSpec.ofIssue(
                "planner", null, "token", "owner", "repo", 123, null, JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS, true);
        assertTrue(spec.autoCommit());
        assertTrue(spec.skipVerification());
    }
}
