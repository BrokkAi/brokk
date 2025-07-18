package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.gui.mop.stream.blocks.*;
import io.github.jbellis.brokk.gui.mop.stream.flex.IdProvider;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the MiniParser class.
 */
public class MiniParserTest {
    
    private MiniParser parser;
    private MarkdownFactory mdFactory;
    private Map<String, ComponentDataFactory> factories;
    private IdProvider idProvider;
    
    @BeforeEach
    void setUp() {
        idProvider = new IdProvider();
        parser = new MiniParser(idProvider);
        mdFactory = new MarkdownFactory();
        factories = new HashMap<>();
        factories.put("code-fence", new TestCodeBlockFactory());
        factories.put("edit-block", new EditBlockFactory());
    }
    
    /**
     * Test-specific CodeBlockFactory that extracts content from element body
     * rather than data-content attribute
     */
    private static class TestCodeBlockFactory extends CodeBlockFactory {
        @Override
        public ComponentData fromElement(org.jsoup.nodes.Element element) {
            int id = Integer.parseInt(element.attr("data-id"));
            String lang = element.attr("data-lang");
            
            // Extract code from element text or first pre tag if present
            String code = "";
            var preTag = element.selectFirst("pre");
            if (preTag != null) {
                code = preTag.text();
            } else if (element.hasAttr("data-content")) {
                // Fallback to data-content for backward compatibility
                code = element.attr("data-content");
            } else {
                code = element.text();
            }
            
            return new CodeBlockComponentData(id, code, lang);
        }
    }
    
    @Test
    void testParseSimpleParagraph() {
        var html = "<p>This is a simple paragraph.</p>";
        var doc = Jsoup.parse(html);
        var element = doc.body().child(0); // <p> element

        var components = parser.parse(element, mdFactory, factories);
        
        // Should produce a single MarkdownComponentData
        assertEquals(1, components.size());
        assertTrue(components.getFirst() instanceof MarkdownComponentData);
        var md = (MarkdownComponentData) components.getFirst();
        assertEquals("<p>This is a simple paragraph.</p>", md.html());
    }
    
    @Test
    void testNestedCodeFence() {
        var html = "<ul><li>Here is a code block:\n" +
                  "<code-fence data-id=\"123\" data-lang=\"java\"><pre>System.out.println(\"test\");</pre></code-fence>\n" +
                  "</li></ul>";
        var doc = Jsoup.parse(html);
        var element = doc.body().child(0); // <ul> element

        var components = parser.parse(element, mdFactory, factories);
        
        // Should produce a single CompositeComponentData
        assertEquals(1, components.size());
        assertTrue(components.getFirst() instanceof CompositeComponentData);
        
        // The composite should have 3 children: HTML before, code fence, HTML after
        var composite = (CompositeComponentData) components.getFirst();
        assertEquals(3, composite.children().size());
        
        // First child is HTML up to the code fence
        assertTrue(composite.children().get(0) instanceof MarkdownComponentData);
        assertTrue(((MarkdownComponentData)composite.children().get(0)).html().contains("<ul><li>Here is a code block:"));
        
        // Second child is the code fence
        assertTrue(composite.children().get(1) instanceof CodeBlockComponentData);
        var codeBlock = (CodeBlockComponentData) composite.children().get(1);
        assertEquals("java", codeBlock.lang());
        assertEquals("System.out.println(\"test\");", codeBlock.body());
        
        // Third child is the closing HTML
        assertTrue(composite.children().get(2) instanceof MarkdownComponentData);
        assertTrue(((MarkdownComponentData)composite.children().get(2)).html().contains("</li></ul>"));
    }
    
    @Test
    void testMultipleNestedCustomTags() {
        var html = "<div>" +
                  "  <p>First paragraph</p>" +
                  "  <code-fence data-id=\"123\" data-lang=\"java\"><pre>code1();</pre></code-fence>" +
                  "  <p>Second paragraph</p>" +
                  "  <code-fence data-id=\"124\" data-lang=\"python\"><pre>code2();</pre></code-fence>" +
                  "  <p>Final paragraph</p>" +
                  "</div>";
        var doc = Jsoup.parse(html);
        var element = doc.body().child(0); // <div> element

        var components = parser.parse(element, mdFactory, factories);
        
        // Should produce a single CompositeComponentData
        assertEquals(1, components.size());
        assertTrue(components.getFirst() instanceof CompositeComponentData);
        
        // The composite should have 5 children
        var composite = (CompositeComponentData) components.getFirst();
        assertEquals(5, composite.children().size());
        
        // Check alternating pattern of markdown and code blocks
        assertTrue(composite.children().get(0) instanceof MarkdownComponentData);
        assertTrue(composite.children().get(1) instanceof CodeBlockComponentData);
        assertTrue(composite.children().get(2) instanceof MarkdownComponentData);
        assertTrue(composite.children().get(3) instanceof CodeBlockComponentData);
        assertTrue(composite.children().get(4) instanceof MarkdownComponentData);
        
        // Verify content of code blocks
        assertEquals("java", ((CodeBlockComponentData)composite.children().get(1)).lang());
        assertEquals("code1();", ((CodeBlockComponentData)composite.children().get(1)).body());
        assertEquals("python", ((CodeBlockComponentData)composite.children().get(3)).lang());
        assertEquals("code2();", ((CodeBlockComponentData)composite.children().get(3)).body());
    }
    
    @Test
    void testDeepNestedCustomTag() {
        var html = "<blockquote><ul><li>" +
                  "  <code-fence data-id=\"123\" data-lang=\"java\"><pre>deeplyNested();</pre></code-fence>" +
                  "</li></ul></blockquote>";
        var doc = Jsoup.parse(html);
        var element = doc.body().child(0); // <blockquote> element

        var components = parser.parse(element, mdFactory, factories);
        
        // Should produce a single CompositeComponentData
        assertEquals(1, components.size());
        assertTrue(components.getFirst() instanceof CompositeComponentData);
        
        // Ensure the deeply nested tag was found
        var composite = (CompositeComponentData) components.getFirst();
        assertTrue(composite.children().stream().anyMatch(c -> c instanceof CodeBlockComponentData));
        
        // Find the code block and verify its content
        var codeBlock = composite.children().stream()
                .filter(c -> c instanceof CodeBlockComponentData)
                .map(c -> (CodeBlockComponentData)c)
                .findFirst()
                .orElse(null);
        
        assertNotNull(codeBlock);
        assertEquals("java", codeBlock.lang());
        assertEquals("deeplyNested();", codeBlock.body());
    }
    
    @Test
    void testEditBlockInsideBlockquote() {
        var html = "<blockquote>" +
                  "  <p>Here's an edit block:</p>" +
                  "  <edit-block data-id=\"456\" data-file=\"Main.java\" data-adds=\"10\" data-dels=\"5\" data-status=\"unknown\"></edit-block>" +
                  "  <p>End of quote.</p>" +
                  "</blockquote>";
        var doc = Jsoup.parse(html);
        var element = doc.body().child(0); // <blockquote> element

        var components = parser.parse(element, mdFactory, factories);
        
        // Should produce a single CompositeComponentData
        assertEquals(1, components.size());
        assertTrue(components.getFirst() instanceof CompositeComponentData);
        
        // Ensure the edit block was found
        var composite = (CompositeComponentData) components.getFirst();
        assertTrue(composite.children().stream().anyMatch(c -> c instanceof EditBlockComponentData));
        
        // Find the edit block and verify its content
        var editBlock = composite.children().stream()
                .filter(c -> c instanceof EditBlockComponentData)
                .map(c -> (EditBlockComponentData)c)
                .findFirst()
                .orElse(null);
        
        assertNotNull(editBlock);
        assertEquals("Main.java", editBlock.file());
          assertEquals(10, editBlock.adds());
          assertEquals(5, editBlock.dels());
          assertEquals(5, editBlock.changed()); // changed = min(10, 5) = 5
      }
    
    @Test
    void testMultipleCustomTagsInDifferentDepths() {
        var html = "<div>" +
                  "  <p>Intro text</p>" +
                  "  <blockquote>" +
                  "    <code-fence data-id=\"123\" data-lang=\"java\" data-content=\"nested();\"/>" +
                  "  </blockquote>" +
                  "  <ul><li>" +
                  "    <edit-block data-id=\"456\" data-file=\"Test.java\" data-adds=\"3\" data-dels=\"2\" data-status=\"unknown\"></edit-block>" +
                  "  </li></ul>" +
                  "  <p>Conclusion</p>" +
                  "</div>";
        var doc = Jsoup.parse(html);
        var element = doc.body().child(0); // <div> element

        var components = parser.parse(element, mdFactory, factories);
        
        // Should produce a single CompositeComponentData
        assertEquals(1, components.size());
        assertTrue(components.getFirst() instanceof CompositeComponentData);
        
        // Check that we found both custom tags
        var composite = (CompositeComponentData) components.getFirst();
        assertEquals(5, composite.children().size(), "Should have 5 children (3 markdown + 2 custom tags)");
        
        // Count the number of each type
        long codeBlocks = composite.children().stream()
                .filter(c -> c instanceof CodeBlockComponentData)
                .count();
        long editBlocks = composite.children().stream()
                .filter(c -> c instanceof EditBlockComponentData)
                .count();
        
        assertEquals(1, codeBlocks, "Should have 1 code block");
        assertEquals(1, editBlocks, "Should have 1 edit block");
    }
    
    @Test
    void testComponentCreationWithThemes() {
        var html = "<div>" +
                  "  <code-fence data-id=\"123\" data-lang=\"java\" data-content=\"testTheme();\"/>" +
                  "</div>";
        var doc = Jsoup.parse(html);
        var element = doc.body().child(0);
        
        var components = parser.parse(element, mdFactory, factories);
        assertEquals(1, components.size());
        
        // Test component creation with light theme
        var lightComponent = components.getFirst().createComponent(false);
        assertNotNull(lightComponent);
        
        // Test component creation with dark theme
        var darkComponent = components.getFirst().createComponent(true);
        assertNotNull(darkComponent);
        
        // For composites, verify all children are created
        if (components.getFirst() instanceof CompositeComponentData composite) {
            // A composite should create a panel with components for each child
            var panel = composite.createComponent(false);
            assertEquals(composite.children().size(), panel.getComponentCount());
        }
    }
    
    // ==== Test stable ID generation ====
    
    @Test
    void sameHtmlSameIds() {
        String html = "<p>Hello world</p>";
        int id1 = extractOnlyMarkdownId(html);
        int id2 = extractOnlyMarkdownId(html);  // second parse
        assertEquals(id1, id2, "Markdown id must be deterministic");
    }
    
    @Test
    void idUnchangedWhenContentBelowChanges() {
        String original = """
            <div>
              <p>Alpha</p>
              <code-fence data-id="77" data-lang="java" data-content="x();"/>
            </div>""";

        String withExtraPara = """
            <div>
              <p>Alpha</p>
              <code-fence data-id="77" data-lang="java" data-content="x();"/>
              <p>EXTRA</p>
            </div>""";

        int idBefore = extractMarkdownId(original, 0);     // index of <p>Alpha</p>
        int idAfter = extractMarkdownId(withExtraPara, 0); // index shifted down
        assertEquals(idBefore, idAfter,
                    "Id of unchanged paragraph must stay the same when text is inserted above");
    }
    
    @Test
    void twoChunksUnderSameParagraphHaveDifferentIdsButStable() {
        String html = """
            <p>before
               <code-fence data-id="11" data-lang="none" data-content="dummy();"/>
               after</p>""";

        var idsFirstParse = extractAllMarkdownIds(html);
        var idsSecondParse = extractAllMarkdownIds(html);  // determinism

        assertEquals(2, idsFirstParse.size());
        assertNotEquals(idsFirstParse.get(0), idsFirstParse.get(1), "Chunks must have distinct ids");
        assertEquals(idsFirstParse, idsSecondParse, "Ids must be repeatable");
    }
    
    @Test
    void textNodeAnchorStillStable() {
        // A text node under body
        String html = "<html><body>Plain text with no wrapping element</body></html>";
        String html2 = "<html><body>Plain text with no wrapping element 123</body></html>";
        int id1 = extractOnlyMarkdownId(html);
        int id2 = extractOnlyMarkdownId(html2);  // small change should retain same ID
        assertEquals(id1, id2, "Text node anchor should produce stable ID");
    }
    
    // ==== Helper methods for stable ID tests ====
    
    private int extractOnlyMarkdownId(String html) {
        var ids = extractAllMarkdownIds(html);
        assertEquals(1, ids.size(), "Expected single markdown block");
        return ids.getFirst();
    }

    private int extractMarkdownId(String html, int index) {
        var ids = extractAllMarkdownIds(html);
        assertTrue(index < ids.size(), "Not enough markdown blocks found");
        return ids.get(index);
    }
    
    private List<Integer> extractAllMarkdownIds(String html) {
        var doc = Jsoup.parse(html);
        var body = doc.body();
        var list = new ArrayList<Integer>();

        for (Node child : body.childNodes()) {
            if (child instanceof Element element) {
                var comps = parser.parse(element, mdFactory, factories);
                for (var cd : flatten(comps)) {
                    if (cd instanceof MarkdownComponentData md) {
                        list.add(md.id());
                    }
                }
            } else if (child instanceof org.jsoup.nodes.TextNode textNode && !textNode.isBlank()) {
                // For plain text nodes, create a markdown component directly
                int id = idProvider.getId(body); // Use body as anchor for stability
                list.add(id);
            }
        }
        return list;
    }

    // Recursively expand composites
    private List<ComponentData> flatten(List<ComponentData> in) {
        var out = new ArrayList<ComponentData>();
        for (ComponentData cd : in) {
            if (cd instanceof CompositeComponentData c) {
                out.addAll(flatten(c.children()));
            } else {
                out.add(cd);
            }
        }
        return out;
    }
}
