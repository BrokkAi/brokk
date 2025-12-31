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
                String fromText = fromSpecial.get().text().join();
                String toText = toSpecial.get().text().join();
                if (!fromText.equals(toText)) {
                    updatedSpecials.add(toSpecial.get());
                }
            }
        }

        // 2. Check for content changes in any overlapping non-special fragments
        boolean contentsChanged = to.fragments.stream()
                .filter(toFrag -> !(toFrag instanceof ContextFragments.StringFragment sf && sf.specialType().isPresent()))
                .anyMatch(toFrag -> from.findWithSameSource(toFrag)
                        .map(fromFrag -> !fromFrag.text().join().equals(toFrag.text().join()))
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
            return CompletableFuture.completedFuture(DROPPED_ALL_CONTEXT);
        }

        if (isEmpty()) {
            return CompletableFuture.completedFuture("(No changes)");
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

            var cm = (ContextManager) icm;
            String cacheKey;
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(taskText.getBytes(StandardCharsets.UTF_8));
                cacheKey = "action_" + HexFormat.of().formatHex(hash);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }

            Optional<String> cached = cm.getProject().getDiskCache().get(cacheKey);
            if (cached.isPresent()) {
                return CompletableFuture.completedFuture(prefix + cached.get());
            }

            return cm.summarizeTaskForConversation(taskText)
                    .thenApply(summary -> {
                        cm.getProject().getDiskCache().put(cacheKey, summary);
                        return prefix + summary;
                    });
        }

        // 2. Aggregate other changes
        List<String> parts = new ArrayList<>();

        if (compressedHistory) {
            parts.add("Compress History");
        }

        if (clearedHistory) {
            parts.add(CLEARED_TASK_HISTORY);
        }

        if (!addedFragments.isEmpty()) {
            parts.add(buildAction("Add", addedFragments));
        }

        if (!removedFragments.isEmpty()) {
            parts.add(buildAction("Remove", removedFragments));
        }

        for (var sf : updatedSpecialFragments) {
            parts.add("Update " + sf.specialType().get().description());
        }

        if (parts.isEmpty() && contentsChanged) {
            parts.add("Load External Changes");
        }

        if (parts.isEmpty()) {
            return CompletableFuture.completedFuture("(No changes detected)");
        }

        return CompletableFuture.completedFuture(String.join("; ", parts));
    }

    private String buildAction(String verb, List<ContextFragment> items) {
        int count = items.size();
        if (count == 1) {
            var shortDesc = items.getFirst().shortDescription().join();
            return verb + " " + shortDesc;
        }

        // Show up to 2 fragments, then indicate count
        var descriptions =
                items.stream().limit(2).map(f -> f.shortDescription().join()).toList();

        var message = verb + " " + String.join(", ", descriptions);
        if (count > 2) {
            message += ", " + (count - 2) + " more";
        }
        return message;
    }
}
