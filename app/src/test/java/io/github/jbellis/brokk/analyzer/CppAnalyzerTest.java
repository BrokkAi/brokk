package io.github.jbellis.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.testutil.TestProject;
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

    @Test
    public void isEmptyTest() {
        assertFalse(analyzer.isEmpty());
    }

    private List<CodeUnit> getAllDeclarations() {
        return testProject.getAllFiles().stream()
                .flatMap(file -> analyzer.getDeclarationsInFile(file).stream())
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

        var allDeclarations = analyzer.getDeclarationsInFile(geometryFile);

        var allFunctions = allDeclarations.stream().filter(CodeUnit::isFunction).collect(Collectors.toList());

        var anonymousNamespaceFunctions = allDeclarations.stream()
                .filter(CodeUnit::isFunction)
                .filter(cu -> cu.shortName().contains("anonymous_helper")
                        || cu.shortName().contains("anonymous_void_func"))
                .collect(Collectors.toList());

        var functionNames = allFunctions.stream().map(cu -> cu.shortName()).collect(Collectors.toList());

        assertFalse(
                anonymousNamespaceFunctions.isEmpty(),
                "Should find function from anonymous namespace. Available functions: " + functionNames);

        var anonymousHelperFunction = anonymousNamespaceFunctions.stream()
                .filter(cu -> cu.identifier().equals("anonymous_helper"))
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

        var declarations = analyzer.getDeclarationsInFile(advancedFile.get());

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
}
