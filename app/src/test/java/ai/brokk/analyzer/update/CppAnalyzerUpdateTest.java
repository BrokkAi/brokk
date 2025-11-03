package ai.brokk.analyzer.update;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.analyzer.*;
import ai.brokk.analyzer.CppAnalyzer;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import org.junit.jupiter.api.*;

class CppAnalyzerUpdateTest {

    private TestProject project;
    private IAnalyzer analyzer;

    @BeforeEach
    void setUp() throws IOException {
        var rootDir = UpdateTestUtil.newTempDir();
        UpdateTestUtil.writeFile(rootDir, "A.cpp", """
                int foo() { return 1; }
                """);
        project = UpdateTestUtil.newTestProject(rootDir, Languages.CPP_TREESITTER);
        analyzer = new CppAnalyzer(project);
    }

    @AfterEach
    void tearDown() {
        if (project != null) project.close();
    }

    @Test
    void explicitUpdate() throws IOException {
        assertTrue(analyzer.getDefinition("foo").isPresent());
        assertTrue(analyzer.getDefinition("bar").isEmpty());

        // mutate
        UpdateTestUtil.writeFile(
                project.getRoot(),
                "A.cpp",
                """
                int foo() { return 1; }
                int bar() { return 2; }
                """);

        var maybeFile = AnalyzerUtil.getFileFor(analyzer, "foo");
        assertTrue(maybeFile.isPresent());
        analyzer = analyzer.update(Set.of(maybeFile.get()));

        assertTrue(analyzer.getDefinition("bar").isPresent());
    }

    @Test
    void autoDetect() throws IOException {
        UpdateTestUtil.writeFile(
                project.getRoot(),
                "A.cpp",
                """
                int foo() { return 1; }
                int baz() { return 3; }
                """);
        analyzer = analyzer.update();
        assertTrue(analyzer.getDefinition("baz").isPresent());

        var file = AnalyzerUtil.getFileFor(analyzer, "foo").orElseThrow();
        Files.deleteIfExists(file.absPath());
        analyzer = analyzer.update();
        assertTrue(analyzer.getDefinition("foo").isEmpty());
    }
}
