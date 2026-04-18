package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.List;
import org.junit.jupiter.api.Test;

public class GoTestAssertionSmellTest extends AbstractBrittleTestSuite {

    @Test
    void meaningfulAssertionLikeBranchDoesNotTriggerNoAssertions() {
        String code =
                """
                package sample
                import "testing"

                func TestMeaningful(t *testing.T) {
                    got := "value"
                    want := "other"
                    if got != want {
                        t.Errorf("got %s, want %s", got, want)
                    }
                }
                """;
        var findings = analyze(code);
        assertFalse(hasReason(findings, "no-assertions"));
    }

    @Test
    void assertionCountUsesTotalDetectedAssertions() {
        String code =
                """
                package sample
                import "testing"

                func TestMixed(t *testing.T) {
                    got := "value"
                    want := "other"
                    if true == true {
                        t.Errorf("tautology")
                    }
                    if got != want {
                        t.Errorf("got %s, want %s", got, want)
                    }
                }
                """;
        var findings = analyze(code);
        var constantEquality = findings.stream()
                .filter(f -> f.reasons().contains("constant-equality"))
                .findFirst()
                .orElse(null);
        assertNotNull(constantEquality, "Expected constant-equality smell");
        assertEquals(2, constantEquality.assertionCount(), "assertionCount should include non-smelly assertions");
    }

    @Test
    void shallowOnlyNotEmittedWhenMixedWithMeaningfulAssertion() {
        String code =
                """
                package sample
                import "testing"

                func TestMixedShallowAndMeaningful(t *testing.T) {
                    var got *int
                    want := "expected"
                    actual := "actual"
                    if got == nil {
                        t.Errorf("got nil")
                    }
                    if actual != want {
                        t.Errorf("want %s, got %s", want, actual)
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "nullness-only"));
        assertFalse(hasReason(findings, "shallow-assertions-only"));
    }

    @Test
    void emitsNoAssertionsWhenNoAssertionLikeBranchesExist() {
        String code =
                """
                package sample
                import "testing"

                func TestNoAssertionLikeBranch(t *testing.T) {
                    value := 42
                    _ = value
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "no-assertions"));
    }

    @Override
    protected String defaultTestPath() {
        return "pkg/sample_test.go";
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
