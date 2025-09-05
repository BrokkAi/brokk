package io.github.jbellis.brokk.analyzer.ranking;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.git.GitDistance;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class GitDistanceMostImportantTest {

    @SuppressWarnings("NullAway.Init")
    private static TestProject testProject;

    @SuppressWarnings("NullAway.Init")
    private static Path testPath;

    @SuppressWarnings("NullAway.Init")
    private static GitRepo testRepo;

    @BeforeAll
    public static void setup() throws Exception {
        var testResourcePath = Path.of("src/test/resources/testcode-git-rank-java")
                .toAbsolutePath()
                .normalize();
        assertTrue(Files.exists(testResourcePath), "Test resource directory 'testcode-git-rank-java' not found.");

        testPath = GitDistanceTestSuite.setupGitHistory(testResourcePath);

        testRepo = new GitRepo(testPath);
        testProject = new TestProjectWithRepo(testPath, Language.JAVA, testRepo);
    }

    @AfterAll
    public static void teardown() {
        GitDistanceTestSuite.teardownGitRepository(testPath);
        if (testProject != null) {
            testProject.close();
        }
    }

    @Test
    public void testMostImportantFiles() throws Exception {
        assertNotNull(testRepo, "GitRepo should be initialized");
        var results = GitDistance.getMostImportantFiles(testRepo, 10);
        assertFalse(results.isEmpty(), "Results should not be empty");

        // Assert that the list is sorted in descending order by score
        for (int i = 1; i < results.size(); i++) {
            assertTrue(
                    results.get(i - 1).score() >= results.get(i).score(), "Results must be sorted descending by score");
        }

        var scores = results.stream()
                .collect(Collectors.toMap(r -> r.file().getFileName().toString(), r -> r.score()));

        // Assert that the file with the highest score is UserService.java
        assertEquals("UserService.java", results.getFirst().file().getFileName().toString());

        // Assert that User.java is also present and has a high score, but lower than UserService.java
        assertTrue(scores.containsKey("User.java"), "User.java should be in the results");
        assertTrue(
                scores.get("UserService.java") > scores.get("User.java"),
                "UserService.java score should be higher than User.java score");

        // Assert that UserRepository.java is present and has a lower score than User.java
        assertTrue(scores.containsKey("UserRepository.java"), "UserRepository.java should be in the results");
        assertTrue(
                scores.get("User.java") > scores.get("UserRepository.java"),
                "User.java score should be higher than UserRepository.java score");
    }
}
