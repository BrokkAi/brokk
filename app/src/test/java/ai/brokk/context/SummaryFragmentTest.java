package ai.brokk.context;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static ai.brokk.testutil.AssertionHelperUtil.assertCodeEquals;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment.SummaryType;
import ai.brokk.context.ContextFragments.SummaryFragment;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public class SummaryFragmentTest {

    @Test
    public void codeunitSkeletonFetchesOnlyTarget() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
    public class Base {}
    class Child extends Base {}
    """, "Test.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            var fragment = new SummaryFragment(cm, "Child", SummaryType.CODEUNIT_SKELETON);
            String text = fragment.text().join();

            assertCodeEquals("""
    package (default package);

    class Child extends Base {
    }
    """, text);

            // sources() should include only Child
            var sources = fragment.sources().join();
            var fqns = sources.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
            assertEquals(Set.of("Child"), fqns, "sources() should include only Child");

            // files() should include Test.java
            ProjectFile expectedFile = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Test.java"))
                    .findFirst()
                    .orElseThrow();
            var files = fragment.files().join();
            assertEquals(Set.of(expectedFile), files, "files() should include Test.java");
        }
    }

    @Test
    public void testSupportingFragmentsForClass() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
    public class Base {}
    class Child extends Base {}
    """, "Test.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            var fragment = new SummaryFragment(cm, "Child", SummaryType.CODEUNIT_SKELETON);
            var supporting = fragment.supportingFragments();

            var fqns = supporting.stream()
                    .filter(f -> f instanceof SummaryFragment)
                    .map(f -> ((SummaryFragment) f).getTargetIdentifier())
                    .collect(Collectors.toSet());

            assertEquals(Set.of("Base"), fqns, "supportingFragments() should return ancestor fragments");
        }
    }

    @Test
    public void fileSkeletonFetchesOnlyTLDs() throws IOException {
        var builder = InlineTestProjectCreator.code("""
    public class Base {}
    """, "Base.java");
        try (var testProject = builder.addFileContents(
                        """
    class Child1 extends Base {}
    class Child2 extends Base {}
    """, "Children.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            ProjectFile childrenFile = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Children.java"))
                    .findFirst()
                    .orElseThrow();

            var fragment = new SummaryFragment(cm, childrenFile.toString(), SummaryType.FILE_SKELETONS);
            String text = fragment.text().join();

            assertCodeEquals(
                    """
    package (default package);

    class Child1 extends Base {
    }

    class Child2 extends Base {
    }
    """,
                    text);

            // sources() should include Child1 and Child2
            var sources = fragment.sources().join();
            var fqns = sources.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
            assertEquals(Set.of("Child1", "Child2"), fqns, "sources() should include both children");

            // files() should include only Children.java
            ProjectFile children = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Children.java"))
                    .findFirst()
                    .orElseThrow();
            var files = fragment.files().join();
            assertEquals(Set.of(children), files, "files() should include Children.java");
        }
    }

    @Test
    public void fileSkeletonWithMultipleTLDs() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
    public interface I1 {}
    public interface I2 {}
    public class Base {}
    """,
                "Base.java");
        try (var testProject = builder.addFileContents(
                        """
    class Child1 extends Base implements I1 {}
    class Child2 extends Base implements I2 {}
    class Standalone {}
    """,
                        "Multi.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            ProjectFile multiFile = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Multi.java"))
                    .findFirst()
                    .orElseThrow();

            var fragment = new SummaryFragment(cm, multiFile.toString(), SummaryType.FILE_SKELETONS);
            String text = fragment.text().join();

            assertCodeEquals(
                    """
                    package (default package);

                    class Child1 extends Base implements I1 {
                    }

                    class Child2 extends Base implements I2 {
                    }

                    class Standalone {
                    }
                    """,
                    text);

            // sources() should include the 3 TLDs
            var sources = fragment.sources().join();
            var fqns = sources.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
            assertEquals(Set.of("Child1", "Child2", "Standalone"), fqns, "sources() should include only TLDs");

            // files() should include only Multi.java
            ProjectFile multi = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Multi.java"))
                    .findFirst()
                    .orElseThrow();
            var files = fragment.files().join();
            assertEquals(Set.of(multi), files, "files() should include only Multi.java");
        }
    }

    @Test
    public void outputFormattedByPackage() throws IOException {
        var builder = InlineTestProjectCreator.code("""
    package p1;
    public class Base {}
    """, "Base.java");
        try (var testProject = builder.addFileContents(
                        """
    package p2;
    import p1.Base;
    class Child extends Base {}
    """, "Child.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            var fragment = new SummaryFragment(cm, "p2.Child", SummaryType.CODEUNIT_SKELETON);
            String text = fragment.text().join();

            assertCodeEquals("""
    package p2;

    class Child extends Base {
    }
    """, text);

            // sources() should include p2.Child
            var sources = fragment.sources().join();
            var fqns = sources.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
            assertEquals(Set.of("p2.Child"), fqns, "sources() should include Child");

            // files() should include Child.java
            ProjectFile child = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Child.java"))
                    .findFirst()
                    .orElseThrow();
            var files = fragment.files().join();
            assertEquals(Set.of(child), files, "files() should include Child.java");
        }
    }

    @Test
    public void nonClassCUSkeletonRendersWithoutAncestorSection() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
    public class MyClass {
    public void myMethod() {}
    }
    """, "Test.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            var fragment = new SummaryFragment(cm, "MyClass.myMethod", SummaryType.CODEUNIT_SKELETON);
            String text = fragment.text().join();

            assertCodeEquals("""
    package (default package);

    public void myMethod()
    """, text);

            // sources() should include only the method; no ancestors for non-class targets
            var sources = fragment.sources().join();
            var fqns = sources.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
            assertEquals(Set.of("MyClass.myMethod"), fqns, "sources() should include only the method");
            assertEquals(sources.size(), fqns.size(), "sources() should not contain duplicates");

            // files() should include only Test.java
            ProjectFile expectedFile = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Test.java"))
                    .findFirst()
                    .orElseThrow();
            var files = fragment.files().join();
            assertEquals(Set.of(expectedFile), files, "files() should include only Test.java");
        }
    }

    @Test
    public void fileSkeletonDoesNotIncludeSharedAncestors() throws IOException {
        var builder = InlineTestProjectCreator.code("""
    public class SharedBase {}
    """, "Base.java");
        try (var testProject = builder.addFileContents(
                        """
    class ChildA extends SharedBase {}
    class ChildB extends SharedBase {}
    """,
                        "Children.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            ProjectFile childrenFile = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Children.java"))
                    .findFirst()
                    .orElseThrow();

            var fragment = new SummaryFragment(cm, childrenFile.toString(), SummaryType.FILE_SKELETONS);
            String text = fragment.text().join();

            // Verify the individual class skeletons are present
            assertTrue(text.contains("class ChildA extends SharedBase"), "Should contain ChildA skeleton");
            assertTrue(text.contains("class ChildB extends SharedBase"), "Should contain ChildB skeleton");
            assertFalse(text.contains("public class SharedBase"), "Should NOT contain SharedBase skeleton");

            // Verify sources()
            var sources = fragment.sources().join();
            var fqns = sources.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
            assertEquals(Set.of("ChildA", "ChildB"), fqns, "sources() should include both children");
        }
    }

    @Test
    public void supportingFragments_excludesInnerClassAncestors() throws IOException {
        try (var testProject = InlineTestProjectCreator.code("", "Outer.java").build()) {
            ProjectFile outerFile = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Outer.java"))
                    .findFirst()
                    .orElseThrow();
            ProjectFile outerBaseFile = new ProjectFile(testProject.getRoot(), "OuterBase.java");
            ProjectFile innerBaseFile = new ProjectFile(testProject.getRoot(), "InnerBase.java");

            CodeUnit outer = CodeUnit.cls(outerFile, "com.example", "Outer");
            CodeUnit inner = CodeUnit.cls(outerFile, "com.example", "Outer.Inner");
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
            analyzer.setDirectAncestors(outer, List.of(outerBase));
            analyzer.setDirectAncestors(inner, List.of(innerBase));

            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);
            var fragment = new SummaryFragment(cm, "com.example.Outer", SummaryType.CODEUNIT_SKELETON);

            var supporting = fragment.supportingFragments();
            var targetIds = supporting.stream()
                    .filter(f -> f instanceof SummaryFragment)
                    .map(f -> ((SummaryFragment) f).getTargetIdentifier())
                    .collect(Collectors.toSet());

            assertTrue(targetIds.contains("com.example.OuterBase"), "Should include OuterBase");
            assertFalse(targetIds.contains("com.example.InnerBase"), "Should NOT include InnerBase");
        }
    }

    @Test
    public void combinedTextDeduplicatesSharedAncestors() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
    public class Base {}
    class ChildA extends Base {}
    class ChildB extends Base {}
    """,
                        "Test.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            var sfA = new SummaryFragment(cm, "ChildA", SummaryType.CODEUNIT_SKELETON);
            var sfB = new SummaryFragment(cm, "ChildB", SummaryType.CODEUNIT_SKELETON);

            List<SummaryFragment> fragments = new ArrayList<>();
            fragments.add(sfA);
            fragments.addAll(sfA.supportingFragments().stream()
                    .map(f -> (SummaryFragment) f)
                    .toList());
            fragments.add(sfB);
            fragments.addAll(sfB.supportingFragments().stream()
                    .map(f -> (SummaryFragment) f)
                    .toList());

            String combined = SummaryFragment.combinedText(fragments);

            // Combined text uses "by package" formatting, so it shouldn't have redundant ancestor headers
            assertFalse(combined.contains("// Direct ancestors"), "Combined text should use flat package formatting");

            // Each child should be present
            assertTrue(combined.contains("class ChildA extends Base"), "Should contain ChildA");
            assertTrue(combined.contains("class ChildB extends Base"), "Should contain ChildB");

            // The superclass "Base" should appear EXACTLY once in the whole combined output
            int count = 0;
            int index = 0;
            while ((index = combined.indexOf("public class Base", index)) != -1) {
                count++;
                index += "public class Base".length();
            }
            assertEquals(1, count, "Shared ancestor 'Base' should only appear once in combined output");
        }
    }
}
