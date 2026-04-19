package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineCoreProject;
import java.util.List;
import org.junit.jupiter.api.Test;

public class PythonTestAssertionSmellTest extends AbstractBrittleTestSuite {

    @Test
    void flagsSelfComparisonAssertion() {
        String code =
                """
                def test_same_value():
                    value = "x"
                    assert value == value
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "self-comparison"));
    }

    @Test
    void flagsConstantTruthAndConstantEquality() {
        String code =
                """
                import unittest

                class SampleTest(unittest.TestCase):
                    def test_constants(self):
                        assert True
                        self.assertEqual(1, 1)
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "constant-truth"));
        assertTrue(hasReason(findings, "constant-equality"));
    }

    @Test
    void flagsTestFunctionWithNoAssertions() {
        String code =
                """
                def test_no_assertions():
                    helper()

                def helper():
                    return 1
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "no-assertions"));
    }

    @Test
    void meaningfulAssertionIsNotFlaggedWithDefaultWeights() {
        String code =
                """
                def test_meaningful():
                    result = {"name": "expected"}
                    assert result["name"] == "expected"
                """;
        var findings = analyze(code);
        assertTrue(findings.isEmpty());
    }

    @Test
    void flagsOnlyNullnessAssertionAsShallow() {
        String code =
                """
                def test_nullness():
                    result = object()
                    assert result is not None
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "nullness-only"));
        assertTrue(hasReason(findings, "shallow-assertions-only"));
    }

    @Test
    void nonTestPythonFileIsSkipped() {
        String code = """
                def helper():
                    assert True
                """;
        var findings = analyze(code, "pkg/module.py");
        assertTrue(findings.isEmpty());
    }

    @Test
    void pytestFixtureNamedLikeTestIsSkipped() {
        String code =
                """
                import pytest

                @pytest.fixture
                def test_data():
                    assert True
                    return 1
                """;
        var findings = analyze(code);
        assertTrue(findings.isEmpty());
    }

    @Override
    protected String defaultTestPath() {
        return "pkg/test_sample.py";
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
