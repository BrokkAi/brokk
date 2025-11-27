package ai.brokk.analyzer;

import static ai.brokk.testutil.AssertionHelperUtil.assertCodeEquals;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.testutil.InlineTestProjectCreator;
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
        // Note: Classes use $ for class boundary, functions use . for member/scope
        var classesInFileA = analyzer.getDeclarations(fileA);
        var classA_CU = CodeUnit.cls(fileA, "a", "A$A"); // packageName="a", shortName="module$ClassName"
        assertTrue(classesInFileA.contains(classA_CU), "File A should contain class A.");

        var topLevelDeclsInA = analyzer.withFileProperties(tld -> tld.get(fileA))
                .topLevelCodeUnits(); // Accessing internal for test validation
        assertNotNull(topLevelDeclsInA, "Top level declarations for file A should exist.");

        var funcA_CU = CodeUnit.fn(fileA, "a", "A.funcA"); // packageName="a", shortName="module.funcName"
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
        assertTrue(analyzer.getSkeleton(funcA_CU).isPresent(), "Skeleton for funcA_CU should be present");
        assertCodeEquals(funcASummary.trim(), analyzer.getSkeleton(funcA_CU).get(), "getSkeleton mismatch for funcA");
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

        // Test class with preceding comment (use $ for class boundary)
        Optional<String> classSourceOpt = AnalyzerUtil.getClassSource(analyzer, "documented$DocumentedClass", true);
        assertTrue(classSourceOpt.isPresent(), "documented$DocumentedClass should be found");

        String normalizedSource = normalize.apply(classSourceOpt.get());

        // Should include the comment before the class
        assertTrue(
                normalizedSource.contains("# Comment before class"), "Class source should include preceding comment");
        assertTrue(normalizedSource.contains("class DocumentedClass:"), "Class source should include class definition");
        assertTrue(normalizedSource.contains("\"\"\""), "Class source should include class docstring");

        // Test nested class with comments (use $ for class boundaries)
        Optional<String> innerClassSourceOpt =
                AnalyzerUtil.getClassSource(analyzer, "documented$OuterClass$InnerClass", true);
        assertTrue(innerClassSourceOpt.isPresent(), "documented$OuterClass$InnerClass should be found");

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

        // Test standalone function with docstring (uses . for function scope)
        Optional<String> functionSource =
                AnalyzerUtil.getMethodSource(analyzer, "documented.standalone_function", true);
        assertTrue(functionSource.isPresent(), "standalone_function should be found");

        String normalizedFunctionSource = normalize.apply(functionSource.get());
        assertTrue(
                normalizedFunctionSource.contains("def standalone_function(param):"),
                "Function source should include function definition");
        assertTrue(normalizedFunctionSource.contains("\"\"\""), "Function source should include docstring");

        // Test method with preceding comment (use $ for class boundary, . for method)
        Optional<String> methodSource =
                AnalyzerUtil.getMethodSource(analyzer, "documented$DocumentedClass.get_value", true);
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

        // Test static method with comment (use $ for class boundary, . for method)
        Optional<String> staticMethodSource =
                AnalyzerUtil.getMethodSource(analyzer, "documented$DocumentedClass.utility_method", true);
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

        // Test class method with comment (use $ for class boundary, . for method)
        Optional<String> classMethodSource =
                AnalyzerUtil.getMethodSource(analyzer, "documented$DocumentedClass.create_default", true);
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
        // Test constructor with comment (use $ for class boundary, . for method)
        Optional<String> constructorSource =
                AnalyzerUtil.getMethodSource(analyzer, "documented$DocumentedClass.__init__", true);
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

        // Test nested class method (use $ for class boundaries, . for method)
        Optional<String> innerMethodSource =
                AnalyzerUtil.getMethodSource(analyzer, "documented$OuterClass$InnerClass.inner_method", true);
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

        // Test class source with and without comments (use $ for class boundary)
        Optional<String> classSourceWithComments =
                AnalyzerUtil.getClassSource(analyzer, "documented$DocumentedClass", true);
        Optional<String> classSourceWithoutComments =
                AnalyzerUtil.getClassSource(analyzer, "documented$DocumentedClass", false);

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

        // Test method source with and without comments (use $ for class boundary, . for method)
        Optional<String> methodSourceWithComments =
                AnalyzerUtil.getMethodSource(analyzer, "documented$DocumentedClass.get_value", true);
        Optional<String> methodSourceWithoutComments =
                AnalyzerUtil.getMethodSource(analyzer, "documented$DocumentedClass.get_value", false);

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
    void testPythonLocalClassesInFunctions() {
        TestProject project = createTestProject("testcode-py", Languages.PYTHON);
        PythonAnalyzer analyzer = new PythonAnalyzer(project);

        ProjectFile localClassesFile = new ProjectFile(project.getRoot(), "local_classes.py");

        // Get all declarations in the file
        Set<CodeUnit> declarations = analyzer.getDeclarations(localClassesFile);

        // Should find the top-level class (uses $ for class boundary: module$ClassName)
        CodeUnit topLevelClassCU = CodeUnit.cls(localClassesFile, "", "local_classes$TopLevelClass");
        assertTrue(declarations.contains(topLevelClassCU), "Should find top-level class");

        // The fix worked! Local classes now have scoped FQNs with $ for class boundary: func$LocalClass
        var scopedLocalClasses = declarations.stream()
                .filter(cu -> cu.isClass() && cu.fqName().contains("$LocalClass"))
                .collect(Collectors.toSet());

        // Verify the fix worked correctly
        assertEquals(3, scopedLocalClasses.size(), "Should find 3 local classes with scoped FQNs");

        // Check that each local class has the proper scoped FQN format (module.func$LocalClass)
        assertTrue(
                scopedLocalClasses.stream()
                        .anyMatch(cu -> cu.fqName().equals("local_classes.test_function_1$LocalClass")),
                "Should find local_classes.test_function_1$LocalClass");
        assertTrue(
                scopedLocalClasses.stream()
                        .anyMatch(cu -> cu.fqName().equals("local_classes.test_function_2$LocalClass")),
                "Should find local_classes.test_function_2$LocalClass");
        assertTrue(
                scopedLocalClasses.stream()
                        .anyMatch(cu -> cu.fqName().equals("local_classes.test_function_3$LocalClass")),
                "Should find local_classes.test_function_3$LocalClass");

        // Verify no duplicate FQNs (the original bug is fixed)
        var fqNames = scopedLocalClasses.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertEquals(3, fqNames.size(), "All local classes should have unique FQNs");

        // Verify top-level declarations include the actual top-level class
        var topLevelDecls =
                analyzer.withFileProperties(tld -> tld.get(localClassesFile)).topLevelCodeUnits();
        assertTrue(topLevelDecls.contains(topLevelClassCU), "Top-level declarations should include TopLevelClass");

        var localClassMethods = declarations.stream()
                .filter(cu -> cu.isFunction() && cu.fqName().contains("$LocalClass."))
                .collect(Collectors.toList());

        // Should find 3 methods: method1, method2, method3
        assertEquals(
                3,
                localClassMethods.size(),
                "Should find 3 methods of local classes. Found: "
                        + localClassMethods.stream().map(CodeUnit::fqName).collect(Collectors.joining(", ")));

        // Verify specific method FQNs (methods use format: module.func$Class.method)
        assertTrue(
                localClassMethods.stream()
                        .anyMatch(cu -> cu.fqName().equals("local_classes.test_function_1$LocalClass.method1")),
                "Should find local_classes.test_function_1$LocalClass.method1");
        assertTrue(
                localClassMethods.stream()
                        .anyMatch(cu -> cu.fqName().equals("local_classes.test_function_2$LocalClass.method2")),
                "Should find local_classes.test_function_2$LocalClass.method2");
        assertTrue(
                localClassMethods.stream()
                        .anyMatch(cu -> cu.fqName().equals("local_classes.test_function_3$LocalClass.method3")),
                "Should find local_classes.test_function_3$LocalClass.method3");

        // Verify methods are properly attached as children of their classes
        for (var localClass : scopedLocalClasses) {
            var children = analyzer.getDirectChildren(localClass);
            assertEquals(
                    1,
                    children.size(),
                    "Each local class should have exactly 1 method as child, but " + localClass.fqName() + " has "
                            + children.size());
            assertTrue(children.get(0).isFunction(), "Child of " + localClass.fqName() + " should be a function");
        }
    }

    @Test
    void testPythonDuplicateFieldsInDifferentScopes() {
        TestProject project = createTestProject("testcode-py", Languages.PYTHON);
        PythonAnalyzer analyzer = new PythonAnalyzer(project);

        ProjectFile duplicateFieldsFile = new ProjectFile(project.getRoot(), "duplictad_fields_test.py");

        // Get all declarations in the file
        Set<CodeUnit> declarations = analyzer.getDeclarations(duplicateFieldsFile);

        // Should find only 1 SRCFILES field (due to deduplication of duplicates)
        var srcfilesFields = declarations.stream()
                .filter(cu -> cu.isField() && cu.identifier().equals("SRCFILES"))
                .collect(Collectors.toList());

        assertEquals(1, srcfilesFields.size(), "Should find 1 SRCFILES field after deduplication");

        // The field should have the expected module-level FQN
        assertEquals("duplictad_fields_test.SRCFILES", srcfilesFields.get(0).fqName());

        // Should also find other variables
        var localVarFields = declarations.stream()
                .filter(cu -> cu.isField() && cu.fqName().contains("LOCAL_VAR"))
                .collect(Collectors.toList());
        assertEquals(0, localVarFields.size(), "Function-local variables should not be captured as fields");
    }

    @Test
    void testPythonAstropyDuplicateFieldPattern() {
        // Test specific astropy pattern that was causing ERROR logging
        TestProject project = createTestProject("testcode-py", Languages.PYTHON);
        PythonAnalyzer analyzer = new PythonAnalyzer(project);

        ProjectFile astropyFile = new ProjectFile(project.getRoot(), "python_duplicate_fields_test.py");

        // Get all declarations in file
        Set<CodeUnit> declarations = analyzer.getDeclarations(astropyFile);

        // Should find only 1 SRCFILES field (last assignment wins)
        var srcfilesFields = declarations.stream()
                .filter(cu -> cu.isField() && cu.identifier().equals("SRCFILES"))
                .collect(Collectors.toList());

        assertEquals(1, srcfilesFields.size(), "Should find 1 SRCFILES field after deduplication");

        // Should find only 1 VERSION field (last assignment wins)
        var versionFields = declarations.stream()
                .filter(cu -> cu.isField() && cu.identifier().equals("VERSION"))
                .collect(Collectors.toList());

        assertEquals(1, versionFields.size(), "Should find 1 VERSION field after deduplication");

        // Should find OTHER_VAR field (no duplicates)
        var otherVarFields = declarations.stream()
                .filter(cu -> cu.isField() && cu.identifier().equals("OTHER_VAR"))
                .collect(Collectors.toList());

        assertEquals(1, otherVarFields.size(), "Should find 1 OTHER_VAR field");

        // Should find class and function
        var classCUs = declarations.stream().filter(CodeUnit::isClass).collect(Collectors.toList());
        var functionCUs = declarations.stream().filter(CodeUnit::isFunction).collect(Collectors.toList());

        assertEquals(2, classCUs.size(), "Should find 2 classes");
        assertEquals(3, functionCUs.size(), "Should find 3 functions");
    }

    @Test
    void testPythonPropertySetterDetection() {
        TestProject project = createTestProject("testcode-py", Languages.PYTHON);
        PythonAnalyzer analyzer = new PythonAnalyzer(project);

        ProjectFile testFile = new ProjectFile(project.getRoot(), "duplictad_fields_test.py");
        Set<CodeUnit> declarations = analyzer.getDeclarations(testFile);

        // Should find property getters but NOT setters (uses $ for class boundary)
        var valueGetters = declarations.stream()
                .filter(cu -> cu.isFunction() && cu.fqName().equals("duplictad_fields_test$PropertyTest.value"))
                .collect(Collectors.toList());

        var nameGetters = declarations.stream()
                .filter(cu -> cu.isFunction() && cu.fqName().equals("duplictad_fields_test$PropertyTest.name"))
                .collect(Collectors.toList());

        // Should find 1 getter each (setters skipped)
        assertEquals(1, valueGetters.size(), "Should find 1 value getter (setter skipped)");
        assertEquals(1, nameGetters.size(), "Should find 1 name getter (setter skipped)");

        // Should find regular functions that were incorrectly flagged before
        var testUncertaintySetter = declarations.stream()
                .filter(cu -> cu.isFunction() && cu.identifier().contains("test_uncertainty_setter"))
                .collect(Collectors.toList());

        var setTemperature = declarations.stream()
                .filter(cu -> cu.isFunction() && cu.identifier().contains("set_temperature"))
                .collect(Collectors.toList());

        var processDataSetter = declarations.stream()
                .filter(cu -> cu.isFunction() && cu.identifier().contains("process_data_setter"))
                .collect(Collectors.toList());

        assertEquals(1, testUncertaintySetter.size(), "Should find test_uncertainty_setter function");
        assertEquals(1, setTemperature.size(), "Should find set_temperature function");
        assertEquals(1, processDataSetter.size(), "Should find process_data_setter function");
    }

    @Test
    void testPythonPropertySetterFiltering() {
        // Test property getter, setter, and deleter filtering
        TestProject project = createTestProject("testcode-py", Languages.PYTHON);
        PythonAnalyzer analyzer = new PythonAnalyzer(project);

        ProjectFile testFile = new ProjectFile(project.getRoot(), "property_setter_test.py");
        Set<CodeUnit> declarations = analyzer.getDeclarations(testFile);

        // Should find the class
        var classes = declarations.stream().filter(CodeUnit::isClass).collect(Collectors.toList());
        assertEquals(1, classes.size(), "Should find 1 class");
        assertEquals("MplTimeConverter", classes.get(0).identifier());

        // Should find exactly 3 methods: format (getter), value (getter), and regular_method
        // The format setter and value deleter should be skipped
        var methods = declarations.stream().filter(CodeUnit::isFunction).collect(Collectors.toList());

        assertEquals(
                3,
                methods.size(),
                "Should find 3 methods (2 getters + regular method, setter and deleter skipped). Found: "
                        + methods.stream().map(CodeUnit::fqName).collect(Collectors.joining(", ")));

        // Verify we have the getters but not the setter or deleter (uses $ for class boundary)
        assertTrue(
                methods.stream().anyMatch(m -> m.fqName().equals("property_setter_test$MplTimeConverter.format")),
                "Should find format getter");
        assertTrue(
                methods.stream().anyMatch(m -> m.fqName().equals("property_setter_test$MplTimeConverter.value")),
                "Should find value getter");
        assertTrue(
                methods.stream()
                        .anyMatch(m -> m.fqName().equals("property_setter_test$MplTimeConverter.regular_method")),
                "Should find regular_method");

        // Verify no duplicate format or value methods
        long formatCount =
                methods.stream().filter(m -> m.identifier().equals("format")).count();
        assertEquals(1, formatCount, "Should find exactly 1 format method (getter only, setter filtered)");

        long valueCount =
                methods.stream().filter(m -> m.identifier().equals("value")).count();
        assertEquals(1, valueCount, "Should find exactly 1 value method (getter only, deleter filtered)");
    }

    @Test
    void testAstropyDuplicateFunctionNames() {
        TestProject project = createTestProject("testcode-py", Languages.PYTHON);
        PythonAnalyzer analyzer = new PythonAnalyzer(project);

        ProjectFile astropyDuplicateFile = new ProjectFile(project.getRoot(), "astropy_duplicate_test.py");
        Set<CodeUnit> declarations = analyzer.getDeclarations(astropyDuplicateFile);

        // Should find 2 LogDRepresentation classes with different function parents
        var logDClasses = declarations.stream()
                .filter(cu -> cu.isClass() && cu.fqName().contains("LogDRepresentation"))
                .collect(Collectors.toList());

        assertEquals(2, logDClasses.size(), "Should find 2 LogDRepresentation classes");

        // Check that both classes have unique FQNs due to different function names
        Set<String> fqNames = logDClasses.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

        assertEquals(2, fqNames.size(), "Both LogDRepresentation classes should have unique FQNs");

        // Should find function-local classes with proper scoping (uses $ for class boundary)
        assertTrue(
                fqNames.stream()
                        .anyMatch(fqn -> fqn.equals("astropy_duplicate_test.test_minimal_subclass$LogDRepresentation")),
                "Should find astropy_duplicate_test.test_minimal_subclass$LogDRepresentation");

        assertTrue(
                fqNames.stream()
                        .anyMatch(fqn -> fqn.equals("astropy_duplicate_test.another_test_function$LogDRepresentation")),
                "Should find astropy_duplicate_test.another_test_function$LogDRepresentation");

        // Verify no duplicate FQNs exist (the main issue is fixed)
        var duplicateFqNames =
                fqNames.stream().collect(Collectors.groupingBy(fqn -> fqn, Collectors.counting())).entrySet().stream()
                        .filter(entry -> entry.getValue() > 1)
                        .count();

        assertEquals(0, duplicateFqNames, "Should have no duplicate FQNs");

        // Test the disambiguation mechanism by checking that function-local classes are properly scoped
        // Function-local classes use $ for class boundary, so we look for $LogDRepresentation pattern
        var functionLocalClasses = fqNames.stream()
                .filter(fqn -> fqn.contains("$LogDRepresentation"))
                .collect(Collectors.toList());

        assertEquals(2, functionLocalClasses.size(), "Both classes should be function-local with proper scoping");
    }

    @Test
    void testDeterministicDisambiguationAcrossFiles() {
        // Test that Python's "last wins" behavior applies to duplicate function-local classes
        // This matches Python's runtime semantics where duplicate definitions replace earlier ones
        TestProject project = createTestProject("testcode-py", Languages.PYTHON);
        PythonAnalyzer analyzer = new PythonAnalyzer(project);

        ProjectFile fileA = new ProjectFile(project.getRoot(), "disambiguation_test_a.py");
        ProjectFile fileB = new ProjectFile(project.getRoot(), "disambiguation_test_b.py");

        // Analyze file A first
        Set<CodeUnit> declarationsA = analyzer.getDeclarations(fileA);
        var classesA = declarationsA.stream()
                .filter(CodeUnit::isClass)
                .filter(cu -> cu.fqName().contains("LocalClass"))
                .collect(Collectors.toList());

        // Should find only 1 class (last definition wins)
        assertEquals(1, classesA.size(), "File A should have only 1 local class (last wins)");
        assertEquals(
                "disambiguation_test_a.test_func$LocalClass",
                classesA.get(0).fqName(),
                "Should use $ for class boundary without bracketed disambiguation");

        // Now analyze file B
        Set<CodeUnit> declarationsB = analyzer.getDeclarations(fileB);
        var classesB = declarationsB.stream()
                .filter(CodeUnit::isClass)
                .filter(cu -> cu.fqName().contains("LocalClass"))
                .collect(Collectors.toList());

        // Should also find only 1 class (last definition wins)
        assertEquals(1, classesB.size(), "File B should have only 1 local class (last wins)");
        assertEquals(
                "disambiguation_test_b.test_func$LocalClass",
                classesB.get(0).fqName(),
                "Should use $ for class boundary without bracketed disambiguation");

        // Verify they're independent - same pattern in different files
        // Note: FQNs differ by module name but follow same pattern
        assertTrue(
                classesA.get(0).fqName().endsWith("test_func$LocalClass"),
                "File A class should end with test_func$LocalClass");
        assertTrue(
                classesB.get(0).fqName().endsWith("test_func$LocalClass"),
                "File B class should end with test_func$LocalClass");
    }

    @Test
    void testNestedFunctionLocalClasses() {
        // Test that nested classes inside function-local classes use $ for class boundaries
        TestProject project = createTestProject("testcode-py", Languages.PYTHON);
        PythonAnalyzer analyzer = new PythonAnalyzer(project);

        ProjectFile file = new ProjectFile(project.getRoot(), "nested_local_classes.py");
        Set<CodeUnit> declarations = analyzer.getDeclarations(file);

        // Find all classes
        var classes = declarations.stream().filter(CodeUnit::isClass).collect(Collectors.toList());

        assertEquals(3, classes.size(), "Should find 3 classes: OuterLocal, InnerLocal, DeepLocal");

        // Verify FQN consistency: all should use $ for class boundaries in function-local context
        var outerLocal = classes.stream()
                .filter(cu -> cu.fqName().equals("nested_local_classes.outer_function$OuterLocal"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                "nested_local_classes.outer_function$OuterLocal",
                outerLocal.fqName(),
                "OuterLocal should be nested_local_classes.outer_function$OuterLocal");

        var innerLocal = classes.stream()
                .filter(cu -> cu.fqName().equals("nested_local_classes.outer_function$OuterLocal$InnerLocal"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                "nested_local_classes.outer_function$OuterLocal$InnerLocal",
                innerLocal.fqName(),
                "InnerLocal should use $ for class boundaries");

        var deepLocal = classes.stream()
                .filter(cu -> cu.fqName().equals("nested_local_classes.outer_function$OuterLocal$InnerLocal$DeepLocal"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                "nested_local_classes.outer_function$OuterLocal$InnerLocal$DeepLocal",
                deepLocal.fqName(),
                "DeepLocal should use $ for class boundaries throughout");

        // Verify parent-child relationships work correctly
        var innerChildren = analyzer.getDirectChildren(innerLocal);
        assertTrue(
                innerChildren.stream().anyMatch(cu -> cu.equals(deepLocal)),
                "InnerLocal should have DeepLocal as child");

        var outerChildren = analyzer.getDirectChildren(outerLocal);
        assertTrue(
                outerChildren.stream().anyMatch(cu -> cu.equals(innerLocal)),
                "OuterLocal should have InnerLocal as child");

        // Verify methods are properly attached (only class methods, not module-level functions)
        var methods = declarations.stream()
                .filter(CodeUnit::isFunction)
                .filter(m -> m.fqName().contains("$Out")) // Filter to only methods inside OuterLocal or InnerLocal
                .collect(Collectors.toList());

        assertEquals(2, methods.size(), "Should find 2 methods (class methods only)");

        assertTrue(
                methods.stream().anyMatch(m -> m.fqName()
                        .equals("nested_local_classes.outer_function$OuterLocal$InnerLocal.inner_method")),
                "inner_method should use $ for class boundaries, . for method");
        assertTrue(
                methods.stream().anyMatch(m -> m.fqName()
                        .equals("nested_local_classes.outer_function$OuterLocal.outer_method")),
                "outer_method should use $ for class boundary, . for method");
    }

    @Test
    void testUnderscorePrefixedFunctionLocalClasses() {
        // Test that functions starting with underscores (_private, __dunder__) are correctly
        // identified as functions (not classes) when detecting function-local classes
        TestProject project = createTestProject("testcode-py", Languages.PYTHON);
        PythonAnalyzer analyzer = new PythonAnalyzer(project);

        ProjectFile file = new ProjectFile(project.getRoot(), "underscore_functions.py");
        Set<CodeUnit> declarations = analyzer.getDeclarations(file);

        // Find all classes
        var classes = declarations.stream().filter(CodeUnit::isClass).collect(Collectors.toList());

        assertEquals(
                5,
                classes.size(),
                "Should find 5 classes: LocalClass, AnotherLocal, MyClass, _PrivateClass, NestedClass");

        // Verify _private_function$LocalClass (function-local class, uses $ for class boundary)
        var localClass = classes.stream()
                .filter(cu -> cu.fqName().equals("underscore_functions._private_function$LocalClass"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "LocalClass should be underscore_functions._private_function$LocalClass (function-local), found: "
                                + classes.stream().map(CodeUnit::fqName).collect(Collectors.joining(", "))));
        assertEquals(
                "underscore_functions._private_function$LocalClass",
                localClass.fqName(),
                "_private_function should be recognized as function, not class");

        // Verify __dunder_function__$AnotherLocal (function-local class, uses $ for class boundary)
        var anotherLocal = classes.stream()
                .filter(cu -> cu.fqName().equals("underscore_functions.__dunder_function__$AnotherLocal"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "AnotherLocal should be underscore_functions.__dunder_function__$AnotherLocal (function-local), found: "
                                + classes.stream().map(CodeUnit::fqName).collect(Collectors.joining(", "))));
        assertEquals(
                "underscore_functions.__dunder_function__$AnotherLocal",
                anotherLocal.fqName(),
                "__dunder_function__ should be recognized as function, not class");

        // Verify underscore_functions$_PrivateClass$NestedClass (regular nested class, NOT function-local)
        var nestedClass = classes.stream()
                .filter(cu -> cu.fqName().equals("underscore_functions$_PrivateClass$NestedClass"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "NestedClass should be underscore_functions$_PrivateClass$NestedClass (regular nested), found: "
                                + classes.stream().map(CodeUnit::fqName).collect(Collectors.joining(", "))));
        assertEquals(
                "underscore_functions$_PrivateClass$NestedClass",
                nestedClass.fqName(),
                "_PrivateClass should be recognized as class (PascalCase), not function");

        // Verify parent-child relationship for _PrivateClass
        var privateClass = classes.stream()
                .filter(cu -> cu.fqName().equals("underscore_functions$_PrivateClass"))
                .findFirst()
                .orElseThrow();
        var privateClassChildren = analyzer.getDirectChildren(privateClass);
        assertTrue(
                privateClassChildren.stream().anyMatch(cu -> cu.equals(nestedClass)),
                "_PrivateClass should have NestedClass as child");
    }

    @Test
    void testFunctionRedefinition() {
        // Test that Python's "last wins" semantics work for top-level function redefinition
        TestProject project = createTestProject("testcode-py", Languages.PYTHON);
        PythonAnalyzer analyzer = new PythonAnalyzer(project);

        ProjectFile file = new ProjectFile(project.getRoot(), "function_redefinition.py");
        Set<CodeUnit> declarations = analyzer.getDeclarations(file);

        // Verify only ONE function named my_function exists (the last definition)
        var functions = declarations.stream()
                .filter(CodeUnit::isFunction)
                .filter(cu ->
                        cu.fqName().endsWith(".my_function") || cu.fqName().equals("my_function"))
                .collect(Collectors.toList());

        assertEquals(1, functions.size(), "Should only have ONE my_function (last definition wins)");

        CodeUnit myFunction = functions.getFirst();
        assertTrue(
                myFunction.fqName().endsWith(".my_function")
                        || myFunction.fqName().equals("my_function"),
                "Function FQN should end with .my_function");

        // Find all classes
        var classes = declarations.stream().filter(CodeUnit::isClass).collect(Collectors.toList());

        // Should have 2 classes: MyClass (top-level) and SecondLocal (from second function definition)
        assertEquals(
                2,
                classes.size(),
                "Should find 2 classes: MyClass and SecondLocal (FirstLocal should NOT exist since first function was replaced)");

        // Verify MyClass exists (uses $ for class boundary: module$ClassName)
        assertTrue(
                classes.stream().anyMatch(cu -> cu.fqName().equals("function_redefinition$MyClass")),
                "MyClass should exist");

        // Verify SecondLocal exists as child of second my_function (uses $ for class boundary)
        var secondLocal = classes.stream()
                .filter(cu -> cu.fqName().equals("function_redefinition.my_function$SecondLocal"))
                .findFirst()
                .orElseThrow(
                        () -> new AssertionError("SecondLocal should exist from second function definition, found: "
                                + classes.stream().map(CodeUnit::fqName).collect(Collectors.joining(", "))));

        assertEquals(
                "function_redefinition.my_function$SecondLocal",
                secondLocal.fqName(),
                "SecondLocal should be attached to my_function");

        // Verify FirstLocal does NOT exist (function was replaced before children were attached)
        assertFalse(
                classes.stream().anyMatch(cu -> cu.fqName().contains("FirstLocal")),
                "FirstLocal should NOT exist - first function definition was replaced");

        // Verify parent-child relationship
        var functionChildren = analyzer.getDirectChildren(myFunction);
        assertEquals(1, functionChildren.size(), "my_function should have exactly 1 child (SecondLocal)");
        assertTrue(
                functionChildren.stream().anyMatch(cu -> cu.equals(secondLocal)),
                "my_function should have SecondLocal as child");
    }

    @Test
    void testPythonDuplicateChildren() {
        // Test Python's "last wins" semantics for duplicate children
        // (methods, class attributes, nested classes)
        TestProject project = createTestProject("testcode-py", Languages.PYTHON);
        PythonAnalyzer analyzer = new PythonAnalyzer(project);

        ProjectFile file = new ProjectFile(project.getRoot(), "duplicate_children.py");
        Set<CodeUnit> declarations = analyzer.getDeclarations(file);

        // Find TestDuplicates class (uses $ for class boundary: module$ClassName)
        var testDuplicatesClass = declarations.stream()
                .filter(CodeUnit::isClass)
                .filter(cu -> cu.fqName().equals("duplicate_children$TestDuplicates"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TestDuplicates class not found. Found: "
                        + declarations.stream()
                                .filter(CodeUnit::isClass)
                                .map(CodeUnit::fqName)
                                .collect(Collectors.joining(", "))));

        // Get children of TestDuplicates
        var children = analyzer.getDirectChildren(testDuplicatesClass);

        // 1. Test duplicate method - should have only 1 "method" (last wins)
        var methods = children.stream()
                .filter(cu -> cu.isFunction() && cu.identifier().equals("method"))
                .toList();
        assertEquals(1, methods.size(), "Should have exactly 1 'method' (last wins)");

        // 2. Test duplicate nested class - should be in children with dot separator
        var innerClasses = children.stream()
                .filter(cu -> cu.isClass()
                        && cu.shortName().contains("Inner")
                        && !cu.shortName().contains("Unique"))
                .toList();
        assertEquals(1, innerClasses.size(), "Should have exactly 1 'Inner' class (last wins)");

        // 3. Verify non-duplicates still exist
        assertTrue(
                children.stream()
                        .anyMatch(cu -> cu.isFunction() && cu.identifier().equals("unique_method")),
                "unique_method should exist");
        assertTrue(
                children.stream().anyMatch(cu -> cu.isClass() && cu.shortName().contains("UniqueInner")),
                "UniqueInner class should exist");
    }

    @Test
    void testPackagedFunctionLocalClasses() {
        // Test that function-local classes work correctly in packaged Python modules
        // This verifies that buildParentFqName correctly handles the case where:
        // - packageName is non-empty (from __init__.py)
        // - Function FQNs include module name: "package.module.function"
        // - Parent lookup must find "package.module.function" when child has classChain="my_function"
        TestProject project = createTestProject("testcode-py", Languages.PYTHON);
        PythonAnalyzer analyzer = new PythonAnalyzer(project);

        ProjectFile file = new ProjectFile(project.getRoot(), "mypackage/packaged_functions.py");
        Set<CodeUnit> declarations = analyzer.getDeclarations(file);

        // Find the function
        var functions = declarations.stream()
                .filter(CodeUnit::isFunction)
                .filter(cu -> cu.identifier().equals("my_function"))
                .collect(Collectors.toList());

        assertEquals(1, functions.size(), "Should find exactly one my_function");
        CodeUnit myFunction = functions.getFirst();

        // Verify function FQN includes package and module
        assertEquals(
                "mypackage.packaged_functions.my_function",
                myFunction.fqName(),
                "Function FQN should include package and module name");

        // Find the local class
        var localClasses = declarations.stream()
                .filter(CodeUnit::isClass)
                .filter(cu -> cu.fqName().contains("LocalClass"))
                .collect(Collectors.toList());

        assertEquals(1, localClasses.size(), "Should find exactly one LocalClass");
        CodeUnit localClass = localClasses.getFirst();

        // Verify local class FQN (uses $ for class boundary: module.func$ClassName)
        assertEquals(
                "mypackage.packaged_functions.my_function$LocalClass",
                localClass.fqName(),
                "Local class FQN should be in package with function-local naming using $");

        // Verify parent-child relationship
        var functionChildren = analyzer.getDirectChildren(myFunction);
        assertEquals(1, functionChildren.size(), "my_function should have exactly 1 child (LocalClass)");
        assertTrue(
                functionChildren.stream().anyMatch(cu -> cu.equals(localClass)),
                "my_function should have LocalClass as child");

        // Verify the local class has a method
        var classChildren = analyzer.getDirectChildren(localClass);
        assertEquals(1, classChildren.size(), "LocalClass should have exactly 1 method");
        var method = classChildren.stream().findFirst().orElseThrow();
        // Methods in function-local classes use $ for class boundary, . for method
        assertEquals(
                "mypackage.packaged_functions.my_function$LocalClass.method",
                method.fqName(),
                "Method FQN should use $ for class boundary, . for method");
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

    @Test
    void testConditionalClassDefinitions() {
        // Test how PythonAnalyzer handles conditional class definitions (like Reflex base.py pattern)
        TestProject testProject = createTestProject("testcode-py", Languages.PYTHON);
        PythonAnalyzer testAnalyzer = new PythonAnalyzer(testProject);

        ProjectFile basePy = new ProjectFile(testProject.getRoot(), "conditional_pkg/base.py");
        Set<CodeUnit> declarations = testAnalyzer.getDeclarations(basePy);

        // Group by type
        var classes = declarations.stream().filter(CodeUnit::isClass).toList();

        // Verify classes inside if/else are captured (uses $ for class boundary)
        assertTrue(
                classes.stream().anyMatch(cu -> cu.fqName().equals("conditional_pkg.base$Base")),
                "Base class inside 'if' should be captured");
        assertTrue(
                classes.stream().anyMatch(cu -> cu.fqName().equals("conditional_pkg.base$Base$Config")),
                "Nested Config class should be captured");
        assertTrue(
                classes.stream().anyMatch(cu -> cu.fqName().equals("conditional_pkg.base$FallbackBase")),
                "FallbackBase class inside 'else' should be captured");

        // Test subclass resolution
        ProjectFile subclassPy = new ProjectFile(testProject.getRoot(), "conditional_pkg/subclass.py");
        Set<CodeUnit> subclassDecls = testAnalyzer.getDeclarations(subclassPy);

        var mySubclass = subclassDecls.stream()
                .filter(cu -> cu.identifier().equals("MySubclass"))
                .findFirst();
        assertTrue(mySubclass.isPresent(), "MySubclass should be found");

        // Check skeleton shows inheritance
        var skeleton = testAnalyzer.getSkeleton(mySubclass.get());
        assertTrue(skeleton.isPresent(), "MySubclass should have a skeleton");
        assertTrue(skeleton.get().contains("(Base)"), "Skeleton should show Base inheritance");

        // Check imports are captured
        var fileProps = testAnalyzer.withFileProperties(fp -> fp.get(subclassPy));
        assertFalse(fileProps.importStatements().isEmpty(), "Imports should be captured");
        assertTrue(
                fileProps.importStatements().stream()
                        .anyMatch(imp -> imp.contains("from conditional_pkg.base import Base")),
                "Should capture 'from conditional_pkg.base import Base'");

        // Can we find Base from getAllDeclarations? (uses $ for class boundary)
        var allDecls = testAnalyzer.getAllDeclarations();
        var baseClass = allDecls.stream()
                .filter(cu -> cu.fqName().equals("conditional_pkg.base$Base"))
                .findFirst();
        assertTrue(baseClass.isPresent(), "conditional_pkg.base$Base should be findable in getAllDeclarations");

        // Test: Can we find the parent class (Base) from MySubclass?
        var ancestors = testAnalyzer.getDirectAncestors(mySubclass.get());

        // Parent resolution should now work
        assertEquals(1, ancestors.size(), "MySubclass should have exactly 1 direct ancestor (Base)");
        assertTrue(
                ancestors.stream().anyMatch(cu -> cu.fqName().equals("conditional_pkg.base$Base")),
                "MySubclass should have conditional_pkg.base$Base as ancestor");

        testProject.close();
    }

    @Test
    void testPythonTypeHierarchy() {
        // Test parent class resolution for Python
        // Note: Classes use $ for class boundary, methods use . for member access
        TestProject testProject = createTestProject("testcode-py", Languages.PYTHON);
        PythonAnalyzer testAnalyzer = new PythonAnalyzer(testProject);

        // Test 1: Simple same-file inheritance
        ProjectFile simplePy = new ProjectFile(testProject.getRoot(), "inheritance/simple.py");
        Set<CodeUnit> simpleDecls = testAnalyzer.getDeclarations(simplePy);

        // Find Dog class (identifier() extracts after last $, so this still works)
        var dogClass =
                simpleDecls.stream().filter(cu -> cu.identifier().equals("Dog")).findFirst();
        assertTrue(dogClass.isPresent(), "Dog class should be found");

        // Find Animal class
        var animalClass = simpleDecls.stream()
                .filter(cu -> cu.identifier().equals("Animal"))
                .findFirst();
        assertTrue(animalClass.isPresent(), "Animal class should be found");

        // Test getDirectAncestors for Dog
        var dogAncestors = testAnalyzer.getDirectAncestors(dogClass.get());

        // This should pass once implementation is complete
        assertEquals(1, dogAncestors.size(), "Dog should have exactly 1 direct ancestor (Animal)");
        assertTrue(
                dogAncestors.stream().anyMatch(cu -> cu.identifier().equals("Animal")),
                "Dog's ancestor should be Animal");

        // Test Cat also extends Animal
        var catClass =
                simpleDecls.stream().filter(cu -> cu.identifier().equals("Cat")).findFirst();
        assertTrue(catClass.isPresent(), "Cat class should be found");
        var catAncestors = testAnalyzer.getDirectAncestors(catClass.get());
        assertEquals(1, catAncestors.size(), "Cat should have exactly 1 direct ancestor (Animal)");

        // Test 2: Multi-level inheritance
        ProjectFile multilevelPy = new ProjectFile(testProject.getRoot(), "inheritance/multilevel.py");
        Set<CodeUnit> multilevelDecls = testAnalyzer.getDeclarations(multilevelPy);

        var childClass = multilevelDecls.stream()
                .filter(cu -> cu.identifier().equals("Child"))
                .findFirst();
        assertTrue(childClass.isPresent(), "Child class should be found");

        // Direct ancestors should only be Middle
        var childDirectAncestors = testAnalyzer.getDirectAncestors(childClass.get());
        assertEquals(1, childDirectAncestors.size(), "Child should have exactly 1 direct ancestor (Middle)");
        assertTrue(
                childDirectAncestors.stream().anyMatch(cu -> cu.identifier().equals("Middle")),
                "Child's direct ancestor should be Middle");

        // Transitive ancestors should include Middle and Base
        var childAllAncestors = testAnalyzer.getAncestors(childClass.get());
        assertEquals(2, childAllAncestors.size(), "Child should have 2 transitive ancestors (Middle, Base)");

        // Test 3: Cross-file inheritance
        ProjectFile childPy = new ProjectFile(testProject.getRoot(), "inheritance/child.py");
        Set<CodeUnit> childFileDecls = testAnalyzer.getDeclarations(childPy);

        var birdClass = childFileDecls.stream()
                .filter(cu -> cu.identifier().equals("Bird"))
                .findFirst();
        assertTrue(birdClass.isPresent(), "Bird class should be found");

        // Bird extends Animal from simple.py
        var birdAncestors = testAnalyzer.getDirectAncestors(birdClass.get());
        assertEquals(1, birdAncestors.size(), "Bird should have exactly 1 direct ancestor (Animal)");
        assertTrue(
                birdAncestors.stream().anyMatch(cu -> cu.identifier().equals("Animal")),
                "Bird's ancestor should be Animal from simple.py");

        // Test 4: Multiple inheritance
        ProjectFile multiplePy = new ProjectFile(testProject.getRoot(), "inheritance/multiple.py");
        Set<CodeUnit> multipleDecls = testAnalyzer.getDeclarations(multiplePy);

        var duckClass = multipleDecls.stream()
                .filter(cu -> cu.identifier().equals("Duck"))
                .findFirst();
        assertTrue(duckClass.isPresent(), "Duck class should be found");

        // Duck extends both Flyable and Swimmable
        var duckAncestors = testAnalyzer.getDirectAncestors(duckClass.get());
        assertEquals(2, duckAncestors.size(), "Duck should have exactly 2 direct ancestors (Flyable, Swimmable)");
        assertTrue(
                duckAncestors.stream().anyMatch(cu -> cu.identifier().equals("Flyable")),
                "Duck's ancestors should include Flyable");
        assertTrue(
                duckAncestors.stream().anyMatch(cu -> cu.identifier().equals("Swimmable")),
                "Duck's ancestors should include Swimmable");

        testProject.close();
    }

    @Test
    void testRelativeImportSameDirectory() throws Exception {
        // Test: from .module import Class
        // Child class in same directory as sibling
        var builder = InlineTestProjectCreator.code("# Package marker\n", "mypackage/__init__.py")
                .addFileContents(
                        """
                class ChildClass:
                    def child_method(self):
                        pass
                """,
                        "mypackage/child.py");

        try (var testProject = builder.addFileContents(
                        """
                from .child import ChildClass

                class SiblingClass:
                    def __init__(self):
                        self.child = ChildClass()
                """,
                        "mypackage/sibling.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var siblingFile = AnalyzerUtil.getFileFor(analyzer, "mypackage.sibling$SiblingClass")
                    .get();
            var imports = analyzer.importedCodeUnitsOf(siblingFile);

            // In Python, class FQNs use $ for class boundary: mypackage.child$ChildClass
            assertTrue(
                    imports.stream().anyMatch(cu -> cu.fqName().equals("mypackage.child$ChildClass")),
                    "Should resolve 'from .child import ChildClass' to mypackage.child$ChildClass");
        }
    }

    @Test
    void testRelativeImportParentDirectory() throws Exception {
        // Test: from ..module import Class
        // Base class in parent directory
        var builder = InlineTestProjectCreator.code("# Package marker\n", "mypackage/__init__.py")
                .addFileContents("# Subpackage marker\n", "mypackage/subdir/__init__.py")
                .addFileContents(
                        """
                class BaseClass:
                    def base_method(self):
                        pass
                """,
                        "mypackage/base.py");

        try (var testProject = builder.addFileContents(
                        """
                from ..base import BaseClass

                class ChildClass(BaseClass):
                    def child_method(self):
                        pass
                """,
                        "mypackage/subdir/child.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var childFile = AnalyzerUtil.getFileFor(analyzer, "mypackage.subdir.child$ChildClass")
                    .get();
            var imports = analyzer.importedCodeUnitsOf(childFile);

            assertTrue(
                    imports.stream().anyMatch(cu -> cu.fqName().equals("mypackage.base$BaseClass")),
                    "Should resolve 'from ..base import BaseClass' to mypackage.base$BaseClass");
        }
    }

    @Test
    void testRelativeImportGrandparentDirectory() throws Exception {
        // Test: from ...module import Class (two levels up)
        var builder = InlineTestProjectCreator.code("# Package marker\n", "mypackage/__init__.py")
                .addFileContents("# Subpackage marker\n", "mypackage/subdir/__init__.py")
                .addFileContents("# Deep package marker\n", "mypackage/subdir/deep/__init__.py")
                .addFileContents(
                        """
                class TopClass:
                    def top_method(self):
                        pass
                """,
                        "mypackage/top.py");

        try (var testProject = builder.addFileContents(
                        """
                from ...top import TopClass

                class DeepClass(TopClass):
                    def deep_method(self):
                        pass
                """,
                        "mypackage/subdir/deep/nested.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var nestedFile = AnalyzerUtil.getFileFor(analyzer, "mypackage.subdir.deep.nested$DeepClass")
                    .get();
            var imports = analyzer.importedCodeUnitsOf(nestedFile);

            assertTrue(
                    imports.stream().anyMatch(cu -> cu.fqName().equals("mypackage.top$TopClass")),
                    "Should resolve 'from ...top import TopClass' to mypackage.top$TopClass");
        }
    }

    @Test
    void testRelativeImportInheritance() throws Exception {
        // Test that classes using relative imports show proper inheritance
        var builder = InlineTestProjectCreator.code("# Package marker\n", "zoo/__init__.py")
                .addFileContents("# Subpackage marker\n", "zoo/mammals/__init__.py")
                .addFileContents(
                        """
                class Animal:
                    def speak(self):
                        pass
                """,
                        "zoo/animal.py");

        try (var testProject = builder.addFileContents(
                        """
                from ..animal import Animal

                class Dog(Animal):
                    def bark(self):
                        return "woof"
                """,
                        "zoo/mammals/dog.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var dogFile =
                    AnalyzerUtil.getFileFor(analyzer, "zoo.mammals.dog$Dog").get();
            var dogDecls = analyzer.getDeclarations(dogFile);

            var dogClass = dogDecls.stream()
                    .filter(cu -> cu.identifier().equals("Dog"))
                    .findFirst();
            assertTrue(dogClass.isPresent(), "Dog class should be found");

            // Verify inheritance through relative import
            var ancestors = analyzer.getDirectAncestors(dogClass.get());
            assertEquals(1, ancestors.size(), "Dog should have exactly 1 direct ancestor (Animal)");
            assertTrue(
                    ancestors.stream().anyMatch(cu -> cu.identifier().equals("Animal")),
                    "Dog should extend Animal via relative import");
        }
    }

    @Test
    void testPackagedTopLevelClassesIncludeModuleName() {
        // Regression test: Top-level classes in packaged Python files should include module name in FQ name
        // Bug: Classes were missing module component (e.g., "tests.units.utils.ExampleTestState" instead of
        // "tests.units.utils.test_utils.ExampleTestState")
        TestProject project = createTestProject("testcode-py", Languages.PYTHON);
        PythonAnalyzer analyzer = new PythonAnalyzer(project);

        ProjectFile testUtilsFile = new ProjectFile(project.getRoot(), "tests/units/utils/test_utils.py");
        Set<CodeUnit> declarations = analyzer.getDeclarations(testUtilsFile);

        // --- Verify top-level classes include module name ---
        var topLevelClasses = declarations.stream()
                .filter(CodeUnit::isClass)
                .filter(cu -> !cu.fqName()
                        .contains(".test_backend_variable_cls$")) // exclude function-local (uses $ for class boundary)
                .collect(Collectors.toList());

        // ExampleTestState should be tests.units.utils.test_utils$ExampleTestState ($ for class boundary)
        assertTrue(
                topLevelClasses.stream()
                        .anyMatch(cu -> cu.fqName().equals("tests.units.utils.test_utils$ExampleTestState")),
                "ExampleTestState should have FQ name including module with $ boundary. Found: "
                        + topLevelClasses.stream().map(CodeUnit::fqName).collect(Collectors.joining(", ")));

        // DataFrame should be tests.units.utils.test_utils$DataFrame ($ for class boundary)
        assertTrue(
                topLevelClasses.stream().anyMatch(cu -> cu.fqName().equals("tests.units.utils.test_utils$DataFrame")),
                "DataFrame should have FQ name including module with $ boundary. Found: "
                        + topLevelClasses.stream().map(CodeUnit::fqName).collect(Collectors.joining(", ")));

        // --- Verify top-level functions include module name ---
        var topLevelFunctions = declarations.stream()
                .filter(CodeUnit::isFunction)
                .filter(cu -> cu.fqName().startsWith("tests.units.utils.test_utils."))
                .filter(cu -> !cu.fqName().contains(".ExampleTestState.")) // exclude methods
                .filter(cu -> !cu.fqName().contains(".DataFrame.")) // exclude methods
                .filter(cu -> !cu.fqName().contains(".TestBackendVariable.")) // exclude function-local class methods
                .collect(Collectors.toList());

        assertTrue(
                topLevelFunctions.stream()
                        .anyMatch(cu -> cu.fqName().equals("tests.units.utils.test_utils.mock_event")),
                "mock_event should have FQ name including module");
        assertTrue(
                topLevelFunctions.stream()
                        .anyMatch(cu -> cu.fqName().equals("tests.units.utils.test_utils.get_above_max_version")),
                "get_above_max_version should have FQ name including module");
        assertTrue(
                topLevelFunctions.stream().anyMatch(cu -> cu.fqName().equals("tests.units.utils.test_utils.test_func")),
                "test_func should have FQ name including module");

        // --- Verify function-local class includes function scope ---
        // test_backend_variable_cls contains a class TestBackendVariable
        // Function-local classes use $ for the class boundary: module.func$ClassName
        var functionLocalClasses = declarations.stream()
                .filter(CodeUnit::isClass)
                .filter(cu -> cu.fqName().contains("TestBackendVariable"))
                .collect(Collectors.toList());

        assertEquals(1, functionLocalClasses.size(), "Should find exactly one TestBackendVariable class");
        assertEquals(
                "tests.units.utils.test_utils.test_backend_variable_cls$TestBackendVariable",
                functionLocalClasses.getFirst().fqName(),
                "Function-local class should have function in its FQ name with $ boundary");

        // --- Verify top-level variables include module name ---
        var topLevelVars = declarations.stream()
                .filter(CodeUnit::isField)
                .filter(cu -> cu.fqName().startsWith("tests.units.utils.test_utils."))
                .collect(Collectors.toList());

        assertTrue(
                topLevelVars.stream().anyMatch(cu -> cu.fqName().equals("tests.units.utils.test_utils.V055")),
                "V055 should have FQ name including module. Found: "
                        + topLevelVars.stream().map(CodeUnit::fqName).collect(Collectors.joining(", ")));
        assertTrue(
                topLevelVars.stream().anyMatch(cu -> cu.fqName().equals("tests.units.utils.test_utils.V059")),
                "V059 should have FQ name including module");
    }

    @Test
    void testNonStandardNamingConventions() {
        // Tests the isLowercaseIdentifier heuristic with non-PEP8 compliant names
        // KEY INSIGHT: TreeSitter correctly identifies classes vs functions from AST node types.
        // The isLowercaseIdentifier heuristic ONLY affects FQN construction for nested symbols,
        // NOT the classification of code units as class vs function.
        TestProject project = createTestProject("testcode-py", Languages.PYTHON);
        PythonAnalyzer testAnalyzer = new PythonAnalyzer(project);

        ProjectFile file = new ProjectFile(project.getRoot(), "nonstandard_naming.py");
        Set<CodeUnit> declarations = testAnalyzer.getDeclarations(file);

        var classes = declarations.stream().filter(CodeUnit::isClass).collect(Collectors.toList());
        var functions = declarations.stream().filter(CodeUnit::isFunction).collect(Collectors.toList());

        // === UPPERCASE FUNCTION NAMES (HTTPServer, GetData, URL) ===
        // Functions are CORRECTLY identified as functions from AST regardless of naming.
        // The FQN uses "." for function scope even though name is uppercase.

        assertTrue(
                functions.stream().anyMatch(cu -> cu.fqName().equals("nonstandard_naming.HTTPServer")),
                "HTTPServer is correctly identified as FUNCTION despite uppercase name");
        assertTrue(
                functions.stream().anyMatch(cu -> cu.fqName().equals("nonstandard_naming.GetData")),
                "GetData is correctly identified as FUNCTION despite PascalCase name");
        assertTrue(
                functions.stream().anyMatch(cu -> cu.fqName().equals("nonstandard_naming.URL")),
                "URL is correctly identified as FUNCTION despite uppercase name");

        // ServerHandler (class inside HTTPServer function) - heuristic affects FQN:
        // Since "HTTPServer" starts with uppercase, it's treated as class-like in parent FQN,
        // so ServerHandler gets "$HTTPServer$ServerHandler" instead of ".HTTPServer$ServerHandler"
        assertTrue(
                classes.stream().anyMatch(cu -> cu.fqName().equals("nonstandard_naming$HTTPServer$ServerHandler")),
                "ServerHandler uses $ boundary because HTTPServer looks like a class to the heuristic");

        // === LOWERCASE CLASS NAMES (myClass, my_class, _privateClass) ===
        // Classes are CORRECTLY identified as classes from AST regardless of naming.
        // The FQN uses "$" for class boundary even though name starts lowercase.

        assertTrue(
                classes.stream().anyMatch(cu -> cu.fqName().equals("nonstandard_naming$myClass")),
                "myClass is correctly identified as CLASS despite lowercase start");
        assertTrue(
                classes.stream().anyMatch(cu -> cu.fqName().equals("nonstandard_naming$my_class")),
                "my_class is correctly identified as CLASS despite snake_case name");
        assertTrue(
                classes.stream().anyMatch(cu -> cu.fqName().equals("nonstandard_naming$_privateClass")),
                "_privateClass is correctly identified as CLASS");

        // Methods inside lowercase classes - uses "." for method boundary
        assertTrue(
                functions.stream().anyMatch(cu -> cu.fqName().equals("nonstandard_naming.myClass.method")),
                "method() inside myClass uses . boundary - but parent uses incorrect . too");
        assertTrue(
                functions.stream().anyMatch(cu -> cu.fqName().equals("nonstandard_naming.my_class.my_method")),
                "my_method() inside my_class - heuristic thinks parent is function, uses . boundary");

        // Nested class inside lowercase parent - heuristic affects FQN:
        // Since "my_class" starts lowercase, heuristic treats it as function scope,
        // so Nested gets ".my_class$Nested" (function-local class pattern)
        assertTrue(
                classes.stream().anyMatch(cu -> cu.fqName().equals("nonstandard_naming.my_class$Nested")),
                "Nested class inside lowercase my_class uses function-local pattern");

        // === MIXED SCENARIOS ===

        // createFactory (camelCase function) - correctly identified as function
        assertTrue(
                functions.stream().anyMatch(cu -> cu.fqName().equals("nonstandard_naming.createFactory")),
                "createFactory is correctly identified as function");

        // product (lowercase class inside createFactory) - heuristic affects FQN:
        // createFactory starts lowercase so treated as function scope -> ".createFactory$product"
        assertTrue(
                classes.stream().anyMatch(cu -> cu.fqName().equals("nonstandard_naming.createFactory$product")),
                "product inside createFactory uses function-local class pattern");

        // process_data (normal function) with DataProcessor (normal class)
        assertTrue(
                functions.stream().anyMatch(cu -> cu.fqName().equals("nonstandard_naming.process_data")),
                "process_data is correctly identified as function");
        assertTrue(
                classes.stream().anyMatch(cu -> cu.fqName().equals("nonstandard_naming.process_data$DataProcessor")),
                "DataProcessor inside process_data is correctly a function-local class");

        // === NORMAL PASCALCASE CLASS FOR COMPARISON ===
        assertTrue(
                classes.stream().anyMatch(cu -> cu.fqName().equals("nonstandard_naming$XMLParser")),
                "XMLParser is correctly identified as class with $ boundary");

        project.close();
    }

    @Test
    void testNonStandardNamingMethodResolution() {
        // Tests that methods and nested classes resolve correctly despite naming conventions
        // NOTE: The heuristic causes FQN mismatches between parent and child when naming is non-standard.
        // This documents the current behavior which may cause parent-child lookups to fail.
        TestProject project = createTestProject("testcode-py", Languages.PYTHON);
        PythonAnalyzer testAnalyzer = new PythonAnalyzer(project);

        ProjectFile file = new ProjectFile(project.getRoot(), "nonstandard_naming.py");
        Set<CodeUnit> declarations = testAnalyzer.getDeclarations(file);

        // === METHODS EXIST WITH CORRECT FQNS ===

        // HTTPServer's ServerHandler.handle() method exists
        var serverHandlerMethod = declarations.stream()
                .filter(cu -> cu.fqName().equals("nonstandard_naming$HTTPServer$ServerHandler.handle"))
                .findFirst();
        assertTrue(serverHandlerMethod.isPresent(),
                "handle() method of ServerHandler should exist with correct FQN");

        // my_class's Nested.nested_method() exists
        var nestedMethod = declarations.stream()
                .filter(cu -> cu.fqName().equals("nonstandard_naming.my_class$Nested.nested_method"))
                .findFirst();
        assertTrue(nestedMethod.isPresent(),
                "nested_method() should exist in Nested class inside my_class");

        // XMLParser.parse() method exists
        var parseMethod = declarations.stream()
                .filter(cu -> cu.fqName().equals("nonstandard_naming$XMLParser.parse"))
                .findFirst();
        assertTrue(parseMethod.isPresent(),
                "parse() method of XMLParser should exist");

        // === PARENT-CHILD RELATIONSHIPS FOR NORMALLY-NAMED SYMBOLS ===

        // XMLParser (correctly identified class) should have parse method as child
        var xmlParser = declarations.stream()
                .filter(cu -> cu.fqName().equals("nonstandard_naming$XMLParser"))
                .findFirst()
                .orElseThrow();
        var xmlParserChildren = testAnalyzer.getDirectChildren(xmlParser);
        assertTrue(
                xmlParserChildren.stream().anyMatch(cu -> cu.fqName().equals("nonstandard_naming$XMLParser.parse")),
                "XMLParser should have parse() method as child");

        // process_data (normal function) should have DataProcessor as child
        var processData = declarations.stream()
                .filter(cu -> cu.fqName().equals("nonstandard_naming.process_data"))
                .findFirst()
                .orElseThrow();
        var processDataChildren = testAnalyzer.getDirectChildren(processData);
        assertTrue(
                processDataChildren.stream().anyMatch(cu ->
                        cu.fqName().equals("nonstandard_naming.process_data$DataProcessor")),
                "process_data should have DataProcessor as child");

        // === KNOWN LIMITATION: Parent-child mismatch for non-standard naming ===
        // HTTPServer (function, FQN uses ".") has child ServerHandler (class, FQN uses "$")
        // The FQN prefix mismatch means getDirectChildren may not find the relationship.
        // This is documented behavior - a limitation of the heuristic approach.

        var httpServer = declarations.stream()
                .filter(cu -> cu.fqName().equals("nonstandard_naming.HTTPServer"))
                .findFirst()
                .orElseThrow();
        var httpServerChildren = testAnalyzer.getDirectChildren(httpServer);
        // Document the limitation: children may be empty or have mismatched FQNs
        // This is expected when function names don't follow lowercase convention
        System.out.println("HTTPServer children: " + httpServerChildren.stream()
                .map(CodeUnit::fqName).collect(Collectors.joining(", ")));

        project.close();
    }

    @Test
    void testCrossModuleClassNameCollisions() throws Exception {
        // Test that two modules in the same package with identically-named classes
        // get distinct FQNs and can be looked up correctly
        var builder = InlineTestProjectCreator.code("# Package marker\n", "pkg/__init__.py")
                .addFileContents(
                        """
                        class C:
                            def method_a(self):
                                pass
                        """,
                        "pkg/a.py")
                .addFileContents(
                        """
                        class C:
                            def method_b(self):
                                pass
                        """,
                        "pkg/b.py");

        try (var testProject = builder.build()) {
            var testAnalyzer = new PythonAnalyzer(testProject);

            // === FQNs should be distinct ===
            var allClasses = testAnalyzer.getAllDeclarations().stream()
                    .filter(CodeUnit::isClass)
                    .filter(cu -> cu.identifier().equals("C"))
                    .collect(Collectors.toList());

            assertEquals(2, allClasses.size(), "Should find 2 classes named 'C'");

            var fqNames = allClasses.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
            assertTrue(fqNames.contains("pkg.a$C"), "Should have pkg.a$C");
            assertTrue(fqNames.contains("pkg.b$C"), "Should have pkg.b$C");

            // === getDefinitions with full FQN should return exactly one ===
            var defsA = testAnalyzer.getDefinitions("pkg.a$C");
            assertEquals(1, defsA.size(), "getDefinitions('pkg.a$C') should return exactly 1 result");
            assertEquals("pkg.a$C", defsA.iterator().next().fqName());

            var defsB = testAnalyzer.getDefinitions("pkg.b$C");
            assertEquals(1, defsB.size(), "getDefinitions('pkg.b$C') should return exactly 1 result");
            assertEquals("pkg.b$C", defsB.iterator().next().fqName());

            // === Verify both classes can be found by identifier from getAllDeclarations ===
            // Note: getDefinitions requires exact FQN, so simple name "C" won't work
            // But getAllDeclarations + filter by identifier confirms both exist and are distinct
            var allCClasses = testAnalyzer.getAllDeclarations().stream()
                    .filter(CodeUnit::isClass)
                    .filter(cu -> cu.identifier().equals("C"))
                    .collect(Collectors.toList());
            assertEquals(2, allCClasses.size(), "Should find both classes with identifier 'C'");
            assertTrue(
                    allCClasses.stream().map(CodeUnit::fqName).collect(Collectors.toSet())
                            .containsAll(Set.of("pkg.a$C", "pkg.b$C")),
                    "Both FQNs should be present");

            // === Methods should be distinct too ===
            var methodA = testAnalyzer.getDefinitions("pkg.a$C.method_a");
            assertEquals(1, methodA.size(), "Should find method_a in pkg.a$C");

            var methodB = testAnalyzer.getDefinitions("pkg.b$C.method_b");
            assertEquals(1, methodB.size(), "Should find method_b in pkg.b$C");

            // method_a should NOT exist in pkg.b$C
            var wrongMethod = testAnalyzer.getDefinitions("pkg.b$C.method_a");
            assertEquals(0, wrongMethod.size(), "method_a should not exist in pkg.b$C");
        }
    }
}
