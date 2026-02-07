package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.usages.UsageHit;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

public class AnalyzerUtilTest {

    @Test
    void testSampleUsageHitsSelection() {
        // Mocking IAnalyzer to return specific source lengths
        IAnalyzer mockAnalyzer = new ai.brokk.analyzer.DisabledAnalyzer() {
            @Override
            public Optional<String> getSource(CodeUnit codeUnit, boolean includeComments) {
                // Return a string of length equal to the shortName parsed as int
                return Optional.of(" ".repeat(Integer.parseInt(codeUnit.shortName())));
            }
        };

        ProjectFile pf = new ProjectFile(Path.of("/"), Path.of("Test.java"));
        CodeUnit overload = CodeUnit.fn(pf, "pkg", "method");

        Set<UsageHit> hits = new HashSet<>();
        // Create 5 hits with enclosing source lengths: 10, 20, 30, 40, 50
        for (int i = 1; i <= 5; i++) {
            CodeUnit enclosing = CodeUnit.fn(pf, "pkg", String.valueOf(i * 10));
            hits.add(new UsageHit(pf, i, 0, 0, enclosing, 1.0, "snippet"));
        }

        Map<CodeUnit, Set<UsageHit>> input = Map.of(overload, hits);
        Map<CodeUnit, List<UsageHit>> result = AnalyzerUtil.sampleUsageHits(input, mockAnalyzer);

        List<UsageHit> sampled = result.get(overload);
        assertEquals(3, sampled.size());

        // Shortest (10), Median (30), Longest (50)
        assertEquals("10", sampled.get(0).enclosing().shortName());
        assertEquals("30", sampled.get(1).enclosing().shortName());
        assertEquals("50", sampled.get(2).enclosing().shortName());
    }
}
