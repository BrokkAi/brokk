package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.FailingTemplateAnalyzer;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestProject;
import ai.brokk.testutil.TestTemplateAnalyzer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        // Get the correct CodeUnit from analyzer
        var classUnit =
                multiAnalyzer.getDefinitions("TestClass").stream().findFirst().orElseThrow();

        // Get skeleton through MultiAnalyzer - should route to Java delegate
        Optional<String> skeleton = multiAnalyzer.getSkeleton(classUnit);

        assertTrue(skeleton.isPresent(), "Should return skeleton for Java class");
        assertTrue(skeleton.get().contains("TestClass"), "Skeleton should contain class name");
        assertTrue(skeleton.get().contains("testMethod"), "Skeleton should contain method name");
    }

    @Test
    public void testDelegateRouting_JavaFile_getSources() {
        // Create a CodeUnit for the testMethod
        var methodUnit = multiAnalyzer.getDefinitions("TestClass.testMethod").stream()
                .findAny()
                .get();

        // Get method sources through MultiAnalyzer - should route to Java delegate via getSources
        Set<String> sources = multiAnalyzer.getSources(methodUnit, true);

        assertFalse(sources.isEmpty(), "Should return method sources for Java method");
        assertTrue(
                sources.stream().anyMatch(s -> s.contains("testMethod")), "Method source should contain method name");
    }

    @Test
    public void testDelegateRouting_JavaFile_getSource() {
        // Get the correct CodeUnit from analyzer
        var classUnit =
                multiAnalyzer.getDefinitions("TestClass").stream().findFirst().orElseThrow();

        // Get class source through MultiAnalyzer - should route to Java delegate via getSource
        Optional<String> classSource = multiAnalyzer.getSource(classUnit, true);

        assertTrue(classSource.isPresent(), "Should return class source for Java class");
        assertTrue(classSource.get().contains("TestClass"), "Class source should contain class name");
        assertTrue(classSource.get().contains("testMethod"), "Class source should contain method");
    }

    @Test
    public void testUnknownExtension_ReturnsEmpty_getSources() {
        // Create a CodeUnit with an unknown extension
        var unknownFile = new ProjectFile(tempDir, "test.xyz");
        var unknownUnit = CodeUnit.fn(unknownFile, "", "SomeClass.someMethod");

        // Get sources - should return empty set and not throw an exception
        Set<String> sources = assertDoesNotThrow(() -> multiAnalyzer.getSources(unknownUnit, true));

        assertTrue(sources.isEmpty(), "Should return empty set for unknown extension");
    }

    @Test
    public void testUnknownExtension_ReturnsEmpty_getSource() {
        // Create a CodeUnit with an unknown extension
        var unknownFile = new ProjectFile(tempDir, "test.xyz");
        var unknownUnit = CodeUnit.cls(unknownFile, "", "UnknownClass");

        // Get source - should return empty and not throw an exception
        Optional<String> source = assertDoesNotThrow(() -> multiAnalyzer.getSource(unknownUnit, true));

        assertFalse(source.isPresent(), "Should return empty for unknown extension");
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
    public void testUnknownExtension_NoException() {
        // Test multiple methods to ensure they all handle missing delegates gracefully
        var unknownFile = new ProjectFile(tempDir, "test.unknown");
        var unknownClass = CodeUnit.cls(unknownFile, "", "Test");
        var unknownMethod = CodeUnit.fn(unknownFile, "", "Test.method");

        // All of these should complete without throwing exceptions
        assertDoesNotThrow(() -> multiAnalyzer.getSkeleton(unknownClass));
        assertDoesNotThrow(() -> multiAnalyzer.getSkeletonHeader(unknownClass));
        assertDoesNotThrow(() -> multiAnalyzer.getSources(unknownMethod, false));
        assertDoesNotThrow(() -> multiAnalyzer.getSource(unknownClass, false));
        assertDoesNotThrow(() -> multiAnalyzer.getDirectChildren(unknownClass));
        assertDoesNotThrow(() -> multiAnalyzer.getDeclarations(unknownFile));
        assertDoesNotThrow(() -> multiAnalyzer.getSkeletons(unknownFile));
    }

    @Test
    public void testGetSources_AggregatesHostAndTemplateEvenIfOneFails() {
        // GIVEN: A CodeUnit in a TypeScript file
        var tsFile = new ProjectFile(tempDir, "app.component.ts");
        var hostClass = CodeUnit.cls(tsFile, "app", "AppComponent");

        // A host analyzer (not used by MultiAnalyzer.getSources for templates, but required for construction)
        IAnalyzer hostAnalyzer = new TestAnalyzer();

        ITemplateAnalyzer failingTemplate = new FailingTemplateAnalyzer("Failing", "Template analysis failed");

        ITemplateAnalyzer validTemplate =
                new TestTemplateAnalyzer("Valid", Map.of(hostClass, Set.of("<div>Template Content</div>")));

        MultiAnalyzer ma =
                new MultiAnalyzer(Map.of(Languages.TYPESCRIPT, hostAnalyzer), List.of(failingTemplate, validTemplate));

        // WHEN: Requesting sources for the host class
        Set<String> result = ma.getSources(hostClass, true);

        // THEN: We should get the valid template source, despite the failure in between
        assertEquals(1, result.size(), "Should aggregate available sources");
        assertTrue(result.contains("<div>Template Content</div>"));
    }

    @Test
    public void testIsTestFile_FallsBackToHeuristicsWhenDelegateLacksCapability() {
        // GIVEN: A MultiAnalyzer that supports TestDetectionProvider (via Java delegate)
        // BUT: We check a file whose language (Python) has no delegate (or a delegate without the capability)
        var pythonTestFile = new ProjectFile(tempDir, "test_script.py");

        // WHEN: Calling ContextManager.isTestFile
        // THEN: It should return true because "test_script.py" matches TEST_FILE_PATTERN,
        // even though MultiAnalyzer.as(TestDetectionProvider.class) is present.
        assertTrue(
                ai.brokk.ContextManager.isTestFile(pythonTestFile, multiAnalyzer),
                "Should fall back to pattern matching when specific language delegate lacks capability");
    }
}
