package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.SpecialTextType;
import ai.brokk.tasks.TaskList;
import ai.brokk.util.Json;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Verifies that calling Context.withTaskList(...) adds a Task List StringFragment,
 * uses JSON syntax, and that pushing this change via ContextHistory tracks an action
 * containing "Task list".
 *
 * This test avoids ContextManager wiring by using Context and ContextHistory directly.
 */
public class TaskListFragmentPersistenceTest {

    @Test
    void setTaskList_pushesFragmentAndTracksAction() throws Exception {
        // Given: initial empty context (headless IContextManager)
        var cm = new IContextManager() {};
        var initial = new Context(cm, (String) null);

        var data = new TaskList.TaskListData(
                List.of(new TaskList.TaskItem("Do A", false), new TaskList.TaskItem("Do B", true)));

        // When: we apply the Task List via Context API and push into history
        var after = initial.withTaskList(data);

        // Then: Top context should include a Task List StringFragment with JSON content
        Optional<ContextFragment.StringFragment> fragOpt = after.getTaskListFragment();
        assertTrue(fragOpt.isPresent(), "Task List fragment should be present");

        var frag = fragOpt.get();
        assertEquals("Task List", frag.description(), "Task List fragment description should match");
        assertEquals(SpecialTextType.TASK_LIST.syntaxStyle(), frag.syntaxStyle(), "Syntax style should be JSON");

        var expectedJson = Json.getMapper().writeValueAsString(data);
        assertEquals(expectedJson, frag.text(), "Fragment JSON should match serialized TaskListData");

        // And: the action should contain "Task list"
        String action = after.getAction();
        assertTrue(action.toLowerCase().contains("task list"), "Action string should contain 'Task list'");

        // And: Context.getTaskListDataOrEmpty should deserialize the same structure
        var parsed = after.getTaskListDataOrEmpty();
        assertEquals(data.tasks(), parsed.tasks(), "Parsed task list should match the original data");
    }
}
