package ai.brokk.analyzer.update;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.analyzer.*;
import ai.brokk.analyzer.CppAnalyzer;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CppAnalyzerUpdateTest {

    @TempDir
    Path tempDir;

    private TestProject project;
    private IAnalyzer analyzer;

    @BeforeEach
    void setUp() throws IOException {
        var initial = new ProjectFile(tempDir, "A.cpp");
        initial.write("""
                int foo() { return 1; }
                """);
        project = new TestProject(tempDir, Languages.CPP_TREESITTER);
        analyzer = new CppAnalyzer(project);
    }

    @AfterEach
    void tearDown() {
        if (project != null) project.close();
    }

    @Test
    void explicitUpdate() throws IOException {
        var fooDef = analyzer.getDefinitions("foo").stream()
                .filter(cu -> "()".equals(cu.signature()))
                .findFirst();
        assertTrue(fooDef.isPresent());
        var barDef = analyzer.getDefinitions("bar").stream()
                .filter(cu -> "()".equals(cu.signature()))
                .findFirst();
        assertTrue(barDef.isEmpty());

        // mutate
        new ProjectFile(project.getRoot(), "A.cpp")
                .write(
                        """
                int foo() { return 1; }
                int bar() { return 2; }
                """);

        var maybeFile = AnalyzerUtil.getFileFor(analyzer, "foo");
        assertTrue(maybeFile.isPresent());
        analyzer = analyzer.update(Set.of(maybeFile.get()));

        var newBarDef = analyzer.getDefinitions("bar").stream()
                .filter(cu -> "()".equals(cu.signature()))
                .findFirst();
        assertTrue(newBarDef.isPresent());
    }

    @Test
    void autoDetect() throws IOException {
        new ProjectFile(project.getRoot(), "A.cpp")
                .write(
                        """
                int foo() { return 1; }
                int baz() { return 3; }
                """);
        analyzer = analyzer.update();
        var bazDef = analyzer.getDefinitions("baz").stream()
                .filter(cu -> "()".equals(cu.signature()))
                .findFirst();
        assertTrue(bazDef.isPresent());

        var file = AnalyzerUtil.getFileFor(analyzer, "foo").orElseThrow();
        Files.deleteIfExists(file.absPath());
        analyzer = analyzer.update();
        var deletedFooDef = analyzer.getDefinitions("foo").stream()
                .filter(cu -> "()".equals(cu.signature()))
                .findFirst();
        assertTrue(deletedFooDef.isEmpty());
    }

    @Test
    void zeroArgFunctionLookup() throws IOException {
        var tmp = UpdateTestUtil.newTempDir();
        UpdateTestUtil.writeFile(tmp, "B.cpp", "int foo() { return 1; }\n");

        var proj = UpdateTestUtil.newTestProject(tmp, Languages.CPP_TREESITTER);
        try (proj) {
            var localAnalyzer = new CppAnalyzer(proj);

            var allFoo = localAnalyzer.getDefinitions("foo");
            assertEquals(1, allFoo.size(), "Should find one definition for 'foo'");

            var fooDef = allFoo.stream().findFirst().orElseThrow();
            assertEquals("foo", fooDef.fqName());
            assertEquals("()", fooDef.signature());
        }
    }
}
