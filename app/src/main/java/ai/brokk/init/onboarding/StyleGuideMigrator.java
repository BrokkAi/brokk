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
 * <p>
 * Migration copies content from .brokk/style.md to AGENTS.md but does NOT delete
 * the legacy file. The legacy file will be ignored by .gitignore (.brokk/**) and
 * AGENTS.md takes precedence when loading the style guide.
 */
public class StyleGuideMigrator {
    private static final Logger logger = LogManager.getLogger(StyleGuideMigrator.class);

    /** Result of migration operation. */
    public record MigrationResult(boolean performed, String message) {}

    /**
     * Migrates legacy .brokk/style.md to AGENTS.md at project root if appropriate.
     * Migration is performed only if legacy file exists with content and target doesn't exist or is blank.
     * <p>
     * The migration copies content to AGENTS.md but leaves .brokk/style.md in place.
     * The legacy file will be ignored by .gitignore and AGENTS.md takes precedence.
     * This avoids git staging issues when .gitignore is updated in the same session.
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

            // Stage AGENTS.md if git repo is available
            // Note: We don't delete/unstage .brokk/style.md - it will be ignored by .gitignore
            if (gitRepo != null) {
                try {
                    gitRepo.add(List.of(agentsFile));
                    logger.debug("Staged AGENTS.md at {}", agentsFile.getRelPath());
                } catch (Exception addEx) {
                    logger.warn("Failed to stage AGENTS.md: {}", addEx.getMessage());
                    // Continue anyway - file is on disk
                }
            }

            logger.info("Migrated style guide from .brokk/style.md to AGENTS.md (legacy file kept, will be ignored)");
            return new MigrationResult(true, "Migrated style.md to AGENTS.md");

        } catch (Exception ex) {
            logger.error("Migration failed: {}", ex.getMessage(), ex);
            return new MigrationResult(false, "Migration failed: " + ex.getMessage());
        }
    }
}
