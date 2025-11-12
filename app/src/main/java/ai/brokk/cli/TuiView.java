package ai.brokk.cli;

import ai.brokk.context.Context;
import java.util.List;

/**
 * Minimal view surface for TuiController, enabling command-routing tests without a concrete console.
 */
public interface TuiView {
    enum Focus {
        PROMPT,
        HISTORY,
        OUTPUT
    }

    void toggleChipPanel();

    void toggleTaskList();

    void setTaskInProgress(boolean progress);

    void setFocus(Focus focus);

    void updateHeader(String usageBar, String balanceText, boolean showSpinner);

    void renderHistory(List<Context> contexts, int selectedIndex);

    void setHistorySelection(int index);

    void clearOutput();

    void appendOutput(String token, boolean isReasoning);

    void renderPrompt(String text);

    void shutdown();
}
