package ai.brokk.context;

import ai.brokk.ExceptionReporter;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.IGitRepo;
import ai.brokk.util.ContentDiffUtils;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Computes and caches diffs between consecutive history entries using a live-context, non-blocking async model.
 *
 * <p>Keys by current context id assuming a single stable predecessor per context. Works directly with live
 * contexts (containing {@link ai.brokk.util.ComputedValue} futures) and uses bounded awaits during diff
 * computation to avoid blocking the UI indefinitely. For fragments that timeout during diff computation,
 * falls back to empty content rather than blocking.
 *
 * <p>This service materializes computed values
 * asynchronously as needed via {@link ai.brokk.util.ComputedValue#await(java.time.Duration)}.
 */
public final class DiffService {
    private static final Logger logger = LogManager.getLogger(DiffService.class);

    private static final Duration TEXT_FALLBACK_TIMEOUT = Duration.ofSeconds(2);

    private final ContextHistory history;
    private final IContextManager cm;
    private final ConcurrentHashMap<UUID, CompletableFuture<List<Context.DiffEntry>>> cache = new ConcurrentHashMap<>();

    DiffService(ContextHistory history) {
        this.history = history;
        this.cm = history.liveContext().getContextManager();
    }

    /**
     * Non-blocking peek: returns cached result if ready, otherwise empty Optional.
     *
     * <p>Safe to call from the EDT; does not block or trigger computation.
     *
     * @param curr the current (new) context to peek diffs for
     * @return Optional containing the diff list if already computed, or empty if not ready
     */
    public Optional<List<Context.DiffEntry>> peek(Context curr) {
        var cf = cache.get(curr.id());
        if (cf != null && cf.isDone()) {
            //noinspection DataFlowIssue (return of `getNow` is not detected as nullable)
            return Optional.ofNullable(cf.getNow(null));
        }
        return Optional.empty();
    }

    /**
     * Computes or retrieves cached diff between this context and its predecessor using the project's background executor.
     *
     * @param curr the current (new) context to compute diffs for
     * @return CompletableFuture that will contain the list of diff entries
     */
    public CompletableFuture<List<Context.DiffEntry>> diff(Context curr) {
        return cache.computeIfAbsent(curr.id(), id -> {
            var cf = new CompletableFuture<List<Context.DiffEntry>>();
            cm.submitBackgroundTask("Compute diffs for context " + id, () -> {
                try {
                    var prev = history.previousOf(curr);
                    var result = (prev == null) ? List.<Context.DiffEntry>of() : computeDiff(curr, prev);
                    cf.complete(result);
                    return result;
                } catch (Throwable t) {
                    cf.completeExceptionally(t);
                    throw new RuntimeException(t);
                }
            });
            return cf;
        });
    }

    /**
     * Best-effort prefetch: triggers diff computation for all contexts with a predecessor.
     *
     * <p>Useful for warming up the cache with multiple contexts in parallel. Does not block the caller.
     *
     * @param contexts the list of contexts to prefetch diffs for
     */
    public void warmUp(List<Context> contexts) {
        for (var c : contexts) {
            if (history.previousOf(c) != null) {
                diff(c);
            }
        }
    }

    /**
     * Clears all cached diff entries.
     *
     * <p>Useful for freeing memory or forcing recomputation of diffs.
     */
    public void clear() {
        cache.clear();
    }

    /**
     * Retains only diffs for the provided set of context ids, discarding all others.
     *
     * <p>Used during history truncation to keep the cache bounded.
     *
     * @param currentIds the set of context ids whose diffs should be retained
     */
    public void retainOnly(Set<UUID> currentIds) {
        cache.keySet().retainAll(currentIds);
    }

    @Blocking
    private static boolean isNewFileInGit(ContextFragment fragment, IGitRepo repo) {
        if (!(fragment instanceof ContextFragment.PathFragment)) {
            return false;
        }
        Set<ProjectFile> files = fragment.files().join();
        if (files.isEmpty()) {
            return false;
        }
        return !repo.getTrackedFiles().contains(files.iterator().next());
    }

    /**
     * Compute per-fragment diffs between curr (new/right) and other (old/left) contexts.
     * Triggers async computations and awaits their completion.
     */
    @Blocking
    public static List<Context.DiffEntry> computeDiff(Context ctx, Context other) {
        // Candidates:
        // - Editable fragments
        // - Image fragments (non-text), including pasted images and image files.
        // Exclude external path fragments from editable candidates; only project files should be diffed.
        var editableFragments =
                ctx.getEditableFragments().filter(f -> f.getType() != ContextFragment.FragmentType.EXTERNAL_PATH);
        var imageFragments = ctx.allFragments().filter(f -> !f.isText());

        var candidates = Stream.concat(editableFragments, imageFragments);
        var diffFutures =
                candidates.map(cf -> computeDiffForFragment(ctx, cf, other)).toList();

        return diffFutures.stream()
                .map(CompletableFuture::join)
                // cDFF returns null to mean "no changes"
                .filter(de -> de != null)
                .toList();
    }

    /**
     * Helper method to compute diff for a single fragment against the other context asynchronously.
     * Returns a CompletableFuture that completes when all async dependencies (e.g., ComputedValue)
     * are resolved and the diff is computed.
     * Triggers async computations but does not block on them.
     * Errors are logged and result in a placeholder DiffEntry with empty diff.
     */
    @Blocking
    private static CompletableFuture<Context.DiffEntry> computeDiffForFragment(
            Context curr, ContextFragment thisFragment, Context other) {
        var otherFragment = other.allFragments()
                .filter(thisFragment::hasSameSource)
                .findFirst()
                .orElse(null);

        if (otherFragment == null) {
            // No matching fragment in 'other'
            // - For non-text fragments (e.g., images), don't emit a diff here.
            // - For PathFragments: only show a diff if it's a new, untracked file in Git.
            if (!thisFragment.isText()) {
                return CompletableFuture.completedFuture(null);
            }

            if (!(thisFragment instanceof ContextFragment.PathFragment)) {
                return CompletableFuture.completedFuture(null);
            }

            var repo = curr.getContextManager().getRepo();
            if (!isNewFileInGit(thisFragment, repo)) {
                // Path fragment exists only in 'curr' but is tracked in Git; suppress diff here.
                return CompletableFuture.completedFuture(null);
            }

            // Text fragment newly added: diff against empty, preferring snapshot text
            return extractFragmentContentAsync(thisFragment).thenApply(newContent -> {
                var oldName = "old/" + thisFragment.shortDescription().renderNowOr("");
                var newName = "new/" + thisFragment.shortDescription().renderNowOr("");
                var result = ContentDiffUtils.computeDiffResult("", newContent, oldName, newName);
                if (result.diff().isEmpty()) {
                    return null;
                }
                return new Context.DiffEntry(
                        thisFragment, result.diff(), result.added(), result.deleted(), "", newContent);
            });
        }

        // Image fragments: compare bytes (prefer frozen snapshot bytes)
        assert thisFragment.isText() == otherFragment.isText();
        if (!thisFragment.isText()) {
            var entry = computeImageDiffEntry(thisFragment, otherFragment);
            return CompletableFuture.completedFuture(entry);
        }

        // Extract text content asynchronously for both sides, preferring snapshots
        var oldContentFuture = extractFragmentContentAsync(otherFragment);
        var newContentFuture = extractFragmentContentAsync(thisFragment);

        // Text fragments: compute textual diff
        return oldContentFuture.thenCombine(newContentFuture, (oldContent, newContent) -> {
            int oldLineCount =
                    oldContent.isEmpty() ? 0 : (int) oldContent.lines().count();
            int newLineCount =
                    newContent.isEmpty() ? 0 : (int) newContent.lines().count();
            logger.trace(
                    "computeDiff: fragment='{}' ctxId={} oldLines={} newLines={}",
                    thisFragment.shortDescription().renderNowOr(""),
                    curr.id(),
                    oldLineCount,
                    newLineCount);

            var oldName = "old/" + thisFragment.shortDescription().renderNowOr("");
            var newName = "new/" + thisFragment.shortDescription().renderNowOr("");
            var result = ContentDiffUtils.computeDiffResult(oldContent, newContent, oldName, newName);

            logger.trace(
                    "computeDiff: fragment='{}' added={} deleted={} diffEmpty={}",
                    thisFragment.shortDescription().renderNowOr(""),
                    result.added(),
                    result.deleted(),
                    result.diff().isEmpty());

            if (result.diff().isEmpty()) {
                return null;
            }

            return new Context.DiffEntry(
                    thisFragment, result.diff(), result.added(), result.deleted(), oldContent, newContent);
        });
    }

    /**
     * Extract text content asynchronously for a fragment. For ComputedFragment, chains its future; otherwise immediate.
     */
    private static CompletableFuture<String> extractFragmentContentAsync(ContextFragment fragment) {
        var computedTextFuture = fragment.text().future();
        return computedTextFuture
                .completeOnTimeout(
                        "Timeout loading contents. Please consider reporting a bug",
                        TEXT_FALLBACK_TIMEOUT.toSeconds(),
                        TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    var msg =
                            """
                            Error loading contents. Please consider reporting a bug.

                            Details:
                            Fragment type %s
                            Fragment description: %s
                            Stacktrace: %s
                            """
                                    .formatted(
                                            fragment.getClass().getSimpleName(),
                                            fragment.shortDescription().renderNowOr(fragment.toString()),
                                            ExceptionReporter.formatStackTrace(ex));
                    logger.warn(msg, ex);
                    return msg;
                });
    }

    /**
     * Compute a placeholder diff entry for image fragments when the bytes differ.
     */
    private static @Nullable Context.DiffEntry computeImageDiffEntry(
            ContextFragment thisFragment, ContextFragment otherFragment) {
        // Prefer frozen bytes (snapshot), fall back to computed image bytes
        byte[] oldImageBytes = null;
        var oldImageBytesCv = otherFragment.imageBytes();
        if (oldImageBytesCv != null) {
            oldImageBytes = oldImageBytesCv.join();
        }

        byte[] newImageBytes = null;
        var newImageBytesCv = thisFragment.imageBytes();
        if (newImageBytesCv != null) {
            newImageBytes = newImageBytesCv.join();
        }

        // If both sides are missing bytes, we cannot compare — omit diff.
        if (oldImageBytes == null && newImageBytes == null) {
            return null;
        }

        // If one side has bytes and the other does not, treat as changed.
        if ((oldImageBytes == null) != (newImageBytes == null)) {
            String diff = "[Image changed]";
            return new Context.DiffEntry(thisFragment, diff, 1, 1, "[image]", "[image]");
        }

        boolean imagesEqual = Arrays.equals(oldImageBytes, newImageBytes);
        if (imagesEqual) {
            return null;
        }
        String diff = "[Image changed]";
        return new Context.DiffEntry(thisFragment, diff, 1, 1, "[image]", "[image]");
    }

    /**
     * Compute the set of ProjectFile objects that differ between curr (new/right) and other (old/left).
     */
    @Blocking
    public static Set<ProjectFile> getChangedFiles(Context curr, Context other) {
        var diffs = computeDiff(curr, other);
        return diffs.stream()
                .flatMap(de -> de.fragment().files().join().stream())
                .collect(Collectors.toSet());
    }
}
