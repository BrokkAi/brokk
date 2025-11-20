package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.util.FileUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for GitHub-style dependency auto-update mechanics.
 *
 * <p>These tests exercise {@link AbstractProject#updateGitDependencyOnDisk(ProjectFile,
 * AbstractProject.DependencyMetadata)} using a local Git repository as the remote.
 */
class GitDependencyAutoUpdateTest {

    private Path tempRoot;
    private Path remoteRepoDir;
    private Git remoteGit;

    @BeforeEach
    void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("brokk-git-dep-update-");
        Files.createDirectories(tempRoot.resolve(AbstractProject.BROKK_DIR).resolve(AbstractProject.DEPENDENCIES_DIR));

        remoteRepoDir = Files.createTempDirectory("brokk-git-remote-");
        remoteGit = Git.init().setDirectory(remoteRepoDir.toFile()).call();

        // Initial commit in the remote repository
        Files.writeString(remoteRepoDir.resolve("file1.txt"), "v1");
        remoteGit.add().addFilepattern(".").call();
        remoteGit
                .commit()
                .setMessage("initial")
                .setAuthor("Test", "test@example.com")
                .call();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (remoteGit != null) {
            remoteGit.close();
        }
        if (remoteRepoDir != null && Files.exists(remoteRepoDir)) {
            FileUtil.deleteRecursively(remoteRepoDir);
        }
        if (tempRoot != null && Files.exists(tempRoot)) {
            FileUtil.deleteRecursively(tempRoot);
        }
    }

    @Test
    void updateGitDependencyOnDisk_shouldReplaceFilesAndReportChanges() throws Exception {
        var project = new MainProject(tempRoot);

        Path dependenciesRoot = tempRoot.resolve(AbstractProject.BROKK_DIR).resolve(AbstractProject.DEPENDENCIES_DIR);
        Path depDir = dependenciesRoot.resolve("test-dep");

        String remoteUrl = remoteRepoDir.toUri().toString();
        String branch = remoteGit.getRepository().getBranch();

        // Seed the dependency directory with an initial clone, mirroring ImportDependencyDialog behavior.
        ai.brokk.git.GitRepoFactory.cloneRepo(remoteUrl, depDir, 1, branch);

        // Remove .git metadata as ImportDependencyDialog.performGitImport() does.
        Path gitDir = depDir.resolve(".git");
        if (Files.exists(gitDir)) {
            FileUtil.deleteRecursively(gitDir);
        }

        // Record dependency metadata pointing at our local "remote" and branch.
        AbstractProject.writeGitDependencyMetadata(depDir, remoteUrl, branch);

        // Mutate the remote repository: add a new file and commit.
        Files.writeString(remoteRepoDir.resolve("file2.txt"), "new");
        remoteGit.add().addFilepattern(".").call();
        remoteGit
                .commit()
                .setMessage("add file2")
                .setAuthor("Test", "test@example.com")
                .call();

        // Build the ProjectFile for the on-disk dependency root.
        var depProjectFile = new ProjectFile(
                project.getMasterRootPathForConfig(),
                project.getMasterRootPathForConfig().relativize(depDir));

        var metadataOpt = AbstractProject.readDependencyMetadata(depDir);
        assertTrue(metadataOpt.isPresent(), "Expected metadata to be present for Git dependency");

        Set<ProjectFile> changedFiles = project.updateGitDependencyOnDisk(depProjectFile, metadataOpt.get());

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
