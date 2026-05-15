package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.CoreTestProject;
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

    private static CoreTestProject testProject;
    private static MultiAnalyzer multiAnalyzer;

    @BeforeAll
    public static void setup() throws IOException {
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

        testProject = new CoreTestProject(tempDir, Set.of(Languages.JAVA));

        var javaAnalyzer = new JavaAnalyzer(testProject);
        multiAnalyzer = new MultiAnalyzer(Map.of(Languages.JAVA, javaAnalyzer));
    }

    @AfterAll
    public static void teardown() throws Exception {
        if (testProject != null) {
            testProject.close();
        }
    }

    @Test
    public void testGetTopLevelDeclarationsJavaFile() {
        var javaFile = new ProjectFile(tempDir, "TestClass.java");
        var topLevelUnits = multiAnalyzer.getTopLevelDeclarations(javaFile);

        assertEquals(1, topLevelUnits.size(), "Should return one top-level class");
        assertEquals("TestClass", topLevelUnits.getFirst().fqName());
        assertTrue(topLevelUnits.getFirst().isClass());
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

    @Test
    public void testDelegateRouting_JavaFile_getSkeleton() {
        var classUnit =
                multiAnalyzer.getDefinitions("TestClass").stream().findFirst().orElseThrow();

        Optional<String> skeleton = multiAnalyzer.getSkeleton(classUnit);

        assertTrue(skeleton.isPresent(), "Should return skeleton for Java class");
        assertTrue(skeleton.get().contains("TestClass"), "Skeleton should contain class name");
        assertTrue(skeleton.get().contains("testMethod"), "Skeleton should contain method name");
    }

    @Test
    public void testDelegateRouting_JavaFile_getSources() {
        var methodUnit = multiAnalyzer.getDefinitions("TestClass.testMethod").stream()
                .findAny()
                .orElseThrow();

        Set<String> sources = multiAnalyzer.getSources(methodUnit, true);

        assertFalse(sources.isEmpty(), "Should return method sources for Java method");
        assertTrue(
                sources.stream().anyMatch(s -> s.contains("testMethod")), "Method source should contain method name");
    }

    @Test
    public void testDelegateRouting_JavaFile_getSource() {
        var classUnit =
                multiAnalyzer.getDefinitions("TestClass").stream().findFirst().orElseThrow();

        Optional<String> classSource = multiAnalyzer.getSource(classUnit, true);

        assertTrue(classSource.isPresent(), "Should return class source for Java class");
        assertTrue(classSource.get().contains("TestClass"), "Class source should contain class name");
        assertTrue(classSource.get().contains("testMethod"), "Class source should contain method");
    }

    @Test
    public void testUnknownExtension_ReturnsEmpty_getSources() {
        var unknownFile = new ProjectFile(tempDir, "test.xyz");
        var unknownUnit = CodeUnit.fn(unknownFile, "", "SomeClass.someMethod");

        Set<String> sources = assertDoesNotThrow(() -> multiAnalyzer.getSources(unknownUnit, true));

        assertTrue(sources.isEmpty(), "Should return empty set for unknown extension");
    }

    @Test
    public void testUnknownExtension_ReturnsEmpty_getSource() {
        var unknownFile = new ProjectFile(tempDir, "test.xyz");
        var unknownUnit = CodeUnit.cls(unknownFile, "", "UnknownClass");

        Optional<String> source = assertDoesNotThrow(() -> multiAnalyzer.getSource(unknownUnit, true));

        assertFalse(source.isPresent(), "Should return empty for unknown extension");
    }

    @Test
    public void testUnknownExtension_ReturnsEmpty_getSkeleton() {
        var unknownFile = new ProjectFile(tempDir, "test.xyz");
        var unknownUnit = CodeUnit.cls(unknownFile, "", "UnknownClass");

        Optional<String> skeleton = assertDoesNotThrow(() -> multiAnalyzer.getSkeleton(unknownUnit));

        assertFalse(skeleton.isPresent(), "Should return empty for unknown extension");
    }

    @Test
    public void testUnknownExtension_NoException() {
        var unknownFile = new ProjectFile(tempDir, "test.unknown");
        var unknownClass = CodeUnit.cls(unknownFile, "", "Test");
        var unknownMethod = CodeUnit.fn(unknownFile, "", "Test.method");

        assertDoesNotThrow(() -> multiAnalyzer.getSkeleton(unknownClass));
        assertDoesNotThrow(() -> multiAnalyzer.getSkeletonHeader(unknownClass));
        assertDoesNotThrow(() -> multiAnalyzer.getSources(unknownMethod, false));
        assertDoesNotThrow(() -> multiAnalyzer.getSource(unknownClass, false));
        assertDoesNotThrow(() -> multiAnalyzer.getDirectChildren(unknownClass));
        assertDoesNotThrow(() -> multiAnalyzer.getDeclarations(unknownFile));
        assertDoesNotThrow(() -> multiAnalyzer.getSkeletons(unknownFile));
    }

    @Test
    public void testIsTestFile_FallsBackToHeuristicsWhenDelegateLacksCapability() {
        var pythonTestFile = new ProjectFile(tempDir, "test_script.py");

        assertTrue(
                TestFileHeuristics.isTestFile(pythonTestFile, multiAnalyzer),
                "Should fall back to pattern matching when specific language delegate lacks capability");
    }
}
