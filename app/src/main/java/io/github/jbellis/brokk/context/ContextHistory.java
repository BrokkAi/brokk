package io.github.jbellis.brokk.context;

import io.github.jbellis.brokk.IConsoleIO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

/**
 * Thread-safe undo/redo stack for *frozen* {@link Context} snapshots.
 *
 * <p>The newest entry is always at the tail of {@link #history}.
 * All public methods are {@code synchronized}, so callers need no extra
 * locking.</p>
 *
 * <p><strong>Contract:</strong> every {@code Context} handed to this class
 * <em>must already be frozen</em> (see {@link Context#freezeAndCleanup()}).  This class
 * never calls {@code freeze()} on its own.</p>
 */
public class ContextHistory {
    private static final Logger logger = LogManager.getLogger(ContextHistory.class);
    private static final int MAX_DEPTH = 100;

    public record ResetEdge(UUID sourceId, UUID targetId) {}

    private final Deque<Context> history = new ArrayDeque<>();
    private final Deque<Context> redo   = new ArrayDeque<>();
    private final List<ResetEdge> resetEdges = new ArrayList<>();

    /** UI-selection; never {@code null} once an initial context is set. */
    private @Nullable Context selected;

    public ContextHistory(Context initialContext) {
        history.add(initialContext.freeze());
    }

    public ContextHistory(List<Context> contexts) {
        this(contexts, List.of());
    }

    public ContextHistory(List<Context> contexts, List<ResetEdge> resetEdges) {
        history.addAll(contexts);
        this.resetEdges.addAll(resetEdges);
    }

    /* ───────────────────────── public API ─────────────────────────── */

    /** Immutable view (oldest → newest). */
    public synchronized List<Context> getHistory() {
        return List.copyOf(history);
    }

    /** Latest context or {@code null} when uninitialised. */
    public synchronized Context topContext() {
        return castNonNull(history.peekLast());
    }

    public synchronized boolean hasUndoStates() { return history.size() > 1; }
    public synchronized boolean hasRedoStates() { return !redo.isEmpty();  }

    public synchronized @Nullable Context getSelectedContext() {
        if (selected == null || !history.contains(selected)) {
            selected = topContext();
        }
        return selected;
    }

    /**
     * Returns {@code true} iff {@code ctx} is present in history.
     * @param ctx the context to check
     * @return {@code true} iff {@code ctx} is present in history.
     */
    public synchronized boolean setSelectedContext(@Nullable Context ctx) {
        if (ctx != null && history.contains(ctx)) {
            selected = ctx;
            return true;
        }
        if (logger.isWarnEnabled()) {
            logger.warn("Attempted to select context {} not present in history (history size: {}, available contexts: {})", 
                       ctx == null ? "null" : ctx, 
                       history.size(),
                       history.stream().map(Context::toString).collect(java.util.stream.Collectors.joining(", ")));
        }
        return false;
    }

    /** Initialise with a single frozen context. */
    public synchronized void setInitialContext(Context frozenInitial) {
        assert !frozenInitial.containsDynamicFragments();
        history.clear();
        redo.clear();
        resetEdges.clear();
        history.add(frozenInitial);
        selected = frozenInitial;
        logger.debug("Initial context set: {}", frozenInitial);
    }

    /** Push {@code frozen} and clear redo stack. */
    public synchronized void addFrozenContextAndClearRedo(Context frozen) {
        assert !frozen.containsDynamicFragments();
        history.addLast(frozen);
        truncateHistory();
        redo.clear();
        selected = frozen;
    }

    /**
     * Replaces the most recent context in history with the provided frozen context.
     * This is useful for coalescing rapid changes into a single history entry.
     */
    public synchronized void replaceTopContext(Context newFrozenContext) {
        assert !newFrozenContext.containsDynamicFragments();
        assert !history.isEmpty() : "Cannot replace top context in empty history";
        history.removeLast();
        history.addLast(newFrozenContext);
        redo.clear();
        selected = newFrozenContext;
    }

    /* ─────────────── undo / redo  ────────────── */

    public record UndoResult(boolean wasUndone, int steps) {
        public static UndoResult none()            { return new UndoResult(false, 0); }
        public static UndoResult success(int n)    { return new UndoResult(true, n);  }
    }

    public synchronized UndoResult undo(int steps, IConsoleIO io) {
        if (steps <= 0 || !hasUndoStates()) {
            return UndoResult.none();
        }

        var toUndo = Math.min(steps, history.size() - 1);
        for (int i = 0; i < toUndo; i++) {
            var popped = history.removeLast();
            resetEdges.removeIf(edge -> edge.targetId().equals(popped.id()));
            redo.addLast(popped);
        }
        applyFrozenContextToWorkspace(history.peekLast(), io);
        selected = topContext();
        return UndoResult.success(toUndo);
    }

    public synchronized UndoResult undoUntil(@Nullable Context target, IConsoleIO io) {
        if (target == null) {
            return UndoResult.none();
        }
        var idx = indexOf(target);
        if (idx < 0) return UndoResult.none();
        var distance = history.size() - 1 - idx;
        return distance == 0 ? UndoResult.none() : undo(distance, io);
    }

    /**
     * Redoes the last undone operation.
     * @param io the console IO for feedback
     * @return {@code true} if something was redone.
     */
    public synchronized boolean redo(IConsoleIO io) {
        if (redo.isEmpty()) return false;
        var popped = redo.removeLast();
        history.addLast(popped);
        truncateHistory();
        selected = topContext();
        applyFrozenContextToWorkspace(history.peekLast(), io);
        return true;
    }

    /* ────────────────────────── private helpers ─────────────────────────── */

    private void truncateHistory() {
        while (history.size() > MAX_DEPTH) {
            var removed = history.removeFirst();
            var historyIds = history.stream().map(Context::id).collect(java.util.stream.Collectors.toSet());
            resetEdges.removeIf(edge -> !historyIds.contains(edge.sourceId()) || !historyIds.contains(edge.targetId()));
            if (logger.isDebugEnabled()) {
                logger.debug("Truncated history (removed oldest context: {})", removed);
            }
        }
    }

    private int indexOf(Context ctx) {
        var i = 0;
        for (var c : history) {
            if (c.equals(ctx)) return i;
            i++;
        }
        return -1;
    }

    public synchronized void addResetEdge(Context source, Context target) {
        assert !source.containsDynamicFragments() && !target.containsDynamicFragments();
        resetEdges.add(new ResetEdge(source.id(), target.id()));
    }

    public synchronized List<ResetEdge> getResetEdges() {
        return List.copyOf(resetEdges);
    }

    /**
     * Applies the state from a frozen context to the workspace by restoring files.
     */
    private void applyFrozenContextToWorkspace(@Nullable Context frozenContext, IConsoleIO io) {
        if (frozenContext == null) {
            logger.warn("Attempted to apply null context to workspace");
            return;
        }
        assert !frozenContext.containsDynamicFragments();
        frozenContext.editableFiles.forEach(fragment -> {
            assert fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH : fragment.getType();
            assert fragment.files().size() == 1 : fragment.files();

            var pf = fragment.files().iterator().next();
            try {
                var newContent = fragment.text();
                var currentContent = pf.exists() ? pf.read() : "";
                
                if (!newContent.equals(currentContent)) {
                    var restoredFiles = new ArrayList<String>();
                    pf.write(newContent);
                    restoredFiles.add(pf.toString());
                    if (!restoredFiles.isEmpty()) {
                        io.systemOutput("Restored files: " + String.join(", ", restoredFiles));
                    }
                }
            } catch (IOException e) {
                io.toolError("Failed to restore file " + pf + ": " + e.getMessage(), "Error");
            }
        });
    }
}
