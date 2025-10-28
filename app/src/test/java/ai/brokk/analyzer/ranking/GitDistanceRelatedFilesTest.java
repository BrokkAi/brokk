package ai.brokk.analyzer.ranking;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitDistance;
import ai.brokk.git.GitRepo;
import ai.brokk.testutil.TestProject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitDistanceRelatedFilesTest {

    private static final Logger logger = LoggerFactory.getLogger(GitDistanceRelatedFilesTest.class);

    private static JavaAnalyzer analyzer;

    private static TestProject testProject;

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

        var results = GitDistance.getRelatedFiles((GitRepo) testProject.getRepo(), seedWeights, 10, false);
        assertFalse(results.isEmpty(), "PMI should return results");

        var user = results.stream()
                .filter(r -> r.file().getFileName().equals("User.java"))
                .findFirst();
        assertTrue(user.isPresent(), results.toString());

        var notification = results.stream()
                .filter(r -> r.file().getFileName().equals("NotificationService.java"))
                .findFirst();

        // PMI should emphasize genuinely related files over loosely related ones
        notification.ifPresent(
                fileRelevance -> assertTrue(user.get().score() > fileRelevance.score(), results.toString()));
    }

    @Test
    public void testPMINoSeedWeights() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");
        assertNotNull(testPath, "Test path should not be null");

        // FIXME 793
        var results = GitDistance.getRelatedFiles((GitRepo) testProject.getRepo(), Map.of(), 10, false);
        assertTrue(results.isEmpty(), "Empty seed weights should yield empty PMI results");
    }

    @Test
    public void testPMIReversed() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");
        assertNotNull(testPath, "Test path should not be null");
        var testFile = new ProjectFile(testProject.getRoot(), "UserService.java");
        var seedWeights = Map.of(testFile, 1.0);

        var results = GitDistance.getRelatedFiles((GitRepo) testProject.getRepo(), seedWeights, 10, true);
        assertFalse(results.isEmpty(), "PMI should return results");

        // Verify ascending order when reversed=true
        for (int i = 1; i < results.size(); i++) {
            assertTrue(
                    results.get(i - 1).score() <= results.get(i).score(),
                    "Results must be sorted ascending when reversed=true");
        }
    }

    @Test
    public void pmiCanonicalizesRenames() throws Exception {
        var tempDir = Files.createTempDirectory("brokk-pmi-rename-test-");
        try {
            // Initialize repo
            try (var git = Git.init().setDirectory(tempDir.toFile()).call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Test User");
                config.setString("user", null, "email", "test@example.com");
                config.save();

                // Create initial files: A.java and UserService.java, commit together
                write(
                        tempDir.resolve("A.java"),
                        """
                        public class A {
                            public String id() { return "a"; }
                        }
                        """);
                write(
                        tempDir.resolve("UserService.java"),
                        """
                        public class UserService {
                            void useA() { new A().id(); }
                        }
                        """);
                git.add()
                        .addFilepattern("A.java")
                        .addFilepattern("UserService.java")
                        .call();
                git.commit()
                        .setMessage("Initial A and UserService")
                        .setSign(false)
                        .call();

                // Modify both together once more (strengthen co-change)
                write(tempDir.resolve("A.java"), Files.readString(tempDir.resolve("A.java")) + "\n// tweak\n");
                write(
                        tempDir.resolve("UserService.java"),
                        Files.readString(tempDir.resolve("UserService.java")) + "\n// tweak\n");
                git.add()
                        .addFilepattern("A.java")
                        .addFilepattern("UserService.java")
                        .call();
                git.commit()
                        .setMessage("Co-change A and UserService")
                        .setSign(false)
                        .call();

                // Rename A.java -> Account.java (git will detect rename on similarity)
                var src = tempDir.resolve("A.java");
                var dst = tempDir.resolve("Account.java");
                Files.move(src, dst);
                git.rm().addFilepattern("A.java").call();
                git.add().addFilepattern("Account.java").call();
                git.commit().setMessage("Rename A to Account").setSign(false).call();

                // Co-change Account.java and UserService.java
                write(dst, Files.readString(dst) + "\n// more changes after rename\n");
                write(
                        tempDir.resolve("UserService.java"),
                        Files.readString(tempDir.resolve("UserService.java")) + "\n// integrate Account\n");
                git.add()
                        .addFilepattern("Account.java")
                        .addFilepattern("UserService.java")
                        .call();
                git.commit()
                        .setMessage("Co-change Account and UserService")
                        .setSign(false)
                        .call();
            }

            // Use GitRepo + PMI
            try (var repo = new GitRepo(tempDir)) {
                // PMI seeded with UserService should surface Account.java (not A.java)
                var newPf = new ProjectFile(tempDir, Path.of("Account.java"));
                var seed = new ProjectFile(tempDir, Path.of("UserService.java"));
                var results = GitDistance.getRelatedFiles(repo, Map.of(seed, 1.0), 10, false);
                assertFalse(results.isEmpty(), "PMI should return results");

                var anyOld =
                        results.stream().anyMatch(r -> r.file().getFileName().equals("A.java"));
                assertFalse(anyOld, "Old path A.java must not appear in PMI results after canonicalization");

                var account =
                        results.stream().filter(r -> r.file().equals(newPf)).findFirst();
                assertTrue(account.isPresent(), "Account.java should appear in PMI results");
            }
        } finally {
            // best-effort cleanup of .git to free locks on Windows and then delete temp dir
            GitDistanceTestSuite.teardownGitRepository(tempDir);
            if (Files.exists(tempDir)) {
                try (var walk = Files.walk(tempDir)) {
                    walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                }
            }
        }
    }

    private static void write(Path p, String content) throws Exception {
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }
}
