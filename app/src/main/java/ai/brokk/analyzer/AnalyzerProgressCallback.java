package ai.brokk.analyzer;

/**
 * Callback interface for reporting progress during analyzer construction or update.
 * Implementations should be thread-safe as they may be called from background threads.
 */
@FunctionalInterface
public interface AnalyzerProgressCallback {
    /**
     * Called periodically to report progress.
     *
     * @param completed Number of items completed
     * @param total Total number of items to process
     * @param description Description of the current operation (e.g., "Parsing Java files")
     */
    void onProgress(int completed, int total, String description);

    /**
     * No-op implementation that ignores all progress updates.
     */
    AnalyzerProgressCallback NOOP = (completed, total, description) -> {};
}
