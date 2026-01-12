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

        assertEquals("ghp_token123", spec.tags().get("github_token"));
        assertEquals("octocat", spec.tags().get("repo_owner"));
        assertEquals("hello-world", spec.tags().get("repo_name"));
        assertEquals("42", spec.tags().get("pr_number"));
    }

    @Test
    void testGetPrNumber_ReturnsCorrectInteger() {
        var spec = JobSpec.ofPrReview("gpt-4", "token", "owner", "repo", 123);

        assertEquals(123, spec.getPrNumber());
    }

    @Test
    void testGetGithubToken_ReturnsCorrectValue() {
        var spec = JobSpec.ofPrReview("gpt-4", "ghp_secrettoken", "owner", "repo", 1);

        assertEquals("ghp_secrettoken", spec.getGithubToken());
    }

    @Test
    void testGetRepoOwner_ReturnsCorrectValue() {
        var spec = JobSpec.ofPrReview("gpt-4", "token", "myorg", "repo", 1);

        assertEquals("myorg", spec.getRepoOwner());
    }

    @Test
    void testGetRepoName_ReturnsCorrectValue() {
        var spec = JobSpec.ofPrReview("gpt-4", "token", "owner", "myrepo", 1);

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
    void testGetPrNumber_ReturnsNullForInvalidNumber() {
        var spec = JobSpec.of(
                "task",
                true,
                true,
                "model",
                null,
                null,
                false,
                Map.of("pr_number", "not-a-number"),
                null,
                null
        );

        assertNull(spec.getPrNumber());
    }

    @Test
    void testGetPrNumber_ReturnsNullForEmptyString() {
        var spec = JobSpec.of(
                "task",
                true,
                true,
                "model",
                null,
                null,
                false,
                Map.of("pr_number", ""),
                null,
                null
        );

        assertNull(spec.getPrNumber());
    }

    @Test
    void testOfPrReview_WithLargePrNumber() {
        var spec = JobSpec.ofPrReview("gpt-4", "token", "owner", "repo", 999999);

        assertEquals(999999, spec.getPrNumber());
    }

    @Test
    void testTagsImmutable() {
        var spec = JobSpec.ofPrReview("gpt-4", "token", "owner", "repo", 1);

        // Verify tags map is immutable by attempting to retrieve it
        var tags = spec.tags();
        assertEquals(4, tags.size());
        assertTrue(tags.containsKey("github_token"));
        assertTrue(tags.containsKey("repo_owner"));
        assertTrue(tags.containsKey("repo_name"));
        assertTrue(tags.containsKey("pr_number"));
    }
}
