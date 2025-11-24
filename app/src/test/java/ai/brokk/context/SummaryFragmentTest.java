package ai.brokk.context;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static ai.brokk.testutil.AssertionHelperUtil.assertCodeEquals;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment.SummaryFragment;
import ai.brokk.context.ContextFragment.SummaryType;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.io.IOException;
import org.junit.jupiter.api.Test;

public class SummaryFragmentTest {

    @Test
    public void codeunitSkeletonFetchesTargetAndAncestors() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                public class Base {}
                class Child extends Base {}
                """,
                        "Test.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            var fragment = new SummaryFragment(cm, "Child", SummaryType.CODEUNIT_SKELETON);
            String text = fragment.text();

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
        }
    }

    @Test
    public void fileSkeletonFetchesTLDsAndTheirAncestors() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
                public class Base {}
                """, "Base.java");
        try (var testProject = builder.addFileContents(
                        """
                class Child1 extends Base {}
                class Child2 extends Base {}
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
            String text = fragment.text();

            assertCodeEquals(
                    """
                    package (default package);

                    class Child2 extends Base {
                    }

                    class Child1 extends Base {
                    }

                    public class Base {
                    }
                    """,
                    text);
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
            String text = fragment.text();

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
        }
    }

    @Test
    public void outputFormattedByPackage() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
                package p1;
                public class Base {}
                """, "Base.java");
        try (var testProject = builder.addFileContents(
                        """
                package p2;
                import p1.Base;
                class Child extends Base {}
                """,
                        "Child.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            var fragment = new SummaryFragment(cm, "p2.Child", SummaryType.CODEUNIT_SKELETON);
            String text = fragment.text();

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
        }
    }

    @Test
    public void nonClassCUSkeletonRendersWithoutAncestorSection() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                public class MyClass {
                    public void myMethod() {}
                }
                """,
                        "Test.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            var fragment = new SummaryFragment(cm, "MyClass.myMethod", SummaryType.CODEUNIT_SKELETON);
            String text = fragment.text();

            assertCodeEquals(
                    """
                    package (default package);

                    public void myMethod()
                    """,
                    text);
        }
    }
}
