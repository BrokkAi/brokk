package ai.brokk.gui.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility for streaming paginated data from an iterator to a Swing UI.
 * Fetches items page-by-page and posts UI updates to the EDT after each page.
 */
public final class StreamingPaginationHelper {
    private static final Logger logger = LogManager.getLogger(StreamingPaginationHelper.class);

    /** Default number of items per page. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /** Default maximum total items to fetch. */
    public static final int DEFAULT_MAX_ITEMS = 500;

    /** Maximum items for lightweight DTOs (e.g., IssueHeader). */
    public static final int MAX_ISSUES = 1000;

    /** Maximum items for heavy objects (e.g., GHPullRequest). */
    public static final int MAX_PRS = 500;

    /** Number of items per batch for "Load more" pagination. */
    public static final int BATCH_SIZE = 100;

    /** Maximum items in sliding window (oldest discarded when exceeded). */
    public static final int MAX_WINDOW_SIZE = 500;

    private StreamingPaginationHelper() {}

    /**
     * Streams items from an iterator, calling onPageLoaded on the EDT after each page.
     * This method should be called from a background thread.
     *
     * @param <T> The type of items being streamed
     * @param iterator Source of items (fetches lazily)
     * @param pageSize Number of items to accumulate before notifying
     * @param maxItems Maximum total items to fetch (stops after reaching this)
     * @param onPageLoaded Called on EDT after each page with accumulated items
     * @param onComplete Called on EDT when streaming finishes successfully
     * @param onError Called on EDT if an error occurs
     */
    public static <T> void streamPages(
            Iterator<T> iterator,
            int pageSize,
            int maxItems,
            PageLoadedCallback<T> onPageLoaded,
            Runnable onComplete,
            Consumer<Exception> onError) {
        var accumulated = new ArrayList<T>();
        boolean isFirstPage = true;

        try {
            while (iterator.hasNext() && accumulated.size() < maxItems) {
                if (Thread.currentThread().isInterrupted()) {
                    logger.debug("Streaming interrupted after {} items", accumulated.size());
                    break;
                }

                // Fetch next page worth of items
                int pageCount = 0;
                while (iterator.hasNext() && pageCount < pageSize && accumulated.size() < maxItems) {
                    accumulated.add(iterator.next());
                    pageCount++;
                }

                if (pageCount == 0) {
                    continue;
                }

                final int totalSoFar = accumulated.size();
                final boolean hasMore = iterator.hasNext() && totalSoFar < maxItems;
                final boolean firstPage = isFirstPage;
                isFirstPage = false;

                // Create a snapshot for the EDT callback
                var snapshot = new ArrayList<>(accumulated);

                SwingUtilities.invokeLater(() -> onPageLoaded.onPageLoaded(snapshot, totalSoFar, hasMore, firstPage));

                logger.debug("Streamed page with {} items, total: {}", pageCount, totalSoFar);
            }

            // Final completion callback
            SwingUtilities.invokeLater(onComplete);
            logger.debug("Streaming complete. Total: {} items", accumulated.size());

        } catch (Exception ex) {
            logger.error("Error during streaming pagination", ex);
            SwingUtilities.invokeLater(() -> onError.accept(ex));
        }
    }

    /**
     * Callback interface for receiving page load notifications.
     *
     * @param <T> The type of items being streamed
     */
    @FunctionalInterface
    public interface PageLoadedCallback<T> {
        /**
         * Called on the EDT after each page of items is loaded.
         *
         * @param allItemsSoFar All items accumulated so far (snapshot, safe to modify)
         * @param totalCount Total number of items fetched so far
         * @param hasMore True if there are more items available to fetch
         * @param isFirstPage True if this is the first page of results
         */
        void onPageLoaded(List<T> allItemsSoFar, int totalCount, boolean hasMore, boolean isFirstPage);
    }

    /**
     * Streams pre-batched pages from an iterator that already returns lists.
     * This is useful when the API already returns pages (like IssueService.listIssuesPaginated).
     *
     * @param <T> The type of items being streamed
     * @param pageIterator Source of pages (each next() returns a List of items)
     * @param maxItems Maximum total items to fetch (stops after reaching this)
     * @param onPageLoaded Called on EDT after each page with accumulated items
     * @param onComplete Called on EDT when streaming finishes successfully
     * @param onError Called on EDT if an error occurs
     */
    public static <T> void streamPrebatchedPages(
            Iterator<List<T>> pageIterator,
            int maxItems,
            PageLoadedCallback<T> onPageLoaded,
            Runnable onComplete,
            Consumer<Exception> onError) {
        var accumulated = new ArrayList<T>();
        boolean isFirstPage = true;

        try {
            while (pageIterator.hasNext() && accumulated.size() < maxItems) {
                if (Thread.currentThread().isInterrupted()) {
                    logger.debug("Streaming interrupted after {} items", accumulated.size());
                    break;
                }

                List<T> page = pageIterator.next();
                if (page.isEmpty()) {
                    continue;
                }

                accumulated.addAll(page);
                final int totalSoFar = accumulated.size();
                final boolean hasMore = pageIterator.hasNext() && totalSoFar < maxItems;
                final boolean firstPage = isFirstPage;
                isFirstPage = false;

                // Create a snapshot for the EDT callback
                var snapshot = new ArrayList<>(accumulated);

                SwingUtilities.invokeLater(() -> onPageLoaded.onPageLoaded(snapshot, totalSoFar, hasMore, firstPage));

                logger.debug("Streamed page with {} items, total: {}", page.size(), totalSoFar);
            }

            // Final completion callback
            SwingUtilities.invokeLater(onComplete);
            logger.debug("Streaming complete. Total: {} items", accumulated.size());

        } catch (Exception ex) {
            logger.error("Error during streaming pagination", ex);
            SwingUtilities.invokeLater(() -> onError.accept(ex));
        }
    }

    /**
     * Formats a loading message for display.
     *
     * @param itemType Type of items (e.g., "issues", "PRs")
     * @param count Current count
     * @param maxItems Maximum items limit
     * @param hasMore Whether there are more items
     * @return Formatted message like "Loaded 75+ issues..." or "Loaded 500 (limit) PRs..."
     */
    public static String formatLoadingMessage(String itemType, int count, int maxItems, boolean hasMore) {
        if (!hasMore) {
            return "";
        }
        String suffix = count >= maxItems ? " (limit)" : "+";
        return "Loaded " + count + suffix + " " + itemType + "...";
    }

    /**
     * Result of loading a single batch of items.
     *
     * @param <T> The type of items
     */
    public record BatchResult<T>(List<T> items, boolean hasMore) {}

    /**
     * Loads a single batch of items from an iterator.
     * This method should be called from a background thread.
     * Unlike streamPages(), this returns after loading one batch, allowing the caller to
     * control when to load more.
     *
     * @param <T> The type of items being loaded
     * @param iterator Source of items (stateful; must be reused across calls to continue pagination)
     * @param batchSize Maximum number of items to load in this batch
     * @return BatchResult containing the loaded items and whether more are available
     */
    public static <T> BatchResult<T> loadBatch(Iterator<T> iterator, int batchSize) {
        var batch = new ArrayList<T>(batchSize);

        while (iterator.hasNext() && batch.size() < batchSize) {
            if (Thread.currentThread().isInterrupted()) {
                logger.debug("Batch loading interrupted after {} items", batch.size());
                break;
            }
            batch.add(iterator.next());
        }

        boolean hasMore = iterator.hasNext();
        logger.debug("Loaded batch of {} items, hasMore: {}", batch.size(), hasMore);
        return new BatchResult<>(batch, hasMore);
    }

    /**
     * Loads a single batch from a pre-batched iterator (one that returns Lists).
     * This method should be called from a background thread.
     *
     * @param <T> The type of items being loaded
     * @param pageIterator Source of pages (stateful; must be reused across calls to continue pagination)
     * @param batchSize Maximum number of items to load in this batch
     * @return BatchResult containing the loaded items and whether more are available
     */
    public static <T> BatchResult<T> loadPrebatchedBatch(Iterator<List<T>> pageIterator, int batchSize) {
        var batch = new ArrayList<T>(batchSize);

        while (pageIterator.hasNext() && batch.size() < batchSize) {
            if (Thread.currentThread().isInterrupted()) {
                logger.debug("Batch loading interrupted after {} items", batch.size());
                break;
            }
            List<T> page = pageIterator.next();
            batch.addAll(page);
        }

        boolean hasMore = pageIterator.hasNext();
        logger.debug("Loaded prebatched batch of {} items, hasMore: {}", batch.size(), hasMore);
        return new BatchResult<>(batch, hasMore);
    }
}
