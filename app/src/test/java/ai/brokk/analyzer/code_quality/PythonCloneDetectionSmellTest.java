package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import org.junit.jupiter.api.Test;

public class PythonCloneDetectionSmellTest extends AbstractCloneDetectionSmellTest {

    @Test
    void flagsRenamedVariableCloneInPython() {
        String a =
                """
                def alpha(value):
                    total = value + 2
                    if total > 20:
                        return total * 3
                    return total - 4
                """;
        String b =
                """
                def beta(seed):
                    amount = seed + 2
                    if amount > 20:
                        return amount * 3
                    return amount - 4
                """;
        var findings = analyze("pkg/a.py", a, "pkg/b.py", b, IAnalyzer.CloneSmellWeights.defaults());
        assertFalse(findings.isEmpty());
        assertTrue(findings.stream()
                .anyMatch(f -> f.enclosingFqName().contains("alpha")
                        && f.peerEnclosingFqName().contains("beta")));
    }

    @Test
    void smallSnippetSuppressedByMinTokenThreshold() {
        String a = """
                def alpha(x):
                    return x + 1
                """;
        String b = """
                def beta(y):
                    return y + 1
                """;
        var strictWeights = new IAnalyzer.CloneSmellWeights(30, 50, 2, 2, 70);
        var findings = analyze("pkg/a.py", a, "pkg/b.py", b, strictWeights);
        assertTrue(findings.isEmpty());
    }
}
