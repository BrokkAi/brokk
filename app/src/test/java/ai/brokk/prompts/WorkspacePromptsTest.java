package ai.brokk.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.Service;
import ai.brokk.TaskEntry;
import ai.brokk.TaskResult;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragments;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.util.Messages;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacePromptsTest {
    @TempDir
    Path tempDir;

    private Context context;
    private TaskResult.TaskMeta currentMeta;

    @BeforeEach
    void setup() {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());
        context = new Context(cm);
        var model = new Service.ModelConfig(
                "current-model", Service.ReasoningLevel.DEFAULT, Service.ProcessingTier.DEFAULT);
        currentMeta = new TaskResult.TaskMeta(TaskResult.Type.ARCHITECT, model);
    }

    @Test
    void testGetHistoryMessages_PrecedenceSummary() {
        var log = new ContextFragments.TaskFragment(List.of(UserMessage.from("log")), "desc");
        var entry = new TaskEntry(0, log, log, "summary text", null);
        context = context.addHistoryEntry(entry);

        List<ChatMessage> messages = WorkspacePrompts.getHistoryMessages(context, currentMeta);

        assertEquals(2, messages.size());
        assertTrue(messages.get(0) instanceof UserMessage);
        assertTrue(Messages.getText(messages.get(0)).contains("summary text"));
    }

    @Test
    void testGetHistoryMessages_PrecedenceSubAgentResult() {
        var log = new ContextFragments.TaskFragment(List.of(UserMessage.from("log")), "desc");
        var subResult =
                new TaskResult(Context.EMPTY, new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS, "done"));
        var meta = new TaskResult.TaskMeta(TaskResult.Type.SEARCH, null);
        var entry = new TaskEntry(0, log, log, null, meta, subResult);
        context = context.addHistoryEntry(entry);

        List<ChatMessage> messages = WorkspacePrompts.getHistoryMessages(context, currentMeta);

        assertEquals(2, messages.size());
        assertTrue(messages.get(0) instanceof UserMessage);
        String text = Messages.getText(messages.get(0));
        assertTrue(text.contains("Search result"));
        assertTrue(text.contains("SUCCESS"));
    }

    @Test
    void testGetHistoryMessages_PrecedenceLlmLogRedaction() {
        var toolReq =
                ToolExecutionRequest.builder().name("myTool").arguments("{}").build();
        var aiMsg = AiMessage.from(toolReq);
        var toolRes = new ToolExecutionResultMessage("id", "myTool", "output");
        var log = new ContextFragments.TaskFragment(List.of(UserMessage.from("query"), aiMsg, toolRes), "desc");

        var entry = new TaskEntry(0, log, log, null, null);
        context = context.addHistoryEntry(entry);

        List<ChatMessage> messages = WorkspacePrompts.getHistoryMessages(context, currentMeta);

        // Should have: User("query"), Ai message (redacted)
        // ToolExecutionResultMessage should be omitted
        assertEquals(2, messages.size());
        assertTrue(messages.get(0) instanceof UserMessage);
        assertTrue(messages.get(1) instanceof AiMessage);

        String aiText = Messages.getText(messages.get(1));
        // CodePrompts.redactToolCallsFromOtherModels (called via redactHistoryMessages)
        // formats tool calls using Messages.getRedactedRepr as: "Tool `name` was invoked with args"
        // prefixed by "[Historical tool usage by a different model]"
        // CodePrompts.redactToolCallsFromOtherModels formats tool calls using Messages.getRedactedRepr
        // and a historical usage prefix: "[Historical tool usage by a different model]\nTool `name` was invoked with
        // args"
        assertTrue(aiText.contains("[Historical tool usage by a different model]"), "AI text was: " + aiText);
        assertTrue(aiText.contains("Tool `myTool` was invoked with"), "AI text was: " + aiText);
        assertTrue(
                messages.stream().noneMatch(m -> m instanceof ToolExecutionResultMessage),
                "ToolExecutionResultMessage was not omitted");
    }
}
