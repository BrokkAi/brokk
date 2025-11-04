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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

    /** Centralized diff caching/memoization for this history. */
    private final DiffService diffService;

    public ContextHistory(Context liveContext) {
        pushLive(liveContext);
        this.diffService = new DiffService(this);
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
    public synchronized Context topContext() {
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
        var updatedLiveContext = contextGenerator.apply(topContext());
        // we deliberately do NOT use a deep equals() here, since we don't want to block for dynamic fragments to
        // materialize
        if (topContext() == updatedLiveContext) {
            return topContext();
        }

        pushLive(updatedLiveContext);
        return topContext();
    }

    /** Push {@code ctx}, select it, and clear redo stack. */
    public synchronized void pushLive(Context ctx) {
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
        var refreshedLive = topContext().copyAndRefresh(changed);
        if (refreshedLive.equals(topContext())) {
            return null;
        }

        var previousAction = topContext().getAction();
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
            pushLive(updatedLive);
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

    /**
     * Centralized diff cache + dispatcher. Keys by current context id assuming a single stable predecessor per context.
     */
    public static final class DiffService {
        private final ContextHistory history;
        private final ConcurrentHashMap<UUID, CompletableFuture<List<Context.DiffEntry>>> cache =
                new ConcurrentHashMap<>();

        DiffService(ContextHistory history) {
            this.history = history;
        }

        /** Non-blocking: returns cached result if ready. */
        public Optional<List<Context.DiffEntry>> peek(Context curr) {
            var cf = cache.get(curr.id());
            if (cf != null && cf.isDone()) {
                return java.util.Optional.ofNullable(cf.getNow(null));
            }
            return java.util.Optional.empty();
        }

        /** Load or compute the diff (shared across callers). */
        public CompletableFuture<List<Context.DiffEntry>> diff(Context curr) {
            return cache.computeIfAbsent(
                    curr.id(),
                    id -> CompletableFuture.supplyAsync(() -> {
                        var prev = history.previousOf(curr);
                        if (prev == null) return java.util.List.of();
                        return curr.getDiff(prev);
                    }));
        }

        /** Best-effort prefetch of all contexts that have a predecessor. */
        public void warmUp(List<Context> contexts) {
            for (var c : contexts) {
                if (history.previousOf(c) != null) {
                    diff(c);
                }
            }
        }

        /** Clear all cached entries. */
        public void clear() {
            cache.clear();
        }

        /** Retain only diffs for the provided set of current Context ids. */
        public void retainOnly(java.util.Set<UUID> currentIds) {
            cache.keySet().retainAll(currentIds);
        }
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
        applySnapshotToWorkspace(topContext(), io);
        selected = topContext();
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
        selected = topContext();
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

    /** Applies the state from a context to the workspace by restoring files. */
    private void applySnapshotToWorkspace(@Nullable Context snapshot, IConsoleIO io) {
        if (snapshot == null) {
            logger.warn("Attempted to apply null context to workspace");
            return;
        }
        snapshot.getEditableFragments()
                .filter(fragment -> fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH)
                .forEach(fragment -> {
                    assert fragment.files().size() == 1 : fragment.files();

                    var pf = fragment.files().iterator().next();
                    try {
                        if (fragment instanceof ContextFragment.ComputedFragment df) {
                            df.computedText().onComplete((newContent, ex) -> {
                                if (ex != null) {
                                    io.toolError("Failed to restore file " + pf + ": " + ex.getMessage(), "Error");
                                } else {
                                    try {
                                        applyFragmentSnapshotToWorkspace(newContent, pf, io);
                                    } catch (IOException e) {
                                        io.toolError("Failed to restore file " + pf + ": " + ex.getMessage(), "Error");
                                    }
                                }
                            });
                        } else {
                            applyFragmentSnapshotToWorkspace(fragment.text(), pf, io);
                        }
                    } catch (IOException e) {
                        io.toolError("Failed to restore file " + pf + ": " + e.getMessage(), "Error");
                    }
                });
    }

    private void applyFragmentSnapshotToWorkspace(String newContent, ProjectFile pf, IConsoleIO io) throws IOException {
        var currentContent = pf.exists() ? pf.read().orElse("") : "";

        if (!newContent.equals(currentContent)) {
            pf.write(newContent);
            var restoredFiles = new ArrayList<String>();
            restoredFiles.add(pf.toString());
            io.showNotification(
                    IConsoleIO.NotificationRole.INFO, "Restored files: " + String.join(", ", restoredFiles));
            io.updateWorkspace();
        }
    }
}
