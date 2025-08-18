package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class CompletionsTest {
    @TempDir
    Path tempDir;

    private static class MockAnalyzer implements IAnalyzer {
        private final ProjectFile mockFile;
        private final List<CodeUnit> allClasses;
        private final Map<String, List<CodeUnit>> methodsMap;

        MockAnalyzer(Path rootDir) {
            this.mockFile = new ProjectFile(rootDir, "MockFile.java");
            this.allClasses = List.of(
                    CodeUnit.cls(mockFile, "a.b", "Do"),
                    CodeUnit.cls(mockFile, "a.b", "Do$Re"),
                    CodeUnit.cls(mockFile, "a.b", "Do$Re$Sub"), // nested inside Re
                    CodeUnit.cls(mockFile, "x.y", "Zz"),
                    CodeUnit.cls(mockFile, "w.u", "Zz"),
                    CodeUnit.cls(mockFile, "test", "CamelClass"));
            this.methodsMap = Map.ofEntries(
                    Map.entry(
                            "a.b.Do",
                            List.of(CodeUnit.fn(mockFile, "a.b", "Do.foo"), CodeUnit.fn(mockFile, "a.b", "Do.bar"))),
                    Map.entry("a.b.Do$Re", List.of(CodeUnit.fn(mockFile, "a.b", "Do$Re.baz"))),
                    Map.entry("a.b.Do$Re$Sub", List.of(CodeUnit.fn(mockFile, "a.b", "Do$Re$Sub.qux"))),
                    Map.entry("x.y.Zz", List.of()),
                    Map.entry("w.u.Zz", List.of()),
                    Map.entry("test.CamelClass", List.of(CodeUnit.fn(mockFile, "test", "CamelClass.someMethod"))));
        }

        @Override
        public List<CodeUnit> getAllDeclarations() {
            return allClasses;
        }

        @Override
        public List<CodeUnit> getMembersInClass(String fqClass) {
            return methodsMap.getOrDefault(fqClass, List.of());
        }

        @Override
        public List<CodeUnit> searchDefinitions(String pattern) {
            if (".*".equals(pattern)) {
                return Stream.concat(allClasses.stream(),
                                     methodsMap.values().stream().flatMap(List::stream))
                        .toList();
            }

            var regex = "^(?i)" + pattern + "$";

            // Find matching classes
            var matchingClasses = allClasses.stream()
                    .filter(cu -> cu.fqName().matches(regex))
                    .toList();

            // Find matching methods
            var matchingMethods = methodsMap.entrySet().stream()
                    .flatMap(entry -> entry.getValue().stream())
                    .filter(cu -> cu.fqName().matches(regex))
                    .toList();

            return Stream.concat(matchingClasses.stream(), matchingMethods.stream())
                    .toList();
        }
    }

    // Helper to extract values for easy assertion
    private static Set<String> toValues(List<CodeUnit> candidates) {
        return candidates.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
    }

    private static Set<String> toShortValues(List<CodeUnit> candidates) {
        return candidates.stream().map(CodeUnit::identifier).collect(Collectors.toSet());
    }

    @Test
    public void testUnqualifiedSingleLetter() {
        var mock = new MockAnalyzer(tempDir);

        // Input "d" -> we want it to match "a.b.Do"
        // Because "Do" simple name starts with 'D'
        var completions = Completions.completeSymbols("d", mock);

        var values = toValues(completions);
        assertEquals(Set.of("a.b.Do", "a.b.Do$Re", "a.b.Do$Re$Sub"), values);
    }

    @Test
    public void testUnqualifiedR() {
        var mock = new MockAnalyzer(tempDir);
        // Input "r" -> user wants to find "a.b.Do$Re" by partial name "Re"
        var completions = Completions.completeSymbols("r", mock);
        var values = toValues(completions);
        assertEquals(Set.of("a.b.Do$Re", "a.b.Do$Re$Sub", "a.b.Do.bar"), values);
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
