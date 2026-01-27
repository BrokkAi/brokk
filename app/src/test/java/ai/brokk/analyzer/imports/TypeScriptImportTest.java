package ai.brokk.analyzer.imports;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ImportAnalysisProvider;
import ai.brokk.analyzer.ImportInfo;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TypescriptAnalyzer;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class TypeScriptImportTest {

    @Test
    public void testImport() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import React, { useState } from 'react';
                import { Something, AnotherThing as AT } from './another-module';
                import * as AllThings from './all-the-things';
                import DefaultThing from './default-thing';

                function foo(): void {};
                """,
                        "foo.ts")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var file = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().equals("foo.ts"))
                    .findFirst()
                    .get();
            var imports = analyzer.importStatementsOf(file);
            var expected = Set.of(
                    "import { Something, AnotherThing as AT } from './another-module';",
                    "import * as AllThings from './all-the-things';",
                    "import React, { useState } from 'react';",
                    "import DefaultThing from './default-thing';");
            assertEquals(expected, new HashSet<>(imports), "Imports should be identical");
        }
    }

    @Test
    public void testResolveImports() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                export function helper(): number { return 42; }
                """,
                        "utils/helper.ts")
                .addFileContents(
                        """
                import { helper } from './utils/helper';
                function main(): number { return helper(); }
                """,
                        "main.ts")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var mainFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("main.ts"))
                    .findFirst()
                    .get();

            Set<CodeUnit> importedUnits =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow().importedCodeUnitsOf(mainFile);

            boolean foundHelper = importedUnits.stream()
                    .anyMatch(cu -> cu.shortName().equals("helper")
                            && cu.source().getRelPath().toString().contains("helper.ts"));

            assertTrue(foundHelper, "Should have resolved 'helper' function from utils/helper.ts");
        }
    }

    @Test
    public void testResolveWildcardImport() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                export function add(a: number, b: number): number { return a + b; }
                export function subtract(a: number, b: number): number { return a - b; }
                export const PI: number = 3.14159;
                """,
                        "math/operations.ts")
                .addFileContents(
                        """
                import * as MathOps from './math/operations';

                function calculate(): number {
                    return MathOps.add(1, 2) + MathOps.PI;
                }
                """,
                        "calculator.ts")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var calculatorFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("calculator.ts"))
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
                    getData(): any[] { return []; }
                }
                """,
                        "src/some/BaseService.ts")
                .addFileContents(
                        """
                import { BaseService } from '../BaseService';

                export class ChildService extends BaseService {
                    process(): number[] { return this.getData().map(x => x * 2); }
                }
                """,
                        "src/some/dir/ChildService.ts")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var childFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("ChildService.ts"))
                    .findFirst()
                    .get();

            Set<CodeUnit> importedUnits =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow().importedCodeUnitsOf(childFile);

            var expectedPath = java.nio.file.Path.of("src", "some", "BaseService.ts");
            boolean foundBaseService = importedUnits.stream()
                    .anyMatch(cu -> cu.shortName().equals("BaseService")
                            && cu.isClass()
                            && cu.source().getRelPath().equals(expectedPath));

            assertTrue(foundBaseService, "Should have resolved 'BaseService' class from src/some/BaseService.ts");
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

                function app(): void {}
                """,
                        "app.ts")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var file = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().equals("app.ts"))
                    .findFirst()
                    .get();
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
                export function shared(): number { return 1; }
                """,
                        "lib/shared.ts")
                .addFileContents(
                        """
                const { shared } = require('./lib/shared');
                shared();
                """,
                        "index.ts")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var indexFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("index.ts"))
                    .findFirst()
                    .get();

            Set<CodeUnit> importedUnits =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow().importedCodeUnitsOf(indexFile);

            boolean foundShared = importedUnits.stream()
                    .anyMatch(cu -> cu.shortName().equals("shared")
                            && cu.source().getRelPath().toString().contains("shared.ts"));

            assertTrue(foundShared, "Should have resolved 'shared' function from require call");
        }
    }

    @Test
    public void testImportWithExplicitExtension() throws IOException {
        // Test that importing './foo.ts' resolves correctly without trying 'foo.ts.ts'
        try (var testProject = InlineTestProjectCreator.code(
                        """
                export function greet(): string { return 'hello'; }
                """,
                        "utils/greet.ts")
                .addFileContents(
                        """
                import { greet } from './utils/greet.ts';
                function main(): string { return greet(); }
                """,
                        "main.ts")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var mainFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("main.ts"))
                    .findFirst()
                    .get();

            Set<CodeUnit> importedUnits =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow().importedCodeUnitsOf(mainFile);

            boolean foundGreet = importedUnits.stream()
                    .anyMatch(cu -> cu.shortName().equals("greet")
                            && cu.source().getRelPath().toString().contains("greet.ts"));

            assertTrue(foundGreet, "Should have resolved 'greet' function from explicit './utils/greet.ts' import");
        }
    }

    @Test
    public void testMixedImportAndRequire() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                export const val: number = 100;
                """, "mod1.ts")
                .addFileContents(
                        """
                export const otherVal: number = 200;
                """, "mod2.ts")
                .addFileContents(
                        """
                import { val } from './mod1';
                const { otherVal } = require('./mod2');
                """,
                        "mixed.ts")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var mixedFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("mixed.ts"))
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
                export function libFunc(): string { return 'lib'; }
                """,
                        "lib/index.ts")
                .addFileContents(
                        """
                import { libFunc } from './lib/index.ts';
                import { libFunc as libFunc2 } from './lib';
                libFunc();
                """,
                        "main.ts")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var mainFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("main.ts"))
                    .findFirst()
                    .get();

            Set<CodeUnit> importedUnits =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow().importedCodeUnitsOf(mainFile);

            var expectedPath = java.nio.file.Path.of("lib", "index.ts");
            boolean foundLibFunc = importedUnits.stream()
                    .anyMatch(cu -> cu.shortName().equals("libFunc")
                            && cu.source().getRelPath().equals(expectedPath));

            assertTrue(foundLibFunc, "Should have resolved 'libFunc' from lib/index.ts via directory import");
        }
    }

    @Test
    public void testRequireFromDirectoryIndex() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                export function libFunc(): string { return 'lib'; }
                """,
                        "lib/index.ts")
                .addFileContents(
                        """
                const { libFunc } = require('./lib/index');
                libFunc();
                """,
                        "main.ts")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var mainFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("main.ts"))
                    .findFirst()
                    .get();

            Set<CodeUnit> importedUnits =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow().importedCodeUnitsOf(mainFile);

            var expectedPath = java.nio.file.Path.of("lib", "index.ts");
            boolean foundLibFunc = importedUnits.stream()
                    .anyMatch(cu -> cu.shortName().equals("libFunc")
                            && cu.source().getRelPath().equals(expectedPath));

            assertTrue(foundLibFunc, "Should have resolved 'libFunc' from lib/index.ts via require");
        }
    }

    @Test
    public void testExplicitFileNotFallbackToDirectoryIndex() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                export function fromFile(): number { return 1; }
                """,
                        "util-dir.ts")
                .addFileContents(
                        """
                export function fromIndex(): number { return 2; }
                """,
                        "util-dir/index.ts")
                .addFileContents(
                        """
                import { fromFile } from './util-dir.ts';
                fromFile();
                """,
                        "main.ts")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var mainFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("main.ts"))
                    .findFirst()
                    .get();

            Set<CodeUnit> importedUnits =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow().importedCodeUnitsOf(mainFile);

            var filePath = java.nio.file.Path.of("util-dir.ts");
            var indexPath = java.nio.file.Path.of("util-dir", "index.ts");

            boolean foundFromFile = importedUnits.stream()
                    .anyMatch(cu -> cu.shortName().equals("fromFile")
                            && cu.source().getRelPath().equals(filePath));
            boolean foundFromIndex = importedUnits.stream()
                    .anyMatch(cu -> cu.source().getRelPath().equals(indexPath));

            assertTrue(foundFromFile, "Should resolve from util-dir.ts");
            assertFalse(foundFromIndex, "Should NOT resolve from util-dir/index.ts when explicit file exists");
        }
    }

    @Test
    public void testRelevantImportsForFunction() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import { Foo } from './foo';
                import { Bar } from './bar';

                export function useFoo(): Foo {
                    return new Foo();
                }
                """,
                        "main.ts")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var useFoo = analyzer.searchDefinitions("useFoo").iterator().next();

            Set<String> relevant =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow().relevantImportsFor(useFoo);

            assertTrue(relevant.contains("import { Foo } from './foo';"), "Should include Foo import");
            assertFalse(relevant.contains("import { Bar } from './bar';"), "Should exclude unused Bar import");
        }
    }

    @Test
    public void testRelevantImportsExcludesUnused() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import { Used } from './used';
                import { Unused } from './unused';

                export function doWork(): void {
                    Used.process();
                }
                """,
                        "work.ts")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var doWork = analyzer.searchDefinitions("doWork").iterator().next();

            Set<String> relevant =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow().relevantImportsFor(doWork);

            assertEquals(1, relevant.size());
            assertTrue(relevant.contains("import { Used } from './used';"));
        }
    }

    @Test
    public void testExtractTypeIdentifiers() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import { Foo } from './models';
                function process(input: Foo): void {
                    console.log(input);
                }
                """,
                        "test.ts")
                .build()) {
            var analyzer = (TypescriptAnalyzer) createTreeSitterAnalyzer(testProject);
            String source =
                    """
                function process(input: Foo): void {
                    console.log(input);
                }
                """;
            Set<String> identifiers = analyzer.extractTypeIdentifiers(source);

            assertTrue(identifiers.contains("Foo"), "Should contain Foo from type annotation");
            assertTrue(identifiers.contains("input"), "Should contain parameter name input");
            assertTrue(identifiers.contains("process"), "Should contain function name process");
        }
    }

    @Test
    public void testRelevantImportsForRequire() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                const fs = require('fs');
                const { readFile } = require('fs');
                const path = require('path');

                export function readConfig(): void {
                    fs.readFileSync('config.json');
                    readFile('other.json', () => {});
                }

                export function unusedFunction(): number {
                    return 1;
                }
                """,
                        "app.ts")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var provider = analyzer.as(ImportAnalysisProvider.class).orElseThrow();

            // Test 1: Function using fs and readFile
            var readConfig = analyzer.searchDefinitions("readConfig").iterator().next();
            Set<String> relevantRead = provider.relevantImportsFor(readConfig);
            assertTrue(
                    relevantRead.stream().anyMatch(s -> s.contains("const fs = require('fs')")),
                    "Should include fs require");
            assertTrue(
                    relevantRead.stream().anyMatch(s -> s.contains("const { readFile } = require('fs')")),
                    "Should include readFile require");
            assertFalse(
                    relevantRead.stream().anyMatch(s -> s.contains("const path = require('path')")),
                    "Should exclude unused path require");

            // Test 2: Function NOT using fs
            var unusedFn =
                    analyzer.searchDefinitions("unusedFunction").iterator().next();
            Set<String> relevantUnused = provider.relevantImportsFor(unusedFn);
            assertTrue(relevantUnused.isEmpty(), "Should exclude all requires for unused function");
        }
    }

    @Test
    public void testCouldImportFileRelativeImport() throws Exception {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import { X } from './utils/helper';
                function foo(): void {}
                """,
                        "src/main.ts")
                .addFileContents(
                        """
                export function X(): void {}
                """,
                        "src/utils/helper.ts")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var mainFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("main.ts"))
                    .findFirst()
                    .get();
            var helperFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("helper.ts"))
                    .findFirst()
                    .get();

            List<ImportInfo> imports = analyzer.importInfoOf(mainFile);

            boolean result = analyzer.couldImportFile(mainFile, imports, helperFile);
            assertTrue(result, "Import './utils/helper' should match target 'src/utils/helper.ts'");
        }
    }

    @Test
    public void testCouldImportFileParentRelativeImport() throws Exception {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import User from '../models/User';
                function foo(): void {}
                """,
                        "src/components/Component.ts")
                .addFileContents(
                        """
                export default class User {}
                """,
                        "src/models/User.ts")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var componentFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("Component.ts"))
                    .findFirst()
                    .get();
            var userFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("User.ts"))
                    .findFirst()
                    .get();

            List<ImportInfo> imports = analyzer.importInfoOf(componentFile);

            boolean result = analyzer.couldImportFile(componentFile, imports, userFile);
            assertTrue(result, "Import '../models/User' should match target 'src/models/User.ts'");
        }
    }

    @Test
    public void testCouldImportFileExternalModule() throws Exception {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import _ from 'lodash';
                function foo(): void {}
                """,
                        "src/main.ts")
                .addFileContents(
                        """
                export function helper(): void {}
                """,
                        "src/utils/helper.ts")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var mainFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("main.ts"))
                    .findFirst()
                    .get();
            var helperFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("helper.ts"))
                    .findFirst()
                    .get();

            List<ImportInfo> imports = analyzer.importInfoOf(mainFile);

            boolean result = analyzer.couldImportFile(mainFile, imports, helperFile);
            assertFalse(result, "Import from 'lodash' (external module) should return false for any project file");
        }
    }

    @Test
    public void testCouldImportFileDirectoryIndex() throws Exception {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import { something } from './utils';
                function foo(): void {}
                """,
                        "src/main.ts")
                .addFileContents(
                        """
                export function something(): void {}
                """,
                        "src/utils/index.ts")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var mainFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("main.ts"))
                    .findFirst()
                    .get();
            var indexFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("index.ts"))
                    .findFirst()
                    .get();

            List<ImportInfo> imports = analyzer.importInfoOf(mainFile);

            boolean result = analyzer.couldImportFile(mainFile, imports, indexFile);
            assertTrue(result, "Import './utils' should match target 'src/utils/index.ts'");
        }
    }
}
