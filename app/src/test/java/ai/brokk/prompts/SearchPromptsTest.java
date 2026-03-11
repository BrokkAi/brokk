package ai.brokk.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.context.Context;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class SearchPromptsTest {
    @Test
    void issueDiagnosisTerminals_isIssueOnly() {
        assertEquals(
                EnumSet.of(SearchPrompts.Terminal.DESCRIBE_ISSUE),
                SearchPrompts.Objective.ISSUE_DESCRIPTION.terminals());
    }

    @Test
    void lutzSystemPrompt_includesObjectiveAndDeliverable() throws Exception {
        var tempDir = Files.createTempDirectory("brokk-search-system-prompt-test-");
        try {
            var project = new TestProject(tempDir);
            var cm = new TestContextManager(
                    project, new TestConsoleIO(), java.util.Set.of(), new ai.brokk.testutil.TestAnalyzer());
            Context ctx = cm.liveContext();

            var answerOnly = SearchPrompts.instance
                    .lutzSystemPrompt(ctx, SearchPrompts.Objective.ANSWER_ONLY)
                    .text();
            assertTrue(answerOnly.contains(
                    "Your goal is to gather enough context to answer the user's question accurately"));
            assertTrue(answerOnly.contains("Deliverable: a comprehensive Markdown answer"));

            var workspaceOnly = SearchPrompts.instance
                    .lutzSystemPrompt(ctx, SearchPrompts.Objective.WORKSPACE_ONLY)
                    .text();
            assertTrue(workspaceOnly.contains("Your goal is to prepare the Workspace for the Code Agent"));
            assertTrue(workspaceOnly.contains("Deliverable: a curated Workspace ready for the Code Agent"));

            var issueDiagnosis = SearchPrompts.instance
                    .lutzSystemPrompt(ctx, SearchPrompts.Objective.ISSUE_DESCRIPTION)
                    .text();
            assertTrue(issueDiagnosis.contains("Your goal is to gather enough context to describe the issue"));
            assertTrue(issueDiagnosis.contains("Deliverable: a high-quality GitHub issue"));
        } finally {
            ai.brokk.util.FileUtil.deleteRecursively(tempDir);
        }
    }
}
