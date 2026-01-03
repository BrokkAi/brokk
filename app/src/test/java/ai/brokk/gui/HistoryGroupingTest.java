package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IContextManager;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragments;
import ai.brokk.gui.HistoryGrouping.GroupDescriptor;
import ai.brokk.gui.HistoryGrouping.GroupType;
import ai.brokk.gui.HistoryGrouping.GroupingBuilder;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@NullMarked
public class HistoryGroupingTest {
    private IContextManager cm;

    @TempDir
    Path tmpDir;

    @BeforeEach
    void setUp() {
        cm = new TestContextManager(new TestProject(tmpDir));
    }

    private Context ctx(String description) {
        // Create a context with a StringFragment whose shortDescription is the given description
        var base = new Context(cm);
        var fragment = new ContextFragments.StringFragment(cm, "content", description, "text");
        return base.addFragments(fragment);
    }

    private static List<GroupDescriptor> discover(List<Context> contexts, Predicate<Context> boundary) {
        return GroupingBuilder.discoverGroups(contexts, boundary, java.util.Set.of(), contextId -> null);
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
        // Since "Build project" is <= 7 words, it won't be summarized and will appear in history
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
    public void anonymousGroup_singleChild_showsJustDescription() {
        // A single ungrouped context should show its description directly (no header since it's a singleton)
        var c1 = ctx("Build project");
        var groups = discover(List.of(c1), c -> false);

        assertEquals(1, groups.size());
        var g = groups.getFirst();
        // Singleton should not show header, but if it did, it would show the description
        assertFalse(g.shouldShowHeader(), "Singleton should not show header");
    }

    @Test
    public void anonymousGroup_complexGrouping() {
        // Build proper chains where each context builds on the previous
        // Note: The first context (index 0) is skipped as "session start"

        // 1. Two adds -> only 1 description after skipping first, returns just that description
        var empty1 = new Context(cm);
        var frag1 = new ContextFragments.StringFragment(cm, "c1", "file1", "text");
        var c1 = empty1.addFragments(frag1);
        var frag2 = new ContextFragments.StringFragment(cm, "c2", "file2", "text");
        var c2 = c1.addFragments(frag2);
        var g1 = discover(List.of(c1, c2), c -> false);
        assertEquals("Add x2", g1.getFirst().label().join());

        // 2. Three adds -> 2 descriptions after skipping first -> "Add x2"
        var frag3 = new ContextFragments.StringFragment(cm, "c3", "file3", "text");
        var c3 = c2.addFragments(frag3);
        var g2 = discover(List.of(c1, c2, c3), c -> false);
        assertEquals("Add x3", g2.getFirst().label().join());

        // 3. Four adds -> 3 descriptions after skipping first -> "Add x3"
        var frag4 = new ContextFragments.StringFragment(cm, "c4", "file4", "text");
        var c4 = c3.addFragments(frag4);
        var g3 = discover(List.of(c1, c2, c3, c4), c -> false);
        assertEquals("Add x4", g3.getFirst().label().join());
    }

    @Test
    public void mixedEntriesGroupCorrectly() {
        // Build a chain where some contexts add and some remove
        // The label computation skips index 0 (session start), so we need 4 real actions

        var empty = new Context(cm);

        var frag1 = new ContextFragments.StringFragment(cm, "c1", "file1", "text");
        var ctx1 = empty.addFragments(frag1);

        var frag2 = new ContextFragments.StringFragment(cm, "c2", "file2", "text");
        var ctx2 = ctx1.addFragments(frag2);

        var ctx3 = ctx2.removeFragments(List.of(frag1));

        var frag3 = new ContextFragments.StringFragment(cm, "c3", "file3", "text");
        var ctx4 = ctx3.addFragments(frag3);

        var ctx5 = ctx4.removeFragments(List.of(frag2));

        // ctx1 is at index 0 and gets skipped in label computation
        // Remaining: ctx2=Add, ctx3=Remove, ctx4=Add, ctx5=Remove -> "Add x2 + Remove x2"
        var contexts = List.of(ctx1, ctx2, ctx3, ctx4, ctx5);
        var groups = discover(contexts, c -> false);

        assertEquals(1, groups.size(), "All contexts should be in one legacy group");
        var g = groups.getFirst();
        assertTrue(g.shouldShowHeader());
        assertEquals(5, g.children().size());
        assertEquals("Add x3 + Remove x2", g.label().join());
    }
}
