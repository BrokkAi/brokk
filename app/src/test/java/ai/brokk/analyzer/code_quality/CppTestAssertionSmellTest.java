package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.tools.CodeQualityTools;
import java.util.List;
import org.junit.jupiter.api.Test;

public class CppTestAssertionSmellTest extends AbstractBrittleTestSuite {
    private static final String TEST_PATH = "test/sample_test.cpp";

    @Test
    void flagsConstantTruthAndConstantEqualityInGTest() {
        String code =
                """
                #include <gtest/gtest.h>

                TEST(SampleTest, SmellyAssertions) {
                    EXPECT_TRUE(true);
                    ASSERT_EQ(1, 1);
                }
                """;
        var findings = analyze(code, TEST_PATH);
        assertTrue(hasReason(findings, "constant-truth"), findings.toString());
        assertTrue(hasReason(findings, "constant-equality"), findings.toString());
    }

    @Test
    void flagsNoAssertionsWhenTestHasNoAssertionMacros() {
        String code =
                """
                #include <gtest/gtest.h>

                TEST(SampleTest, NoAssertions) {
                    int value = 42;
                    value++;
                }
                """;
        var findings = analyze(code, TEST_PATH);
        assertTrue(hasReason(findings, "no-assertions"), findings.toString());
    }

    @Test
    void toolReturnsNoFindingsMessageForMeaningfulAssertion() {
        String code =
                """
                #include <gtest/gtest.h>

                TEST(SampleTest, Meaningful) {
                    int got = compute();
                    EXPECT_EQ(42, got);
                }

                int compute() {
                    return 42;
                }
                """;
        String report = toolReport(code, 4);
        assertTrue(report.startsWith("No test assertion smells met minScore"), report);
    }

    @Test
    void toolProducesMarkdownTableForSmellyCppTest() {
        String code =
                """
                #include <gtest/gtest.h>

                TEST(SampleTest, Smelly) {
                    EXPECT_TRUE(true);
                }
                """;
        String report = toolReport(code, 1);
        assertTrue(report.contains("## Test assertion smells"), report);
        assertTrue(report.contains("| Score | Kind | Assertions | Symbol | File | Reasons | Excerpt |"), report);
        assertTrue(report.contains("constant-truth"), report);
    }

    @Test
    void flagsNullnessOnlyAndShallowOnlyForNullChecks() {
        String code =
                """
                #include <gtest/gtest.h>

                int* load() {
                    static int value = 1;
                    return &value;
                }

                TEST(SampleTest, NullnessOnly) {
                    int* result = load();
                    EXPECT_NE(result, nullptr);
                }
                """;
        var findings = analyze(code, TEST_PATH);
        assertTrue(hasReason(findings, "nullness-only"), findings.toString());
        assertTrue(hasReason(findings, "shallow-assertions-only"), findings.toString());
    }

    @Test
    void mixedMeaningfulAssertionDoesNotEmitShallowOnly() {
        String code =
                """
                #include <gtest/gtest.h>

                int compute() {
                    return 42;
                }

                int* load() {
                    static int value = 1;
                    return &value;
                }

                TEST(SampleTest, Mixed) {
                    int* result = load();
                    EXPECT_NE(result, nullptr);
                    EXPECT_EQ(42, compute());
                }
                """;
        var findings = analyze(code, TEST_PATH);
        assertTrue(hasReason(findings, "nullness-only"), findings.toString());
        assertFalse(hasReason(findings, "shallow-assertions-only"), findings.toString());
    }

    private String toolReport(String source, int minScore) {
        String path = TEST_PATH;
        try (var testProject = InlineTestProjectCreator.code(source, path).build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            var cm = new TestContextManager(testProject, new TestConsoleIO(), java.util.Set.of(), analyzer);
            var tools = new CodeQualityTools(cm);
            return tools.reportTestAssertionSmells(
                    List.of(path), minScore, 80, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1);
        }
    }

    @Override
    protected List<IAnalyzer.TestAssertionSmell> analyze(
            String source, String path, IAnalyzer.TestAssertionWeights weights) {
        try (var testProject = InlineTestProjectCreator.code(source, path).build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            ProjectFile file = new ProjectFile(testProject.getRoot(), path);
            return analyzer.findTestAssertionSmells(file, weights);
        }
    }
}
