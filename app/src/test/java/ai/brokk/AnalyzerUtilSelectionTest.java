package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.usages.UsageHit;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestContextManager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AnalyzerUtilSelectionTest {

    private IProject project;
    private Path projectRoot;
    private ProjectFile pfA;
    private TestAnalyzer analyzer;
    private TestContextManager cm;

    @BeforeEach
    void setup() {
        try {
            project = InlineTestProjectCreator.code("class A {}\n", "src/main/java/A.java")
                    .addFileContents("class B {}\n", "src/main/java/sub/B.java")
                    .addFileContents("readme\n", "README.md")
                    .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        projectRoot = project.getRoot();

        pfA = new ProjectFile(projectRoot, "src/main/java/A.java");

        CodeUnit classFoo = CodeUnit.cls(pfA, "com.acme", "Foo");
        CodeUnit methodBar = CodeUnit.fn(pfA, "com.acme.Foo", "bar");

        analyzer = new TestAnalyzer(List.of(classFoo), Map.of("com.acme.Foo.bar", List.of(methodBar)));
        cm = new TestContextManager(projectRoot, new NoOpConsoleIO(), analyzer);
    }

    // (a) File selection

    @Test
    void fileFound_plain() {
        Optional<ContextFragment> frag = AnalyzerUtil.selectFileFragment(cm, "src/main/java/A.java", false);
        assertTrue(frag.isPresent(), "Expected ProjectPathFragment for existing file");
        assertTrue(
                frag.get() instanceof ContextFragments.ProjectPathFragment,
                "Expected a ProjectPathFragment when summarize=false");
    }

    @Test
    void fileFound_summarize() {
        Optional<ContextFragment> frag = AnalyzerUtil.selectFileFragment(cm, "src/main/java/A.java", true);
        assertTrue(frag.isPresent(), "Expected SummaryFragment for existing file when summarize=true");
        assertTrue(frag.get() instanceof ContextFragments.SummaryFragment, "Expected SummaryFragment");
        ContextFragments.SummaryFragment s = (ContextFragments.SummaryFragment) frag.get();
        assertEquals(
                ContextFragment.SummaryType.FILE_SKELETONS,
                s.getSummaryType(),
                "Summary type should be FILE_SKELETONS");
        assertEquals(
                "src/main/java/A.java",
                s.getTargetIdentifier().replace('\\', '/'),
                "Target identifier should be the relative path");
    }

    @Test
    void fileNotFound() {
        Optional<ContextFragment> frag = AnalyzerUtil.selectFileFragment(cm, "missing.txt", false);
        assertTrue(frag.isEmpty(), "Expected empty Optional for non-existent file");
    }

    // (b) Folder selection

    @Test
    void folder_noSubfolders_noSummarize() {
        Set<ContextFragment> fragments = AnalyzerUtil.selectFolderFragments(cm, "src/main/java", false, false);
        assertEquals(1, fragments.size(), "Expected only the direct child file A.java");
        assertTrue(
                fragments.stream().allMatch(f -> f instanceof ContextFragments.ProjectPathFragment),
                "All fragments should be ProjectPathFragment when summarize=false");
    }

    @Test
    void folder_withSubfolders_summarize() {
        Set<ContextFragment> fragments = AnalyzerUtil.selectFolderFragments(cm, "src/main/java", true, true);
        assertEquals(2, fragments.size(), "Expected A.java and sub/B.java when including subfolders");
        assertTrue(
                fragments.stream().allMatch(f -> f instanceof ContextFragments.SummaryFragment),
                "All fragments should be SummaryFragment when summarize=true");
        fragments.forEach(f -> {
            ContextFragments.SummaryFragment s = (ContextFragments.SummaryFragment) f;
            assertEquals(
                    ContextFragment.SummaryType.FILE_SKELETONS,
                    s.getSummaryType(),
                    "Summary type should be FILE_SKELETONS");
        });
    }

    // (c) Class selection

    @Test
    void class_exact_noSummarize() {
        Optional<ContextFragment> frag = AnalyzerUtil.selectClassFragment(analyzer, cm, "com.acme.Foo", false);
        assertTrue(frag.isPresent(), "Expected fragment for exact class name");
        assertTrue(frag.get() instanceof ContextFragments.CodeFragment, "Expected CodeFragment when summarize=false");
    }

    @Test
    void class_fallback_summarize() {
        Optional<ContextFragment> frag = AnalyzerUtil.selectClassFragment(analyzer, cm, "Foo", true);
        assertTrue(frag.isPresent(), "Expected fragment via fallback search for class 'Foo'");
        assertTrue(frag.get() instanceof ContextFragments.SummaryFragment, "Expected SummaryFragment");
        ContextFragments.SummaryFragment s = (ContextFragments.SummaryFragment) frag.get();
        assertEquals(
                ContextFragment.SummaryType.CODEUNIT_SKELETON,
                s.getSummaryType(),
                "Summary type should be CODEUNIT_SKELETON");
    }

    // (d) Method selection

    @Test
    void method_exact_noSummarize() {
        Optional<ContextFragment> frag = AnalyzerUtil.selectMethodFragment(analyzer, cm, "com.acme.Foo.bar", false);
        assertTrue(frag.isPresent(), "Expected fragment for exact method name");
        assertTrue(frag.get() instanceof ContextFragments.CodeFragment, "Expected CodeFragment when summarize=false");
    }

    @Test
    void method_fallback_summarize() {
        Optional<ContextFragment> frag = AnalyzerUtil.selectMethodFragment(analyzer, cm, "bar", true);
        assertTrue(frag.isPresent(), "Expected fragment via fallback search for method 'bar'");
        assertTrue(frag.get() instanceof ContextFragments.SummaryFragment, "Expected SummaryFragment");
        ContextFragments.SummaryFragment s = (ContextFragments.SummaryFragment) frag.get();
        assertEquals(
                ContextFragment.SummaryType.CODEUNIT_SKELETON,
                s.getSummaryType(),
                "Summary type should be CODEUNIT_SKELETON");
    }

    // (e) Usage selection

    @Test
    void usages_exactMethod_summarize() {
        Optional<ContextFragment> frag = AnalyzerUtil.selectUsageFragment(analyzer, cm, "com.acme.Foo.bar", true);
        assertTrue(frag.isPresent(), "Expected a fragment for usage selection on exact method");
        assertTrue(
                frag.get() instanceof ContextFragments.UsageFragment,
                "Summarize=true for a method should return a UsageFragment");
        ContextFragments.UsageFragment uf = (ContextFragments.UsageFragment) frag.get();
        assertEquals("com.acme.Foo.bar", uf.targetIdentifier(), "Target identifier should match input");
        assertTrue(uf.includeTestFiles(), "includeTestFiles should be true");
        assertEquals(ContextFragments.UsageMode.FULL, uf.mode(), "Default mode should be FULL");
    }

    @Test
    void usages_sampleMode() {
        Optional<ContextFragment> frag = AnalyzerUtil.selectUsageFragment(
                analyzer, cm, "com.acme.Foo.bar", false, ContextFragments.UsageMode.SAMPLE);
        assertTrue(frag.isPresent(), "Expected a fragment for usage selection with SAMPLE mode");
        ContextFragments.UsageFragment uf = (ContextFragments.UsageFragment) frag.get();
        assertEquals(ContextFragments.UsageMode.SAMPLE, uf.mode(), "Mode should be SAMPLE");
        assertFalse(uf.includeTestFiles(), "includeTestFiles should be false");
    }

    @Test
    void usages_classOrUnknown() {
        Optional<ContextFragment> frag = AnalyzerUtil.selectUsageFragment(analyzer, cm, "com.acme.Foo", false);
        assertTrue(frag.isPresent(), "Expected a fragment for class/unknown usage selection");
        assertTrue(
                frag.get() instanceof ContextFragments.UsageFragment,
                "Summarize=true for non-method should return a UsageFragment");
        ContextFragments.UsageFragment u = (ContextFragments.UsageFragment) frag.get();
        assertEquals("com.acme.Foo", u.targetIdentifier(), "Target identifier should be the input/class FQN");
        assertFalse(u.includeTestFiles(), "includeTestFiles should be false as passed");
    }

    // (f) Edge cases: empty/blank input

    @Test
    void emptyInput_returnsEmpty() {
        assertTrue(AnalyzerUtil.selectFileFragment(cm, "   ", false).isEmpty(), "File selection should be empty");
        assertTrue(
                AnalyzerUtil.selectFolderFragments(cm, "   ", false, false).isEmpty(),
                "Folder selection should be empty");
        assertTrue(
                AnalyzerUtil.selectClassFragment(analyzer, cm, "   ", false).isEmpty(),
                "Class selection should be empty");
        assertTrue(
                AnalyzerUtil.selectMethodFragment(analyzer, cm, "   ", false).isEmpty(),
                "Method selection should be empty");
        assertTrue(
                AnalyzerUtil.selectUsageFragment(analyzer, cm, "   ", false).isEmpty(),
                "Usage selection should be empty");
    }

    // (h) Edge cases: folder input normalization

    @Test
    void folderInput_isNormalized() {
        Set<ContextFragment> a = AnalyzerUtil.selectFolderFragments(cm, "/src/main/java/", true, false);
        Set<ContextFragment> b = AnalyzerUtil.selectFolderFragments(cm, "src\\main\\java\\", true, false);
        Set<ContextFragment> c = AnalyzerUtil.selectFolderFragments(cm, "\\src\\main\\java", true, false);

        assertEquals(2, a.size(), "Expected two files under src/main/java with subfolders");
        assertEquals(2, b.size(), "Expected two files under src/main/java with subfolders");
        assertEquals(2, c.size(), "Expected two files under src/main/java with subfolders");

        assertTrue(a.stream().allMatch(f -> f instanceof ContextFragments.ProjectPathFragment));
        assertTrue(b.stream().allMatch(f -> f instanceof ContextFragments.ProjectPathFragment));
        assertTrue(c.stream().allMatch(f -> f instanceof ContextFragments.ProjectPathFragment));
    }

    // (i) Edge cases: no matches for classes/methods/usages

    @Test
    void noMatch_returnsEmptyForClassesAndMethods() {
        assertTrue(
                AnalyzerUtil.selectClassFragment(analyzer, cm, "com.acme.DoesNotExist", false)
                        .isEmpty(),
                "No matching class should return empty");
        assertTrue(
                AnalyzerUtil.selectMethodFragment(analyzer, cm, "com.acme.DoesNotExist.method", false)
                        .isEmpty(),
                "No matching method should return empty");
    }

    @Test
    void usage_noMatch_returnsUsageFragmentWithRawInput() {
        var frag = AnalyzerUtil.selectUsageFragment(analyzer, cm, "noSuchSymbol", false);
        assertTrue(frag.isPresent(), "Expected UsageFragment to be returned even when no symbol was found");
        assertTrue(frag.get() instanceof ContextFragments.UsageFragment, "Expected UsageFragment");
        ContextFragments.UsageFragment u = (ContextFragments.UsageFragment) frag.get();
        assertEquals("noSuchSymbol", u.targetIdentifier(), "Target identifier should be the raw input");
    }

    @Test
    void testSampleUsageHitsSelection() {
        var hits = new HashSet<UsageHit>();
        for (int i = 0; i < 5; i++) {
            var enclosing = CodeUnit.fn(pfA, "com.acme.Foo", "method" + i);
            hits.add(new UsageHit(pfA, i + 1, i * 10, i * 10 + 5, enclosing, 1.0, "snippet" + i));
        }
        // All 5 hits should be distinct
        assertEquals(5, hits.size(), "All hits should be distinct when using different offsets");

        // Two hits with same enclosing but different offsets should NOT be equal
        var enclosing = CodeUnit.fn(pfA, "com.acme.Foo", "sharedMethod");
        var hit1 = new UsageHit(pfA, 1, 0, 5, enclosing, 1.0, "a");
        var hit2 = new UsageHit(pfA, 2, 10, 15, enclosing, 1.0, "b");
        assertNotEquals(hit1, hit2, "Hits with different offsets should not be equal even with same enclosing");

        // Two hits with same file, offsets, and enclosing should be equal
        var hit3 = new UsageHit(pfA, 1, 0, 5, enclosing, 0.5, "c");
        assertEquals(
                hit1,
                hit3,
                "Hits with same file, offsets, and enclosing should be equal regardless of confidence/snippet");
    }
}
