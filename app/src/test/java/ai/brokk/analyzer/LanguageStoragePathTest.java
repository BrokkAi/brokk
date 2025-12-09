package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import ai.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LanguageStoragePathTest {

    @Test
    void javaLanguageStoragePathDiffersPerProjectRoot(@TempDir Path tempDir) throws Exception {
        Path root1 = tempDir.resolve("main");
        Path root2 = tempDir.resolve("worktree");
        Files.createDirectories(root1);
        Files.createDirectories(root2);

        Language lang = new JavaLanguage();

        TestProject project1 = new TestProject(root1, lang);
        TestProject project2 = new TestProject(root2, lang);

        Path storage1 = lang.getStoragePath(project1).toAbsolutePath().normalize();
        Path storage2 = lang.getStoragePath(project2).toAbsolutePath().normalize();

        assertNotEquals(storage1, storage2, "JavaLanguage storage paths must differ for distinct project roots");
    }

    @Test
    void pythonLanguageStoragePathDiffersPerProjectRoot(@TempDir Path tempDir) throws Exception {
        Path root1 = tempDir.resolve("main");
        Path root2 = tempDir.resolve("worktree");
        Files.createDirectories(root1);
        Files.createDirectories(root2);

        Language lang = new PythonLanguage();

        TestProject project1 = new TestProject(root1, lang);
        TestProject project2 = new TestProject(root2, lang);

        Path storage1 = lang.getStoragePath(project1).toAbsolutePath().normalize();
        Path storage2 = lang.getStoragePath(project2).toAbsolutePath().normalize();

        assertNotEquals(storage1, storage2, "PythonLanguage storage paths must differ for distinct project roots");
    }
}
