package ai.brokk.cli;

import ai.brokk.ContextManager;
import ai.brokk.agents.SearchAgent;
import ai.brokk.context.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Supplier;

public final class TuiController {
    private static final int ESCAPE = 27;
    private static final char BACKSPACE = '\b';
    private static final int DELETE = 127;

    private final ContextManager cm;
    private final TuiView console;
    private final BufferedReader reader;
    private final PrintStream out;
    private final StringBuilder promptBuffer = new StringBuilder();
    private volatile boolean running = false;
    private TuiView.Focus currentFocus = TuiView.Focus.PROMPT;
    private volatile List<Context> historyCache = List.of();
    private volatile int historySelection = -1;
    private volatile boolean historyRendered = false;

    public TuiController(ContextManager cm, TuiView console) {
        this(cm, console, new InputStreamReader(System.in), System.out);
    }

    public TuiController(ContextManager cm, TuiView console, Reader inputReader, PrintStream out) {
        this.cm = Objects.requireNonNull(cm, "cm");
        this.console = Objects.requireNonNull(console, "console");
        Objects.requireNonNull(inputReader, "inputReader");
        this.reader = inputReader instanceof BufferedReader br ? br : new BufferedReader(inputReader);
        this.out = Objects.requireNonNull(out, "out");
    }

    TuiController(TuiView console, Reader inputReader, PrintStream out) {
        this.cm = null;
        this.console = Objects.requireNonNull(console, "console");
        Objects.requireNonNull(inputReader, "inputReader");
        this.reader = inputReader instanceof BufferedReader br ? br : new BufferedReader(inputReader);
        this.out = Objects.requireNonNull(out, "out");
    }

    public void run() {
        printWelcome();
        running = true;
        promptBuffer.setLength(0);
        updateFocus(TuiView.Focus.PROMPT);
        console.renderPrompt("");
        refreshHistoryFromManager();

        var skipNextLineFeed = false;

        try {
            int code;
            while (running && (code = reader.read()) != -1) {
                if (!running) {
                    break;
                }
                if (skipNextLineFeed) {
                    if (code == '\n') {
                        skipNextLineFeed = false;
                        continue;
                    }
                    skipNextLineFeed = false;
                }
                if (code == '\r') {
                    handleEnter();
                    skipNextLineFeed = true;
                    continue;
                }
                if (code == '\n') {
                    handleEnter();
                    continue;
                }
                if (code == '\t') {
                    handleTab();
                    continue;
                }
                if (code == BACKSPACE || code == DELETE) {
                    handleBackspace();
                    continue;
                }
                if (code == ESCAPE) {
                    handleEscapeSequence();
                    continue;
                }
                var ch = (char) code;
                if (!Character.isISOControl(ch)) {
                    handlePrintable(ch);
                }
            }
        } catch (IOException e) {
            if (cm != null) {
                cm.getIo().toolError("Input error: " + e.getMessage(), "TUI");
            } else {
                out.println("[TUI] Input error: " + e.getMessage());
                out.flush();
            }
        } finally {
            console.shutdown();
        }
    }

    private void handleTab() {
        var nextFocus = switch (currentFocus) {
            case PROMPT -> TuiView.Focus.HISTORY;
            case HISTORY -> TuiView.Focus.OUTPUT;
            case OUTPUT -> TuiView.Focus.PROMPT;
        };
        updateFocus(nextFocus);
    }

    private void handleEscapeSequence() throws IOException {
        reader.mark(1);
        var modifier = reader.read();
        if (modifier == -1) {
            return;
        }
        if (modifier != '[') {
            reader.reset();
            return;
        }
        reader.mark(1);
        var code = reader.read();
        if (code == -1) {
            return;
        }
        switch (code) {
            case 'A' -> handleArrowUp();
            case 'B' -> handleArrowDown();
            default -> reader.reset();
        }
    }

    private void handleArrowUp() {
        if (currentFocus != TuiView.Focus.HISTORY) {
            return;
        }
        refreshHistoryFromManager();
        if (historyCache.isEmpty()) {
            return;
        }
        var target = historySelection;
        if (target < 0) {
            target = historyCache.size() - 1;
        } else if (target > 0) {
            target--;
        }
        updateHistorySelection(target);
    }

    private void handleArrowDown() {
        if (currentFocus != TuiView.Focus.HISTORY) {
            return;
        }
        refreshHistoryFromManager();
        if (historyCache.isEmpty()) {
            return;
        }
        var target = historySelection;
        if (target < 0) {
            target = 0;
        } else if (target < historyCache.size() - 1) {
            target++;
        }
        updateHistorySelection(target);
    }

    private void handlePrintable(char ch) {
        if (currentFocus == TuiView.Focus.HISTORY) {
            handleHistoryPrintable(ch);
            return;
        }
        if (currentFocus != TuiView.Focus.PROMPT) {
            return;
        }
        promptBuffer.append(ch);
        console.renderPrompt(promptBuffer.toString());
    }

    private void handleHistoryPrintable(char ch) {
        var normalized = Character.toLowerCase(ch);
        if (normalized == 'u') {
            triggerUndo();
        } else if (normalized == 'r') {
            triggerRedo();
        }
    }

    private void handleBackspace() {
        if (currentFocus != TuiView.Focus.PROMPT) {
            return;
        }
        if (promptBuffer.length() == 0) {
            return;
        }
        promptBuffer.setLength(promptBuffer.length() - 1);
        console.renderPrompt(promptBuffer.toString());
    }

    private void applyHistorySelection() {
        if (cm == null) {
            out.println("[TUI] Cannot apply history selection in test routing mode.");
            out.flush();
            return;
        }
        var contexts = cm.getContextHistoryList();
        if (contexts == null || contexts.isEmpty()) {
            return;
        }
        var index = historySelection;
        if (index < 0 || index >= contexts.size()) {
            index = contexts.size() - 1;
        }
        if (index < 0) {
            return;
        }
        cm.setSelectedContext(contexts.get(index));
        historyRendered = false;
        refreshHistoryFromManager();
    }

    private void triggerUndo() {
        if (cm == null) {
            out.println("[TUI] Undo is unavailable in test routing mode.");
            out.flush();
            return;
        }
        triggerHistoryMutation("Undo", cm::undoContextAsync);
    }

    private void triggerRedo() {
        if (cm == null) {
            out.println("[TUI] Redo is unavailable in test routing mode.");
            out.flush();
            return;
        }
        triggerHistoryMutation("Redo", cm::redoContextAsync);
    }

    private void triggerHistoryMutation(String verb, Supplier<Future<?>> mutationSupplier) {
        if (cm == null) {
            out.println("[TUI] " + verb + " is unavailable in test routing mode.");
            out.flush();
            return;
        }
        if (mutationSupplier == null) {
            historyRendered = false;
            refreshHistoryFromManager();
            return;
        }
        final Future<?> pending;
        try {
            pending = mutationSupplier.get();
        } catch (Throwable t) {
            cm.getIo().toolError("Unable to " + verb.toLowerCase(Locale.ROOT) + ": " + t.getMessage(), "TUI");
            return;
        }
        if (pending == null) {
            historyRendered = false;
            refreshHistoryFromManager();
            return;
        }
        CompletableFuture
                .runAsync(() -> {
                    try {
                        pending.get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new CompletionException(e);
                    } catch (ExecutionException | CancellationException e) {
                        throw new CompletionException(e);
                    }
                })
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        var cause = throwable.getCause() == null ? throwable : throwable.getCause();
                        cm.getIo().toolError(verb + " failed: " + cause.getMessage(), "TUI");
                    }
                    historyRendered = false;
                    refreshHistoryFromManager();
                });
    }

    private void handleEnter() {
        if (currentFocus == TuiView.Focus.HISTORY) {
            applyHistorySelection();
            return;
        }
        if (currentFocus != TuiView.Focus.PROMPT) {
            return;
        }
        var input = promptBuffer.toString();
        promptBuffer.setLength(0);
        console.renderPrompt("");
        var trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (trimmed.startsWith("/")) {
            handleCommand(trimmed);
        } else {
            submitLutz(trimmed);
        }
    }

    private void updateFocus(TuiView.Focus focus) {
        currentFocus = Objects.requireNonNull(focus, "focus");
        console.setFocus(currentFocus);
        if (currentFocus == TuiView.Focus.HISTORY) {
            refreshHistoryFromManager();
            if (historySelection >= 0) {
                console.setHistorySelection(historySelection);
            }
        } else if (currentFocus == TuiView.Focus.PROMPT) {
            console.renderPrompt(promptBuffer.toString());
        }
    }

    private void refreshHistoryFromManager() {
        List<Context> contexts;
        if (cm == null) {
            contexts = List.of();
        } else {
            var list = cm.getContextHistoryList();
            contexts = list == null ? List.of() : List.copyOf(list);
        }

        var newSelection = computeHistorySelection(contexts);
        var contextChanged = !contexts.equals(historyCache);
        var selectionChanged = newSelection != historySelection;

        if (historyRendered && !contextChanged && !selectionChanged) {
            return;
        }

        historyCache = contexts;
        historySelection = newSelection;
        console.renderHistory(historyCache, historySelection);
        console.setHistorySelection(historySelection);
        historyRendered = true;
    }

    private int computeHistorySelection(List<Context> contexts) {
        if (contexts.isEmpty()) {
            return -1;
        }
        if (cm != null) {
            var selected = cm.selectedContext();
            if (selected != null) {
                var idx = contexts.indexOf(selected);
                if (idx >= 0) {
                    return idx;
                }
            }
        }
        if (historySelection >= 0 && historySelection < contexts.size()) {
            return historySelection;
        }
        return contexts.size() - 1;
    }

    private void updateHistorySelection(int index) {
        if (historyCache.isEmpty()) {
            historySelection = -1;
            console.renderHistory(historyCache, historySelection);
            console.setHistorySelection(historySelection);
            historyRendered = true;
            return;
        }
        var clamped = Math.max(0, Math.min(index, historyCache.size() - 1));
        historySelection = clamped;
        console.renderHistory(historyCache, historySelection);
        console.setHistorySelection(historySelection);
        historyRendered = true;
    }

    private void handleCommand(String cmd) {
        var normalized = cmd.toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "/c", "/chips" -> {
                console.toggleChipPanel();
                out.println("[TUI] Chip panel toggled.");
                out.flush();
            }
            case "/t", "/tasks" -> {
                console.toggleTaskList();
                out.println("[TUI] Task list toggled.");
                out.flush();
            }
            case "/q", "/quit", "/exit" -> {
                running = false;
                out.println("[TUI] Exiting...");
                out.flush();
            }
            default -> {
                out.println("[TUI] Unknown command: " + cmd);
                out.flush();
            }
        }
    }

    private void submitLutz(String prompt) {
        if (cm == null) {
            out.println("[TUI] Lutz is unavailable in test routing mode.");
            out.flush();
            return;
        }
        console.setTaskInProgress(true);
        try {
            var future =
                    cm.submitLlmAction(() -> {
                        try (var scope = cm.beginTask(prompt, true)) {
                            var planningModel = cm.getCodeModel();
                            var agent = new SearchAgent(
                                    cm.liveContext(),
                                    prompt,
                                    planningModel,
                                    SearchAgent.Objective.LUTZ,
                                    scope);
                            agent.execute();
                        }
                    });
            future.whenComplete((ignored, ex) -> {
                console.setTaskInProgress(false);
                if (ex != null) {
                    cm.getIo().toolError("Lutz execution failed: " + ex.getMessage(), "TUI");
                }
            });
        } catch (Throwable t) {
            console.setTaskInProgress(false);
            cm.getIo().toolError("Unable to submit Lutz run: " + t.getMessage(), "TUI");
        }
    }

    private void printWelcome() {
        out.println();
        out.println("Brokk TUI - interactive mode");
        out.println("Slash commands (map to Ctrl/Cmd shortcuts):");
        out.println("  /chips (/c)  -> toggle context chip panel");
        out.println("  /tasks (/t)  -> toggle read-only task list");
        out.println("  /quit  (/q)  -> exit the TUI");
        out.println("Enter any other text to launch a Lutz search with the default models.");
        out.flush();
    }
}
