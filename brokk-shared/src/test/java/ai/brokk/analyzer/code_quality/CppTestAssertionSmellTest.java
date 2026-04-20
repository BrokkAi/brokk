package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineCoreProject;
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
        var findings = analyze(code);
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
        var findings = analyze(code);
        assertTrue(hasReason(findings, "no-assertions"), findings.toString());
    }

    @Test
    void meaningfulAssertionIsNotFlaggedWithDefaultWeights() {
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
        var findings = analyze(code);
        assertTrue(findings.isEmpty());
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
        var findings = analyze(code);
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
        var findings = analyze(code);
        assertTrue(hasReason(findings, "nullness-only"), findings.toString());
        assertFalse(hasReason(findings, "shallow-assertions-only"), findings.toString());
    }

    @Test
    void ignoresStandaloneMacroLikeIdentifierInNonTestCode() {
        String code =
                """
                int TEST = 0;

                void helper() {
                    if (TEST > 0) {
                        TEST++;
                    }
                }
                """;
        var findings = analyze(code, "src/sample.cpp");
        assertTrue(findings.isEmpty(), findings.toString());
    }

    @Test
    void bindsMarkerToItsOwnBodyWhenNearbyBlocksExist() {
        String code =
                """
                #include <gtest/gtest.h>

                void before() {
                    int x = 0;
                    (void)x;
                }

                TEST(SampleTest, BodyAssociation) {
                    if (true) {
                        int y = 1;
                        (void)y;
                    }
                    EXPECT_TRUE(true);
                }

                void after() {
                    int z = 2;
                    (void)z;
                }
                """;
        var findings = analyze(code);
        long constantTruthCount = findings.stream()
                .filter(f -> f.reasons().contains("constant-truth"))
                .count();
        assertTrue(constantTruthCount == 1, findings.toString());
        assertFalse(hasReason(findings, "no-assertions"), findings.toString());
    }

    @Override
    protected String defaultTestPath() {
        return TEST_PATH;
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
