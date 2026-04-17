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

public class PhpTestAssertionSmellTest extends AbstractBrittleTestSuite {

    @Test
    void emitsNoAssertionsForTestMethodWithNoAssertions() {
        String code =
                """
                <?php
                class SampleTest {
                    public function testNoAssertions(): void {
                        $value = 42;
                        $value++;
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "no-assertions"), findings.toString());
    }

    @Test
    void flagsConstantTruthAndConstantEquality() {
        String code =
                """
                <?php
                class SampleTest {
                    public function testConstants(): void {
                        $this->assertTrue(true);
                        $this->assertEquals(1, 1);
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "constant-truth"), findings.toString());
        assertTrue(hasReason(findings, "constant-equality"), findings.toString());
    }

    @Test
    void flagsSelfComparisonAssertion() {
        String code =
                """
                <?php
                class SampleTest {
                    public function testSelfComparison(): void {
                        $value = "x";
                        $this->assertSame($value, $value);
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "self-comparison"), findings.toString());
    }

    @Test
    void flagsNullnessOnlyAndShallowOnly() {
        String code =
                """
                <?php
                class SampleTest {
                    public function testNullnessOnly(): void {
                        $value = new \\stdClass();
                        $this->assertNotNull($value);
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "nullness-only"), findings.toString());
        assertTrue(hasReason(findings, "shallow-assertions-only"), findings.toString());
    }

    @Test
    void expectExceptionCountsAsAssertionEquivalentSoNoAssertionsIsNotEmitted() {
        String code =
                """
                <?php
                class SampleTest {
                    public function testExpectException(): void {
                        $this->expectException(\\RuntimeException::class);
                        throw new \\RuntimeException("boom");
                    }
                }
                """;
        var findings = analyze(code);
        assertFalse(hasReason(findings, "no-assertions"), findings.toString());
    }

    @Test
    void toolReturnsNoFindingsMessageForCleanTest() {
        String code =
                """
                <?php
                class SampleTest {
                    public function testMeaningful(): void {
                        $value = "expected";
                        $this->assertEquals("expected", $value);
                    }
                }
                """;
        String report = toolReport(code, 4);
        assertTrue(report.startsWith("No test assertion smells met minScore"), report);
    }

    @Test
    void toolProducesMarkdownTableForSmellyTest() {
        String code =
                """
                <?php
                class SampleTest {
                    public function testSmelly(): void {
                        $this->assertTrue(true);
                    }
                }
                """;
        String report = toolReport(code, 1);
        assertTrue(report.contains("## Test assertion smells"), report);
        assertTrue(report.contains("| Score | Kind | Assertions | Symbol | File | Reasons | Excerpt |"), report);
        assertTrue(report.contains("constant-truth"), report);
    }

    private String toolReport(String source, int minScore) {
        String path = defaultTestPath();
        try (var testProject = InlineTestProjectCreator.code(source, path).build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            var cm = new TestContextManager(testProject, new TestConsoleIO(), java.util.Set.of(), analyzer);
            var tools = new CodeQualityTools(cm);
            return tools.reportTestAssertionSmells(
                    List.of(path), minScore, 80, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1);
        }
    }

    @Override
    protected String defaultTestPath() {
        return "tests/SampleTest.php";
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
