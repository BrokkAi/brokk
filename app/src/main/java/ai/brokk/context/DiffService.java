package ai.brokk.context;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.ExceptionReporter;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.concurrent.ComputedValue;
import ai.brokk.concurrent.LoggingFuture;
import ai.brokk.git.CommitInfo;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitRepoData.FileDiff;
import ai.brokk.git.GitWorkflow;
import ai.brokk.git.IGitRepo;
import ai.brokk.project.IProject;
import ai.brokk.util.ContentDiffUtils;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Computes and caches diffs between consecutive history entries using a live-context, non-blocking async model.
 * This is *only* suitable for displaying diffs of files changed on disk! If you want instead to answer the question of
 * "did *anything* change between these two Contexts" then you should use ContextDelta instead.
 *
 * <p>Uses a global bounded cache of (prev, curr) context pairs to avoid redundant computations across sessions.
 * This service materializes computed values asynchronously as needed via
 * {@link ComputedValue#await(Duration)}.
 */
public final class DiffService {
    private static final Logger logger = LogManager.getLogger(DiffService.class);

    private static final Duration TEXT_FALLBACK_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_CACHE_SIZE = 1000;

    /** Identity-based pair for caching diffs between two specific context instances. */
    private record ContextPair(@Nullable Context prev, Context curr) {}

    private final AsyncCache<ContextPair, List<FragmentDiff>> cache =
            Caffeine.newBuilder().maximumSize(MAX_CACHE_SIZE).buildAsync();

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
    public Optional<List<FragmentDiff>> peek(Context curr) {
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
    public CompletableFuture<List<FragmentDiff>> diff(Context curr) {
        var prev = history.previousOf(curr);
        var key = new ContextPair(prev, curr);

        return cache.get(key, (k, executor) -> {
            if (k.prev() == null) {
                return CompletableFuture.completedFuture(List.of());
            }
            var revision = history.getGitState(k.prev().id())
                    .map(ContextHistory.GitState::commitHash)
                    .orElse("HEAD");
            return LoggingFuture.supplyAsync(
                    () -> diff(k.curr(), castNonNull(k.prev()), revision), cm.getBackgroundTasks());
        });
    }

    /**
     * Clears all cached diff entries.
     */
    void clear() {
        cache.synchronous().invalidateAll();
        cache.synchronous().cleanUp();
    }

    @Blocking
    private static boolean isNewFragmentInteresting(ContextFragment fragment, IProject project, String revision) {
        var interestingFiles =
                project.filterExcludedFiles(fragment.sourceFiles().join());
        if (interestingFiles.isEmpty()) {
            return false;
        }

        return fragment.sourceFiles().join().stream().anyMatch(file -> {
            try {
                // If getFileContent returns an empty string for the revision, the file is not yet committed.
                return project.getRepo().getFileContent(revision, file).isEmpty();
            } catch (Exception e) {
                // If an error occurs (e.g. GitAPIException), we treat it as not committed.
                logger.warn("Failed to get content from {} for file {}: {}", revision, file, e.getMessage());
                return true;
            }
        });
    }

    /**
     * Compute per-fragment diffs between curr (new/right) and other (old/left) contexts.
     * Triggers async computations and awaits their completion.
     */
    @Blocking
    public static List<FragmentDiff> diff(Context ctx, Context other, String revision) {
        var candidates = ctx.allFragments().filter(cf -> isDiffable(cf));
        var diffFutures = candidates
                .map(cf -> computeDiffForFragment(ctx, cf, other, revision))
                .toList();

        return diffFutures.stream()
                .map(CompletableFuture::join)
                // cDFF returns null to mean "no changes"
                .filter(de -> de != null)
                .distinct()
                .toList();
    }

    private static boolean isDiffable(ContextFragment fragment) {
        return fragment.getType().isEditable() || !fragment.isText();
    }

    /**
     * Helper method to compute diff for a single fragment against the other context asynchronously.
     * Returns a CompletableFuture that completes when all async dependencies (e.g., ComputedValue)
     * are resolved and the diff is computed.
     * Triggers async computations but does not block on them.
     * Errors are logged and result in a placeholder DiffEntry with empty diff.
     *
     * The DiffEntry returned is Nullable!
     */
    @Blocking
    private static CompletableFuture<FragmentDiff> computeDiffForFragment(
            Context curr, ContextFragment thisFragment, Context other, String revision) {
        var otherFragment = other.allFragments()
                .filter(thisFragment::hasSameSource)
                .findFirst()
                .orElse(null);

        // other==null will result in showing all of this as "new";
        // suppress for non-text fragments and fragments whose files exist in Git
        if (otherFragment == null) {
            if (!thisFragment.isText()) {
                // Non-text new fragments are not diffed here.
                return CompletableFuture.completedFuture(null);
            }

            var project = curr.getContextManager().getProject();
            if (!isNewFragmentInteresting(thisFragment, project, revision)) {
                return CompletableFuture.completedFuture(null);
            }
        }

        // Delegate to the general-purpose computeDiff helper which handles text vs image parity,
        // content extraction, and diff computation.
        return diff(otherFragment, thisFragment).exceptionally(ex -> {
            var desc = thisFragment.shortDescription().join();
            logger.warn("Error computing diff for fragment '{}'", desc, ex);
            return null;
        });
    }

    @Blocking
    public static CompletableFuture<FragmentDiff> diff(
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
                var oldName = newFragment.shortDescription().join();
                var newName = newFragment.shortDescription().join();
                var result = ContentDiffUtils.computeDiffResult("", newContent, oldName, newName);
                if (result.diff().isEmpty()) {
                    return null;
                }
                return new FragmentDiff(newFragment, result.diff(), result.added(), result.deleted(), "", newContent);
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
                    newFragment.shortDescription().join(),
                    oldLineCount,
                    newLineCount);

            var oldName = newFragment.shortDescription().join();
            var newName = newFragment.shortDescription().join();
            var result = ContentDiffUtils.computeDiffResult(oldContent, newContent, oldName, newName);

            logger.trace(
                    "computeDiff: fragment='{}' added={} deleted={} diffEmpty={}",
                    newFragment.shortDescription().join(),
                    result.added(),
                    result.deleted(),
                    result.diff().isEmpty());

            if (result.diff().isEmpty()) {
                return null;
            }

            return new FragmentDiff(
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
                                            fragment.shortDescription().join(),
                                            ExceptionReporter.formatStackTrace(ex));
                    logger.warn(msg, ex);
                    return msg;
                });
    }

    /**
     * Compute a placeholder diff entry for image fragments when the bytes differ.
     */
    private static @Nullable DiffService.FragmentDiff computeImageDiffEntry(
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
            return new FragmentDiff(thisFragment, diff, 1, 1, "[image]", "[image]");
        }

        boolean imagesEqual = Arrays.equals(oldImageBytes, newImageBytes);
        if (imagesEqual) {
            return null;
        }
        String diff = "[Image changed]";
        return new FragmentDiff(thisFragment, diff, 1, 1, "[image]", "[image]");
    }

    @Blocking
    public static CumulativeChanges cumulativeDiff(
            IGitRepo repo, String leftRef, String rightRef, List<CommitInfo> commits) {
        if (!(repo instanceof GitRepo gitRepo)) {
            return new CumulativeChanges(0, 0, 0, List.of(), commits);
        }

        List<FileDiff> fileDiffs;
        try {
            fileDiffs = gitRepo.data().getFileDiffs(leftRef, rightRef);
        } catch (GitAPIException e) {
            logger.error("Failed to compute cumulative diff: {}", e.getMessage());
            return new CumulativeChanges(0, 0, 0, List.of(), commits);
        }

        int totalAdded = 0;
        int totalDeleted = 0;
        for (var fd : fileDiffs) {
            if (fd.isBinary()) {
                // For binary files, we just treat it as a 1-line change if they differ
                if (!fd.oldText().equals(fd.newText())) {
                    totalAdded++;
                    totalDeleted++;
                }
                continue;
            }
            var res = ContentDiffUtils.computeDiffResult(fd.oldText(), fd.newText(), "old", "new");
            totalAdded += res.added();
            totalDeleted += res.deleted();
        }

        return new CumulativeChanges(fileDiffs.size(), totalAdded, totalDeleted, fileDiffs, commits);
    }

    @Blocking
    public static String cumulativeDiff(Context from, Context to) {
        var projectPathFiles = to.allFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.PROJECT_PATH)
                .flatMap(f -> f.sourceFiles().join().stream())
                .collect(Collectors.toSet());

        var candidates = to.allFragments().filter(f -> isDiffable(f)).filter(f -> {
            // Deduplicate: skip Code/Usage fragments if their file is already in a ProjectPathFragment
            if (f instanceof ContextFragments.CodeFragment || f instanceof ContextFragments.UsageFragment) {
                return f.sourceFiles().join().stream().noneMatch(projectPathFiles::contains);
            }
            return true;
        });

        return candidates
                .map(newFrag -> {
                    var oldFrag = from.allFragments()
                            .filter(newFrag::hasSameSource)
                            .findFirst()
                            .orElse(null);
                    return diff(oldFrag, newFrag).join();
                })
                .filter(Objects::nonNull)
                .map(FragmentDiff::diff)
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * Pre-computes titles for FileDiffs off-EDT to avoid blocking calls on the UI thread.
     * Deduplicates by title and returns a stable-sorted list.
     *
     * @param res the cumulative changes containing per-file diff entries
     * @return list of (title, FileDiff) pairs sorted by title
     */
    @Blocking
    public static List<Map.Entry<String, FileDiff>> preparePerFileSummaries(CumulativeChanges res) {
        var list =
                new ArrayList<Map.Entry<String, FileDiff>>(res.perFileChanges().size());
        var seen = new HashSet<String>();
        for (var fd : res.perFileChanges()) {
            var file = fd.newFile() != null ? fd.newFile() : fd.oldFile();
            if (file == null) continue;
            String title = file.getRelPath().toString();
            if (!seen.add(title)) {
                logger.warn("Duplicate cumulative change title '{}' detected; skipping extra entry.", title);
                continue;
            }
            list.add(Map.entry(title, fd));
        }
        list.sort(Map.Entry.comparingByKey());
        return list;
    }

    /** Cumulative changes summary across multiple files. */
    public record CumulativeChanges(
            int filesChanged,
            int totalAdded,
            int totalDeleted,
            List<FileDiff> perFileChanges,
            List<CommitInfo> commits,
            // null when we don't have a GitRepo
            @Nullable GitWorkflow.PushPullState pushPullState) {

        /** Convenience constructor without pushPullState. */
        public CumulativeChanges(
                int filesChanged,
                int totalAdded,
                int totalDeleted,
                List<FileDiff> perFileChanges,
                List<CommitInfo> commits) {
            this(filesChanged, totalAdded, totalDeleted, perFileChanges, commits, null);
        }

        @NotNull
        public String toDiff() {
            return perFileChanges().stream()
                    .map(fd -> {
                        String oldPath = fd.oldFile() == null ? "/dev/null" : "a/" + fd.oldFile().toString();
                        String newPath = fd.newFile() == null ? "/dev/null" : "b/" + fd.newFile().toString();

                        if (fd.isBinary()) {
                            return "Binary files %s and %s differ".formatted(oldPath, newPath);
                        }
                        return ContentDiffUtils.computeDiffResult(
                                        fd.oldText(),
                                        fd.newText(),
                                        fd.oldFile() == null ? null : fd.oldFile().toString(),
                                        fd.newFile() == null ? null : fd.newFile().toString())
                                .diff();
                    })
                    .collect(Collectors.joining("\n\n"));
        }

        @NotNull
        public String toReviewDiff(IAnalyzer analyzer) {
            return perFileChanges().stream()
                    .map(fd -> {
                        String oldName =
                                fd.oldFile() == null ? null : fd.oldFile().toString();
                        String newName =
                                fd.newFile() == null ? null : fd.newFile().toString();
                        return ContentDiffUtils.computeReviewDiffResult(
                                        analyzer, fd.newFile(), fd.oldText(), fd.newText(), oldName, newName)
                                .diff();
                    })
                    .collect(Collectors.joining("\n\n"));
        }
    }

    /**
     * Per-fragment diff entry between two contexts.
     */
    public record FragmentDiff(
            ContextFragment fragment, String diff, int linesAdded, int linesDeleted, String oldText, String newText) {
        @Blocking
        public String title() {
            var files = fragment.sourceFiles().join();
            if (!files.isEmpty()) {
                var pf = files.iterator().next();
                return pf.getRelPath().toString();
            }
            return fragment.shortDescription().join();
        }
    }
}
