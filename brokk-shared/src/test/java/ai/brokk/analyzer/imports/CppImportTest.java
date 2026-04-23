package ai.brokk.analyzer.imports;

import static ai.brokk.testutil.InlineCoreProject.code;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ImportAnalysisProvider;
import ai.brokk.analyzer.ImportInfo;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TreeSitterAnalyzer;
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

        try (var project = code(content, "main.cpp").build()) {
            var analyzer = (TreeSitterAnalyzer) project.getAnalyzer();
            List<String> imports = analyzer.importStatementsOf(project.file("main.cpp"));

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

        try (var project = code(content, "math.cpp").build()) {
            var analyzer = (TreeSitterAnalyzer) project.getAnalyzer();
            List<String> imports = analyzer.importStatementsOf(project.file("math.cpp"));

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

        try (var project = code(headerContent, "math.h")
                .addFileContents(sourceContent, "main.cpp")
                .build()) {
            var analyzer = (TreeSitterAnalyzer) project.getAnalyzer();

            var mainFile = project.file("main.cpp");
            var mathHeader = project.file("math.h");

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

    @Test
    void testImportExtractionIgnoresCommentsWithQuotes() throws IOException {
        String content =
                """
                #include "header.h" // "note"
                #include "other.h" /* "comment" */

                int main() { return 0; }
                """;

        try (var project = code(content, "main.cpp").build()) {
            var analyzer = (TreeSitterAnalyzer) project.getAnalyzer();
            analyzer = (TreeSitterAnalyzer) analyzer.update();

            ProjectFile projectFile = new ProjectFile(project.getRoot(), "main.cpp");

            List<String> imports = analyzer.importStatementsOf(projectFile);

            assertEquals(2, imports.size(), "Should detect 2 include statements");
            // The raw import statements include the full line from the source
            assertTrue(imports.stream().anyMatch(i -> i.contains("header.h")));
            assertTrue(imports.stream().anyMatch(i -> i.contains("other.h")));
        }
    }

    @Test
    void testImportResolutionIgnoresTrailingCommentQuotes() throws IOException {
        String headerContent = """
                void helperFunction();
                """;

        String sourceContent =
                """
                #include "helper.h" // "some note"

                int main() { return 0; }
                """;

        try (var project = code(headerContent, "helper.h")
                .addFileContents(sourceContent, "main.cpp")
                .build()) {
            var analyzer = (TreeSitterAnalyzer) project.getAnalyzer();
            analyzer = (TreeSitterAnalyzer) analyzer.update();

            ProjectFile mainFile = new ProjectFile(project.getRoot(), "main.cpp");

            Set<CodeUnit> importedUnits = ((ImportAnalysisProvider) analyzer).importedCodeUnitsOf(mainFile);

            boolean foundFn =
                    importedUnits.stream().anyMatch(cu -> cu.shortName().equals("helperFunction") && cu.isFunction());

            assertTrue(foundFn, "Should resolve helperFunction from helper.h despite trailing comment with quotes");
        }
    }

    @Test
    void testRelevantImportsForFunction() throws IOException {
        String header = "void helperFunction();";
        String source =
                """
                #include "helper.h"
                void caller() { helperFunction(); }
                """;

        try (var project =
                code(header, "helper.h").addFileContents(source, "main.cpp").build()) {
            var analyzer = (TreeSitterAnalyzer) project.getAnalyzer();
            analyzer = (TreeSitterAnalyzer) analyzer.update();

            ProjectFile mainFile = new ProjectFile(project.getRoot(), "main.cpp");
            var callerFn = analyzer.getDeclarations(mainFile).stream()
                    .filter(cu -> cu.identifier().equals("caller"))
                    .findFirst()
                    .orElseThrow();

            Set<String> relevant = ((ImportAnalysisProvider) analyzer).relevantImportsFor(callerFn);

            assertTrue(relevant.contains("#include \"helper.h\""), "Should include helper.h used in caller");
        }
    }

    @Test
    void testRelevantImportsExcludesUnused() throws IOException {
        String h1 = "void f1();";
        String h2 = "void f2();";
        String source =
                """
                #include "h1.h"
                #include "h2.h"
                void caller() { f1(); }
                """;

        try (var project = code(h1, "h1.h")
                .addFileContents(h2, "h2.h")
                .addFileContents(source, "main.cpp")
                .build()) {
            var analyzer = (TreeSitterAnalyzer) project.getAnalyzer();
            analyzer = (TreeSitterAnalyzer) analyzer.update();

            ProjectFile mainFile = new ProjectFile(project.getRoot(), "main.cpp");
            var callerFn = analyzer.getDeclarations(mainFile).stream()
                    .filter(cu -> cu.identifier().equals("caller"))
                    .findFirst()
                    .orElseThrow();

            Set<String> relevant = ((ImportAnalysisProvider) analyzer).relevantImportsFor(callerFn);

            assertTrue(relevant.contains("#include \"h1.h\""), "Should include used h1.h");
            assertFalse(relevant.contains("#include \"h2.h\""), "Should NOT include unused h2.h");
        }
    }

    @Test
    void testImportResolutionIgnoresPathTraversalOutsideProject() throws IOException {
        String sourceContent =
                """
                #include "../outside.h"
                #include "../../way_outside.h"

                int main() { return 0; }
                """;

        try (var project = code(sourceContent, "src/main.cpp").build()) {
            var analyzer = (TreeSitterAnalyzer) project.getAnalyzer();
            analyzer = (TreeSitterAnalyzer) analyzer.update();

            ProjectFile mainFile = new ProjectFile(project.getRoot(), "src/main.cpp");

            // Should not throw and should return empty set since includes escape project root
            Set<CodeUnit> importedUnits = ((ImportAnalysisProvider) analyzer).importedCodeUnitsOf(mainFile);

            assertTrue(
                    importedUnits.isEmpty(), "Includes that escape project root should not resolve to any CodeUnits");
        }
    }

    @Test
    void testCouldImportFile_quotedIncludeMatches() throws Exception {
        // Test: #include "utils/helper.h" should return true for utils/helper.h
        String headerContent = "void helperFunction();";
        String sourceContent =
                """
                #include "utils/helper.h"

                int main() { return 0; }
                """;

        try (var project = code(headerContent, "utils/helper.h")
                .addFileContents(sourceContent, "main.cpp")
                .build()) {
            var analyzer = (TreeSitterAnalyzer) project.getAnalyzer();
            analyzer = (TreeSitterAnalyzer) analyzer.update();

            ProjectFile sourceFile = new ProjectFile(project.getRoot(), "main.cpp");
            ProjectFile targetFile = new ProjectFile(project.getRoot(), "utils/helper.h");

            List<ImportInfo> imports = analyzer.importInfoOf(sourceFile);

            boolean result = analyzer.couldImportFile(sourceFile, imports, targetFile);

            assertTrue(result, "#include \"utils/helper.h\" should match utils/helper.h");
        }
    }

    @Test
    void testCouldImportFile_angleBracketSystemIncludes() throws Exception {
        // Test: Angle bracket includes (<vector>) are system includes and should never match
        // project files because they reference external headers.
        String headerContent = "void myFunction();";
        String sourceContent =
                """
                #include <myheader.h>
                #include <vector>

                int main() { return 0; }
                """;

        try (var project = code(headerContent, "myheader.h")
                .addFileContents(sourceContent, "main.cpp")
                .build()) {
            var analyzer = (TreeSitterAnalyzer) project.getAnalyzer();
            analyzer = (TreeSitterAnalyzer) analyzer.update();

            ProjectFile sourceFile = new ProjectFile(project.getRoot(), "main.cpp");
            ProjectFile targetFile = new ProjectFile(project.getRoot(), "myheader.h");

            List<ImportInfo> imports = analyzer.importInfoOf(sourceFile);

            boolean result = analyzer.couldImportFile(sourceFile, imports, targetFile);

            assertFalse(result, "System includes (angle brackets) should not match project files");
        }
    }

    @Test
    void testCouldImportFile_relativeIncludeResolvesCorrectly() throws Exception {
        // Test: #include "helper.h" should match both helper.h and src/helper.h (suffix match)
        String headerContent = "void helperFunction();";
        String sourceContent =
                """
                #include "helper.h"

                int main() { return 0; }
                """;

        try (var project = code(headerContent, "src/helper.h")
                .addFileContents(sourceContent, "src/main.cpp")
                .build()) {
            var analyzer = (TreeSitterAnalyzer) project.getAnalyzer();
            analyzer = (TreeSitterAnalyzer) analyzer.update();

            ProjectFile sourceFile = new ProjectFile(project.getRoot(), "src/main.cpp");
            ProjectFile targetFile = new ProjectFile(project.getRoot(), "src/helper.h");

            List<ImportInfo> imports = analyzer.importInfoOf(sourceFile);

            boolean result = analyzer.couldImportFile(sourceFile, imports, targetFile);

            assertTrue(result, "#include \"helper.h\" should match src/helper.h via suffix match");
        }
    }
}
