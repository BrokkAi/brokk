package ai.brokk.context;

import static ai.brokk.testutil.AssertionHelperUtil.assertCodeContains;
import static ai.brokk.testutil.AssertionHelperUtil.assertCodeEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        analyzer = new TestAnalyzer(List.of(baseCls, subCls), Map.of());
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

        Set<ProjectFile> files = fragment.referencedFiles().join();
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
        assertEquals(Set.of(file), fragment.referencedFiles().join());
    }

    @Test
    void testCodeFragmentExcludesInnerClassAncestorSkeletons() {
        ProjectFile file = new ProjectFile(tempDir, "Outer.java");
        ProjectFile outerBaseFile = new ProjectFile(tempDir, "OuterBase.java");
        ProjectFile innerBaseFile = new ProjectFile(tempDir, "InnerBase.java");

        CodeUnit outer = CodeUnit.cls(file, "com.example", "Outer");
        CodeUnit inner = CodeUnit.cls(file, "com.example", "Outer.Inner");
        CodeUnit outerBase = CodeUnit.cls(outerBaseFile, "com.example", "OuterBase");
        CodeUnit innerBase = CodeUnit.cls(innerBaseFile, "com.example", "InnerBase");

        TestAnalyzer analyzer = new TestAnalyzer() {
            @Override
            public List<CodeUnit> getDirectChildren(CodeUnit cu) {
                if (Objects.equals(outer, cu)) {
                    return List.of(inner);
                }
                return super.getDirectChildren(cu);
            }
        };

        analyzer.addDeclaration(outer);
        analyzer.addDeclaration(inner);
        analyzer.addDeclaration(outerBase);
        analyzer.addDeclaration(innerBase);
        analyzer.setDirectAncestors(outer, List.of(outerBase));
        analyzer.setDirectAncestors(inner, List.of(innerBase));
        analyzer.setSource(outer, "class Outer extends OuterBase { class Inner extends InnerBase {} }");

        TestContextManager customManager = new TestContextManager(tempDir, new TestConsoleIO(), analyzer);
        var fragment = new ContextFragments.CodeFragment(customManager, "com.example.Outer");

        var supporting = fragment.supportingFragments();

        // Should contain OuterBase but NOT InnerBase
        boolean hasOuterBase = supporting.stream()
                .filter(f -> f instanceof ContextFragments.SummaryFragment)
                .map(f -> (ContextFragments.SummaryFragment) f)
                .anyMatch(sf -> sf.getTargetIdentifier().equals("com.example.OuterBase"));

        boolean hasInnerBase = supporting.stream()
                .filter(f -> f instanceof ContextFragments.SummaryFragment)
                .map(f -> (ContextFragments.SummaryFragment) f)
                .anyMatch(sf -> sf.getTargetIdentifier().equals("com.example.InnerBase"));

        assertTrue(hasOuterBase, "Should include ancestor of targeted class");
        assertFalse(hasInnerBase, "Should NOT include ancestor of non-targeted inner class");
    }

    @Test
    void testCodeFragmentIncludesImportStatements() {
        ProjectFile file = new ProjectFile(tempDir, "Example.java");
        CodeUnit cls = CodeUnit.cls(file, "com.example", "Example");

        analyzer.addDeclaration(cls);
        analyzer.setSource(cls, "class Example {}");
        List<String> imports = List.of("import java.util.List;", "import java.util.Map;");
        analyzer.setImportStatements(file, imports);
        // TestAnalyzer doesn't implement ImportAnalysisProvider by default, so it falls back to all imports
        analyzer.setRelevantImports(cls, new java.util.LinkedHashSet<>(imports));

        var fragment = new ContextFragments.CodeFragment(contextManager, cls);
        String text = fragment.text().join();

        assertCodeContains(
                """
                <imports>
                import java.util.List;
                import java.util.Map;
                </imports>

                <class file="Example.java">
                class Example {}
                </class>
                """,
                text);
    }

    @Test
    void testCodeFragmentFiltersIrrelevantImports() {
        ProjectFile file = new ProjectFile(tempDir, "Example.java");
        CodeUnit method = CodeUnit.fn(file, "com.example", "Example.run");

        analyzer.addDeclaration(method);
        analyzer.addDeclaration(CodeUnit.cls(file, "com.example", "Example"));
        analyzer.setSource(method, "void run(List list) {}");

        List<String> allImports = List.of("import java.util.List;", "import java.util.Map;");
        analyzer.setImportStatements(file, allImports);

        // Simulate ImportAnalysisProvider returning only relevant imports
        analyzer.setRelevantImports(method, Set.of("import java.util.List;"));

        var fragment = new ContextFragments.CodeFragment(contextManager, method);
        String text = fragment.text().join();

        assertCodeEquals(
                """
                <imports>
                import java.util.List;
                </imports>

                <methods class="com.example.Example.run" file="Example.java">
                void run(List list) {}
                </methods>
                """,
                text);
    }

    @Test
    void testCodeFragmentWithNoImportsHasNoLeadingWhitespace() {
        ProjectFile file = new ProjectFile(tempDir, "NoImports.java");
        CodeUnit cls = CodeUnit.cls(file, "com.example", "NoImports");

        analyzer.addDeclaration(cls);
        String code = "class NoImports {}";
        analyzer.setSource(cls, code);
        analyzer.setImportStatements(file, List.of());

        var fragment = new ContextFragments.CodeFragment(contextManager, cls);
        String text = fragment.text().join();

        assertCodeContains("""
                <class file="NoImports.java">
                class NoImports {}
                </class>
                """, text);
    }

    @Test
    void testCodeFragmentIncludesImportStatementsForMethod() {
        ProjectFile file = new ProjectFile(tempDir, "Example.java");
        CodeUnit method = CodeUnit.fn(file, "com.example", "Example.run");

        analyzer.addDeclaration(method);
        analyzer.addDeclaration(CodeUnit.cls(file, "com.example", "Example"));
        analyzer.setSource(method, "void run() {}");
        List<String> imports = List.of("import java.util.List;");
        analyzer.setImportStatements(file, imports);
        analyzer.setRelevantImports(method, Set.copyOf(imports));

        var fragment = new ContextFragments.CodeFragment(contextManager, method);
        String text = fragment.text().join();

        assertCodeEquals(
                """
                <imports>
                import java.util.List;
                </imports>

                <methods class="com.example.Example.run" file="Example.java">
                void run() {}
                </methods>
                """,
                text);
    }

    @Test
    void testCodeFragmentIncludesMultipleOverloads() {
        ProjectFile file = new ProjectFile(tempDir, "Overloads.java");
        CodeUnit v1 = new CodeUnit(file, CodeUnitType.FUNCTION, "com.example", "Overloads.verify", "(IProject,String)");
        CodeUnit v2 =
                new CodeUnit(file, CodeUnitType.FUNCTION, "com.example", "Overloads.verify", "(IProject,String,Map)");

        analyzer.addDeclaration(v1);
        analyzer.addDeclaration(v2);
        analyzer.setSource(v1, "public static void verify(IProject p, String s) {}");
        analyzer.setSource(v2, "public static void verify(IProject p, String s, Map m) {}");

        analyzer.setRelevantImports(v1, Set.of("import ai.brokk.project.IProject;"));
        analyzer.setRelevantImports(v2, Set.of("import ai.brokk.project.IProject;", "import java.util.Map;"));

        var fragment = new ContextFragments.CodeFragment(contextManager, "com.example.Overloads.verify");
        String text = fragment.text().join();

        assertEquals(Set.of(v1, v2), fragment.sources().join());
        assertEquals(Set.of(file), fragment.referencedFiles().join());

        assertCodeEquals(
                """
                <imports>
                import ai.brokk.project.IProject;
                import java.util.Map;
                </imports>

                <methods class="com.example.Overloads.verify" file="Overloads.java">
                public static void verify(IProject p, String s) {}
                </methods>

                <methods class="com.example.Overloads.verify" file="Overloads.java">
                public static void verify(IProject p, String s, Map m) {}
                </methods>
                """,
                text);
    }

    @Test
    void testCodeFragmentResolvesShortDescriptionFromFqmn() {
        ProjectFile file = new ProjectFile(tempDir, "Example.java");
        CodeUnit method = CodeUnit.fn(file, "com.example", "Example.run");

        analyzer.addDeclaration(method);
        analyzer.setSource(method, "void run() {}");

        // Pass only the FQMN (null eagerUnit) to simulate ReferenceAgent's behavior
        var fragment = new ContextFragments.CodeFragment(contextManager, "com.example.Example.run");

        // The dynamically resolved shortDescription should match the method's short name, not the FQMN
        String shortDesc = fragment.shortDescription().join();
        assertEquals(method.shortName(), shortDesc);
    }

    @Test
    void testProjectPathFragmentIncludesAncestorSkeletons() {
        ProjectFile parentFile = new ProjectFile(tempDir, "Parent.java");
        ProjectFile childFile = new ProjectFile(tempDir, "Child.java");

        CodeUnit parentCls = CodeUnit.cls(parentFile, "com.example", "Parent");
        CodeUnit childCls = CodeUnit.cls(childFile, "com.example", "Parent.Child");

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
