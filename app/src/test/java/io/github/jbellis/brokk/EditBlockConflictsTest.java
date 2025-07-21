package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.git.InMemoryRepo;
import io.github.jbellis.brokk.prompts.EditBlockConflictsParser;
import io.github.jbellis.brokk.testutil.TestConsoleIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class EditBlockConflictsTest {
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
    }

    @Test
    void testParseEditBlocksSimple(@TempDir Path tempDir) {
        String edit = """
                Here's the change:

                <<<<<<< SEARCH foo.txt
                Two
                ======= foo.txt
                Tooooo
                >>>>>>> REPLACE foo.txt

                Hope you like it!
                """;

        EditBlock.SearchReplaceBlock[] blocks = parseBlocks(tempDir, edit, Set.of("foo.txt"));
        assertEquals(1, blocks.length);
        assertEquals("foo.txt", blocks[0].filename().toString());
        assertEquals("Two\n", blocks[0].beforeText());
        assertEquals("Tooooo\n", blocks[0].afterText());
    }

    @Test
    void testParseEditBlocksMultipleSameFile(@TempDir Path tempDir) {
        String edit = """
                Here's the change:

                <<<<<<< SEARCH foo.txt
                one
                ======= foo.txt
                two
                >>>>>>> REPLACE foo.txt

                <<<<<<< SEARCH foo.txt
                three
                ======= foo.txt
                four
                >>>>>>> REPLACE foo.txt

                Hope you like it!
                """;

        EditBlock.SearchReplaceBlock[] blocks = parseBlocks(tempDir, edit, Set.of("foo.txt"));
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
    void testParseEditBlocksNoFinalNewline(@TempDir Path tempDir) {
        String edit = """
                <<<<<<< SEARCH foo/coder.py
                lineA
                ======= foo/coder.py
                lineB
                >>>>>>> REPLACE foo/coder.py

                <<<<<<< SEARCH foo/coder.py
                lineC
                ======= foo/coder.py
                lineD
                >>>>>>> REPLACE foo/coder.py"""; // no newline at the end

        EditBlock.SearchReplaceBlock[] blocks = parseBlocks(tempDir, edit, Set.of("foo/coder.py"));
        assertEquals(2, blocks.length);
        assertEquals("lineA\n", blocks[0].beforeText());
        assertEquals("lineB\n", blocks[0].afterText());
        assertEquals("lineC\n", blocks[1].beforeText());
        assertEquals("lineD\n", blocks[1].afterText());
    }

    @Test
    void testParseEditBlocksNewFileThenExisting(@TempDir Path tempDir) {
        String edit = """
                Here's the change:

                <<<<<<< SEARCH filename/to/a/file2.txt
                ======= filename/to/a/file2.txt
                three
                >>>>>>> REPLACE filename/to/a/file2.txt

                another change

                <<<<<<< SEARCH filename/to/a/file1.txt
                one
                ======= filename/to/a/file1.txt
                two
                >>>>>>> REPLACE filename/to/a/file1.txt

                Hope you like it!
                """;

        EditBlock.SearchReplaceBlock[] blocks = parseBlocks(tempDir, edit, Set.of("filename/to/a/file1.txt"));
        assertEquals(2, blocks.length);
        assertEquals("filename/to/a/file2.txt", blocks[0].filename());
        assertEquals("", blocks[0].beforeText().trim());
        assertEquals("three", blocks[0].afterText().trim());
        assertEquals("filename/to/a/file1.txt", blocks[1].filename());
        assertEquals("one", blocks[1].beforeText().trim());
        assertEquals("two", blocks[1].afterText().trim());
    }
    
    @Test
    void testApplyEditsFailsForUnknownFile(@TempDir Path tempDir) throws IOException, EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        TestConsoleIO io = new TestConsoleIO();

        Path existingFile = tempDir.resolve("fileA.txt");
        Files.writeString(existingFile, "Line X\n");

        String response = """
                <<<<<<< SEARCH unknownFile.txt
                replacement
                ======= unknownFile.txt
                replacement
                >>>>>>> REPLACE unknownFile.txt
                """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlockConflictsParser.instance.parseEditBlocks(response, ctx.getEditableFiles()).blocks();
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

                <<<<<<< SEARCH foo.txt
                Two
                ======= foo.txt
                Tooooo

                oops! no trailing >>>>>> REPLACE foo.txt
                """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("foo.txt"));
        var result = EditBlockConflictsParser.instance.parseEditBlocks(edit, ctx.getEditableFiles());
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

                 <<<<<<< SEARCH
                Two
                 =======
                Tooooo
                 >>>>>>> REPLACE
                """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of());
        var result = EditBlockConflictsParser.instance.parseEditBlocks(edit, ctx.getEditableFiles());
        // It should fail parsing because the filename is missing
        assertEquals(0, result.blocks().size());
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
            <<<<<<< SEARCH fileA.txt
            replacement
            ======= fileA.txt
            replacement
            >>>>>>> REPLACE fileA.txt
            """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlockConflictsParser.instance.parseEditBlocks(response, ctx.getEditableFiles()).blocks();
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
        <<<<<<< SEARCH fileA.txt
        Original text
        ======= fileA.txt
        Updated text
        >>>>>>> REPLACE fileA.txt
        """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlockConflictsParser.instance.parseEditBlocks(response, ctx.getEditableFiles()).blocks();
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
        <<<<<<< SEARCH replaceTest.txt
        ======= replaceTest.txt
        %s
        >>>>>>> REPLACE replaceTest.txt
        """.formatted(replacementContent.trim()); // Use trim because EditBlock adds newline

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("replaceTest.txt"));
        var blocks = EditBlockConflictsParser.instance.parseEditBlocks(response, ctx.getEditableFiles()).blocks();
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
        <<<<<<< SEARCH foo.txt
        old line
        =======
        new line
        >>>>>>> REPLACE foo.txt
        """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("foo.txt"));
        var result = EditBlockConflictsParser.instance.parseEditBlocks(content, ctx.getEditableFiles());
        // Expect exactly one successfully parsed block, no parse errors
        assertEquals(1, result.blocks().size(), "Should parse a single block");
        assertNull(result.parseError(), "No parse errors expected");

        var block = result.blocks().getFirst();
        assertEquals("foo.txt", block.filename());
        assertEquals("old line\n", block.beforeText());
        assertEquals("new line\n", block.afterText());
    }

    @Test
    void testForgivingDividerMultipleMatches(@TempDir Path tempDir) {
        String content = """
            <<<<<<< SEARCH foo.txt
            line A
            =======
            line B
            =======
            line C
            >>>>>>> REPLACE foo.txt
            """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("foo.txt"));
        var result = EditBlockConflictsParser.instance.parseEditBlocks(content, ctx.getEditableFiles());
        // Because we found more than one standalone "=======" line in the SEARCH->REPLACE block,
        // the parser should treat it as an error for that block, producing zero blocks.
        assertEquals(0, result.blocks().size(), "No successful blocks expected when multiple dividers found");
        assertNotNull(result.parseError(), "Should report parse error on multiple matches");
    }

    @Test
    void testRejectMultipleDividersWithFilenames(@TempDir Path tempDir) {
        // believe it or not I saw GP2.5 do this in the wild
        String content = """
            <<<<<<< SEARCH foo.txt
            line A
            ======= foo.txt
            line B
            ======= foo.txt
            line C
            >>>>>>> REPLACE foo.txt
            """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("foo.txt"));
        var result = EditBlockConflictsParser.instance.parseEditBlocks(content, ctx.getEditableFiles());
        assertEquals(0, result.blocks().size(), "Should reject blocks with multiple named dividers");
        assertNotNull(result.parseError(), "Should report parse error on multiple named dividers");
    }

    @Test
    void testForgivingDividerZeroMatches(@TempDir Path tempDir) {
        String content = """
        <<<<<<< SEARCH foo.txt
        line A
        line B
        >>>>>>> REPLACE foo.txt
        """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("foo.txt"));
        var result = EditBlockConflictsParser.instance.parseEditBlocks(content, ctx.getEditableFiles());
        // Because there was no "foo.txt =======" nor a single standalone "=======" line,
        // this block also fails to parse, yielding zero blocks and a parse error.
        assertEquals(0, result.blocks().size(), "No successful blocks expected without any divider line");
        assertNotNull(result.parseError(), "Should report parse error on zero matches");
    }

    /**
     * Demonstrates that a malformed block can coexist with a well-formed one:
     * the malformed block is skipped (recorded as an error) but the good block is parsed.
     */
    @Test
    void testForgivingDividerPartialFailure(@TempDir Path tempDir) {
        String content = """
        <<<<<<< SEARCH foo.txt
        line A
        >>>>>>> REPLACE foo.txt

        <<<<<<< SEARCH bar.txt
        some
        ======= bar.txt
        other
        >>>>>>> REPLACE bar.txt
        """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("foo.txt"));
        var result = EditBlockConflictsParser.instance.parseEditBlocks(content, ctx.getEditableFiles());
        // Expect 1 block to parse OK (the bar.txt block),
        // and 1 parse error from the foo.txt block that has no divider.
        assertEquals(1, result.blocks().size(), "One successfully parsed block");
        assertNotNull(result.parseError(), "Expect parse error for the malformed block");

        var goodBlock = result.blocks().getFirst();
        assertEquals("bar.txt", goodBlock.filename());
        assertEquals("some\n", goodBlock.beforeText());
        assertEquals("other\n", goodBlock.afterText());
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
            <<<<<<< SEARCH fileA.txt
            NonExistentBeforeText
            ======= fileA.txt
            %s
            >>>>>>> REPLACE fileA.txt
            """.formatted(alreadyPresentText);

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlockConflictsParser.instance.parseEditBlocks(response, ctx.getEditableFiles()).blocks();
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
                <<<<<<< SEARCH fileB.txt
                -Line 2
                +Line Two
                ======= fileB.txt
                Replacement Text
                >>>>>>> REPLACE fileB.txt
                """;

            TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileB.txt"));
            var blocks = EditBlockConflictsParser.instance.parseEditBlocks(response, ctx.getEditableFiles()).blocks();
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

<<<<<<< HEAD
    // ----------------------------------------------------
    // Helper methods
    // ----------------------------------------------------
    private EditBlock.SearchReplaceBlock[] parseBlocks(Path root, String fullResponse, Set<String> validFilenames) {
=======
        // ----------------------------------------------------
        // Helper methods
        // ----------------------------------------------------
    private EditBlock.SearchReplaceBlock[] parseBlocks(String fullResponse, Set<String> validFilenames) {
        var root = FileSystems.getDefault().getRootDirectories().iterator().next();
>>>>>>> f12962d1 (Fixes for Unix-Specific Expectations on Tests (#431))
        var files = validFilenames.stream().map(f -> new ProjectFile(root, Path.of(f))).collect(Collectors.toSet());
        var blocks = EditBlockConflictsParser.instance.parseEditBlocks(fullResponse, files).blocks();
        return blocks.toArray(new EditBlock.SearchReplaceBlock[0]);
    }
}
