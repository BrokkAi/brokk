package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.EditBlock.OutputBlock;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EditBlockParserTest {
    @Test
    void testParseEmptyString() {
        var result = EditBlockParser.instance.parse("", Set.of()).blocks();
        assertEquals(0, result.size());
    }

    @Test
    void testParsePlainTextOnly() {
        String input = "This is just plain text.";
        var result = EditBlockParser.instance.parse(input, Set.of()).blocks();
        assertEquals(1, result.size());
        assertEquals(OutputBlock.plain(input), result.getFirst());
    }

    @Test
    void testParseSimpleEditBlock() {
        String input =
                """
                ```
                build.gradle
                <<<<<<< SEARCH
                dependencies {
                    implementation("a:b:1.0")
                }
                =======
                dependencies {
                    implementation("a:b:2.0") // updated
                }
                >>>>>>> REPLACE
                ```
                """;
        var result = EditBlockParser.instance.parse(input, Set.of()).blocks();

        assertEquals(1, result.size());
        assertEquals("build.gradle", result.getFirst().block().rawFileName());
        assertTrue(result.getFirst().block().beforeText().contains("a:b:1.0"));
        assertTrue(result.getFirst().block().afterText().contains("a:b:2.0"));
    }

    @Test
    void testParseEditBlockInsideText() {
        String input =
                """
        Some introductory text.
        ```
        build.gradle
        <<<<<<< SEARCH
        dependencies {
            implementation("a:b:1.0")
        }
        =======
        dependencies {
            implementation("a:b:2.0") // updated
        }
        >>>>>>> REPLACE
        ```
        Some concluding text.
        """;
        var result = EditBlockParser.instance.parse(input, Set.of()).blocks();

        assertEquals(3, result.size());
        assertTrue(result.get(0).text().contains("introductory"));
        assertEquals("build.gradle", result.get(1).block().rawFileName());
        assertTrue(result.get(1).block().beforeText().contains("a:b:1.0"));
        assertTrue(result.get(1).block().afterText().contains("a:b:2.0"));
        assertTrue(result.get(2).text().contains("concluding"));
    }

    @Test
    void testParseMultipleValidEditBlocks() {
        String input =
                """
                Text prologue
                ```
                file1.txt
                <<<<<<< SEARCH
                abc
                =======
                def
                >>>>>>> REPLACE
                ```

                ```
                file2.java
                <<<<<<< SEARCH
                class A {}
                =======
                class B {}
                >>>>>>> REPLACE
                ```
                Text epilogue
                """;
        var result = EditBlockParser.instance.parse(input, Set.of()).blocks();

        assertEquals(4, result.size()); // prologue, s/r, s/r, epilogue
        // TODO flesh out asserts
    }

    @Test
    void testParseMalformedEditBlockFallsBackToText() {
        // Missing ======= divider
        String input =
                """
                Some introductory text.
                ```
                build.gradle
                <<<<<<< SEARCH
                dependencies {
                    implementation("a:b:1.0")
                }
                >>>>>>> REPLACE
                ```
                Some concluding text.
                """;
        var editParseResult = EditBlockParser.instance.parseEditBlocks(input, Set.of());
        assertNotNull(editParseResult.parseError(), "EditBlock parser should report an error");
        assertTrue(editParseResult.blocks().isEmpty(), "EditBlock parser should find no valid blocks");

        // LlmOutputParser should fall back to plain/code parsing
        var result = EditBlockParser.instance.parse(input, Set.of()).blocks();
        assertEquals(1, result.size());
        assertNotNull(result.getFirst().text());
    }

    @Test
    void testParseSearchBlockContainingConflictMarkers() {
        // replace line A + conflict marker with line B
        String input =
                """
                ```
                file.txt
                <<<<<<< SEARCH
                line A
                <<<<<<< HEAD
                conflict version
                =======
                other version
                >>>>>>> branch
                =======
                line B
                >>>>>>> REPLACE
                ```
                """;
        var result = EditBlockParser.instance.parse(input, Set.of()).blocks();

        assertEquals(1, result.size(), result.toString());
        var block = result.getFirst().block();
        assertEquals("file.txt", block.rawFileName());
        assertTrue(
                block.beforeText().contains("<<<<<<< HEAD"), "Nested conflict start should be preserved in beforeText");
        assertTrue(block.beforeText().contains("conflict version"));
        assertTrue(block.beforeText().contains("other version"));
        assertTrue(block.afterText().contains("line B"));
    }

    @Test
    void testParseReplaceBlockContainingConflictMarkers() {
        // Nested conflict markers inside REPLACE side should be preserved and not terminate the block.
        String input =
                """
                ```
                File.java
                <<<<<<< SEARCH
                int x = 1;
                =======
                int x = 2;
                <<<<<<< HEAD
                int y = 1;
                =======
                int y = 2;
                >>>>>>> branch
                >>>>>>> REPLACE
                ```
                """;
        var result = EditBlockParser.instance.parse(input, Set.of()).blocks();

        assertEquals(1, result.size(), result.toString());
        var block = result.getFirst().block();
        assertEquals("File.java", block.rawFileName());
        assertTrue(block.afterText().contains("<<<<<<< HEAD"));
        assertTrue(block.afterText().contains(">>>>>>> branch"));
    }

    @Test
    void testParseFenceLessBlockWithConflictMarkers() {
        String input =
                """
                <<<<<<< SEARCH
                A
                <<<<<<< HEAD
                ours
                =======
                theirs
                >>>>>>> branch
                =======
                B
                >>>>>>> REPLACE
                """;
        var result = EditBlockParser.instance.parse(input, Set.of()).blocks();

        assertEquals(1, result.size(), result.toString());
        var block = result.getFirst().block();
        assertNull(block.rawFileName());
        assertTrue(block.beforeText().contains("ours"));
        assertTrue(block.afterText().contains("B"));
    }

    @Test
    void testGitConflictAloneIsNotEditBlockAndNoError() {
        String input =
                """
                Here is a plain git conflict, not an edit block:

                <<<<<<< HEAD
                alpha
                =======
                beta
                >>>>>>> branch
                """;

        // parseEditBlocks should not flag an error or produce blocks
        var editsOnly = EditBlockParser.instance.parseEditBlocks(input, Set.of());
        assertTrue(editsOnly.blocks().isEmpty(), "No edit blocks expected");
        assertNull(editsOnly.parseError(), "No parse error should be reported for plain git conflicts");

        // Full parser should return the whole thing as plain text
        var result = EditBlockParser.instance.parse(input, Set.of()).blocks();
        assertEquals(1, result.size());
        assertNotNull(result.getFirst().text());
    }

    @Test
    void testFencedBlockWithoutFilenameIsSingleEditBlockAndFilenameIsNullable() {
        // This used to produce a leading plain-text "```" block and skip updating currentFilename in the fenced path.
        String input =
                """
                ```
                <<<<<<< SEARCH
                before
                =======
                after
                >>>>>>> REPLACE
                ```
                """;

        // Full parse should treat the entire fenced region as a single edit block
        var result = EditBlockParser.instance.parse(input, Set.of()).blocks();
        assertEquals(1, result.size(), result.toString());
        var block = result.getFirst().block();
        assertNotNull(block, "Expected an edit block");
        assertNull(block.rawFileName(), "No explicit filename should yield a null rawFileName");
        assertTrue(block.beforeText().contains("before"));
        assertTrue(block.afterText().contains("after"));

        // Edits-only parse should also return exactly one block with no parse error
        var editsOnly = EditBlockParser.instance.parseEditBlocks(input, Set.of());
        assertEquals(1, editsOnly.blocks().size());
        assertNull(editsOnly.parseError());
    }

    @Test
    void testParseMarkersWithoutBlocksTriggersError() {
        // Content contains our marker but parsing yields no blocks => parseEditBlocks should produce a helpful error.
        String input =
                """
                Some prelude text.
                >>>>>>> REPLACE
                More trailing text.
                """;
        var editsOnly = EditBlockParser.instance.parseEditBlocks(input, Set.of());
        assertTrue(editsOnly.blocks().isEmpty(), "No edit blocks expected");
        assertNotNull(editsOnly.parseError(), "Expected parse error suggesting an edit block was intended");
    }

    @Test
    void testSuggestSearchReplaceForDiffFormat() {
        String input =
                """
                Some explanation
                @@ -1,3 +1,3 @@
                -old line
                +new line
                context line
                """;
        var editsOnly = EditBlockParser.instance.parseEditBlocks(input, Set.of());
        assertTrue(editsOnly.blocks().isEmpty(), "No edit blocks expected for diff input");
        assertNotNull(editsOnly.parseError(), "Parser should suggest using SEARCH/REPLACE for diff inputs");
        assertTrue(
                editsOnly.parseError().toUpperCase().contains("SEARCH/REPLACE"),
                "Error message should mention SEARCH/REPLACE");
    }
}
