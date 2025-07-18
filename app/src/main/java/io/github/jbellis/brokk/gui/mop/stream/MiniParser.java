package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.gui.mop.stream.blocks.ComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.ComponentDataFactory;
import io.github.jbellis.brokk.gui.mop.stream.blocks.CompositeComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.MarkdownFactory;
import io.github.jbellis.brokk.gui.mop.stream.flex.IdProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A parser that processes a Jsoup Element tree and extracts custom tags (like code-fence, edit-block) 
 * even when they're nested inside regular HTML elements.
 * 
 * This creates a "mini-tree" representation per top-level element, allowing the incremental renderer
 * to properly handle nested custom blocks while maintaining its flat list architecture.
 */
public class MiniParser {
    private static final Logger logger = LogManager.getLogger(MiniParser.class);
    private static final AtomicInteger snippetIdGen = new AtomicInteger(1000);
    
    private final IdProvider idProvider;
    private Map<Integer, int[]> ordinalByAnchor = new HashMap<>();

    public MiniParser(IdProvider idProvider) {
        this.idProvider = idProvider;
    }

    /**
     * Parses a top-level HTML element and extracts all custom tags within it.
     * 
     * @param topLevel The top-level HTML element to parse
     * @param mdFactory The factory for creating markdown components
     * @param factories Map of tag names to their component factories
     * @return A list of ComponentData objects (usually a single composite if nested tags are found)
     */
    public List<ComponentData> parse(Element topLevel,
                                    MarkdownFactory mdFactory,
                                    Map<String, ComponentDataFactory> factories) { // idProvider removed from parameters
        
        this.ordinalByAnchor = new HashMap<>(); // Reset for each parse operation
        
        var childrenData = new ArrayList<ComponentData>();
        var sb = new StringBuilder();
        
        // Recursively walk the element tree
        walkElement(topLevel, sb, childrenData, mdFactory, factories);
        
        // Flush any remaining HTML text
        if (sb.length() > 0) {
            // Pass the current anchor if it exists, otherwise the top level element
            flushMarkdown(currentAnchor != null ? currentAnchor : topLevel, sb, childrenData, mdFactory);
        }
        
        // If we found any nested custom tags, wrap in a composite
        if (childrenData.size() > 1) {
            // At least one custom tag was found inside, so create a composite
            return List.of(new CompositeComponentData(generateIdForSnippet(), childrenData));
        } else if (childrenData.size() == 1) {
            // Just one component (either all text or a single custom tag)
            return childrenData;
        } else {
            // Empty element (shouldn't happen)
            return List.of();
        }
    }
    
    /**
     * Recursively walks an element tree, extracting custom tags and regular HTML.
     * 
     * @param node Current node being processed
     * @param sb StringBuilder accumulating regular HTML
     * @param childrenData List to collect ComponentData objects
     * @param mdFactory Factory for creating markdown components
     * @param factories Map of custom tag factories
     */
    private void walkElement(Node node, 
                            StringBuilder sb, 
                            List<ComponentData> childrenData,
                            MarkdownFactory mdFactory,
                            Map<String, ComponentDataFactory> factories) {
        
        if (node instanceof Element element) {
            String tagName = element.tagName();
            
            // Check if this is a registered custom tag
            if (factories.containsKey(tagName)) {
                // Flush any accumulated HTML first
                if (sb.length() > 0) {
                    flushMarkdown(node, sb, childrenData, mdFactory);
                }
                
                // Create component for this custom tag
                var factory = factories.get(tagName);
                try {
                    ComponentData component = factory.fromElement(element);
                    childrenData.add(component);
                } catch (Exception ex) {
                    // Log the error but continue parsing - don't crash the UI
                    logger.warn("Failed to parse {} tag: {}. Error: {}", 
                               tagName, element.outerHtml(), ex.getMessage());
                    
                    // Include the raw HTML in the output so the content isn't lost, but properly escape it
                    ensureAnchor(node, sb);
                    sb.append(org.jsoup.nodes.Entities.escape(element.outerHtml()));
                }
                
            } else {
                // This is a regular HTML element - serialize opening tag
                ensureAnchor(node, sb);
                sb.append("<").append(tagName);
                
                // Add attributes with proper escaping
                element.attributes().forEach(attr -> 
                    sb.append(" ").append(attr.getKey()).append("=\"")
                      .append(org.jsoup.nodes.Entities.escape(attr.getValue())).append("\"")
                );
                
                sb.append(">");
                
                // Recurse into children
                for (var child : element.childNodes()) {
                    walkElement(child, sb, childrenData, mdFactory, factories);
                }
                
                // Add closing tag
                sb.append("</").append(tagName).append(">");
            }
        } else if (node instanceof TextNode textNode) {
            // Just append the text with proper escaping
            ensureAnchor(node, sb);
            sb.append(org.jsoup.nodes.Entities.escape(textNode.getWholeText()));
        }
        // Other node types (comments, etc.) are ignored
    }
    
    // Current anchor node for the in-progress markdown segment
    private @Nullable Node currentAnchor = null;
    
    /**
     * Remembers the first node that contributes to an empty StringBuilder.
     * This node becomes the "anchor" for generating a stable ID.
     * 
     * @param node The current node being processed
     * @param sb The StringBuilder containing accumulated HTML
     */
    private void ensureAnchor(Node node, StringBuilder sb) {
        if (sb.isEmpty() && currentAnchor == null) {
            currentAnchor = node;
        }
    }
    
    /**
     * Flushes the accumulated HTML to a new MarkdownComponentData with a stable ID.
     * 
     * @param anchor The node to derive the stable ID from
     * @param sb The StringBuilder containing HTML to flush
     * @param childrenData List to add the new component to
     * @param mdFactory Factory for creating markdown components
     */
    private void flushMarkdown(Node anchor, StringBuilder sb, List<ComponentData> childrenData, MarkdownFactory mdFactory) {
        if (sb.length() == 0) return;
        
        int id = computeStableId(anchor);
        childrenData.add(mdFactory.fromText(id, sb.toString()));
        
        sb.setLength(0);
        currentAnchor = null;
    }
    
    /**
     * Computes a stable, deterministic ID for a markdown segment
     * based on the anchor node's position in the document.
     * 
     * @param anchor The node to derive the ID from
     * @return A stable integer ID
     */
    private int computeStableId(Node anchor) {
        // We prefer an Element - fall back to parent if anchor is a TextNode
        int baseId;
        if (anchor instanceof Element e) {
            baseId = idProvider.getId(e);
        } else {
            // For a TextNode or other node type, find the nearest Element parent
            Node parent = anchor.parent();
            while (parent != null && !(parent instanceof Element)) {
                parent = parent.parent();
            }
            
            if (parent instanceof Element e) {
                baseId = idProvider.getId(e);
            } else {
                // Fallback if no Element parent found (unlikely)
                baseId = anchor.hashCode() & Integer.MAX_VALUE;
            }
        }
        
        // Track ordinals per baseId (not per Node instance) to handle multiple segments 
        // with the same structural position
        int ordinal = ordinalByAnchor.computeIfAbsent(baseId, k -> new int[1])[0]++;
        
        // Use Objects.hash to combine values safely
        return Math.abs(Objects.hash(baseId, ordinal));
    }
    
    /**
     * Generates a unique ID for a snippet or composite.
     * 
     * @return A unique integer ID
     */
    private int generateIdForSnippet() {
        return snippetIdGen.getAndIncrement();
    }
}
