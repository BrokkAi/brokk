package ai.brokk.analyzer.imports;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AnalyzerUtil;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ImportAnalysisProvider;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class JavaScriptImportTest {

    @Test
    public void testImport() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import React, { useState } from 'react';
                import { Something, AnotherThing as AT } from './another-module';
                import * as AllThings from './all-the-things';
                import DefaultThing from './default-thing';
                import './side-effect-module';
                import 'global-polyfill';

                function foo() {};
                """,
                        "foo.js")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var file = AnalyzerUtil.getFileFor(analyzer, "foo").get();
            var imports = analyzer.importStatementsOf(file);
            var expected = Set.of(
                    "import { Something, AnotherThing as AT } from './another-module';",
                    "import * as AllThings from './all-the-things';",
                    "import React, { useState } from 'react';",
                    "import DefaultThing from './default-thing';",
                    "import './side-effect-module';",
                    "import 'global-polyfill';");
            assertEquals(expected, new HashSet<>(imports), "Imports should be identical");
        }
    }

    @Test
    public void testResolveImports() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                export function helper() { return 42; }
                """,
                        "utils/helper.js")
                .addFileContents(
                        """
                import { helper } from './utils/helper';
                function main() { return helper(); }
                """,
                        "main.js")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var mainFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("main.js"))
                    .findFirst()
                    .get();

            Set<CodeUnit> importedUnits =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow().importedCodeUnitsOf(mainFile);

            boolean foundHelper = importedUnits.stream()
                    .anyMatch(cu -> cu.shortName().equals("helper")
                            && cu.source().getRelPath().toString().contains("helper.js"));

            assertTrue(foundHelper, "Should have resolved 'helper' function from utils/helper.js");
        }
    }

    @Test
    public void testResolveWildcardImport() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                export function add(a, b) { return a + b; }
                export function subtract(a, b) { return a - b; }
                export const PI = 3.14159;
                """,
                        "math/operations.js")
                .addFileContents(
                        """
                import * as MathOps from './math/operations';

                function calculate() {
                    return MathOps.add(1, 2) + MathOps.PI;
                }
                """,
                        "calculator.js")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var calculatorFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("calculator.js"))
                    .findFirst()
                    .get();

            Set<CodeUnit> importedUnits =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow().importedCodeUnitsOf(calculatorFile);

            boolean foundAdd =
                    importedUnits.stream().anyMatch(cu -> cu.shortName().equals("add") && cu.isFunction());
            boolean foundSubtract =
                    importedUnits.stream().anyMatch(cu -> cu.shortName().equals("subtract") && cu.isFunction());
            boolean foundPI =
                    importedUnits.stream().anyMatch(cu -> cu.shortName().endsWith("PI") && cu.isField());

            assertTrue(foundAdd, "Should have resolved 'add' function from wildcard import");
            assertTrue(foundSubtract, "Should have resolved 'subtract' function from wildcard import");
            assertTrue(foundPI, "Should have resolved 'PI' constant from wildcard import");
        }
    }

    @Test
    public void testResolveImportsFromNestedDirectoryToParent() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                export class BaseService {
                    getData() { return []; }
                }
                """,
                        "src/some/BaseService.js")
                .addFileContents(
                        """
                import { BaseService } from '../BaseService';

                export class ChildService extends BaseService {
                    process() { return this.getData().map(x => x * 2); }
                }
                """,
                        "src/some/dir/ChildService.js")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var childFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("ChildService.js"))
                    .findFirst()
                    .get();

            Set<CodeUnit> importedUnits =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow().importedCodeUnitsOf(childFile);

            var expectedPath = Path.of("src", "some", "BaseService.js");
            boolean foundBaseService = importedUnits.stream()
                    .anyMatch(cu -> cu.shortName().equals("BaseService")
                            && cu.isClass()
                            && cu.source().getRelPath().equals(expectedPath));

            assertTrue(foundBaseService, "Should have resolved 'BaseService' class from src/some/BaseService.js");
        }
    }

    @Test
    public void testRequireImport() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                const path = require('path');
                const fs = require('fs');
                const local = require('./local-module');
                const { func } = require('../other');

                function app() {}
                """,
                        "app.js")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var file = AnalyzerUtil.getFileFor(analyzer, "app").get();
            var imports = analyzer.importStatementsOf(file);

            assertTrue(imports.stream().anyMatch(s -> s.contains("require('path')")));
            assertTrue(imports.stream().anyMatch(s -> s.contains("require('fs')")));
            assertTrue(imports.stream().anyMatch(s -> s.contains("require('./local-module')")));
            assertTrue(imports.stream().anyMatch(s -> s.contains("require('../other')")));
            assertEquals(4, imports.size());
        }
    }

    @Test
    public void testResolveRequireImport() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                export function shared() { return 1; }
                """, "lib/shared.js")
                .addFileContents(
                        """
                const { shared } = require('./lib/shared');
                shared();
                """,
                        "index.js")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var indexFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("index.js"))
                    .findFirst()
                    .get();

            Set<CodeUnit> importedUnits =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow().importedCodeUnitsOf(indexFile);

            boolean foundShared = importedUnits.stream()
                    .anyMatch(cu -> cu.shortName().equals("shared")
                            && cu.source().getRelPath().toString().contains("shared.js"));

            assertTrue(foundShared, "Should have resolved 'shared' function from require call");
        }
    }

    @Test
    public void testSideEffectImportResolution() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                // polyfill.js sets up global state
                if (typeof window !== 'undefined') {
                    window.polyfilled = true;
                }
                export const POLYFILL_VERSION = '1.0';
                """,
                        "polyfill.js")
                .addFileContents(
                        """
                import './polyfill';

                function main() {
                    console.log('app started');
                }
                """,
                        "app.js")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var appFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("app.js"))
                    .findFirst()
                    .get();

            Set<CodeUnit> importedUnits =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow().importedCodeUnitsOf(appFile);

            boolean foundPolyfillExport = importedUnits.stream()
                    .anyMatch(cu -> cu.shortName().endsWith("POLYFILL_VERSION")
                            && cu.source().getRelPath().toString().contains("polyfill.js"));

            assertTrue(foundPolyfillExport, "Should have resolved exports from side-effect import './polyfill'");
        }
    }

    @Test
    public void testImportWithExplicitExtension() throws IOException {
        // Test that importing './foo.js' resolves correctly without trying 'foo.js.js'
        try (var testProject = InlineTestProjectCreator.code(
                        """
                export function greet() { return 'hello'; }
                """,
                        "utils/greet.js")
                .addFileContents(
                        """
                import { greet } from './utils/greet.js';
                function main() { return greet(); }
                """,
                        "main.js")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var mainFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("main.js"))
                    .findFirst()
                    .get();

            Set<CodeUnit> importedUnits =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow().importedCodeUnitsOf(mainFile);

            boolean foundGreet = importedUnits.stream()
                    .anyMatch(cu -> cu.shortName().equals("greet")
                            && cu.source().getRelPath().toString().contains("greet.js"));

            assertTrue(foundGreet, "Should have resolved 'greet' function from explicit './utils/greet.js' import");
        }
    }

    @Test
    public void testMixedImportAndRequire() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                export const val = 100;
                """, "mod1.js")
                .addFileContents("""
                export const otherVal = 200;
                """, "mod2.js")
                .addFileContents(
                        """
                import { val } from './mod1';
                const { otherVal } = require('./mod2');
                """,
                        "mixed.js")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var mixedFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("mixed.js"))
                    .findFirst()
                    .get();

            Set<CodeUnit> importedUnits =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow().importedCodeUnitsOf(mixedFile);

            boolean foundVal =
                    importedUnits.stream().anyMatch(cu -> cu.shortName().endsWith("val"));
            boolean foundOtherVal =
                    importedUnits.stream().anyMatch(cu -> cu.shortName().endsWith("otherVal"));

            assertTrue(foundVal, "Should resolve ES6 import");
            assertTrue(foundOtherVal, "Should resolve CommonJS require");
        }
    }

    @Test
    public void testImportFromDirectoryIndex() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                export function libFunc() { return 'lib'; }
                """,
                        "lib/index.js")
                .addFileContents(
                        """
                import { libFunc } from './lib/index.js';
                import { libFunc as libFunc2 } from './lib';
                libFunc();
                """,
                        "main.js")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var mainFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("main.js"))
                    .findFirst()
                    .get();

            Set<CodeUnit> importedUnits =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow().importedCodeUnitsOf(mainFile);

            var expectedPath = Path.of("lib", "index.js");
            boolean foundLibFunc = importedUnits.stream()
                    .anyMatch(cu -> cu.shortName().equals("libFunc")
                            && cu.source().getRelPath().equals(expectedPath));

            assertTrue(foundLibFunc, "Should have resolved 'libFunc' from lib/index.js via directory import");
        }
    }

    @Test
    public void testRequireFromDirectoryIndex() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                export function libFunc() { return 'lib'; }
                """,
                        "lib/index.js")
                .addFileContents(
                        """
                const { libFunc } = require('./lib/index');
                libFunc();
                """,
                        "main.js")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var mainFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("main.js"))
                    .findFirst()
                    .get();

            Set<CodeUnit> importedUnits =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow().importedCodeUnitsOf(mainFile);

            var expectedPath = Path.of("lib", "index.js");
            boolean foundLibFunc = importedUnits.stream()
                    .anyMatch(cu -> cu.shortName().equals("libFunc")
                            && cu.source().getRelPath().equals(expectedPath));

            assertTrue(foundLibFunc, "Should have resolved 'libFunc' from lib/index.js via require");
        }
    }

    @Test
    public void testExplicitFileNotFallbackToDirectoryIndex() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                export function fromFile() { return 1; }
                """, "util-dir.js")
                .addFileContents(
                        """
                export function fromIndex() { return 2; }
                """,
                        "util-dir/index.js")
                .addFileContents(
                        """
                import { fromFile } from './util-dir.js';
                fromFile();
                """,
                        "main.js")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var mainFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("main.js"))
                    .findFirst()
                    .get();

            Set<CodeUnit> importedUnits =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow().importedCodeUnitsOf(mainFile);

            var filePath = Path.of("util-dir.js");
            var indexPath = Path.of("util-dir", "index.js");

            boolean foundFromFile = importedUnits.stream()
                    .anyMatch(cu -> cu.shortName().equals("fromFile")
                            && cu.source().getRelPath().equals(filePath));
            boolean foundFromIndex = importedUnits.stream()
                    .anyMatch(cu -> cu.source().getRelPath().equals(indexPath));

            assertTrue(foundFromFile, "Should resolve from util-dir.js");
            assertFalse(foundFromIndex, "Should NOT resolve from util-dir/index.js when explicit file exists");
        }
    }
}
