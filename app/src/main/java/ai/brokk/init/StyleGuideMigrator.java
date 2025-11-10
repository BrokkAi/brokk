package ai.brokk.init;

import ai.brokk.git.GitRepo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Handles migration of legacy .brokk/style.md to AGENTS.md at project root.
 * This is a standalone utility that can be called by orchestrators before
 * setting up git ignore configuration.
 */
public class StyleGuideMigrator {
    private static final Logger logger = LogManager.getLogger(StyleGuideMigrator.class);

    /**
     * Result of migration operation.
     *
     * @param performed Whether migration was actually performed
     * @param message Optional message describing what happened or why migration was skipped
     */
    public record MigrationResult(boolean performed, String message) {}

    /**
     * Migrates legacy .brokk/style.md to AGENTS.md at project root if appropriate.
     *
     * <p>Migration is performed only if:
     * <ul>
     *   <li>Legacy file (.brokk/style.md) exists and is not blank
     *   <li>Target file (AGENTS.md) either doesn't exist or is blank/empty
     * </ul>
     *
     * <p>The migration involves:
     * <ul>
     *   <li>Reading content from .brokk/style.md
     *   <li>Writing content to AGENTS.md
     *   <li>Deleting .brokk/style.md
     *   <li>Optionally staging both files to git (if gitRepo provided)
     * </ul>
     *
     * <p>This operation is idempotent - calling it multiple times will not cause issues.
     *
     * @param brokkDir Path to .brokk directory (e.g., /project/.brokk)
     * @param agentsFile Path to AGENTS.md file (e.g., /project/AGENTS.md)
     * @param gitRepo Optional GitRepo for staging changes (can be null for non-git operations)
     * @return Result indicating whether migration was performed and a descriptive message
     */
    public static MigrationResult migrate(Path brokkDir, Path agentsFile, @Nullable GitRepo gitRepo) {
        var legacyStylePath = brokkDir.resolve("style.md");

        // Check if legacy file exists
        if (!Files.exists(legacyStylePath)) {
            return new MigrationResult(false, "Legacy style.md does not exist");
        }

        // Read legacy content
        String legacyContent;
        try {
            legacyContent = Files.readString(legacyStylePath);
        } catch (IOException ex) {
            logger.warn("Cannot read legacy style.md: {}", ex.getMessage());
            return new MigrationResult(false, "Cannot read legacy style.md: " + ex.getMessage());
        }

        // Skip if legacy content is blank
        if (legacyContent.isBlank()) {
            return new MigrationResult(false, "Legacy style.md is empty");
        }

        // Check target file status
        boolean targetMissing = !Files.exists(agentsFile);
        boolean targetEmpty = false;

        if (!targetMissing) {
            try {
                targetEmpty = Files.readString(agentsFile).isBlank();
            } catch (IOException ex) {
                logger.warn("Error reading target AGENTS.md, treating as empty: {}", ex.getMessage());
                targetEmpty = true;
            }
        }

        // Skip if target exists and has content
        if (!targetMissing && !targetEmpty) {
            return new MigrationResult(false, "AGENTS.md already exists with content");
        }

        // Perform migration
        try {
            // If git repo provided, stage legacy file before deleting so git tracks the deletion
            if (gitRepo != null) {
                gitRepo.add(legacyStylePath);
            }

            // Write content to new location
            Files.writeString(agentsFile, legacyContent);

            // Delete legacy file
            Files.delete(legacyStylePath);

            logger.info("Successfully migrated style guide from .brokk/style.md to AGENTS.md");
            return new MigrationResult(true, "Migrated style.md to AGENTS.md");

        } catch (Exception ex) {
            logger.error("Migration failed: {}", ex.getMessage(), ex);
            return new MigrationResult(false, "Migration failed: " + ex.getMessage());
        }
    }
}
