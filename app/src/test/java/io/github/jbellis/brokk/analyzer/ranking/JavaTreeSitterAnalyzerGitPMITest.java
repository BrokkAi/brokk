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

public class JavaTreeSitterAnalyzerGitPMITest {

    private static final Logger logger = LoggerFactory.getLogger(JavaTreeSitterAnalyzerGitPMITest.class);

    @Nullable
    private static JavaTreeSitterAnalyzer analyzer;

    @Nullable
    private static TestProject testProject;

    @Nullable
    private static Path testPath;

    @BeforeAll
    public static void setup() throws Exception {
        var testResourcePath = Path.of("src/test/resources/testcode-git-rank-java")
                .toAbsolutePath()
                .normalize();
        assertTrue(Files.exists(testResourcePath), "Test resource directory 'testcode-git-rank-java' not found.");

        // Prepare a git history that matches the co-change patterns expected by the assertions
        testPath = GitDistanceTestSuite.setupGitHistory(testResourcePath);

        testProject = new TestProject(testPath, Language.JAVA);
        logger.debug("Setting up analyzer with test code from {}", testPath);
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
    public void testPMIWithSeedWeights() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");
        assertNotNull(testPath, "Test path should not be null");

        var seedWeights = Map.of("com.example.service.UserService", 1.0);

        var results = GitDistance.getPMI(analyzer, testPath, seedWeights, 10, false);
        assertFalse(results.isEmpty(), "PMI should return results");

        var userService = results.stream()
                .filter(r -> r.unit().fqName().equals("com.example.service.UserService"))
                .findFirst();
        assertTrue(userService.isPresent(), "UserService should be included in PMI results");

        var user = results.stream()
                .filter(r -> r.unit().fqName().equals("com.example.model.User"))
                .findFirst();
        assertTrue(user.isPresent(), "User should be included in PMI results");

        var notification = results.stream()
                .filter(r -> r.unit().fqName().equals("com.example.service.NotificationService"))
                .findFirst();

        // PMI should emphasize genuinely related files over loosely related ones
        if (notification.isPresent()) {
            assertTrue(
                    user.get().score() > notification.get().score(),
                    "User should rank higher than NotificationService by PMI");
        }
    }

    @Test
    public void testPMINoSeedWeights() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");
        assertNotNull(testPath, "Test path should not be null");

        var results = GitDistance.getPMI(analyzer, testPath, Map.of(), 10, false);
        assertTrue(results.isEmpty(), "Empty seed weights should yield empty PMI results");
    }

    @Test
    public void testPMIReversed() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");
        assertNotNull(testPath, "Test path should not be null");

        var seedWeights = Map.of("com.example.service.UserService", 1.0);

        var results = GitDistance.getPMI(analyzer, testPath, seedWeights, 10, true);
        assertFalse(results.isEmpty(), "PMI should return results");

        // Verify ascending order when reversed=true
        for (int i = 1; i < results.size(); i++) {
            assertTrue(
                    results.get(i - 1).score() <= results.get(i).score(),
                    "Results must be sorted ascending when reversed=true");
        }
    }
}
