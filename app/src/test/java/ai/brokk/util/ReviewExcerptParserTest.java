package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ICodeReview;
import java.util.List;
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
        Map<Integer, ICodeReview.CodeExcerpt> results = ReviewExcerptParser.instance.parseExcerpts(input);

        assertEquals(1, results.size());
        ICodeReview.CodeExcerpt excerpt = results.get(1);
        assertEquals("src/main/java/Foo.java", excerpt.file());
        assertEquals("public class Foo {}", excerpt.excerpt());
    }

    @Test
    void testParseMultipleExcerpts() {
        String input = """
                BRK_EXCERPT_10
                FileA.txt
                ```
                content A
                ```

                Some text in between.

                BRK_EXCERPT_20
                FileB.txt
                ```python
                content B
                ```
                """;
        Map<Integer, ICodeReview.CodeExcerpt> results = ReviewExcerptParser.instance.parseExcerpts(input);

        assertEquals(2, results.size());
        assertEquals("content A", results.get(10).excerpt());
        assertEquals("content B", results.get(20).excerpt());
        assertEquals("FileA.txt", results.get(10).file());
        assertEquals("FileB.txt", results.get(20).file());
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
        Map<Integer, ICodeReview.CodeExcerpt> results = ReviewExcerptParser.instance.parseExcerpts(input);
        assertEquals("const x = 1;", results.get(1).excerpt());
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
        Map<Integer, ICodeReview.CodeExcerpt> results = ReviewExcerptParser.instance.parseExcerpts(input);

        assertEquals(1, results.size(), "Should have found exactly one valid block");
        assertTrue(results.containsKey(3), "Should contain key 3");
        assertEquals("valid", results.get(3).excerpt());
    }

    @Test
    void testEmptyContent() {
        String input = """
                BRK_EXCERPT_0
                empty.txt
                ```
                
                ```
                """;
        Map<Integer, ICodeReview.CodeExcerpt> results = ReviewExcerptParser.instance.parseExcerpts(input);
        assertEquals("", results.get(0).excerpt());
    }

    @Test
    void testGuidedReviewFromRaw() {
        var excerpts = Map.of(
                0, new ICodeReview.CodeExcerpt("FileA.java", "code A"),
                1, new ICodeReview.CodeExcerpt("FileB.java", "code B")
        );

        var rawDesign = new ICodeReview.RawDesignFeedback(
                "Design Issue", "Desc", List.of(0, 1), "Fix it");
        var rawTactical = new ICodeReview.RawTacticalFeedback(
                "Bug", 0, "Fix bug");

        var rawReview = new ICodeReview.RawReview(
                "Overview",
                List.of(rawDesign),
                List.of(rawTactical),
                List.of("Test more"));

        ICodeReview.GuidedReview guided = ICodeReview.GuidedReview.fromRaw(rawReview, excerpts);

        assertEquals("Overview", guided.overview());
        assertEquals(1, guided.designNotes().size());
        assertEquals(2, guided.designNotes().getFirst().excerpts().size());
        assertEquals("FileA.java", guided.designNotes().getFirst().excerpts().get(0).file());
        assertEquals("FileB.java", guided.designNotes().getFirst().excerpts().get(1).file());

        assertEquals(1, guided.tacticalNotes().size());
        assertEquals("FileA.java", guided.tacticalNotes().getFirst().excerpt().file());
        assertEquals("code A", guided.tacticalNotes().getFirst().excerpt().excerpt());
        assertEquals("Test more", guided.additionalTests().getFirst());
    }
}
