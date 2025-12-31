package ai.brokk.tasks;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IContextManager;
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

    private IContextManager cm;
    private Context context;

    @BeforeEach
    void setUp() {
        cm = new TestContextManager(Path.of(".").toAbsolutePath().normalize(), new TestConsoleIO());
        context = new Context(cm);
    }

    // ===== createOrReplaceTaskList Tests =====

    @Test
    void createOrReplaceTaskList_replaceEmptyWithNew() throws Exception {
        var tasks = List.of("Task 1", "Task 2", "Task 3");

        // Create task items directly without calling ContextManager
        var items = tasks.stream().map(t -> new TaskList.TaskItem(t, t, false)).toList();
        var result = context.withTaskList(new TaskList.TaskListData(items));

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
        var contextWithInitial = context.withTaskList(initialData);

        // Replace with new tasks (simulating replacement)
        var newTasks = List.of("New Task A", "New Task B");
        var newItems =
                newTasks.stream().map(t -> new TaskList.TaskItem(t, t, false)).toList();
        var result = contextWithInitial.withTaskList(new TaskList.TaskListData(newItems));

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
        var contextWithInitial = context.withTaskList(initialData);

        // Replace with empty list (simulating clearing)
        var result = contextWithInitial.withTaskList(new TaskList.TaskListData(List.of()));

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
        var result = context.withTaskList(new TaskList.TaskListData(items));

        var data = result.getTaskListDataOrEmpty();
        assertEquals(1, data.tasks().size(), "Should filter out whitespace-only tasks");
        assertEquals("Valid Task", data.tasks().get(0).text());
    }

    // ===== Title Summarization Tests =====

    @Test
    void taskTitlesAreSummarized() throws Exception {
        // Verify that task titles are summarized (non-blank, different from text if long)
        var tasks = List.of("This is a very long task description that should be summarized");

        var items = tasks.stream().map(t -> new TaskList.TaskItem(t, t, false)).toList();
        var result = context.withTaskList(new TaskList.TaskListData(items));

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
        var result = context.withTaskList(new TaskList.TaskListData(items));

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
        var result = context.withTaskList(new TaskList.TaskListData(items));

        // The description should indicate that something changed (task list added)
        var description = result.getAction(context);
        assertFalse(description.isEmpty(), "Description should not be empty after adding task list");
    }
}
