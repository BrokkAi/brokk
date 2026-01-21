package ai.brokk.analyzer.imports;

import static ai.brokk.testutil.InlineTestProjectCreator.code;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ImportAnalysisProvider;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TreeSitterAnalyzer;
import ai.brokk.project.IProject;
import ai.brokk.testutil.AnalyzerCreator;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CppImportTest {

    @Test
    void testImportExtraction() throws IOException {
        String content =
                """
                #include <iostream>
                #include "header.h"
                #include <vector>

                int main() { return 0; }
                """;

        try (IProject project = code(content, "main.cpp").build()) {
            TreeSitterAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);
            analyzer = (TreeSitterAnalyzer) analyzer.update();

            ProjectFile projectFile = new ProjectFile(project.getRoot(), "main.cpp");

            List<String> imports = analyzer.importStatementsOf(projectFile);

            assertEquals(3, imports.size(), "Should detect 3 include statements");
            assertTrue(imports.contains("#include <iostream>"));
            assertTrue(imports.contains("#include \"header.h\""));
            assertTrue(imports.contains("#include <vector>"));
        }
    }

    @Test
    void testNoImports() throws IOException {
        String content =
                """
                int add(int a, int b) {
                    return a + b;
                }
                """;

        try (IProject project = code(content, "math.cpp").build()) {
            TreeSitterAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);
            ProjectFile projectFile = new ProjectFile(project.getRoot(), "math.cpp");

            List<String> imports = analyzer.importStatementsOf(projectFile);

            assertTrue(imports.isEmpty(), "Should return empty list for file with no includes");
        }
    }

    @Test
    void testImportResolution() throws IOException {
        // 1. Create a header file with declarations
        String headerContent =
                """
                class MathUtils {
                public:
                    static int add(int a, int b);
                };

                void globalFunction();
                """;

        // 2. Create a source file that includes the header
        String sourceContent =
                """
                #include "math.h"
                #include <iostream>

                int main() { return 0; }
                """;

        try (IProject project = code(headerContent, "math.h")
                .addFileContents(sourceContent, "main.cpp")
                .build()) {
            TreeSitterAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);
            analyzer = (TreeSitterAnalyzer) analyzer.update();

            ProjectFile mainFile = new ProjectFile(project.getRoot(), "main.cpp");
            ProjectFile mathHeader = new ProjectFile(project.getRoot(), "math.h");

            // 3. Verify imported code units
            Set<CodeUnit> importedUnits = ((ImportAnalysisProvider) analyzer).importedCodeUnitsOf(mainFile);

            // Should find MathUtils and globalFunction from math.h
            boolean foundClass =
                    importedUnits.stream().anyMatch(cu -> cu.shortName().equals("MathUtils") && cu.isClass());
            boolean foundFn =
                    importedUnits.stream().anyMatch(cu -> cu.shortName().equals("globalFunction") && cu.isFunction());

            assertTrue(foundClass, "Should resolve MathUtils class from header");
            assertTrue(foundFn, "Should resolve globalFunction from header");

            // 4. Verify angle-bracket includes are ignored in resolution
            // (iostream declarations should not be in the set because the file doesn't exist in project)
            boolean foundStd =
                    importedUnits.stream().anyMatch(cu -> cu.source().toString().contains("iostream"));
            assertFalse(foundStd, "System headers should not be resolved to CodeUnits");

            // 5. Verify referencingFilesOf
            Set<ProjectFile> referencers = ((ImportAnalysisProvider) analyzer).referencingFilesOf(mathHeader);
            assertTrue(referencers.contains(mainFile), "main.cpp should be a referencing file of math.h");
        }
    }
}
