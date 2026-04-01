package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import ai.brokk.tools.ToolRegistry;
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

    @Test
    void testCallCodeAgentRejectedWhenReadOnly() {
        StreamingChatModel model = new StubModel();
        var agent = new ArchitectAgent(cm, model, model, "goal", null, new Context(cm), consoleIO);
        agent.setReadOnly(true);

        var ex = assertThrows(ToolRegistry.FatalLlmException.class, () -> agent.callCodeAgent("edit the file", false));

        assertTrue(ex.getMessage().contains("disabled"));
    }

    @Test
    void testSetBuildDetailsRejectedWhenBuildToolsDisabled() {
        StreamingChatModel model = new StubModel();
        var agent = new ArchitectAgent(cm, model, model, "goal", null, new Context(cm), consoleIO);
        agent.setBuildToolsEnabled(false);

        var result = agent.setBuildDetails(
                "./gradlew test", "./gradlew test", "./gradlew test --tests ExampleTest", List.of("build"));

        assertEquals("Build/test tools are disabled for this Architect run.", result);
        assertFalse(project.hasBuildDetails(), "Disabled build tools should not persist new build details");
    }

    @Test
    void testVerifyBuildCommandRejectedWhenBuildToolsDisabled() {
        StreamingChatModel model = new StubModel();
        var agent = new ArchitectAgent(cm, model, model, "goal", null, new Context(cm), consoleIO);
        agent.setBuildToolsEnabled(false);

        var result = agent.verifyBuildCommand();

        assertEquals("Build/test tools are disabled for this Architect run.", result);
    }
}
