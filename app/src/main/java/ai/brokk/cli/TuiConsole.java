package ai.brokk.cli;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
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
public final class TuiConsole extends MemoryConsole implements IConsoleIO {
    private final ContextManager cm;
    private final PrintStream out;
    private final ScheduledExecutorService scheduler;
    private final Object renderLock = new Object();

    private volatile boolean showChipPanel = false;
    private volatile boolean showTaskList = false;

    private volatile float lastBalance = -1f;
    private volatile String lastTokenUsageBar = "";

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

    public void toggleChipPanel() {
        showChipPanel = !showChipPanel;
        synchronized (renderLock) {
            renderHeader();
            renderChipPanel();
        }
    }

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
        if (lastBalance >= 0f) {
            parts.add(String.format("Balance: $%.2f", lastBalance));
        }
        if (!lastTokenUsageBar.isBlank()) {
            parts.add("Usage: " + lastTokenUsageBar);
        }
        if (spinnerVisible) {
            parts.add("[" + (spinnerMessage.isBlank() ? "Working..." : spinnerMessage) + "]");
        }
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
