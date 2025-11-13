package ai.brokk.init.onboarding;

import ai.brokk.IProject;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

/**
 * Captures the current state of a project during onboarding.
 * <p>
 * This is a pure data structure with no business logic.
 * Used by OnboardingStep implementations to determine if they are applicable
 * and what actions they should take.
 * <p>
 * State includes:
 * - File presence (AGENTS.md, legacy style.md, project.properties)
 * - File content status (empty vs has content)
 * - Git configuration status (.gitignore setup)
 * - Build details availability
 * - Style guide generation status (including whether it was skipped due to no Git)
 */
public record ProjectState(
        // Project reference
        IProject project,
        Path configRoot,

        // Style guide state
        boolean agentsMdExists,
        boolean agentsMdHasContent,
        boolean legacyStyleMdExists,
        boolean legacyStyleMdHasContent,
        boolean styleGenerationSkippedDueToNoGit, // NEW: flag for post-git regeneration

        // Build details state
        boolean projectPropertiesExists,
        boolean projectPropertiesHasContent,
        boolean buildDetailsAvailable, // true if BuildDetails future completed successfully

        // Git state
        boolean gitignoreExists,
        boolean gitignoreConfigured, // true if .brokk/** or .brokk/ pattern present

        // Async handles (for steps that need to wait on background operations)
        @Nullable CompletableFuture<String> styleGuideFuture, // Future<String> with style content
        @Nullable CompletableFuture<?> buildDetailsFuture // Future<?> for build details
        ) {

    /**
     * Checks if project is fully configured.
     * A project is considered configured if it has:
     * - A style guide (AGENTS.md or legacy style.md with content)
     * - Project properties file
     * - Properly configured .gitignore
     */
    public boolean isFullyConfigured() {
        boolean hasStyleGuide =
                (agentsMdExists && agentsMdHasContent) || (legacyStyleMdExists && legacyStyleMdHasContent);
        boolean hasProperties = projectPropertiesExists && projectPropertiesHasContent;
        boolean gitConfigured = gitignoreConfigured;

        return hasStyleGuide && hasProperties && gitConfigured;
    }

    /**
     * Checks if migration from legacy style.md to AGENTS.md is needed.
     * Migration is needed if:
     * - Legacy style.md exists with content
     * - AGENTS.md doesn't exist or is empty
     */
    public boolean needsMigration() {
        return legacyStyleMdExists && legacyStyleMdHasContent && (!agentsMdExists || !agentsMdHasContent);
    }

    /**
     * Checks if build settings dialog should be shown.
     * Show if project is not fully configured.
     */
    public boolean needsBuildSettings() {
        return !isFullyConfigured();
    }

    /**
     * Checks if git configuration is needed (.gitignore needs Brokk patterns).
     * Only returns true if the project has Git initialized and .gitignore lacks Brokk patterns.
     */
    public boolean needsGitConfig() {
        return project.hasGit() && !gitignoreConfigured;
    }

    /**
     * Checks if style guide regeneration should be offered after Git setup.
     * This is true when style generation was previously skipped due to missing Git
     * and Git repository is now present.
     */
    public boolean needsPostGitStyleRegeneration() {
        return styleGenerationSkippedDueToNoGit && project.hasGit();
    }
}
