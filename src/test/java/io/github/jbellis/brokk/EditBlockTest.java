package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.agents.CodeAgent;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.git.InMemoryRepo;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import io.github.jbellis.brokk.testutil.NoOpConsoleIO;
import io.github.jbellis.brokk.testutil.TestConsoleIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class EditBlockTest {
    static class TestContextManager implements IContextManager {
        private final Path root;
        private final Set<ProjectFile> validFiles;
        private final IGitRepo repo = new InMemoryRepo();

        public TestContextManager(Path root, Set<String> validFiles) {
            this.root = root;
            this.validFiles = validFiles.stream().map(f -> new ProjectFile(root, Path.of(f))).collect(Collectors.toSet());
        }

        @Override
        public ProjectFile toFile(String relName) {
            return new ProjectFile(root, Path.of(relName));
        }

        @Override
        public Set<ProjectFile> getEditableFiles() {
            return validFiles;
        }

        @Override
        public IGitRepo getRepo() {
            return repo;
        }

        @Override
        public IConsoleIO getIo() {
            return new NoOpConsoleIO();
        }
    }

    @Test
    void testParseEditBlocksSimple() {
        String edit = """
                      Here's the change:
                      
                      ```
                      foo.txt
                      <<<<<<< SEARCH
                      Two
                      =======
                      Tooooo
                      >>>>>>> REPLACE
                      ```
                      
                      Hope you like it!
                      """;

        EditBlock.SearchReplaceBlock[] blocks = parseBlocks(edit, Set.of("foo.txt"));
        assertEquals(1, blocks.length);
        assertEquals("foo.txt", blocks[0].filename().toString());
        assertEquals("Two\n", blocks[0].beforeText());
        assertEquals("Tooooo\n", blocks[0].afterText());
    }

    @Test
    void testParseEditBlocksBackwardsFilename() {
        // should be able to find the filename even when it's misplaced outside the blocks
        String edit = """
                      Here's the change:
                      
                      foo.txt
                      ```
                      <<<<<<< SEARCH
                      Two
                      =======
                      Tooooo
                      >>>>>>> REPLACE
                      ```
                      
                      Hope you like it!
                      """;

        EditBlock.SearchReplaceBlock[] blocks = parseBlocks(edit, Set.of("foo.txt"));
        assertEquals(1, blocks.length);
        assertEquals("foo.txt", blocks[0].filename().toString());
        assertEquals("Two\n", blocks[0].beforeText());
        assertEquals("Tooooo\n", blocks[0].afterText());
    }

    @Test
    void testParseEditBlocksMultipleSameFile() {
        String edit = """
                      Here's the change:
                      
                      ```
                      foo.txt
                      <<<<<<< SEARCH
                      one
                      =======
                      two
                      >>>>>>> REPLACE
                      ```
                      
                      ```
                      foo.txt
                      <<<<<<< SEARCH
                      three
                      =======
                      four
                      >>>>>>> REPLACE
                      ```
                      
                      Hope you like it!
                      """;

        EditBlock.SearchReplaceBlock[] blocks = parseBlocks(edit, Set.of("foo.txt"));
        assertEquals(2, blocks.length);
        // first block
        assertEquals("foo.txt", blocks[0].filename().toString());
        assertEquals("one\n", blocks[0].beforeText());
        assertEquals("two\n", blocks[0].afterText());
        // second block
        assertEquals("foo.txt", blocks[1].filename().toString());
        assertEquals("three\n", blocks[1].beforeText());
        assertEquals("four\n", blocks[1].afterText());
    }

    @Test
    void testParseEditBlocksNoFinalNewline() {
        String edit = """
                      ```
                      foo/coder.py
                      <<<<<<< SEARCH
                      lineA
                      =======
                      lineB
                      >>>>>>> REPLACE
                      ```
                      
                      ```
                      foo/coder.py
                      <<<<<<< SEARCH
                      lineC
                      =======
                      lineD
                      >>>>>>> REPLACE
                      ```"""; // no final newline

        EditBlock.SearchReplaceBlock[] blocks = parseBlocks(edit, Set.of("foo/coder.py"));
        assertEquals(2, blocks.length);
        assertEquals("lineA\n", blocks[0].beforeText());
        assertEquals("lineB\n", blocks[0].afterText());
        assertEquals("lineC\n", blocks[1].beforeText());
        assertEquals("lineD\n", blocks[1].afterText());
    }

    @Test
    void testParseEditBlocksNewFileThenExisting() {
        String edit = """
                      Here's the change:
                      
                      ```
                      filename/to/a/file2.txt    
                      <<<<<<< SEARCH
                      =======
                      three
                      >>>>>>> REPLACE
                      ```
                      
                      another change
                      
                      ```
                      filename/to/a/file1.txt    
                      <<<<<<< SEARCH
                      one
                      =======
                      two
                      >>>>>>> REPLACE
                      ```
                      
                      Hope you like it!
                      """;

        EditBlock.SearchReplaceBlock[] blocks = parseBlocks(edit, Set.of("filename/to/a/file1.txt"));
        assertEquals(2, blocks.length);
        assertEquals("filename/to/a/file2.txt", blocks[0].filename());
        assertEquals("", blocks[0].beforeText().trim());
        assertEquals("three", blocks[0].afterText().trim());
        assertEquals("filename/to/a/file1.txt", blocks[1].filename());
        assertEquals("one", blocks[1].beforeText().trim());
        assertEquals("two", blocks[1].afterText().trim());
    }

    @Test
    void testApplyEditsCreatesNewFile(@TempDir Path tempDir) throws IOException, EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        TestConsoleIO io = new TestConsoleIO();
        Path existingFile = tempDir.resolve("fileA.txt");
        Files.writeString(existingFile, "Original text\n");

        String response = """
                          ```
                          fileA.txt
                          <<<<<<< SEARCH
                          Original text
                          =======
                          Updated text
                          >>>>>>> REPLACE
                          ```
                          
                          ```
                          newFile.txt
                          <<<<<<< SEARCH
                          =======
                          Created content
                          >>>>>>> REPLACE
                          ```
                          """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlockParser.instance.parseEditBlocks(response, ctx.getEditableFiles()).blocks();
        var ca = new CodeAgent(ctx, new Service.UnavailableStreamingModel());
        ca.preCreateNewFiles(blocks);
        EditBlock.applyEditBlocks(ctx, io, blocks);

        // existing filename
        String actualA = Files.readString(existingFile);
        assertTrue(actualA.contains("Updated"));

        // new filename
        Path newFile = tempDir.resolve("newFile.txt");
        String newFileText = Files.readString(newFile);
        assertEquals("Created content\n", newFileText);

        // no errors
        assertTrue(io.getErrorLog().isEmpty(), "No error expected");
    }

    @Test
    void testApplyEditsFailsForUnknownFile(@TempDir Path tempDir) throws IOException, EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        TestConsoleIO io = new TestConsoleIO();

        Path existingFile = tempDir.resolve("fileA.txt");
        Files.writeString(existingFile, "Line X\n");

        String response = """
                          ```
                          unknownFile.txt
                          <<<<<<< SEARCH
                          replacement
                          =======
                          replacement
                          >>>>>>> REPLACE
                          """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlockParser.instance.parseEditBlocks(response, ctx.getEditableFiles()).blocks();
        var result = EditBlock.applyEditBlocks(ctx, io, blocks);

        assertNotEquals(List.of(), result.failedBlocks());
    }

    /**
     * Check that we can parse an unclosed block and fail gracefully.
     * (Similar to python test_find_original_update_blocks_unclosed)
     */
    @Test
    void testParseEditBlocksUnclosed(@TempDir Path tempDir) {
        String edit = """
                      Here's the change:
                      
                      ```
                      foo.txt
                      <<<<<<< SEARCH
                      Two
                      =======
                      Tooooo
                      
                      oops! no trailing >>>>>> REPLACE
                      ```
                      """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("foo.txt"));
        var result = EditBlockParser.instance.parseEditBlocks(edit, ctx.getEditableFiles());
        assertNotEquals(null, result.parseError());
    }

    /**
     * Check that we parse blocks that have no filename at all (which is a parse error if we
     * haven't yet established a 'currentFile').
     */
    @Test
    void testParseEditBlocksMissingFilename(@TempDir Path tempDir) {
        String edit = """
                      Here's the change:
                      
                      ```
                      <<<<<<< SEARCH
                      Two
                      =======
                      Tooooo
                      >>>>>>> REPLACE
                      ```
                      """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of());
        var result = EditBlockParser.instance.parseEditBlocks(edit, ctx.getEditableFiles());
        assertEquals(1, result.blocks().size());
        assertNull(result.blocks().getFirst().filename());
    }

    /**
     * Test detection of a possible "mangled" or fuzzy filename match.
     */
    @Test
    void testResolveFilenameIgnoreCase(@TempDir Path tempDir) throws EditBlock.SymbolAmbiguousException, EditBlock.SymbolNotFoundException {
        TestContextManager ctx = new TestContextManager(tempDir, Set.of("foo.txt"));
        var f = EditBlock.resolveProjectFile(ctx, "fOo.TXt");
        assertEquals("foo.txt", f.getFileName());
    }

    @Test
    void testNoMatchFailure(@TempDir Path tempDir) throws IOException, EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        TestConsoleIO io = new TestConsoleIO();
        Path existingFile = tempDir.resolve("fileA.txt");
        Files.writeString(existingFile, "AAA\nBBB\nCCC\n");

        // The "beforeText" is too different from anything in the file
        String response = """
                          ```
                          fileA.txt
                          <<<<<<< SEARCH
                          replacement
                          =======
                          replacement
                          >>>>>>> REPLACE
                          ```
                          """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlockParser.instance.parseEditBlocks(response, ctx.getEditableFiles()).blocks();
        var result = EditBlock.applyEditBlocks(ctx, io, blocks);

        // Assert exactly one failure with the correct reason
        assertEquals(1, result.failedBlocks().size(), "Expected exactly one failed block");
        assertEquals(EditBlock.EditBlockFailureReason.NO_MATCH, result.failedBlocks().getFirst().reason(), "Expected failure reason to be NO_MATCH");

        // Assert that the file content remains unchanged after the failed edit
        String finalContent = Files.readString(existingFile);
        assertEquals("AAA\nBBB\nCCC\n", finalContent, "File content should remain unchanged after a NO_MATCH failure.");
    }

    @Test
    void testEditResultContainsOriginalContents(@TempDir Path tempDir) throws IOException, EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        TestConsoleIO io = new TestConsoleIO();
        Path existingFile = tempDir.resolve("fileA.txt");
        String originalContent = "Original text\n";
        Files.writeString(existingFile, originalContent);

        String response = """
                          ```
                          fileA.txt
                          <<<<<<< SEARCH
                          Original text
                          =======
                          Updated text
                          >>>>>>> REPLACE
                          ```
                          """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlockParser.instance.parseEditBlocks(response, ctx.getEditableFiles()).blocks();
        var result = EditBlock.applyEditBlocks(ctx, io, blocks);

        // Verify original content is returned
        var fileA = new ProjectFile(tempDir, Path.of("fileA.txt"));
        assertEquals(originalContent, result.originalContents().get(fileA));

        // Verify file was actually changed
        String actualContent = Files.readString(existingFile);
        assertEquals("Updated text\n", actualContent);
    }

    @Test
    void testApplyEditsEmptySearchReplacesFile(@TempDir Path tempDir) throws IOException, EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        TestConsoleIO io = new TestConsoleIO();
        Path testFile = tempDir.resolve("replaceTest.txt");
        String originalContent = "Initial content.\n";
        Files.writeString(testFile, originalContent);

        String replacementContent = "Replacement text.\n";
        String response = """
                          ```
                          replaceTest.txt
                          <<<<<<< SEARCH
                          =======
                          %s
                          >>>>>>> REPLACE
                          ```
                          """.formatted(replacementContent.trim()); // Use trim because EditBlock adds newline

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("replaceTest.txt"));
        var blocks = EditBlockParser.instance.parseEditBlocks(response, ctx.getEditableFiles()).blocks();
        assertEquals(1, blocks.size());
        assertTrue(blocks.getFirst().beforeText().isEmpty()); // Verify search block is empty

        var result = EditBlock.applyEditBlocks(ctx, io, blocks);

        // Verify the file content is now *only* the replacement text
        String actualContent = Files.readString(testFile);
        assertEquals(replacementContent, actualContent); // Expected content is exactly the replacement

        // Verify no failures
        assertTrue(result.failedBlocks().isEmpty(), "No failures expected");
        assertTrue(io.getErrorLog().isEmpty(), "No IO errors expected");
    }

    /**
     * These tests illustrate how the new "forgiving" parse logic works when we
     * don't see a "filename =======" divider between SEARCH and REPLACE, but do
     * see a standalone "=======" line. If exactly one such line is found,
     * we use it as the divider; otherwise it's a non-fatal parse error.
     */
    @Test
    void testForgivingDividerSingleMatch(@TempDir Path tempDir) {
        String content = """
                         ```
                         foo.txt
                         <<<<<<< SEARCH
                         old line
                         =======
                         new line
                         >>>>>>> REPLACE
                         ```
                         """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("foo.txt"));
        var result = EditBlockParser.instance.parseEditBlocks(content, ctx.getEditableFiles());
        // Expect exactly one successfully parsed block, no parse errors
        assertEquals(1, result.blocks().size(), "Should parse a single block");
        assertNull(result.parseError(), "No parse errors expected");

        var block = result.blocks().getFirst();
        assertEquals("foo.txt", block.filename());
        assertEquals("old line\n", block.beforeText());
        assertEquals("new line\n", block.afterText());
    }

    @Test
    void testmissingDivider(@TempDir Path tempDir) {
        String content = """
                         ```
                         foo.txt
                         <<<<<<< SEARCH
                         line A
                         line B
                         >>>>>>> REPLACE
                         ```
                         """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("foo.txt"));
        var result = EditBlockParser.instance.parseEditBlocks(content, ctx.getEditableFiles());
        assertEquals(0, result.blocks().size(), "No successful blocks expected without any divider line");
        assertNotNull(result.parseError(), "Should report parse error on zero matches");
    }

    @Test
    void testNoMatchFailureWithExistingReplacementText(@TempDir Path tempDir) throws IOException, EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        TestConsoleIO io = new TestConsoleIO();
        Path existingFile = tempDir.resolve("fileA.txt");
        String initialContent = "AAA\nBBB\nCCC\n";
        String alreadyPresentText = "Replacement Text"; // This text is NOT in initialContent yet, but we want it there
        String fileContent = initialContent + alreadyPresentText + "\nDDD\n";
        Files.writeString(existingFile, fileContent);

        // The "beforeText" will not match, but the "afterText" is already in the file
        String response = """
                          ```
                          fileA.txt
                          <<<<<<< SEARCH
                          NonExistentBeforeText
                          =======
                          %s
                          >>>>>>> REPLACE
                          ```
                          """.formatted(alreadyPresentText);

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlockParser.instance.parseEditBlocks(response, ctx.getEditableFiles()).blocks();
        var result = EditBlock.applyEditBlocks(ctx, io, blocks);

        // Assert exactly one failure with NO_MATCH reason
        assertEquals(1, result.failedBlocks().size(), "Expected exactly one failed block");
        var failedBlock = result.failedBlocks().getFirst();
        assertEquals(EditBlock.EditBlockFailureReason.NO_MATCH, failedBlock.reason(), "Expected failure reason to be NO_MATCH");

        // Assert the specific commentary is present
        assertTrue(failedBlock.commentary().contains("replacement text is already present"), "Expected specific commentary about existing replacement text");

        // Assert that the file content remains unchanged
        String finalContent = Files.readString(existingFile);
        assertEquals(fileContent, finalContent, "File content should remain unchanged after the failed edit");
    }

    @Test
    void testNoMatchFailureWithDiffLikeSearchText(@TempDir Path tempDir) throws IOException, EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        TestConsoleIO io = new TestConsoleIO();
        Path existingFile = tempDir.resolve("fileB.txt");
        String initialContent = "Line 1\nLine 2\nLine 3\n";
        Files.writeString(existingFile, initialContent);

        // The "beforeText" will not match, and contains diff-like lines
        String response = """
                          ```
                          fileB.txt
                          <<<<<<< SEARCH
                          -Line 2
                          +Line Two
                          =======
                          Replacement Text
                          >>>>>>> REPLACE
                          ```
                          """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileB.txt"));
        var blocks = EditBlockParser.instance.parseEditBlocks(response, ctx.getEditableFiles()).blocks();
        var result = EditBlock.applyEditBlocks(ctx, io, blocks);

        // Assert exactly one failure with NO_MATCH reason
        assertEquals(1, result.failedBlocks().size(), "Expected exactly one failed block");
        var failedBlock = result.failedBlocks().getFirst();
        assertEquals(EditBlock.EditBlockFailureReason.NO_MATCH, failedBlock.reason(), "Expected failure reason to be NO_MATCH");

        // Assert the specific commentary about diff format is present
        assertTrue(failedBlock.commentary().contains("not unified diff format"),
                   "Expected specific commentary about diff-like search text");

        // Assert that the file content remains unchanged
        String finalContent = Files.readString(existingFile);
        assertEquals(initialContent, finalContent, "File content should remain unchanged after the failed edit");
    }

    @Test
    void testExtractCodeWithEmbeddedBackticks() {
        String textWithEmbeddedBackticks = """
                                           Some intro.
                                           ```java
                                           public class Test {
                                               String s = "```"; // Embedded backticks
                                               /*
                                                * Another ``` example
                                                */
                                           }
                                           ```
                                           Some outro.
                                           """;
        // Expected code includes content between "```java\n" and the next "\n```" (if present) or "```"
        // The content itself ends with a newline if the closing ``` is on its own line.
        // This will have a trailing newline from the text block if not careful. Let's be precise.
        String expectedCodePrecise = "public class Test {\n" +
                                     "    String s = \"```\"; // Embedded backticks\n" +
                                     "    /*\n" +
                                     "     * Another ``` example\n" +
                                     "     */\n" +
                                     "}\n";

        String actualCode = EditBlock.extractCodeFromTripleBackticks(textWithEmbeddedBackticks);
        assertEquals(expectedCodePrecise, actualCode);
    }

    @Test
    void testExtractCodeWithEmbeddedBackticksAndMultipleBlocks() {
        // This test verifies the behavior when multiple blocks are present and the first has embedded backticks.
        // The greedy (.*) will cause it to capture content until the *last* ``` in the input string
        // if `extractCodeFromTripleBackticks` is fed the whole string.
        String textWithMultipleBlocks = """
                                        ```java
                                        public class Test1 {
                                            String s = "```"; // Embedded
                                        }
                                        ```
                                        Some intermediate text.
                                        ```python
                                        print("Hello")
                                        ```
                                        """;
        // This will have a trailing newline
        String expectedMergedCodePrecise = "public class Test1 {\n" +
                                           "    String s = \"```\"; // Embedded\n" +
                                           "}\n" +
                                           "```\n" +
                                           "Some intermediate text.\n" +
                                           "```python\n" +
                                           "print(\"Hello\")\n";

        String actualCode = EditBlock.extractCodeFromTripleBackticks(textWithMultipleBlocks);
        assertEquals(expectedMergedCodePrecise, actualCode,
                     "Greedy regex (.*) is expected to merge content if multiple blocks are present in the input string.");
    }

    // ----------------------------------------------------
    // Helper methods
    // ----------------------------------------------------
    private EditBlock.SearchReplaceBlock[] parseBlocks(String fullResponse, Set<String> validFilenames) {
        var files = validFilenames.stream().map(f -> new ProjectFile(Path.of("/"), Path.of(f))).collect(Collectors.toSet());
        var blocks = EditBlockParser.instance.parseEditBlocks(fullResponse, files).blocks();
        return blocks.toArray(new EditBlock.SearchReplaceBlock[0]);
    }
}
