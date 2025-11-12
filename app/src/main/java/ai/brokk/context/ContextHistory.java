package ai.brokk.context;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.IConsoleIO;
import ai.brokk.IProject;
import ai.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Thread-safe undo/redo stack for {@link Context} snapshots with a live-context, non-blocking async design.
 *
 * <p>The newest entry is always at the tail of {@link #history}. All public methods are {@code synchronized}, so
 * callers need no extra locking.
 *
 * <p><strong>Contract:</strong> Contexts stored in this history are <em>live</em> (contain dynamic fragments with
 * {@link ai.brokk.util.ComputedValue} futures). This class does NOT freeze contexts before storing them. For
 * serialization, use {@link #applySnapshotToWorkspace(Context, IConsoleIO)} (Context, java.time.Duration)} to
 * materialize computed values as needed without blocking the UI.
 */
public class ContextHistory {
    private static final Logger logger = LogManager.getLogger(ContextHistory.class);
    private static final int MAX_DEPTH = 100;
    private static final Duration SNAPSHOT_AWAIT_TIMEOUT = Duration.ofSeconds(5);

    public record ResetEdge(UUID sourceId, UUID targetId) {}

    public record GitState(String commitHash, @Nullable String diff) {}

    public record DeletedFile(ProjectFile file, String content, boolean wasTracked) {}

    public record ContextHistoryEntryInfo(List<DeletedFile> deletedFiles) {}

    private final Deque<Context> history = new ArrayDeque<>();
    private final Deque<Context> redo = new ArrayDeque<>();
    private final List<ResetEdge> resetEdges = new ArrayList<>();
    private final Map<UUID, GitState> gitStates = new HashMap<>();
    private final Map<UUID, ContextHistoryEntryInfo> entryInfos = new HashMap<>();

    /** UI-selection; never {@code null} once an initial context is set. */
    private @Nullable Context selected;

    /**
     * Centralized diff service for computing and caching diffs between consecutive history entries.
     * Works with live contexts and uses asynchronous {@link ai.brokk.util.ComputedValue} evaluation
     * where needed to avoid blocking the UI.
     */
    private final DiffService diffService;

    public ContextHistory(Context liveContext) {
        pushContext(liveContext);
        this.diffService = new DiffService(this);
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
            List<Context> contexts,
            List<ResetEdge> resetEdges,
            Map<UUID, GitState> gitStates,
            Map<UUID, ContextHistoryEntryInfo> entryInfos) {
        if (contexts.isEmpty()) {
            throw new IllegalArgumentException("Cannot initialize ContextHistory from empty list of contexts");
        }
        history.addAll(contexts);
        this.resetEdges.addAll(resetEdges);
        this.gitStates.putAll(gitStates);
        this.entryInfos.putAll(entryInfos);
        selected = history.peekLast();
        this.diffService = new DiffService(this);
    }

    /* ───────────────────────── public API ─────────────────────────── */

    /** Immutable view (oldest → newest). */
    public synchronized List<Context> getHistory() {
        return List.copyOf(history);
    }

    /** Latest context or {@code null} when uninitialised. */
    public synchronized Context liveContext() {
        return castNonNull(history.peekLast());
    }

    public synchronized boolean hasUndoStates() {
        return history.size() > 1;
    }

    public synchronized boolean hasRedoStates() {
        return !redo.isEmpty();
    }

    public synchronized @Nullable Context getSelectedContext() {
        if (selected == null || !getContextIds().contains(selected.id())) {
            selected = liveContext();
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
        if (ctx != null && getContextIds().contains(ctx.id())) {
            selected = ctx;
            return true;
        }
        if (logger.isWarnEnabled()) {
            logger.warn(
                    "Attempted to select context {} not present in history (history size: {}, available contexts: {})",
                    ctx == null ? "null" : ctx,
                    history.size(),
                    history.stream().map(Context::toString).collect(Collectors.joining(", ")));
        }
        return false;
    }

    public synchronized Context push(Function<Context, Context> contextGenerator) {
        var updatedLiveContext = contextGenerator.apply(liveContext());
        // we deliberately do NOT use a deep equals() here, since we don't want to block for dynamic fragments to
        // materialize
        if (Objects.equals(liveContext(), updatedLiveContext)) {
            return liveContext();
        }

        pushContext(updatedLiveContext);
        return liveContext();
    }

    /** Push {@code ctx}, select it, and clear redo stack. */
    public synchronized void pushContext(Context ctx) {
        history.addLast(ctx);
        truncateHistory();
        redo.clear();
        selected = ctx;
    }

    /**
     * Replaces the most recent context in history with the provided live and frozen contexts. This is useful for
     * coalescing rapid changes into a single history entry.
     */
    public synchronized void replaceTop(Context newLive) {
        assert !history.isEmpty() : "Cannot replace top context in empty history";
        history.removeLast();
        history.addLast(newLive);
        redo.clear();
        selected = newLive;
    }

    /**
     * Processes external file changes using the refresh model with an explicit set of changed files.
     * Uses liveContext.copyAndRefresh(changed) to selectively refresh affected fragments.
     *
     * Keeps the existing "Load external changes (n)" counting behavior.
     *
     * @param changed the set of files that changed; may be empty
     * @return The new frozen context if a change was made, otherwise null.
     */
    public synchronized @Nullable Context processExternalFileChangesIfNeeded(Set<ProjectFile> changed) {
        var refreshedLive = liveContext().copyAndRefresh(changed);
        if (refreshedLive.equals(liveContext())) {
            return null;
        }

        var previousAction = liveContext().getAction();
        boolean isContinuation = previousAction.startsWith("Load external changes");

        String newAction = "Load external changes";
        if (isContinuation) {
            // Parse the existing action to extract the count if present
            var pattern = Pattern.compile("Load external changes(?: \\((\\d+)\\))?");
            var matcher = pattern.matcher(previousAction);
            int newCount;
            if (matcher.matches() && matcher.group(1) != null) {
                try {
                    newCount = Integer.parseInt(matcher.group(1)) + 1;
                } catch (NumberFormatException e) {
                    newCount = 2;
                }
            } else {
                newCount = 2;
            }
            newAction = "Load external changes (%d)".formatted(newCount);
        }

        var updatedLive = refreshedLive.withAction(CompletableFuture.completedFuture(newAction));

        if (isContinuation) {
            replaceTop(updatedLive);
        } else {
            pushContext(updatedLive);
        }
        return updatedLive;
    }

    /** Returns the previous frozen Context for the given one, or {@code null} if none (oldest). */
    public synchronized @Nullable Context previousOf(Context curr) {
        Context prev = null;
        for (var c : history) {
            if (c.equals(curr)) {
                return prev;
            }
            prev = c;
        }
        return null;
    }

    /** Exposes the centralized diff service. */
    public DiffService getDiffService() {
        return diffService;
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

    public synchronized UndoResult undo(int steps, IConsoleIO io, IProject project) {
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
        applySnapshotToWorkspace(liveContext(), io);
        selected = liveContext();
        return UndoResult.success(toUndo);
    }

    private void undoFileDeletions(IConsoleIO io, IProject project, Context popped) {
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

    public synchronized UndoResult undoUntil(@Nullable Context target, IConsoleIO io, IProject project) {
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
    public synchronized boolean redo(IConsoleIO io, IProject project) {
        if (redo.isEmpty()) return false;
        var popped = redo.removeLast();
        history.addLast(popped);
        truncateHistory();
        selected = liveContext();
        applySnapshotToWorkspace(history.peekLast(), io);
        redoFileDeletions(io, project, popped);
        return true;
    }

    private void redoFileDeletions(IConsoleIO io, IProject project, Context popped) {
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
            var historyIds = getContextIds();
            resetEdges.removeIf(edge -> !historyIds.contains(edge.sourceId()) || !historyIds.contains(edge.targetId()));
            // keep diff cache bounded to current history
            diffService.retainOnly(historyIds);
            if (logger.isDebugEnabled()) {
                logger.debug("Truncated history (removed oldest context: {})", removed);
            }
        }
    }

    private Set<UUID> getContextIds() {
        return history.stream().map(Context::id).collect(Collectors.toSet());
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

    /** Applies the state from a context to the workspace by restoring files. */
    private void applySnapshotToWorkspace(@Nullable Context snapshot, IConsoleIO io) {
        if (snapshot == null) {
            logger.warn("Attempted to apply null context to workspace");
            return;
        }

        // Phase 0: best-effort pre-warm; runs off-EDT in undo/redo flows
        snapshot.awaitContextsAreComputed(SNAPSHOT_AWAIT_TIMEOUT);

        // Phase 1: materialize all desired contents from the snapshot with bounded waits
        var desiredContents = new LinkedHashMap<ProjectFile, String>();
        var materializationWarnings = new ArrayList<String>();

        snapshot.getEditableFragments()
                .filter(fragment -> fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH)
                .forEach(fragment -> {
                    assert fragment.files().size() == 1 : fragment.files();
                    var pf = fragment.files().iterator().next();

                    try {
                        String newContent;
                        if (fragment instanceof ContextFragment.ComputedFragment df) {
                            var tryNow = df.computedText().tryGet();
                            if (tryNow.isPresent()) {
                                newContent = tryNow.get();
                            } else {
                                var awaited = df.computedText().await(SNAPSHOT_AWAIT_TIMEOUT);
                                if (awaited.isPresent()) {
                                    newContent = awaited.get();
                                } else {
                                    // Do not fall back to reading current disk state; we want the snapshot value
                                    materializationWarnings.add(pf.toString());
                                    return;
                                }
                            }
                        } else {
                            newContent = fragment.text();
                        }
                        desiredContents.put(pf, newContent);
                    } catch (Exception e) {
                        logger.warn("Failed to materialize snapshot content for {}: {}", pf, e.getMessage());
                        materializationWarnings.add(pf.toString());
                    }
                });

        // Phase 2: write all differing files and notify once
        var restoredFiles = new ArrayList<String>();
        for (var entry : desiredContents.entrySet()) {
            var pf = entry.getKey();
            var newContent = entry.getValue();
            try {
                var currentContent = pf.exists() ? pf.read().orElse("") : "";
                if (!Objects.equals(newContent, currentContent)) {
                    pf.write(newContent);
                    restoredFiles.add(pf.toString());
                }
            } catch (IOException e) {
                logger.error("Failed to restore file {} from snapshot", pf, e);
                io.toolError("Failed to restore file " + pf + ": " + e.getMessage(), "Undo/Redo Error");
            }
        }

        if (!restoredFiles.isEmpty()) {
            io.showNotification(
                    IConsoleIO.NotificationRole.INFO, "Restored files: " + String.join(", ", restoredFiles));
            io.updateWorkspace();
        }

        if (!materializationWarnings.isEmpty()) {
            io.toolError(
                    "Some files could not be restored within timeout: " + String.join(", ", materializationWarnings),
                    "Undo/Redo Warning");
        }
    }
}
