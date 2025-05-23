package io.github.jbellis.brokk.analyzer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;


public final class JavascriptAnalyzerTest {
    /** Creates a TestProject rooted under src/test/resources/{subDir}. */
    static TestProject createTestProject(String subDir, io.github.jbellis.brokk.analyzer.Language lang) { // Use Brokk's Language enum
        Path testDir = Path.of("src/test/resources", subDir);
        assertTrue(Files.exists(testDir), "Test resource dir missing: " + testDir);
        assertTrue(Files.isDirectory(testDir), testDir + " is not a directory");
        return new TestProject(testDir.toAbsolutePath(), lang);
    }

    /* -------------------- JavaScript / JSX -------------------- */

    @Test
    void testJavascriptJsxSkeletons() {
        TestProject project = createTestProject("testcode-js", io.github.jbellis.brokk.analyzer.Language.JAVASCRIPT);
        JavascriptAnalyzer ana = new JavascriptAnalyzer(project);
        assertInstanceOf(JavascriptAnalyzer.class, ana);

        assertFalse(ana.isEmpty(), "Analyzer should have processed JS/JSX files");

        ProjectFile jsxFile = new ProjectFile(project.getRoot(), "Hello.jsx");
        var skelJsx = ana.getSkeletons(jsxFile);
        assertFalse(skelJsx.isEmpty(), "Skeletons map for Hello.jsx should not be empty. Found: " + skelJsx.keySet().stream().map(CodeUnit::fqName).collect(Collectors.joining(", ")));

        var jsxClass = CodeUnit.cls(jsxFile, "", "JsxClass");
        var jsxArrowFn = CodeUnit.fn(jsxFile, "", "JsxArrowFnComponent");
        var localJsxArrowFn = CodeUnit.fn(jsxFile, "", "LocalJsxArrowFn");
        var plainJsxFunc = CodeUnit.fn(jsxFile, "", "PlainJsxFunc");

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

        Set<CodeUnit> declarationsInJsx = ana.getDeclarationsInFile(jsxFile);
        assertTrue(declarationsInJsx.contains(jsxClass), "getDeclarationsInFile mismatch for Hello.jsx: missing jsxClass. Found: " + declarationsInJsx);
        assertTrue(declarationsInJsx.contains(jsxArrowFn), "getDeclarationsInFile mismatch for Hello.jsx: missing jsxArrowFn. Found: " + declarationsInJsx);
        // Add other expected CUs like localJsxArrowFn, plainJsxFunc, and JsxClass.render
        assertTrue(declarationsInJsx.contains(localJsxArrowFn), "getDeclarationsInFile mismatch for Hello.jsx: missing localJsxArrowFn. Found: " + declarationsInJsx);
        assertTrue(declarationsInJsx.contains(plainJsxFunc), "getDeclarationsInFile mismatch for Hello.jsx: missing plainJsxFunc. Found: " + declarationsInJsx);
        assertTrue(declarationsInJsx.contains(CodeUnit.fn(jsxFile, "", "JsxClass.render")), "getDeclarationsInFile mismatch for Hello.jsx: missing JsxClass.render. Found: " + declarationsInJsx);

        assertEquals(expectedExportedArrowFnSkeleton.trim(), ana.getSkeleton(jsxArrowFn.fqName()).get().trim(), "getSkeleton mismatch for JsxArrowFnComponent FQ name");

        ProjectFile jsFile = new ProjectFile(project.getRoot(), "Hello.js");
        var skelJs = ana.getSkeletons(jsFile);
        assertFalse(skelJs.isEmpty(), "Skeletons map for Hello.js should not be empty.");

        var helloClass = CodeUnit.cls(jsFile, "", "Hello");
        var utilFunc = CodeUnit.fn(jsFile, "", "util");

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
        TestProject project = createTestProject("testcode-js", Language.JAVASCRIPT);
        JavascriptAnalyzer analyzer = new JavascriptAnalyzer(project);
        assertFalse(analyzer.isEmpty(), "Analyzer should not be empty after processing JS files");

        ProjectFile helloJsFile = new ProjectFile(project.getRoot(), "Hello.js");
        ProjectFile helloJsxFile = new ProjectFile(project.getRoot(), "Hello.jsx");
        ProjectFile varsJsFile = new ProjectFile(project.getRoot(), "Vars.js");

        // Define CodeUnits relevant to the test
        // These must match the CUs created by the analyzer internally for the maps to be hit correctly.
        CodeUnit helloClass = CodeUnit.cls(helloJsFile, "", "Hello"); // From Hello.js
        CodeUnit jsxArrowFn = CodeUnit.fn(helloJsxFile, "", "JsxArrowFnComponent"); // From Hello.jsx
        CodeUnit topConstJs = CodeUnit.field(varsJsFile, "", "_module_.TOP_CONST_JS"); // From Vars.js

        Set<CodeUnit> sources = Set.of(helloClass, jsxArrowFn, topConstJs);
        Set<String> actualSymbols = analyzer.getSymbols(sources);

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
        Set<String> emptySymbols = analyzer.getSymbols(Set.of());
        assertTrue(emptySymbols.isEmpty(), "getSymbols with empty sources should return an empty set.");

        // Test with a single top-level function CU
        CodeUnit utilFunc = CodeUnit.fn(helloJsFile, "", "util"); // from Hello.js
        Set<String> utilSymbols = analyzer.getSymbols(Set.of(utilFunc));
        assertEquals(Set.of("util"), utilSymbols, "Symbols mismatch for util function.");

        // Test with a single class CU from a JSX file (should include its methods)
        CodeUnit jsxClass = CodeUnit.cls(helloJsxFile, "", "JsxClass");
        // Expected: "JsxClass" from the class itself, "render" from its method
        Set<String> jsxClassSymbols = analyzer.getSymbols(Set.of(jsxClass));
        assertEquals(Set.of("JsxClass", "render"), jsxClassSymbols, "Symbols mismatch for JsxClass.");

        // Test with a non-exported top-level variable
        CodeUnit localVarJs = CodeUnit.field(varsJsFile, "", "_module_.localVarJs");
        Set<String> localVarSymbols = analyzer.getSymbols(Set.of(localVarJs));
        assertEquals(Set.of("localVarJs"), localVarSymbols, "Symbols mismatch for localVarJs.");

        // Test with multiple sources from the same file
        CodeUnit plainJsxFunc = CodeUnit.fn(helloJsxFile, "", "PlainJsxFunc");
        Set<CodeUnit> jsxSources = Set.of(jsxClass, plainJsxFunc);
        Set<String> jsxCombinedSymbols = analyzer.getSymbols(jsxSources);
        assertEquals(Set.of("JsxClass", "render", "PlainJsxFunc"), jsxCombinedSymbols, "Symbols mismatch for combined JSX sources.");
    }

    @Test
    void testJsxFeaturesSkeletons() {
        TestProject project = createTestProject("testcode-js", Language.JAVASCRIPT);
        IAnalyzer ana = new JavascriptAnalyzer(project);
        assertInstanceOf(JavascriptAnalyzer.class, ana, "Analyzer should be JavascriptAnalyzer");
        JavascriptAnalyzer analyzer = (JavascriptAnalyzer) ana;

        ProjectFile featuresFile = new ProjectFile(project.getRoot(), "FeaturesTest.jsx");
        var skeletons = analyzer.getSkeletons(featuresFile);
        assertFalse(skeletons.isEmpty(), "Skeletons map for FeaturesTest.jsx should not be empty.");

        // Module CU for imports
        CodeUnit moduleCU = CodeUnit.module(featuresFile, "", "_module_");
        assertTrue(skeletons.containsKey(moduleCU), "Skeletons map should contain module CU for imports.");
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
        Optional<String> mecSkeletonOpt = analyzer.getSkeleton(mecCU.fqName());
        assertTrue(mecSkeletonOpt.isPresent(), "getSkeleton should find MyExportedComponent by FQ name.");
        assertEquals(expectedMecSkeleton.trim(), mecSkeletonOpt.get().trim(), "getSkeleton for MyExportedComponent FQ name mismatch.");
    }

    @Test
    void testJavascriptTopLevelVariables() {
        TestProject project = createTestProject("testcode-js", Language.JAVASCRIPT);
        IAnalyzer ana = new JavascriptAnalyzer(project);
        assertInstanceOf(JavascriptAnalyzer.class, ana);
        JavascriptAnalyzer analyzer = (JavascriptAnalyzer) ana;

        ProjectFile varsJsFile = new ProjectFile(project.getRoot(), "Vars.js");
        var skelVars = analyzer.getSkeletons(varsJsFile);

        assertFalse(skelVars.isEmpty(), "Skeletons map for Vars.js should not be empty. Found: " + skelVars.keySet());

        CodeUnit topConstJsCU = CodeUnit.field(varsJsFile, "", "_module_.TOP_CONST_JS");
        CodeUnit localVarJsCU = CodeUnit.field(varsJsFile, "", "_module_.localVarJs");

        assertTrue(skelVars.containsKey(topConstJsCU), "Skeletons map should contain _module_.TOP_CONST_JS. Found: " + skelVars.keySet());
        assertEquals("export const TOP_CONST_JS = 123", skelVars.get(topConstJsCU).strip());

        assertTrue(skelVars.containsKey(localVarJsCU), "Skeletons map should contain _module_.localVarJs. Found: " + skelVars.keySet());
        assertEquals("let localVarJs = \"abc\"", skelVars.get(localVarJsCU).strip());

        // Ensure these are not mistaken for classes
        Set<CodeUnit> declarationsInVarsJs = analyzer.getDeclarationsInFile(varsJsFile);
        assertTrue(declarationsInVarsJs.contains(topConstJsCU), "_module_.TOP_CONST_JS should be in declarations list for Vars.js. Found: " + declarationsInVarsJs);
        assertFalse(topConstJsCU.isClass(), "_module_.TOP_CONST_JS CU should not be a class.");
        assertTrue(declarationsInVarsJs.contains(localVarJsCU), "_module_.localVarJs should be in declarations list for Vars.js. Found: " + declarationsInVarsJs);
        assertFalse(localVarJsCU.isClass(), "_module_.localVarJs CU should not be a class.");
    }
}
