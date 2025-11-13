package ai.brokk.context;

import ai.brokk.IContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.util.ContentDiffUtils;
import ai.brokk.util.ImageUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
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
    public void retainOnly(java.util.Set<UUID> currentIds) {
        cache.keySet().retainAll(currentIds);
    }

    /**
     * Compute per-fragment diffs between curr (new/right) and other (old/left) contexts.
     * Triggers async computations and awaits their completion.
     */
    public static List<Context.DiffEntry> computeDiff(Context curr, Context other) {
        try {
            var diffFutures = curr.allFragments()
                    .map(cf -> computeDiffForFragment(curr, cf, other))
                    .toList();

            //noinspection ConstantValue (Objects::nonNull) is highlighted as unnecessary, but this is necessary
            return diffFutures.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception ex) {
            logger.error("Error computing diffs between contexts: {}", ex.getMessage(), ex);
            return List.of();
        }
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
            // New fragment (no match in other) - diff against empty
            return extractFragmentContentAsync(thisFragment, true)
                    .thenApply(newContent -> {
                        var result = ContentDiffUtils.computeDiffResult(
                                "",
                                newContent,
                                "old/" + thisFragment.shortDescription(),
                                "new/" + thisFragment.shortDescription());
                        if (result.diff().isEmpty()) {
                            return null;
                        }
                        return new Context.DiffEntry(
                                thisFragment, result.diff(), result.added(), result.deleted(), "", newContent);
                    })
                    .exceptionally(ex -> {
                        logger.warn(
                                "Error computing diff for new fragment '{}': {}",
                                thisFragment.shortDescription(),
                                ex.getMessage(),
                                ex);
                        return new Context.DiffEntry(
                                thisFragment, "[Error computing diff]", 0, 0, "", "[Failed to extract content]");
                    });
        }

        // Extract content asynchronously for both sides
        var oldContentFuture = extractFragmentContentAsync(otherFragment, false);
        var newContentFuture = extractFragmentContentAsync(thisFragment, true);

        // Image fragments: compare bytes
        if (!thisFragment.isText() || !otherFragment.isText()) {
            return newContentFuture
                    .thenCombine(oldContentFuture, (nc, oc) -> computeImageDiffEntry(thisFragment, otherFragment))
                    .exceptionally(ex -> {
                        logger.warn(
                                "Error computing image diff for fragment '{}': {}",
                                thisFragment.shortDescription(),
                                ex.getMessage(),
                                ex);
                        return new Context.DiffEntry(
                                thisFragment, "[Error computing image diff]", 0, 0, "", "[Failed to extract image]");
                    });
        }

        // Text fragments: compute textual diff
        return oldContentFuture
                .thenCombine(newContentFuture, (oldContent, newContent) -> {
                    int oldLineCount =
                            oldContent.isEmpty() ? 0 : (int) oldContent.lines().count();
                    int newLineCount =
                            newContent.isEmpty() ? 0 : (int) newContent.lines().count();
                    logger.trace(
                            "computeDiff: fragment='{}' ctxId={} oldLines={} newLines={}",
                            thisFragment.shortDescription(),
                            curr.id(),
                            oldLineCount,
                            newLineCount);

                    var result = ContentDiffUtils.computeDiffResult(
                            oldContent,
                            newContent,
                            "old/" + thisFragment.shortDescription(),
                            "new/" + thisFragment.shortDescription());

                    logger.trace(
                            "computeDiff: fragment='{}' added={} deleted={} diffEmpty={}",
                            thisFragment.shortDescription(),
                            result.added(),
                            result.deleted(),
                            result.diff().isEmpty());

                    if (result.diff().isEmpty()) {
                        return null;
                    }

                    return new Context.DiffEntry(
                            thisFragment, result.diff(), result.added(), result.deleted(), oldContent, newContent);
                })
                .exceptionally(ex -> {
                    logger.warn(
                            "Error computing diff for fragment '{}': {}",
                            thisFragment.shortDescription(),
                            ex.getMessage(),
                            ex);
                    return new Context.DiffEntry(
                            thisFragment, "[Error computing diff]", 0, 0, "", "[Failed to extract content]");
                });
    }

    /**
     * Extract text content asynchronously for a fragment. For ComputedFragment, chains its future; otherwise immediate.
     */
    private static CompletableFuture<String> extractFragmentContentAsync(ContextFragment fragment, boolean isNew) {
        try {
            if (fragment instanceof ContextFragment.ComputedFragment cf) {
                var computedTextFuture = cf.computedText().future();
                return computedTextFuture.exceptionally(ex -> {
                    logger.warn(
                            "Error computing text for {} fragment '{}': {}",
                            fragment.getClass().getSimpleName(),
                            fragment.shortDescription(),
                            ex.getMessage(),
                            ex);
                    return "";
                });
            }
            return CompletableFuture.completedFuture(fragment.text());
        } catch (UncheckedIOException e) {
            logger.warn(
                    "IO error reading content for {} fragment '{}' ({}): {}",
                    fragment.getClass().getSimpleName(),
                    fragment.shortDescription(),
                    isNew ? "new" : "old",
                    e.getMessage());
            return CompletableFuture.completedFuture("");
        } catch (java.util.concurrent.CancellationException e) {
            logger.warn(
                    "Computation cancelled for {} fragment '{}': {}",
                    fragment.getClass().getSimpleName(),
                    fragment.shortDescription(),
                    e.getMessage());
            return CompletableFuture.completedFuture("");
        } catch (Exception e) {
            logger.error(
                    "Unexpected error extracting content for {} fragment '{}': {}",
                    fragment.getClass().getSimpleName(),
                    fragment.shortDescription(),
                    e.getMessage(),
                    e);
            return CompletableFuture.completedFuture("");
        }
    }

    /**
     * Extract image bytes from a fragment, handling Computed/Image fragments.
     */
    private static byte @Nullable [] extractImageBytes(ContextFragment fragment) {
        try {
            if (fragment instanceof ContextFragment.ImageFragment imgFrag) {
                var image = imgFrag.image();
                return ImageUtil.imageToBytes(image);
            }
        } catch (java.util.concurrent.CancellationException | IOException e) {
            logger.warn(
                    "Computation cancelled for image fragment '{}'; image will show as changed. Cause: {}",
                    fragment.shortDescription(),
                    e.getMessage());
            return null;
        }
        return null;
    }

    /**
     * Compute a placeholder diff entry for image fragments when the bytes differ.
     */
    private static @Nullable Context.DiffEntry computeImageDiffEntry(
            ContextFragment thisFragment, ContextFragment otherFragment) {
        byte[] oldImageBytes = extractImageBytes(otherFragment);
        byte[] newImageBytes = extractImageBytes(thisFragment);

        if (oldImageBytes == null && newImageBytes == null) {
            return null;
        }
        boolean imagesEqual =
                oldImageBytes != null && newImageBytes != null && Arrays.equals(oldImageBytes, newImageBytes);
        if (imagesEqual) {
            return null;
        }
        String diff = "[Image changed]";
        return new Context.DiffEntry(
                thisFragment,
                diff,
                1,
                1,
                oldImageBytes != null ? "[image]" : "",
                newImageBytes != null ? "[image]" : "");
    }

    /**
     * Compute the set of ProjectFile objects that differ between curr (new/right) and other (old/left).
     */
    public static java.util.Set<ProjectFile> getChangedFiles(Context curr, Context other) {
        var diffs = computeDiff(curr, other);
        return diffs.stream().flatMap(de -> de.fragment().files().stream()).collect(Collectors.toSet());
    }
}
