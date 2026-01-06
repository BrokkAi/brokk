package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewParserTest {

    @Test
    void testParseSingleExcerpt() {
        String input = """
                Here is the excerpt:
                BRK_EXCERPT_1
                src/main/java/Foo.java @10
                ```java
                public class Foo {}
                ```
                """;
        Map<Integer, ReviewParser.RawExcerpt> results = ReviewParser.instance.parseExcerpts(input);

        assertEquals(1, results.size());
        ReviewParser.RawExcerpt excerpt = results.get(1);
        assertEquals("src/main/java/Foo.java", excerpt.file());
        assertEquals(10, excerpt.line());
        assertEquals("public class Foo {}", excerpt.excerpt());
    }

    @Test
    void testParseMultipleExcerpts() {
        String input = """
                BRK_EXCERPT_10
                FileA.txt @5
                ```
                content A
                ```

                Some text in between.

                BRK_EXCERPT_20
                FileB.txt @15
                ```python
                content B
                ```
                """;
        Map<Integer, ReviewParser.RawExcerpt> results = ReviewParser.instance.parseExcerpts(input);

        assertEquals(2, results.size());
        assertEquals("content A", results.get(10).excerpt());
        assertEquals(5, results.get(10).line());
        assertEquals("content B", results.get(20).excerpt());
        assertEquals(15, results.get(20).line());
        assertEquals("FileA.txt", results.get(10).file());
        assertEquals("FileB.txt", results.get(20).file());
    }

    @Test
    void testHandlesOptionalLanguageSpecifier() {
        String input = """
                BRK_EXCERPT_1
                file.js @1
                ```javascript
                const x = 1;
                ```
                """;
        Map<Integer, ReviewParser.RawExcerpt> results = ReviewParser.instance.parseExcerpts(input);
        assertEquals("const x = 1;", results.get(1).excerpt());
    }

    @Test
    void testMalformedAndUnclosedBlocks() {
        String input = """
                Malformed 1: missing newline after ID
                BRK_EXCERPT_1 file.txt @10
                ```
                code
                ```

                Malformed 2: unclosed fence
                BRK_EXCERPT_2
                file2.txt @10
                ```
                unfinished code

                Malformed 3: missing @line
                BRK_EXCERPT_4
                file4.txt
                ```
                missing line
                ```

                Valid block after malformed (with some leading whitespace):
                  BRK_EXCERPT_3
                file3.txt @42
                ```
                valid
                ```
                """;
        Map<Integer, ReviewParser.RawExcerpt> results = ReviewParser.instance.parseExcerpts(input);

        assertEquals(1, results.size(), "Should have found exactly one valid block");
        assertTrue(results.containsKey(3), "Should contain key 3");
        assertEquals("valid", results.get(3).excerpt());
    }

    @Test
    void testEmptyContent() {
        String input = """
                BRK_EXCERPT_0
                empty.txt @1
                ```
                
                ```
                """;
        Map<Integer, ReviewParser.RawExcerpt> results = ReviewParser.instance.parseExcerpts(input);
        assertEquals("", results.get(0).excerpt());
    }

    @Test
    void testNestedCodeFencesInContent() {
        String input = """
                BRK_EXCERPT_5
                nested.md @100
                ```markdown
                Outer start
                  ```
                  Indented fence is content
                  ```
                Inline ``` fence is content
                Outer end
                ```
                """;
        Map<Integer, ReviewParser.RawExcerpt> results = ReviewParser.instance.parseExcerpts(input);

        assertEquals(1, results.size());
        String expected = """
                Outer start
                  ```
                  Indented fence is content
                  ```
                Inline ``` fence is content
                Outer end""".stripIndent();
        assertEquals(expected, results.get(5).excerpt());
    }

    @Test
    void testRejectsFilenameWithoutLineNumber() {
        String input = """
                BRK_EXCERPT_1
                src/main/java/NoLine.java
                ```java
                public class NoLine {}
                ```
                """;
        Map<Integer, ReviewParser.RawExcerpt> results = ReviewParser.instance.parseExcerpts(input);
        assertTrue(results.isEmpty(), "Should reject excerpt without @line");
    }

    @Test
    void testParsesLineNumberCorrectly() {
        String input = """
                BRK_EXCERPT_1
                path/to/MyClass.java @42
                ```java
                code here
                ```
                """;
        Map<Integer, ReviewParser.RawExcerpt> results = ReviewParser.instance.parseExcerpts(input);
        assertEquals(1, results.size());
        ReviewParser.RawExcerpt excerpt = results.get(1);
        assertEquals("path/to/MyClass.java", excerpt.file());
        assertEquals(42, excerpt.line());
        assertEquals("code here", excerpt.excerpt());
    }

    @Test
    void testGuidedReviewFromRaw() {
        var contents = Map.of(
                0, "code A",
                1, "code B"
        );
        var files = Map.of(
                0, "FileA.java",
                1, "FileB.java"
        );

        var rawDesign = new ReviewParser.RawDesignFeedback(
                "Design Issue", "Desc", List.of(0, 1), "Fix it");
        var rawTactical = new ReviewParser.RawTacticalFeedback(
                "Bug", 0, "Fix bug");

        var rawReview = new ReviewParser.RawReview(
                "Overview",
                List.of(rawDesign),
                List.of(rawTactical),
                List.of("Test more"));

        ReviewParser.GuidedReview guided = ReviewParser.GuidedReview.fromRaw(
                rawReview,
                contents,
                files,
                (f, c) -> new ReviewParser.CodeExcerpt(f, 1, ReviewParser.DiffSide.NEW, c));

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
