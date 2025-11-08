package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Test parity between getDefinition(String) and getDefinition(CodeUnit) to ensure:
 * 1. No mutual recursion loops
 * 2. Identical behavior for equivalent inputs
 * 3. Proper handling of edge cases
 */
public class GetDefinitionPurityTest {

    private static TestProject javaProject;
    private static TestProject pythonProject;
    private static JavaAnalyzer javaAnalyzer;
    private static PythonAnalyzer pythonAnalyzer;

    @BeforeAll
    static void setup() throws IOException {
        Path javaTestDir = Path.of("src/test/resources/testcode-java").toAbsolutePath();
        if (Files.exists(javaTestDir)) {
            javaProject = new TestProject(javaTestDir, Languages.JAVA);
            javaAnalyzer = new JavaAnalyzer(javaProject);
        }

        Path pythonTestDir = Path.of("src/test/resources/testcode-py").toAbsolutePath();
        if (Files.exists(pythonTestDir)) {
            pythonProject = new TestProject(pythonTestDir, Languages.PYTHON);
            pythonAnalyzer = new PythonAnalyzer(pythonProject);
        }
    }

    @AfterAll
    static void teardown() {
        if (javaProject != null) javaProject.close();
        if (pythonProject != null) pythonProject.close();
    }

    // ==================== JAVA ANALYZER TESTS ====================

    @Test
    @Timeout(5)
    void testJavaGetDefinitionStringAndCodeUnitParity() {
        if (javaAnalyzer == null) {
            return; // Skip if test resources not available
        }

        List<CodeUnit> allDeclaredUnits = javaAnalyzer.getAllDeclarations();
        assertFalse(allDeclaredUnits.isEmpty(), "Java analyzer should find declarations");

        // Test parity for a sample of CodeUnits
        for (CodeUnit cu : allDeclaredUnits.stream().limit(10).toList()) {
            String fqName = cu.fqName();

            // Get definition via String path
            Optional<CodeUnit> defViaString = javaAnalyzer.getDefinition(fqName);

            // Get definition via CodeUnit path
            Optional<CodeUnit> defViaCodeUnit = javaAnalyzer.getDefinition(cu);

            // Both should produce the same result (or both be empty)
            assertEquals(
                    defViaString.isPresent(),
                    defViaCodeUnit.isPresent(),
                    "Parity violation for " + fqName + ": String returned "
                            + defViaString.map(CodeUnit::fqName).orElse("empty")
                            + " but CodeUnit returned "
                            + defViaCodeUnit.map(CodeUnit::fqName).orElse("empty"));

            if (defViaString.isPresent() && defViaCodeUnit.isPresent()) {
                assertEquals(
                        defViaString.get().fqName(),
                        defViaCodeUnit.get().fqName(),
                        "FQNames should match for " + fqName);
            }
        }
    }

    @Test
    @Timeout(5)
    void testJavaGetDefinitionNoRecursion() {
        if (javaAnalyzer == null) {
            return;
        }

        // Pick a few well-known symbols
        String[] testSymbols = {"D", "A.method1", "D.field1"};

        for (String symbol : testSymbols) {
            Optional<CodeUnit> result = assertTimeout(
                    java.time.Duration.ofSeconds(2),
                    () -> javaAnalyzer.getDefinition(symbol),
                    "getDefinition(String) for " + symbol + " completed within timeout");

            // If we get here, no stack overflow occurred
            assertTrue(true, "No recursion detected for " + symbol);
        }
    }

    @Test
    @Timeout(5)
    void testJavaGetDefinitionCodeUnitNoRecursion() {
        if (javaAnalyzer == null) {
            return;
        }

        List<CodeUnit> allDeclaredUnits = javaAnalyzer.getAllDeclarations();
        assertFalse(allDeclaredUnits.isEmpty());

        // Test a few CodeUnits for recursion
        for (CodeUnit cu : allDeclaredUnits.stream().limit(5).toList()) {
            Optional<CodeUnit> result = assertTimeout(
                    java.time.Duration.ofSeconds(2),
                    () -> javaAnalyzer.getDefinition(cu),
                    "getDefinition(CodeUnit) for " + cu.fqName() + " completed within timeout");

            assertTrue(true, "No recursion detected for " + cu.fqName());
        }
    }

    @Test
    @Timeout(5)
    void testJavaGetDefinitionConsistency() {
        if (javaAnalyzer == null) {
            return;
        }

        // Multiple calls with same input should return equal results
        String fqName = "D";

        Optional<CodeUnit> result1 = javaAnalyzer.getDefinition(fqName);
        Optional<CodeUnit> result2 = javaAnalyzer.getDefinition(fqName);

        assertEquals(result1, result2, "Multiple calls should return consistent results");
    }

    // ==================== PYTHON ANALYZER TESTS ====================

    @Test
    @Timeout(5)
    void testPythonGetDefinitionStringAndCodeUnitParity() {
        if (pythonAnalyzer == null) {
            return; // Skip if test resources not available
        }

        List<CodeUnit> allDeclaredUnits = pythonAnalyzer.getAllDeclarations();
        assertFalse(allDeclaredUnits.isEmpty(), "Python analyzer should find declarations");

        // Test parity for a sample of CodeUnits
        for (CodeUnit cu : allDeclaredUnits.stream().limit(10).toList()) {
            String fqName = cu.fqName();

            // Get definition via String path
            Optional<CodeUnit> defViaString = pythonAnalyzer.getDefinition(fqName);

            // Get definition via CodeUnit path
            Optional<CodeUnit> defViaCodeUnit = pythonAnalyzer.getDefinition(cu);

            // Both should produce the same result (or both be empty)
            assertEquals(
                    defViaString.isPresent(),
                    defViaCodeUnit.isPresent(),
                    "Parity violation for " + fqName + ": String returned "
                            + defViaString.map(CodeUnit::fqName).orElse("empty")
                            + " but CodeUnit returned "
                            + defViaCodeUnit.map(CodeUnit::fqName).orElse("empty"));

            if (defViaString.isPresent() && defViaCodeUnit.isPresent()) {
                assertEquals(
                        defViaString.get().fqName(),
                        defViaCodeUnit.get().fqName(),
                        "FQNames should match for " + fqName);
            }
        }
    }

    @Test
    @Timeout(5)
    void testPythonGetDefinitionNoRecursion() {
        if (pythonAnalyzer == null) {
            return;
        }

        // Pick a few well-known symbols if they exist
        List<CodeUnit> allDeclaredUnits = pythonAnalyzer.getAllDeclarations();
        if (allDeclaredUnits.isEmpty()) {
            return;
        }

        for (CodeUnit cu : allDeclaredUnits.stream().limit(5).toList()) {
            Optional<CodeUnit> result = assertTimeout(
                    java.time.Duration.ofSeconds(2),
                    () -> pythonAnalyzer.getDefinition(cu.fqName()),
                    "getDefinition(String) for " + cu.fqName() + " completed within timeout");

            assertTrue(true, "No recursion detected for " + cu.fqName());
        }
    }

    @Test
    @Timeout(5)
    void testPythonGetDefinitionCodeUnitNoRecursion() {
        if (pythonAnalyzer == null) {
            return;
        }

        List<CodeUnit> allDeclaredUnits = pythonAnalyzer.getAllDeclarations();
        if (allDeclaredUnits.isEmpty()) {
            return;
        }

        // Test a few CodeUnits for recursion
        for (CodeUnit cu : allDeclaredUnits.stream().limit(5).toList()) {
            Optional<CodeUnit> result = assertTimeout(
                    java.time.Duration.ofSeconds(2),
                    () -> pythonAnalyzer.getDefinition(cu),
                    "getDefinition(CodeUnit) for " + cu.fqName() + " completed within timeout");

            assertTrue(true, "No recursion detected for " + cu.fqName());
        }
    }

    // ==================== DISABLED ANALYZER TESTS ====================

    @Test
    @Timeout(5)
    void testDisabledAnalyzerGetDefinitionParity() {
        DisabledAnalyzer analyzer = new DisabledAnalyzer();

        // Both overloads should return empty
        Optional<CodeUnit> viaString = analyzer.getDefinition("SomeClass");
        Optional<CodeUnit> viaCodeUnit =
                analyzer.getDefinition(CodeUnit.cls(new ProjectFile(Path.of("/tmp"), "test.txt"), "", "SomeClass"));

        assertFalse(viaString.isPresent(), "DisabledAnalyzer.getDefinition(String) should return empty");
        assertFalse(viaCodeUnit.isPresent(), "DisabledAnalyzer.getDefinition(CodeUnit) should return empty");
    }

    @Test
    @Timeout(5)
    void testDisabledAnalyzerNoRecursion() {
        DisabledAnalyzer analyzer = new DisabledAnalyzer();

        // Should complete without stack overflow
        assertTimeout(java.time.Duration.ofSeconds(1), () -> {
            analyzer.getDefinition("Test");
            analyzer.getDefinition(CodeUnit.cls(new ProjectFile(Path.of("/tmp"), "test.txt"), "", "Test"));
        });
    }

    // ==================== TEST ANALYZER TESTS ====================

    @Test
    @Timeout(5)
    void testTestAnalyzerGetDefinitionParity() {
        TestAnalyzer analyzer = new TestAnalyzer();

        // Both overloads should return empty
        Optional<CodeUnit> viaString = analyzer.getDefinition("SomeClass");
        Optional<CodeUnit> viaCodeUnit =
                analyzer.getDefinition(CodeUnit.cls(new ProjectFile(Path.of("/tmp"), "test.txt"), "", "SomeClass"));

        assertFalse(viaString.isPresent(), "TestAnalyzer.getDefinition(String) should return empty");
        assertFalse(viaCodeUnit.isPresent(), "TestAnalyzer.getDefinition(CodeUnit) should return empty");
    }

    @Test
    @Timeout(5)
    void testTestAnalyzerNoRecursion() {
        TestAnalyzer analyzer = new TestAnalyzer();

        // Should complete without stack overflow
        assertTimeout(java.time.Duration.ofSeconds(1), () -> {
            analyzer.getDefinition("Test");
            analyzer.getDefinition(CodeUnit.cls(new ProjectFile(Path.of("/tmp"), "test.txt"), "", "Test"));
        });
    }
}
