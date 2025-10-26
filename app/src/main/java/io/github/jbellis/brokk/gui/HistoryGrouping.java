package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.context.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Unified grouping model for context history rendering.
 *
 * This file provides:
 * - GroupDescriptor: immutable description of a logical group of contexts,
 *   with enough metadata for the UI to render headers and child rows.
 * - GroupingBuilder.discoverGroups: a deterministic algorithm that discovers
 *   contiguous groups given a list of Context and a boundary predicate.
 *
 * Rules implemented:
 * 1) Groups are contiguous runs bounded by boundaries (isBoundary.test(ctx) == true).
 *    Boundaries terminate any ongoing group and are always returned as singleton groups
 *    without a header row.
 * 2) If a context has a non-null getGroupId(), it belongs to a group identified by that UUID.
 *    Group-by-id groups always form a group, even if the group size is 1. Headers are shown
 *    only when the group has 2+ children.
 * 3) Legacy action-based grouping: when getGroupId() is null, contiguous runs of identical actions
 *    may be grouped. Only create a legacy group when the run length is >= 2. Otherwise, return
 *    the context as a singleton without a header.
 * 4) Preserve original order; return descriptors covering all contexts.
 *
 * Stable keys:
 * - For GROUP_BY_ID: key = groupId.toString()
 * - For GROUP_BY_ACTION (legacy): key = first child's context id as String
 *
 * UI note:
 * - HistoryOutputPanel stores expand/collapse state keyed by UUID today; callers can convert
 *   GroupDescriptor.key into UUID for id-based groups or derive a stable UUID from the first
 *   child id for legacy groups if desired. Using the first child context id matches the current
 *   legacy logic.
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

      List<GroupDescriptor> out = new ArrayList<>();
      final int n = contexts.size();

      int i = 0;
      while (i < n) {
        Context ctx = contexts.get(i);

        // Boundaries are always singleton groups without a header
        if (isBoundary.test(ctx)) {
          List<Context> single = List.of(ctx);
          GroupDescriptor gd = new GroupDescriptor(
              GroupType.GROUP_BY_ACTION,
              ctx.id().toString(),          // stable key for singleton
              "",                           // no header
              single,
              false,                        // no header for singleton
              false                         // provisional; will patch isLastGroup below
          );
          out.add(gd);
          i += 1;
          continue;
        }

        UUID groupId = ctx.getGroupId();

        // Group-by-id: contiguous run sharing the same id, terminated by boundary
        if (groupId != null) {
          int j = i;
          while (j < n) {
            Context c = contexts.get(j);
            if (isBoundary.test(c)) break;
            if (!groupId.equals(c.getGroupId())) break;
            j++;
          }
          List<Context> children = Collections.unmodifiableList(new ArrayList<>(contexts.subList(i, j)));
          boolean showHeader = children.size() >= 2;

          String label = showHeader ? computeHeaderLabelFor(children) : "";
          GroupDescriptor gd = new GroupDescriptor(
              GroupType.GROUP_BY_ID,
              groupId.toString(),
              label,
              children,
              showHeader,
              false // provisional
          );
          out.add(gd);
          i = j;
          continue;
        }

        // Legacy action-based grouping for contexts without a groupId
        String action = ctx.getAction();
        int j = i + 1;
        while (j < n) {
          Context c = contexts.get(j);
          if (isBoundary.test(c)) break;
          if (c.getGroupId() != null) break; // stop at any explicit group
          if (!Objects.equals(action, c.getAction())) break;
          j++;
        }

        int len = j - i;
        if (len >= 2) {
          List<Context> children = Collections.unmodifiableList(new ArrayList<>(contexts.subList(i, j)));
          boolean showHeader = true;
          String label = computeHeaderLabelFor(children);
          String key = children.get(0).id().toString(); // stable key for legacy group
          GroupDescriptor gd = new GroupDescriptor(
              GroupType.GROUP_BY_ACTION,
              key,
              label,
              children,
              showHeader,
              false // provisional
          );
          out.add(gd);
          i = j;
        } else {
          // Singleton without groupId: top-level, no header
          List<Context> single = List.of(ctx);
          GroupDescriptor gd = new GroupDescriptor(
              GroupType.GROUP_BY_ACTION,
              ctx.id().toString(),
              "",
              single,
              false,
              false // provisional
          );
          out.add(gd);
          i += 1;
        }
      }

      // Patch isLastGroup flag
      if (!out.isEmpty()) {
        int last = out.size() - 1;
        GroupDescriptor tail = out.get(last);
        out.set(last, new GroupDescriptor(
            tail.type(),
            tail.key(),
            tail.label(),
            tail.children(),
            tail.shouldShowHeader(),
            true));
      }

      return List.copyOf(out);
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
}
