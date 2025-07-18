package io.github.jbellis.brokk.flex;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import io.github.jbellis.brokk.gui.mop.stream.flex.BrokkMarkdownExtension;
import io.github.jbellis.brokk.gui.mop.stream.flex.IdProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the various forms of SEARCH/REPLACE block syntax recognition.
 */
class SearchReplaceBlocksTest {
    private Parser parser;
    private HtmlRenderer renderer;
    private IdProvider idProvider;

    @BeforeEach
    void setUp() {
        idProvider = new IdProvider();  // Reset counter for each test
        MutableDataSet options = new MutableDataSet()
                .set(Parser.EXTENSIONS, List.of(BrokkMarkdownExtension.create()))
                .set(IdProvider.ID_PROVIDER, idProvider)
                // Keep html straightforward for string-comparison
                .set(HtmlRenderer.SOFT_BREAK, "<br />\n")
                .set(HtmlRenderer.ESCAPE_HTML, true);

        parser = Parser.builder(options).build();
        renderer = HtmlRenderer.builder(options).build();
    }

    @Test
    void normalSyntaxTest() {
        var md = """
                file1.txt
                <<<<<<< SEARCH
                one
                =======
                two
                >>>>>>> REPLACE
                """;

        String html = renderer.render(parser.parse(md));
        System.out.println(html);

        // Verify it's recognized as an edit block
        assertTrue(html.contains("<edit-block"), "should be recognized as an edit block");
        assertEquals(1, html.split("<edit-block").length - 1, "should have exactly one edit block");
        
        // Verify attributes
        assertTrue(html.contains("data-file=\"file1.txt\""), "filename attribute should be correct");
        assertTrue(html.contains("data-adds=\"1\""), "adds count should be correct");
        assertTrue(html.contains("data-dels=\"1\""), "dels count should be correct");
        assertTrue(html.contains("data-changed=\"1\""), "changed count should be correct");
        
        // Verify no raw markers
        assertFalse(html.contains("<<<<<<<"), "raw markers should not appear in output");
        assertFalse(html.contains("======="), "raw markers should not appear in output");
        assertFalse(html.contains(">>>>>>>"), "raw markers should not appear in output");
    }

    @Test
    void conflictSyntaxTest() {
        var md = """
                <<<<<<< SEARCH file1.txt
                one
                ======= file1.txt
                two
                >>>>>>> REPLACE file1.txt
                """;

        String html = renderer.render(parser.parse(md));
        System.out.println(html);

        // Verify it's recognized as an edit block
        assertTrue(html.contains("<edit-block"), "should be recognized as an edit block");
        assertEquals(1, html.split("<edit-block").length - 1, "should have exactly one edit block");
        
        // Verify attributes
        assertTrue(html.contains("data-file=\"file1.txt\""), "filename attribute should be correct");
        assertTrue(html.contains("data-adds=\"1\""), "adds count should be correct");
        assertTrue(html.contains("data-dels=\"1\""), "dels count should be correct");
        assertTrue(html.contains("data-changed=\"1\""), "changed count should be correct");
        
        // Verify no raw markers
        assertFalse(html.contains("<<<<<<<"), "raw markers should not appear in output");
        assertFalse(html.contains("======="), "raw markers should not appear in output");
        assertFalse(html.contains(">>>>>>>"), "raw markers should not appear in output");
    }

    @Test
    void normalSyntaxInCodeFencesTest() {
        var md = """
                ```
                file1.txt
                <<<<<<< SEARCH
                one
                =======
                two
                >>>>>>> REPLACE
                ```
                """;

        String html = renderer.render(parser.parse(md));
        System.out.println(html);

        // Verify it's recognized as an edit block
        assertTrue(html.contains("<edit-block"), "should be recognized as an edit block");
        assertEquals(1, html.split("<edit-block").length - 1, "should have exactly one edit block");
        
        // Verify attributes
        assertTrue(html.contains("data-file=\"file1.txt\""), "filename attribute should be correct");
        assertTrue(html.contains("data-adds=\"1\""), "adds count should be correct");
        assertTrue(html.contains("data-dels=\"1\""), "dels count should be correct");
        assertTrue(html.contains("data-changed=\"1\""), "changed count should be correct");
        
        // Verify no raw markers or fence markers
        assertFalse(html.contains("<<<<<<<"), "raw markers should not appear in output");
        assertFalse(html.contains("======="), "raw markers should not appear in output");
        assertFalse(html.contains(">>>>>>>"), "raw markers should not appear in output");
        assertFalse(html.contains("```"), "fence markers should not appear in output");
    }

    @Test
    void conflictSyntaxInCodeFencesTest() {
        var md = """
                ```
                <<<<<<< SEARCH file1.txt
                one
                ======= file1.txt
                two
                >>>>>>> REPLACE file1.txt
                ```
                """;

        String html = renderer.render(parser.parse(md));
        System.out.println(html);

        // Verify it's recognized as an edit block
        assertTrue(html.contains("<edit-block"), "should be recognized as an edit block");
        assertEquals(1, html.split("<edit-block").length - 1, "should have exactly one edit block");
        
        // Verify attributes
        assertTrue(html.contains("data-file=\"file1.txt\""), "filename attribute should be correct");
        assertTrue(html.contains("data-adds=\"1\""), "adds count should be correct");
        assertTrue(html.contains("data-dels=\"1\""), "dels count should be correct");
        assertTrue(html.contains("data-changed=\"1\""), "changed count should be correct");
        
        // Verify no raw markers or fence markers
        assertFalse(html.contains("<<<<<<<"), "raw markers should not appear in output");
        assertFalse(html.contains("======="), "raw markers should not appear in output");
        assertFalse(html.contains(">>>>>>>"), "raw markers should not appear in output");
        assertFalse(html.contains("```"), "fence markers should not appear in output");
    }

    @Test
    void normalSyntaxInCodeFencesWithLanguageTest() {
        var md = """
                ```java
                file1.txt
                <<<<<<< SEARCH
                one
                =======
                two
                >>>>>>> REPLACE
                ```
                """;

        String html = renderer.render(parser.parse(md));
        System.out.println(html);

        // Verify it's recognized as an edit block
        assertTrue(html.contains("<edit-block"), "should be recognized as an edit block");
        assertEquals(1, html.split("<edit-block").length - 1, "should have exactly one edit block");
        
        // Verify attributes
        assertTrue(html.contains("data-file=\"file1.txt\""), "filename attribute should be correct");
        assertTrue(html.contains("data-adds=\"1\""), "adds count should be correct");
        assertTrue(html.contains("data-dels=\"1\""), "dels count should be correct");
        assertTrue(html.contains("data-changed=\"1\""), "changed count should be correct");
        
        // Verify no raw markers or fence markers
        assertFalse(html.contains("<<<<<<<"), "raw markers should not appear in output");
        assertFalse(html.contains("======="), "raw markers should not appear in output");
        assertFalse(html.contains(">>>>>>>"), "raw markers should not appear in output");
        assertFalse(html.contains("```java"), "fence markers should not appear in output");
    }

    @Test
    void conflictSyntaxInCodeFencesWithLanguageTest() {
        var md = """
                ```java
                <<<<<<< SEARCH file1.txt
                one
                ======= file1.txt
                two
                >>>>>>> REPLACE file1.txt
                ```
                """;

        String html = renderer.render(parser.parse(md));
        System.out.println(html);

        // Verify it's recognized as an edit block
        assertTrue(html.contains("<edit-block"), "should be recognized as an edit block");
        assertEquals(1, html.split("<edit-block").length - 1, "should have exactly one edit block");
        
        // Verify attributes
        assertTrue(html.contains("data-file=\"file1.txt\""), "filename attribute should be correct");
        assertTrue(html.contains("data-adds=\"1\""), "adds count should be correct");
        assertTrue(html.contains("data-dels=\"1\""), "dels count should be correct");
        assertTrue(html.contains("data-changed=\"1\""), "changed count should be correct");
        
        // Verify no raw markers or fence markers
        assertFalse(html.contains("<<<<<<<"), "raw markers should not appear in output");
        assertFalse(html.contains("======="), "raw markers should not appear in output");
        assertFalse(html.contains(">>>>>>>"), "raw markers should not appear in output");
        assertFalse(html.contains("```java"), "fence markers should not appear in output");
    }

    @Test
    void strangeSyntaxTest() {
        var md = """
                ```file1.txt
                <<<<<<< SEARCH
                one
                =======
                two
                >>>>>>> REPLACE
                ```
                """;

        String html = renderer.render(parser.parse(md));
        System.out.println(html);

        // Verify it's recognized as an edit block
        assertTrue(html.contains("<edit-block"), "should be recognized as an edit block");
        assertEquals(1, html.split("<edit-block").length - 1, "should have exactly one edit block");
        
        // Verify attributes
        assertTrue(html.contains("data-file=\"file1.txt\""), "filename attribute should be correct");
        assertTrue(html.contains("data-adds=\"1\""), "adds count should be correct");
        assertTrue(html.contains("data-dels=\"1\""), "dels count should be correct");
        assertTrue(html.contains("data-changed=\"1\""), "changed count should be correct");
        
        // Verify no raw markers or fence markers
        assertFalse(html.contains("<<<<<<<"), "raw markers should not appear in output");
        assertFalse(html.contains("======="), "raw markers should not appear in output");
        assertFalse(html.contains(">>>>>>>"), "raw markers should not appear in output");
        assertFalse(html.contains("```file1.txt"), "fence markers should not appear in output");
    }
}
