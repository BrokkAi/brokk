package ai.brokk.git;

import org.eclipse.jgit.lib.ProgressMonitor;
import org.jetbrains.annotations.Nullable;

/**
 * A ProgressMonitor that supports cancellation and progress reporting.
 * JGit operations periodically check isCancelled() and will abort if true.
 */
public class CancellableProgressMonitor implements ProgressMonitor {
    private volatile boolean cancelled = false;
    private @Nullable ProgressCallback callback;

    /**
     * Callback interface for receiving progress updates from JGit operations.
     */
    public interface ProgressCallback {
        /**
         * Called when a new task begins.
         * @param taskName the name of the task (e.g., "Receiving objects", "Resolving deltas")
         * @param totalWork total units of work, or UNKNOWN if indeterminate
         */
        void onTaskStart(String taskName, int totalWork);

        /**
         * Called as work progresses.
         * @param completed units of work completed in this update (incremental, not cumulative)
         */
        void onProgress(int completed);

        /**
         * Called when the current task ends.
         */
        void onTaskEnd();
    }

    /**
     * Sets the callback for progress updates.
     */
    public void setCallback(@Nullable ProgressCallback callback) {
        this.callback = callback;
    }

    /**
     * Request cancellation of the ongoing operation.
     * JGit will check this flag periodically and abort when it sees it's true.
     */
    public void cancel() {
        cancelled = true;
    }

    @Override
    public void start(int totalTasks) {
        // No-op - we handle individual tasks via beginTask
    }

    @Override
    public void beginTask(String title, int totalWork) {
        var cb = callback;
        if (cb != null) {
            cb.onTaskStart(title, totalWork);
        }
    }

    @Override
    public void update(int completed) {
        var cb = callback;
        if (cb != null) {
            cb.onProgress(completed);
        }
    }

    @Override
    public void endTask() {
        var cb = callback;
        if (cb != null) {
            cb.onTaskEnd();
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void showDuration(boolean enabled) {
        // No-op
    }
}
