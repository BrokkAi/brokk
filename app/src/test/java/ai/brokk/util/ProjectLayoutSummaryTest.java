package ai.brokk.util;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectLayoutSummaryTest {

    @TempDir
    Path tempDir;

    @Test
    void testDeterminism() throws IOException {
        setupMockProject();
        TestProject project = new TestProject(tempDir);
        ProjectLayoutSummary summary = new ProjectLayoutSummary(project);

        var result1 = summary.render();
        var result2 = summary.render();

        assertEquals(result1.markdown(), result2.markdown());
        assertEquals(result1.fingerprint(), result2.fingerprint());
        assertTrue(result1.markdown().contains("## Project Layout"));
        assertTrue(result1.markdown().contains("### Directory Tree"));
    }

    @Test
    void testBoundingLines() throws IOException {
        setupMockProject();
        TestProject project = new TestProject(tempDir);
        ProjectLayoutSummary summary = new ProjectLayoutSummary(project);

        System.setProperty("brokk.layout.maxLines", "5");
        try {
            var result = summary.render();
            long lineCount = result.markdown().lines().count();
            // maxLines (5) + truncation note (2) = 7
            assertTrue(lineCount <= 10, "Line count was " + lineCount);
            assertTrue(result.markdown().contains("[Note: truncated to fit size limits]"));
        } finally {
            System.clearProperty("brokk.layout.maxLines");
        }
    }

    @Test
    void testBoundingChars() throws IOException {
        setupMockProject();
        TestProject project = new TestProject(tempDir);
        ProjectLayoutSummary summary = new ProjectLayoutSummary(project);

        System.setProperty("brokk.layout.maxChars", "50");
        try {
            var result = summary.render();
            assertTrue(result.markdown().length() < 150); 
            assertTrue(result.markdown().contains("[Note: truncated to fit size limits]"));
        } finally {
            System.clearProperty("brokk.layout.maxChars");
        }
    }

    private void setupMockProject() throws IOException {
        Files.createDirectories(tempDir.resolve("app/src/main/java/ai/brokk/util"));
        Files.writeString(tempDir.resolve("app/src/main/java/ai/brokk/util/Foo.java"), "package ai.brokk.util; public class Foo {}");
        Files.writeString(tempDir.resolve("app/src/main/java/ai/brokk/util/Bar.java"), "package ai.brokk.util; public class Bar {}");
        Files.createDirectories(tempDir.resolve("docs"));
        Files.writeString(tempDir.resolve("docs/readme.md"), "# Readme");
        Files.writeString(tempDir.resolve("build.gradle"), "// gradle");
    }
}
