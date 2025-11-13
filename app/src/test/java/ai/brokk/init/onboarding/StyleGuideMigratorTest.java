package ai.brokk.init.onboarding;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitTestCleanupUtil;
import ai.brokk.init.onboarding.StyleGuideMigrator.MigrationResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for StyleGuideMigrator.
 * Covers git and non-git scenarios, edge cases, and idempotency.
 */
class StyleGuideMigratorTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private Path brokkDir;
    private Path agentsFile;
    private GitRepo gitRepo;

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = tempDir.resolve("testProject");
        Files.createDirectories(projectRoot);
        brokkDir = projectRoot.resolve(".brokk");
        Files.createDirectories(brokkDir);
        agentsFile = projectRoot.resolve("AGENTS.md");

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

    @Test
    void testSuccessfulMigration_WithGit() throws Exception {
        // Create legacy style.md with content
        Path legacyFile = brokkDir.resolve("style.md");
        Files.writeString(legacyFile, "# Legacy Style Guide\nOld content here");

        // Perform migration
        var legacyStyle = new ProjectFile(projectRoot, ".brokk/style.md");
        var agentsFilePf = new ProjectFile(projectRoot, "AGENTS.md");
        MigrationResult result = StyleGuideMigrator.migrate(legacyStyle, agentsFilePf, gitRepo);

        // Verify migration performed
        assertTrue(result.performed(), "Migration should be performed");

        // Verify AGENTS.md has the legacy content
        assertTrue(Files.exists(agentsFile), "AGENTS.md should exist");
        String agentsContent = Files.readString(agentsFile);
        assertEquals("# Legacy Style Guide\nOld content here", agentsContent);

        // Verify legacy file was deleted
        assertFalse(Files.exists(legacyFile), "Legacy style.md should be deleted");

        // Verify message
        assertTrue(result.message().contains("Migrated"), "Message should indicate migration");
    }

    @Test
    void testSuccessfulMigration_WithoutGit() throws Exception {
        // Create legacy style.md with content
        Path legacyFile = brokkDir.resolve("style.md");
        Files.writeString(legacyFile, "# Style Content");

        // Perform migration without git
        var legacyStyle = new ProjectFile(projectRoot, ".brokk/style.md");
        var agentsFilePf = new ProjectFile(projectRoot, "AGENTS.md");
        MigrationResult result = StyleGuideMigrator.migrate(legacyStyle, agentsFilePf, null);

        // Verify migration performed
        assertTrue(result.performed(), "Migration should be performed");

        // Verify files
        assertTrue(Files.exists(agentsFile), "AGENTS.md should exist");
        assertFalse(Files.exists(legacyFile), "Legacy file should be deleted");
        assertEquals("# Style Content", Files.readString(agentsFile));
    }

    @Test
    void testTargetExists_NoMigration() throws Exception {
        // Create both files
        Path legacyFile = brokkDir.resolve("style.md");
        Files.writeString(legacyFile, "# Legacy");
        Files.writeString(agentsFile, "# Current Content");

        // Perform migration
        var legacyStyle = new ProjectFile(projectRoot, ".brokk/style.md");
        var agentsFilePf = new ProjectFile(projectRoot, "AGENTS.md");
        MigrationResult result = StyleGuideMigrator.migrate(legacyStyle, agentsFilePf, gitRepo);

        // Verify NO migration performed
        assertFalse(result.performed(), "Should not migrate when target exists with content");

        // Verify AGENTS.md kept current content
        String agentsContent = Files.readString(agentsFile);
        assertEquals("# Current Content", agentsContent);

        // Verify legacy file still exists
        assertTrue(Files.exists(legacyFile), "Legacy file should remain");
    }

    @Test
    void testTargetEmpty_MigrationProceeds() throws Exception {
        // Create legacy file and empty target
        Path legacyFile = brokkDir.resolve("style.md");
        Files.writeString(legacyFile, "# Legacy Content");
        Files.writeString(agentsFile, "");

        // Perform migration
        var legacyStyle = new ProjectFile(projectRoot, ".brokk/style.md");
        var agentsFilePf = new ProjectFile(projectRoot, "AGENTS.md");
        MigrationResult result = StyleGuideMigrator.migrate(legacyStyle, agentsFilePf, gitRepo);

        // Verify migration performed
        assertTrue(result.performed(), "Migration should proceed for empty target");

        // Verify content migrated
        assertEquals("# Legacy Content", Files.readString(agentsFile));
        assertFalse(Files.exists(legacyFile));
    }

    @Test
    void testLegacyMissing_NoMigration() throws Exception {
        // Don't create legacy file

        // Perform migration
        var legacyStyle = new ProjectFile(projectRoot, ".brokk/style.md");
        var agentsFilePf = new ProjectFile(projectRoot, "AGENTS.md");
        MigrationResult result = StyleGuideMigrator.migrate(legacyStyle, agentsFilePf, gitRepo);

        // Verify NO migration performed
        assertFalse(result.performed(), "Should not migrate when legacy file missing");
        assertTrue(result.message().contains("does not exist"));
    }

    @Test
    void testLegacyEmpty_NoMigration() throws Exception {
        // Create empty legacy file
        Path legacyFile = brokkDir.resolve("style.md");
        Files.writeString(legacyFile, "");

        // Perform migration
        var legacyStyle = new ProjectFile(projectRoot, ".brokk/style.md");
        var agentsFilePf = new ProjectFile(projectRoot, "AGENTS.md");
        MigrationResult result = StyleGuideMigrator.migrate(legacyStyle, agentsFilePf, gitRepo);

        // Verify NO migration performed
        assertFalse(result.performed(), "Should not migrate empty legacy file");
        assertTrue(result.message().contains("empty"));
    }

    @Test
    void testIdempotency() throws Exception {
        // Create legacy file
        Path legacyFile = brokkDir.resolve("style.md");
        Files.writeString(legacyFile, "# Content");

        // Perform migration first time
        var legacyStyle = new ProjectFile(projectRoot, ".brokk/style.md");
        var agentsFilePf = new ProjectFile(projectRoot, "AGENTS.md");
        MigrationResult result1 = StyleGuideMigrator.migrate(legacyStyle, agentsFilePf, gitRepo);
        assertTrue(result1.performed(), "First migration should succeed");

        // Perform migration second time
        MigrationResult result2 = StyleGuideMigrator.migrate(legacyStyle, agentsFilePf, gitRepo);
        assertFalse(result2.performed(), "Second migration should be no-op");

        // Verify AGENTS.md still has correct content
        assertEquals("# Content", Files.readString(agentsFile));
    }

    @Test
    void testGitStagingWithMove() throws Exception {
        // Create and commit legacy file first
        Path legacyFile = brokkDir.resolve("style.md");
        Files.writeString(legacyFile, "# Legacy Style Guide\nOld content");

        try (Git git = Git.open(projectRoot.toFile())) {
            // Git uses forward slashes internally regardless of OS
            git.add().addFilepattern(".brokk/style.md").call();
            git.commit()
                    .setMessage("Add legacy style.md")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
        }

        // Perform migration
        var legacyStyle = new ProjectFile(projectRoot, ".brokk/style.md");
        var agentsFilePf = new ProjectFile(projectRoot, "AGENTS.md");
        MigrationResult result = StyleGuideMigrator.migrate(legacyStyle, agentsFilePf, gitRepo);
        assertTrue(result.performed(), "Migration should succeed");

        // Verify files on disk
        assertTrue(Files.exists(agentsFile), "AGENTS.md should exist");
        assertFalse(Files.exists(legacyFile), "Legacy file should be deleted");
        assertEquals("# Legacy Style Guide\nOld content", Files.readString(agentsFile));

        // Verify Git staging
        try (Git git = Git.open(projectRoot.toFile())) {
            var status = git.status().call();

            // Check that changes are staged (either as move, or as add+remove)
            // Git move may show as added + removed before commit
            // Git status always uses forward slashes for paths regardless of OS
            boolean agentsAdded = status.getAdded().contains("AGENTS.md");
            boolean legacyRemoved = status.getRemoved().contains(".brokk/style.md");

            assertTrue(
                    agentsAdded || status.getChanged().contains("AGENTS.md"),
                    "AGENTS.md should be staged (added or changed): " + status.getAdded() + " / "
                            + status.getChanged());
            assertTrue(
                    legacyRemoved || status.getChanged().contains(".brokk/style.md"),
                    ".brokk/style.md should be staged for removal: " + status.getRemoved() + " / "
                            + status.getChanged());
        }
    }

    @Test
    void testGitStagingFreshFile() throws Exception {
        // Create legacy file but don't commit it (fresh migration scenario)
        Path legacyFile = brokkDir.resolve("style.md");
        Files.writeString(legacyFile, "# Fresh Style Guide");

        // Perform migration
        var legacyStyle = new ProjectFile(projectRoot, ".brokk/style.md");
        var agentsFilePf = new ProjectFile(projectRoot, "AGENTS.md");
        MigrationResult result = StyleGuideMigrator.migrate(legacyStyle, agentsFilePf, gitRepo);
        assertTrue(result.performed(), "Migration should succeed");

        // Verify Git staging
        try (Git git = Git.open(projectRoot.toFile())) {
            var status = git.status().call();

            // Git status always uses forward slashes for paths regardless of OS
            // AGENTS.md should be staged as added
            assertTrue(
                    status.getAdded().contains("AGENTS.md"),
                    "AGENTS.md should be in added files: " + status.getAdded());

            // Legacy file might be in untracked or removed depending on whether move detected it
            assertTrue(
                    status.getRemoved().contains(".brokk/style.md")
                            || status.getUntracked().contains(".brokk/style.md")
                            || !Files.exists(legacyFile),
                    ".brokk/style.md should be removed or untracked");
        }
    }

    @Test
    void testGitStagingFailureDoesNotPreventMigration() throws Exception {
        // Create a scenario where git staging might fail but file operations should succeed
        Path legacyFile = brokkDir.resolve("style.md");
        Files.writeString(legacyFile, "# Content");

        // Use a git repo wrapper that simulates staging failure
        var failingGitRepo = new GitRepo(projectRoot) {
            @Override
            public synchronized void move(String from, String to) {
                throw new RuntimeException("Simulated move failure");
            }

            @Override
            public synchronized void add(java.util.Collection<ProjectFile> files) {
                throw new RuntimeException("Simulated add failure");
            }

            @Override
            public synchronized void remove(ProjectFile file) {
                throw new RuntimeException("Simulated remove failure");
            }
        };

        // Migration should fail due to git staging errors
        var legacyStyle = new ProjectFile(projectRoot, ".brokk/style.md");
        var agentsFilePf = new ProjectFile(projectRoot, "AGENTS.md");
        MigrationResult result = StyleGuideMigrator.migrate(legacyStyle, agentsFilePf, failingGitRepo);

        assertFalse(result.performed(), "Migration should fail when git operations fail");
        assertTrue(
                result.message().contains("Migration failed"),
                "Error message should indicate failure: " + result.message());
    }
}
