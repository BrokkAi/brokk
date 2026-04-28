package ai.brokk.analyzer.complexity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.testutil.InlineCoreProject;
import org.junit.jupiter.api.Test;

public class PythonCognitiveComplexityTest {

    @Test
    void testSimpleFunctionComplexity() {
        assertComplexity("""
                def method():
                    pass
                """, "method", 0);
    }

    @Test
    void testIfComplexity() {
        assertComplexity(
                """
                def method(a):
                    if a:
                        print(a)
                """,
                "method",
                1);
    }

    @Test
    void testNestedIfComplexity() {
        assertComplexity(
                """
                def method(a, b):
                    if a:
                        if b:
                            print(b)
                """,
                "method",
                3);
    }

    @Test
    void testElifDoesNotAddNestingComplexity() {
        assertComplexity(
                """
                def method(x):
                    if x > 0:
                        return 1
                    elif x < 0:
                        return -1
                    else:
                        return 0
                """,
                "method",
                2);
    }

    @Test
    void testElseBlockWithIfTraversesAllStatements() {
        assertComplexity(
                """
                def method(a, b, c):
                    if a:
                        pass
                    else:
                        if b:
                            pass
                        while c:
                            pass
                """,
                "method",
                5);
    }

    @Test
    void testLoopsComplexity() {
        assertComplexity(
                """
                def method(items, ready):
                    for item in items:
                        print(item)
                    while ready:
                        break
                """,
                "method",
                2);
    }

    @Test
    void testTryExceptComplexity() {
        assertComplexity(
                """
                def method():
                    try:
                        do_something()
                    except ValueError:
                        handle_value()
                    except Exception:
                        handle_exception()
                """,
                "method",
                2);
    }

    @Test
    void testBooleanOperatorSequencesComplexity() {
        assertComplexity(
                """
                def method(a, b, c):
                    if a and b or c:
                        pass
                """,
                "method",
                3);
    }

    @Test
    void testConditionalExpressionComplexity() {
        assertComplexity(
                """
                def method(x):
                    return "high" if x > 10 else "low"
                """,
                "method",
                1);
    }

    @Test
    void testMatchCaseComplexity() {
        assertComplexity(
                """
                def method(status):
                    match status:
                        case 200:
                            return "OK"
                        case 404:
                            return "Not Found"
                        case _:
                            return "Error"
                """,
                "method",
                3);
    }

    @Test
    void testLambdaBodyCountsInsideEnclosingFunction() {
        assertComplexity(
                """
                def method(a):
                    f = lambda value: 1 if a else 0
                    return f(1)
                """,
                "method",
                2);
    }

    @Test
    void testNestedFunctionBodyDoesNotCountInsideEnclosingFunction() {
        String source =
                """
                def outer(a, b):
                    def helper():
                        if a:
                            if b:
                                return 1
                        return 0
                    return helper()
                """;
        assertComplexity(source, "outer", 0);
    }

    @Test
    void testDeepNestingDoesNotOverflowStack() {
        int depth = 400;
        var code = new StringBuilder("def method(a):\n");
        for (int i = 0; i < depth; i++) {
            code.append("    ".repeat(i + 1)).append("if a:\n");
        }
        code.append("    ".repeat(depth + 1)).append("print(a)\n");

        assertDoesNotThrow(() -> assertComplexity(code.toString(), "method", depth * (depth + 1) / 2));
    }

    private void assertComplexity(String source, String functionName, int expected) {
        try (var project = InlineCoreProject.code(source, "complexity_test.py").build()) {
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
