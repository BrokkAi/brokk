package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IContextManager;
import ai.brokk.context.Context;
import ai.brokk.gui.HistoryGrouping.GroupDescriptor;
import ai.brokk.gui.HistoryGrouping.GroupType;
import ai.brokk.gui.HistoryGrouping.GroupingBuilder;
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
    public void twoUngrouped_contiguous_becomesLegacyGroup() {
        var c1 = ctx("Compile module A");
        var c2 = ctx("Compile module A");

        var groups = discover(List.of(c1, c2), c -> false);

        assertEquals(
                1, groups.size(), "Two contiguous ungrouped contexts should be grouped into one legacy action group");
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
        var boundary = ctx("B boundary"); // mark this as boundary via predicate
        var a2 = ctx("C action");

        Predicate<Context> isBoundary = c -> c == boundary;

        var groups = discover(List.of(a1, boundary, a2), isBoundary);

        // Expect three groups: [a1] singleton, [boundary] singleton, then [a2] singleton
        assertEquals(
                3,
                groups.size(),
                "Boundary should terminate prior group, and boundary (ungrouped) is its own singleton");

        var g0 = groups.get(0);
        assertFalse(g0.shouldShowHeader(), "First should be singleton without header");
        assertEquals(1, g0.children().size());
        assertEquals(a1.id(), g0.children().getFirst().id());

        var g1 = groups.get(1);
        assertEquals(GroupType.GROUP_BY_ACTION, g1.type(), "Boundary without groupId should be a legacy singleton");
        assertFalse(g1.shouldShowHeader(), "Ungrouped boundary should not show a header");
        assertEquals(1, g1.children().size());
        assertEquals(boundary.id(), g1.children().get(0).id());

        var g2 = groups.get(2);
        assertFalse(g2.shouldShowHeader(), "Trailing ungrouped after boundary should be singleton without header");
        assertEquals(1, g2.children().size());
        assertEquals(a2.id(), g2.children().get(0).id());
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
        assertTrue(
                Boolean.TRUE.equals(expandedMap.get(oldKeyUuid)),
                "Seeded expansion state should remain valid with same key");
    }

    @Test
    public void ungroupedDifferentActions_groupedTogetherUntilBoundary() {
        var a = ctx("Edit file");
        var b = ctx("Run tests");

        var groups = discover(List.of(a, b), c -> false);

        assertEquals(1, groups.size(), "Contiguous ungrouped contexts should form one legacy group");
        var g = groups.getFirst();
        assertEquals(GroupType.GROUP_BY_ACTION, g.type());
        assertTrue(g.shouldShowHeader());
        assertEquals(2, g.children().size());
        assertEquals(a.id(), g.children().get(0).id());
        assertEquals(b.id(), g.children().get(1).id());
    }

    @Test
    public void boundaryWithGroupId_startsNewGroupById() {
        var gid = UUID.randomUUID();
        var a1 = ctx("Prep");
        var boundary = ctxWithGroup("Phase start", gid, "Batch L");
        var a2 = ctxWithGroup("Phase continue", gid, "Batch L");

        Predicate<Context> isBoundary = c -> c == boundary;

        var groups = discover(List.of(a1, boundary, a2), isBoundary);

        assertEquals(2, groups.size(), "Expected singleton followed by a group-by-id group starting at boundary");

        var g0 = groups.get(0);
        assertFalse(g0.shouldShowHeader());
        assertEquals(1, g0.children().size());
        assertEquals(a1.id(), g0.children().getFirst().id());

        var g1 = groups.get(1);
        assertEquals(GroupType.GROUP_BY_ID, g1.type());
        assertTrue(g1.shouldShowHeader());
        assertEquals(gid.toString(), g1.key());
        assertEquals("Batch L", g1.label());
        assertEquals(2, g1.children().size());
        assertEquals(boundary.id(), g1.children().get(0).id());
        assertEquals(a2.id(), g1.children().get(1).id());
    }

    @Test
    public void mixedLegacyUngroupedAndGroupId() {
        var gid = UUID.randomUUID();

        var a = ctx("Edit");
        var b = ctx("Run");

        var g1 = ctxWithGroup("Start", gid, "Batch");
        var g2 = ctxWithGroup("Continue", gid, "Batch");

        var c = ctx("Format");

        Predicate<Context> isBoundary = c0 -> c0 == g1; // g1 acts as a boundary; also starts its group

        var groups = discover(List.of(a, b, g1, g2, c), isBoundary);

        assertEquals(3, groups.size(), "Expected legacy group, then group-by-id, then singleton");

        // Legacy group [a, b]
        var gg0 = groups.get(0);
        assertEquals(GroupType.GROUP_BY_ACTION, gg0.type());
        assertTrue(gg0.shouldShowHeader());
        assertEquals(2, gg0.children().size());
        assertEquals(a.id(), gg0.children().get(0).id());
        assertEquals(b.id(), gg0.children().get(1).id());

        // Group-by-id [g1, g2]
        var gg1 = groups.get(1);
        assertEquals(GroupType.GROUP_BY_ID, gg1.type());
        assertTrue(gg1.shouldShowHeader());
        assertEquals(2, gg1.children().size());
        assertEquals(gid.toString(), gg1.key());
        assertEquals("Batch", gg1.label());
        assertEquals(g1.id(), gg1.children().get(0).id());
        assertEquals(g2.id(), gg1.children().get(1).id());

        // Singleton [c]
        var gg2 = groups.get(2);
        assertFalse(gg2.shouldShowHeader());
        assertEquals(1, gg2.children().size());
        assertEquals(c.id(), gg2.children().getFirst().id());
    }
}
