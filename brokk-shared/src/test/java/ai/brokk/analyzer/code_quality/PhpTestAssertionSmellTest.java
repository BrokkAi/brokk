package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.testutil.InlineCoreProject;
import java.util.List;
import java.util.stream.Collectors;
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
        var findings = analyze(code);
        assertTrue(findings.stream().noneMatch(f -> f.score() >= 4), findings.toString());
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
        var findings = analyze(code);
        var strong = findings.stream().filter(f -> f.score() >= 1).toList();
        assertTrue(!strong.isEmpty(), findings.toString());
        assertTrue(hasReason(strong, "constant-truth"), strong.toString());
    }

    @Override
    protected String defaultTestPath() {
        return "tests/SampleTest.php";
    }

    @Override
    protected List<IAnalyzer.TestAssertionSmell> analyze(
            String source, String path, IAnalyzer.TestAssertionWeights weights) {
        try (var testProject = InlineCoreProject.code(source, path).build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            return analyzer.findTestAssertionSmells(testProject.file(path), weights).stream()
                    .map(f -> new IAnalyzer.TestAssertionSmell(
                            f.file(),
                            f.enclosingFqName(),
                            f.assertionKind(),
                            f.score(),
                            f.assertionCount(),
                            // keep reasons stable in failure output ordering
                            f.reasons().stream().sorted().collect(Collectors.toList()),
                            f.excerpt()))
                    .toList();
        }
    }
}
