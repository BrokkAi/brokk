package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.prompts.EditBlockParser;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Comprehensive tests for BRK_CLASS and BRK_FUNCTION syntax-aware edit block features. Uses the existing test files
 * from testcode-java without modification, copying them to a temporary directory for each test to ensure isolation.
 */
public class EditBlockSyntaxTest {

    @TempDir
    Path tempDir;

    private JavaAnalyzer analyzer;
    private TestProject testProject;
    private Path sandboxPath;

    @BeforeEach
    void setupEach() throws IOException {
        final var testResourcesPath =
                Path.of("src/test/resources/testcode-java").toAbsolutePath().normalize();
        assertTrue(Files.exists(testResourcesPath), "Test resource directory 'testcode-java' not found.");

        sandboxPath = tempDir.resolve("testcode-java-sandbox");
        copyDir(testResourcesPath, sandboxPath);

        testProject = new TestProject(sandboxPath, Languages.JAVA);
        analyzer = new JavaAnalyzer(testProject);
    }

    @AfterEach
    void teardownEach() {
        if (testProject != null) {
            testProject.close();
        }
    }

    // ==================== BRK_FUNCTION Tests ====================

    @Test
    void testBrkFunction_SimpleMethod() throws Exception {
        var file = new ProjectFile(sandboxPath, "A.java");
        var ctx = createContext(Set.of(file));

        String response =
                """
                ```
                A.java
                <<<<<<< SEARCH
                BRK_FUNCTION A.method1
                =======
                void method1() {
                    System.out.println("modified");
                }
                >>>>>>> REPLACE
                ```
                """;

        var blocks = parseBlocks(response, ctx);
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertTrue(result.failedBlocks().isEmpty(), "Should succeed for unique method");
        var content = Files.readString(file.absPath());
        assertTrue(
                content.contains("modified"), "Expected " + file + " to contain 'modified'. Full content:\n" + content);
        // The original method1 contained "hello". This edit changed it to "modified".
        // The file still contains "hello" in method7, so asserting "not contains hello" for the whole file is
        // incorrect.
        // We only assert the change to method1.
        assertTrue(
                content.contains("System.out.println(\"modified\");"),
                "Expected method1 to be modified. Full content:\n" + content);
        // The original method1's specific 'System.out.println("hello");' line should be gone.
        // However, the test content still contains "System.out.println(\"hello\");" from A.AInner.AInnerInner.method7.
        // Therefore, a global assertion for absence is incorrect. We rely on the assertion for the new content.
    }

    @Test
    void testBrkFunction_OverloadedMethod_Fails() throws Exception {
        var file = new ProjectFile(sandboxPath, "A.java");
        var ctx = createContext(Set.of(file));

        String response =
                """
                ```
                A.java
                <<<<<<< SEARCH
                BRK_FUNCTION A.method2
                =======
                public String method2(String input) {
                    return "new_" + input;
                }
                >>>>>>> REPLACE
                ```
                """;

        var blocks = parseBlocks(response, ctx);
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertEquals(1, result.failedBlocks().size(), "Should fail for overloaded method");
        assertEquals(
                EditBlock.EditBlockFailureReason.AMBIGUOUS_MATCH,
                result.failedBlocks().getFirst().reason(),
                "Should fail with AMBIGUOUS_MATCH");
    }

    @Test
    void testBrkFunction_StaticMethod() throws Exception {
        var file = new ProjectFile(sandboxPath, "A.java");
        var ctx = createContext(Set.of(file));

        // Pre-check: ensure analyzer can find the target method
        assertNotNull(analyzer, "Analyzer should be initialized");
        var methodOpt = assertDoesNotThrow(() -> AnalyzerUtil.getMethodSource(analyzer, "A.method4", true));
        assertTrue(methodOpt.isPresent(), "Analyzer should locate A.method4");

        String response =
                """
                ```
                A.java
                <<<<<<< SEARCH
                BRK_FUNCTION A.method4
                =======
                public static int method4(double foo, Integer bar) {
                    return 42;
                }
                >>>>>>> REPLACE
                ```
                """;

        var blocks = parseBlocks(response, ctx);
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertTrue(result.failedBlocks().isEmpty(), "Should succeed for static method");
        var content = Files.readString(file.absPath());
        assertTrue(
                content.contains("return 42;"),
                "Expected " + file + " to contain 'return 42;'. Full content:\n" + content);
    }

    @Test
    void testBrkFunction_NestedClassMethod() throws Exception {
        var file = new ProjectFile(sandboxPath, "A.java");
        var ctx = createContext(Set.of(file));

        String response =
                """
                ```
                A.java
                <<<<<<< SEARCH
                BRK_FUNCTION A.AInner.AInnerInner.method7
                =======
                public void method7() {
                    System.out.println("nested modified");
                }
                >>>>>>> REPLACE
                ```
                """;

        var blocks = parseBlocks(response, ctx);
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertTrue(result.failedBlocks().isEmpty(), "Should succeed for nested class method");
        var content = Files.readString(file.absPath());
        assertTrue(
                content.contains("nested modified"),
                "Expected " + file + " to contain 'nested modified'. Full content:\n" + content);
    }

    @Test
    void testBrkFunction_Constructor() throws Exception {
        var file = new ProjectFile(sandboxPath, "B.java");
        var ctx = createContext(Set.of(file));

        String response =
                """
                ```
                B.java
                <<<<<<< SEARCH
                BRK_FUNCTION B.B
                =======
                public B() {
                    System.out.println("modified constructor");
                }
                >>>>>>> REPLACE
                ```
                """;

        var blocks = parseBlocks(response, ctx);
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertTrue(result.failedBlocks().isEmpty(), "Should succeed for constructor");
        var content = Files.readString(file.absPath());
        assertTrue(
                content.contains("modified constructor"),
                "Expected " + file + " to contain 'modified constructor'. Full content:\n" + content);
    }

    @Test
    void testBrkFunction_NonExistent_Fails() throws Exception {
        var file = new ProjectFile(sandboxPath, "A.java");
        var ctx = createContext(Set.of(file));

        String response =
                """
                ```
                A.java
                <<<<<<< SEARCH
                BRK_FUNCTION A.nonExistentMethod
                =======
                public void nonExistentMethod() {}
                >>>>>>> REPLACE
                ```
                """;

        var blocks = parseBlocks(response, ctx);
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertEquals(1, result.failedBlocks().size(), "Should fail for non-existent method");
        assertEquals(
                EditBlock.EditBlockFailureReason.NO_MATCH,
                result.failedBlocks().getFirst().reason(),
                "Should fail with NO_MATCH");
    }

    @Test
    void testBrkFunction_WrongClassName_Fails() throws Exception {
        var file = new ProjectFile(sandboxPath, "A.java");
        var ctx = createContext(Set.of(file));

        String response =
                """
                ```
                A.java
                <<<<<<< SEARCH
                BRK_FUNCTION B.method1
                =======
                void method1() {}
                >>>>>>> REPLACE
                ```
                """;

        var blocks = parseBlocks(response, ctx);
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertEquals(1, result.failedBlocks().size(), "Should fail for wrong class name");
        assertEquals(
                EditBlock.EditBlockFailureReason.NO_MATCH,
                result.failedBlocks().getFirst().reason(),
                "Should fail with NO_MATCH");
    }

    @Test
    void testBrkFunction_PackagedClass() throws Exception {
        var file = new ProjectFile(sandboxPath, "Packaged.java");
        var ctx = createContext(Set.of(file));

        String response =
                """
                ```
                Packaged.java
                <<<<<<< SEARCH
                BRK_FUNCTION io.github.jbellis.brokk.Foo.bar
                =======
                public void bar() {
                    System.out.println("modified bar");
                }
                >>>>>>> REPLACE
                ```
                """;

        var blocks = parseBlocks(response, ctx);
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertTrue(result.failedBlocks().isEmpty(), "Should succeed for packaged class method");
        var content = Files.readString(file.absPath());
        assertTrue(
                content.contains("modified bar"),
                "Expected " + file + " to contain 'modified bar'. Full content:\n" + content);
    }

    // ==================== BRK_CLASS Tests ====================

    @Test
    void testBrkClass_SimpleClass() throws Exception {
        var file = new ProjectFile(sandboxPath, "BaseClass.java");
        var ctx = createContext(Set.of(file));

        String response =
                """
                ```
                BaseClass.java
                <<<<<<< SEARCH
                BRK_CLASS BaseClass
                =======
                public class BaseClass {
                    public void parentMethod() {
                        System.out.println("modified parent");
                    }
                }
                >>>>>>> REPLACE
                ```
                """;

        var blocks = parseBlocks(response, ctx);
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertTrue(result.failedBlocks().isEmpty(), "Should succeed for simple class");
        var content = Files.readString(file.absPath());
        assertTrue(
                content.contains("modified parent"),
                "Expected " + file + " to contain 'modified parent'. Full content:\n" + content);
    }

    @Test
    void testBrkClass_ClassWithMultipleMethods() throws Exception {
        var file = new ProjectFile(sandboxPath, "B.java");
        var ctx = createContext(Set.of(file));

        String response =
                """
                ```
                B.java
                <<<<<<< SEARCH
                BRK_CLASS B
                =======
                public class B {
                    public B() {
                        System.out.println("new B");
                    }

                    public void newMethod() {
                        System.out.println("new method");
                    }
                }
                >>>>>>> REPLACE
                ```
                """;

        var blocks = parseBlocks(response, ctx);
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertTrue(result.failedBlocks().isEmpty(), "Should succeed for class with multiple methods");
        var content = Files.readString(file.absPath());
        assertTrue(
                content.contains("new method"),
                "Expected " + file + " to contain 'new method'. Full content:\n" + content);
        assertFalse(
                content.contains("callsIntoA"),
                "Expected " + file + " to not contain 'callsIntoA'. Full content:\n" + content);
    }

    @Test
    void testBrkClass_NestedClass() throws Exception {
        var file = new ProjectFile(sandboxPath, "A.java");
        var ctx = createContext(Set.of(file));

        // Pre-check: ensure analyzer can find the nested target class
        assertNotNull(analyzer, "Analyzer should be initialized");
        var clsOpt = assertDoesNotThrow(() -> AnalyzerUtil.getClassSource(analyzer, "A.AInner", true));
        assertTrue(clsOpt.isPresent(), "Analyzer should locate class A.AInner");

        String response =
                """
                ```
                A.java
                <<<<<<< SEARCH
                BRK_CLASS A.AInner
                =======
                public class AInner {
                    public void newInnerMethod() {
                        System.out.println("new inner");
                    }
                }
                >>>>>>> REPLACE
                ```
                """;

        var blocks = parseBlocks(response, ctx);
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertTrue(result.failedBlocks().isEmpty(), result.failedBlocks().toString());
        var content = Files.readString(file.absPath());
        assertTrue(
                content.contains("new inner"),
                "Expected " + file + " to contain 'new inner'. Full content:\n" + content);
        assertFalse(
                content.contains("AInnerInner"),
                "Expected " + file + " to not contain 'AInnerInner'. Full content:\n" + content);
    }

    @Test
    void testBrkClass_DoublyNestedClass() throws Exception {
        var file = new ProjectFile(sandboxPath, "A.java");
        var ctx = createContext(Set.of(file));

        // Pre-check: ensure analyzer can find the doubly nested target class
        assertNotNull(analyzer, "Analyzer should be initialized");
        var clsOpt = AnalyzerUtil.getClassSource(analyzer, "A.AInner.AInnerInner", true);
        assertTrue(clsOpt.isPresent(), "Analyzer should locate class A.AInner.AInnerInner");

        String response =
                """
                ```
                A.java
                <<<<<<< SEARCH
                BRK_CLASS A.AInner.AInnerInner
                =======
                public class AInnerInner {
                    public void newDoublyNestedMethod() {
                        System.out.println("doubly nested new");
                    }
                }
                >>>>>>> REPLACE
                ```
                """;

        var blocks = parseBlocks(response, ctx);
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertTrue(result.failedBlocks().isEmpty(), "Should succeed for doubly nested class");
        var content = Files.readString(file.absPath());
        assertTrue(
                content.contains("doubly nested new"),
                "Expected " + file + " to contain 'doubly nested new'. Full content:\n" + content);
    }

    @Test
    void testBrkClass_StaticNestedClass() throws Exception {
        var file = new ProjectFile(sandboxPath, "D.java");
        var ctx = createContext(Set.of(file));

        String response =
                """
                ```
                D.java
                <<<<<<< SEARCH
                BRK_CLASS D.DSubStatic
                =======
                private static class DSubStatic {
                    public void staticNestedMethod() {
                        System.out.println("static nested");
                    }
                }
                >>>>>>> REPLACE
                ```
                """;

        var blocks = parseBlocks(response, ctx);
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertTrue(result.failedBlocks().isEmpty(), "Should succeed for static nested class");
        var content = Files.readString(file.absPath());
        assertTrue(
                content.contains("static nested"),
                "Expected " + file + " to contain 'static nested'. Full content:\n" + content);
    }

    @Test
    void testBrkClass_EnumClass() throws Exception {
        var file = new ProjectFile(sandboxPath, "EnumClass.java");
        var ctx = createContext(Set.of(file));

        String response =
                """
                ```
                EnumClass.java
                <<<<<<< SEARCH
                BRK_CLASS EnumClass
                =======
                public enum EnumClass {
                    FOO, BAR, BAZ
                }
                >>>>>>> REPLACE
                ```
                """;

        var blocks = parseBlocks(response, ctx);
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertTrue(result.failedBlocks().isEmpty(), "Should succeed for enum class");
        var content = Files.readString(file.absPath());
        assertTrue(content.contains("BAZ"), "Expected " + file + " to contain 'BAZ'. Full content:\n" + content);
    }

    @Test
    void testBrkClass_RecordClass() throws Exception {
        var file = new ProjectFile(sandboxPath, "C.java");
        var ctx = createContext(Set.of(file));

        String response =
                """
                ```
                C.java
                <<<<<<< SEARCH
                BRK_CLASS C.Foo
                =======
                record Foo(int x, String name) {
                    public Foo {
                        System.out.println("Modified Foo constructor");
                    }
                }
                >>>>>>> REPLACE
                ```
                """;

        var blocks = parseBlocks(response, ctx);
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertTrue(result.failedBlocks().isEmpty(), "Should succeed for record class");
        var content = Files.readString(file.absPath());
        assertTrue(
                content.contains("Modified Foo constructor"),
                "Expected " + file + " to contain 'Modified Foo constructor'. Full content:\n" + content);
        assertTrue(
                content.contains("String name"),
                "Expected " + file + " to contain 'String name'. Full content:\n" + content);
    }

    @Test
    void testBrkClass_NonExistent_Fails() throws Exception {
        var file = new ProjectFile(sandboxPath, "A.java");
        var ctx = createContext(Set.of(file));

        // Pre-check: ensure analyzer reports the class is not found
        assertNotNull(analyzer, "Analyzer should be initialized");
        var missingClassOpt =
                assertDoesNotThrow(() -> AnalyzerUtil.getClassSource(analyzer, "A.NonExistentClass", true));
        assertTrue(missingClassOpt.isEmpty(), "Analyzer should report missing class A.NonExistentClass");

        String response =
                """
                ```
                A.java
                <<<<<<< SEARCH
                BRK_CLASS A.NonExistentClass
                =======
                public class NonExistentClass {}
                >>>>>>> REPLACE
                ```
                """;

        var blocks = parseBlocks(response, ctx);
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertEquals(1, result.failedBlocks().size(), "Should fail for non-existent class");
        assertEquals(
                EditBlock.EditBlockFailureReason.NO_MATCH,
                result.failedBlocks().getFirst().reason(),
                "Should fail with NO_MATCH");
    }

    @Test
    void testBrkClass_PackagedClass() throws Exception {
        var file = new ProjectFile(sandboxPath, "Packaged.java");
        var ctx = createContext(Set.of(file));

        String response =
                """
                ```
                Packaged.java
                <<<<<<< SEARCH
                BRK_CLASS io.github.jbellis.brokk.Foo
                =======
                public class Foo {
                    public void bar() {
                        System.out.println("modified packaged class");
                    }

                    public void newMethod() {
                        System.out.println("new method");
                    }
                }
                >>>>>>> REPLACE
                ```
                """;

        var blocks = parseBlocks(response, ctx);
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertTrue(result.failedBlocks().isEmpty(), "Should succeed for packaged class");
        var content = Files.readString(file.absPath());
        assertTrue(content.contains("modified packaged class"), "Packaged class should be updated");
        assertTrue(content.contains("newMethod"), "New method should be added");
    }

    // ==================== Edge Cases ====================

    @Test
    void testBrkFunction_WithComments() throws Exception {
        var file = new ProjectFile(sandboxPath, "A.java");
        var ctx = createContext(Set.of(file));

        String response =
                """
                ```
                A.java
                <<<<<<< SEARCH
                BRK_FUNCTION A.method2
                =======
                public String method2(String input) {
                    // New implementation
                    return "new_" + input;
                }

                public String method2(String input, int otherInput) {
                    // New overload implementation
                    return "new_" + input + " " + otherInput;
                }
                >>>>>>> REPLACE
                ```
                """;

        var blocks = parseBlocks(response, ctx);
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertEquals(1, result.failedBlocks().size(), "Should still fail for overloaded methods even with comments");
    }

    @Test
    void testBrkClass_WithAnnotations() throws Exception {
        var file = new ProjectFile(sandboxPath, "AnnotatedClass.java");
        var ctx = createContext(Set.of(file));

        String response =
                """
                ```
                AnnotatedClass.java
                <<<<<<< SEARCH
                BRK_CLASS AnnotatedClass.InnerHelper
                =======
                @CustomAnnotation("modified-inner-class")
                public static class InnerHelper {
                    @CustomAnnotation("modified-inner-method")
                    public String help(String message) {
                        return "Modified Helper: " + message;
                    }
                }
                >>>>>>> REPLACE
                ```
                """;

        var blocks = parseBlocks(response, ctx);
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertTrue(result.failedBlocks().isEmpty(), "Should succeed for annotated inner class");
        var content = Files.readString(file.absPath());
        assertTrue(
                content.contains("Modified Helper"),
                "Expected " + file + " to contain 'Modified Helper'. Full content:\n" + content);
    }

    @Test
    void testMultipleBrkEdits_SameFile() throws Exception {
        var file = new ProjectFile(sandboxPath, "E.java");
        var ctx = createContext(Set.of(file));

        // Pre-check: ensure analyzer can find both target methods in E
        assertNotNull(analyzer, "Analyzer should be initialized");
        var sMethod = assertDoesNotThrow(() -> AnalyzerUtil.getMethodSource(analyzer, "E.sMethod", true));
        var iMethod = assertDoesNotThrow(() -> AnalyzerUtil.getMethodSource(analyzer, "E.iMethod", true));
        assertTrue(sMethod.isPresent(), "Analyzer should locate E.sMethod");
        assertTrue(iMethod.isPresent(), "Analyzer should locate E.iMethod");

        String response =
                """
                ```
                E.java
                <<<<<<< SEARCH
                BRK_FUNCTION E.sMethod
                =======
                public static void sMethod() {
                    System.out.println("Modified static method");
                }
                >>>>>>> REPLACE
                ```

                ```
                E.java
                <<<<<<< SEARCH
                BRK_FUNCTION E.iMethod
                =======
                public void iMethod() {
                    System.out.println("Modified instance method");
                }
                >>>>>>> REPLACE
                ```
                """;

        var blocks = parseBlocks(response, ctx);
        var result = EditBlock.apply(ctx, new TestConsoleIO(), blocks);

        assertTrue(result.failedBlocks().isEmpty(), "Should succeed for multiple edits to same file");
        var content = Files.readString(file.absPath());
        assertTrue(
                content.contains("Modified static method"),
                "Expected " + file + " to contain 'Modified static method'. Full content:\n" + content);
        assertTrue(
                content.contains("Modified instance method"),
                "Expected " + file + " to contain 'Modified instance method'. Full content:\n" + content);
    }

    // ==================== Helper Methods ====================

    private TestContextManager createContext(Set<ProjectFile> files) {
        return new TestContextManager(testProject, new TestConsoleIO(), new HashSet<>(files), analyzer);
    }

    private List<EditBlock.SearchReplaceBlock> parseBlocks(String response, TestContextManager ctx) {
        return EditBlockParser.instance
                .parseEditBlocks(response, ctx.getFilesInContext())
                .blocks();
    }

    /**
     * Recursively copies a directory from source to destination.
     *
     * @param source The source directory path.
     * @param dest The destination directory path.
     * @throws IOException If an I/O error occurs during the copy operation.
     */
    private static void copyDir(Path source, Path dest) throws IOException {
        Files.walk(source).forEach(p -> {
            try {
                Path relativePath = source.relativize(p);
                Path destPath = dest.resolve(relativePath);
                if (Files.isDirectory(p)) {
                    Files.createDirectories(destPath);
                } else {
                    Files.copy(p, destPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to copy file or directory: " + p, e);
            }
        });
    }
}
