package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IContextManager;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for SkeletonFragment functionality.
 *
 * <p>Focused on testing CodeUnit skeleton lookup behavior, including handling of overloaded
 * methods.
 */
public class SkeletonFragmentTest {

    private static JavaAnalyzer javaAnalyzer;
    private static TestProject javaTestProject;

    @BeforeAll
    static void setupAnalyzer() throws IOException {
        Path javaTestPath =
                Path.of("src/test/resources/testcode-java").toAbsolutePath().normalize();
        assertTrue(
                java.nio.file.Files.exists(javaTestPath),
                "Test resource directory 'testcode-java' not found.");
        javaTestProject = new TestProject(javaTestPath, Languages.JAVA);
        javaAnalyzer = new JavaAnalyzer(javaTestProject);
    }

    @AfterAll
    static void teardownAnalyzer() {
        if (javaTestProject != null) {
            javaTestProject.close();
        }
    }

    private IContextManager createMockContextManager(IAnalyzer analyzer) {
        return (IContextManager) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {IContextManager.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getAnalyzer" -> analyzer;
                        case "getAnalyzerUninterrupted" -> analyzer;
                        case "getProject" -> javaTestProject;
                        default -> throw new UnsupportedOperationException(
                                "Unexpected call: " + method.getName());
                    };
                });
    }

    @Test
    void testSources_WithValidClass() {
        IContextManager cm = createMockContextManager(javaAnalyzer);
        var fragment =
                new ContextFragment.SkeletonFragment(
                        cm, java.util.List.of("A"), ContextFragment.SummaryType.CODEUNIT_SKELETON);

        var sources = fragment.sources();

        assertFalse(sources.isEmpty(), "Should find source for valid class");
        assertEquals(1, sources.size(), "Should return exactly one CodeUnit for class");
        var cu = sources.iterator().next();
        assertEquals("A", cu.fqName(), "Should match the requested class");
        assertTrue(cu.isClass(), "Should be a class");
    }

    @Test
    void testSources_WithNonExistentSymbol() {
        IContextManager cm = createMockContextManager(javaAnalyzer);
        var fragment =
                new ContextFragment.SkeletonFragment(
                        cm,
                        java.util.List.of("NonExistent"),
                        ContextFragment.SummaryType.CODEUNIT_SKELETON);

        var sources = fragment.sources();

        assertTrue(sources.isEmpty(), "Should return empty set for non-existent symbol");
    }

    @Test
    void testSources_WithOverloadedMethod() {
        IContextManager cm = createMockContextManager(javaAnalyzer);
        // A.method2 has overloads in the test data
        var fragment =
                new ContextFragment.SkeletonFragment(
                        cm,
                        java.util.List.of("A.method2"),
                        ContextFragment.SummaryType.CODEUNIT_SKELETON);

        var sources = fragment.sources();

        // With current implementation (getDefinition), should return one overload
        // After migration to getDefinitions().findFirst(), behavior remains the same
        assertFalse(sources.isEmpty(), "Should find at least one overload");
        assertEquals(1, sources.size(), "Should return first overload only");
        var cu = sources.iterator().next();
        assertEquals("A.method2", cu.fqName(), "Should match the requested method name");
        assertTrue(cu.isFunction(), "Should be a function");
    }

    @Test
    void testText_WithNonExistentSymbol() {
        IContextManager cm = createMockContextManager(javaAnalyzer);
        var fragment =
                new ContextFragment.SkeletonFragment(
                        cm,
                        java.util.List.of("NonExistent"),
                        ContextFragment.SummaryType.CODEUNIT_SKELETON);

        String result = fragment.text();

        // SkeletonFragment returns "No summaries available" when no skeletons are found
        assertTrue(
                result.contains("No summaries available"),
                "Should return no summaries message for non-existent symbol");
    }

    @Test
    void testText_WithValidClass() {
        IContextManager cm = createMockContextManager(javaAnalyzer);
        var fragment =
                new ContextFragment.SkeletonFragment(
                        cm, java.util.List.of("A"), ContextFragment.SummaryType.CODEUNIT_SKELETON);

        String result = fragment.text();

        assertFalse(result.isEmpty(), "Should return non-empty skeleton for valid class");
        assertTrue(result.contains("class A"), "Should contain class declaration");
        // Skeleton should not contain method bodies
        assertFalse(
                result.contains("System.out"), "Skeleton should not contain method body details");
    }
}
