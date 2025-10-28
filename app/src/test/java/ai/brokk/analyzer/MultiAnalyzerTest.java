package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
}
