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
    public static ContextDelta between(Context from, Context to) {
        var added = to.fragments.stream()
                .filter(from::containsWithSameSource)
                .toList();

        var removed = from.fragments.stream()
                .filter(to::containsWithSameSource)
                .toList();

        // Task history changes
        var addedTasks = new ArrayList<TaskEntry>();
        if (to.taskHistory.size() > from.taskHistory.size()) {
            // New tasks were added
            addedTasks.addAll(to.taskHistory.subList(from.taskHistory.size(), to.taskHistory.size()));
        }

        boolean clearedHistory = from.taskHistory.size() > to.taskHistory.size() && to.taskHistory.isEmpty();

        return new ContextDelta(added, removed, addedTasks, clearedHistory, to.getDescriptionOverride());
    }
}
