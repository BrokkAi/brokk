package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestAnalyzer;
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

    @Test
    public void testExactShortNameIncludesParentClass() throws Exception {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                                package ai.brokk.gui;

                                public class Chrome {
                                    public static class AnalyzerStatusStrip {}
                                }
                                """,
                        "src/main/java/ai/brokk/gui/Chrome.java")
                .build()) {

            IAnalyzer analyzer = new JavaAnalyzer(testProject);

            List<CodeUnit> results = Completions.completeSymbols("Chrome", analyzer);
            var fqns = results.stream().map(CodeUnit::fqName).collect(java.util.stream.Collectors.toSet());

            assertTrue(
                    fqns.contains("ai.brokk.gui.Chrome"),
                    "Autocomplete should include the parent class FQN when query equals the short name");
        }
    }

    @Test
    public void testExactShortNameIncludesParentClass_withMethodOnly() throws Exception {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                                package ai.brokk.gui;

                                public class Chrome {
                                    public void analyze() {}
                                    private int helper() { return 42; }
                                    public static void main(String[] args) {}
                                }
                                """,
                        "src/main/java/ai/brokk/gui/Chrome.java")
                .build()) {

            IAnalyzer analyzer = new JavaAnalyzer(testProject);

            List<CodeUnit> results = Completions.completeSymbols("Chrome", analyzer);
            var fqns = results.stream().map(CodeUnit::fqName).collect(java.util.stream.Collectors.toSet());

            assertTrue(
                    fqns.contains("ai.brokk.gui.Chrome"),
                    "Autocomplete should include the parent class FQN for short-name query when only methods are present");
        }
    }

    @Test
    public void testExactShortNameIncludesParentClass_withFieldOnly() throws Exception {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                                package ai.brokk.gui;

                                public class Chrome {
                                    public static final int VERSION = 1;
                                    private String name = "chrome";
                                }
                                """,
                        "src/main/java/ai/brokk/gui/Chrome.java")
                .build()) {

            IAnalyzer analyzer = new JavaAnalyzer(testProject);

            List<CodeUnit> results = Completions.completeSymbols("Chrome", analyzer);
            var fqns = results.stream().map(CodeUnit::fqName).collect(java.util.stream.Collectors.toSet());

            assertTrue(
                    fqns.contains("ai.brokk.gui.Chrome"),
                    "Autocomplete should include the parent class FQN for short-name query when only fields are present");
        }
    }
}
