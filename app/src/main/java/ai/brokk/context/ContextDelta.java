package ai.brokk.context;

import ai.brokk.TaskEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Represents the delta between two Context states.
 * Unlike DiffService which tracks file content changes, this tracks workspace state changes
 * (fragments added/removed, task history changes).
 *
 * @param addedFragments fragments present in 'to' but not in 'from'
 * @param removedFragments fragments present in 'from' but not in 'to'
 * @param addedTasks tasks present in 'to' history that weren't in 'from'
 * @param clearedHistory true if 'to' history is empty while 'from' was not
 * @param descriptionOverride optional description for this transition
 */
public record ContextDelta(
        List<ContextFragment> addedFragments,
        List<ContextFragment> removedFragments,
        List<TaskEntry> addedTasks,
        boolean clearedHistory,
        @Nullable String descriptionOverride) {

    public boolean isEmpty() {
        return addedFragments.isEmpty()
                && removedFragments.isEmpty()
                && addedTasks.isEmpty()
                && !clearedHistory
                && descriptionOverride == null;
    }

    /**
     * Computes the delta between two contexts.
     * Returns what changed from {@code from} to reach {@code to}.
     *
     * @param from the baseline context (typically the previous state)
     * @param to   the target context (typically the current state)
     * @return a ContextDelta describing the changes
     */
    public static ContextDelta between(@Nullable Context from, Context to) {
        if (from == null) {
            return new ContextDelta(to.fragments, List.of(), to.taskHistory, false, "");
        }

        var previousFragments = Set.copyOf(from.fragments);
        var currentFragments = Set.copyOf(to.fragments);

        var added = to.fragments.stream()
                .filter(f -> !previousFragments.contains(f))
                .toList();

        var removed = from.fragments.stream()
                .filter(f -> !currentFragments.contains(f))
                .toList();

        // Task history changes
        var addedTasks = new ArrayList<TaskEntry>();
        if (to.taskHistory.size() > from.taskHistory.size()) {
            // New tasks were added
            addedTasks.addAll(to.taskHistory.subList(from.taskHistory.size(), to.taskHistory.size()));
        }

        boolean clearedHistory = from.taskHistory.size() > to.taskHistory.size() && to.taskHistory.isEmpty();

        return new ContextDelta(added, removed, addedTasks, clearedHistory, "");
    }

    /**
     * Returns a human-readable description of the changes in this delta.
     */
    public String description() {
        if (descriptionOverride != null) {
            return descriptionOverride;
        }

        if (isEmpty()) {
            return "(No changes)";
        }

        // 1. Prioritize Task History changes
        if (!addedTasks.isEmpty()) {
            TaskEntry latest = addedTasks.getLast();
            String summary = latest.summary();
            if (summary != null && !summary.isBlank()) {
                return summary;
            }
            var log = latest.log();
            if (log != null) {
                return log.shortDescription().join();
            }
        }

        // 2. Fragment delta logic
        if (!addedFragments.isEmpty()) {
            return buildAction("Added", addedFragments);
        }

        if (!removedFragments.isEmpty()) {
            if (addedFragments.isEmpty() && addedTasks.isEmpty() && !clearedHistory && removedFragments.size() > 0 && isAllFragmentsRemoved()) {
                return "Dropped all Context";
            }
            return buildAction("Removed", removedFragments);
        }

        if (clearedHistory) {
            return "Cleared Task History";
        }

        return "(No changes detected)";
    }

    private boolean isAllFragmentsRemoved() {
        // This is a bit of a hack since we don't have the final size here,
        // but if the delta says we removed items and didn't add any, and it matches 
        // a "clear" intent. 
        // In Context.getDescription it checked if this.fragments.isEmpty().
        return false; 
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
