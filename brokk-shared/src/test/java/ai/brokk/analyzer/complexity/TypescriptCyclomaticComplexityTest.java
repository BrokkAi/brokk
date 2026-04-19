package ai.brokk.analyzer.complexity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.testutil.InlineCoreProject;
import java.io.IOException;
import org.junit.jupiter.api.Test;

public class TypescriptCyclomaticComplexityTest {

    @Test
    public void testBaseComplexity() throws IOException {
        assertComplexity(
                """
                function simple(): void {
                    console.log("hello");
                }
                """,
                1);
    }

    @Test
    public void testControlFlow() throws IOException {
        assertComplexity(
                """
                function flow(a: number, b: string | null): number {
                    if (a > 10 && b !== null) {
                        for (let i = 0; i < a; i++) {
                            if (i % 2 === 0) continue;
                        }
                    }
                    return b ?? "default" ? 1 : 0;
                }
                """,
                7);
        // base(1) + if(1) + &&(1) + for(1) + if(1) + ??(1) + ternary(1) = 7
    }

    @Test
    public void testSwitchCase() throws IOException {
        assertComplexity(
                """
                function handle(val: number): string {
                    switch(val) {
                        case 1: return 'a';
                        case 2:
                        case 3: return 'b';
                        default: return 'c';
                    }
                }
                """,
                4); // base(1) + case 1, 2, 3 (3) = 4
    }

    private void assertComplexity(String code, int expected) throws IOException {
        try (var testProject = InlineCoreProject.code(code, "test.ts").build()) {
            var analyzer = testProject.getAnalyzer();
            CodeUnit cu = analyzer.getAllDeclarations().stream()
                    .filter(CodeUnit::isFunction)
                    .findFirst()
                    .orElseThrow();
            assertEquals(
                    expected, analyzer.computeCyclomaticComplexity(cu), "Complexity mismatch for: " + cu.shortName());
        }
    }
}
