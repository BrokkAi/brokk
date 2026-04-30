package ai.brokk.analyzer.complexity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.testutil.InlineCoreProject;
import org.junit.jupiter.api.Test;

public class ScalaCognitiveComplexityTest {

    @Test
    void testSimpleFunctionComplexity() {
        assertComplexity("def method(): Int = 0", "method", 0);
    }

    @Test
    void testIfNestedIfAndElseIfComplexity() {
        assertComplexity(
                """
                def method(a: Int, b: Int): Int = {
                  if (a > 0) {
                    if (b > 0) return 1
                  } else if (a < 0) {
                    return -1
                  }
                  0
                }
                """,
                "method",
                4);
    }

    @Test
    void testLoopsMatchCatchLogicalAndLambda() {
        assertComplexity(
                """
                def method(xs: List[Int], x: Int): Int = {
                  val f = (y: Int) => { if (y > 0) 1 else 0 }
                  for (item <- xs) {
                    if (x > 0 && item > 0 || item < 10) return f(item)
                  }
                  try risky() catch { case _: Exception => recover() }
                  x match { case 1 => f(x); case _ => 0 }
                }
                """,
                "method",
                9);
    }

    @Test
    void testClassMethodComplexity() {
        assertComplexity(
                """
                class S {
                  def method(x: Int): Int = {
                    if (x > 0) 1 else 0
                  }
                }
                """,
                "method",
                1);
    }

    @Test
    void testDeepNestingDoesNotOverflow() {
        String source = "def method(x: Int): Int = {\n"
                + "if (x > 0) {\n".repeat(120)
                + "return 1\n"
                + "}\n".repeat(120)
                + "0\n}";
        assertComplexity(source, "method", 7_260);
    }

    private void assertComplexity(String source, String functionName, int expected) {
        try (var project =
                InlineCoreProject.code(source, "complexity_test.scala").build()) {
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
