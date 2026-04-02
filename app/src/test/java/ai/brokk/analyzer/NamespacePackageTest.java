package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
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

        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
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

        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
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

    @Test
    void testFallbackToDirectory(@TempDir Path tempDir) throws IOException {
        // File with no namespace should use directory
        var subdir = tempDir.resolve("src").resolve("utils");
        Files.createDirectories(subdir);
        var testFile = subdir.resolve("test.ts");
        Files.writeString(testFile, """
            export function helper() { }
            """);

        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
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

    @Test
    void testMixedNamespaceAndDirectory(@TempDir Path tempDir) throws IOException {
        // File in subdirectory with namespace should prefer namespace
        var subdir = tempDir.resolve("src").resolve("models");
        Files.createDirectories(subdir);
        var testFile = subdir.resolve("user.ts");
        Files.writeString(
                testFile,
                """
            namespace Domain.User {
                export class UserModel { }
            }
            """);

        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        var analyzer = new TypescriptAnalyzer(project);
        var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));

        var userModel = analyzer.getDeclarations(projectFile).stream()
                .filter(cu -> cu.fqName().contains("UserModel"))
                .filter(CodeUnit::isClass)
                .findFirst();

        assertTrue(userModel.isPresent(), "UserModel should be captured");
        assertEquals("Domain.User", userModel.get().packageName(), "Should prefer namespace over directory");
        logger.info("UserModel FQN: {}", userModel.get().fqName());
    }

    @Test
    void testVSCodeNamespacePattern(@TempDir Path tempDir) throws IOException {
        // Real pattern from VSCode codebase
        var testFile = tempDir.resolve("strings.ts");
        Files.writeString(
                testFile,
                """
            namespace strings {
                export function format(value: string, ...args: any[]): string {
                    return value;
                }

                export function escape(html: string): string {
                    return html;
                }
            }
            """);

        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        var analyzer = new TypescriptAnalyzer(project);
        var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));

        var declarations = analyzer.getDeclarations(projectFile);
        logger.info(
                "All declarations: {}",
                declarations.stream().map(CodeUnit::fqName).toList());

        var formatFunc = declarations.stream()
                .filter(cu -> cu.fqName().contains("format"))
                .filter(CodeUnit::isFunction)
                .findFirst();

        var escapeFunc = declarations.stream()
                .filter(cu -> cu.fqName().contains("escape"))
                .filter(CodeUnit::isFunction)
                .findFirst();

        assertTrue(formatFunc.isPresent(), "format function should be captured");
        assertTrue(escapeFunc.isPresent(), "escape function should be captured");

        assertEquals("strings", formatFunc.get().packageName(), "Should use namespace");
        assertEquals("strings", escapeFunc.get().packageName(), "Should use namespace");

        // Note: Current behavior includes namespace in both packageName and shortName
        // FQN format: packageName.shortName where shortName includes namespace
        assertTrue(formatFunc.get().fqName().contains("format"), "FQN should contain format");
        assertTrue(escapeFunc.get().fqName().contains("escape"), "FQN should contain escape");
    }

    @Test
    void testComplexNestedPattern(@TempDir Path tempDir) throws IOException {
        // Complex nesting: dotted namespace inside regular namespace
        var testFile = tempDir.resolve("complex.ts");
        Files.writeString(
                testFile,
                """
            namespace Outer {
                export namespace Inner.Deep {
                    export interface Config {
                        value: string;
                    }

                    export function setup() { }
                }
            }
            """);

        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        var analyzer = new TypescriptAnalyzer(project);
        var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));

        var declarations = analyzer.getDeclarations(projectFile);
        logger.info(
                "Complex nesting declarations: {}",
                declarations.stream().map(CodeUnit::fqName).toList());

        var configInterface = declarations.stream()
                .filter(cu -> cu.fqName().contains("Config"))
                .filter(CodeUnit::isClass)
                .findFirst();

        var setupFunc = declarations.stream()
                .filter(cu -> cu.fqName().contains("setup"))
                .filter(CodeUnit::isFunction)
                .findFirst();

        assertTrue(configInterface.isPresent(), "Config interface should be captured");
        assertTrue(setupFunc.isPresent(), "setup function should be captured");

        // Should combine Outer + Inner.Deep = Outer.Inner.Deep
        assertEquals("Outer.Inner.Deep", configInterface.get().packageName());
        assertEquals("Outer.Inner.Deep", setupFunc.get().packageName());
    }

    @Test
    void testModuleKeyword(@TempDir Path tempDir) throws IOException {
        // TypeScript also supports 'module' keyword (same as namespace in modern TS)
        // Note: The TreeSitter grammar uses 'internal_module' node type for both 'module' and 'namespace'
        var testFile = tempDir.resolve("module.ts");
        Files.writeString(
                testFile,
                """
            module MyModule {
                export function legacyFunc() { }
            }
            """);

        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        var analyzer = new TypescriptAnalyzer(project);
        var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));

        var declarations = analyzer.getDeclarations(projectFile);
        logger.info(
                "Module test declarations: {}",
                declarations.stream().map(CodeUnit::fqName).toList());

        var legacyFunc = declarations.stream()
                .filter(cu -> cu.fqName().contains("legacyFunc"))
                .filter(CodeUnit::isFunction)
                .findFirst();

        assertTrue(legacyFunc.isPresent(), "legacyFunc should be captured");
        // The function should be found with MyModule in its FQN (either as package or as part of name)
        assertTrue(
                legacyFunc.get().fqName().contains("MyModule"),
                "FQN should include MyModule: " + legacyFunc.get().fqName());
        assertTrue(
                legacyFunc.get().fqName().contains("legacyFunc"),
                "FQN should include legacyFunc: " + legacyFunc.get().fqName());
        logger.info("legacyFunc FQN: {}", legacyFunc.get().fqName());
    }
}
