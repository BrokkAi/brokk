package ai.brokk.analyzer.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CppAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class CppImportTest {

    @Test
    void testImportExtraction(@TempDir Path tempDir) throws IOException {
        TestProject project = new TestProject(tempDir, Languages.CPP_TREESITTER);

        String content = """
                #include <iostream>
                #include "header.h"
                #include <vector>
                
                int main() { return 0; }
                """;

        Path filePath = tempDir.resolve("main.cpp");
        Files.writeString(filePath, content);

        CppAnalyzer analyzer = new CppAnalyzer(project);
        // Ensure the file is analyzed by triggering an update
        analyzer = (CppAnalyzer) analyzer.update();
        
        ProjectFile projectFile = new ProjectFile(tempDir, "main.cpp");

        List<String> imports = analyzer.importStatementsOf(projectFile);

        assertEquals(3, imports.size(), "Should detect 3 include statements");
        assertTrue(imports.contains("#include <iostream>"));
        assertTrue(imports.contains("#include \"header.h\""));
        assertTrue(imports.contains("#include <vector>"));
    }

    @Test
    void testNoImports(@TempDir Path tempDir) throws IOException {
        TestProject project = new TestProject(tempDir, Languages.CPP_TREESITTER);

        String content = """
                int add(int a, int b) {
                    return a + b;
                }
                """;

        Path filePath = tempDir.resolve("math.cpp");
        Files.writeString(filePath, content);

        CppAnalyzer analyzer = new CppAnalyzer(project);
        ProjectFile projectFile = new ProjectFile(tempDir, "math.cpp");

        List<String> imports = analyzer.importStatementsOf(projectFile);

        assertTrue(imports.isEmpty(), "Should return empty list for file with no includes");
    }

    @Test
    void testImportResolution(@TempDir Path tempDir) throws IOException {
        TestProject project = new TestProject(tempDir, Languages.CPP_TREESITTER);

        // 1. Create a header file with declarations
        String headerContent = """
                class MathUtils {
                public:
                    static int add(int a, int b);
                };
                
                void globalFunction();
                """;
        Path headerPath = tempDir.resolve("math.h");
        Files.writeString(headerPath, headerContent);

        // 2. Create a source file that includes the header
        String sourceContent = """
                #include "math.h"
                #include <iostream>
                
                int main() { return 0; }
                """;
        Path sourcePath = tempDir.resolve("main.cpp");
        Files.writeString(sourcePath, sourceContent);

        CppAnalyzer analyzer = new CppAnalyzer(project);
        analyzer = (CppAnalyzer) analyzer.update();

        ProjectFile mainFile = new ProjectFile(tempDir, "main.cpp");
        ProjectFile mathHeader = new ProjectFile(tempDir, "math.h");

        // 3. Verify imported code units
        Set<CodeUnit> importedUnits = analyzer.importedCodeUnitsOf(mainFile);

        // Should find MathUtils and globalFunction from math.h
        boolean foundClass = importedUnits.stream()
                .anyMatch(cu -> cu.shortName().equals("MathUtils") && cu.isClass());
        boolean foundFn = importedUnits.stream()
                .anyMatch(cu -> cu.shortName().equals("globalFunction") && cu.isFunction());

        assertTrue(foundClass, "Should resolve MathUtils class from header");
        assertTrue(foundFn, "Should resolve globalFunction from header");

        // 4. Verify angle-bracket includes are ignored in resolution
        // (iostream declarations should not be in the set because the file doesn't exist in project)
        boolean foundStd = importedUnits.stream()
                .anyMatch(cu -> cu.source().toString().contains("iostream"));
        assertTrue(!foundStd, "System headers should not be resolved to CodeUnits");

        // 5. Verify referencingFilesOf
        Set<ProjectFile> referencers = analyzer.referencingFilesOf(mathHeader);
        assertTrue(referencers.contains(mainFile), "main.cpp should be a referencing file of math.h");
    }
}
