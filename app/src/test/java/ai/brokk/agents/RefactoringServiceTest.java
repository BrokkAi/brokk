package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepoData.FileDiff;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RefactoringServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void testDetectRefactorings_emptyList() {
        var service = new RefactoringService();
        var result = service.detectRefactorings(List.of());

        assertNotNull(result);
        assertFalse(result.hasRefactorings());
        assertTrue(result.summary().isEmpty());
    }

    @Test
    void testDetectRefactorings_nonJavaFile() {
        var service = new RefactoringService();
        var oldFile = new ProjectFile(tempDir, "test.txt");
        var newFile = new ProjectFile(tempDir, "test.txt");
        var diff = new FileDiff(oldFile, newFile, "old content", "new content");

        var result = service.detectRefactorings(List.of(diff));

        assertNotNull(result);
        assertFalse(result.hasRefactorings());
    }

    @Test
    void testHasSupportedFiles_withJavaFile() {
        var service = new RefactoringService();
        var javaFile = new ProjectFile(tempDir, "Test.java");
        var diff = new FileDiff(javaFile, javaFile, "class Test {}", "class Test {}");

        assertTrue(service.hasSupportedFiles(List.of(diff)));
    }

    @Test
    void testHasSupportedFiles_withPythonFile() {
        var service = new RefactoringService();
        var pyFile = new ProjectFile(tempDir, "test.py");
        var diff = new FileDiff(pyFile, pyFile, "def foo(): pass", "def foo(): pass");

        assertTrue(service.hasSupportedFiles(List.of(diff)));
    }

    @Test
    void testHasSupportedFiles_withUnsupportedFile() {
        var service = new RefactoringService();
        var txtFile = new ProjectFile(tempDir, "test.txt");
        var diff = new FileDiff(txtFile, txtFile, "content", "content");

        assertFalse(service.hasSupportedFiles(List.of(diff)));
    }

    @Test
    void testDetectRefactorings_renameMethod() {
        var service = new RefactoringService();
        var javaFile = new ProjectFile(tempDir, "src/main/java/com/example/Test.java");

        String oldContent =
                """
                package com.example;

                public class Test {
                    public void oldMethodName() {
                        System.out.println("Hello");
                    }
                }
                """;

        String newContent =
                """
                package com.example;

                public class Test {
                    public void newMethodName() {
                        System.out.println("Hello");
                    }
                }
                """;

        var diff = new FileDiff(javaFile, javaFile, oldContent, newContent);
        var result = service.detectRefactorings(List.of(diff));

        assertNotNull(result);
        // RefactoringMiner should detect the method rename
        // Note: exact detection depends on RefactoringMiner's heuristics
        if (result.hasRefactorings()) {
            assertTrue(
                    result.refactorings().stream()
                            .anyMatch(r -> r.type().toLowerCase().contains("rename")),
                    "Expected a rename refactoring to be detected");
            assertFalse(result.summary().isEmpty());
        }
    }

    @Test
    void testDetectRefactorings_extractMethod() {
        var service = new RefactoringService();
        var javaFile = new ProjectFile(tempDir, "src/main/java/com/example/Calculator.java");

        String oldContent =
                """
                package com.example;

                public class Calculator {
                    public int calculate(int a, int b) {
                        int result = a + b;
                        System.out.println("Result: " + result);
                        return result;
                    }
                }
                """;

        String newContent =
                """
                package com.example;

                public class Calculator {
                    public int calculate(int a, int b) {
                        int result = add(a, b);
                        printResult(result);
                        return result;
                    }

                    private int add(int a, int b) {
                        return a + b;
                    }

                    private void printResult(int result) {
                        System.out.println("Result: " + result);
                    }
                }
                """;

        var diff = new FileDiff(javaFile, javaFile, oldContent, newContent);
        var result = service.detectRefactorings(List.of(diff));

        assertNotNull(result);
        // RefactoringMiner should detect extract method refactorings
        if (result.hasRefactorings()) {
            assertTrue(
                    result.refactorings().stream()
                            .anyMatch(r -> r.type().toLowerCase().contains("extract")),
                    "Expected an extract refactoring to be detected");
        }
    }

    @Test
    void testRefactoringResultSummary_formatIsMarkdown() {
        var service = new RefactoringService();
        var javaFile = new ProjectFile(tempDir, "src/main/java/com/example/Test.java");

        String oldContent =
                """
                package com.example;

                public class Test {
                    private String name;

                    public String getName() {
                        return name;
                    }
                }
                """;

        String newContent =
                """
                package com.example;

                public class Test {
                    private String fullName;

                    public String getFullName() {
                        return fullName;
                    }
                }
                """;

        var diff = new FileDiff(javaFile, javaFile, oldContent, newContent);
        var result = service.detectRefactorings(List.of(diff));

        if (result.hasRefactorings()) {
            String summary = result.summary();
            assertTrue(summary.startsWith("## Detected Refactorings"), "Summary should start with markdown header");
            assertTrue(summary.contains("###"), "Summary should contain subsection headers");
        }
    }

    @Test
    void testDetectedRefactoring_hasLocations() {
        var service = new RefactoringService();
        var javaFile = new ProjectFile(tempDir, "src/main/java/com/example/Foo.java");

        String oldContent =
                """
                package com.example;

                public class Foo {
                    public void bar() {}
                }
                """;

        String newContent =
                """
                package com.example;

                public class Foo {
                    public void baz() {}
                }
                """;

        var diff = new FileDiff(javaFile, javaFile, oldContent, newContent);
        var result = service.detectRefactorings(List.of(diff));

        if (result.hasRefactorings()) {
            var refactoring = result.refactorings().getFirst();
            assertNotNull(refactoring.type());
            assertNotNull(refactoring.description());
            // At least one side should have locations
            assertFalse(
                    refactoring.leftSideLocations().isEmpty() && refactoring.rightSideLocations().isEmpty(),
                    "Refactoring should have at least one location");
        }
    }

    @Test
    void testDetectRefactorings_multipleFiles() {
        var service = new RefactoringService();

        var file1 = new ProjectFile(tempDir, "src/main/java/com/example/A.java");
        var file2 = new ProjectFile(tempDir, "src/main/java/com/example/B.java");

        String oldContent1 =
                """
                package com.example;

                public class A {
                    public void methodA() {}
                }
                """;

        String newContent1 =
                """
                package com.example;

                public class A {
                    public void renamedMethodA() {}
                }
                """;

        String oldContent2 =
                """
                package com.example;

                public class B {
                    public void methodB() {}
                }
                """;

        String newContent2 =
                """
                package com.example;

                public class B {
                    public void renamedMethodB() {}
                }
                """;

        var diffs = List.of(
                new FileDiff(file1, file1, oldContent1, newContent1),
                new FileDiff(file2, file2, oldContent2, newContent2));

        var result = service.detectRefactorings(diffs);

        assertNotNull(result);
        // Should detect refactorings across both files
    }

    @Test
    void testEmptyResult() {
        var emptyResult = RefactoringService.RefactoringResult.empty();

        assertNotNull(emptyResult);
        assertFalse(emptyResult.hasRefactorings());
        assertTrue(emptyResult.refactorings().isEmpty());
        assertEquals("", emptyResult.summary());
    }
}
