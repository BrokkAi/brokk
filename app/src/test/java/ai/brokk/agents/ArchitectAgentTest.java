package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.TaskEntry;
import ai.brokk.TaskResult;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.SpecialTextType;
import ai.brokk.prompts.WorkspacePrompts;
import ai.brokk.tasks.TaskList;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchitectAgentTest {
    @TempDir
    Path projectRoot;

    private TestProject project;
    private TestContextManager cm;
    private TestConsoleIO consoleIO;

    @BeforeEach
    void setUp() {
        project = new TestProject(projectRoot);
        consoleIO = new TestConsoleIO();
        cm = new TestContextManager(projectRoot, consoleIO);
    }

    private static final class StubModel implements StreamingChatModel {}

    @Test
    void testArchitectPromptIncludesTaskList() {
        var tasks = List.of(
                new TaskList.TaskItem(null, "First task description", false),
                new TaskList.TaskItem("Second Task", "Second task with title", false));
        var taskListData = new TaskList.TaskListData(tasks);

        var context = new Context(cm).withTaskList(taskListData);

        var architectSuppressed = EnumSet.noneOf(SpecialTextType.class);
        var messages = WorkspacePrompts.getMessagesGroupedByMutability(context, architectSuppressed);

        String allMessageText = messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::singleText)
                .reduce("", (a, b) -> a + "\n" + b);

        assertTrue(
                allMessageText.contains("Task List") || allMessageText.contains("First task"),
                "Architect prompt should include task list content. Got: " + allMessageText);
    }

    @Test
    void testCodeAgentPromptExcludesTaskList() {
        var tasks = List.of(
                new TaskList.TaskItem(null, "First task description", false),
                new TaskList.TaskItem("Second Task", "Second task with title", false));
        var taskListData = new TaskList.TaskListData(tasks);

        var context = new Context(cm).withTaskList(taskListData);

        var codeAgentSuppressed = EnumSet.of(SpecialTextType.TASK_LIST);
        var messages = WorkspacePrompts.getMessagesGroupedByMutability(context, codeAgentSuppressed);

        String allMessageText = messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::singleText)
                .reduce("", (a, b) -> a + "\n" + b);

        assertFalse(
                allMessageText.contains("Task List")
                        || allMessageText.contains("First task")
                        || allMessageText.contains("Second task"),
                "CodeAgent prompt should NOT include task list content when suppressed. Got: " + allMessageText);
    }

    @Test
    void testSubAgentOutcomePreference() {
        var cm = new TestContextManager(projectRoot, consoleIO);
        var ctx = new Context(cm);
        var subResult = new TaskResult(
                Context.EMPTY, new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS, "Concise Outcome"));

        var model = new ai.brokk.Service.ModelConfig(
                "m", ai.brokk.Service.ReasoningLevel.DEFAULT, ai.brokk.Service.ProcessingTier.DEFAULT);
        var searchMeta = new TaskResult.TaskMeta(TaskResult.Type.SEARCH, model);

        // Setup entry with summary AND sub-agent result. Result should win.
        var entry = new TaskEntry(0, null, null, "Raw Summary", searchMeta, subResult);
        ctx = ctx.withHistory(List.of(entry));

        // When Architect gets history messages, it should see the result block instead of raw messages or summary
        var archMeta = new TaskResult.TaskMeta(TaskResult.Type.ARCHITECT, model);
        var historyMessages = WorkspacePrompts.getHistoryMessages(ctx, archMeta);

        boolean foundConcise = historyMessages.stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> ((UserMessage) m).singleText())
                .anyMatch(t -> t.contains("Concise Outcome"));

        boolean foundSummary = historyMessages.stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> ((UserMessage) m).singleText())
                .anyMatch(t -> t.contains("Raw Summary"));

        assertTrue(
                foundConcise,
                "Architect should see the concise sub-agent outcome in history. Messages: " + historyMessages);
        assertFalse(foundSummary, "Architect should NOT see the raw summary when a sub-agent result is available");
    }

    @Test
    void testFinalMopMessagesMergedIntoLastHistoryEntry() {
        StreamingChatModel model = new StubModel();

        var initialMsg = UserMessage.from("initial");
        var entry = new TaskEntry(
                0, new ContextFragments.TaskFragment(List.of(initialMsg), "initial-desc"), null, null, null);
        var ctx = new Context(cm).withHistory(List.of(entry));

        var agent = new ArchitectAgent(cm, model, model, "goal", null, ctx, consoleIO);

        consoleIO.llmOutput("final-status", ChatMessageType.AI, ai.brokk.LlmOutputMeta.newMessage());

        var tr = new ai.brokk.TaskResult(ctx, new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS));
        var finalMessages = consoleIO.getLlmRawMessages();
        assertFalse(finalMessages.isEmpty());

        var taskHistory = tr.context().getTaskHistory();
        Context updatedContext;
        if (!taskHistory.isEmpty()) {
            var lastEntry = taskHistory.getLast();
            var updatedLastEntry = lastEntry.withAppendedMopMessages(finalMessages, "Architect: goal");
            var newHistory = new java.util.ArrayList<>(taskHistory);
            newHistory.set(newHistory.size() - 1, updatedLastEntry);
            updatedContext = tr.context().withHistory(newHistory);
        } else {
            updatedContext = tr.context()
                    .addHistoryEntry(new ContextFragments.TaskFragment(finalMessages, "Architect: goal"), null);
        }

        assertEquals(1, updatedContext.getTaskHistory().size());
        assertEquals(
                List.of(initialMsg, finalMessages.getFirst()),
                updatedContext.getTaskHistory().getLast().mopLog().messages());
    }
}
