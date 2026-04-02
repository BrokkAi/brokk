package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GitWorkflowTest {

    @Test
    void parseCommitMessageDraftFindsBalancedJsonAtEndOfNarratedOutput() {
        String input =
                """
                Wait, let me think this through.
                The prompt says most commits should only contain a subject line.

                {"subject":"test: simplify persistence and ensure cache directories exist","body":["- Remove legacy `.gz` and `.gzip` file deletion checks.","- Legacy compressed formats are no longer supported.","- Ensure parent directories exist in `WorktreeProjectWarmStartTest`.","- This prevents cache file writing errors."],"useBody":true}
                """;

        var parsed = GitWorkflow.parseCommitMessageDraft(input, false);

        assertEquals(
                """
                test: simplify persistence and ensure cache directories exist

                - Remove legacy `.gz` and `.gzip` file deletion checks.
                - Legacy compressed formats are no longer supported.
                - Ensure parent directories exist in `WorktreeProjectWarmStartTest`.
                - This prevents cache file writing errors.
                """,
                parsed.map(GitWorkflow::formatCommitMessage).orElseThrow());
    }

    @Test
    void parseCommitMessageDraftRejectsMetaNarrationInStructuredFields() {
        String input =
                """
                {"subject":"Subject: test: final subject","body":[],"useBody":false}
                """;

        assertEquals(true, GitWorkflow.parseCommitMessageDraft(input, true).isEmpty());
    }

    @Test
    void parseCommitMessageDraftRejectsBodyForOneLineRequests() {
        String input =
                """
                {"subject":"test: final subject","body":["- Extra explanation"],"useBody":true}
                """;

        assertEquals(true, GitWorkflow.parseCommitMessageDraft(input, true).isEmpty());
    }

    @Test
    void parseCommitMessageDraftRejectsLegacyFreeformOutput() {
        String input =
                """
                Wait, let me think this through.

                test: final subject

                - Extra explanation
                """;

        assertEquals(true, GitWorkflow.parseCommitMessageDraft(input, false).isEmpty());
    }

    @Test
    void parseCommitMessageDraftRejectsInconsistentBodyFlags() {
        String input =
                """
                {"subject":"test: final subject","body":["- Extra explanation"],"useBody":false}
                """;

        assertEquals(true, GitWorkflow.parseCommitMessageDraft(input, false).isEmpty());
    }
}
