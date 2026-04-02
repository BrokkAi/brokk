package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GitWorkflowTest {

    @Test
    void parseCommitMessageDraftRejectsNarratedOutput() {
        String input =
                """
                Wait, let me think this through.
                The prompt says most commits should only contain a subject line.

                {"subject":"test: simplify persistence","body":[],"useBody":false}
                """;

        var parsed = GitWorkflow.parseCommitMessageDraft(input, false);
        assertEquals(true, parsed.isEmpty());
    }

    @Test
    void parseCommitMessageDraftAcceptsCleanJson() {
        String input =
                """
                {"subject":"test: simplify persistence","body":[],"useBody":false}
                """;

        var parsed = GitWorkflow.parseCommitMessageDraft(input, false);
        assertEquals("test: simplify persistence", parsed.map(GitWorkflow::formatCommitMessage).orElseThrow());
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
