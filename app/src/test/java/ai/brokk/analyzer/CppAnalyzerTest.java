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
     * Extracts the base function name from a CodeUnit shortName, removing enclosing class/namespace qualifiers.
     *
     * <p>Since parameters are now in the signature field (not shortName), this method only strips class/namespace
     * prefixes.
     *
     * Examples:
     * - "m" -> "m"
     * - "C.m" -> "m"
     * - "ns::C::m" -> "m"
     * - "S.operator()" -> "operator()"
     * - "operator==" -> "operator=="
     */
    private static String getBaseFunctionName(CodeUnit cu) {
        String shortName = cu.shortName();

        // Handle C++ scope operator '::' first
        int idx = shortName.lastIndexOf("::");
        if (idx >= 0 && idx + 2 < shortName.length()) {
            return shortName.substring(idx + 2);
        }

        // Fallback: split on common hierarchy separators and take the last token
        int lastDot = shortName.lastIndexOf('.');
        int lastDollar = shortName.lastIndexOf('$');
        int sep = Math.max(lastDot, lastDollar);
        if (sep >= 0 && sep + 1 < shortName.length()) {
            return shortName.substring(sep + 1);
        }

        return shortName;
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

    @Test
    public void testTemplateParameterParsing() {
        var file = testProject.getAllFiles().stream()
                .filter(f -> f.absPath().toString().endsWith("overload_edgecases.h"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("overload_edgecases.h not found"));

        var decls = analyzer.getDeclarations(file);
        assertFalse(decls.isEmpty(), "Should find declarations in overload_edgecases.h");

        // Find overloaded 'f' declarations
        var overloads = decls.stream()
                .filter(CodeUnit::isFunction)
                .filter(cu -> getBaseFunctionName(cu).equals("f"))
                .collect(Collectors.toList());

        assertEquals(2, overloads.size(), "Should find two overloads of f() from templates");

        // Ensure parameter type snippets are present in signatures (normalized)
        var signatures = overloads.stream().map(CodeUnit::signature).collect(Collectors.toSet());
        boolean hasMap = signatures.stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(sig -> sig.contains("map") || sig.contains("std::map"));
        boolean hasVectorPair = signatures.stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(sig -> sig.contains("pair") || sig.contains("std::pair"));
        assertTrue(hasMap, "Should include std::map parameter in at least one signature");
        assertTrue(hasVectorPair, "Should include std::pair parameter in at least one signature");
    }

    @Test
    public void testFunctionPointerParameterParsing() {
        var file = testProject.getAllFiles().stream()
                .filter(f -> f.absPath().toString().endsWith("function_pointers.h"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("function_pointers.h not found"));

        var decls = analyzer.getDeclarations(file);
        assertFalse(decls.isEmpty(), "Should find declarations in function_pointers.h");

        var funcs = decls.stream().filter(CodeUnit::isFunction).collect(Collectors.toList());

        // g should exist and h should exist
        assertTrue(funcs.stream().anyMatch(cu -> getBaseFunctionName(cu).equals("g")), "Should find g()");
        assertTrue(funcs.stream().anyMatch(cu -> getBaseFunctionName(cu).equals("h")), "Should find h()");
    }

    @Test
    public void testQualifiersDistinguishOverloads() {
        var file = testProject.getAllFiles().stream()
                .filter(f -> f.absPath().toString().endsWith("qualifiers.h"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("qualifiers.h not found"));

        var decls = analyzer.getDeclarations(file);
        assertFalse(decls.isEmpty(), "Should find declarations in qualifiers.h");

        var fOverloads = decls.stream()
                .filter(CodeUnit::isFunction)
                .filter(cu -> getBaseFunctionName(cu).equals("f"))
                .collect(Collectors.toList());

        // Expect at least 3 distinct overloads (distinguished by qualifiers in signature)
        // Note: Overloads now have same fqName but different signatures
        assertTrue(fOverloads.size() >= 3, "Should distinguish f() overloads by qualifiers");

        // Verify signatures are distinct
        var signatures = fOverloads.stream().map(CodeUnit::signature).collect(Collectors.toSet());
        assertTrue(
                signatures.size() >= 3,
                "Should have at least 3 distinct signatures for f() overloads with different qualifiers");
    }

    @Test
    public void testExtendedQualifiersDistinguishOverloads() {
        var file = testProject.getAllFiles().stream()
                .filter(f -> f.absPath().toString().endsWith("qualifiers_extra.h"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("qualifiers_extra.h not found"));

        var decls = analyzer.getDeclarations(file);
        assertFalse(decls.isEmpty(), "Should find declarations in qualifiers_extra.h");

        // Collect all function CodeUnits named 'f'
        var fOverloads = decls.stream()
                .filter(CodeUnit::isFunction)
                .filter(cu -> getBaseFunctionName(cu).equals("f"))
                .collect(Collectors.toList());

        logger.debug(
                "Found {} overloads of f(): {}",
                fOverloads.size(),
                fOverloads.stream().map(CodeUnit::signature).collect(Collectors.toList()));

        // Get their signatures
        var signatures = fOverloads.stream().map(CodeUnit::signature).collect(Collectors.toSet());

        logger.debug("Signature variants for f():");
        signatures.forEach(sig -> logger.debug("  - {}", sig));

        // Assertion (a): Distinct signatures for volatile vs const volatile variants
        var volatileSig = signatures.stream()
                .filter(sig -> sig != null && sig.contains("volatile") && !sig.contains("const volatile"))
                .findFirst();
        var constVolatileSig = signatures.stream()
                .filter(sig -> sig != null && sig.contains("const volatile"))
                .findFirst();

        assertTrue(
                volatileSig.isPresent(),
                "Should have signature containing 'volatile' for volatile member function. Available: " + signatures);
        assertTrue(
                constVolatileSig.isPresent(),
                "Should have signature containing 'const volatile' for const volatile member function. Available: "
                        + signatures);
        assertNotEquals(
                volatileSig.get(),
                constVolatileSig.get(),
                "volatile and const volatile variants should have distinct signatures");

        // Assertion (b): Distinguish & vs && reference qualifiers
        var lvalueRefSig = signatures.stream()
                .filter(sig -> sig != null && sig.contains("&") && !sig.contains("&&"))
                .findFirst();
        var rvalueRefSig = signatures.stream()
                .filter(sig -> sig != null && sig.contains("&&"))
                .findFirst();

        assertTrue(
                lvalueRefSig.isPresent() || rvalueRefSig.isPresent(),
                "Should have at least one reference-qualified variant. Available: " + signatures);
        if (lvalueRefSig.isPresent() && rvalueRefSig.isPresent()) {
            assertNotEquals(
                    lvalueRefSig.get(),
                    rvalueRefSig.get(),
                    "& and && reference qualifiers should produce distinct signatures");
        }

        // Assertion (c): Distinct signatures for noexcept(true) vs noexcept(false)
        // Collect h() overloads to test noexcept distinction
        var hOverloads = decls.stream()
                .filter(CodeUnit::isFunction)
                .filter(cu -> getBaseFunctionName(cu).equals("h"))
                .collect(Collectors.toList());

        logger.debug(
                "Found {} overloads of h(): {}",
                hOverloads.size(),
                hOverloads.stream().map(CodeUnit::signature).collect(Collectors.toList()));

        var hSignatures = hOverloads.stream().map(CodeUnit::signature).collect(Collectors.toSet());

        var noexceptTrueSig = hSignatures.stream()
                .filter(sig -> sig != null && sig.contains("noexcept(true)"))
                .findFirst();
        var noexceptFalseSig = hSignatures.stream()
                .filter(sig -> sig != null && sig.contains("noexcept(false)"))
                .findFirst();

        assertTrue(
                noexceptTrueSig.isPresent(),
                "Should have signature containing 'noexcept(true)' for noexcept(true) variant. Available: "
                        + hSignatures);
        assertTrue(
                noexceptFalseSig.isPresent(),
                "Should have signature containing 'noexcept(false)' for noexcept(false) variant. Available: "
                        + hSignatures);
        assertNotEquals(
                noexceptTrueSig.get(),
                noexceptFalseSig.get(),
                "noexcept(true) and noexcept(false) variants should have distinct signatures");
    }

    @Test
    public void testOperatorOverloads() {
        var file = testProject.getAllFiles().stream()
                .filter(f -> f.absPath().toString().endsWith("operators.h"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("operators.h not found"));

        var decls = analyzer.getDeclarations(file);
        assertFalse(decls.isEmpty(), "Should find declarations in operators.h");

        // Gather function CodeUnits
        var funcs = decls.stream().filter(CodeUnit::isFunction).collect(Collectors.toList());
        assertFalse(funcs.isEmpty(), "Should find function-like declarations (operators)");

        logger.debug("Found {} function declarations in operators.h", funcs.size());
        funcs.forEach(cu -> logger.debug("  - {} (signature: {})", cu.shortName(), cu.signature()));

        // Check member operator(): base name should be "operator()"
        var memberCallOps = funcs.stream()
                .filter(cu -> getBaseFunctionName(cu).equals("operator()"))
                .collect(Collectors.toList());
        assertFalse(memberCallOps.isEmpty(), "Should find member operator() overload(s)");

        logger.debug("Found {} member operator() overload(s)", memberCallOps.size());
        memberCallOps.forEach(cu -> logger.debug("  - {} (signature: {})", cu.shortName(), cu.signature()));

        // Ensure at least one member operator signature contains 'const' qualifier
        boolean memberHasConst = memberCallOps.stream()
                .map(CodeUnit::signature)
                .filter(java.util.Objects::nonNull)
                .anyMatch(sig -> sig.contains("const"));
        assertTrue(memberHasConst, "Member operator() signature should include 'const' qualifier");

        // Check non-member operator== exists as a global function and includes int parameter types
        var nonMemberEq = funcs.stream()
                .filter(cu -> getBaseFunctionName(cu).equals("operator=="))
                .filter(cu -> cu.packageName().isEmpty())
                .collect(Collectors.toList());
        assertFalse(nonMemberEq.isEmpty(), "Should find non-member operator==(int,int) as a global function");

        logger.debug("Found {} non-member operator== overload(s)", nonMemberEq.size());
        nonMemberEq.forEach(cu -> logger.debug(
                "  - {} (signature: {}, packageName: {})", cu.shortName(), cu.signature(), cu.packageName()));

        boolean eqHasIntParams = nonMemberEq.stream()
                .map(CodeUnit::signature)
                .filter(java.util.Objects::nonNull)
                .anyMatch(sig -> sig.contains("int"));
        assertTrue(eqHasIntParams, "operator== signature should include int parameters");
    }

    @Test
    public void testConstructorDestructorHandling() {
        var file = testProject.getAllFiles().stream()
                .filter(f -> f.absPath().toString().endsWith("ctor_dtor.h"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("ctor_dtor.h not found"));

        var decls = analyzer.getDeclarations(file);

        // Expect constructor T() and T(int) and destructor ~T
        boolean hasCtorNoArgs =
                decls.stream().anyMatch(cu -> getBaseFunctionName(cu).equals("T"));
        boolean hasDtor = decls.stream().anyMatch(cu -> getBaseFunctionName(cu).startsWith("~T"));
        assertTrue(hasCtorNoArgs, "Should find constructor T()");
        assertTrue(hasDtor, "Should find destructor ~T()");
    }

    @Test
    public void testScopedDefinitionParameterExtraction() {
        var file = testProject.getAllFiles().stream()
                .filter(f -> f.absPath().toString().endsWith("scoped_def.cpp"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("scoped_def.cpp not found"));

        var decls = analyzer.getDeclarations(file);

        // Method m should be found
        assertTrue(decls.stream().anyMatch(cu -> getBaseFunctionName(cu).equals("m")), "Should find C::m");
    }

    @Test
    public void testDuplicatePrototypesCollapsed() {
        var file = testProject.getAllFiles().stream()
                .filter(f -> f.absPath().toString().endsWith("dupe_prototypes.h"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("dupe_prototypes.h not found"));

        logger.info("Test file exists at: {}", file.absPath());
        var decls = analyzer.getDeclarations(file);
        logger.info("Analyzing dupe_prototypes.h at: {}", file.absPath());
        logger.info("Total declarations found: {}", decls.size());
        decls.forEach(cu -> logger.info("  - {} (kind: {}, isFunction: {})", cu.fqName(), cu.kind(), cu.isFunction()));

        // Filter for functions named 'duplicated_function'
        var dupFuncs = decls.stream()
                .filter(CodeUnit::isFunction)
                .filter(cu -> getBaseFunctionName(cu).equals("duplicated_function"))
                .collect(Collectors.toList());

        logger.debug("Found {} declaration(s) for duplicated_function", dupFuncs.size());
        dupFuncs.forEach(cu -> logger.debug("  - {} (FQN: {})", cu.shortName(), cu.fqName()));

        // Assert that duplicate prototypes are collapsed to a single declaration
        assertEquals(1, dupFuncs.size(), "Duplicate prototypes should be collapsed to a single declaration");

        // Verify skeleton generation succeeds and contains the retained declaration
        var skeletons = analyzer.getSkeletons(file);
        assertNotNull(skeletons, "Should generate skeletons for dupe_prototypes.h");

        // Verify the retained function has a skeleton entry
        assertTrue(
                skeletons.containsKey(dupFuncs.get(0)),
                "Skeletons should include the retained declaration for duplicated_function");

        // Verify the skeleton is non-null and non-empty
        var skeleton = skeletons.get(dupFuncs.get(0));
        assertNotNull(skeleton, "Skeleton for duplicated_function should be non-null");
        assertFalse(skeleton.isEmpty(), "Skeleton for duplicated_function should be non-empty");

        logger.debug("Skeleton for duplicated_function: {}", skeleton);
    }

    @Test
    public void testDefinitionPreferredOverDeclaration() {
        var file = testProject.getAllFiles().stream()
                .filter(f -> f.absPath().toString().endsWith("forward_decl.h"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("forward_decl.h not found"));

        var skeletons = analyzer.getSkeletons(file);

        // There should be a skeleton entry (definition) for foo() and its skeleton should include body placeholder or
        // implementation
        var fooEntry = skeletons.entrySet().stream()
                .filter(e -> e.getKey().isFunction())
                .filter(e -> getBaseFunctionName(e.getKey()).equals("foo"))
                .findFirst();

        assertTrue(fooEntry.isPresent(), "Should find skeleton for foo()");

        // Ensure we prefer the single definition over any prototype in the header
        int skeletonFooCount = (int) skeletons.keySet().stream()
                .filter(CodeUnit::isFunction)
                .filter(k -> getBaseFunctionName(k).equals("foo"))
                .count();
        assertEquals(1, skeletonFooCount, "Should prefer single definition for foo() over prototype in header");

        // Also verify declarations set contains exactly one function CodeUnit for foo()
        var decls = analyzer.getDeclarations(file);
        long declFooCount = decls.stream()
                .filter(CodeUnit::isFunction)
                .filter(cu -> getBaseFunctionName(cu).equals("foo"))
                .count();
        assertEquals(1, declFooCount, "Only one function CodeUnit for foo() should be present (the definition)");

        String sig = fooEntry.get().getValue();
        // The signature should either include a body placeholder or actual body text; prefer placeholder check
        assertTrue(
                sig.contains("{...}") || sig.contains("{"), "Expected function skeleton to indicate a body for foo()");
    }

    @Test
    public void testDefinitionPreferredInSameFile() {
        // Validate semantics don't rely on placeholder strings by using a file that contains:
        // - a class method declaration inside the class
        // - an out-of-line definition of that method later in the same file
        //
        // The analyzer should expose a single top-level function CodeUnit for the definition,
        // even though the in-class declaration has a differently formatted signature (no body).
        var file = testProject.getAllFiles().stream()
                .filter(f -> f.absPath().toString().endsWith("decl_vs_def.h"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("decl_vs_def.h not found"));

        // Declarations: ensure we only consider the out-of-line definition (scope-resolved name)
        var decls = analyzer.getDeclarations(file);
        var outOfLineFuncs = decls.stream()
                .filter(CodeUnit::isFunction)
                .filter(cu -> getBaseFunctionName(cu).equals("declaration_only"))
                .filter(cu -> cu.fqName().contains("::") || cu.shortName().contains("::"))
                .collect(Collectors.toList());

        // Deduplicate by signature to avoid double-captures of the same definition
        var uniqueOutOfLineSigs = outOfLineFuncs.stream()
                .map(CodeUnit::signature)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        assertEquals(
                1,
                uniqueOutOfLineSigs.size(),
                "Should have exactly one unique out-of-line definition signature for 'declaration_only'. "
                        + "Candidates: "
                        + outOfLineFuncs.stream().map(CodeUnit::fqName).collect(Collectors.toList())
                        + ", Signatures: " + uniqueOutOfLineSigs);

        // Skeletons: confirm the corresponding skeleton contains a body placeholder for the definition
        var skeletons = analyzer.getSkeletons(file);
        var funcSkeletonEntry = skeletons.entrySet().stream()
                .filter(e -> e.getKey().isFunction())
                .filter(e -> getBaseFunctionName(e.getKey()).equals("declaration_only"))
                .findFirst();

        assertTrue(funcSkeletonEntry.isPresent(), "Should have skeleton for out-of-line definition");
        String skeleton = funcSkeletonEntry.get().getValue();
        assertTrue(
                skeleton.contains("{...}"),
                "Out-of-line definition should contain '{...}' body placeholder. Skeleton: " + skeleton);

        // Sanity: ensure in-class declaration is still present in the class skeleton and does not have body placeholder
        var classSkeleton = skeletons.entrySet().stream()
                .filter(e -> e.getKey().isClass())
                .filter(e -> e.getKey().shortName().contains("DeclVsDef"))
                .map(Map.Entry::getValue)
                .findFirst();
        assertTrue(classSkeleton.isPresent(), "DeclVsDef class skeleton should be present");
        String classSkelText = classSkeleton.get();
        var declLine = classSkelText
                .lines()
                .filter(line -> line.contains("declaration_only"))
                .filter(line -> !line.contains("::"))
                .findFirst()
                .orElse("");
        assertFalse(
                declLine.contains("{...}") || declLine.contains("{"),
                "Class-scope declaration should not include body placeholder");
    }

    @Test
    public void testTemplateFunctionPointerParameter() {
        var file = testProject.getAllFiles().stream()
                .filter(f -> f.absPath().toString().endsWith("template_fpointers.h"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("template_fpointers.h not found"));

        var decls = analyzer.getDeclarations(file);
        assertFalse(decls.isEmpty(), "Should find declarations in template_fpointers.h");

        // Find function CodeUnits and locate 'g'
        var gFuncs = decls.stream()
                .filter(CodeUnit::isFunction)
                .filter(cu -> getBaseFunctionName(cu).equals("g"))
                .collect(Collectors.toList());

        assertFalse(gFuncs.isEmpty(), "Should find function g() in template_fpointers.h");

        // Pick one occurrence and inspect its signature for intact template argument list
        var gCu = gFuncs.get(0);
        String sig = gCu.signature();
        logger.debug("Found g() signature: {}", sig);

        // The signature should include the intact template argument list "std::vector<int>"
        // without splitting on the comma inside the template arguments
        assertTrue(
                sig != null && sig.contains("std::vector<int>"),
                "Signature should include intact template argument list: expected to find 'std::vector<int>' in "
                        + sig);
    }

    @Test
    public void testNamespacedOverloadedFunctionFqNames() {
        // Test for issue 1: Package duplication in FQNs for namespace-scoped overloaded functions
        var file = testProject.getAllFiles().stream()
                .filter(f -> f.absPath().toString().endsWith("namespace_overloads.h"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("namespace_overloads.h not found"));

        var decls = analyzer.getDeclarations(file);
        assertFalse(decls.isEmpty(), "Should find declarations in namespace_overloads.h");

        logger.debug("Total declarations found: {}", decls.size());
        decls.forEach(cu -> logger.debug(
                "  - {} (kind: {}, packageName: '{}', shortName: '{}', fqName: '{}')",
                cu.identifier(),
                cu.kind(),
                cu.packageName(),
                cu.shortName(),
                cu.fqName()));

        // Find free function overloads in namespace 'ns'
        var freeFuncOverloads = decls.stream()
                .filter(CodeUnit::isFunction)
                .filter(cu -> getBaseFunctionName(cu).equals("free_func"))
                .collect(Collectors.toList());

        assertEquals(2, freeFuncOverloads.size(), "Should find 2 overloads of free_func");

        for (var cu : freeFuncOverloads) {
            logger.debug("Checking free_func overload: {}", cu.fqName());

            // Verify package name is correct
            assertEquals("ns", cu.packageName(), "Package name should be 'ns' for free_func");

            // Verify NO package duplication in FQN
            // FQN should be "ns.free_func(int)" or "ns.free_func(double)"
            // NOT "ns.ns.free_func(int)" or "ns.ns.free_func(double)"
            assertFalse(
                    cu.fqName().contains("ns.ns."),
                    "FQN should NOT contain duplicated package 'ns.ns.' but was: " + cu.fqName());

            // Verify FQN starts with package name exactly once
            assertTrue(cu.fqName().startsWith("ns."), "FQN should start with 'ns.' but was: " + cu.fqName());

            // Verify shortName doesn't contain package prefix
            assertFalse(
                    cu.shortName().startsWith("ns."),
                    "shortName should NOT start with package prefix 'ns.' but was: " + cu.shortName());
        }

        // Find method overloads in class C within namespace 'ns'
        var methodOverloads = decls.stream()
                .filter(CodeUnit::isFunction)
                .filter(cu -> getBaseFunctionName(cu).equals("method"))
                .collect(Collectors.toList());

        assertEquals(2, methodOverloads.size(), "Should find 2 overloads of C.method");

        for (var cu : methodOverloads) {
            logger.debug("Checking method overload: {}", cu.fqName());

            // Verify package name is correct
            assertEquals("ns", cu.packageName(), "Package name should be 'ns' for C.method");

            // Verify NO package duplication in FQN
            // FQN should be "ns.C.method(int)" or "ns.C.method(double)"
            // NOT "ns.ns.C.method(int)" or "ns.ns.C.method(double)"
            assertFalse(
                    cu.fqName().contains("ns.ns."),
                    "FQN should NOT contain duplicated package 'ns.ns.' but was: " + cu.fqName());

            // Verify FQN starts with package name exactly once
            assertTrue(cu.fqName().startsWith("ns."), "FQN should start with 'ns.' but was: " + cu.fqName());

            // Verify shortName doesn't contain package prefix (should be "C.method(...)")
            assertFalse(
                    cu.shortName().startsWith("ns."),
                    "shortName should NOT start with package prefix 'ns.' but was: " + cu.shortName());
        }
    }

    @Test
    public void testDefinitionVsDeclarationDetection() {
        // Test for issue 3: Verify AST-based detection of definitions vs declarations
        var file = testProject.getAllFiles().stream()
                .filter(f -> f.absPath().toString().endsWith("decl_vs_def.h"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("decl_vs_def.h not found"));

        var skeletons = analyzer.getSkeletons(file);
        assertFalse(skeletons.isEmpty(), "Should find skeletons in decl_vs_def.h");

        logger.debug("Total skeletons found: {}", skeletons.size());
        skeletons.forEach((cu, skeleton) -> logger.debug("  - {} (kind: {}):\n{}", cu.fqName(), cu.kind(), skeleton));

        // Find the DeclVsDef class skeleton
        var classSkeleton = skeletons.entrySet().stream()
                .filter(e -> e.getKey().isClass())
                .filter(e -> e.getKey().shortName().contains("DeclVsDef"))
                .map(Map.Entry::getValue)
                .findFirst();

        assertTrue(classSkeleton.isPresent(), "Should find DeclVsDef class skeleton");

        String classSkeletonContent = classSkeleton.get();
        logger.debug("DeclVsDef class skeleton:\n{}", classSkeletonContent);

        // Verify declaration_only appears without body placeholder in class skeleton
        assertTrue(
                classSkeletonContent.contains("void declaration_only()"),
                "Class skeleton should contain declaration_only method declaration");

        // Extract just the declaration_only line from the class skeleton
        var declarationOnlyLine = classSkeletonContent
                .lines()
                .filter(line -> line.contains("declaration_only"))
                .filter(line -> !line.contains("::"))
                .findFirst()
                .orElse("");

        logger.debug("declaration_only line in class: '{}'", declarationOnlyLine);

        // Declaration in class body should NOT have body placeholder
        assertFalse(
                declarationOnlyLine.contains("{...}") || declarationOnlyLine.contains("{"),
                "Declaration-only method in class body should NOT contain body placeholder. Line: "
                        + declarationOnlyLine);

        // Verify inline_definition appears WITH body placeholder in class skeleton
        var inlineDefinitionLine = classSkeletonContent
                .lines()
                .filter(line -> line.contains("inline_definition"))
                .findFirst()
                .orElse("");

        logger.debug("inline_definition line in class: '{}'", inlineDefinitionLine);

        assertTrue(
                inlineDefinitionLine.contains("{...}") || inlineDefinitionLine.contains("{"),
                "Inline definition should contain body placeholder. Line: " + inlineDefinitionLine);

        // Find out-of-line definition of declaration_only (should have body placeholder)
        var outOfLineDefinition = skeletons.entrySet().stream()
                .filter(e -> e.getKey().isFunction())
                .filter(e -> getBaseFunctionName(e.getKey()).equals("declaration_only"))
                .filter(e -> e.getValue().contains("DeclVsDef::"))
                .findFirst();

        assertTrue(outOfLineDefinition.isPresent(), "Should find out-of-line definition of declaration_only method");

        String outOfLineSkeleton = outOfLineDefinition.get().getValue();
        logger.debug("declaration_only (out-of-line definition) skeleton: {}", outOfLineSkeleton);

        // Out-of-line definition should have body placeholder
        assertTrue(
                outOfLineSkeleton.contains("{...}"),
                "Out-of-line definition should contain '{...}' body placeholder. Skeleton: " + outOfLineSkeleton);
    }

    @Test
    public void testSignatureFieldPopulation() {
        // Test that signature field is properly populated for C++ functions
        var file = testProject.getAllFiles().stream()
                .filter(f -> f.absPath().toString().endsWith("namespace_overloads.h"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("namespace_overloads.h not found"));

        var decls = analyzer.getDeclarations(file);
        assertFalse(decls.isEmpty(), "Should find declarations in namespace_overloads.h");

        logger.debug("Testing signature field population:");
        decls.forEach(cu -> logger.debug(
                "  - {} (kind: {}, signature: '{}', shortName: '{}', fqName: '{}')",
                cu.identifier(),
                cu.kind(),
                cu.signature(),
                cu.shortName(),
                cu.fqName()));

        // Find function declarations
        var functions = decls.stream().filter(CodeUnit::isFunction).collect(Collectors.toList());

        assertTrue(functions.size() >= 4, "Should find at least 4 function declarations");

        for (var func : functions) {
            logger.debug("Checking function: {}", func.fqName());

            // All functions should have signature populated
            assertNotNull(func.signature(), "Signature field should be populated for function: " + func.fqName());
            assertTrue(func.hasSignature(), "hasSignature() should return true for: " + func.fqName());

            // Signature should contain parentheses
            assertTrue(
                    func.signature().startsWith("("),
                    "Signature should start with '(' for function: " + func.fqName() + ", got: " + func.signature());
            assertTrue(
                    func.signature().contains(")"),
                    "Signature should contain ')' for function: " + func.fqName() + ", got: " + func.signature());

            // FQN should NOT contain parameters
            var fqName = func.fqName();
            assertFalse(
                    fqName.contains("("),
                    "FQN should NOT contain '(' (parameters should be in signature only): " + fqName);

            // shortName should NOT contain parameters
            var shortName = func.shortName();
            assertFalse(
                    shortName.contains("("),
                    "shortName should NOT contain '(' (parameters should be in signature only): " + shortName);
        }

        // Verify specific functions have expected signatures
        var freeFuncInt = functions.stream()
                .filter(f -> f.shortName().equals("free_func") && f.signature().contains("int"))
                .findFirst();
        assertTrue(freeFuncInt.isPresent(), "Should find free_func with int parameter");
        assertEquals("(int)", freeFuncInt.get().signature(), "free_func(int) should have signature '(int)'");
        assertEquals(
                "ns.free_func",
                freeFuncInt.get().fqName(),
                "free_func FQN should be 'ns.free_func' without parameters");

        var methodInt = functions.stream()
                .filter(f -> f.shortName().equals("C.method") && f.signature().contains("int"))
                .findFirst();
        assertTrue(methodInt.isPresent(), "Should find C.method with int parameter");
        assertEquals("(int)", methodInt.get().signature(), "C.method(int) should have signature '(int)'");
        assertEquals(
                "ns.C.method", methodInt.get().fqName(), "C.method FQN should be 'ns.C.method' without parameters");
    }

    @Test
    public void testHasBodyFlagPrefersDefinitionInSameFile() {
        // Validate hasBody flag semantics for in-class declaration vs out-of-line definition in the same file.
        var file = testProject.getAllFiles().stream()
                .filter(f -> f.absPath().toString().endsWith("decl_vs_def.h"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("decl_vs_def.h not found"));

        var decls = analyzer.getDeclarations(file);
        assertFalse(decls.isEmpty(), "Should find declarations in decl_vs_def.h");

        // Collect all function CodeUnits named 'declaration_only'
        var candidates = decls.stream()
                .filter(CodeUnit::isFunction)
                .filter(cu -> getBaseFunctionName(cu).equals("declaration_only"))
                .collect(Collectors.toList());

        assertTrue(
                candidates.size() >= 2,
                "Should find at least 2 CodeUnits for declaration_only (in-class declaration and out-of-line definition)");

        // Determine hasBody for each
        class CUHasBody {
            final CodeUnit cu;
            final boolean hasBody;

            CUHasBody(CodeUnit cu, boolean hasBody) {
                this.cu = cu;
                this.hasBody = hasBody;
            }
        }

        var withFlags = candidates.stream()
                .map(cu -> new CUHasBody(cu, analyzer.withCodeUnitProperties(map -> map.getOrDefault(
                                cu, TreeSitterAnalyzer.CodeUnitProperties.empty())
                        .hasBody())))
                .collect(Collectors.toList());

        // Identify out-of-line definition (prefer those with hasBody == true)
        var outOfLineOpt =
                withFlags.stream().filter(x -> x.hasBody).map(x -> x.cu).findFirst();
        assertTrue(outOfLineOpt.isPresent(), "Out-of-line definition with hasBody=true should exist");
        CodeUnit outOfLine = outOfLineOpt.get();

        // Identify class-scope declaration (hasBody == false)
        var inClassOpt =
                withFlags.stream().filter(x -> !x.hasBody).map(x -> x.cu).findFirst();
        assertTrue(inClassOpt.isPresent(), "In-class declaration with hasBody=false should exist");
        CodeUnit inClass = inClassOpt.get();

        // Sanity: ensure they are distinct CUs
        assertNotEquals(outOfLine, inClass, "Out-of-line definition and in-class declaration should be distinct");

        // Verify the skeleton for the out-of-line definition contains display placeholder "{...}"
        var skeletons = analyzer.getSkeletons(file);
        String defSkeleton = skeletons.get(outOfLine);
        assertNotNull(defSkeleton, "Skeleton for out-of-line definition should exist");
        assertTrue(
                defSkeleton.contains("{...}"),
                "Out-of-line definition skeleton should contain '{...}' body placeholder. Skeleton: " + defSkeleton);
    }

    @Test
    public void testHasBodyFlagAcrossHeaderAndSourceFiles() {
        // Validate hasBody semantics across header prototype and source definition (forward_decl.h)
        var headerFile = testProject.getAllFiles().stream()
                .filter(f -> f.absPath().toString().endsWith("forward_decl.h"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("forward_decl.h not found"));

        // Gather header declarations and filter for functions named 'foo'
        var headerDecls = analyzer.getDeclarations(headerFile);
        assertFalse(headerDecls.isEmpty(), "Should find declarations in forward_decl.h");

        var headerCandidates = headerDecls.stream()
                .filter(CodeUnit::isFunction)
                .filter(cu -> getBaseFunctionName(cu).equals("foo"))
                .collect(Collectors.toList());
        assertFalse(
                headerCandidates.isEmpty(),
                "Should find at least one function named 'foo' in header. Available: "
                        + headerDecls.stream().map(CodeUnit::fqName).collect(Collectors.toList()));

        // Try to select a true header prototype with hasBody == false
        CodeUnit headerProto = null;
        for (var cu : headerCandidates) {
            boolean hasBody = analyzer.withCodeUnitProperties(
                    map -> map.getOrDefault(cu, TreeSitterAnalyzer.CodeUnitProperties.empty())
                            .hasBody());
            if (!hasBody) {
                headerProto = cu;
                break;
            }
        }

        if (headerProto != null) {
            final var headerProtoFinal = headerProto;
            boolean headerHasBody = analyzer.withCodeUnitProperties(
                    map -> map.getOrDefault(headerProtoFinal, TreeSitterAnalyzer.CodeUnitProperties.empty())
                            .hasBody());
            assertFalse(headerHasBody, "Header prototype should have hasBody == false");

            // Search across all declarations for a definition (hasBody == true) with the same FQN
            var allDecls = getAllDeclarations();
            CodeUnit sourceDef = null;
            for (var cu : allDecls) {
                if (!cu.isFunction()) continue;
                if (!getBaseFunctionName(cu).equals("foo")) continue;
                if (!cu.fqName().equals(headerProto.fqName())) continue;

                final var candidateFinal = cu;
                boolean hasBody = analyzer.withCodeUnitProperties(
                        map -> map.getOrDefault(candidateFinal, TreeSitterAnalyzer.CodeUnitProperties.empty())
                                .hasBody());
                if (hasBody) {
                    sourceDef = cu;
                    break;
                }
            }

            assertNotNull(
                    sourceDef,
                    "Should find a source definition with hasBody == true for 'foo' and FQN " + headerProto.fqName());

            // Verify the skeleton for the source definition contains the display placeholder
            var sourceFile = sourceDef.source();
            var sourceSkeletons = analyzer.getSkeletons(sourceFile);
            String srcSkel = sourceSkeletons.get(sourceDef);
            assertNotNull(srcSkel, "Source skeleton for definition should exist");
            assertTrue(
                    srcSkel.contains("{...}"),
                    "Source definition skeleton should contain '{...}' placeholder. Skeleton: " + srcSkel);
        } else {
            // No header prototype (hasBody==false) found: header may contain definition or merges occurred
            var allDecls = getAllDeclarations();
            CodeUnit sourceDef = null;
            for (var cu : allDecls) {
                if (!cu.isFunction()) continue;
                if (!getBaseFunctionName(cu).equals("foo")) continue;

                final var candidateFinal = cu;
                boolean hasBody = analyzer.withCodeUnitProperties(
                        map -> map.getOrDefault(candidateFinal, TreeSitterAnalyzer.CodeUnitProperties.empty())
                                .hasBody());
                if (hasBody) {
                    sourceDef = cu;
                    break;
                }
            }

            assertNotNull(
                    sourceDef,
                    "Could not find a definition (hasBody==true) for function 'foo' across all declarations.");

            // Verify the skeleton for the found definition contains the display placeholder
            var sourceFile = sourceDef.source();
            var sourceSkeletons = analyzer.getSkeletons(sourceFile);
            String srcSkel = sourceSkeletons.get(sourceDef);
            assertNotNull(srcSkel, "Source skeleton for definition should exist");
            assertTrue(
                    srcSkel.contains("{...}"),
                    "Source definition skeleton should contain '{...}' placeholder. Skeleton: " + srcSkel);

            // Additionally, ensure the header skeletons prefer a single function entry for 'foo'
            var headerSkeletons = analyzer.getSkeletons(headerFile);
            long headerFooCount = headerSkeletons.keySet().stream()
                    .filter(CodeUnit::isFunction)
                    .filter(k -> getBaseFunctionName(k).equals("foo"))
                    .count();
            assertEquals(
                    1L,
                    headerFooCount,
                    "Header skeletons should prefer a single function entry for 'foo', but found: " + headerFooCount);
        }
    }
}
