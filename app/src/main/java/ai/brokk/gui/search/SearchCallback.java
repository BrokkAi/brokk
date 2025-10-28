package ai.brokk.gui.search;

import org.jetbrains.annotations.Nullable;

/** Interface for components that can be searched by SearchBarPanel. */
public interface SearchCallback {
    /**
     * Performs a search with the given command and returns search results.
     *
     * @param command The search command containing search term and options
     * @return SearchResults containing information about matches, or null if no matches
     */
    @Nullable
    SearchResults performSearch(SearchCommand command);

    /** Moves to the previous search result. */
    void goToPreviousResult();

    /** Moves to the next search result. */
    void goToNextResult();

    /** Stops the current search and clears any highlighting. */
    void stopSearch();
}
