package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for FileSystemWalker utility that walks filesystem trees and collects files.
 */
class FileSystemWalkerTest {

    @Test
    void testEmptyDirectory(@TempDir Path tempDir) {
        var files = FileSystemWalker.walk(tempDir, Set.of());

        assertEquals(0, files.size(), "Empty directory should return no files");
    }

    @Test
    void testNestedDirectories(@TempDir Path tempDir) throws IOException {
        Path subDir = tempDir.resolve("sub");
        Path deepDir = subDir.resolve("deep");
        Files.createDirectories(deepDir);

        Files.writeString(tempDir.resolve("root.txt"), "root");
        Files.writeString(subDir.resolve("sub.txt"), "sub");
        Files.writeString(deepDir.resolve("deep.txt"), "deep");

        var files = FileSystemWalker.walk(tempDir, Set.of());

        assertEquals(3, files.size(), "Should find files at all levels");
        assertTrue(files.stream().anyMatch(f -> f.getRelPath().equals(Path.of("root.txt"))));
        assertTrue(files.stream().anyMatch(f -> f.getRelPath().equals(Path.of("sub/sub.txt"))));
        assertTrue(files.stream().anyMatch(f -> f.getRelPath().equals(Path.of("sub/deep/deep.txt"))));
    }

    @Test
    void testSkipMultipleDirectories(@TempDir Path tempDir) throws IOException {
        Path gitDir = tempDir.resolve(".git");
        Path brokkDir = tempDir.resolve(".brokk");
        Files.createDirectories(gitDir);
        Files.createDirectories(brokkDir);

        Files.writeString(tempDir.resolve("visible.txt"), "visible");
        Files.writeString(gitDir.resolve("git-hidden.txt"), "git-hidden");
        Files.writeString(brokkDir.resolve("brokk-hidden.txt"), "brokk-hidden");

        var files = FileSystemWalker.walk(tempDir, Set.of(".git", ".brokk"));

        assertEquals(1, files.size(), "Should skip both .git and .brokk directories");
        assertTrue(files.stream().anyMatch(f -> f.getRelPath().equals(Path.of("visible.txt"))));
        assertFalse(files.stream().anyMatch(f -> f.getRelPath().toString().contains(".git")));
        assertFalse(files.stream().anyMatch(f -> f.getRelPath().toString().contains(".brokk")));
    }

    @Test
    void testProjectFileProperties(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("test.java"), "public class Test {}");

        var files = FileSystemWalker.walk(tempDir, Set.of());

        assertEquals(1, files.size());
        ProjectFile file = files.iterator().next();

        // Verify ProjectFile properties
        assertEquals(tempDir.toAbsolutePath().normalize(), file.getRoot());
        assertEquals(Path.of("test.java"), file.getRelPath());
        assertEquals("java", file.extension());
    }

    @Test
    void testMixedFilesAndDirectories(@TempDir Path tempDir) throws IOException {
        // Create a realistic project structure
        Path srcDir = tempDir.resolve("src");
        Path testDir = tempDir.resolve("test");
        Path gitDir = tempDir.resolve(".git");
        Path brokkDir = tempDir.resolve(".brokk");

        Files.createDirectories(srcDir);
        Files.createDirectories(testDir);
        Files.createDirectories(gitDir);
        Files.createDirectories(brokkDir);

        Files.writeString(tempDir.resolve("README.md"), "readme");
        Files.writeString(srcDir.resolve("Main.java"), "main");
        Files.writeString(testDir.resolve("MainTest.java"), "test");
        Files.writeString(gitDir.resolve("config"), "git-config");
        Files.writeString(brokkDir.resolve("cache.db"), "cache");

        var files = FileSystemWalker.walk(tempDir, Set.of(".git", ".brokk"));

        assertEquals(3, files.size(), "Should find source files but skip .git and .brokk");
        assertTrue(files.stream().anyMatch(f -> f.getRelPath().equals(Path.of("README.md"))));
        assertTrue(files.stream().anyMatch(f -> f.getRelPath().equals(Path.of("src/Main.java"))));
        assertTrue(files.stream().anyMatch(f -> f.getRelPath().equals(Path.of("test/MainTest.java"))));
        assertFalse(files.stream().anyMatch(f -> f.getRelPath().toString().contains(".git")));
        assertFalse(files.stream().anyMatch(f -> f.getRelPath().toString().contains(".brokk")));
    }
}
