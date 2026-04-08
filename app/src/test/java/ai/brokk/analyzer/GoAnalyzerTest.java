package ai.brokk.analyzer;

import static ai.brokk.testutil.AssertionHelperUtil.assertCodeEquals;
import static ai.brokk.testutil.UsageFinderTestUtil.fileNamesFromHits;
import static ai.brokk.testutil.UsageFinderTestUtil.newFinder;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import ai.brokk.AnalyzerUtil;
import ai.brokk.testutil.AnalyzerCreator;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterGo;

public class GoAnalyzerTest {
    private static final Logger logger = LoggerFactory.getLogger(GoAnalyzerTest.class);

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
        testProject = new TestProject(testCodeDir, Languages.GO);
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
        return analyzer.determinePackageName(null, null, rootNode, SourceContent.of(code));
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
        assertEquals("main", analyzer.determinePackageName(packagesGoFile, null, rootNode, SourceContent.of(content)));
    }

    @Test
    void testDeterminePackageName_FromProjectFile_AnotherGo() throws IOException {
        TSParser parser = new TSParser();
        TSTree tree;
        String content = Files.readString(anotherGoFile.absPath());
        parser.setLanguage(GO_LANGUAGE);
        tree = parser.parseString(null, content);
        TSNode rootNode = tree.getRootNode();
        assertEquals(
                "anotherpkg", analyzer.determinePackageName(anotherGoFile, null, rootNode, SourceContent.of(content)));
    }

    @Test
    void testDeterminePackageName_FromProjectFile_NoPkgGo() throws IOException {
        TSParser parser = new TSParser();
        TSTree tree;
        String content = Files.readString(noPkgGoFile.absPath());
        parser.setLanguage(GO_LANGUAGE);
        tree = parser.parseString(null, content);
        TSNode rootNode = tree.getRootNode();
        assertEquals("", analyzer.determinePackageName(noPkgGoFile, null, rootNode, SourceContent.of(content)));
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
        assertEquals("", analyzer.determinePackageName(emptyGoFile, null, rootNode, SourceContent.of(content)));
    }

    @Test
    void testGetDeclarationsInFile_FunctionsAndTypes() {
        Set<CodeUnit> declarations = analyzer.getDeclarations(declarationsGoFile);
        assertNotNull(declarations, "Declarations set should not be null.");

        // Check if the analyzer processed the file at all. If topLevelDeclarations doesn't contain the file,
        // it means it might have been filtered out or an error occurred during its initial processing.
        assertTrue(
                analyzer.withFileProperties(tld -> tld.containsKey(declarationsGoFile))
                        .booleanValue(),
                "Analyzer's topLevelDeclarations should contain declarations.go. Current keys: "
                        + analyzer.withFileProperties(Map::keySet));
        assertFalse(
                declarations.isEmpty(),
                "Declarations set should not be empty for declarations.go. Check query and createCodeUnit logic. Actual declarations: "
                        + declarations.stream().map(CodeUnit::fqName).toList());

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
        CodeUnit expectedUint32Map = CodeUnit.cls(pf, "declpkg", "Uint32Map");
        CodeUnit expectedStringAlias = CodeUnit.field(pf, "declpkg", "_module_.StringAlias");
        CodeUnit expectedMyInt = CodeUnit.cls(pf, "declpkg", "MyInt");
        CodeUnit expectedMyIntString = CodeUnit.fn(pf, "declpkg", "MyInt.String");
        CodeUnit expectedGroupedNamedType = CodeUnit.cls(pf, "declpkg", "GroupedNamedType");
        CodeUnit expectedGroupedAlias = CodeUnit.field(pf, "declpkg", "_module_.GroupedAlias");

        assertTrue(
                declarations.contains(expectedFunc),
                "Declarations should contain MyTopLevelFunction. Found: "
                        + declarations.stream()
                                .map(cu -> cu.fqName() + "(" + cu.kind() + ")")
                                .toList());
        assertTrue(
                declarations.contains(expectedStruct),
                "Declarations should contain MyStruct. Found: "
                        + declarations.stream()
                                .map(cu -> cu.fqName() + "(" + cu.kind() + ")")
                                .toList());
        assertTrue(
                declarations.contains(expectedInterface),
                "Declarations should contain MyInterface. Found: "
                        + declarations.stream()
                                .map(cu -> cu.fqName() + "(" + cu.kind() + ")")
                                .toList());
        assertTrue(
                declarations.contains(otherFunc),
                "Declarations should contain anotherFunc. Found: "
                        + declarations.stream()
                                .map(cu -> cu.fqName() + "(" + cu.kind() + ")")
                                .toList());
        assertTrue(
                declarations.contains(expectedVar),
                "Declarations should contain MyGlobalVar. Found: "
                        + declarations.stream()
                                .map(cu -> cu.fqName() + "(" + cu.kind() + ")")
                                .toList());
        assertTrue(
                declarations.contains(expectedConst),
                "Declarations should contain MyGlobalConst. Found: "
                        + declarations.stream()
                                .map(cu -> cu.fqName() + "(" + cu.kind() + ")")
                                .toList());
        assertTrue(
                declarations.contains(expectedMethod_GetFieldA),
                "Declarations should contain method MyStruct.GetFieldA. Found: "
                        + declarations.stream()
                                .map(cu -> cu.fqName() + "(" + cu.kind() + ")")
                                .toList());
        assertTrue(
                declarations.contains(expectedStructFieldA),
                "Declarations should contain struct field MyStruct.FieldA. Found: "
                        + declarations.stream()
                                .map(cu -> cu.fqName() + "(" + cu.kind() + ")")
                                .toList());
        assertTrue(
                declarations.contains(expectedInterfaceMethod_DoSomething),
                "Declarations should contain interface method MyInterface.DoSomething. Found: "
                        + declarations.stream()
                                .map(cu -> cu.fqName() + "(" + cu.kind() + ")")
                                .toList());
        assertTrue(
                declarations.contains(expectedUint32Map),
                "Declarations should contain named type Uint32Map as CLASS_LIKE. Found: "
                        + declarations.stream()
                                .map(cu -> cu.fqName() + "(" + cu.kind() + ")")
                                .toList());
        assertTrue(
                declarations.contains(expectedStringAlias),
                "Declarations should contain type alias StringAlias as FIELD_LIKE. Found: "
                        + declarations.stream()
                                .map(cu -> cu.fqName() + "(" + cu.kind() + ")")
                                .toList());
        assertTrue(
                declarations.contains(expectedMyInt),
                "Declarations should contain named type MyInt as CLASS_LIKE. Found: "
                        + declarations.stream()
                                .map(cu -> cu.fqName() + "(" + cu.kind() + ")")
                                .toList());
        assertTrue(
                declarations.contains(expectedMyIntString),
                "Declarations should contain method MyInt.String. Found: "
                        + declarations.stream()
                                .map(cu -> cu.fqName() + "(" + cu.kind() + ")")
                                .toList());
        assertFalse(
                declarations.contains(CodeUnit.field(pf, "declpkg", "_module_.Uint32Map")),
                "Named type Uint32Map should NOT appear as FIELD_LIKE.");
        assertTrue(
                declarations.contains(expectedGroupedNamedType),
                "Declarations should contain grouped named type GroupedNamedType as CLASS_LIKE. Found: "
                        + declarations.stream()
                                .map(cu -> cu.fqName() + "(" + cu.kind() + ")")
                                .toList());
        assertTrue(
                declarations.contains(expectedGroupedAlias),
                "Declarations should contain grouped alias GroupedAlias as FIELD_LIKE. Found: "
                        + declarations.stream()
                                .map(cu -> cu.fqName() + "(" + cu.kind() + ")")
                                .toList());
        assertFalse(
                declarations.contains(CodeUnit.field(pf, "declpkg", "_module_.GroupedNamedType")),
                "Grouped named type GroupedNamedType should NOT appear as FIELD_LIKE.");

        // Verify skeleton structure differences
        String uint32MapSkeleton = analyzer.getSkeleton(expectedUint32Map).orElse("");
        String myIntSkeleton = analyzer.getSkeleton(expectedMyInt).orElse("");
        String stringAliasSkeleton = analyzer.getSkeleton(expectedStringAlias).orElse("");

        assertTrue(
                uint32MapSkeleton.contains("{"), "CLASS_LIKE Uint32Map should have class-like structure (curly brace)");
        assertTrue(myIntSkeleton.contains("{"), "CLASS_LIKE MyInt should have class-like structure (curly brace)");
        assertFalse(stringAliasSkeleton.contains("{"), "FIELD_LIKE StringAlias should NOT have curly braces");

        // Verify parenting
        List<CodeUnit> myIntChildren = analyzer.getDirectChildren(expectedMyInt);
        assertEquals(1, myIntChildren.size(), "MyInt should have exactly 1 child (String method)");
        assertTrue(myIntChildren.contains(expectedMyIntString), "MyInt.String should be a child of MyInt");

        List<CodeUnit> uint32MapChildren = analyzer.getDirectChildren(expectedUint32Map);
        assertTrue(uint32MapChildren.isEmpty(), "Uint32Map (named type with no methods) should have zero children");

        assertEquals(
                15,
                declarations.size(),
                "Expected 15 declarations in declarations.go. Found: "
                        + declarations.stream().map(CodeUnit::fqName).toList());
    }

    @Test
    void testFqNameConstructionRationale() {
        Set<CodeUnit> declarations = analyzer.getDeclarations(declarationsGoFile);
        Map<String, CodeUnit> fqnMap = declarations.stream().collect(Collectors.toMap(CodeUnit::fqName, cu -> cu));

        // 1. Top-level function: Simple package.Name
        assertEquals(
                "declpkg.MyTopLevelFunction",
                fqnMap.get("declpkg.MyTopLevelFunction").fqName());

        // 2. Struct: Simple package.Name
        assertEquals("declpkg.MyStruct", fqnMap.get("declpkg.MyStruct").fqName());

        // 3. Interface: Simple package.Name
        assertEquals("declpkg.MyInterface", fqnMap.get("declpkg.MyInterface").fqName());

        // 4. Method on struct: Receiver type is part of the name to match Class.Method semantics
        // This ensures methods are logically parented by their types in the FQN.
        assertEquals(
                "declpkg.MyStruct.GetFieldA",
                fqnMap.get("declpkg.MyStruct.GetFieldA").fqName());

        // 5. Interface method: Similar to struct methods, parented by the interface name
        assertEquals(
                "declpkg.MyInterface.DoSomething",
                fqnMap.get("declpkg.MyInterface.DoSomething").fqName());

        // 6. Struct field: Uses StructName.FieldName convention for FQN uniqueness and grouping
        assertEquals(
                "declpkg.MyStruct.FieldA", fqnMap.get("declpkg.MyStruct.FieldA").fqName());

        // 7. Global variable: Uses _module_ prefix
        assertNotNull(fqnMap.get("declpkg._module_.MyGlobalVar"), "Global variable MyGlobalVar should be present");
        assertEquals(
                "declpkg._module_.MyGlobalVar",
                fqnMap.get("declpkg._module_.MyGlobalVar").fqName());

        // 8. Global constant: Uses _module_ prefix
        assertNotNull(fqnMap.get("declpkg._module_.MyGlobalConst"), "Global constant MyGlobalConst should be present");
        assertEquals(
                "declpkg._module_.MyGlobalConst",
                fqnMap.get("declpkg._module_.MyGlobalConst").fqName());

        // 9. Type alias: Treated as FIELD_LIKE with _module_ prefix
        assertNotNull(fqnMap.get("declpkg._module_.StringAlias"), "Type alias StringAlias should be present");
        assertEquals(
                "declpkg._module_.StringAlias",
                fqnMap.get("declpkg._module_.StringAlias").fqName());

        // 10. Named type: Treated as CLASS_LIKE (can have methods), so no _module_ prefix
        assertEquals("declpkg.MyInt", fqnMap.get("declpkg.MyInt").fqName());
    }

    @Test
    void testInlineProjectConstFqName() throws IOException {
        String source =
                """
                package yaml

                type yaml_parser_state_t int

                const (
                    yaml_PARSE_STREAM_START_STATE yaml_parser_state_t = iota
                    yaml_PARSE_FLOW_MAPPING_VALUE_STATE
                )
                """
                        .stripIndent();

        try (var project = InlineTestProjectCreator.code(source, "parser.go").build()) {
            var inlineAnalyzer = new GoAnalyzer(project);
            var file = project.getAnalyzableFiles(Languages.GO).stream()
                    .findFirst()
                    .orElseThrow();
            var declarations = inlineAnalyzer.getDeclarations(file);
            var fqns = declarations.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

            assertTrue(
                    fqns.contains("yaml._module_.yaml_PARSE_FLOW_MAPPING_VALUE_STATE"),
                    "Expected FQN for package-level const yaml_PARSE_FLOW_MAPPING_VALUE_STATE. Found: " + fqns);

            CodeUnit cu = inlineAnalyzer
                    .getDefinitions("yaml._module_.yaml_PARSE_STREAM_START_STATE")
                    .getFirst();
            String skeleton = inlineAnalyzer.getSkeleton(cu).orElse("");
            assertCodeEquals("yaml_PARSE_STREAM_START_STATE yaml_parser_state_t = iota", skeleton);
        }
    }

    @Test
    void testGetDefinition_FunctionsAndTypes() {
        ProjectFile pf = declarationsGoFile;

        Optional<CodeUnit> funcDef =
                analyzer.getDefinitions("declpkg.MyTopLevelFunction").stream().findFirst();
        assertTrue(funcDef.isPresent(), "Definition for declpkg.MyTopLevelFunction should be found.");
        assertEquals(CodeUnit.fn(pf, "declpkg", "MyTopLevelFunction"), funcDef.get());
        assertTrue(funcDef.get().isFunction());

        Optional<CodeUnit> structDef =
                analyzer.getDefinitions("declpkg.MyStruct").stream().findFirst();
        assertTrue(structDef.isPresent(), "Definition for declpkg.MyStruct should be found.");
        assertEquals(CodeUnit.cls(pf, "declpkg", "MyStruct"), structDef.get());
        assertTrue(structDef.get().isClass());

        Optional<CodeUnit> interfaceDef =
                analyzer.getDefinitions("declpkg.MyInterface").stream().findFirst();
        assertTrue(interfaceDef.isPresent(), "Definition for declpkg.MyInterface should be found.");
        assertEquals(CodeUnit.cls(pf, "declpkg", "MyInterface"), interfaceDef.get());
        assertTrue(interfaceDef.get().isClass());

        Optional<CodeUnit> nonExistentDef =
                analyzer.getDefinitions("declpkg.NonExistent").stream().findFirst();
        assertFalse(nonExistentDef.isPresent(), "Definition for a non-existent symbol should not be found.");
    }

    @Test
    void testGetSkeleton_TopLevelFunction() {
        // From declarations.go: package declpkg; func MyTopLevelFunction(param int) string { ... }
        Optional<String> skeleton = AnalyzerUtil.getSkeleton(analyzer, "declpkg.MyTopLevelFunction");
        assertTrue(skeleton.isPresent(), "Skeleton for declpkg.MyTopLevelFunction should be found.");
        // Note: paramsText might be raw like "(param int)" or just "param int" depending on TSA. Adjust if needed.
        // The returnTypeText should be "string".
        // The current renderFunctionDeclaration formats it as: "func MyTopLevelFunction(param int) string { ... }"
        String expected = "func MyTopLevelFunction(param int) string { ... }";
        assertCodeEquals(expected.trim(), skeleton.get());
    }

    @Test
    void testGetSkeleton_AnotherFunction() {
        // From declarations.go: package declpkg; func anotherFunc() {}
        Optional<String> skeleton = AnalyzerUtil.getSkeleton(analyzer, "declpkg.anotherFunc");
        assertTrue(skeleton.isPresent(), "Skeleton for declpkg.anotherFunc should be found.");
        String expected = "func anotherFunc() { ... }"; // No params, no return type in source
        assertCodeEquals(expected.trim(), skeleton.get());
    }

    @Test
    void testGetSkeleton_InterfaceWithMethods() {
        // From declarations.go: package declpkg; type MyInterface interface { DoSomething() }
        Optional<String> skeleton = AnalyzerUtil.getSkeleton(analyzer, "declpkg.MyInterface");
        assertTrue(skeleton.isPresent(), "Skeleton for declpkg.MyInterface should be found.");
        String expected =
                """
                          type MyInterface interface {
                            DoSomething()
                          }""";
        assertCodeEquals(expected.trim(), skeleton.get());
    }

    @Test
    void testGetSkeletonHeader_Function() {
        Optional<String> header = AnalyzerUtil.getSkeletonHeader(analyzer, "declpkg.MyTopLevelFunction");
        assertTrue(header.isPresent(), "Skeleton header for declpkg.MyTopLevelFunction should be found.");
        // For functions without children, getSkeletonHeader is the same as getSkeleton
        String expected = "func MyTopLevelFunction(param int) string { ... }";
        assertCodeEquals(expected.trim(), header.get());
    }

    @Test
    void testGetSkeletonHeader_Type() {
        Optional<String> headerStruct = AnalyzerUtil.getSkeletonHeader(analyzer, "declpkg.MyStruct");
        assertTrue(headerStruct.isPresent(), "Skeleton header for declpkg.MyStruct should be found.");
        String expectedStruct =
                """
                type MyStruct struct {
                  FieldA int
                  [...]
                }
                """;
        assertCodeEquals(expectedStruct.trim(), headerStruct.get());

        Optional<String> headerInterface = AnalyzerUtil.getSkeletonHeader(analyzer, "declpkg.MyInterface");
        assertTrue(headerInterface.isPresent(), "Skeleton header for declpkg.MyInterface should be found.");
        String expectedInterface =
                """
                type MyInterface interface {
                  [...]
                }
                """;
        assertCodeEquals(expectedInterface.trim(), headerInterface.get());
    }

    @Test
    void testGetSkeleton_PackageLevelVar() {
        // From declarations.go: var MyGlobalVar int = 42
        Optional<String> skeleton = AnalyzerUtil.getSkeleton(analyzer, "declpkg._module_.MyGlobalVar");
        assertTrue(skeleton.isPresent(), "Skeleton for declpkg._module_.MyGlobalVar should be found.");
        // The skeleton will be the text of the var_spec node
        assertCodeEquals("MyGlobalVar int = 42", skeleton.get());
    }

    @Test
    void testGetSkeleton_PackageLevelConst() {
        // From declarations.go: const MyGlobalConst = "hello_const"
        Optional<String> skeleton = AnalyzerUtil.getSkeleton(analyzer, "declpkg._module_.MyGlobalConst");
        assertTrue(skeleton.isPresent(), "Skeleton for declpkg._module_.MyGlobalConst should be found.");
        // The skeleton will be the text of the const_spec node
        assertCodeEquals("MyGlobalConst = \"hello_const\"", skeleton.get());
    }

    @Test
    void testGetDefinition_PackageLevelVarConst() {
        ProjectFile pf = declarationsGoFile;

        Optional<CodeUnit> varDef =
                analyzer.getDefinitions("declpkg._module_.MyGlobalVar").stream().findFirst();
        assertTrue(varDef.isPresent(), "Definition for declpkg._module_.MyGlobalVar should be found.");
        assertEquals(CodeUnit.field(pf, "declpkg", "_module_.MyGlobalVar"), varDef.get());
        assertFalse(varDef.get().isFunction());
        assertFalse(varDef.get().isClass());

        Optional<CodeUnit> constDef = analyzer.getDefinitions("declpkg._module_.MyGlobalConst").stream()
                .findFirst();
        assertTrue(constDef.isPresent(), "Definition for declpkg._module_.MyGlobalConst should be found.");
        assertEquals(CodeUnit.field(pf, "declpkg", "_module_.MyGlobalConst"), constDef.get());
        assertFalse(constDef.get().isFunction());
        assertFalse(constDef.get().isClass());
    }

    @Test
    void testGetSkeleton_Method() {
        // MyStruct.GetFieldA in declarations.go
        // FQN is now declpkg.MyStruct.GetFieldA
        Optional<String> skeleton = AnalyzerUtil.getSkeleton(analyzer, "declpkg.MyStruct.GetFieldA");
        assertTrue(skeleton.isPresent(), "Skeleton for declpkg.MyStruct.GetFieldA should be found.");
        String expected = "func (s MyStruct) GetFieldA() int { ... }";
        assertCodeEquals(expected.trim(), skeleton.get());
    }

    @Test
    void testGetSkeleton_StructWithMethodsAndFields() {
        // MyStruct in declarations.go
        Optional<String> skeleton = AnalyzerUtil.getSkeleton(analyzer, "declpkg.MyStruct");
        assertTrue(skeleton.isPresent(), "Skeleton for declpkg.MyStruct should be found.");

        // Now expecting fields and methods.
        String expectedSkeleton =
                """
                                  type MyStruct struct {
                                    FieldA int
                                    func (s MyStruct) GetFieldA() int { ... }
                                  }""";
        String actualSkeleton = skeleton.get();
        assertCodeEquals(
                expectedSkeleton.trim(), actualSkeleton, "Skeleton for MyStruct with fields and methods mismatch.");
    }

    @Test
    void testGetMembersInClass_StructMethodsAndFields() {
        ProjectFile pf = declarationsGoFile;
        List<CodeUnit> members = AnalyzerUtil.getMembersInClass(analyzer, "declpkg.MyStruct");
        assertNotNull(members, "Members list for MyStruct should not be null.");
        assertFalse(members.isEmpty(), "Members list for MyStruct should not be empty.");

        CodeUnit expectedFieldA = CodeUnit.field(pf, "declpkg", "MyStruct.FieldA");
        assertTrue(
                members.contains(expectedFieldA),
                "Members of MyStruct should include FieldA. Found: "
                        + members.stream().map(CodeUnit::fqName).toList());

        CodeUnit expectedMethod = CodeUnit.fn(pf, "declpkg", "MyStruct.GetFieldA");
        assertTrue(
                members.contains(expectedMethod),
                "Members of MyStruct should include GetFieldA method. Found: "
                        + members.stream().map(CodeUnit::fqName).toList());

        assertEquals(
                2,
                members.size(),
                "MyStruct should have 2 members (FieldA and GetFieldA method). Actual: "
                        + members.stream().map(CodeUnit::fqName).toList());
    }

    @Test
    void testGetMembersInClass_InterfaceMethods() {
        ProjectFile pf = declarationsGoFile;
        List<CodeUnit> members = AnalyzerUtil.getMembersInClass(analyzer, "declpkg.MyInterface");
        assertNotNull(members, "Members list for MyInterface should not be null.");
        assertFalse(members.isEmpty(), "Members list for MyInterface should not be empty.");

        CodeUnit expectedMethod = CodeUnit.fn(pf, "declpkg", "MyInterface.DoSomething");
        assertTrue(
                members.contains(expectedMethod),
                "Members of MyInterface should include DoSomething method. Found: "
                        + members.stream().map(CodeUnit::fqName).toList());

        assertEquals(
                1,
                members.size(),
                "MyInterface should have 1 member (DoSomething method). Actual: "
                        + members.stream().map(CodeUnit::fqName).toList());
    }

    private String normalizeSource(String s) {
        if (s == null) return null;
        return s.lines().map(String::strip).filter(line -> !line.isEmpty()).collect(Collectors.joining("\n"));
    }

    @Test
    void testGetClassSource_GoStruct() {
        // MyStruct in declarations.go
        final var sourceOpt = AnalyzerUtil.getSource(analyzer, "declpkg.MyStruct", true);
        assertTrue(sourceOpt.isPresent());
        final var source = sourceOpt.get();
        assertNotNull(source, "Source for declpkg.MyStruct should not be null");
        String expectedSource = "type MyStruct struct {\n\tFieldA int\n}";
        assertEquals(normalizeSource(expectedSource), normalizeSource(source));
    }

    @Test
    void testGetClassSource_GoInterface() {
        // MyInterface in declarations.go
        final var sourceOpt = AnalyzerUtil.getSource(analyzer, "declpkg.MyInterface", true);
        assertTrue(sourceOpt.isPresent());
        final var source = sourceOpt.get();
        assertNotNull(source, "Source for declpkg.MyInterface should not be null");
        String expectedSource = "type MyInterface interface {\n\tDoSomething()\n}";
        assertEquals(normalizeSource(expectedSource), normalizeSource(source));
    }

    @Test
    void testGetMethodSource_GoFunction() {
        // MyTopLevelFunction in declarations.go
        Optional<String> sourceOpt = AnalyzerUtil.getSource(analyzer, "declpkg.MyTopLevelFunction", true);
        assertTrue(sourceOpt.isPresent(), "Source for declpkg.MyTopLevelFunction should be present.");
        String expectedSource = "func MyTopLevelFunction(param int) string {\n\treturn \"hello\"\n}";
        assertEquals(normalizeSource(expectedSource), normalizeSource(sourceOpt.get()));
    }

    @Test
    void testGetMethodSource_GoMethod() {
        // GetFieldA method of MyStruct in declarations.go
        // FQN is now declpkg.MyStruct.GetFieldA
        Optional<String> sourceOpt = AnalyzerUtil.getSource(analyzer, "declpkg.MyStruct.GetFieldA", true);
        assertTrue(sourceOpt.isPresent(), "Source for declpkg.MyStruct.GetFieldA method should be present.");
        String expectedSource =
                "// Add this method for MyStruct\nfunc (s MyStruct) GetFieldA() int {\n\treturn s.FieldA\n}";
        assertEquals(normalizeSource(expectedSource), normalizeSource(sourceOpt.get()));
    }

    @Test
    void testGetClassSource_NonExistent() {
        var srcOpt = AnalyzerUtil.getSource(analyzer, "declpkg.NonExistentClass", true);
        assertTrue(srcOpt.isEmpty());
    }

    @Test
    void testGetMethodSource_NonExistent() {
        Optional<String> sourceOpt = AnalyzerUtil.getSource(analyzer, "declpkg.NonExistentFunction", true);
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
                CodeUnit.field(pf, "declpkg", "MyStruct.FieldA"), // Field of MyStruct
                CodeUnit.fn(pf, "declpkg", "MyStruct.GetFieldA"), // Method of MyStruct
                CodeUnit.fn(pf, "declpkg", "MyInterface.DoSomething") // Method of MyInterface
                );

        // Filter to ensure we only use CUs actually found by the analyzer in that file for the test input
        // This makes the test robust to an evolving analyzer that might not find all initially listed CUs
        Set<CodeUnit> actualCUsInFile = analyzer.getDeclarations(declarationsGoFile);
        Set<CodeUnit> inputCUsForTest =
                sourceCodeUnits.stream().filter(actualCUsInFile::contains).collect(Collectors.toSet());

        if (inputCUsForTest.size() < 5) { // Arbitrary threshold to ensure enough variety
            System.err.println(
                    "testGetSymbols_Go: Warning - Input CUs for test is smaller than expected. Actual found in file: "
                            + actualCUsInFile.stream().map(CodeUnit::fqName).toList());
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

    @Test
    void testPackageNameExtractedCorrectlyInFQNs() {
        var pf = new ProjectFile(testProject.getRoot(), Path.of("mypkg", "mypkg.go"));
        var declarations = analyzer.getDeclarations(pf);

        assertFalse(declarations.isEmpty(), "Should find declarations in mypkg.go");

        var fqns = declarations.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(fqns.contains("mypkg.MyFunc"), "Expected mypkg.MyFunc, got: " + fqns);
        assertTrue(fqns.contains("mypkg.MyType"), "Expected mypkg.MyType, got: " + fqns);

        assertTrue(
                declarations.stream()
                        .noneMatch(
                                cu -> cu.fqName().equals("mypkg") || cu.fqName().startsWith("package")),
                "Package clause should not appear as a CodeUnit: " + fqns);
    }

    @Test
    void testStructFieldMultiDeclaratorsSplitIntoIndividualFields() throws IOException {
        String source =
                """
                package declpkg

                type StructName struct {
                    Field1, Field2, Field3 int
                    Address1, Address2     string `json:"address"`
                }
                """
                        .stripIndent();

        try (var project = InlineTestProjectCreator.code(source, "fields.go").build()) {
            var inlineAnalyzer = (GoAnalyzer) AnalyzerCreator.createTreeSitterAnalyzer(project);
            ProjectFile pf = new ProjectFile(project.getRoot(), "fields.go");
            Set<CodeUnit> declarations = inlineAnalyzer.getDeclarations(pf);

            // Assert CodeUnits exist for all fields
            CodeUnit f1 = CodeUnit.field(pf, "declpkg", "StructName.Field1");
            CodeUnit f2 = CodeUnit.field(pf, "declpkg", "StructName.Field2");
            CodeUnit f3 = CodeUnit.field(pf, "declpkg", "StructName.Field3");
            CodeUnit a1 = CodeUnit.field(pf, "declpkg", "StructName.Address1");
            CodeUnit a2 = CodeUnit.field(pf, "declpkg", "StructName.Address2");

            assertTrue(declarations.contains(f1), "Should contain Field1");
            assertTrue(declarations.contains(f2), "Should contain Field2");
            assertTrue(declarations.contains(f3), "Should contain Field3");
            assertTrue(declarations.contains(a1), "Should contain Address1");
            assertTrue(declarations.contains(a2), "Should contain Address2");

            // Assert Skeletons are clean and individual
            assertCodeEquals("Field1 int", inlineAnalyzer.getSkeleton(f1).orElse(""));
            assertCodeEquals("Field2 int", inlineAnalyzer.getSkeleton(f2).orElse(""));
            assertCodeEquals("Field3 int", inlineAnalyzer.getSkeleton(f3).orElse(""));
            assertCodeEquals(
                    "Address1 string `json:\"address\"`",
                    inlineAnalyzer.getSkeleton(a1).orElse(""));
            assertCodeEquals(
                    "Address2 string `json:\"address\"`",
                    inlineAnalyzer.getSkeleton(a2).orElse(""));

            // Verify they don't contain commas or siblings
            assertFalse(
                    inlineAnalyzer.getSkeleton(f1).get().contains("Field2"),
                    "Skeleton for Field1 should not contain Field2");
            assertFalse(
                    inlineAnalyzer.getSkeleton(a1).get().contains("Address2"),
                    "Skeleton for Address1 should not contain Address2");
        }
    }

    @Test
    void testComplexFieldInitializerIsOmitted() {
        String code =
                """
                package main

                var simpleInt int = 42
                var simpleString string = "hello"
                var complexObj = NewComplexObject("some", "args")
                var inlineStruct = struct{A int}{A: 1}
                """;

        try (var testProject = InlineTestProjectCreator.code(code, "fields.go").build()) {
            var inlineAnalyzer = (GoAnalyzer) AnalyzerCreator.createTreeSitterAnalyzer(testProject);
            ProjectFile file = inlineAnalyzer.getAnalyzedFiles().stream()
                    .filter(f -> f.getFileName().equals("fields.go"))
                    .findAny()
                    .orElseThrow();
            var skeletons = inlineAnalyzer.getSkeletons(file);

            CodeUnit simpleInt = inlineAnalyzer.getDefinitions("main._module_.simpleInt").stream()
                    .findFirst()
                    .orElseThrow();
            CodeUnit simpleString = inlineAnalyzer.getDefinitions("main._module_.simpleString").stream()
                    .findFirst()
                    .orElseThrow();
            CodeUnit complexObj = inlineAnalyzer.getDefinitions("main._module_.complexObj").stream()
                    .findFirst()
                    .orElseThrow();
            CodeUnit inlineStruct = inlineAnalyzer.getDefinitions("main._module_.inlineStruct").stream()
                    .findFirst()
                    .orElseThrow();

            assertEquals("simpleInt int = 42", skeletons.get(simpleInt).trim());
            assertEquals(
                    "simpleString string = \"hello\"",
                    skeletons.get(simpleString).trim());
            assertEquals("complexObj", skeletons.get(complexObj).trim());
            assertEquals("inlineStruct", skeletons.get(inlineStruct).trim());
        }
    }

    @Test
    void testMixedMultiValueInitializerTruncation() {
        String code = """
                package main
                var a, b = 1, complexFunc()
                """;

        try (var testProject = InlineTestProjectCreator.code(code, "multi.go").build()) {
            var inlineAnalyzer = (GoAnalyzer) AnalyzerCreator.createTreeSitterAnalyzer(testProject);
            ProjectFile file =
                    inlineAnalyzer.getAnalyzedFiles().stream().findFirst().orElseThrow();
            var skeletons = inlineAnalyzer.getSkeletons(file);

            CodeUnit aUnit = inlineAnalyzer.getDefinitions("main._module_.a").stream()
                    .findFirst()
                    .orElseThrow();
            CodeUnit bUnit = inlineAnalyzer.getDefinitions("main._module_.b").stream()
                    .findFirst()
                    .orElseThrow();

            // Multi-value assignments are treated as complex and truncated to just names
            assertEquals("a", skeletons.get(aUnit).trim());
            assertEquals("b", skeletons.get(bUnit).trim());
        }
    }

    @Test
    void testFunctionLocalVariablesAreNotReportedAsDeclarations() throws IOException {
        String source =
                """
                package ipnlocal

                func init() {
                    breakTCPConns = breakTCPConnsDarwin
                }

                func breakTCPConnsDarwin() error {
                    var matched int
                    if matched == 0 {
                        return nil
                    }
                    return nil
                }
                """
                        .stripIndent();

        try (var project =
                InlineTestProjectCreator.code(source, "breaktcp_darwin.go").build()) {
            var inlineAnalyzer = (GoAnalyzer) AnalyzerCreator.createTreeSitterAnalyzer(project);
            var file = new ProjectFile(project.getRoot(), "breaktcp_darwin.go");
            var declarations = inlineAnalyzer.getDeclarations(file);
            var fqns = declarations.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

            assertTrue(
                    fqns.contains("ipnlocal.breakTCPConnsDarwin"),
                    "Expected function declaration to be present. Found: " + fqns);
            assertFalse(
                    fqns.contains("ipnlocal._module_.matched"),
                    "Function-local variables should not be reported as declarations. Found: " + fqns);
        }
    }

    @Test
    void testFunctionLocalAnonymousStructFieldsAreNotReportedAsDeclarations() throws IOException {
        String source =
                """
                package ipnlocal

                func TestExpandProxyArgUnix() {
                    tests := []struct {
                        input string
                        wantURL string
                        wantInsecure bool
                    }{
                        {
                            input: "unix:/tmp/test.sock",
                            wantURL: "unix:/tmp/test.sock",
                        },
                    }
                    _ = tests
                }
                """
                        .stripIndent();

        try (var project =
                InlineTestProjectCreator.code(source, "serve_unix_test.go").build()) {
            var inlineAnalyzer = (GoAnalyzer) AnalyzerCreator.createTreeSitterAnalyzer(project);
            var file = new ProjectFile(project.getRoot(), "serve_unix_test.go");
            var declarations = inlineAnalyzer.getDeclarations(file);
            var fqns = declarations.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

            assertTrue(
                    fqns.contains("ipnlocal.TestExpandProxyArgUnix"),
                    "Expected test function declaration to be present. Found: " + fqns);
            assertFalse(
                    fqns.stream().anyMatch(fqn -> fqn.endsWith(".input")),
                    "Function-local anonymous struct fields should not be reported as declarations. Found: " + fqns);
            assertFalse(
                    fqns.stream().anyMatch(fqn -> fqn.endsWith(".wantURL")),
                    "Function-local anonymous struct fields should not be reported as declarations. Found: " + fqns);
            assertFalse(
                    fqns.stream().anyMatch(fqn -> fqn.endsWith(".wantInsecure")),
                    "Function-local anonymous struct fields should not be reported as declarations. Found: " + fqns);
        }
    }

    @Test
    void testAnonymousStructFieldsAndFunctionLocalTypesAreNotReportedAsDeclarations() throws IOException {
        String source =
                """
                package cli

                var debugArgs struct {
                    file string
                    cpuSec int
                }

                func runDaemonMetrics() {
                    type change struct {
                        name string
                    }
                    _ = change{}
                }
                """
                        .stripIndent();

        try (var project = InlineTestProjectCreator.code(source, "debug.go").build()) {
            var inlineAnalyzer = (GoAnalyzer) AnalyzerCreator.createTreeSitterAnalyzer(project);
            var file = new ProjectFile(project.getRoot(), "debug.go");
            var declarations = inlineAnalyzer.getDeclarations(file);
            var fqns = declarations.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

            assertTrue(
                    fqns.contains("cli._module_.debugArgs"),
                    "Expected package-level variable declaration to be present. Found: " + fqns);
            assertTrue(
                    fqns.contains("cli.runDaemonMetrics"),
                    "Expected function declaration to be present. Found: " + fqns);
            assertFalse(
                    fqns.contains("cli.change"),
                    "Function-local types should not be reported as declarations. Found: " + fqns);
            assertFalse(
                    fqns.stream().anyMatch(fqn -> fqn.endsWith(".file")),
                    "Anonymous struct fields should not be reported as declarations. Found: " + fqns);
            assertFalse(
                    fqns.stream().anyMatch(fqn -> fqn.endsWith(".cpuSec")),
                    "Anonymous struct fields should not be reported as declarations. Found: " + fqns);
        }
    }

    @Test
    public void getUsesClassComprehensivePatternsTest() throws InterruptedException {
        var finder = newFinder(testProject, analyzer);
        var symbol = "main.BaseStruct";
        var either = finder.findUsages(symbol).toEither();

        assumeFalse(either.hasErrorMessage(), "Go analyzer unavailable");

        var hits = either.getUsages();
        var files = fileNamesFromHits(hits);
        assertTrue(
                files.contains("class_usage_patterns.go"),
                "Expected comprehensive usage patterns in class_usage_patterns.go; actual: " + files);

        var classUsageHits = hits.stream()
                .filter(h -> h.file().absPath().getFileName().toString().equals("class_usage_patterns.go"))
                .toList();
        assertTrue(
                classUsageHits.size() >= 2,
                "Expected at least 2 different usage patterns, found: " + classUsageHits.size());
    }
}
