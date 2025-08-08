package io.github.jbellis.brokk.analyzer.ranking;

import io.github.jbellis.brokk.analyzer.JavaTreeSitterAnalyzer;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.git.GitDistance;
import io.github.jbellis.brokk.testutil.TestProject;
import org.eclipse.jgit.api.Git;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class JavaTreeSitterAnalyzerGitPageRankTest {

    private final static Logger logger = LoggerFactory.getLogger(JavaTreeSitterAnalyzerGitPageRankTest.class);

    @Nullable
    private static JavaTreeSitterAnalyzer analyzer;
    @Nullable
    private static TestProject testProject;
    @Nullable
    private static Path testPath;

    @BeforeAll
    public static void setup() throws Exception {
        testPath = Path.of("src/test/resources/testcode-git-rank-java").toAbsolutePath().normalize();
        assertTrue(Files.exists(testPath), "Test resource directory 'testcode-git-rank-java' not found.");

        // Initialize git repository and create commits with co-occurrence patterns
        setupGitHistory(testPath);

        testProject = new TestProject(testPath, Language.JAVA);
        logger.debug("Setting up analyzer with test code from {}", testPath.toAbsolutePath().normalize());
        analyzer = new JavaTreeSitterAnalyzer(testProject, new HashSet<>());
    }

    private static void teardownGitRepository() {
        if (testPath != null) {
            Path gitDir = testPath.resolve(".git");
            if (Files.exists(gitDir)) {
                try (final var walk = Files.walk(gitDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(java.io.File::delete);
                } catch (Exception e) {
                    logger.warn("Failed to delete git directory: {}", e.getMessage());
                }
            }
        }
    }

    @AfterAll
    public static void teardown() {
        teardownGitRepository();
        if (testProject != null) {
            testProject.close();
        }
    }

    private static void setupGitHistory(Path testPath) throws Exception {
        teardownGitRepository(); // start fresh
        try (var git = Git.init().setDirectory(testPath.toFile()).call()) {
            // Configure git user for commits
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.save();

            // Commit 1: User and UserRepository together (creates User-UserRepository edge)
            git.add().addFilepattern("User.java").call();
            git.add().addFilepattern("UserRepository.java").call();
            git.commit().setMessage("Initial user model and repository").setSign(false).call();

            // Commit 2: UserService with User and UserRepository (strengthens existing edges, adds UserService)
            git.add().addFilepattern("UserService.java").call();
            git.commit().setMessage("Add user service layer").setSign(false).call();

            // Commit 3: Update User and UserService together (strengthens User-UserService edge)
            Files.writeString(testPath.resolve("User.java"),
                    Files.readString(testPath.resolve("User.java")) + "\n    // Added toString method stub\n");
            Files.writeString(testPath.resolve("UserService.java"),
                    Files.readString(testPath.resolve("UserService.java")) + "\n    // Added validation\n");
            git.add().addFilepattern("User.java").call();
            git.add().addFilepattern("UserService.java").call();
            git.commit().setMessage("Update user model and service").setSign(false).call();

            // Commit 4: NotificationService standalone (creates isolated component)
            git.add().addFilepattern("NotificationService.java").call();
            git.commit().setMessage("Add notification service").setSign(false).call();

            // Commit 5: UserService and NotificationService together (creates UserService-NotificationService edge)
            Files.writeString(testPath.resolve("UserService.java"),
                    Files.readString(testPath.resolve("UserService.java")) + "\n    // Added notification integration\n");
            git.add().addFilepattern("UserService.java").call();
            git.add().addFilepattern("NotificationService.java").call();
            git.commit().setMessage("Integrate notifications with user service").setSign(false).call();

            // Commit 6: ValidationService alone
            git.add().addFilepattern("ValidationService.java").call();
            git.commit().setMessage("Add validation service").setSign(false).call();

            // Commit 7: User, UserService, and ValidationService together (creates multiple edges)
            Files.writeString(testPath.resolve("User.java"),
                    Files.readString(testPath.resolve("User.java")) + "\n    // Added validation\n");
            git.add().addFilepattern("User.java").call();
            git.add().addFilepattern("UserService.java").call();
            git.add().addFilepattern("ValidationService.java").call();
            git.commit().setMessage("Add validation to user workflows").setSign(false).call();

            logger.debug("Created git history with 7 commits for GitRank testing");
        }
    }

    @Test
    public void testGitRankWithCoOccurrence() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");

        var projectRoot = testPath;

        // Create seed weights favoring UserService
        var seedWeights = Map.of(
                "com.example.service.UserService", 1.0
        );

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
            assertTrue(userResult.get().score() > notificationResult.get().score(),
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
        results.forEach(result ->
                assertTrue(result.score() > 0, "All results should have positive scores"));
    }

    @Test
    public void testGitRankReversed() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");

        var projectRoot = testPath;

        var seedWeights = Map.of(
                "com.example.service.UserService", 1.0
        );

        // Run GitRank in reversed order (lowest scores first)
        assertNotNull(projectRoot);
        var results = GitDistance.getPagerank(analyzer, projectRoot, seedWeights, 5, true);

        assertFalse(results.isEmpty(), "GitRank should return results");

        // Verify results are in ascending order of scores
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i - 1).score() <= results.get(i).score(),
                    "Results should be in ascending order when reversed=true");
        }
    }
}