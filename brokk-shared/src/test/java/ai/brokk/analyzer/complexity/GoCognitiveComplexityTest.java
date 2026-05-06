package ai.brokk.analyzer.complexity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.testutil.InlineCoreProject;
import org.junit.jupiter.api.Test;

public class GoCognitiveComplexityTest {

    @Test
    void testSimpleFunctionComplexity() {
        assertComplexity("package main\nfunc method() int { return 0 }", "method", 0);
    }

    @Test
    void testIfNestedIfAndElseIfComplexity() {
        assertComplexity(
                """
                package main
                func method(a, b int) int {
                    if a > 0 {
                        if b > 0 { return 1 }
                    } else if a < 0 {
                        return -1
                    }
                    return 0
                }
                """,
                "method",
                4);
    }

    @Test
    void testLoopsSwitchSelectLogicalAndFunctionLiteral() {
        assertComplexity(
                """
                package main
                func method(ch chan int, x int) int {
                    f := func() int { if x > 0 { return 1 }; return 0 }
                outer:
                    for i := 0; i < x; i++ {
                        if x > 0 && i > 0 || i < 10 { break outer }
                    }
                    switch x { case 1: return f(); default: return 0 }
                    select { case <-ch: return 1; default: return 0 }
                }
                """,
                "method",
                10);
    }

    @Test
    void testMethodComplexity() {
        assertComplexity(
                """
                package main
                type S struct{}
                func (s S) method(x int) int {
                    if x > 0 { return 1 }
                    return 0
                }
                """,
                "method",
                1);
    }

    @Test
    void testDeepNestingDoesNotOverflow() {
        String source = "package main\nfunc method(x int) int {\n"
                + "if x > 0 {\n".repeat(120)
                + "return 1\n"
                + "}\n".repeat(120)
                + "return 0\n}";
        assertComplexity(source, "method", 7_260);
    }

    private void assertComplexity(String source, String functionName, int expected) {
        try (var project = InlineCoreProject.code(source, "complexity_test.go").build()) {
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
