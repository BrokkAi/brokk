package ai.brokk.cli;

/**
 * Minimal view surface for TuiController, enabling command-routing tests without a concrete console.
 */
public interface TuiView {
    void toggleChipPanel();

    void toggleTaskList();

    void setTaskInProgress(boolean progress);

    void shutdown();
}
