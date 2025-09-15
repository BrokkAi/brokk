package io.github.jbellis.brokk.analyzer.update;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import org.junit.jupiter.api.*;

class TypescriptAnalyzerUpdateTest {

    private TestProject project;
    private TypescriptAnalyzer analyzer;

    @BeforeEach
    void setUp() throws IOException {
        var rootDir = UpdateTestUtil.newTempDir();
        UpdateTestUtil.writeFile(
                rootDir,
                "hello.ts",
                """
                export function foo(): number { return 1; }
                """);
        project = UpdateTestUtil.newTestProject(rootDir, Languages.TYPESCRIPT);
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

        UpdateTestUtil.writeFile(
                project.getRoot(),
                "hello.ts",
                """
                export function foo(): number { return 1; }
                export function bar(): number { return 2; }
                """);

        var file = analyzer.getFileFor("foo").orElseThrow();
        analyzer.update(Set.of(file));

        assertTrue(analyzer.getDefinition("bar").isPresent());
    }

    @Test
    void autoDetect() throws IOException {
        UpdateTestUtil.writeFile(
                project.getRoot(),
                "hello.ts",
                """
                export function foo(): number { return 1; }
                export function baz(): number { return 3; }
                """);
        analyzer.update();
        assertTrue(analyzer.getDefinition("baz").isPresent());

        var file = analyzer.getFileFor("foo").orElseThrow();
        Files.deleteIfExists(file.absPath());
        analyzer.update();
        assertTrue(analyzer.getDefinition("foo").isEmpty());
    }
}
