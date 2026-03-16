package ai.brokk.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.context.Context;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import dev.langchain4j.data.message.SystemMessage;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchPromptsTest {
    @TempDir
    Path tempDir;

    @Test
    void issueDiagnosisTerminals_isIssueOnly() {
        assertEquals(
                EnumSet.of(SearchPrompts.Terminal.DESCRIBE_ISSUE),
                SearchPrompts.Objective.ISSUE_DESCRIPTION.terminals());
    }

    @Test
    void searchSystemPrompt_containsGroundedGuidance() {
        Context context = new Context(new TestContextManager(tempDir, new NoOpConsoleIO()));

        // Include tools to trigger conditional sections
        SystemMessage message = SearchPrompts.instance.searchSystemPrompt(
                context,
                SearchPrompts.Objective.ANSWER_ONLY,
                List.of("answer", "abortSearch", "searchSymbols", "jq", "getGitLog"));

        String content = message.text();
        assertTrue(content.contains("Be grounded in repository evidence"), "Prompt should contain grounded guidance");
        assertTrue(content.contains("cost/benefit exploration"), "Prompt should mention cost/benefit");
        assertTrue(content.contains("caller can retry"), "Prompt should allow pragmatic stopping");
        assertTrue(content.contains("furtherInvestigation"), "Prompt should reference furtherInvestigation field");
        assertTrue(
                content.contains("answer(explanation, furtherInvestigation)"),
                "Prompt should reference answer signature");
        assertTrue(content.contains("abortSearch(explanation)"), "Prompt should reference abort signature");

        // Assert dynamic conditional sections
        assertTrue(content.contains("Use syntax-aware tools"), "Syntax-aware guidance should be present");
        assertTrue(content.contains("Use structured tools"), "Structured data guidance should be present");
        assertTrue(content.contains("Use Git tools"), "Git history guidance should be present");
    }

    @Test
    void searchSystemPrompt_workspaceObjective_containsWorkspaceComplete() {
        Context context = new Context(new TestContextManager(tempDir, new NoOpConsoleIO()));

        SystemMessage message = SearchPrompts.instance.searchSystemPrompt(
                context, SearchPrompts.Objective.WORKSPACE_ONLY, List.of("workspaceComplete", "abortSearch"));

        String content = message.text();
        assertTrue(
                content.contains("workspaceComplete(fragmentIdsOrDescriptions, furtherInvestigation)"),
                "Prompt should reference workspaceComplete signature");
    }
}
