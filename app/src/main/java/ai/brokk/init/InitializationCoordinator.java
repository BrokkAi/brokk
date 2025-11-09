package ai.brokk.init;

import ai.brokk.IProject;
import ai.brokk.agents.BuildAgent.BuildDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Coordinates initialization phases and determines required dialogs.
 * <p>
 * This class has NO UI dependencies and can be tested without Swing.
 * It orchestrates:
 * - CompletableFuture coordination (style guide, build details)
 * - File visibility guarantees
 * - Determination of which dialogs are needed
 * - State tracking through initialization phases
 */
public class InitializationCoordinator {
    private static final Logger logger = LoggerFactory.getLogger(InitializationCoordinator.class);
    private static final Duration DEFAULT_FILE_VISIBILITY_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Initialization phases - tracks progress through initialization.
     */
    public enum Phase {
        CREATING_PROJECT,      // Project object being created
        GENERATING_STYLE_GUIDE, // Background style guide generation in progress
        LOADING_BUILD_DETAILS, // Background build details inference in progress
        FILES_READY,           // All files written and visible
        COMPLETE               // All initialization finished
    }

    /**
     * Result of initialization coordination - tells UI what dialogs to show.
     * This is a pure data structure with no UI logic.
     */
    public record InitializationResult(
        boolean needsMigrationDialog,     // true if .brokk/style.md exists but no AGENTS.md
        boolean needsBuildSettingsDialog, // true if project not fully configured
        boolean needsGitConfigDialog,     // true if .gitignore not properly set
        Phase finalPhase                  // Final phase reached
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
        CompletableFuture<BuildDetails> buildDetailsFuture
    ) {
        logger.debug("Starting initialization coordination");
        currentPhase = Phase.GENERATING_STYLE_GUIDE;

        // Wait for style guide to complete
        return styleGuideFuture
            .thenCompose(v -> {
                logger.debug("Style guide future completed, ensuring file visibility");
                currentPhase = Phase.LOADING_BUILD_DETAILS;

                // Ensure style guide file is visible to all threads
                try {
                    var styleGuidePath = project.getRoot().resolve("AGENTS.md");
                    if (Files.exists(styleGuidePath)) {
                        ensureFileVisible(styleGuidePath, DEFAULT_FILE_VISIBILITY_TIMEOUT);
                        logger.debug("Style guide file visibility confirmed");
                    } else {
                        logger.debug("No AGENTS.md found (may be using legacy .brokk/style.md)");
                    }
                } catch (IOException e) {
                    logger.warn("Error ensuring style guide file visibility", e);
                }

                // Now wait for build details
                return buildDetailsFuture;
            })
            .thenApply(buildDetails -> {
                logger.debug("Build details future completed, determining required dialogs");
                currentPhase = Phase.FILES_READY;

                // Ensure build details file is visible
                try {
                    var propsPath = project.getRoot().resolve(".brokk/project.properties");
                    if (Files.exists(propsPath)) {
                        ensureFileVisible(propsPath, DEFAULT_FILE_VISIBILITY_TIMEOUT);
                        logger.debug("Build details file visibility confirmed");
                    }
                } catch (IOException e) {
                    logger.warn("Error ensuring build details file visibility", e);
                }

                // Determine which dialogs are needed
                var result = determineRequiredDialogs(project);
                currentPhase = Phase.COMPLETE;

                logger.info("Initialization coordination complete. Migration dialog: {}, Build settings: {}, Git config: {}",
                    result.needsMigrationDialog, result.needsBuildSettingsDialog, result.needsGitConfigDialog);

                return result;
            })
            .exceptionally(ex -> {
                logger.error("Error during initialization coordination", ex);
                // Return a result that shows no dialogs on error (fail-safe)
                return new InitializationResult(false, false, false, currentPhase);
            });
    }

    /**
     * Ensures a file is visible to all threads by retrying with timeout.
     * This addresses the race condition where a file is written in one thread
     * but not immediately visible to another thread due to OS-level caching.
     *
     * @param path Path to file to check
     * @param timeout Maximum time to wait for file to become visible
     * @throws IOException if file is not visible after timeout
     */
    private void ensureFileVisible(Path path, Duration timeout) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("File does not exist: " + path);
        }

        var startTime = System.nanoTime();
        var timeoutNanos = timeout.toNanos();
        var retryCount = 0;

        while (true) {
            try {
                // Try to read file size - this forces a filesystem access
                // and acts as a memory barrier
                var size = Files.size(path);

                // Also verify file is readable and not empty (for style guide)
                if (path.getFileName().toString().equals("AGENTS.md")) {
                    if (size == 0) {
                        logger.warn("AGENTS.md exists but is empty (size=0), retrying...");
                        throw new IOException("File is empty");
                    }
                }

                // File is visible and readable
                logger.debug("File visibility confirmed: {} (size={}, retries={})",
                    path.getFileName(), size, retryCount);
                return;

            } catch (IOException e) {
                // Check timeout
                var elapsed = System.nanoTime() - startTime;
                if (elapsed > timeoutNanos) {
                    logger.error("File visibility timeout after {}ms: {}",
                        Duration.ofNanos(elapsed).toMillis(), path);
                    throw new IOException("File not visible after " + timeout, e);
                }

                // Wait and retry
                retryCount++;
                try {
                    Thread.sleep(10); // 10ms between retries
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for file visibility", ie);
                }
            }
        }
    }

    /**
     * Determines which dialogs need to be shown based on project state.
     * This is pure logic with no UI dependencies.
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
            var root = project.getRoot();
            var agentsMd = root.resolve("AGENTS.md");
            var legacyStyleMd = root.resolve(".brokk/style.md");
            var gitignorePath = root.resolve(".gitignore");

            // Check 1: Migration needed?
            // If .brokk/style.md exists with content AND AGENTS.md doesn't exist
            if (Files.exists(legacyStyleMd) && !Files.exists(agentsMd)) {
                try {
                    var content = Files.readString(legacyStyleMd).trim();
                    if (!content.isEmpty()) {
                        needsMigration = true;
                        logger.debug("Migration needed: .brokk/style.md exists with content, AGENTS.md missing");
                    }
                } catch (IOException e) {
                    logger.warn("Error reading legacy style.md", e);
                }
            }

            // Check 2: Build settings dialog needed?
            // Show if project is not fully configured
            boolean hasProperties = Files.exists(root.resolve(".brokk/project.properties"));
            boolean hasStyleGuide = Files.exists(agentsMd) || Files.exists(legacyStyleMd);
            boolean gitConfigured = isBrokkIgnored(gitignorePath);

            boolean fullyConfigured = hasProperties && hasStyleGuide && gitConfigured;
            needsBuildSettings = !fullyConfigured;

            logger.debug("Configuration check: properties={}, styleGuide={}, gitConfigured={}, fullyConfigured={}",
                hasProperties, hasStyleGuide, gitConfigured, fullyConfigured);

            // Check 3: Git config dialog needed?
            // Show if .gitignore doesn't have comprehensive .brokk patterns
            needsGitConfig = !gitConfigured;

            logger.debug("Dialog determination: migration={}, buildSettings={}, gitConfig={}",
                needsMigration, needsBuildSettings, needsGitConfig);

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
     *
     * @param gitignorePath Path to .gitignore file
     * @return true if .brokk is comprehensively ignored
     */
    private boolean isBrokkIgnored(Path gitignorePath) throws IOException {
        if (!Files.exists(gitignorePath)) {
            logger.debug(".gitignore does not exist");
            return false;
        }

        var content = Files.readString(gitignorePath);

        // Check each line for comprehensive .brokk ignore patterns
        // Don't match partial patterns like .brokk/workspace.properties
        for (var line : content.split("\n")) {
            var trimmed = line.trim();

            // Remove trailing comments
            var commentIndex = trimmed.indexOf('#');
            if (commentIndex > 0) {
                trimmed = trimmed.substring(0, commentIndex).trim();
            }

            // Match .brokk/** (comprehensive) or .brokk/ (directory)
            if (trimmed.equals(".brokk/**") || trimmed.equals(".brokk/")) {
                logger.debug("Found comprehensive .brokk ignore pattern: {}", trimmed);
                return true;
            }
        }

        logger.debug(".gitignore exists but lacks comprehensive .brokk ignore pattern");
        return false;
    }

    /**
     * Gets the current initialization phase.
     *
     * @return current phase
     */
    public Phase getCurrentPhase() {
        return currentPhase;
    }
}
