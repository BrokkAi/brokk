package io.github.jbellis.brokk.analyzer.update;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import org.junit.jupiter.api.*;

class GoAnalyzerUpdateTest {

    private TestProject project;
    private GoAnalyzer analyzer;

    @BeforeEach
    void setUp() throws IOException {
        var rootDir = UpdateTestUtil.newTempDir();
        UpdateTestUtil.writeFile(
                rootDir,
                "a.go",
                """
                package main
                func Foo() int { return 1 }
                """);
        project = UpdateTestUtil.newTestProject(rootDir, Language.GO);
        analyzer = new GoAnalyzer(project);
    }

    @AfterEach
    void tearDown() {
        if (project != null) project.close();
    }

    @Test
    void explicitUpdate() throws IOException {
        assertTrue(analyzer.getDefinition("main.Foo").isPresent());
        assertTrue(analyzer.getDefinition("main.Bar").isEmpty());

        UpdateTestUtil.writeFile(
                project.getRoot(),
                "a.go",
                """
                package main
                func Foo() int { return 1 }
                func Bar() int { return 2 }
                """);

        var file = analyzer.getFileFor("main.Foo").orElseThrow();
        analyzer.update(Set.of(file));
        assertTrue(analyzer.getDefinition("main.Bar").isPresent());
    }

    @Test
    void autoDetect() throws IOException {
        UpdateTestUtil.writeFile(
                project.getRoot(),
                "a.go",
                """
                package main
                func Foo() int { return 1 }
                func Baz() int { return 3 }
                """);
        analyzer.update();
        assertTrue(analyzer.getDefinition("main.Baz").isPresent());

        var file = analyzer.getFileFor("main.Foo").orElseThrow();
        Files.deleteIfExists(file.absPath());
        analyzer.update();
        assertTrue(analyzer.getDefinition("main.Foo").isEmpty());
    }
}
