package io.github.jbellis.brokk.analyzer.ranking;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.analyzer.JavaTreeSitterAnalyzer;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.git.GitDistance;
import io.github.jbellis.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaTreeSitterAnalyzerGitPageRankTest {

    private static final Logger logger = LoggerFactory.getLogger(JavaTreeSitterAnalyzerGitPageRankTest.class);

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
        analyzer = new JavaTreeSitterAnalyzer(testProject, new HashSet<>());
    }

    @AfterAll
    public static void teardown() {
        GitDistanceTestSuite.teardownGitRepository(testPath);
        if (testProject != null) {
            testProject.close();
        }
    }

    @Test
    public void testGitRankWithCoOccurrence() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");

        var projectRoot = testPath;

        // Create seed weights favoring UserService
        var seedWeights = Map.of("com.example.service.UserService", 1.0);

        // Run GitRank
        var results = GitDistance.getPagerank(analyzer, projectRoot, seedWeights, 10, false);

        assertFalse(results.isEmpty(), "GitRank should return results");
        logger.info("GitRank results: {}", results);

        // UserService should rank highly due to its central role and seed weight
        var userServiceResult = results.stream()
                .filter(r -> r.unit().fqName().equals("com.example.service.UserService"))
                .findFirst();
        assertTrue(userServiceResult.isPresent(), "UserService should be in results");

        // User should also rank well due to frequent co-occurrence
        var userResult = results.stream()
                .filter(r -> r.unit().fqName().equals("com.example.model.User"))
                .findFirst();
        assertTrue(userResult.isPresent(), "User should be in results");

        // Verify that central components rank higher than isolated ones
        var notificationResult = results.stream()
                .filter(r -> r.unit().fqName().equals("com.example.service.NotificationService"))
                .findFirst();

        if (notificationResult.isPresent() && userResult.isPresent()) {
            assertTrue(
                    userResult.get().score() > notificationResult.get().score(),
                    "User should rank higher than NotificationService due to more co-occurrences");
        }
    }

    @Test
    public void testGitRankWithNoSeedWeights() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");

        var projectRoot = testPath;

        // Run GitRank with no seed weights
        assertNotNull(projectRoot);
        var results = GitDistance.getPagerank(analyzer, projectRoot, Map.of(), 5, false);

        assertFalse(results.isEmpty(), "GitRank should return results even without seed weights");
        logger.info("GitRank results (no seeds): {}", results);

        // All results should have positive scores
        results.forEach(result -> assertTrue(result.score() > 0, "All results should have positive scores"));
    }

    @Test
    public void testGitRankReversed() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");

        var projectRoot = testPath;

        var seedWeights = Map.of("com.example.service.UserService", 1.0);

        // Run GitRank in reversed order (lowest scores first)
        assertNotNull(projectRoot);
        var results = GitDistance.getPagerank(analyzer, projectRoot, seedWeights, 5, true);

        assertFalse(results.isEmpty(), "GitRank should return results");

        // Verify results are in ascending order of scores
        for (int i = 1; i < results.size(); i++) {
            assertTrue(
                    results.get(i - 1).score() <= results.get(i).score(),
                    "Results should be in ascending order when reversed=true");
        }
    }
}
