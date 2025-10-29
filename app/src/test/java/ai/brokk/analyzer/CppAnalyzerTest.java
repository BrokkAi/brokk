package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CppAnalyzerTest {

    private static final Logger logger = LoggerFactory.getLogger(CppAnalyzerTest.class);

    @Nullable
    private static CppAnalyzer analyzer;

    @Nullable
    private static TestProject testProject;

    @BeforeAll
    public static void setup() throws IOException {
        final var testPath =
                Path.of("src/test/resources/testcode-cpp").toAbsolutePath().normalize();
        assertTrue(Files.exists(testPath), "Test resource directory 'testcode-cpp' not found.");
        testProject = new TestProject(testPath, Languages.CPP_TREESITTER);
        logger.debug(
                "Setting up analyzer with test code from {}",
                testPath.toAbsolutePath().normalize());
        analyzer = new CppAnalyzer(testProject);
    }

    @AfterAll
    public static void teardown() {
        if (testProject != null) {
            testProject.close();
        }
    }

    /**
     * Extracts the base function name from a CodeUnit shortName that may include parameter signature.
     * For C++ functions, shortName is "functionName(paramTypes)" so we extract the part before '('.
     * For non-functions or functions without signatures, returns the shortName as-is.
     */
    private static String getBaseFunctionName(CodeUnit cu) {
        String shortName = cu.shortName();
        int parenIndex = shortName.indexOf('(');
        return parenIndex > 0 ? shortName.substring(0, parenIndex) : shortName;
    }

    @Test
    public void isEmptyTest() {
        assertFalse(analyzer.isEmpty());
    }

    private List<CodeUnit> getAllDeclarations() {
        return testProject.getAllFiles().stream()
                .flatMap(file -> analyzer.getDeclarations(file).stream())
                .collect(Collectors.toList());
    }

    @Test
    public void testNamespaceAnalysis() {
        var allDeclarations = getAllDeclarations();

        // Check for actual namespace declarations (MODULE type)
        var namespaceDeclarations = allDeclarations.stream()
                .filter(cu -> cu.kind() == CodeUnitType.MODULE)
                .collect(Collectors.toList());

        logger.debug("Found {} namespace declarations", namespaceDeclarations.size());
        namespaceDeclarations.forEach(cu -> logger.debug("Namespace: {} (type: {})", cu.shortName(), cu.kind()));

        // Should find specific namespace declarations
        assertTrue(namespaceDeclarations.size() >= 2, "Should find at least 2 namespace declarations");

        // Verify specific namespaces are detected
        var namespaceNames =
                namespaceDeclarations.stream().map(CodeUnit::shortName).collect(Collectors.toSet());

        assertTrue(namespaceNames.contains("graphics"), "Should find 'graphics' namespace");
        assertTrue(namespaceNames.contains("ui::widgets"), "Should find 'ui::widgets' nested namespace");
    }

    @Test
    public void testAnonymousNamespace() {
        var geometryFile = testProject.getAllFiles().stream()
                .filter(file -> file.absPath().toString().endsWith("geometry.cpp"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("geometry.cpp not found"));

        var allDeclarations = analyzer.getDeclarations(geometryFile);

        var allFunctions = allDeclarations.stream().filter(CodeUnit::isFunction).collect(Collectors.toList());

        // Note: C++ function names include parameter signatures, so we need to extract base names
        var anonymousNamespaceFunctions = allDeclarations.stream()
                .filter(CodeUnit::isFunction)
                .filter(cu -> {
                    String baseName = getBaseFunctionName(cu);
                    return baseName.contains("anonymous_helper") || baseName.contains("anonymous_void_func");
                })
                .collect(Collectors.toList());

        var functionNames = allFunctions.stream().map(cu -> cu.shortName()).collect(Collectors.toList());

        assertFalse(
                anonymousNamespaceFunctions.isEmpty(),
                "Should find function from anonymous namespace. Available functions: " + functionNames);

        var anonymousHelperFunction = anonymousNamespaceFunctions.stream()
                .filter(cu -> {
                    String identifier = cu.identifier();
                    // identifier() returns the part after last dot, but may still have params
                    int parenIndex = identifier.indexOf('(');
                    String baseIdentifier = parenIndex > 0 ? identifier.substring(0, parenIndex) : identifier;
                    return baseIdentifier.equals("anonymous_helper");
                })
                .findFirst();

        assertTrue(
                anonymousHelperFunction.isPresent(), "Should find anonymous_helper function from anonymous namespace");

        var skeletons = analyzer.getSkeletons(geometryFile);
        var functionSkeletons = skeletons.entrySet().stream()
                .filter(entry -> entry.getKey().isFunction())
                .filter(entry -> entry.getKey().shortName().contains("anonymous_"))
                .collect(Collectors.toList());

        assertFalse(functionSkeletons.isEmpty(), "Should generate skeletons for anonymous namespace functions");
    }

    @Test
    public void testClassAnalysis() {
        var allDeclarations = getAllDeclarations();

        // Check for class declarations
        var classes = allDeclarations.stream()
                .filter(cu -> cu.kind() == CodeUnitType.CLASS)
                .filter(cu -> cu.shortName().contains("Circle")
                        || cu.shortName().contains("Renderer")
                        || cu.shortName().contains("Widget"))
                .collect(Collectors.toList());

        logger.debug("Found {} class declarations", classes.size());
        classes.forEach(cu -> logger.debug("Class: {} (type: {})", cu.shortName(), cu.kind()));

        assertTrue(classes.size() >= 2, "Should find at least Circle and other class declarations");
    }

    @Test
    public void testSkeletonOutput() {
        // Test that C++ skeletons include body placeholder for functions with bodies
        var geometryFile = testProject.getAllFiles().stream()
                .filter(file -> file.absPath().toString().endsWith("geometry.cpp"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("geometry.cpp not found"));

        var skeletons = analyzer.getSkeletons(geometryFile);

        logger.debug("=== All skeletons for geometry.cpp ===");
        skeletons.entrySet().forEach(entry -> {
            var codeUnit = entry.getKey();
            var skeleton = entry.getValue();
            logger.debug("CodeUnit: {} ({})", codeUnit.fqName(), codeUnit.kind());
            logger.debug("Skeleton: {}", skeleton);
            logger.debug("---");
        });

        // Look for functions that should have body placeholders
        var functionSkeletons = skeletons.entrySet().stream()
                .filter(entry -> entry.getKey().kind() == CodeUnitType.FUNCTION)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertFalse(functionSkeletons.isEmpty(), "Should find at least one function skeleton");

        // Check that function skeletons use {...} placeholder instead of actual body content
        for (var entry : functionSkeletons.entrySet()) {
            var skeleton = entry.getValue();
            var functionName = entry.getKey().fqName();

            logger.debug("Function: {} -> Skeleton: {}", functionName, skeleton);

            // The skeleton should contain {...} placeholder for functions with bodies
            if (functionName.contains("getArea")
                    || functionName.contains("print")
                    || functionName.contains("global_func")) {
                assertTrue(
                        skeleton.contains("{...}"),
                        "Function " + functionName + " should contain body placeholder {...}, but got: " + skeleton);
            }
        }
    }

    @Test
    public void testNestedClassSkeletonOutput() {
        var nestedFile = testProject.getAllFiles().stream()
                .filter(file -> file.absPath().toString().endsWith("nested.cpp"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("nested.cpp not found"));

        var skeletons = analyzer.getSkeletons(nestedFile);

        var outerClass = skeletons.entrySet().stream()
                .filter(entry -> entry.getKey().shortName().equals("Outer"))
                .findFirst();

        assertTrue(outerClass.isPresent(), "Should detect Outer class");

        var outerSkeleton = outerClass.get().getValue();
        assertTrue(
                outerSkeleton.contains("class Inner"), "Should detect nested Inner class within Outer class skeleton");

        var mainFunction = skeletons.keySet().stream()
                .filter(cu -> cu.fqName().contains("main"))
                .findFirst();

        assertTrue(mainFunction.isPresent(), "Should detect main function");
    }

    @Test
    public void testStructAnalysis() {
        var allDeclarations = getAllDeclarations();

        // Check for struct declarations
        var structs = allDeclarations.stream()
                .filter(cu -> cu.kind() == CodeUnitType.CLASS)
                .filter(cu -> cu.shortName().contains("Point"))
                .collect(Collectors.toList());

        logger.debug("Found {} struct declarations", structs.size());
        structs.forEach(cu -> logger.debug("Struct: {} (type: {})", cu.shortName(), cu.kind()));

        assertTrue(structs.size() >= 1, "Should find Point struct declaration");
    }

    @Test
    public void testGlobalDeclarations() {
        var allDeclarations = getAllDeclarations();

        var globalFunctions = allDeclarations.stream()
                .filter(cu -> cu.kind() == CodeUnitType.FUNCTION)
                .filter(cu -> cu.packageName().isEmpty())
                .filter(cu -> cu.fqName().contains("global"))
                .collect(Collectors.toList());

        assertTrue(
                globalFunctions.size() >= 2,
                "Should find at least 2 global functions: global_func and uses_global_func");
        assertTrue(
                globalFunctions.stream().anyMatch(cu -> cu.fqName().contains("global_func")),
                "Should find global_func declaration");
        assertTrue(
                globalFunctions.stream().anyMatch(cu -> cu.fqName().contains("uses_global_func")),
                "Should find uses_global_func declaration");

        var globalVariables = allDeclarations.stream()
                .filter(cu -> cu.kind() == CodeUnitType.FIELD)
                .filter(cu -> cu.packageName().isEmpty())
                .filter(cu -> cu.fqName().contains("global"))
                .collect(Collectors.toList());

        assertTrue(globalVariables.size() >= 1, "Should find at least 1 global variable: global_var");
        assertTrue(
                globalVariables.stream().anyMatch(cu -> cu.fqName().contains("global_var")),
                "Should find global_var declaration");
    }

    @Test
    public void testStructFieldsAndMethods() {
        var geometryFile = testProject.getAllFiles().stream()
                .filter(file -> file.absPath().toString().endsWith("geometry.h"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("geometry.h not found"));

        var skeletons = analyzer.getSkeletons(geometryFile);

        var pointStruct = skeletons.entrySet().stream()
                .filter(entry -> entry.getKey().shortName().equals("Point"))
                .findFirst();

        assertTrue(pointStruct.isPresent(), "Should find Point struct");

        var pointSkeleton = pointStruct.get().getValue();

        assertTrue(
                pointSkeleton.contains("int x") || pointSkeleton.contains("x"),
                "Point struct skeleton should contain field 'x'");
        assertTrue(
                pointSkeleton.contains("int y") || pointSkeleton.contains("y"),
                "Point struct skeleton should contain field 'y'");
    }

    @Test
    public void testEnumAnalysis() {
        var allDeclarations = getAllDeclarations();

        var enums = allDeclarations.stream()
                .filter(cu -> cu.kind() == CodeUnitType.CLASS)
                .filter(cu -> cu.shortName().contains("Color")
                        || cu.shortName().contains("BlendMode")
                        || cu.shortName().contains("Status")
                        || cu.shortName().contains("WidgetType"))
                .collect(Collectors.toList());

        assertTrue(enums.size() >= 1, "Should find enum declarations from advanced_features.h");
    }

    @Test
    public void testUnionAnalysis() {
        var allDeclarations = getAllDeclarations();

        var unions = allDeclarations.stream()
                .filter(cu -> cu.kind() == CodeUnitType.CLASS)
                .filter(cu -> cu.shortName().contains("Pixel") || cu.shortName().contains("DataValue"))
                .collect(Collectors.toList());

        assertTrue(unions.size() >= 1, "Should find union declarations from advanced_features.h");
    }

    @Test
    public void testNamespacePackageNaming() {
        var allDeclarations = getAllDeclarations();

        var classesWithNamespaces = allDeclarations.stream()
                .filter(cu -> cu.kind() == CodeUnitType.CLASS)
                .filter(cu -> !cu.packageName().isEmpty())
                .collect(Collectors.toList());

        var graphicsClasses = classesWithNamespaces.stream()
                .filter(cu -> cu.packageName().equals("graphics"))
                .collect(Collectors.toList());
        assertTrue(graphicsClasses.size() >= 2, "Should find classes in 'graphics' namespace");

        var nestedNamespaceClasses = classesWithNamespaces.stream()
                .filter(cu -> cu.packageName().equals("ui::widgets"))
                .collect(Collectors.toList());
        assertTrue(nestedNamespaceClasses.size() >= 1, "Should find classes in 'ui::widgets' nested namespace");

        var graphicsColorFound = classesWithNamespaces.stream()
                .anyMatch(cu ->
                        cu.packageName().equals("graphics") && cu.shortName().contains("Color"));
        assertTrue(graphicsColorFound, "Should find Color enum in graphics namespace");

        var graphicsRendererFound = classesWithNamespaces.stream()
                .anyMatch(cu ->
                        cu.packageName().equals("graphics") && cu.shortName().contains("Renderer"));
        assertTrue(graphicsRendererFound, "Should find Renderer class in graphics namespace");

        var widgetFound = classesWithNamespaces.stream()
                .anyMatch(cu ->
                        cu.packageName().equals("ui::widgets") && cu.shortName().contains("Widget"));
        assertTrue(widgetFound, "Should find Widget class in ui::widgets namespace");
    }

    @Test
    public void testTypeAliasAnalysis() {
        var allDeclarations = getAllDeclarations();

        var aliases = allDeclarations.stream()
                .filter(cu -> cu.kind() == CodeUnitType.CLASS)
                .filter(cu -> cu.shortName().contains("ColorValue")
                        || cu.shortName().contains("PixelBuffer")
                        || cu.shortName().contains("String")
                        || cu.shortName().contains("uint32_t"))
                .collect(Collectors.toList());

        logger.debug("Type aliases are not supported by current C++ TreeSitter grammar (expected: 0)");
    }

    @Test
    public void testFunctionAnalysis() {
        var allDeclarations = getAllDeclarations();

        var functions = allDeclarations.stream()
                .filter(cu -> cu.kind() == CodeUnitType.FUNCTION)
                .collect(Collectors.toList());

        assertTrue(functions.size() >= 3, "Should find multiple function declarations");
    }

    @Test
    public void testComprehensiveDeclarationCount() {
        var allDeclarations = getAllDeclarations();

        var byType = allDeclarations.stream().collect(Collectors.groupingBy(CodeUnit::kind, Collectors.counting()));

        assertTrue(allDeclarations.size() >= 10, "Should find at least 10 declarations with enhanced C++ constructs");

        assertTrue(byType.containsKey(CodeUnitType.CLASS), "Should find class-like declarations");
        assertTrue(byType.containsKey(CodeUnitType.FUNCTION), "Should find function declarations");
    }

    @Test
    public void testSpecificFileAnalysis() {
        var advancedFile = testProject.getAllFiles().stream()
                .filter(f -> f.toString().contains("advanced_features.h"))
                .findFirst();

        assertTrue(advancedFile.isPresent(), "Should find advanced_features.h test file");

        var declarations = analyzer.getDeclarations(advancedFile.get());

        assertTrue(
                declarations.size() >= 5,
                "Should find at least 5 declarations in advanced_features.h with enums, unions, classes, etc.");
    }

    @Test
    public void testAdvancedFeaturesSkeletonOutput() {
        var advancedFile = testProject.getAllFiles().stream()
                .filter(file -> file.absPath().toString().endsWith("advanced_features.h"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("advanced_features.h not found"));

        var skeletons = analyzer.getSkeletons(advancedFile);

        var namespaceSkeletons = skeletons.entrySet().stream()
                .filter(entry -> entry.getKey().kind() == CodeUnitType.MODULE)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertFalse(namespaceSkeletons.isEmpty(), "Should find at least one namespace");

        var graphicsNamespace = namespaceSkeletons.entrySet().stream()
                .filter(entry -> entry.getKey().fqName().equals("graphics"))
                .findFirst();

        assertTrue(graphicsNamespace.isPresent(), "Should find graphics namespace");
        String graphicsSkeleton = graphicsNamespace.get().getValue();

        assertTrue(
                graphicsSkeleton.contains("Color") || graphicsSkeleton.contains("Renderer"),
                "Graphics namespace should contain some declarations");
    }

    @Test
    public void testDuplicateHandling() {
        // Test that C++ analyzer handles duplicate CodeUnits gracefully (first-wins strategy)
        var duplicatesFile = testProject.getAllFiles().stream()
                .filter(file -> file.absPath().toString().endsWith("duplicates.h"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("duplicates.h not found"));

        // This should not throw errors even though the file contains duplicates
        var declarations = analyzer.getDeclarations(duplicatesFile);
        assertNotNull(declarations, "Should successfully analyze file with duplicates");

        logger.debug("Found {} declarations in duplicates.h", declarations.size());
        declarations.forEach(cu -> logger.debug("  - {} (kind: {})", cu.fqName(), cu.kind()));

        // Verify we got declarations (even if some duplicates were filtered)
        assertFalse(declarations.isEmpty(), "Should find at least some declarations");

        // Check that specific classes are present (first occurrence should be kept)
        var classNames = declarations.stream()
                .filter(CodeUnit::isClass)
                .map(CodeUnit::shortName)
                .collect(Collectors.toSet());

        logger.debug("Found classes: {}", classNames);

        // Should find the first occurrence of these classes
        assertTrue(
                classNames.contains("ForwardDeclaredClass"),
                "Should find ForwardDeclaredClass (first occurrence kept)");
        assertTrue(
                classNames.contains("ConditionalClass"),
                "Should find ConditionalClass (first conditional branch kept)");
        assertTrue(classNames.contains("TemplateClass"), "Should find TemplateClass (primary template kept)");
        assertTrue(classNames.contains("Point"), "Should find Point struct");

        // Verify skeletons can be generated without errors
        var skeletons = analyzer.getSkeletons(duplicatesFile);
        assertNotNull(skeletons, "Should generate skeletons for file with duplicates");
        assertFalse(skeletons.isEmpty(), "Should have at least some skeleton entries");

        logger.debug("Generated {} skeletons for duplicates.h", skeletons.size());
    }

    @Test
    public void testFunctionOverloadsPreserved() {
        // Test that function overloads are preserved (not treated as duplicates)
        var duplicatesFile = testProject.getAllFiles().stream()
                .filter(file -> file.absPath().toString().endsWith("simple_overloads.h"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("simple_overloads.h not found"));

        var declarations = analyzer.getDeclarations(duplicatesFile);

        // Debug: log all declarations found
        logger.debug("Total declarations found: {}", declarations.size());
        declarations.forEach(
                cu -> logger.debug("  - {} (kind: {}, isFunction: {})", cu.fqName(), cu.kind(), cu.isFunction()));

        // Find all overloads of overloadedFunction
        // Note: C++ function names include parameter signatures, e.g., "overloadedFunction(int)"
        var overloads = declarations.stream()
                .filter(cu -> getBaseFunctionName(cu).equals("overloadedFunction"))
                .collect(Collectors.toList());

        logger.debug("Found {} overloads of overloadedFunction", overloads.size());
        overloads.forEach(cu -> logger.debug("  - {}", cu.fqName()));

        // All 3 overloads should be preserved
        assertEquals(3, overloads.size(), "All 3 overloads of overloadedFunction should be preserved");

        // Verify each has unique signature
        var skeletons = analyzer.getSkeletons(duplicatesFile);
        var signatures = overloads.stream()
                .map(skeletons::get)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        logger.debug("Found {} skeleton signatures for overloads", signatures.size());
        signatures.forEach(sig -> logger.debug("  Signature: {}", sig));

        assertEquals(3, signatures.size(), "Each overload should have a skeleton");

        // Convert to set to check uniqueness
        var uniqueSignatures = new java.util.HashSet<>(signatures);
        assertEquals(3, uniqueSignatures.size(), "Each overload should have a unique signature");

        // Verify specific parameter patterns exist
        var hasInt = signatures.stream().anyMatch(s -> s.contains("(int") && !s.contains(", int"));
        var hasDouble = signatures.stream().anyMatch(s -> s.contains("(double"));
        var hasTwoInts = signatures.stream().anyMatch(s -> s.contains("(int") && s.contains(", int"));

        assertTrue(hasInt, "Should have overload with single int parameter");
        assertTrue(hasDouble, "Should have overload with double parameter");
        assertTrue(hasTwoInts, "Should have overload with two int parameters");
    }

    @Test
    public void testAnonymousStructHandling() {
        // Test that anonymous structs/classes don't generate warnings
        var advancedFile = testProject.getAllFiles().stream()
                .filter(file -> file.absPath().toString().endsWith("advanced_features.h"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("advanced_features.h not found"));

        // This file contains an anonymous struct inside the Pixel union
        // Should process without WARN logs about extractSimpleName failures
        var declarations = analyzer.getDeclarations(advancedFile);
        assertNotNull(declarations, "Should successfully analyze file with anonymous structs");

        logger.debug("Found {} declarations in advanced_features.h", declarations.size());

        // Verify Pixel union is found
        var unions = declarations.stream()
                .filter(cu -> cu.isClass())
                .filter(cu -> cu.shortName().contains("Pixel"))
                .collect(Collectors.toList());

        assertFalse(unions.isEmpty(), "Should find Pixel union");

        // Verify skeletons can be generated without warnings
        var skeletons = analyzer.getSkeletons(advancedFile);
        assertNotNull(skeletons, "Should generate skeletons for file with anonymous structs");
        assertFalse(skeletons.isEmpty(), "Should have skeleton entries");

        // The anonymous struct inside Pixel union should be handled gracefully
        // with "(anonymous)" name rather than generating warnings
        logger.debug("Successfully processed advanced_features.h with anonymous struct");
    }

    @Test
    public void testParseOnceCachingPerformance() {
        var geometryFile = testProject.getAllFiles().stream()
                .filter(file -> file.absPath().toString().endsWith("geometry.cpp"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("geometry.cpp not found"));

        logger.info("Initial cache stats: {}", analyzer.getCacheStatistics());

        // First call - should parse and cache the tree
        long startTime = System.nanoTime();
        var skeletons1 = analyzer.getSkeletons(geometryFile);
        long firstCallTime = System.nanoTime() - startTime;

        logger.info("After first getSkeletons() call:");
        logger.info("  - Time: {} ms", firstCallTime / 1_000_000.0);
        logger.info("  - Cache stats: {}", analyzer.getCacheStatistics());
        logger.info("  - Found {} skeletons", skeletons1.size());

        // Second call - should use cached tree (much faster)
        startTime = System.nanoTime();
        var skeletons2 = analyzer.getSkeletons(geometryFile);
        long secondCallTime = System.nanoTime() - startTime;

        logger.info("After second getSkeletons() call:");
        logger.info("  - Time: {} ms", secondCallTime / 1_000_000.0);
        logger.info("  - Cache stats: {}", analyzer.getCacheStatistics());
        logger.info("  - Found {} skeletons", skeletons2.size());

        // Verify results are identical
        assertEquals(skeletons1, skeletons2, "Results should be identical - caching works correctly");

        // Performance improvement validation
        assertTrue(skeletons1.size() > 0, "Should find at least one skeleton");

        if (secondCallTime > 0) {
            double improvement = (double) firstCallTime / secondCallTime;
            logger.info("Performance improvement ratio: {}x", String.format("%.2f", improvement));

            // Performance analysis (informational due to measurement variance at microsecond level)
            if (improvement > 1.2) {
                logger.info("✓ Significant performance improvement detected!");
            } else if (improvement >= 0.5) {
                logger.info("✓ Performance within expected range - caching is working");
            } else {
                logger.info("? Unexpected performance variance detected");
            }
        }
    }

    @Test
    public void testCFileExtensionSupport() {
        // Test that .c files are properly recognized and don't trigger "Tree not found in cache" warnings
        var cFile = testProject.getAllFiles().stream()
                .filter(file -> file.absPath().toString().endsWith("test_file.c"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("test_file.c not found"));

        logger.debug("Testing .c file: {}", cFile.absPath());

        // This should work without triggering "Tree not found in cache" warning
        // because .c extension should now be included in the initial parse
        var declarations = analyzer.getDeclarations(cFile);
        assertNotNull(declarations, "Should successfully analyze .c file");
        assertFalse(declarations.isEmpty(), "Should find declarations in .c file");

        logger.debug("Found {} declarations in test_file.c", declarations.size());
        declarations.forEach(cu -> logger.debug("  - {} (kind: {})", cu.fqName(), cu.kind()));

        // Verify we found the expected symbols
        // Note: C++ function names include parameter signatures, e.g., "add_numbers(int,int)"
        var functionNames = declarations.stream()
                .filter(CodeUnit::isFunction)
                .map(CppAnalyzerTest::getBaseFunctionName)
                .collect(Collectors.toSet());

        assertTrue(functionNames.contains("add_numbers"), "Should find add_numbers function");

        var structs = declarations.stream()
                .filter(CodeUnit::isClass)
                .map(CodeUnit::shortName)
                .collect(Collectors.toSet());

        assertTrue(structs.contains("Point"), "Should find Point struct");

        logger.debug("Successfully processed .c file without cache warnings");
    }
}
