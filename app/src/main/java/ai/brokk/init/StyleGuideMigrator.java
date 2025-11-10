package ai.brokk.init;

import ai.brokk.git.GitRepo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Non-UI helper for migrating legacy .brokk/style.md to AGENTS.md at project root.
 * Handles both Git-based (with staging) and non-Git scenarios, including edge cases.
 * Designed to be called explicitly by orchestrators, never implicitly by UI setup steps.
 */
public class StyleGuideMigrator {
    private static final Logger logger = LogManager.getLogger(StyleGuideMigrator.class);

    /**
     * Result of a migration attempt.
     *
     * @param success true if migration completed (including idempotent no-op)
     * @param wasMigrated true if files were actually moved/copied
     * @param stagedToGit true if migrated file was staged to git
     * @param errorMessage optional error details if success is false
     */
    public record MigrationResult(
            boolean success, boolean wasMigrated, boolean stagedToGit, @Nullable String errorMessage) {}

    /**
     * Migrates .brokk/style.md to AGENTS.md at the project root.
     *
     * <p>Behavior:
     * <ul>
     *   <li>If AGENTS.md already exists and has content, does nothing (idempotent).
     *   <li>If .brokk/style.md is missing or empty, does nothing (idempotent).
     *   <li>Otherwise, copies content to AGENTS.md and deletes .brokk/style.md.
     *   <li>If gitRepo is provided and non-null, stages the files to git.
     * </ul>
     *
     * @param brokkDir path to .brokk directory
     * @param agentsMdPath path to target AGENTS.md (typically projectRoot/AGENTS.md)
     * @param gitRepo optional GitRepo for staging; if null, no git staging occurs
     * @return MigrationResult with outcome details
     */
    public MigrationResult migrate(Path brokkDir, Path agentsMdPath, @Nullable GitRepo gitRepo) {
        try {
            Path legacyStylePath = brokkDir.resolve("style.md");

            // Check if legacy file exists and is readable
            if (!Files.exists(legacyStylePath)) {
                logger.debug("Legacy style.md not found at {}; migration is idempotent (no-op)", legacyStylePath);
                return new MigrationResult(true, false, false, null);
            }

            String legacyContent;
            try {
                legacyContent = Files.readString(legacyStylePath);
            } catch (IOException e) {
                String errorMsg = "Unable to read legacy style.md: " + e.getMessage();
                logger.error(errorMsg);
                return new MigrationResult(false, false, false, errorMsg);
            }

            // Skip if legacy file is empty
            if (legacyContent.isBlank()) {
                logger.debug("Legacy style.md is empty; migration is idempotent (no-op)");
                return new MigrationResult(true, false, false, null);
            }

            // Check if target already exists and has content
            if (Files.exists(agentsMdPath)) {
                try {
                    String targetContent = Files.readString(agentsMdPath);
                    if (!targetContent.isBlank()) {
                        logger.debug(
                                "AGENTS.md already exists with content; migration is idempotent (no-op)");
                        return new MigrationResult(true, false, false, null);
                    }
                } catch (IOException e) {
                    String errorMsg = "Unable to read target AGENTS.md: " + e.getMessage();
                    logger.error(errorMsg);
                    return new MigrationResult(false, false, false, errorMsg);
                }
            }

            // Perform migration: write target, then delete legacy
            try {
                Files.writeString(agentsMdPath, legacyContent);
                logger.debug("Created AGENTS.md with content from style.md");
            } catch (IOException e) {
                String errorMsg = "Failed to write AGENTS.md: " + e.getMessage();
                logger.error(errorMsg);
                return new MigrationResult(false, false, false, errorMsg);
            }

            // Delete legacy file
            try {
                Files.delete(legacyStylePath);
                logger.debug("Deleted legacy style.md");
            } catch (IOException e) {
                String errorMsg = "Failed to delete legacy style.md (AGENTS.md was created): " + e.getMessage();
                logger.error(errorMsg);
                // Still consider migration successful since target was written
                return new MigrationResult(true, true, false, errorMsg);
            }

            // Stage to git if repo is provided
            boolean stagedToGit = false;
            if (gitRepo != null) {
                try {
                    // Stage the deletion of legacy file (git tracks it)
                    gitRepo.add(legacyStylePath);
                    // Stage the new target file
                    gitRepo.add(agentsMdPath);
                    stagedToGit = true;
                    logger.info("Staged style.md -> AGENTS.md migration to git");
                } catch (Exception gitEx) {
                    String gitError = "Failed to stage migration to git: " + gitEx.getMessage();
                    logger.warn(gitError);
                    // Migration itself succeeded, just git staging failed
                    return new MigrationResult(true, true, false, gitError);
                }
            }

            logger.info("Successfully migrated style.md to AGENTS.md");
            return new MigrationResult(true, true, stagedToGit, null);

        } catch (Exception e) {
            String errorMsg = "Unexpected error during migration: " + e.getMessage();
            logger.error(errorMsg, e);
            return new MigrationResult(false, false, false, errorMsg);
        }
    }
}
