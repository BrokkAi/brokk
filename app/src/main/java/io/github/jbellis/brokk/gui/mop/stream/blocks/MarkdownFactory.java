package io.github.jbellis.brokk.gui.mop.stream.blocks;

import org.jsoup.nodes.Element;

/**
 * Factory for creating MarkdownComponentData instances.
 * Handles both explicit "markdown" tags and plain text nodes.
 */
public class MarkdownFactory implements ComponentDataFactory {
    @Override
    public String tagName() {
        return "markdown";
    }
    
    @Override
    public ComponentData fromElement(Element element) {
        throw new UnsupportedOperationException("Direct parsing of <markdown> tags is not supported");
    }
    
    /**
     * Creates a MarkdownComponentData from plain text with an explicit ID.
     */
    public ComponentData fromText(int id, String html) {
        return new MarkdownComponentData(id, html);
    }
}
