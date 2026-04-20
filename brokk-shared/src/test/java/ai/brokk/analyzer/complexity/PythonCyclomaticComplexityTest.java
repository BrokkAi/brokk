package ai.brokk.analyzer.complexity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.testutil.InlineCoreProject;
import org.junit.jupiter.api.Test;

public class PythonCyclomaticComplexityTest {

    @Test
    void testPythonCyclomaticComplexity() throws Exception {
        String pythonSource =
                """
            def base_method():
                pass

            def if_else_method(x):
                if x > 0:
                    return 1
                else:
                    return 0

            def elif_method(x):
                if x > 10:
                    return 2
                elif x > 0:
                    return 1
                else:
                    return 0

            def loop_method(items):
                for item in items:
                    print(item)
                while True:
                    break

            def exception_method():
                try:
                    do_something()
                except ValueError:
                    handle_val()
                except Exception:
                    handle_exc()

            def boolean_op_method(a, b):
                if a and b:
                    pass
                if a or b:
                    pass

            def conditional_expr_method(x):
                return "high" if x > 10 else "low"

            def match_case_method(status):
                match status:
                    case 200:
                        return "OK"
                    case 404:
                        return "Not Found"
                    case _:
                        return "Error"
            """;

        try (var project =
                InlineCoreProject.code(pythonSource, "complexity_test.py").build()) {
            IAnalyzer analyzer = project.getAnalyzer();

            assertComplexity(analyzer, "base_method", 1);
            assertComplexity(analyzer, "if_else_method", 2);
            assertComplexity(analyzer, "elif_method", 3);
            assertComplexity(analyzer, "loop_method", 3);
            assertComplexity(analyzer, "exception_method", 3);
            assertComplexity(analyzer, "boolean_op_method", 5); // 1 base + 2 if + 1 'and' + 1 'or'
            assertComplexity(analyzer, "conditional_expr_method", 2);
            assertComplexity(analyzer, "match_case_method", 4); // 1 base + 3 cases
        }
    }

    private void assertComplexity(IAnalyzer analyzer, String functionName, int expected) {
        CodeUnit cu = analyzer.getAllDeclarations().stream()
                .filter(u -> u.isFunction() && u.identifier().equals(functionName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Function not found: " + functionName));

        int actual = analyzer.computeCyclomaticComplexity(cu);
        assertEquals(expected, actual, "Complexity for " + functionName);
    }
}
