package io.github.jbellis.brokk.gui.mop.stream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple sanity-checks for {@link SymbolBadgeCustomizer}.
 */
public class SymbolBadgeCustomizerTest {

    private Element apply(String html) {
        Document doc = Jsoup.parse(html);
        Element body  = doc.body();
        var customizer = SymbolBadgeCustomizer.create(new MockContextManager());
        customizer.markStreamingComplete();
        customizer.customize(body);
        return body;
    }

    @Test
    public void testAnchorWithDataSymbolIdGetsBadge() {
        var body = apply("<p><a href=\"#\" " + BadgeConstants.ATTR_DATA_SYMBOL_ID + "=\"Foo\">Foo</a></p>");
        assertEquals(1, body.select("a." + BadgeConstants.CLASS_CLICKABLE_BADGE + "." + BadgeConstants.CLASS_BADGE_SYMBOL).size(),
                     "Anchor with data-symbol-id should become a clickable symbol badge");
    }

    @Test
    public void testAnchorWithSymbolTextGetsAttributeAndBadge() {
        var body = apply("<p><a href=\"#\">Foo.Bar</a></p>");
        var anchor = body.selectFirst("a");
        assertNotNull(anchor.attr(BadgeConstants.ATTR_DATA_SYMBOL_ID), "SymbolId attribute should be added");
        assertFalse(anchor.attr(BadgeConstants.ATTR_DATA_SYMBOL_ID).isBlank());
        assertTrue(anchor.hasClass(BadgeConstants.CLASS_CLICKABLE_BADGE), "Anchor should become clickable");
        assertTrue(anchor.hasClass(BadgeConstants.CLASS_BADGE_SYMBOL), "Anchor should have symbol badge class");
    }

    @Test
    public void testInlineCodeSymbolGetsBadge() {
        var body = apply("<p>Call <code>Foo.bar()</code> now</p>");
        assertEquals(1, body.select("a." + BadgeConstants.CLASS_CLICKABLE_BADGE + "." + BadgeConstants.CLASS_BADGE_SYMBOL).size(),
                     "Inline code symbol should become a clickable symbol badge");
    }
    
}
