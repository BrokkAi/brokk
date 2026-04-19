package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineCoreProject;
import java.util.List;
import org.junit.jupiter.api.Test;

public class RustTestAssertionSmellTest extends AbstractBrittleTestSuite {

    @Test
    void assertionCountUsesTotalRecognizedAssertionMacros() {
        String literal = "a".repeat(defaultWeights().largeLiteralLengthThreshold());
        String code =
                """
                #[test]
                fn mixed_assertions() {
                    let expected = String::from("expected");
                    let actual = String::from("actual");
                    assert_eq!("%s", actual);
                    assert_eq!(actual, expected);
                }
                """
                        .formatted(literal);
        var findings = analyze(code);
        var overspecified = findings.stream()
                .filter(f -> f.reasons().contains("overspecified-literal"))
                .findFirst()
                .orElse(null);
        assertNotNull(overspecified, "Expected overspecified-literal smell");
        assertEquals(2, overspecified.assertionCount(), "assertionCount should include non-smelly assertions");
    }

    @Test
    void meaningfulAssertionDoesNotTriggerNoAssertions() {
        String code =
                """
                #[test]
                fn meaningful_assert() {
                    let expected = String::from("x");
                    let actual = String::from("x");
                    assert_eq!(actual, expected);
                }
                """;
        var findings = analyze(code);
        assertFalse(hasReason(findings, "no-assertions"));
    }

    @Test
    void shallowOnlyNotEmittedWhenMixedWithMeaningfulMacro() {
        String literal = "a".repeat(defaultWeights().largeLiteralLengthThreshold());
        String code =
                """
                #[test]
                fn mixed_shallow_and_meaningful() {
                    let expected = String::from("x");
                    let actual = String::from("y");
                    assert_eq!("%s", actual);
                    assert_eq!(actual, expected);
                }
                """
                        .formatted(literal);
        var findings = analyze(code);
        assertTrue(hasReason(findings, "overspecified-literal"));
        assertFalse(hasReason(findings, "shallow-assertions-only"));
    }

    @Test
    void cfgTestAloneDoesNotMarkHelperFunctionAsTest() {
        String code =
                """
                #[cfg(test)]
                mod tests {
                    fn helper_like_test_name() {
                        assert_eq!(1, 1);
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.isEmpty());
    }

    @Test
    void directTestAttributeEnablesSmellDetection() {
        String literal = "a".repeat(defaultWeights().largeLiteralLengthThreshold());
        String code =
                """
                #[test]
                fn constant_assertion() {
                    assert_eq!("%s", actual());
                }

                fn actual() -> String {
                    String::from("x")
                }
                """;
        code = code.formatted(literal);
        var findings = analyze(code);
        assertTrue(hasReason(findings, "overspecified-literal"));
    }

    @Override
    protected String defaultTestPath() {
        return "src/lib.rs";
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
