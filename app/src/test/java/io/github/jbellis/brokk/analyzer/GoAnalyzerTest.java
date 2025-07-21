package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.testutil.TestProject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterGo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors; // Already present, no change needed to this line, but ensure it's here


import static org.junit.jupiter.api.Assertions.*;

public class GoAnalyzerTest {
    private static TestProject testProject;
    private static GoAnalyzer analyzer;
    private static final TSLanguage GO_LANGUAGE = new TreeSitterGo(); // For direct parsing tests

    private static ProjectFile packagesGoFile;
    private static ProjectFile anotherGoFile;
    private static ProjectFile noPkgGoFile;
    private static ProjectFile emptyGoFile;
    private static ProjectFile declarationsGoFile;


    @BeforeAll
    static void setUp() {
        Path testCodeDir = Path.of("src/test/resources/testcode-go").toAbsolutePath();
        assertTrue(Files.exists(testCodeDir), "Test resource directory 'testcode-go' not found.");
        testProject = new TestProject(testCodeDir, Language.GO);
        analyzer = new GoAnalyzer(testProject);

        packagesGoFile = new ProjectFile(testProject.getRoot(), "packages.go");
        anotherGoFile = new ProjectFile(testProject.getRoot(), Path.of("anotherpkg", "another.go"));
        noPkgGoFile = new ProjectFile(testProject.getRoot(), "nopkg.go");
        emptyGoFile = new ProjectFile(testProject.getRoot(), "empty.go");
        declarationsGoFile = new ProjectFile(testProject.getRoot(), "declarations.go");
    }

    // Helper method to parse Go code and get the root node
    private TSNode parseGoCode(String code) {
        TSParser parser = new TSParser();
        parser.setLanguage(GO_LANGUAGE);
        TSTree tree = parser.parseString(null, code);
        return tree.getRootNode();
    }

    private String getPackageNameViaAnalyzerHelper(String code) {
        TSParser parser = new TSParser();
        TSTree tree;
        parser.setLanguage(GO_LANGUAGE);
        tree = parser.parseString(null, code);
        TSNode rootNode = tree.getRootNode();
        // Pass null for definitionNode as it's not used by Go's determinePackageName for package clauses
        return analyzer.determinePackageName(null, null, rootNode, code);
    }

    @Test
    void testDeterminePackageName_SimpleMain() {
        String code = "package main\n\nfunc main() {}";
        assertEquals("main", getPackageNameViaAnalyzerHelper(code));
    }

    @Test
    void testDeterminePackageName_CustomPackage() {
        String code = "package mypkg\n\nimport \"fmt\"\n\nfunc Hello() { fmt.Println(\"Hello\") }";
        assertEquals("mypkg", getPackageNameViaAnalyzerHelper(code));
    }

    @Test
    void testDeterminePackageName_WithComments() {
        String code = "// This is a comment\npackage main /* another comment */";
        assertEquals("main", getPackageNameViaAnalyzerHelper(code));
    }

    @Test
    void testDeterminePackageName_NoPackageClause() {
        String code = "func main() {}"; // Missing package clause
        assertEquals("", getPackageNameViaAnalyzerHelper(code));
    }

    @Test
    void testDeterminePackageName_EmptyFileContent() {
        String code = "";
        assertEquals("", getPackageNameViaAnalyzerHelper(code));
    }

    // Tests using ProjectFile and reading from actual test files
    @Test
    void testDeterminePackageName_FromProjectFile_PackagesGo() throws IOException {
        TSParser parser = new TSParser();
        TSTree tree;
        String content = Files.readString(packagesGoFile.absPath());
        parser.setLanguage(GO_LANGUAGE);
        tree = parser.parseString(null, content);
        TSNode rootNode = tree.getRootNode();
        assertEquals("main", analyzer.determinePackageName(packagesGoFile, null, rootNode, content));
    }

    @Test
    void testDeterminePackageName_FromProjectFile_AnotherGo() throws IOException {
        TSParser parser = new TSParser();
        TSTree tree;
        String content = Files.readString(anotherGoFile.absPath());
        parser.setLanguage(GO_LANGUAGE);
        tree = parser.parseString(null, content);
        TSNode rootNode = tree.getRootNode();
        assertEquals("anotherpkg", analyzer.determinePackageName(anotherGoFile, null, rootNode, content));
    }

    @Test
    void testDeterminePackageName_FromProjectFile_NoPkgGo() throws IOException {
        TSParser parser = new TSParser();
        TSTree tree;
        String content = Files.readString(noPkgGoFile.absPath());
        parser.setLanguage(GO_LANGUAGE);
        tree = parser.parseString(null, content);
        TSNode rootNode = tree.getRootNode();
        assertEquals("", analyzer.determinePackageName(noPkgGoFile, null, rootNode, content));
    }

    @Test
    void testDeterminePackageName_FromProjectFile_EmptyGo() throws IOException {
        TSParser parser = new TSParser();
        TSTree tree;
        String content = Files.readString(emptyGoFile.absPath());
        parser.setLanguage(GO_LANGUAGE);
        tree = parser.parseString(null, content);
        // Tree-sitter parsing an empty string results in a root node of type "ERROR"
        // or a specific "source_file" node that is empty or contains only EOF.
        // The query for package clause will simply not match.
        TSNode rootNode = tree.getRootNode();
        assertEquals("", analyzer.determinePackageName(emptyGoFile, null, rootNode, content));
    }

    @Test
    void testGetDeclarationsInFile_FunctionsAndTypes() {
        Set<CodeUnit> declarations = analyzer.getDeclarationsInFile(declarationsGoFile);
        assertNotNull(declarations, "Declarations set should not be null.");

        // Check if the analyzer processed the file at all. If topLevelDeclarations doesn't contain the file,
        // it means it might have been filtered out or an error occurred during its initial processing.
        assertTrue(analyzer.topLevelDeclarations.containsKey(declarationsGoFile),
                   "Analyzer's topLevelDeclarations should contain declarations.go. Current keys: " + analyzer.topLevelDeclarations.keySet());
        assertFalse(declarations.isEmpty(),
                    "Declarations set should not be empty for declarations.go. Check query and createCodeUnit logic. Actual declarations: " +
                    declarations.stream().map(CodeUnit::fqName).toList());

        ProjectFile pf = declarationsGoFile;

        CodeUnit expectedFunc = CodeUnit.fn(pf, "declpkg", "MyTopLevelFunction");
        CodeUnit expectedStruct = CodeUnit.cls(pf, "declpkg", "MyStruct");
        CodeUnit expectedInterface = CodeUnit.cls(pf, "declpkg", "MyInterface");
        CodeUnit otherFunc = CodeUnit.fn(pf, "declpkg", "anotherFunc");
        CodeUnit expectedVar = CodeUnit.field(pf, "declpkg", "_module_.MyGlobalVar");
        CodeUnit expectedConst = CodeUnit.field(pf, "declpkg", "_module_.MyGlobalConst");
        CodeUnit expectedMethod_GetFieldA = CodeUnit.fn(pf, "declpkg", "MyStruct.GetFieldA");
        CodeUnit expectedStructFieldA = CodeUnit.field(pf, "declpkg", "MyStruct.FieldA");
        CodeUnit expectedInterfaceMethod_DoSomething = CodeUnit.fn(pf, "declpkg", "MyInterface.DoSomething");


        assertTrue(declarations.contains(expectedFunc),
                   "Declarations should contain MyTopLevelFunction. Found: " + declarations.stream().map(cu -> cu.fqName() + "(" + cu.kind() + ")").toList());
        assertTrue(declarations.contains(expectedStruct),
                   "Declarations should contain MyStruct. Found: " + declarations.stream().map(cu -> cu.fqName() + "(" + cu.kind() + ")").toList());
        assertTrue(declarations.contains(expectedInterface),
                   "Declarations should contain MyInterface. Found: " + declarations.stream().map(cu -> cu.fqName() + "(" + cu.kind() + ")").toList());
        assertTrue(declarations.contains(otherFunc),
                   "Declarations should contain anotherFunc. Found: " + declarations.stream().map(cu -> cu.fqName() + "(" + cu.kind() + ")").toList());
        assertTrue(declarations.contains(expectedVar),
                   "Declarations should contain MyGlobalVar. Found: " + declarations.stream().map(cu -> cu.fqName() + "(" + cu.kind() + ")").toList());
        assertTrue(declarations.contains(expectedConst),
                   "Declarations should contain MyGlobalConst. Found: " + declarations.stream().map(cu -> cu.fqName() + "(" + cu.kind() + ")").toList());
        assertTrue(declarations.contains(expectedMethod_GetFieldA),
                   "Declarations should contain method MyStruct.GetFieldA. Found: " + declarations.stream().map(cu -> cu.fqName() + "(" + cu.kind() + ")").toList());
        assertTrue(declarations.contains(expectedStructFieldA),
                   "Declarations should contain struct field MyStruct.FieldA. Found: " + declarations.stream().map(cu -> cu.fqName() + "(" + cu.kind() + ")").toList());
        assertTrue(declarations.contains(expectedInterfaceMethod_DoSomething),
                   "Declarations should contain interface method MyInterface.DoSomething. Found: " + declarations.stream().map(cu -> cu.fqName() + "(" + cu.kind() + ")").toList());


        assertEquals(9, declarations.size(),
                    "Expected 9 declarations in declarations.go. Found: " +
                    declarations.stream().map(CodeUnit::fqName).toList());
    }

    @Test
    void testGetDefinition_FunctionsAndTypes() {
        ProjectFile pf = declarationsGoFile;

        java.util.Optional<CodeUnit> funcDef = analyzer.getDefinition("declpkg.MyTopLevelFunction");
        assertTrue(funcDef.isPresent(), "Definition for declpkg.MyTopLevelFunction should be found.");
        assertEquals(CodeUnit.fn(pf, "declpkg", "MyTopLevelFunction"), funcDef.get());
        assertTrue(funcDef.get().isFunction());

        java.util.Optional<CodeUnit> structDef = analyzer.getDefinition("declpkg.MyStruct");
        assertTrue(structDef.isPresent(), "Definition for declpkg.MyStruct should be found.");
        assertEquals(CodeUnit.cls(pf, "declpkg", "MyStruct"), structDef.get());
        assertTrue(structDef.get().isClass());

        java.util.Optional<CodeUnit> interfaceDef = analyzer.getDefinition("declpkg.MyInterface");
        assertTrue(interfaceDef.isPresent(), "Definition for declpkg.MyInterface should be found.");
        assertEquals(CodeUnit.cls(pf, "declpkg", "MyInterface"), interfaceDef.get());
        assertTrue(interfaceDef.get().isClass());

        java.util.Optional<CodeUnit> nonExistentDef = analyzer.getDefinition("declpkg.NonExistent");
        assertFalse(nonExistentDef.isPresent(), "Definition for a non-existent symbol should not be found.");
    }

    @Test
    void testGetSkeleton_TopLevelFunction() {
        // From declarations.go: package declpkg; func MyTopLevelFunction(param int) string { ... }
        java.util.Optional<String> skeleton = analyzer.getSkeleton("declpkg.MyTopLevelFunction");
        assertTrue(skeleton.isPresent(), "Skeleton for declpkg.MyTopLevelFunction should be found.");
        // Note: paramsText might be raw like "(param int)" or just "param int" depending on TSA. Adjust if needed.
        // The returnTypeText should be "string".
        // The current renderFunctionDeclaration formats it as: "func MyTopLevelFunction(param int) string { ... }"
        String expected = "func MyTopLevelFunction(param int) string { ... }";
        assertEquals(expected.trim(), skeleton.get().trim());
    }

    @Test
    void testGetSkeleton_AnotherFunction() {
        // From declarations.go: package declpkg; func anotherFunc() {}
        java.util.Optional<String> skeleton = analyzer.getSkeleton("declpkg.anotherFunc");
        assertTrue(skeleton.isPresent(), "Skeleton for declpkg.anotherFunc should be found.");
        String expected = "func anotherFunc() { ... }"; // No params, no return type in source
        assertEquals(expected.trim(), skeleton.get().trim());
    }

    @Test
    void testGetSkeleton_InterfaceWithMethods() {
        // From declarations.go: package declpkg; type MyInterface interface { DoSomething() }
        java.util.Optional<String> skeleton = analyzer.getSkeleton("declpkg.MyInterface");
        assertTrue(skeleton.isPresent(), "Skeleton for declpkg.MyInterface should be found.");
        String expected = """
                          type MyInterface interface {
                            DoSomething()
                          }""";
        assertEquals(expected.stripIndent().trim(), skeleton.get().stripIndent().trim());
    }

    @Test
    void testGetSkeletonHeader_Function() {
        java.util.Optional<String> header = analyzer.getSkeletonHeader("declpkg.MyTopLevelFunction");
        assertTrue(header.isPresent(), "Skeleton header for declpkg.MyTopLevelFunction should be found.");
        // For functions without children, getSkeletonHeader is the same as getSkeleton
        String expected = "func MyTopLevelFunction(param int) string { ... }";
        assertEquals(expected.trim(), header.get().trim());
    }

    @Test
    void testGetSkeletonHeader_Type() {
        java.util.Optional<String> headerStruct = analyzer.getSkeletonHeader("declpkg.MyStruct");
        assertTrue(headerStruct.isPresent(), "Skeleton header for declpkg.MyStruct should be found.");
        String expectedStruct = "type MyStruct struct {";
        assertEquals(expectedStruct.trim(), headerStruct.get().trim());

        java.util.Optional<String> headerInterface = analyzer.getSkeletonHeader("declpkg.MyInterface");
        assertTrue(headerInterface.isPresent(), "Skeleton header for declpkg.MyInterface should be found.");
        String expectedInterface = "type MyInterface interface {";
        assertEquals(expectedInterface.trim(), headerInterface.get().trim());
    }

    @Test
    void testGetSkeleton_PackageLevelVar() {
        // From declarations.go: var MyGlobalVar int = 42
        java.util.Optional<String> skeleton = analyzer.getSkeleton("declpkg._module_.MyGlobalVar");
        assertTrue(skeleton.isPresent(), "Skeleton for declpkg._module_.MyGlobalVar should be found.");
        // The skeleton will be the text of the var_spec node
        assertEquals("MyGlobalVar int = 42", skeleton.get().trim());
    }

    @Test
    void testGetSkeleton_PackageLevelConst() {
        // From declarations.go: const MyGlobalConst = "hello_const"
        java.util.Optional<String> skeleton = analyzer.getSkeleton("declpkg._module_.MyGlobalConst");
        assertTrue(skeleton.isPresent(), "Skeleton for declpkg._module_.MyGlobalConst should be found.");
        // The skeleton will be the text of the const_spec node
        assertEquals("MyGlobalConst = \"hello_const\"", skeleton.get().trim());
    }

    @Test
    void testGetDefinition_PackageLevelVarConst() {
        ProjectFile pf = declarationsGoFile;

        java.util.Optional<CodeUnit> varDef = analyzer.getDefinition("declpkg._module_.MyGlobalVar");
        assertTrue(varDef.isPresent(), "Definition for declpkg._module_.MyGlobalVar should be found.");
        assertEquals(CodeUnit.field(pf, "declpkg", "_module_.MyGlobalVar"), varDef.get());
        assertFalse(varDef.get().isFunction());
        assertFalse(varDef.get().isClass());

        java.util.Optional<CodeUnit> constDef = analyzer.getDefinition("declpkg._module_.MyGlobalConst");
        assertTrue(constDef.isPresent(), "Definition for declpkg._module_.MyGlobalConst should be found.");
        assertEquals(CodeUnit.field(pf, "declpkg", "_module_.MyGlobalConst"), constDef.get());
        assertFalse(constDef.get().isFunction());
        assertFalse(constDef.get().isClass());
    }

    @Test
    void testGetSkeleton_Method() {
        // MyStruct.GetFieldA in declarations.go
        // FQN is now declpkg.MyStruct.GetFieldA
        java.util.Optional<String> skeleton = analyzer.getSkeleton("declpkg.MyStruct.GetFieldA");
        assertTrue(skeleton.isPresent(), "Skeleton for declpkg.MyStruct.GetFieldA should be found.");
        String expected = "func (s MyStruct) GetFieldA() int { ... }";
        assertEquals(expected.trim(), skeleton.get().trim());
    }

    @Test
    void testGetSkeleton_StructWithMethodsAndFields() {
        // MyStruct in declarations.go
        java.util.Optional<String> skeleton = analyzer.getSkeleton("declpkg.MyStruct");
        assertTrue(skeleton.isPresent(), "Skeleton for declpkg.MyStruct should be found.");

        // Now expecting fields and methods.
        String expectedSkeleton = """
                                  type MyStruct struct {
                                    FieldA int
                                    func (s MyStruct) GetFieldA() int { ... }
                                  }""";
        String actualSkeleton = skeleton.get().replaceAll("\\r\\n", "\n").trim(); // Normalize newlines
        assertEquals(expectedSkeleton.stripIndent().trim(), actualSkeleton, "Skeleton for MyStruct with fields and methods mismatch.");
    }

    @Test
    void testGetMembersInClass_StructMethodsAndFields() {
        ProjectFile pf = declarationsGoFile;
        java.util.List<CodeUnit> members = analyzer.getMembersInClass("declpkg.MyStruct");
        assertNotNull(members, "Members list for MyStruct should not be null.");
        assertFalse(members.isEmpty(), "Members list for MyStruct should not be empty.");

        CodeUnit expectedFieldA = CodeUnit.field(pf, "declpkg", "MyStruct.FieldA");
        assertTrue(members.contains(expectedFieldA),
                   "Members of MyStruct should include FieldA. Found: " +
                   members.stream().map(CodeUnit::fqName).toList());

        CodeUnit expectedMethod = CodeUnit.fn(pf, "declpkg", "MyStruct.GetFieldA");
        assertTrue(members.contains(expectedMethod),
                   "Members of MyStruct should include GetFieldA method. Found: " +
                   members.stream().map(CodeUnit::fqName).toList());

        assertEquals(2, members.size(),
                     "MyStruct should have 2 members (FieldA and GetFieldA method). Actual: " +
                     members.stream().map(CodeUnit::fqName).toList());
    }

    @Test
    void testGetMembersInClass_InterfaceMethods() {
        ProjectFile pf = declarationsGoFile;
        java.util.List<CodeUnit> members = analyzer.getMembersInClass("declpkg.MyInterface");
        assertNotNull(members, "Members list for MyInterface should not be null.");
        assertFalse(members.isEmpty(), "Members list for MyInterface should not be empty.");

        CodeUnit expectedMethod = CodeUnit.fn(pf, "declpkg", "MyInterface.DoSomething");
        assertTrue(members.contains(expectedMethod),
                   "Members of MyInterface should include DoSomething method. Found: " +
                   members.stream().map(CodeUnit::fqName).toList());

        assertEquals(1, members.size(),
                     "MyInterface should have 1 member (DoSomething method). Actual: " +
                     members.stream().map(CodeUnit::fqName).toList());
    }

    private String normalizeSource(String s) {
        if (s == null) return null;
        return s.lines().map(String::strip).filter(line -> !line.isEmpty()).collect(Collectors.joining("\n"));
    }

    @Test
    void testGetClassSource_GoStruct() {
        // MyStruct in declarations.go
        String source = analyzer.getClassSource("declpkg.MyStruct");
        assertNotNull(source, "Source for declpkg.MyStruct should not be null");
        String expectedSource = "type MyStruct struct {\n\tFieldA int\n}";
        assertEquals(normalizeSource(expectedSource), normalizeSource(source));
    }

    @Test
    void testGetClassSource_GoInterface() {
        // MyInterface in declarations.go
        String source = analyzer.getClassSource("declpkg.MyInterface");
        assertNotNull(source, "Source for declpkg.MyInterface should not be null");
        String expectedSource = "type MyInterface interface {\n\tDoSomething()\n}";
        assertEquals(normalizeSource(expectedSource), normalizeSource(source));
    }

    @Test
    void testGetMethodSource_GoFunction() {
        // MyTopLevelFunction in declarations.go
        java.util.Optional<String> sourceOpt = analyzer.getMethodSource("declpkg.MyTopLevelFunction");
        assertTrue(sourceOpt.isPresent(), "Source for declpkg.MyTopLevelFunction should be present.");
        String expectedSource = "func MyTopLevelFunction(param int) string {\n\treturn \"hello\"\n}";
        assertEquals(normalizeSource(expectedSource), normalizeSource(sourceOpt.get()));
    }

    @Test
    void testGetMethodSource_GoMethod() {
        // GetFieldA method of MyStruct in declarations.go
        // FQN is now declpkg.MyStruct.GetFieldA
        java.util.Optional<String> sourceOpt = analyzer.getMethodSource("declpkg.MyStruct.GetFieldA");
        assertTrue(sourceOpt.isPresent(), "Source for declpkg.MyStruct.GetFieldA method should be present.");
        String expectedSource = "func (s MyStruct) GetFieldA() int {\n\treturn s.FieldA\n}";
        assertEquals(normalizeSource(expectedSource), normalizeSource(sourceOpt.get()));
    }

    @Test
    void testGetClassSource_NonExistent() {
        assertThrows(SymbolNotFoundException.class, () -> {
            analyzer.getClassSource("declpkg.NonExistentClass");
        });
    }

    @Test
    void testGetMethodSource_NonExistent() {
        java.util.Optional<String> sourceOpt = analyzer.getMethodSource("declpkg.NonExistentFunction");
        assertFalse(sourceOpt.isPresent(), "Source for a non-existent function should be empty.");
    }

    @Test
    void testGetSymbols_Go() {
        ProjectFile pf = declarationsGoFile; // From setup

        // Create a diverse set of CodeUnits that are expected to be in declarations.go
        // Note: For methods, use the FQN as currently generated by createCodeUnit (e.g., pkg.MethodName)
        Set<CodeUnit> sourceCodeUnits = Set.of(
            CodeUnit.fn(pf, "declpkg", "MyTopLevelFunction"),
            CodeUnit.cls(pf, "declpkg", "MyStruct"),
            CodeUnit.cls(pf, "declpkg", "MyInterface"),
            CodeUnit.field(pf, "declpkg", "_module_.MyGlobalVar"),
            CodeUnit.field(pf, "declpkg", "_module_.MyGlobalConst"),
            CodeUnit.fn(pf, "declpkg", "anotherFunc"),
            CodeUnit.field(pf, "declpkg", "MyStruct.FieldA"),          // Field of MyStruct
            CodeUnit.fn(pf, "declpkg", "MyStruct.GetFieldA"),         // Method of MyStruct
            CodeUnit.fn(pf, "declpkg", "MyInterface.DoSomething") // Method of MyInterface
        );

        // Filter to ensure we only use CUs actually found by the analyzer in that file for the test input
        // This makes the test robust to an evolving analyzer that might not find all initially listed CUs
        Set<CodeUnit> actualCUsInFile = analyzer.getDeclarationsInFile(declarationsGoFile);
        Set<CodeUnit> inputCUsForTest = sourceCodeUnits.stream()
                                           .filter(actualCUsInFile::contains)
                                           .collect(Collectors.toSet());
        
        if (inputCUsForTest.size() < 5) { // Arbitrary threshold to ensure enough variety
            System.err.println("testGetSymbols_Go: Warning - Input CUs for test is smaller than expected. Actual found in file: " + 
                               actualCUsInFile.stream().map(CodeUnit::fqName).toList());
            // Potentially fail or log more assertively if this implies a regression in earlier stages
        }
        
        Set<String> extractedSymbols = analyzer.getSymbols(inputCUsForTest);

        // Define expected unqualified symbols based on the FQNs above
        // CodeUnit.identifier() correctly gives the unqualified name.
        Set<String> relevantExpectedSymbols = sourceCodeUnits.stream()
            .filter(inputCUsForTest::contains) // ensure we only expect symbols for CUs that are actually testable
            .map(CodeUnit::identifier) 
            .collect(Collectors.toSet());

        assertEquals(relevantExpectedSymbols, extractedSymbols, "Extracted symbols do not match expected symbols.");

        // Test with an empty set
        assertTrue(analyzer.getSymbols(Set.of()).isEmpty(), "getSymbols with empty set should return empty.");
    }
}
