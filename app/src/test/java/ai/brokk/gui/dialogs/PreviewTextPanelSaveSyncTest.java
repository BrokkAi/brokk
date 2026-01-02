package ai.brokk.gui.dialogs;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestAnalyzerWrapper;
import ai.brokk.testutil.TestContextManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewTextPanelSaveSyncTest {

    @TempDir
    Path tempDir;

    @Test
    void testSyncAnalyzerAfterWriteInvokesUpdateFiles() throws java.io.IOException {
        var file = new ProjectFile(tempDir, "test.java");
        file.create();

        var wrapper = new TestAnalyzerWrapper();

        PreviewTextPanel.syncAnalyzerAfterWrite(wrapper, file);

        assertTrue(
                wrapper.getUpdateFilesCalls().contains(Set.of(file)),
                "Analyzer updateFiles should have been called for the saved file");
    }

    @Test
    void testBuildPostSaveContextRefreshesStaleFragment() throws Exception {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());
        var file = new ProjectFile(tempDir, "test.txt");
        Files.writeString(file.absPath(), "initial content");

        // 1. Add file to workspace
        cm.addEditableFile(file);

        // 2. Materialize the current fragment snapshot
        var originalFrag = cm.liveContext().fileFragments().findFirst().orElseThrow();
        String originalText = originalFrag.text().join();
        assertEquals("initial content", originalText);

        // 3. Change file on disk
        Files.writeString(file.absPath(), "new content");

        // 4. Build post-save context
        var postSaveCtx = PreviewTextPanel.buildPostSaveContext(cm, file);

        // 5. Assert fragment is refreshed
        var newFrag = postSaveCtx.fileFragments().findFirst().orElseThrow();
        String newText = newFrag.text().join();

        assertNotSame(originalFrag, newFrag, "Fragment instance should have been replaced");
        assertEquals("new content", newText, "Context fragment should reflect new disk content");
    }
}
