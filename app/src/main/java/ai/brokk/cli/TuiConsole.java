package ai.brokk.cli;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.TaskEntry;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.tasks.TaskList;
import dev.langchain4j.data.message.ChatMessageType;
import java.awt.Component;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

/**
 * Pane-aware ANSI renderer for the Brokk terminal UI.
 */
public final class TuiConsole extends MemoryConsole implements IConsoleIO, TuiView {
    private static final String ANSI_CLEAR = "\u001b[2J";
    private static final String ANSI_HOME = "\u001b[H";
    private static final String ANSI_RESET = "\u001b[0m";
    private static final String SECTION_INDENT = "    ";

    private final ContextManager cm;
    private final PrintStream out;
    private final ScheduledExecutorService scheduler;
    private final Object renderLock = new Object();

    private volatile Focus currentFocus = Focus.PROMPT;
    private final StringBuilder outputBuffer = new StringBuilder();
    private volatile List<Context> historyCache = List.of();
    private volatile int historyIndex = -1;
    private volatile String lastTokenUsageBar = "";
    private volatile float lastBalance = -1f;
    private volatile String balanceText = "";
    private volatile boolean hasBalanceOverride = false;
    private volatile boolean spinnerVisible = false;
    private volatile String spinnerMessage = "";
    private volatile String promptBuffer = "";
    private volatile boolean showChipPanel = false;
    private volatile boolean showTaskList = false;
    private volatile List<TaskEntry> stagedHistory = List.of();

    public TuiConsole(ContextManager cm) {
        this(cm, System.out);
    }

    public TuiConsole(ContextManager cm, PrintStream out) {
        this.cm = Objects.requireNonNull(cm, "cm");
        this.out = Objects.requireNonNull(out, "out");
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "tui-balance-poller");
            t.setDaemon(true);
            return t;
        });
        renderScreen();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                var svc = cm.getService();
                var balance = svc.getUserBalance();
                var prior = lastBalance;
                if (prior < 0f || Math.abs(balance - prior) >= 0.01f) {
                    updateStateAndRender(() -> {
                        lastBalance = balance;
                        if (!hasBalanceOverride) {
                            balanceText = BalanceFormatter.format(balance);
                        }
                    });
                }
            } catch (Throwable t) {
                // swallow; TUI must not terminate due to polling failures
            }
        }, 0, 30, TimeUnit.SECONDS);
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    @Override
    public void toggleChipPanel() {
        updateStateAndRender(() -> showChipPanel = !showChipPanel);
    }

    @Override
    public void toggleTaskList() {
        updateStateAndRender(() -> showTaskList = !showTaskList);
    }

    @Override
    public void llmOutput(String token, ChatMessageType type, boolean explicitNewMessage, boolean isReasoning) {
        var newMessage = isNewMessage(type, explicitNewMessage);
        super.llmOutput(token, type, explicitNewMessage, isReasoning);
        updateStateAndRender(() -> {
            if (newMessage) {
                outputBuffer.setLength(0);
                appendStagedHistoryLocked();
                outputBuffer.append('[').append(type.name()).append("] ");
            }
            outputBuffer.append(token);
        });
    }

    @Override
    public void setLlmAndHistoryOutput(List<TaskEntry> history, TaskEntry taskEntry) {
        prepareOutputForNextStream(history);
        appendLineToOutput("=== Task: " + String.valueOf(taskEntry) + " ===");
    }

    @Override
    public void prepareOutputForNextStream(List<TaskEntry> history) {
        Objects.requireNonNull(history, "history");
        synchronized (renderLock) {
            stagedHistory = List.copyOf(history);
        }
    }

    @Override
    public void toolError(String msg, String title) {
        appendLineToOutput("[TOOL ERROR] " + title + ": " + msg);
    }

    @Override
    public void showNotification(NotificationRole role, String message) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(message, "message");
        var prefix = switch (role) {
            case ERROR -> "[ERROR] ";
            case CONFIRM -> "[CONFIRM] ";
            case COST -> "[COST] ";
            case INFO -> "[INFO] ";
        };
        appendLineToOutput(prefix + message);
    }

    @Override
    public int showConfirmDialog(String message, String title, int optionType, int messageType) {
        appendLineToOutput("[CONFIRM] " + title + ": " + message + " (auto-yes)");
        return 0;
    }

    @Override
    public int showConfirmDialog(
            @Nullable Component parent, String message, String title, int optionType, int messageType) {
        return showConfirmDialog(message, title, optionType, messageType);
    }

    @Override
    public void showOutputSpinner(String message) {
        updateStateAndRender(() -> {
            spinnerVisible = true;
            spinnerMessage = message == null ? "" : message;
        });
    }

    @Override
    public void hideOutputSpinner() {
        updateStateAndRender(() -> {
            spinnerVisible = false;
            spinnerMessage = "";
        });
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
        appendLineToOutput("[Workspace] updated");
    }

    @Override
    public void updateGitRepo() {
        appendLineToOutput("[Git] repo updated");
    }

    @Override
    public void updateContextHistoryTable(Context context) {
        var action = context == null ? "(unknown)" : context.getAction();
        appendLineToOutput("[History] context updated: " + action);
    }

    public void updateTokenUsageSummary(String summary) {
        updateStateAndRender(() -> lastTokenUsageBar = summary == null ? "" : summary);
    }

    public void clearTokenUsage() {
        updateTokenUsageSummary("");
    }

    @Override
    public void setFocus(Focus focus) {
        Objects.requireNonNull(focus, "focus");
        updateStateAndRender(() -> currentFocus = focus);
    }

    @Override
    public void updateHeader(String usageBar, String balanceOverride, boolean showSpinner) {
        updateStateAndRender(() -> {
            lastTokenUsageBar = usageBar == null ? "" : usageBar;
            if (balanceOverride != null && !balanceOverride.isBlank()) {
                balanceText = balanceOverride;
                hasBalanceOverride = true;
            } else {
                hasBalanceOverride = false;
                if (lastBalance >= 0f) {
                    balanceText = BalanceFormatter.format(lastBalance);
                } else {
                    balanceText = "";
                }
            }
            spinnerVisible = showSpinner;
            if (!spinnerVisible) {
                spinnerMessage = "";
            } else if (spinnerMessage.isBlank()) {
                spinnerMessage = "Working...";
            }
        });
    }

    @Override
    public void renderHistory(List<Context> contexts, int selectedIndex) {
        Objects.requireNonNull(contexts, "contexts");
        updateStateAndRender(() -> {
            historyCache = List.copyOf(contexts);
            historyIndex = normalizeSelection(selectedIndex, historyCache.size());
        });
    }

    @Override
    public void setHistorySelection(int index) {
        updateStateAndRender(() -> historyIndex = normalizeSelection(index, historyCache.size()));
    }

    @Override
    public void clearOutput() {
        updateStateAndRender(() -> {
            resetTranscript();
            outputBuffer.setLength(0);
            stagedHistory = List.of();
        });
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
        updateStateAndRender(() -> promptBuffer = text);
    }

    private void renderScreen() {
        synchronized (renderLock) {
            renderScreenLocked();
        }
    }

    private void renderScreenLocked() {
        out.print(ANSI_CLEAR);
        out.print(ANSI_HOME);

        renderHeaderLocked();
        renderHistoryLocked();
        if (showChipPanel) {
            renderChipPanelLocked();
        }
        renderOutputLocked();
        if (showTaskList) {
            renderTaskListLocked();
        }
        renderPromptLocked();

        out.print(ANSI_RESET);
        out.flush();
    }

    private void renderHeaderLocked() {
        out.println("============ Brokk TUI ============");
        var parts = new ArrayList<String>();
        if (!balanceText.isBlank()) {
            parts.add("Balance " + balanceText);
        } else if (lastBalance >= 0f) {
            parts.add("Balance " + BalanceFormatter.format(lastBalance));
        }
        if (!lastTokenUsageBar.isBlank()) {
            parts.add("Tokens " + lastTokenUsageBar);
        }
        if (spinnerVisible) {
            var msg = spinnerMessage.isBlank() ? "Working..." : spinnerMessage;
            parts.add("[" + msg + "]");
        }
        if (!parts.isEmpty()) {
            out.println(String.join("  |  ", parts));
        }
        out.println("Focus: " + currentFocus);
    }

    private void renderHistoryLocked() {
        out.println();
        renderSectionHeader("History", Focus.HISTORY);
        if (historyCache.isEmpty()) {
            out.println(SECTION_INDENT + "(empty)");
            return;
        }
        for (var i = 0; i < historyCache.size(); i++) {
            var ctx = historyCache.get(i);
            var action = ctx.getAction();
            if (action == null || action.isBlank()) {
                action = "(no action)";
            }
            var selectionMarker = i == historyIndex ? "*" : " ";
            out.println(SECTION_INDENT + selectionMarker + " [" + i + "] " + action);
        }
    }

    private void renderChipPanelLocked() {
        out.println();
        out.println("  Context Fragments:");
        try {
            var fragments = cm.liveContext().getAllFragmentsInDisplayOrder();
            if (fragments.isEmpty()) {
                out.println(SECTION_INDENT + "(none)");
            } else {
                for (var fragment : fragments) {
                    out.println(SECTION_INDENT + "- [" + fragment.getType() + "] " + safeShortDescription(fragment));
                }
            }
        } catch (Throwable t) {
            out.println(SECTION_INDENT + "(error rendering fragments: " + t.getMessage() + ")");
        }
    }

    private void renderOutputLocked() {
        out.println();
        renderSectionHeader("Output", Focus.OUTPUT);
        if (outputBuffer.length() == 0) {
            out.println(SECTION_INDENT + "(waiting for output)");
            return;
        }
        renderTextBlock(outputBuffer.toString());
    }

    private void renderTaskListLocked() {
        out.println();
        out.println("  Tasks:");
        try {
            var data = cm.getTaskList();
            var tasks = data == null ? null : data.tasks();
            if (tasks == null || tasks.isEmpty()) {
                out.println(SECTION_INDENT + "(No tasks)");
            } else {
                var index = 1;
                for (var task : tasks) {
                    var mark = task.done() ? 'x' : ' ';
                    out.println(String.format("%s%2d. [%c] %s", SECTION_INDENT, index++, mark, task.text()));
                }
            }
        } catch (Throwable t) {
            out.println(SECTION_INDENT + "(error rendering tasks: " + t.getMessage() + ")");
        }
    }

    private void renderPromptLocked() {
        out.println();
        renderSectionHeader("Prompt", Focus.PROMPT);
        if (promptBuffer.isEmpty()) {
            out.println(SECTION_INDENT + "(ready)");
        } else {
            out.println(SECTION_INDENT + promptBuffer.replace("\n", "\n" + SECTION_INDENT));
        }
    }

    private void renderSectionHeader(String title, Focus focus) {
        out.println(sectionPrefix(focus) + title);
    }

    private String sectionPrefix(Focus focus) {
        return currentFocus == focus ? "▶ " : "  ";
    }

    private void renderTextBlock(String text) {
        var sanitized = text.replace("\r", "");
        if (sanitized.isEmpty()) {
            out.println(SECTION_INDENT + "(empty)");
            return;
        }
        out.println(SECTION_INDENT + sanitized.replace("\n", "\n" + SECTION_INDENT));
    }

    private void appendLineToOutput(String line) {
        Objects.requireNonNull(line, "line");
        updateStateAndRender(() -> {
            if (outputBuffer.length() > 0) {
                outputBuffer.append('\n');
            }
            outputBuffer.append(line);
        });
    }

    private void appendStagedHistoryLocked() {
        if (stagedHistory.isEmpty()) {
            return;
        }
        outputBuffer.append("-- History --\n");
        for (var entry : stagedHistory) {
            outputBuffer.append(" * ").append(entry).append('\n');
        }
        outputBuffer.append('\n');
        stagedHistory = List.of();
    }

    private void updateStateAndRender(Runnable update) {
        synchronized (renderLock) {
            update.run();
            renderScreenLocked();
        }
    }

    private static int normalizeSelection(int index, int size) {
        if (index < 0 || index >= size) {
            return -1;
        }
        return index;
    }

    private static String safeShortDescription(ContextFragment fragment) {
        try {
            var description = fragment.shortDescription();
            return description == null ? "" : description;
        } catch (Throwable t) {
            return fragment.getType().name();
        }
    }
}
