package ai.brokk.analyzer.tests;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.PhpAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

@NullMarked
public class PhpTestDetectionTest {

    @Test
    void testNameBasedDetection() throws IOException {
        String code = """
            <?php
            function testFoo() { }
            """;
        IProject project = InlineTestProjectCreator.code(code, "Test.php").build();
        PhpAnalyzer analyzer = new PhpAnalyzer(project);
        analyzer.update();

        assertTrue(
                analyzer.containsTests(new ProjectFile(project.getRoot(), "Test.php")),
                "Should detect test based on function name");
    }

    @Test
    void testDocblockBasedDetection() throws IOException {
        String code = """
            <?php
            /** @test */
            function foo() { }
            """;
        IProject project = InlineTestProjectCreator.code(code, "Test.php").build();
        PhpAnalyzer analyzer = new PhpAnalyzer(project);
        analyzer.update();

        assertTrue(
                analyzer.containsTests(new ProjectFile(project.getRoot(), "Test.php")),
                "Should detect test based on @test docblock");
    }

    @Test
    void testNegativeDetection() throws IOException {
        String code =
                """
            <?php
            function normalFunction() { }
            class NormalClass {
                public function normalMethod() { }
            }
            """;
        IProject project = InlineTestProjectCreator.code(code, "Normal.php").build();
        PhpAnalyzer analyzer = new PhpAnalyzer(project);
        analyzer.update();

        assertFalse(
                analyzer.containsTests(new ProjectFile(project.getRoot(), "Normal.php")),
                "Should not detect tests in normal file");
    }

    @Test
    void testNameBasedDetectionIsCaseInsensitive() throws IOException {
        String code = """
            <?php
            function TestFoo() { }
            function TESTBar() { }
            """;
        IProject project = InlineTestProjectCreator.code(code, "CaseInsensitive.php").build();
        PhpAnalyzer analyzer = new PhpAnalyzer(project);
        analyzer.update();

        assertTrue(
                analyzer.containsTests(new ProjectFile(project.getRoot(), "CaseInsensitive.php")),
                "Should detect tests for mixed/upper-case function names since PHP is case-insensitive");
    }

    @Test
    void testContextManagerIntegration() throws IOException {
        String code = """
            <?php
            function testFoo() { }
            """;
        IProject project =
                InlineTestProjectCreator.code(code, "Integration.php").build();
        PhpAnalyzer analyzer = new PhpAnalyzer(project);
        analyzer.update();

        assertTrue(
                ContextManager.isTestFile(new ProjectFile(project.getRoot(), "Integration.php"), analyzer),
                "ContextManager should recognize file as test file via analyzer");
    }
}
