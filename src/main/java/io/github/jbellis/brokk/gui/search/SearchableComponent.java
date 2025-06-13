package io.github.jbellis.brokk.gui.search;

import javax.swing.*;
import org.jetbrains.annotations.Nullable;

/**
 * Interface for components that can be searched by a search bar.
 * This provides a minimal contract for search functionality without
 * tying the search bar to specific component implementations.
 */
public interface SearchableComponent {

    /**
     * Gets the text content of this component for searching.
     */
    String getText();

    /**
     * Gets the currently selected text, if any.
     *
     * @return the selected text, or null if no text is selected
     */
    @Nullable
    String getSelectedText();

    /**
     * Gets the current caret position in the text.
     */
    int getCaretPosition();

    /**
     * Sets the caret position in the text.
     */
    void setCaretPosition(int position);

    /**
     * Requests focus for this component.
     */
    void requestFocusInWindow();

    /**
     * Highlights all occurrences of the search text in the component.
     *
     * @param searchText the text to search for
     * @param caseSensitive whether the search should be case-sensitive
     */
    void highlightAll(String searchText, boolean caseSensitive);

    /**
     * Clears all search highlights.
     */
    void clearHighlights();

    /**
     * Finds the next occurrence of the search text relative to the current position.
     *
     * @param searchText the text to search for
     * @param caseSensitive whether the search should be case-sensitive
     * @param forward true to search forward, false to search backward
     * @return true if a match was found and the caret was moved to it, false otherwise
     */
    boolean findNext(String searchText, boolean caseSensitive, boolean forward);

    /**
     * Centers the current caret position in the viewport if this component is scrollable.
     * This is called after a successful search to ensure the match is visible.
     */
    default void centerCaretInView() {
        // Default implementation does nothing - components can override if they support scrolling
    }

    /**
     * Gets the underlying Swing component for event handling and parent component operations.
     */
    JComponent getComponent();

    /**
     * Counts the total number of matches for the given search text.
     */
    int countMatches(String searchText, boolean caseSensitive);

    /**
     * Gets the current match index (1-based) after a findNext operation.
     * Returns 0 if no match is currently selected.
     */
    int getCurrentMatchIndex(String searchText, boolean caseSensitive);
}
