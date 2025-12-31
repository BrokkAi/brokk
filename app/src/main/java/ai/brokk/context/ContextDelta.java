package ai.brokk.context;

import ai.brokk.TaskEntry;
import java.util.ArrayList;
import java.util.List;

import ai.brokk.TaskResult;
import org.jetbrains.annotations.Blocking;

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
    @Blocking
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
        boolean contentsChanged = false;

        // 1. Check for updated special fragments
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

        // 2. Check for content changes in any overlapping fragments
        for (ContextFragment toFrag : to.fragments) {
            if (!(toFrag instanceof ContextFragments.StringFragment sf) || sf.specialType().isPresent()) {
                continue;
            }

            var fromFragOpt = from.findWithSameSource(toFrag);
            if (fromFragOpt.isPresent()) {
                String fromText = fromFragOpt.get().text().join();
                String toText = toFrag.text().join();
                if (!fromText.equals(toText)) {
                    contentsChanged = true;
                    break;
                }
            }
        }

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
    public String description() {
        if (sessionReset) {
            return DROPPED_ALL_CONTEXT;
        }

        if (isEmpty()) {
            return "(No changes)";
        }

        // 1. Prioritize New Task History (User/AI turn)
        if (!addedTasks.isEmpty()) {
            TaskEntry latest = addedTasks.getLast();

            var log = latest.log();
            if (log != null) {
                var typeText = (latest.meta() == null || latest.meta().type() == TaskResult.Type.CONTEXT) ? "" : latest.meta().type().displayName();
                return (typeText.isBlank() ? "" : typeText + ": ") + log.shortDescription().join();
            }

            String summary = latest.summary();
            if (summary != null) {
                return summary;
            }

            // shouldn't happen
            return "TaskEntry with neither log nor summary, report a bug";
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
