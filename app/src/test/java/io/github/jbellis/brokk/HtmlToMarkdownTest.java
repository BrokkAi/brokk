package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.util.HtmlToMarkdown;
import org.junit.jupiter.api.Test;

public class HtmlToMarkdownTest {

    @Test
    public void testPlainText() {
        String input = "This is plain text.";
        String output = HtmlToMarkdown.maybeConvertToMarkdown(input);
        assertEquals(input, output, "Plain text should be returned unchanged.");
    }

    @Test
    public void testHtmlConversion() {
        String html = "<html><body>"
                + "<h1>Hi <a href='test'>link</a></h1>"
                + "<p>Some additional text.</p>"
                + "<img src='pic.jpg'>"
                + "</body></html>";
        String markdown = HtmlToMarkdown.maybeConvertToMarkdown(html);

        // Check that the conversion result contains expected text.
        assertTrue(markdown.contains("Hi"), "Markdown should contain the header text.");
        assertTrue(markdown.contains("link"), "Markdown should contain the link text.");
        assertTrue(markdown.contains("Some additional text"), "Markdown should contain the paragraph text.");

        // Ensure that link and image markdown syntax have been removed.
        assertFalse(markdown.contains("["), "Markdown should not include Markdown link syntax.");
        assertFalse(markdown.contains("!["), "Markdown should not include Markdown image syntax.");
    }
}
