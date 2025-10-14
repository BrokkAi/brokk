package io.github.jbellis.brokk.analyzer.ranking;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.analyzer.JavaAnalyzer;
import io.github.jbellis.brokk.analyzer.Languages;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitDistance;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitDistancePMITest {

    private static final Logger logger = LoggerFactory.getLogger(GitDistancePMITest.class);

    @SuppressWarnings("NullAway.Init")
    private static JavaAnalyzer analyzer;

    @SuppressWarnings("NullAway.Init")
    private static TestProject testProject;

    @SuppressWarnings("NullAway.Init")
    private static Path testPath;

    @BeforeAll
    public static void setup() throws Exception {
        var testResourcePath = Path.of("src/test/resources/testcode-git-rank-java")
                .toAbsolutePath()
                .normalize();
        assertTrue(Files.exists(testResourcePath), "Test resource directory 'testcode-git-rank-java' not found.");

        // Prepare a git history that matches the co-change patterns expected by the assertions
        testPath = GitDistanceTestSuite.setupGitHistory(testResourcePath);

        var testRepo = new GitRepo(testPath);
        testProject = new TestProjectWithRepo(testPath, Languages.JAVA, testRepo);
        logger.debug("Setting up analyzer with test code from {}", testPath);
        analyzer = new JavaAnalyzer(testProject);
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
        var testFile = new ProjectFile(testProject.getRoot(), "UserService.java");
        var seedWeights = Map.of(testFile, 1.0);

        var results = GitDistance.getPMI((GitRepo) testProject.getRepo(), seedWeights, 10, false);
        assertFalse(results.isEmpty(), "PMI should return results");

        var userService =
                results.stream().filter(r -> r.file().equals(testFile)).findFirst();
        assertTrue(userService.isPresent(), "UserService should be included in PMI results");

        var user = results.stream()
                .filter(r -> r.file().getFileName().equals("User.java"))
                .findFirst();
        assertTrue(user.isPresent(), "User should be included in PMI results");

        var notification = results.stream()
                .filter(r -> r.file().getFileName().equals("NotificationService.java"))
                .findFirst();

        // PMI should emphasize genuinely related files over loosely related ones
        notification.ifPresent(fileRelevance -> assertTrue(
                user.get().score() > fileRelevance.score(), "User should rank higher than NotificationService by PMI"));
    }

    @Test
    public void testPMINoSeedWeights() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");
        assertNotNull(testPath, "Test path should not be null");

        // FIXME 793
        var results = GitDistance.getPMI((GitRepo) testProject.getRepo(), Map.of(), 10, false);
        assertTrue(results.isEmpty(), "Empty seed weights should yield empty PMI results");
    }

    @Test
    public void testPMIReversed() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");
        assertNotNull(testPath, "Test path should not be null");
        var testFile = new ProjectFile(testProject.getRoot(), "UserService.java");
        var seedWeights = Map.of(testFile, 1.0);

        var results = GitDistance.getPMI((GitRepo) testProject.getRepo(), seedWeights, 10, true);
        assertFalse(results.isEmpty(), "PMI should return results");

        // Verify ascending order when reversed=true
        for (int i = 1; i < results.size(); i++) {
            assertTrue(
                    results.get(i - 1).score() <= results.get(i).score(),
                    "Results must be sorted ascending when reversed=true");
        }
    }
}
