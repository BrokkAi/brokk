package ai.brokk.init;

import ai.brokk.AbstractProject;
import ai.brokk.IProject;
import ai.brokk.agents.BuildAgent.BuildDetails;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates initialization phases and determines required dialogs.
 * <p>
 * This class has NO UI dependencies and can be tested without Swing.
 * It orchestrates:
 * - CompletableFuture coordination (style guide, build details)
 * - File visibility guarantees
 * - Determination of which dialogs are needed
 * - State tracking through initialization phases
 * <p>
 * <b>Architecture Note:</b> This class provides the legacy approach to initialization.
 * For new code, consider using {@link ai.brokk.init.onboarding.OnboardingOrchestrator}
 * which offers better extensibility, testability, and explicit step dependencies.
 * Both approaches can coexist; this class continues to be maintained for backward
 * compatibility with existing Chrome integration.
 * <p>
 * The static helper methods ({@link #hasAgentsMd}, {@link #hasLegacyStyleMd}, etc.)
 * are shared between both approaches and provide a single source of truth for
 * project state checks.
 */
public class InitializationCoordinator {
    private static final Logger logger = LoggerFactory.getLogger(InitializationCoordinator.class);

    /**
     * Initialization phases - tracks progress through initialization.
     */
    public enum Phase {
        CREATING_PROJECT, // Project object being created
        GENERATING_STYLE_GUIDE, // Background style guide generation in progress
        LOADING_BUILD_DETAILS, // Background build details inference in progress
        FILES_READY, // All files written and visible
        COMPLETE // All initialization finished
    }

    /**
     * Result of initialization coordination - tells UI what dialogs to show.
     * This is a pure data structure with no UI logic.
     */
    public record InitializationResult(
            boolean needsMigrationDialog, // true if .brokk/style.md exists but no AGENTS.md
            boolean needsBuildSettingsDialog, // true if project not fully configured
            boolean needsGitConfigDialog, // true if .gitignore not properly set
            Phase finalPhase // Final phase reached
            ) {}

    private volatile Phase currentPhase = Phase.CREATING_PROJECT;

    /**
     * Creates a new InitializationCoordinator.
     */
    public InitializationCoordinator() {
        logger.debug("InitializationCoordinator created");
    }

    /**
     * Coordinates initialization by:
     * 1. Waiting for both style guide and build details futures to complete
     * 2. Ensuring files are visible to all threads
     * 3. Determining which dialogs are needed
     *
     * @param project The project being initialized
     * @param styleGuideFuture Future that completes when style guide is written
     * @param buildDetailsFuture Future that completes when build details are inferred
     * @return Future containing initialization result (which dialogs to show)
     */
    public CompletableFuture<InitializationResult> coordinate(
            IProject project,
            CompletableFuture<Void> styleGuideFuture,
            CompletableFuture<BuildDetails> buildDetailsFuture) {
        logger.debug("Starting initialization coordination");
        currentPhase = Phase.GENERATING_STYLE_GUIDE;

        // Wait for style guide to complete
        return styleGuideFuture
                .thenCompose(v -> {
                    logger.debug("Style guide future completed, ensuring file visibility");
                    currentPhase = Phase.LOADING_BUILD_DETAILS;

                    // Ensure style guide file is visible to all threads
                    // IMPORTANT: Even if AGENTS.md didn't exist before, it may have been created
                    // by style guide generation. We MUST wait for it to be written and non-empty.
                    try {
                        var styleGuidePath =
                                project.getMasterRootPathForConfig().resolve(AbstractProject.STYLE_GUIDE_FILE);
                        // Always try to ensure file visibility, even if it didn't exist before
                        // This waits for the file to be created and filled with content
                        ensureFileVisible(styleGuidePath);
                        logger.debug("Style guide file visibility confirmed");
                    } catch (IOException e) {
                        // File doesn't exist or is empty - check for legacy location
                        try {
                            var legacyPath = project.getMasterRootPathForConfig()
                                    .resolve(AbstractProject.BROKK_DIR)
                                    .resolve(AbstractProject.LEGACY_STYLE_GUIDE_FILE);
                            if (Files.exists(legacyPath) && Files.size(legacyPath) > 0) {
                                logger.debug("Using legacy .brokk/style.md (AGENTS.md not found or empty)");
                            } else {
                                logger.warn("No style guide file found or all files are empty", e);
                            }
                        } catch (IOException legacyEx) {
                            logger.warn("Error checking for legacy style guide", legacyEx);
                        }
                    }

                    // Now wait for build details
                    return buildDetailsFuture;
                })
                .thenApply(buildDetails -> {
                    logger.debug("Build details future completed, determining required dialogs");
                    currentPhase = Phase.FILES_READY;

                    // Ensure build details file is visible
                    try {
                        var propsPath = project.getMasterRootPathForConfig()
                                .resolve(AbstractProject.BROKK_DIR)
                                .resolve(AbstractProject.PROJECT_PROPERTIES_FILE);
                        if (Files.exists(propsPath)) {
                            ensureFileVisible(propsPath);
                            logger.debug("Build details file visibility confirmed");
                        }
                    } catch (IOException e) {
                        logger.warn("Error ensuring build details file visibility", e);
                    }

                    // Determine which dialogs are needed
                    var result = determineRequiredDialogs(project);
                    currentPhase = Phase.COMPLETE;

                    logger.info(
                            "Initialization coordination complete. Migration dialog: {}, Build settings: {}, Git config: {}",
                            result.needsMigrationDialog,
                            result.needsBuildSettingsDialog,
                            result.needsGitConfigDialog);

                    return result;
                })
                .exceptionally(ex -> {
                    logger.error("Error during initialization coordination", ex);
                    // Return a result that shows no dialogs on error (fail-safe)
                    return new InitializationResult(false, false, false, currentPhase);
                });
    }

    /**
     * Verifies a file exists and is non-empty.
     * Since saveStyleGuide() uses AtomicWrites, when the CompletableFuture completes,
     * the file is guaranteed to be atomically written. No polling needed.
     *
     * @param path Path to file to check
     * @throws IOException if file doesn't exist or is empty
     */
    private void ensureFileVisible(Path path) throws IOException {
        // With AtomicWrites, file is either invisible or complete
        // When CompletableFuture completes, file write is done
        var size = Files.size(path);

        // Verify file is not empty (for style guide)
        if (path.getFileName().toString().equals("AGENTS.md")) {
            if (size == 0) {
                throw new IOException("AGENTS.md exists but is empty after style guide generation");
            }
        }

        // File is visible and readable
        logger.debug("File visibility confirmed: {} (size={})", path.getFileName(), size);
    }

    /**
     * Determines which dialogs need to be shown based on project state.
     * This is pure logic with no UI dependencies.
     * <p>
     * Note: This method provides the legacy dialog determination approach.
     * For new code, consider using OnboardingOrchestrator which offers
     * better extensibility and testability.
     *
     * @param project Project to check
     * @return InitializationResult indicating which dialogs are needed
     */
    private InitializationResult determineRequiredDialogs(IProject project) {
        logger.debug("Determining required dialogs for project: {}", project.getRoot());

        boolean needsMigration = false;
        boolean needsBuildSettings = false;
        boolean needsGitConfig = false;

        try {
            var configRoot = project.getMasterRootPathForConfig();
            var gitignorePath = configRoot.resolve(".gitignore");

            // Check 1: Migration needed?
            // Use state probes for consistency with OnboardingOrchestrator
            needsMigration = hasLegacyStyleMdWithContent(configRoot) && !hasAgentsMd(configRoot);
            if (needsMigration) {
                logger.debug("Migration needed: .brokk/style.md exists with content, AGENTS.md missing");
            }

            // Check 2: Build settings dialog needed?
            // Show if project is not fully configured
            boolean hasProperties = hasProjectProperties(configRoot);
            boolean hasStyleGuide = hasAgentsMd(configRoot) || hasLegacyStyleMd(configRoot);
            boolean gitConfigured = isBrokkIgnored(gitignorePath);

            boolean fullyConfigured = hasProperties && hasStyleGuide && gitConfigured;
            needsBuildSettings = !fullyConfigured;

            logger.debug(
                    "Configuration check: properties={}, styleGuide={}, gitConfigured={}, fullyConfigured={}",
                    hasProperties,
                    hasStyleGuide,
                    gitConfigured,
                    fullyConfigured);

            // Check 3: Git config dialog needed?
            // Show if .gitignore doesn't have comprehensive .brokk patterns
            needsGitConfig = !gitConfigured;

            logger.debug(
                    "Dialog determination: migration={}, buildSettings={}, gitConfig={}",
                    needsMigration,
                    needsBuildSettings,
                    needsGitConfig);

        } catch (Exception e) {
            logger.error("Error determining required dialogs", e);
            // On error, show all dialogs to be safe
            needsMigration = true;
            needsBuildSettings = true;
            needsGitConfig = true;
        }

        return new InitializationResult(needsMigration, needsBuildSettings, needsGitConfig, Phase.COMPLETE);
    }

    /**
     * Checks if .brokk directory is properly ignored in .gitignore.
     * Requires exact match of .brokk/** or .brokk/ patterns.
     * <p>
     * This method delegates to {@link GitIgnoreUtils#isBrokkIgnored(Path)} which
     * provides shared git ignore checking logic across the codebase.
     *
     * @param gitignorePath Path to .gitignore file
     * @return true if .brokk is comprehensively ignored
     * @throws IOException if there's an error reading the file
     */
    public static boolean isBrokkIgnored(Path gitignorePath) throws IOException {
        return GitIgnoreUtils.isBrokkIgnored(gitignorePath);
    }

    /**
     * Gets the current initialization phase.
     *
     * @return current phase
     */
    public Phase getCurrentPhase() {
        return currentPhase;
    }

    // ========== State Probe Helpers ==========
    // These helpers allow OnboardingOrchestrator to query project state
    // without duplicating the isBrokkIgnored logic

    /**
     * Checks if AGENTS.md file exists.
     *
     * @param configRoot project configuration root
     * @return true if AGENTS.md exists
     */
    public static boolean hasAgentsMd(Path configRoot) {
        return Files.exists(configRoot.resolve(AbstractProject.STYLE_GUIDE_FILE));
    }

    /**
     * Checks if AGENTS.md file exists with content.
     *
     * @param configRoot project configuration root
     * @return true if AGENTS.md exists and is not empty
     */
    public static boolean hasAgentsMdWithContent(Path configRoot) {
        try {
            var path = configRoot.resolve(AbstractProject.STYLE_GUIDE_FILE);
            return Files.exists(path) && Files.size(path) > 0;
        } catch (IOException e) {
            logger.warn("Error checking AGENTS.md content", e);
            return false;
        }
    }

    /**
     * Checks if legacy .brokk/style.md file exists.
     *
     * @param configRoot project configuration root
     * @return true if legacy style.md exists
     */
    public static boolean hasLegacyStyleMd(Path configRoot) {
        return Files.exists(configRoot.resolve(AbstractProject.BROKK_DIR)
                .resolve(AbstractProject.LEGACY_STYLE_GUIDE_FILE));
    }

    /**
     * Checks if legacy .brokk/style.md file exists with content.
     *
     * @param configRoot project configuration root
     * @return true if legacy style.md exists and has non-blank content
     */
    public static boolean hasLegacyStyleMdWithContent(Path configRoot) {
        try {
            var path = configRoot.resolve(AbstractProject.BROKK_DIR)
                    .resolve(AbstractProject.LEGACY_STYLE_GUIDE_FILE);
            if (!Files.exists(path)) {
                return false;
            }
            var content = Files.readString(path);
            return !content.isBlank();
        } catch (IOException e) {
            logger.warn("Error checking legacy style.md content", e);
            return false;
        }
    }

    /**
     * Checks if project.properties file exists.
     *
     * @param configRoot project configuration root
     * @return true if project.properties exists
     */
    public static boolean hasProjectProperties(Path configRoot) {
        return Files.exists(configRoot.resolve(AbstractProject.BROKK_DIR)
                .resolve(AbstractProject.PROJECT_PROPERTIES_FILE));
    }

    /**
     * Checks if project.properties file exists with content.
     *
     * @param configRoot project configuration root
     * @return true if project.properties exists and is not empty
     */
    public static boolean hasProjectPropertiesWithContent(Path configRoot) {
        try {
            var path = configRoot.resolve(AbstractProject.BROKK_DIR)
                    .resolve(AbstractProject.PROJECT_PROPERTIES_FILE);
            return Files.exists(path) && Files.size(path) > 0;
        } catch (IOException e) {
            logger.warn("Error checking project.properties content", e);
            return false;
        }
    }
}
