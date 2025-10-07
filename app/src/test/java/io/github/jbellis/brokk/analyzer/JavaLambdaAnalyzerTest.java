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
                        .startsWith("Interface.Interface$anon$")),
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
                        .startsWith("AnonymousUsage.NestedClass.getSomething$anon$")),
                "Expected a lambda discovered under AnonymousUsage.NestedClass.getSomething with anon suffix");
    }
}
