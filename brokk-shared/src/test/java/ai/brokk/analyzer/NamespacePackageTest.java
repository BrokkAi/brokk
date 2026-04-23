package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.testutil.CoreTestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test to verify that TypeScript namespace-based package detection works correctly.
 */
public class NamespacePackageTest {

    private static final Logger logger = LoggerFactory.getLogger(NamespacePackageTest.class);

    @Test
    void testSimpleNamespace(@TempDir Path tempDir) throws IOException {
        var testFile = tempDir.resolve("test.ts");
        Files.writeString(
                testFile,
                """
            namespace MyApp {
                export function helper() { }
            }
            """);

        try (var project = new CoreTestProject(tempDir, Set.of(Languages.TYPESCRIPT))) {
            var analyzer = new TypescriptAnalyzer(project);
            var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));

            var helperFunc = analyzer.getDeclarations(projectFile).stream()
                    .filter(cu -> cu.fqName().contains("helper"))
                    .filter(CodeUnit::isFunction)
                    .findFirst();

            assertTrue(helperFunc.isPresent(), "helper function should be captured");
            assertEquals("MyApp", helperFunc.get().packageName(), "Should use namespace as package");
            logger.info("helper FQN: {}", helperFunc.get().fqName());
        }
    }

    @Test
    void testNestedNamespaces(@TempDir Path tempDir) throws IOException {
        var testFile = tempDir.resolve("test.ts");
        Files.writeString(
                testFile,
                """
            namespace A {
                export namespace B {
                    export class C { }
                }
            }
            """);

        try (var project = new CoreTestProject(tempDir, Set.of(Languages.TYPESCRIPT))) {
            var analyzer = new TypescriptAnalyzer(project);
            var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));

            var classC = analyzer.getDeclarations(projectFile).stream()
                    .filter(cu -> cu.fqName().contains("C"))
                    .filter(CodeUnit::isClass)
                    .findFirst();

            assertTrue(classC.isPresent(), "Class C should be captured");
            assertEquals("A.B", classC.get().packageName(), "Should use nested namespace path");
            logger.info("Class C FQN: {}", classC.get().fqName());
        }
    }

    @Test
    void testDottedNamespace(@TempDir Path tempDir) throws IOException {
        var testFile = tempDir.resolve("test.ts");
        Files.writeString(
                testFile,
                """
            namespace A.B.C {
                export function foo() { }
            }
            """);

        try (var project = new CoreTestProject(tempDir, Set.of(Languages.TYPESCRIPT))) {
            var analyzer = new TypescriptAnalyzer(project);
            var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));

            var fooFunc = analyzer.getDeclarations(projectFile).stream()
                    .filter(cu -> cu.fqName().contains("foo"))
                    .filter(CodeUnit::isFunction)
                    .findFirst();

            assertTrue(fooFunc.isPresent(), "foo function should be captured");
            assertEquals("A.B.C", fooFunc.get().packageName(), "Should parse dotted namespace");
            logger.info("foo FQN: {}", fooFunc.get().fqName());
        }
    }

    @Test
    void testFallbackToDirectory(@TempDir Path tempDir) throws IOException {
        var subdir = tempDir.resolve("src").resolve("utils");
        Files.createDirectories(subdir);
        var testFile = subdir.resolve("test.ts");
        Files.writeString(testFile, """
            export function helper() { }
            """);

        try (var project = new CoreTestProject(tempDir, Set.of(Languages.TYPESCRIPT))) {
            var analyzer = new TypescriptAnalyzer(project);
            var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));

            var helperFunc = analyzer.getDeclarations(projectFile).stream()
                    .filter(cu -> cu.fqName().contains("helper"))
                    .filter(CodeUnit::isFunction)
                    .findFirst();

            assertTrue(helperFunc.isPresent(), "helper function should be captured");
            assertEquals("src.utils", helperFunc.get().packageName(), "Should fall back to directory-based package");
            logger.info("helper FQN: {}", helperFunc.get().fqName());
        }
    }
}
