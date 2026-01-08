package ai.brokk.analyzer.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.GoAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

@NullMarked
public class GoTestDetectionTest {

    @Test
    void testContainsTestsDetection() throws IOException {
        String testPointerContent =
                """
                package foo
                import "testing"
                func TestPointer(t *testing.T) {}
                """;

        // Coverage for the non-pointer parameter form; file contains ONLY the value-param test.
        String testValueContent =
                """
                package foo
                import "testing"
                func TestValue(t testing.T) {}
                """;

        // Explicit negative case: benchmark-only file must not be detected as tests.
        String benchmarkOnlyContent =
                """
                package foo
                import "testing"
                func BenchmarkOnly(b *testing.B) {}
                """;

        // Negative case: benchmark + receiver method named like a test must not be detected.
        // We intentionally do not treat receiver methods as tests.
        String receiverAndBenchmarkContent =
                """
                package foo
                import "testing"

                func BenchmarkStuff(b *testing.B) {}

                type S struct {}
                func (s *S) TestMethod(t *testing.T) {}
                """;

        // Paths do NOT match TEST_FILE_PATTERN but content contains semantic markers.
        String pointerPath = "pkg/ptr.go";
        String valuePath = "pkg/val.go";
        String benchmarkOnlyPath = "pkg/bench.go";
        String receiverAndBenchmarkPath = "pkg/lib.go";

        IProject project = InlineTestProjectCreator.code(testPointerContent, pointerPath)
                .addFileContents(testValueContent, valuePath)
                .addFileContents(benchmarkOnlyContent, benchmarkOnlyPath)
                .addFileContents(receiverAndBenchmarkContent, receiverAndBenchmarkPath)
                .build();

        GoAnalyzer analyzer = new GoAnalyzer(project);
        analyzer = (GoAnalyzer) analyzer.update();

        ProjectFile pointerFile = new ProjectFile(project.getRoot(), pointerPath);
        ProjectFile valueFile = new ProjectFile(project.getRoot(), valuePath);
        ProjectFile benchmarkOnlyFile = new ProjectFile(project.getRoot(), benchmarkOnlyPath);
        ProjectFile receiverAndBenchmarkFile = new ProjectFile(project.getRoot(), receiverAndBenchmarkPath);

        // Verify analyzer semantic detection
        assertTrue(analyzer.containsTests(pointerFile), "Should detect pointer-based test: " + pointerPath);
        assertTrue(analyzer.containsTests(valueFile), "Should detect value-based test: " + valuePath);

        assertFalse(
                analyzer.containsTests(benchmarkOnlyFile),
                "Benchmarks are not tests; a file with only BenchmarkXxx should not be detected as containing tests: "
                        + benchmarkOnlyPath);

        // Receiver methods should not trigger test detection even if named TestXxx.
        assertFalse(
                analyzer.containsTests(receiverAndBenchmarkFile),
                "We intentionally do not treat receiver methods as tests; file should not be detected as containing tests: "
                        + receiverAndBenchmarkPath);

        // Additional negative case: Function named Test but wrong parameter type
        String wrongParamContent =
                """
                package foo
                import "testing"
                func TestNotReally(t *testing.B) {}
                """;
        String wrongParamPath = "pkg/wrong_param.go";
        IProject project2 =
                InlineTestProjectCreator.code(wrongParamContent, wrongParamPath).build();
        GoAnalyzer analyzer2 = new GoAnalyzer(project2);
        analyzer2 = (GoAnalyzer) analyzer2.update();
        assertFalse(
                analyzer2.containsTests(new ProjectFile(project2.getRoot(), wrongParamPath)),
                "Should NOT detect test if parameter is *testing.B instead of *testing.T");

        // Verify ContextManager integration (should use semantic analyzer signal, not filename heuristics)
        assertTrue(
                ContextManager.isTestFile(pointerFile, analyzer),
                "ContextManager should identify pointer test file via analyzer");
        assertTrue(
                ContextManager.isTestFile(valueFile, analyzer),
                "ContextManager should identify value test file via analyzer");

        // Negative case: Multiple parameters sharing a type (e.g., func TestMulti(a, b *testing.T))
        // Go's spec for tests requires exactly one parameter.
        String multiParamContent =
                """
                package foo
                import "testing"
                func TestMulti(a, b *testing.T) {}
                """;
        String multiParamPath = "pkg/multi_param.go";
        IProject project3 =
                InlineTestProjectCreator.code(multiParamContent, multiParamPath).build();
        GoAnalyzer analyzer3 = new GoAnalyzer(project3);
        analyzer3 = (GoAnalyzer) analyzer3.update();
        assertFalse(
                analyzer3.containsTests(new ProjectFile(project3.getRoot(), multiParamPath)),
                "Should NOT detect test if function has multiple parameters, even if they share *testing.T type");
    }

    @Test
    void testExtraParametersDetection() throws IOException {
        String content =
                """
                package foo
                import "testing"
                func TestExtra(t *testing.T, x int) {}
                """;
        String path = "pkg/extra_param.go";
        IProject project = InlineTestProjectCreator.code(content, path).build();
        GoAnalyzer analyzer = new GoAnalyzer(project);
        analyzer = (GoAnalyzer) analyzer.update();

        assertFalse(
                analyzer.containsTests(new ProjectFile(project.getRoot(), path)),
                "Should NOT detect test if function has extra parameters beyond *testing.T");
    }

    @Test
    void testGenericTestDetection() throws IOException {
        String content =
                """
                package foo
                import "testing"
                func TestGeneric[T any](t *testing.T) {}
                """;
        String path = "pkg/generic_test.go";
        IProject project = InlineTestProjectCreator.code(content, path).build();
        GoAnalyzer analyzer = new GoAnalyzer(project);
        analyzer = (GoAnalyzer) analyzer.update();

        assertFalse(
                analyzer.containsTests(new ProjectFile(project.getRoot(), path)),
                "Should NOT detect test if function is generic (has type parameters)");
    }
}
