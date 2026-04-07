package ai.brokk.analyzer.imports;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ImportInfo;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.PythonAnalyzer;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PythonCouldImportTest {

    @TempDir
    Path tempDir;

    private PythonAnalyzer analyzer;

    @BeforeEach
    public void setUp() throws IOException {
        // Create a basic structure with __init__.py files to define packages
        Files.createDirectories(tempDir.resolve("pkg/sub"));
        Files.writeString(tempDir.resolve("pkg/__init__.py"), "");
        Files.writeString(tempDir.resolve("pkg/sub/__init__.py"), "");

        var project = new TestProject(tempDir);
        analyzer = new PythonAnalyzer(project);
    }

    private ProjectFile createFile(String relativePath, String content) throws IOException {
        Path filePath = tempDir.resolve(relativePath);
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content);
        return new ProjectFile(tempDir, relativePath);
    }

    @Test
    public void testCouldImportFile_RelativeParentImport() throws IOException {
        // Source: pkg/sub/module.py
        // Target: pkg/utils.py
        // Import: from .. import utils
        ProjectFile source = createFile("pkg/sub/module.py", "from .. import utils");
        ProjectFile target = createFile("pkg/utils.py", "def some_fn(): pass");

        ImportInfo imp = new ImportInfo("from .. import utils", false, "utils", null);

        assertTrue(
                analyzer.couldImportFile(source, List.of(imp), target),
                "Should resolve .. as parent package and match utils.py");
    }

    @Test
    public void testCouldImportFile_RelativeParentModuleImport() throws IOException {
        // Source: pkg/sub/module.py
        // Target: pkg/other.py
        // Import: from ..other import something
        ProjectFile source = createFile("pkg/sub/module.py", "from ..other import something");
        ProjectFile target = createFile("pkg/other.py", "something = 1");

        ImportInfo imp = new ImportInfo("from ..other import something", false, "something", null);

        assertTrue(
                analyzer.couldImportFile(source, List.of(imp), target),
                "Should resolve ..other as pkg.other and match target");
    }

    @Test
    public void testCouldImportFile_InvalidRelativeImportConservativeReturn() throws IOException {
        // Source: pkg/module.py (Package depth 1)
        // Import: from ... import utils (Attempts to go 2 levels up, above root)
        // Target: some_other.py
        ProjectFile source = createFile("pkg/module.py", "from ... import utils");
        ProjectFile target = createFile("some_other.py", "");

        // PythonAnalyzer.resolveRelativeImport returns Optional.empty() for this.
        // couldImportFile should be conservative and return true if resolution fails.
        ImportInfo imp = new ImportInfo("from ... import utils", false, "utils", null);

        assertTrue(
                analyzer.couldImportFile(source, List.of(imp), target),
                "Should return true conservatively when relative import resolution fails (too many dots)");
    }

    @Test
    public void testCouldImportFile_NegativeMatch() throws IOException {
        ProjectFile source = createFile("pkg/module.py", "import unrelated");
        ProjectFile target = createFile("pkg/target.py", "");

        ImportInfo imp = new ImportInfo("import unrelated", false, "unrelated", null);

        assertFalse(analyzer.couldImportFile(source, List.of(imp), target), "Should not match unrelated import");
    }
}
