package io.github.jbellis.brokk.gui.search;

import java.awt.Component;

/**
 * Represents a search match within a MarkdownOutputPanel structure.
 * Contains positioning information for proper visual ordering of matches.
 */
public record SearchMatch(
    Type type,
    Component actualUiComponent, // The JEditorPane or RSyntaxTextArea
    int panelIndex,
    int rendererIndex,
    int componentVisualOrderInRenderer, // Index of actualUiComponent in renderer.getRoot().getComponents()
    int subComponentIndex, // For distinguishing multiple components at same position
    // For MARKDOWN:
    int markerId,
    // For CODE:
    RTextAreaSearchableComponent codeSearchable,
    int startOffset,
    int endOffset
) implements Comparable<SearchMatch> {
    
    public enum Type { MARKDOWN, CODE }

    // Markdown Match constructor
    public SearchMatch(int markerId, Component actualUiComponent, int panelIndex, int rendererIndex, 
          int componentVisualOrderInRenderer, int subComponentIndex) {
        this(Type.MARKDOWN, actualUiComponent, panelIndex, rendererIndex, componentVisualOrderInRenderer, 
             subComponentIndex, markerId, null, -1, -1);
    }

    // Code Match constructor
    public SearchMatch(RTextAreaSearchableComponent codeSearchable, int startOffset, int endOffset, 
          Component actualUiComponent, int panelIndex, int rendererIndex, 
          int componentVisualOrderInRenderer, int subComponentIndex) {
        this(Type.CODE, actualUiComponent, panelIndex, rendererIndex, componentVisualOrderInRenderer, 
             subComponentIndex, -1, codeSearchable, startOffset, endOffset);
    }

    @Override
    public int compareTo(SearchMatch other) {
        // First compare by panel
        int panelCmp = Integer.compare(this.panelIndex, other.panelIndex);
        if (panelCmp != 0) return panelCmp;

        // Then by renderer
        int rendererCmp = Integer.compare(this.rendererIndex, other.rendererIndex);
        if (rendererCmp != 0) return rendererCmp;

        // Then by component position in renderer
        int componentOrderCmp = Integer.compare(this.componentVisualOrderInRenderer, other.componentVisualOrderInRenderer);
        if (componentOrderCmp != 0) return componentOrderCmp;
        
        // Then by sub-component index (for multiple components at same position)
        int subComponentCmp = Integer.compare(this.subComponentIndex, other.subComponentIndex);
        if (subComponentCmp != 0) return subComponentCmp;

        // Finally, within the same exact position, order by content position
        if (this.type == Type.MARKDOWN && other.type == Type.MARKDOWN) {
            return Integer.compare(this.markerId, other.markerId);
        } else if (this.type == Type.CODE && other.type == Type.CODE) {
            return Integer.compare(this.startOffset, other.startOffset);
        } else {
            // When types differ at same position and Y, we need additional context
            // For now, maintain stable ordering without type preference
            return 0; // Consider them equal, maintain original order
        }
    }
}