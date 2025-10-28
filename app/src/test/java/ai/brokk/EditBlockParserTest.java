package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.EditBlock.OutputBlock;
import ai.brokk.prompts.EditBlockParser;
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

    @Test
    void testParseThreeFilesOneBlockEach() {
        String input =
                """
        ```
        src/main/java/io/github/jbellis/brokk/ContextManager.java
        <<<<<<< SEARCH
            static Optional<AiMessage> redactAiMessage(AiMessage aiMessage, EditBlockParser parser) {
        =======
            public static Optional<AiMessage> redactAiMessage(AiMessage aiMessage, EditBlockParser parser) {
        >>>>>>> REPLACE
        ```
        ```
        src/main/java/io/github/jbellis/brokk/agents/CodeAgent.java
        <<<<<<< SEARCH
            private List<ChatMessage> prepareMessagesForTaskEntryLog() {
        =======
            /**
             * Prepares messages for storage in a TaskEntry.
             * This involves filtering raw LLM I/O to keep USER, CUSTOM, and AI messages.
             * AI messages containing edit blocks will have those blocks replaced by redaction placeholders.
             */
            private List<ChatMessage> prepareMessagesForTaskEntryLog() {
        >>>>>>> REPLACE
        ```
        ```
        src/test/java/io/github/jbellis/brokk/ContextManagerRedactionTest.java
        <<<<<<< SEARCH
        package io.github.jbellis.brokk;

        class ContextManagerRedactionTest {
            private String createSingleBlockMessage(String filename, String search, String replace) {
                return ""
                   ```
                   %s
                    <<<<<<< SEARCH
                            %s
                    =======
                    %s
                    >>>>>>> REPLACE
                   ```
        "".formatted(filename, search, replace);
        }

        @Test
        =======
        package io.github.jbellis.brokk;

        import java.util.Optional;

        class ContextManagerRedactionTest {
            private String createSingleBlockMessage(String filename, String search, String replace) {
                return ""
                    ```
                    %s
                    <<<<<<< SEARCH
                    %s
                    =======
                    %s
                    >>>>>>> REPLACE
                    ```
                    "".formatted(filename, search, replace);
            }

            @Test
            >>>>>>> REPLACE
            ```
        """;

        var editsOnly = EditBlockParser.instance.parseEditBlocks(input, Set.of());
        assertNull(editsOnly.parseError(), "No parse errors expected");
        var blocks = editsOnly.blocks();
        assertEquals(3, blocks.size(), "Should parse exactly three edit blocks");

        // Block 1 assertions
        var b1 = blocks.get(0);
        assertEquals("src/main/java/io/github/jbellis/brokk/ContextManager.java", b1.rawFileName());
        assertEquals(
                "    static Optional<AiMessage> redactAiMessage(AiMessage aiMessage, EditBlockParser parser) {\n",
                b1.beforeText());
        assertEquals(
                "    public static Optional<AiMessage> redactAiMessage(AiMessage aiMessage, EditBlockParser parser) {\n",
                b1.afterText());

        // Block 2 assertions
        var b2 = blocks.get(1);
        assertEquals("src/main/java/io/github/jbellis/brokk/agents/CodeAgent.java", b2.rawFileName());
        assertEquals("    private List<ChatMessage> prepareMessagesForTaskEntryLog() {\n", b2.beforeText());
        String expectedAfter2 =
                """
                    /**
                     * Prepares messages for storage in a TaskEntry.
                     * This involves filtering raw LLM I/O to keep USER, CUSTOM, and AI messages.
                     * AI messages containing edit blocks will have those blocks replaced by redaction placeholders.
                     */
                    private List<ChatMessage> prepareMessagesForTaskEntryLog() {
                """;
        assertEquals(expectedAfter2, b2.afterText());

        // Block 3 assertions
        var b3 = blocks.get(2);
        assertEquals("src/test/java/io/github/jbellis/brokk/ContextManagerRedactionTest.java", b3.rawFileName());
        String expectedBefore3 =
                """
        package io.github.jbellis.brokk;

        class ContextManagerRedactionTest {
            private String createSingleBlockMessage(String filename, String search, String replace) {
                return ""
                   ```
                   %s
                    <<<<<<< SEARCH
                            %s
                    =======
                    %s
                    >>>>>>> REPLACE
                   ```
        "".formatted(filename, search, replace);
        }

        @Test
        """;
        String expectedAfter3 =
                """
        package io.github.jbellis.brokk;

        import java.util.Optional;

        class ContextManagerRedactionTest {
            private String createSingleBlockMessage(String filename, String search, String replace) {
                return ""
                    ```
                    %s
                    <<<<<<< SEARCH
                    %s
                    =======
                    %s
                    >>>>>>> REPLACE
                    ```
                    "".formatted(filename, search, replace);
            }

            @Test
        """;
        assertEquals(expectedBefore3, b3.beforeText());
        assertEquals(expectedAfter3, b3.afterText());
    }

    @Test
    void testContextManagerRedactionExampleParsesToSingleBlock() {
        // This mirrors the snippet from ContextManagerRedactionTest.java in the goal:
        // a single fenced SEARCH/REPLACE block targeting that file path.
        String input =
                """
                ```
                src/test/java/io/github/jbellis/brokk/ContextManagerRedactionTest.java
                <<<<<<< SEARCH
                private String createSingleBlockMessage(String filename, String search, String replace) {
                    return \"\"\"
                           ```
                           %s
                           <<<<<<< SEARCH
                           %s
                           =======
                           %s
                           >>>>>>> REPLACE
                           ```
                           \"\"\".formatted(filename, search, replace);
                }
                =======
                private String createSingleBlockMessage(String filename, String search, String replace) {
                    return \"\"\"
                           ```
                           %s
                           <<<<<<< SEARCH
                           %s
                           =======
                           %s
                           >>>>>>> REPLACE
                           ```
                           \"\"\".formatted(filename, search, replace);
                }

                @Test
                void handlesBlockWithNullFilename() {
                    // Create a block without a filename line. EditBlockParser will parse this with a null filename.
                    String blockWithNullFilename = \"\"\"
                                               ```
                                               <<<<<<< SEARCH
                                               old code
                                               =======
                                               new code
                                               >>>>>>> REPLACE
                                               ```
                                               \"\"\";
                    AiMessage originalMessage = new AiMessage(blockWithNullFilename);

                    Optional<AiMessage> redactedMessage = ContextManager.redactAiMessage(originalMessage, parser);

                    assertTrue(redactedMessage.isPresent(), "Message with only block (null filename) should be present.");
                    assertEquals(ELIDED_NULL_BLOCK_PLACEHOLDER, redactedMessage.get().text(), "Message content should be the null filename placeholder.");
                }
                >>>>>>> REPLACE
                ```
                """;

        var editsOnly = EditBlockParser.instance.parseEditBlocks(input, Set.of());
        assertNull(editsOnly.parseError(), "No parse errors expected for a well-formed fenced block");
        var blocks = editsOnly.blocks();
        assertEquals(1, blocks.size(), "The example should parse to exactly one SEARCH/REPLACE block");

        var b = blocks.getFirst();
        assertEquals(
                "src/test/java/io/github/jbellis/brokk/ContextManagerRedactionTest.java",
                b.rawFileName(),
                "Filename line inside the fence should be captured as rawFileName");
        assertTrue(
                b.beforeText().contains("createSingleBlockMessage"),
                "Before side should include the helper method body");
        assertTrue(
                b.afterText().contains("handlesBlockWithNullFilename"),
                "After side should include the replacement test method");
    }
}
