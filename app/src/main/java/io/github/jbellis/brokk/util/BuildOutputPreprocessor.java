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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
     * Captures essential build context metadata that should be preserved alongside extracted errors. This ensures LLMs
     * retain visibility into build tool, module, and failure context.
     */
    public static class BuildContext {
        public final String buildTool;
        public final @Nullable String failedTask;
        public final @Nullable String module;
        public final String result;
        public final @Nullable String errorCount;

        public BuildContext(
                String buildTool,
                @Nullable String failedTask,
                @Nullable String module,
                String result,
                @Nullable String errorCount) {
            this.buildTool = buildTool;
            this.failedTask = failedTask;
            this.module = module;
            this.result = result;
            this.errorCount = errorCount;
        }

        public String formatHeader() {
            StringBuilder header = new StringBuilder("Build Context:\n");
            header.append("- Tool: ").append(buildTool).append("\n");

            if (failedTask != null && !failedTask.isEmpty()) {
                header.append("- Failed Task: ").append(failedTask).append("\n");
            }

            if (module != null && !module.isEmpty()) {
                header.append("- Module: ").append(module).append("\n");
            }

            header.append("- Result: ").append(result);

            if (errorCount != null && !errorCount.isEmpty()) {
                header.append(" (").append(errorCount).append(")");
            }

            header.append("\n");
            return header.toString();
        }
    }

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
        BuildContext buildContext = extractBuildContext(buildOutput);
        logger.debug(
                "Extracted build context: Tool={}, Task={}, Module={}, Result={}",
                buildContext.buildTool,
                buildContext.failedTask,
                buildContext.module,
                buildContext.result);

        var llm = contextManager.getLlm(contextManager.getService().quickestModel(), "BuildOutputPreprocessor");
        var messages = List.of(createSystemMessage(), createExtractionRequest(buildOutput, buildContext));

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

        return handlePreprocessingResult(result, buildContext, buildOutput, contextManager);
    }

    private static String handlePreprocessingResult(
            Llm.StreamingResult result,
            BuildContext buildContext,
            String originalOutput,
            IContextManager contextManager) {
        if (result.error() != null) {
            logPreprocessingError(result.error(), contextManager);
            return originalOutput;
        }

        String extractedErrors = result.text().trim();
        if (extractedErrors.isBlank()) {
            logger.warn("Build output preprocessing returned empty result. Using original output.");
            return originalOutput;
        }

        String finalOutput = ensureBuildContextHeader(extractedErrors, buildContext);

        var lines = Splitter.on('\n').splitToList(originalOutput);
        logger.info(
                "Successfully extracted relevant errors from build output. "
                        + "Reduced from {} lines to {} characters.",
                lines.size(),
                finalOutput.length());

        return finalOutput;
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

    private static String ensureBuildContextHeader(String extractedErrors, BuildContext buildContext) {
        if (!extractedErrors.startsWith("Build Context:")) {
            logger.debug("Adding missing build context header to LLM output");
            return buildContext.formatHeader() + "\nExtracted Errors:\n" + extractedErrors;
        }
        return extractedErrors;
    }

    /**
     * Extracts essential build context metadata from build output using pattern matching. This preserves important
     * information about build tool, failed tasks, modules, and overall status.
     */
    private static BuildContext extractBuildContext(String buildOutput) {
        String buildTool = detectBuildTool(buildOutput);
        String failedTask = extractFailedTask(buildOutput, buildTool);
        String module = extractModule(buildOutput, buildTool);
        String result = extractResult(buildOutput);
        String errorCount = extractErrorCount(buildOutput);

        return new BuildContext(buildTool, failedTask, module, result, errorCount);
    }

    private static String detectBuildTool(String buildOutput) {
        // Gradle patterns
        if (buildOutput.contains("> Task :") || buildOutput.contains("Gradle") || buildOutput.contains("./gradlew")) {
            return "Gradle";
        }

        // Maven patterns
        if (buildOutput.contains("[INFO] Building")
                || buildOutput.contains("[ERROR] Failed to execute goal")
                || buildOutput.contains("mvn ")
                || buildOutput.contains("maven-")) {
            return "Maven";
        }

        // NPM patterns
        if (buildOutput.contains("npm ERR!")
                || buildOutput.contains("npm run")
                || buildOutput.contains("package.json")) {
            return "npm";
        }

        // TypeScript patterns
        if (buildOutput.contains("> tsc ") || buildOutput.contains("error TS")) {
            return "TypeScript";
        }

        // SBT patterns
        if (buildOutput.contains("[error]") && (buildOutput.contains("sbt ") || buildOutput.contains("Build.scala"))) {
            return "sbt";
        }

        return "Unknown";
    }

    private static @Nullable String extractFailedTask(String buildOutput, String buildTool) {
        return switch (buildTool) {
            case "Gradle" -> {
                // Look for "> Task :module:taskName FAILED"
                Pattern gradlePattern = Pattern.compile("> Task (:[\\w-]+(?::[\\w-]+)*) FAILED");
                Matcher gradleMatcher = gradlePattern.matcher(buildOutput);
                if (gradleMatcher.find()) {
                    yield gradleMatcher.group(1);
                }
                yield null;
            }
            case "Maven" -> {
                // Look for "[ERROR] Failed to execute goal ... on project module-name"
                Pattern mavenPattern = Pattern.compile(
                        "\\[ERROR\\] Failed to execute goal [^:]+:[^:]+:[^\\s]+ \\([^)]+\\) on project ([\\w-]+)");
                Matcher mavenMatcher = mavenPattern.matcher(buildOutput);
                if (mavenMatcher.find()) {
                    yield "compile on " + mavenMatcher.group(1);
                }
                yield null;
            }
            case "npm" -> {
                // Look for "npm ERR! command failed"
                if (buildOutput.contains("npm ERR! command failed")) {
                    yield "npm run build";
                }
                yield null;
            }
            case "TypeScript" -> {
                if (buildOutput.contains("> tsc ")) {
                    yield "tsc --build";
                }
                yield null;
            }
            default -> null;
        };
    }

    private static @Nullable String extractModule(String buildOutput, String buildTool) {
        return switch (buildTool) {
            case "Gradle" -> {
                // Extract module from task path ":module:task" -> "module"
                Pattern gradleModulePattern = Pattern.compile("> Task :(\\w[\\w-]*):(?:\\w+) FAILED");
                Matcher gradleModuleMatcher = gradleModulePattern.matcher(buildOutput);
                if (gradleModuleMatcher.find()) {
                    yield gradleModuleMatcher.group(1);
                }
                yield null;
            }
            case "Maven" -> {
                // Look for "[INFO] Building module-name"
                Pattern mavenModulePattern = Pattern.compile("\\[INFO\\] Building ([\\w-]+)");
                Matcher mavenModuleMatcher = mavenModulePattern.matcher(buildOutput);
                if (mavenModuleMatcher.find()) {
                    yield mavenModuleMatcher.group(1);
                }
                yield null;
            }
            default -> null;
        };
    }

    private static String extractResult(String buildOutput) {
        if (buildOutput.contains("BUILD FAILED")) {
            return "BUILD FAILED";
        } else if (buildOutput.contains("BUILD SUCCESSFUL")) {
            return "BUILD SUCCESSFUL";
        } else if (buildOutput.contains("[INFO] BUILD FAILURE")) {
            return "BUILD FAILURE";
        } else if (buildOutput.contains("npm ERR!")) {
            return "npm build failed";
        } else if (buildOutput.contains("Found ") && buildOutput.contains(" error")) {
            return "TypeScript compilation failed";
        }

        return "BUILD FAILED";
    }

    private static @Nullable String extractErrorCount(String buildOutput) {
        // Look for various error count patterns
        Pattern[] errorPatterns = {
            Pattern.compile("(\\d+) errors?"), // "3 errors"
            Pattern.compile("Found (\\d+) error"), // TypeScript "Found 4 errors"
            Pattern.compile("(\\d+) actionable tasks.*failed"), // Gradle "5 actionable tasks: 2 executed, 3 failed"
            Pattern.compile("\\[INFO\\] (\\d+) errors?") // Maven "[INFO] 2 errors"
        };

        for (Pattern pattern : errorPatterns) {
            Matcher matcher = pattern.matcher(buildOutput);
            if (matcher.find()) {
                String count = matcher.group(1);
                return count + " error" + (count.equals("1") ? "" : "s");
            }
        }

        return null;
    }

    private static SystemMessage createSystemMessage() {
        return new SystemMessage(
                """
            You are a build output analyzer. Your task is to extract the most relevant and actionable
            compilation/build errors from verbose build system output while preserving essential build context.

            IMPORTANT: Always start your response with a "Build Context:" header that preserves:
            - Build tool (Gradle, Maven, npm, TypeScript, etc.)
            - Failed task/command (e.g., ":app:compileJava", "mvn compile")
            - Module/project name (if applicable)
            - Overall build result and error count

            Then extract up to %d actual errors that developers need to fix, focusing on:
            1. Compilation errors (syntax errors, type errors, missing imports, etc.)
            2. Test failures with specific failure reasons
            3. Dependency resolution failures
            4. Build configuration errors

            For each error, include:
            - The file path where the error occurred
            - Line numbers when available
            - The specific error message
            - 2-3 lines of surrounding context when helpful for understanding
            - Any relevant stack trace snippets (but not full traces)

            IGNORE:
            - Verbose progress messages
            - Successful compilation messages
            - General build system startup/shutdown logs
            - Warnings that don't prevent the build (unless no errors exist)
            - Duplicate error messages

            FORMAT your response as:
            Build Context:
            - Tool: [detected build tool]
            - Failed Task: [specific task that failed]
            - Module: [module/project if applicable]
            - Result: [BUILD FAILED/etc with error count]

            Extracted Errors:
            [clean, readable format of the extracted errors]

            If no actual errors are found, return the most relevant warning or failure information available
            with the build context header.
            """
                        .stripIndent()
                        .formatted(MAX_EXTRACTED_ERRORS));
    }

    private static UserMessage createExtractionRequest(String buildOutput, BuildContext buildContext) {
        return new UserMessage(
                """
            Please extract the most relevant compilation/build errors from this build output:

            ```
            %s
            ```

            Based on the detected build context:
            - Tool: %s
            - Failed Task: %s
            - Module: %s
            - Result: %s
            - Error Count: %s

            Extract up to %d actual errors with their context, following the specified format with Build Context header.
            """
                        .stripIndent()
                        .formatted(
                                buildOutput,
                                buildContext.buildTool,
                                buildContext.failedTask != null ? buildContext.failedTask : "Unknown",
                                buildContext.module != null ? buildContext.module : "N/A",
                                buildContext.result,
                                buildContext.errorCount != null ? buildContext.errorCount : "Unknown",
                                MAX_EXTRACTED_ERRORS));
    }
}
