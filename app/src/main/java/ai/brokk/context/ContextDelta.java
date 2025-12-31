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
        boolean compressedHistory,
        boolean externalChanges,
        boolean sessionReset,
        List<ContextFragments.StringFragment> updatedSpecialFragments) {

    public boolean isEmpty() {
        return addedFragments.isEmpty()
                && removedFragments.isEmpty()
                && addedTasks.isEmpty()
                && !clearedHistory
                && !compressedHistory
                && !externalChanges
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
                .filter(f -> !from.containsWithSameSource(f))
                .toList();

        var removed = from.fragments.stream()
                .filter(f -> !to.containsWithSameSource(f))
                .toList();

        // Task history changes
        var addedTasks = new ArrayList<TaskEntry>();
        boolean compressedHistory = false;
        if (to.taskHistory.size() > from.taskHistory.size()) {
            addedTasks.addAll(to.taskHistory.subList(from.taskHistory.size(), to.taskHistory.size()));
        } else if (to.taskHistory.size() == from.taskHistory.size() && !to.taskHistory.isEmpty()) {
            // Check if any entries were compressed (isCompressed() is on TaskEntry)
            for (int i = 0; i < to.taskHistory.size(); i++) {
                if (to.taskHistory.get(i).isCompressed() && !from.taskHistory.get(i).isCompressed()) {
                    compressedHistory = true;
                    break;
                }
            }
        }

        boolean clearedHistory = from.taskHistory.size() > to.taskHistory.size() && to.taskHistory.isEmpty();

        // Check for content changes in existing fragments
        var updatedSpecials = new ArrayList<ContextFragments.StringFragment>();
        boolean externalChanges = false;
        if (added.isEmpty() && removed.isEmpty() && !to.fragments.equals(from.fragments)) {
            // All fragments have same sources, but contents differ.
            for (int i = 0; i < to.fragments.size(); i++) {
                var fTo = to.fragments.get(i);
                var fFrom = from.fragments.get(i);
                if (!fTo.equals(fFrom)) {
                    if (fTo instanceof ContextFragments.StringFragment sf && sf.specialType().isPresent()) {
                        updatedSpecials.add(sf);
                    } else {
                        externalChanges = true;
                    }
                }
            }
        }

        return new ContextDelta(
                added,
                removed,
                addedTasks,
                clearedHistory,
                compressedHistory,
                externalChanges,
                sessionReset,
                updatedSpecials);
    }

    /**
     * Returns a human-readable description of the changes in this delta.
     */
    public String description() {
        if (sessionReset) {
            return "Reset Session";
        }

        if (isEmpty()) {
            return "(No changes)";
        }

        // 1. Prioritize New Task History (User/AI turn)
        if (!addedTasks.isEmpty()) {
            TaskEntry latest = addedTasks.getLast();
            var log = latest.log();
            if (log != null) {
                return log.shortDescription().join();
            }
            String summary = latest.summary();
            if (summary != null) {
                return summary;
            }
            return "Task Added";
        }

        // 2. Aggregate other changes
        List<String> parts = new ArrayList<>();

        if (compressedHistory) {
            parts.add("Compressed History");
        }

        if (clearedHistory) {
            parts.add("Cleared Conversation");
        }

        if (!addedFragments.isEmpty()) {
            parts.add(buildAction("Added", addedFragments));
        }

        if (!removedFragments.isEmpty()) {
            parts.add(buildAction("Removed", removedFragments));
        }

        for (var sf : updatedSpecialFragments) {
            parts.add("Updated " + sf.specialType().get().description());
        }

        if (parts.isEmpty() && externalChanges) {
            parts.add("Loaded External Changes");
        }

        if (parts.isEmpty()) {
            return "(No changes detected)";
        }

        return String.join("; ", parts);
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
