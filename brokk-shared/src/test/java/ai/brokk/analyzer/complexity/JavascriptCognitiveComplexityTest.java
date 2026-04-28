package ai.brokk.analyzer.complexity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.testutil.InlineCoreProject;
import org.junit.jupiter.api.Test;

public class JavascriptCognitiveComplexityTest {

    @Test
    void testSimpleFunctionComplexity() {
        assertComplexity(
                """
                function method() {
                    console.log("hello");
                }
                """,
                "method",
                0);
    }

    @Test
    void testIfComplexity() {
        assertComplexity(
                """
                function method(a) {
                    if (a) {
                        return 1;
                    }
                }
                """,
                "method",
                1);
    }

    @Test
    void testNestedIfComplexity() {
        assertComplexity(
                """
                function method(a, b) {
                    if (a) {
                        if (b) {
                            return 1;
                        }
                    }
                }
                """,
                "method",
                3);
    }

    @Test
    void testElseIfDoesNotAddNestingComplexity() {
        assertComplexity(
                """
                function method(x) {
                    if (x > 0) {
                        return 1;
                    } else if (x < 0) {
                        return -1;
                    } else {
                        return 0;
                    }
                }
                """,
                "method",
                2);
    }

    @Test
    void testLoopsComplexity() {
        assertComplexity(
                """
                function method(items, ready) {
                    for (let i = 0; i < items.length; i++) {}
                    for (const item in items) {}
                    while (ready) {}
                    do {} while (ready);
                }
                """,
                "method",
                4);
    }

    @Test
    void testCatchComplexity() {
        assertComplexity(
                """
                function method() {
                    try {
                        risky();
                    } catch (e) {
                        recover();
                    }
                }
                """,
                "method",
                1);
    }

    @Test
    void testTernaryComplexity() {
        assertComplexity(
                """
                function method(x) {
                    return x > 10 ? "high" : "low";
                }
                """,
                "method",
                1);
    }

    @Test
    void testSwitchCasesIgnoreDefault() {
        assertComplexity(
                """
                function method(value) {
                    switch (value) {
                        case 1:
                            return "one";
                        case 2:
                            return "two";
                        default:
                            return "other";
                    }
                }
                """,
                "method",
                2);
    }

    @Test
    void testLogicalAndCoalescingOperatorSequencesComplexity() {
        assertComplexity(
                """
                function method(a, b, c, d) {
                    if (a && b || c ?? d) {
                        return true;
                    }
                    return false;
                }
                """,
                "method",
                4);
    }

    @Test
    void testLabeledBreakAndContinueCountButUnlabeledDoNot() {
        assertComplexity(
                """
                function method(items) {
                    outer:
                    for (const item in items) {
                        if (item.done) {
                            break outer;
                        }
                        while (item.ready) {
                            continue;
                        }
                    }
                }
                """,
                "method",
                6);
    }

    @Test
    void testArrowFunctionComplexity() {
        assertComplexity(
                """
                const method = (value) => {
                    if (value) {
                        return value;
                    }
                    return 0;
                };
                """,
                "method",
                1);
    }

    @Test
    void testNestedFunctionBodyDoesNotCountInsideEnclosingFunction() {
        assertComplexity(
                """
                function outer(a, b) {
                    function helper() {
                        if (a) {
                            if (b) {
                                return 1;
                            }
                        }
                        return 0;
                    }
                    return helper();
                }
                """,
                "outer",
                0);
    }

    private void assertComplexity(String source, String functionName, int expected) {
        try (var project = InlineCoreProject.code(source, "complexity_test.js").build()) {
            IAnalyzer analyzer = project.getAnalyzer();
            CodeUnit cu = analyzer.getAllDeclarations().stream()
                    .filter(u -> u.isFunction() && u.identifier().equals(functionName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Function not found: " + functionName));

            int actual = analyzer.computeCognitiveComplexity(cu);
            assertEquals(expected, actual, "Complexity for " + functionName);
        }
    }
}
