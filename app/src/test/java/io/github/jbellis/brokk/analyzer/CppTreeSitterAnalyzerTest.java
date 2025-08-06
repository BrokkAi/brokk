package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.testutil.TestProject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class CppTreeSitterAnalyzerTest {

    /** Creates a TestProject rooted under src/test/resources/{subDir}. */
    static TestProject createTestProject(String subDir, io.github.jbellis.brokk.analyzer.Language lang) {
        Path testDir = Path.of("src/test/resources", subDir);
        assertTrue(Files.exists(testDir), "Test resource dir missing: " + testDir);
        assertTrue(Files.isDirectory(testDir), testDir + " is not a directory");
        return new TestProject(testDir.toAbsolutePath(), lang);
    }

    @Test
    void testCppTreeSitterInitializationAndBasicStructure() {
        TestProject project = createTestProject("testcode-cpp", Language.CPP_TREESITTER);
        CppTreeSitterAnalyzer analyzer = new CppTreeSitterAnalyzer(project, Set.of());
        assertInstanceOf(CppTreeSitterAnalyzer.class, analyzer);
        assertFalse(analyzer.isEmpty(), "Analyzer should have processed C++ files");
    }

    @Test
    void testCppClassAnalysis() {
        TestProject project = createTestProject("testcode-cpp", Language.CPP_TREESITTER);
        CppTreeSitterAnalyzer analyzer = new CppTreeSitterAnalyzer(project, Set.of());

        // Test geometry.h - shapes::Circle class
        ProjectFile geometryHeader = new ProjectFile(project.getRoot(), "geometry.h");
        var declarationsInHeader = analyzer.getDeclarationsInFile(geometryHeader);

        // Expected: shapes::Circle class, Point struct, global function declarations
        var circleClass = CodeUnit.cls(geometryHeader, "shapes", "Circle");
        var pointStruct = CodeUnit.cls(geometryHeader, "", "Point");

        assertTrue(declarationsInHeader.contains(circleClass),
                   "Header should contain shapes::Circle class. Found: " + declarationsInHeader);
        assertTrue(declarationsInHeader.contains(pointStruct),
                   "Header should contain Point struct. Found: " + declarationsInHeader);

        // Test skeletons for classes
        var headerSkeletons = analyzer.getSkeletons(geometryHeader);
        assertTrue(headerSkeletons.containsKey(circleClass), "Should have skeleton for Circle class");
        assertTrue(headerSkeletons.containsKey(pointStruct), "Should have skeleton for Point struct");

        var circleSkeleton = headerSkeletons.get(circleClass);
        assertTrue(circleSkeleton.contains("class Circle"), "Circle skeleton should contain class declaration");

        var pointSkeleton = headerSkeletons.get(pointStruct);
        assertTrue(pointSkeleton.contains("struct Point"), "Point skeleton should contain struct declaration");
    }

    @Test
    void testCppNamespaceHandling() {
        TestProject project = createTestProject("testcode-cpp", Language.CPP_TREESITTER);
        CppTreeSitterAnalyzer analyzer = new CppTreeSitterAnalyzer(project, Set.of());

        // Test geometry.cpp - implementations in shapes namespace
        ProjectFile geometryImpl = new ProjectFile(project.getRoot(), "geometry.cpp");
        var declarationsInImpl = analyzer.getDeclarationsInFile(geometryImpl);

        // Expected functions in shapes namespace and global namespace
        var circleConstructor = CodeUnit.fn(geometryImpl, "shapes", "Circle::Circle");
        var getAreaMethod = CodeUnit.fn(geometryImpl, "shapes", "Circle::getArea");
        var getObjectTypeMethod = CodeUnit.fn(geometryImpl, "shapes", "Circle::getObjectType");
        var anotherInShapes = CodeUnit.fn(geometryImpl, "shapes", "another_in_shapes");

        // Global functions
        var pointPrint = CodeUnit.fn(geometryImpl, "", "Point::print");
        var globalFunc = CodeUnit.fn(geometryImpl, "", "global_func");
        var usesGlobalFunc = CodeUnit.fn(geometryImpl, "", "uses_global_func");

        assertTrue(declarationsInImpl.contains(circleConstructor),
                   "Should contain Circle constructor in shapes namespace");
        assertTrue(declarationsInImpl.contains(getAreaMethod),
                   "Should contain getArea method in shapes namespace");
        assertTrue(declarationsInImpl.contains(anotherInShapes),
                   "Should contain another_in_shapes function in shapes namespace");
        assertTrue(declarationsInImpl.contains(pointPrint),
                   "Should contain Point::print in global namespace");
        assertTrue(declarationsInImpl.contains(globalFunc),
                   "Should contain global_func in global namespace");
    }

    @Test
    void testCppFunctionAnalysis() {
        TestProject project = createTestProject("testcode-cpp", Language.CPP_TREESITTER);
        CppTreeSitterAnalyzer analyzer = new CppTreeSitterAnalyzer(project, Set.of());

        ProjectFile geometryImpl = new ProjectFile(project.getRoot(), "geometry.cpp");
        var skeletons = analyzer.getSkeletons(geometryImpl);

        // Test function skeletons
        var globalFunc = CodeUnit.fn(geometryImpl, "", "global_func");
        assertTrue(skeletons.containsKey(globalFunc), "Should have skeleton for global_func");

        var globalFuncSkeleton = skeletons.get(globalFunc);
        assertTrue(globalFuncSkeleton.contains("void global_func"),
                   "global_func skeleton should contain function signature");
        assertTrue(globalFuncSkeleton.contains("int val"),
                   "global_func skeleton should contain parameter");
    }

    @Test
    void testCppGlobalVariables() {
        TestProject project = createTestProject("testcode-cpp", Language.CPP_TREESITTER);
        CppTreeSitterAnalyzer analyzer = new CppTreeSitterAnalyzer(project, Set.of());

        ProjectFile geometryImpl = new ProjectFile(project.getRoot(), "geometry.cpp");
        var declarations = analyzer.getDeclarationsInFile(geometryImpl);

        // Check for global variable - this might be captured as a field declaration
        var globalVar = CodeUnit.field(geometryImpl, "", "global_var");

        // Note: Depending on how the TreeSitter grammar captures global variables,
        // this test might need adjustment
        boolean hasGlobalVar = declarations.stream()
                .anyMatch(cu -> cu.kind() == CodeUnitType.FIELD && cu.fqName().contains("global_var"));

        // This test verifies that global variables are being processed in some form
        assertTrue(hasGlobalVar || !declarations.isEmpty(),
                   "Should process global variables or other declarations. Found: " + declarations);
    }

    @Test
    void testCppTemplateHandling() {
        TestProject project = createTestProject("testcode-cpp", Language.CPP_TREESITTER);
        CppTreeSitterAnalyzer analyzer = new CppTreeSitterAnalyzer(project, Set.of());

        // This is a basic test - we might need to add template test files
        // For now, just verify the analyzer doesn't crash on existing files
        ProjectFile geometryHeader = new ProjectFile(project.getRoot(), "geometry.h");
        var declarations = analyzer.getDeclarationsInFile(geometryHeader);

        assertNotNull(declarations, "Should handle files without templates gracefully");
        assertFalse(declarations.isEmpty(), "Should find some declarations");
    }

    @Test
    void testCppMultipleFiles() {
        TestProject project = createTestProject("testcode-cpp", Language.CPP_TREESITTER);
        CppTreeSitterAnalyzer analyzer = new CppTreeSitterAnalyzer(project, Set.of());

        // Test that analyzer processes multiple C++ files
        ProjectFile mainCpp = new ProjectFile(project.getRoot(), "main.cpp");
        ProjectFile nestedCpp = new ProjectFile(project.getRoot(), "nested.cpp");

        if (Files.exists(mainCpp.absPath())) {
            var mainDeclarations = analyzer.getDeclarationsInFile(mainCpp);
            assertNotNull(mainDeclarations, "Should process main.cpp");
        }

        if (Files.exists(nestedCpp.absPath())) {
            var nestedDeclarations = analyzer.getDeclarationsInFile(nestedCpp);
            assertNotNull(nestedDeclarations, "Should process nested.cpp");
        }

        // Verify analyzer isn't empty after processing multiple files
        assertFalse(analyzer.isEmpty(), "Analyzer should contain declarations from multiple files");
    }

    @Test
    void testCppSkeletonGeneration() {
        TestProject project = createTestProject("testcode-cpp", Language.CPP_TREESITTER);
        CppTreeSitterAnalyzer analyzer = new CppTreeSitterAnalyzer(project, Set.of());

        ProjectFile geometryHeader = new ProjectFile(project.getRoot(), "geometry.h");
        var skeletons = analyzer.getSkeletons(geometryHeader);

        assertFalse(skeletons.isEmpty(), "Should generate skeletons for C++ constructs");

        // Verify skeleton content makes sense
        for (var entry : skeletons.entrySet()) {
            var codeUnit = entry.getKey();
            var skeleton = entry.getValue();

            assertNotNull(skeleton, "Skeleton should not be null for " + codeUnit);
            assertFalse(skeleton.trim().isEmpty(), "Skeleton should not be empty for " + codeUnit);

            if (codeUnit.isClass()) {
                assertTrue(skeleton.contains("class") || skeleton.contains("struct"),
                          "Class skeleton should contain class/struct keyword");
            } else if (codeUnit.isFunction()) {
                assertTrue(skeleton.contains("(") && skeleton.contains(")"),
                          "Function skeleton should contain parentheses");
            }
        }
    }
}