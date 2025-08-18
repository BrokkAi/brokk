package io.github.jbellis.brokk.analyzer.update;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.testutil.TestProject;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.*;

class PythonTreeSitterAnalyzerUpdateTest {

    private TestProject project;
    private PythonAnalyzer analyzer;

    @BeforeEach
    void setUp() throws IOException {
        var rootDir = UpdateTestUtil.newTempDir();
        UpdateTestUtil.writeFile(rootDir, "mod.py", """
        def foo():
            return 1
        """);
        project = UpdateTestUtil.newTestProject(rootDir, Language.PYTHON);
        analyzer = new PythonAnalyzer(project, new HashSet<>());
    }

    @AfterEach
    void tearDown() {
        if (project != null) project.close();
    }

    @Test
    void explicitUpdate() throws IOException {
        assertTrue(analyzer.getDefinition("foo").isPresent());
        assertTrue(analyzer.getDefinition("bar").isEmpty());

        // change: add bar()
        UpdateTestUtil.writeFile(
                project.getRoot(),
                "mod.py",
                """
        def foo():
            return 1

        def bar():
            return 2
        """);

        var maybeFile = analyzer.getFileFor("foo");
        assertTrue(maybeFile.isPresent());
        analyzer.update(Set.of(maybeFile.get()));
        assertTrue(analyzer.getDefinition("bar").isPresent());
    }

    @Test
    void autoDetectChangesAndDeletes() throws IOException {
        // modify file
        UpdateTestUtil.writeFile(project.getRoot(), "mod.py", """
        def foo():
            return 42
        """);
        analyzer.update();
        // There is no separate fqName namespace for functions in a module-less python file,
        // the simple name remains 'foo', verify it's still present
        assertTrue(analyzer.getDefinition("foo").isPresent());

        // delete file – symbols should disappear
        var pyFile = analyzer.getFileFor("foo").orElseThrow();
        java.nio.file.Files.deleteIfExists(pyFile.absPath());
        analyzer.update();
        assertTrue(analyzer.getDefinition("foo").isEmpty());
    }
}
