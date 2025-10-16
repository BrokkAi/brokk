package io.github.jbellis.brokk.analyzer.update;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.testutil.TestProject;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.*;

class PythonAnalyzerUpdateTest {

    private TestProject project;
    private IAnalyzer analyzer;

    @BeforeEach
    void setUp() throws IOException {
        var rootDir = UpdateTestUtil.newTempDir();
        UpdateTestUtil.writeFile(rootDir, "mod.py", """
        def foo():
            return 1
        """);
        project = UpdateTestUtil.newTestProject(rootDir, Languages.PYTHON);
        analyzer = new PythonAnalyzer(project);
    }

    @AfterEach
    void tearDown() {
        if (project != null) project.close();
    }

    @Test
    void explicitUpdate() throws IOException {
        assertTrue(analyzer.getDefinition("mod.foo").isPresent());
        assertTrue(analyzer.getDefinition("mod.bar").isEmpty());

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

        var maybeFile = analyzer.getFileFor("mod.foo");
        assertTrue(maybeFile.isPresent());
        analyzer = analyzer.update(Set.of(maybeFile.get()));
        assertTrue(analyzer.getDefinition("mod.bar").isPresent());
    }

    @Test
    void autoDetectChangesAndDeletes() throws IOException {
        // modify file
        UpdateTestUtil.writeFile(project.getRoot(), "mod.py", """
        def foo():
            return 42
        """);
        analyzer = analyzer.update();
        // There is no separate fqName namespace for functions in a module-less python file,
        // the simple name remains 'foo', verify it's still present
        assertTrue(analyzer.getDefinition("mod.foo").isPresent());

        // delete file – symbols should disappear
        var pyFile = analyzer.getFileFor("mod.foo").orElseThrow();
        java.nio.file.Files.deleteIfExists(pyFile.absPath());
        analyzer = analyzer.update();
        assertTrue(analyzer.getDefinition("mod.foo").isEmpty());
    }
}
