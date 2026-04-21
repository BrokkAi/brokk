package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.ProjectFile;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectFilesPanelTest {

    private static ProjectFile pf(Path root, String relativePath) {
        return new ProjectFile(root, Path.of(relativePath));
    }

    @Test
    void matchesSearch_filenameSubstring(@TempDir Path tempDir) {
        assertTrue(ProjectFilesPanel.matchesSearch(pf(tempDir, "src/main/Main.java"), "main"));
        assertTrue(ProjectFilesPanel.matchesSearch(pf(tempDir, "src/MainActivity.java"), "activity"));
        assertFalse(ProjectFilesPanel.matchesSearch(pf(tempDir, "src/Helper.java"), "main"));
    }

    @Test
    void matchesSearch_directoryComponent(@TempDir Path tempDir) {
        assertTrue(ProjectFilesPanel.matchesSearch(pf(tempDir, "src/util/Helper.java"), "util"));
        assertTrue(ProjectFilesPanel.matchesSearch(pf(tempDir, "src/main/util/Helper.java"), "util"));
        assertFalse(ProjectFilesPanel.matchesSearch(pf(tempDir, "src/main/Helper.java"), "util"));
    }

    @Test
    void matchesSearch_pathPrefixWithSlash(@TempDir Path tempDir) {
        assertTrue(ProjectFilesPanel.matchesSearch(pf(tempDir, "src/main/Main.java"), "src/main/"));
        assertTrue(ProjectFilesPanel.matchesSearch(pf(tempDir, "src/main/util/Helper.java"), "src/"));
        assertFalse(ProjectFilesPanel.matchesSearch(pf(tempDir, "test/main/Main.java"), "src/"));
    }
}
