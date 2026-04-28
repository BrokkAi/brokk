package ai.brokk.analyzer.complexity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.testutil.InlineCoreProject;
import org.junit.jupiter.api.Test;

public class RustCognitiveComplexityTest {

    @Test
    void testSimpleFunctionComplexity() {
        assertComplexity("fn method() -> i32 { 0 }", "method", 0);
    }

    @Test
    void testIfNestedIfAndElseIfComplexity() {
        assertComplexity(
                """
                fn method(a: i32, b: i32) -> i32 {
                    if a > 0 {
                        if b > 0 { return 1; }
                    } else if a < 0 {
                        return -1;
                    }
                    0
                }
                """,
                "method",
                4);
    }

    @Test
    void testLoopsMatchLogicalAndClosure() {
        assertComplexity(
                """
                fn method(x: i32) -> i32 {
                    let f = || { if x > 0 { 1 } else { 0 } };
                    'outer: for i in 0..x {
                        if x > 0 && i > 0 || i < 10 { break 'outer; }
                    }
                    while x > 0 { continue; }
                    match x { 1 => f(), _ => 0 }
                }
                """,
                "method",
                10);
    }

    @Test
    void testImplMethodComplexity() {
        assertComplexity(
                """
                struct S;
                impl S {
                    fn method(&self, x: i32) -> i32 {
                        if x > 0 { return 1; }
                        0
                    }
                }
                """,
                "method",
                1);
    }

    @Test
    void testDeepNestingDoesNotOverflow() {
        String source = "fn method(x: i32) -> i32 {\n"
                + "if x > 0 {\n".repeat(120)
                + "return 1;\n"
                + "}\n".repeat(120)
                + "0\n}";
        assertComplexity(source, "method", 7_260);
    }

    private void assertComplexity(String source, String functionName, int expected) {
        try (var project = InlineCoreProject.code(source, "src/lib.rs").build()) {
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
