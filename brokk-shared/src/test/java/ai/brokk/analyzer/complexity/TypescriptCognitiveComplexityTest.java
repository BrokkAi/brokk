package ai.brokk.analyzer.complexity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.testutil.InlineCoreProject;
import org.junit.jupiter.api.Test;

public class TypescriptCognitiveComplexityTest {

    @Test
    void testSimpleTypedFunctionComplexity() {
        assertComplexity(
                """
                function method(value: number): number {
                    return value;
                }
                """,
                "method",
                0);
    }

    @Test
    void testTypedControlFlowComplexity() {
        assertComplexity(
                """
                function method(a: number, b?: string): number {
                    if (a > 10 && b !== undefined) {
                        for (let i = 0; i < a; i++) {
                            if (i % 2 === 0) {
                                return i;
                            }
                        }
                    }
                    return b ?? "default" ? 1 : 0;
                }
                """,
                "method", 9);
    }

    @Test
    void testMethodDefinitionComplexity() {
        assertComplexity(
                """
                class Service {
                    method(value: number): string {
                        if (value > 0) {
                            return "positive";
                        } else if (value < 0) {
                            return "negative";
                        }
                        return "zero";
                    }
                }
                """,
                "method",
                2);
    }

    @Test
    void testTypedArrowFunctionComplexity() {
        assertComplexity(
                """
                const method = (value: number): number => {
                    if (value > 0) {
                        return value;
                    }
                    return 0;
                };
                """,
                "method",
                1);
    }

    @Test
    void testNestedArrowFunctionBodyDoesNotCountInsideEnclosingFunction() {
        assertComplexity(
                """
                function outer(values: number[]): number {
                    const helper = (value: number): number => {
                        if (value > 0) {
                            if (value % 2 === 0) {
                                return value;
                            }
                        }
                        return 0;
                    };
                    return values.length;
                }
                """,
                "outer", 0);
    }

    private void assertComplexity(String source, String functionName, int expected) {
        try (var project = InlineCoreProject.code(source, "complexity_test.ts").build()) {
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
