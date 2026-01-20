package ai.brokk.analyzer.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AnalyzerUtil;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.TreeSitterAnalyzer;
import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class JavaScriptImportTest {

    private TreeSitterAnalyzer createAnalyzer(IProject project) {
        return (TreeSitterAnalyzer) project.getBuildLanguage().createAnalyzer(project);
    }

    @Test
    public void testImport() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import React, { useState } from 'react';
                import { Something, AnotherThing as AT } from './another-module';
                import * as AllThings from './all-the-things';
                import DefaultThing from './default-thing';

                function foo() {};
                """,
                        "foo.js")
                .build()) {
            var analyzer = createAnalyzer(testProject);
            var file = AnalyzerUtil.getFileFor(analyzer, "foo").get();
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

            var analyzer = createAnalyzer(testProject);
            var mainFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("main.js"))
                    .findFirst()
                    .get();

            Set<CodeUnit> importedUnits = analyzer.importedCodeUnitsOf(mainFile);

            boolean foundHelper = importedUnits.stream()
                    .anyMatch(cu -> cu.shortName().equals("helper") && cu.source().getRelPath().toString().contains("helper.js"));

            assertTrue(foundHelper, "Should have resolved 'helper' function from utils/helper.js");
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

            var analyzer = createAnalyzer(testProject);
            var childFile = testProject.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().endsWith("ChildService.js"))
                    .findFirst()
                    .get();

            Set<CodeUnit> importedUnits = analyzer.importedCodeUnitsOf(childFile);

            boolean foundBaseService = importedUnits.stream()
                    .anyMatch(cu -> cu.shortName().equals("BaseService")
                            && cu.isClass()
                            && cu.source().getRelPath().toString().contains("src/some/BaseService.js"));

            assertTrue(foundBaseService, "Should have resolved 'BaseService' class from src/some/BaseService.js");
        }
    }
}
