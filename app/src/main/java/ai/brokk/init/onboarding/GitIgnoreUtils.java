package ai.brokk.init.onboarding;

import com.google.common.base.Splitter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility class for Git ignore operations.
 * <p>
 * This class provides shared functionality for checking .gitignore patterns,
 * used by OnboardingOrchestrator and MainProject.
 */
public class GitIgnoreUtils {
    private static final Logger logger = LogManager.getLogger(GitIgnoreUtils.class);

    // Utility class - prevent instantiation
    private GitIgnoreUtils() {}

    /**
     * Checks if .brokk directory is properly ignored in .gitignore.
     * Requires exact match of .brokk/** or .brokk/ patterns, with optional leading / or ./
     *
     * @param gitignorePath Path to the .gitignore file
     * @return true if .brokk is comprehensively ignored
     */
    public static boolean isBrokkIgnored(Path gitignorePath) throws IOException {
        if (!Files.exists(gitignorePath)) {
            logger.debug(".gitignore does not exist");
            return false;
        }

        var content = Files.readString(gitignorePath);

        // Check each line for comprehensive .brokk ignore patterns
        // Don't match partial patterns like .brokk/workspace.properties
        for (var line : Splitter.on('\n').split(content)) {
            var trimmed = line.trim();

            // Remove trailing comments
            int commentIndex = trimmed.indexOf('#');
            if (commentIndex >= 0) {
                trimmed = trimmed.substring(0, commentIndex).trim();
            }

            // Strip optional leading ./ or / prefix
            if (trimmed.startsWith("./")) {
                trimmed = trimmed.substring(2);
            } else if (trimmed.startsWith("/")) {
                trimmed = trimmed.substring(1);
            }

            // Match .brokk/** (comprehensive) or .brokk/ (directory)
            if (trimmed.equals(".brokk/**") || trimmed.equals(".brokk/")) {
                logger.debug("Found comprehensive .brokk ignore pattern: {}", line.trim());
                return true;
            }
        }

        logger.debug(".gitignore exists but lacks comprehensive .brokk ignore pattern");
        return false;
    }

    /**
     * Checks if .brokk directory is properly ignored in .gitignore.
     * Requires exact match of .brokk/** or .brokk/ patterns, with optional leading / or ./
     *
     * @param gitignoreFile ProjectFile representing the .gitignore file
     * @return true if .brokk is comprehensively ignored
     */
    public static boolean isBrokkIgnored(ai.brokk.analyzer.ProjectFile gitignoreFile) throws IOException {
        return isBrokkIgnored(gitignoreFile.absPath());
    }
}
