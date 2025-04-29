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
 * Tests for the BrokkMarkdownExtension which converts SEARCH/REPLACE blocks to edit-block elements.
 */
class BrokkMarkdownExtensionTest {
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
    void regularCodeFenceGetsRenderedAsCodeFenceElement() {
        var md = """
                ```java
                public class Test {
                    public static void main(String[] args) {
                        System.out.println("Hello");
                    }
                }
                ```
                """;

        String html = renderer.render(parser.parse(md));
        System.out.println(html);

        // 1) We get exactly one code-fence element
        assertTrue(html.contains("<code-fence"), "expected a <code-fence> placeholder");
        assertEquals(1, html.split("<code-fence").length - 1,
                     "should create exactly one <code-fence>");

        // 2) Data attributes are present and escaped properly
        assertTrue(html.contains("data-lang=\"java\""), "language attribute missing");
        assertTrue(html.contains("data-id=\""), "id attribute missing");
        assertTrue(html.contains("data-content=\""), "content attribute missing");

        // 3) Code content is properly included and escaped
        assertTrue(html.contains("System.out.println"), "code content should be included");
        assertTrue(html.contains("&quot;Hello&quot;"), "string quotes should be escaped");

        // 4) The original markdown fence markers should not appear in output
        assertFalse(html.contains("```java"), "opening fence marker should not appear in output");
        assertFalse(html.contains("```\n"), "closing fence marker should not appear in output");
    }

    @Test
    void fencedBlockGetsRenderedAsEditBlock() {
        var md = """
                ```
                foo.txt
                <<<<<<< SEARCH
                a
                =======
                b
                >>>>>>> REPLACE
                ```
                """;

        String html = renderer.render(parser.parse(md));

        // 1) We get exactly one edit-block element

        assertTrue(html.contains("<edit-block"), "expected an <edit-block> placeholder");
        System.out.println(html);
        assertEquals(1, html.split("<edit-block").length - 1,
                     "should create exactly one <edit-block>");

        // 2) Data attributes are present and escaped properly
        assertTrue(html.contains("data-file=\"foo.txt\""), "filename attribute missing");
        assertTrue(html.contains("data-adds=\"1\""), "adds attribute incorrect");
        assertTrue(html.contains("data-dels=\"1\""), "dels attribute incorrect");

        // 3) Raw conflict markers must NOT appear in the rendered html
        assertFalse(html.contains("<<<<<<<"), "raw conflict marker leaked into html");
        assertFalse(html.contains("======="), "raw conflict marker leaked into html");
        assertFalse(html.contains(">>>>>>>"), "raw conflict marker leaked into html");
    }

    @Test
    void unfencedBlockIsRecognisedEarly() {
        var md = """
                <<<<<<< SEARCH example.txt
                lineA
                =======
                lineB
                >>>>>>> REPLACE
                """;

        String html = renderer.render(parser.parse(md));

        // Should still recognise the block and render a placeholder
        assertTrue(html.contains("<edit-block"),
                   "unfenced block should still become an <edit-block>");
        assertTrue(html.contains("data-file=\"example.txt\""),
                   "filename extracted from SEARCH line");
    }

    @Test
    void multipleBlocksReceiveDistinctIds() {
        var md = """
                ```
                file1.txt
                <<<<<<< SEARCH
                one
                =======
                two
                >>>>>>> REPLACE
                ```
                
                ```
                file2.txt
                <<<<<<< SEARCH
                three
                =======
                four
                >>>>>>> REPLACE
                ```
                """;

        String html = renderer.render(parser.parse(md));

        System.out.println(html);
        // Two edit-blocks with different ids
        assertEquals(2, html.split("<edit-block").length - 1, "expected two blocks");

        // Check that we have one data-id="0" (for the first block)
        assertTrue(html.contains("data-id=\"1187388123\""), "first id should be 0");

        // Get the second ID
        int firstIdPos = html.indexOf("data-id=\"");
        int secondIdPos = html.indexOf("data-id=\"", firstIdPos + 1);

        // Make sure there is a second ID
        assertTrue(secondIdPos > 0, "should find a second ID");

        // Extract the second ID
        int idStart = secondIdPos + 9; // length of 'data-id="'
        int idEnd = html.indexOf("\"", idStart);
        String secondId = html.substring(idStart, idEnd);

        // Make sure second ID is not 0
        assertNotEquals("0", secondId, "second id should be different from first");
    }

    @Test
    void multipleBlocksWithoutFencesReceiveDistinctIds() {
        var md = """
                <<<<<<< SEARCH file1.txt
                one
                =======
                two
                >>>>>>> REPLACE
                
                <<<<<<< SEARCH file2.txt
                three
                =======
                four
                >>>>>>> REPLACE
                """;

        String html = renderer.render(parser.parse(md));

        System.out.println(html);
        // Two edit-blocks with different ids
        assertEquals(2, html.split("<edit-block").length - 1, "expected two blocks");

        // Check that we have one data-id="0" (for the first block)
        assertTrue(html.contains("data-id=\"1187388123\""), "first id should be 0");

        // Get the second ID
        int firstIdPos = html.indexOf("data-id=\"");
        int secondIdPos = html.indexOf("data-id=\"", firstIdPos + 1);

        // Make sure there is a second ID
        assertTrue(secondIdPos > 0, "should find a second ID");

        // Extract the second ID
        int idStart = secondIdPos + 9; // length of 'data-id="'
        int idEnd = html.indexOf("\"", idStart);
        String secondId = html.substring(idStart, idEnd);

        // Make sure second ID is not 0
        assertNotEquals("0", secondId, "second id should be different from first");
    }

    @Test
    void mixedCodeFenceAndEditBlocksAreRenderedCorrectly() {
        var md = """
                Here's a code example:
                
                ```java
                public class Example {
                    public static void main(String[] args) {
                        System.out.println("Hello");
                    }
                }
                ```
                
                And here's an edit block:
                
                ```
                example.txt
                <<<<<<< SEARCH
                old content
                =======
                new content
                >>>>>>> REPLACE
                ```
                
                Another code block:
                
                ```python
                def hello():
                    print("Hello, Python!")
                ```
                """;

        String html = renderer.render(parser.parse(md));
        System.out.println(html);

        // 1) We get exactly two code-fence elements and one edit-block
        assertEquals(2, html.split("<code-fence").length - 1,
                     "should create exactly two <code-fence> elements");
        assertEquals(1, html.split("<edit-block").length - 1,
                     "should create exactly one <edit-block> element");

        // 2) Code fence attributes are present
        assertTrue(html.contains("data-lang=\"java\""), "Java language attribute missing");
        assertTrue(html.contains("data-lang=\"python\""), "Python language attribute missing");

        // 3) Edit block attributes are present
        assertTrue(html.contains("data-file=\"example.txt\""), "filename attribute missing");
        assertTrue(html.contains("data-adds=\"1\""), "adds attribute incorrect");
        assertTrue(html.contains("data-dels=\"1\""), "dels attribute incorrect");

        // 4) Content is properly included and escaped
        assertTrue(html.contains("System.out.println"), "Java code content missing");
        assertTrue(html.contains("print(&quot;Hello, Python!&quot;)"), "Python code content missing");

        // 5) Raw markers must not appear in the rendered html
        assertFalse(html.contains("<<<<<<<"), "raw conflict marker leaked into html");
        assertFalse(html.contains("======="), "raw conflict marker leaked into html");
        assertFalse(html.contains(">>>>>>>"), "raw conflict marker leaked into html");
        assertFalse(html.contains("```java"), "opening fence marker should not appear in output");
        assertFalse(html.contains("```python"), "opening fence marker should not appear in output");
    }


    @Test
    void realWorldEditBlocksGetsRenderedCorrectly() {
        var md = """
                Here are the detailed changes:
                
                ### 1. First, let's create a new ComponentData interface:
                
                <<<<<<< SEARCH src/main/java/io/github/jbellis/brokk/gui/mop/stream/blocks/ComponentData.java
                ======= src/main/java/io/github/jbellis/brokk/gui/mop/stream/blocks/ComponentData.java
                package io.github.jbellis.brokk.gui.mop.stream.blocks;
                
                import javax.swing.*;
                
                /**
                 * Represents a component that can be rendered in the UI.
                 * Each component has a stable ID and a fingerprint for change detection.
                 */
                public sealed interface ComponentData
                    permits MarkdownComponentData, CodeBlockComponentData, EditBlockComponentData {
                
                    /**
                     * Returns the unique identifier for this component.
                     */
                    int id();
                
                    /**
                     * Returns a fingerprint that changes when the component's content changes.
                     */
                    String fp();
                
                    /**
                     * Creates a new Swing component for this data.
                     * 
                     * @param darkTheme whether to use dark theme styling
                     * @return a new Swing component
                     */
                    JComponent createComponent(boolean darkTheme);
                
                    /**
                     * Updates an existing component with this data.
                     * Implementations should preserve caret position and scroll state when possible.
                     * 
                     * @param component the component to update
                     */
                    void updateComponent(JComponent component);
                }
                >>>>>>> REPLACE src/main/java/io/github/jbellis/brokk/gui/mop/stream/blocks/ComponentData.java
                
                ### 2. Now let's create concrete implementations:
                
                <<<<<<< SEARCH src/main/java/io/github/jbellis/brokk/gui/mop/stream/blocks/MarkdownComponentData.java
                ======= src/main/java/io/github/jbellis/brokk/gui/mop/stream/blocks/MarkdownComponentData.java
                package io.github.jbellis.brokk.gui.mop.stream.blocks;
                
                import io.github.jbellis.brokk.gui.mop.MarkdownRenderUtil;
                
                import javax.swing.*;
                import javax.swing.text.JTextComponent;
                import java.awt.*;
                
                /**
                 * Represents a Markdown prose segment between placeholders.
                 */
                public record MarkdownComponentData(int id, String html) implements ComponentData {
                    @Override
                    public String fp() {
                        return html.hashCode() + "";
                    }
                
                    @Override
                    public JComponent createComponent(boolean darkTheme) {
                        JEditorPane editor = MarkdownRenderUtil.createHtmlPane(darkTheme);
                
                        // Update content
                        editor.setText("<html><body>" + html + "</body></html>");
                
                        // Configure for left alignment and proper sizing
                        editor.setAlignmentX(Component.LEFT_ALIGNMENT);
                        editor.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
                
                        return editor;
                    }
                
                    @Override
                    public void updateComponent(JComponent component) {
                        if (component instanceof JEditorPane editor) {
                            // Record current scroll position
                            var viewport = SwingUtilities.getAncestorOfClass(JViewport.class, editor);
                            Point viewPosition = viewport instanceof JViewport ? ((JViewport)viewport).getViewPosition() : null;
                
                            // Update content
                            editor.setText("<html><body>" + html + "</body></html>");
                
                            // Restore scroll position if possible
                            if (viewport instanceof JViewport && viewPosition != null) {
                                ((JViewport)viewport).setViewPosition(viewPosition);
                            }
                        }
                    }
                }
                >>>>>>> REPLACE src/main/java/io/github/jbellis/brokk/gui/mop/stream/blocks/MarkdownComponentData.java
                
                """;

        String html = renderer.render(parser.parse(md));
        System.out.println(html);
        
        // Verify HTML structure
        assertTrue(html.contains("<p>Here are the detailed changes:</p>"), "Paragraph should be preserved");
        assertTrue(html.contains("<h3>1. First, let's create a new ComponentData interface:</h3>"), "Heading should be preserved");
        
        // Verify edit blocks are created
        assertEquals(2, html.split("<edit-block").length - 1, "Should have exactly 2 edit blocks");
        
        // Verify first edit block attributes
        assertTrue(html.contains("data-file=\"src/main/java/io/github/jbellis/brokk/gui/mop/stream/blocks/ComponentData.java\""), 
                  "First file path should be correct");
        assertTrue(html.contains("data-adds=\"37\""), "First adds count should be correct");
        assertTrue(html.contains("data-dels=\"0\""), "First dels count should be correct");
        
        // Verify second edit block attributes
        assertTrue(html.contains("data-file=\"src/main/java/io/github/jbellis/brokk/gui/mop/stream/blocks/MarkdownComponentData.java\""), 
                  "Second file path should be correct");
        assertTrue(html.contains("data-adds=\"48\""), "Second adds count should be correct");
        assertTrue(html.contains("data-dels=\"0\""), "Second dels count should be correct");
        
        // Verify IDs are present
        assertTrue(html.contains("data-id=\""), "ID attributes should be present");
    }

    @Test
    void realWorldEditBlocksGetsRenderedCorrectly2() {
        var md = """
                ### 4. MarkdownOutputPanel refactor
                
                4.1  Data structure
                ```
                record Row(BaseChatMessagePanel bubble, IncrementalBlockRenderer renderer) {}
                private final List<Row> rows = new ArrayList<>();
                ```
                This replaces the parallel `messageComponents` + `currentRenderer` duo.
                
                4.2  `addNewMessage` \s
                ```
                var bubble   = createSpeechBubble(message, isDarkTheme);
                var renderer = new IncrementalBlockRenderer(isDarkTheme, POLICY.get(message.type()));
                
                bubble.add(renderer.getRoot(), BorderLayout.CENTER);
                renderer.update(Messages.getText(message));
                
                rows.add(new Row(bubble, renderer));
                add(bubble);
                ```
                """;

        String html = renderer.render(parser.parse(md));
        System.out.println(html);
        
        // Verify HTML structure
        assertTrue(html.contains("<h3>4. MarkdownOutputPanel refactor</h3>"), "Heading should be preserved");
        assertTrue(html.contains("<p>4.1  Data structure</p>"), "Subheading should be preserved");
        assertTrue(html.contains("<p>This replaces the parallel <code>messageComponents</code>"), "Inline code should be preserved");
        
        // Verify code blocks are created
        assertEquals(2, html.split("<code-fence").length - 1, "Should have exactly 2 code blocks");
        
        // Verify code content is included
        assertTrue(html.contains("data-content=\"record Row"), "First code block content should be present");
        assertTrue(html.contains("data-content=\"var bubble"), "Second code block content should be present");
        
        // Verify IDs are present
        assertTrue(html.contains("data-id=\""), "ID attributes should be present");
    }
}
