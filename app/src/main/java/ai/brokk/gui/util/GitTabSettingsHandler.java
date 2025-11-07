package ai.brokk.gui.util;

import javax.swing.SwingUtilities;

/**
 * Centralizes the common pattern for handling GitHub token or issue provider changes in Git UI tabs.
 * Encapsulates the sequence: reset UI state, cancel active tasks, and refresh data.
 */
public final class GitTabSettingsHandler {
    private GitTabSettingsHandler() {}

    /**
     * Handles a settings change (token or provider) by executing the provided actions in sequence.
     * Executes on the EDT to ensure thread safety.
     *
     * @param resetUiState A Runnable that resets UI state (e.g., clears error flags, enables controls)
     * @param cancelActiveTasks A Runnable that cancels any active tasks/futures
     * @param refreshData A Runnable that refreshes data (may be scheduled asynchronously afterward)
     */
    public static void handleProviderOrTokenChange(
            Runnable resetUiState, Runnable cancelActiveTasks, Runnable refreshData) {
        SwingUtilities.invokeLater(() -> {
            // Step 1: Reset UI state
            resetUiState.run();

            // Step 2: Cancel active tasks
            cancelActiveTasks.run();

            // Step 3: Refresh data (schedule on EDT if needed)
            SwingUtilities.invokeLater(refreshData);
        });
    }
}
