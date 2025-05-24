package io.github.jbellis.brokk.analyzer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;


public final class JavascriptAnalyzerTest {
    private static TestProject jsTestProject;
    private static JavascriptAnalyzer jsAnalyzer;
    private static ProjectFile helloJsFile;
    private static ProjectFile helloJsxFile;
    private static ProjectFile varsJsFile;

    /** Creates a TestProject rooted under src/test/resources/{subDir}. */
    static TestProject createTestProject(String subDir, io.github.jbellis.brokk.analyzer.Language lang) { // Use Brokk's Language enum
        Path testDir = Path.of("src/test/resources", subDir);
        assertTrue(Files.exists(testDir), "Test resource dir missing: " + testDir);
        assertTrue(Files.isDirectory(testDir), testDir + " is not a directory");
        return new TestProject(testDir.toAbsolutePath(), lang);
    }

    @BeforeAll
    static void setup() {
        jsTestProject = createTestProject("testcode-js", Language.JAVASCRIPT);
        jsAnalyzer = new JavascriptAnalyzer(jsTestProject);
        assertFalse(jsAnalyzer.isEmpty(), "Analyzer should have processed JS/JSX files");

        helloJsFile = new ProjectFile(jsTestProject.getRoot(), "Hello.js");
        helloJsxFile = new ProjectFile(jsTestProject.getRoot(), "Hello.jsx");
        varsJsFile = new ProjectFile(jsTestProject.getRoot(), "Vars.js");
    }

    /* -------------------- JavaScript / JSX -------------------- */

    @Test
    void testJavascriptJsxSkeletons() {
        assertFalse(jsAnalyzer.isEmpty(), "Analyzer should have processed JS/JSX files");

        var skelJsx = jsAnalyzer.getSkeletons(helloJsxFile);
        assertFalse(skelJsx.isEmpty(), "Skeletons map for Hello.jsx should not be empty. Found: " + skelJsx.keySet().stream().map(CodeUnit::fqName).collect(Collectors.joining(", ")));

        var jsxClass = CodeUnit.cls(helloJsxFile, "", "JsxClass");
        var jsxArrowFn = CodeUnit.fn(helloJsxFile, "", "JsxArrowFnComponent");
        var localJsxArrowFn = CodeUnit.fn(helloJsxFile, "", "LocalJsxArrowFn");
        var plainJsxFunc = CodeUnit.fn(helloJsxFile, "", "PlainJsxFunc");

        assertTrue(skelJsx.containsKey(jsxClass), "Skeleton map should contain JsxClass. Skeletons: " + skelJsx.keySet());
        assertTrue(skelJsx.containsKey(jsxArrowFn), "Skeleton map should contain JsxArrowFnComponent. Skeletons: " + skelJsx.keySet());
        assertTrue(skelJsx.containsKey(localJsxArrowFn), "Skeleton map should contain LocalJsxArrowFn. Skeletons: " + skelJsx.keySet());
        assertTrue(skelJsx.containsKey(plainJsxFunc), "Skeleton map should contain PlainJsxFunc. Skeletons: " + skelJsx.keySet());

        String expectedJsxClassSkeleton = """
        export class JsxClass {
          function render(): JSX.Element ...
        }
        """.stripIndent();
        assertEquals(expectedJsxClassSkeleton.trim(), skelJsx.get(jsxClass).trim(), "JsxClass skeleton mismatch in Hello.jsx.");


        String expectedExportedArrowFnSkeleton = """
        export JsxArrowFnComponent({ name }): JSX.Element => ...
        """.stripIndent();
        assertEquals(expectedExportedArrowFnSkeleton.trim(), skelJsx.get(jsxArrowFn).trim(), "JsxArrowFnComponent skeleton mismatch");

        String expectedLocalArrowFnSkeleton = """
        LocalJsxArrowFn() => ...
        """.stripIndent();
        assertEquals(expectedLocalArrowFnSkeleton.trim(), skelJsx.get(localJsxArrowFn).trim(), "LocalJsxArrowFn skeleton mismatch");

        String expectedPlainJsxFuncSkeleton = """
        function PlainJsxFunc() ...
        """.stripIndent();
        assertEquals(expectedPlainJsxFuncSkeleton.trim(), skelJsx.get(plainJsxFunc).trim(), "PlainJsxFunc skeleton mismatch");

        Set<CodeUnit> declarationsInJsx = jsAnalyzer.getDeclarationsInFile(helloJsxFile);
        assertTrue(declarationsInJsx.contains(jsxClass), "getDeclarationsInFile mismatch for Hello.jsx: missing jsxClass. Found: " + declarationsInJsx);
        assertTrue(declarationsInJsx.contains(jsxArrowFn), "getDeclarationsInFile mismatch for Hello.jsx: missing jsxArrowFn. Found: " + declarationsInJsx);
        // Add other expected CUs like localJsxArrowFn, plainJsxFunc, and JsxClass.render
        assertTrue(declarationsInJsx.contains(localJsxArrowFn), "getDeclarationsInFile mismatch for Hello.jsx: missing localJsxArrowFn. Found: " + declarationsInJsx);
        assertTrue(declarationsInJsx.contains(plainJsxFunc), "getDeclarationsInFile mismatch for Hello.jsx: missing plainJsxFunc. Found: " + declarationsInJsx);
        assertTrue(declarationsInJsx.contains(CodeUnit.fn(helloJsxFile, "", "JsxClass.render")), "getDeclarationsInFile mismatch for Hello.jsx: missing JsxClass.render. Found: " + declarationsInJsx);

        assertEquals(expectedExportedArrowFnSkeleton.trim(), jsAnalyzer.getSkeleton(jsxArrowFn.fqName()).get().trim(), "getSkeleton mismatch for JsxArrowFnComponent FQ name");

        var skelJs = jsAnalyzer.getSkeletons(helloJsFile);
        assertFalse(skelJs.isEmpty(), "Skeletons map for Hello.js should not be empty.");

        var helloClass = CodeUnit.cls(helloJsFile, "", "Hello");
        var utilFunc = CodeUnit.fn(helloJsFile, "", "util");

        assertTrue(skelJs.containsKey(helloClass), "Skeleton map should contain Hello class from Hello.js");
        assertTrue(skelJs.containsKey(utilFunc), "Skeleton map should contain util function from Hello.js");

        String expectedHelloClassSkeleton = """
        export class Hello {
          function greet() ...
        }
        """.stripIndent();
        assertEquals(expectedHelloClassSkeleton.trim(), skelJs.get(helloClass).trim(), "Hello class skeleton mismatch in Hello.js.");


        String expectedUtilFuncSkeleton = """
        export function util() ...
        """.stripIndent();
        assertEquals(expectedUtilFuncSkeleton.trim(), skelJs.get(utilFunc).trim());
    }

    @Test
    void testJavascriptGetSymbols() {
        assertFalse(jsAnalyzer.isEmpty(), "Analyzer should not be empty after processing JS files");

        // Define CodeUnits relevant to the test
        // These must match the CUs created by the analyzer internally for the maps to be hit correctly.
        CodeUnit helloClass = CodeUnit.cls(helloJsFile, "", "Hello"); // From Hello.js
        CodeUnit jsxArrowFn = CodeUnit.fn(helloJsxFile, "", "JsxArrowFnComponent"); // From Hello.jsx
        CodeUnit topConstJs = CodeUnit.field(varsJsFile, "", "Vars.js.TOP_CONST_JS"); // From Vars.js

        Set<CodeUnit> sources = Set.of(helloClass, jsxArrowFn, topConstJs);
        Set<String> actualSymbols = jsAnalyzer.getSymbols(sources);

        // Expected symbols:
        // "Hello" from helloClass itself
        // "greet" from the greet() method, which is a child of helloClass
        // "JsxArrowFnComponent" from jsxArrowFn itself
        // "TOP_CONST_JS" from topConstJs itself (after stripping _module_.)
        Set<String> expectedSymbols = Set.of(
                "Hello",
                "greet",
                "JsxArrowFnComponent",
                "TOP_CONST_JS"
        );
        assertEquals(expectedSymbols, actualSymbols, "Symbols mismatch for combined sources.");

        // Test with an empty set of sources
        Set<String> emptySymbols = jsAnalyzer.getSymbols(Set.of());
        assertTrue(emptySymbols.isEmpty(), "getSymbols with empty sources should return an empty set.");

        // Test with a single top-level function CU
        CodeUnit utilFunc = CodeUnit.fn(helloJsFile, "", "util"); // from Hello.js
        Set<String> utilSymbols = jsAnalyzer.getSymbols(Set.of(utilFunc));
        assertEquals(Set.of("util"), utilSymbols, "Symbols mismatch for util function.");

        // Test with a single class CU from a JSX file (should include its methods)
        CodeUnit jsxClass = CodeUnit.cls(helloJsxFile, "", "JsxClass");
        // Expected: "JsxClass" from the class itself, "render" from its method
        Set<String> jsxClassSymbols = jsAnalyzer.getSymbols(Set.of(jsxClass));
        assertEquals(Set.of("JsxClass", "render"), jsxClassSymbols, "Symbols mismatch for JsxClass.");

        // Test with a non-exported top-level variable
        CodeUnit localVarJs = CodeUnit.field(varsJsFile, "", "Vars.js.localVarJs");
        Set<String> localVarSymbols = jsAnalyzer.getSymbols(Set.of(localVarJs));
        assertEquals(Set.of("localVarJs"), localVarSymbols, "Symbols mismatch for localVarJs.");

        // Test with multiple sources from the same file
        CodeUnit plainJsxFunc = CodeUnit.fn(helloJsxFile, "", "PlainJsxFunc");
        Set<CodeUnit> jsxSources = Set.of(jsxClass, plainJsxFunc);
        Set<String> jsxCombinedSymbols = jsAnalyzer.getSymbols(jsxSources);
        assertEquals(Set.of("JsxClass", "render", "PlainJsxFunc"), jsxCombinedSymbols, "Symbols mismatch for combined JSX sources.");
    }

    @Test
    void testJsxFeaturesSkeletons() {
        ProjectFile featuresFile = new ProjectFile(jsTestProject.getRoot(), "FeaturesTest.jsx");
        var skeletons = jsAnalyzer.getSkeletons(featuresFile);
        assertFalse(skeletons.isEmpty(), "Skeletons map for FeaturesTest.jsx should not be empty.");

        // Module CU for imports
        CodeUnit moduleCU = CodeUnit.module(featuresFile, "", "FeaturesTest.jsx");
        assertTrue(skeletons.containsKey(moduleCU), "Skeletons map should contain module CU for imports. Found: " + skeletons.keySet());
        String expectedImports = """
        import React, { useState } from 'react';
        import { Something, AnotherThing as AT } from './another-module';
        import * as AllThings from './all-the-things';
        import DefaultThing from './default-thing';
        """.stripIndent();
        assertEquals(expectedImports.trim(), skeletons.get(moduleCU).trim(), "Module imports skeleton mismatch.");

        // MyExportedComponent: JSX inference + mutations
        CodeUnit mecCU = CodeUnit.fn(featuresFile, "", "MyExportedComponent");
        assertTrue(skeletons.containsKey(mecCU), "Skeleton for MyExportedComponent missing.");
        String expectedMecSkeleton = """
        // mutates: counter, wasUpdated
        export function MyExportedComponent(props): JSX.Element ...
        """.stripIndent();
        assertEquals(expectedMecSkeleton.trim(), skeletons.get(mecCU).trim(), "MyExportedComponent skeleton mismatch.");

        // MyExportedArrowComponent: JSX inference (arrow) + mutation
        CodeUnit meacCU = CodeUnit.fn(featuresFile, "", "MyExportedArrowComponent");
        assertTrue(skeletons.containsKey(meacCU), "Skeleton for MyExportedArrowComponent missing.");
        String expectedMeacSkeleton = """
        // mutates: localStatus
        export MyExportedArrowComponent({ id }): JSX.Element => ...
        """.stripIndent();
        assertEquals(expectedMeacSkeleton.trim(), skeletons.get(meacCU).trim(), "MyExportedArrowComponent skeleton mismatch.");

        // internalProcessingUtil: No JSX inference (local) + mutation
        CodeUnit ipuCU = CodeUnit.fn(featuresFile, "", "internalProcessingUtil");
        assertTrue(skeletons.containsKey(ipuCU), "Skeleton for internalProcessingUtil missing.");
        String expectedIpuSkeleton = """
        // mutates: isValid
        function internalProcessingUtil(dataObject) ...
        """.stripIndent();
        assertEquals(expectedIpuSkeleton.trim(), skeletons.get(ipuCU).trim(), "internalProcessingUtil skeleton mismatch.");

        // updateGlobalConfig: No JSX inference (lowercase) + mutation
        CodeUnit ugcCU = CodeUnit.fn(featuresFile, "", "updateGlobalConfig");
        assertTrue(skeletons.containsKey(ugcCU), "Skeleton for updateGlobalConfig missing.");
        String expectedUgcSkeleton = """
        // mutates: global_config_val
        export function updateGlobalConfig(newVal) ...
        """.stripIndent();
        assertEquals(expectedUgcSkeleton.trim(), skeletons.get(ugcCU).trim(), "updateGlobalConfig skeleton mismatch.");

        // ComponentWithComment: JSX inference (despite comment)
        CodeUnit cwcCU = CodeUnit.fn(featuresFile, "", "ComponentWithComment");
        assertTrue(skeletons.containsKey(cwcCU), "Skeleton for ComponentWithComment missing.");
        String expectedCwcSkeleton = """
        export function ComponentWithComment(user /*: UserType */): JSX.Element ...
        """.stripIndent(); // Mutations comment should not appear if no mutations
        assertEquals(expectedCwcSkeleton.trim(), skeletons.get(cwcCU).trim(), "ComponentWithComment skeleton mismatch.");

        // modifyUser: Mutations, no JSX
        CodeUnit muCU = CodeUnit.fn(featuresFile, "", "modifyUser");
        assertTrue(skeletons.containsKey(muCU), "Skeleton for modifyUser missing.");
        String expectedMuSkeleton = """
        // mutates: age, name
        export function modifyUser(user) ...
        """.stripIndent();
        assertEquals(expectedMuSkeleton.trim(), skeletons.get(muCU).trim(), "modifyUser skeleton mismatch.");

        // Verify getSkeleton for one of the CUs
        Optional<String> mecSkeletonOpt = jsAnalyzer.getSkeleton(mecCU.fqName());
        assertTrue(mecSkeletonOpt.isPresent(), "getSkeleton should find MyExportedComponent by FQ name.");
        assertEquals(expectedMecSkeleton.trim(), mecSkeletonOpt.get().trim(), "getSkeleton for MyExportedComponent FQ name mismatch.");
    }

    @Test
    void testJavascriptTopLevelVariables() {
        var skelVars = jsAnalyzer.getSkeletons(varsJsFile);

        assertFalse(skelVars.isEmpty(), "Skeletons map for Vars.js should not be empty. Found: " + skelVars.keySet());

        CodeUnit topConstJsCU = CodeUnit.field(varsJsFile, "", "Vars.js.TOP_CONST_JS");
        CodeUnit localVarJsCU = CodeUnit.field(varsJsFile, "", "Vars.js.localVarJs");

        assertTrue(skelVars.containsKey(topConstJsCU), "Skeletons map should contain Vars.js.TOP_CONST_JS. Found: " + skelVars.keySet());
        assertEquals("export const TOP_CONST_JS = 123", skelVars.get(topConstJsCU).strip());

        assertTrue(skelVars.containsKey(localVarJsCU), "Skeletons map should contain Vars.js.localVarJs. Found: " + skelVars.keySet());
        assertEquals("let localVarJs = \"abc\"", skelVars.get(localVarJsCU).strip());

        // Ensure these are not mistaken for classes
        Set<CodeUnit> declarationsInVarsJs = jsAnalyzer.getDeclarationsInFile(varsJsFile);
        assertTrue(declarationsInVarsJs.contains(topConstJsCU), "Vars.js.TOP_CONST_JS should be in declarations list for Vars.js. Found: " + declarationsInVarsJs);
        assertFalse(topConstJsCU.isClass(), "Vars.js.TOP_CONST_JS CU should not be a class.");
        assertTrue(declarationsInVarsJs.contains(localVarJsCU), "Vars.js.localVarJs should be in declarations list for Vars.js. Found: " + declarationsInVarsJs);
        assertFalse(localVarJsCU.isClass(), "Vars.js.localVarJs CU should not be a class.");
    }

    @Test
    void testUsagePageImports() {
        ProjectFile usagePageFile = new ProjectFile(jsTestProject.getRoot(), "UsagePage.jsx");
        var skeletons = jsAnalyzer.getSkeletons(usagePageFile);
        assertFalse(skeletons.isEmpty(), "Skeletons map for UsagePage.jsx should not be empty.");

        // Determine the package name. Since UsagePage.jsx is in the root of "testcode-js",
        // the package name should be empty according to JavascriptAnalyzer.determinePackageName.
        String expectedPackageName = ""; // Root files have an empty package name.
        CodeUnit moduleCU = CodeUnit.module(usagePageFile, expectedPackageName, "UsagePage.jsx");

        assertTrue(skeletons.containsKey(moduleCU), "Skeletons map should contain module CU for imports in UsagePage.jsx. Found: " + skeletons.keySet());

        String importSkeleton = skeletons.get(moduleCU);
        assertNotNull(importSkeleton, "Import skeleton for UsagePage.jsx should not be null.");

        long importCount = importSkeleton.lines().filter(line -> !line.isBlank()).count();
        // UsagePage.jsx has 10 import statements, which span 44 actual lines of text.
        assertEquals(44, importCount, "UsagePage.jsx import skeleton should have 44 non-blank lines.");
    }

    // Tests from TreeSitterAnalyzerMiscTest
    @Test
    void testIsCpg() {
        assertFalse(jsAnalyzer.isCpg(), "TreeSitterAnalyzer instances should not be CPGs.");
    }

    @Test
    void testGetSkeletonHeader() {
        // Test case 1: Class in JSX
        Optional<String> jsxClassHeader = jsAnalyzer.getSkeletonHeader("JsxClass");
        assertTrue(jsxClassHeader.isPresent(), "Skeleton header for JsxClass should be defined.");
        assertEquals("export class JsxClass {", jsxClassHeader.get().trim());

        // Test case 2: Arrow function component in JSX
        Optional<String> jsxArrowFnHeader = jsAnalyzer.getSkeletonHeader("JsxArrowFnComponent");
        assertTrue(jsxArrowFnHeader.isPresent(), "Skeleton header for JsxArrowFnComponent should be defined.");
        assertEquals("export JsxArrowFnComponent({ name }): JSX.Element => ...", jsxArrowFnHeader.get().trim());
        
        // Test case 3: Regular function in JS
        Optional<String> utilFuncHeader = jsAnalyzer.getSkeletonHeader("util"); // From Hello.js
        assertTrue(utilFuncHeader.isPresent(), "Skeleton header for util function should be defined.");
        assertEquals("export function util() ...", utilFuncHeader.get().trim());

        // Test case 4: Non-existent FQ name
        Optional<String> nonExistentHeader = jsAnalyzer.getSkeletonHeader("NonExistentSymbol");
        assertTrue(nonExistentHeader.isEmpty(), "Skeleton header for a non-existent symbol should be empty.");
    }

    @Test
    void testGetMembersInClass() {
        // Test case 1: JsxClass in Hello.jsx
        List<CodeUnit> jsxClassMembers = jsAnalyzer.getMembersInClass("JsxClass");
        assertFalse(jsxClassMembers.isEmpty(), "JsxClass should have members.");
        CodeUnit renderMethod = CodeUnit.fn(helloJsxFile, "", "JsxClass.render");
        assertTrue(jsxClassMembers.contains(renderMethod), "JsxClass members should include render method.");
        assertEquals(1, jsxClassMembers.size(), "JsxClass should have 1 member (render).");

        // Test case 2: Hello class in Hello.js
        List<CodeUnit> helloClassMembers = jsAnalyzer.getMembersInClass("Hello");
        assertFalse(helloClassMembers.isEmpty(), "Hello class should have members.");
        CodeUnit greetMethod = CodeUnit.fn(helloJsFile, "", "Hello.greet");
        assertTrue(helloClassMembers.contains(greetMethod), "Hello class members should include greet method.");
        assertEquals(1, helloClassMembers.size(), "Hello class should have 1 member (greet).");

        // Test case 3: Non-class FQ name (e.g., a function)
        List<CodeUnit> functionMembers = jsAnalyzer.getMembersInClass("util"); // 'util' is a function
        assertTrue(functionMembers.isEmpty(), "A function should not have members in this context.");

        // Test case 4: Non-existent FQ name
        List<CodeUnit> nonExistentMembers = jsAnalyzer.getMembersInClass("NonExistentClass");
        assertTrue(nonExistentMembers.isEmpty(), "A non-existent class should have no members.");
    }

    @Test
    void testGetFileFor() {
        // Test case 1: Function in Hello.js
        Optional<ProjectFile> utilFile = jsAnalyzer.getFileFor("util");
        assertTrue(utilFile.isPresent(), "File for 'util' function should be found.");
        assertEquals(helloJsFile, utilFile.get());

        // Test case 2: Class in Hello.jsx
        Optional<ProjectFile> jsxClassFile = jsAnalyzer.getFileFor("JsxClass");
        assertTrue(jsxClassFile.isPresent(), "File for 'JsxClass' should be found.");
        assertEquals(helloJsxFile, jsxClassFile.get());

        // Test case 3: Method in Hello.jsx (JsxClass.render)
        Optional<ProjectFile> renderMethodFile = jsAnalyzer.getFileFor("JsxClass.render");
        assertTrue(renderMethodFile.isPresent(), "File for 'JsxClass.render' method should be found.");
        assertEquals(helloJsxFile, renderMethodFile.get());
        
        // Test case 4: Top-level variable in Vars.js
        Optional<ProjectFile> topConstJsFile = jsAnalyzer.getFileFor("Vars.js.TOP_CONST_JS");
        assertTrue(topConstJsFile.isPresent(), "File for 'Vars.js.TOP_CONST_JS' should be found.");
        assertEquals(varsJsFile, topConstJsFile.get());

        // Test case 5: Non-existent FQ name
        Optional<ProjectFile> nonExistentFile = jsAnalyzer.getFileFor("NonExistentSymbol");
        assertTrue(nonExistentFile.isEmpty(), "File for a non-existent symbol should not be found.");
    }

    @Test
    void testGetDefinition() {
        // Test case 1: Arrow function component in Hello.jsx
        Optional<CodeUnit> jsxArrowFnDef = jsAnalyzer.getDefinition("JsxArrowFnComponent");
        assertTrue(jsxArrowFnDef.isPresent(), "Definition for 'JsxArrowFnComponent' should be found.");
        assertEquals("JsxArrowFnComponent", jsxArrowFnDef.get().fqName());
        assertEquals(helloJsxFile, jsxArrowFnDef.get().source());

        // Test case 2: Method in Hello.js
        Optional<CodeUnit> greetMethodDef = jsAnalyzer.getDefinition("Hello.greet");
        assertTrue(greetMethodDef.isPresent(), "Definition for 'Hello.greet' method should be found.");
        assertEquals("Hello.greet", greetMethodDef.get().fqName());
        assertEquals(helloJsFile, greetMethodDef.get().source());
        
        // Test case 3: Top-level variable in Vars.js
        Optional<CodeUnit> topConstJsDef = jsAnalyzer.getDefinition("Vars.js.TOP_CONST_JS");
        assertTrue(topConstJsDef.isPresent(), "Definition for 'Vars.js.TOP_CONST_JS' should be found.");
        assertEquals("Vars.js.TOP_CONST_JS", topConstJsDef.get().fqName());
        assertEquals(varsJsFile, topConstJsDef.get().source());

        // Test case 4: Non-existent FQ name
        Optional<CodeUnit> nonExistentDef = jsAnalyzer.getDefinition("NonExistentSymbol");
        assertTrue(nonExistentDef.isEmpty(), "Definition for a non-existent symbol should not be found.");
    }

    @Test
    void testSearchDefinitions() {
        // Test case 1: Search for "Jsx"
        List<CodeUnit> jsxSymbols = jsAnalyzer.searchDefinitions("Jsx");
        assertFalse(jsxSymbols.isEmpty(), "Should find symbols containing 'Jsx'.");
        Set<String> jsxFqNames = jsxSymbols.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(jsxFqNames.contains("JsxClass"));
        assertTrue(jsxFqNames.contains("JsxClass.render")); // Member of JsxClass
        assertTrue(jsxFqNames.contains("JsxArrowFnComponent"));
        assertTrue(jsxFqNames.contains("LocalJsxArrowFn")); // Non-exported local arrow function
        assertTrue(jsxFqNames.contains("PlainJsxFunc"));    // Non-exported plain function
        assertEquals(5, jsxSymbols.size(), "Expected 5 symbols containing 'Jsx'.");

        // Test case 2: Search for "Hello" (matches class name)
        List<CodeUnit> helloSymbols = jsAnalyzer.searchDefinitions("Hello");
        assertFalse(helloSymbols.isEmpty(), "Should find symbols containing 'Hello'.");
        Set<String> helloFqNames = helloSymbols.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(helloFqNames.contains("Hello"), "Should find 'Hello' class.");
        assertTrue(helloFqNames.contains("Hello.greet"), "Should find 'Hello.greet' method.");
        // JsxArrowFnComponent fqName does not contain "Hello", so it shouldn't be matched by fqName search.
        assertEquals(2, helloSymbols.size(), "Expected 2 symbols containing 'Hello' in their FQ names.");

        // Test case 3: Search for ".render" (matches method name part)
        List<CodeUnit> renderSymbols = jsAnalyzer.searchDefinitions(".render");
        assertFalse(renderSymbols.isEmpty(), "Should find symbols containing '.render'.");
        assertTrue(renderSymbols.stream().anyMatch(cu -> "JsxClass.render".equals(cu.fqName())), "Should find 'JsxClass.render'.");
        assertEquals(1, renderSymbols.size(), "Expected 1 symbol matching '.render'.");
        
        // Test case 4: Search for "TOP_CONST" (matches variable name part)
        List<CodeUnit> constSymbols = jsAnalyzer.searchDefinitions("TOP_CONST");
        assertFalse(constSymbols.isEmpty(), "Should find symbols containing 'TOP_CONST'.");
        assertTrue(constSymbols.stream().anyMatch(cu -> "Vars.js.TOP_CONST_JS".equals(cu.fqName())), "Should find 'Vars.js.TOP_CONST_JS'.");
        assertEquals(1, constSymbols.size(), "Expected 1 symbol matching 'TOP_CONST'.");

        // Test case 5: Non-existent pattern
        List<CodeUnit> nonExistentSymbols = jsAnalyzer.searchDefinitions("NonExistentPatternXYZ");
        assertTrue(nonExistentSymbols.isEmpty(), "Searching for a non-existent pattern should return an empty list.");

        // Test case 6: Null pattern
        List<CodeUnit> nullPatternSymbols = jsAnalyzer.searchDefinitions(null);
        assertTrue(nullPatternSymbols.isEmpty(), "Searching with a null pattern should return an empty list.");

        // Test case 7: Empty pattern
        List<CodeUnit> emptyPatternSymbols = jsAnalyzer.searchDefinitions("");
        assertTrue(emptyPatternSymbols.isEmpty(), "Searching with an empty pattern should return an empty list.");
    }

    @Test
    void testGetClassSource_Js() {
        // Test case 1: Valid class from Hello.js
        String helloClassSource = jsAnalyzer.getClassSource("Hello");
        String expectedHelloClassSource = """
        export class Hello {
            greet() { console.log("hi"); }
        }""";
        assertEquals(expectedHelloClassSource.stripIndent().trim(), helloClassSource.stripIndent().trim());

        // Test case 2: Valid class from Hello.jsx
        String jsxClassSource = jsAnalyzer.getClassSource("JsxClass");
        String expectedJsxClassSource = """
        export class JsxClass {
            render() {
                return <div className="class-jsx">Hello from JSX Class</div>;
            }
        }""";
        assertEquals(expectedJsxClassSource.stripIndent().trim(), jsxClassSource.stripIndent().trim());

        // Test case 3: Non-existent class
        assertThrows(SymbolNotFoundException.class, () -> jsAnalyzer.getClassSource("NonExistentClass"),
                     "Requesting source for a non-existent class should throw SymbolNotFoundException.");

        // Test case 4: Existing symbol that is a function, not a class
        assertThrows(SymbolNotFoundException.class, () -> jsAnalyzer.getClassSource("util"),
                     "Requesting class source for a function symbol should throw SymbolNotFoundException.");

        // Test case 5: Existing symbol that is a field, not a class
        assertThrows(SymbolNotFoundException.class, () -> jsAnalyzer.getClassSource("Vars.js.TOP_CONST_JS"),
                     "Requesting class source for a field symbol should throw SymbolNotFoundException.");
    }

    @Test
    void testGetMethodSource_Js() {
        // Test case 1: Method in Hello.js
        Optional<String> greetMethodSourceOpt = jsAnalyzer.getMethodSource("Hello.greet");
        assertTrue(greetMethodSourceOpt.isPresent(), "Source for 'Hello.greet' should be found.");
        String expectedGreetMethodSource = """
        greet() { console.log("hi"); }""";
        assertEquals(expectedGreetMethodSource.stripIndent().trim(), greetMethodSourceOpt.get().stripIndent().trim());

        // Test case 2: Method in Hello.jsx
        Optional<String> renderMethodSourceOpt = jsAnalyzer.getMethodSource("JsxClass.render");
        assertTrue(renderMethodSourceOpt.isPresent(), "Source for 'JsxClass.render' should be found.");
        String expectedRenderMethodSource = """
        render() {
                return <div className="class-jsx">Hello from JSX Class</div>;
            }"""; // Note: Indentation within the method body is preserved.
        assertEquals(expectedRenderMethodSource.stripIndent().trim(), renderMethodSourceOpt.get().stripIndent().trim());

        // Test case 3: Exported function in Hello.js
        Optional<String> utilFuncSourceOpt = jsAnalyzer.getMethodSource("util");
        assertTrue(utilFuncSourceOpt.isPresent(), "Source for 'util' function should be found.");
        String expectedUtilFuncSource = """
        export function util() { return 42; }""";
        assertEquals(expectedUtilFuncSource.stripIndent().trim(), utilFuncSourceOpt.get().stripIndent().trim());

        // Test case 4: Exported arrow function in Hello.jsx
        Optional<String> jsxArrowFnSourceOpt = jsAnalyzer.getMethodSource("JsxArrowFnComponent");
        assertTrue(jsxArrowFnSourceOpt.isPresent(), "Source for 'JsxArrowFnComponent' should be found.");
        // Note: The source for an arrow function assigned to a const/let is just the arrow function part.
        String expectedJsxArrowFnSource = """
        ({ name }) => {
            return (
                <section>
                    <p>Hello, {name} from JSX Arrow Function!</p>
                </section>
            );
        }""";
        assertEquals(expectedJsxArrowFnSource.stripIndent().trim(), jsxArrowFnSourceOpt.get().stripIndent().trim());

        // Test case 5: Local (non-exported) arrow function in Hello.jsx
        Optional<String> localJsxArrowFnSourceOpt = jsAnalyzer.getMethodSource("LocalJsxArrowFn");
        assertTrue(localJsxArrowFnSourceOpt.isPresent(), "Source for 'LocalJsxArrowFn' should be found.");
        String expectedLocalJsxArrowFnSource = """
        () => <button>Click Me</button>""";
        assertEquals(expectedLocalJsxArrowFnSource.stripIndent().trim(), localJsxArrowFnSourceOpt.get().stripIndent().trim());

        // Test case 6: Local (non-exported) plain function in Hello.jsx
        Optional<String> plainJsxFuncSourceOpt = jsAnalyzer.getMethodSource("PlainJsxFunc");
        assertTrue(plainJsxFuncSourceOpt.isPresent(), "Source for 'PlainJsxFunc' should be found.");
        String expectedPlainJsxFuncSource = """
        function PlainJsxFunc() {
            return <article>Some article content</article>;
        }""";
        assertEquals(expectedPlainJsxFuncSource.stripIndent().trim(), plainJsxFuncSourceOpt.get().stripIndent().trim());

        // Test case 7: Non-existent method
        Optional<String> nonExistentMethodSourceOpt = jsAnalyzer.getMethodSource("NonExistent.method");
        assertTrue(nonExistentMethodSourceOpt.isEmpty(), "Requesting source for a non-existent method should return Option.empty().");

        // Test case 8: Existing symbol that is a class, not a function
        Optional<String> classAsMethodSourceOpt = jsAnalyzer.getMethodSource("Hello");
        assertTrue(classAsMethodSourceOpt.isEmpty(), "Requesting method source for a class symbol should return Option.empty().");
        
        // Test case 9: Existing symbol that is a field, not a function
        Optional<String> fieldAsMethodSourceOpt = jsAnalyzer.getMethodSource("Vars.js.TOP_CONST_JS");
        assertTrue(fieldAsMethodSourceOpt.isEmpty(), "Requesting method source for a field symbol should return Option.empty().");
    }
}
