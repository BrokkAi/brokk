package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestAnalyzer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class CompletionsExactShortNameTest {

    @TempDir
    Path tempDir;

    /**
     * Fake analyzer that:
     * - returns only nested classes from autocompleteDefinitions (e.g., ai.brokk.gui.Chrome.AnalyzerStatusStrip)
     * - returns the parent class from getDefinitions("ai.brokk.gui.Chrome")
     */
    static class FakeAnalyzer extends TestAnalyzer {
        private final CodeUnit parent;
        private final CodeUnit nested;

        FakeAnalyzer(CodeUnit parent, CodeUnit nested) {
            super(List.of(), Map.of());
            this.parent = parent;
            this.nested = nested;
        }

        @Override
        public Set<CodeUnit> autocompleteDefinitions(String query) {
            // Simulate analyzer returning only nested members for autocomplete
            return Set.of(nested);
        }

        @Override
        public SequencedSet<CodeUnit> getDefinitions(String fqName) {
            if (fqName.equals(parent.fqName())) {
                return sortDefinitions(Set.of(parent));
            }
            return sortDefinitions(Set.of());
        }

        @Override
        public List<CodeUnit> getAllDeclarations() {
            // Force autocomplete path to be used; fallback should not add parent
            return List.of();
        }
    }

    @Test
    public void testExactShortNameIncludesParentClass() {
        ProjectFile mockFile = new ProjectFile(tempDir, "Chrome.java");
        CodeUnit parent = CodeUnit.cls(mockFile, "ai.brokk.gui", "Chrome");
        CodeUnit nested = CodeUnit.cls(mockFile, "ai.brokk.gui", "Chrome.AnalyzerStatusStrip");

        IAnalyzer analyzer = new FakeAnalyzer(parent, nested);

        var results = Completions.completeSymbols("Chrome", analyzer);
        var fqns = results.stream().map(CodeUnit::fqName).collect(java.util.stream.Collectors.toSet());

        assertTrue(
                fqns.contains("ai.brokk.gui.Chrome"),
                "Autocomplete should include the parent class FQN when query equals the short name");
    }
}
