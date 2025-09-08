package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.prompts.EditBlockParser;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EditBlockParseAllBlocksTest {
    @Test
    void testParseEmptyString() {
        var blocksOnly = EditBlockParser.instance.parse("", Set.of()).blocks();
        assertEquals(0, blocksOnly.size(), "Expected no SRBs for empty input");

        var redacted = EditBlockParser.instance.redact("");
        assertEquals("", redacted, "Redacting empty input should be a no-op");
    }

    @Test
    void testParsePlainTextOnly() {
        String input = "This is just plain text.";
        var blocksOnly = EditBlockParser.instance.parse(input, Set.of()).blocks();
        assertEquals(0, blocksOnly.size(), "Expected no SRBs for plain text");

        var redacted = EditBlockParser.instance.redact(input);
        assertEquals(input, redacted, "Redaction should not change plain text");
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
        var blocks = EditBlockParser.instance.parse(input, Set.of()).blocks();

        assertEquals(1, blocks.size());
        var block = blocks.getFirst();
        assertEquals("build.gradle", block.rawFileName());
        assertTrue(block.beforeText().contains("a:b:1.0"));
        assertTrue(block.afterText().contains("a:b:2.0"));

        var redacted = EditBlockParser.instance.redact(input);
        assertTrue(redacted.contains("[elided SEARCH/REPLACE block]"), "Expected elided placeholder in redacted result");
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
        var blocks = EditBlockParser.instance.parse(input, Set.of()).blocks();

        assertEquals(1, blocks.size());
        var block = blocks.getFirst();
        assertEquals("build.gradle", block.rawFileName());
        assertTrue(block.beforeText().contains("a:b:1.0"));
        assertTrue(block.afterText().contains("a:b:2.0"));

        var redacted = EditBlockParser.instance.redact(input);
        assertTrue(redacted.contains("Some introductory text."));
        assertTrue(redacted.contains("[elided SEARCH/REPLACE block]"));
        assertTrue(redacted.contains("Some concluding text."));
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
        var blocks = EditBlockParser.instance.parse(input, Set.of()).blocks();
        assertEquals(2, blocks.size(), "Expected two SRBs");

        var redacted = EditBlockParser.instance.redact(input);
        assertTrue(redacted.contains("Text prologue"));
        assertTrue(redacted.contains("Text epilogue"));

        // Expect two placeholders for two blocks
        int first = redacted.indexOf("[elided SEARCH/REPLACE block]");
        assertTrue(first >= 0, "Expected first elided block");
        int second = redacted.indexOf("[elided SEARCH/REPLACE block]", first + 1);
        assertTrue(second >= 0, "Expected second elided block");
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
        var editParseResult = EditBlockParser.instance.parse(input, Set.of());
        assertNotNull(editParseResult.parseError(), "EditBlock parser should report an error");
        assertTrue(editParseResult.blocks().isEmpty(), "EditBlock parser should find no valid blocks");

        // redact should be a no-op for malformed blocks
        var redacted = EditBlockParser.instance.redact(input);
        assertEquals(input, redacted, "Malformed block should be treated as plain text in redaction");
    }
}
