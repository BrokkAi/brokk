package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IContextManager;
import ai.brokk.analyzer.CodeUnit;
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
 * Tests for CallGraphFragment functionality.
 *
 * <p>These tests verify that CallGraphFragment correctly handles method lookup and call graph
 * generation, including edge cases with overloaded methods.
 */
public class CallGraphFragmentTest {

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
    void testSources_WithValidMethod() {
        IContextManager cm = createMockContextManager(javaAnalyzer);
        var fragment = new ContextFragment.CallGraphFragment(cm, "A.method1", 1, true);

        var sources = fragment.sources();

        assertFalse(sources.isEmpty(), "Should find source for valid method");
        assertEquals(1, sources.size(), "Should return exactly one CodeUnit");
        var cu = sources.iterator().next();
        assertEquals("A.method1", cu.fqName(), "Should match the requested method");
        assertTrue(cu.isFunction(), "Should be a function");
    }

    @Test
    void testSources_WithNonExistentMethod() {
        IContextManager cm = createMockContextManager(javaAnalyzer);
        var fragment = new ContextFragment.CallGraphFragment(cm, "NonExistent.method", 1, true);

        var sources = fragment.sources();

        assertTrue(sources.isEmpty(), "Should return empty set for non-existent method");
    }

    @Test
    void testSources_WithOverloadedMethod() {
        IContextManager cm = createMockContextManager(javaAnalyzer);
        // A.method2 has overloads in the test data
        var fragment = new ContextFragment.CallGraphFragment(cm, "A.method2", 1, true);

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
    void testText_WithNonExistentMethod() {
        IContextManager cm = createMockContextManager(javaAnalyzer);
        var fragment = new ContextFragment.CallGraphFragment(cm, "NonExistent.method", 1, true);

        String result = fragment.text();

        assertTrue(
                result.contains("Method not found"),
                "Should return 'Method not found' message for non-existent method");
        assertTrue(
                result.contains("NonExistent.method"),
                "Should include the method name in error message");
    }

    @Test
    void testFiles_WithValidMethod() {
        IContextManager cm = createMockContextManager(javaAnalyzer);
        var fragment = new ContextFragment.CallGraphFragment(cm, "A.method1", 1, true);

        var files = fragment.files();

        assertFalse(files.isEmpty(), "Should find file for valid method");
        assertTrue(
                files.stream().anyMatch(f -> f.toString().contains("A.java")),
                "Should include A.java file");
    }

    @Test
    void testRepr() {
        IContextManager cm = createMockContextManager(javaAnalyzer);

        var calleeFragment = new ContextFragment.CallGraphFragment(cm, "A.method1", 2, true);
        assertEquals(
                "CallGraph('A.method1', depth=2, direction=OUT)",
                calleeFragment.repr(),
                "Should format callee graph correctly");

        var callerFragment = new ContextFragment.CallGraphFragment(cm, "A.method1", 3, false);
        assertEquals(
                "CallGraph('A.method1', depth=3, direction=IN)",
                callerFragment.repr(),
                "Should format caller graph correctly");
    }

    @Test
    void testDescription() {
        IContextManager cm = createMockContextManager(javaAnalyzer);

        var calleeFragment = new ContextFragment.CallGraphFragment(cm, "A.method1", 2, true);
        assertEquals(
                "Callees of A.method1 (depth 2)",
                calleeFragment.description(),
                "Should describe callee graph correctly");

        var callerFragment = new ContextFragment.CallGraphFragment(cm, "A.method1", 3, false);
        assertEquals(
                "Callers of A.method1 (depth 3)",
                callerFragment.description(),
                "Should describe caller graph correctly");
    }
}
