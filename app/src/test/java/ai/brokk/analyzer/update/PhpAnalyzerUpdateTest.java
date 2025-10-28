package ai.brokk.analyzer.update;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.*;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.PhpAnalyzer;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import org.junit.jupiter.api.*;

class PhpAnalyzerUpdateTest {

    private TestProject project;
    private IAnalyzer analyzer;

    @BeforeEach
    void setUp() throws IOException {
        var rootDir = UpdateTestUtil.newTempDir();
        UpdateTestUtil.writeFile(
                rootDir,
                "foo.php",
                """
                <?php
                function foo(): int { return 1; }
                """);
        project = UpdateTestUtil.newTestProject(rootDir, Languages.PHP);
        analyzer = new PhpAnalyzer(project);
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
                "foo.php",
                """
                <?php
                function foo(): int { return 1; }
                function bar(): int { return 2; }
                """);

        var file = analyzer.getFileFor("foo").orElseThrow();
        analyzer = analyzer.update(Set.of(file));

        assertTrue(analyzer.getDefinition("bar").isPresent());
    }

    @Test
    void autoDetect() throws IOException {
        UpdateTestUtil.writeFile(
                project.getRoot(),
                "foo.php",
                """
                <?php
                function foo(): int { return 1; }
                function baz(): int { return 3; }
                """);
        analyzer = analyzer.update();
        assertTrue(analyzer.getDefinition("baz").isPresent());

        var file = analyzer.getFileFor("foo").orElseThrow();
        Files.deleteIfExists(file.absPath());
        analyzer = analyzer.update();
        assertTrue(analyzer.getDefinition("foo").isEmpty());
    }
}
