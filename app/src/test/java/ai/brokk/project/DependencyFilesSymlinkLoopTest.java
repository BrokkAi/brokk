package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("IProject.Dependency.files() symlink loop handling")
class DependencyFilesSymlinkLoopTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("files() does not throw when dependency contains symlink cycle")
    void filesDoesNotThrowOnSymlinkLoop() throws Exception {
        // Skip on systems that don't support symlinks (e.g., some Windows configurations)
        assumeTrue(supportsSymlinks(), "Filesystem does not support symbolic links");

        // Create dependency directory structure:
        // tempDir/
        //   dependency/
        //     subdir/
        //       file.txt
        //       loop -> subdir  (symlink cycle if followed)
        Path dependencyRoot = tempDir.resolve("dependency");
        Path subdir = dependencyRoot.resolve("subdir");
        Files.createDirectories(subdir);

        Path regularFile = subdir.resolve("file.txt");
        Files.writeString(regularFile, "test content");

        Path symlinkLoop = subdir.resolve("loop");
        Files.createSymbolicLink(symlinkLoop, subdir);

        ProjectFile root = new ProjectFile(tempDir, tempDir.relativize(dependencyRoot));
        var dependency = new IProject.Dependency(root, Languages.NONE);

        Set<ProjectFile> result = assertDoesNotThrow(
                dependency::files, "files() should not throw when dependency contains a symlink cycle");

        assertTrue(
                result.stream().anyMatch(pf -> pf.getFileName().equals("file.txt")), "Result should contain file.txt");
        assertTrue(
                result.stream().noneMatch(pf -> pf.getFileName().equals("loop")),
                "Result should not contain the symlink 'loop'");
    }

    @Test
    @DisplayName("files() returns files normally when no symlink cycle exists")
    void filesReturnsFilesNormally() throws Exception {
        // Create dependency directory structure without symlinks:
        // tempDir/
        //   dependency/
        //     subdir/
        //       file.txt
        Path dependencyRoot = tempDir.resolve("dependency");
        Path subdir = dependencyRoot.resolve("subdir");
        Files.createDirectories(subdir);

        Path regularFile = subdir.resolve("file.txt");
        Files.writeString(regularFile, "test content");

        // Create ProjectFile root pointing at the dependency directory
        ProjectFile root = new ProjectFile(tempDir, tempDir.relativize(dependencyRoot));

        // Construct Dependency and call files()
        var dependency = new IProject.Dependency(root, Languages.NONE);
        Set<ProjectFile> result = dependency.files();

        // Should return the one regular file
        assertEquals(1, result.size(), "files() should return one file");
        ProjectFile found = result.iterator().next();
        assertEquals("file.txt", found.getFileName(), "Should find file.txt");
    }

    private boolean supportsSymlinks() {
        try {
            Path testLink = tempDir.resolve("symlink_test");
            Path testTarget = tempDir.resolve("symlink_target");
            Files.createFile(testTarget);
            Files.createSymbolicLink(testLink, testTarget);
            Files.delete(testLink);
            Files.delete(testTarget);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
