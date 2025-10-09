package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.GitTestCleanupUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for monorepo subdirectory support - opening a subdirectory of a git repository
 * as a Brokk project.
 */
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
     * Tests where the config directory is placed when opening a subdirectory.
     * Expected: Should be at git root (like worktrees), not in subdirectory.
     * Current behavior: May be in subdirectory (bug).
     */
    @Test
    void testSubdirectoryProject_WhereIsConfig() throws Exception {
        Path subdirPath = repoRoot.resolve("subproject");
        subdirProject = AbstractProject.createProject(subdirPath, null);

        Path masterConfigPath = subdirProject.getMasterRootPathForConfig();
        Path projectRoot = subdirProject.getRoot();

        System.out.println("=== testSubdirectoryProject_WhereIsConfig ===");
        System.out.println("Git repo root: " + repoRoot);
        System.out.println("Project root: " + projectRoot);
        System.out.println("Master config path: " + masterConfigPath);
        System.out.println("Expected config path: " + repoRoot);
        System.out.println("projectRoot equals masterConfigPath: " + projectRoot.equals(masterConfigPath));
        System.out.println("repoRoot equals masterConfigPath: " + repoRoot.equals(masterConfigPath));

        // Document current behavior
        if (projectRoot.equals(masterConfigPath)) {
            System.out.println("CURRENT BEHAVIOR: Config stored in subdirectory (likely wrong)");
        } else if (repoRoot.equals(masterConfigPath)) {
            System.out.println("CURRENT BEHAVIOR: Config stored at git root (correct)");
        }

        // Expected behavior: config should be at git root for subdirectory projects
        assertEquals(
                repoRoot.toAbsolutePath().normalize(),
                masterConfigPath,
                "Config should be stored at git repository root, not in subdirectory");
    }

    /**
     * Tests that saving properties creates .brokk directory at git root, not subdirectory.
     * This test ensures physical files are created in the correct location.
     */
    @Test
    void testSubdirectoryProject_PhysicalConfigLocation() throws Exception {
        Path subdirPath = repoRoot.resolve("subproject");
        var mainProj = (MainProject) AbstractProject.createProject(subdirPath, null);
        subdirProject = mainProj;

        // Trigger creation of config files by saving properties
        mainProj.saveProjectProperties();

        Path expectedBrokkDir = repoRoot.resolve(".brokk");
        Path wrongBrokkDir = subdirPath.resolve(".brokk");

        System.out.println("=== testSubdirectoryProject_PhysicalConfigLocation ===");
        System.out.println("Expected .brokk at: " + expectedBrokkDir);
        System.out.println("Wrong .brokk at: " + wrongBrokkDir);
        System.out.println("Exists at root: " + Files.exists(expectedBrokkDir));
        System.out.println("Exists at subdirectory: " + Files.exists(wrongBrokkDir));

        // Config files should be created at git root
        assertTrue(Files.exists(expectedBrokkDir), ".brokk should exist at git root");
        assertTrue(
                Files.exists(expectedBrokkDir.resolve("project.properties")),
                "project.properties should exist at git root");

        // Config files should NOT be created at subdirectory
        assertFalse(Files.exists(wrongBrokkDir), ".brokk should NOT exist at subdirectory");
    }

    /**
     * Regression test for llm-history being created at git root, not subdirectory.
     * This was a bug where Llm.java used getRoot() instead of getMasterRootPathForConfig().
     */
    @Test
    void testSubdirectoryProject_LlmHistoryLocation() throws Exception {
        Path subdirPath = repoRoot.resolve("subproject");
        var mainProj = (MainProject) AbstractProject.createProject(subdirPath, null);
        subdirProject = mainProj;

        // Simulate what happens when Llm creates history directory
        Path historyDir = io.github.jbellis.brokk.Llm.getHistoryBaseDir(mainProj.getMasterRootPathForConfig());

        Path expectedHistoryDir = repoRoot.resolve(".brokk").resolve("llm-history");
        Path wrongHistoryDir = subdirPath.resolve(".brokk").resolve("llm-history");

        System.out.println("=== testSubdirectoryProject_LlmHistoryLocation ===");
        System.out.println("Expected llm-history at: " + expectedHistoryDir);
        System.out.println("Wrong llm-history at: " + wrongHistoryDir);
        System.out.println("Actual historyDir: " + historyDir);

        // LLM history should be at git root
        assertEquals(expectedHistoryDir, historyDir, "llm-history should be at git repository root");

        // Create the directory to verify physical location
        Files.createDirectories(historyDir);

        assertTrue(Files.exists(expectedHistoryDir), "llm-history should exist at git root");
        assertFalse(Files.exists(wrongHistoryDir), "llm-history should NOT exist at subdirectory");
    }

    /**
     * Regression test for git staging bug when opening subdirectory.
     * Previously, adding files from git root when projectRoot was a subdirectory
     * would create incorrect relative paths (with ..) causing staging issues.
     * This test verifies that add() operations succeed without exceptions.
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

        System.out.println("=== testSubdirectoryProject_GitAddFilesAtRoot ===");
        System.out.println("Testing add operations with files at git root...");

        // The key test: These add operations should succeed
        // Previously would fail or create incorrect paths due to using repository.getWorkTree()
        // instead of gitTopLevel for path relativization
        assertDoesNotThrow(() -> gitRepo.add(gitignoreFile), "Should be able to add .gitignore from git root");

        assertDoesNotThrow(
                () -> gitRepo.add(List.of(new ProjectFile(repoRoot, ".brokk/test.txt"))),
                "Should be able to add .brokk/test.txt from git root using ProjectFile");

        System.out.println("Successfully added files from git root without path errors");
    }

    /**
     * Tests that getTrackedFiles() correctly filters to only show files under the subdirectory.
     * This should already work based on the GitRepo implementation.
     */
    @Test
    void testSubdirectoryProject_TrackedFilesFiltering() throws Exception {
        Path subdirPath = repoRoot.resolve("subproject");
        subdirProject = AbstractProject.createProject(subdirPath, null);

        Set<ProjectFile> trackedFiles = subdirProject.getRepo().getTrackedFiles();
        Set<String> fileNames =
                trackedFiles.stream().map(ProjectFile::getFileName).collect(Collectors.toSet());

        System.out.println("=== testSubdirectoryProject_TrackedFilesFiltering ===");
        System.out.println("Tracked files: " + fileNames);

        // Should only see files in subproject, not root-file.txt or files in another-subproject
        assertTrue(fileNames.contains("sub-file1.txt"), "Should contain sub-file1.txt");
        assertTrue(fileNames.contains("sub-file2.txt"), "Should contain sub-file2.txt");
        assertFalse(fileNames.contains("root-file.txt"), "Should NOT contain root-file.txt");
        assertFalse(fileNames.contains("another-file.txt"), "Should NOT contain another-file.txt");

        assertEquals(2, fileNames.size(), "Should have exactly 2 files from subproject");
    }

    /**
     * Tests that two subdirectories opened as separate projects share the same config location.
     * Expected: Both should use git root for config.
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

            System.out.println("=== testSubdirectoryProject_ConfigSharing ===");
            System.out.println("Subdir1 config path: " + config1);
            System.out.println("Subdir2 config path: " + config2);
            System.out.println("Git repo root: " + repoRoot);
            System.out.println("Configs are equal: " + config1.equals(config2));

            // Both should share the same config location (git root)
            assertEquals(config1, config2, "Both subdirectories should share the same config location");
            assertEquals(
                    repoRoot.toAbsolutePath().normalize(),
                    config1,
                    "Config should be at git repository root");
        } finally {
            if (project1 != null) project1.close();
            if (project2 != null) project2.close();
        }
    }

    /**
     * Baseline test: opening a project at the git repository root.
     * Config should be stored at the root (same as project root).
     */
    @Test
    void testRegularProject_AtGitRoot() throws Exception {
        rootProject = AbstractProject.createProject(repoRoot, null);

        Path masterConfigPath = rootProject.getMasterRootPathForConfig();
        Path projectRoot = rootProject.getRoot();

        System.out.println("=== testRegularProject_AtGitRoot ===");
        System.out.println("Project root: " + projectRoot);
        System.out.println("Master config path: " + masterConfigPath);

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

    /**
     * Test that all files are visible when opening at git root (no filtering).
     */
    @Test
    void testRegularProject_AllFilesVisible() throws Exception {
        rootProject = AbstractProject.createProject(repoRoot, null);

        Set<ProjectFile> trackedFiles = rootProject.getRepo().getTrackedFiles();
        Set<String> fileNames =
                trackedFiles.stream().map(ProjectFile::getFileName).collect(Collectors.toSet());

        System.out.println("=== testRegularProject_AllFilesVisible ===");
        System.out.println("Tracked files: " + fileNames);

        // Should see ALL files when opening at root
        assertTrue(fileNames.contains("root-file.txt"), "Should contain root-file.txt");
        assertTrue(fileNames.contains("sub-file1.txt"), "Should contain sub-file1.txt");
        assertTrue(fileNames.contains("sub-file2.txt"), "Should contain sub-file2.txt");
        assertTrue(fileNames.contains("another-file.txt"), "Should contain another-file.txt");

        assertEquals(4, fileNames.size(), "Should have all 4 files from entire repo");
    }

    /**
     * Test that git operations work correctly for regular (non-subdirectory) projects.
     */
    @Test
    void testRegularProject_GitOperations() throws Exception {
        rootProject = AbstractProject.createProject(repoRoot, null);
        var gitRepo = (GitRepo) rootProject.getRepo();

        // Create and add a file at root
        Path testFile = repoRoot.resolve("test-file.txt");
        Files.writeString(testFile, "test content");

        System.out.println("=== testRegularProject_GitOperations ===");
        System.out.println("Testing git add at root...");

        // Git operations should work normally
        assertDoesNotThrow(() -> gitRepo.add(testFile), "Should be able to add file at git root");

        System.out.println("Successfully added file at git root");
    }

    /**
     * Tests that worktrees correctly use the main repository's config location.
     * This is existing functionality that should continue to work.
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

            System.out.println("=== testWorktreeProject_ConfigLocation ===");
            System.out.println("Git repo root: " + repoRoot);
            System.out.println("Worktree path: " + worktreePath);
            System.out.println("Worktree master config path: " + masterConfigPath);

            // Worktree should use main repo's config location
            // Normalize both paths to handle macOS /private/var vs /var symlinks
            assertEquals(
                    repoRoot.toRealPath(),
                    masterConfigPath.toRealPath(),
                    "Worktree should use main repository's config location");
        } finally {
            if (worktreeProject != null) {
                worktreeProject.close();
            }
            gitRepo.removeWorktree(worktreePath, true);
        }
    }

    /**
     * Compares the behavior of subdirectory projects vs worktree projects.
     * Both should behave similarly for config storage (at git root).
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

            System.out.println("=== testSubdirectoryVsWorktree_Comparison ===");
            System.out.println("Git repo root: " + repoRoot);
            System.out.println("Subdirectory config: " + subdirConfig);
            System.out.println("Worktree config: " + worktreeConfig);
            System.out.println("Both use git root: " + subdirConfig.equals(worktreeConfig));

            // Both subdirectory and worktree should use git root for config
            // Normalize paths to handle macOS /private/var vs /var symlinks
            assertEquals(
                    worktreeConfig.toRealPath(),
                    subdirConfig.toRealPath(),
                    "Subdirectory and worktree should use same config location (git root)");
            assertEquals(
                    repoRoot.toRealPath(),
                    subdirConfig.toRealPath(),
                    "Both should use git repository root for config");
        } finally {
            subdirProj.close();
            worktreeProj.close();
            gitRepo.removeWorktree(worktreePath, true);
        }
    }
}
