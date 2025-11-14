package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.update.UpdateTestUtil;
import ai.brokk.prompts.EditBlockParser;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditBlockTest {
    @Test
    void testParseEditBlocksSimple() {
        String edit =
                """
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
        assertEquals("foo.txt", blocks[0].rawFileName().toString());
        assertEquals("Two\n", blocks[0].beforeText());
        assertEquals("Tooooo\n", blocks[0].afterText());
    }

    @Test
    void testParseEditBlocksBackwardsFilename() {
        // should be able to find the filename even when it's misplaced outside the blocks
        String edit =
                """
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
        assertEquals("foo.txt", blocks[0].rawFileName().toString());
        assertEquals("Two\n", blocks[0].beforeText());
        assertEquals("Tooooo\n", blocks[0].afterText());
    }

    @Test
    void testParseEditBlocksMultipleSameFile() {
        String edit =
                """
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
        assertEquals("foo.txt", blocks[0].rawFileName().toString());
        assertEquals("one\n", blocks[0].beforeText());
        assertEquals("two\n", blocks[0].afterText());
        // second block
        assertEquals("foo.txt", blocks[1].rawFileName().toString());
        assertEquals("three\n", blocks[1].beforeText());
        assertEquals("four\n", blocks[1].afterText());
    }

    @Test
    void testParseEditBlocksNoFinalNewline() {
        String edit =
                """
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
        String edit =
                """
                      Here's the change:

                      ```
                      filename/to/a/file2.txt
                      <<<<<<< SEARCH
                      BRK_ENTIRE_FILE
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
        assertEquals("filename/to/a/file2.txt", blocks[0].rawFileName());
        assertEquals("BRK_ENTIRE_FILE\n", blocks[0].beforeText());
        assertEquals("three\n", blocks[0].afterText());
        assertEquals("filename/to/a/file1.txt", blocks[1].rawFileName());
        assertEquals("one\n", blocks[1].beforeText());
        assertEquals("two\n", blocks[1].afterText());
    }

    @Test
    void testApplyEditsCreatesNewFile(@TempDir Path tempDir)
            throws IOException, EditBlock.AmbiguousMatchException, EditBlock.NoMatchException, InterruptedException {
        TestConsoleIO io = new TestConsoleIO();
        Path existingFile = tempDir.resolve("fileA.txt");
        Files.writeString(existingFile, "Original text\n");

        String response =
                """
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
                          BRK_ENTIRE_FILE
                          =======
                          Created content
                          >>>>>>> REPLACE
                          ```
                          """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlockParser.instance
                .parseEditBlocks(response, ctx.getFilesInContext())
                .blocks();
        EditBlock.apply(ctx, io, blocks);

        // existing filename
        String actualA = Files.readString(existingFile);
        assertTrue(actualA.contains("Updated"), "Expected fileA.txt to contain 'Updated'. Full content:\n" + actualA);

        // new filename
        Path newFile = tempDir.resolve("newFile.txt");
        String newFileText = Files.readString(newFile);
        assertEquals("Created content\n", newFileText);

        // no errors
        assertTrue(io.getErrorLog().isEmpty(), "No error expected");
    }

    @Test
    void testApplyEditsFailsForUnknownFile(@TempDir Path tempDir) throws IOException, InterruptedException {
        TestConsoleIO io = new TestConsoleIO();

        Path existingFile = tempDir.resolve("fileA.txt");
        Files.writeString(existingFile, "Line X\n");

        String response =
                """
                          ```
                          unknownFile.txt
                          <<<<<<< SEARCH
                          replacement
                          =======
                          replacement
                          >>>>>>> REPLACE
                          """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlockParser.instance
                .parseEditBlocks(response, ctx.getFilesInContext())
                .blocks();
        var result = EditBlock.apply(ctx, io, blocks);

        assertFalse(result.failedBlocks().isEmpty(), "Expected failures for unknownFile.txt but got none");
    }

    @Test
    void testApplyEditsFailsForInvalidFilename(@TempDir Path tempDir) throws IOException, InterruptedException {
        TestConsoleIO io = new TestConsoleIO();

        String invalidFilename = "invalid\0filename.txt";
        String response =
                """
                          ```
                          %s
                          <<<<<<< SEARCH
                          a
                          =======
                          b
                          >>>>>>> REPLACE
                          ```
                          """
                        .formatted(invalidFilename);

        TestContextManager ctx = new TestContextManager(tempDir, Set.of());
        var blocks = EditBlockParser.instance
                .parseEditBlocks(response, ctx.getFilesInContext())
                .blocks();
        var result = EditBlock.apply(ctx, io, blocks);

        assertEquals(1, result.failedBlocks().size());
        assertEquals(
                EditBlock.EditBlockFailureReason.FILE_NOT_FOUND,
                result.failedBlocks().getFirst().reason());
    }

    /**
     * Check that we can parse an unclosed block and fail gracefully. (Similar to python
     * test_find_original_update_blocks_unclosed)
     */
    @Test
    void testParseEditBlocksUnclosed(@TempDir Path tempDir) {
        String edit =
                """
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
        var result = EditBlockParser.instance.parseEditBlocks(edit, ctx.getFilesInContext());
        assertNotNull(result.parseError(), "Expected parse error for unclosed edit block");
    }

    /**
     * Check that we parse blocks that have no filename at all (which is a parse error if we haven't yet established a
     * 'currentFile').
     */
    @Test
    void testParseEditBlocksMissingFilename(@TempDir Path tempDir) {
        String edit =
                """
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
        var result = EditBlockParser.instance.parseEditBlocks(edit, ctx.getFilesInContext());
        assertEquals(1, result.blocks().size());
        assertNull(result.blocks().getFirst().rawFileName());
    }

    /** Test detection of a possible "mangled" or fuzzy filename match. */
    @Test
    void testResolveFilenameIgnoreCase(@TempDir Path tempDir) throws EditBlock.SymbolResolutionException {
        TestContextManager ctx = new TestContextManager(tempDir, Set.of("foo.txt"));
        var f = EditBlock.resolveProjectFile(ctx, "fOo.TXt");
        assertEquals("foo.txt", f.getFileName());
    }

    @Test
    void testNoMatchFailure(@TempDir Path tempDir)
            throws IOException, EditBlock.AmbiguousMatchException, EditBlock.NoMatchException, InterruptedException {
        TestConsoleIO io = new TestConsoleIO();
        Path existingFile = tempDir.resolve("fileA.txt");
        Files.writeString(existingFile, "AAA\nBBB\nCCC\n");

        // The "beforeText" is too different from anything in the file
        String response =
                """
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
        var blocks = EditBlockParser.instance
                .parseEditBlocks(response, ctx.getFilesInContext())
                .blocks();
        var result = EditBlock.apply(ctx, io, blocks);

        // Assert exactly one failure with the correct reason
        assertEquals(1, result.failedBlocks().size(), "Expected exactly one failed block");
        assertEquals(
                EditBlock.EditBlockFailureReason.NO_MATCH,
                result.failedBlocks().getFirst().reason(),
                "Expected failure reason to be NO_MATCH");

        // Assert that the file content remains unchanged after the failed edit
        String finalContent = Files.readString(existingFile);
        assertEquals("AAA\nBBB\nCCC\n", finalContent, "File content should remain unchanged after a NO_MATCH failure.");
    }

    @Test
    void testEditResultContainsOriginalContents(@TempDir Path tempDir)
            throws IOException, EditBlock.AmbiguousMatchException, EditBlock.NoMatchException, InterruptedException {
        TestConsoleIO io = new TestConsoleIO();
        Path existingFile = tempDir.resolve("fileA.txt");
        String originalContent = "Original text\n";
        Files.writeString(existingFile, originalContent);

        String response =
                """
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
        var blocks = EditBlockParser.instance
                .parseEditBlocks(response, ctx.getFilesInContext())
                .blocks();
        var result = EditBlock.apply(ctx, io, blocks);

        // Verify original content is returned
        var fileA = new ProjectFile(tempDir, Path.of("fileA.txt"));
        assertEquals(originalContent, result.originalContents().get(fileA));

        // Verify file was actually changed
        String actualContent = Files.readString(existingFile);
        assertEquals("Updated text\n", actualContent);
    }

    @Test
    void testApplyEditsEmptySearchReplacesFile(@TempDir Path tempDir)
            throws IOException, EditBlock.AmbiguousMatchException, EditBlock.NoMatchException, InterruptedException {
        TestConsoleIO io = new TestConsoleIO();
        Path testFile = tempDir.resolve("replaceTest.txt");
        String originalContent = "Initial content.\n";
        Files.writeString(testFile, originalContent);

        String replacementContent = "Replacement text.\n";
        String response =
                """
                          ```
                          replaceTest.txt
                          <<<<<<< SEARCH
                          BRK_ENTIRE_FILE
                          =======
                          %s
                          >>>>>>> REPLACE
                          ```
                          """
                        .formatted(replacementContent.trim());

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("replaceTest.txt"));
        var blocks = EditBlockParser.instance
                .parseEditBlocks(response, ctx.getFilesInContext())
                .blocks();
        assertEquals(1, blocks.size());
        assertEquals("BRK_ENTIRE_FILE\n", blocks.getFirst().beforeText()); // now explicit marker

        var result = EditBlock.apply(ctx, io, blocks);

        String actualContent = Files.readString(testFile);
        assertEquals(replacementContent, actualContent);

        assertTrue(result.failedBlocks().isEmpty(), "No failures expected");
        assertTrue(io.getErrorLog().isEmpty(), "No IO errors expected");
    }

    /**
     * These tests illustrate how the new "forgiving" parse logic works when we don't see a "filename =======" divider
     * between SEARCH and REPLACE, but do see a standalone "=======" line. If exactly one such line is found, we use it
     * as the divider; otherwise it's a non-fatal parse error.
     */
    @Test
    void testForgivingDividerSingleMatch(@TempDir Path tempDir) {
        String content =
                """
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
        var result = EditBlockParser.instance.parseEditBlocks(content, ctx.getFilesInContext());
        // Expect exactly one successfully parsed block, no parse errors
        assertEquals(1, result.blocks().size(), "Should parse a single block");
        assertNull(result.parseError(), "No parse errors expected");

        var block = result.blocks().getFirst();
        assertEquals("foo.txt", block.rawFileName());
        assertEquals("old line\n", block.beforeText());
        assertEquals("new line\n", block.afterText());
    }

    @Test
    void testmissingDivider(@TempDir Path tempDir) {
        String content =
                """
                         ```
                         foo.txt
                         <<<<<<< SEARCH
                         line A
                         line B
                         >>>>>>> REPLACE
                         ```
                         """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("foo.txt"));
        var result = EditBlockParser.instance.parseEditBlocks(content, ctx.getFilesInContext());
        assertEquals(0, result.blocks().size(), "No successful blocks expected without any divider line");
        assertNotNull(result.parseError(), "Should report parse error on zero matches");
    }

    @Test
    void testNoMatchFailureWithExistingReplacementText(@TempDir Path tempDir)
            throws IOException, EditBlock.AmbiguousMatchException, EditBlock.NoMatchException, InterruptedException {
        TestConsoleIO io = new TestConsoleIO();
        Path existingFile = tempDir.resolve("fileA.txt");
        String initialContent = "AAA\nBBB\nCCC\n";
        String alreadyPresentText = "Replacement Text"; // This text is NOT in initialContent yet, but we want it there
        String fileContent = initialContent + alreadyPresentText + "\nDDD\n";
        Files.writeString(existingFile, fileContent);

        // The "beforeText" will not match, but the "afterText" is already in the file
        String response =
                """
                          ```
                          fileA.txt
                          <<<<<<< SEARCH
                          NonExistentBeforeText
                          =======
                          %s
                          >>>>>>> REPLACE
                          ```
                          """
                        .formatted(alreadyPresentText);

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlockParser.instance
                .parseEditBlocks(response, ctx.getFilesInContext())
                .blocks();
        var result = EditBlock.apply(ctx, io, blocks);

        // Assert exactly one failure with NO_MATCH reason
        assertEquals(1, result.failedBlocks().size(), "Expected exactly one failed block");
        var failedBlock = result.failedBlocks().getFirst();
        assertEquals(
                EditBlock.EditBlockFailureReason.NO_MATCH,
                failedBlock.reason(),
                "Expected failure reason to be NO_MATCH");

        // Assert the specific commentary is present
        assertTrue(
                failedBlock.commentary().contains("replacement text is already present"),
                "Expected specific commentary about existing replacement text");

        // Assert that the file content remains unchanged
        String finalContent = Files.readString(existingFile);
        assertEquals(fileContent, finalContent, "File content should remain unchanged after the failed edit");
    }

    @Test
    void testNoMatchFailureWithDiffLikeSearchText(@TempDir Path tempDir)
            throws IOException, EditBlock.AmbiguousMatchException, EditBlock.NoMatchException, InterruptedException {
        TestConsoleIO io = new TestConsoleIO();
        Path existingFile = tempDir.resolve("fileB.txt");
        String initialContent = "Line 1\nLine 2\nLine 3\n";
        Files.writeString(existingFile, initialContent);

        // The "beforeText" will not match, and contains diff-like lines
        String response =
                """
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
        var blocks = EditBlockParser.instance
                .parseEditBlocks(response, ctx.getFilesInContext())
                .blocks();
        var result = EditBlock.apply(ctx, io, blocks);

        // Assert exactly one failure with NO_MATCH reason
        assertEquals(1, result.failedBlocks().size(), "Expected exactly one failed block");
        var failedBlock = result.failedBlocks().getFirst();
        assertEquals(
                EditBlock.EditBlockFailureReason.NO_MATCH,
                failedBlock.reason(),
                "Expected failure reason to be NO_MATCH");

        // Assert the specific commentary about diff format is present
        assertTrue(
                failedBlock.commentary().contains("not unified diff format"),
                "Expected specific commentary about diff-like search text");

        // Assert that the file content remains unchanged
        String finalContent = Files.readString(existingFile);
        assertEquals(initialContent, finalContent, "File content should remain unchanged after the failed edit");
    }

    @Test
    void testExtractCodeWithEmbeddedBackticks() {
        String textWithEmbeddedBackticks =
                """
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
        String expectedCodePrecise = "public class Test {\n" + "    String s = \"```\"; // Embedded backticks\n"
                + "    /*\n"
                + "     * Another ``` example\n"
                + "     */\n"
                + "}\n";

        String actualCode = EditBlock.extractCodeFromTripleBackticks(textWithEmbeddedBackticks);
        assertEquals(expectedCodePrecise, actualCode);
    }

    @Test
    void testExtractCodeWithEmbeddedBackticksAndMultipleBlocks() {
        // This test verifies the behavior when multiple blocks are present and the first has embedded backticks.
        // The greedy (.*) will cause it to capture content until the *last* ``` in the input string
        // if `extractCodeFromTripleBackticks` is fed the whole string.
        String textWithMultipleBlocks =
                """
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
        String expectedMergedCodePrecise = "public class Test1 {\n" + "    String s = \"```\"; // Embedded\n"
                + "}\n"
                + "```\n"
                + "Some intermediate text.\n"
                + "```python\n"
                + "print(\"Hello\")\n";

        String actualCode = EditBlock.extractCodeFromTripleBackticks(textWithMultipleBlocks);
        assertEquals(
                expectedMergedCodePrecise,
                actualCode,
                "Greedy regex (.*) is expected to merge content if multiple blocks are present in the input string.");
    }

    @Test
    void testResolveAbsoluteFilename(@TempDir Path tempDir) throws Exception {
        Path subdir = tempDir.resolve("src");
        Files.createDirectories(subdir);
        Path filePath = subdir.resolve("foo.txt");
        Files.writeString(filePath, "content\n");
        var sep = File.separator;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("src%sfoo.txt".formatted(sep)));

        ProjectFile pf = EditBlock.resolveProjectFile(ctx, "%ssrc%sfoo.txt".formatted(sep, sep));
        assertEquals("foo.txt", pf.getFileName());
        assertEquals(filePath, pf.absPath());
    }

    @Test
    void testResolveAbsoluteNonExistentFilename(@TempDir Path tempDir) {
        var sep = File.separator;
        TestContextManager ctx = new TestContextManager(tempDir, Set.of());
        assertThrows(
                EditBlock.SymbolInvalidException.class,
                () -> EditBlock.resolveProjectFile(ctx, "%ssrc%sfoo.txt".formatted(sep, sep)));
    }

    // ----------------------------------------------------
    // Tests for BRK_CONFLICT handling
    // ----------------------------------------------------
    @Test
    void testReplaceBrkConflictBlock(@TempDir Path tempDir) throws IOException, InterruptedException {
        TestConsoleIO io = new TestConsoleIO();
        Path testFile = tempDir.resolve("conf.txt");

        String conflictBlock = "BRK_CONFLICT_BEGIN_1\n"
                + "BRK_OUR_VERSION abc\n"
                + "abc Some conflicting line\n"
                + "BRK_CONFLICT_END_1\n";

        String originalContent = "start\n" + conflictBlock + "end\n";
        Files.writeString(testFile, originalContent);

        // New SEARCH syntax: single line identifies the conflict region to replace
        var block = new EditBlock.SearchReplaceBlock("conf.txt", "BRK_CONFLICT_1\n", "Resolved line\n");
        TestContextManager ctx = new TestContextManager(tempDir, Set.of("conf.txt"));

        var result = EditBlock.apply(ctx, io, List.of(block));

        // Should have applied successfully
        assertTrue(result.failedBlocks().isEmpty(), "No failures expected when replacing unique BRK conflict block");

        String finalContent = Files.readString(testFile);
        assertEquals("start\nResolved line\nend\n", finalContent);
    }

    @Test
    void testBrkFunctionReplacement_UniqueMethod_JavaAnalyzer() throws Exception {
        var rootDir = UpdateTestUtil.newTempDir();
        UpdateTestUtil.writeFile(
                rootDir,
                "A.java",
                """
                public class A {
                  public int method1() { return 1; }
                }
                """);

        var project = UpdateTestUtil.newTestProject(rootDir, Languages.JAVA);
        var analyzer = new JavaAnalyzer(project);

        var editable = Set.of(new ProjectFile(rootDir, "A.java"));
        var ctx = new TestContextManager(project, new TestConsoleIO(), new HashSet<>(editable), analyzer);

        String response =
                """
                ```
                A.java
                <<<<<<< SEARCH
                BRK_FUNCTION A.method1
                =======
                public int method1() { return 2; }
                >>>>>>> REPLACE
                ```
                """;

        var blocks = EditBlockParser.instance
                .parseEditBlocks(response, ctx.getFilesInContext())
                .blocks();
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        var content = Files.readString(rootDir.resolve("A.java"));
        assertTrue(content.contains("return 2;"), "Method body should be updated");
        assertTrue(result.failedBlocks().isEmpty(), "No failures expected");
    }

    @Test
    void testBrkFunctionReplacement_OverloadedMethod_Ambiguous() throws Exception {
        var rootDir = UpdateTestUtil.newTempDir();
        UpdateTestUtil.writeFile(
                rootDir,
                "B.java",
                """
                public class B {
                  public int foo(int x) { return x; }
                  public String foo(String s) { return s; }
                }
                """);

        var project = UpdateTestUtil.newTestProject(rootDir, Languages.JAVA);
        var analyzer = new JavaAnalyzer(project);

        var editable = Set.of(new ProjectFile(rootDir, "B.java"));
        var ctx = new TestContextManager(project, new TestConsoleIO(), new HashSet<>(editable), analyzer);

        String response =
                """
                ```
                B.java
                <<<<<<< SEARCH
                BRK_FUNCTION B.foo
                =======
                public int foo(int x) { return x + 1; }
                >>>>>>> REPLACE
                ```
                """;

        var blocks = EditBlockParser.instance
                .parseEditBlocks(response, ctx.getFilesInContext())
                .blocks();
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertEquals(1, result.failedBlocks().size(), "One failed block expected");
        assertEquals(
                EditBlock.EditBlockFailureReason.AMBIGUOUS_MATCH,
                result.failedBlocks().getFirst().reason(),
                "Overloads must be rejected as ambiguous");
    }

    @Test
    void testBrkClassReplacement_JavaAnalyzer() throws Exception {
        var rootDir = UpdateTestUtil.newTempDir();
        UpdateTestUtil.writeFile(
                rootDir,
                "C.java",
                """
                public class C {
                  public int v() { return 10; }
                }
                """);

        var project = UpdateTestUtil.newTestProject(rootDir, Languages.JAVA);
        var analyzer = new JavaAnalyzer(project);

        var editable = Set.of(new ProjectFile(rootDir, "C.java"));
        var ctx = new TestContextManager(project, new TestConsoleIO(), new HashSet<>(editable), analyzer);

        String response =
                """
                ```
                C.java
                <<<<<<< SEARCH
                BRK_CLASS C
                =======
                public class C {
                  public int v() { return 42; }
                }
                >>>>>>> REPLACE
                ```
                """;

        var blocks = EditBlockParser.instance
                .parseEditBlocks(response, ctx.getFilesInContext())
                .blocks();
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        var content = Files.readString(rootDir.resolve("C.java"));
        assertTrue(content.contains("return 42;"), "Class body should be replaced");
        assertTrue(result.failedBlocks().isEmpty(), "No failures expected");
    }

    @Test
    void testTrailingNewlinePreservation(@TempDir Path tempDir) throws IOException, InterruptedException {
        TestConsoleIO io = new TestConsoleIO();
        TestContextManager ctx = new TestContextManager(tempDir, Set.of("file1.txt", "file2.txt"));

        // Part 1: File without trailing newline
        Path file1 = tempDir.resolve("file1.txt");
        String originalContent1 = "line1\nline2"; // No trailing newline
        Files.writeString(file1, originalContent1);

        // Part 2: File with trailing newline
        Path file2 = tempDir.resolve("file2.txt");
        String originalContent2 = "lineA\nlineB\n"; // With trailing newline
        Files.writeString(file2, originalContent2);

        var block1 = new EditBlock.SearchReplaceBlock("file1.txt", "line2\n", "line two\n");
        var block2 = new EditBlock.SearchReplaceBlock("file2.txt", "lineB\n", "line B\n");
        EditBlock.apply(ctx, io, List.of(block1, block2));

        String updatedContent1 = Files.readString(file1);
        assertEquals("line1\nline two", updatedContent1);
        assertFalse(updatedContent1.endsWith("\n"));

        String updatedContent2 = Files.readString(file2);
        assertEquals("lineA\nline B\n", updatedContent2);
        assertTrue(updatedContent2.endsWith("\n"));
    }

    // ----------------------------------------------------
    // Helper methods
    // ----------------------------------------------------
    private EditBlock.SearchReplaceBlock[] parseBlocks(String fullResponse, Set<String> validFilenames) {
        var root = FileSystems.getDefault().getRootDirectories().iterator().next();
        var files = validFilenames.stream()
                .map(f -> new ProjectFile(root, Path.of(f)))
                .collect(Collectors.toSet());
        var blocks =
                EditBlockParser.instance.parseEditBlocks(fullResponse, files).blocks();
        return blocks.toArray(new EditBlock.SearchReplaceBlock[0]);
    }
}
