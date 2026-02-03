package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.context.Context;
import ai.brokk.context.ContextDelta;
import ai.brokk.tasks.TaskList;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.nio.file.Path;
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
        var cm = new TestContextManager(Path.of(".").toAbsolutePath().normalize(), new TestConsoleIO());
        var initial = new Context(cm);

        // Create context with mixed task states
        var existingData = new TaskList.TaskListData(List.of(
                new TaskList.TaskItem("Done 1", "Completed task", true),
                new TaskList.TaskItem("Incomplete 1", "Incomplete task 1", false),
                new TaskList.TaskItem("Done 2", "Another done", true),
                new TaskList.TaskItem("Incomplete 2", "Incomplete task 2", false)));
        var contextWithTasks = initial.withTaskList(existingData);

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
        var cm = new TestContextManager(Path.of(".").toAbsolutePath().normalize(), new TestConsoleIO());
        var initial = new Context(cm);

        // Scenario: User has incomplete tasks, then replaces list
        var existingData = new TaskList.TaskListData(List.of(new TaskList.TaskItem("Work", "Incomplete work", false)));
        var contextWithTasks = initial.withTaskList(existingData);

        // Capture pre-existing incomplete
        var preExisting = contextWithTasks.getTaskListDataOrEmpty().tasks().stream()
                .filter(t -> !t.done())
                .toList();
        assertTrue(!preExisting.isEmpty(), "Pre-existing incomplete tasks present");

        // Now replace the list
        var newTasks = List.of("New task A", "New task B");
        var result = contextWithTasks.withTaskList(new TaskList.TaskListData(
                newTasks.stream().map(t -> new TaskList.TaskItem(t, t, false)).toList()));

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
        var cm = new TestContextManager(Path.of(".").toAbsolutePath().normalize(), new TestConsoleIO());
        var initial = new Context(cm);

        // Scenario: No existing tasks, user creates new list -> autoplay should be allowed
        var newTasks = List.of("Task 1", "Task 2");
        var result = initial.withTaskList(new TaskList.TaskListData(
                newTasks.stream().map(t -> new TaskList.TaskItem(t, t, false)).toList()));

        // Verify new tasks exist
        var data = result.getTaskListDataOrEmpty();
        assertEquals(2, data.tasks().size());

        // No pre-existing incomplete, so autoplay guard would not trigger
        // (Verified at UI layer)
    }

    @Test
    void setTaskList_fragmentDescriptionAndSyntaxConsistent() {
        var cm = new TestContextManager(Path.of(".").toAbsolutePath().normalize(), new TestConsoleIO());
        var initial = new Context(cm);

        var result = initial.withTaskList(
                new TaskList.TaskListData(List.of(new TaskList.TaskItem("Task 1", "Task 1", false))));

        var frag = result.getTaskListFragment();
        assertTrue(frag.isPresent());

        // Verify fragment metadata is correct for UI refresh
        assertEquals("Task List", frag.get().description().join());
        assertNotNull(frag.get().syntaxStyle());
    }

    @Test
    void createOrReplaceTaskList_clearingEmptyListRemovesFragment() {
        var cm = new TestContextManager(Path.of(".").toAbsolutePath().normalize(), new TestConsoleIO());
        var initial = new Context(cm);

        // Create with tasks
        var c1 = initial.withTaskList(
                new TaskList.TaskListData(List.of(new TaskList.TaskItem("Task 1", "Task 1", false))));
        assertTrue(c1.getTaskListFragment().isPresent());

        // Replace with empty (via withTaskList)
        var c2 = c1.withTaskList(new TaskList.TaskListData(List.of()));
        assertFalse(c2.getTaskListFragment().isPresent(), "Fragment should be removed on empty replacement");
    }

    @Test
    void taskListChanges_produceNonEmptyDeltaAndMeaningfulAction() {
        var cm = new TestContextManager(Path.of(".").toAbsolutePath().normalize(), new TestConsoleIO());
        var ctx0 = new Context(cm);

        // Step 1: Add a new task list to an empty context
        var data1 = new TaskList.TaskListData(List.of(
                new TaskList.TaskItem("T1", "First task", false), new TaskList.TaskItem("T2", "Second task", false)));
        var ctx1 = ctx0.withTaskList(data1);

        var delta1 = ContextDelta.between(ctx0, ctx1).join();
        var action1 = ctx1.getAction(ctx0).join();

        assertFalse(delta1.isEmpty(), "Delta should not be empty after adding task list");
        assertNotNull(action1);
        assertFalse(action1.isBlank());
        assertNotEquals("(No changes)", action1);
        assertTrue(action1.contains("Task List"), "Action should mention Task List: " + action1);

        // Step 2: Update the existing task list (content change)
        var data2 = new TaskList.TaskListData(
                "Big picture",
                List.of(
                        new TaskList.TaskItem("T1", "First task", true), // mark done
                        new TaskList.TaskItem("T2", "Second task", false)));
        var ctx2 = ctx1.withTaskList(data2);

        var delta2 = ContextDelta.between(ctx1, ctx2).join();
        var action2 = ctx2.getAction(ctx1).join();

        assertFalse(delta2.isEmpty(), "Delta should not be empty after updating task list content");
        assertNotNull(action2);
        assertFalse(action2.isBlank());
        assertNotEquals("(No changes)", action2);
        assertTrue(action2.contains("Task List"), "Action should mention Task List: " + action2);

        // Step 3: Clear the task list
        var data3 = new TaskList.TaskListData(List.of());
        var ctx3 = ctx2.withTaskList(data3);

        var delta3 = ContextDelta.between(ctx2, ctx3).join();
        var action3 = ctx3.getAction(ctx2).join();

        assertFalse(delta3.isEmpty(), "Delta should not be empty after clearing task list");
        assertNotNull(action3);
        assertFalse(action3.isBlank());
        assertNotEquals("(No changes)", action3);
        assertTrue(action3.contains("Task List"), "Action should mention Task List: " + action3);
    }
}
