package io.github.jbellis.brokk.analyzer.ranking;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.analyzer.JavaTreeSitterAnalyzer;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.git.GitDistance;
import io.github.jbellis.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaTreeSitterAnalyzerGitInverseCooccurenceTest {

    private static final Logger logger = LoggerFactory.getLogger(JavaTreeSitterAnalyzerGitInverseCooccurenceTest.class);

    @Nullable
    private static JavaTreeSitterAnalyzer analyzer;

    @Nullable
    private static TestProject testProject;

    @Nullable
    private static Path testPath;

    @BeforeAll
    public static void setup() throws Exception {
        final var testResourcePath = Path.of("src/test/resources/testcode-git-rank-java")
                .toAbsolutePath()
                .normalize();
        assertTrue(Files.exists(testResourcePath), "Test resource directory 'testcode-git-rank-java' not found.");

        // Initialize git repository and create commits with co-occurrence patterns
        testPath = GitDistanceTestSuite.setupGitHistory(testResourcePath);

        testProject = new TestProject(testPath, Language.JAVA);
        logger.debug(
                "Setting up analyzer with test code from {}",
                testPath.toAbsolutePath().normalize());
        analyzer = new JavaTreeSitterAnalyzer(testProject);
    }

    @AfterAll
    public static void teardown() {
        GitDistanceTestSuite.teardownGitRepository(testPath);
        if (testProject != null) {
            testProject.close();
        }
    }

    @Test
    public void testInverseCooccurrenceWithSeedWeights() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");
        assertNotNull(testPath, "Test path should not be null");

        var projectRoot = testPath;
        var seedWeights = Map.of("com.example.service.UserService", 1.0);

        var results = GitDistance.getInverseFileCountCooccurrence(analyzer, projectRoot, seedWeights, 10, false);

        assertFalse(results.isEmpty(), "Inverse co-occurrence should return results");

        var userServiceResult = results.stream()
                .filter(r -> r.unit().fqName().equals("com.example.service.UserService"))
                .findFirst();
        assertTrue(userServiceResult.isPresent(), "UserService should be in results");

        var userResult = results.stream()
                .filter(r -> r.unit().fqName().equals("com.example.model.User"))
                .findFirst();
        assertTrue(userResult.isPresent(), "User should be in results");

        var notificationResult = results.stream()
                .filter(r -> r.unit().fqName().equals("com.example.service.NotificationService"))
                .findFirst();

        if (notificationResult.isPresent()) {
            assertTrue(
                    userResult.get().score() > notificationResult.get().score(),
                    "User should rank higher than NotificationService due to more co-occurrences");
        }
    }

    @Test
    public void testInverseCooccurrenceNoSeedWeights() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");
        assertNotNull(testPath, "Test path should not be null");

        var results = GitDistance.getInverseFileCountCooccurrence(analyzer, testPath, Map.of(), 10, false);
        assertTrue(results.isEmpty(), "No seed weights should produce empty results");
    }

    @Test
    public void testInverseCooccurrenceReversed() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");
        assertNotNull(testPath, "Test path should not be null");

        var seedWeights = Map.of("com.example.service.UserService", 1.0);

        var results = GitDistance.getInverseFileCountCooccurrence(analyzer, testPath, seedWeights, 10, true);
        assertFalse(results.isEmpty(), "Inverse co-occurrence should return results");

        for (int i = 1; i < results.size(); i++) {
            assertTrue(
                    results.get(i - 1).score() <= results.get(i).score(),
                    "Results should be in ascending order when reversed=true");
        }
    }
}
