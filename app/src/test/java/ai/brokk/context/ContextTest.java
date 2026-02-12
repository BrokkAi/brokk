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
import java.util.*;
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
        pf1.write("public class CodeFragmentTarget {}");

        var pf2 = new ProjectFile(tempDir, "src/AnotherClass.java");
        Files.createDirectories(pf2.absPath().getParent());
        pf2.write("public class AnotherClass {}");

        var cu1 = createTestCodeUnit("com.example.CodeFragmentTarget", pf1);
        var cu2 = createTestCodeUnit("com.example.AnotherClass", pf2);

        analyzer = new TestAnalyzer(List.of(cu1, cu2), Map.of());
        contextManager = new TestContextManager(tempDir, new NoOpConsoleIO(), analyzer);
    }

    @Test
    void testaddFragmentsDedupAndAction() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Foo.java");
        Files.createDirectories(pf.absPath().getParent());
        pf.write("public class Foo {}");

        var p1 = new ContextFragments.ProjectPathFragment(pf, contextManager);
        var p2 = new ContextFragments.ProjectPathFragment(pf, contextManager);

        // Start with a non-empty context so getDescription doesn't hit the empty check if we had one
        var originalCtx = new Context(contextManager)
                .addFragments(new ContextFragments.StringFragment(
                        contextManager, "base", "base", SyntaxConstants.SYNTAX_STYLE_NONE));
        var ctx = originalCtx.addFragments(List.of(p1, p2));

        // Dedup: only one path fragment
        assertEquals(
                1,
                ctx.allFragments().filter(f -> f.getType().isPath()).count(),
                "Duplicate path fragments should be deduped");
    }

    @Test
    void testAddVirtualFragmentsDedupBySource() {
        var v1 = new ContextFragments.StringFragment(
                contextManager, "same text", "desc-1", SyntaxConstants.SYNTAX_STYLE_NONE);
        // Identical content and description -> should be deduped
        var v2 = new ContextFragments.StringFragment(
                contextManager, "same text", "desc-1", SyntaxConstants.SYNTAX_STYLE_NONE);

        var ctx = new Context(contextManager);
        ctx = ctx.addFragments(v1);
        ctx = ctx.addFragments(v2);

        assertEquals(
                1,
                ctx.allFragments().filter(f -> !f.getType().isPath()).count(),
                "Duplicate virtual fragments should be deduped by id/source");
    }

    @Test
    void testEditableFragmentsOrderingAndReadOnlyFilter() throws Exception {
        // Prepare path files with distinct mtimes
        var pfA = new ProjectFile(tempDir, "src/A.java");
        Files.createDirectories(pfA.absPath().getParent());
        pfA.write("class A {}");
        Thread.sleep(1100); // ensure different mtime granularity across platforms

        var pfB = new ProjectFile(tempDir, "src/B.java");
        Files.createDirectories(pfB.absPath().getParent());
        pfB.write("class B {}");

        var projectFragA = new ContextFragments.ProjectPathFragment(pfA, contextManager);
        var projectFragB = new ContextFragments.ProjectPathFragment(pfB, contextManager);

        // External path fragment
        var extPath = tempDir.resolve("external.txt");
        Files.writeString(extPath, "external");
        var extFrag = new ContextFragments.ExternalPathFragment(new ExternalFile(extPath), contextManager);

        // Editable virtual: CodeFragment
        var cu = analyzer.getDefinitions("com.example.CodeFragmentTarget").stream()
                .findFirst()
                .orElseThrow();
        var codeFrag = new ContextFragments.CodeFragment(contextManager, cu);

        var ctx = new Context(contextManager)
                .addFragments(List.of(projectFragA, projectFragB))
                .addFragments(List.of(extFrag))
                .addFragments(codeFrag);

        // Order: editable virtuals first (CodeFragment),
        // then project path fragments.
        // ExternalPathFragment is not in FragmentType.EDITABLE_TYPES.
        var editable = ctx.getEditableFragments().toList();
        assertEquals(3, editable.size(), "All editable fragments should be present before read-only filtering");
        assertInstanceOf(ContextFragments.CodeFragment.class, editable.get(0), "Editable virtuals should come first");

        // Check that path fragments follow
        assertTrue(editable.get(1) instanceof ContextFragments.ProjectPathFragment);
        assertTrue(editable.get(2) instanceof ContextFragments.ProjectPathFragment);

        // Mark CodeFragment as read-only and verify it drops from editable
        var ctx2 = ctx.setReadonly(codeFrag, true);
        var editable2 = ctx2.getEditableFragments().toList();
        assertEquals(2, editable2.size(), "Read-only fragments should be filtered out");
        assertFalse(editable2.stream().anyMatch(f -> f instanceof ContextFragments.CodeFragment));
        assertTrue(ctx2.isMarkedReadonly(codeFrag), "Read-only state should be tracked");
    }

    @Test
    void testRemoveFragmentsClearsReadOnlyAndAllowsReAdd() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Rm.java");
        Files.createDirectories(pf.absPath().getParent());
        pf.write("class Rm {}");
        var ppf = new ContextFragments.ProjectPathFragment(pf, contextManager);

        var ctx = new Context(contextManager).addFragments(List.of(ppf));
        ctx = ctx.setReadonly(ppf, true);
        assertTrue(ctx.isMarkedReadonly(ppf));

        // Remove fragment
        var ctxRemoved = ctx.removeFragments(List.of(ppf));
        assertEquals(
                0, ctxRemoved.allFragments().filter(f -> f.getType().isPath()).count(), "Fragment should be removed");

        // Re-add the same instance; read-only should not persist
        var ctxReadded = ctxRemoved.addFragments(List.of(ppf));
        assertEquals(
                1, ctxReadded.allFragments().filter(f -> f.getType().isPath()).count());
        assertFalse(ctxReadded.isMarkedReadonly(ppf), "Read-only should be cleared after removal");
    }

    @Test
    void testWithBuildResultFailureAndSuccessClears() {
        var ctx = new Context(contextManager);

        // Build failed -> fragment added
        ctx = ctx.withBuildResult(false, "Some error");
        var buildFrag = ctx.getBuildFragment();
        assertTrue(buildFrag.isPresent(), "Build result fragment should be present on failure");
        assertEquals("Some error", buildFrag.get().text().join());
        assertFalse(ctx.getBuildError().isBlank());

        // Build success -> cleared
        ctx = ctx.withBuildResult(true, "ignored");
        assertTrue(ctx.getBuildFragment().isEmpty(), "Build result fragment should be cleared on success");
        assertTrue(ctx.getBuildError().isBlank(), "No build error after success");
    }

    @Test
    void testBuildResultFragmentIsNonEditableButShownAsReadonly() {
        var ctx = new Context(contextManager).withBuildResult(false, "Build failed: something went wrong");
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
    void testCopyAndRefreshReplacesComputedFragmentsOnChange() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Refresh.java");
        pf.write("class Refresh {}");
        var ppf = new ContextFragments.ProjectPathFragment(pf, contextManager);

        var sf = new ContextFragments.StringFragment(contextManager, "text", "desc", SyntaxConstants.SYNTAX_STYLE_NONE);

        var ctx = new Context(contextManager).addFragments(List.of(ppf)).addFragments(sf);

        pf.write("class RefreshR0 { public static void main() {} }");
        var refreshed = ctx.copyAndRefresh(Set.of(pf));

        // ProjectPathFragment should be replaced (new instance), StringFragment should be reused (same instance)
        var oldPpf =
                ctx.allFragments().filter(f -> f.getType().isPath()).findFirst().orElseThrow();
        var newPpf = refreshed
                .allFragments()
                .filter(f -> f.getType().isPath())
                .findFirst()
                .orElseThrow();
        assertNotSame(oldPpf, newPpf, "Computed project fragment should be refreshed");
        assertSame(
                sf,
                refreshed
                        .allFragments()
                        .filter(f -> f instanceof ContextFragments.StringFragment)
                        .findFirst()
                        .orElseThrow(),
                "Unrelated virtual fragments should be reused");
    }

    @Test
    void testCopyAndRefreshPreservesReadOnly() throws Exception {
        var pf = new ProjectFile(tempDir, "src/RefreshRO.java");
        pf.write("class RefreshRO {}");
        var ppf = new ContextFragments.ProjectPathFragment(pf, contextManager);

        var ctx = new Context(contextManager).addFragments(List.of(ppf));
        // Mark the fragment read-only
        ctx = ctx.setReadonly(ppf, true);
        assertTrue(ctx.isMarkedReadonly(ppf), "Precondition: fragment should be read-only");

        // Update and trigger refresh
        pf.write("class RefreshR0 { public static void main() {} }");
        var refreshed = ctx.copyAndRefresh(Set.of(pf));

        var newFrag = refreshed
                .allFragments()
                .filter(f -> f.getType().isPath())
                .findFirst()
                .orElseThrow();
        assertNotSame(ppf, newFrag, "Project fragment should be refreshed to a new instance");

        // Verify read-only state is preserved on the refreshed fragment
        assertTrue(refreshed.isMarkedReadonly(newFrag), "Read-only state must persist across refresh");
    }

    @Test
    void testUnionCombinesWithoutDuplicates() throws Exception {
        var pf = new ProjectFile(tempDir, "src/U.java");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "class U {}");
        var ppf1 = new ContextFragments.ProjectPathFragment(pf, contextManager);

        var s1 = new ContextFragments.StringFragment(contextManager, "Text-1", "D1", SyntaxConstants.SYNTAX_STYLE_NONE);
        var s2 = new ContextFragments.StringFragment(contextManager, "Text-2", "D2", SyntaxConstants.SYNTAX_STYLE_NONE);

        var ctx1 = new Context(contextManager).addFragments(List.of(ppf1)).addFragments(s1);
        var ctx2 = new Context(contextManager).addFragments(List.of(ppf1)).addFragments(s2);

        var merged = ctx1.union(ctx2);

        // One path (dedup), two unique virtuals
        assertEquals(1, merged.allFragments().filter(f -> f.getType().isPath()).count());
        assertEquals(2, merged.allFragments().filter(f -> !f.getType().isPath()).count());
    }

    @Test
    void testGetAllFragmentsInDisplayOrderIncludesHistoryFirst() {
        var s1 = new ContextFragments.StringFragment(contextManager, "T", "D", SyntaxConstants.SYNTAX_STYLE_NONE);
        var ctx = new Context(contextManager).addFragments(s1);

        // Add a history entry
        var msgs = List.<ChatMessage>of(UserMessage.from("User"), AiMessage.from("AI"));
        var log = new ContextFragments.TaskFragment(contextManager, msgs, "Log");
        var entry = new TaskEntry(1, log, null);
        ctx = ctx.addHistoryEntry(entry);

        var all = ctx.getAllFragmentsInDisplayOrder();
        assertFalse(all.isEmpty());
        assertTrue(all.getFirst() instanceof ContextFragments.HistoryFragment, "History should be first when present");

        // Then path and virtuals follow; exact order beyond first isn't asserted here
        long historyCount = all.stream()
                .filter(f -> f instanceof ContextFragments.HistoryFragment)
                .count();
        assertEquals(1L, historyCount, "Exactly one history fragment should be present");
    }

    @Test
    void testWorkspaceContentEqualsBySource() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Eq.java");
        Files.createDirectories(pf.absPath().getParent());
        pf.write("class Eq {}");

        var f1 = new ContextFragments.ProjectPathFragment(pf, contextManager);
        var f2 = new ContextFragments.ProjectPathFragment(pf, contextManager); // different instance, same source

        var c1 = new Context(contextManager).addFragments(List.of(f1));
        var c2 = new Context(contextManager).addFragments(List.of(f2));

        assertTrue(c1.workspaceContentEquals(c2), "Contexts with same sources should be equivalent");
    }

    @Test
    void testWithAddedClassesSkipsWorkspaceFiles() throws Exception {
        // Workspace contains CodeFragmentTarget's source; should skip adding CodeFragment for it
        var pfWorkspace = new ProjectFile(tempDir, "src/CodeFragmentTarget.java");
        Files.createDirectories(pfWorkspace.absPath().getParent());
        pfWorkspace.write("public class CodeFragmentTarget {}");
        var ppf = new ContextFragments.ProjectPathFragment(pfWorkspace, contextManager);

        // Another class not in workspace should be added as CodeFragment
        var ctx = new Context(contextManager).addFragments(List.of(ppf));
        var tools = new ai.brokk.tools.WorkspaceTools(ctx);
        tools.addClassesToWorkspace(List.of("com.example.CodeFragmentTarget", "com.example.AnotherClass"));
        ctx = tools.getContext();

        var virtuals = ctx.allFragments().filter(f -> !f.getType().isPath()).toList();
        assertEquals(1, virtuals.size(), "Only non-workspace class should be added");
        assertTrue(virtuals.get(0) instanceof ContextFragments.CodeFragment);
        var codeFrag = (ContextFragments.CodeFragment) virtuals.get(0);
        // Assert that the fragment is for the expected fully qualified name
        assertEquals("com.example.AnotherClass", codeFrag.getFullyQualifiedName());
    }

    @Test
    void testBuildRelatedSymbolsRespectsToIgnore() throws Exception {
        // Create test files
        var pf1 = new ProjectFile(tempDir, "src/Rel1.java");
        Files.createDirectories(pf1.absPath().getParent());
        pf1.write("class Rel1 {}");
        var pf2 = new ProjectFile(tempDir, "src/Rel2.java");
        pf2.write("class Rel2 {}");
        var pf3 = new ProjectFile(tempDir, "src/Rel3.java");
        pf3.write("class Rel3 {}");

        var orderedCandidates = List.of(pf1, pf2, pf3);

        // Create a Context subclass that returns a deterministic candidate list
        var ctx = new Context(contextManager) {
            @Override
            public List<ProjectFile> getMostRelevantFiles(int topK) {
                return orderedCandidates.stream().limit(topK).toList();
            }
        };

        // Ignore pf2 -> should not appear in results
        var ignored = Set.of(pf2);
        var result = ctx.buildRelatedSymbols(10, 20, ignored);

        assertFalse(result.containsKey(pf2), "Ignored file should not appear in related symbols");
        // pf1 and pf3 may or may not appear depending on whether analyzer returns blank summaries,
        // but pf2 must never appear
    }

    @Test
    void testBuildRelatedSymbolsLimitsToTopN() throws Exception {
        var pf1 = new ProjectFile(tempDir, "src/TopN1.java");
        Files.createDirectories(pf1.absPath().getParent());
        pf1.write("class TopN1 {}");
        var pf2 = new ProjectFile(tempDir, "src/TopN2.java");
        pf2.write("class TopN2 {}");
        var pf3 = new ProjectFile(tempDir, "src/TopN3.java");
        pf3.write("class TopN3 {}");
        var pf4 = new ProjectFile(tempDir, "src/TopN4.java");
        pf4.write("class TopN4 {}");

        var allCandidates = List.of(pf1, pf2, pf3, pf4);

        var ctx = new Context(contextManager) {
            @Override
            public List<ProjectFile> getMostRelevantFiles(int topK) {
                return allCandidates.stream().limit(topK).toList();
            }
        };

        // n=2 means only pf1 and pf2 are eligible, even though k=10 wants more
        var result = ctx.buildRelatedSymbols(10, 2, Set.of());

        // Result keys must be a subset of {pf1, pf2}
        for (var key : result.keySet()) {
            assertTrue(
                    key.equals(pf1) || key.equals(pf2),
                    "Only files within top-n should be eligible, but found: " + key);
        }
        assertFalse(result.containsKey(pf3), "pf3 is outside top-n and must not appear");
        assertFalse(result.containsKey(pf4), "pf4 is outside top-n and must not appear");
    }

    @Test
    void testBuildRelatedSymbolsReturnsExpectedFilesIndependentOfMapOrder() throws Exception {
        var pf1 = new ProjectFile(tempDir, "src/Order1.java");
        Files.createDirectories(pf1.absPath().getParent());
        pf1.write("class Order1 {}");
        var pf2 = new ProjectFile(tempDir, "src/Order2.java");
        pf2.write("class Order2 {}");
        var pf3 = new ProjectFile(tempDir, "src/Order3.java");
        pf3.write("class Order3 {}");

        var orderedCandidates = List.of(pf1, pf2, pf3);

        // Use a TestAnalyzer that returns non-blank summaries for all files
        var summaryAnalyzer = new TestAnalyzer(List.of(), Map.of()) {
            @Override
            public String summarizeSymbols(ProjectFile pf) {
                return "summary of " + pf;
            }
        };
        var summaryCm = new TestContextManager(tempDir, new NoOpConsoleIO(), summaryAnalyzer);

        var ctx = new Context(summaryCm) {
            @Override
            public List<ProjectFile> getMostRelevantFiles(int topK) {
                return orderedCandidates.stream().limit(topK).toList();
            }
        };

        var result = ctx.buildRelatedSymbols(10, 20, Set.of());

        // buildRelatedSymbols may run in parallel and does not guarantee map iteration order.
        var keys = result.keySet().stream()
                .sorted(Comparator.comparing(ProjectFile::toString))
                .toList();
        var expected = orderedCandidates.stream()
                .sorted(Comparator.comparing(ProjectFile::toString))
                .toList();
        assertEquals(expected, keys);
    }

    @Test
    void testBuildRelatedSymbolsKLimitsReturnedCount() throws Exception {
        var pf1 = new ProjectFile(tempDir, "src/KLimit1.java");
        Files.createDirectories(pf1.absPath().getParent());
        pf1.write("class KLimit1 {}");
        var pf2 = new ProjectFile(tempDir, "src/KLimit2.java");
        pf2.write("class KLimit2 {}");
        var pf3 = new ProjectFile(tempDir, "src/KLimit3.java");
        pf3.write("class KLimit3 {}");

        var orderedCandidates = List.of(pf1, pf2, pf3);

        var summaryAnalyzer = new TestAnalyzer(List.of(), Map.of()) {
            @Override
            public String summarizeSymbols(ProjectFile pf) {
                return "summary of " + pf;
            }
        };
        var summaryCm = new TestContextManager(tempDir, new NoOpConsoleIO(), summaryAnalyzer);

        var ctx = new Context(summaryCm) {
            @Override
            public List<ProjectFile> getMostRelevantFiles(int topK) {
                return orderedCandidates.stream().limit(topK).toList();
            }
        };

        // k=2 should return at most 2 files even though n=20 makes all 3 eligible
        var result = ctx.buildRelatedSymbols(2, 20, Set.of());
        assertTrue(result.size() <= 2, "Should return at most k files, got: " + result.size());
    }

    @Test
    void testIsFileContentEmpty_withEmptyContext() {
        var ctx = new Context(contextManager);
        assertTrue(ctx.isFileContentEmpty(), "Empty context should have no file content");
    }

    @Test
    void testIsFileContentEmpty_withOnlyStringFragments() {
        var ctx = new Context(contextManager);
        var stringFrag = new ContextFragments.StringFragment(
                contextManager, "some text", "description", SyntaxConstants.SYNTAX_STYLE_NONE);
        ctx = ctx.addFragments(stringFrag);
        // StringFragment.files() returns an empty set, so isFileContentEmpty should be true
        assertTrue(ctx.isFileContentEmpty(), "Context with only STRING fragments should report no file content");
    }

    @Test
    void testIsFileContentEmpty_withProjectPathFragment() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Test.java");
        Files.createDirectories(pf.absPath().getParent());
        pf.write("class Test {}");
        var ppf = new ContextFragments.ProjectPathFragment(pf, contextManager);

        var ctx = new Context(contextManager).addFragments(List.of(ppf));
        assertFalse(ctx.isFileContentEmpty(), "Context with PROJECT_PATH fragment should have file content");
    }

    @Test
    void testIsFileContentEmpty_withCodeFragment() {
        var cu = analyzer.getDefinitions("com.example.CodeFragmentTarget").stream()
                .findFirst()
                .orElseThrow();
        var codeFrag = new ContextFragments.CodeFragment(contextManager, cu);

        var ctx = new Context(contextManager).addFragments(codeFrag);
        assertFalse(ctx.isFileContentEmpty(), "Context with CODE fragment should have file content");
    }

    @Test
    void testIsFileContentEmpty_withTaskFragment() {
        var ctx = new Context(contextManager);
        List<ChatMessage> msgs = List.of(UserMessage.from("User"), AiMessage.from("AI"));
        var taskFrag = new ContextFragments.TaskFragment(contextManager, msgs, "task");
        ctx = ctx.addFragments(taskFrag);
        assertTrue(ctx.isFileContentEmpty(), "Context with only TASK fragments should report no file content");
    }

    @Test
    void testIsFileContentEmpty_withMixedFragments() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Mixed.java");
        Files.createDirectories(pf.absPath().getParent());
        pf.write("class Mixed {}");
        var ppf = new ContextFragments.ProjectPathFragment(pf, contextManager);

        var stringFrag =
                new ContextFragments.StringFragment(contextManager, "text", "desc", SyntaxConstants.SYNTAX_STYLE_NONE);

        var ctx = new Context(contextManager).addFragments(stringFrag).addFragments(List.of(ppf));
        assertFalse(
                ctx.isFileContentEmpty(), "Context with mixed fragments including file content should return false");
    }

    @Test
    void testAddFragmentsDoesNotUpdateContent() throws Exception {
        var pf = new ProjectFile(tempDir, "src/AddFrag.java");
        Files.createDirectories(pf.absPath().getParent());

        // 1. Initial content "v1"
        pf.write("v1");
        var p1 = new ContextFragments.ProjectPathFragment(pf, contextManager);
        // Ensure p1 reads and caches "v1"
        assertEquals("v1", p1.text().join());

        var ctx = new Context(contextManager).addFragments(List.of(p1));

        // 2. Change content to "v2" on disk
        pf.write("v2");

        // 3. Create new fragment p2 which sees "v2"
        var p2 = new ContextFragments.ProjectPathFragment(pf, contextManager);
        assertEquals("v2", p2.text().join());

        // 4. Add p2 to context.
        // Expectation: addFragments deduplicates by source and PRESERVES the existing fragment (p1).
        // Therefore, the content in context remains "v1".
        var ctx2 = ctx.addFragments(List.of(p2));
        var fragmentInContext = ctx2.allFragments()
                .filter(f -> f.getType().isPath())
                .findFirst()
                .orElseThrow();

        assertEquals(
                "v1",
                fragmentInContext.text().join(),
                "addFragments should preserve existing fragment content even if new fragment has newer content");
    }

    @Test
    void testAddFragmentsExpandsSupportingFragments() {
        ContextFragment supporting =
                new ContextFragments.StringFragment(contextManager, "supp", "supp", SyntaxConstants.SYNTAX_STYLE_NONE);
        ContextFragment primary =
                new ContextFragments.StringFragment(
                        contextManager, "primary", "primary", SyntaxConstants.SYNTAX_STYLE_NONE) {
                    @Override
                    public Set<ContextFragment> supportingFragments() {
                        return Set.of(supporting);
                    }
                };

        Context ctx = new Context(contextManager).addFragments(List.of(primary));

        assertTrue(ctx.allFragments().anyMatch(f -> f == primary), "Primary fragment should be present");
        assertTrue(
                ctx.allFragments().anyMatch(f -> f == supporting),
                "Supporting fragment should be expanded and present");
    }

    @Test
    void testCopyAndRefreshUpdatesContent() throws Exception {
        var pf = new ProjectFile(tempDir, "src/Refreshed.java");
        Files.createDirectories(pf.absPath().getParent());

        // 1. Initial content "v1"
        pf.write("v1");
        var p1 = new ContextFragments.ProjectPathFragment(pf, contextManager);
        // Ensure p1 reads and caches "v1"
        assertEquals("v1", p1.text().join());

        var ctx = new Context(contextManager).addFragments(List.of(p1));

        // 2. Change content to "v2" on disk
        pf.write("v2");

        // 3. Call copyAndRefresh
        var refreshedCtx = ctx.copyAndRefresh(Set.of(pf));
        var fragmentInContext = refreshedCtx
                .allFragments()
                .filter(f -> f.getType().isPath())
                .findFirst()
                .orElseThrow();

        // Expectation: copyAndRefresh should re-read from disk
        assertEquals("v2", fragmentInContext.text().join(), "copyAndRefresh should update fragment content from disk");

        // Also verify it's a new instance
        assertNotSame(p1, fragmentInContext);
    }
}
