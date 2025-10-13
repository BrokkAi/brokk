package io.github.jbellis.brokk.analyzer.ranking;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.Languages;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitDistance;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.git.LocalFileRepo;
import io.github.jbellis.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
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
        testProject = new TestProjectWithRepo(testPath, Languages.JAVA, testRepo);
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
        var results = GitDistance.getMostImportantFilesScored(testRepo, 10);
        assertFalse(results.isEmpty(), "Results should not be empty");

        for (int i = 1; i < results.size(); i++) {
            assertTrue(
                    results.get(i - 1).score() >= results.get(i).score(), "Results must be sorted descending by score");
        }

        var scores =
                results.stream().collect(Collectors.toMap(r -> r.file().getFileName(), IAnalyzer.FileRelevance::score));

        assertEquals("UserService.java", results.getFirst().file().getFileName());

        assertTrue(scores.containsKey("User.java"), "User.java should be in the results");
        assertTrue(
                scores.get("UserService.java") > scores.get("User.java"),
                "UserService.java score should be higher than User.java score");

        assertTrue(scores.containsKey("UserRepository.java"), "UserRepository.java should be in the results");
        assertTrue(
                scores.get("User.java") > scores.get("UserRepository.java"),
                "User.java score should be higher than UserRepository.java score");
    }

    @Test
    public void testSortByImportanceEmpty() {
        assertNotNull(testRepo, "GitRepo should be initialized");
        var results = GitDistance.sortByImportance(List.of(), testRepo);
        assertTrue(results.isEmpty(), "Empty input should yield empty results");
    }

    @Test
    public void testSortByImportanceSubset() {
        assertNotNull(testRepo, "GitRepo should be initialized");
        var user = new ProjectFile(testProject.getRoot(), "User.java");
        var userRepo = new ProjectFile(testProject.getRoot(), "UserRepository.java");
        var userService = new ProjectFile(testProject.getRoot(), "UserService.java");

        var filesToSort = List.of(user, userRepo, userService);
        var results = GitDistance.sortByImportance(filesToSort, testRepo);

        assertFalse(results.isEmpty(), "Results should not be empty");
        assertEquals(3, results.size(), "Should return all 3 input files");

        assertEquals(userService, results.get(0), "UserService should be most important");
        assertEquals(user, results.get(1), "User should be second");
        assertEquals(userRepo, results.get(2), "UserRepository should be third");
    }

    @Test
    public void testSortByImportanceAllFiles() {
        assertNotNull(testRepo, "GitRepo should be initialized");
        var allFiles = testRepo.getTrackedFiles().stream()
                .sorted(Comparator.comparing(BrokkFile::getFileName))
                .toList();

        var results = GitDistance.sortByImportance(allFiles, testRepo);

        assertFalse(results.isEmpty(), "Results should not be empty");
        assertEquals(allFiles.size(), results.size(), "Should return all input files");

        for (int i = 1; i < results.size(); i++) {
            var prev = results.get(i - 1);
            var curr = results.get(i);
            assertTrue(
                    allFiles.contains(prev) && allFiles.contains(curr), "All results should be from the input files");
        }

        assertEquals("UserService.java", results.getFirst().getFileName(), "UserService should be most important");
    }

    @Test
    public void testSortByImportanceIncludesUnscoredFilesWithLocalRepo() throws Exception {
        Path temp = Files.createTempDirectory("brokk-localrepo-test")
                .toAbsolutePath()
                .normalize();
        try {
            // Create a few files in a non-git directory
            var a = new ProjectFile(temp, "A.java");
            var b = new ProjectFile(temp, "B.java");
            var c = new ProjectFile(temp, "C.java");

            a.write("class A {}");
            b.write("class B {}");
            c.write("class C {}");

            IGitRepo repo = new LocalFileRepo(temp);

            var input = List.of(a, b, c);
            var results = GitDistance.sortByImportance(input, repo);

            // Should not be empty and should include all input files (since none have scores)
            assertFalse(results.isEmpty(), "Results should not be empty");
            assertEquals(input.size(), results.size(), "All input files should be present in the results");

            // With no git history, order should be preserved
            assertEquals(input, results, "Order should be preserved when there are no scores");
        } finally {
            deleteRecursively(temp);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted((p1, p2) -> p2.getNameCount() - p1.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
