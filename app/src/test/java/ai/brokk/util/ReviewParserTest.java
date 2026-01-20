package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepoData.FileDiff;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewParserTest {

    @Test
    void testParseSingleExcerpt() {
        String input =
                """
                Here is the excerpt:
                At `src/main/java/Foo.java` line 10:
                ```java
                public class Foo {}
                ```
                """;
        List<ReviewParser.RawExcerpt> results = ReviewParser.instance.parseExcerpts(input);

        assertEquals(1, results.size());
        ReviewParser.RawExcerpt excerpt = results.getFirst();
        assertEquals("src/main/java/Foo.java", excerpt.file());
        assertEquals(10, excerpt.line());
        assertEquals("public class Foo {}", excerpt.excerpt());
    }

    @Test
    void testParseMultipleExcerpts() {
        String input =
                """
                At `FileA.txt` line 5:
                ```
                content A
                ```

                Some text in between.

                At `FileB.txt` line 15:
                ```python
                content B
                ```
                """;
        List<ReviewParser.RawExcerpt> results = ReviewParser.instance.parseExcerpts(input);

        assertEquals(2, results.size());
        ReviewParser.RawExcerpt excerptA = results.stream()
                .filter(e -> e.file().equals("FileA.txt"))
                .findFirst()
                .get();
        ReviewParser.RawExcerpt excerptB = results.stream()
                .filter(e -> e.file().equals("FileB.txt"))
                .findFirst()
                .get();
        assertEquals("content A", excerptA.excerpt());
        assertEquals(5, excerptA.line());
        assertEquals("content B", excerptB.excerpt());
        assertEquals(15, excerptB.line());
    }

    @Test
    void testHandlesOptionalLanguageSpecifier() {
        String content = "const x = 1;";
        String input =
                """
                At `file.js` line 1:
                ```javascript
                %s
                ```
                """
                        .formatted(content);
        List<ReviewParser.RawExcerpt> results = ReviewParser.instance.parseExcerpts(input);
        assertEquals(content, results.getFirst().excerpt());
    }

    @Test
    void testMalformedAndUnclosedBlocks() {
        String input =
                """
                Malformed 1: missing At line before code block
                ```
                some text
                ```

                Malformed 2: unclosed fence
                At `file2.txt` line 10:
                ```
                unfinished code

                Some intervening text to separate the blocks.

                Valid block:
                At `valid.txt` line 42:
                ```
                valid
                ```
                """;
        List<ReviewParser.RawExcerpt> results = ReviewParser.instance.parseExcerpts(input);

        assertEquals(1, results.size(), "Should have found one valid block");
        ReviewParser.RawExcerpt excerpt = results.getFirst();
        assertEquals("valid.txt", excerpt.file());
        assertEquals("valid", excerpt.excerpt());
        assertEquals(42, excerpt.line());
    }

    @Test
    void testEmptyContent() {
        String input =
                """
                At `empty.txt` line 1:
                ```

                ```
                """;
        List<ReviewParser.RawExcerpt> results = ReviewParser.instance.parseExcerpts(input);
        assertEquals("", results.getFirst().excerpt());
    }

    @Test
    void testNestedCodeFencesInContent() {
        String input =
                """
                At `nested.md` line 100:
                ```markdown
                Outer start
                  ```
                  Indented fence is content
                  ```
                Inline ``` fence is content
                Outer end
                ```
                """;
        List<ReviewParser.RawExcerpt> results = ReviewParser.instance.parseExcerpts(input);

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
        assertEquals(expected, results.getFirst().excerpt());
    }

    @Test
    void testRejectsCodeBlockWithoutAtPrefix() {
        String input =
                """
                ```java
                public class NoLine {}
                ```
                """;
        List<ReviewParser.RawExcerpt> results = ReviewParser.instance.parseExcerpts(input);
        assertTrue(results.isEmpty(), "Should reject code block without At prefix");
    }

    @Test
    void testParsesLineNumberCorrectly() {
        String input =
                """
                At `path/to/MyClass.java` line 42:
                ```java
                code here
                ```
                """;
        List<ReviewParser.RawExcerpt> results = ReviewParser.instance.parseExcerpts(input);
        assertEquals(1, results.size());
        ReviewParser.RawExcerpt excerpt = results.getFirst();
        assertEquals("path/to/MyClass.java", excerpt.file());
        assertEquals(42, excerpt.line());
        assertEquals("code here", excerpt.excerpt());
    }

    @Test
    void testGuidedReviewFromRaw() {
        var rawDesign = new ReviewParser.RawDesignFeedback("Design Issue", "Desc", List.of(0, 1), "Fix it");
        var rawTactical = new ReviewParser.RawTacticalFeedback("Bug", "Bug description", 0, "Fix bug");

        var rawReview = new ReviewParser.RawReview(
                "Overview",
                List.of(),
                List.of(rawDesign),
                List.of(rawTactical),
                List.of(new ReviewParser.TestFeedback("Test more", "Run the test")));

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
        assertEquals("Test more", guided.additionalTests().getFirst().title());
        assertEquals("Run the test", guided.additionalTests().getFirst().recommendation());
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
                At `File.java` line 10:
                ```
                code1
                ```
                Middle text.
                At `Other.java` line 20:
                ```
                code2
                ```
                Suffix text.""";

        List<ReviewParser.Segment> segments = ReviewParser.instance.parseToSegments(input);
        assertEquals(5, segments.size());

        assertTrue(segments.get(0) instanceof ReviewParser.TextSegment);
        assertEquals("Prefix text.\n", ((ReviewParser.TextSegment) segments.get(0)).text());

        assertTrue(segments.get(1) instanceof ReviewParser.ExcerptSegment);
        assertEquals("code1", ((ReviewParser.ExcerptSegment) segments.get(1)).content());

        assertTrue(segments.get(2) instanceof ReviewParser.TextSegment);
        // After an excerpt, the text segment starts with \n (from after the closing fence)
        assertTrue(((ReviewParser.TextSegment) segments.get(2)).text().contains("Middle text."));

        assertTrue(segments.get(3) instanceof ReviewParser.ExcerptSegment);
        assertEquals("code2", ((ReviewParser.ExcerptSegment) segments.get(3)).content());

        assertTrue(segments.get(4) instanceof ReviewParser.TextSegment);
        assertTrue(((ReviewParser.TextSegment) segments.get(4)).text().contains("Suffix text."));
    }

    @Test
    void testSerializationRoundTrip() {
        String input =
                """
                Text before.
                At `File.java` line 10:
                ```
                content
                ```
                Text after.""";

        List<ReviewParser.Segment> segments = ReviewParser.instance.parseToSegments(input);
        String serialized = ReviewParser.instance.serializeSegments(segments);

        assertEquals(input.trim(), serialized.trim());
    }

    @Test
    void testParseMarkdownReview_WellFormed() {
        String markdown =
                """
                ## Overview
                This is a good change.

                ## Key Changes
                ### New Logic
                Added logic for X.
                At `A.java` line 10:
                ```
                codeX
                ```

                ## Design Notes
                ### Better Abstraction
                We should use a factory here.
                At `A.java` line 1:
                ```
                code1
                ```
                **Recommendation:** Implement the factory.

                ## Tactical Notes
                ### Fix Null
                Potential NPE at
                At `B.java` line 5:
                ```
                code2
                ```
                **Recommendation:** Add a null check.

                ## Additional Tests
                ### Test Factory
                Desc
                **Recommendation:** Test the factory

                ### Test Null
                Desc
                **Recommendation:** Test null input
                """
                        .stripIndent();

        Path root = Path.of(".").toAbsolutePath().normalize();
        var resolved = Map.of(
                0,
                        new ReviewParser.CodeExcerpt(
                                new ProjectFile(root, "A.java"), null, 10, ReviewParser.DiffSide.NEW, "codeX"),
                1,
                        new ReviewParser.CodeExcerpt(
                                new ProjectFile(root, "A.java"), null, 1, ReviewParser.DiffSide.NEW, "code1"),
                2,
                        new ReviewParser.CodeExcerpt(
                                new ProjectFile(root, "B.java"), null, 5, ReviewParser.DiffSide.NEW, "code2"));

        var review = ReviewParser.instance.parseMarkdownReview(markdown, resolved);

        assertEquals("This is a good change.", review.overview());
        assertEquals(1, review.keyChanges().size());
        assertEquals("New Logic", review.keyChanges().get(0).title());
        assertEquals(1, review.keyChanges().get(0).excerpts().size());

        assertEquals(1, review.designNotes().size());
        assertEquals("Better Abstraction", review.designNotes().get(0).title());
        assertEquals("Implement the factory.", review.designNotes().get(0).recommendation());
        assertEquals(1, review.designNotes().get(0).excerpts().size());

        assertEquals(1, review.tacticalNotes().size(), "tacticalNotes should have 1 item");
        assertEquals("Fix Null", review.tacticalNotes().get(0).title());
        var tacticalExcerpt = review.tacticalNotes().get(0).excerpt();
        assertTrue(tacticalExcerpt != null);
        assertEquals("code2", tacticalExcerpt.excerpt());

        assertEquals(2, review.additionalTests().size());
        assertEquals("Test Factory", review.additionalTests().get(0).title());
        assertEquals("Test the factory", review.additionalTests().get(0).recommendation());
    }

    @Test
    void testParseMarkdownReview_TacticalWithoutExcerpt() {
        String markdown =
                """
                ## Tactical Notes
                ### General Improvement
                This is a tactical observation without a specific code link.
                **Recommendation:** Consider refactoring global state.
                """
                        .stripIndent();

        var review = ReviewParser.instance.parseMarkdownReview(markdown, Map.of());

        // Tactical notes without valid excerpts are filtered out during parsing
        assertEquals(0, review.tacticalNotes().size());
    }

    @Test
    void testParseMarkdownReview_MultiParagraphOverview() {
        String markdown =
                """
                ## Overview
                Para one.

                Para two.

                ## Design Notes
                ### Title
                Desc.
                """
                        .stripIndent();

        var review = ReviewParser.instance.parseMarkdownReview(markdown, Map.of());
        assertEquals("Para one.\n\nPara two.", review.overview());
    }

    @Test
    void testParseMarkdownReview_MissingSections() {
        String markdown = """
            ## Overview
            Minimal review.
            """;
        var review = ReviewParser.instance.parseMarkdownReview(markdown, Map.of());
        assertEquals("Minimal review.", review.overview());
        assertTrue(review.designNotes().isEmpty());
        assertTrue(review.tacticalNotes().isEmpty());
        assertTrue(review.additionalTests().isEmpty());
    }

    @Test
    void testParseMarkdownReview_MultipleExcerptsAndEdgeCases() {
        String markdown =
                """
            ## Design Notes
            ### Complex Issue
            See
            At `A.java` line 1:
            ```
            1
            ```
            and also
            At `B.java` line 1:
            ```
            2
            ```

            Some more text.
            **Recommendation:** Fix both.

            ### Empty Note
            Some description.
            **Recommendation:** Do nothing.
            """;

        Path root = Path.of(".").toAbsolutePath().normalize();
        var resolved = Map.of(
                0,
                        new ReviewParser.CodeExcerpt(
                                new ProjectFile(root, "A.java"), null, 1, ReviewParser.DiffSide.NEW, "1"),
                1,
                        new ReviewParser.CodeExcerpt(
                                new ProjectFile(root, "B.java"), null, 1, ReviewParser.DiffSide.NEW, "2"));

        var review = ReviewParser.instance.parseMarkdownReview(markdown, resolved);
        assertEquals(2, review.designNotes().size());
        assertEquals(2, review.designNotes().get(0).excerpts().size());
        assertEquals("Fix both.", review.designNotes().get(0).recommendation());
        assertEquals("Do nothing.", review.designNotes().get(1).recommendation());
    }

    @Test
    void testFromRawCleansExcerptsFromFields() {
        // The cleanMetadata method replaces escaped newlines (\n literal) with actual newlines
        var rawDesign = new ReviewParser.RawDesignFeedback(
                "Title", "Description with\\ncode block and more text.", List.of(0), "Recommendation with\\nmore");

        var rawReview = new ReviewParser.RawReview("Overview", List.of(), List.of(rawDesign), List.of(), List.of());

        Path root = Path.of(".").toAbsolutePath().normalize();
        var resolvedExcerpts = Map.of(
                0,
                new ReviewParser.CodeExcerpt(
                        new ProjectFile(root, "File.java"), null, 1, ReviewParser.DiffSide.NEW, "code"));

        ReviewParser.GuidedReview guided = ReviewParser.GuidedReview.fromRaw(rawReview, resolvedExcerpts);
        ReviewParser.DesignFeedback design = guided.designNotes().getFirst();

        assertEquals("Description with\ncode block and more text.", design.description());
        assertEquals("Recommendation with\nmore", design.recommendation());
    }

    @Test
    void testParseMarkdownReview_RecommendationMidSentence_NotMatched() {
        // "Recommendation:" appearing mid-sentence should not be treated as the recommendation delimiter
        String markdown =
                """
            ## Design Notes
            ### Naming Issue
            The variable name contains Recommendation: suffix which is confusing.
            **Recommendation:** Rename the variable to something clearer.
            """;

        Path root = Path.of(".").toAbsolutePath().normalize();
        var review = ReviewParser.instance.parseMarkdownReview(markdown, Map.of());

        // The description should include "contains Recommendation: suffix"
        // The actual recommendation should be "Rename the variable to something clearer."
        assertEquals(1, review.designNotes().size());
        assertTrue(review.designNotes().get(0).description().contains("contains Recommendation: suffix"));
        assertEquals(
                "Rename the variable to something clearer.",
                review.designNotes().get(0).recommendation());
    }

    @Test
    void testParseMarkdownReview_RecommendationInCodeBlock_NotMatched() {
        // "Recommendation:" appearing in code excerpts should not be treated as delimiter
        // Note: The content in the code block is parsed by flexmark, which may handle quotes differently
        String content = "String key = \"Recommendation:\";";
        String markdown =
                """
            ## Tactical Notes
            ### Config Issue
            The code has a hardcoded string:
            At `config.java` line 10:
            ```java
            %s
            ```
            **Recommendation:** Use a constant instead.
            """
                        .formatted(content);

        Path root = Path.of(".").toAbsolutePath().normalize();
        var resolved = Map.of(
                0,
                new ReviewParser.CodeExcerpt(
                        new ProjectFile(root, "config.java"), null, 10, ReviewParser.DiffSide.NEW, content));

        var review = ReviewParser.instance.parseMarkdownReview(markdown, resolved);

        assertEquals(1, review.tacticalNotes().size());
        // The recommendation should only be the actual recommendation, not the code
        assertEquals("Use a constant instead.", review.tacticalNotes().get(0).recommendation());
    }

    @Test
    void testParseMarkdownReview_BoldRecommendationFormat() {
        // The expected format uses bold: **Recommendation:**
        String markdown =
                """
            ## Design Notes
            ### API Design
            The API is inconsistent.
            **Recommendation:** Refactor to use builder pattern.

            ### Error Handling
            Missing error cases.
            **Recommendation:** Add try-catch blocks.
            """;

        var review = ReviewParser.instance.parseMarkdownReview(markdown, Map.of());

        assertEquals(2, review.designNotes().size());
        assertEquals(
                "Refactor to use builder pattern.", review.designNotes().get(0).recommendation());
        assertEquals("Add try-catch blocks.", review.designNotes().get(1).recommendation());
    }

    @Test
    void testParseMarkdownReview_NonBoldRecommendationIgnored() {
        // Plain "Recommendation:" without bold should not be the delimiter when bold version exists
        String markdown =
                """
            ## Design Notes
            ### Mixed Format
            The method has a Recommendation: comment in the code.
            **Recommendation:** Remove the outdated comment.
            """;

        var review = ReviewParser.instance.parseMarkdownReview(markdown, Map.of());

        assertEquals(1, review.designNotes().size());
        assertTrue(review.designNotes().get(0).description().contains("Recommendation: comment"));
        assertEquals("Remove the outdated comment.", review.designNotes().get(0).recommendation());
    }

    @Test
    void testParseMarkdownReview_PreservesFormatting() {
        String markdown =
                """
            ## Design Notes
            ### Formatting Test
            This description has **bold**, *italic*, `code`, and [links](http://example.com).
            **Recommendation:** Fix the `code` and check the [link](http://test.com).
            """
                        .stripIndent();

        var review = ReviewParser.instance.parseMarkdownReview(markdown, Map.of());

        assertEquals(1, review.designNotes().size());
        ReviewParser.DesignFeedback note = review.designNotes().get(0);
        assertEquals(
                "This description has **bold**, *italic*, `code`, and [links](http://example.com).",
                note.description());
        assertEquals("Fix the `code` and check the [link](http://test.com).", note.recommendation());
    }

    @Test
    void testParseMarkdownReview_FiltersExcerptMetadata() {
        // When an excerpt is included in a note, the "At `filepath` line N:" should not appear in description
        String markdown =
                """
                ## Tactical Notes
                ### Filter Metadata
                Here is the problem:

                At `src/main/java/App.java` line 50:
                ```java
                code
                ```

                The code above is bad.
                **Recommendation:** Fix it.
                """;

        Path root = Path.of(".").toAbsolutePath().normalize();
        var resolved = Map.of(
                0,
                new ReviewParser.CodeExcerpt(
                        new ProjectFile(root, "src/main/java/App.java"), null, 50, ReviewParser.DiffSide.NEW, "code"));

        var review = ReviewParser.instance.parseMarkdownReview(markdown, resolved);

        assertEquals(1, review.tacticalNotes().size(), "Should have one tactical note");
        String description = review.tacticalNotes().get(0).description();
        assertTrue(description.contains("problem"), "Should contain intro text");
        assertTrue(description.contains("bad"), "Should contain text after excerpt");
        assertFalse(
                description.contains("At `src/main/java/App.java` line 50:"), "Should NOT contain the metadata line");
    }

    @Test
    void testParseMarkdownReview_UnclosedCodeBlock() {
        String markdown =
                """
                ## Design Notes
                ### Some Issue
                Description here.
                ```java
                public void method() {
                    // code without closing fence
                **Recommendation:** Fix it.
                """;

        // Should not throw exception and should extract what it can.
        // flexmark usually treats the rest of the document as part of the code block if unclosed.
        var review = ReviewParser.instance.parseMarkdownReview(markdown, Map.of());

        assertEquals(1, review.designNotes().size());
        assertEquals("Some Issue", review.designNotes().getFirst().title());
    }

    @Test
    void testParseMarkdownReview_MissingTopLevelHeaders() {
        String markdown =
                """
                ### Orphaned Note
                This note is not under a ## section.
                **Recommendation:** Ignore or handle gracefully.
                """;

        var review = ReviewParser.instance.parseMarkdownReview(markdown, Map.of());

        // Since the parser relies on currentTopLevelSection, notes without a ## header are ignored.
        assertTrue(review.designNotes().isEmpty());
        assertTrue(review.tacticalNotes().isEmpty());
    }

    @Test
    void testParseMarkdownReview_MalformedHeaders() {
        String markdown =
                """
                ##Design Notes
                ### Spaced Header
                Desc.
                **Recommendation:** Fix spacing.

                ## Tactical Notes
                #### Deep Header
                Too deep.
                **Recommendation:** Shallow up.
                """;

        var review = ReviewParser.instance.parseMarkdownReview(markdown, Map.of());

        // "##Design Notes" is typically not recognized as a header by CommonMark (requires space).
        // "#### Deep Header" is level 4, while the parser looks for level 3 for notes.
        assertTrue(review.designNotes().isEmpty());
        assertTrue(review.tacticalNotes().isEmpty());
    }

    @Test
    void testParseMarkdownReview_CodeBlockInExcerptArea() {
        // Test handling when a code block has proper At prefix format
        String markdown =
                """
                ## Tactical Notes
                ### Valid Excerpt
                At `File.java` line 1:
                ```java
                code
                ```
                **Recommendation:** Fix formatting.
                """;

        Path root = Path.of(".").toAbsolutePath().normalize();
        var resolved = Map.of(
                0,
                new ReviewParser.CodeExcerpt(
                        new ProjectFile(root, "File.java"), null, 1, ReviewParser.DiffSide.NEW, "code"));

        var review = ReviewParser.instance.parseMarkdownReview(markdown, resolved);

        assertEquals(1, review.tacticalNotes().size());
        assertEquals("Valid Excerpt", review.tacticalNotes().getFirst().title());
    }

    @Test
    void testSectionRoundTrip() {
        String markdown =
                """
                ## Overview
                This is the overview.

                ## Design Notes
                ### Title 1
                Content 1

                ### Title 2
                Content 2

                ## Tactical Notes
                ### Title 3
                Content 3
                """
                        .stripIndent();

        List<ReviewParser.Section> sections = ReviewParser.instance.parseIntoSections(markdown);
        // Sections: Overview, Title 1, Title 2, Title 3
        assertEquals(4, sections.size());
        assertEquals("Overview", sections.get(0).title());
        assertEquals("Title 1", sections.get(1).title());
        assertEquals("Title 2", sections.get(2).title());
        assertEquals("Title 3", sections.get(3).title());

        String serialized = ReviewParser.instance.serializeSections(sections);

        String expected =
                """
                ## Overview
                This is the overview.

                ## Design Notes
                ### Title 1
                Content 1

                ### Title 2
                Content 2

                ## Tactical Notes
                ### Title 3
                Content 3
                """
                        .stripIndent();

        assertEquals(expected.trim(), serialized.trim());
    }

    @Test
    void testParseNumberedExcerpts() {
        String input =
                """
                I've fixed those excerpts for you:

                Excerpt 0:
                At `File1.java` line 10:
                ```java
                code 1
                ```

                Excerpt 2:
                Some commentary here.
                At `File2.java` line 20:
                ```
                code 2
                ```
                """;

        Map<Integer, ReviewParser.RawExcerpt> results = ReviewParser.instance.parseNumberedExcerpts(input);

        assertEquals(2, results.size());
        assertEquals("File1.java", results.get(0).file());
        assertEquals("code 1", results.get(0).excerpt());
        assertEquals("File2.java", results.get(2).file());
        assertEquals("code 2", results.get(2).excerpt());
    }

    @Test
    void testParseLiveReviewLog() throws IOException {
        Path resourcePath = Path.of("src/test/resources/reviews/jbe2.log");
        String markdown = Files.readString(resourcePath);

        List<ReviewParser.RawExcerpt> raws = ReviewParser.instance.parseExcerpts(markdown);
        assertEquals(7, raws.size(), "jbe2.log should contain 7 excerpts");

        // Spot check a few stable excerpt headers to ensure ordering and parsing are correct.
        assertEquals(
                "errorprone-checks/src/main/java/ai/brokk/errorprone/BlockingOperationChecker.java",
                raws.get(0).file());
        assertEquals(47, raws.get(0).line());

        assertEquals(
                "errorprone-checks/src/main/java/ai/brokk/errorprone/TypeHierarchyUtils.java",
                raws.get(1).file());
        assertEquals(35, raws.get(1).line());

        assertEquals(
                "errorprone-checks/src/main/java/ai/brokk/errorprone/BlockingOperationChecker.java",
                raws.get(2).file());
        assertEquals(83, raws.get(2).line());

        // Later excerpts are more likely to change ordering as the log evolves; avoid brittle index checks.
        var chromeExcerpt = raws.stream()
                .filter(r -> r.file().equals("app/src/main/java/ai/brokk/gui/Chrome.java"))
                .findFirst()
                .orElseThrow();
        assertEquals(2493, chromeExcerpt.line());

        // "resolve" the raw excerpts by assuming their file and line information are correct
        Path root = Path.of(".").toAbsolutePath().normalize();
        Map<Integer, ReviewParser.CodeExcerpt> resolved = new HashMap<>();
        for (int i = 0; i < raws.size(); i++) {
            ReviewParser.RawExcerpt raw = raws.get(i);
            resolved.put(
                    i,
                    new ReviewParser.CodeExcerpt(
                            new ProjectFile(root, raw.file()),
                            null,
                            raw.line(),
                            ReviewParser.DiffSide.NEW,
                            raw.excerpt()));
        }

        ReviewParser.GuidedReview review = ReviewParser.instance.parseMarkdownReview(markdown, resolved);

        assertFalse(review.overview().isBlank(), "Overview should not be blank");
        assertTrue(
                review.overview().contains("BlockingOperationChecker"),
                "Overview should mention BlockingOperationChecker");

        assertEquals(3, review.keyChanges().size(), "Key Changes should have 3 notes");
        assertEquals(1, review.designNotes().size(), "Design Notes should have 1 note");
        assertEquals(2, review.tacticalNotes().size(), "Tactical Notes should have 2 notes");
        assertEquals(2, review.additionalTests().size(), "Additional Tests should have 2 items");

        // Key Changes: exactly 1 excerpt per note, and wired up via resolved map.
        for (int i = 0; i < review.keyChanges().size(); i++) {
            var keyChange = review.keyChanges().get(i);
            assertEquals(1, keyChange.excerpts().size(), "Each Key Change should have exactly 1 excerpt");

            assertEquals(
                    raws.get(i).file(),
                    keyChange.excerpts().getFirst().file().getRelPath().toString(),
                    "Key Change excerpt file should match raw excerpt order");
            assertEquals(
                    raws.get(i).line(),
                    keyChange.excerpts().getFirst().line(),
                    "Key Change excerpt line should match raw excerpt order");
        }

        // Design Notes: the single note in jbe2.log has two excerpts.
        assertEquals(2, review.designNotes().getFirst().excerpts().size(), "Design note should have 2 excerpts");

        // Spot check that recommendations were parsed.
        assertTrue(
                review.designNotes().getFirst().recommendation().contains("Remove"),
                "Design note recommendation should be parsed");
        assertTrue(
                review.additionalTests().getFirst().recommendation().contains("Add a test"),
                "Additional test recommendation should be parsed");
    }

    @Test
    void testValidateParsedNotes() {
        // 1. Well-formed review
        String validMarkdown =
                """
                ## Design Notes
                ### D1
                Desc
                **Recommendation:** Rec

                ## Tactical Notes
                ### T1
                Desc
                At `file.java` line 1:
                ```
                code
                ```
                **Recommendation:** Rec
                """
                        .stripIndent();

        List<ReviewParser.NoteValidationError> errors1 = ReviewParser.instance.validateParsedNotes(validMarkdown);
        assertTrue(errors1.isEmpty(), "Should have no errors for valid review");

        // 2. Malformed review
        String invalidMarkdown =
                """
                ## Design Notes
                ### Bad Design
                Desc
                **Recommendation:**

                ## Tactical Notes
                ### Missing Excerpt
                Desc
                **Recommendation:** Fix it

                ### Empty Rec
                At `file.java` line 2:
                ```
                code
                ```
                **Recommendation:**

                ### Missing Rec Marker
                At `file.java` line 3:
                ```
                code
                ```
                Just text.
                """
                        .stripIndent();

        List<ReviewParser.NoteValidationError> errors2 = ReviewParser.instance.validateParsedNotes(invalidMarkdown);
        assertEquals(4, errors2.size());

        assertTrue(errors2.stream()
                .anyMatch(e -> e.title().equals("Bad Design") && e.message().contains("empty recommendation")));
        assertTrue(errors2.stream()
                .anyMatch(
                        e -> e.title().equals("Missing Excerpt") && e.message().contains("At `filepath` line N:")));
        assertTrue(errors2.stream()
                .anyMatch(e -> e.title().equals("Empty Rec") && e.message().contains("empty recommendation")));
        assertTrue(errors2.stream()
                .anyMatch(e -> e.title().equals("Missing Rec Marker")
                        && e.message().contains("missing a **Recommendation:**")));
    }

    @Test
    void testFindClosingFenceTerminationWithPathologicalInput() {
        // Construct a large input where code blocks never close properly
        StringBuilder sb = new StringBuilder();
        sb.append("At `File.java` line 10:\n");
        sb.append("```java\n");
        for (int i = 0; i < 2000; i++) {
            sb.append("content line ").append(i).append("\n");
        }
        // No closing fence

        // This should terminate quickly due to maxLookahead
        List<ReviewParser.Segment> segments = ReviewParser.instance.parseToSegments(sb.toString());

        // If it terminated correctly, all content will be text because the
        // code block never found a valid closing fence within the lookahead.
        assertFalse(segments.isEmpty());
        assertInstanceOf(ReviewParser.TextSegment.class, segments.getFirst());
    }

    @Test
    void testCodeBlockWithoutAtPrefixIsNotExcerpt() {
        String input =
                """
                ```java
                content
                ```
                Some text
                At `Source.java` line 1:
                ```
                real excerpt
                ```
                """;
        // Code block without At prefix should not be parsed as excerpt
        List<ReviewParser.Segment> segments = ReviewParser.instance.parseToSegments(input);

        // Should have one excerpt (the one with At prefix)
        long excerptCount = segments.stream()
                .filter(s -> s instanceof ReviewParser.ExcerptSegment)
                .count();
        assertEquals(1, excerptCount);

        ReviewParser.ExcerptSegment excerpt = (ReviewParser.ExcerptSegment) segments.stream()
                .filter(s -> s instanceof ReviewParser.ExcerptSegment)
                .findFirst()
                .orElseThrow();

        assertEquals("Source.java", excerpt.file());
        assertEquals("real excerpt", excerpt.content());
    }

    @Test
    void testMatchExcerptInFile() {
        Path root = Path.of(".").toAbsolutePath().normalize();
        ProjectFile file = new ProjectFile(root, "test.java");
        var diff = new FileDiff(file, file, "line1\nline2\nline3", "line1\nline2-new\nline3");

        // Match in NEW
        var excerptNew = new ReviewParser.RawExcerpt("test.java", 2, "line2-new");
        ReviewParser.ExcerptMatch matchNew = ReviewParser.matchExcerptInFile(excerptNew, diff);
        assertNotNull(matchNew);
        assertEquals(2, matchNew.line());
        assertEquals(ReviewParser.DiffSide.NEW, matchNew.side());

        // Match in OLD (not in new)
        var excerptOld = new ReviewParser.RawExcerpt("test.java", 2, "line2");
        ReviewParser.ExcerptMatch matchOld = ReviewParser.matchExcerptInFile(excerptOld, diff);
        assertNotNull(matchOld);
        assertEquals(2, matchOld.line());
        assertEquals(ReviewParser.DiffSide.OLD, matchOld.side());

        // Whitespace insensitive
        var excerptWS = new ReviewParser.RawExcerpt("test.java", 1, "  line1  ");
        ReviewParser.ExcerptMatch matchWS = ReviewParser.matchExcerptInFile(excerptWS, diff);
        assertNotNull(matchWS);
        assertEquals(1, matchWS.line());

        // No match
        var excerptNone = new ReviewParser.RawExcerpt("test.java", 1, "garbage");
        assertNull(ReviewParser.matchExcerptInFile(excerptNone, diff));

        // Multi-line match
        ProjectFile multiFile = new ProjectFile(root, "multi.java");
        var multiDiff = new FileDiff(multiFile, multiFile, "a\nb\nc\nd\ne", "a\nb\nc\nd\ne");
        var multiExcerpt = new ReviewParser.RawExcerpt("multi.java", 3, "b\nc\nd");
        ReviewParser.ExcerptMatch multiMatch = ReviewParser.matchExcerptInFile(multiExcerpt, multiDiff);
        assertNotNull(multiMatch);
        assertEquals(2, multiMatch.line()); // Starts at line 2
        assertEquals("b\nc\nd", multiMatch.matchedText());
    }

    @Test
    void testMatchExcerptInFile_emptyAndFull() {
        Path root = Path.of(".").toAbsolutePath().normalize();
        ProjectFile file = new ProjectFile(root, "empty.java");
        var diff = new FileDiff(file, file, "", "");
        var excerpt = new ReviewParser.RawExcerpt("empty.java", 1, "content");

        assertNull(ReviewParser.matchExcerptInFile(excerpt, diff));

        // Excerpt spans entire file
        var content = "line1\nline2";
        ProjectFile fullFile = new ProjectFile(root, "full.java");
        var fullDiff = new FileDiff(fullFile, fullFile, content, content);
        var fullExcerpt = new ReviewParser.RawExcerpt("full.java", 1, content);

        ReviewParser.ExcerptMatch match = ReviewParser.matchExcerptInFile(fullExcerpt, fullDiff);
        assertNotNull(match);
        assertEquals(1, match.line());
    }

    @Test
    void testTagExcerpts() {
        String input =
                """
                Some text.
                At `File.java` line 10:
                ```
                code
                ```
                More text.
                At `Other.java` line 20:
                ```
                code2
                ```
                """;
        String tagged = ReviewParser.instance.tagExcerpts(input);
        String expected =
                """
                Some text.
                [Excerpt 0]
                At `File.java` line 10:
                ```
                code
                ```
                More text.
                [Excerpt 1]
                At `Other.java` line 20:
                ```
                code2
                ```
                """;
        assertEquals(expected.trim(), tagged.trim());
    }

    @Test
    void testMatchExcerptInContent() {
        String content = "line1\nline2\nline3";

        // Exact match
        var excerpt1 = new ReviewParser.RawExcerpt("test.java", 2, "line2");
        var match1 = ReviewParser.matchExcerptInContent(excerpt1, content).orElse(null);
        assertNotNull(match1);
        assertEquals(2, match1.line());
        assertEquals("line2", match1.matchedText());

        // Whitespace insensitive
        var excerpt2 = new ReviewParser.RawExcerpt("test.java", 1, "  line1  ");
        var match2 = ReviewParser.matchExcerptInContent(excerpt2, content).orElse(null);
        assertNotNull(match2);
        assertEquals(1, match2.line());

        // No match
        var excerpt3 = new ReviewParser.RawExcerpt("test.java", 1, "garbage");
        assertTrue(ReviewParser.matchExcerptInContent(excerpt3, content).isEmpty());

        // Multi-line match
        var multiContent = "a\nb\nc\nd\ne";
        var multiExcerpt = new ReviewParser.RawExcerpt("test.java", 3, "b\nc\nd");
        var multiMatch =
                ReviewParser.matchExcerptInContent(multiExcerpt, multiContent).orElse(null);
        assertNotNull(multiMatch);
        assertEquals(2, multiMatch.line());
        assertEquals("b\nc\nd", multiMatch.matchedText());
    }
}
