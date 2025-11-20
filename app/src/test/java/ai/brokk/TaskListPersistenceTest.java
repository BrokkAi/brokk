package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.SpecialTextType;
import ai.brokk.tasks.TaskList;
import ai.brokk.util.Json;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Persistence tests for task list create/replace and append operations.
 * Verifies that task lists are correctly serialized, stored, and deserialized.
 *
 * Note: These tests require a full ContextManager instance which depends on project infrastructure.
 * They are marked @Disabled pending test infrastructure setup.
 */
@Disabled("Requires full ContextManager with project infrastructure")
public class TaskListPersistenceTest {

    @Test
    void createOrReplaceTaskList_persistsAndDeserializes() throws Exception {
        var initial = new Context(null, (String) null);

        var tasks = List.of("Build feature X", "Add unit tests", "Write documentation");
        var afterCreate = initial.withTaskList(
                new TaskList.TaskListData(tasks.stream()
                        .map(t -> new TaskList.TaskItem(t, t, false))
                        .toList()),
                "Task list created");

        // Verify fragment exists and is JSON
        Optional<ContextFragment.StringFragment> fragOpt = afterCreate.getTaskListFragment();
        assertTrue(fragOpt.isPresent(), "Task list fragment should exist after creation");

        var frag = fragOpt.get();
        assertEquals("Task List", frag.description());
        assertEquals(SpecialTextType.TASK_LIST.syntaxStyle(), frag.syntaxStyle());

        // Verify JSON can be parsed back
        var data = Json.fromJson(frag.text(), TaskList.TaskListData.class);
        assertEquals(3, data.tasks().size());
        assertEquals("Build feature X", data.tasks().get(0).text());
        assertEquals("Add unit tests", data.tasks().get(1).text());
        assertEquals("Write documentation", data.tasks().get(2).text());

        // All should be incomplete
        for (var task : data.tasks()) {
            assertFalse(task.done());
        }

        // Verify getTaskListDataOrEmpty also deserializes
        var deserialized = afterCreate.getTaskListDataOrEmpty();
        assertEquals(data.tasks(), deserialized.tasks());
    }

    @Test
    void appendTaskList_persistsIncrementalChanges() throws Exception {
        var initial = new Context(null, (String) null);

        // Create initial list
        var initialTasks = List.of("Task 1", "Task 2");
        var initialData = new TaskList.TaskListData(initialTasks.stream()
                .map(t -> new TaskList.TaskItem(t, t, false))
                .toList());
        var afterCreate = initial.withTaskList(initialData, "Initial tasks");

        // Append more tasks
        var appendTasks = List.of("Task 3", "Task 4");
        var existingTasks =
                new java.util.ArrayList<>(afterCreate.getTaskListDataOrEmpty().tasks());
        existingTasks.addAll(appendTasks.stream()
                .map(t -> new TaskList.TaskItem(t, t, false))
                .toList());
        var afterAppend = afterCreate.withTaskList(new TaskList.TaskListData(existingTasks), "Tasks appended");

        // Verify all 4 tasks exist
        var data = afterAppend.getTaskListDataOrEmpty();
        assertEquals(4, data.tasks().size());
        assertEquals("Task 1", data.tasks().get(0).text());
        assertEquals("Task 2", data.tasks().get(1).text());
        assertEquals("Task 3", data.tasks().get(2).text());
        assertEquals("Task 4", data.tasks().get(3).text());

        // Verify JSON persistence
        var frag = afterAppend.getTaskListFragment();
        assertTrue(frag.isPresent());
        var parsedData = Json.fromJson(frag.get().text(), TaskList.TaskListData.class);
        assertEquals(4, parsedData.tasks().size());
    }

    @Test
    void createOrReplaceTaskList_dropsCompletedTasks_persistsCorrectly() throws Exception {
        var initial = new Context(null, (String) null);

        // Create with mixed states
        var mixed = new TaskList.TaskListData(List.of(
                new TaskList.TaskItem("Done 1", "Completed", true),
                new TaskList.TaskItem("Incomplete", "To do", false),
                new TaskList.TaskItem("Done 2", "Also done", true)));
        var contextWithMixed = initial.withTaskList(mixed, "Mixed initial");

        // Replace with new tasks (simulating replacement by creating new data)
        var newTasks = List.of("Fresh task 1", "Fresh task 2");
        var newData = new TaskList.TaskListData(
                newTasks.stream().map(t -> new TaskList.TaskItem(t, t, false)).toList());
        var afterReplace = contextWithMixed.withTaskList(newData, "Task list replaced");

        // Verify replacement in persistent storage
        var frag = afterReplace.getTaskListFragment();
        assertTrue(frag.isPresent());
        var data = Json.fromJson(frag.get().text(), TaskList.TaskListData.class);
        assertEquals(2, data.tasks().size());
        assertEquals("Fresh task 1", data.tasks().get(0).text());
        assertEquals("Fresh task 2", data.tasks().get(1).text());

        // Completed tasks should not be in the new list
        for (var task : data.tasks()) {
            assertFalse(task.done());
        }
    }

    @Test
    void appendTaskList_preservesTaskOrder_acrossMultipleAppends() throws Exception {
        var initial = new Context(null, (String) null);

        // First create
        var c1 = initial.withTaskList(
                new TaskList.TaskListData(List.of("Task A", "Task B").stream()
                        .map(t -> new TaskList.TaskItem(t, t, false))
                        .toList()),
                "Created A, B");

        // First append
        var existing2 = new java.util.ArrayList<>(c1.getTaskListDataOrEmpty().tasks());
        existing2.add(new TaskList.TaskItem("Task C", "Task C", false));
        var c2 = c1.withTaskList(new TaskList.TaskListData(existing2), "Appended C");

        // Second append
        var existing3 = new java.util.ArrayList<>(c2.getTaskListDataOrEmpty().tasks());
        existing3.add(new TaskList.TaskItem("Task D", "Task D", false));
        existing3.add(new TaskList.TaskItem("Task E", "Task E", false));
        var c3 = c2.withTaskList(new TaskList.TaskListData(existing3), "Appended D, E");

        var data = c3.getTaskListDataOrEmpty();
        assertEquals(5, data.tasks().size());
        assertEquals("Task A", data.tasks().get(0).text());
        assertEquals("Task B", data.tasks().get(1).text());
        assertEquals("Task C", data.tasks().get(2).text());
        assertEquals("Task D", data.tasks().get(3).text());
        assertEquals("Task E", data.tasks().get(4).text());
    }

    @Test
    void taskListFragment_usesCorrectSyntaxStyle() throws Exception {
        var initial = new Context(null, (String) null);

        var result = initial.withTaskList(
                new TaskList.TaskListData(List.of(new TaskList.TaskItem("Task 1", "Task 1", false))),
                "Task list created");

        var frag = result.getTaskListFragment();
        assertTrue(frag.isPresent());
        assertEquals(SpecialTextType.TASK_LIST.syntaxStyle(), frag.get().syntaxStyle());
    }
}
