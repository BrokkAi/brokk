package io.github.jbellis.brokk.util;

import io.github.jbellis.brokk.IContextManager;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Standardized pipeline for processing build output before sending to LLM agents.
 * Two-step process: sanitization (path cleanup) then preprocessing (error extraction).
 * For LLM context optimization only - use raw output for success/failure decisions.
 */
public class BuildOutputPipeline {
    private static final Logger logger = LogManager.getLogger(BuildOutputPipeline.class);

    /**
     * Lightweight path sanitization without LLM processing.
     */
    public static String sanitizeOnly(@Nullable String rawBuildOutput, IContextManager contextManager) {
        if (rawBuildOutput == null) {
            return "";
        }

        try {
            return sanitizeBuildOutput(rawBuildOutput, contextManager);
        } catch (Exception e) {
            logger.warn("Exception during build output sanitization: {}. Using original output.", e.getMessage(), e);
            return rawBuildOutput;
        }
    }

    /**
     * Full pipeline: sanitization + LLM-based error extraction for verbose output.
     */
    public static String processForLlm(@Nullable String rawBuildOutput, IContextManager contextManager) {
        if (rawBuildOutput == null) {
            return "";
        }

        logger.debug(
                "Processing build output through standard pipeline. Original length: {} chars",
                rawBuildOutput.length());

        try {
            // Step 1: Sanitize build output (cosmetic cleanup)
            String sanitized = sanitizeBuildOutput(rawBuildOutput, contextManager);
            logger.debug("After sanitization: {} chars", sanitized.length());

            // Step 2: Preprocess for context optimization
            String processed = BuildOutputPreprocessor.preprocessBuildOutput(sanitized, contextManager);
            logger.debug("After preprocessing: {} chars", processed.length());

            return processed;

        } catch (Exception e) {
            logger.warn(
                    "Exception during build output pipeline processing: {}. Using original output.", e.getMessage(), e);
            return rawBuildOutput;
        }
    }

    /**
     * Converts absolute paths to relative paths for LLM consumption.
     * Handles Windows/Unix paths and prevents accidental partial matches.
     */
    private static String sanitizeBuildOutput(String text, IContextManager contextManager) {
        var root = contextManager.getProject().getRoot().toAbsolutePath().normalize();
        var rootAbs = root.toString();

        // Build forward- and back-slash variants with a trailing separator
        var rootFwd = rootAbs.replace('\\', '/');
        if (!rootFwd.endsWith("/")) {
            rootFwd = rootFwd + "/";
        }
        var rootBwd = rootAbs.replace('/', '\\');
        if (!rootBwd.endsWith("\\")) {
            rootBwd = rootBwd + "\\";
        }

        // Case-insensitive replacement and boundary-checked:
        // - (?<![A-Za-z0-9._-]) ensures the match is not preceded by a typical path/token character.
        // - The trailing separator in rootFwd/rootBwd ensures we only match a directory prefix of a larger path.
        // - (?=\S) ensures there is at least one non-whitespace character following the prefix (i.e., a larger path).
        var sanitized = text;
        var forwardPattern = Pattern.compile("(?i)(?<![A-Za-z0-9._-])" + Pattern.quote(rootFwd) + "(?=\\S)");
        var backwardPattern = Pattern.compile("(?i)(?<![A-Za-z0-9._-])" + Pattern.quote(rootBwd) + "(?=\\S)");

        sanitized = forwardPattern.matcher(sanitized).replaceAll("");
        sanitized = backwardPattern.matcher(sanitized).replaceAll("");

        return sanitized;
    }
}
