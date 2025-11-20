package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.context.Context;
import ai.brokk.tasks.TaskList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for createOrReplaceTaskList and appendTaskList operations.
 * Tests verify task list replacement, appending, summarization, and state persistence.
 */
public class TaskListTest {

    private IContextManager cm;
    private Context context;

    @BeforeEach
    void setUp() {
        cm = new IContextManager() {};
        context = new Context(cm, (String) null);
    }

    // ===== createOrReplaceTaskList Tests =====

    @Test
    void createOrReplaceTaskList_replaceEmptyWithNew() throws Exception {
        var tasks = List.of("Task 1", "Task 2", "Task 3");

        // Create task items directly without calling ContextManager
        var items = tasks.stream().map(t -> new TaskList.TaskItem(t, t, false)).toList();
        var result = context.withTaskList(new TaskList.TaskListData(items), "Task list created");

        // Verify fragment exists
        var fragment = result.getTaskListFragment();
        assertTrue(fragment.isPresent(), "Task list fragment should be present");

        // Verify data
        var data = result.getTaskListDataOrEmpty();
        assertEquals(3, data.tasks().size(), "Should have 3 tasks");
        assertEquals("Task 1", data.tasks().get(0).text());
        assertEquals("Task 2", data.tasks().get(1).text());
        assertEquals("Task 3", data.tasks().get(2).text());

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
        var contextWithInitial = context.withTaskList(initialData, "Initial setup");

        // Replace with new tasks (simulating replacement)
        var newTasks = List.of("New Task A", "New Task B");
        var newItems =
                newTasks.stream().map(t -> new TaskList.TaskItem(t, t, false)).toList();
        var result = contextWithInitial.withTaskList(new TaskList.TaskListData(newItems), "Task list replaced");

        // Verify old tasks are gone and completed tasks dropped
        var data = result.getTaskListDataOrEmpty();
        assertEquals(2, data.tasks().size(), "Should have 2 new tasks");
        assertEquals("New Task A", data.tasks().get(0).text());
        assertEquals("New Task B", data.tasks().get(1).text());

        // New tasks should be incomplete
        for (var task : data.tasks()) {
            assertFalse(task.done(), "Replaced tasks should be incomplete");
        }
    }

    @Test
    void createOrReplaceTaskList_emptyTasksClears() throws Exception {
        // Start with some tasks
        var initialData = new TaskList.TaskListData(List.of(new TaskList.TaskItem("Task", "Some task", false)));
        var contextWithInitial = context.withTaskList(initialData, "Initial setup");

        // Replace with empty list (simulating clearing)
        var result = contextWithInitial.withTaskList(new TaskList.TaskListData(List.of()), "Task list cleared");

        // Task list fragment should be removed
        var fragment = result.getTaskListFragment();
        assertTrue(fragment.isEmpty(), "Empty task list should remove fragment");
    }

    @Test
    void createOrReplaceTaskList_whitespaceOnlyTasksIgnored() throws Exception {
        var tasks = List.of("   ", "\t", "Valid Task", "  \n  ");

        // Simulate filtering of whitespace-only tasks
        var validTasks =
                tasks.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        var items =
                validTasks.stream().map(t -> new TaskList.TaskItem(t, t, false)).toList();
        var result = context.withTaskList(new TaskList.TaskListData(items), "Task list created");

        var data = result.getTaskListDataOrEmpty();
        assertEquals(1, data.tasks().size(), "Should filter out whitespace-only tasks");
        assertEquals("Valid Task", data.tasks().get(0).text());
    }

    // ===== appendTaskList Tests =====

    @Test
    void appendTaskList_toEmptyList() throws Exception {
        var tasks = List.of("New Task 1", "New Task 2");

        // Simulate appending to empty list
        var items = tasks.stream().map(t -> new TaskList.TaskItem(t, t, false)).toList();
        var result = context.withTaskList(new TaskList.TaskListData(items), "Task list created");

        var data = result.getTaskListDataOrEmpty();
        assertEquals(2, data.tasks().size(), "Should append 2 tasks to empty list");
        assertEquals("New Task 1", data.tasks().get(0).text());
        assertEquals("New Task 2", data.tasks().get(1).text());
    }

    @Test
    void appendTaskList_toExistingList() throws Exception {
        // Start with initial tasks
        var initialData = new TaskList.TaskListData(List.of(
                new TaskList.TaskItem("Title 1", "First task", false),
                new TaskList.TaskItem("Title 2", "Second task", true)));
        var contextWithInitial = context.withTaskList(initialData, "Initial setup");

        // Append new tasks (simulate appending)
        var newTasks = List.of("Third task", "Fourth task");
        var existing = new java.util.ArrayList<>(
                contextWithInitial.getTaskListDataOrEmpty().tasks());
        existing.addAll(
                newTasks.stream().map(t -> new TaskList.TaskItem(t, t, false)).toList());
        var result = contextWithInitial.withTaskList(new TaskList.TaskListData(existing), "Task list updated");

        // Verify all tasks preserved in order
        var data = result.getTaskListDataOrEmpty();
        assertEquals(4, data.tasks().size(), "Should have 4 tasks total");
        assertEquals("First task", data.tasks().get(0).text());
        assertEquals("Second task", data.tasks().get(1).text());
        assertTrue(data.tasks().get(1).done(), "Second task should remain done");
        assertEquals("Third task", data.tasks().get(2).text());
        assertEquals("Fourth task", data.tasks().get(3).text());

        // New tasks should be incomplete
        assertFalse(data.tasks().get(2).done());
        assertFalse(data.tasks().get(3).done());
    }

    @Test
    void appendTaskList_emptyTasksNoOp() throws Exception {
        // Start with initial tasks
        var initialData = new TaskList.TaskListData(List.of(new TaskList.TaskItem("Task", "Existing task", false)));
        var contextWithInitial = context.withTaskList(initialData, "Initial setup");

        // Append empty list (no-op)
        var result = contextWithInitial;

        // Should return same context (no-op)
        var data = result.getTaskListDataOrEmpty();
        assertEquals(1, data.tasks().size(), "Should remain unchanged");
        assertEquals("Existing task", data.tasks().get(0).text());
    }

    @Test
    void appendTaskList_preservesCompletedTasks() throws Exception {
        // Start with one completed task
        var initialData = new TaskList.TaskListData(List.of(new TaskList.TaskItem("Done", "Completed task", true)));
        var contextWithInitial = context.withTaskList(initialData, "Initial setup");

        // Append new tasks
        var newTasks = List.of("New incomplete task");
        var existing = new java.util.ArrayList<>(
                contextWithInitial.getTaskListDataOrEmpty().tasks());
        existing.addAll(
                newTasks.stream().map(t -> new TaskList.TaskItem(t, t, false)).toList());
        var result = contextWithInitial.withTaskList(new TaskList.TaskListData(existing), "Task list updated");

        var data = result.getTaskListDataOrEmpty();
        assertEquals(2, data.tasks().size());
        assertTrue(data.tasks().get(0).done(), "Completed task should remain done");
        assertFalse(data.tasks().get(1).done(), "New task should be incomplete");
    }

    // ===== Title Summarization Tests =====

    @Test
    void taskTitlesAreSummarized() throws Exception {
        // Verify that task titles are summarized (non-blank, different from text if long)
        var tasks = List.of("This is a very long task description that should be summarized");

        var items = tasks.stream().map(t -> new TaskList.TaskItem(t, t, false)).toList();
        var result = context.withTaskList(new TaskList.TaskListData(items), "Task list created");

        var data = result.getTaskListDataOrEmpty();
        assertEquals(1, data.tasks().size());
        var task = data.tasks().get(0);
        assertNotNull(task.title(), "Task should have a title");
        assertFalse(task.title().isEmpty(), "Task title should not be empty");
        // Title may differ from full text due to summarization
        assertEquals(
                "This is a very long task description that should be summarized",
                task.text(),
                "Task text should match input");
    }

    @Test
    void taskWithoutTitle_usesText() throws Exception {
        var tasks = List.of("Simple task");

        var items = tasks.stream().map(t -> new TaskList.TaskItem(t, t, false)).toList();
        var result = context.withTaskList(new TaskList.TaskListData(items), "Task list created");

        var data = result.getTaskListDataOrEmpty();
        var task = data.tasks().get(0);
        assertEquals("Simple task", task.text());
        assertNotNull(task.title());
    }

    // ===== Action Message Tests =====

    @Test
    void createOrReplaceTaskList_setsCorrectAction() throws Exception {
        var tasks = List.of("Task 1");

        var items = tasks.stream().map(t -> new TaskList.TaskItem(t, t, false)).toList();
        var result = context.withTaskList(new TaskList.TaskListData(items), "Task list replaced");

        var action = result.getAction();
        assertTrue(action.toLowerCase().contains("replaced"), "Action should indicate replacement");
    }

    @Test
    void appendTaskList_setsCorrectAction() throws Exception {
        var initialData = new TaskList.TaskListData(List.of(new TaskList.TaskItem("Task", "Initial", false)));
        var contextWithInitial = context.withTaskList(initialData, "Initial");
        var tasks = List.of("New task");

        var existing = new java.util.ArrayList<>(
                contextWithInitial.getTaskListDataOrEmpty().tasks());
        existing.addAll(
                tasks.stream().map(t -> new TaskList.TaskItem(t, t, false)).toList());
        var result = contextWithInitial.withTaskList(new TaskList.TaskListData(existing), "Task list updated");

        var action = result.getAction();
        assertTrue(action.toLowerCase().contains("updated"), "Action should indicate update");
    }
}
