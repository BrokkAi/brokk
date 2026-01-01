package ai.brokk.context;

import ai.brokk.ContextManager;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.TaskEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import ai.brokk.TaskResult;
import ai.brokk.util.ComputedValue;
import ai.brokk.util.ExecutorServiceUtil;
import org.jetbrains.annotations.Blocking;

import static java.util.Objects.requireNonNull;

/**
 * Represents the delta between two Context states.
 * Unlike DiffService which tracks file content changes, this tracks workspace state changes
 * (fragments added/removed, task history changes).
 */
public record ContextDelta(
        List<ContextFragment> addedFragments,
        List<ContextFragment> removedFragments,
        List<TaskEntry> addedTasks,
        boolean clearedHistory,
        boolean compressedHistory,
        boolean contentsChanged,
        boolean sessionReset,
        List<ContextFragments.StringFragment> updatedSpecialFragments) {

    // leaving these past tense for compatibility with old sessions
    public static final String CLEARED_TASK_HISTORY = "Cleared Task History";
    public static final String DROPPED_ALL_CONTEXT = "Dropped all Context";

    public boolean isEmpty() {
        return addedFragments.isEmpty()
                && removedFragments.isEmpty()
                && addedTasks.isEmpty()
                && !clearedHistory
                && !compressedHistory
                && !contentsChanged
                && !sessionReset
                && updatedSpecialFragments.isEmpty();
    }

    /**
     * Computes the delta between two contexts.
     * Returns what changed from {@code from} to reach {@code to}.
     *
     * @param from the baseline context (typically the previous state)
     * @param to   the target context (typically the current state)
     * @return a ContextDelta describing the changes
     */
    public static ContextDelta between(Context from, Context to) {
        boolean sessionReset = !from.isEmpty() && to.isEmpty();

        var added = to.fragments.stream()
                .filter(f -> from.findWithSameSource(f).isEmpty())
                .toList();

        var removed = from.fragments.stream()
                .filter(f -> to.findWithSameSource(f).isEmpty())
                .toList();

        // Task history changes
        var addedTasks = new ArrayList<TaskEntry>();
        boolean compressedHistory = false;
        if (to.taskHistory.size() > from.taskHistory.size()) {
            addedTasks.addAll(to.taskHistory.subList(from.taskHistory.size(), to.taskHistory.size()));
        }
        // Check for compression in the overlapping portion of history
        int commonSize = Math.min(from.taskHistory.size(), to.taskHistory.size());
        for (int i = 0; i < commonSize; i++) {
            if (to.taskHistory.get(i).isCompressed() && !from.taskHistory.get(i).isCompressed()) {
                compressedHistory = true;
                break;
            }
        }

        boolean clearedHistory = from.taskHistory.size() > to.taskHistory.size() && to.taskHistory.isEmpty();

        // 1. Check for updated special fragments
        var updatedSpecials = new ArrayList<ContextFragments.StringFragment>();
        for (SpecialTextType type : SpecialTextType.values()) {
            var fromSpecial = from.getSpecial(type.description());
            var toSpecial = to.getSpecial(type.description());

            if (fromSpecial.isPresent() && toSpecial.isPresent()) {
                String fromText = fromSpecial.get().text().renderNowOr("");
                String toText = toSpecial.get().text().renderNowOr("");
                if (!fromText.equals(toText)) {
                    updatedSpecials.add(toSpecial.get());
                }
            }
        }

        // 2. Check for content changes in any overlapping non-special fragments
        boolean contentsChanged = to.fragments.stream()
                .filter(toFrag -> !(toFrag instanceof ContextFragments.StringFragment sf && sf.specialType().isPresent()))
                .anyMatch(toFrag -> from.findWithSameSource(toFrag)
                        .map(fromFrag -> !fromFrag.text().renderNowOr("").equals(toFrag.text().renderNowOr("")))
                        .orElse(false));

        return new ContextDelta(
                added,
                removed,
                addedTasks,
                clearedHistory,
                compressedHistory,
                contentsChanged,
                sessionReset,
                updatedSpecials);
    }

    /**
     * Returns a human-readable description of the changes in this delta.
     */
    public ComputedValue<String> description(IContextManager icm) {
        if (sessionReset) {
            return ComputedValue.completed(DROPPED_ALL_CONTEXT);
        }

        if (isEmpty()) {
            return ComputedValue.completed("(No changes)");
        }

        // 1. Prioritize New Task History (User/AI turn)
        if (!addedTasks.isEmpty()) {
            TaskEntry latest = addedTasks.getLast();
            String prefix = (latest.meta() == null || latest.meta().type() == TaskResult.Type.CONTEXT)
                    ? "" : latest.meta().type().displayName() + ": ";
            String taskText;
            if (latest.isCompressed()) {
                taskText = requireNonNull(latest.summary());
            } else {
                taskText = requireNonNull(latest.log()).shortDescription;
            }

            String cacheKey;
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(taskText.getBytes(StandardCharsets.UTF_8));
                cacheKey = "action_" + HexFormat.of().formatHex(hash);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }

            var cache = icm.getProject().getDiskCache();
            var executor = ExecutorServiceUtil.newVirtualThreadExecutor("delta-cache-", 1);
            return new ComputedValue<>(CompletableFuture.supplyAsync(() -> cache.get(cacheKey), executor)
                    .thenApply(cachedOpt -> {
                        if (cachedOpt.isPresent()) {
                            return prefix + cachedOpt.get();
                        }
                        // Truncate task text as a simple summary fallback
                        String summary = taskText.length() > 80 
                                ? taskText.substring(0, 77) + "..." 
                                : taskText;
                        // Remove newlines for single-line display
                        summary = summary.replace('\n', ' ').replace('\r', ' ');
                        cache.put(cacheKey, summary);
                        return prefix + summary;
                    })
                    .whenComplete((r, e) -> executor.shutdown()));
        }

        // 2. Aggregate other changes asynchronously
        var executor = ai.brokk.util.ExecutorServiceUtil.newVirtualThreadExecutor("delta-desc-", 4);

        List<CompletableFuture<String>> futures = new ArrayList<>();

        if (compressedHistory) {
            futures.add(CompletableFuture.completedFuture("Compress History"));
        }

        if (clearedHistory) {
            futures.add(CompletableFuture.completedFuture(CLEARED_TASK_HISTORY));
        }

        if (!addedFragments.isEmpty()) {
            futures.add(buildActionAsync("Add", addedFragments).future());
        }

        if (!removedFragments.isEmpty()) {
            futures.add(buildActionAsync("Remove", removedFragments).future());
        }

        for (var sf : updatedSpecialFragments) {
            String desc = sf.specialType().get().description();
            futures.add(CompletableFuture.completedFuture("Update " + desc));
        }

        if (futures.isEmpty() && contentsChanged) {
            futures.add(CompletableFuture.completedFuture("Load External Changes"));
        }

        if (futures.isEmpty()) {
            executor.shutdown();
            return ComputedValue.completed("(No changes detected)");
        }

        CompletableFuture<String> combined = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApplyAsync(v -> {
                    List<String> results = futures.stream()
                            .map(f -> f.getNow(null))
                            .toList();
                    return String.join("; ", results);
                }, executor)
                .whenComplete((r, e) -> executor.shutdown());

        return new ComputedValue<>(combined);
    }

    private ComputedValue<String> buildActionAsync(String verb, List<ContextFragment> items) {
        int count = items.size();
        List<CompletableFuture<String>> shortDescFutures = items.stream()
                .limit(2)
                .map(f -> f.shortDescription().future())
                .toList();

        CompletableFuture<String> result = CompletableFuture.allOf(shortDescFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    var descriptions = shortDescFutures.stream()
                            .map(f -> f.getNow(null))
                            .toList();

                    if (count == 1) {
                        return verb + " " + descriptions.getFirst();
                    }

                    var message = verb + " " + String.join(", ", descriptions);
                    if (count > 2) {
                        message += ", " + (count - 2) + " more";
                    }
                    return message;
                });

        return new ComputedValue<>(result);
    }
}
