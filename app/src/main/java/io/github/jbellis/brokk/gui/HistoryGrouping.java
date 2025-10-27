package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.context.Context;
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
            String label,
            List<Context> children,
            boolean shouldShowHeader,
            boolean isLastGroup) {

        public GroupDescriptor {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(children, "children");
        }
    }

    public static final class GroupingBuilder {
        private GroupingBuilder() {}

        /**
         * Discover logical group descriptors from the provided contexts.
         *
         * @param contexts the full list of contexts to group, in display order
         * @param isBoundary boundary predicate; true indicates a boundary context that terminates any group
         * @return ordered list of group descriptors covering all input contexts
         */
        public static List<GroupDescriptor> discoverGroups(List<Context> contexts, Predicate<Context> isBoundary) {
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
                    emitSegment(contexts, segStart, i, out, isBoundary);
                    segStart = i;
                }
            }
            // emit final segment [segStart, n)
            emitSegment(contexts, segStart, n, out, isBoundary);

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
         * Within a segment, produce maximal runs of:
         * - same non-null groupId (GROUP_BY_ID, header always shown)
         * - contiguous null groupId (legacy), header shown only when length >= 2
         */
        private static void emitSegment(
                List<Context> contexts,
                int start,
                int end,
                List<GroupDescriptor> out,
                java.util.function.Predicate<Context> isBoundary) {
            int i = start;
            while (i < end) {
                Context ctx = contexts.get(i);
                UUID groupId = ctx.getGroupId();

                if (groupId != null) {
                    int j = i + 1;
                    while (j < end && groupId.equals(contexts.get(j).getGroupId())) {
                        j++;
                    }
                    List<Context> children = Collections.unmodifiableList(new ArrayList<>(contexts.subList(i, j)));
                    // Header always shown for id-groups, including size 1
                    String preferredLabel = children.stream()
                            .map(Context::getGroupLabel)
                            .filter(s -> s != null && !s.isBlank())
                            .findFirst()
                            .orElse(null);

                    out.add(new GroupDescriptor(
                            GroupType.GROUP_BY_ID, groupId.toString(), preferredLabel, children, true, false));
                    i = j;
                } else {
                    // If this item is a boundary and ungrouped, it must not be absorbed into a legacy run.
                    if (isBoundary.test(ctx)) {
                        List<Context> single = List.of(ctx);
                        out.add(new GroupDescriptor(
                                GroupType.GROUP_BY_ACTION, ctx.id().toString(), "", single, false, false));
                        i = i + 1;
                        continue;
                    }

                    // Legacy run: contiguous ungrouped items that are not boundaries
                    int j = i + 1;
                    while (j < end && contexts.get(j).getGroupId() == null && !isBoundary.test(contexts.get(j))) {
                        j++;
                    }
                    int len = j - i;
                    if (len >= 2) {
                        List<Context> children = Collections.unmodifiableList(new ArrayList<>(contexts.subList(i, j)));
                        String label = computeHeaderLabelFor(children);
                        String key = children.get(0).id().toString();
                        out.add(new GroupDescriptor(GroupType.GROUP_BY_ACTION, key, label, children, true, false));
                    } else {
                        // Singleton legacy (no header)
                        List<Context> single = List.of(ctx);
                        out.add(new GroupDescriptor(
                                GroupType.GROUP_BY_ACTION, ctx.id().toString(), "", single, false, false));
                    }
                    i = j;
                }
            }
        }

        private static String computeHeaderLabelFor(List<Context> children) {
            int size = children.size();
            if (size == 2) {
                var a0 = safeFirstWord(children.get(0).getAction());
                var a1 = safeFirstWord(children.get(1).getAction());
                return a0 + " + " + a1;
            }
            return size + " actions";
        }

        private static String safeFirstWord(String text) {
            if (text == null || text.isBlank()) {
                return "";
            }
            int idx = text.indexOf(' ');
            return (idx < 0) ? text : text.substring(0, idx);
        }
    }

    /**
     * Build a mapping from Context.id to the visible row index in the given JTable.
     * Visible children map to their own row; children of collapsed groups map to the group's header row.
     * If the table is null, returns an empty map. If descriptors are null or empty, maps only currently
     * visible Context rows (collapsed children cannot be resolved to headers without descriptors).
     */
    public static java.util.Map<java.util.UUID, Integer> buildContextToRowMap(
            java.util.List<GroupDescriptor> descriptors, javax.swing.JTable table) {
        if (table == null) {
            return java.util.Map.of();
        }

        // Index descriptors by UUID key (groupId for id-groups; first-child id for legacy action groups)
        var byKey = new HashMap<java.util.UUID, GroupDescriptor>();
        if (descriptors != null) {
            for (var gd : descriptors) {
                try {
                    var keyUuid = java.util.UUID.fromString(gd.key());
                    byKey.put(keyUuid, gd);
                } catch (IllegalArgumentException ignored) {
                    // skip malformed keys
                }
            }
        }

        var result = new HashMap<java.util.UUID, Integer>();

        var model = table.getModel();
        // First pass: map visible Context rows directly
        for (int row = 0; row < model.getRowCount(); row++) {
            var val = model.getValueAt(row, 2);
            if (val instanceof io.github.jbellis.brokk.context.Context ctx) {
                result.put(ctx.id(), row);
            }
        }

        // Second pass: for collapsed group headers, map each child to the header row
        for (int row = 0; row < model.getRowCount(); row++) {
            var val = model.getValueAt(row, 2);
            if (val instanceof io.github.jbellis.brokk.gui.HistoryOutputPanel.GroupRow gr && !gr.expanded()) {
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
