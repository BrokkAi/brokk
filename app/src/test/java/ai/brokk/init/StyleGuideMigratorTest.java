package ai.brokk.init;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.git.GitRepo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for StyleGuideMigrator.
 * Covers success paths (git and non-git), edge cases, and idempotency.
 */
class StyleGuideMigratorTest {

    private StyleGuideMigrator migrator;

    @BeforeEach
    void setUp() {
        migrator = new StyleGuideMigrator();
    }

    @Test
    void testMigrateSuccessNonGit(@TempDir Path tempDir) throws IOException {
        // Setup: legacy style.md with content, no git repo
        Path brokkDir = tempDir.resolve(".brokk");
        Files.createDirectories(brokkDir);
        Path legacyPath = brokkDir.resolve("style.md");
        String content = "# Style Guide\nCode style rules here.";
        Files.writeString(legacyPath, content);

        Path agentsMdPath = tempDir.resolve("AGENTS.md");

        // Execute
        StyleGuideMigrator.MigrationResult result = migrator.migrate(brokkDir, agentsMdPath, null);

        // Verify
        assertTrue(result.success());
        assertTrue(result.wasMigrated());
        assertFalse(result.stagedToGit()); // No git repo provided
        assertNull(result.errorMessage());
        assertTrue(Files.exists(agentsMdPath));
        assertEquals(content, Files.readString(agentsMdPath));
        assertFalse(Files.exists(legacyPath));
    }

    @Test
    void testMigrateLegacyNotFound(@TempDir Path tempDir) {
        // Setup: no legacy style.md
        Path brokkDir = tempDir.resolve(".brokk");
        Files.createDirectories(brokkDir);
        Path agentsMdPath = tempDir.resolve("AGENTS.md");

        // Execute
        StyleGuideMigrator.MigrationResult result = migrator.migrate(brokkDir, agentsMdPath, null);

        // Verify: idempotent (no-op)
        assertTrue(result.success());
        assertFalse(result.wasMigrated());
        assertNull(result.errorMessage());
    }

    @Test
    void testMigrateLegacyEmpty(@TempDir Path tempDir) throws IOException {
        // Setup: legacy style.md exists but is empty
        Path brokkDir = tempDir.resolve(".brokk");
        Files.createDirectories(brokkDir);
        Path legacyPath = brokkDir.resolve("style.md");
        Files.writeString(legacyPath, "   \n\n  ");

        Path agentsMdPath = tempDir.resolve("AGENTS.md");

        // Execute
        StyleGuideMigrator.MigrationResult result = migrator.migrate(brokkDir, agentsMdPath, null);

        // Verify: idempotent (no-op)
        assertTrue(result.success());
        assertFalse(result.wasMigrated());
        assertNull(result.errorMessage());
        assertTrue(Files.exists(legacyPath)); // Not deleted
    }

    @Test
    void testMigrateTargetAlreadyExists(@TempDir Path tempDir) throws IOException {
        // Setup: legacy with content, target AGENTS.md with content
        Path brokkDir = tempDir.resolve(".brokk");
        Files.createDirectories(brokkDir);
        Path legacyPath = brokkDir.resolve("style.md");
        Files.writeString(legacyPath, "Legacy content");

        Path agentsMdPath = tempDir.resolve("AGENTS.md");
        String existingContent = "Existing AGENTS content";
        Files.writeString(agentsMdPath, existingContent);

        // Execute
        StyleGuideMigrator.MigrationResult result = migrator.migrate(brokkDir, agentsMdPath, null);

        // Verify: idempotent (no-op)
        assertTrue(result.success());
        assertFalse(result.wasMigrated());
        assertNull(result.errorMessage());
        assertEquals(existingContent, Files.readString(agentsMdPath)); // Unchanged
        assertTrue(Files.exists(legacyPath)); // Not deleted
    }

    @Test
    void testMigrateTargetExistsButEmpty(@TempDir Path tempDir) throws IOException {
        // Setup: legacy with content, target AGENTS.md exists but is empty
        Path brokkDir = tempDir.resolve(".brokk");
        Files.createDirectories(brokkDir);
        Path legacyPath = brokkDir.resolve("style.md");
        String legacyContent = "# Important Guide";
        Files.writeString(legacyPath, legacyContent);

        Path agentsMdPath = tempDir.resolve("AGENTS.md");
        Files.writeString(agentsMdPath, "  \n  ");

        // Execute
        StyleGuideMigrator.MigrationResult result = migrator.migrate(brokkDir, agentsMdPath, null);

        // Verify: migrates because target is empty
        assertTrue(result.success());
        assertTrue(result.wasMigrated());
        assertNull(result.errorMessage());
        assertEquals(legacyContent, Files.readString(agentsMdPath));
        assertFalse(Files.exists(legacyPath));
    }

    @Test
    void testMigrateUnreadableLegacy(@TempDir Path tempDir) throws IOException {
        // Setup: legacy style.md that cannot be read
        Path brokkDir = tempDir.resolve(".brokk");
        Files.createDirectories(brokkDir);
        Path legacyPath = brokkDir.resolve("style.md");
        Files.writeString(legacyPath, "content");

        // Make file unreadable
        legacyPath.toFile().setReadable(false);

        Path agentsMdPath = tempDir.resolve("AGENTS.md");

        try {
            // Execute
            StyleGuideMigrator.MigrationResult result = migrator.migrate(brokkDir, agentsMdPath, null);

            // Verify: failure with error message
            assertFalse(result.success());
            assertFalse(result.wasMigrated());
            assertNotNull(result.errorMessage());
            assertTrue(result.errorMessage().contains("Unable to read legacy style.md"));
        } finally {
            // Restore permissions for cleanup
            legacyPath.toFile().setReadable(true);
        }
    }

    @Test
    void testMigrateIdempotentAfterSuccess(@TempDir Path tempDir) throws IOException {
        // Setup: legacy style.md with content
        Path brokkDir = tempDir.resolve(".brokk");
        Files.createDirectories(brokkDir);
        Path legacyPath = brokkDir.resolve("style.md");
        String content = "# Style Guide";
        Files.writeString(legacyPath, content);

        Path agentsMdPath = tempDir.resolve("AGENTS.md");

        // Execute first migration
        StyleGuideMigrator.MigrationResult result1 = migrator.migrate(brokkDir, agentsMdPath, null);
        assertTrue(result1.success());
        assertTrue(result1.wasMigrated());

        // Execute second migration (should be no-op)
        StyleGuideMigrator.MigrationResult result2 = migrator.migrate(brokkDir, agentsMdPath, null);

        // Verify: idempotent
        assertTrue(result2.success());
        assertFalse(result2.wasMigrated());
        assertNull(result2.errorMessage());
    }

    @Test
    void testMigrateWithMockGitRepo(@TempDir Path tempDir) throws IOException {
        // Setup: legacy style.md, test GitRepo implementation that tracks adds
        Path brokkDir = tempDir.resolve(".brokk");
        Files.createDirectories(brokkDir);
        Path legacyPath = brokkDir.resolve("style.md");
        String content = "# Style Guide";
        Files.writeString(legacyPath, content);

        Path agentsMdPath = tempDir.resolve("AGENTS.md");

        // Test GitRepo that tracks calls (default interface implementation pattern)
        Set<Path> stagedPaths = new HashSet<>();
        GitRepo testRepo = new GitRepo(tempDir) {
            @Override
            public void add(Path path) {
                stagedPaths.add(path);
            }

            @Override
            public void add(java.util.List<ai.brokk.analyzer.ProjectFile> files) {
                // Not used in this test
            }
        };

        // Execute
        StyleGuideMigrator.MigrationResult result = migrator.migrate(brokkDir, agentsMdPath, testRepo);

        // Verify
        assertTrue(result.success());
        assertTrue(result.wasMigrated());
        assertTrue(result.stagedToGit());
        assertNull(result.errorMessage());
        assertEquals(content, Files.readString(agentsMdPath));
        assertFalse(Files.exists(legacyPath));
        assertTrue(stagedPaths.contains(legacyPath));
        assertTrue(stagedPaths.contains(agentsMdPath));
    }

    @Test
    void testMigrateGitRepoStageFailureNonFatal(@TempDir Path tempDir) throws IOException {
        // Setup: migration should succeed even if git staging fails
        Path brokkDir = tempDir.resolve(".brokk");
        Files.createDirectories(brokkDir);
        Path legacyPath = brokkDir.resolve("style.md");
        String content = "# Style Guide";
        Files.writeString(legacyPath, content);

        Path agentsMdPath = tempDir.resolve("AGENTS.md");

        // Test GitRepo that throws on add() (default interface implementation pattern)
        GitRepo testRepo = new GitRepo(tempDir) {
            @Override
            public void add(Path path) throws Exception {
                throw new IOException("Git staging failed");
            }

            @Override
            public void add(java.util.List<ai.brokk.analyzer.ProjectFile> files) throws Exception {
                throw new IOException("Git staging failed");
            }
        };

        // Execute
        StyleGuideMigrator.MigrationResult result = migrator.migrate(brokkDir, agentsMdPath, testRepo);

        // Verify: migration succeeds, but staging failed
        assertTrue(result.success());
        assertTrue(result.wasMigrated());
        assertFalse(result.stagedToGit());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("Failed to stage migration to git"));
        // Files should still be migrated
        assertEquals(content, Files.readString(agentsMdPath));
        assertFalse(Files.exists(legacyPath));
    }
}
