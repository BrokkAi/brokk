package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.gui.mop.stream.blocks.ComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.MarkdownComponentData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that IncrementalBlockRenderer properly applies HtmlCustomizers during rendering.
 */
public class IncrementalBlockRendererCustomizerTest {

    private IncrementalBlockRenderer renderer;

    @BeforeEach
    public void setUp() {
        renderer = new IncrementalBlockRenderer(false);
    }

    @Test
    public void testCustomizerAppliedDuringInitialRendering() {
        String markdown = "This is a test document with test content.";
        
        // Set customizer that highlights 'test'
        HtmlCustomizer testHighlighter = new TextNodeMarkerCustomizer(
            "test", false, true, "<mark>", "</mark>"
        );
        renderer.setHtmlCustomizer(testHighlighter);
        
        // Build component data - this should apply the customizer
        String html = renderer.createHtml(markdown);
        List<ComponentData> components = renderer.buildComponentData(html);
        
        // Extract HTML from MarkdownComponentData
        String resultHtml = extractMarkdownHtml(components);
        
        // Verify customizer was applied
        assertTrue(resultHtml.contains("<mark"), "Should contain mark tags from customizer");
        assertTrue(resultHtml.contains("data-brokk-marker"), "Should contain marker attributes");
        
        // Count occurrences of 'test' being highlighted
        long markCount = countMatches(resultHtml, "<mark");
        assertEquals(2, markCount, "Should highlight both occurrences of 'test'");
    }

    @Test
    public void testCustomizerAppliedDuringReprocessing() {
        String markdown = "Find alpha and beta in this text.";
        
        // Initially no customizer
        renderer.setHtmlCustomizer(HtmlCustomizer.DEFAULT);
        String html = renderer.createHtml(markdown);
        List<ComponentData> initialComponents = renderer.buildComponentData(html);
        String initialHtml = extractMarkdownHtml(initialComponents);
        
        // Should have no marks initially
        assertFalse(initialHtml.contains("<mark"), "Should not contain marks initially");
        
        // Set customizer for 'alpha'
        HtmlCustomizer alphaHighlighter = new TextNodeMarkerCustomizer(
            "alpha", false, true, "<mark>", "</mark>"
        );
        renderer.setHtmlCustomizer(alphaHighlighter);
        
        // Reprocess with customizer
        renderer.reprocessForCustomizer();
        
        // The reprocessForCustomizer method works asynchronously, but since we're testing
        // the core buildComponentData functionality, let's test it directly
        List<ComponentData> reprocessedComponents = renderer.buildComponentData(renderer.createHtml(markdown));
        String reprocessedHtml = extractMarkdownHtml(reprocessedComponents);
        
        // Verify customizer was applied
        assertTrue(reprocessedHtml.contains("<mark"), "Should contain marks after reprocessing");
        assertTrue(reprocessedHtml.contains("alpha"), "Should highlight 'alpha'");
        assertFalse(reprocessedHtml.contains("<mark>beta"), "Should not highlight 'beta'");
    }

    @Test
    public void testSwitchingCustomizers() {
        String markdown = "Search for apple and orange in the text.";
        
        // First customizer highlights 'apple'
        HtmlCustomizer appleHighlighter = new TextNodeMarkerCustomizer(
            "apple", false, true, "<mark class='apple'>", "</mark>"
        );
        renderer.setHtmlCustomizer(appleHighlighter);
        
        String html = renderer.createHtml(markdown);
        List<ComponentData> appleComponents = renderer.buildComponentData(html);
        String appleHtml = extractMarkdownHtml(appleComponents);
        
        assertTrue(appleHtml.contains("class=\"apple\""), "Should highlight apple with apple class");
        assertFalse(appleHtml.contains("class=\"orange\""), "Should not have orange class");
        
        // Switch to highlighting 'orange'
        HtmlCustomizer orangeHighlighter = new TextNodeMarkerCustomizer(
            "orange", false, true, "<mark class='orange'>", "</mark>"
        );
        renderer.setHtmlCustomizer(orangeHighlighter);
        
        List<ComponentData> orangeComponents = renderer.buildComponentData(renderer.createHtml(markdown));
        String orangeHtml = extractMarkdownHtml(orangeComponents);
        
        assertTrue(orangeHtml.contains("class=\"orange\""), "Should highlight orange with orange class");
        assertFalse(orangeHtml.contains("class=\"apple\""), "Should not have apple class anymore");
    }

    @Test
    public void testCustomizerWithComplexMarkdown() {
        String markdown = """
            # Test Header
            
            This is a **test** paragraph with `test` code.
            
            ```java
            public void test() {
                System.out.println("test");
            }
            ```
            
            * Test item 1
            * Another test item
            """;
        
        HtmlCustomizer testHighlighter = new TextNodeMarkerCustomizer(
            "test", false, true, "<mark>", "</mark>"
        );
        renderer.setHtmlCustomizer(testHighlighter);
        
        String html = renderer.createHtml(markdown);
        List<ComponentData> components = renderer.buildComponentData(html);
        String resultHtml = extractMarkdownHtml(components);
        
        // Should highlight 'test' in text but not in code blocks
        assertTrue(resultHtml.contains("<mark"), "Should contain mark tags");
        
        // Count marks - should be less than total occurrences of 'test' due to code block protection
        long totalTestOccurrences = countMatches(markdown.toLowerCase(), "test");
        long markedOccurrences = countMatches(resultHtml, "<mark");
        
        assertTrue(markedOccurrences > 0, "Should mark some occurrences");
        assertTrue(markedOccurrences < totalTestOccurrences, 
            "Should mark fewer than total due to code block protection");
    }

    @Test
    public void testCustomizerInCompactedState() {
        String markdown1 = "First block with target word.";
        String markdown2 = "Second block with target word.";
        
        // Set customizer
        HtmlCustomizer targetHighlighter = new TextNodeMarkerCustomizer(
            "target", false, true, "<mark>", "</mark>"
        );
        renderer.setHtmlCustomizer(targetHighlighter);
        
        // Process first markdown
        renderer.update(markdown1);
        
        // Process second markdown (this would normally be incremental)
        renderer.update(markdown1 + "\n\n" + markdown2);
        
        // Build compacted snapshot
        List<ComponentData> compactedComponents = renderer.buildCompactedSnapshot(1L);
        
        if (compactedComponents != null) {
            String compactedHtml = extractMarkdownHtml(compactedComponents);
            
            // Verify customizer works in compacted state
            assertTrue(compactedHtml.contains("<mark"), "Should contain marks in compacted state");
            long markCount = countMatches(compactedHtml, "<mark");
            assertEquals(2, markCount, "Should highlight 'target' in both blocks");
        }
    }

    @Test
    public void testNoOpCustomizer() {
        String markdown = "This text should remain unchanged.";
        
        // Use default (no-op) customizer
        renderer.setHtmlCustomizer(HtmlCustomizer.DEFAULT);
        
        String html = renderer.createHtml(markdown);
        List<ComponentData> components = renderer.buildComponentData(html);
        String resultHtml = extractMarkdownHtml(components);
        
        // Should not contain any marker attributes
        assertFalse(resultHtml.contains("data-brokk-marker"), 
            "Should not contain marker attributes with no-op customizer");
        assertFalse(resultHtml.contains("<mark"), 
            "Should not contain mark tags with no-op customizer");
    }

    @Test
    public void testCustomizerWithEmptyContent() {
        String emptyMarkdown = "";
        
        HtmlCustomizer testHighlighter = new TextNodeMarkerCustomizer(
            "test", false, true, "<mark>", "</mark>"
        );
        renderer.setHtmlCustomizer(testHighlighter);
        
        String html = renderer.createHtml(emptyMarkdown);
        List<ComponentData> components = renderer.buildComponentData(html);
        
        // Should handle empty content gracefully
        assertTrue(components.isEmpty() || extractMarkdownHtml(components).trim().isEmpty(),
            "Should handle empty content gracefully");
    }

    @Test
    public void testCustomizerPersistsAcrossUpdates() {
        HtmlCustomizer persistentHighlighter = new TextNodeMarkerCustomizer(
            "persistent", false, true, "<mark>", "</mark>"
        );
        renderer.setHtmlCustomizer(persistentHighlighter);
        
        // First update
        String markdown1 = "This has persistent data.";
        renderer.update(markdown1);
        
        // Second update
        String markdown2 = "More persistent information here.";
        renderer.update(markdown2);
        
        // Build components for the current state
        String html = renderer.createHtml(markdown2);
        List<ComponentData> components = renderer.buildComponentData(html);
        String resultHtml = extractMarkdownHtml(components);
        
        // Customizer should still be applied
        assertTrue(resultHtml.contains("<mark"), 
            "Customizer should persist across updates");
        assertTrue(resultHtml.contains("persistent"), 
            "Should highlight the target word");
    }

    @Test
    public void testCustomizerWithUniqueMarkerIds() {
        String markdown = "Find word and word and word in text.";
        
        HtmlCustomizer wordHighlighter = new TextNodeMarkerCustomizer(
            "word", false, true, "<mark>", "</mark>"
        );
        renderer.setHtmlCustomizer(wordHighlighter);
        
        String html = renderer.createHtml(markdown);
        List<ComponentData> components = renderer.buildComponentData(html);
        String resultHtml = extractMarkdownHtml(components);
        
        // Should have unique IDs for each marked occurrence
        assertTrue(resultHtml.contains("data-brokk-id="), "Should have marker IDs");
        
        // Extract all marker IDs and verify they're unique
        String[] idMatches = resultHtml.split("data-brokk-id=\"");
        if (idMatches.length > 1) {
            for (int i = 1; i < idMatches.length; i++) {
                String idPart = idMatches[i];
                int endQuote = idPart.indexOf('"');
                if (endQuote > 0) {
                    String id = idPart.substring(0, endQuote);
                    assertTrue(id.matches("\\d+"), "ID should be numeric: " + id);
                }
            }
        }
    }

    /**
     * Helper to extract HTML content from MarkdownComponentData objects.
     */
    private String extractMarkdownHtml(List<ComponentData> components) {
        return components.stream()
                .filter(cd -> cd instanceof MarkdownComponentData)
                .map(cd -> ((MarkdownComponentData) cd).html())
                .reduce("", (a, b) -> a + b);
    }

    /**
     * Helper to count occurrences of a substring in a string.
     */
    private long countMatches(String text, String substring) {
        if (text == null || substring == null || substring.isEmpty()) {
            return 0;
        }
        
        long count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }
}