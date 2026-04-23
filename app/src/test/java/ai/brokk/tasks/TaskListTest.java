package ai.brokk.tasks;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IAppContextManager;
import ai.brokk.context.Context;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for createOrReplaceTaskList operations.
 * Tests verify task list replacement, summarization, and state persistence.
 */
public class TaskListTest {

    private IAppContextManager cm;
    private Context context;

    @BeforeEach
    void setUp() {
        cm = new TestContextManager(Path.of(".").toAbsolutePath().normalize(), new TestConsoleIO());
        context = new Context(cm);
    }

    // ===== createOrReplaceTaskList Tests =====

    @Test
    void createOrReplaceTaskList_replaceEmptyWithNew() throws Exception {
        var tasks = List.of(
                new TaskList.TaskItem(
                        "Task 1", "Inst 1\n\n**Key Locations:**\nLoc 1\n\n**Key Discoveries:**\nDisc 1", false),
                new TaskList.TaskItem("Task 2", "Inst 2", false),
                new TaskList.TaskItem("Task 3", "Inst 3\n\n**Key Locations:**\nLoc 3", false));

        var result = cm.createOrReplaceTaskList(context, "Big Picture", tasks);

        // Verify fragment exists
        var fragment = result.getTaskListFragment();
        assertTrue(fragment.isPresent(), "Task list fragment should be present");

        // Verify data
        var data = result.getTaskListDataOrEmpty();
        assertEquals(3, data.tasks().size(), "Should have 3 tasks");
        assertEquals("Task 1", data.tasks().get(0).title());
        assertTrue(data.tasks().get(0).text().contains("Inst 1"));
        assertTrue(data.tasks().get(0).text().contains("Loc 1"));
        assertTrue(data.tasks().get(0).text().contains("Disc 1"));

        // All tasks should start as incomplete
        for (var task : data.tasks()) {
            assertFalse(task.done(), "New tasks should be incomplete");
        }
    }

    @Test
    void createOrReplaceTaskList_replaceExistingDropsCompleted() throws Exception {
        // Initial list with mixed completed/incomplete tasks
        var initialData = new TaskList.TaskListData(List.of(
                new TaskList.TaskItem("Done 1", "First completed task", true),
                new TaskList.TaskItem("Incomplete 1", "First incomplete task", false),
                new TaskList.TaskItem("Done 2", "Second completed task", true)));
        var contextWithInitial = context.withTaskList(initialData);

        // Replace with new tasks
        var newTasks = List.of(
                new TaskList.TaskItem("New Task A", "Inst A", false),
                new TaskList.TaskItem("New Task B", "Inst B", false));
        var result = cm.createOrReplaceTaskList(contextWithInitial, "Replacement", newTasks);

        // Verify old tasks are gone and completed tasks dropped
        var data = result.getTaskListDataOrEmpty();
        assertEquals(2, data.tasks().size(), "Should have 2 new tasks");
        assertEquals("New Task A", data.tasks().get(0).title());
        assertEquals("New Task B", data.tasks().get(1).title());

        // New tasks should be incomplete
        for (var task : data.tasks()) {
            assertFalse(task.done(), "Replaced tasks should be incomplete");
        }
    }

    @Test
    void createOrReplaceTaskList_emptyTasksClears() throws Exception {
        // Start with some tasks
        var initialData = new TaskList.TaskListData(List.of(new TaskList.TaskItem("Task", "Some task", false)));
        var contextWithInitial = context.withTaskList(initialData);

        // Replace with empty list (simulating clearing)
        var result = cm.createOrReplaceTaskList(contextWithInitial, null, List.of());

        // Task list fragment should be removed
        var fragment = result.getTaskListFragment();
        assertTrue(fragment.isEmpty(), "Empty task list should remove fragment");
    }

    // ===== Title Summarization Tests =====

    @Test
    void taskTitlesExplicitlySet() throws Exception {
        var explicitTitle = "Summarized Title";
        var longInstructions = "This is a very long task description that should be summarized";
        var tasks = List.of(new TaskList.TaskItem(explicitTitle, longInstructions, false));

        var result = cm.createOrReplaceTaskList(context, "Summary Test", tasks);

        var data = result.getTaskListDataOrEmpty();
        assertEquals(1, data.tasks().size());
        var task = data.tasks().get(0);
        assertEquals(explicitTitle, task.title());
        assertTrue(task.text().contains(longInstructions));
    }

    // ===== Action Message Tests =====

    @Test
    void createOrReplaceTaskList_setsCorrectAction() throws Exception {
        var tasks = List.of(new TaskList.TaskItem("Task 1", "Inst 1", false));

        var result = cm.createOrReplaceTaskList(context, "Action Test", tasks);

        // The description should indicate that something changed (task list added)
        var description = result.getAction(context).join();
        assertFalse(description.isEmpty(), "Description should not be empty after adding task list");
    }

    @Test
    void formatChecklist_producesExpectedMarkdown() {
        var data = new TaskList.TaskListData(List.of(
                new TaskList.TaskItem("Task 1", "Text 1", true), new TaskList.TaskItem("Task 2", "Text 2", false)));

        String expected = "1. [x] Task 1\n2. [ ] Task 2";
        assertEquals(expected, TaskList.formatChecklist(data));
    }

    @Test
    void formatChecklist_handlesEmptyList() {
        var data = new TaskList.TaskListData(List.of());
        assertEquals("(No tasks)", TaskList.formatChecklist(data));
    }
}
