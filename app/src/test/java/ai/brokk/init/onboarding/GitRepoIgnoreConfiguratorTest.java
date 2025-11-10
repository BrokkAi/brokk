package ai.brokk.init.onboarding;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IConsoleIO;
import ai.brokk.IProject;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitTestCleanupUtil;
import ai.brokk.init.onboarding.GitIgnoreConfigurator.SetupResult;
import dev.langchain4j.data.message.ChatMessageType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for GitIgnoreConfigurator.
 * Tests git operations without UI dependencies.
 */
class GitRepoIgnoreConfiguratorTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private GitRepo gitRepo;

    /**
     * Minimal test implementation of IProject.
     */
    private static class TestProject implements IProject {
        private final Path root;
        private final GitRepo repo;

        TestProject(Path root, GitRepo repo) {
            this.root = root;
            this.repo = repo;
        }

        @Override
        public Path getRoot() {
            return root;
        }

        @Override
        public Path getMasterRootPathForConfig() {
            return root;
        }

        @Override
        public GitRepo getRepo() {
            return repo;
        }

        @Override
        public boolean hasGit() {
            return repo != null;
        }

        @Override
        public void close() {
            // No-op for tests
        }
    }

    /**
     * Test implementation of IConsoleIO that captures notifications.
     */
    private static class TestConsoleIO implements IConsoleIO {
        private final List<String> notifications = new ArrayList<>();

        @Override
        public void showNotification(NotificationRole role, String message) {
            notifications.add(role + ": " + message);
        }

        @Override
        public void llmOutput(String token, ChatMessageType type, boolean isNewMessage, boolean isReasoning) {
            // No-op for tests
        }

        @Override
        public void toolError(String msg, String title) {
            notifications.add("ERROR: " + msg);
        }

        List<String> getNotifications() {
            return notifications;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = tempDir.resolve("testProject");
        Files.createDirectories(projectRoot);

        // Initialize git repository
        try (Git git = Git.init().setDirectory(projectRoot.toFile()).call()) {
            // Create an initial commit
            Path readme = projectRoot.resolve("README.md");
            Files.writeString(readme, "Initial commit file.");
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setMessage("Initial commit")
                    .setAuthor("Test User", "test@example.com")
                    .setSign(false)
                    .call();
        }

        gitRepo = new GitRepo(projectRoot);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (gitRepo != null) {
            GitTestCleanupUtil.cleanupGitResources(gitRepo);
        }
    }

    /**
     * Test 1: Fresh project with no .gitignore - should create and stage it
     */
    @Test
    void testFreshProject_CreatesGitignore() throws Exception {
        var project = new TestProject(projectRoot, gitRepo);
        var consoleIO = new TestConsoleIO();

        // Verify no .gitignore exists
        assertFalse(Files.exists(projectRoot.resolve(".gitignore")));

        // Execute
        SetupResult result = GitIgnoreConfigurator.setupGitIgnoreAndStageFiles(project, consoleIO);

        // Verify .gitignore was created
        assertTrue(Files.exists(projectRoot.resolve(".gitignore")));
        assertTrue(result.gitignoreUpdated(), "gitignoreUpdated should be true");
        assertTrue(result.errorMessage().isEmpty(), "Should have no error");

        // Verify .gitignore contains Brokk patterns
        String gitignoreContent = Files.readString(projectRoot.resolve(".gitignore"));
        assertTrue(gitignoreContent.contains(".brokk/**"));
        assertTrue(gitignoreContent.contains("!AGENTS.md"));
        assertTrue(gitignoreContent.contains("!.brokk/style.md"));

        // Verify .gitignore was staged
        assertTrue(
                result.stagedFiles().stream().anyMatch(f -> f.getRelPath().equals(Path.of(".gitignore"))),
                ".gitignore should be in staged files");

        // Verify notification was sent
        assertTrue(consoleIO.getNotifications().stream().anyMatch(n -> n.contains("Updated .gitignore")));
    }

    /**
     * Test 2: Existing .gitignore without Brokk patterns - should update it
     */
    @Test
    void testExistingGitignore_WithoutBrokkPatterns_UpdatesIt() throws Exception {
        // Create existing .gitignore without Brokk patterns
        Files.writeString(projectRoot.resolve(".gitignore"), "node_modules/\n*.log\n");

        var project = new TestProject(projectRoot, gitRepo);
        var consoleIO = new TestConsoleIO();

        // Execute
        SetupResult result = GitIgnoreConfigurator.setupGitIgnoreAndStageFiles(project, consoleIO);

        // Verify .gitignore was updated
        assertTrue(result.gitignoreUpdated(), "gitignoreUpdated should be true");

        String gitignoreContent = Files.readString(projectRoot.resolve(".gitignore"));
        assertTrue(gitignoreContent.contains("node_modules/"), "Should preserve existing content");
        assertTrue(gitignoreContent.contains(".brokk/**"), "Should add Brokk pattern");
    }

    /**
     * Test 3: Existing .gitignore with comprehensive Brokk pattern - should not update
     */
    @Test
    void testExistingGitignore_WithComprehensivePattern_DoesNotUpdate() throws Exception {
        // Create .gitignore with comprehensive pattern
        Files.writeString(projectRoot.resolve(".gitignore"), "node_modules/\n.brokk/**\n");

        var project = new TestProject(projectRoot, gitRepo);
        var consoleIO = new TestConsoleIO();

        // Execute
        SetupResult result = GitIgnoreConfigurator.setupGitIgnoreAndStageFiles(project, consoleIO);

        // Verify .gitignore was NOT updated
        assertFalse(result.gitignoreUpdated(), "gitignoreUpdated should be false");

        // Verify no update notification
        assertTrue(
                consoleIO.getNotifications().stream().noneMatch(n -> n.contains("Updated .gitignore")),
                "Should not notify about update");
    }

    /**
     * Test 4: Existing .gitignore with alternative pattern .brokk/ - should not update
     */
    @Test
    void testExistingGitignore_WithAlternativePattern_DoesNotUpdate() throws Exception {
        // Create .gitignore with .brokk/ pattern
        Files.writeString(projectRoot.resolve(".gitignore"), ".brokk/\n");

        var project = new TestProject(projectRoot, gitRepo);
        var consoleIO = new TestConsoleIO();

        // Execute
        SetupResult result = GitIgnoreConfigurator.setupGitIgnoreAndStageFiles(project, consoleIO);

        // Verify .gitignore was NOT updated
        assertFalse(result.gitignoreUpdated(), "gitignoreUpdated should be false for .brokk/ pattern");
    }

    /**
     * Test 5: Existing .gitignore with partial pattern - should update
     */
    @Test
    void testExistingGitignore_WithPartialPattern_UpdatesIt() throws Exception {
        // Create .gitignore with only partial patterns (not comprehensive)
        Files.writeString(projectRoot.resolve(".gitignore"), ".brokk/workspace.properties\n.brokk/sessions/\n");

        var project = new TestProject(projectRoot, gitRepo);

        // Execute
        SetupResult result = GitIgnoreConfigurator.setupGitIgnoreAndStageFiles(project, null);

        // Verify .gitignore WAS updated (partial patterns are not sufficient)
        assertTrue(
                result.gitignoreUpdated(), "gitignoreUpdated should be true for partial patterns - need comprehensive");

        String gitignoreContent = Files.readString(projectRoot.resolve(".gitignore"));
        assertTrue(gitignoreContent.contains(".brokk/**"), "Should add comprehensive pattern");
    }

    /**
     * Test 6: Pattern with trailing comment should be recognized
     */
    @Test
    void testGitignore_PatternWithComment_IsRecognized() throws Exception {
        // Create .gitignore with pattern followed by comment
        Files.writeString(projectRoot.resolve(".gitignore"), ".brokk/** # Brokk configuration files\n");

        var project = new TestProject(projectRoot, gitRepo);

        // Execute
        SetupResult result = GitIgnoreConfigurator.setupGitIgnoreAndStageFiles(project, null);

        // Verify pattern was recognized (no update needed)
        assertFalse(result.gitignoreUpdated(), "Should recognize pattern despite comment");
    }

    /**
     * Test 7: Stub files are created
     */
    @Test
    void testStubFiles_AreCreated() throws Exception {
        var project = new TestProject(projectRoot, gitRepo);

        // Execute
        GitIgnoreConfigurator.setupGitIgnoreAndStageFiles(project, null);

        // Verify stub files exist
        assertTrue(Files.exists(projectRoot.resolve("AGENTS.md")), "AGENTS.md should be created");
        assertTrue(Files.exists(projectRoot.resolve(".brokk/review.md")), "review.md should be created");
        assertTrue(
                Files.exists(projectRoot.resolve(".brokk/project.properties")), "project.properties should be created");

        // Verify files have content
        assertTrue(Files.size(projectRoot.resolve("AGENTS.md")) > 0, "AGENTS.md should not be empty");
        assertTrue(Files.size(projectRoot.resolve(".brokk/review.md")) > 0, "review.md should not be empty");
    }

    /**
     * Test 8: Shared files are staged to git
     */
    @Test
    void testSharedFiles_AreStaged() throws Exception {
        var project = new TestProject(projectRoot, gitRepo);

        // Execute
        SetupResult result = GitIgnoreConfigurator.setupGitIgnoreAndStageFiles(project, null);

        // Verify shared files are in staged list (using Path objects for platform independence)
        List<Path> stagedPaths =
                result.stagedFiles().stream().map(ProjectFile::getRelPath).toList();

        assertTrue(stagedPaths.contains(Path.of("AGENTS.md")), "AGENTS.md should be staged");
        assertTrue(stagedPaths.contains(Path.of(".brokk", "review.md")), "review.md should be staged");
        assertTrue(
                stagedPaths.contains(Path.of(".brokk", "project.properties")), "project.properties should be staged");
    }

    /**
     * Test 9: NO migration - legacy style.md exists but configurator does not migrate
     */
    @Test
    void testNoMigration_LegacyFileExists() throws Exception {
        // Create legacy .brokk/style.md with content
        Files.createDirectories(projectRoot.resolve(".brokk"));
        Files.writeString(projectRoot.resolve(".brokk/style.md"), "# Legacy Style Guide\nOld content here");

        var project = new TestProject(projectRoot, gitRepo);

        // Execute
        SetupResult result = GitIgnoreConfigurator.setupGitIgnoreAndStageFiles(project, null);

        // Verify NO migration performed - configurator never migrates
        // Instead, a stub AGENTS.md should be created
        assertTrue(Files.exists(projectRoot.resolve("AGENTS.md")));
        String agentsContent = Files.readString(projectRoot.resolve("AGENTS.md"));
        assertTrue(agentsContent.contains("# Agents Guide"), "Should have stub content");
        assertFalse(agentsContent.contains("Legacy Style Guide"), "Should NOT contain legacy content");

        // Verify legacy file still exists (not deleted)
        assertTrue(Files.exists(projectRoot.resolve(".brokk/style.md")), "Legacy file should remain");

        // Verify legacy file is NOT in staged files list
        assertFalse(
                result.stagedFiles().stream().anyMatch(f -> f.getRelPath().equals(Path.of(".brokk", "style.md"))),
                "Legacy file should NOT be in staged files");
    }

    /**
     * Test 10: NO migration - empty legacy file exists
     */
    @Test
    void testNoMigration_EmptyLegacyFile() throws Exception {
        // Create empty legacy file
        Files.createDirectories(projectRoot.resolve(".brokk"));
        Files.writeString(projectRoot.resolve(".brokk/style.md"), "");

        var project = new TestProject(projectRoot, gitRepo);

        // Execute
        SetupResult result = GitIgnoreConfigurator.setupGitIgnoreAndStageFiles(project, null);

        // Verify stub AGENTS.md was created (configurator never migrates)
        assertTrue(Files.exists(projectRoot.resolve("AGENTS.md")));
        String agentsContent = Files.readString(projectRoot.resolve("AGENTS.md"));
        assertTrue(agentsContent.contains("# Agents Guide"), "Should have stub content");
    }

    /**
     * Test 11: NO migration - both legacy and AGENTS.md exist
     */
    @Test
    void testNoMigration_BothFilesExist() throws Exception {
        // Create both files
        Files.createDirectories(projectRoot.resolve(".brokk"));
        Files.writeString(projectRoot.resolve(".brokk/style.md"), "# Legacy");
        Files.writeString(projectRoot.resolve("AGENTS.md"), "# Current");

        var project = new TestProject(projectRoot, gitRepo);

        // Execute
        SetupResult result = GitIgnoreConfigurator.setupGitIgnoreAndStageFiles(project, null);

        // Verify AGENTS.md kept current content (configurator never migrates)
        String agentsContent = Files.readString(projectRoot.resolve("AGENTS.md"));
        assertTrue(agentsContent.contains("# Current"));
        assertFalse(agentsContent.contains("# Legacy"));
    }

    /**
     * Test 12: Project without GitRepo - returns error
     */
    @Test
    void testNonGitProject_ReturnsError() throws Exception {
        // Create project without GitRepo
        var nonGitProject = new TestProject(projectRoot, new GitRepo(projectRoot) {
            @Override
            public void add(Path path) {
            }

            @Override
            public void add(java.util.Collection<ProjectFile> files) {
            }

            @Override
            public void remove(ProjectFile file) {
            }
        }) {
            @Override
            public GitRepo getRepo() {
                return null;
            }

            @Override
            public boolean hasGit() {
                return false;
            }
        };

        // Execute
        SetupResult result = GitIgnoreConfigurator.setupGitIgnoreAndStageFiles(nonGitProject, null);

        // Verify error returned
        assertTrue(result.errorMessage().isPresent(), "Should return error for non-git project");
        assertTrue(
                result.errorMessage().get().contains("not a GitRepo"),
                "Error should mention GitRepo: " + result.errorMessage().get());

        // Verify no operations performed
        assertFalse(result.gitignoreUpdated());
        assertTrue(result.stagedFiles().isEmpty());
    }

    /**
     * Test 13: Operation with null consoleIO - should work silently
     */
    @Test
    void testNullConsoleIO_WorksSilently() throws Exception {
        var project = new TestProject(projectRoot, gitRepo);

        // Execute with null consoleIO (should not throw)
        SetupResult result = GitIgnoreConfigurator.setupGitIgnoreAndStageFiles(project, null);

        // Verify operation succeeded
        assertTrue(result.gitignoreUpdated());
        assertTrue(result.errorMessage().isEmpty());
    }

    /**
     * Test 14: Gitignore with leading/trailing whitespace in pattern
     */
    @Test
    void testGitignore_WithWhitespace_IsRecognized() throws Exception {
        // Create .gitignore with pattern that has whitespace
        Files.writeString(projectRoot.resolve(".gitignore"), "  .brokk/**  \n");

        var project = new TestProject(projectRoot, gitRepo);

        // Execute
        SetupResult result = GitIgnoreConfigurator.setupGitIgnoreAndStageFiles(project, null);

        // Verify pattern was recognized despite whitespace
        assertFalse(result.gitignoreUpdated(), "Should recognize pattern with whitespace");
    }

    /**
     * Test 15: Ensures .gitignore ends with newline when updating
     */
    @Test
    void testGitignore_EndsWithNewline() throws Exception {
        // Create .gitignore without trailing newline
        Files.writeString(projectRoot.resolve(".gitignore"), "node_modules/");

        var project = new TestProject(projectRoot, gitRepo);

        // Execute
        GitIgnoreConfigurator.setupGitIgnoreAndStageFiles(project, null);

        // Verify content formatting
        String gitignoreContent = Files.readString(projectRoot.resolve(".gitignore"));
        assertTrue(gitignoreContent.contains("node_modules/\n"), "Should add newline after existing content");
        assertTrue(gitignoreContent.contains("\n### BROKK'S CONFIGURATION ###\n"), "Should have section header");
    }
}
