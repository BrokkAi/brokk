package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.project.IProject;
import java.io.IOException;
import java.nio.file.Path;
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
            project = InlineTestProjectCreator
                    .code("class A {}\n", "src/main/java/A.java")
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
                frag.get() instanceof ContextFragment.ProjectPathFragment,
                "Expected a ProjectPathFragment when summarize=false");
    }

    @Test
    void fileFound_summarize() {
        Optional<ContextFragment> frag = AnalyzerUtil.selectFileFragment(cm, "src/main/java/A.java", true);
        assertTrue(frag.isPresent(), "Expected SummaryFragment for existing file when summarize=true");
        assertTrue(frag.get() instanceof ContextFragment.SummaryFragment, "Expected SummaryFragment");
        ContextFragment.SummaryFragment s = (ContextFragment.SummaryFragment) frag.get();
        assertEquals(
                ContextFragment.SummaryType.FILE_SKELETONS, s.getSummaryType(), "Summary type should be FILE_SKELETONS");
        assertEquals(
                "src/main/java/A.java",
                s.getTargetIdentifier(),
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
        Set<ContextFragment> fragments =
                AnalyzerUtil.selectFolderFragments(cm, "src/main/java", false, false);
        assertEquals(1, fragments.size(), "Expected only the direct child file A.java");
        assertTrue(
                fragments.stream().allMatch(f -> f instanceof ContextFragment.ProjectPathFragment),
                "All fragments should be ProjectPathFragment when summarize=false");
    }

    @Test
    void folder_withSubfolders_summarize() {
        Set<ContextFragment> fragments =
                AnalyzerUtil.selectFolderFragments(cm, "src/main/java", true, true);
        assertEquals(2, fragments.size(), "Expected A.java and sub/B.java when including subfolders");
        assertTrue(
                fragments.stream().allMatch(f -> f instanceof ContextFragment.SummaryFragment),
                "All fragments should be SummaryFragment when summarize=true");
        fragments.forEach(f -> {
            ContextFragment.SummaryFragment s = (ContextFragment.SummaryFragment) f;
            assertEquals(
                    ContextFragment.SummaryType.FILE_SKELETONS, s.getSummaryType(), "Summary type should be FILE_SKELETONS");
        });
    }

    // (c) Class selection

    @Test
    void class_exact_noSummarize() {
        Optional<ContextFragment> frag = AnalyzerUtil.selectClassFragment(analyzer, cm, "com.acme.Foo", false);
        assertTrue(frag.isPresent(), "Expected fragment for exact class name");
        assertTrue(
                frag.get() instanceof ContextFragment.CodeFragment, "Expected CodeFragment when summarize=false");
    }

    @Test
    void class_fallback_summarize() {
        Optional<ContextFragment> frag = AnalyzerUtil.selectClassFragment(analyzer, cm, "Foo", true);
        assertTrue(frag.isPresent(), "Expected fragment via fallback search for class 'Foo'");
        assertTrue(frag.get() instanceof ContextFragment.SummaryFragment, "Expected SummaryFragment");
        ContextFragment.SummaryFragment s = (ContextFragment.SummaryFragment) frag.get();
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
        assertTrue(
                frag.get() instanceof ContextFragment.CodeFragment, "Expected CodeFragment when summarize=false");
    }

    @Test
    void method_fallback_summarize() {
        Optional<ContextFragment> frag = AnalyzerUtil.selectMethodFragment(analyzer, cm, "bar", true);
        assertTrue(frag.isPresent(), "Expected fragment via fallback search for method 'bar'");
        assertTrue(frag.get() instanceof ContextFragment.SummaryFragment, "Expected SummaryFragment");
        ContextFragment.SummaryFragment s = (ContextFragment.SummaryFragment) frag.get();
        assertEquals(
                ContextFragment.SummaryType.CODEUNIT_SKELETON,
                s.getSummaryType(),
                "Summary type should be CODEUNIT_SKELETON");
    }

    // (e) Usage selection

    @Test
    void usages_exactMethod_summarize() {
        Optional<ContextFragment> frag =
                AnalyzerUtil.selectUsageFragment(analyzer, cm, "com.acme.Foo.bar", true, true);
        assertTrue(frag.isPresent(), "Expected a fragment for usage selection on exact method");
        assertTrue(
                frag.get() instanceof ContextFragment.CallGraphFragment,
                "Summarize=true for a method should return a CallGraphFragment");
        ContextFragment.CallGraphFragment cg = (ContextFragment.CallGraphFragment) frag.get();
        assertEquals("com.acme.Foo.bar", cg.getMethodName(), "Method name should match input");
        assertEquals(1, cg.getDepth(), "Depth should be 1");
        assertFalse(cg.isCalleeGraph(), "Expected caller graph (isCalleeGraph=false)");
    }

    @Test
    void usages_classOrUnknown() {
        Optional<ContextFragment> frag =
                AnalyzerUtil.selectUsageFragment(analyzer, cm, "com.acme.Foo", false, true);
        assertTrue(frag.isPresent(), "Expected a fragment for class/unknown usage selection");
        assertTrue(
                frag.get() instanceof ContextFragment.UsageFragment,
                "Summarize=true for non-method should return a UsageFragment");
        ContextFragment.UsageFragment u = (ContextFragment.UsageFragment) frag.get();
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
                AnalyzerUtil.selectUsageFragment(analyzer, cm, "   ", false, false).isEmpty(),
                "Usage selection should be empty");
    }

    // (g) Edge cases: analyzer == null

    @Test
    void nullAnalyzer_returnsEmptyForClassMethodUsage() {
        IAnalyzer nullAnalyzer = null;
        assertTrue(
                AnalyzerUtil.selectClassFragment(nullAnalyzer, cm, "Foo", false).isEmpty(),
                "Class selection should be empty when analyzer is null");
        assertTrue(
                AnalyzerUtil.selectMethodFragment(nullAnalyzer, cm, "Foo.bar", false).isEmpty(),
                "Method selection should be empty when analyzer is null");
        assertTrue(
                AnalyzerUtil.selectUsageFragment(nullAnalyzer, cm, "Foo", false, false).isEmpty(),
                "Usage selection should be empty when analyzer is null");
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

        assertTrue(a.stream().allMatch(f -> f instanceof ContextFragment.ProjectPathFragment));
        assertTrue(b.stream().allMatch(f -> f instanceof ContextFragment.ProjectPathFragment));
        assertTrue(c.stream().allMatch(f -> f instanceof ContextFragment.ProjectPathFragment));
    }

    // (i) Edge cases: no matches for classes/methods/usages

    @Test
    void noMatch_returnsEmptyForClassesAndMethods() {
        assertTrue(
                AnalyzerUtil.selectClassFragment(analyzer, cm, "com.acme.DoesNotExist", false).isEmpty(),
                "No matching class should return empty");
        assertTrue(
                AnalyzerUtil.selectMethodFragment(analyzer, cm, "com.acme.DoesNotExist.method", false).isEmpty(),
                "No matching method should return empty");
    }

    @Test
    void usage_noMatch_returnsUsageFragmentWithRawInput() {
        var frag = AnalyzerUtil.selectUsageFragment(analyzer, cm, "noSuchSymbol", false, false);
        assertTrue(frag.isPresent(), "Expected UsageFragment to be returned even when no symbol was found");
        assertTrue(frag.get() instanceof ContextFragment.UsageFragment, "Expected UsageFragment");
        ContextFragment.UsageFragment u = (ContextFragment.UsageFragment) frag.get();
        assertEquals("noSuchSymbol", u.targetIdentifier(), "Target identifier should be the raw input");
    }
}
