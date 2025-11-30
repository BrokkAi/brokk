package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestContextManager;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextTest {

    @TempDir
    Path tempDir;

    private IContextManager contextManager;
    private TestAnalyzer analyzer;

    private static CodeUnit createTestCodeUnit(String fqName, ProjectFile pf) {
        String shortName = fqName.substring(fqName.lastIndexOf('.') + 1);
        String packageName = fqName.contains(".") ? fqName.substring(0, fqName.lastIndexOf('.')) : "";
        return new CodeUnit(pf, CodeUnitType.CLASS, packageName, shortName);
    }

    @BeforeEach
    void setUp() throws Exception {
        // Prepare a couple of Java files for CodeUnit sources
        var pf1 = new ProjectFile(tempDir, "src/CodeFragmentTarget.java");
        Files.createDirectories(pf1.absPath().getParent());
        Files.writeString(pf1.absPath(), "public class CodeFragmentTarget {}");

        var pf2 = new ProjectFile(tempDir, "src/AnotherClass.java");
        Files.createDirectories(pf2.absPath().getParent());
        Files.writeString(pf2.absPath(), "public class AnotherClass {}");

        var cu1 = createTestCodeUnit("com.example.CodeFragmentTarget", pf1);
        var cu2 = createTestCodeUnit("com.example.AnotherClass", pf2);

        analyzer = new TestAnalyzer(List.of(cu1, cu2), Map.of());
        contextManager = new TestContextManager(tempDir, new NoOpConsoleIO(), analyzer);

        // Reset fragment ID counter for test isolation
        ContextFragment.setMinimumId(1);
    }

    @Test
    void testAddPathFragmentsDedupAndAction() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Foo.java");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "public class Foo {}");

        var p1 = new ContextFragment.ProjectPathFragment(pf, contextManager);
        var p2 = new ContextFragment.ProjectPathFragment(pf, contextManager);

        var ctx = new Context(contextManager, "init");
        ctx = ctx.addPathFragments(List.of(p1, p2));

        // Dedup: only one path fragment
        assertEquals(1, ctx.fileFragments().count(), "Duplicate path fragments should be deduped");
        assertTrue(ctx.getAction().startsWith("Edit "), "Action should summarize edit");
    }

    @Test
    void testAddVirtualFragmentsDedupBySource() {
        var v1 = new ContextFragment.StringFragment(
                contextManager, "same text", "desc-1", SyntaxConstants.SYNTAX_STYLE_NONE);
        // Identical content and description -> should be deduped
        var v2 = new ContextFragment.StringFragment(
                contextManager, "same text", "desc-1", SyntaxConstants.SYNTAX_STYLE_NONE);

        var ctx = new Context(contextManager, "init");
        ctx = ctx.addVirtualFragment(v1);
        ctx = ctx.addVirtualFragment(v2);

        assertEquals(1, ctx.virtualFragments().count(), "Duplicate virtual fragments should be deduped by id/source");
    }


    @Test
    void testRemoveFragmentsClearsReadOnlyAndAllowsReAdd() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Rm.java");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "class Rm {}");
        var ppf = new ContextFragment.ProjectPathFragment(pf, contextManager);

        var ctx = new Context(contextManager, "init").addPathFragments(List.of(ppf));
        ctx = ctx.setReadonly(ppf, true);
        assertTrue(ctx.isMarkedReadonly(ppf));

        // Remove fragment
        var ctxRemoved = ctx.removeFragments(List.of(ppf));
        assertEquals(0, ctxRemoved.fileFragments().count(), "Fragment should be removed");

        // Re-add the same instance; read-only should not persist
        var ctxReadded = ctxRemoved.addPathFragments(List.of(ppf));
        assertEquals(1, ctxReadded.fileFragments().count());
        assertFalse(ctxReadded.isMarkedReadonly(ppf), "Read-only should be cleared after removal");
    }

    @Test
    void testWithBuildResultFailureAndSuccessClears() {
        var ctx = new Context(contextManager, "init");

        // Build failed -> fragment added
        ctx = ctx.withBuildResult(false, "Some error");
        var buildFrag = ctx.getBuildFragment();
        assertTrue(buildFrag.isPresent(), "Build result fragment should be present on failure");
        assertEquals("Some error", buildFrag.get().text());
        assertFalse(ctx.getBuildError().isBlank());

        // Build success -> cleared
        ctx = ctx.withBuildResult(true, "ignored");
        assertTrue(ctx.getBuildFragment().isEmpty(), "Build result fragment should be cleared on success");
        assertTrue(ctx.getBuildError().isBlank(), "No build error after success");
    }

    @Test
    void testBuildResultFragmentIsNonEditableButShownAsReadonly() {
        var ctx = new Context(contextManager, "init").withBuildResult(false, "Build failed: something went wrong");
        var buildFrag = ctx.getBuildFragment().orElseThrow();

        // Build fragment should be part of the read-only workspace view, but not editable.
        assertTrue(
                ctx.getReadonlyFragments().anyMatch(f -> f.equals(buildFrag)),
                "Build result fragment should appear in the readonly fragments view");
        assertFalse(
                ctx.getEditableFragments().anyMatch(f -> f.equals(buildFrag)),
                "Build result fragment should not be editable");
    }

    @Test
    void testIsAiResultDetection() {
        List<ChatMessage> msgs = List.of(UserMessage.from("U"), AiMessage.from("A"));
        var tf = new ContextFragment.TaskFragment(contextManager, msgs, "task");
        var ctx = new Context(contextManager, "init").withParsedOutput(tf, "action");
        assertTrue(ctx.isAiResult(), "AI result should be true when AI message is present");

        List<ChatMessage> msgs2 = List.of(UserMessage.from("Only user"));
        var tf2 = new ContextFragment.TaskFragment(contextManager, msgs2, "task");
        var ctx2 = new Context(contextManager, "init").withParsedOutput(tf2, "action");
        assertFalse(ctx2.isAiResult(), "AI result should be false with no AI messages");
    }

    @Test
    void testCopyAndRefreshReplacesComputedFragmentsOnChange() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Refresh.java");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "class Refresh {}");
        var ppf = new ContextFragment.ProjectPathFragment(pf, contextManager);

        var sf = new ContextFragment.StringFragment(contextManager, "text", "desc", SyntaxConstants.SYNTAX_STYLE_NONE);

        var ctx = new Context(contextManager, "init")
                .addPathFragments(List.of(ppf))
                .addVirtualFragment(sf);

        var refreshed = ctx.copyAndRefresh(Set.of(pf));

        // ProjectPathFragment should be replaced (new instance), StringFragment should be reused (same instance)
        var oldPpf = ctx.fileFragments().findFirst().orElseThrow();
        var newPpf = refreshed.fileFragments().findFirst().orElseThrow();
        assertNotSame(oldPpf, newPpf, "Computed project fragment should be refreshed");
        assertSame(
                sf,
                refreshed
                        .virtualFragments()
                        .filter(f -> f instanceof ContextFragment.StringFragment)
                        .findFirst()
                        .orElseThrow(),
                "Unrelated virtual fragments should be reused");
        assertEquals("Load external changes", refreshed.getAction(), "Action should be set accordingly");
    }

    @Test
    void testCopyAndRefreshPreservesReadOnly() throws Exception {
        var pf = new ProjectFile(tempDir, "src/RefreshRO.java");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "class RefreshRO {}");
        var ppf = new ContextFragment.ProjectPathFragment(pf, contextManager);

        var ctx = new Context(contextManager, "init").addPathFragments(List.of(ppf));
        // Mark the fragment read-only
        ctx = ctx.setReadonly(ppf, true);
        assertTrue(ctx.isMarkedReadonly(ppf), "Precondition: fragment should be read-only");

        // Trigger refresh
        var refreshed = ctx.copyAndRefresh(Set.of(pf));

        // Ensure a new instance was created for the project fragment
        var newFrag = refreshed.fileFragments().findFirst().orElseThrow();
        assertNotSame(ppf, newFrag, "Project fragment should be refreshed to a new instance");

        // Verify read-only state is preserved on the refreshed fragment
        assertTrue(refreshed.isMarkedReadonly(newFrag), "Read-only state must persist across refresh");
    }

    @Test
    void testUnionCombinesWithoutDuplicates() throws Exception {
        var pf = new ProjectFile(tempDir, "src/U.java");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "class U {}");
        var ppf1 = new ContextFragment.ProjectPathFragment(pf, contextManager);

        var s1 = new ContextFragment.StringFragment(contextManager, "Text-1", "D1", SyntaxConstants.SYNTAX_STYLE_NONE);
        var s2 = new ContextFragment.StringFragment(contextManager, "Text-2", "D2", SyntaxConstants.SYNTAX_STYLE_NONE);

        var ctx1 = new Context(contextManager, "c1")
                .addPathFragments(List.of(ppf1))
                .addVirtualFragment(s1);
        var ctx2 = new Context(contextManager, "c2")
                .addPathFragments(List.of(ppf1))
                .addVirtualFragment(s2);

        var merged = ctx1.union(ctx2);

        // One path (dedup), two unique virtuals
        assertEquals(1, merged.fileFragments().count());
        assertEquals(2, merged.virtualFragments().count());
    }

    @Test
    void testGetAllFragmentsInDisplayOrderIncludesHistoryFirst() {
        var s1 = new ContextFragment.StringFragment(contextManager, "T", "D", SyntaxConstants.SYNTAX_STYLE_NONE);
        var ctx = new Context(contextManager, "init").addVirtualFragment(s1);

        // Add a history entry
        var msgs = List.<ChatMessage>of(UserMessage.from("User"), AiMessage.from("AI"));
        var log = new ContextFragment.TaskFragment(contextManager, msgs, "Log");
        var entry = new TaskEntry(1, log, null);
        ctx = ctx.addHistoryEntry(entry, log, CompletableFuture.completedFuture("act"));

        var all = ctx.getAllFragmentsInDisplayOrder();
        assertFalse(all.isEmpty());
        assertTrue(all.getFirst() instanceof ContextFragment.HistoryFragment, "History should be first when present");

        // Then path and virtuals follow; exact order beyond first isn't asserted here
        long historyCount = all.stream()
                .filter(f -> f instanceof ContextFragment.HistoryFragment)
                .count();
        assertEquals(1L, historyCount, "Exactly one history fragment should be present");
    }

    @Test
    void testGetActionSummarizingWhenIncomplete() {
        var ctx = new Context(contextManager, "init").withAction(new CompletableFuture<>());
        assertEquals(Context.SUMMARIZING, ctx.getAction(), "Should show summarizing when action is incomplete");
    }

    @Test
    void testWorkspaceContentEqualsBySource() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Eq.java");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "class Eq {}");

        var f1 = new ContextFragment.ProjectPathFragment(pf, contextManager);
        var f2 = new ContextFragment.ProjectPathFragment(pf, contextManager); // different instance, same source

        var c1 = new Context(contextManager, "c1").addPathFragments(List.of(f1));
        var c2 = new Context(contextManager, "c2").addPathFragments(List.of(f2));

        assertTrue(c1.workspaceContentEquals(c2), "Contexts with same sources should be equivalent");
    }

    @Test
    void testWithAddedClassesSkipsWorkspaceFiles() throws Exception {
        // Workspace contains CodeFragmentTarget's source; should skip adding CodeFragment for it
        var pfWorkspace = new ProjectFile(tempDir, "src/CodeFragmentTarget.java");
        Files.createDirectories(pfWorkspace.absPath().getParent());
        Files.writeString(pfWorkspace.absPath(), "public class CodeFragmentTarget {}");
        var ppf = new ContextFragment.ProjectPathFragment(pfWorkspace, contextManager);

        // Another class not in workspace should be added as CodeFragment
        var ctx = new Context(contextManager, "init").addPathFragments(List.of(ppf));
        ctx = Context.withAddedClasses(
                ctx, List.of("com.example.CodeFragmentTarget", "com.example.AnotherClass"), analyzer);

        var virtuals = ctx.virtualFragments().toList();
        assertEquals(1, virtuals.size(), "Only non-workspace class should be added");
        assertTrue(virtuals.get(0) instanceof ContextFragment.CodeFragment);
        var codeFrag = (ContextFragment.CodeFragment) virtuals.get(0);
        // May need to compute the unit; but we can assert the FQN via repr or computedUnit
        var maybeUnit = codeFrag.computedUnit().await(Duration.ofSeconds(5));
        assertTrue(maybeUnit.isPresent());
        assertEquals("com.example.AnotherClass", maybeUnit.get().fqName());
    }

    @Test
    void testWithGroupSetsFields() {
        var ctx = new Context(contextManager, "init");
        var gid = UUID.randomUUID();
        var labeled = ctx.withGroup(gid, "group-label");
        assertEquals(gid, labeled.getGroupId());
        assertEquals("group-label", labeled.getGroupLabel());
    }
}
