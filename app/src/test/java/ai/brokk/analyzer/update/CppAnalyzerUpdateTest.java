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
        // New behavior: fqName is WITHOUT signature
        assertTrue(analyzer.getDefinitions("foo").stream().findFirst().isPresent());
        assertTrue(analyzer.getDefinitions("bar").stream().findFirst().isEmpty());

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

        assertTrue(analyzer.getDefinitions("bar").stream().findFirst().isPresent());
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
        // New behavior: fqName is WITHOUT signature
        assertTrue(analyzer.getDefinitions("baz").stream().findFirst().isPresent());

        var file = AnalyzerUtil.getFileFor(analyzer, "foo").orElseThrow();
        Files.deleteIfExists(file.absPath());
        analyzer = analyzer.update();
        assertTrue(analyzer.getDefinitions("foo").stream().findFirst().isEmpty());
    }

    @Test
    void fqNameLookupWithoutSignature() throws IOException {
        // Create an isolated temporary project with a single zero-arg function 'foo'
        var tmp = UpdateTestUtil.newTempDir();
        UpdateTestUtil.writeFile(tmp, "B.cpp", "int foo() { return 1; }\n");

        var proj = UpdateTestUtil.newTestProject(tmp, Languages.CPP_TREESITTER);
        try (proj) {
            var localAnalyzer = new CppAnalyzer(proj);

            // New behavior: fqName is WITHOUT signature
            // "foo()" is not a valid fqName - it mixes fqName with signature
            var withoutParen = localAnalyzer.getDefinitions("foo").stream().findFirst();
            assertTrue(withoutParen.isPresent(), "Should find function by fqName 'foo'");
            assertEquals("foo", withoutParen.get().fqName());
        }
    }
}
