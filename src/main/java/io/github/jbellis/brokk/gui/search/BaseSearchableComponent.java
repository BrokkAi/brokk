package io.github.jbellis.brokk.gui.search;

import javax.swing.*;
import org.jetbrains.annotations.Nullable;

/**
 * Base implementation of SearchableComponent with common functionality.
 */
public abstract class BaseSearchableComponent implements SearchableComponent {

    protected String currentSearchTerm = "";
    protected boolean currentCaseSensitive = false;
    @Nullable
    protected SearchCompleteCallback searchCompleteCallback = null;

    @Override
    public void setSearchCompleteCallback(@Nullable SearchCompleteCallback callback) {
        this.searchCompleteCallback = callback;
    }

    @Override
    @Nullable
    public SearchCompleteCallback getSearchCompleteCallback() {
        return searchCompleteCallback;
    }


    /**
     * Notifies the callback that search is complete.
     *
     * @param totalMatches total number of matches found
     * @param currentMatch current match number (1-based)
     */
    protected void notifySearchComplete(int totalMatches, int currentMatch) {
        if (searchCompleteCallback != null) {
            // If already on EDT, call directly; otherwise use invokeLater
            if (SwingUtilities.isEventDispatchThread()) {
                searchCompleteCallback.onSearchComplete(totalMatches, currentMatch);
            } else {
                SwingUtilities.invokeLater(() -> {
                    if (searchCompleteCallback != null) {
                        searchCompleteCallback.onSearchComplete(totalMatches, currentMatch);
                    }
                });
            }
        }
    }

    /**
     * Notifies the callback that an error occurred.
     */
    protected void notifySearchError(String error) {
        if (searchCompleteCallback != null) {
            // If already on EDT, call directly; otherwise use invokeLater
            if (SwingUtilities.isEventDispatchThread()) {
                searchCompleteCallback.onSearchError(error);
            } else {
                SwingUtilities.invokeLater(() -> {
                    if (searchCompleteCallback != null) {
                        searchCompleteCallback.onSearchError(error);
                    }
                });
            }
        }
    }

    /**
     * Checks if the search term or case sensitivity has changed.
     *
     * @param searchText the new search text
     * @param caseSensitive the new case sensitivity
     * @return true if changed, false otherwise
     */
    protected boolean hasSearchChanged(String searchText, boolean caseSensitive) {
        return !searchText.equals(currentSearchTerm) || caseSensitive != currentCaseSensitive;
    }

    /**
     * Updates the current search state.
     *
     * @param searchText the new search text
     * @param caseSensitive the new case sensitivity
     */
    protected void updateSearchState(String searchText, boolean caseSensitive) {
        this.currentSearchTerm = searchText;
        this.currentCaseSensitive = caseSensitive;
    }
}
