package ai.brokk.init.onboarding;

import ai.brokk.git.GitRepo;
import java.io.IOException;
import java.nio.file.Files;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Handles migration of legacy .brokk/style.md to AGENTS.md at project root.
 * This is a standalone utility that can be called by orchestrators before
 * setting up git ignore configuration.
 * <p>
 * Migration copies content from .brokk/style.md to AGENTS.md and then deletes
 * the legacy file.
 */
public class StyleGuideMigrator {
    private static final Logger logger = LogManager.getLogger(StyleGuideMigrator.class);

    /** Result of migration operation. */
    public record MigrationResult(boolean performed, String message) {}

    /**
     * Migrates legacy .brokk/style.md to AGENTS.md at project root if appropriate.
     * Migration is performed only if legacy file exists with content and target doesn't exist or is blank.
     * <p>
     * The migration copies content to AGENTS.md and then deletes the legacy file.
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

        // Perform migration: copy content to AGENTS.md (leave legacy file in place)
        try {
            Files.writeString(agentsFile.absPath(), legacyContent);
            logger.debug("Copied legacy content to AGENTS.md");

            // Use git move for proper rename semantics (aligns with master behavior)
            if (gitRepo != null) {
                try {
                    gitRepo.move(
                            legacyStyle.getRelPath().toString(),
                            agentsFile.getRelPath().toString());
                    logger.info("Staged style.md -> AGENTS.md rename in Git");
                } catch (Exception gitEx) {
                    logger.warn("Git rename failed, deleting manually: {}", gitEx.getMessage());
                    try {
                        Files.deleteIfExists(legacyStyle.absPath());
                    } catch (IOException deleteEx) {
                        logger.debug("Failed to delete legacy file: {}", deleteEx.getMessage());
                    }
                }
            } else {
                // No git - just delete legacy file
                try {
                    Files.deleteIfExists(legacyStyle.absPath());
                } catch (IOException deleteEx) {
                    logger.debug("Failed to delete legacy file: {}", deleteEx.getMessage());
                }
            }

            logger.info("Migrated style guide from .brokk/style.md to AGENTS.md");
            return new MigrationResult(true, "Migrated style.md to AGENTS.md");

        } catch (Exception ex) {
            logger.error("Migration failed: {}", ex.getMessage(), ex);
            return new MigrationResult(false, "Migration failed: " + ex.getMessage());
        }
    }
}
