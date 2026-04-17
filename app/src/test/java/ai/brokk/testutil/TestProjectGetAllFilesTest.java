package ai.brokk.testutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.Languages;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestProjectGetAllFilesTest {

    @Test
    void bareSystemTempRoot_throws() {
        var project = new TestProject(Path.of(System.getProperty("java.io.tmpdir")), Languages.NONE);
        IllegalStateException ex = assertThrows(IllegalStateException.class, project::getAllFiles);
        assertTrue(ex.getMessage().contains("@TempDir"));
    }

    @Test
    void bareSystemTempRoot_withAllFiles_doesNotThrow() {
        var project =
                new TestProject(Path.of(System.getProperty("java.io.tmpdir")), Languages.NONE).withAllFiles(Set.of());
        assertTrue(project.getAllFiles().isEmpty());
    }

    @Test
    void getAllFiles_walksDedicatedTempRoot(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "x");
        var project = new TestProject(tempDir, Languages.NONE);
        assertEquals(1, project.getAllFiles().size());
    }
}
