package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.usages.UsageHit;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestContextManager;
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
        project = InlineTestProjectCreator.code("class A {}\n", "src/main/java/A.java")
                .addFileContents("class B {}\n", "src/main/java/sub/B.java")
                .addFileContents("readme\n", "README.md")
                .build();
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

    @Test
    void fileFound_withDistinctRootPathObject() {
        // Create a new ContextManager where the project root is a distinct Path object (different instance, same value)
        Path distinctRoot = Path.of(projectRoot.toString());
        assertEquals(projectRoot, distinctRoot, "Paths should represent the same location");

        TestContextManager distinctCm = new TestContextManager(distinctRoot, new NoOpConsoleIO(), analyzer);

        Optional<ContextFragment> frag = AnalyzerUtil.selectFileFragment(distinctCm, "src/main/java/A.java", false);
        assertTrue(frag.isPresent(), "Should find file even if Root Path instances are distinct");
        assertInstanceOf(ContextFragments.ProjectPathFragment.class, frag.get());
        assertEquals(
                "src/main/java/A.java",
                ((ContextFragments.ProjectPathFragment) frag.get())
                        .file()
                        .getRelPath()
                        .toString()
                        .replace('\\', '/'));
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
        Set<ContextFragment> frags = AnalyzerUtil.selectClassFragment(analyzer, cm, "com.acme.Foo", false);
        assertFalse(frags.isEmpty(), "Expected fragment for exact class name");
        assertTrue(
                frags.iterator().next() instanceof ContextFragments.CodeFragment,
                "Expected CodeFragment when summarize=false");
    }

    @Test
    void class_fallback_summarize() {
        Set<ContextFragment> frags = AnalyzerUtil.selectClassFragment(analyzer, cm, "Foo", true);
        assertFalse(frags.isEmpty(), "Expected fragment via fallback search for class 'Foo'");
        ContextFragment first = frags.iterator().next();
        assertTrue(first instanceof ContextFragments.SummaryFragment, "Expected SummaryFragment");
        ContextFragments.SummaryFragment s = (ContextFragments.SummaryFragment) first;
        assertEquals(
                ContextFragment.SummaryType.CODEUNIT_SKELETON,
                s.getSummaryType(),
                "Summary type should be CODEUNIT_SKELETON");
    }

    @Test
    void class_overloaded_noSummarize() {
        // Simulating C++ template specializations where FQN is same but signature differs
        CodeUnit cls1 = new CodeUnit(pfA, CodeUnitType.CLASS, "com.acme", "Bar", "<T>");
        CodeUnit cls2 = new CodeUnit(pfA, CodeUnitType.CLASS, "com.acme", "Bar", "<int>");

        // TestAnalyzer constructor takes List<CodeUnit> for top-level declarations
        TestAnalyzer overloadAnalyzer = new TestAnalyzer(List.of(cls1, cls2), Map.of()); // No methods
        TestContextManager overloadCm = new TestContextManager(projectRoot, new NoOpConsoleIO(), overloadAnalyzer);

        Set<ContextFragment> frags =
                AnalyzerUtil.selectClassFragment(overloadAnalyzer, overloadCm, "com.acme.Bar", false);
        assertEquals(2, frags.size(), "Expected two fragments for overloaded/specialized class");
    }

    // (d) Method selection

    @Test
    void method_exact_noSummarize() {
        Set<ContextFragment> frags = AnalyzerUtil.selectMethodFragment(analyzer, cm, "com.acme.Foo.bar", false);
        assertFalse(frags.isEmpty(), "Expected fragment for exact method name");
        assertTrue(
                frags.iterator().next() instanceof ContextFragments.CodeFragment,
                "Expected CodeFragment when summarize=false");
    }

    @Test
    void method_fallback_summarize() {
        Set<ContextFragment> frags = AnalyzerUtil.selectMethodFragment(analyzer, cm, "bar", true);
        assertFalse(frags.isEmpty(), "Expected fragment via fallback search for method 'bar'");
        ContextFragment first = frags.iterator().next();
        assertTrue(first instanceof ContextFragments.SummaryFragment, "Expected SummaryFragment");
        ContextFragments.SummaryFragment s = (ContextFragments.SummaryFragment) first;
        assertEquals(
                ContextFragment.SummaryType.CODEUNIT_SKELETON,
                s.getSummaryType(),
                "Summary type should be CODEUNIT_SKELETON");
    }

    @Test
    void method_overloaded_noSummarize() {
        CodeUnit bar1 = new CodeUnit(pfA, CodeUnitType.FUNCTION, "com.acme.Foo", "bar", "(int)");
        CodeUnit bar2 = new CodeUnit(pfA, CodeUnitType.FUNCTION, "com.acme.Foo", "bar", "(double)");

        TestAnalyzer overloadAnalyzer = new TestAnalyzer(List.of(), Map.of("com.acme.Foo.bar", List.of(bar1, bar2)));
        TestContextManager overloadCm = new TestContextManager(projectRoot, new NoOpConsoleIO(), overloadAnalyzer);

        Set<ContextFragment> frags =
                AnalyzerUtil.selectMethodFragment(overloadAnalyzer, overloadCm, "com.acme.Foo.bar", false);
        assertEquals(2, frags.size(), "Expected two fragments for overloaded method");
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
    void sampleUsages_collapsesByOwnerAndUsesLowerPercentiles() {
        ProjectFile file = pfA;
        List<CodeUnit> owners = new java.util.ArrayList<>();
        Map<String, List<CodeUnit>> methods = new java.util.HashMap<>();
        TestAnalyzer sampleAnalyzer = new TestAnalyzer(owners, methods);
        List<UsageHit> hits = new java.util.ArrayList<>();

        for (int i = 1; i <= 10; i++) {
            CodeUnit owner = CodeUnit.cls(file, "pkg", "Owner" + i);
            CodeUnit method = CodeUnit.fn(file, "pkg.Owner" + i, "m" + i);
            owners.add(owner);
            sampleAnalyzer.setSource(method, "x".repeat(i * 10));
            hits.add(new UsageHit(file, i, i, i + 1, method, 0.5, "hit-" + i));
        }

        CodeUnit lowerConfidenceShort = CodeUnit.fn(file, "pkg.Owner1", "tiny");
        sampleAnalyzer.setSource(lowerConfidenceShort, "tiny");
        hits.addFirst(new UsageHit(file, 100, 100, 101, lowerConfidenceShort, 0.1, "tiny-hit"));

        List<AnalyzerUtil.CodeWithSource> sampled = AnalyzerUtil.sampleUsages(sampleAnalyzer, hits);

        assertEquals(3, sampled.size(), "Expected three sampled examples");
        assertEquals(
                List.of(10, 20, 30),
                sampled.stream().map(s -> s.code().length()).toList(),
                "10/20/30 percentile sampling should choose the lower decile examples after owner collapse");
        assertFalse(
                sampled.stream().anyMatch(s -> s.code().equals("tiny")),
                "Lower-confidence method from the same owner should not replace the owner's representative");
    }

    @Test
    void sampleUsages_percentileCollisionsAdvanceToDistinctExamples() {
        ProjectFile file = pfA;
        List<CodeUnit> owners = new java.util.ArrayList<>();
        TestAnalyzer sampleAnalyzer = new TestAnalyzer(owners, Map.of());
        List<UsageHit> hits = new java.util.ArrayList<>();

        for (int i = 1; i <= 4; i++) {
            CodeUnit owner = CodeUnit.cls(file, "pkg", "CollisionOwner" + i);
            CodeUnit method = CodeUnit.fn(file, "pkg.CollisionOwner" + i, "m" + i);
            owners.add(owner);
            sampleAnalyzer.setSource(method, "y".repeat(i * 7));
            hits.add(new UsageHit(file, i, i, i + 1, method, 1.0, "collision-" + i));
        }

        List<AnalyzerUtil.CodeWithSource> sampled = AnalyzerUtil.sampleUsages(sampleAnalyzer, hits);

        assertEquals(
                List.of(7, 14, 21),
                sampled.stream().map(s -> s.code().length()).toList(),
                "Colliding percentile indices should advance to the next unused examples");
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
