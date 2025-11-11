package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class MultiAnalyzerTest {

    @TempDir
    static Path tempDir;

    private static TestProject testProject;
    private static MultiAnalyzer multiAnalyzer;

    @BeforeAll
    public static void setup() throws IOException {
        // Create a Java file
        Path javaFile = tempDir.resolve("TestClass.java");
        Files.writeString(
                javaFile,
                """
                public class TestClass {
                    public void testMethod() {
                        System.out.println("Hello");
                    }
                }
                """);

        testProject = new TestProject(tempDir, Languages.JAVA);

        // Create MultiAnalyzer with Java support
        var javaAnalyzer = new JavaAnalyzer(testProject);
        multiAnalyzer = new MultiAnalyzer(Map.of(Languages.JAVA, javaAnalyzer));
    }

    @AfterAll
    public static void teardown() {
        if (testProject != null) {
            testProject.close();
        }
    }

    @Test
    public void testGetTopLevelDeclarationsJavaFile() {
        var javaFile = new ProjectFile(tempDir, "TestClass.java");
        var topLevelUnits = multiAnalyzer.getTopLevelDeclarations(javaFile);

        assertEquals(1, topLevelUnits.size(), "Should return one top-level class");
        assertEquals("TestClass", topLevelUnits.get(0).fqName());
        assertTrue(topLevelUnits.get(0).isClass());
    }

    @Test
    public void testGetTopLevelDeclarationsUnsupportedLanguageReturnsEmpty() {
        var pythonFile = new ProjectFile(tempDir, "test.py");
        var topLevelUnits = multiAnalyzer.getTopLevelDeclarations(pythonFile);

        assertTrue(topLevelUnits.isEmpty(), "Should return empty list for unsupported language");
    }

    @Test
    public void testGetTopLevelDeclarationsNonExistentFile() {
        var nonExistentFile = new ProjectFile(tempDir, "NonExistent.java");
        var topLevelUnits = multiAnalyzer.getTopLevelDeclarations(nonExistentFile);

        assertTrue(topLevelUnits.isEmpty(), "Should return empty list for non-existent file");
    }

    // Delegate Resolution Tests

    @Test
    public void testDelegateRouting_JavaFile_getSkeleton() {
        // Create a CodeUnit for the TestClass
        var javaFile = new ProjectFile(tempDir, "TestClass.java");
        var classUnit = CodeUnit.cls(javaFile, "", "TestClass");

        // Get skeleton through MultiAnalyzer - should route to Java delegate
        Optional<String> skeleton = multiAnalyzer.getSkeleton(classUnit);

        assertTrue(skeleton.isPresent(), "Should return skeleton for Java class");
        assertTrue(skeleton.get().contains("TestClass"), "Skeleton should contain class name");
        assertTrue(skeleton.get().contains("testMethod"), "Skeleton should contain method name");
    }

    @Test
    public void testDelegateRouting_JavaFile_getMethodSources() {
        // Create a CodeUnit for the testMethod
        var javaFile = new ProjectFile(tempDir, "TestClass.java");
        var methodUnit = CodeUnit.fn(javaFile, "", "TestClass.testMethod");

        // Get method sources through MultiAnalyzer - should route to Java delegate
        Set<String> sources = multiAnalyzer.getMethodSources(methodUnit, true);

        assertFalse(sources.isEmpty(), "Should return method sources for Java method");
        assertTrue(
                sources.stream().anyMatch(s -> s.contains("testMethod")), "Method source should contain method name");
    }

    @Test
    public void testDelegateRouting_JavaFile_getClassSource() {
        // Create a CodeUnit for the TestClass
        var javaFile = new ProjectFile(tempDir, "TestClass.java");
        var classUnit = CodeUnit.cls(javaFile, "", "TestClass");

        // Get class source through MultiAnalyzer - should route to Java delegate
        Optional<String> classSource = multiAnalyzer.getClassSource(classUnit, true);

        assertTrue(classSource.isPresent(), "Should return class source for Java class");
        assertTrue(classSource.get().contains("TestClass"), "Class source should contain class name");
        assertTrue(classSource.get().contains("testMethod"), "Class source should contain method");
    }

    @Test
    public void testUnknownExtension_ReturnsEmpty_getSkeleton() {
        // Create a CodeUnit with an unknown extension
        var unknownFile = new ProjectFile(tempDir, "test.xyz");
        var unknownUnit = CodeUnit.cls(unknownFile, "", "UnknownClass");

        // Get skeleton - should return empty and not throw an exception
        Optional<String> skeleton = assertDoesNotThrow(() -> multiAnalyzer.getSkeleton(unknownUnit));

        assertFalse(skeleton.isPresent(), "Should return empty for unknown extension");
    }

    @Test
    public void testUnknownExtension_ReturnsEmpty_getMethodSources() {
        // Create a CodeUnit with an unknown extension
        var unknownFile = new ProjectFile(tempDir, "test.xyz");
        var unknownUnit = CodeUnit.fn(unknownFile, "", "SomeClass.someMethod");

        // Get method sources - should return empty set and not throw an exception
        Set<String> sources = assertDoesNotThrow(() -> multiAnalyzer.getMethodSources(unknownUnit, true));

        assertTrue(sources.isEmpty(), "Should return empty set for unknown extension");
    }

    @Test
    public void testUnknownExtension_ReturnsEmpty_getClassSource() {
        // Create a CodeUnit with an unknown extension
        var unknownFile = new ProjectFile(tempDir, "test.xyz");
        var unknownUnit = CodeUnit.cls(unknownFile, "", "UnknownClass");

        // Get class source - should return empty and not throw an exception
        Optional<String> classSource = assertDoesNotThrow(() -> multiAnalyzer.getClassSource(unknownUnit, true));

        assertFalse(classSource.isPresent(), "Should return empty for unknown extension");
    }

    @Test
    public void testUnknownExtension_NoException() {
        // Test multiple methods to ensure they all handle missing delegates gracefully
        var unknownFile = new ProjectFile(tempDir, "test.unknown");
        var unknownClass = CodeUnit.cls(unknownFile, "", "Test");
        var unknownMethod = CodeUnit.fn(unknownFile, "", "Test.method");

        // All of these should complete without throwing exceptions
        assertDoesNotThrow(() -> multiAnalyzer.getSkeleton(unknownClass));
        assertDoesNotThrow(() -> multiAnalyzer.getSkeletonHeader(unknownClass));
        assertDoesNotThrow(() -> multiAnalyzer.getMethodSources(unknownMethod, false));
        assertDoesNotThrow(() -> multiAnalyzer.getClassSource(unknownClass, false));
        assertDoesNotThrow(() -> multiAnalyzer.getDirectChildren(unknownClass));
        assertDoesNotThrow(() -> multiAnalyzer.getDeclarations(unknownFile));
        assertDoesNotThrow(() -> multiAnalyzer.getSkeletons(unknownFile));
    }
}
