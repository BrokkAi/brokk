package ai.brokk.difftool.ui;

/**
 * Interface defining the lifecycle and basic state contract for panels managed by the diff tool.
 */
public interface DiffPanelLifecycle {
    /**
     * Clean up resources and listeners.
     */
    void dispose();

    /**
     * @return true if the panel has unsaved modifications.
     */
    boolean hasUnsavedChanges();
}
