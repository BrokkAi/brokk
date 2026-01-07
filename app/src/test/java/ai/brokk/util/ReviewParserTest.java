package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ProjectFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewParserTest {

    @Test
    void testParseSingleExcerpt() {
        String input =
                """
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
        String input =
                """
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
        String input =
                """
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
        String input =
                """
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
        String input =
                """
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
        String input =
                """
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
        String expected =
                """
                Outer start
                  ```
                  Indented fence is content
                  ```
                Inline ``` fence is content
                Outer end"""
                        .stripIndent();
        assertEquals(expected, results.get(5).excerpt());
    }

    @Test
    void testRejectsFilenameWithoutLineNumber() {
        String input =
                """
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
        String input =
                """
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
                1, "code B");
        var files = Map.of(
                0, "FileA.java",
                1, "FileB.java");

        var rawDesign = new ReviewParser.RawDesignFeedback("Design Issue", "Desc", List.of(0, 1), "Fix it");
        var rawTactical = new ReviewParser.RawTacticalFeedback("Bug", "Bug description", 0, "Fix bug");

        var rawReview =
                new ReviewParser.RawReview("Overview", List.of(rawDesign), List.of(rawTactical), List.of("Test more"));

        Path root = Path.of(".").toAbsolutePath().normalize();
        var resolvedExcerpts = Map.of(
                0,
                        new ReviewParser.CodeExcerpt(
                                new ProjectFile(root, "FileA.java"), null, 1, ReviewParser.DiffSide.NEW, "code A"),
                1,
                        new ReviewParser.CodeExcerpt(
                                new ProjectFile(root, "FileB.java"), null, 1, ReviewParser.DiffSide.NEW, "code B"));

        ReviewParser.GuidedReview guided = ReviewParser.GuidedReview.fromRaw(rawReview, resolvedExcerpts);

        assertEquals("Overview", guided.overview());
        assertEquals(1, guided.designNotes().size());
        assertEquals(2, guided.designNotes().getFirst().excerpts().size());
        assertEquals(
                "FileA.java",
                guided.designNotes()
                        .getFirst()
                        .excerpts()
                        .get(0)
                        .file()
                        .getRelPath()
                        .toString());
        assertEquals(
                "FileB.java",
                guided.designNotes()
                        .getFirst()
                        .excerpts()
                        .get(1)
                        .file()
                        .getRelPath()
                        .toString());

        assertEquals(1, guided.tacticalNotes().size());
        assertEquals(
                "FileA.java",
                guided.tacticalNotes().getFirst().excerpt().file().getRelPath().toString());
        assertEquals("code A", guided.tacticalNotes().getFirst().excerpt().excerpt());
        assertEquals("Test more", guided.additionalTests().getFirst());
    }

    @Test
    void testFindBestMatch() {
        var matches = List.of(
                new WhitespaceMatch(10, "line 11"),
                new WhitespaceMatch(20, "line 21"),
                new WhitespaceMatch(30, "line 31"));

        // Exact match
        assertEquals(10, ReviewParser.findBestMatch(matches, 11).startLine());

        // Target line before all matches
        assertEquals(10, ReviewParser.findBestMatch(matches, 5).startLine());

        // Target line after all matches
        assertEquals(30, ReviewParser.findBestMatch(matches, 50).startLine());

        // Closer to middle
        assertEquals(20, ReviewParser.findBestMatch(matches, 22).startLine());

        // Closer to end
        assertEquals(30, ReviewParser.findBestMatch(matches, 28).startLine());

        // Tie-breaker (first one wins)
        assertEquals(10, ReviewParser.findBestMatch(matches, 16).startLine());
    }

    @Test
    void testFindBestMatch_additionalEdgeCases() {
        var matches = List.of(new WhitespaceMatch(10, "line 11"), new WhitespaceMatch(20, "line 21"));

        // Single match
        var single = List.of(new WhitespaceMatch(5, "only"));
        assertEquals(5, ReviewParser.findBestMatch(single, 100).startLine());

        // Target line before all matches
        assertEquals(10, ReviewParser.findBestMatch(matches, 1).startLine());

        // Target line after all matches
        assertEquals(20, ReviewParser.findBestMatch(matches, 30).startLine());
    }

    @Test
    void testParseToSegmentsInterleaved() {
        String input =
                """
                Prefix text.
                BRK_EXCERPT_1
                File.java @10
                ```
                code1
                ```
                Middle text.
                BRK_EXCERPT_2
                Other.java @20
                ```
                code2
                ```
                Suffix text.""";

        List<ReviewParser.Segment> segments = ReviewParser.instance.parseToSegments(input);
        assertEquals(5, segments.size());

        assertTrue(segments.get(0) instanceof ReviewParser.TextSegment);
        assertEquals("Prefix text.\n", ((ReviewParser.TextSegment) segments.get(0)).text());

        assertTrue(segments.get(1) instanceof ReviewParser.ExcerptSegment);
        assertEquals(1, ((ReviewParser.ExcerptSegment) segments.get(1)).id());
        assertEquals("code1", ((ReviewParser.ExcerptSegment) segments.get(1)).content());

        assertTrue(segments.get(2) instanceof ReviewParser.TextSegment);
        assertEquals("\nMiddle text.\n", ((ReviewParser.TextSegment) segments.get(2)).text());

        assertTrue(segments.get(3) instanceof ReviewParser.ExcerptSegment);
        assertEquals(2, ((ReviewParser.ExcerptSegment) segments.get(3)).id());

        assertTrue(segments.get(4) instanceof ReviewParser.TextSegment);
        assertEquals("\nSuffix text.", ((ReviewParser.TextSegment) segments.get(4)).text());
    }

    @Test
    void testSerializationRoundTrip() {
        String input =
                """
                Text before.
                BRK_EXCERPT_1
                File.java @10
                ```
                content
                ```
                Text after.""";

        List<ReviewParser.Segment> segments = ReviewParser.instance.parseToSegments(input);
        String serialized = ReviewParser.instance.serializeSegments(segments);

        // We compare normalized/trimmed because the serializer might vary slightly in trailing newlines
        // depending on how the input was formatted.
        assertEquals(input.trim(), serialized.trim());
    }
}
