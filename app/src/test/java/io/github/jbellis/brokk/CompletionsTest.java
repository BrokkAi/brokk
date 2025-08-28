package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.testutil.MockAnalyzer;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class CompletionsTest {
    @TempDir
    Path tempDir;

    // Helper to extract values for easy assertion, preserving order by score
    private static List<String> toValues(List<CodeUnit> candidates) {
        return candidates.stream().map(CodeUnit::fqName).toList();
    }

    @Test
    public void testUnqualifiedInput() {
        var mock = new MockAnalyzer(tempDir);

        // Input "do" -> we want it to match "a.b.Do" and its methods
        var completions = Completions.completeSymbols("do", mock);

        var values = toValues(completions);
        assertEquals(List.of("a.b.Do", "a.b.Do.bar", "a.b.Do.foo"), values);
    }

    @Test
    public void testUnqualifiedRe() {
        var mock = new MockAnalyzer(tempDir);
        // Input "re" -> user wants to find "a.b.Do$Re" and its methods
        var completions = Completions.completeSymbols("re", mock);
        var values = toValues(completions);
        assertEquals(List.of("a.b.Do$Re", "a.b.Do$Re.baz"), values);
    }

    @Test
    public void testNestedClassRe() {
        var mock = new MockAnalyzer(tempDir);
        var completions = Completions.completeSymbols("Re", mock);
        var values = toValues(completions);

        assertEquals(List.of("a.b.Do$Re", "a.b.Do$Re$Sub", "a.b.Do$Re.baz", "a.b.Do$Re$Sub.qux"), values);
    }

    @Test
    public void testCamelCaseCompletion() {
        var mock = new MockAnalyzer(tempDir);
        // Input "CC" -> should match "test.CamelClass" and its methods due to camel case matching
        var completions = Completions.completeSymbols("CC", mock);
        var values = toValues(completions);
        assertEquals(List.of("test.CamelClass", "test.CamelClass.someMethod"), values);

        completions = Completions.completeSymbols("cam", mock);
        values = toValues(completions);
        assertEquals(List.of("test.CamelClass", "test.CamelClass.someMethod"), values);
    }

    @Test
    public void testShortNameCompletions() {
        var mock = new MockAnalyzer(tempDir);

        var completions = Completions.completeSymbols("Do", mock);
        assertEquals(
                List.of(
                        "a.b.Do",
                        "a.b.Do$Re",
                        "a.b.Do.bar",
                        "a.b.Do.foo",
                        "a.b.Do$Re$Sub",
                        "a.b.Do$Re.baz",
                        "a.b.Do$Re$Sub.qux"),
                toValues(completions));
    }

    @Test
    public void testClassWithDotSuffix() {
        var mock = new MockAnalyzer(tempDir);

        var completions = Completions.completeSymbols("Do.", mock);
        var values = toValues(completions);
        assertEquals(List.of("a.b.Do.bar", "a.b.Do.foo", "a.b.Do"), values);
    }
}
