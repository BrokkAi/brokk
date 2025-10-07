package io.github.jbellis.brokk.context;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import io.github.jbellis.brokk.AbstractProject;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Thread-safe undo/redo stack for *frozen* {@link Context} snapshots.
 *
 * <p>The newest entry is always at the tail of {@link #history}. All public methods are {@code synchronized}, so
 * callers need no extra locking.
 *
 * <p><strong>Contract:</strong> every {@code Context} handed to this class <em>must already be frozen</em> (see
 * {@link Context#freezeAndCleanup()}). This class never calls {@code freeze()} on its own.
 */
public class ContextHistory {
    private static final Logger logger = LogManager.getLogger(ContextHistory.class);
    private static final int MAX_DEPTH = 100;

    public record ResetEdge(UUID sourceId, UUID targetId) {}

    public record GitState(String commitHash, @Nullable String diff) {}

    public record DeletedFile(ProjectFile file, String content, boolean wasTracked) {}

    public record ContextHistoryEntryInfo(List<DeletedFile> deletedFiles) {}

    private final Deque<Context> history = new ArrayDeque<>();
    private final Deque<Context> redo = new ArrayDeque<>();
    private final List<ResetEdge> resetEdges = new ArrayList<>();
    private final Map<UUID, GitState> gitStates = new HashMap<>();
    private final Map<UUID, ContextHistoryEntryInfo> entryInfos = new HashMap<>();
    private Context liveContext;

    /** UI-selection; never {@code null} once an initial context is set. */
    private @Nullable Context selected;

    public ContextHistory(Context liveContext) {
        var fr = liveContext.freezeAndCleanup();
        this.liveContext = fr.liveContext();
        var frozen = fr.frozenContext();
        history.add(frozen);
        selected = frozen;
    }

    public ContextHistory(List<Context> contexts) {
        this(contexts, List.of(), Map.of(), Map.of());
    }

    public ContextHistory(List<Context> contexts, List<ResetEdge> resetEdges) {
        this(contexts, resetEdges, Map.of(), Map.of());
    }

    public ContextHistory(List<Context> contexts, List<ResetEdge> resetEdges, Map<UUID, GitState> gitStates) {
        this(contexts, resetEdges, gitStates, Map.of());
    }

    public ContextHistory(
            List<Context> frozenContexts,
            List<ResetEdge> resetEdges,
            Map<UUID, GitState> gitStates,
            Map<UUID, ContextHistoryEntryInfo> entryInfos) {
        if (frozenContexts.isEmpty()) {
            throw new IllegalArgumentException("Cannot initialize ContextHistory from empty list of contexts");
        }
        history.addAll(frozenContexts);
        this.resetEdges.addAll(resetEdges);
        this.gitStates.putAll(gitStates);
        this.entryInfos.putAll(entryInfos);
        this.liveContext = Context.unfreeze(castNonNull(history.peekLast()));
        selected = history.peekLast();
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

    public synchronized Context getLiveContext() {
        return liveContext;
    }

    public synchronized boolean hasUndoStates() {
        return history.size() > 1;
    }

    public synchronized boolean hasRedoStates() {
        return !redo.isEmpty();
    }

    public synchronized @Nullable Context getSelectedContext() {
        if (selected == null || !history.contains(selected)) {
            selected = topContext();
        }
        return selected;
    }

    /**
     * Returns {@code true} iff {@code ctx} is present in history.
     *
     * @param ctx the context to check
     * @return {@code true} iff {@code ctx} is present in history.
     */
    public synchronized boolean setSelectedContext(@Nullable Context ctx) {
        if (ctx != null && history.contains(ctx)) {
            selected = ctx;
            return true;
        }
        if (logger.isWarnEnabled()) {
            logger.warn(
                    "Attempted to select context {} not present in history (history size: {}, available contexts: {})",
                    ctx == null ? "null" : ctx,
                    history.size(),
                    history.stream().map(Context::toString).collect(java.util.stream.Collectors.joining(", ")));
        }
        return false;
    }

    /**
     * Applies the given function to the live context, freezes the result, and pushes it to the history.
     *
     * @param contextGenerator a function to apply to the live context
     * @return the new live context
     */
    public synchronized Context push(Function<Context, Context> contextGenerator) {
        var updatedLiveContext = contextGenerator.apply(this.liveContext);
        if (this.liveContext.equals(updatedLiveContext)) {
            return this.liveContext;
        }

        var fr = updatedLiveContext.freezeAndCleanup();
        this.liveContext = fr.liveContext();
        addFrozenContextAndClearRedo(fr.frozenContext());
        return this.liveContext;
    }

    /**
     * Applies the given function to the top (frozen) context and pushes the result to the history. This operates on the
     * frozen context rather than the live context, making it suitable for silent updates that shouldn't trigger file
     * reloading or history compression.
     *
     * <p>Unlike with push(), the `contextGenerator` must not generate dynamic ContextFragments.
     */
    public synchronized Context pushQuietly(Function<Context, Context> contextGenerator) {
        var updatedTopContext = contextGenerator.apply(topContext());
        if (topContext().equals(updatedTopContext)) {
            return topContext();
        }

        // apply the same transformation to the live context
        assert !updatedTopContext.containsDynamicFragments();
        liveContext = contextGenerator.apply(liveContext);
        addFrozenContextAndClearRedo(updatedTopContext);
        return updatedTopContext;
    }

    public synchronized void pushLiveAndFrozen(Context live, Context frozen) {
        this.liveContext = live;
        addFrozenContextAndClearRedo(frozen);
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
     * Replaces the most recent context in history with the provided live and frozen contexts. This is useful for
     * coalescing rapid changes into a single history entry.
     */
    public synchronized void replaceTop(Context newLive, Context newFrozen) {
        assert !newFrozen.containsDynamicFragments();
        assert !history.isEmpty() : "Cannot replace top context in empty history";
        history.removeLast();
        history.addLast(newFrozen);
        redo.clear();
        selected = newFrozen;
        liveContext = newLive;
    }

    /**
     * Processes external file changes by deciding whether to replace the top context or push a new one. If the current
     * top context's action starts with "Load external changes", it updates the count and replaces it. Otherwise, it
     * pushes a new context entry.
     *
     * @return The new frozen context if a change was made, otherwise null.
     */
    public synchronized @Nullable Context processExternalFileChangesIfNeeded() {
        var fr = liveContext.freezeAndCleanup();
        if (!topContext().workspaceContentEquals(fr.frozenContext())) {
            var topCtx = topContext();
            var previousAction = topCtx.getAction();
            if (!previousAction.startsWith("Load external changes")) {
                // If the previous action is not about external changes, push a new context
                var newLiveContext = fr.liveContext()
                        .withParsedOutput(null, CompletableFuture.completedFuture("Load external changes"));
                var cleaned = newLiveContext.freezeAndCleanup();
                pushLiveAndFrozen(cleaned.liveContext(), cleaned.frozenContext());
                return cleaned.frozenContext();
            }

            // Parse the existing action to extract the count if present
            var pattern = Pattern.compile("Load external changes(?: \\((\\d+)\\))?");
            var matcher = pattern.matcher(previousAction);
            int newCount;
            if (matcher.matches() && matcher.group(1) != null) {
                var countGroup = matcher.group(1);
                try {
                    newCount = Integer.parseInt(countGroup) + 1;
                } catch (NumberFormatException e) {
                    newCount = 2;
                }
            } else {
                newCount = 2;
            }

            // Form the new action string with the updated count
            var newAction = newCount > 1 ? "Load external changes (%d)".formatted(newCount) : "Load external changes";
            var newLiveContext = fr.liveContext().withParsedOutput(null, CompletableFuture.completedFuture(newAction));
            var cleaned = newLiveContext.freezeAndCleanup();
            replaceTop(cleaned.liveContext(), cleaned.frozenContext());
            return cleaned.frozenContext();
        }
        return null;
    }

    /* ─────────────── undo / redo  ────────────── */

    public record UndoResult(boolean wasUndone, int steps) {
        public static UndoResult none() {
            return new UndoResult(false, 0);
        }

        public static UndoResult success(int n) {
            return new UndoResult(true, n);
        }
    }

    public synchronized UndoResult undo(int steps, IConsoleIO io, AbstractProject project) {
        if (steps <= 0 || !hasUndoStates()) {
            return UndoResult.none();
        }

        var toUndo = Math.min(steps, history.size() - 1);
        for (int i = 0; i < toUndo; i++) {
            var popped = history.removeLast();
            resetEdges.removeIf(edge -> edge.targetId().equals(popped.id()));
            undoFileDeletions(io, project, popped);
            redo.addLast(popped);
        }
        var newTop = history.peekLast();
        applyFrozenContextToWorkspace(newTop, io);
        liveContext = Context.unfreeze(castNonNull(newTop));
        selected = topContext();
        return UndoResult.success(toUndo);
    }

    private void undoFileDeletions(IConsoleIO io, AbstractProject project, Context popped) {
        getEntryInfo(popped.id()).ifPresent(info -> {
            if (info.deletedFiles().isEmpty()) {
                return;
            }

            var trackedToStage = new ArrayList<ProjectFile>();

            for (var deletedFile : info.deletedFiles()) {
                var pf = deletedFile.file();
                try {
                    pf.write(deletedFile.content());
                    if (deletedFile.wasTracked()) {
                        trackedToStage.add(pf);
                    }
                } catch (IOException e) {
                    var msg = "Failed to restore deleted file during undo: " + pf;
                    io.toolError(msg, "Undo Error");
                    logger.error(msg, e);
                }
            }

            if (!trackedToStage.isEmpty() && project.hasGit()) {
                try {
                    project.getRepo().add(trackedToStage);
                    io.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            "Restored and staged files: "
                                    + String.join(
                                            ", ",
                                            trackedToStage.stream()
                                                    .map(Object::toString)
                                                    .toList()));
                } catch (Exception e) {
                    var msg = "Failed to stage restored files during undo: " + e.getMessage();
                    io.toolError(msg, "Undo Error");
                    logger.error(msg, e);
                }
            }
        });
    }

    public synchronized UndoResult undoUntil(@Nullable Context target, IConsoleIO io, AbstractProject project) {
        if (target == null) {
            return UndoResult.none();
        }
        var idx = indexOf(target);
        if (idx < 0) return UndoResult.none();
        var distance = history.size() - 1 - idx;
        return distance == 0 ? UndoResult.none() : undo(distance, io, project);
    }

    /**
     * Redoes the last undone operation.
     *
     * @param io the console IO for feedback
     * @return {@code true} if something was redone.
     */
    public synchronized boolean redo(IConsoleIO io, AbstractProject project) {
        if (redo.isEmpty()) return false;
        var popped = redo.removeLast();
        history.addLast(popped);
        truncateHistory();
        liveContext = Context.unfreeze(castNonNull(popped));
        selected = topContext();
        applyFrozenContextToWorkspace(history.peekLast(), io);
        redoFileDeletions(io, project, popped);
        return true;
    }

    private void redoFileDeletions(IConsoleIO io, AbstractProject project, Context popped) {
        getEntryInfo(popped.id()).ifPresent(info -> {
            var filesToDelete =
                    info.deletedFiles().stream().map(DeletedFile::file).toList();
            if (!filesToDelete.isEmpty()) {
                try {
                    if (project.hasGit()) {
                        project.getRepo().forceRemoveFiles(filesToDelete);
                    } else {
                        for (var file : filesToDelete) {
                            Files.deleteIfExists(file.absPath());
                        }
                    }
                    io.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            "Deleted files as part of redo: "
                                    + String.join(
                                            ", ",
                                            filesToDelete.stream()
                                                    .map(Object::toString)
                                                    .toList()));
                } catch (Exception e) {
                    io.toolError("Failed to delete files during redo: " + e.getMessage(), "Redo error");
                    logger.error("Failed to delete files during redo", e);
                }
            }
        });
    }

    /* ────────────────────────── private helpers ─────────────────────────── */

    private void truncateHistory() {
        while (history.size() > MAX_DEPTH) {
            var removed = history.removeFirst();
            gitStates.remove(removed.id());
            entryInfos.remove(removed.id());
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

    public synchronized void addGitState(UUID contextId, GitState gitState) {
        gitStates.put(contextId, gitState);
    }

    public synchronized Optional<GitState> getGitState(UUID contextId) {
        return Optional.ofNullable(gitStates.get(contextId));
    }

    public synchronized Map<UUID, GitState> getGitStates() {
        return Map.copyOf(gitStates);
    }

    public synchronized void addEntryInfo(UUID contextId, ContextHistoryEntryInfo info) {
        entryInfos.put(contextId, info);
    }

    public synchronized Optional<ContextHistoryEntryInfo> getEntryInfo(UUID contextId) {
        return Optional.ofNullable(entryInfos.get(contextId));
    }

    public synchronized Map<UUID, ContextHistoryEntryInfo> getEntryInfos() {
        return Map.copyOf(entryInfos);
    }

    /**
     * Occasionally you will need to determine which live fragment a frozen fragment came from. This does that by
     * assuming that the live and frozen Contexts have their fragments in the same order.
     */
    public synchronized ContextFragment mapToLiveFragment(ContextFragment f) {
        if (!(f instanceof FrozenFragment)) {
            return f;
        }

        var ctx = topContext();
        int idx = ctx.getAllFragmentsInDisplayOrder().indexOf(f);
        assert idx >= 0 : "Fragment %s not found in top context %s".formatted(f, ctx.getAllFragmentsInDisplayOrder());
        return getLiveContext().getAllFragmentsInDisplayOrder().get(idx);
    }

    /** Applies the state from a frozen context to the workspace by restoring files. */
    private void applyFrozenContextToWorkspace(@Nullable Context frozenContext, IConsoleIO io) {
        if (frozenContext == null) {
            logger.warn("Attempted to apply null context to workspace");
            return;
        }
        assert !frozenContext.containsDynamicFragments();
        frozenContext
                .getEditableFragments()
                .filter(fragment -> fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH)
                .forEach(fragment -> {
                    assert fragment.files().size() == 1 : fragment.files();

                    var pf = fragment.files().iterator().next();
                    try {
                        var newContent = fragment.text();
                        var currentContent = pf.exists() ? pf.read().orElse("") : "";

                        if (!newContent.equals(currentContent)) {
                            pf.write(newContent);
                            var restoredFiles = new ArrayList<String>();
                            restoredFiles.add(pf.toString());
                            io.showNotification(
                                    IConsoleIO.NotificationRole.INFO,
                                    "Restored files: " + String.join(", ", restoredFiles));
                            io.updateWorkspace();
                        }
                    } catch (IOException e) {
                        io.toolError("Failed to restore file " + pf + ": " + e.getMessage(), "Error");
                    }
                });
    }
}
