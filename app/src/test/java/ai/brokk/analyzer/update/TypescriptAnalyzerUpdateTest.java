package ai.brokk.analyzer.update;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.analyzer.*;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.TypescriptAnalyzer;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TypescriptAnalyzerUpdateTest {

    @TempDir
    Path tempDir;

    private TestProject project;
    private IAnalyzer analyzer;

    @BeforeEach
    void setUp() throws IOException {
        // Use JUnit TempDir for isolated test directory
        new ProjectFile(tempDir, "hello.ts")
                .write("""
                export function foo(): number { return 1; }
                """);
        project = new TestProject(tempDir, Languages.TYPESCRIPT);
        analyzer = new TypescriptAnalyzer(project);
    }

    @AfterEach
    void tearDown() {
        if (project != null) project.close();
    }

    @Test
    void explicitUpdate() throws IOException {
        assertTrue(analyzer.getDefinition("foo").isPresent());
        assertTrue(analyzer.getDefinition("bar").isEmpty());

        new ProjectFile(project.getRoot(), "hello.ts")
                .write(
                        """
                export function foo(): number { return 1; }
                export function bar(): number { return 2; }
                """);

        var file = AnalyzerUtil.getFileFor(analyzer, "foo").orElseThrow();
        analyzer = analyzer.update(Set.of(file));

        assertTrue(analyzer.getDefinition("bar").isPresent());
    }

    @Test
    void autoDetect() throws IOException {
        new ProjectFile(project.getRoot(), "hello.ts")
                .write(
                        """
                export function foo(): number { return 1; }
                export function baz(): number { return 3; }
                """);
        analyzer = analyzer.update();
        assertTrue(analyzer.getDefinition("baz").isPresent());

        var file = AnalyzerUtil.getFileFor(analyzer, "foo").orElseThrow();
        Files.deleteIfExists(file.absPath());
        analyzer = analyzer.update();
        assertTrue(analyzer.getDefinition("foo").isEmpty());
    }
}
