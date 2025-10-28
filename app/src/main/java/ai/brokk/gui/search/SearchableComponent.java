package ai.brokk.gui.search;

import javax.swing.*;
import org.jetbrains.annotations.Nullable;

/**
 * Interface for components that can be searched by a search bar. This provides a minimal contract for search
 * functionality without tying the search bar to specific component implementations.
 */
public interface SearchableComponent {

    /**
     * Gets the text content of this component for searching. This method is synchronous and should return immediately.
     */
    String getText();

    /**
     * Gets the currently selected text, if any. This method is synchronous and should return immediately.
     *
     * @return the selected text, or null if no text is selected
     */
    @Nullable
    String getSelectedText();

    /** Gets the current caret position in the text. This method is synchronous and should return immediately. */
    int getCaretPosition();

    /** Sets the caret position in the text. This method is synchronous and should complete immediately. */
    void setCaretPosition(int position);

    /** Requests focus for this component. This method is synchronous and should complete immediately. */
    void requestFocusInWindow();

    /** Interface for receiving search completion callbacks. */
    interface SearchCompleteCallback {
        /**
         * Called when search highlighting is complete and results are available.
         *
         * @param totalMatches the total number of matches found
         * @param currentMatchIndex the current match index (1-based), or 0 if no current match
         */
        void onSearchComplete(int totalMatches, int currentMatchIndex);

        /**
         * Called when a search operation encounters an error.
         *
         * @param error description of the error that occurred
         */
        default void onSearchError(String error) {
            // Default implementation does nothing - components can override for error handling
        }

        /** Called when a search operation starts. */
        default void onSearchStart() {
            // Default implementation does nothing - components can override for start notifications
        }

        /** A no-op callback that can be used when a callback is required but no notification is desired. */
        SearchCompleteCallback NONE = (totalMatches, currentMatchIndex) -> {};
    }

    /** Interface for receiving search navigation callbacks. */
    interface SearchNavigationCallback {
        /**
         * Called when the user navigates to a search result.
         *
         * @param caretPosition the new caret position after navigation
         */
        void onSearchNavigation(int caretPosition);

        /** A no-op callback that can be used when a callback is required but no notification is desired. */
        SearchNavigationCallback NONE = (caretPosition) -> {};
    }

    /**
     * Sets a callback to be notified when search operations complete. All SearchableComponent implementations must
     * support this async pattern. Synchronous implementations should call the callback immediately.
     *
     * @param callback the callback to notify when operations complete
     */
    void setSearchCompleteCallback(@Nullable SearchCompleteCallback callback);

    /**
     * Sets a callback to be notified when search navigation occurs.
     *
     * @param callback the callback to notify when navigation occurs
     */
    default void setSearchNavigationCallback(@Nullable SearchNavigationCallback callback) {
        // Default implementation does nothing - components can override if they support navigation callbacks
    }

    /**
     * Convenience method to notify immediate feedback when starting a search. This method is synchronous and executes
     * immediately. This can be used by implementations to provide instant UI feedback.
     *
     * @param searchText the search text being processed
     */
    default void notifySearchStart(String searchText) {
        var callback = getSearchCompleteCallback();
        if (callback != null && !searchText.isEmpty()) {
            callback.onSearchStart();
        }
    }

    /**
     * Gets the current search complete callback. Useful for implementations that need to access the callback.
     *
     * @return the current callback, or null if none is set
     */
    @Nullable
    default SearchCompleteCallback getSearchCompleteCallback() {
        return SearchCompleteCallback.NONE; // Default implementation that never returns null
    }

    /**
     * Highlights all occurrences of the search text in the component. This operation is asynchronous - completion will
     * be signaled via the SearchCompleteCallback.
     *
     * @param searchText the text to search for
     * @param caseSensitive whether the search should be case-sensitive
     */
    void highlightAll(String searchText, boolean caseSensitive);

    /**
     * Clears all search highlights. This method is synchronous and should complete immediately. Visual updates should
     * be applied before this method returns.
     */
    void clearHighlights();

    /**
     * Finds the next occurrence of the search text relative to the current position. This method has hybrid behavior:
     * it returns synchronously but may trigger SearchCompleteCallback asynchronously if the match index changes.
     *
     * <p>For async implementations, this method should still return immediately with a boolean indicating if navigation
     * was initiated successfully.
     *
     * @param searchText the text to search for
     * @param caseSensitive whether the search should be case-sensitive
     * @param forward true to search forward, false to search backward
     * @return true if a match was found and the caret was moved to it, false otherwise
     */
    boolean findNext(String searchText, boolean caseSensitive, boolean forward);

    /**
     * Centers the current caret position in the viewport if this component is scrollable. This method is synchronous
     * and should complete immediately. This is called after a successful search to ensure the match is visible.
     */
    default void centerCaretInView() {
        // Default implementation does nothing - components can override if they support scrolling
    }

    /**
     * Gets the underlying Swing component for event handling and parent component operations. This method is
     * synchronous and should return immediately.
     */
    JComponent getComponent();
}
