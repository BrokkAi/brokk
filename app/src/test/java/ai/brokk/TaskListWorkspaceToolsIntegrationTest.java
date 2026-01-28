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
        var tasks = List.of(
                new WorkspaceTools.TaskListEntry("Task 1", "Step 1: Design API", "loc1", "disc1"),
                new WorkspaceTools.TaskListEntry("Task 2", "Step 2: Implement", "loc2", "disc2"),
                new WorkspaceTools.TaskListEntry("Task 3", "Step 3: Test", "loc3", "disc3"));

        var result = wst.createOrReplaceTaskList(explanation, tasks);

        assertTrue(result.contains("Task 1"));
        assertTrue(result.contains("Task 2"));
        assertTrue(result.contains("Task 3"));

        var updatedContext = wst.getContext();
        var data = updatedContext.getTaskListDataOrEmpty();
        assertEquals(3, data.tasks().size());
    }

    @Test
    void workspaceTools_createOrReplaceTaskList_updateContextImmutably() {
        var cm = new TestContextManager(Paths.get(".").toAbsolutePath().normalize(), new TestConsoleIO());
        var context = new Context(cm);
        var wst = new WorkspaceTools(context);

        wst.createOrReplaceTaskList("Initial", List.of(new WorkspaceTools.TaskListEntry("T1", "Task A", "", "")));
        var c1 = wst.getContext();
        assertEquals(1, c1.getTaskListDataOrEmpty().tasks().size());

        // Replace with new list (immutably updating WorkspaceTools context)
        wst.setContext(c1);
        wst.createOrReplaceTaskList(
                "Replace",
                List.of(
                        new WorkspaceTools.TaskListEntry("T2", "Task B", "", ""),
                        new WorkspaceTools.TaskListEntry("T3", "Task C", "", "")));
        var c2 = wst.getContext();
        assertEquals(2, c2.getTaskListDataOrEmpty().tasks().size());
        assertEquals("T2", c2.getTaskListDataOrEmpty().tasks().get(0).title());
        assertEquals("T3", c2.getTaskListDataOrEmpty().tasks().get(1).title());
    }

    @Test
    void workspaceTools_createOrReplaceTaskList_outputFormatted() {
        var cm = new TestContextManager(Paths.get(".").toAbsolutePath().normalize(), new TestConsoleIO());
        var context = new Context(cm);
        var wst = new WorkspaceTools(context);

        var tasks = List.of(
                new WorkspaceTools.TaskListEntry("Fix Parser", "Fix bug in parser", "", ""),
                new WorkspaceTools.TaskListEntry("Errors", "Add error handling", "", ""),
                new WorkspaceTools.TaskListEntry("Docs", "Update docs", "", ""));
        var result = wst.createOrReplaceTaskList("Bug fix sprint", tasks);

        assertTrue(result.contains("# Task List"));
        assertTrue(result.contains("1. Fix Parser"));
        assertTrue(result.contains("2. Errors"));
        assertTrue(result.contains("3. Docs"));
    }

    @Test
    void workspaceTools_createOrReplace_dropsCompletedFromPrevious() {
        var cm = new TestContextManager(Paths.get(".").toAbsolutePath().normalize(), new TestConsoleIO());
        var initial = new Context(cm);

        var mixed = new TaskList.TaskListData(List.of(
                new TaskList.TaskItem("Done", "Completed", true), new TaskList.TaskItem("Todo", "Not done", false)));
        var c1 = initial.withTaskList(mixed);

        var wst = new WorkspaceTools(c1);
        wst.createOrReplaceTaskList(
                "Fresh start", List.of(new WorkspaceTools.TaskListEntry("New", "New task", "", "")));

        var updated = wst.getContext();
        var data = updated.getTaskListDataOrEmpty();
        assertEquals(1, data.tasks().size());
        assertEquals("New", data.tasks().get(0).title());
        assertTrue(data.tasks().stream().noneMatch(t -> t.title().equals("Done")));
    }

    @Test
    void workspaceTools_createOrReplaceTaskList_passesBigPicture() {
        var cm = new TestContextManager(Paths.get(".").toAbsolutePath().normalize(), new TestConsoleIO());
        var context = new Context(cm);
        var wst = new WorkspaceTools(context);

        var explanation = "This is the big picture explanation for the task list";
        var tasks = List.of(
                new WorkspaceTools.TaskListEntry("T1", "Task 1", "", ""),
                new WorkspaceTools.TaskListEntry("T2", "Task 2", "", ""));

        wst.createOrReplaceTaskList(explanation, tasks);

        var updatedContext = wst.getContext();
        var data = updatedContext.getTaskListDataOrEmpty();

        assertEquals(explanation, data.bigPicture());
        assertEquals(2, data.tasks().size());
    }

    @Test
    void workspaceTools_taskTitlesExplicitlySet() {
        var cm = new TestContextManager(Paths.get(".").toAbsolutePath().normalize(), new TestConsoleIO());
        var context = new Context(cm);
        var wst = new WorkspaceTools(context);

        var longTaskDescription =
                "This is a very long task description that would benefit from auto-summarization to fit nicely in the UI";
        var explicitTitle = "Summarized Title";
        wst.createOrReplaceTaskList(
                "Init", List.of(new WorkspaceTools.TaskListEntry(explicitTitle, longTaskDescription, "", "")));

        var data = wst.getContext().getTaskListDataOrEmpty();
        var task = data.tasks().get(0);

        assertEquals(explicitTitle, task.title());
        assertTrue(task.text().contains(longTaskDescription));
    }
}
