package ai.brokk.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.context.Context;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
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
    void lutzSystemPrompt_includesShellInitGuidance() {
        TestProject project = new TestProject(tempDir);
        TestContextManager cm = new TestContextManager(project);
        Context context = new Context(cm);

        var systemMsg = SearchPrompts.instance.lutzSystemPrompt(context, SearchPrompts.Objective.LUTZ);
        String text = systemMsg.text();

        assertTrue(
                text.contains("runShellCommand for project initialization/scaffolding"),
                "Prompt should contain scaffolding guidance");
        assertTrue(
                text.contains("do not use shell output as a substitute for code-symbol analysis"),
                "Prompt should contain symbol analysis warning");
    }

    @Test
    void searchAgentSystemPrompt_includesShellInitGuidance() {
        TestProject project = new TestProject(tempDir);
        TestContextManager cm = new TestContextManager(project);
        Context context = new Context(cm);

        var systemMsg = SearchPrompts.instance.searchSystemPrompt(
                context, SearchPrompts.Objective.LUTZ, List.of("searchSymbols", "runShellCommand"));
        String text = systemMsg.text();

        assertTrue(
                text.contains("project initialization/bootstrap commands"), "Prompt should contain bootstrap guidance");
        assertTrue(
                text.contains("not for primary analyzed-code symbol understanding"),
                "Prompt should contain symbol understanding warning");
    }
}
