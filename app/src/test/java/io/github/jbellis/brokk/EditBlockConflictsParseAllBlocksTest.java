package io.github.jbellis.brokk;

import io.github.jbellis.brokk.EditBlock.OutputBlock;
import io.github.jbellis.brokk.prompts.EditBlockConflictsParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EditBlockConflictsParseAllBlocksTest {
    @Test
    void testParseEmptyString() {
        var result = EditBlockConflictsParser.instance.parse("").blocks();
        assertEquals(0, result.size());
    }

    @Test
    void testParsePlainTextOnly() {
        String input = "This is just plain text.";
        var result = EditBlockConflictsParser.instance.parse(input).blocks();
        assertEquals(1, result.size());
        assertEquals(OutputBlock.plain(input), result.getFirst());
    }
    
    @Test
    void testParseSimpleEditBlock() {
        String input = """
                <<<<<<< SEARCH build.gradle
                dependencies {
                    implementation("a:b:1.0")
                }
                ======= build.gradle
                dependencies {
                    implementation("a:b:2.0") // updated
                }
                >>>>>>> REPLACE build.gradle
                """;
        var result = EditBlockConflictsParser.instance.parse(input).blocks();

        assertEquals(1, result.size());
        assertEquals("build.gradle", result.getFirst().block().filename());
        assertTrue(result.getFirst().block().beforeText().contains("a:b:1.0"));
        assertTrue(result.getFirst().block().afterText().contains("a:b:2.0"));
    }

    @Test
    void testParseEditBlockInsideText() {
        String input = """
        Some introductory text.
        <<<<<<< SEARCH build.gradle
        dependencies {
            implementation("a:b:1.0")
        }
        ======= build.gradle
        dependencies {
            implementation("a:b:2.0") // updated
        }
        >>>>>>> REPLACE build.gradle
        Some concluding text.
        """;
        var result = EditBlockConflictsParser.instance.parse(input).blocks();

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
                <<<<<<< SEARCH file1.txt
                abc
                ======= file1.txt
                def
                >>>>>>> REPLACE file1.txt
                
                <<<<<<< SEARCH file2.java
                class A {}
                ======= file2.java
                class B {}
                >>>>>>> REPLACE file2.java
                Text epilogue
                """;
        var result = EditBlockConflictsParser.instance.parse(input).blocks();

        assertEquals(4, result.size()); // prologue, s/r, s/r, epilogue
        // TODO flesh out asserts
    }


    @Test
    void testParseMalformedEditBlockFallsBackToText() {
        // Missing ======= divider
        String input = """
                Some introductory text.
                <<<<<<< SEARCH build.gradle
                dependencies {
                    implementation("a:b:1.0")
                }
                >>>>>>> REPLACE build.gradle
                Some concluding text.
                """;
        var editParseResult = EditBlockConflictsParser.instance.parseEditBlocks(input, null);
        assertNotNull(editParseResult.parseError(), "EditBlock parser should report an error");
        assertTrue(editParseResult.blocks().isEmpty(), "EditBlock parser should find no valid blocks");

        // LlmOutputParser should fall back to plain/code parsing
        var result = EditBlockConflictsParser.instance.parse(input).blocks();
        assertEquals(1, result.size());
        assertNotNull(result.getFirst().text());
    }
}
