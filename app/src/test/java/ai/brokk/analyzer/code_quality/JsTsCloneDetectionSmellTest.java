package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import org.junit.jupiter.api.Test;

public class JsTsCloneDetectionSmellTest extends AbstractCloneDetectionSmellTest {

    @Test
    void flagsRenamedVariableCloneInTypeScript() {
        String a =
                """
                export function alpha(input: number): number {
                  const total = input + 2;
                  if (total > 20) {
                    return total * 3;
                  }
                  return total - 4;
                }
                """;
        String b =
                """
                export function beta(seed: number): number {
                  const amount = seed + 2;
                  if (amount > 20) {
                    return amount * 3;
                  }
                  return amount - 4;
                }
                """;
        var findings = analyze("src/a.ts", a, "src/b.ts", b, IAnalyzer.CloneSmellWeights.defaults());
        assertFalse(findings.isEmpty());
        assertTrue(findings.stream()
                .anyMatch(f -> f.enclosingFqName().contains("alpha")
                        && f.peerEnclosingFqName().contains("beta")));
    }

    @Test
    void tinyFunctionsCanBeIgnoredByMinTokens() {
        String a =
                """
                export function alpha(x: number): number {
                  return x + 1;
                }
                """;
        String b =
                """
                export function beta(y: number): number {
                  return y + 1;
                }
                """;
        var strictWeights = new IAnalyzer.CloneSmellWeights(40, 50, 2, 2, 70);
        var findings = analyze("src/a.ts", a, "src/b.ts", b, strictWeights);
        assertTrue(findings.isEmpty());
    }
}
