package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.testutil.TestProject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;


public final class PythonAnalyzerTest {

    /** Creates a TestProject rooted under src/test/resources/{subDir}. */
    static TestProject createTestProject(String subDir, io.github.jbellis.brokk.analyzer.Language lang) { // Use Brokk's Language enum
        Path testDir = Path.of("src/test/resources", subDir);
        assertTrue(Files.exists(testDir), "Test resource dir missing: " + testDir);
        assertTrue(Files.isDirectory(testDir), testDir + " is not a directory");
        return new TestProject(testDir.toAbsolutePath(), lang);
    }

    /* -------------------- Python -------------------- */

    @Test
    void testPythonInitializationAndSkeletons() {
        TestProject project = createTestProject("testcode-py", io.github.jbellis.brokk.analyzer.Language.PYTHON); // Use Brokk's Language enum
        PythonAnalyzer ana = new PythonAnalyzer(project);
        assertInstanceOf(PythonAnalyzer.class, ana);
        // Cast to PythonAnalyzer
        assertFalse(ana.isEmpty(), "Analyzer should have processed Python files");

        ProjectFile fileA = new ProjectFile(project.getRoot(), "a/A.py");
        // Skeletons are now reconstructed. We check CodeUnits first.
        var classesInFileA = ana.getDeclarationsInFile(fileA);
        var classA_CU = CodeUnit.cls(fileA, "a", "A");
        assertTrue(classesInFileA.contains(classA_CU), "File A should contain class A.");

        var topLevelDeclsInA = ana.topLevelDeclarations.get(fileA); // Accessing internal for test validation
        assertNotNull(topLevelDeclsInA, "Top level declarations for file A should exist.");

        var funcA_CU = CodeUnit.fn(fileA, "a", "A.funcA");
        assertTrue(topLevelDeclsInA.contains(funcA_CU), "File A should contain function funcA as top-level.");
        assertTrue(topLevelDeclsInA.contains(classA_CU), "File A should contain class A as top-level.");

        // Test reconstructed skeletons
        var skelA = ana.getSkeletons(fileA);
        assertFalse(skelA.isEmpty(), "Reconstructed skeletons map for file A should not be empty.");

        assertTrue(skelA.containsKey(classA_CU), "Skeleton map should contain class A.");
        String classASkeleton = skelA.get(classA_CU);
        assertTrue(classASkeleton.contains("class A:"), "Class A skeleton content error.");

        assertTrue(skelA.containsKey(funcA_CU), "Skeleton map should contain function funcA.");
        String funcASkeleton = skelA.get(funcA_CU);
        assertTrue(funcASkeleton.contains("def funcA():"), "funcA skeleton content error.");

        var funcASummary = "def funcA(): ...\n";
        assertEquals(funcASummary.trim(), funcASkeleton.trim());

        // Replaced text block with standard string concatenation due to persistent compiler errors
        var classASummary = "class A:\n" +
                            "  def __init__(self): ...\n" +
                            "  def method1(self) -> None: ...\n" +
                            "  def method2(self, input_str: str, other_input: int = None) -> str: ...\n" +
                            "  def method3(self) -> Callable[[int], int]: ...\n" +
                            "  @staticmethod\n" +
                            "  def method4(foo: float, bar: int) -> int: ...\n" +
                            "  def method5(self) -> None: ...\n" +
                            "  def method6(self) -> None: ...\n";
        // Note: PythonAnalyzer.getLanguageSpecificIndent() might affect exact string match if not "  "
        assertEquals(classASummary.trim(), classASkeleton.trim(), "Class A skeleton mismatch.");

        Set<CodeUnit> declarationsInA = ana.getDeclarationsInFile(fileA);
        assertTrue(declarationsInA.contains(classA_CU), "getDeclarationsInFile mismatch for file A: missing classA_CU. Found: " + declarationsInA);
        assertTrue(declarationsInA.contains(funcA_CU), "getDeclarationsInFile mismatch for file A: missing funcA_CU. Found: " + declarationsInA);
        // Add other expected CUs if necessary for a more complete check, e.g., methods of classA_CU
        assertTrue(ana.getSkeleton(funcA_CU.fqName()).isPresent(), "Skeleton for funcA_CU should be present");
        assertEquals(funcASummary.trim(), ana.getSkeleton(funcA_CU.fqName()).get().trim(), "getSkeleton mismatch for funcA");
    }

    @Test
    void testPythonTopLevelVariables() {
        TestProject project = createTestProject("testcode-py", io.github.jbellis.brokk.analyzer.Language.PYTHON);
        IAnalyzer ana = new PythonAnalyzer(project);
        assertInstanceOf(PythonAnalyzer.class, ana);
        PythonAnalyzer analyzer = (PythonAnalyzer) ana;

        ProjectFile varsPyFile = new ProjectFile(project.getRoot(), "vars.py");
        var skelVars = analyzer.getSkeletons(varsPyFile);

        // vars.py content:
        // TOP_VALUE = 99
        // export_like = "not really"

        // For Python top-level fields, shortName is now "moduleName.fieldName"
        CodeUnit topValueCU = CodeUnit.field(varsPyFile, "", "vars.TOP_VALUE");
        CodeUnit exportLikeCU = CodeUnit.field(varsPyFile, "", "vars.export_like");

        assertTrue(skelVars.containsKey(topValueCU), "Skeletons map should contain vars.TOP_VALUE. Found: " + skelVars.keySet());
        assertEquals("TOP_VALUE = 99", skelVars.get(topValueCU).strip());

        assertTrue(skelVars.containsKey(exportLikeCU), "Skeletons map should contain export_like. Found: " + skelVars.keySet());
        assertEquals("export_like = \"not really\"", skelVars.get(exportLikeCU).strip()); // Note: Query captures the whole assignment

        // Ensure these are not mistaken for classes
        Set<CodeUnit> declarationsInVarsPy = analyzer.getDeclarationsInFile(varsPyFile);
        assertTrue(declarationsInVarsPy.contains(topValueCU), "TOP_VALUE should be in declarations list for vars.py. Found: " + declarationsInVarsPy);
        assertFalse(topValueCU.isClass(), "TOP_VALUE CU should not be a class.");
        assertTrue(declarationsInVarsPy.contains(exportLikeCU), "export_like should be in declarations list for vars.py. Found: " + declarationsInVarsPy);
        assertFalse(exportLikeCU.isClass(), "export_like CU should not be a class.");

        // Verify that getTopLevelDeclarations includes these fields
        var topLevelDecls = ((TreeSitterAnalyzer)analyzer).topLevelDeclarations.get(varsPyFile);
        assertNotNull(topLevelDecls, "Top level declarations for vars.py should exist.");
        assertTrue(topLevelDecls.contains(topValueCU), "Top-level declarations should include TOP_VALUE.");
        assertTrue(topLevelDecls.contains(exportLikeCU), "Top-level declarations should include export_like.");
    }
}
