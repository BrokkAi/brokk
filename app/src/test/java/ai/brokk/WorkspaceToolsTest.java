package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.context.Context;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.tools.WorkspaceTools;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

public class WorkspaceToolsTest {

    @Test
    void workspaceTools_createOrReplaceTaskList_delegatesToContextManager() {
        var cm = new TestContextManager(Paths.get(".").toAbsolutePath().normalize(), new TestConsoleIO());
        var context = new Context(cm);
        var wst = new WorkspaceTools(context);

        var explanation = "Building feature X requires these steps";
        var tasks = List.of(
                new WorkspaceTools.TaskListEntry("Task 1", "Step 1: Design API", "Accept 1", "loc1", "disc1"),
                new WorkspaceTools.TaskListEntry("Task 2", "Step 2: Implement", "Accept 2", "loc2", "disc2"),
                new WorkspaceTools.TaskListEntry("Task 3", "Step 3: Test", "Accept 3", "loc3", "disc3"));

        var result = wst.createOrReplaceTaskList(explanation, tasks);

        assertTrue(result.contains("Task 1"));
        assertTrue(result.contains("Task 2"));
        assertTrue(result.contains("Task 3"));

        var updatedContext = wst.getContext();
        var data = updatedContext.getTaskListDataOrEmpty();
        assertEquals(3, data.tasks().size());
    }
}
