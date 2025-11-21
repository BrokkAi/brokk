package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.util.DependencyUpdater;
import ai.brokk.util.FileUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the multi-dependency auto-update coordinator in {@link AbstractProject}.
 *
 * <p>These tests exercise {@link AbstractProject#autoUpdateDependenciesOnce(boolean, boolean)}
 * to ensure it respects flags, ignores dependencies without metadata, and updates only the
 * appropriate dependency kinds.
 */
class DependencyAutoUpdateCoordinatorTest {

    private Path tempRoot;
    private Path dependenciesRoot;
    private Path localSourceDir;

    private Path remoteRepoDir;
    private Git remoteGit;

    @BeforeEach
    void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("brokk-dep-auto-coord-");
        dependenciesRoot = tempRoot.resolve(AbstractProject.BROKK_DIR).resolve(AbstractProject.DEPENDENCIES_DIR);
        Files.createDirectories(dependenciesRoot);

        // Local source used for LOCAL_PATH dependencies
        localSourceDir = Files.createDirectory(tempRoot.resolve("external-lib"));
        Files.writeString(localSourceDir.resolve("LocalFoo.java"), "class LocalFooV1 {}");
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

    private void initRemoteRepo() throws Exception {
        remoteRepoDir = Files.createTempDirectory("brokk-dep-auto-remote-");
        remoteGit = Git.init().setDirectory(remoteRepoDir.toFile()).call();
        Files.writeString(remoteRepoDir.resolve("file1.txt"), "v1");
        remoteGit.add().addFilepattern(".").call();
        remoteGit
                .commit()
                .setMessage("initial")
                .setAuthor("Test", "test@example.com")
                .call();
    }

    private void seedLocalDependency(Path depDir) throws IOException {
        Files.createDirectories(depDir);
        Files.copy(localSourceDir.resolve("LocalFoo.java"), depDir.resolve("LocalFoo.java"));
        DependencyUpdater.writeLocalPathDependencyMetadata(
                depDir, localSourceDir.toAbsolutePath().normalize());
    }

    private void seedGitDependency(Path depDir) throws Exception {
        initRemoteRepo();
        String remoteUrl = remoteRepoDir.toUri().toString();
        String branch = remoteGit.getRepository().getBranch();

        ai.brokk.git.GitRepoFactory.cloneRepo(remoteUrl, depDir, 1, branch);
        Path gitDir = depDir.resolve(".git");
        if (Files.exists(gitDir)) {
            FileUtil.deleteRecursively(gitDir);
        }
        DependencyUpdater.writeGitDependencyMetadata(depDir, remoteUrl, branch);
    }

    @Test
    void autoUpdateDependenciesOnce_shouldDoNothingWhenFlagsDisabled() throws Exception {
        var project = new MainProject(tempRoot);

        Path localDepDir = dependenciesRoot.resolve("local-dep");
        seedLocalDependency(localDepDir);

        // Mutate local source so an update would be detected if enabled
        Files.writeString(localSourceDir.resolve("LocalFoo.java"), "class LocalFooV2 {}");

        // Sanity check: dependency still has old contents
        assertEquals("class LocalFooV1 {}", Files.readString(localDepDir.resolve("LocalFoo.java")));

        var result = DependencyUpdater.autoUpdateDependenciesOnce(project, false, false);

        assertTrue(result.changedFiles().isEmpty(), "No files should change when flags are disabled");
        assertEquals(0, result.updatedDependencies(), "No dependencies should be reported as updated");
        assertEquals(
                "class LocalFooV1 {}",
                Files.readString(localDepDir.resolve("LocalFoo.java")),
                "Dependency contents should remain unchanged when auto-update is disabled");
    }

    @Test
    void autoUpdateDependenciesOnce_shouldUpdateOnlyLocalWhenGitDisabled() throws Exception {
        var project = new MainProject(tempRoot);

        Path localDepDir = dependenciesRoot.resolve("local-dep");
        seedLocalDependency(localDepDir);

        Path gitDepDir = dependenciesRoot.resolve("git-dep");
        seedGitDependency(gitDepDir);

        // Mutate both sources
        Files.writeString(localSourceDir.resolve("LocalFoo.java"), "class LocalFooV2 {}");
        Files.writeString(localSourceDir.resolve("LocalBar.java"), "class LocalBar {}");

        Files.writeString(remoteRepoDir.resolve("file2.txt"), "new");
        remoteGit.add().addFilepattern(".").call();
        remoteGit
                .commit()
                .setMessage("add file2")
                .setAuthor("Test", "test@example.com")
                .call();

        var result = DependencyUpdater.autoUpdateDependenciesOnce(project, true, false);

        // Local dependency should be updated
        assertEquals("class LocalFooV2 {}", Files.readString(localDepDir.resolve("LocalFoo.java")));
        assertTrue(Files.exists(localDepDir.resolve("LocalBar.java")));

        // Git dependency should remain at original state (no file2.txt)
        assertFalse(
                Files.exists(gitDepDir.resolve("file2.txt")),
                "Git dependency should not be updated when Git auto-update is disabled");

        assertEquals(1, result.updatedDependencies(), "Exactly one dependency (local) should be reported as updated");

        Set<String> changedRelPaths = result.changedFiles().stream()
                .map(ProjectFile::getRelPath)
                .map(Path::toString)
                .collect(Collectors.toSet());

        String expectedLocalBarRel = project.getMasterRootPathForConfig()
                .relativize(localDepDir.resolve("LocalBar.java"))
                .toString();
        assertTrue(
                changedRelPaths.contains(expectedLocalBarRel),
                "Changed files should include new LocalBar.java from local dependency");
    }

    @Test
    void autoUpdateDependenciesOnce_shouldUpdateOnlyGitWhenLocalDisabled() throws Exception {
        var project = new MainProject(tempRoot);

        Path localDepDir = dependenciesRoot.resolve("local-dep");
        seedLocalDependency(localDepDir);

        Path gitDepDir = dependenciesRoot.resolve("git-dep");
        seedGitDependency(gitDepDir);

        // Mutate both sources
        Files.writeString(localSourceDir.resolve("LocalFoo.java"), "class LocalFooV2 {}");

        Files.writeString(remoteRepoDir.resolve("file2.txt"), "new");
        remoteGit.add().addFilepattern(".").call();
        remoteGit
                .commit()
                .setMessage("add file2")
                .setAuthor("Test", "test@example.com")
                .call();

        var result = DependencyUpdater.autoUpdateDependenciesOnce(project, false, true);

        // Local dependency should remain unchanged
        assertEquals(
                "class LocalFooV1 {}",
                Files.readString(localDepDir.resolve("LocalFoo.java")),
                "Local dependency should not be updated when local auto-update is disabled");

        // Git dependency should be updated
        assertEquals(
                "new",
                Files.readString(gitDepDir.resolve("file2.txt")),
                "Git dependency should contain latest contents from remote");

        assertEquals(1, result.updatedDependencies(), "Exactly one dependency (Git) should be reported as updated");

        Set<String> changedRelPaths = result.changedFiles().stream()
                .map(ProjectFile::getRelPath)
                .map(Path::toString)
                .collect(Collectors.toSet());

        String expectedGitFileRel = project.getMasterRootPathForConfig()
                .relativize(gitDepDir.resolve("file2.txt"))
                .toString();
        assertTrue(
                changedRelPaths.contains(expectedGitFileRel),
                "Changed files should include new file2.txt from Git dependency");
    }

    @Test
    void autoUpdateDependenciesOnce_shouldIgnoreDependenciesWithoutMetadata() throws Exception {
        var project = new MainProject(tempRoot);

        Path localDepDir = dependenciesRoot.resolve("local-dep");
        seedLocalDependency(localDepDir);

        // Dependency without metadata
        Path noMetaDepDir = dependenciesRoot.resolve("no-meta-dep");
        Files.createDirectories(noMetaDepDir);
        Files.writeString(noMetaDepDir.resolve("Orphan.java"), "class Orphan {}");

        // Mutate local source to cause an update
        Files.writeString(localSourceDir.resolve("LocalFoo.java"), "class LocalFooV2 {}");

        var result = DependencyUpdater.autoUpdateDependenciesOnce(project, true, false);

        // Local dependency should be updated as usual
        assertEquals("class LocalFooV2 {}", Files.readString(localDepDir.resolve("LocalFoo.java")));

        // no-meta-dep should be untouched
        assertEquals(
                "class Orphan {}",
                Files.readString(noMetaDepDir.resolve("Orphan.java")),
                "Dependency without metadata should be ignored by auto-update");

        assertEquals(
                1, result.updatedDependencies(), "Only the metadata-backed dependency should be reported as updated");

        Set<String> changedRelPaths = result.changedFiles().stream()
                .map(ProjectFile::getRelPath)
                .map(Path::toString)
                .collect(Collectors.toSet());

        String orphanRel = project.getMasterRootPathForConfig()
                .relativize(noMetaDepDir.resolve("Orphan.java"))
                .toString();
        assertFalse(
                changedRelPaths.contains(orphanRel),
                "Changed files set should not include files from dependencies without metadata");
    }
}
