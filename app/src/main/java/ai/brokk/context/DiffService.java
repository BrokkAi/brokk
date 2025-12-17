package ai.brokk.context;

import ai.brokk.ExceptionReporter;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitWorkflow;
import ai.brokk.git.IGitRepo;
import ai.brokk.util.ContentDiffUtils;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
    // Generation token to invalidate/bounce warm-up tasks and cache writes after session switches.
    private final AtomicLong generation = new AtomicLong(0);

    /**
     * A snapshot container for diff cache entries keyed by context id.
     * Values are the same futures used by this DiffService; intended for in-memory reuse across session switches.
     */
    public static final class DiffCache extends ConcurrentHashMap<UUID, CompletableFuture<List<Context.DiffEntry>>> {
        private static final long serialVersionUID = 1L;

        public DiffCache() {
            super();
        }

        public DiffCache(Map<UUID, CompletableFuture<List<Context.DiffEntry>>> src) {
            super(src);
        }
    }

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
     * Returns a shallow snapshot of the internal cache suitable for reuse within the same JVM.
     * Futures are not duplicated; references are carried over.
     */
    public DiffCache snapshot() {
        return new DiffCache(cache);
    }

    /**
     * Seed this service's cache from a previously captured snapshot. Only entries whose ids are present in allowedIds
     * are seeded. Existing entries are not overwritten.
     *
     * @param snapshot a non-null snapshot
     * @param allowedIds the set of context ids valid for the current history
     */
    public void seedFrom(DiffCache snapshot, Set<UUID> allowedIds) {
        if (snapshot == null || snapshot.isEmpty() || allowedIds.isEmpty()) {
            return;
        }
        for (var e : snapshot.entrySet()) {
            var id = e.getKey();
            if (!allowedIds.contains(id)) continue;
            cache.putIfAbsent(id, e.getValue());
        }
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

    public static final int DEFAULT_WARMUP_RECENT = 10;

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
 * Warm up diffs for the most recent contexts only, using a single coordinator task and bounded concurrency.
 * This avoids scheduling one background task per context on the global executor.
 *
 * @param max maximum number of most recent contexts (with a predecessor) to warm up
 */
public void warmUpRecent(int max) {
    if (max <= 0) {
        return;
    }
    var contexts = history.getHistory();
    if (contexts.size() <= 1) {
        return;
    }

    // Collect up to `max` most recent contexts that have a predecessor.
    var recent = new ArrayList<Context>(Math.min(max, contexts.size()));
    for (int i = contexts.size() - 1; i >= 0 && recent.size() < max; i--) {
        var c = contexts.get(i);
        if (history.previousOf(c) != null) {
            recent.add(c);
        }
    }
    if (recent.isEmpty()) {
        return;
    }

    // Capture current generation to guard against staleness.
    final long genAtSchedule = generation.get();

    // Single coordinator task; bounded concurrency inside with a small local executor.
    cm.submitBackgroundTask("Warm up diffs (recent " + recent.size() + ")", () -> {
        int concurrencyHint = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 2));
        var exec = java.util.concurrent.Executors.newFixedThreadPool(concurrencyHint, r -> {
            var t = new Thread(r);
            t.setDaemon(true);
            t.setName("DiffWarmUp-" + t.threadId());
            return t;
        });

        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>(recent.size());
            for (var ctx : recent) {
                var prev = history.previousOf(ctx);
                if (prev == null) {
                    continue;
                }
                var f = CompletableFuture.runAsync(() -> {
                    // Early exit if invalidated before starting this unit of work
                    if (generation.get() != genAtSchedule) {
                        return;
                    }
                    try {
                        var result = computeDiff(ctx, prev);

                        // Skip cache write if this work became stale.
                        if (generation.get() != genAtSchedule) {
                            return;
                        }

                        cache.compute(ctx.id(), (id, existing) -> {
                            if (existing == null) {
                                var cf = new CompletableFuture<List<Context.DiffEntry>>();
                                cf.complete(result);
                                return cf;
                            } else {
                                if (!existing.isDone()) {
                                    existing.complete(result);
                                }
                                return existing;
                            }
                        });
                    } catch (Throwable t) {
                        if (generation.get() != genAtSchedule) {
                            return;
                        }
                        cache.compute(ctx.id(), (id, existing) -> {
                            if (existing == null) {
                                var cf = new CompletableFuture<List<Context.DiffEntry>>();
                                cf.completeExceptionally(t);
                                return cf;
                            } else {
                                if (!existing.isDone()) {
                                    existing.completeExceptionally(t);
                                }
                                return existing;
                            }
                        });
                    }
                }, exec);
                futures.add(f);
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            return null;
        } finally {
            exec.shutdown();
        }
    });
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
     * Invalidates any in-flight warm-up by bumping the generation and clearing cache state.
     * Stale warm-up workers will skip cache writes after this is invoked.
     */
    public void invalidate() {
        generation.incrementAndGet();
        clear();
    }

    /**
     * Alias for invalidate; provided for lifecycle symmetry.
     */
    public void close() {
        invalidate();
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
