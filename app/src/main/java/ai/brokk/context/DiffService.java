package ai.brokk.context;

import ai.brokk.ExceptionReporter;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitWorkflow;
import ai.brokk.git.IGitRepo;
import ai.brokk.util.ContentDiffUtils;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
 * <p>Uses a global bounded cache of (prev, curr) context pairs to avoid redundant computations across sessions.
 * This service materializes computed values asynchronously as needed via
 * {@link ai.brokk.util.ComputedValue#await(java.time.Duration)}.
 */
public final class DiffService {
    private static final Logger logger = LogManager.getLogger(DiffService.class);

    private static final Duration TEXT_FALLBACK_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_CACHE_SIZE = 1000;

    /** Identity-based pair for caching diffs between two specific context instances. */
    private record ContextPair(@Nullable Context prev, Context curr) {
        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (!(o instanceof ContextPair(Context prev1, Context curr1))) return false;
            return prev == prev1 && curr == curr1;
        }

        @Override
        public int hashCode() {
            return Objects.hash(System.identityHashCode(prev), System.identityHashCode(curr));
        }
    }

    private static final Cache<ContextPair, CompletableFuture<List<Context.DiffEntry>>> cache =
            Caffeine.newBuilder().maximumSize(MAX_CACHE_SIZE).build();

    private final ContextHistory history;
    private final IContextManager cm;

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
        var prev = history.previousOf(curr);
        var cf = cache.getIfPresent(new ContextPair(prev, curr));
        if (cf != null && cf.isDone()) {
            //noinspection DataFlowIssue (return of `getNow` is not detected as nullable)
            return Optional.ofNullable(cf.getNow(null));
        }
        return Optional.empty();
    }

    /**
     * Computes or retrieves cached diff between this context and its predecessor.
     * Submits background computation via the context manager's background executor.
     *
     * @param curr the current (new) context to compute diffs for
     * @return CompletableFuture that will contain the list of diff entries
     */
    public CompletableFuture<List<Context.DiffEntry>> diff(Context curr) {
        var prev = history.previousOf(curr);
        var key = new ContextPair(prev, curr);

        var existing = cache.getIfPresent(key);
        if (existing != null) {
            return existing;
        }

        var future = new CompletableFuture<List<Context.DiffEntry>>();
        // Caffeine's Cache doesn't have putIfAbsent that returns existing, so we use manual management for simplicity.
        // Race is fine here as computeDiff is idempotent.
        cache.put(key, future);

        Executor executor;
        try {
            executor = cm.getBackgroundTasks();
        } catch (UnsupportedOperationException | NullPointerException e) {
            // Fallback for tests or contexts where no background executor is available
            executor = CompletableFuture::runAsync;
        }

        executor.execute(() -> {
            try {
                var result = (prev == null) ? List.<Context.DiffEntry>of() : computeDiff(curr, prev);
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
                logger.warn("Error computing diffs for context {}", curr.id(), t);
            }
        });

        return future;
    }

    /**
     * Clears all cached diff entries.
     */
    public void clear() {
        cache.invalidateAll();
    }

    /**
     * Invalidates cached results.
     */
    public void invalidate() {
        clear();
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
                .distinct()
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

        // If this fragment is new-only and is a PathFragment that is tracked by Git, suppress the diff.
        if (otherFragment == null) {
            if (!thisFragment.isText()) {
                // Non-text new fragments are not diffed here.
                return CompletableFuture.completedFuture(null);
            }

            if (thisFragment instanceof ContextFragment.PathFragment) {
                var repo = curr.getContextManager().getRepo();
                if (!isNewFileInGit(thisFragment, repo)) {
                    // Path fragment exists only in 'curr' but is tracked in Git; suppress diff here.
                    return CompletableFuture.completedFuture(null);
                }
            }
        }

        // Delegate to the general-purpose computeDiff helper which handles text vs image parity,
        // content extraction, and diff computation.
        return computeDiff(otherFragment, thisFragment).exceptionally(ex -> {
            var desc = thisFragment.shortDescription().renderNowOr(thisFragment.toString());
            logger.warn("Error computing diff for fragment '{}'", desc, ex);
            return null;
        });
    }

    @Blocking
    public static CompletableFuture<Context.DiffEntry> computeDiff(
            @Nullable ContextFragment oldFragment, ContextFragment newFragment) {
        // If fragments don't share the same source, we can't sensibly diff them here.
        if (oldFragment != null && !newFragment.hasSameSource(oldFragment)) {
            return CompletableFuture.completedFuture(null);
        }

        // New-only fragment (old == null)
        if (oldFragment == null) {
            if (!newFragment.isText()) {
                // Non-text new fragments are not diffed here.
                return CompletableFuture.completedFuture(null);
            }
            return extractFragmentContentAsync(newFragment).thenApply(newContent -> {
                var oldName = "old/" + newFragment.shortDescription().renderNowOr("");
                var newName = "new/" + newFragment.shortDescription().renderNowOr("");
                var result = ContentDiffUtils.computeDiffResult("", newContent, oldName, newName);
                if (result.diff().isEmpty()) {
                    return null;
                }
                return new Context.DiffEntry(
                        newFragment, result.diff(), result.added(), result.deleted(), "", newContent);
            });
        }

        // Both fragments present: ensure text/image parity
        assert oldFragment.isText() == newFragment.isText();
        if (!newFragment.isText()) {
            var entry = computeImageDiffEntry(newFragment, oldFragment);
            return CompletableFuture.completedFuture(entry);
        }

        // Text fragments: extract contents asynchronously and compute diff
        var oldContentFuture = extractFragmentContentAsync(oldFragment);
        var newContentFuture = extractFragmentContentAsync(newFragment);

        return oldContentFuture.thenCombine(newContentFuture, (oldContent, newContent) -> {
            int oldLineCount =
                    oldContent.isEmpty() ? 0 : (int) oldContent.lines().count();
            int newLineCount =
                    newContent.isEmpty() ? 0 : (int) newContent.lines().count();
            logger.trace(
                    "computeDiff: fragment='{}' oldLines={} newLines={}",
                    newFragment.shortDescription().renderNowOr(""),
                    oldLineCount,
                    newLineCount);

            var oldName = "old/" + newFragment.shortDescription().renderNowOr("");
            var newName = "new/" + newFragment.shortDescription().renderNowOr("");
            var result = ContentDiffUtils.computeDiffResult(oldContent, newContent, oldName, newName);

            logger.trace(
                    "computeDiff: fragment='{}' added={} deleted={} diffEmpty={}",
                    newFragment.shortDescription().renderNowOr(""),
                    result.added(),
                    result.deleted(),
                    result.diff().isEmpty());

            if (result.diff().isEmpty()) {
                return null;
            }

            return new Context.DiffEntry(
                    newFragment, result.diff(), result.added(), result.deleted(), oldContent, newContent);
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

    /**
     * Summarizes cumulative changes between two Git references for a given set of files.
     * Handles git IO errors gracefully by skipping problematic files rather than failing the entire operation.
     *
     * @param repo the Git repository
     * @param leftRef the baseline commit/branch reference (left/old side)
     * @param rightRef the target commit/branch reference (right/new side)
     * @param files the set of modified files to diff
     * @return CumulativeChanges with per-file diffs and aggregated statistics
     */
    @Blocking
    public static CumulativeChanges summarizeDiff(
            IGitRepo repo, String leftRef, String rightRef, Set<IGitRepo.ModifiedFile> files) {
        if (!(repo instanceof ai.brokk.git.GitRepo gitRepo)) {
            return new CumulativeChanges(0, 0, 0, List.of());
        }

        if (files.isEmpty()) {
            return new CumulativeChanges(0, 0, 0, List.of());
        }

        List<Context.DiffEntry> perFileChanges = new ArrayList<>();
        int totalAdded = 0;
        int totalDeleted = 0;

        for (var modFile : files) {
            var file = modFile.file();

            // Compute left content based on left reference
            String leftContent = "";
            if (!leftRef.isBlank()) {
                if ("WORKING".equals(leftRef)) {
                    leftContent = file.read().orElse("");
                } else {
                    try {
                        var leftFrag = ContextFragment.GitFileFragment.fromCommit(file, leftRef, gitRepo);
                        leftContent = leftFrag.text().join();
                    } catch (RuntimeException e) {
                        // File doesn't exist at leftRef (new file) - treat as empty baseline
                        logger.debug("File {} not found at {}, treating as new file", file, leftRef);
                        leftContent = "";
                    }
                }
            }

            // Compute right content based on right reference
            String rightContent = "";
            if (!rightRef.isBlank()) {
                if ("WORKING".equals(rightRef)) {
                    rightContent = file.read().orElse("");
                } else {
                    try {
                        var rightFragTmp = ContextFragment.GitFileFragment.fromCommit(file, rightRef, gitRepo);
                        rightContent = rightFragTmp.text().join();
                    } catch (RuntimeException e) {
                        // File doesn't exist at rightRef (deleted file) - treat as empty right side
                        logger.debug("File {} not found at {}, treating as deleted file", file, rightRef);
                        rightContent = "";
                    }
                }
            }

            // Compute line counts
            var diffRes = ContentDiffUtils.computeDiffResult(leftContent, rightContent, "old", "new");
            int added = diffRes.added();
            int deleted = diffRes.deleted();

            // Skip if no changes
            if (added == 0 && deleted == 0) {
                continue;
            }

            totalAdded += added;
            totalDeleted += deleted;

            // Build DiffEntry using the right-side fragment as representative
            ContextFragment.GitFileFragment rightFragForEntry;
            if ("WORKING".equals(rightRef)) {
                rightFragForEntry = new ContextFragment.GitFileFragment(file, "WORKING", rightContent);
            } else {
                try {
                    rightFragForEntry = ContextFragment.GitFileFragment.fromCommit(file, rightRef, gitRepo);
                } catch (RuntimeException e) {
                    // File doesn't exist at rightRef (e.g., deleted) - synthesize with current computed rightContent
                    rightFragForEntry = new ContextFragment.GitFileFragment(file, rightRef, rightContent);
                }
            }

            var de = new Context.DiffEntry(rightFragForEntry, "", added, deleted, leftContent, rightContent);
            perFileChanges.add(de);
        }

        return new CumulativeChanges(perFileChanges.size(), totalAdded, totalDeleted, perFileChanges);
    }

    /**
     * Pre-computes titles for DiffEntries off-EDT to avoid blocking calls on the UI thread.
     * Deduplicates by title and returns a stable-sorted list.
     *
     * @param res the cumulative changes containing per-file diff entries
     * @return list of (title, DiffEntry) pairs sorted by title
     */
    @Blocking
    public static List<Map.Entry<String, Context.DiffEntry>> preparePerFileSummaries(CumulativeChanges res) {
        var list = new ArrayList<Map.Entry<String, Context.DiffEntry>>(
                res.perFileChanges().size());
        var seen = new HashSet<String>();
        for (var de : res.perFileChanges()) {
            String title = de.title();
            if (!seen.add(title)) {
                logger.warn("Duplicate cumulative change title '{}' detected; skipping extra entry.", title);
                continue;
            }
            list.add(Map.entry(title, de));
        }
        list.sort(Comparator.comparing(Map.Entry::getKey));
        return list;
    }

    /** Cumulative changes summary across multiple files. */
    public record CumulativeChanges(
            int filesChanged,
            int totalAdded,
            int totalDeleted,
            List<Context.DiffEntry> perFileChanges,
            @Nullable GitWorkflow.PushPullState pushPullState) {

        /** Convenience constructor without pushPullState. */
        public CumulativeChanges(
                int filesChanged, int totalAdded, int totalDeleted, List<Context.DiffEntry> perFileChanges) {
            this(filesChanged, totalAdded, totalDeleted, perFileChanges, null);
        }
    }
}
