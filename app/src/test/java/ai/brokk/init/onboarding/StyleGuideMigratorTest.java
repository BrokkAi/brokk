package ai.brokk.init.onboarding;

import static org.junit.jupiter.api.Assertions.*;

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

    /**
     * Test 1: Successful migration with git staging
     */
    @Test
    void testSuccessfulMigration_WithGit() throws Exception {
        // Create legacy style.md with content
        Path legacyFile = brokkDir.resolve("style.md");
        Files.writeString(legacyFile, "# Legacy Style Guide\nOld content here");

        // Perform migration
        MigrationResult result = StyleGuideMigrator.migrate(brokkDir, agentsFile, gitRepo);

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

    /**
     * Test 2: Successful migration without git (non-git project)
     */
    @Test
    void testSuccessfulMigration_WithoutGit() throws Exception {
        // Create legacy style.md with content
        Path legacyFile = brokkDir.resolve("style.md");
        Files.writeString(legacyFile, "# Style Content");

        // Perform migration without git
        MigrationResult result = StyleGuideMigrator.migrate(brokkDir, agentsFile, null);

        // Verify migration performed
        assertTrue(result.performed(), "Migration should be performed");

        // Verify files
        assertTrue(Files.exists(agentsFile), "AGENTS.md should exist");
        assertFalse(Files.exists(legacyFile), "Legacy file should be deleted");
        assertEquals("# Style Content", Files.readString(agentsFile));
    }

    /**
     * Test 3: Target already exists with content - no migration
     */
    @Test
    void testTargetExists_NoMigration() throws Exception {
        // Create both files
        Path legacyFile = brokkDir.resolve("style.md");
        Files.writeString(legacyFile, "# Legacy");
        Files.writeString(agentsFile, "# Current Content");

        // Perform migration
        MigrationResult result = StyleGuideMigrator.migrate(brokkDir, agentsFile, gitRepo);

        // Verify NO migration performed
        assertFalse(result.performed(), "Should not migrate when target exists with content");

        // Verify AGENTS.md kept current content
        String agentsContent = Files.readString(agentsFile);
        assertEquals("# Current Content", agentsContent);

        // Verify legacy file still exists
        assertTrue(Files.exists(legacyFile), "Legacy file should remain");
    }

    /**
     * Test 4: Target exists but is empty - migration should proceed
     */
    @Test
    void testTargetEmpty_MigrationProceeds() throws Exception {
        // Create legacy file and empty target
        Path legacyFile = brokkDir.resolve("style.md");
        Files.writeString(legacyFile, "# Legacy Content");
        Files.writeString(agentsFile, "");

        // Perform migration
        MigrationResult result = StyleGuideMigrator.migrate(brokkDir, agentsFile, gitRepo);

        // Verify migration performed
        assertTrue(result.performed(), "Migration should proceed for empty target");

        // Verify content migrated
        assertEquals("# Legacy Content", Files.readString(agentsFile));
        assertFalse(Files.exists(legacyFile));
    }

    /**
     * Test 5: Target exists but is blank (whitespace only) - migration should proceed
     */
    @Test
    void testTargetBlank_MigrationProceeds() throws Exception {
        // Create legacy file and blank target
        Path legacyFile = brokkDir.resolve("style.md");
        Files.writeString(legacyFile, "# Legacy Content");
        Files.writeString(agentsFile, "   \n  \n");

        // Perform migration
        MigrationResult result = StyleGuideMigrator.migrate(brokkDir, agentsFile, gitRepo);

        // Verify migration performed
        assertTrue(result.performed(), "Migration should proceed for blank target");

        // Verify content migrated
        assertEquals("# Legacy Content", Files.readString(agentsFile));
        assertFalse(Files.exists(legacyFile));
    }

    /**
     * Test 6: Legacy file doesn't exist - no migration
     */
    @Test
    void testLegacyMissing_NoMigration() throws Exception {
        // Don't create legacy file

        // Perform migration
        MigrationResult result = StyleGuideMigrator.migrate(brokkDir, agentsFile, gitRepo);

        // Verify NO migration performed
        assertFalse(result.performed(), "Should not migrate when legacy file missing");
        assertTrue(result.message().contains("does not exist"));
    }

    /**
     * Test 7: Legacy file is empty - no migration
     */
    @Test
    void testLegacyEmpty_NoMigration() throws Exception {
        // Create empty legacy file
        Path legacyFile = brokkDir.resolve("style.md");
        Files.writeString(legacyFile, "");

        // Perform migration
        MigrationResult result = StyleGuideMigrator.migrate(brokkDir, agentsFile, gitRepo);

        // Verify NO migration performed
        assertFalse(result.performed(), "Should not migrate empty legacy file");
        assertTrue(result.message().contains("empty"));
    }

    /**
     * Test 8: Legacy file is blank (whitespace only) - no migration
     */
    @Test
    void testLegacyBlank_NoMigration() throws Exception {
        // Create blank legacy file
        Path legacyFile = brokkDir.resolve("style.md");
        Files.writeString(legacyFile, "   \n  \n");

        // Perform migration
        MigrationResult result = StyleGuideMigrator.migrate(brokkDir, agentsFile, gitRepo);

        // Verify NO migration performed
        assertFalse(result.performed(), "Should not migrate blank legacy file");
    }

    /**
     * Test 9: Legacy file is unreadable - error result
     */
    @Test
    void testLegacyUnreadable_ErrorResult() throws Exception {
        // Create legacy file
        Path legacyFile = brokkDir.resolve("style.md");
        Files.writeString(legacyFile, "# Content");

        // Make file unreadable
        legacyFile.toFile().setReadable(false);

        try {
            // Perform migration
            MigrationResult result = StyleGuideMigrator.migrate(brokkDir, agentsFile, gitRepo);

            // Verify error reported
            assertFalse(result.performed(), "Migration should fail for unreadable file");
            assertTrue(result.message().contains("Cannot read"), "Should mention read error");
        } finally {
            // Restore readability for cleanup
            legacyFile.toFile().setReadable(true);
        }
    }

    /**
     * Test 10: Idempotency - calling twice doesn't cause issues
     */
    @Test
    void testIdempotency() throws Exception {
        // Create legacy file
        Path legacyFile = brokkDir.resolve("style.md");
        Files.writeString(legacyFile, "# Content");

        // Perform migration first time
        MigrationResult result1 = StyleGuideMigrator.migrate(brokkDir, agentsFile, gitRepo);
        assertTrue(result1.performed(), "First migration should succeed");

        // Perform migration second time
        MigrationResult result2 = StyleGuideMigrator.migrate(brokkDir, agentsFile, gitRepo);
        assertFalse(result2.performed(), "Second migration should be no-op");

        // Verify AGENTS.md still has correct content
        assertEquals("# Content", Files.readString(agentsFile));
    }

    /**
     * Test 11: Migration with multiline content
     */
    @Test
    void testMultilineContent() throws Exception {
        // Create legacy file with multiline content
        Path legacyFile = brokkDir.resolve("style.md");
        String multilineContent = """
                # Style Guide

                ## Formatting
                - Use spaces
                - No trailing whitespace

                ## Naming
                - Use camelCase
                """;
        Files.writeString(legacyFile, multilineContent);

        // Perform migration
        MigrationResult result = StyleGuideMigrator.migrate(brokkDir, agentsFile, gitRepo);

        // Verify migration
        assertTrue(result.performed());
        assertEquals(multilineContent, Files.readString(agentsFile));
        assertFalse(Files.exists(legacyFile));
    }

    /**
     * Test 12: Migration preserves exact content including special characters
     */
    @Test
    void testSpecialCharacters() throws Exception {
        // Create legacy file with special characters
        Path legacyFile = brokkDir.resolve("style.md");
        String specialContent = "# Style\n\nUse `code` and *emphasis* and [links](http://example.com)\n";
        Files.writeString(legacyFile, specialContent);

        // Perform migration
        MigrationResult result = StyleGuideMigrator.migrate(brokkDir, agentsFile, gitRepo);

        // Verify exact content preserved
        assertTrue(result.performed());
        assertEquals(specialContent, Files.readString(agentsFile));
    }
}
