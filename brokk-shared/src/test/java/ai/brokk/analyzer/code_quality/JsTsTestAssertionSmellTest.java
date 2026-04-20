package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineCoreProject;
import java.util.List;
import org.junit.jupiter.api.Test;

public class JsTsTestAssertionSmellTest extends AbstractBrittleTestSuite {

    @Test
    void flagsSelfComparisonAssertion() {
        String code =
                """
                test("same value", () => {
                    const value = "x";
                    expect(value).toBe(value);
                });
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "self-comparison"));
    }

    @Test
    void flagsConstantTruthAndConstantEquality() {
        String code =
                """
                test("constants", () => {
                    expect(true).toBe(true);
                });
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "constant-equality"));
    }

    @Test
    void flagsItBodyWithNoAssertions() {
        String code =
                """
                it("has no assertions", () => {
                    work();
                });

                function work() {}
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "no-assertions"));
    }

    @Test
    void meaningfulAssertionIsNotFlaggedWithDefaultWeights() {
        String code =
                """
                it("checks the semantic value", () => {
                    const result = { name: "expected" };
                    expect(result.name).toBe("expected");
                });
                """;
        var findings = analyze(code);
        assertTrue(findings.isEmpty());
    }

    @Test
    void flagsOnlyTruthyAssertionAsShallow() {
        String code =
                """
                it("only checks truthiness", () => {
                    const result = build();
                    expect(result).toBeTruthy();
                });

                function build() {
                    return {};
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "nullness-only"));
        assertTrue(hasReason(findings, "shallow-assertions-only"));
    }

    @Test
    void flagsSnapshotOnlyAssertion() {
        String code =
                """
                test("snapshot only", () => {
                    const rendered = render();
                    expect(rendered).toMatchSnapshot();
                });

                function render() {
                    return "<div>value</div>";
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "snapshot-assertion"));
    }

    @Test
    void nonTestTypeScriptFileIsSkipped() {
        String code =
                """
                function helper() {
                    expect(true).toBe(true);
                }
                """;
        var findings = analyze(code, "src/sample.ts");
        assertTrue(findings.isEmpty());
    }

    @Override
    protected String defaultTestPath() {
        return "src/sample.test.ts";
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
