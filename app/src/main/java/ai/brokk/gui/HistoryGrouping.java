package ai.brokk.gui;

import ai.brokk.context.Context;
import ai.brokk.util.ComputedValue;
import java.util.*;
import java.util.function.Predicate;

/**
 * Unified grouping model for context history rendering.
 *
 * This file provides:
 * - GroupingBuilder.discoverGroups: a deterministic algorithm that discovers
 *   contiguous groups given a list of Context and a boundary predicate.
 *
 * Rules implemented:
 * 1) Groups are contiguous runs bounded by boundaries (isBoundary.test(ctx) == true).
 *    Boundaries terminate the preceding group but can themselves be the first item of a new group.
 * 2) If a context has a non-null getGroupId(), it belongs to a group identified by that UUID.
 *    Group-by-id groups always form a group, even if the group size is 1, and always show a header.
 * 3) Legacy grouping: when getGroupId() is null, contiguous runs of ungrouped contexts up to a
 *    boundary may be grouped. Only create a legacy group when the run length is >= 2. Otherwise,
 *    return the context as a singleton without a header.
 * 4) Preserve original order; return descriptors covering all contexts.
 *
 * Stable keys:
 * - For GROUP_BY_ID: key = groupId.toString()
 * - For GROUP_BY_ACTION (legacy): key = first child's context id as String
 */
public final class HistoryGrouping {

    private HistoryGrouping() {
        // utility
    }

    public enum GroupType {
        GROUP_BY_ID,
        GROUP_BY_ACTION
    }

    /**
     * Immutable descriptor for a discovered group.
     *
     * - type: whether the group is formed by explicit groupId or legacy action-based grouping.
     * - key: stable identifier for the group.
     *   - GROUP_BY_ID: groupId.toString()
     *   - GROUP_BY_ACTION: first-child context id as String
     * - label: display label for a header row; may be empty for singletons (no header).
     * - children: the contexts in display order.
     * - shouldShowHeader: whether a header/triangle row should be rendered.
     * - isLastGroup: whether this descriptor is the last group in the list.
     */
    public record GroupDescriptor(
            GroupType type,
            String key,
            ComputedValue<String> label,
            List<Context> children,
            boolean shouldShowHeader,
            boolean isLastGroup) {}

    public static final class GroupingBuilder {
        private GroupingBuilder() {}

        /**
         * Discover logical group descriptors from the provided contexts.
         *
         * @param contexts the full list of contexts to group, in display order
         * @param isBoundary boundary predicate; true indicates a boundary context that terminates any group
         * @param groupLookup function to look up the groupId for a given context ID
         * @return ordered list of group descriptors covering all input contexts
         */
        public static List<GroupDescriptor> discoverGroups(
                List<Context> contexts,
                Predicate<Context> isBoundary,
                Set<UUID> resetTargetIds,
                java.util.function.Function<UUID, UUID> groupLookup) {
            if (contexts.isEmpty()) {
                return List.of();
            }

            final int n = contexts.size();
            List<GroupDescriptor> out = new ArrayList<>();

            // 1) Pre-split into boundary-separated segments.
            // A boundary means "there is a cut before this item," so it starts a new segment.
            int segStart = 0;
            for (int i = 1; i < n; i++) {
                if (isBoundary.test(contexts.get(i))) {
                    // emit [segStart, i)
                    emitSegment(contexts, segStart, i, out, isBoundary, resetTargetIds, groupLookup);
                    segStart = i;
                }
            }
            // emit final segment [segStart, n)
            emitSegment(contexts, segStart, n, out, isBoundary, resetTargetIds, groupLookup);

            // 2) Mark last descriptor, if any
            if (!out.isEmpty()) {
                int last = out.size() - 1;
                GroupDescriptor tail = out.get(last);
                out.set(
                        last,
                        new GroupDescriptor(
                                tail.type(), tail.key(), tail.label(), tail.children(), tail.shouldShowHeader(), true));
            }

            return List.copyOf(out);
        }

        /**
         * Emit group descriptors for a boundary-free segment [start, end).
         * Within a segment, produce maximal runs of contiguous items that are not boundaries.
         * A header is shown only when the run length is >= 2 or explicit groupIds are present.
         */
        private static void emitSegment(
                List<Context> contexts,
                int start,
                int end,
                List<GroupDescriptor> out,
                java.util.function.Predicate<Context> isBoundary,
                Set<UUID> resetTargetIds,
                java.util.function.Function<UUID, UUID> groupLookup) {
            int i = start;
            while (i < end) {
                Context ctx = contexts.get(i);
                UUID groupId = groupLookup.apply(ctx.id());

                // If this item is a boundary, it is emitted as a singleton without a header.
                if (isBoundary.test(ctx)) {
                    List<Context> single = List.of(ctx);
                    out.add(new GroupDescriptor(
                            GroupType.GROUP_BY_ACTION,
                            ctx.id().toString(),
                            ComputedValue.completed(""),
                            single,
                            false,
                            false));
                    i = i + 1;
                    continue;
                }

                // Case A: Grouped by explicit ID
                if (groupId != null) {
                    int j = i + 1;
                    while (j < end && groupId.equals(groupLookup.apply(contexts.get(j).id()))) {
                        j++;
                    }
                    List<Context> children = contexts.subList(i, j);
                    var label = computeHeaderLabelFor(contexts, i, j, resetTargetIds);
                    out.add(new GroupDescriptor(
                            GroupType.GROUP_BY_ID, groupId.toString(), label, children, true, false));
                    i = j;
                    continue;
                }

                // Case B: Legacy/Action grouping (runs of contiguous items that are not boundaries and have no groupId)
                int j = i + 1;
                while (j < end && !isBoundary.test(contexts.get(j)) && groupLookup.apply(contexts.get(j).id()) == null) {
                    j++;
                }
                int len = j - i;
                if (len >= 2) {
                    List<Context> children = contexts.subList(i, j);
                    var label = computeHeaderLabelFor(contexts, i, j, resetTargetIds);
                    String key = children.get(0).id().toString();
                    out.add(new GroupDescriptor(GroupType.GROUP_BY_ACTION, key, label, children, true, false));
                } else {
                    // Singleton (no header)
                    List<Context> single = List.of(ctx);
                    out.add(new GroupDescriptor(
                            GroupType.GROUP_BY_ACTION,
                            ctx.id().toString(),
                            ComputedValue.completed(""),
                            single,
                            false,
                            false));
                }
                i = j;
            }
        }

        private static ComputedValue<String> computeHeaderLabelFor(
                List<Context> contexts, int i, int j, Set<UUID> resetTargetIds) {
            int size = j - i;
            assert size > 0 : "%d <= %d".formatted(j, i);

            // If it's a single relevant action, just return that CV directly.
            if (size == 1) {
                return getDescription(contexts, i, resetTargetIds);
            }

            // Map all child descriptions into a single aggregate CV.
            List<ComputedValue<String>> cvs = new ArrayList<>();
            for (int k = i; k < j; k++) {
                cvs.add(getDescription(contexts, k, resetTargetIds));
            }

            // We combine the futures of all child actions.
            var futures =
                    cvs.stream().map(ComputedValue::future).toArray(java.util.concurrent.CompletableFuture[]::new);

            return new ai.brokk.util.ComputedValue<>(
                    "header-aggregate",
                    java.util.concurrent.CompletableFuture.allOf(futures).thenApply(v -> {
                        // All are done now. Re-calculate the label using completed strings.
                        List<String> words = cvs.stream()
                                .map(cv -> safeFirstWord(cv.renderNowOr(Context.SUMMARIZING)))
                                .toList();

                        Map<String, Long> counts = words.stream()
                                .collect(java.util.stream.Collectors.groupingBy(
                                        w -> w, LinkedHashMap::new, java.util.stream.Collectors.counting()));

                        List<String> formatted = counts.entrySet().stream()
                                .map(e -> e.getValue() > 1 ? e.getKey() + " x" + e.getValue() : e.getKey())
                                .toList();

                        if (formatted.size() == 1) return formatted.getFirst();
                        if (formatted.size() == 2) return formatted.get(0) + " + " + formatted.get(1);
                        return formatted.get(0) + " + more";
                    }));
        }

        private static ComputedValue<String> getDescription(
                List<Context> contexts, int index, Set<UUID> resetTargetIds) {
            Context ctx = contexts.get(index);
            if (resetTargetIds.contains(ctx.id())) {
                return ComputedValue.completed("Copy From History");
            }
            Context prev = index > 0 ? contexts.get(index - 1) : null;
            return ctx.getAction(prev);
        }

        private static String safeFirstWord(String text) {
            if (text.isBlank()) {
                return "";
            }
            int idx = text.indexOf(' ');
            return (idx < 0) ? text : text.substring(0, idx);
        }
    }

    /**
     * Build a mapping from Context.id to the visible row index in the given JTable.
     * Visible children map to their own row; children of collapsed groups map to the group's header row.
     * If descriptors are empty, maps only currently visible Context rows (collapsed children cannot be
     * resolved to headers without descriptors).
     */
    public static java.util.Map<java.util.UUID, Integer> buildContextToRowMap(
            java.util.List<GroupDescriptor> descriptors, javax.swing.JTable table) {
        // Index descriptors by UUID key (groupId for id-groups; first-child id for legacy action groups)
        var byKey = new HashMap<java.util.UUID, GroupDescriptor>();
        for (var gd : descriptors) {
            try {
                var keyUuid = java.util.UUID.fromString(gd.key());
                byKey.put(keyUuid, gd);
            } catch (IllegalArgumentException ignored) {
                // skip malformed keys
            }
        }

        var result = new HashMap<java.util.UUID, Integer>();

        var model = table.getModel();
        // First pass: map visible Context rows directly
        for (int row = 0; row < model.getRowCount(); row++) {
            var val = model.getValueAt(row, 2);
            if (val instanceof Context ctx) {
                result.put(ctx.id(), row);
            }
        }

        // Second pass: for collapsed group headers, map each child to the header row
        for (int row = 0; row < model.getRowCount(); row++) {
            var val = model.getValueAt(row, 2);
            if (val instanceof HistoryOutputPanel.GroupRow gr && !gr.expanded()) {
                var gd = byKey.get(gr.key());
                if (gd == null || !gd.shouldShowHeader()) {
                    continue;
                }
                for (var child : gd.children()) {
                    result.putIfAbsent(child.id(), row);
                }
            }
        }

        return result;
    }
}
