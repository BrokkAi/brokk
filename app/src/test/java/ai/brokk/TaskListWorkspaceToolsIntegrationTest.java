package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.context.Context;
import ai.brokk.tasks.TaskList;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.tools.WorkspaceTools;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Integration tests verifying WorkspaceTools task list method delegates correctly
 * to ContextManager and produce the expected task list state.
 */
public class TaskListWorkspaceToolsIntegrationTest {

    @Test
    void workspaceTools_createOrReplaceTaskList_delegatesToContextManager() {
        var cm = new TestContextManager(Paths.get(".").toAbsolutePath().normalize(), new TestConsoleIO());
        var context = new Context(cm);
        var wst = new WorkspaceTools(context);

        var explanation = "Building feature X requires these steps";
        var tasks = List.of("Step 1: Design API", "Step 2: Implement", "Step 3: Test");

        var result = wst.createOrReplaceTaskList(explanation, tasks);

        assertTrue(result.contains("Step 1: Design API"));
        assertTrue(result.contains("Step 2: Implement"));
        assertTrue(result.contains("Step 3: Test"));

        var updatedContext = wst.getContext();
        var data = updatedContext.getTaskListDataOrEmpty();
        assertEquals(3, data.tasks().size());
    }

    @Test
    void workspaceTools_createOrReplaceTaskList_updateContextImmutably() {
        var cm = new TestContextManager(Paths.get(".").toAbsolutePath().normalize(), new TestConsoleIO());
        var context = new Context(cm);
        var wst = new WorkspaceTools(context);

        wst.createOrReplaceTaskList("Initial", List.of("Task A"));
        var c1 = wst.getContext();
        assertEquals(1, c1.getTaskListDataOrEmpty().tasks().size());

        // Replace with new list (immutably updating WorkspaceTools context)
        wst.setContext(c1);
        wst.createOrReplaceTaskList("Replace", List.of("Task B", "Task C"));
        var c2 = wst.getContext();
        assertEquals(2, c2.getTaskListDataOrEmpty().tasks().size());
        assertEquals("Task B", c2.getTaskListDataOrEmpty().tasks().get(0).text());
        assertEquals("Task C", c2.getTaskListDataOrEmpty().tasks().get(1).text());
    }

    @Test
    void workspaceTools_createOrReplaceTaskList_outputFormatted() {
        var cm = new TestContextManager(Paths.get(".").toAbsolutePath().normalize(), new TestConsoleIO());
        var context = new Context(cm);
        var wst = new WorkspaceTools(context);

        var tasks = List.of("Fix bug in parser", "Add error handling", "Update docs");
        var result = wst.createOrReplaceTaskList("Bug fix sprint", tasks);

        assertTrue(result.contains("# Task List"));
        assertTrue(result.contains("1. Fix bug in parser"));
        assertTrue(result.contains("2. Add error handling"));
        assertTrue(result.contains("3. Update docs"));
    }

    @Test
    void workspaceTools_createOrReplace_dropsCompletedFromPrevious() {
        var cm = new TestContextManager(Paths.get(".").toAbsolutePath().normalize(), new TestConsoleIO());
        var initial = new Context(cm);

        var mixed = new TaskList.TaskListData(List.of(
                new TaskList.TaskItem("Done", "Completed", true), new TaskList.TaskItem("Todo", "Not done", false)));
        var c1 = initial.withTaskList(mixed);

        var wst = new WorkspaceTools(c1);
        wst.createOrReplaceTaskList("Fresh start", List.of("New task"));

        var updated = wst.getContext();
        var data = updated.getTaskListDataOrEmpty();
        assertEquals(1, data.tasks().size());
        assertEquals("New task", data.tasks().get(0).text());
        assertTrue(data.tasks().stream().noneMatch(t -> t.text().equals("Completed")));
    }

    @Test
    void workspaceTools_taskTitlesAutoSummarized_inBothMethods() {
        var cm = new TestContextManager(Paths.get(".").toAbsolutePath().normalize(), new TestConsoleIO());
        var context = new Context(cm);
        var wst = new WorkspaceTools(context);

        var longTaskDescription =
                "This is a very long task description that would benefit from auto-summarization to fit nicely in the UI";
        wst.createOrReplaceTaskList("Init", List.of(longTaskDescription));

        var data = wst.getContext().getTaskListDataOrEmpty();
        var task = data.tasks().get(0);

        assertNotNull(task.title());
        assertFalse(task.title().isEmpty());
        assertEquals(longTaskDescription, task.text());
    }
}
