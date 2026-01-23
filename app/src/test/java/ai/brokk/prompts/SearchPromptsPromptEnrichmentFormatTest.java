package ai.brokk.prompts;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.agents.TestScriptedLanguageModel;
import ai.brokk.context.Context;
import ai.brokk.TaskResult;
import ai.brokk.Service;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchPromptsPromptEnrichmentFormatTest {

    @Test
    void promptEnrichmentDirective_includesRequiredSectionsAndProhibitions() throws Exception {
        var tempDir = Files.createTempDirectory("brokk-prompt-enrichment-test-");
        try {
            var project = new TestProject(tempDir);
            var cm = new TestContextManager(
                    project, new TestConsoleIO(), java.util.Set.of(), new ai.brokk.testutil.TestAnalyzer());
            Context ctx = cm.liveContext();

            String goal =
                    """
                    Please enrich this request into a prompt I can hand to an LLM.

                    Request: Update app/src/main/java/com/acme/Foo.java in function Foo.compute(int a, int b) to avoid overflow.
                    Facts: The method currently does naive addition. The project uses Java 21.
                    Test expectation: Add/adjust a unit test to cover overflow behavior and run the relevant tests.
                    """
                            .stripIndent();

            var model = new TestScriptedLanguageModel("unused");
            var meta = new TaskResult.TaskMeta(TaskResult.Type.SEARCH, new Service.ModelConfig("test"));
            var result = SearchPrompts.instance.buildPrompt(
                    ctx,
                    model,
                    meta,
                    goal,
                    SearchPrompts.Objective.PROMPT_ENRICHMENT,
                    List.of(),
                    List.of());

            String directive = extractPromptEnrichmentDirective(result.messages());

            assertTrue(directive.contains("<prompt_enrichment>"));

            assertTrue(directive.contains("enrichment"));
            assertTrue(directive.contains("Do NOT invent"));
            assertTrue(directive.contains("Do NOT guess"));
            assertTrue(directive.contains("Identify the primary code changes"));

            assertTrue(directive.contains("**Summary**"));
            assertTrue(directive.contains("**Context**"));
            assertTrue(directive.contains("**Requirements**"));
            assertTrue(directive.contains("**Constraints**"));
            assertTrue(directive.contains("**Edge Cases**"));
            assertTrue(directive.contains("**Acceptance Criteria**"));
            assertTrue(directive.contains("**Open Questions**"));
            assertTrue(directive.contains("**Verification**"));
            assertTrue(directive.contains("**Plan**"));

            assertTrue(directive.contains("step-by-step"));
            assertTrue(directive.contains("cite them; otherwise do NOT invent paths/symbols"));
            assertTrue(directive.contains("key files/modules/classes/methods"));
            assertTrue(directive.contains("only if supported by the input or discovered from the repo"));
            assertTrue(directive.contains("otherwise ask in **Open Questions**"));
        } finally {
            ai.brokk.util.FileUtil.deleteRecursively(tempDir);
        }
    }

    private static String extractPromptEnrichmentDirective(List<ChatMessage> messages) {
        return messages.stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> ((UserMessage) m).singleText())
                .filter(t -> t.contains("<prompt_enrichment>"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No UserMessage contained <prompt_enrichment> directive"));
    }
}
