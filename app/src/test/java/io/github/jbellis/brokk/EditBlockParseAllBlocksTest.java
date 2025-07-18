package io.github.jbellis.brokk;

import io.github.jbellis.brokk.EditBlock.OutputBlock;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EditBlockParseAllBlocksTest {
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
        String input = """
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
        assertEquals("build.gradle", result.getFirst().block().filename());
        assertTrue(result.getFirst().block().beforeText().contains("a:b:1.0"));
        assertTrue(result.getFirst().block().afterText().contains("a:b:2.0"));
    }

    @Test
    void testParseEditBlockInsideText() {
        String input = """
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
        assertEquals("build.gradle", result.get(1).block().filename());
        assertTrue(result.get(1).block().beforeText().contains("a:b:1.0"));
        assertTrue(result.get(1).block().afterText().contains("a:b:2.0"));
        assertTrue(result.get(2).text().contains("concluding"));
    }

    @Test
    void testParseMultipleValidEditBlocks() {
        String input = """
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
        String input = """
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
}
