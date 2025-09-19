package io.github.jbellis.brokk.util;

import com.google.common.base.Splitter;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.Llm;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Utility for preprocessing long build outputs to extract only the most relevant errors before sending to LLM agents
 * for analysis and fixes. This helps reduce context window usage while ensuring actionable error information is
 * preserved.
 */
public class BuildOutputPreprocessor {
    private static final Logger logger = LogManager.getLogger(BuildOutputPreprocessor.class);

    /**
     * Minimum number of lines in build output to trigger preprocessing. Below this threshold, the original output is
     * returned unchanged.
     */
    public static final int THRESHOLD_LINES = 200;

    /**
     * Maximum number of errors to extract from the build output. This limits context size while ensuring we capture
     * multiple related issues.
     */
    public static final int MAX_EXTRACTED_ERRORS = 10;

    /**
     * Timeout in seconds for LLM calls during build output preprocessing. This ensures the non-critical preprocessing
     * optimization fails fast and doesn't block for the full model timeout duration (2-15 minutes).
     *
     * <p>Applied via {@link #applyCustomTimeout(Supplier, long)} to wrap the LLM request with a 30-second timeout. If
     * this timeout is exceeded, preprocessing is aborted and the original build output is returned.
     */
    public static final long PREPROCESSING_TIMEOUT_SECONDS = 30L;

    /**
     * Applies a custom timeout to an LLM operation to ensure build output preprocessing fails fast. This ensures
     * preprocessing doesn't block for the full model timeout duration (2-15 minutes).
     *
     * @param operation The LLM operation to execute with timeout
     * @param timeoutSeconds Timeout in seconds
     * @return LLM result, or error result if timeout occurs
     */
    private static Llm.StreamingResult applyCustomTimeout(Supplier<Llm.StreamingResult> operation, long timeoutSeconds)
            throws InterruptedException {

        try {
            return CompletableFuture.supplyAsync(operation)
                    .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .get();
        } catch (ExecutionException e) {
            return handleExecutionException(e, timeoutSeconds);
        }
    }

    private static Llm.StreamingResult handleExecutionException(ExecutionException e, long timeoutSeconds)
            throws InterruptedException {
        var cause = e.getCause();

        if (cause instanceof RuntimeException && cause.getCause() instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            throw (InterruptedException) cause.getCause();
        }

        if (cause instanceof TimeoutException) {
            return handleTimeout(timeoutSeconds);
        }

        return new Llm.StreamingResult(null, new RuntimeException("Build output preprocessing failed", cause));
    }

    private static Llm.StreamingResult handleTimeout(long timeoutSeconds) {
        logger.warn("Build output preprocessing timed out after {} seconds", timeoutSeconds);
        return new Llm.StreamingResult(
                null,
                new RuntimeException("Build output preprocessing timed out after " + timeoutSeconds + " seconds"));
    }

    /**
     * Preprocesses build output by extracting the most relevant errors when the output is longer than the threshold.
     * Uses the quickest model for fast error extraction with a 30-second timeout.
     *
     * @param buildOutput The raw build output from compilation/test commands
     * @param contextManager The context manager to access the quickest model via getLlm
     * @return Preprocessed output containing only relevant errors, or original output if preprocessing is not needed or
     *     fails. Never returns null - empty input returns empty string.
     */
    public static String preprocessBuildOutput(@Nullable String buildOutput, IContextManager contextManager) {
        if (buildOutput == null) {
            return "";
        }
        if (buildOutput.isBlank()) {
            return buildOutput;
        }

        if (!shouldPreprocess(buildOutput)) {
            return buildOutput;
        }

        try {
            return performPreprocessing(buildOutput, contextManager);
        } catch (Exception e) {
            logger.warn("Exception during build output preprocessing: {}. Using original output.", e.getMessage(), e);
            return buildOutput;
        }
    }

    private static boolean shouldPreprocess(String buildOutput) {
        List<String> lines = Splitter.on('\n').splitToList(buildOutput);
        if (lines.size() <= THRESHOLD_LINES) {
            logger.debug(
                    "Build output has {} lines, below threshold of {}. Skipping preprocessing.",
                    lines.size(),
                    THRESHOLD_LINES);
            return false;
        }

        logger.info(
                "Build output has {} lines, above threshold of {}. Extracting relevant errors.",
                lines.size(),
                THRESHOLD_LINES);
        return true;
    }

    private static String performPreprocessing(String buildOutput, IContextManager contextManager)
            throws InterruptedException {
        var llm = contextManager.getLlm(contextManager.getService().quickestModel(), "BuildOutputPreprocessor");
        var messages = List.of(createSystemMessage(), createExtractionRequest(buildOutput));

        var result = applyCustomTimeout(
                () -> {
                    try {
                        return llm.sendRequest(messages, false);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                },
                PREPROCESSING_TIMEOUT_SECONDS);

        return handlePreprocessingResult(result, buildOutput, contextManager);
    }

    private static String handlePreprocessingResult(
            Llm.StreamingResult result, String originalOutput, IContextManager contextManager) {
        if (result.error() != null) {
            logPreprocessingError(result.error(), contextManager);
            return originalOutput;
        }

        String extractedErrors = result.text().trim();
        if (extractedErrors.isBlank()) {
            logger.warn("Build output preprocessing returned empty result. Using original output.");
            return originalOutput;
        }

        var lines = Splitter.on('\n').splitToList(originalOutput);
        logger.info(
                "Successfully extracted relevant errors from build output. "
                        + "Reduced from {} lines to {} characters.",
                lines.size(),
                extractedErrors.length());

        return extractedErrors;
    }

    private static void logPreprocessingError(@Nullable Throwable error, IContextManager contextManager) {
        if (error == null) {
            logger.warn("Build output preprocessing failed with null error. Using original output.");
            return;
        }

        boolean isCustomTimeout =
                error.getMessage() != null && error.getMessage().contains("Build output preprocessing timed out");
        boolean isModelTimeout =
                error.getMessage() != null && error.getMessage().contains("timed out");

        if (isCustomTimeout) {
            logger.warn(
                    "Build output preprocessing timed out after {} seconds (quickest model: {}). Using original output.",
                    PREPROCESSING_TIMEOUT_SECONDS,
                    contextManager.getService().quickestModel().getClass().getSimpleName());
        } else if (isModelTimeout) {
            logger.warn(
                    "Build output preprocessing timed out at model level (quickest model: {}). Using original output.",
                    contextManager.getService().quickestModel().getClass().getSimpleName());
        } else {
            logger.warn("Error during build output preprocessing: {}. Using original output.", error.getMessage());
        }
    }

    private static SystemMessage createSystemMessage() {
        return new SystemMessage(
                """
            You are familiar with common build tools (Gradle, Maven, npm, TypeScript, sbt, etc.).
            Extract the most relevant compilation and build errors from this verbose build output.

            Focus on up to %d actionable errors that developers need to fix:
            1. Compilation errors (syntax errors, type errors, missing imports)
            2. Test failures with specific failure reasons
            3. Dependency resolution failures
            4. Build configuration errors

            For each error, include:
            - File path and line number when available
            - Specific error message
            - 2-3 lines of context when helpful
            - Relevant stack trace snippets (not full traces)

            IGNORE verbose progress messages, successful compilation output,
            general startup/shutdown logs, and non-blocking warnings.

            Return the extracted errors in a clean, readable format.
            """
                        .stripIndent()
                        .formatted(MAX_EXTRACTED_ERRORS));
    }

    private static UserMessage createExtractionRequest(String buildOutput) {
        return new UserMessage(
                """
            Please extract the most relevant compilation/build errors from this build output:

            ```
            %s
            ```
            """
                        .stripIndent()
                        .formatted(buildOutput));
    }
}
