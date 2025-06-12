package io.github.jbellis.brokk.gui.search;

import javax.swing.*;

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
     * Interface for receiving search completion callbacks.
     */
    interface SearchCompleteCallback {
        /**
         * Called when search highlighting is complete and results are available.
         * 
         * @param totalMatches the total number of matches found
         * @param currentMatchIndex the current match index (1-based), or 0 if no current match
         */
        void onSearchComplete(int totalMatches, int currentMatchIndex);
    }
    
    /**
     * Sets a callback to be notified when search operations complete.
     * All SearchableComponent implementations must support this async pattern.
     * Synchronous implementations should call the callback immediately.
     * 
     * @param callback the callback to notify when operations complete
     */
    void setSearchCompleteCallback(SearchCompleteCallback callback);

    /**
     * Highlights all occurrences of the search text in the component.
     * This operation is asynchronous - completion will be signaled via the SearchCompleteCallback.
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
     * May trigger SearchCompleteCallback if match index changes.
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
}
