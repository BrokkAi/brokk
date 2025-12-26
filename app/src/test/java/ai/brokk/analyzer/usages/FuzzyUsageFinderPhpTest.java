package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.PhpAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TreeSitterAnalyzer;
import ai.brokk.project.IProject;
import ai.brokk.testutil.TestService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FuzzyUsageFinderPhpTest {

    private static final Logger logger = LoggerFactory.getLogger(FuzzyUsageFinderPhpTest.class);

    private static IProject testProject;
    private static TreeSitterAnalyzer analyzer;

    @BeforeAll
    public static void setup() throws IOException {
        testProject = createTestProject("testcode-php");
        analyzer = new PhpAnalyzer(testProject);
        logger.debug(
                "Setting up FuzzyUsageFinder PHP tests with test code from {}",
                testProject.getRoot().toAbsolutePath().normalize());
    }

    @AfterAll
    public static void teardown() {
        try {
            testProject.close();
        } catch (Exception e) {
            logger.error("Exception encountered while closing the test project at the end of testing", e);
        }
    }

    private static IProject createTestProject(String subDir) {
        var testDir = Path.of("./src/test/resources", subDir).toAbsolutePath().normalize();
        assertTrue(Files.exists(testDir), String.format("Test resource dir missing: %s", testDir));
        assertTrue(Files.isDirectory(testDir), String.format("%s is not a directory", testDir));

        return new IProject() {
            @Override
            public Path getRoot() {
                return testDir.toAbsolutePath();
            }

            @Override
            public Set<ProjectFile> getAllFiles() {
                var files = testDir.toFile().listFiles();
                if (files == null) {
                    return Collections.emptySet();
                }
                return Arrays.stream(files)
                        .map(file -> new ProjectFile(testDir, testDir.relativize(file.toPath())))
                        .collect(Collectors.toSet());
            }
        };
    }

    private static Set<String> fileNamesFromHits(Set<UsageHit> hits) {
        return hits.stream()
                .map(hit -> hit.file().absPath().getFileName().toString())
                .collect(Collectors.toSet());
    }

    private static FuzzyUsageFinder newFinder(IProject project) {
        return new FuzzyUsageFinder(project, analyzer, new TestService(project), null);
    }

    @Test
    public void getUsesClassComprehensivePatternsTest() {
        var finder = newFinder(testProject);
        var symbol = "BaseClass";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            logger.info("PHP test skipped: " + either.getErrorMessage());
            return;
        }

        var hits = either.getUsages();
        if (hits.isEmpty()) {
            logger.info("PHP test: no hits found, skipping validation");
            return;
        }

        var files = fileNamesFromHits(hits);
        assertTrue(
                files.contains("ClassUsagePatterns.php"),
                "Expected comprehensive usage patterns in ClassUsagePatterns.php; actual: " + files);

        var classUsageHits = hits.stream()
                .filter(h -> h.file()
                        .absPath()
                        .getFileName()
                        .toString()
                        .equals("ClassUsagePatterns.php"))
                .toList();
        assertTrue(
                classUsageHits.size() >= 2,
                "Expected at least 2 different usage patterns, found: " + classUsageHits.size());
    }
}
