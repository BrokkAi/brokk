package io.github.jbellis.brokk.gui.search;

import java.awt.Component;

/**
 * Represents a search match within a MarkdownOutputPanel structure.
 * Contains positioning information for proper visual ordering of matches.
 */
public sealed interface SearchMatch extends Comparable<SearchMatch>
    permits MarkdownSearchMatch, CodeSearchMatch {

    /**
     * Gets the actual UI component containing the match.
     */
    Component actualUiComponent();

    /**
     * Gets the panel index for visual ordering.
     */
    int panelIndex();

    /**
     * Gets the renderer index within the panel.
     */
    int rendererIndex();

    /**
     * Gets the component's visual order within the renderer.
     */
    int componentVisualOrderInRenderer();

    /**
     * Default comparison implementation for visual ordering.
     */
    @Override
    default int compareTo(SearchMatch other) {
        // First compare by panel
        int panelCmp = Integer.compare(this.panelIndex(), other.panelIndex());
        if (panelCmp != 0) return panelCmp;

        // Then by renderer
        int rendererCmp = Integer.compare(this.rendererIndex(), other.rendererIndex());
        if (rendererCmp != 0) return rendererCmp;

        // Then by component position in renderer
        int componentOrderCmp = Integer.compare(this.componentVisualOrderInRenderer(), other.componentVisualOrderInRenderer());
        if (componentOrderCmp != 0) return componentOrderCmp;

        // Handle sub-component ordering for code matches
        if (this instanceof CodeSearchMatch thisCode && other instanceof CodeSearchMatch otherCode) {
            int subComponentCmp = Integer.compare(thisCode.subComponentIndex(), otherCode.subComponentIndex());
            if (subComponentCmp != 0) return subComponentCmp;
        }

        // Finally, within the same exact position, order by content position
        if (this instanceof MarkdownSearchMatch thisMarkdown && other instanceof MarkdownSearchMatch otherMarkdown) {
            return Integer.compare(thisMarkdown.markerId(), otherMarkdown.markerId());
        } else if (this instanceof CodeSearchMatch thisCode && other instanceof CodeSearchMatch otherCode) {
            return Integer.compare(thisCode.startOffset(), otherCode.startOffset());
        } else {
            // When types differ at same position, maintain stable ordering without type preference
            return 0; // Consider them equal, maintain original order
        }
    }
}

/**
 * Represents a search match in markdown content.
 */
record MarkdownSearchMatch(
    int markerId,
    Component actualUiComponent,
    int panelIndex,
    int rendererIndex,
    int componentVisualOrderInRenderer,
    int subComponentIndex
) implements SearchMatch {

    /**
     * Creates a markdown search match.
     */
    public MarkdownSearchMatch(int markerId, Component actualUiComponent, int panelIndex, int rendererIndex,
                             int componentVisualOrderInRenderer, int subComponentIndex) {
        this.markerId = markerId;
        this.actualUiComponent = actualUiComponent;
        this.panelIndex = panelIndex;
        this.rendererIndex = rendererIndex;
        this.componentVisualOrderInRenderer = componentVisualOrderInRenderer;
        this.subComponentIndex = subComponentIndex;
    }
}

/**
 * Represents a search match in code content.
 */
record CodeSearchMatch(
    RTextAreaSearchableComponent codeSearchable,
    int startOffset,
    int endOffset,
    Component actualUiComponent,
    int panelIndex,
    int rendererIndex,
    int componentVisualOrderInRenderer,
    int subComponentIndex
) implements SearchMatch {

    /**
     * Creates a code search match.
     */
    public CodeSearchMatch(RTextAreaSearchableComponent codeSearchable, int startOffset, int endOffset,
                         Component actualUiComponent, int panelIndex, int rendererIndex,
                         int componentVisualOrderInRenderer, int subComponentIndex) {
        this.codeSearchable = codeSearchable;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.actualUiComponent = actualUiComponent;
        this.panelIndex = panelIndex;
        this.rendererIndex = rendererIndex;
        this.componentVisualOrderInRenderer = componentVisualOrderInRenderer;
        this.subComponentIndex = subComponentIndex;
    }
}
