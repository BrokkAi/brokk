package io.github.jbellis.brokk.analyzer.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.TreeSitterAnalyzer;
import io.github.jbellis.brokk.testutil.InlineTestProjectCreator;
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
            var file = analyzer.getFileFor("foo").get();
            var imports = analyzer.importStatementsOf(file);
            var expected = Set.of(
                    "import { Something, AnotherThing as AT } from './another-module';",
                    "import * as AllThings from './all-the-things';",
                    "import React, { useState } from 'react';",
                    "import DefaultThing from './default-thing';");
            assertEquals(expected, new HashSet<>(imports), "Imports should be identical");
        }
    }
}
