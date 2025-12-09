package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitTestCleanupUtil;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.MainProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for monorepo subdirectory support - opening a subdirectory of a git repository as a Brokk project. */
public class MonorepoSubdirectoryTest {
    @TempDir
    Path tempDir;

    private Path repoRoot;
    private GitRepo gitRepo;
    private AbstractProject rootProject;
    private AbstractProject subdirProject;

    @BeforeEach
    void setUp() throws Exception {
        // Create a git repository with subdirectory structure
        repoRoot = tempDir.resolve("monorepo");
        Files.createDirectories(repoRoot);

        // Initialize git repo
        try (Git git = Git.init().setDirectory(repoRoot.toFile()).call()) {
            // Create files in root
            Path rootFile = repoRoot.resolve("root-file.txt");
            Files.writeString(rootFile, "Root level file");
            git.add().addFilepattern("root-file.txt").call();

            // Create subdirectory with files
            Path subDir = repoRoot.resolve("subproject");
            Files.createDirectories(subDir);
            Path subFile1 = subDir.resolve("sub-file1.txt");
            Path subFile2 = subDir.resolve("sub-file2.txt");
            Files.writeString(subFile1, "Subdirectory file 1");
            Files.writeString(subFile2, "Subdirectory file 2");
            git.add().addFilepattern("subproject/sub-file1.txt").call();
            git.add().addFilepattern("subproject/sub-file2.txt").call();

            // Create another subdirectory
            Path subDir2 = repoRoot.resolve("another-subproject");
            Files.createDirectories(subDir2);
            Path subFile3 = subDir2.resolve("another-file.txt");
            Files.writeString(subFile3, "Another subdirectory file");
            git.add().addFilepattern("another-subproject/another-file.txt").call();

            // Initial commit
            git.commit()
                    .setMessage("Initial commit")
                    .setAuthor("Test User", "test@example.com")
                    .setSign(false)
                    .call();
        }

        gitRepo = new GitRepo(repoRoot);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (subdirProject != null) {
            subdirProject.close();
        }
        if (rootProject != null) {
            rootProject.close();
        }
        if (gitRepo != null) {
            GitTestCleanupUtil.cleanupGitResources(gitRepo);
        }
    }

    /**
     * Tests where the config directory is placed when opening a subdirectory. Expected: Should be at the subdirectory
     * (unlike worktrees, which store config at git root).
     */
    @Test
    void testSubdirectoryProject_WhereIsConfig() throws Exception {
        Path subdirPath = repoRoot.resolve("subproject");
        subdirProject = AbstractProject.createProject(subdirPath, null);

        Path masterConfigPath = subdirProject.getMasterRootPathForConfig();
        Path projectRoot = subdirProject.getRoot();

        // Expected behavior: config should be at the subdirectory for subdirectory projects
        assertEquals(
                subdirPath.toAbsolutePath().normalize(),
                masterConfigPath,
                "Config should be stored at the subdirectory, not git repository root");
    }

    /**
     * Tests that saving properties creates .brokk directory at the subdirectory, not git root. This test ensures physical
     * files are created in the correct location.
     */
    @Test
    void testSubdirectoryProject_PhysicalConfigLocation() throws Exception {
        Path subdirPath = repoRoot.resolve("subproject");
        var mainProj = (MainProject) AbstractProject.createProject(subdirPath, null);
        subdirProject = mainProj;

        // Set a configuration property to trigger file creation, then save
        mainProj.setCommitMessageFormat("test: {message}");
        mainProj.saveProjectProperties();

        Path expectedBrokkDir = subdirPath.resolve(".brokk");
        Path wrongBrokkDir = repoRoot.resolve(".brokk");

        // Config files should be created at subdirectory
        assertTrue(Files.exists(expectedBrokkDir), ".brokk should exist at subdirectory");
        assertTrue(
                Files.exists(expectedBrokkDir.resolve("project.properties")),
                "project.properties should exist at subdirectory");

        // Config files should NOT be created at git root
        assertFalse(Files.exists(wrongBrokkDir), ".brokk should NOT exist at git root");
    }

    /**
     * Tests that llm-history is created at the subdirectory, using getMasterRootPathForConfig().
     */
    @Test
    void testSubdirectoryProject_LlmHistoryLocation() throws Exception {
        Path subdirPath = repoRoot.resolve("subproject");
        var mainProj = (MainProject) AbstractProject.createProject(subdirPath, null);
        subdirProject = mainProj;

        // Simulate what happens when Llm creates history directory
        Path historyDir = ai.brokk.Llm.getHistoryBaseDir(mainProj.getMasterRootPathForConfig());

        Path expectedHistoryDir = subdirPath.resolve(".brokk").resolve("llm-history");
        Path wrongHistoryDir = repoRoot.resolve(".brokk").resolve("llm-history");

        // LLM history should be at subdirectory
        assertEquals(expectedHistoryDir, historyDir, "llm-history should be at subdirectory");

        // Create the directory to verify physical location
        Files.createDirectories(historyDir);

        assertTrue(Files.exists(expectedHistoryDir), "llm-history should exist at subdirectory");
        assertFalse(Files.exists(wrongHistoryDir), "llm-history should NOT exist at git root");
    }

    /**
     * Regression test for git staging bug when opening subdirectory. Previously, adding files from git root when
     * projectRoot was a subdirectory would create incorrect relative paths (with ..) causing staging issues. This test
     * verifies that add() operations succeed without exceptions.
     */
    @Test
    void testSubdirectoryProject_GitAddFilesAtRoot() throws Exception {
        Path subdirPath = repoRoot.resolve("subproject");
        subdirProject = AbstractProject.createProject(subdirPath, null);

        var gitRepo = (GitRepo) subdirProject.getRepo();

        // Create files at git root that should be addable
        Path gitignoreFile = repoRoot.resolve(".gitignore");
        Path brokkFile = repoRoot.resolve(".brokk").resolve("test.txt");
        Files.createDirectories(brokkFile.getParent());
        Files.writeString(gitignoreFile, ".brokk/\n");
        Files.writeString(brokkFile, "test");

        // The key test: These add operations should succeed
        // Previously would fail or create incorrect paths due to using repository.getWorkTree()
        // instead of gitTopLevel for path relativization
        assertDoesNotThrow(() -> gitRepo.add(gitignoreFile), "Should be able to add .gitignore from git root");

        assertDoesNotThrow(
                () -> gitRepo.add(List.of(new ProjectFile(repoRoot, ".brokk/test.txt"))),
                "Should be able to add .brokk/test.txt from git root using ProjectFile");
    }

    /**
     * Tests that getTrackedFiles() correctly filters to only show files under the subdirectory. This should already
     * work based on the GitRepo implementation.
     */
    @Test
    void testSubdirectoryProject_TrackedFilesFiltering() throws Exception {
        Path subdirPath = repoRoot.resolve("subproject");
        subdirProject = AbstractProject.createProject(subdirPath, null);

        Set<ProjectFile> trackedFiles = subdirProject.getRepo().getTrackedFiles();
        Set<String> fileNames =
                trackedFiles.stream().map(ProjectFile::getFileName).collect(Collectors.toSet());

        // Should only see files in subproject, not root-file.txt or files in another-subproject
        assertTrue(fileNames.contains("sub-file1.txt"), "Should contain sub-file1.txt");
        assertTrue(fileNames.contains("sub-file2.txt"), "Should contain sub-file2.txt");
        assertFalse(fileNames.contains("root-file.txt"), "Should NOT contain root-file.txt");
        assertFalse(fileNames.contains("another-file.txt"), "Should NOT contain another-file.txt");

        assertEquals(2, fileNames.size(), "Should have exactly 2 files from subproject");
    }

    /**
     * Tests that two subdirectories opened as separate projects have independent config locations. Expected: Each
     * subdirectory should use its own directory for config, not shared at git root.
     */
    @Test
    void testSubdirectoryProject_ConfigSharing() throws Exception {
        Path subdir1Path = repoRoot.resolve("subproject");
        Path subdir2Path = repoRoot.resolve("another-subproject");

        AbstractProject project1 = null;
        AbstractProject project2 = null;

        try {
            project1 = AbstractProject.createProject(subdir1Path, null);
            project2 = AbstractProject.createProject(subdir2Path, null);

            Path config1 = project1.getMasterRootPathForConfig();
            Path config2 = project2.getMasterRootPathForConfig();

            // Each subdirectory should have its own config location
            assertNotEquals(config1, config2, "Each subdirectory should have its own config location");
            assertEquals(subdir1Path.toAbsolutePath().normalize(), config1, "Config should be at first subdirectory");
            assertEquals(subdir2Path.toAbsolutePath().normalize(), config2, "Config should be at second subdirectory");
        } finally {
            if (project1 != null) project1.close();
            if (project2 != null) project2.close();
        }
    }

    /**
     * Baseline test: opening a project at the git repository root. Config should be stored at the root (same as project
     * root).
     */
    @Test
    void testRegularProject_AtGitRoot() throws Exception {
        rootProject = AbstractProject.createProject(repoRoot, null);

        Path masterConfigPath = rootProject.getMasterRootPathForConfig();
        Path projectRoot = rootProject.getRoot();

        // For a regular project at git root, both should be the same
        assertEquals(
                projectRoot.toAbsolutePath().normalize(),
                masterConfigPath,
                "Regular project should have config at project root");
        assertEquals(
                repoRoot.toAbsolutePath().normalize(),
                masterConfigPath,
                "Regular project at git root should have config at git root");
    }

    /** Test that all files are visible when opening at git root (no filtering). */
    @Test
    void testRegularProject_AllFilesVisible() throws Exception {
        rootProject = AbstractProject.createProject(repoRoot, null);

        Set<ProjectFile> trackedFiles = rootProject.getRepo().getTrackedFiles();
        Set<String> fileNames =
                trackedFiles.stream().map(ProjectFile::getFileName).collect(Collectors.toSet());

        // Should see ALL files when opening at root
        assertTrue(fileNames.contains("root-file.txt"), "Should contain root-file.txt");
        assertTrue(fileNames.contains("sub-file1.txt"), "Should contain sub-file1.txt");
        assertTrue(fileNames.contains("sub-file2.txt"), "Should contain sub-file2.txt");
        assertTrue(fileNames.contains("another-file.txt"), "Should contain another-file.txt");

        assertEquals(4, fileNames.size(), "Should have all 4 files from entire repo");
    }

    /** Test that git operations work correctly for regular (non-subdirectory) projects. */
    @Test
    void testRegularProject_GitOperations() throws Exception {
        rootProject = AbstractProject.createProject(repoRoot, null);
        var gitRepo = (GitRepo) rootProject.getRepo();

        // Create and add a file at root
        Path testFile = repoRoot.resolve("test-file.txt");
        Files.writeString(testFile, "test content");

        // Git operations should work normally
        assertDoesNotThrow(() -> gitRepo.add(testFile), "Should be able to add file at git root");
    }

    /**
     * Critical test: Verify git operations in subdirectory only affect subdirectory files. When opening a subdirectory,
     * git add/commit should be scoped to that subdirectory, not the entire repository.
     */
    @Test
    void testSubdirectoryProject_GitOperationsScoped() throws Exception {
        Path subdirPath = repoRoot.resolve("subproject");
        subdirProject = AbstractProject.createProject(subdirPath, null);
        var gitRepo = (GitRepo) subdirProject.getRepo();

        // Create new files: one in subdirectory, one outside
        Path fileInSubdir = subdirPath.resolve("new-file-in-subdir.txt");
        Path fileOutsideSubdir = repoRoot.resolve("new-file-at-root.txt");
        Files.writeString(fileInSubdir, "content in subdir");
        Files.writeString(fileOutsideSubdir, "content at root");

        // Try to add both files
        gitRepo.add(fileInSubdir);
        gitRepo.add(fileOutsideSubdir);

        // Check what files are tracked after add
        var trackedFiles = gitRepo.getTrackedFiles();
        var trackedNames = trackedFiles.stream()
                .map(f -> f.absPath().getFileName().toString())
                .collect(Collectors.toSet());

        boolean subdirFileVisible = trackedNames.contains("new-file-in-subdir.txt");
        boolean rootFileVisible = trackedNames.contains("new-file-at-root.txt");

        // Assert the expected behavior: when opening a subdirectory,
        // only files within that subdirectory should be visible/trackable
        assertTrue(subdirFileVisible, "File added in subdirectory should be visible in getTrackedFiles()");
        assertFalse(
                rootFileVisible,
                "File added outside subdirectory should NOT be visible in getTrackedFiles() - "
                        + "git operations should be scoped to the opened subdirectory");
    }

    /**
     * Verify that build commands execute in the subdirectory, not at git root. This is correct for monorepos where each
     * package has its own build config.
     */
    @Test
    void testSubdirectoryProject_BuildDirectoryIsSubdir() throws Exception {
        Path subdirPath = repoRoot.resolve("subproject");
        subdirProject = AbstractProject.createProject(subdirPath, null);

        // The project root should be the subdirectory
        Path projectRoot = subdirProject.getRoot();

        // When opening a subdirectory, getRoot() should return the subdirectory
        // This is where build commands will execute
        assertEquals(subdirPath, projectRoot, "Project root should be the subdirectory, not git root");
        assertNotEquals(
                repoRoot,
                projectRoot,
                "Build directory (project root) should be subdirectory, not git root - "
                        + "this allows each package in a monorepo to have its own build config");
    }

    /** Verify that for regular projects, build directory IS the git root. */
    @Test
    void testRegularProject_BuildDirectoryIsGitRoot() throws Exception {
        rootProject = AbstractProject.createProject(repoRoot, null);

        Path projectRoot = rootProject.getRoot();

        // For regular projects, getRoot() should equal git root
        assertEquals(repoRoot, projectRoot, "For regular projects, build directory should be git root");
    }

    /**
     * Tests that worktrees use their own projectRoot for config.
     * Each project (including worktrees) has independent config storage.
     */
    @Test
    void testWorktreeProject_ConfigLocation() throws Exception {
        assumeTrue(gitRepo.supportsWorktrees(), "Worktrees not supported, skipping test");

        // Create a worktree
        String worktreeBranch = "worktree-branch";
        Path worktreePath = tempDir.resolve("worktree");
        gitRepo.addWorktree(worktreeBranch, worktreePath);

        AbstractProject worktreeProject = null;
        try {
            worktreeProject = AbstractProject.createProject(worktreePath, null);

            Path masterConfigPath = worktreeProject.getMasterRootPathForConfig();

            // Worktree should use its own root for config (not main repo)
            // Normalize both paths to handle macOS /private/var vs /var symlinks
            assertEquals(
                    worktreePath.toRealPath(),
                    masterConfigPath.toRealPath(),
                    "Worktree should use its own root for config");
        } finally {
            if (worktreeProject != null) {
                worktreeProject.close();
            }
            gitRepo.removeWorktree(worktreePath, true);
        }
    }

    /**
     * Tests that worktrees opened at a subdirectory path store config at that subdirectory, not at git root.
     * This ensures consistent behavior: subdirectory projects always have independent config, whether regular or worktree.
     */
    @Test
    void testWorktreeSubdirectory_ConfigLocation() throws Exception {
        assumeTrue(gitRepo.supportsWorktrees(), "Worktrees not supported, skipping test");

        // Create a worktree
        String worktreeBranch = "worktree-subdir-branch";
        Path worktreePath = tempDir.resolve("worktree-subdir");
        gitRepo.addWorktree(worktreeBranch, worktreePath);

        // Open the worktree at a subdirectory path
        Path worktreeSubdirPath = worktreePath.resolve("subproject");
        Files.createDirectories(worktreeSubdirPath);

        AbstractProject worktreeSubdirProject = null;
        try {
            worktreeSubdirProject = AbstractProject.createProject(worktreeSubdirPath, null);

            Path masterConfigPath = worktreeSubdirProject.getMasterRootPathForConfig();

            // Worktree subdirectory should use subdirectory for config, not git root
            // Normalize paths to handle macOS /private/var vs /var symlinks
            assertEquals(
                    worktreeSubdirPath.toRealPath(),
                    masterConfigPath.toRealPath(),
                    "Worktree subdirectory should use subdirectory for config, not git root");
        } finally {
            if (worktreeSubdirProject != null) {
                worktreeSubdirProject.close();
            }
            gitRepo.removeWorktree(worktreePath, true);
        }
    }

    /**
     * Compares the behavior of subdirectory projects vs worktree projects.
     * Both now use their own projectRoot for config (independent storage).
     */
    @Test
    void testSubdirectoryVsWorktree_Comparison() throws Exception {
        assumeTrue(gitRepo.supportsWorktrees(), "Worktrees not supported, skipping test");

        // Create subdirectory project
        Path subdirPath = repoRoot.resolve("subproject");
        AbstractProject subdirProj = AbstractProject.createProject(subdirPath, null);

        // Create worktree project
        String worktreeBranch = "comparison-worktree";
        Path worktreePath = tempDir.resolve("comparison-worktree");
        gitRepo.addWorktree(worktreeBranch, worktreePath);
        AbstractProject worktreeProj = AbstractProject.createProject(worktreePath, null);

        try {
            Path subdirConfig = subdirProj.getMasterRootPathForConfig();
            Path worktreeConfig = worktreeProj.getMasterRootPathForConfig();

            // Both subdirectory and worktree use their own projectRoot for config
            // Normalize paths to handle macOS /private/var vs /var symlinks
            assertNotEquals(
                    worktreeConfig.toRealPath(),
                    subdirConfig.toRealPath(),
                    "Subdirectory and worktree should have different config locations");
            assertEquals(
                    subdirPath.toRealPath(),
                    subdirConfig.toRealPath(),
                    "Subdirectory should use subdirectory for config");
            assertEquals(
                    worktreePath.toRealPath(),
                    worktreeConfig.toRealPath(),
                    "Worktree should use its own root for config");
        } finally {
            subdirProj.close();
            worktreeProj.close();
            gitRepo.removeWorktree(worktreePath, true);
        }
    }

    /**
     * Verify that BuildAgent only detects build configurations in the subdirectory, not at git root. This ensures each
     * package in a monorepo uses its own build configuration. BuildAgent uses project.getAllFiles() which filters to
     * the projectRoot (subdirectory).
     */
    @Test
    void testSubdirectoryProject_BuildDetectionScope() throws Exception {
        // Create a build config at git root
        Path rootPackageJson = repoRoot.resolve("package.json");
        Files.writeString(rootPackageJson, "{\"name\": \"root-package\"}");

        // Create a build config in the subdirectory
        Path subdirPath = repoRoot.resolve("subproject");
        Path subdirPackageJson = subdirPath.resolve("package.json");
        Files.writeString(subdirPackageJson, "{\"name\": \"subproject-package\"}");

        // Commit both files
        try (Git git = Git.open(repoRoot.toFile())) {
            git.add().addFilepattern("package.json").call();
            git.add().addFilepattern("subproject/package.json").call();
            git.commit().setSign(false).setMessage("Add build configs at root and subdir").call();
        }

        // Open the subdirectory as a project
        subdirProject = AbstractProject.createProject(subdirPath, null);

        // Get all files that BuildAgent would see
        var allFiles = subdirProject.getAllFiles();
        var fileNames =
                allFiles.stream().map(f -> f.absPath().getFileName().toString()).collect(Collectors.toSet());

        // BuildAgent should only see files in the subdirectory
        assertTrue(fileNames.contains("sub-file1.txt"), "BuildAgent should see files in subdirectory");
        assertFalse(
                fileNames.contains("root-file.txt"),
                "BuildAgent should NOT see files at git root - only subdirectory files are visible");

        // Verify the build config in subdirectory is visible
        var subdirPackageJsonFile = allFiles.stream()
                .filter(f -> f.absPath().equals(subdirPackageJson))
                .findFirst();
        assertTrue(subdirPackageJsonFile.isPresent(), "BuildAgent should find build config in subdirectory");

        // Verify the root build config is NOT visible
        var rootPackageJsonFile = allFiles.stream()
                .filter(f -> f.absPath().equals(rootPackageJson))
                .findFirst();
        assertTrue(
                rootPackageJsonFile.isEmpty(),
                "BuildAgent should NOT see build config at git root - "
                        + "each package in monorepo uses its own build configuration");
    }

    /**
     * Integration test: Full workflow from subdirectory project to worktree creation and session management.
     * This test simulates the complete user experience:
     * 1. Open a subdirectory project
     * 2. Create a worktree from it
     * 3. Verify the worktree opens at the corresponding subdirectory
     * 4. Verify config isolation between projects
     * 5. Verify session management works correctly
     */
    @Test
    void testIntegration_SubdirectoryWorktreeWorkflow() throws Exception {
        Path subdirPath = repoRoot.resolve("subproject");

        // Step 1: Create and configure main subdirectory project
        var mainProject = (MainProject) AbstractProject.createProject(subdirPath, null);
        AbstractProject closeable1 = mainProject;

        try {
            // Verify config location for main subdirectory project
            assertEquals(
                    subdirPath.toAbsolutePath().normalize(),
                    mainProject.getMasterRootPathForConfig(),
                    "Main subdirectory project should have config at subdirectory");

            // Set some configuration
            mainProject.setCommitMessageFormat("test: {message}");
            mainProject.saveProjectProperties();
            Path mainBrokkDir = subdirPath.resolve(".brokk");
            assertTrue(Files.exists(mainBrokkDir.resolve("project.properties")), "Main project config should exist");

            // Create a session directory to test session management
            Path mainSessionsDir = mainBrokkDir.resolve("sessions");
            Files.createDirectories(mainSessionsDir);
            Path sessionZip = mainSessionsDir.resolve("test-session.zip");
            Files.writeString(sessionZip, "dummy session data");

            // Step 2: Create a worktree from the main git repository
            var gitRepo = (GitRepo) mainProject.getRepo();

            // Get current branch name to create worktree from it
            String currentBranch = gitRepo.getCurrentBranch();
            String worktreeBranch = "test-worktree-branch";

            // Create the new branch locally first, so worktree has the same content
            gitRepo.createBranch(worktreeBranch, currentBranch);

            // Create worktree at git root level (simulating what GitWorktreeTab does)
            Path worktreeRoot = tempDir.resolve("worktree-root");
            gitRepo.addWorktree(worktreeBranch, worktreeRoot);

            // Step 3: Open the corresponding subdirectory in the worktree
            // This mimics the logic in GitWorktreeTab where it calculates relativeSubdir
            Path relativeSubdir = gitRepo.getRelativeSubdir(subdirPath);
            assert relativeSubdir != null : "subdirPath should be a subdirectory";
            Path worktreeSubdir = worktreeRoot.resolve(relativeSubdir);

            // Verify the subdirectory exists in the worktree
            assertTrue(Files.exists(worktreeSubdir), "Worktree subdirectory should exist");

            var worktreeProject = AbstractProject.createProject(worktreeSubdir, mainProject);
            AbstractProject closeable2 = worktreeProject;

            try {
                // Step 4: Verify config isolation
                // Worktree subdirectory should have its OWN config, not shared with main repo
                assertEquals(
                        worktreeSubdir.toAbsolutePath().normalize(),
                        worktreeProject.getMasterRootPathForConfig(),
                        "Worktree subdirectory should have isolated config");

                // Verify config directories are independent
                Path mainBrokkDirActual = mainBrokkDir.toAbsolutePath().normalize();
                Path worktreeBrokkDir =
                        worktreeSubdir.resolve(".brokk").toAbsolutePath().normalize();

                assertNotEquals(
                        mainBrokkDirActual,
                        worktreeBrokkDir,
                        "Worktree and main subdirectory should have different .brokk directories");

                // Verify config files don't exist yet in worktree
                assertFalse(
                        Files.exists(worktreeBrokkDir.resolve("workspace.properties")),
                        "Worktree subdirectory should start with no config");

                // Create a workspace property file in worktree to simulate independent config
                Files.createDirectories(worktreeBrokkDir);
                Path worktreeWsProps = worktreeBrokkDir.resolve("workspace.properties");
                Files.writeString(worktreeWsProps, "# Worktree workspace config\n");
                assertTrue(Files.exists(worktreeWsProps), "Worktree config should now exist");

                // Verify main project config still exists independently
                assertTrue(
                        Files.exists(mainBrokkDir.resolve("project.properties")),
                        "Main project config should still exist independently");

                // Step 5: Verify session management
                Path worktreeSessionsDir = worktreeBrokkDir.resolve("sessions");
                Files.createDirectories(worktreeSessionsDir);

                // Sessions should be isolated
                assertFalse(
                        Files.exists(worktreeSessionsDir.resolve("test-session.zip")),
                        "Worktree should not see main project's sessions");

                // Create a session in worktree
                Path worktreeSessionZip = worktreeSessionsDir.resolve("worktree-session.zip");
                Files.writeString(worktreeSessionZip, "worktree session data");

                // Verify main project doesn't see worktree session
                assertFalse(
                        Files.exists(mainSessionsDir.resolve("worktree-session.zip")),
                        "Main project should not see worktree's sessions");

                // Step 6: Verify git operations work correctly in both projects
                // Create a new file in worktree subdirectory
                Path newFile = worktreeSubdir.resolve("worktree-new-file.txt");
                Files.writeString(newFile, "New file in worktree");

                var worktreeGitRepo = (GitRepo) worktreeProject.getRepo();
                // The key test: This add operation should succeed
                // Previously would fail or create incorrect paths due to using repository.getWorkTree()
                // instead of gitTopLevel for path relativization
                assertDoesNotThrow(
                        () -> worktreeGitRepo.add(newFile), "Should be able to stage file in worktree subdirectory");

                // Verify files exist on disk in the worktree
                assertTrue(
                        Files.exists(worktreeSubdir.resolve("sub-file1.txt")),
                        "Worktree should have original committed files on disk");
                assertTrue(
                        Files.exists(worktreeSubdir.resolve("sub-file2.txt")),
                        "Worktree should have original committed files on disk");
                assertTrue(Files.exists(newFile), "Worktree should have the newly created file on disk");

                // Step 7: Verify directory structure is correct
                // The worktree subdirectory should exist and contain the same structure
                assertTrue(Files.isDirectory(worktreeSubdir), "Worktree subdirectory should exist");
                assertEquals(
                        "subproject",
                        worktreeSubdir.getFileName().toString(),
                        "Worktree should preserve subdirectory name");

            } finally {
                closeable2.close();
            }

            // Step 8: Verify main project is unaffected
            assertTrue(
                    Files.exists(mainBrokkDir.resolve("project.properties")),
                    "Main project config should be unchanged");
            assertTrue(Files.exists(sessionZip), "Main project session should still exist");

        } finally {
            closeable1.close();
        }
    }

    /**
     * Tests that getModifiedFiles() only returns files within the subdirectory scope.
     * Regression test for bug where modified files outside projectRoot were incorrectly included.
     */
    @Test
    void testSubdirectoryProject_ModifiedFilesFiltering() throws Exception {
        Path subdirPath = repoRoot.resolve("subproject");
        subdirProject = AbstractProject.createProject(subdirPath, null);
        var gitRepo = (GitRepo) subdirProject.getRepo();

        // Modify a file inside the subdirectory
        Path insideFile = subdirPath.resolve("sub-file1.txt");
        Files.writeString(insideFile, "modified content inside");

        // Modify a file outside the subdirectory (at git root)
        Path outsideFile = repoRoot.resolve("root-file.txt");
        Files.writeString(outsideFile, "modified content outside");

        // Get modified files from subdirectory project
        var modifiedFiles = gitRepo.getModifiedFiles();
        var modifiedPaths = modifiedFiles.stream()
                .map(mf -> mf.file().getRelPath().toString())
                .collect(Collectors.toSet());

        // Should only contain the file inside the subdirectory
        assertTrue(modifiedPaths.contains("sub-file1.txt"), "Should contain modified file inside subdirectory");
        assertFalse(
                modifiedPaths.stream().anyMatch(p -> p.contains("root-file")),
                "Should NOT contain modified file outside subdirectory");
        assertEquals(1, modifiedPaths.size(), "Should have exactly 1 modified file");
    }
}
