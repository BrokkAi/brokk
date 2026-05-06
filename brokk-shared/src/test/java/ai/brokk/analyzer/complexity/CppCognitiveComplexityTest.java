package ai.brokk.analyzer.complexity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.testutil.InlineCoreProject;
import org.junit.jupiter.api.Test;

public class CppCognitiveComplexityTest {

    @Test
    void testSimpleFunctionComplexity() {
        assertComplexity("int method() { return 0; }", "method", 0);
    }

    @Test
    void testIfNestedIfAndElseIfComplexity() {
        assertComplexity(
                """
                int method(int a, int b) {
                    if (a > 0) {
                        if (b > 0) return 1;
                    } else if (a < 0) {
                        return -1;
                    }
                    return 0;
                }
                """,
                "method",
                4);
    }

    @Test
    void testLoopsSwitchCatchTernaryLogicalAndLabeledJumps() {
        assertComplexity(
                """
                int method(int x) {
                    label:
                    for (int i = 0; i < x; i++) {
                        if (x > 0 && i > 0 || i < 10) break label;
                    }
                    while (x-- > 0) continue;
                    switch (x) { case 1: return 1; default: return 0; }
                    try { risky(); } catch (...) { recover(); }
                    return x > 0 ? 1 : 0;
                }
                """,
                "method",
                10);
    }

    @Test
    void testMethodLambdaAndNestedFunctionBoundary() {
        assertComplexity(
                """
                struct S {
                    int method(int x) {
                        auto f = [&]() { if (x > 0) return 1; return 0; };
                        return f();
                    }
                };
                int helper(int x) {
                    if (x) { if (x > 1) return 1; }
                    return 0;
                }
                """,
                "method",
                2);
    }

    @Test
    void testDeepNestingDoesNotOverflow() {
        String source = "int method(int x) {\n" + "if (x) {\n".repeat(120) + "return 1;\n" + "}\n".repeat(120) + "}";
        assertComplexity(source, "method", 7_260);
    }

    private void assertComplexity(String source, String functionName, int expected) {
        try (var project = InlineCoreProject.code(source, "complexity_test.cpp").build()) {
            assertEquals(expected, complexity(project.getAnalyzer(), functionName), "Complexity for " + functionName);
        }
    }

    private int complexity(IAnalyzer analyzer, String functionName) {
        CodeUnit cu = analyzer.getAllDeclarations().stream()
                .filter(u -> u.isFunction() && u.identifier().endsWith(functionName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Function not found: " + functionName));
        return analyzer.computeCognitiveComplexity(cu);
    }
}
