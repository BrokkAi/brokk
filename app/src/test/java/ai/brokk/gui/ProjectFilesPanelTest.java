package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.ProjectFile;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProjectFilesPanelTest {

    // Use temp directory root to ensure cross-platform absolute path
    private static final Path ROOT =
            Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();

    private static ProjectFile pf(String relativePath) {
        return new ProjectFile(ROOT, Path.of(relativePath));
    }

    @Test
    void matchesSearch_filenameSubstring() {
        assertTrue(ProjectFilesPanel.matchesSearch(pf("src/main/Main.java"), "main"));
        assertTrue(ProjectFilesPanel.matchesSearch(pf("src/MainActivity.java"), "activity"));
        assertFalse(ProjectFilesPanel.matchesSearch(pf("src/Helper.java"), "main"));
    }

    @Test
    void matchesSearch_directoryComponent() {
        assertTrue(ProjectFilesPanel.matchesSearch(pf("src/util/Helper.java"), "util"));
        assertTrue(ProjectFilesPanel.matchesSearch(pf("src/main/util/Helper.java"), "util"));
        assertFalse(ProjectFilesPanel.matchesSearch(pf("src/main/Helper.java"), "util"));
    }

    @Test
    void matchesSearch_pathPrefixWithSlash() {
        assertTrue(ProjectFilesPanel.matchesSearch(pf("src/main/Main.java"), "src/main/"));
        assertTrue(ProjectFilesPanel.matchesSearch(pf("src/main/util/Helper.java"), "src/"));
        assertFalse(ProjectFilesPanel.matchesSearch(pf("test/main/Main.java"), "src/"));
    }
}
