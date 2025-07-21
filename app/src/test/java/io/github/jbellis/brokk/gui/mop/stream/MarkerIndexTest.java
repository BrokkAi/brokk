package io.github.jbellis.brokk.gui.mop.stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that IncrementalBlockRenderer can locate Swing components by marker id.
 */
public class MarkerIndexTest {

    private IncrementalBlockRenderer renderer;

    @BeforeEach
    public void setUp() {
        renderer = new IncrementalBlockRenderer(false);
    }

    @Test
    public void testFindByMarkerId() throws Exception {
        renderer.setHtmlCustomizer(new TextNodeMarkerCustomizer("alpha", true, true,
                                                                "<mark>", "</mark>"));

        // Run update on EDT to ensure components are created
        SwingUtilities.invokeAndWait(() -> renderer.update("one alpha two"));

        var ids = renderer.getIndexedMarkerIds();
        assertEquals(1, ids.size(), "Exactly one marker id expected");
        int id = ids.iterator().next();

        AtomicReference<JComponent> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
                ref.set(renderer.findByMarkerId(id).orElse(null)));

        assertNotNull(ref.get(), "Renderer should resolve component for marker id");
    }

    @Test
    public void testMultipleMarkerIds() throws Exception {
        renderer.setHtmlCustomizer(new TextNodeMarkerCustomizer("test", false, true,
                                                                "<mark>", "</mark>"));

        // Text with multiple occurrences of 'test'
        SwingUtilities.invokeAndWait(() -> 
            renderer.update("First test, second test, third test."));

        var ids = renderer.getIndexedMarkerIds();
        assertEquals(3, ids.size(), "Should have three marker ids");

        // Verify each id resolves to a component
        AtomicReference<Integer> foundComponents = new AtomicReference<>(0);
        SwingUtilities.invokeAndWait(() -> {
            int count = 0;
            for (int id : ids) {
                if (renderer.findByMarkerId(id).isPresent()) {
                    count++;
                }
            }
            foundComponents.set(count);
        });

        assertEquals(3, foundComponents.get(), "All marker ids should resolve to components");
    }

    @Test
    public void testNoMarkersWhenNoCustomizer() throws Exception {
        // No customizer set
        SwingUtilities.invokeAndWait(() -> renderer.update("text with potential markers"));

        var ids = renderer.getIndexedMarkerIds();
        assertTrue(ids.isEmpty(), "Should have no marker ids without customizer");
    }

    @Test
    public void testNoMarkersWhenNoMatches() throws Exception {
        renderer.setHtmlCustomizer(new TextNodeMarkerCustomizer("notfound", false, true,
                                                                "<mark>", "</mark>"));

        SwingUtilities.invokeAndWait(() -> renderer.update("text with no matches"));

        var ids = renderer.getIndexedMarkerIds();
        assertTrue(ids.isEmpty(), "Should have no marker ids when search term not found");
    }

    @Test
    public void testMarkerIdNotFound() throws Exception {
        renderer.setHtmlCustomizer(new TextNodeMarkerCustomizer("test", false, true,
                                                                "<mark>", "</mark>"));

        SwingUtilities.invokeAndWait(() -> renderer.update("test content"));

        AtomicReference<Boolean> notFound = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            // Try to find a marker id that doesn't exist
            int nonExistentId = 99999;
            notFound.set(renderer.findByMarkerId(nonExistentId).isEmpty());
        });

        assertTrue(notFound.get(), "Should return empty Optional for non-existent marker id");
    }

    @Test
    public void testMarkerIndexRebuildAfterUpdate() throws Exception {
        renderer.setHtmlCustomizer(new TextNodeMarkerCustomizer("word", false, true,
                                                                "<mark>", "</mark>"));

        // Initial content
        SwingUtilities.invokeAndWait(() -> renderer.update("first word"));
        
        var initialIds = renderer.getIndexedMarkerIds();
        assertEquals(1, initialIds.size(), "Should have one marker initially");

        // Update with more content
        SwingUtilities.invokeAndWait(() -> renderer.update("first word second word"));
        
        var updatedIds = renderer.getIndexedMarkerIds();
        assertEquals(2, updatedIds.size(), "Should have two markers after update");

        // Verify all new ids resolve to components
        AtomicReference<Boolean> allResolved = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            boolean resolved = updatedIds.stream()
                .allMatch(id -> renderer.findByMarkerId(id).isPresent());
            allResolved.set(resolved);
        });

        assertTrue(allResolved.get(), "All updated marker ids should resolve to components");
    }

    @Test
    public void testMarkerIndexAfterCustomizerChange() throws Exception {
        // Start with one customizer
        renderer.setHtmlCustomizer(new TextNodeMarkerCustomizer("alpha", false, true,
                                                                "<mark>", "</mark>"));

        SwingUtilities.invokeAndWait(() -> renderer.update("alpha beta gamma"));
        
        var alphaIds = renderer.getIndexedMarkerIds();
        assertEquals(1, alphaIds.size(), "Should have one marker for alpha");

        // Switch to different customizer
        renderer.setHtmlCustomizer(new TextNodeMarkerCustomizer("beta", false, true,
                                                                "<mark>", "</mark>"));

        SwingUtilities.invokeAndWait(() -> renderer.reprocessForCustomizer());
        
        // Note: reprocessForCustomizer is async, so we test the core buildComponentData method
        var html = renderer.createHtml("alpha beta gamma");
        var components = renderer.buildComponentData(html);
        
        // Extract HTML and verify beta is highlighted, not alpha
        String resultHtml = components.stream()
            .filter(cd -> cd instanceof io.github.jbellis.brokk.gui.mop.stream.blocks.MarkdownComponentData)
            .map(cd -> ((io.github.jbellis.brokk.gui.mop.stream.blocks.MarkdownComponentData) cd).html())
            .reduce("", (a, b) -> a + b);
        
        assertTrue(resultHtml.contains("beta"), "Should contain beta");
        assertFalse(resultHtml.contains("<mark>alpha"), "Should not highlight alpha anymore");
    }

    @Test
    public void testMarkerIndexWithComplexContent() throws Exception {
        renderer.setHtmlCustomizer(new TextNodeMarkerCustomizer("item", false, true,
                                                                "<mark>", "</mark>"));

        String complexMarkdown = """
            # Header with item
            
            List of items:
            * First item
            * Second item  
            * Third item
            
            Code block (should not match):
            ```
            item = "value"
            ```
            
            Final item in text.
            """;

        SwingUtilities.invokeAndWait(() -> renderer.update(complexMarkdown));

        var ids = renderer.getIndexedMarkerIds();
        assertTrue(ids.size() >= 3, "Should have at least 3 markers (excluding code block)");
        assertTrue(ids.size() <= 5, "Should have at most 5 markers");

        // Verify all ids resolve to components
        AtomicReference<Boolean> allResolved = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            boolean resolved = ids.stream()
                .allMatch(id -> renderer.findByMarkerId(id).isPresent());
            allResolved.set(resolved);
        });

        assertTrue(allResolved.get(), "All marker ids should resolve to components in complex content");
    }

    @Test
    public void testMarkerIndexAfterCompaction() throws Exception {
        renderer.setHtmlCustomizer(new TextNodeMarkerCustomizer("target", false, true,
                                                                "<mark>", "</mark>"));

        String markdown = "First target and second target.";
        SwingUtilities.invokeAndWait(() -> renderer.update(markdown));

        var preCompactionIds = renderer.getIndexedMarkerIds();
        assertEquals(2, preCompactionIds.size(), "Should have two markers before compaction");

        // Build compacted snapshot
        var compactedComponents = renderer.buildCompactedSnapshot(1L);
        
        if (compactedComponents != null) {
            SwingUtilities.invokeAndWait(() -> 
                renderer.applyCompactedSnapshot(compactedComponents, 1L));

            var postCompactionIds = renderer.getIndexedMarkerIds();
            assertEquals(2, postCompactionIds.size(), "Should still have two markers after compaction");

            // Verify all ids still resolve to components
            AtomicReference<Boolean> allResolved = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                boolean resolved = postCompactionIds.stream()
                    .allMatch(id -> renderer.findByMarkerId(id).isPresent());
                allResolved.set(resolved);
            });

            assertTrue(allResolved.get(), "All marker ids should resolve after compaction");
        }
    }

    @Test
    public void testEmptyMarkerIndexInitially() throws Exception {
        // New renderer should have empty marker index
        var ids = renderer.getIndexedMarkerIds();
        assertTrue(ids.isEmpty(), "New renderer should have no marker ids");

        AtomicReference<Boolean> notFound = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            notFound.set(renderer.findByMarkerId(1).isEmpty());
        });

        assertTrue(notFound.get(), "Should not find any marker id in empty renderer");
    }

    @Test
    public void testMarkerIndexImmutability() throws Exception {
        renderer.setHtmlCustomizer(new TextNodeMarkerCustomizer("test", false, true,
                                                                "<mark>", "</mark>"));

        SwingUtilities.invokeAndWait(() -> renderer.update("test content"));

        var ids = renderer.getIndexedMarkerIds();
        assertEquals(1, ids.size(), "Should have one marker");

        // Try to modify the returned set (should be immutable)
        assertThrows(UnsupportedOperationException.class, () -> {
            ids.add(999);
        }, "Returned marker ids set should be immutable");
    }
}
