package ai.brokk.init.onboarding;

import ai.brokk.git.GitRepo;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
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

    /** Result of migration operation. */
    public record MigrationResult(boolean performed, String message) {}

    /**
     * Migrates legacy .brokk/style.md to AGENTS.md at project root if appropriate.
     * Migration is performed only if legacy file exists with content and target doesn't exist or is blank.
     * The migration reads content from .brokk/style.md, writes to AGENTS.md, deletes .brokk/style.md,
     * and optionally stages both files to git using move or add/remove operations.
     * This operation is idempotent - calling it multiple times will not cause issues.
     */
    public static MigrationResult migrate(
            ai.brokk.analyzer.ProjectFile legacyStyle,
            ai.brokk.analyzer.ProjectFile agentsFile,
            @Nullable GitRepo gitRepo) {
        // Check if legacy file exists
        if (!Files.exists(legacyStyle.absPath())) {
            return new MigrationResult(false, "Legacy style.md does not exist");
        }

        // Read legacy content
        String legacyContent;
        try {
            legacyContent = Files.readString(legacyStyle.absPath());
        } catch (IOException ex) {
            logger.warn("Cannot read legacy style.md: {}", ex.getMessage());
            return new MigrationResult(false, "Cannot read legacy style.md: " + ex.getMessage());
        }

        // Skip if legacy content is blank
        if (legacyContent.isBlank()) {
            return new MigrationResult(false, "Legacy style.md is empty");
        }

        // Check target file status
        boolean targetMissing = !Files.exists(agentsFile.absPath());
        boolean targetEmpty = false;

        if (!targetMissing) {
            try {
                targetEmpty = Files.readString(agentsFile.absPath()).isBlank();
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
            // Write content to new location first
            Files.writeString(agentsFile.absPath(), legacyContent);
            logger.debug("Wrote legacy content to AGENTS.md");

            // If git repo provided, stage the rename operation
            if (gitRepo != null) {
                // Try using GitRepo.move for proper rename staging
                try {
                    gitRepo.move(
                            legacyStyle.getRelPath().toString(),
                            agentsFile.getRelPath().toString());
                    logger.debug(
                            "Staged rename using GitRepo.move: {} -> {}",
                            legacyStyle.getRelPath(),
                            agentsFile.getRelPath());
                    // GitRepo.move already deleted the source file, no need to delete again
                } catch (Exception moveEx) {
                    logger.debug("GitRepo.move failed ({}), falling back to add/remove", moveEx.getMessage());

                    // Fallback: explicitly stage add and remove
                    try {
                        gitRepo.add(List.of(agentsFile));
                        logger.debug("Staged addition of AGENTS.md at {}", agentsFile.getRelPath());
                    } catch (Exception addEx) {
                        logger.warn(
                                "Failed to stage AGENTS.md addition at {}: {}",
                                agentsFile.getRelPath(),
                                addEx.getMessage());
                        throw addEx;
                    }

                    try {
                        gitRepo.remove(legacyStyle);
                        logger.debug("Staged removal of .brokk/style.md at {}", legacyStyle.getRelPath());
                    } catch (Exception removeEx) {
                        logger.warn(
                                "Failed to stage .brokk/style.md removal at {}: {}",
                                legacyStyle.getRelPath(),
                                removeEx.getMessage());
                        throw removeEx;
                    }

                    // Delete legacy file from filesystem (only in fallback path)
                    Files.delete(legacyStyle.absPath());
                    logger.debug("Deleted legacy .brokk/style.md from filesystem");
                }
            } else {
                // No git repo, just delete the file
                Files.delete(legacyStyle.absPath());
                logger.debug("Deleted legacy .brokk/style.md from filesystem (no git)");
            }

            logger.info("Successfully migrated style guide from .brokk/style.md to AGENTS.md");
            return new MigrationResult(true, "Migrated style.md to AGENTS.md");

        } catch (Exception ex) {
            logger.error("Migration failed: {}", ex.getMessage(), ex);
            return new MigrationResult(false, "Migration failed: " + ex.getMessage());
        }
    }
}
