package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IContextManager;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextFragmentsTest {
    @TempDir
    Path tempDir;

    private IContextManager mockContextManager;

    @BeforeEach
    void setup() throws IOException {
        // Mock project components
        var project = new TestProject(tempDir, Languages.JAVA);

        // Use empty analyzer as these tests focus on file extraction from text
        var testAnalyzer = new TestAnalyzer(Collections.emptyList(), Collections.emptyMap(), project);
        mockContextManager = new TestContextManager(tempDir, new NoOpConsoleIO(), testAnalyzer);
    }

    @Test
    void testStringFragmentExtractsFilesFromPathList() throws Exception {
        var file1 = new ProjectFile(tempDir, "src/PathListFile1.java");
        var file2 = new ProjectFile(tempDir, "src/PathListFile2.java");
        Files.createDirectories(file1.absPath().getParent());
        Files.writeString(file1.absPath(), "class PathListFile1 {}");
        Files.writeString(file2.absPath(), "class PathListFile2 {}");

        String pathList = file1 + "\n" + file2 + "\n";

        var fragment = new ContextFragments.StringFragment(
                mockContextManager, pathList, "File list", SyntaxConstants.SYNTAX_STYLE_NONE);

        assertEquals(Set.of(file1, file2), fragment.referencedFiles().join());
    }

    @Test
    void testPathListExtractionSkipsNonExistentFiles() throws Exception {
        var existingFile = new ProjectFile(tempDir, "src/ExistingFile.java");
        Files.createDirectories(existingFile.absPath().getParent());
        Files.writeString(existingFile.absPath(), "class ExistingFile {}");

        String pathList = existingFile + "\nsrc/NonExistentFile.java\n";

        var fragment = new ContextFragments.StringFragment(
                mockContextManager, pathList, "Mixed file list", SyntaxConstants.SYNTAX_STYLE_NONE);

        assertEquals(Set.of(existingFile), fragment.referencedFiles().join());
    }

    @Test
    void testMixedPastedListCollectsOnlyValidPathsForStringAndPasteFragments() throws Exception {
        var file1 = new ProjectFile(tempDir, "src/MixedValid1.java");
        var file2 = new ProjectFile(tempDir, "src/MixedValid2.java");
        Files.createDirectories(file1.absPath().getParent());
        Files.writeString(file1.absPath(), "class MixedValid1 {}");
        Files.writeString(file2.absPath(), "class MixedValid2 {}");

        String mixed = "# comment\n" + file1 + "\nnot/a/real/file.txt\n    " + file2 + "\ngarbage line\n";

        var stringFragment = new ContextFragments.StringFragment(
                mockContextManager, mixed, "Mixed content", SyntaxConstants.SYNTAX_STYLE_NONE);
        assertEquals(Set.of(file1, file2), stringFragment.referencedFiles().join());

        var pasteFragment = new ContextFragments.PasteTextFragment(
                mockContextManager,
                mixed,
                CompletableFuture.completedFuture("Mixed content"),
                CompletableFuture.completedFuture(SyntaxConstants.SYNTAX_STYLE_NONE));
        assertEquals(Set.of(file1, file2), pasteFragment.referencedFiles().join());
    }

    @Test
    void testDiffTakesPrecedenceOverPathList() throws Exception {
        var projectFile = new ProjectFile(tempDir, "src/DiffPrecedence.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "class DiffPrecedence {}");

        var decoyFile = new ProjectFile(tempDir, "src/Decoy.java");
        Files.writeString(decoyFile.absPath(), "class Decoy {}");

        String diffText =
                """
                diff --git a/src/DiffPrecedence.java b/src/DiffPrecedence.java
                --- a/src/DiffPrecedence.java
                +++ b/src/DiffPrecedence.java
                @@ -1 +1 @@
                -class DiffPrecedence {}
                +class DiffPrecedence { }

                See src/Decoy.java
                """;

        var fragment = new ContextFragments.StringFragment(
                mockContextManager, diffText, "Diff content", SyntaxConstants.SYNTAX_STYLE_NONE);

        var files = fragment.referencedFiles().join();
        assertTrue(files.contains(projectFile), "Should contain the file from the diff");
        assertFalse(
                files.contains(decoyFile),
                "Should NOT contain the decoy file (diff parser should return early ignoring trailing text)");
    }

    @Test
    void testPathsMayOccurAnywhere() throws Exception {
        var file = new ProjectFile(tempDir, "src/TestFile.java");
        Files.createDirectories(file.absPath().getParent());
        Files.writeString(file.absPath(), "class TestFile {}");

        String mixedFormats = file + ":10: error: cannot find symbol\n"
                + file + ":25:    public void method() {\n"
                + "    at com.example.Test(" + file + ":42)\n"
                + "https://example.com/docs/api.html\n";

        var fragment = new ContextFragments.StringFragment(
                mockContextManager, mixedFormats, "Mixed formats", SyntaxConstants.SYNTAX_STYLE_NONE);

        assertEquals(Set.of(file), fragment.referencedFiles().join());
    }

    @Test
    void testPathsWithSpacesAreExtracted() throws Exception {
        var fileWithSpace = new ProjectFile(tempDir, "src/My Class.java");
        var normalFile = new ProjectFile(tempDir, "src/NormalFile.java");
        Files.createDirectories(fileWithSpace.absPath().getParent());
        Files.writeString(fileWithSpace.absPath(), "class MyClass {}");
        Files.writeString(normalFile.absPath(), "class NormalFile {}");

        String pathList = fileWithSpace + "\n" + normalFile + "\n";

        var fragment = new ContextFragments.StringFragment(
                mockContextManager, pathList, "Path list with spaces", SyntaxConstants.SYNTAX_STYLE_NONE);

        assertEquals(
                Set.of(fileWithSpace, normalFile), fragment.referencedFiles().join());
    }

    @Test
    void testPasteTextFragmentExtractsFilesOnFutureTimeout() throws Exception {
        var file = new ProjectFile(tempDir, "src/TimeoutTest.java");
        Files.createDirectories(file.absPath().getParent());
        Files.writeString(file.absPath(), "class TimeoutTest {}");

        var failingDescFuture = new CompletableFuture<String>();
        failingDescFuture.completeExceptionally(new RuntimeException("Simulated LLM timeout"));

        var failingSyntaxFuture = new CompletableFuture<String>();
        failingSyntaxFuture.completeExceptionally(new RuntimeException("Simulated LLM failure"));

        var fragment = new ContextFragments.PasteTextFragment(
                mockContextManager, file.toString(), failingDescFuture, failingSyntaxFuture);

        assertEquals(Set.of(file), fragment.referencedFiles().join());
        assertEquals("Paste of text content", fragment.description().join());
    }

    @Test
    void testStringFragmentHandlesUnifiedDiffParserException() {
        String malformedDiff =
                """
                --- a/src/Malformed.java
                +++ b/src/Malformed.java
                @@ invalid @@
                """;

        var fragment = new ContextFragments.StringFragment(
                mockContextManager, malformedDiff, "Malformed Diff", SyntaxConstants.SYNTAX_STYLE_NONE);
        assertTrue(fragment.referencedFiles().join().isEmpty(), "Files list should be empty when diff parsing fails");
    }
}
