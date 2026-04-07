package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.TestGitRepo;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IssueServiceTest {

    @TempDir
    Path tempDir;

    private TestGitRepo createInitializedRepo(Path projectRoot, Path worktreeDir) throws Exception {
        Files.createDirectories(projectRoot);
        Files.createDirectories(worktreeDir);
        try (Git git = Git.init().setDirectory(projectRoot.toFile()).call()) {
            // GitRepo requires at least one commit to resolve HEAD in many operations
            Files.writeString(projectRoot.resolve("README.md"), "test");
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("Initial commit").setSign(false).call();
        }
        return new TestGitRepo(projectRoot, worktreeDir);
    }

    @Test
    void testGenerateBranchName_NewBranch() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Path worktreeDir = tempDir.resolve("worktrees");
        try (TestGitRepo repo = createInitializedRepo(projectRoot, worktreeDir)) {
            // Use deterministic seam to avoid randomness in tests while validating format.
            String branchName = IssueService.generateBranchName(42, repo, "fixed");
            assertTrue(
                    branchName.startsWith("brokk/issue-42-fixed"),
                    () -> "branchName should start with brokk/issue-42-fixed");
            // Allow optional collision suffix appended by sanitizeBranchName (e.g., -2, -3)
            assertTrue(
                    branchName.matches("brokk/issue-42-fixed(-\\d+)?"),
                    "branchName should be brokk/issue-42-fixed with optional -N collision suffix");
        }
    }

    @Test
    void testGenerateBranchName_DuplicateHandlesCollisions() throws Exception {
        Path projectRoot = tempDir.resolve("project-dup");
        Path worktreeDir = tempDir.resolve("worktrees-dup");
        try (TestGitRepo repo = createInitializedRepo(projectRoot, worktreeDir)) {
            // Use the deterministic seam to force the same proposed name so sanitizeBranchName exercises collision
            // logic.
            repo.getGit().branchCreate().setName("brokk/issue-42-deterministic").call();
            repo.invalidateCaches();

            String branchName = IssueService.generateBranchName(42, repo, "deterministic");
            // sanitizeBranchName logic appends -2, -3, etc.
            assertEquals("brokk/issue-42-deterministic-2", branchName);
        }
    }

    @Test
    void testGenerateBranchName_MultipleCollisions() throws Exception {
        Path projectRoot = tempDir.resolve("project-multi-dup");
        Path worktreeDir = tempDir.resolve("worktrees-multi-dup");
        try (TestGitRepo repo = createInitializedRepo(projectRoot, worktreeDir)) {
            // Create several existing branches and use deterministic seam to exercise multi-collision handling.
            repo.getGit().branchCreate().setName("brokk/issue-42-deterministic").call();
            repo.getGit()
                    .branchCreate()
                    .setName("brokk/issue-42-deterministic-2")
                    .call();
            repo.getGit()
                    .branchCreate()
                    .setName("brokk/issue-42-deterministic-3")
                    .call();
            repo.invalidateCaches();

            String branchName = IssueService.generateBranchName(42, repo, "deterministic");
            // Should find the next available suffix
            assertEquals("brokk/issue-42-deterministic-4", branchName);
        }
    }

    @Test
    void testBranchCreationWithExpectedName() throws Exception {
        Path projectRoot = tempDir.resolve("project-create");
        Path worktreeDir = tempDir.resolve("worktrees-create");
        try (TestGitRepo repo = createInitializedRepo(projectRoot, worktreeDir)) {
            // Verify we can create and checkout a branch proposed by the randomized generator.
            String branchName = IssueService.generateBranchNameWithRandomSuffix(101, repo);
            String prefix = "brokk/issue-101-";
            assertTrue(branchName.startsWith(prefix), () -> "branchName should start with " + prefix);

            repo.createAndCheckoutBranch(branchName, "HEAD");
            assertEquals(branchName, repo.getCurrentBranch());
        }
    }

    @Test
    void testBuildPrDescription_NonEmptySummary() {
        String input = "  Hello world  \n";
        String expected = "Hello world\n\nFixes #42";
        assertEquals(expected, IssueService.buildPrDescription(input, 42));
    }

    @Test
    void testBuildPrDescription_EmptySummary() {
        String expected = "Fixes #99";
        assertEquals(expected, IssueService.buildPrDescription("", 99));
    }

    @Test
    void testBuildPrDescription_WhitespaceOnlySummary() {
        String expected = "Fixes #7";
        assertEquals(expected, IssueService.buildPrDescription(" \n\t ", 7));
    }
}
