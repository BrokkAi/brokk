package io.github.jbellis.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class JavaLambdaAnalyzerTest {

    @Nullable
    private static JavaTreeSitterAnalyzer analyzer;

    @Nullable
    private static TestProject testProject;

    @BeforeAll
    public static void setup() throws IOException {
        final var testPath =
                Path.of("src/test/resources/testcode-java").toAbsolutePath().normalize();
        assertTrue(Files.exists(testPath), "Test resource directory 'testcode-java' not found.");
        testProject = new TestProject(testPath, Languages.JAVA);
        analyzer = new JavaTreeSitterAnalyzer(testProject);
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
        final var maybeFile = analyzer.getFileFor("Interface");
        assertTrue(maybeFile.isPresent(), "Should resolve file for class Interface");
        final var file = maybeFile.get();

        final var declarations = analyzer.getDeclarationsInFile(file);

        assertTrue(
                declarations.stream().filter(CodeUnit::isFunction).anyMatch(cu -> cu.fqName()
                        .equals("Interface.Interface$anon$5:24")),
                "Expected a lambda discovered under AnonymousUsage.NestedClass.getSomething with anon suffix");
    }

    @Test
    public void discoversLambdaInsideNestedMethod_ifPresentLambda() {
        // AnonymousUsage.NestedClass.getSomething contains: ifPresent(s -> ...)
        final var maybeFile = analyzer.getFileFor("AnonymousUsage");
        assertTrue(maybeFile.isPresent(), "Should resolve file for class AnonymousUsage");
        final var file = maybeFile.get();

        final var declarations = analyzer.getDeclarationsInFile(file);

        assertTrue(
                declarations.stream().filter(CodeUnit::isFunction).anyMatch(cu -> cu.fqName()
                        .equals("AnonymousUsage.NestedClass.getSomething$anon$15:37")),
                "Expected a lambda discovered under AnonymousUsage.NestedClass.getSomething with anon suffix");
    }

    @Test
    public void lambdaSource_InterfaceDEFAULT() {
        // Interface.DEFAULT contains: root -> { };
        final var maybeFile = analyzer.getFileFor("Interface");
        assertTrue(maybeFile.isPresent(), "Should resolve file for class Interface");
        final var file = maybeFile.get();

        final var maybeLambda = analyzer.getDeclarationsInFile(file).stream()
                .filter(CodeUnit::isFunction)
                .filter(cu -> cu.fqName().equals("Interface.Interface$anon$5:24"))
                .findFirst();

        assertTrue(maybeLambda.isPresent(), "Expected to find lambda CodeUnit for Interface.DEFAULT");
        final var lambdaCu = maybeLambda.get();

        final var srcOpt = analyzer.getSourceForCodeUnit(lambdaCu, false);
        assertTrue(srcOpt.isPresent(), "Should be able to fetch source for lambda");
        final var src = srcOpt.get();

        assertTrue(src.equals("root -> { }"), "Lambda source is incorrect");
    }

    @Test
    public void lambdaSource_AnonymousUsage_ifPresent() {
        // AnonymousUsage.NestedClass.getSomething contains: ifPresent(s -> map.put("foo", "test"))
        final var maybeFile = analyzer.getFileFor("AnonymousUsage");
        assertTrue(maybeFile.isPresent(), "Should resolve file for class AnonymousUsage");
        final var file = maybeFile.get();

        final var maybeLambda = analyzer.getDeclarationsInFile(file).stream()
                .filter(CodeUnit::isFunction)
                .filter(cu -> cu.fqName().equals("AnonymousUsage.NestedClass.getSomething$anon$15:37"))
                .findFirst();

        assertTrue(maybeLambda.isPresent(), "Expected to find lambda CodeUnit inside getSomething");
        final var lambdaCu = maybeLambda.get();

        final var srcOpt = analyzer.getSourceForCodeUnit(lambdaCu, false);
        assertTrue(srcOpt.isPresent(), "Should be able to fetch source for lambda");
        final var src = srcOpt.get();

        assertTrue(src.equals("s -> map.put(\"foo\", \"test\")"), "Lambda source is incorrect");
    }

    @Test
    public void lambdaIsChildOfEnclosingMethod_getSomething() {
        // Verify the lambda is a direct child of the enclosing method
        final var maybeMethod = analyzer.getDefinition("AnonymousUsage.NestedClass.getSomething");
        assertTrue(maybeMethod.isPresent(), "Should resolve method definition for getSomething");
        final var methodCu = maybeMethod.get();

        final var children = analyzer.directChildren(methodCu);
        assertTrue(
                children.stream().filter(CodeUnit::isFunction).anyMatch(cu -> cu.fqName()
                        .equals("AnonymousUsage.NestedClass.getSomething$anon$15:37")),
                "Lambda should be a direct child of the enclosing method getSomething");
    }
}
