package ai.brokk.context;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static ai.brokk.testutil.AssertionHelperUtil.assertCodeEquals;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment.SummaryType;
import ai.brokk.context.ContextFragments.SummaryFragment;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public class SummaryFragmentTest {

    @Test
    public void codeunitSkeletonFetchesTargetAndAncestors() throws IOException {
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

            assertCodeEquals(
                    """
    package (default package);

    class Child extends Base {
    }

    // Direct ancestors of Child: Base

    package (default package);

    public class Base {
    }
    """,
                    text);

            // sources() should include Child and its direct ancestor Base; no duplicates
            var sources = fragment.sources().join();
            var fqns = sources.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
            assertEquals(Set.of("Child", "Base"), fqns, "sources() should include Child and Base");
            assertEquals(sources.size(), fqns.size(), "sources() should not contain duplicates");

            // files() should include the single file containing both declarations
            ProjectFile expectedFile = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Test.java"))
                    .findFirst()
                    .orElseThrow();
            var files = fragment.files().join();
            assertEquals(Set.of(expectedFile), files, "files() should include only Test.java");
        }
    }

    @Test
    public void fileSkeletonFetchesTLDsAndTheirAncestors() throws IOException {
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

    public class Base {
    }
    """,
                    text);

            // sources() should include Child1, Child2, and Base; no duplicates
            var sources = fragment.sources().join();
            var fqns = sources.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
            assertEquals(Set.of("Child1", "Child2", "Base"), fqns, "sources() should include both children and Base");
            assertEquals(sources.size(), fqns.size(), "sources() should not contain duplicates");

            // files() should include Children.java and Base.java
            ProjectFile children = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Children.java"))
                    .findFirst()
                    .orElseThrow();
            ProjectFile base = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Base.java"))
                    .findFirst()
                    .orElseThrow();
            var files = fragment.files().join();
            assertEquals(Set.of(children, base), files, "files() should include Children.java and Base.java");
        }
    }

    @Test
    public void fileSkeletonWithMultipleTLDsAndMixedAncestors() throws IOException {
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

                    public class Base {
                    }

                    public interface I1 {
                    }

                    public interface I2 {
                    }
                    """,
                    text);

            // sources() should include the 3 TLDs and their ancestors (Base, I1, I2); no duplicates
            var sources = fragment.sources().join();
            var fqns = sources.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
            assertEquals(
                    Set.of("Child1", "Child2", "Standalone", "Base", "I1", "I2"),
                    fqns,
                    "sources() should include TLDs and their direct ancestors");
            assertEquals(sources.size(), fqns.size(), "sources() should not contain duplicates");

            // files() should include Multi.java and Base.java
            ProjectFile multi = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Multi.java"))
                    .findFirst()
                    .orElseThrow();
            ProjectFile base = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Base.java"))
                    .findFirst()
                    .orElseThrow();
            var files = fragment.files().join();
            assertEquals(Set.of(multi, base), files, "files() should include Multi.java and Base.java");
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

            assertCodeEquals(
                    """
    package p2;

    class Child extends Base {
    }

    // Direct ancestors of Child: Base

    package p1;

    public class Base {
    }
    """,
                    text);

            // sources() should include p2.Child and p1.Base; no duplicates
            var sources = fragment.sources().join();
            var fqns = sources.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
            assertEquals(
                    Set.of("p2.Child", "p1.Base"), fqns, "sources() should include Child and Base across packages");
            assertEquals(sources.size(), fqns.size(), "sources() should not contain duplicates");

            // files() should include Child.java and Base.java
            ProjectFile child = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Child.java"))
                    .findFirst()
                    .orElseThrow();
            ProjectFile base = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Base.java"))
                    .findFirst()
                    .orElseThrow();
            var files = fragment.files().join();
            assertEquals(Set.of(child, base), files, "files() should include Child.java and Base.java");
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
    public void fileSkeletonDeduplicatesSharedAncestorsInOutput() throws IOException {
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

            long count = Pattern.compile("\\bclass\\s+SharedBase\\b")
                    .matcher(text)
                    .results()
                    .count();
            assertEquals(1, count, "SharedBase skeleton should only appear once in the output text");

            // Verify the individual class skeletons are present (without assuming exact formatting)
            assertTrue(text.contains("class ChildA extends SharedBase"), "Should contain ChildA skeleton");
            assertTrue(text.contains("class ChildB extends SharedBase"), "Should contain ChildB skeleton");
            assertTrue(text.contains("public class SharedBase"), "Should contain SharedBase skeleton");

            // Verify sources() has no duplicates
            var sources = fragment.sources().join();
            var fqns = sources.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
            assertEquals(
                    Set.of("ChildA", "ChildB", "SharedBase"),
                    fqns,
                    "sources() should include both children and SharedBase");
            assertEquals(sources.size(), fqns.size(), "sources() should not contain duplicates");
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

            String combined = SummaryFragment.combinedText(java.util.List.of(sfA, sfB));

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
