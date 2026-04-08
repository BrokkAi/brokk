package ai.brokk.analyzer.complexity;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import org.junit.jupiter.api.Test;

public class JavascriptCyclomaticComplexityTest {

    @Test
    public void testBaseComplexity() throws IOException {
        assertComplexity(
                """
                function simple() {
                    console.log("hello");
                }
                """,
                1);
    }

    @Test
    public void testIfElse() throws IOException {
        assertComplexity(
                """
                function check(a) {
                    if (a > 0) {
                        return 1;
                    } else {
                        return 0;
                    }
                }
                """,
                2);
    }

    @Test
    public void testLoops() throws IOException {
        assertComplexity(
                """
                function loops(arr) {
                    for (let i = 0; i < 10; i++) {}
                    while (true) {}
                    do {} while (false);
                    for (const x in arr) {}
                }
                """,
                5);
    }

    @Test
    public void testSwitchCase() throws IOException {
        assertComplexity(
                """
                function handle(val) {
                    switch(val) {
                        case 1: return 'a';
                        case 2: return 'b';
                        default: return 'c';
                    }
                }
                """,
                3);
    }

    @Test
    public void testTernaryAndLogical() throws IOException {
        assertComplexity(
                """
                function complex(a, b) {
                    const x = a ? b : 1;
                    return (a && b) || (a ?? b);
                }
                """,
                5);
    }

    @Test
    public void testCatch() throws IOException {
        assertComplexity(
                """
                function safer() {
                    try {
                        risky();
                    } catch (e) {
                        log(e);
                    }
                }
                """,
                2);
    }

    private void assertComplexity(String code, int expected) throws IOException {
        try (var testProject = InlineTestProjectCreator.code(code, "test.js").build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            CodeUnit cu = analyzer.getAllDeclarations().stream()
                    .filter(CodeUnit::isFunction)
                    .findFirst()
                    .orElseThrow();
            assertEquals(
                    expected, analyzer.computeCyclomaticComplexity(cu), "Complexity mismatch for: " + cu.shortName());
        }
    }
}
