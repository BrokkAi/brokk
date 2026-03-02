package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.tools.WorkspaceTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class WorkspaceToolsTest {

    @TempDir
    Path tempDir;

    @Test
    void workspaceTools_createOrReplaceTaskList_delegatesToContextManager() {
        var cm = new TestContextManager(tempDir, new TestConsoleIO());
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

    @Test
    void addLineRangeToWorkspace_addsEditableLineRangeFragment() throws Exception {
        var cm = new TestContextManager(tempDir, new TestConsoleIO());
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/range.txt"), "L1\nL2\nL3\nL4\nL5\n");

        var wst = new WorkspaceTools(new Context(cm));
        String result = wst.addLineRangeToWorkspace("src/range.txt", 2, 4);
        assertTrue(result.contains("Added: src/range.txt (lines 2-4)"));

        var ctx = wst.getContext();
        var fragment = ctx.allFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.LINE_RANGE)
                .findFirst()
                .orElseThrow();

        assertTrue(fragment instanceof ContextFragments.LineRangeFragment);
        assertTrue(ctx.getEditableFragments().anyMatch(f -> f.id().equals(fragment.id())));
        assertTrue(fragment.text().join().contains("File: src/range.txt (lines 2-4)"));
        assertTrue(fragment.text().join().contains("2: L2"));
        assertTrue(fragment.text().join().contains("4: L4"));
    }

    @Test
    void addLineRangeToWorkspace_clampsToActualFileEnd() throws Exception {
        var cm = new TestContextManager(tempDir, new TestConsoleIO());
        Files.writeString(tempDir.resolve("clamp.txt"), "1\n2\n3\n");

        var wst = new WorkspaceTools(new Context(cm));
        wst.addLineRangeToWorkspace("clamp.txt", 1, 200);

        var fragment = wst.getContext()
                .allFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.LINE_RANGE)
                .findFirst()
                .orElseThrow();

        String text = fragment.text().join();
        assertTrue(text.contains("File: clamp.txt (lines 1-3)"));
        assertTrue(text.contains("3: 3"));
    }

    @Test
    void addLineRangeToWorkspace_capsRangeAt200Lines() throws Exception {
        var cm = new TestContextManager(tempDir, new TestConsoleIO());
        String content = java.util.stream.IntStream.rangeClosed(1, 250)
                .mapToObj(i -> "L" + i)
                .collect(java.util.stream.Collectors.joining("\n"));
        Files.writeString(tempDir.resolve("cap.txt"), content);

        var wst = new WorkspaceTools(new Context(cm));
        wst.addLineRangeToWorkspace("cap.txt", 1, 500);

        var fragment = wst.getContext()
                .allFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.LINE_RANGE)
                .findFirst()
                .orElseThrow();

        String text = fragment.text().join();
        assertTrue(text.contains("File: cap.txt (lines 1-200)"));
        assertTrue(text.contains("200: L200"));
        assertTrue(!text.contains("201: L201"));
    }

    @Test
    void addLineRangeToWorkspace_returnsAlreadyPresentForDuplicateRange() throws Exception {
        var cm = new TestContextManager(tempDir, new TestConsoleIO());
        Files.writeString(tempDir.resolve("dup.txt"), "A\nB\nC\n");

        var wst = new WorkspaceTools(new Context(cm));
        wst.addLineRangeToWorkspace("dup.txt", 1, 2);
        String second = wst.addLineRangeToWorkspace("dup.txt", 1, 2);

        assertTrue(second.contains("Already present (no-op): dup.txt:1-2"));
        long lineRangeCount = wst.getContext()
                .allFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.LINE_RANGE)
                .count();
        assertEquals(1L, lineRangeCount);
    }

    @Test
    void addLineRangeToWorkspace_rejectsMissingFile() {
        var cm = new TestContextManager(tempDir, new TestConsoleIO());
        var wst = new WorkspaceTools(new Context(cm));

        String result = wst.addLineRangeToWorkspace("missing.txt", 1, 10);
        assertTrue(result.contains("File not found: missing.txt"));
        assertEquals(0L, wst.getContext().allFragments().count());
    }
}
