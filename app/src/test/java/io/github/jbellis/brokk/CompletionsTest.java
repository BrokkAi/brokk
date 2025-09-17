package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.testutil.MockAnalyzer;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class CompletionsTest {
    @TempDir
    Path tempDir;

    // Helper to extract values for easy assertion
    private static Set<String> toValues(List<CodeUnit> candidates) {
        return candidates.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
    }

    private static Set<String> toShortValues(List<CodeUnit> candidates) {
        return candidates.stream().map(CodeUnit::identifier).collect(Collectors.toSet());
    }

    @Test
    public void testUnqualifiedInput() {
        var mock = new MockAnalyzer(tempDir);

        // Input "do" -> we want it to match "a.b.Do"
        // Because "Do" simple name starts with 'D'
        var completions = Completions.completeSymbols("do", mock, CodeUnit.NameType.IDENTIFIER);

        var values = toValues(completions);
        assertEquals(Set.of("a.b.Do"), values);
    }

    @Test
    public void testUnqualifiedRe() {
        var mock = new MockAnalyzer(tempDir);
        // Input "re" -> user wants to find "a.b.Do$Re" by partial name "Re"
        var completions = Completions.completeSymbols("re", mock, CodeUnit.NameType.IDENTIFIER);
        var values = toValues(completions);
        assertEquals(Set.of("a.b.Do.Re"), values);
    }

    @Test
    public void testNestedClassRe() {
        var mock = new MockAnalyzer(tempDir);
        var completions = Completions.completeSymbols("Re", mock, CodeUnit.NameType.IDENTIFIER);
        var values = toValues(completions);

        assertEquals(2, values.size());
        assertTrue(values.contains("a.b.Do.Re"));
        assertTrue(values.contains("a.b.Do.Re.Sub"));
    }

    @Test
    public void testCamelCaseCompletion() {
        var mock = new MockAnalyzer(tempDir) {
            @Override
            public List<CodeUnit> autocompleteDefinitions(String query) {
                // give all for the sake of testing camel case fuzzy matching
                return super.autocompleteDefinitions(".*");
            }
        };
        // Input "CC" -> should match "test.CamelClass" due to camel case matching
        var completions = Completions.completeSymbols("CC", mock, CodeUnit.NameType.IDENTIFIER);
        var values = toValues(completions);
        assertEquals(Set.of("test.CamelClass"), values);

        completions = Completions.completeSymbols("cam", mock, CodeUnit.NameType.IDENTIFIER);
        values = toValues(completions);
        assertEquals(Set.of("test.CamelClass"), values);
    }

    @Test
    public void testShortNameCompletions() {
        var mock = new MockAnalyzer(tempDir);

        var completions = Completions.completeSymbols("Do", mock, CodeUnit.NameType.IDENTIFIER);
        assertEquals(3, completions.size());
        var shortValues = toShortValues(completions);
        assertTrue(shortValues.contains("Do"));
        assertTrue(shortValues.contains("Do.Re"));
        assertTrue(shortValues.contains("Do.Re.Sub"));
    }

    @Test
    public void testArchCompletion() {
        var mock = new MockAnalyzer(tempDir);
        var completions = Completions.completeSymbols("arch", mock, CodeUnit.NameType.IDENTIFIER);
        var values = toValues(completions);
        assertEquals(Set.of("a.b.Architect"), values);
    }
}
