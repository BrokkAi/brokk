package ai.brokk.analyzer.tests;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.PythonAnalyzer;
import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

@NullMarked
public class PythonTestDetectionTest {

    @Test
    void testTestPrefixedFunctionDetection() throws IOException {
        String code = """
            def test_addition():
                assert 1 + 1 == 2
            """;

        IProject project = InlineTestProjectCreator.code(code, "Example.py").build();
        PythonAnalyzer analyzer = new PythonAnalyzer(project);
        analyzer.update();

        assertTrue(
                analyzer.containsTests(new ProjectFile(project.getRoot(), "Example.py")),
                "Should detect tests based on def test_... function name");
    }

    @Test
    void testTestPrefixedMethodDetection() throws IOException {
        String code =
                """
            class TestMath:
                def test_addition(self):
                    assert 1 + 1 == 2
            """;

        IProject project = InlineTestProjectCreator.code(code, "Example.py").build();
        PythonAnalyzer analyzer = new PythonAnalyzer(project);
        analyzer.update();

        assertTrue(
                analyzer.containsTests(new ProjectFile(project.getRoot(), "Example.py")),
                "Should detect tests based on test_ prefixed method name");
    }

    @Test
    void testPytestMarkDetection() throws IOException {
        String code =
                """
            import pytest

            @pytest.mark.slow
            def some_helper():
                return 123
            """;

        IProject project = InlineTestProjectCreator.code(code, "Example.py").build();
        PythonAnalyzer analyzer = new PythonAnalyzer(project);
        analyzer.update();

        assertTrue(
                analyzer.containsTests(new ProjectFile(project.getRoot(), "Example.py")),
                "Should detect tests based on @pytest.mark.* decorator");
    }

    @Test
    void testNegativeDetection() throws IOException {
        String code =
                """
            def add(a, b):
                return a + b

            class Math:
                def add(self, a, b):
                    return a + b
            """;

        IProject project = InlineTestProjectCreator.code(code, "Example.py").build();
        PythonAnalyzer analyzer = new PythonAnalyzer(project);
        analyzer.update();

        assertFalse(
                analyzer.containsTests(new ProjectFile(project.getRoot(), "Example.py")),
                "Should not detect tests in a normal Python file");
    }
}
