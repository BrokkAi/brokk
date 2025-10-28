package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public final class PythonAnalyzerTest {

    @Nullable
    private static TestProject project;

    @Nullable
    private static PythonAnalyzer analyzer;

    @BeforeAll
    public static void setup() {
        Path testDir = Path.of("src/test/resources", "testcode-py");
        assertTrue(Files.exists(testDir), "Test resource dir missing: " + testDir);
        assertTrue(Files.isDirectory(testDir), testDir + " is not a directory");
        project = new TestProject(testDir.toAbsolutePath(), Languages.PYTHON);
        analyzer = new PythonAnalyzer(project);
    }

    @AfterAll
    public static void teardown() {
        if (project != null) {
            project.close();
        }
    }

    /** Creates a TestProject rooted under src/test/resources/{subDir}. */
    static TestProject createTestProject(String subDir, Language lang) { // Use Brokk's Language enum
        Path testDir = Path.of("src/test/resources", subDir);
        assertTrue(Files.exists(testDir), "Test resource dir missing: " + testDir);
        assertTrue(Files.isDirectory(testDir), testDir + " is not a directory");
        return new TestProject(testDir.toAbsolutePath(), lang);
    }

    /* -------------------- Python -------------------- */

    @Test
    void testPythonInitializationAndSkeletons() {
        assertInstanceOf(PythonAnalyzer.class, analyzer);
        // Cast to PythonAnalyzer
        assertFalse(analyzer.isEmpty(), "Analyzer should have processed Python files");

        ProjectFile fileA = new ProjectFile(project.getRoot(), "a/A.py");
        // Skeletons are now reconstructed. We check CodeUnits first.
        var classesInFileA = analyzer.getDeclarations(fileA);
        var classA_CU = CodeUnit.cls(fileA, "a", "A");
        assertTrue(classesInFileA.contains(classA_CU), "File A should contain class A.");

        var topLevelDeclsInA = analyzer.withFileProperties(tld -> tld.get(fileA))
                .topLevelCodeUnits(); // Accessing internal for test validation
        assertNotNull(topLevelDeclsInA, "Top level declarations for file A should exist.");

        var funcA_CU = CodeUnit.fn(fileA, "a", "A.funcA");
        assertTrue(topLevelDeclsInA.contains(funcA_CU), "File A should contain function funcA as top-level.");
        assertTrue(topLevelDeclsInA.contains(classA_CU), "File A should contain class A as top-level.");

        // Test reconstructed skeletons
        var skelA = analyzer.getSkeletons(fileA);
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
        var classASummary = "class A:\n" + "  def __init__(self): ...\n"
                + "  def method1(self) -> None: ...\n"
                + "  def method2(self, input_str: str, other_input: int = None) -> str: ...\n"
                + "  def method3(self) -> Callable[[int], int]: ...\n"
                + "  @staticmethod\n"
                + "  def method4(foo: float, bar: int) -> int: ...\n"
                + "  def method5(self) -> None: ...\n"
                + "  def method6(self) -> None: ...\n";
        // Note: PythonAnalyzer.getLanguageSpecificIndent() might affect exact string match if not "  "
        assertEquals(classASummary.trim(), classASkeleton.trim(), "Class A skeleton mismatch.");

        Set<CodeUnit> declarationsInA = analyzer.getDeclarations(fileA);
        assertTrue(
                declarationsInA.contains(classA_CU),
                "getDeclarationsInFile mismatch for file A: missing classA_CU. Found: " + declarationsInA);
        assertTrue(
                declarationsInA.contains(funcA_CU),
                "getDeclarationsInFile mismatch for file A: missing funcA_CU. Found: " + declarationsInA);
        // Add other expected CUs if necessary for a more complete check, e.g., methods of classA_CU
        assertTrue(analyzer.getSkeleton(funcA_CU.fqName()).isPresent(), "Skeleton for funcA_CU should be present");
        assertEquals(
                funcASummary.trim(),
                analyzer.getSkeleton(funcA_CU.fqName()).get().trim(),
                "getSkeleton mismatch for funcA");
    }

    @Test
    void testPythonTopLevelVariables() {
        ProjectFile varsPyFile = new ProjectFile(project.getRoot(), "vars.py");
        var skelVars = analyzer.getSkeletons(varsPyFile);

        // vars.py content:
        // TOP_VALUE = 99
        // export_like = "not really"

        // For Python top-level fields, shortName is now "moduleName.fieldName"
        CodeUnit topValueCU = CodeUnit.field(varsPyFile, "", "vars.TOP_VALUE");
        CodeUnit exportLikeCU = CodeUnit.field(varsPyFile, "", "vars.export_like");

        assertTrue(
                skelVars.containsKey(topValueCU),
                "Skeletons map should contain vars.TOP_VALUE. Found: " + skelVars.keySet());
        assertEquals("TOP_VALUE = 99", skelVars.get(topValueCU).strip());

        assertTrue(
                skelVars.containsKey(exportLikeCU),
                "Skeletons map should contain export_like. Found: " + skelVars.keySet());
        assertEquals(
                "export_like = \"not really\"",
                skelVars.get(exportLikeCU).strip()); // Note: Query captures the whole assignment

        // Ensure these are not mistaken for classes
        Set<CodeUnit> declarationsInVarsPy = analyzer.getDeclarations(varsPyFile);
        assertTrue(
                declarationsInVarsPy.contains(topValueCU),
                "TOP_VALUE should be in declarations list for vars.py. Found: " + declarationsInVarsPy);
        assertFalse(topValueCU.isClass(), "TOP_VALUE CU should not be a class.");
        assertTrue(
                declarationsInVarsPy.contains(exportLikeCU),
                "export_like should be in declarations list for vars.py. Found: " + declarationsInVarsPy);
        assertFalse(exportLikeCU.isClass(), "export_like CU should not be a class.");

        // Verify that getTopLevelDeclarations includes these fields
        var topLevelDecls =
                analyzer.withFileProperties(tld -> tld.get(varsPyFile)).topLevelCodeUnits();
        assertNotNull(topLevelDecls, "Top level declarations for vars.py should exist.");
        assertTrue(topLevelDecls.contains(topValueCU), "Top-level declarations should include TOP_VALUE.");
        assertTrue(topLevelDecls.contains(exportLikeCU), "Top-level declarations should include export_like.");
    }

    @Test
    void testPythonGetClassSourceWithComments() {
        Function<String, String> normalize =
                s -> s.lines().map(String::strip).filter(l -> !l.isEmpty()).collect(Collectors.joining("\n"));

        // Test class with preceding comment (use correct FQN format)
        Optional<String> classSourceOpt = analyzer.getClassSource("DocumentedClass", true);
        assertTrue(classSourceOpt.isPresent(), "DocumentedClass should be found");

        String normalizedSource = normalize.apply(classSourceOpt.get());

        // Should include the comment before the class
        assertTrue(
                normalizedSource.contains("# Comment before class"), "Class source should include preceding comment");
        assertTrue(normalizedSource.contains("class DocumentedClass:"), "Class source should include class definition");
        assertTrue(normalizedSource.contains("\"\"\""), "Class source should include class docstring");

        // Test nested class with comments (use correct FQN format)
        Optional<String> innerClassSourceOpt = analyzer.getClassSource("OuterClass$InnerClass", true);
        assertTrue(innerClassSourceOpt.isPresent(), "OuterClass$InnerClass should be found");

        String normalizedInnerSource = normalize.apply(innerClassSourceOpt.get());

        // Should include comment before nested class
        assertTrue(
                normalizedInnerSource.contains("# Comment before nested class")
                        || normalizedInnerSource.contains("Comment before nested class"),
                "Inner class source should include preceding comment");
        assertTrue(
                normalizedInnerSource.contains("class InnerClass:"),
                "Inner class source should include class definition");
    }

    @Test
    void testPythonGetMethodSourceWithComments() {
        Function<String, String> normalize =
                s -> s.lines().map(String::strip).filter(l -> !l.isEmpty()).collect(Collectors.joining("\n"));

        // Test standalone function with docstring
        Optional<String> functionSource = analyzer.getMethodSource("documented.standalone_function", true);
        assertTrue(functionSource.isPresent(), "standalone_function should be found");

        String normalizedFunctionSource = normalize.apply(functionSource.get());
        assertTrue(
                normalizedFunctionSource.contains("def standalone_function(param):"),
                "Function source should include function definition");
        assertTrue(normalizedFunctionSource.contains("\"\"\""), "Function source should include docstring");

        // Test method with preceding comment (use correct FQN format)
        Optional<String> methodSource = analyzer.getMethodSource("DocumentedClass.get_value", true);
        assertTrue(methodSource.isPresent(), "get_value method should be found");

        String normalizedMethodSource = normalize.apply(methodSource.get());
        assertTrue(
                normalizedMethodSource.contains("# Comment before instance method")
                        || normalizedMethodSource.contains("Comment before instance method"),
                "Method source should include preceding comment");
        assertTrue(
                normalizedMethodSource.contains("def get_value(self):"),
                "Method source should include method definition");
        assertTrue(normalizedMethodSource.contains("\"\"\""), "Method source should include method docstring");

        // Test static method with comment (use correct FQN format)
        Optional<String> staticMethodSource = analyzer.getMethodSource("DocumentedClass.utility_method", true);
        assertTrue(staticMethodSource.isPresent(), "utility_method should be found");

        String normalizedStaticSource = normalize.apply(staticMethodSource.get());
        assertTrue(
                normalizedStaticSource.contains("# Comment before static method")
                        || normalizedStaticSource.contains("Comment before static method"),
                "Static method source should include preceding comment");
        assertTrue(normalizedStaticSource.contains("@staticmethod"), "Static method source should include decorator");
        assertTrue(
                normalizedStaticSource.contains("def utility_method(data):"),
                "Static method source should include method definition");

        // Test class method with comment (use correct FQN format)
        Optional<String> classMethodSource = analyzer.getMethodSource("DocumentedClass.create_default", true);
        assertTrue(classMethodSource.isPresent(), "create_default should be found");

        String normalizedClassMethodSource = normalize.apply(classMethodSource.get());
        assertTrue(
                normalizedClassMethodSource.contains("# Comment before class method")
                        || normalizedClassMethodSource.contains("Comment before class method"),
                "Class method source should include preceding comment");
        assertTrue(
                normalizedClassMethodSource.contains("@classmethod"), "Class method source should include decorator");
        assertTrue(
                normalizedClassMethodSource.contains("def create_default(cls):"),
                "Class method source should include method definition");
    }

    @Test
    void testPythonCommentExpansionEdgeCases() {
        // Test constructor with comment (use correct FQN format)
        Optional<String> constructorSource = analyzer.getMethodSource("DocumentedClass.__init__", true);
        assertTrue(constructorSource.isPresent(), "__init__ method should be found");

        Function<String, String> normalize =
                s -> s.lines().map(String::strip).filter(l -> !l.isEmpty()).collect(Collectors.joining("\n"));

        String normalizedConstructorSource = normalize.apply(constructorSource.get());
        assertTrue(
                normalizedConstructorSource.contains("# Comment before constructor")
                        || normalizedConstructorSource.contains("Comment before constructor"),
                "Constructor source should include preceding comment");
        assertTrue(
                normalizedConstructorSource.contains("def __init__(self, value: int):"),
                "Constructor source should include method definition");

        // Test nested class method (use correct FQN format)
        Optional<String> innerMethodSource = analyzer.getMethodSource("OuterClass.InnerClass.inner_method", true);
        assertTrue(innerMethodSource.isPresent(), "inner_method should be found");

        String normalizedInnerMethodSource = normalize.apply(innerMethodSource.get());
        assertTrue(
                normalizedInnerMethodSource.contains("# Comment before inner method")
                        || normalizedInnerMethodSource.contains("Comment before inner method"),
                "Inner method source should include preceding comment");
        assertTrue(
                normalizedInnerMethodSource.contains("def inner_method(self):"),
                "Inner method source should include method definition");
        assertTrue(normalizedInnerMethodSource.contains("\"\"\""), "Inner method source should include docstring");
    }

    @Test
    void testPythonDualRangeExtraction() {
        Function<String, String> normalize =
                s -> s.lines().map(String::strip).filter(l -> !l.isEmpty()).collect(Collectors.joining("\n"));

        // Test class source with and without comments
        Optional<String> classSourceWithComments = analyzer.getClassSource("DocumentedClass", true);
        Optional<String> classSourceWithoutComments = analyzer.getClassSource("DocumentedClass", false);

        assertTrue(classSourceWithComments.isPresent(), "Class source with comments should be present");
        assertTrue(classSourceWithoutComments.isPresent(), "Class source without comments should be present");

        String normalizedWithComments = normalize.apply(classSourceWithComments.get());
        String normalizedWithoutComments = normalize.apply(classSourceWithoutComments.get());

        // With comments should include the preceding comment
        assertTrue(
                normalizedWithComments.startsWith("# Comment before class"),
                "Class source with comments should start with preceding comment");

        // Without comments should NOT include the preceding comment (should start with class definition)
        assertTrue(
                normalizedWithoutComments.startsWith("class DocumentedClass:"),
                "Class source without comments should start with class definition, not comment");

        // Both should include the class definition itself
        assertTrue(
                normalizedWithComments.contains("class DocumentedClass:"),
                "Class source with comments should include class definition");
        assertTrue(
                normalizedWithoutComments.contains("class DocumentedClass:"),
                "Class source without comments should include class definition");

        // Test method source with and without comments
        Optional<String> methodSourceWithComments = analyzer.getMethodSource("DocumentedClass.get_value", true);
        Optional<String> methodSourceWithoutComments = analyzer.getMethodSource("DocumentedClass.get_value", false);

        assertTrue(methodSourceWithComments.isPresent(), "Method source with comments should be present");
        assertTrue(methodSourceWithoutComments.isPresent(), "Method source without comments should be present");

        String normalizedMethodWithComments = normalize.apply(methodSourceWithComments.get());
        String normalizedMethodWithoutComments = normalize.apply(methodSourceWithoutComments.get());

        // With comments should include the preceding comment at the start
        assertTrue(
                normalizedMethodWithComments.startsWith("# Comment before instance method")
                        || normalizedMethodWithComments.startsWith("Comment before instance method"),
                "Method source with comments should start with preceding comment");

        // Without comments should start with the method definition itself
        assertTrue(
                normalizedMethodWithoutComments.startsWith("def get_value(self):"),
                "Method source without comments should start with method definition, not comment");

        // Both should include the method definition itself
        assertTrue(
                normalizedMethodWithComments.contains("def get_value(self):"),
                "Method source with comments should include method definition");
        assertTrue(
                normalizedMethodWithoutComments.contains("def get_value(self):"),
                "Method source without comments should include method definition");
    }

    @Test
    public void testCodeUnitsAreDeduplicated() {
        // getAllDeclarations should not contain duplicate FQNs even if multiple capture paths produce same logical unit
        var allDecls = analyzer.getAllDeclarations();
        var unique = new HashSet<>(allDecls);
        assertEquals(unique.size(), allDecls.size(), "All declaration FQNs should be unique after deduplication");

        var topDecls = analyzer.getTopLevelDeclarations().values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
        var uniqueTopDecls = new HashSet<>(topDecls);
        assertEquals(
                uniqueTopDecls.size(),
                topDecls.size(),
                "Top-level declaration FQNs should be unique after deduplication");
    }
}
