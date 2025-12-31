package ai.brokk.context;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestAnalyzerWrapper;
import ai.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class UndoRedoWatcherPauseTest {

    @Test
    public void undoPausesWatcherAndRedoRemainsAvailable() throws Exception {
        Path tmpRoot = Files.createTempDirectory("brokk-test-undo-redo");
        TestProject project = new TestProject(tmpRoot);

        ContextManager cm = new ContextManager(project);

        // Inject FakeAnalyzerWrapper into ContextManager via reflection
        TestAnalyzerWrapper fake = new TestAnalyzerWrapper();
        cm.setAnalyzerWrapper(fake);

        // Create a file in the temp project (not strictly required for pause/resume semantics)
        ProjectFile pf = new ProjectFile(tmpRoot, "sample.txt");
        pf.create();
        pf.write("hello");

        // Work directly on ContextHistory to avoid persisting via SessionManager (which TestProject doesn't implement)
        var ch = cm.getContextHistory();

        // Build two contexts so that undo is possible
        ch.push(ctx -> ctx.withDescription("first change"));
        ch.push(ctx -> ctx.withDescription("second change"));

        // Perform undo while pausing file change notifications
        var undoResult = cm.withFileChangeNotificationsPaused(() -> ch.undo(1, cm.getIo(), project));
        Assertions.assertTrue(undoResult.wasUndone(), "Undo should succeed when there is at least one prior context");

        // Verify pause/resume called exactly once for the undo operation
        Assertions.assertEquals(1, fake.getPauseCount(), "pause() should be called exactly once during undo");
        Assertions.assertEquals(1, fake.getResumeCount(), "resume() should be called exactly once during undo");

        // Perform redo while pausing file change notifications
        var redoResult = cm.withFileChangeNotificationsPaused(() -> ch.redo(cm.getIo(), project));
        Assertions.assertTrue(redoResult.wasRedone(), "Redo should be possible after one undo");

        // Verify pause/resume called exactly once for the redo operation as well (cumulative totals: 2 each)
        Assertions.assertEquals(2, fake.getPauseCount(), "pause() should be called exactly twice across undo and redo");
        Assertions.assertEquals(
                2, fake.getResumeCount(), "resume() should be called exactly twice across undo and redo");
    }
}
