package ai.brokk.init;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IProject;
import ai.brokk.agents.BuildAgent.BuildDetails;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Note: GitIgnoreUtils is in same package, no import needed but included for clarity

/**
 * Tests for InitializationCoordinator.
 * These tests run without UI (no Swing dependencies).
 */
class InitializationCoordinatorTest {

    @TempDir
    Path tempDir;

    private InitializationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new InitializationCoordinator();
    }

    /**
     * Minimal implementation of IProject for testing.
     */
    private static class TestProject implements IProject {
        private final Path root;

        TestProject(Path root) {
            this.root = root;
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
        public void close() {
            // No-op for tests
        }
    }

    /**
     * Creates a mock project for testing.
     * Creates real directory structure in temp dir.
     */
    private IProject createMockProject(
            boolean hasAgentsMd, boolean hasLegacyStyleMd, boolean hasGitignore, boolean gitignoreComplete)
            throws Exception {
        // Create .brokk directory
        Files.createDirectories(tempDir.resolve(".brokk"));

        // Create AGENTS.md if requested
        if (hasAgentsMd) {
            Files.writeString(tempDir.resolve("AGENTS.md"), "# Style Guide\nGenerated content");
        }

        // Create .brokk/style.md if requested
        if (hasLegacyStyleMd) {
            Files.writeString(tempDir.resolve(".brokk/style.md"), "# Legacy Style\nOld content");
        }

        // Create .gitignore if requested
        if (hasGitignore) {
            var content = gitignoreComplete
                    ? ".brokk/**\n" // Comprehensive pattern
                    : ".brokk/workspace.properties\n"; // Partial pattern
            Files.writeString(tempDir.resolve(".gitignore"), content);
        }

        // Create project.properties
        Files.writeString(tempDir.resolve(".brokk/project.properties"), "# Properties");

        // Return test project
        return new TestProject(tempDir);
    }

    /**
     * Creates futures that complete successfully.
     */
    private CompletableFuture<Void> createSuccessfulStyleFuture() {
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<BuildDetails> createSuccessfulBuildFuture() {
        return CompletableFuture.completedFuture(BuildDetails.EMPTY);
    }

    /**
     * Test 1: Fresh project (Scenario 1) - All dialogs needed
     */
    @Test
    void testFreshProject_AllDialogsNeeded() throws Exception {
        // Setup: Fresh project with AGENTS.md but no .gitignore
        var project = createMockProject(
                true, // hasAgentsMd
                false, // hasLegacyStyleMd
                false, // hasGitignore
                false // gitignoreComplete (N/A)
                );

        // Execute
        var result = coordinator
                .coordinate(project, createSuccessfulStyleFuture(), createSuccessfulBuildFuture())
                .get(5, TimeUnit.SECONDS);

        // Verify
        assertFalse(result.needsMigrationDialog(), "Should not need migration (no legacy file)");
        assertTrue(result.needsBuildSettingsDialog(), "Should need build settings (not fully configured)");
        assertTrue(result.needsGitConfigDialog(), "Should need git config (no .gitignore)");
        assertEquals(InitializationCoordinator.Phase.COMPLETE, result.finalPhase());
    }

    /**
     * Test 2: Legacy project (Scenario 2) - Migration needed
     */
    @Test
    void testLegacyProject_MigrationNeeded() throws Exception {
        // Setup: Project with .brokk/style.md but no AGENTS.md
        var project = createMockProject(
                false, // hasAgentsMd
                true, // hasLegacyStyleMd
                true, // hasGitignore
                false // gitignoreComplete (partial pattern)
                );

        // Execute
        var result = coordinator
                .coordinate(project, createSuccessfulStyleFuture(), createSuccessfulBuildFuture())
                .get(5, TimeUnit.SECONDS);

        // Verify
        assertTrue(result.needsMigrationDialog(), "Should need migration (.brokk/style.md exists)");
        assertTrue(result.needsBuildSettingsDialog(), "Should need build settings");
        assertTrue(result.needsGitConfigDialog(), "Should need git config (incomplete .gitignore)");
    }

    /**
     * Test 3: Fully configured project (Scenario 3) - No dialogs
     */
    @Test
    void testFullyConfiguredProject_NoDialogs() throws Exception {
        // Setup: Project fully configured
        var project = createMockProject(
                true, // hasAgentsMd
                false, // hasLegacyStyleMd
                true, // hasGitignore
                true // gitignoreComplete
                );

        // Execute
        var result = coordinator
                .coordinate(project, createSuccessfulStyleFuture(), createSuccessfulBuildFuture())
                .get(5, TimeUnit.SECONDS);

        // Verify
        assertFalse(result.needsMigrationDialog(), "Should not need migration");
        assertFalse(result.needsBuildSettingsDialog(), "Should not need build settings (fully configured)");
        assertFalse(result.needsGitConfigDialog(), "Should not need git config (complete .gitignore)");
    }

    /**
     * Test 4: Empty legacy file - should not show migration dialog
     */
    @Test
    void testEmptyLegacyFile_NoMigration() throws Exception {
        // Setup: .brokk/style.md exists but is empty
        Files.createDirectories(tempDir.resolve(".brokk"));
        Files.writeString(tempDir.resolve(".brokk/style.md"), ""); // Empty file
        Files.writeString(tempDir.resolve(".brokk/project.properties"), "# Properties");
        Files.writeString(tempDir.resolve("AGENTS.md"), "# Generated");
        Files.writeString(tempDir.resolve(".gitignore"), ".brokk/**");

        var project = createMockProject(false, false, true, true);

        // Execute
        var result = coordinator
                .coordinate(project, createSuccessfulStyleFuture(), createSuccessfulBuildFuture())
                .get(5, TimeUnit.SECONDS);

        // Verify
        assertFalse(result.needsMigrationDialog(), "Should NOT need migration (legacy file is empty)");
    }

    /**
     * Test 5: File visibility wait - retries until file appears
     */
    @Test
    void testFileVisibility_WaitsForFileToAppear() throws Exception {
        var project = createMockProject(false, false, false, false);

        // Create file in background with delay
        var styleFuture = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(100); // Simulate slow file write
                Files.writeString(tempDir.resolve("AGENTS.md"), "Delayed content");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Execute - should wait for file to appear
        var result = coordinator
                .coordinate(project, styleFuture, createSuccessfulBuildFuture())
                .get(5, TimeUnit.SECONDS);

        // Verify file exists (visibility was ensured)
        assertTrue(Files.exists(tempDir.resolve("AGENTS.md")));
        assertNotNull(result);
    }

    /**
     * Test 6: Phase transitions
     */
    @Test
    void testPhaseTransitions() throws Exception {
        var project = createMockProject(true, false, false, false);

        assertEquals(InitializationCoordinator.Phase.CREATING_PROJECT, coordinator.getCurrentPhase());

        var resultFuture =
                coordinator.coordinate(project, createSuccessfulStyleFuture(), createSuccessfulBuildFuture());

        // Wait for completion
        var result = resultFuture.get(5, TimeUnit.SECONDS);

        // Should be in COMPLETE phase
        assertEquals(InitializationCoordinator.Phase.COMPLETE, coordinator.getCurrentPhase());
        assertEquals(InitializationCoordinator.Phase.COMPLETE, result.finalPhase());
    }

    /**
     * Test 7: Git ignore pattern matching - comprehensive pattern
     */
    @Test
    void testGitIgnorePatternMatching_Comprehensive() throws Exception {
        Files.createDirectories(tempDir.resolve(".brokk"));
        Files.writeString(tempDir.resolve(".brokk/project.properties"), "# Properties");
        Files.writeString(tempDir.resolve("AGENTS.md"), "# Generated");

        // Create .gitignore with comprehensive pattern
        Files.writeString(tempDir.resolve(".gitignore"), ".brokk/**\n");

        var project = createMockProject(false, false, true, true);

        var result = coordinator
                .coordinate(project, createSuccessfulStyleFuture(), createSuccessfulBuildFuture())
                .get(5, TimeUnit.SECONDS);

        // Should not need git config (comprehensive pattern exists)
        assertFalse(result.needsGitConfigDialog());
    }

    /**
     * Test 8: Git ignore with comments
     */
    @Test
    void testGitIgnoreWithComments() throws Exception {
        Files.createDirectories(tempDir.resolve(".brokk"));
        Files.writeString(tempDir.resolve(".brokk/project.properties"), "# Properties");
        Files.writeString(tempDir.resolve("AGENTS.md"), "# Generated");

        // Create .gitignore with pattern and comment
        Files.writeString(tempDir.resolve(".gitignore"), ".brokk/** # Brokk files\n");

        var project = createMockProject(false, false, true, true);

        var result = coordinator
                .coordinate(project, createSuccessfulStyleFuture(), createSuccessfulBuildFuture())
                .get(5, TimeUnit.SECONDS);

        // Should recognize pattern despite comment
        assertFalse(result.needsGitConfigDialog());
    }

    /**
     * Test 9: Legacy file with empty content (whitespace only)
     */
    @Test
    void testLegacyFileWithWhitespace_NoMigration() throws Exception {
        Files.createDirectories(tempDir.resolve(".brokk"));
        Files.writeString(tempDir.resolve(".brokk/style.md"), "   \n\n  \t  "); // Whitespace only
        Files.writeString(tempDir.resolve(".brokk/project.properties"), "# Properties");
        Files.writeString(tempDir.resolve("AGENTS.md"), "# Generated");
        Files.writeString(tempDir.resolve(".gitignore"), ".brokk/**");

        var project = createMockProject(false, false, true, true);

        var result = coordinator
                .coordinate(project, createSuccessfulStyleFuture(), createSuccessfulBuildFuture())
                .get(5, TimeUnit.SECONDS);

        // Verify no migration needed (after trim, it's empty)
        assertFalse(result.needsMigrationDialog(), "Should NOT need migration (legacy file has only whitespace)");
    }

    /**
     * Test 10: Project with AGENTS.md AND legacy style.md - no migration
     */
    @Test
    void testBothFilesExist_NoMigration() throws Exception {
        Files.createDirectories(tempDir.resolve(".brokk"));
        Files.writeString(tempDir.resolve(".brokk/style.md"), "# Legacy");
        Files.writeString(tempDir.resolve("AGENTS.md"), "# Generated");
        Files.writeString(tempDir.resolve(".brokk/project.properties"), "# Properties");
        Files.writeString(tempDir.resolve(".gitignore"), ".brokk/**");

        var project = createMockProject(false, false, true, true);

        var result = coordinator
                .coordinate(project, createSuccessfulStyleFuture(), createSuccessfulBuildFuture())
                .get(5, TimeUnit.SECONDS);

        // Should not need migration (AGENTS.md already exists)
        assertFalse(result.needsMigrationDialog());
    }

    // ========== Tests for State Probe Helpers ==========

    /**
     * Test 11: hasAgentsMd helper
     */
    @Test
    void testHasAgentsMd() throws Exception {
        // Setup: No AGENTS.md
        assertFalse(InitializationCoordinator.hasAgentsMd(tempDir));

        // Create AGENTS.md
        Files.writeString(tempDir.resolve("AGENTS.md"), "# Agents");

        // Verify
        assertTrue(InitializationCoordinator.hasAgentsMd(tempDir));
    }

    /**
     * Test 12: hasAgentsMdWithContent helper
     */
    @Test
    void testHasAgentsMdWithContent() throws Exception {
        // Setup: No file
        assertFalse(InitializationCoordinator.hasAgentsMdWithContent(tempDir));

        // Create empty file
        Files.writeString(tempDir.resolve("AGENTS.md"), "");
        assertFalse(InitializationCoordinator.hasAgentsMdWithContent(tempDir), "Empty file should return false");

        // Add content
        Files.writeString(tempDir.resolve("AGENTS.md"), "# Agents");
        assertTrue(InitializationCoordinator.hasAgentsMdWithContent(tempDir));
    }

    /**
     * Test 13: hasLegacyStyleMd helper
     */
    @Test
    void testHasLegacyStyleMd() throws Exception {
        // Setup: No legacy file
        Files.createDirectories(tempDir.resolve(".brokk"));
        assertFalse(InitializationCoordinator.hasLegacyStyleMd(tempDir));

        // Create legacy file
        Files.writeString(tempDir.resolve(".brokk/style.md"), "# Legacy");

        // Verify
        assertTrue(InitializationCoordinator.hasLegacyStyleMd(tempDir));
    }

    /**
     * Test 14: hasLegacyStyleMdWithContent helper
     */
    @Test
    void testHasLegacyStyleMdWithContent() throws Exception {
        Files.createDirectories(tempDir.resolve(".brokk"));

        // Setup: No file
        assertFalse(InitializationCoordinator.hasLegacyStyleMdWithContent(tempDir));

        // Create empty file
        Files.writeString(tempDir.resolve(".brokk/style.md"), "");
        assertFalse(InitializationCoordinator.hasLegacyStyleMdWithContent(tempDir), "Empty file should return false");

        // Create file with only whitespace
        Files.writeString(tempDir.resolve(".brokk/style.md"), "   \n  \t  ");
        assertFalse(InitializationCoordinator.hasLegacyStyleMdWithContent(tempDir), "Whitespace-only file should return false");

        // Add actual content
        Files.writeString(tempDir.resolve(".brokk/style.md"), "# Legacy Style");
        assertTrue(InitializationCoordinator.hasLegacyStyleMdWithContent(tempDir));
    }

    /**
     * Test 15: hasProjectProperties helper
     */
    @Test
    void testHasProjectProperties() throws Exception {
        Files.createDirectories(tempDir.resolve(".brokk"));

        // Setup: No properties file
        assertFalse(InitializationCoordinator.hasProjectProperties(tempDir));

        // Create properties file
        Files.writeString(tempDir.resolve(".brokk/project.properties"), "# Properties");

        // Verify
        assertTrue(InitializationCoordinator.hasProjectProperties(tempDir));
    }

    /**
     * Test 16: hasProjectPropertiesWithContent helper
     */
    @Test
    void testHasProjectPropertiesWithContent() throws Exception {
        Files.createDirectories(tempDir.resolve(".brokk"));

        // Setup: No file
        assertFalse(InitializationCoordinator.hasProjectPropertiesWithContent(tempDir));

        // Create empty file
        Files.writeString(tempDir.resolve(".brokk/project.properties"), "");
        assertFalse(InitializationCoordinator.hasProjectPropertiesWithContent(tempDir), "Empty file should return false");

        // Add content
        Files.writeString(tempDir.resolve(".brokk/project.properties"), "# Properties");
        assertTrue(InitializationCoordinator.hasProjectPropertiesWithContent(tempDir));
    }

    /**
     * Test 17: isBrokkIgnored helper with various patterns
     */
    @Test
    void testIsBrokkIgnored_VariousPatterns() throws Exception {
        var gitignorePath = tempDir.resolve(".gitignore");

        // No .gitignore
        assertFalse(GitIgnoreUtils.isBrokkIgnored(gitignorePath));

        // Comprehensive pattern .brokk/**
        Files.writeString(gitignorePath, ".brokk/**\n");
        assertTrue(GitIgnoreUtils.isBrokkIgnored(gitignorePath));

        // Alternative pattern .brokk/
        Files.writeString(gitignorePath, ".brokk/\n");
        assertTrue(GitIgnoreUtils.isBrokkIgnored(gitignorePath));

        // Partial pattern (not comprehensive)
        Files.writeString(gitignorePath, ".brokk/workspace.properties\n");
        assertFalse(GitIgnoreUtils.isBrokkIgnored(gitignorePath), "Partial pattern should not match");

        // Pattern with comment
        Files.writeString(gitignorePath, ".brokk/** # Brokk files\n");
        assertTrue(GitIgnoreUtils.isBrokkIgnored(gitignorePath), "Should recognize pattern with comment");

        // Pattern with whitespace
        Files.writeString(gitignorePath, "  .brokk/**  \n");
        assertTrue(GitIgnoreUtils.isBrokkIgnored(gitignorePath), "Should recognize pattern with whitespace");
    }
}
