package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitTestCleanupUtil;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.MainProject;
import ai.brokk.util.DependencyUpdater;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for GitHub-style dependency auto-update mechanics.
 *
 * <p>These tests exercise {@link DependencyUpdater#updateGitDependencyOnDisk(ai.brokk.IProject, ProjectFile,
 * DependencyUpdater.DependencyMetadata)} using a local Git repository as the remote.
 */
class GitDependencyAutoUpdateTest {

    @TempDir
    Path tempRoot;

    @TempDir
    Path remoteRepoDir;

    private Git remoteGit;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tempRoot.resolve(AbstractProject.BROKK_DIR).resolve(AbstractProject.DEPENDENCIES_DIR));

        remoteGit = Git.init().setDirectory(remoteRepoDir.toFile()).call();

        // Initial commit in the remote repository
        Files.writeString(remoteRepoDir.resolve("file1.txt"), "v1");
        remoteGit.add().addFilepattern(".").call();
        remoteGit
                .commit()
                .setMessage("initial")
                .setAuthor("Test", "test@example.com")
                .setSign(false)
                .call();
    }

    @AfterEach
    void tearDown() {
        // Use GitTestCleanupUtil for robust cleanup; @TempDir handles directory deletion
        GitTestCleanupUtil.cleanupGitResources(null, remoteGit);
    }

    @Test
    void updateGitDependencyOnDisk_shouldReplaceFilesAndReportChanges() throws Exception {
        var project = new MainProject(tempRoot);

        Path dependenciesRoot = tempRoot.resolve(AbstractProject.BROKK_DIR).resolve(AbstractProject.DEPENDENCIES_DIR);
        Path depDir = dependenciesRoot.resolve("test-dep");

        String remoteUrl = remoteRepoDir.toUri().toString();
        String branch = remoteGit.getRepository().getBranch();

        // Seed the dependency directory with an initial clone, mirroring ImportDependencyDialog behavior.
        try (var clonedRepo = ai.brokk.git.GitRepoFactory.cloneRepo(remoteUrl, depDir, 1, branch)) {
            // Close GitRepo to release file handles
        }

        // Remove .git metadata as ImportDependencyDialog.performGitImport() does.
        // On Windows, deletion may fail due to lingering file handles; this is non-fatal for the test.
        Path gitDir = depDir.resolve(".git");
        if (Files.exists(gitDir)) {
            try {
                GitTestCleanupUtil.forceDeleteDirectory(gitDir);
            } catch (Exception e) {
                System.err.println("Could not delete .git directory (continuing): " + e.getMessage());
            }
        }

        // Record dependency metadata pointing at our local "remote" and branch.
        DependencyUpdater.writeGitDependencyMetadata(depDir, remoteUrl, branch, null);

        // Mutate the remote repository: add a new file and commit.
        Files.writeString(remoteRepoDir.resolve("file2.txt"), "new");
        remoteGit.add().addFilepattern(".").call();
        remoteGit
                .commit()
                .setMessage("add file2")
                .setAuthor("Test", "test@example.com")
                .setSign(false)
                .call();

        // Build the ProjectFile for the on-disk dependency root.
        var depProjectFile = new ProjectFile(
                project.getMasterRootPathForConfig(),
                project.getMasterRootPathForConfig().relativize(depDir));

        var metadataOpt = DependencyUpdater.readDependencyMetadata(depDir);
        assertTrue(metadataOpt.isPresent(), "Expected metadata to be present for Git dependency");

        Set<ProjectFile> changedFiles =
                DependencyUpdater.updateGitDependencyOnDisk(project, depProjectFile, metadataOpt.get());

        assertFalse(changedFiles.isEmpty(), "Changed files set should not be empty after remote update");

        var relPaths = changedFiles.stream()
                .map(ProjectFile::getRelPath)
                .map(Path::toString)
                .collect(Collectors.toSet());

        String expectedNewFileRel = project.getMasterRootPathForConfig()
                .relativize(depDir.resolve("file2.txt"))
                .toString();

        assertTrue(relPaths.contains(expectedNewFileRel), "Changed files should contain newly added file2.txt");

        assertEquals(
                "new",
                Files.readString(depDir.resolve("file2.txt")),
                "Local dependency should contain updated contents from remote");
    }
}
