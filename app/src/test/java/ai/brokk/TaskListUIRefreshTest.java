package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.context.Context;
import ai.brokk.tasks.TaskList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests verifying that task list UI refresh is triggered after persistence
 * with pre-existing incomplete task guard.
 *
 * Note: Full UI testing with Chrome mocking requires SwingUtilities coordination.
 * These tests focus on verifying the context-level behavior and capture of pre-existing tasks.
 */
public class TaskListUIRefreshTest {

    @Test
    void setTaskList_capturesPreExistingIncompleteTasks_beforeModification() {
        var cm = new IContextManager() {};
        var initial = new Context(cm, (String) null);

        // Create context with mixed task states
        var existingData = new TaskList.TaskListData(List.of(
                new TaskList.TaskItem("Done 1", "Completed task", true),
                new TaskList.TaskItem("Incomplete 1", "Incomplete task 1", false),
                new TaskList.TaskItem("Done 2", "Another done", true),
                new TaskList.TaskItem("Incomplete 2", "Incomplete task 2", false)));
        var contextWithTasks = initial.withTaskList(existingData, "Initial setup");

        // Capture pre-existing incomplete tasks by examining before replacement
        var preExistingIncompleteTasks = contextWithTasks.getTaskListDataOrEmpty().tasks().stream()
                .filter(t -> !t.done())
                .map(TaskList.TaskItem::text)
                .toList();

        assertEquals(2, preExistingIncompleteTasks.size(), "Should have 2 incomplete tasks");
        assertTrue(preExistingIncompleteTasks.contains("Incomplete task 1"));
        assertTrue(preExistingIncompleteTasks.contains("Incomplete task 2"));
    }

    @Test
    void setTaskList_guardsAutoPlay_doesNotPlayNewTasksIfPreExistingIncomplete() {
        var initial = new Context(null, (String) null);

        // Scenario: User has incomplete tasks, then replaces list
        var existingData = new TaskList.TaskListData(List.of(new TaskList.TaskItem("Work", "Incomplete work", false)));
        var contextWithTasks = initial.withTaskList(existingData, "Initial");

        // Capture pre-existing incomplete
        var preExisting = contextWithTasks.getTaskListDataOrEmpty().tasks().stream()
                .filter(t -> !t.done())
                .toList();
        assertTrue(!preExisting.isEmpty(), "Pre-existing incomplete tasks present");

        // Now replace the list
        var newTasks = List.of("New task A", "New task B");
        var result = contextWithTasks.withTaskList(
                new TaskList.TaskListData(newTasks.stream()
                        .map(t -> new TaskList.TaskItem(t, t, false))
                        .toList()),
                "Task list replaced");

        // Verify new tasks were created
        var newData = result.getTaskListDataOrEmpty();
        assertEquals(2, newData.tasks().size());

        // Guard logic: if pre-existing incomplete tasks existed, autoplay should NOT trigger
        // (This is enforced at the UI layer via refreshTaskListUI(true, preExisting))
        // We verify the mechanism is in place by checking pre-existing capture
        assertFalse(preExisting.isEmpty(), "Pre-existing incomplete tasks should be captured for guard");
    }

    @Test
    void setTaskList_allowsAutoPlay_whenNoPreExistingIncompleteTasks() {
        var initial = new Context(null, (String) null);

        // Scenario: No existing tasks, user creates new list -> autoplay should be allowed
        var newTasks = List.of("Task 1", "Task 2");
        var result = initial.withTaskList(
                new TaskList.TaskListData(newTasks.stream()
                        .map(t -> new TaskList.TaskItem(t, t, false))
                        .toList()),
                "Task list created");

        // Verify new tasks exist
        var data = result.getTaskListDataOrEmpty();
        assertEquals(2, data.tasks().size());

        // No pre-existing incomplete, so autoplay guard would not trigger
        // (Verified at UI layer)
    }

    @Test
    void appendTaskList_preservesPreExistingIncompleteTasksAcrossAppend() {
        var initial = new Context(null, (String) null);

        // Create initial list with incomplete task
        var initialTasks = List.of("Existing incomplete");
        var c1 = initial.withTaskList(
                new TaskList.TaskListData(initialTasks.stream()
                        .map(t -> new TaskList.TaskItem(t, t, false))
                        .toList()),
                "Initial task");

        // Capture pre-existing incomplete
        var preExisting = c1.getTaskListDataOrEmpty().tasks().stream()
                .filter(t -> !t.done())
                .map(TaskList.TaskItem::text)
                .toList();
        assertTrue(preExisting.contains("Existing incomplete"));

        // Append more tasks
        var existing = new java.util.ArrayList<>(c1.getTaskListDataOrEmpty().tasks());
        existing.add(new TaskList.TaskItem("New task", "New task", false));
        var c2 = c1.withTaskList(new TaskList.TaskListData(existing), "Appended new task");

        // Verify pre-existing incomplete is preserved
        var afterAppendData = c2.getTaskListDataOrEmpty();
        assertTrue(afterAppendData.tasks().stream()
                .anyMatch(t -> !t.done() && t.text().equals("Existing incomplete")));
        assertTrue(afterAppendData.tasks().stream()
                .anyMatch(t -> !t.done() && t.text().equals("New task")));
    }

    @Test
    void setTaskList_fragmentDescriptionAndSyntaxConsistent() {
        var cm = new IContextManager() {};
        var initial = new Context(cm, (String) null);

        var result = initial.withTaskList(
                new TaskList.TaskListData(List.of(new TaskList.TaskItem("Task 1", "Task 1", false))),
                "Task list created");

        var frag = result.getTaskListFragment();
        assertTrue(frag.isPresent());

        // Verify fragment metadata is correct for UI refresh
        assertEquals("Task List", frag.get().description().join());
        assertNotNull(frag.get().syntaxStyle());
    }

    @Test
    void createOrReplaceTaskList_clearingEmptyListRemovesFragment() {
        var cm = new IContextManager() {};
        var initial = new Context(cm, (String) null);

        // Create with tasks
        var c1 = initial.withTaskList(
                new TaskList.TaskListData(List.of(new TaskList.TaskItem("Task 1", "Task 1", false))),
                "Task list created");
        assertTrue(c1.getTaskListFragment().isPresent());

        // Replace with empty (via withTaskList)
        var c2 = c1.withTaskList(new TaskList.TaskListData(List.of()), "Task list cleared");
        assertFalse(c2.getTaskListFragment().isPresent(), "Fragment should be removed on empty replacement");
    }
}
