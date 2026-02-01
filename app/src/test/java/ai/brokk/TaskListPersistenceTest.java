package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.context.Context;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.SpecialTextType;
import ai.brokk.tasks.TaskList;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.util.Json;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Persistence tests for task list create/replace and append operations.
 * Verifies that task lists are correctly serialized, stored, and deserialized.
 *
 * Note: These tests require a full ContextManager instance which depends on project infrastructure.
 * They are marked @Disabled pending test infrastructure setup.
 */
public class TaskListPersistenceTest {

    @Test
    void createOrReplaceTaskList_persistsAndDeserializes() throws Exception {
        var testCm = new TestContextManager(Path.of(".").toAbsolutePath().normalize(), new TestConsoleIO());
        var initial = new Context(testCm);

        var tasks = List.of(
                new TaskList.TaskItem(
                        "Build feature X",
                        "Inst X\n\n**Key Locations:**\nLoc X\n\n**Key Discoveries:**\nDisc X",
                        false),
                new TaskList.TaskItem("Add unit tests", "Inst Y", false));
        var afterCreate = testCm.createOrReplaceTaskList(initial, "Persistence Test", tasks);

        // Verify fragment exists and is JSON
        Optional<ContextFragments.StringFragment> fragOpt = afterCreate.getTaskListFragment();
        assertTrue(fragOpt.isPresent(), "Task list fragment should exist after creation");

        var frag = fragOpt.get();
        assertEquals("Task List", frag.description().join());
        assertEquals(SpecialTextType.TASK_LIST.syntaxStyle(), frag.syntaxStyle().join());

        // Verify JSON can be parsed back
        var data = Json.fromJson(frag.text().join(), TaskList.TaskListData.class);
        assertEquals(2, data.tasks().size());
        assertEquals("Build feature X", data.tasks().get(0).title());
        assertTrue(data.tasks().get(0).text().contains("Inst X"));
        assertTrue(data.tasks().get(0).text().contains("Loc X"));
        assertTrue(data.tasks().get(0).text().contains("Disc X"));

        // All should be incomplete
        for (var task : data.tasks()) {
            assertFalse(task.done());
        }

        // Verify getTaskListDataOrEmpty also deserializes
        var deserialized = afterCreate.getTaskListDataOrEmpty();
        assertEquals(data.tasks(), deserialized.tasks());
    }

    @Test
    void createOrReplaceTaskList_dropsCompletedTasks_persistsCorrectly() throws Exception {
        var testCm = new TestContextManager(Path.of(".").toAbsolutePath().normalize(), new TestConsoleIO());
        var initial = new Context(testCm);

        // Create with mixed states
        var mixed = new TaskList.TaskListData(List.of(
                new TaskList.TaskItem("Done 1", "Completed", true),
                new TaskList.TaskItem("Incomplete", "To do", false),
                new TaskList.TaskItem("Done 2", "Also done", true)));
        var contextWithMixed = initial.withTaskList(mixed);

        // Replace with new tasks
        var newTasks = List.of(
                new TaskList.TaskItem("Fresh task 1", "Inst 1", false),
                new TaskList.TaskItem("Fresh task 2", "Inst 2", false));
        var afterReplace = testCm.createOrReplaceTaskList(contextWithMixed, "New scope", newTasks);

        // Verify replacement in persistent storage
        var frag = afterReplace.getTaskListFragment();
        assertTrue(frag.isPresent());
        var data = Json.fromJson(frag.get().text().join(), TaskList.TaskListData.class);
        assertEquals(2, data.tasks().size());
        assertEquals("Fresh task 1", data.tasks().get(0).title());
        assertEquals("Fresh task 2", data.tasks().get(1).title());

        // Completed tasks should not be in the new list
        for (var task : data.tasks()) {
            assertFalse(task.done());
        }
    }

    @Test
    void createOrReplaceTaskList_persistsBigPicture() throws Exception {
        var testCm = new TestContextManager(Path.of(".").toAbsolutePath().normalize(), new TestConsoleIO());
        var initial = new Context(testCm);

        var bigPicture = "This is the big picture overview of the project goals";
        var tasks = List.of(
                new TaskList.TaskItem("First task", "Inst 1", false),
                new TaskList.TaskItem("Second task", "Inst 2", false));
        var afterCreate = testCm.createOrReplaceTaskList(initial, bigPicture, tasks);

        // Verify fragment exists
        Optional<ContextFragments.StringFragment> fragOpt = afterCreate.getTaskListFragment();
        assertTrue(fragOpt.isPresent(), "Task list fragment should exist after creation");

        // Verify JSON can be parsed back with bigPicture preserved
        var frag = fragOpt.get();
        var data = Json.fromJson(frag.text().join(), TaskList.TaskListData.class);
        assertEquals(bigPicture, data.bigPicture());
        assertEquals(2, data.tasks().size());

        // Verify getTaskListDataOrEmpty also returns bigPicture
        var deserialized = afterCreate.getTaskListDataOrEmpty();
        assertEquals(bigPicture, deserialized.bigPicture());
    }

    @Test
    void taskListFragment_usesCorrectSyntaxStyle() throws Exception {
        var initial =
                new Context(new TestContextManager(Path.of(".").toAbsolutePath().normalize(), new TestConsoleIO()));

        var result = initial.withTaskList(
                new TaskList.TaskListData(List.of(new TaskList.TaskItem("Task 1", "Task 1", false))));

        var frag = result.getTaskListFragment();
        assertTrue(frag.isPresent());
        assertEquals(
                SpecialTextType.TASK_LIST.syntaxStyle(),
                frag.get().syntaxStyle().join());
    }
}
