package ai.brokk.gui.dialogs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestAnalyzerWrapper;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewTextPanelSaveSyncTest {

    @TempDir
    Path tempDir;

    @Test
    void testSyncAnalyzerAfterWriteInvokesUpdateFiles() {
        var file = new ProjectFile(tempDir, "test.java");
        file.create();

        var wrapper = new TestAnalyzerWrapper();

        PreviewTextPanel.syncAnalyzerAfterWrite(wrapper, file);

        assertTrue(
                wrapper.getUpdateFilesCalls().contains(Set.of(file)),
                "Analyzer updateFiles should have been called for the saved file");
    }
}
