package ai.brokk.util;

import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.project.ModelProperties.ModelType;
import com.google.common.base.Splitter;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility for preprocessing long build outputs to extract only the most relevant errors before sending to LLM agents
 * for analysis and fixes. This helps reduce context window usage while ensuring actionable error information is
 * preserved.
 *
 * <p>Provides both lightweight path sanitization and full LLM-based error extraction with timeout protection. For LLM
 * context optimization only - use raw output for success/failure decisions.
 */
public class BuildOutputPreprocessor {
    private static final Logger logger = LogManager.getLogger(BuildOutputPreprocessor.class);

    /**
     * Minimum number of lines in build output to trigger preprocessing. Below this threshold, the original output is
     * returned unchanged.
     */
    public static final int THRESHOLD_LINES = 500;

    /**
     * Maximum number of errors to extract from the build output. This limits context size while ensuring we capture
     * multiple related issues.
     */
    public static final int MAX_EXTRACTED_ERRORS = 5;

    /**
     * Lightweight path sanitization without LLM processing. Converts absolute paths to relative paths for cleaner
     * output.
     *
     * @param rawBuildOutput The build output to sanitize (use empty string if no output)
     * @param contextManager The context manager to access project root
     * @return Sanitized output with relative paths, or original output if sanitization fails
     */
    public static String sanitizeOnly(String rawBuildOutput, IContextManager contextManager) {
        try {
            return sanitizeBuildOutput(rawBuildOutput, contextManager);
        } catch (Exception e) {
            logger.warn("Exception during build output sanitization: {}. Using original output.", e.getMessage(), e);
            return rawBuildOutput;
        }
    }

    /**
     * Full pipeline: sanitization + LLM-based error extraction for verbose output.
     *
     * @param rawBuildOutput The build output to process (use empty string if no output)
     * @param contextManager The context manager to access project root and LLM
     * @return Processed output with extracted errors, or original output if processing fails
     */
    public static String processForLlm(String rawBuildOutput, IContextManager contextManager)
            throws InterruptedException {
        logger.debug(
                "Processing build output through standard pipeline. Original length: {} chars",
                rawBuildOutput.length());

        // Step 1: Sanitize build output (cosmetic cleanup)
        String sanitized = sanitizeBuildOutput(rawBuildOutput, contextManager);
        logger.debug("After sanitization: {} chars", sanitized.length());

        // Step 2: Preprocess for context optimization
        String processed = maybePreprocessOutput(sanitized, contextManager);
        logger.debug("After preprocessing: {} chars", processed.length());

        return processed;
    }

    /**
     * Preprocesses build output by extracting the most relevant errors when the output is longer than the threshold.
     * Uses the quickest model for fast error extraction, relying on the LLM service's built-in timeout protection.
     *
     * @param buildOutput The raw build output from compilation/test commands (empty string if no output)
     * @param cm The context manager to access the quickest model via getLlm
     * @return Preprocessed output containing only relevant errors, or original output if preprocessing is not needed or
     *     fails. Never returns null - empty input returns empty string.
     */
    public static String maybePreprocessOutput(String buildOutput, IContextManager cm) throws InterruptedException {
        List<String> lines = Splitter.on('\n').splitToList(buildOutput);
        logger.debug("Build output has {} lines, preprocessing threshold is {}", lines.size(), THRESHOLD_LINES);
        if (lines.size() <= THRESHOLD_LINES) {
            return buildOutput;
        }

        // Limit build output to fit within token constraints
        var model = cm.getService().getModel(ModelType.BUILD_PROCESSOR);
        var llm = cm.getLlm(model, "BuildOutputPreprocessor");
        String truncatedOutput = truncateToTokenLimit(buildOutput, model, cm);
        return preprocessOutput(truncatedOutput, cm, llm);
    }

    private static String preprocessOutput(String truncatedOutput, IContextManager contextManager, Llm llm)
            throws InterruptedException {

        var systemMessage = new SystemMessage(
                """
            You are familiar with common build and lint tools and you extract the most relevant compilation
            and build errors from verbose output.

            Return the extracted errors in a clean, readable format.
            """);

        var userMessage = new UserMessage(
                """
            Please extract the most relevant compilation/build errors from this build output.

            Focus on the %d most important, actionable errors that developers need to fix:
            1. Compilation errors (syntax errors, type errors, missing imports)
            2. Test failures with specific failure reasons
            3. Dependency resolution failures
            4. Build configuration errors

            For each error, include:
            - Specific error message
            - File path and line number when available
            - Full stack trace when available
            - Relevant debug output: you may TRIM the output to the most relevant portions, but you
              MUST NOT CHANGE the parts you include

            WARNINGS AND ERRORS IN THE SAME FILE:
            Include ALL errors AND warnings from the same file. Warnings often indicate
            symptoms of an error elsewhere in the file, and developers need to see both
            to understand the full picture.

            ERROR HANDLING RULES:
            - Include each error message verbatim
            - Include ALL warnings from files that also have errors
            - Only skip IDENTICAL duplicate messages (same file, line, and message)
            - IGNORE verbose progress messages, successful compilation output,
              general startup/shutdown logs

            Return the extracted errors in a clean, readable format.

            EXAMPLE showing trimming of framework (junit/jupiter) boilerplate and irrelevant log output, while
            preserving the most relevant original lines exactly:
            [ORIGINAL]
            ```
            ai.brokk.analyzer.TypeInferenceTest.gtd_fieldDeclaration_returnsFieldRange()
               GTD on field declaration should find the field ==> expected: <true> but was: <false>
                  at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:158)
                  at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:139)
                  at app//org.junit.jupiter.api.AssertTrue.failNotTrue(AssertTrue.java:69)
                  at app//org.junit.jupiter.api.AssertTrue.assertTrue(AssertTrue.java:41)
                  at app//org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:228)
                  at app//ai.brokk.analyzer.TypeInferenceTest.gtd_fieldDeclaration_returnsFieldRange(TypeInferenceTest.java:960)
            23:23:15.128 [Test worker] INFO  Environment - Adaptive IO cap from FD limits: maxFD=1048576, openFD=228, freeFD=1048348, cap=16
            23:23:15.131 [Test worker] DEBUG TreeSitterAnalyzer - Initializing TreeSitterAnalyzer for language: Java, query resource: treesitter/java.scm
            23:23:15.191 [Test worker] INFO  TreeSitterAnalyzer - File processing summary: 1 files processed successfully
            23:23:15.192 [Test worker] DEBUG TreeSitterAnalyzer - [Java] Stage timing (wall clock coverage; stages overlap): Read Files=0s 1ms, Parse Files=0s 0ms, Process Files=0s 29ms, Merge Results=0s 0ms, Total=0s 47ms
            23:23:15.193 [Test worker] DEBUG TreeSitterAnalyzer - [Java] Snapshot TreeSitterAnalyzer created - codeUnits: 5, files: 1
            23:23:15.195 [Test worker] DEBUG TreeSitterAnalyzer - [Java] Snapshot TreeSitterAnalyzer created - codeUnits: 5, files: 1
            23:23:15.197 [Test worker] DEBUG TreeSitterAnalyzer - [Java] TreeSitter analysis complete - codeUnits: 5, files: 1
            23:23:15.198 [Test worker] DEBUG TreeSitterAnalyzer - getDefinitionLocation: file=Test.java, offset=53
            ```
            [EXTRACTED]
            ```
            ai.brokk.analyzer.TypeInferenceTest.gtd_fieldDeclaration_returnsFieldRange()
               GTD on field declaration should find the field ==> expected: <true> but was: <false>
                  at app//ai.brokk.analyzer.TypeInferenceTest.gtd_fieldDeclaration_returnsFieldRange(TypeInferenceTest.java:960)
            23:23:15.197 [Test worker] DEBUG TreeSitterAnalyzer - [Java] TreeSitter analysis complete - codeUnits: 5, files: 1
            23:23:15.198 [Test worker] DEBUG TreeSitterAnalyzer - getDefinitionLocation: file=Test.java, offset=53
            ```
            """
                        .formatted(MAX_EXTRACTED_ERRORS));
        var messages = List.of(systemMessage, userMessage);

        var result = llm.sendRequest(messages);
        if (result.error() != null || result.isPartial() || result.text().isBlank()) {
            logPreprocessingError(result, contextManager);
            return truncatedOutput;
        }

        return result.text().trim();
    }

    /**
     * Truncates build output to fit within conservative token limits by repeatedly halving the line count. Uses a
     * conservative estimate since our tokenizer is approximate and we want to avoid exceeding limits.
     *
     * @param buildOutput The original build output
     * @param model
     * @return Truncated output that should fit within token constraints
     */
    @SuppressWarnings("UnusedVariable")
    private static String truncateToTokenLimit(String buildOutput, StreamingChatModel model, IContextManager cm) {
        int targetTokens = cm.getService().getMaxInputTokens(model) / 2;
        logger.debug("Using conservative target of {} tokens for build output", targetTokens);

        List<String> lines = Splitter.on('\n').splitToList(buildOutput);
        int currentLineCount = lines.size();

        // Repeatedly halve line count until we're under target
        while (lines.size() > 1) {
            var approximateTokens = Messages.getApproximateTokens(buildOutput);
            if (approximateTokens <= targetTokens) {
                logger.debug(
                        "Build output estimated at {} tokens, under target of {}", approximateTokens, targetTokens);
                return buildOutput;
            }

            currentLineCount = currentLineCount / 2;
            var truncatedLines = lines.subList(0, currentLineCount);
            buildOutput = String.join("\n", truncatedLines);
        }

        return buildOutput;
    }

    private static void logPreprocessingError(Llm.StreamingResult result, IContextManager contextManager) {
        if (result.isPartial()) {
            logger.warn("Build output preprocessing incomplete; using original output");
            return;
        }

        var error = result.error();
        if (error != null) {
            boolean isTimeout = error.getMessage() != null && error.getMessage().contains("timed out");
            if (isTimeout) {
                logger.warn(
                        "Build output preprocessing timed out (quickest model: {}). Using original output.",
                        contextManager.getService().quickestModel().getClass().getSimpleName());
            } else {
                logger.warn("Error during build output preprocessing: {}. Using original output.", error.getMessage());
            }
        }

        if (result.text().isBlank()) {
            logger.warn("Build output preprocessing empty string; using original output.");
        }
    }

    /**
     * Converts absolute paths to relative paths for LLM consumption. Handles Windows/Unix paths and prevents accidental
     * partial matches.
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
