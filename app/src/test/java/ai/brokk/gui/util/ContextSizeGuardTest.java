package ai.brokk.gui.util;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextSizeGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void estimateTokens_multipleFiles() throws IOException {
        var file1 = tempDir.resolve("a.txt");
        var file2 = tempDir.resolve("b.txt");
        Files.writeString(file1, "x".repeat(400));
        Files.writeString(file2, "y".repeat(800));

        var pf1 = new ProjectFile(tempDir, tempDir.relativize(file1));
        var pf2 = new ProjectFile(tempDir, tempDir.relativize(file2));
        var estimate = ContextSizeGuard.estimateTokens(List.of(pf1, pf2));

        assertEquals(2, estimate.fileCount());
        assertEquals(300, estimate.estimatedTokens()); // (400 + 800) / 4
        assertFalse(estimate.isTruncated());
    }

    @Test
    void estimateTokens_nestedDirectories() throws IOException {
        var level1 = tempDir.resolve("level1");
        var level2 = level1.resolve("level2");
        Files.createDirectories(level2);
        Files.writeString(level1.resolve("a.txt"), "x".repeat(400));
        Files.writeString(level2.resolve("b.txt"), "y".repeat(400));

        var pf = new ProjectFile(tempDir, tempDir.relativize(level1));
        var estimate = ContextSizeGuard.estimateTokens(Set.of(pf));

        assertEquals(2, estimate.fileCount());
        assertEquals(200, estimate.estimatedTokens());
    }
}
