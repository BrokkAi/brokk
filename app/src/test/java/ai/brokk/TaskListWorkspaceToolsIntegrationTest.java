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
 * Integration tests verifying WorkspaceTools task list methods delegate correctly
 * to ContextManager and produce the expected task list state.
 */
public class TaskListWorkspaceToolsIntegrationTest {

    @Test
    void workspaceTools_createOrReplaceTaskList_delegatesToContextManager() {
        var cm = new TestContextManager(Paths.get("."), new TestConsoleIO());
        var context = new Context(cm);
        var wst = new WorkspaceTools(context);

        // Call the tool method
        var explanation = "Building feature X requires these steps";
        var tasks = List.of("Step 1: Design API", "Step 2: Implement", "Step 3: Test");

        var result = wst.createOrReplaceTaskList(explanation, tasks);

        // Verify result contains formatted task list
        assertTrue(result.contains("Step 1: Design API"));
        assertTrue(result.contains("Step 2: Implement"));
        assertTrue(result.contains("Step 3: Test"));

        // Verify context was updated via wst.getContext()
        var updatedContext = wst.getContext();
        var data = updatedContext.getTaskListDataOrEmpty();
        assertEquals(3, data.tasks().size());
    }

    @Test
    void workspaceTools_appendTaskList_delegatesToContextManager() {
        var cm = new TestContextManager(Paths.get("."), new TestConsoleIO());
        var initial = new Context(cm);

        // Create initial list (simulate via withTaskList)
        var c1 = initial.withTaskList(
                new TaskList.TaskListData(List.of(new TaskList.TaskItem("Initial task", "Initial task", false))),
                "Initial task created");

        // Now use WorkspaceTools to append
        var wst = new WorkspaceTools(c1);
        var explanation = "Additional refinement steps";
        var tasks = List.of("Refine API", "Add documentation");

        var result = wst.appendTaskList(explanation, tasks);

        // Verify result
        assertTrue(result.contains("Refine API"));
        assertTrue(result.contains("Add documentation"));

        // Verify context has all tasks via wst.getContext()
        var updatedContext = wst.getContext();
        var data = updatedContext.getTaskListDataOrEmpty();
        assertEquals(3, data.tasks().size());
        assertEquals("Initial task", data.tasks().get(0).text());
        assertEquals("Refine API", data.tasks().get(1).text());
        assertEquals("Add documentation", data.tasks().get(2).text());
    }

    @Test
    void workspaceTools_bothTaskListTools_updateContextImmutably() {
        var cm = new TestContextManager(Paths.get("."), new TestConsoleIO());
        var context = new Context(cm);
        var wst = new WorkspaceTools(context);

        // First operation
        wst.createOrReplaceTaskList("Initial", List.of("Task A"));
        var c1 = wst.getContext();
        assertEquals(1, c1.getTaskListDataOrEmpty().tasks().size());

        // WorkspaceTools should return updated context
        var c2 = wst.getContext();
        assertEquals(1, c2.getTaskListDataOrEmpty().tasks().size());

        // Append operation
        wst.setContext(c1); // Reset to c1
        wst.appendTaskList("Add more", List.of("Task B"));
        var c3 = wst.getContext();
        assertEquals(2, c3.getTaskListDataOrEmpty().tasks().size());
    }

    @Test
    void workspaceTools_createOrReplaceTaskList_outputFormatted() {
        var cm = new TestContextManager(Paths.get("."), new TestConsoleIO());
        var context = new Context(cm);
        var wst = new WorkspaceTools(context);

        var tasks = List.of("Fix bug in parser", "Add error handling", "Update docs");
        var result = wst.createOrReplaceTaskList("Bug fix sprint", tasks);

        // Verify markdown formatting
        assertTrue(result.contains("# Task List"));
        assertTrue(result.contains("1. Fix bug in parser"));
        assertTrue(result.contains("2. Add error handling"));
        assertTrue(result.contains("3. Update docs"));
    }

    @Test
    void workspaceTools_appendTaskList_outputFormatted() {
        var cm = new TestContextManager(Paths.get("."), new TestConsoleIO());
        var initial = new Context(cm);

        // Create initial list via Context API
        var c1 = initial.withTaskList(
                new TaskList.TaskListData(List.of(new TaskList.TaskItem("Initial", "Initial", false))),
                "Initial created");
        var wst = new WorkspaceTools(c1);

        var tasks = List.of("Follow-up 1", "Follow-up 2");
        var result = wst.appendTaskList("Phase 2", tasks);

        // Verify markdown formatting
        assertTrue(result.contains("# Task List"));
        assertTrue(result.contains("1. Follow-up 1"));
        assertTrue(result.contains("2. Follow-up 2"));
    }

    @Test
    void workspaceTools_createOrReplace_dropsCompletedFromPrevious() {
        var cm = new TestContextManager(Paths.get("."), new TestConsoleIO());
        var initial = new Context(cm);

        // Create list with mixed states
        var mixed = new TaskList.TaskListData(List.of(
                new TaskList.TaskItem("Done", "Completed", true), new TaskList.TaskItem("Todo", "Not done", false)));
        var c1 = initial.withTaskList(mixed, "Mixed");

        // Use WorkspaceTools to replace
        var wst = new WorkspaceTools(c1);
        wst.createOrReplaceTaskList("Fresh start", List.of("New task"));

        var updated = wst.getContext();
        var data = updated.getTaskListDataOrEmpty();
        assertEquals(1, data.tasks().size());
        assertEquals("New task", data.tasks().get(0).text());
        // Completed task should be gone
        assertTrue(data.tasks().stream().noneMatch(t -> t.text().equals("Completed")));
    }

    @Test
    void workspaceTools_appendTaskList_preservesCompletedFromPrevious() {
        var cm = new TestContextManager(Paths.get("."), new TestConsoleIO());
        var initial = new Context(cm);

        // Create list with completed task
        var completed = new TaskList.TaskListData(List.of(new TaskList.TaskItem("Done", "Finished", true)));
        var c1 = initial.withTaskList(completed, "With completed");

        // Use WorkspaceTools to append
        var wst = new WorkspaceTools(c1);
        wst.appendTaskList("More work", List.of("New task"));

        var updated = wst.getContext();
        var data = updated.getTaskListDataOrEmpty();
        assertEquals(2, data.tasks().size());
        assertTrue(data.tasks().get(0).done(), "Completed task should be preserved");
        assertFalse(data.tasks().get(1).done(), "New task should be incomplete");
    }

    @Test
    void workspaceTools_taskTitlesAutoSummarized_inBothMethods() {
        var cm = new TestContextManager(Paths.get("."), new TestConsoleIO());
        var context = new Context(cm);
        var wst = new WorkspaceTools(context);

        var longTaskDescription =
                "This is a very long task description that would benefit from auto-summarization to fit nicely in the UI";
        wst.createOrReplaceTaskList("Init", List.of(longTaskDescription));

        var data = wst.getContext().getTaskListDataOrEmpty();
        var task = data.tasks().get(0);

        // Title should be present and reasonably sized (summary)
        assertNotNull(task.title());
        assertFalse(task.title().isEmpty());
        // Text should be full description
        assertEquals(longTaskDescription, task.text());
    }
}
