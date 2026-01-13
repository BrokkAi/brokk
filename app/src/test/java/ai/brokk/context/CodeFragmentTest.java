package ai.brokk.context;

import static ai.brokk.testutil.AssertionHelperUtil.assertCodeContains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class CodeFragmentTest {
    @TempDir
    Path tempDir;

    private TestContextManager contextManager;
    private TestAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new TestAnalyzer();
        contextManager = new TestContextManager(tempDir, new TestConsoleIO(), analyzer);
    }

    @Test
    void testCodeFragmentIncludesAncestorSkeletons() throws Exception {
        ProjectFile baseFile = new ProjectFile(tempDir, "Base.java");
        ProjectFile subFile = new ProjectFile(tempDir, "Sub.java");

        CodeUnit baseCls = CodeUnit.cls(baseFile, "com.example", "Base");
        CodeUnit subCls = CodeUnit.cls(subFile, "com.example", "Sub");

        // Use addDeclaration instead of re-init to match TestAnalyzer patterns if possible,
        // but re-init is safe here.
        analyzer = new TestAnalyzer(List.of(baseCls, subCls), java.util.Map.of());
        contextManager = new TestContextManager(tempDir, null, analyzer);

        analyzer.setDirectAncestors(subCls, List.of(baseCls));
        analyzer.setSkeleton(baseCls, "class Base {}");
        analyzer.setSource(subCls, "class Sub extends Base {}");

        var fragment = new ContextFragments.CodeFragment(contextManager, subCls);
        String text = fragment.text().join();

        // Text should contain only the class source
        assertCodeContains(text, "class Sub extends Base {}");

        // Ancestors should be in supporting fragments
        var supporting = fragment.supportingFragments();
        assertEquals(1, supporting.size());

        var ancestorFragment =
                (ContextFragments.SummaryFragment) supporting.iterator().next();
        assertEquals("com.example.Base", ancestorFragment.getTargetIdentifier());
        assertEquals(ContextFragment.SummaryType.CODEUNIT_SKELETON, ancestorFragment.getSummaryType());

        // Sources of the fragment itself
        Set<CodeUnit> sources = fragment.sources().join();
        assertTrue(sources.contains(subCls));
        assertEquals(1, sources.size());

        Set<ProjectFile> files = fragment.files().join();
        assertTrue(files.contains(subFile));
        assertEquals(1, files.size());
    }

    @Test
    void testCodeFragmentForMethodDoesNotIncludeAncestors() {
        ProjectFile file = new ProjectFile(tempDir, "Example.java");
        CodeUnit cls = CodeUnit.cls(file, "com.example", "Example");
        CodeUnit method = CodeUnit.fn(file, "com.example", "Example.run");

        analyzer.addDeclaration(cls);
        analyzer.addDeclaration(method);
        analyzer.setDirectAncestors(cls, List.of(CodeUnit.cls(file, "com.example", "Parent")));
        analyzer.setSource(method, "void run() {}");

        var fragment = new ContextFragments.CodeFragment(contextManager, method);
        String text = fragment.text().join();

        assertCodeContains(text, "void run() {}");
        assertTrue(fragment.supportingFragments().isEmpty(), "Methods should not pull in class ancestors");

        assertEquals(Set.of(method), fragment.sources().join());
        assertEquals(Set.of(file), fragment.files().join());
    }

    @Test
    void testProjectPathFragmentIncludesAncestorSkeletons() {
        ProjectFile parentFile = new ProjectFile(tempDir, "Parent.java");
        ProjectFile childFile = new ProjectFile(tempDir, "Child.java");

        CodeUnit parentCls = CodeUnit.cls(parentFile, "com.example", "Parent");
        CodeUnit childCls = CodeUnit.cls(childFile, "com.example", "Child");

        analyzer.addDeclaration(parentCls);
        analyzer.addDeclaration(childCls);
        analyzer.setDirectAncestors(childCls, List.of(parentCls));

        var fragment = new ContextFragments.ProjectPathFragment(childFile, contextManager);

        var supporting = fragment.supportingFragments();
        assertEquals(1, supporting.size());

        var ancestorFragment =
                (ContextFragments.SummaryFragment) supporting.iterator().next();
        assertEquals("com.example.Parent", ancestorFragment.getTargetIdentifier());
        assertEquals(ContextFragment.SummaryType.CODEUNIT_SKELETON, ancestorFragment.getSummaryType());
    }
}
