package io.github.jbellis.brokk.gui.mop.stream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link TextNodeMarkerCustomizer}.
 */
public class TextNodeMarkerCustomizerTest {

    // Helper method to apply customizer and return the resulting body
    private Element applyCustomizer(String html, String term, boolean caseSensitive, boolean wholeWord) {
        Document doc = Jsoup.parseBodyFragment(html);
        Element body = doc.body();

        HtmlCustomizer customizer = new TextNodeMarkerCustomizer(
            term, caseSensitive, wholeWord, "<mark>", "</mark>"
        );
        customizer.customize(body);

        return body;
    }

    // Helper to count marks in HTML
    private int countMarks(Element body) {
        return body.select("mark").size();
    }

    @Test
    public void testBasicWholeWordMatching() {
        String html = "<p>This is a test. Testing the test functionality.</p>";
        Element result = applyCustomizer(html, "test", false, true);

        assertEquals(2, countMarks(result), "Should match 'test' as whole word twice");

        // Verify exact matches
        var marks = result.select("mark");
        marks.forEach(mark -> {
            assertEquals("test", mark.text().toLowerCase());
            assertTrue(mark.hasAttr("data-brokk-marker"));
            assertTrue(mark.hasAttr("data-brokk-id"));
            assertTrue(mark.attr("data-brokk-id").matches("\\d+"));
        });
    }

    @Test
    public void testCaseSensitiveMatching() {
        String html = "<p>Test test TEST</p>";

        // Case-sensitive search for "test"
        Element caseSensitive = applyCustomizer(html, "test", true, false);
        assertEquals(1, countMarks(caseSensitive), "Case-sensitive should match only 'test'");
        assertEquals("test", caseSensitive.select("mark").first().text());

        // Case-insensitive search for "test"
        Element caseInsensitive = applyCustomizer(html, "test", false, false);
        assertEquals(3, countMarks(caseInsensitive), "Case-insensitive should match all variants");
    }

    @Test
    public void testPartialWordMatching() {
        String html = "<p>Testing tested tester test</p>";

        // Whole word matching
        Element wholeWord = applyCustomizer(html, "test", false, true);
        assertEquals(1, countMarks(wholeWord), "Whole word should match only 'test'");

        // Partial word matching
        Element partial = applyCustomizer(html, "test", false, false);
        assertEquals(4, countMarks(partial), "Partial should match all occurrences");
    }

    @ParameterizedTest
    @ValueSource(strings = {"script", "style"})
    public void testSkipProtectedTags(String tagName) {
        String html = String.format("<p>test</p><%s>test</%s>", tagName, tagName);
        Element result = applyCustomizer(html, "test", false, true);

        assertEquals(1, countMarks(result), "Should only match outside " + tagName);
        assertEquals(0, result.select(tagName + " mark").size(),
            "Should not match inside " + tagName);
    }

    @Test
    public void testSkipImageTag() {
        // img is self-closing and doesn't contain text, but test the alt attribute scenario
        String html = "<p>test</p><img alt='test image' src='test.png'>";
        Element result = applyCustomizer(html, "test", false, true);

        assertEquals(1, countMarks(result), "Should only match outside img tag");
        assertEquals(0, result.select("img mark").size(),
            "Should not match inside img");
    }

    @Test
    public void testNestedElements() {
        String html = "<div><p>test <span>test</span> test</p></div>";
        Element result = applyCustomizer(html, "test", false, true);

        assertEquals(3, countMarks(result), "Should match all occurrences in nested elements");
    }

    @Test
    public void testMultipleTermsInSameParagraph() {
        String html = "<p>The test is a test of the test system</p>";
        Element result = applyCustomizer(html, "test", false, true);

        assertEquals(3, countMarks(result), "Should match all occurrences");

        // Verify each has unique ID
        var marks = result.select("mark");
        var ids = marks.stream()
            .map(m -> m.attr("data-brokk-id"))
            .distinct()
            .toList();
        assertEquals(3, ids.size(), "Each match should have unique ID");
    }

    @Test
    public void testEmptyHandling() {
        // Empty HTML
        Element emptyResult = applyCustomizer("", "test", false, true);
        assertEquals(0, countMarks(emptyResult));

        // Whitespace only
        Element whitespaceResult = applyCustomizer("   \n\t  ", "test", false, true);
        assertEquals(0, countMarks(whitespaceResult));
    }

    @Test
    public void testSpecialCharactersInSearchTerm() {
        String html = "<p>Find the $test variable and test$ function</p>";

        // Search for "$test" - note that $ is not a word character, so word boundaries might not work as expected
        Element result = applyCustomizer(html, "$test", false, false);
        assertEquals(1, countMarks(result), "Should match $test");
        assertEquals("$test", result.select("mark").first().text());
    }

    @Test
    public void testPunctuationBoundaries() {
        String html = "<p>test, test. test! test? (test) [test] {test}</p>";
        Element result = applyCustomizer(html, "test", false, true);

        assertEquals(7, countMarks(result), "Should match test with various punctuation");
    }

    @Test
    public void testHtmlEntities() {
        String html = "<p>test &amp; test &lt;test&gt; test</p>";
        Element result = applyCustomizer(html, "test", false, true);

        assertEquals(4, countMarks(result), "Should match test around HTML entities");
    }

    @Test
    public void testNoMatchScenario() {
        String html = "<p>This text does not contain the search term</p>";
        Element result = applyCustomizer(html, "test", false, true);

        assertEquals(0, countMarks(result), "Should not create marks when no match");
        assertEquals(html, "<p>" + result.select("p").first().html() + "</p>",
            "HTML should remain unchanged when no matches");
    }

    @Test
    public void testConsecutiveMatches() {
        String html = "<p>test test test</p>";
        Element result = applyCustomizer(html, "test", false, true);

        assertEquals(3, countMarks(result), "Should match consecutive occurrences");
    }

    @Test
    public void testMatchAtBoundaries() {
        String html = "<p>test at start and end test</p>";
        Element result = applyCustomizer(html, "test", false, true);

        assertEquals(2, countMarks(result), "Should match at start and end");
    }

    @ParameterizedTest
    @CsvSource({
        "'test case', 'test', true, 1",
        "'test case', 'case', true, 1",
        "'test-case', 'test', true, 1",
        "'test_case', 'case', true, 0",  // Underscore is word character, so 'case' is not word boundary
        "'testcase', 'test', true, 0",
        "'testcase', 'case', true, 0"
    })
    public void testWordBoundaryVariations(String text, String term, boolean wholeWord, int expected) {
        String html = "<p>" + text + "</p>";
        Element result = applyCustomizer(html, term, false, wholeWord);

        assertEquals(expected, countMarks(result),
            String.format("'%s' searching for '%s' with wholeWord=%s", text, term, wholeWord));
    }

    @Test
    public void testCustomWrappers() {
        String html = "<p>test</p>";
        Document doc = Jsoup.parseBodyFragment(html);
        Element body = doc.body();

        HtmlCustomizer customizer = new TextNodeMarkerCustomizer(
            "test", false, true,
            "<span class='highlight' style='background: yellow'>",
            "</span>"
        );
        customizer.customize(body);

        var spans = body.select("span.highlight");
        assertEquals(1, spans.size());
        assertEquals("background: yellow", spans.first().attr("style"));
        assertEquals("test", spans.first().text());
    }

    @Test
    public void testMightMatchOptimization() {
        TextNodeMarkerCustomizer customizer = new TextNodeMarkerCustomizer(
            "test", false, true, "<mark>", "</mark>"
        );

        // Should match (case-insensitive)
        assertTrue(customizer.mightMatch("This is a test"));
        assertTrue(customizer.mightMatch("TEST"));

        // Whole word matching, so "testing" should NOT match when wholeWord=true
        assertFalse(customizer.mightMatch("testing"));

        // Should not match
        assertFalse(customizer.mightMatch("No match here"));
        assertFalse(customizer.mightMatch(""));
        assertFalse(customizer.mightMatch(null));
    }

    @Test
    public void testInvalidTermHandling() {
        // Empty term should throw
        assertThrows(IllegalArgumentException.class,
            () -> new TextNodeMarkerCustomizer("", false, true, "<mark>", "</mark>"));

        // Null term should throw
        assertThrows(NullPointerException.class,
            () -> new TextNodeMarkerCustomizer(null, false, true, "<mark>", "</mark>"));
    }

    @Test
    public void testPreserveExistingMarks() {
        String html = "<p>test <mark>existing</mark> test</p>";
        Element result = applyCustomizer(html, "test", false, true);

        assertEquals(3, result.select("mark").size(), "Should have 2 new + 1 existing marks");

        // Verify existing mark doesn't get data-brokk attributes
        var existingMark = result.select("mark").stream()
            .filter(m -> "existing".equals(m.text()))
            .findFirst()
            .orElseThrow();
        assertFalse(existingMark.hasAttr("data-brokk-id"),
            "Existing marks should not get brokk attributes");
    }

    @Test
    public void testComplexHtmlStructure() {
        String html = """
            <div>
                <h1>Test Header</h1>
                <p>First test paragraph with <strong>bold test</strong></p>
                <ul>
                    <li>Test item 1</li>
                    <li>Another test item</li>
                </ul>
                <table>
                    <tr><td>Test cell</td></tr>
                </table>
            </div>
            """;

        Element result = applyCustomizer(html, "test", false, true);

        assertEquals(6, countMarks(result), "Should match in various HTML contexts");

        // Verify matches in different contexts
        assertEquals(1, result.select("h1 mark").size());
        assertEquals(2, result.select("p mark").size());
        assertEquals(2, result.select("li mark").size());
        assertEquals(1, result.select("td mark").size());
    }

    @Test
    public void testUnicodeAndInternational() {
        String html = "<p>Test café test naïve test</p>";
        Element result = applyCustomizer(html, "test", false, true);

        assertEquals(3, countMarks(result), "Should handle Unicode text correctly");
    }

    @Test
    public void testRepeatedCustomization() {
        String html = "<p>test</p>";
        Document doc = Jsoup.parseBodyFragment(html);
        Element body = doc.body();

        HtmlCustomizer customizer = new TextNodeMarkerCustomizer(
            "test", false, true, "<mark>", "</mark>"
        );

        // Apply twice
        customizer.customize(body);
        customizer.customize(body);

        // Should not double-wrap due to data-brokk-marker protection
        assertEquals(1, body.select("mark").size(), "Should not double-wrap");
        assertEquals(0, body.select("mark mark").size(), "Should not nest marks");
    }

    @Test
    public void testHighlightInsideInlineCode() {
        String html = "<p><code>test</code></p>";
        Element result = applyCustomizer(html, "test", false, true);

        assertEquals(1, countMarks(result), "Should highlight inside <code>");
        assertEquals(1, result.select("code mark").size(), "Highlight must be inside <code>");
    }

    @Test
    public void testHighlightInsidePreCodeBlock() {
        String html = "<pre><code>test</code></pre>";
        Element result = applyCustomizer(html, "test", false, true);

        assertEquals(1, countMarks(result), "Should highlight inside <pre><code>");
        assertEquals(1, result.select("pre code mark").size(), "Highlight must be inside nested code");
    }

    @Test
    public void testHighlightInsideAnchor() {
        String html = "<p><a href=\"#\">test</a></p>";
        Element result = applyCustomizer(html, "test", false, true);

        assertEquals(1, countMarks(result), "Should highlight inside <a>");
        assertEquals(1, result.select("a mark").size(), "Highlight must be inside <a>");
    }
}
