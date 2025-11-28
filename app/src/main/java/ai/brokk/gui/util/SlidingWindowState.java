package ai.brokk.gui.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages a sliding window of items for paginated loading.
 * When the window exceeds MAX_WINDOW_SIZE, oldest items are discarded.
 *
 * @param <T> The type of items in the window
 */
public final class SlidingWindowState<T> {
    private final List<T> items = new ArrayList<>();
    private int totalDiscarded = 0;
    private boolean hasMore = true;

    /**
     * Returns true if more items are available from the API.
     */
    public boolean hasMore() {
        return hasMore;
    }

    /**
     * Returns an unmodifiable view of the current items in the window.
     */
    public List<T> getItems() {
        return Collections.unmodifiableList(items);
    }

    /**
     * Returns the total number of items discarded from the beginning of the window.
     */
    public int getTotalDiscarded() {
        return totalDiscarded;
    }

    /**
     * Returns the 1-based index of the first item in the window.
     */
    public int getWindowStart() {
        return totalDiscarded + 1;
    }

    /**
     * Returns the 1-based index of the last item in the window.
     */
    public int getWindowEnd() {
        return totalDiscarded + items.size();
    }

    /**
     * Returns the current size of the window.
     */
    public int size() {
        return items.size();
    }

    /**
     * Appends a batch of new items to the window.
     * If the window exceeds MAX_WINDOW_SIZE, oldest items are discarded.
     *
     * @param newItems The new items to append
     * @param moreAvailable True if more items are available from the API
     */
    public void appendBatch(List<T> newItems, boolean moreAvailable) {
        items.addAll(newItems);
        hasMore = moreAvailable;

        while (items.size() > StreamingPaginationHelper.MAX_WINDOW_SIZE) {
            items.remove(0);
            totalDiscarded++;
        }
    }

    /**
     * Clears the window and resets all state.
     */
    public void clear() {
        items.clear();
        totalDiscarded = 0;
        hasMore = true;
    }

    /**
     * Formats a status message showing the current window position.
     *
     * @param itemType Type of items (e.g., "issues", "PRs")
     * @return Formatted message like "Showing 401-500 of 500+ items" or empty if at start
     */
    public String formatStatusMessage(String itemType) {
        if (items.isEmpty()) {
            return "";
        }
        if (totalDiscarded == 0 && !hasMore) {
            return "";
        }
        var suffix = hasMore ? "+" : "";
        return String.format("Showing %d-%d of %d%s %s",
                             getWindowStart(), getWindowEnd(), getWindowEnd(), suffix, itemType);
    }
}
