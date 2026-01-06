package ai.brokk.analyzer.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.ScalaAnalyzer;
import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import org.junit.jupiter.api.Test;

public class ScalaTestDetectionTest {

    @Test
    void detectsJUnitTestAnnotation() throws IOException {
        String code =
                """
            import org.junit.Test

            class Example {
              @Test
              def itWorks(): Unit = {
                ()
              }
            }
            """;

        IProject project = InlineTestProjectCreator.code(code, "Example.scala").build();
        ScalaAnalyzer analyzer = new ScalaAnalyzer(project);
        analyzer.update();

        var file = new ProjectFile(project.getRoot(), "Example.scala");
        assertTrue(analyzer.containsTests(file));
        assertTrue(ContextManager.isTestFile(file, analyzer));
    }

    @Test
    void detectsFunSuiteStructureWithoutImports() throws IOException {
        String code =
                """
            class ExampleSuite {
              test("it works") {
                assert(1 + 1 == 2)
              }
            }
            """;

        IProject project = InlineTestProjectCreator.code(code, "Example.scala").build();
        ScalaAnalyzer analyzer = new ScalaAnalyzer(project);
        analyzer.update();

        var file = new ProjectFile(project.getRoot(), "Example.scala");
        assertTrue(analyzer.containsTests(file));
        assertTrue(ContextManager.isTestFile(file, analyzer));
    }

    @Test
    void detectsFlatSpecStructureWithoutImports() throws IOException {
        String code =
                """
            class ExampleSpec {
              "A Calculator" should "add two numbers" in {
                assert(1 + 1 == 2)
              }
            }
            """;

        IProject project = InlineTestProjectCreator.code(code, "Example.scala").build();
        ScalaAnalyzer analyzer = new ScalaAnalyzer(project);
        analyzer.update();

        var file = new ProjectFile(project.getRoot(), "Example.scala");
        assertTrue(analyzer.containsTests(file));
        assertTrue(ContextManager.isTestFile(file, analyzer));
    }

    @Test
    void negativeCaseWithScalaTestImportOnly() throws IOException {
        String code =
                """
            import org.scalatest.funsuite.AnyFunSuite

            class Example {
              def add(a: Int, b: Int): Int = a + b
            }
            """;

        IProject project = InlineTestProjectCreator.code(code, "Example.scala").build();
        ScalaAnalyzer analyzer = new ScalaAnalyzer(project);
        analyzer.update();

        var file = new ProjectFile(project.getRoot(), "Example.scala");
        assertFalse(analyzer.containsTests(file));
        assertFalse(ContextManager.isTestFile(file, analyzer));
    }

    @Test
    void negativeCaseNoMarkersNoScalaTestImports() throws IOException {
        String code =
                """
            class Example {
              def add(a: Int, b: Int): Int = a + b
            }
            """;

        IProject project = InlineTestProjectCreator.code(code, "Example.scala").build();
        ScalaAnalyzer analyzer = new ScalaAnalyzer(project);
        analyzer.update();

        var file = new ProjectFile(project.getRoot(), "Example.scala");
        assertFalse(analyzer.containsTests(file));
        assertFalse(ContextManager.isTestFile(file, analyzer));
    }
}
