package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.testutil.CoreTestProject;
import ai.brokk.testutil.TestCodeProject;
import java.io.IOException;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class JavaLambdaAnalyzerTest {

    @Nullable
    private static JavaAnalyzer analyzer;

    @Nullable
    private static CoreTestProject testProject;

    @BeforeAll
    public static void setup() throws IOException {
        testProject = TestCodeProject.fromResourceDir("testcode-java", Languages.JAVA);
        analyzer = (JavaAnalyzer) new JavaAnalyzer(testProject).update();
    }

    @AfterAll
    public static void teardown() {
        if (testProject != null) {
            testProject.close();
        }
    }

    @Test
    public void discoversLambdaInsideNestedMethod_InterfaceDEFAULTLambda() {
        // Interface.DEFAULT contains: root -> { };
        final var maybeFile = AnalyzerUtil.getFileFor(analyzer, "Interface");
        assertTrue(maybeFile.isPresent(), "Should resolve file for class Interface");
        final var file = maybeFile.get();

        final var declarations = analyzer.getDeclarations(file);
        assertTrue(
                declarations.stream()
                        .filter(CodeUnit::isFunction)
                        .map(CodeUnit::fqName)
                        .anyMatch(name -> name.contains("$anon$")),
                "Expected at least one anonymous lambda declaration in Interface.java");
    }

    @Test
    public void discoversLambdaInsideNestedMethod_ifPresentLambda() {
        // AnonymousUsage.NestedClass.getSomething contains: ifPresent(s -> ...)
        final var maybeFile = AnalyzerUtil.getFileFor(analyzer, "AnonymousUsage");
        assertTrue(maybeFile.isPresent(), "Should resolve file for class AnonymousUsage");
        final var file = maybeFile.get();

        final var declarations = analyzer.getDeclarations(file);

        assertTrue(
                declarations.stream().filter(CodeUnit::isFunction).anyMatch(cu -> cu.fqName()
                        .equals("AnonymousUsage.NestedClass.getSomething$anon$15:37")),
                "Expected a lambda discovered under AnonymousUsage.NestedClass.getSomething with anon suffix");
    }

    @Test
    public void lambdaSource_InterfaceDEFAULT() {
        // Interface.DEFAULT contains: root -> { };
        final var maybeFile = AnalyzerUtil.getFileFor(analyzer, "Interface");
        assertTrue(maybeFile.isPresent(), "Should resolve file for class Interface");
        final var file = maybeFile.get();

        final var expectedPattern = java.util.regex.Pattern.compile("root\\s*->\\s*\\{\\s*\\}\\s*;?");
        boolean found = analyzer.getDeclarations(file).stream()
                .filter(CodeUnit::isFunction)
                .filter(cu -> analyzer.isAnonymousStructure(cu.fqName()))
                .map(cu -> analyzer.getSource(cu, false))
                .flatMap(java.util.Optional::stream)
                .map(String::trim)
                .anyMatch(src -> expectedPattern.matcher(src).find());

        assertTrue(found, "Expected to find lambda source matching: " + expectedPattern);
    }

    @Test
    public void lambdaSource_AnonymousUsage_ifPresent() {
        // AnonymousUsage.NestedClass.getSomething contains: ifPresent(s -> map.put("foo", "test"))
        final var methodCu = analyzer.getDefinitions("AnonymousUsage.NestedClass.getSomething").stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Should resolve method definition for getSomething"));
        final var lambdaCu = analyzer.getDirectChildren(methodCu).stream()
                .filter(CodeUnit::isFunction)
                .filter(cu -> analyzer.isAnonymousStructure(cu.fqName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected to find lambda CodeUnit inside getSomething"));

        final var srcOpt = analyzer.getSource(lambdaCu, false);
        assertTrue(srcOpt.isPresent(), "Should be able to fetch source for lambda");
        final var normalized = srcOpt.get().replaceAll("\\s+", " ").trim();
        assertEquals("s -> map.put(\"foo\", \"test\")", normalized, "Lambda source is incorrect");
    }

    @Test
    public void lambdaIsChildOfEnclosingMethod_getSomething() {
        // Verify the lambda is a direct child of the enclosing method
        final var maybeMethod = analyzer.getDefinitions("AnonymousUsage.NestedClass.getSomething").stream()
                .findFirst();
        assertTrue(maybeMethod.isPresent(), "Should resolve method definition for getSomething");
        final var methodCu = maybeMethod.get();

        final var children = analyzer.getDirectChildren(methodCu);
        assertTrue(
                children.stream()
                        .filter(CodeUnit::isFunction)
                        .anyMatch(cu -> analyzer.isAnonymousStructure(cu.fqName())),
                "Lambda should be a direct child of the enclosing method getSomething");
    }

    @Test
    public void lambdaIsAnon() {
        assertTrue(analyzer.isAnonymousStructure("AnonymousUsage.NestedClass.getSomething$anon$1:1"));
        assertFalse(analyzer.isAnonymousStructure("AnonymousUsage.NestedClass.getSomething"));
    }

    @Test
    public void lambdaNotInSearch_getSomething() {
        // Verify the lambda is not in a search result
        final var searchResult = analyzer.searchDefinitions("AnonymousUsage.NestedClass.getSomething.*");
        final var hasAnonInSearch = searchResult.stream()
                .filter(x -> x.fqName().contains("$anon$"))
                .iterator()
                .hasNext();
        assertFalse(hasAnonInSearch, "Should not return lambdas in NestedClass.getSomething");
    }
}
