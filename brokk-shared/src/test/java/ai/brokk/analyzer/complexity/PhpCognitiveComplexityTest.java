package ai.brokk.analyzer.complexity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.testutil.InlineCoreProject;
import org.junit.jupiter.api.Test;

public class PhpCognitiveComplexityTest {

    @Test
    void testSimpleFunctionComplexity() {
        assertComplexity("<?php function method() { return 0; }", "method", 0);
    }

    @Test
    void testIfNestedIfAndElseIfComplexity() {
        assertComplexity(
                """
                <?php
                function method($a, $b) {
                    if ($a > 0) {
                        if ($b > 0) return 1;
                    } elseif ($a < 0) {
                        return -1;
                    }
                    return 0;
                }
                """,
                "method",
                4);
    }

    @Test
    void testLoopsSwitchCatchTernaryLogicalAndAnonymousFunction() {
        assertComplexity(
                """
                <?php
                function method($items, $x) {
                    $f = function() use ($x) { if ($x > 0) return 1; return 0; };
                    foreach ($items as $item) {
                        if ($x > 0 && $item || $x ?? false) break;
                    }
                    switch ($x) { case 1: return $f(); default: return 0; }
                    try { risky(); } catch (Exception $e) { recover(); }
                    return $x > 0 ? 1 : 0;
                }
                """,
                "method",
                11);
    }

    @Test
    void testMethodComplexity() {
        assertComplexity(
                """
                <?php
                class S {
                    function method($x) {
                        if ($x > 0) return 1;
                        return 0;
                    }
                }
                """,
                "method",
                1);
    }

    @Test
    void testDeepNestingDoesNotOverflow() {
        String source = "<?php function method($x) {\n"
                + "if ($x > 0) {\n".repeat(120)
                + "return 1;\n"
                + "}\n".repeat(120)
                + "}";
        assertComplexity(source, "method", 7_260);
    }

    private void assertComplexity(String source, String functionName, int expected) {
        try (var project = InlineCoreProject.code(source, "complexity_test.php").build()) {
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
