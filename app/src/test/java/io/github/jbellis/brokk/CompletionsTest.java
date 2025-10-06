package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.testutil.TestAnalyzer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
        var mock = createTestAnalyzer();

        // Input "do" -> we want it to match "a.b.Do"
        // Because "Do" simple name starts with 'D'
        var completions = Completions.completeSymbols("do", mock);

        var values = toValues(completions);
        assertEquals(Set.of("a.b.Do"), values);
    }

    @Test
    public void testUnqualifiedRe() {
        var mock = createTestAnalyzer();
        // Input "re" -> user wants to find "a.b.Do$Re" by partial name "Re"
        var completions = Completions.completeSymbols("re", mock);
        var values = toValues(completions);
        assertEquals(Set.of("a.b.Do.Re"), values);
    }

    @Test
    public void testNestedClassRe() {
        var mock = createTestAnalyzer();
        var completions = Completions.completeSymbols("Re", mock);
        var values = toValues(completions);

        assertEquals(2, values.size());
        assertTrue(values.contains("a.b.Do.Re"));
        assertTrue(values.contains("a.b.Do.Re.Sub"));
    }

    @Test
    public void testCamelCaseCompletion() {
        var mock = createTestAnalyzer();

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
        var mock = createTestAnalyzer();

        var completions = Completions.completeSymbols("Do", mock);
        assertEquals(3, completions.size());
        var shortValues = toShortValues(completions);
        assertTrue(shortValues.contains("Do"));
        assertTrue(shortValues.contains("Do.Re"));
        assertTrue(shortValues.contains("Do.Re.Sub"));
    }

    @Test
    public void testArchCompletion() {
        var mock = createTestAnalyzer();
        var completions = Completions.completeSymbols("arch", mock);
        var values = toValues(completions);
        assertEquals(Set.of("a.b.Architect"), values);
    }

    private TestAnalyzer createTestAnalyzer() {
        ProjectFile mockFile = new ProjectFile(tempDir, "MockFile.java");
        var allClasses = List.of(
                CodeUnit.cls(mockFile, "a.b", "Do"),
                CodeUnit.cls(mockFile, "a.b", "Do.Re"),
                CodeUnit.cls(mockFile, "a.b", "Do.Re.Sub"), // nested inside Re
                CodeUnit.cls(mockFile, "x.y", "Zz"),
                CodeUnit.cls(mockFile, "w.u", "Zz"),
                CodeUnit.cls(mockFile, "test", "CamelClass"),
                CodeUnit.cls(mockFile, "a.b", "Architect"));
        Map<String, List<CodeUnit>> methodsMap = Map.ofEntries(
                Map.entry(
                        "a.b.Do",
                        List.of(CodeUnit.fn(mockFile, "a.b", "Do.foo"), CodeUnit.fn(mockFile, "a.b", "Do.bar"))),
                Map.entry("a.b.Do.Re", List.of(CodeUnit.fn(mockFile, "a.b", "Do.Re.baz"))),
                Map.entry("a.b.Do.Re.Sub", List.of(CodeUnit.fn(mockFile, "a.b", "Do.Re.Sub.qux"))),
                Map.entry("x.y.Zz", List.of()),
                Map.entry("w.u.Zz", List.of()),
                Map.entry("test.CamelClass", List.of(CodeUnit.fn(mockFile, "test", "CamelClass.someMethod"))));
        return new TestAnalyzer(allClasses, methodsMap);
    }
}
