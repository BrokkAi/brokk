package ai.brokk.git;

import org.eclipse.jgit.lib.ProgressMonitor;

/**
 * A simple ProgressMonitor that supports cancellation.
 * JGit operations periodically check isCancelled() and will abort if true.
 */
public class CancellableProgressMonitor implements ProgressMonitor {
    private volatile boolean cancelled = false;

    /**
     * Request cancellation of the ongoing operation.
     * JGit will check this flag periodically and abort when it sees it's true.
     */
    public void cancel() {
        cancelled = true;
    }

    @Override
    public void start(int totalTasks) {
        // No-op
    }

    @Override
    public void beginTask(String title, int totalWork) {
        // No-op
    }

    @Override
    public void update(int completed) {
        // No-op
    }

    @Override
    public void endTask() {
        // No-op
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
