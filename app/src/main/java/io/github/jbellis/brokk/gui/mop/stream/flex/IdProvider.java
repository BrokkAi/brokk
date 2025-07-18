package io.github.jbellis.brokk.gui.mop.stream.flex;

import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.DataKey;

/**
 * Provides stable, deterministic IDs for markdown blocks.
 * 
 * These IDs are used to track components across re-renders, allowing the 
 * incremental renderer to reuse existing Swing components when their 
 * content hasn't changed.
 */
public class IdProvider {
    // logger field removed as it was unused
    
    /**
     * DataKey for storing/retrieving the IdProvider from Flexmark's parser context.
     */
    public static final DataKey<IdProvider> ID_PROVIDER = new DataKey<>("ID_PROVIDER", new IdProvider());
    
    /**
     * Generates a stable ID for a node based on its source position.
     * This ensures that the same block of text will always get the same ID,
     * even if surrounding content changes.
     * 
     * @param node the Flexmark node to generate an ID for
     * @return a deterministic integer ID
     */
    public int getId(Node node) {
        // Use the start offset as a basis for the ID
        // This ensures the same physical block gets the same ID even if content above it changes
        int startOffset = node.getStartOffset();
        
        // Include node type name in the hash calculation
        String nodeType = node.getClass().getSimpleName();
        
        // Combine node type and position for a more unique ID
        int typeHash = nodeType.hashCode();
        int id = Math.abs(31 * startOffset + typeHash);
        return id;
    }
    
    /**
     * Generates a stable ID for a JSoup Element based on its string representation
     * and position in the document.
     * 
     * @param element the JSoup element to generate an ID for
     * @return a deterministic integer ID
     */
    public int getId(org.jsoup.nodes.Element element) {
        // Use element's tag name, attributes, and class names to create a stable hash
        String tagName = element.tagName();
        String attributes = element.attributes().toString();
        
        // Combine these characteristics for a unique fingerprint
        int hash = tagName.hashCode();
        hash = 31 * hash + attributes.hashCode();
        
        // Use element's source position if available (elementSiblingIndex is the position among siblings)
        int position = element.elementSiblingIndex();
        hash = 31 * hash + position;
        
        return Math.abs(hash);
    }
}
