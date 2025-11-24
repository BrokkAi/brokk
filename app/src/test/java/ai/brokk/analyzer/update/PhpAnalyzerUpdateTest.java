package ai.brokk.analyzer.update;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.analyzer.*;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.PhpAnalyzer;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PhpAnalyzerUpdateTest {

    @TempDir
    Path tempDir;

    private TestProject project;
    private IAnalyzer analyzer;

    @BeforeEach
    void setUp() throws IOException {
        // create initial file in the temp directory
        new ProjectFile(tempDir, "foo.php")
                .write("""
                <?php
                function foo(): int { return 1; }
                """);
        project = new TestProject(tempDir, Languages.PHP);
        analyzer = new PhpAnalyzer(project);
    }

    @AfterEach
    void tearDown() {
        if (project != null) project.close();
    }

    @Test
    void explicitUpdate() throws IOException {
        assertTrue(analyzer.getDefinitions("foo").stream().findFirst().isPresent());
        assertTrue(analyzer.getDefinitions("bar").stream().findFirst().isEmpty());

        new ProjectFile(project.getRoot(), "foo.php")
                .write(
                        """
                <?php
                function foo(): int { return 1; }
                function bar(): int { return 2; }
                """);

        var file = AnalyzerUtil.getFileFor(analyzer, "foo").orElseThrow();
        analyzer = analyzer.update(Set.of(file));

        assertTrue(analyzer.getDefinitions("bar").stream().findFirst().isPresent());
    }

    @Test
    void autoDetect() throws IOException {
        new ProjectFile(project.getRoot(), "foo.php")
                .write(
                        """
                <?php
                function foo(): int { return 1; }
                function baz(): int { return 3; }
                """);
        analyzer = analyzer.update();
        assertTrue(analyzer.getDefinitions("baz").stream().findFirst().isPresent());

        var file = AnalyzerUtil.getFileFor(analyzer, "foo").orElseThrow();
        Files.deleteIfExists(file.absPath());
        analyzer = analyzer.update();
        assertTrue(analyzer.getDefinitions("foo").stream().findFirst().isEmpty());
    }
}
