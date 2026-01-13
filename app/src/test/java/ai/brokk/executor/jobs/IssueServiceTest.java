package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
            git.commit().setMessage("Initial commit").call();
        }
        return new TestGitRepo(projectRoot, worktreeDir);
    }

    @Test
    void testGenerateBranchName_NewBranch() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Path worktreeDir = tempDir.resolve("worktrees");
        try (TestGitRepo repo = createInitializedRepo(projectRoot, worktreeDir)) {
            String branchName = IssueService.generateBranchName(42, repo);
            assertEquals("brokk/issue-42", branchName);
        }
    }

    @Test
    void testGenerateBranchName_DuplicateHandlesCollisions() throws Exception {
        Path projectRoot = tempDir.resolve("project-dup");
        Path worktreeDir = tempDir.resolve("worktrees-dup");
        try (TestGitRepo repo = createInitializedRepo(projectRoot, worktreeDir)) {
            // Simulate existing branch by creating it directly via JGit
            // (TestGitRepo.createBranch is overridden as no-op)
            repo.getGit().branchCreate().setName("brokk/issue-42").call();
            repo.invalidateCaches();

            String branchName = IssueService.generateBranchName(42, repo);
            // sanitizeBranchName logic appends -2, -3, etc.
            assertEquals("brokk/issue-42-2", branchName);
        }
    }

    @Test
    void testGenerateBranchName_MultipleCollisions() throws Exception {
        Path projectRoot = tempDir.resolve("project-multi-dup");
        Path worktreeDir = tempDir.resolve("worktrees-multi-dup");
        try (TestGitRepo repo = createInitializedRepo(projectRoot, worktreeDir)) {
            // Create several existing branches
            repo.getGit().branchCreate().setName("brokk/issue-42").call();
            repo.getGit().branchCreate().setName("brokk/issue-42-2").call();
            repo.getGit().branchCreate().setName("brokk/issue-42-3").call();
            repo.invalidateCaches();

            String branchName = IssueService.generateBranchName(42, repo);
            // Should find the next available suffix
            assertEquals("brokk/issue-42-4", branchName);
        }
    }

    @Test
    void testBranchCreationWithExpectedName() throws Exception {
        Path projectRoot = tempDir.resolve("project-create");
        Path worktreeDir = tempDir.resolve("worktrees-create");
        try (TestGitRepo repo = createInitializedRepo(projectRoot, worktreeDir)) {
            String branchName = IssueService.generateBranchName(101, repo);
            assertEquals("brokk/issue-101", branchName);

            // Verify we can actually create and checkout this branch
            repo.createAndCheckoutBranch(branchName, "HEAD");
            assertEquals(branchName, repo.getCurrentBranch());
        }
    }
}
