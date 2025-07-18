package io.github.jbellis.brokk.gui.mop.stream.blocks;

import org.jsoup.nodes.Element;

/**
 * Factory for creating ComponentData instances from HTML elements.
 */
public interface ComponentDataFactory {
    /**
     * Returns the HTML tag name this factory handles.
     */
    String tagName();
    
    /**
     * Creates a ComponentData instance from the given HTML element.
     */
    ComponentData fromElement(Element element);
}
