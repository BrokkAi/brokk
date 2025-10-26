package io.github.jbellis.brokk.gui;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.gui.HistoryGrouping.GroupDescriptor;
import io.github.jbellis.brokk.gui.HistoryGrouping.GroupType;
import io.github.jbellis.brokk.gui.HistoryGrouping.GroupingBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

public class HistoryGroupingTest {

  private static Context ctx(String action) {
    var base = new Context(new IContextManager() {}, null);
    return base.withAction(CompletableFuture.completedFuture(action));
  }

  private static Context ctxWithGroup(String action, UUID gid, String label) {
    return ctx(action).withGroup(gid, label);
  }

  private static List<GroupDescriptor> discover(List<Context> contexts, Predicate<Context> boundary) {
    return GroupingBuilder.discoverGroups(contexts, boundary);
  }

  @Test
  public void singleUngrouped_isStandaloneNoHeader() {
    var c1 = ctx("Build project");
    var groups = discover(List.of(c1), c -> false);

    assertEquals(1, groups.size(), "Expected one descriptor");
    var g = groups.getFirst();
    assertEquals(GroupType.GROUP_BY_ACTION, g.type(), "Singletons are represented as action groups without header");
    assertFalse(g.shouldShowHeader(), "Singleton should not have a header");
    assertEquals(1, g.children().size(), "One child in singleton");
    assertEquals(c1.id(), g.children().getFirst().id());
    assertEquals("", g.label(), "Singleton label should be empty");
  }

  @Test
  public void twoUngrouped_sameAction_becomesActionGroup() {
    var c1 = ctx("Compile module A");
    var c2 = ctx("Compile module A");

    var groups = discover(List.of(c1, c2), c -> false);

    assertEquals(1, groups.size(), "Two identical actions should be grouped into one legacy action group");
    var g = groups.getFirst();
    assertEquals(GroupType.GROUP_BY_ACTION, g.type());
    assertTrue(g.shouldShowHeader(), "Legacy action group should show header");
    assertEquals(2, g.children().size());
    assertEquals(c1.id().toString(), g.key(), "Legacy group key should be first child id");
  }

  @Test
  public void singleGroupId_showsHeaderAndOneChild() {
    var gid = UUID.randomUUID();
    var c1 = ctxWithGroup("Run tests", gid, "Test Run");

    var groups = discover(List.of(c1), c -> false);

    assertEquals(1, groups.size());
    var g = groups.getFirst();
    assertEquals(GroupType.GROUP_BY_ID, g.type());
    assertTrue(g.shouldShowHeader(), "GroupId groups always show a header, even singletons");
    assertEquals(1, g.children().size());
    assertEquals(gid.toString(), g.key());
    assertEquals("Test Run", g.label());
  }

  @Test
  public void multiGroupId_showsHeaderAndAllChildren() {
    var gid = UUID.randomUUID();
    var c1 = ctxWithGroup("Run tests", gid, "Batch XYZ");
    var c2 = ctxWithGroup("Run tests", gid, "Batch XYZ");
    var c3 = ctxWithGroup("Run tests", gid, "Batch XYZ");

    var groups = discover(List.of(c1, c2, c3), c -> false);

    assertEquals(1, groups.size());
    var g = groups.getFirst();
    assertEquals(GroupType.GROUP_BY_ID, g.type());
    assertTrue(g.shouldShowHeader());
    assertEquals(3, g.children().size());
    assertEquals(gid.toString(), g.key());
    assertEquals("Batch XYZ", g.label());
  }

  @Test
  public void boundaryBreaksGroups() {
    var a1 = ctx("A action");
    var boundary = ctx("A action"); // mark this as boundary via predicate
    var a2 = ctx("A action");

    Predicate<Context> isBoundary = c -> c == boundary;

    var groups = discover(List.of(a1, boundary, a2), isBoundary);

    // Expect three singletons with no headers; boundary is not merged with neighbors
    assertEquals(3, groups.size(), "Boundary should prevent grouping across it");

    for (int i = 0; i < groups.size(); i++) {
      var g = groups.get(i);
      assertFalse(g.shouldShowHeader(), "All should be singleton descriptors without header");
      assertEquals(1, g.children().size());
    }

    assertEquals(a1.id(), groups.get(0).children().getFirst().id());
    assertEquals(boundary.id(), groups.get(1).children().getFirst().id());
    assertEquals(a2.id(), groups.get(2).children().getFirst().id());
  }

  @Test
  public void expansionKeyRemainsStableWhenAppendingUngroupedAfterGroupId() {
    var gid = UUID.randomUUID();

    var c1 = ctxWithGroup("Do work", gid, "Batch");
    var c2 = ctxWithGroup("Do more work", gid, "Batch");

    var initial = discover(List.of(c1, c2), c -> false);
    assertEquals(1, initial.size());
    var group = initial.getFirst();
    assertEquals(GroupType.GROUP_BY_ID, group.type());
    var oldKey = group.key();
    var oldKeyUuid = UUID.fromString(oldKey);

    // Simulate seeded expansion state keyed by UUID
    var expandedMap = new java.util.HashMap<UUID, Boolean>();
    expandedMap.put(oldKeyUuid, Boolean.TRUE);

    // Append a new ungrouped item after the group
    var c3 = ctx("Standalone");
    var next = new ArrayList<Context>();
    next.addAll(List.of(c1, c2));
    next.add(c3);

    var recomputed = discover(next, c -> false);

    // Find the original group descriptor
    GroupDescriptor found = recomputed.stream()
        .filter(gd -> gd.type() == GroupType.GROUP_BY_ID && gd.key().equals(oldKey))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Original group descriptor not found after append"));

    assertEquals(oldKey, found.key(), "Group key should remain the same");
    assertTrue(Boolean.TRUE.equals(expandedMap.get(oldKeyUuid)), "Seeded expansion state should remain valid with same key");
  }
}
