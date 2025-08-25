package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        var completions = Completions.completeSymbols("do", mock);

        var values = toValues(completions);
        assertEquals(Set.of("a.b.Do"), values);
    }

    @Test
    public void testUnqualifiedRe() {
        var mock = new MockAnalyzer(tempDir);
        // Input "re" -> user wants to find "a.b.Do$Re" by partial name "Re"
        var completions = Completions.completeSymbols("re", mock);
        var values = toValues(completions);
        assertEquals(Set.of("a.b.Do$Re"), values);
    }

    @Test
    public void testNestedClassRe() {
        var mock = new MockAnalyzer(tempDir);
        var completions = Completions.completeSymbols("Re", mock);
        var values = toValues(completions);

        assertEquals(Set.of("a.b.Do$Re", "a.b.Do$Re$Sub"), values);
    }

    @Test
    public void testCamelCaseCompletion() {
        var mock = new MockAnalyzer(tempDir);
        // Input "CC" -> should match "test.CamelClass" due to camel case matching
        var completions = Completions.completeSymbols("CC", mock);
        var values = toValues(completions);
        assertEquals(Set.of("test.CamelClass"), values);

        completions = Completions.completeSymbols("cam", mock);
        values = toValues(completions);
        assertEquals(Set.of("test.CamelClass"), values);
    }

    @Test
    public void testShortNameCompletions() {
        var mock = new MockAnalyzer(tempDir);

        var completions = Completions.completeSymbols("Do", mock);
        assertEquals(Set.of("Do", "Do$Re", "Do$Re$Sub"), toShortValues(completions));
    }
}
