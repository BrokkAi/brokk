package io.github.jbellis.brokk.gui.mop.stream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CompositeHtmlCustomizer, particularly the deduplication functionality.
 */
class CompositeHtmlCustomizerTest {

    private static class TestCustomizer implements HtmlCustomizer {
        private final int id;
        private int callCount = 0;

        TestCustomizer(int id) {
            this.id = id;
        }

        @Override
        public void customize(Element root) {
            callCount++;
            root.attr("test-customizer-" + id, String.valueOf(callCount));
        }

        @Override
        public int getCustomizerId() {
            return id;
        }

        int getCallCount() {
            return callCount;
        }
    }

    @Test
    void testDeduplication() {
        var customizer1a = new TestCustomizer(100);
        var customizer1b = new TestCustomizer(100); // Same ID as 1a
        var customizer2 = new TestCustomizer(200);

        // Create composite with duplicates
        var composite = new CompositeHtmlCustomizer(customizer1a, customizer1b, customizer2);

        // Create test HTML
        var doc = Jsoup.parse("<html><body><p>Test content</p></body></html>");
        var body = doc.body();

        // Apply customizers
        composite.customize(body);

        // Verify that only the first customizer with ID 100 was called
        assertEquals(1, customizer1a.getCallCount(), "First customizer with ID 100 should be called once");
        assertEquals(0, customizer1b.getCallCount(), "Second customizer with ID 100 should not be called (duplicate)");
        assertEquals(1, customizer2.getCallCount(), "Customizer with ID 200 should be called once");

        // Verify attributes were set correctly
        assertTrue(body.hasAttr("test-customizer-100"), "Should have attribute from customizer 100");
        assertTrue(body.hasAttr("test-customizer-200"), "Should have attribute from customizer 200");
        assertEquals("1", body.attr("test-customizer-100"), "Customizer 100 should have been called exactly once");
        assertEquals("1", body.attr("test-customizer-200"), "Customizer 200 should have been called exactly once");
    }

    @Test
    void testNoDuplicatesPreserveOrder() {
        var customizer1 = new TestCustomizer(100);
        var customizer2 = new TestCustomizer(200);
        var customizer3 = new TestCustomizer(300);

        var composite = new CompositeHtmlCustomizer(customizer1, customizer2, customizer3);

        var doc = Jsoup.parse("<html><body><p>Test content</p></body></html>");
        var body = doc.body();

        composite.customize(body);

        // All should be called once
        assertEquals(1, customizer1.getCallCount());
        assertEquals(1, customizer2.getCallCount());
        assertEquals(1, customizer3.getCallCount());
    }

    @Test
    void testNullCustomizersAreFiltered() {
        var customizer1 = new TestCustomizer(100);
        var customizer2 = new TestCustomizer(200);

        var composite = new CompositeHtmlCustomizer(customizer1, null, customizer2, null);

        var doc = Jsoup.parse("<html><body><p>Test content</p></body></html>");
        var body = doc.body();

        composite.customize(body);

        assertEquals(1, customizer1.getCallCount());
        assertEquals(1, customizer2.getCallCount());
    }

    @Test
    void testCompositeCustomizerHasUniqueId() {
        var composite = new CompositeHtmlCustomizer();
        assertEquals(1000, composite.getCustomizerId(), "CompositeHtmlCustomizer should have reserved ID 1000");
    }
}