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
        assertEquals(EnumSet.of(SearchPrompts.Terminal.DESCRIBE_ISSUE), SearchPrompts.Objective.ISSUE_DESCRIPTION.terminals());
    }

    @Test
    void searchSystemPrompt_includesObjectiveAndDeliverable() throws Exception {
        var tempDir = Files.createTempDirectory("brokk-search-system-prompt-test-");
        try {
            var project = new TestProject(tempDir);
            var cm = new TestContextManager(
                    project, new TestConsoleIO(), java.util.Set.of(), new ai.brokk.testutil.TestAnalyzer());
            Context ctx = cm.liveContext();

            var answerOnly = SearchPrompts.instance
                    .searchSystemPrompt(ctx, SearchPrompts.Objective.ANSWER_ONLY)
                    .text();
            assertTrue(answerOnly.contains("Objective: ANSWER_ONLY"));
            assertTrue(answerOnly.contains("Deliverable: a comprehensive Markdown answer"));

            var workspaceOnly = SearchPrompts.instance
                    .searchSystemPrompt(ctx, SearchPrompts.Objective.WORKSPACE_ONLY)
                    .text();
            assertTrue(workspaceOnly.contains("Objective: WORKSPACE_ONLY"));
            assertTrue(workspaceOnly.contains("Deliverable: a curated Workspace ready for the Code Agent"));

            var issueDiagnosis = SearchPrompts.instance
                    .searchSystemPrompt(ctx, SearchPrompts.Objective.ISSUE_DESCRIPTION)
                    .text();
            assertTrue(issueDiagnosis.contains("Objective: ISSUE_DIAGNOSIS"));
            assertTrue(issueDiagnosis.contains("Deliverable: a high-quality GitHub issue"));
        } finally {
            ai.brokk.util.FileUtil.deleteRecursively(tempDir);
        }
    }
}
