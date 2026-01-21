package ai.brokk.analyzer.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CppAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}
