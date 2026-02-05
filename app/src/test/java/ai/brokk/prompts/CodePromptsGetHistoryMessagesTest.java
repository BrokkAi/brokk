package ai.brokk.prompts;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AbstractService;
import ai.brokk.IContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.TaskResult;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragments;
import ai.brokk.util.Messages;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class CodePromptsGetHistoryMessagesTest {

    @Test
    void getHistoryMessages_sameModel_preservesToolExecutionRequests() {
        IContextManager cm = new IContextManager() {};
        Context ctx = new Context(cm);

        var toolRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("searchFiles")
                .arguments("{\"pattern\": \"*.java\"}")
                .build();
        var aiWithTools = new AiMessage(List.of(toolRequest));
        var toolResult = new ToolExecutionResultMessage("call-1", "searchFiles", "Found 5 files");
        var finalAi = new AiMessage("Done.");
        var user = new UserMessage("Find Java files");

        var entryMessages = List.<ChatMessage>of(user, aiWithTools, toolResult, finalAi);

        var fragment = new ContextFragments.TaskFragment(cm, entryMessages, "test task");
        var entry = new TaskEntry(-1, fragment, null);
        var entryMeta = new TaskResult.TaskMeta(TaskResult.Type.CODE, new AbstractService.ModelConfig("model-A"));
        entry = new TaskEntry(entry.sequence(), entry.log(), entry.summary(), entryMeta);

        ctx = ctx.withHistory(List.of(entry));

        var currentMeta = new TaskResult.TaskMeta(TaskResult.Type.CODE, new AbstractService.ModelConfig("model-A"));
        var history = CodePrompts.instance.getHistoryMessages(ctx, currentMeta);

        var toolAiMessages = history.stream()
                .filter(m -> m instanceof AiMessage ai && ai.hasToolExecutionRequests())
                .map(m -> (AiMessage) m)
                .toList();

        assertEquals(1, toolAiMessages.size());
        assertSame(aiWithTools, toolAiMessages.getFirst());
        assertEquals(1, toolAiMessages.getFirst().toolExecutionRequests().size());

        assertTrue(history.stream().anyMatch(m -> m instanceof ToolExecutionResultMessage));

        assertFalse(history.stream()
                .filter(m -> m instanceof AiMessage)
                .map(m -> ((AiMessage) m).text())
                .filter(Objects::nonNull)
                .anyMatch(t -> t.contains("[Historical tool usage by a different model]")));
    }

    @Test
    void getHistoryMessages_differentModel_dropsToolExecutionResults_andRedactsToolRequests() {
        IContextManager cm = new IContextManager() {};
        Context ctx = new Context(cm);

        var toolRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("searchFiles")
                .arguments("{\"pattern\": \"*.java\"}")
                .build();
        var aiWithTools = new AiMessage(List.of(toolRequest));
        var toolResult = new ToolExecutionResultMessage("call-1", "searchFiles", "Found 5 files");
        var finalAi = new AiMessage("Done.");
        var user = new UserMessage("Find Java files");

        var entryMessages = List.<ChatMessage>of(user, aiWithTools, toolResult, finalAi);

        var fragment = new ContextFragments.TaskFragment(cm, entryMessages, "test task");
        var entry = new TaskEntry(-1, fragment, null);
        var entryMeta = new TaskResult.TaskMeta(TaskResult.Type.CODE, new AbstractService.ModelConfig("model-A"));
        entry = new TaskEntry(entry.sequence(), entry.log(), entry.summary(), entryMeta);

        ctx = ctx.withHistory(List.of(entry));

        var currentMeta = new TaskResult.TaskMeta(TaskResult.Type.CODE, new AbstractService.ModelConfig("model-B"));
        var history = CodePrompts.instance.getHistoryMessages(ctx, currentMeta);

        assertFalse(history.stream().anyMatch(m -> m instanceof ToolExecutionResultMessage));

        assertTrue(history.stream()
                .filter(m -> m instanceof UserMessage)
                .map(Messages::getText)
                .anyMatch("Find Java files"::equals));

        assertFalse(history.stream().anyMatch(m -> m instanceof AiMessage ai && ai.hasToolExecutionRequests()));

        var aiTexts = history.stream()
                .filter(m -> m instanceof AiMessage)
                .map(m -> ((AiMessage) m).text())
                .filter(Objects::nonNull)
                .toList();

        assertTrue(aiTexts.stream().anyMatch(t -> t.contains("[Historical tool usage by a different model]")));
        assertTrue(aiTexts.stream().anyMatch(t -> t.contains("searchFiles")));
    }
}
