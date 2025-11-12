package ai.brokk.cli;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.cli.BalanceFormatter;
import ai.brokk.TaskEntry;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.tasks.TaskList;
import dev.langchain4j.data.message.ChatMessageType;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

/**
 * Simple text UI console for headless/terminal usage.
 * - Always-visible header with balance and token usage summary
 * - Streams LLM output line-oriented to stdout
 * - Toggleable context chip panel and read-only task list
 */
public final class TuiConsole extends MemoryConsole implements IConsoleIO, TuiView {
    private final ContextManager cm;
    private final PrintStream out;
    private final ScheduledExecutorService scheduler;
    private final Object renderLock = new Object();

    private volatile boolean showChipPanel = false;
    private volatile boolean showTaskList = false;
    private volatile Focus currentFocus = Focus.PROMPT;

    private volatile float lastBalance = -1f;
    private volatile String lastTokenUsageBar = "";
    private volatile String balanceTextOverride = "";

    private volatile List<Context> renderedHistory = List.of();
    private volatile int historySelection = -1;

    private volatile boolean spinnerVisible = false;
    private volatile String spinnerMessage = "";

    private volatile List<TaskEntry> pendingHistory = List.of();

    public TuiConsole(ContextManager cm) {
        this(cm, System.out);
    }

    public TuiConsole(ContextManager cm, PrintStream out) {
        this.cm = Objects.requireNonNull(cm, "cm");
        this.out = Objects.requireNonNull(out, "out");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "tui-balance-poller");
            t.setDaemon(true);
            return t;
        });
        synchronized (renderLock) {
            renderHeader();
        }
        scheduler.scheduleAtFixedRate(() -> {
            try {
                var svc = cm.getService();
                float bal = svc.getUserBalance();
                boolean first = lastBalance < 0f;
                if (first || Math.abs(bal - lastBalance) >= 0.01f) {
                    lastBalance = bal;
                    synchronized (renderLock) {
                        renderHeader();
                    }
                }
            } catch (Throwable t) {
                // swallow; headless console must not crash due to polling
            }
        }, 0, 30, TimeUnit.SECONDS);
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    @Override
    public void toggleChipPanel() {
        showChipPanel = !showChipPanel;
        synchronized (renderLock) {
            renderHeader();
            renderChipPanel();
        }
    }

    @Override
    public void toggleTaskList() {
        showTaskList = !showTaskList;
        synchronized (renderLock) {
            renderHeader();
            renderTaskList();
        }
    }

    @Override
    public void llmOutput(String token, ChatMessageType type, boolean explicitNewMessage, boolean isReasoning) {
        super.llmOutput(token, type, explicitNewMessage, isReasoning);
        synchronized (renderLock) {
            if (explicitNewMessage && !pendingHistory.isEmpty()) {
                out.println();
                out.println("=== History: " + pendingHistory.size() + " entries ===");
                pendingHistory = List.of();
            }
            out.print(token);
            out.flush();
        }
    }

    @Override
    public void setLlmAndHistoryOutput(List<TaskEntry> history, TaskEntry taskEntry) {
        prepareOutputForNextStream(history);
        synchronized (renderLock) {
            out.println();
            out.println("=== Task: " + String.valueOf(taskEntry));
        }
    }

    @Override
    public void prepareOutputForNextStream(List<TaskEntry> history) {
        pendingHistory = new ArrayList<>(history);
    }

    @Override
    public void toolError(String msg, String title) {
        synchronized (renderLock) {
            out.println();
            out.println("[TOOL ERROR] " + title + ": " + msg);
            out.flush();
        }
    }

    @Override
    public void showNotification(NotificationRole role, String message) {
        String prefix = switch (role) {
            case ERROR -> "[ERROR] ";
            case CONFIRM -> "[CONFIRM] ";
            case COST -> "[COST] ";
            case INFO -> "[INFO] ";
        };
        synchronized (renderLock) {
            out.println();
            out.println(prefix + message);
            out.flush();
        }
    }

    @Override
    public int showConfirmDialog(String message, String title, int optionType, int messageType) {
        synchronized (renderLock) {
            out.println();
            out.println("[CONFIRM] " + title + ": " + message + " (auto-yes)");
            out.flush();
        }
        return 0;
    }

    @Override
    public int showConfirmDialog(
            @Nullable java.awt.Component parent, String message, String title, int optionType, int messageType) {
        return showConfirmDialog(message, title, optionType, messageType);
    }

    @Override
    public void showOutputSpinner(String message) {
        spinnerVisible = true;
        spinnerMessage = message;
        synchronized (renderLock) {
            renderHeader();
        }
    }

    @Override
    public void hideOutputSpinner() {
        spinnerVisible = false;
        spinnerMessage = "";
        synchronized (renderLock) {
            renderHeader();
        }
    }

    @Override
    public void showSessionSwitchSpinner() {
        showOutputSpinner("Switching session...");
    }

    @Override
    public void hideSessionSwitchSpinner() {
        hideOutputSpinner();
    }

    @Override
    public void setTaskInProgress(boolean progress) {
        if (progress) {
            showOutputSpinner("Running task...");
        } else {
            hideOutputSpinner();
        }
    }

    @Override
    public void updateWorkspace() {
        synchronized (renderLock) {
            out.println();
            out.println("[Workspace] updated");
            out.flush();
        }
    }

    @Override
    public void updateGitRepo() {
        synchronized (renderLock) {
            out.println();
            out.println("[Git] repo updated");
            out.flush();
        }
    }

    @Override
    public void updateContextHistoryTable(Context context) {
        synchronized (renderLock) {
            out.println();
            out.println("[History] context updated: " + context.getAction());
            out.flush();
        }
    }

    public void updateTokenUsageSummary(String summary) {
        lastTokenUsageBar = summary == null ? "" : summary;
        synchronized (renderLock) {
            renderHeader();
        }
    }

    public void clearTokenUsage() {
        updateTokenUsageSummary("");
    }

    private void renderHeader() {
        out.println();
        out.println("==================== Brokk TUI ====================");
        var parts = new ArrayList<String>();
        if (!balanceTextOverride.isBlank()) {
            parts.add("Balance: " + balanceTextOverride);
        } else if (lastBalance >= 0f) {
            parts.add("Balance: " + BalanceFormatter.format(lastBalance));
        }
        if (!lastTokenUsageBar.isBlank()) {
            parts.add("Usage: " + lastTokenUsageBar);
        }
        if (spinnerVisible) {
            parts.add("[" + (spinnerMessage.isBlank() ? "Working..." : spinnerMessage) + "]");
        }
        parts.add("Focus: " + currentFocus);
        out.println(String.join("  |  ", parts));
        out.println("===================================================");
        out.flush();
        if (showChipPanel) {
            renderChipPanel();
        }
        if (showTaskList) {
            renderTaskList();
        }
    }

    @Override
    public void setFocus(Focus focus) {
        currentFocus = Objects.requireNonNull(focus, "focus");
        synchronized (renderLock) {
            renderHeader();
        }
    }

    @Override
    public void updateHeader(String usageBar, String balanceText, boolean showSpinner) {
        lastTokenUsageBar = usageBar == null ? "" : usageBar;
        balanceTextOverride = balanceText == null ? "" : balanceText;
        spinnerVisible = showSpinner;
        if (!spinnerVisible) {
            spinnerMessage = "";
        } else if (spinnerMessage.isBlank()) {
            spinnerMessage = "Working...";
        }
        synchronized (renderLock) {
            renderHeader();
        }
    }

    @Override
    public void renderHistory(List<Context> contexts, int selectedIndex) {
        Objects.requireNonNull(contexts, "contexts");
        synchronized (renderLock) {
            renderedHistory = List.copyOf(contexts);
            historySelection = normalizeSelection(selectedIndex, renderedHistory.size());
            renderHistoryInternal();
        }
    }

    @Override
    public void setHistorySelection(int index) {
        synchronized (renderLock) {
            historySelection = normalizeSelection(index, renderedHistory.size());
            renderHistoryInternal();
        }
    }

    @Override
    public void clearOutput() {
        synchronized (renderLock) {
            resetTranscript();
            pendingHistory = List.of();
            out.println();
            out.println("-- Output cleared --");
            out.flush();
        }
    }

    @Override
    public void appendOutput(String token, boolean isReasoning) {
        Objects.requireNonNull(token, "token");
        var newMessage = messages.isEmpty() || messages.getLast().type() != ChatMessageType.AI;
        llmOutput(token, ChatMessageType.AI, newMessage, isReasoning);
    }

    @Override
    public void renderPrompt(String text) {
        Objects.requireNonNull(text, "text");
        synchronized (renderLock) {
            out.println();
            out.println("Prompt> " + text);
            out.flush();
        }
    }

    private void renderHistoryInternal() {
        out.println();
        out.println("-- History --");
        if (renderedHistory.isEmpty()) {
            out.println("(empty)");
        } else {
            var selection = historySelection;
            if (selection < 0 || selection >= renderedHistory.size()) {
                selection = -1;
            }
            for (int i = 0; i < renderedHistory.size(); i++) {
                var ctx = renderedHistory.get(i);
                var action = ctx.getAction();
                if (action == null || action.isBlank()) {
                    action = "(no action)";
                }
                var marker = i == selection ? ">" : " ";
                out.println(marker + " [" + i + "] " + action);
            }
        }
        out.flush();
    }

    private static int normalizeSelection(int index, int size) {
        if (index < 0 || index >= size) {
            return -1;
        }
        return index;
    }

    private void renderChipPanel() {
        try {
            var ctx = cm.liveContext();
            var fragments = ctx.getAllFragmentsInDisplayOrder();
            out.println("-- Context Fragments --");
            if (fragments.isEmpty()) {
                out.println("(none)");
            } else {
                for (var f : fragments) {
                    out.println("- [" + f.getType() + "] " + safeShortDescription(f));
                }
            }
            out.flush();
        } catch (Throwable t) {
            out.println("-- Context Fragments --");
            out.println("(error rendering fragments: " + t.getMessage() + ")");
            out.flush();
        }
    }

    private void renderTaskList() {
        try {
            TaskList.TaskListData data = cm.getTaskList();
            out.println("-- Tasks --");
            if (data == null || data.tasks() == null || data.tasks().isEmpty()) {
                out.println("(No tasks)");
            } else {
                int i = 1;
                for (var t : data.tasks()) {
                    var mark = t.done() ? 'x' : ' ';
                    out.println(String.format("%2d. [%c] %s", i++, mark, t.text()));
                }
            }
            out.flush();
        } catch (Throwable t) {
            out.println("-- Tasks --");
            out.println("(error rendering tasks: " + t.getMessage() + ")");
            out.flush();
        }
    }

    private static String safeShortDescription(ContextFragment f) {
        try {
            var s = f.shortDescription();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return f.getType().name();
        }
    }
}
