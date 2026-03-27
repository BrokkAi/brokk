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

        assertTrue(result.llmText().contains("Task 1"));
        assertTrue(result.llmText().contains("Task 2"));
        assertTrue(result.llmText().contains("Task 3"));

        var updatedContext = result.context();
        var data = updatedContext.getTaskListDataOrEmpty();
        assertEquals(3, data.tasks().size());
    }

    @Test
    void addLineRangeToWorkspace_addsEditableLineRangeFragment() throws Exception {
        var cm = new TestContextManager(tempDir, new TestConsoleIO());
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/range.txt"), "L1\nL2\nL3\nL4\nL5\n");

        String filePath = Path.of("src", "range.txt").toString();

        var wst = new WorkspaceTools(new Context(cm));
        var result = wst.addLineRangeToWorkspace(filePath, 2, 4);
        assertTrue(result.llmText().contains("Added: %s (lines 2-4)".formatted(filePath)));
        assertEquals(1, result.addedFragments().size());

        var ctx = result.context();
        var fragment = ctx.allFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.LINE_RANGE)
                .findFirst()
                .orElseThrow();

        assertTrue(fragment instanceof ContextFragments.LineRangeFragment);
        assertTrue(ctx.getEditableFragments().anyMatch(f -> f.id().equals(fragment.id())));
        assertTrue(fragment.text().join().contains("File: %s (lines 2-4)".formatted(filePath)));
        assertTrue(fragment.text().join().contains("2: L2"));
        assertTrue(fragment.text().join().contains("4: L4"));
    }

    @Test
    void addLineRangeToWorkspace_clampsToActualFileEnd() throws Exception {
        var cm = new TestContextManager(tempDir, new TestConsoleIO());
        String filePath = Path.of("clamp.txt").toString();
        Files.writeString(tempDir.resolve(filePath), "1\n2\n3\n");

        var wst = new WorkspaceTools(new Context(cm));
        var result = wst.addLineRangeToWorkspace(filePath, 1, 200);
        var fragment = result.context()
                .allFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.LINE_RANGE)
                .findFirst()
                .orElseThrow();

        String text = fragment.text().join();
        assertTrue(text.contains("File: %s (lines 1-3)".formatted(filePath)));
        assertTrue(text.contains("3: 3"));
    }

    @Test
    void addLineRangeToWorkspace_capsRangeAt200Lines() throws Exception {
        var cm = new TestContextManager(tempDir, new TestConsoleIO());
        String filePath = Path.of("cap.txt").toString();
        String content = java.util.stream.IntStream.rangeClosed(1, 250)
                .mapToObj(i -> "L" + i)
                .collect(java.util.stream.Collectors.joining("\n"));
        Files.writeString(tempDir.resolve(filePath), content);

        var wst = new WorkspaceTools(new Context(cm));
        var result = wst.addLineRangeToWorkspace(filePath, 1, 500);
        var fragment = result.context()
                .allFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.LINE_RANGE)
                .findFirst()
                .orElseThrow();

        String text = fragment.text().join();
        assertTrue(text.contains("File: %s (lines 1-200)".formatted(filePath)));
        assertTrue(text.contains("200: L200"));
        assertTrue(!text.contains("201: L201"));
    }

    @Test
    void addLineRangeToWorkspace_returnsAlreadyPresentForDuplicateRange() throws Exception {
        var cm = new TestContextManager(tempDir, new TestConsoleIO());
        String filePath = Path.of("dup.txt").toString();
        Files.writeString(tempDir.resolve("dup.txt"), "A\nB\nC\n");

        var wst = new WorkspaceTools(new Context(cm));
        wst.addLineRangeToWorkspace(filePath, 1, 2);
        var second = wst.addLineRangeToWorkspace(filePath, 1, 2);

        assertTrue(second.llmText().contains("Already present (no-op): %s:1-2".formatted(filePath)));
        long lineRangeCount = second.context()
                .allFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.LINE_RANGE)
                .count();
        assertEquals(1L, lineRangeCount);
    }

    @Test
    void addLineRangeToWorkspace_rejectsMissingFile() {
        var cm = new TestContextManager(tempDir, new TestConsoleIO());
        var wst = new WorkspaceTools(new Context(cm));

        var result = wst.addLineRangeToWorkspace("missing.txt", 1, 10);
        assertTrue(result.llmText().contains("File not found: missing.txt"));
        assertEquals(0L, result.context().allFragments().count());
    }

    @Test
    void dropWorkspaceFragments_reportsRemovedFragmentsAndPreservesReport() throws Exception {
        var cm = new TestContextManager(tempDir, new TestConsoleIO());
        var ctx = new Context(cm);

        Files.writeString(tempDir.resolve("to-drop.txt"), "content");
        var file = cm.toFile("to-drop.txt");
        var fragment = new ContextFragments.ProjectPathFragment(file, cm);
        ctx = ctx.addFragments(fragment);

        var wst = new WorkspaceTools(ctx);
        var removal = new WorkspaceTools.FragmentRemoval(fragment.id(), "facts", "reason");
        var result = wst.dropWorkspaceFragments(List.of(removal));

        assertEquals(1, result.removedFragments().size());
        assertEquals(fragment.id(), result.removedFragments().getFirst().id());
        assertTrue(result.dropReport().droppedFragmentIds().contains(fragment.id()));
        assertTrue(result.context().findWithSameSource(fragment).isEmpty());
    }

    @Test
    void runShellCommand_executesAndReturnsOutput() {
        var cm = new TestContextManager(tempDir, new TestConsoleIO());
        var wst = new WorkspaceTools(new Context(cm));

        // Use a platform-independent simple command
        String cmd = Environment.isWindows() ? "echo hello" : "echo hello";
        var result = wst.runShellCommand(cmd);

        assertTrue(result.contains("Command succeeded"));
        assertTrue(result.contains("hello"));
    }

    @Test
    void addUrlContentsToWorkspace_invalidUrl_returnsTypedErrorOutput() {
        var cm = new TestContextManager(tempDir, new TestConsoleIO());
        var wst = new WorkspaceTools(new Context(cm));

        var result = wst.addUrlContentsToWorkspace("not-a-url");
        assertTrue(result.llmText().contains("Invalid URL format"));
        assertEquals(0L, result.context().allFragments().count());
    }
}
