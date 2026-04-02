package ai.brokk.analyzer.update;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.analyzer.*;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.PythonAnalyzer;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PythonAnalyzerUpdateTest {

    @TempDir
    Path tempDir;

    private TestProject project;
    private IAnalyzer analyzer;

    @BeforeEach
    void setUp() throws IOException {
        // create initial file in the temp directory
        var initial = new ProjectFile(tempDir, "mod.py");
        initial.write("""
        def foo():
            return 1
        """);
        project = new TestProject(tempDir, Languages.PYTHON);
        analyzer = new PythonAnalyzer(project);
    }

    @AfterEach
    void tearDown() {
        if (project != null) project.close();
    }

    @Test
    void explicitUpdate() throws IOException {
        assertTrue(analyzer.getDefinitions("mod.foo").stream().findFirst().isPresent());
        assertTrue(analyzer.getDefinitions("mod.bar").stream().findFirst().isEmpty());

        // change: add bar()
        new ProjectFile(project.getRoot(), "mod.py")
                .write(
                        """
        def foo():
            return 1

        def bar():
            return 2
        """);

        var maybeFile = AnalyzerUtil.getFileFor(analyzer, "mod.foo");
        assertTrue(maybeFile.isPresent());
        analyzer = analyzer.update(Set.of(maybeFile.get()));
        assertTrue(analyzer.getDefinitions("mod.bar").stream().findFirst().isPresent());
    }

    @Test
    void autoDetectChangesAndDeletes() throws IOException {
        // modify file
        new ProjectFile(project.getRoot(), "mod.py").write("""
        def foo():
            return 42
        """);
        analyzer = analyzer.update();
        // There is no separate fqName namespace for functions in a module-less python file,
        // the simple name remains 'foo', verify it's still present
        assertTrue(analyzer.getDefinitions("mod.foo").stream().findFirst().isPresent());

        // delete file – symbols should disappear
        var pyFile = AnalyzerUtil.getFileFor(analyzer, "mod.foo").orElseThrow();
        Files.deleteIfExists(pyFile.absPath());
        analyzer = analyzer.update();
        assertTrue(analyzer.getDefinitions("mod.foo").stream().findFirst().isEmpty());
    }
}
