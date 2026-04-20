package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineCoreProject;
import java.util.List;
import org.junit.jupiter.api.Test;

public class CSharpTestAssertionSmellTest extends AbstractBrittleTestSuite {

    @Test
    void flagsConstantTruthAndConstantEquality() {
        String code =
                """
                using Xunit;

                namespace Example;

                public class SampleTest {
                    [Fact]
                    public void Constants() {
                        Assert.True(true);
                        Assert.Equal(1, 1);
                    }
                }
                """;
        var findings = analyze(code);
        assertFalse(findings.isEmpty());
        assertTrue(hasReason(findings, "constant-equality"));
    }

    @Test
    void flagsSelfComparisonAssertion() {
        String code =
                """
                using Xunit;

                namespace Example;

                public class SampleTest {
                    [Fact]
                    public void SameValue() {
                        var value = "x";
                        Assert.Equal(value, value);
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "self-comparison"));
    }

    @Test
    void flagsTestMethodWithNoAssertions() {
        String code =
                """
                using Xunit;

                namespace Example;

                public class SampleTest {
                    [Fact]
                    public void NoAssertions() {
                        var value = 42;
                        _ = value;
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "no-assertions"));
    }

    @Test
    void flagsNullnessOnlyAsShallow() {
        String code =
                """
                using Xunit;

                namespace Example;

                public class SampleTest {
                    [Fact]
                    public void NullnessOnly() {
                        object value = new();
                        Assert.NotNull(value);
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "nullness-only"));
        assertTrue(hasReason(findings, "shallow-assertions-only"));
    }

    @Test
    void meaningfulAssertionIsNotFlaggedWithDefaultWeights() {
        String code =
                """
                using Xunit;

                namespace Example;

                public class SampleTest {
                    [Fact]
                    public void Meaningful() {
                        var result = Name();
                        Assert.Equal("expected", result);
                    }

                    private static string Name() => "expected";
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.isEmpty());
    }

    @Test
    void nonTestCSharpFileIsSkipped() {
        String code =
                """
                namespace Example;

                public class Sample {
                    public void LooksLikeAssertion() {
                        Assert.Equal(1, 1);
                    }
                }
                """;
        var findings = analyze(code, "src/Sample.cs");
        assertTrue(findings.isEmpty());
    }

    @Test
    void weightTuningCanSuppressFindings() {
        String code =
                """
                using Xunit;

                namespace Example;

                public class SampleTest {
                    [Fact]
                    public void Constant() {
                        Assert.True(true);
                    }
                }
                """;
        var defaults = analyze(code);
        var tunedWeights = new IAnalyzer.TestAssertionWeights(0, 0, 0, 0, 0, 0, 0, 0, 0, 10, 4, 120);
        var tuned = analyze(code, tunedWeights);
        assertFalse(defaults.isEmpty(), "Default heuristics should flag constant truth");
        assertTrue(tuned.isEmpty(), "Zeroed smell weights should suppress the same finding");
    }

    @Override
    protected String defaultTestPath() {
        return "com/example/SampleTest.cs";
    }

    @Override
    protected List<IAnalyzer.TestAssertionSmell> analyze(
            String source, String path, IAnalyzer.TestAssertionWeights weights) {
        try (var testProject = InlineCoreProject.code(source, path).build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            ProjectFile file = new ProjectFile(testProject.getRoot(), path);
            return analyzer.findTestAssertionSmells(file, weights);
        }
    }
}
