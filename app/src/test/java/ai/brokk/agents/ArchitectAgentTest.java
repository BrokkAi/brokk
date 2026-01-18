package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.context.Context;
import ai.brokk.context.SpecialTextType;
import ai.brokk.prompts.WorkspacePrompts;
import ai.brokk.tasks.TaskList;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import dev.langchain4j.data.message.UserMessage;
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
}
