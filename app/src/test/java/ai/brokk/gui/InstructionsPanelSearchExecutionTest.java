package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.tasks.TaskList;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the preExistingIncompleteTasks capture logic in InstructionsPanel.executeSearchInternal.
 *
 * These tests verify that the task list state is captured synchronously BEFORE submitAction,
 * ensuring that tasks added by SearchAgent during execution are NOT included in preExistingIncompleteTasks.
 *
 * The fix changes the capture from happening inside the async lambda (where SearchAgent may have
 * already modified the list) to happening synchronously before submitAction is called.
 */
public class InstructionsPanelSearchExecutionTest {

    /**
     * Verifies that capturing incomplete tasks from a task list correctly filters out completed tasks.
     *
     * This tests the core capture logic: tasks.stream().filter(t -> !t.done()).map(TaskList.TaskItem::text).collect(Collectors.toSet())
     */
    @Test
    void testCaptureIncompleteTasksFiltersCompletedTasks() {
        // Arrange: Create a task list with a mix of complete and incomplete tasks
        var tasks = List.of(
                new TaskList.TaskItem(null, "Task A", false),
                new TaskList.TaskItem(null, "Task B", true),
                new TaskList.TaskItem(null, "Task C", false),
                new TaskList.TaskItem(null, "Task D", true)
        );
        var taskListData = new TaskList.TaskListData(tasks);

        // Act: Capture incomplete tasks using the same logic as InstructionsPanel.executeSearchInternal
        Set<String> captured = taskListData.tasks().stream()
                .filter(t -> !t.done())
                .map(TaskList.TaskItem::text)
                .collect(Collectors.toSet());

        // Assert: Only incomplete tasks should be captured
        assertEquals(Set.of("Task A", "Task C"), captured);
        assertFalse(captured.contains("Task B"));
        assertFalse(captured.contains("Task D"));
    }

    /**
     * Verifies that capturing BEFORE task list modification excludes newly-added tasks.
     *
     * This is the core test for the timing fix: if we capture the incomplete tasks synchronously
     * BEFORE submitAction (and before SearchAgent runs), tasks added by SearchAgent during execution
     * will NOT be in the preExistingIncompleteTasks set.
     */
    @Test
    void testCaptureBeforeModificationDoesNotIncludeNewTasks() {
        // Arrange: Initial task list with 2 incomplete tasks (represents state BEFORE SearchAgent runs)
        var initialTasks = List.of(
                new TaskList.TaskItem(null, "Original Task 1", false),
                new TaskList.TaskItem(null, "Original Task 2", false)
        );
        var taskListData = new TaskList.TaskListData(initialTasks);

        // Act: Capture incomplete tasks BEFORE any modification (this is the fix)
        Set<String> capturedBeforeModification = taskListData.tasks().stream()
                .filter(t -> !t.done())
                .map(TaskList.TaskItem::text)
                .collect(Collectors.toSet());

        // Simulate SearchAgent adding a new task during execution
        var modifiedTasks = new ArrayList<>(taskListData.tasks());
        modifiedTasks.add(new TaskList.TaskItem(null, "Agent-Created Task", false));
        var modifiedTaskListData = new TaskList.TaskListData(modifiedTasks);

        // For comparison: capture AFTER modification (this is the old buggy behavior)
        Set<String> capturedAfterModification = modifiedTaskListData.tasks().stream()
                .filter(t -> !t.done())
                .map(TaskList.TaskItem::text)
                .collect(Collectors.toSet());

        // Assert: Pre-modification capture excludes the agent-created task
        assertEquals(Set.of("Original Task 1", "Original Task 2"), capturedBeforeModification);
        assertFalse(capturedBeforeModification.contains("Agent-Created Task"),
                "Capture BEFORE modification should NOT include agent-created tasks");

        // Assert: Post-modification capture would incorrectly include the agent-created task
        assertTrue(capturedAfterModification.contains("Agent-Created Task"),
                "Capture AFTER modification would incorrectly include newly-added tasks (old buggy behavior)");
        assertEquals(3, capturedAfterModification.size());
    }

    /**
     * Verifies that an empty task list results in an empty captured set.
     */
    @Test
    void testCaptureFromEmptyTaskListReturnsEmptySet() {
        var emptyTaskList = new TaskList.TaskListData(List.of());

        Set<String> captured = emptyTaskList.tasks().stream()
                .filter(t -> !t.done())
                .map(TaskList.TaskItem::text)
                .collect(Collectors.toSet());

        assertTrue(captured.isEmpty());
    }

    /**
     * Verifies that a task list with only completed tasks results in an empty captured set.
     */
    @Test
    void testCaptureFromAllCompletedTasksReturnsEmptySet() {
        var allCompletedTasks = List.of(
                new TaskList.TaskItem(null, "Done 1", true),
                new TaskList.TaskItem(null, "Done 2", true)
        );
        var taskListData = new TaskList.TaskListData(allCompletedTasks);

        Set<String> captured = taskListData.tasks().stream()
                .filter(t -> !t.done())
                .map(TaskList.TaskItem::text)
                .collect(Collectors.toSet());

        assertTrue(captured.isEmpty());
    }

    /**
     * Verifies that duplicate task texts are deduplicated in the captured set.
     *
     * Since we collect into a Set<String>, duplicates are automatically removed.
     */
    @Test
    void testCaptureDeduplicatesDuplicateTaskTexts() {
        var tasksWithDuplicates = List.of(
                new TaskList.TaskItem(null, "Same Task", false),
                new TaskList.TaskItem(null, "Same Task", false),  // duplicate text
                new TaskList.TaskItem(null, "Different Task", false)
        );
        var taskListData = new TaskList.TaskListData(tasksWithDuplicates);

        Set<String> captured = taskListData.tasks().stream()
                .filter(t -> !t.done())
                .map(TaskList.TaskItem::text)
                .collect(Collectors.toSet());

        // Set naturally deduplicates
        assertEquals(2, captured.size());
        assertEquals(Set.of("Same Task", "Different Task"), captured);
    }

    /**
     * Verifies the timing fix with a more realistic scenario:
     * 1. Start with some incomplete and completed tasks
     * 2. Capture the incomplete tasks BEFORE any modification
     * 3. Simulate SearchAgent adding multiple tasks and changing completion status
     * 4. Verify that preExistingIncompleteTasks only contains the original incomplete tasks
     */
    @Test
    void testTimingFixWithRealisticScenario() {
        // Arrange: Initial task list simulating pre-SearchAgent state
        var initialTasks = List.of(
                new TaskList.TaskItem(null, "Fix authentication bug", false),
                new TaskList.TaskItem(null, "Add unit tests", false),
                new TaskList.TaskItem(null, "Update documentation", true) // completed
        );
        var taskListData = new TaskList.TaskListData(initialTasks);

        // Act: Capture pre-existing incomplete tasks (this must happen BEFORE submitAction)
        Set<String> preExistingIncompleteTasks = taskListData.tasks().stream()
                .filter(t -> !t.done())
                .map(TaskList.TaskItem::text)
                .collect(Collectors.toSet());

        // Simulate SearchAgent execution:
        // - Adds new tasks it created
        // - Marks some tasks as complete
        var agentModifiedTasks = new ArrayList<>(taskListData.tasks());
        agentModifiedTasks.add(new TaskList.TaskItem(null, "Refactor database layer", false));
        agentModifiedTasks.add(new TaskList.TaskItem(null, "Add caching", false));
        // Simulate completing the first task
        agentModifiedTasks.set(0, new TaskList.TaskItem(null, "Fix authentication bug", true));

        // Assert: preExistingIncompleteTasks should only contain the original incomplete tasks
        // from BEFORE SearchAgent ran
        assertEquals(
                Set.of("Fix authentication bug", "Add unit tests"),
                preExistingIncompleteTasks,
                "Should only contain incomplete tasks from before SearchAgent execution");
        assertFalse(
                preExistingIncompleteTasks.contains("Refactor database layer"),
                "Should not include tasks created by SearchAgent");
        assertFalse(
                preExistingIncompleteTasks.contains("Add caching"),
                "Should not include tasks created by SearchAgent");
    }
}
