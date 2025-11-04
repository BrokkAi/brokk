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
        // Note: C++ function names include parameter signatures, e.g., "foo()"
        assertTrue(analyzer.getDefinition("foo()").isPresent());
        assertTrue(analyzer.getDefinition("bar()").isEmpty());

        // mutate
        new ProjectFile(project.getRoot(), "A.cpp")
                .write(
                        """
                int foo() { return 1; }
                int bar() { return 2; }
                """);

        var maybeFile = AnalyzerUtil.getFileFor(analyzer, "foo()");
        assertTrue(maybeFile.isPresent());
        analyzer = analyzer.update(Set.of(maybeFile.get()));

        assertTrue(analyzer.getDefinition("bar()").isPresent());
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
        // Note: C++ function names include parameter signatures, e.g., "baz()"
        assertTrue(analyzer.getDefinition("baz()").isPresent());

        var file = AnalyzerUtil.getFileFor(analyzer, "foo()").orElseThrow();
        Files.deleteIfExists(file.absPath());
        analyzer = analyzer.update();
        assertTrue(analyzer.getDefinition("foo()").isEmpty());
    }

    @Test
    void backCompatZeroArgLookup() throws IOException {
        // Create an isolated temporary project with a single zero-arg function 'foo'
        var tmp = UpdateTestUtil.newTempDir();
        UpdateTestUtil.writeFile(tmp, "B.cpp", "int foo() { return 1; }\n");

        var proj = UpdateTestUtil.newTestProject(tmp, Languages.CPP_TREESITTER);
        try (proj) {
            var localAnalyzer = new CppAnalyzer(proj);

            var withParen = localAnalyzer.getDefinition("foo()");
            var withoutParen = localAnalyzer.getDefinition("foo");

            // Both should be present or both absent, and when present they should refer to the same definition
            assertEquals(withParen.isPresent(), withoutParen.isPresent(), "Presence should match for 'foo' vs 'foo()'");
            assertEquals(
                    withParen, withoutParen, "Definition lookup should return the same result for 'foo' and 'foo()'");
        }
    }
}
