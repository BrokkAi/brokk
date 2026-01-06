package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ICodeReview;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewExcerptParserTest {

    @Test
    void testParseSingleExcerpt() {
        String input = """
                Here is the excerpt:
                BRK_EXCERPT_1
                src/main/java/Foo.java
                ```java
                public class Foo {}
                ```
                """;
        Map<String, ICodeReview.CodeExcerpt> results = ReviewExcerptParser.instance.parseExcerpts(input);

        assertEquals(1, results.size());
        ICodeReview.CodeExcerpt excerpt = results.get("1");
        assertEquals("src/main/java/Foo.java", excerpt.file());
        assertEquals("public class Foo {}", excerpt.excerpt());
    }

    @Test
    void testParseMultipleExcerpts() {
        String input = """
                BRK_EXCERPT_A
                FileA.txt
                ```
                content A
                ```

                Some text in between.

                BRK_EXCERPT_B
                FileB.txt
                ```python
                content B
                ```
                """;
        Map<String, ICodeReview.CodeExcerpt> results = ReviewExcerptParser.instance.parseExcerpts(input);

        assertEquals(2, results.size());
        assertEquals("content A", results.get("A").excerpt());
        assertEquals("content B", results.get("B").excerpt());
        assertEquals("FileA.txt", results.get("A").file());
        assertEquals("FileB.txt", results.get("B").file());
    }

    @Test
    void testHandlesOptionalLanguageSpecifier() {
        String input = """
                BRK_EXCERPT_1
                file.js
                ```javascript
                const x = 1;
                ```
                """;
        Map<String, ICodeReview.CodeExcerpt> results = ReviewExcerptParser.instance.parseExcerpts(input);
        assertEquals("const x = 1;", results.get("1").excerpt());
    }

    @Test
    void testMalformedAndUnclosedBlocks() {
        String input = """
                Malformed 1: missing newline after ID
                BRK_EXCERPT_1 file.txt
                ```
                code
                ```

                Malformed 2: unclosed fence
                BRK_EXCERPT_2
                file2.txt
                ```
                unfinished code

                Valid block after malformed (with some leading whitespace):
                  BRK_EXCERPT_3
                file3.txt
                ```
                valid
                ```
                """;
        Map<String, ICodeReview.CodeExcerpt> results = ReviewExcerptParser.instance.parseExcerpts(input);

        assertEquals(1, results.size(), "Should have found exactly one valid block");
        assertTrue(results.containsKey("3"), "Should contain key '3'");
        assertEquals("valid", results.get("3").excerpt());
    }

    @Test
    void testEmptyContent() {
        String input = """
                BRK_EXCERPT_EMPTY
                empty.txt
                ```
                
                ```
                """;
        Map<String, ICodeReview.CodeExcerpt> results = ReviewExcerptParser.instance.parseExcerpts(input);
        assertEquals("", results.get("EMPTY").excerpt());
    }
}
