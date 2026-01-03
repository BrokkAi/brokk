package ai.brokk.context;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.IProject;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
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
    public static final Duration SNAPSHOT_AWAIT_TIMEOUT = Duration.ofSeconds(5);

    public record ResetEdge(UUID sourceId, UUID targetId) {}

    public record GitState(String commitHash, @Nullable String diff) {}

    public record DeletedFile(ProjectFile file, String content, boolean wasTracked) {}

    public record ContextHistoryEntryInfo(List<DeletedFile> deletedFiles) {}

    private final Deque<Context> history = new ArrayDeque<>();
    private final Deque<Context> redo = new ArrayDeque<>();
    private final List<ResetEdge> resetEdges = new ArrayList<>();
    private final Map<UUID, GitState> gitStates = new HashMap<>();
    private final Map<UUID, ContextHistoryEntryInfo> entryInfos = new HashMap<>();

    /**
     * Tracks the ID of the last context created by an external file change to handle continuations.
     */
    private @Nullable UUID lastExternalChangeId;

    /**
     * UI-selection; never {@code null} once an initial context is set.
     */
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

    public synchronized void replaceTopInternal(Context newLive) {
        assert !history.isEmpty() : "Cannot replace top context in empty history";
        history.removeLast();
        history.addLast(newLive);
        redo.clear();
        selected = newLive;
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

    /**
     * Immutable view (oldest → newest).
     */
    public synchronized List<Context> getHistory() {
        return List.copyOf(history);
    }

    /**
     * Latest context or {@code null} when uninitialised.
     */
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
            // Ensure diffs for the selected context start computing
            diffService.diff(selected);
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

    /**
     * Push {@code ctx}, select it, and clear redo stack.
     */
    public synchronized void pushContext(Context ctx) {
        pushContextInternal(ctx, true);
    }

    @Blocking
    public synchronized @Nullable Context processExternalFileChangesIfNeeded(Set<ProjectFile> changed) {
        var base = liveContext();

        // Identify the affected fragments by referenced ProjectFiles
        var toReplace = base.allFragments()
                .filter(f -> {
                    var filesOpt = f.files().await(SNAPSHOT_AWAIT_TIMEOUT);
                    return filesOpt.map(projectFiles -> projectFiles.stream().anyMatch(changed::contains))
                            .orElse(false);
                })
                .toList();

        if (toReplace.isEmpty()) {
            return null; // nothing to refresh
        }

        // Refresh only the affected fragments; do NOT precompute text(), to keep snapshots cleared pre-serialization.
        var replacements = toReplace.stream().map(ContextFragment::refreshCopy).toList();

        // Merge: keep all unaffected fragments, but swap in the refreshed ones.
        Context merged = base.removeFragments(toReplace).addFragments(replacements);

        // Guard: if refresh produced no actual content differences, avoid adding a no-op
        // Note: this may block briefly while diffs are computed; this method is @Blocking.
        var delta = ContextDelta.between(base, merged).join();
        if (delta.isEmpty()) {
            return null; // nothing meaningful changed; do not push/replace
        }

        // Maintain continuation semantics for rapid external changes.
        boolean isContinuation = Objects.equals(base.id(), lastExternalChangeId);

        // parsedOutput == null indicates no AI result (render no icon in activity)
        var updatedLive = merged.withParsedOutput(null);

        if (isContinuation) {
            replaceTopInternal(updatedLive);
        } else {
            pushContextInternal(updatedLive, false);
        }
        lastExternalChangeId = updatedLive.id();
        return updatedLive;
    }

    /**
     * Returns the previous frozen Context for the given one, or {@code null} if none (oldest).
     */
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

    /**
     * Exposes the centralized diff service.
     */
    public DiffService getDiffService() {
        return diffService;
    }

    /* ─────────────── undo / redo  ────────────── */

    public record UndoResult(boolean wasUndone, int steps, Set<ProjectFile> changedFiles) {
        public static UndoResult none() {
            return new UndoResult(false, 0, Set.of());
        }

        public static UndoResult success(int n, Set<ProjectFile> changedFiles) {
            return new UndoResult(true, n, changedFiles);
        }
    }

    public synchronized UndoResult undo(int steps, IConsoleIO io, IProject project) {
        if (steps <= 0 || !hasUndoStates()) {
            return UndoResult.none();
        }

        var toUndo = Math.min(steps, history.size() - 1);
        for (int i = 0; i < toUndo; i++) {
            var popped = history.removeLast();
            // Snapshot the context before moving it to redo stack, as it was the live context
            // and its content might not be cached yet.
            try {
                popped.awaitContextsAreComputed(SNAPSHOT_AWAIT_TIMEOUT);
            } catch (InterruptedException e) {
                logger.warn("Interrupted while waiting for undo state to complete.");
            }
            resetEdges.removeIf(edge -> edge.targetId().equals(popped.id()));
            undoFileDeletions(io, project, popped);
            redo.addLast(popped);
        }
        var changedFiles = applySnapshotToWorkspace(liveContext(), io);
        selected = liveContext();
        // Start computing diffs for the new live context post-undo
        diffService.diff(selected);
        return UndoResult.success(toUndo, changedFiles);
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
                                    + trackedToStage.stream()
                                            .map(Object::toString)
                                            .collect(Collectors.joining(", ")));
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
     * @param wasRedone true if the redo was applied.
     * @param changedFiles the changes files from the result
     */
    public record RedoResult(boolean wasRedone, Set<ProjectFile> changedFiles) {
        public static RedoResult none() {
            return new RedoResult(false, Set.of());
        }

        public static RedoResult success(Set<ProjectFile> changedFiles) {
            return new RedoResult(true, changedFiles);
        }
    }

    public synchronized RedoResult redo(IConsoleIO io, IProject project) {
        if (redo.isEmpty()) return RedoResult.none();
        var popped = redo.removeLast();
        history.addLast(popped);
        truncateHistory();
        selected = liveContext();
        var changedFiles = applySnapshotToWorkspace(castNonNull(history.peekLast()), io);
        // Start computing diffs for the live context post-redo
        diffService.diff(selected);
        redoFileDeletions(io, project, popped);
        return RedoResult.success(changedFiles);
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
                                    + filesToDelete.stream()
                                            .map(Object::toString)
                                            .collect(Collectors.joining(", ")));
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
            if (logger.isDebugEnabled()) {
                logger.debug("Truncated history (removed oldest context: {})", removed);
            }
        }
    }

    /**
     * Internal helper to push a context with control over whether to capture a snapshot immediately.
     */
    private synchronized void pushContextInternal(Context ctx, boolean snapshotNow) {
        history.addLast(ctx);
        if (snapshotNow) {
            snapshotContext(ctx);
        }
        truncateHistory();
        redo.clear();
        selected = ctx;
    }


    /**
     * Performs synchronous snapshotting of the given context to ensure stable, historical restoration.
     */
    private void snapshotContext(Context ctx) {
        try {
            ctx.awaitContextsAreComputed(SNAPSHOT_AWAIT_TIMEOUT);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
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

    @Blocking
    private Set<ProjectFile> applySnapshotToWorkspace(Context snapshot, IConsoleIO io) {
        // Phase 0: wait once up front
        try {
            snapshot.awaitContextsAreComputed(ContextHistory.SNAPSHOT_AWAIT_TIMEOUT);
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for contexts to be computed", e);
        }

        // Phase 1: materialize all desired contents from the snapshot with bounded waits
        var desiredContents = new LinkedHashMap<ProjectFile, String>();
        var desiredImageBytes = new LinkedHashMap<ProjectFile, byte[]>();
        var materializationWarnings = new ArrayList<String>();

        // Restore editable project text files
        snapshot.getEditableFragments()
                .filter(fragment -> fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH)
                .forEach(fragment -> {
                    var filesOpt = fragment.files().tryGet();
                    if (filesOpt.isEmpty()) {
                        materializationWarnings.add(fragment.toString());
                        return;
                    }

                    var files = filesOpt.get();
                    assert files.size() == 1 : fragment.files();
                    var pf = files.iterator().next();

                    var awaited = fragment.text().tryGet();
                    if (awaited.isPresent()) {
                        desiredContents.put(pf, awaited.get());
                    } else {
                        materializationWarnings.add(fragment.toString());
                    }
                });

        // Restore project-backed image files (IMAGE_FILE)
        snapshot.allFragments()
                .filter(fragment -> fragment.getType() == ContextFragment.FragmentType.IMAGE_FILE)
                .forEach(fragment -> {
                    var filesOpt = fragment.files().tryGet();
                    if (filesOpt.isEmpty()) {
                        materializationWarnings.add(fragment.toString());
                        return;
                    }
                    var files = filesOpt.get();
                    if (files.size() != 1) {
                        materializationWarnings.add(fragment.toString());
                        return;
                    }
                    var pf = files.iterator().next();
                    // Only restore images that are within the project (ProjectFile)
                    var imageBytesCv = fragment.imageBytes();
                    if (imageBytesCv == null) {
                        materializationWarnings.add(fragment.toString());
                        return;
                    }
                    var bytesOpt = imageBytesCv.tryGet();
                    if (bytesOpt.isPresent()) {
                        desiredImageBytes.put(pf, bytesOpt.get());
                    } else {
                        materializationWarnings.add(fragment.toString());
                    }
                });

        // Phase 2: write all differing files and collect changed files
        var changedFiles = new HashSet<ProjectFile>();

        // Write text files
        for (var entry : desiredContents.entrySet()) {
            var pf = entry.getKey();
            var newContent = entry.getValue();
            try {
                var currentContent = pf.exists() ? pf.read().orElse("") : "";
                if (!Objects.equals(newContent, currentContent)) {
                    pf.write(newContent);
                    changedFiles.add(pf);
                }
            } catch (IOException e) {
                logger.error("Failed to restore file {} from snapshot", pf, e);
                io.toolError("Failed to restore file " + pf + ": " + e.getMessage(), "Undo/Redo Error");
            }
        }

        // Write image files
        for (var entry : desiredImageBytes.entrySet()) {
            var pf = entry.getKey();
            var bytes = entry.getValue();
            if (bytes == null) continue;
            try {
                byte[] currentBytes = Files.exists(pf.absPath()) ? Files.readAllBytes(pf.absPath()) : null;
                if (currentBytes == null || !java.util.Arrays.equals(currentBytes, bytes)) {
                    Files.write(pf.absPath(), bytes);
                    changedFiles.add(pf);
                }
            } catch (IOException e) {
                logger.error("Failed to restore image file {} from snapshot", pf, e);
                io.toolError("Failed to restore image file " + pf + ": " + e.getMessage(), "Undo/Redo Error");
            }
        }

        if (!changedFiles.isEmpty()) {
            io.showNotification(
                    IConsoleIO.NotificationRole.INFO,
                    "Restored files: "
                            + changedFiles.stream().map(ProjectFile::toString).collect(Collectors.joining(", ")));
            io.updateWorkspace();
        }

        if (!materializationWarnings.isEmpty()) {
            io.toolError(
                    "Some files could not be restored within timeout: " + String.join(", ", materializationWarnings),
                    "Undo/Redo Warning");
        }

        return changedFiles;
    }
}
