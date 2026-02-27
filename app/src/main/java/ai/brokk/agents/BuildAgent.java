package ai.brokk.agents;

import static ai.brokk.project.FileFilteringService.normalizeExclusionPattern;
import static ai.brokk.project.FileFilteringService.toUnixPath;
import static java.util.Objects.requireNonNull;

import ai.brokk.AnalyzerUtil;
import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.LlmOutputMeta;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.project.IProject;
import ai.brokk.tools.SearchTools;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.util.BuildOutputProcessor;
import ai.brokk.util.BuildToolConventions;
import ai.brokk.util.BuildToolConventions.BuildSystem;
import ai.brokk.util.BuildTools;
import ai.brokk.util.BuildVerifier;
import ai.brokk.util.Environment;
import ai.brokk.util.EnvironmentPython;
import ai.brokk.util.Messages;
import ai.brokk.util.ShellConfig;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import com.github.mustachejava.util.DecoratedCollection;
import com.google.common.base.Splitter;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ToolChoice;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * The BuildAgent class is responsible for executing a process to gather and report build details for a software
 * project's development environment. It interacts with tools, processes project files, and uses an LLM to identify
 * build commands, test configurations, and exclusions, ultimately providing structured build information or aborting if
 * unsupported.
 */
public class BuildAgent {
    private static final Logger logger = LogManager.getLogger(BuildAgent.class);

    // Safety limits to prevent infinite loops
    private static final int MAX_ITERATIONS = 10;
    private static final int MAX_REPEATED_TOOL_CALLS = 5;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Llm llm;
    private final ToolRegistry globalRegistry;
    private final IConsoleIO io;

    // Use standard ChatMessage history
    private final List<ChatMessage> chatHistory = new ArrayList<>();
    private final IProject project;
    // Field to store the result from the reportBuildDetails tool
    private @Nullable BuildDetails reportedDetails = null;
    // Field to store the reason from the abortBuildDetails tool
    private @Nullable String abortReason = null;
    // Field to store directories to exclude from code intelligence
    private List<String> currentExcludedDirectories = new ArrayList<>();
    // Patterns that came directly from the LLM (not gitignore baseline)
    private Set<String> llmAddedPatterns = Set.of();

    public BuildAgent(IProject project, Llm llm, ToolRegistry globalRegistry, IConsoleIO io) {
        this.project = project;
        this.llm = llm;
        this.globalRegistry = globalRegistry;
        this.io = io;
    }

    /**
     * Returns patterns that came directly from the LLM (not gitignore baseline).
     * Call this after execute() to get patterns for UI highlighting.
     */
    public Set<String> getLlmAddedPatterns() {
        return llmAddedPatterns;
    }

    /**
     * Returns the details reported by the tool, for testing only.
     */
    @VisibleForTesting
    @Nullable
    BuildDetails getReportedDetails() {
        return reportedDetails;
    }

    /**
     * Execute the build information gathering process.
     *
     * @return The gathered BuildDetails record, or EMPTY if the process fails or is interrupted.
     */
    public BuildDetails execute() throws InterruptedException {
        return execute(false);
    }

    /**
     * Execute the build information gathering process with optional validation.
     *
     * @param validate If true, validates reported commands before returning.
     * @return The gathered BuildDetails record, or EMPTY if the process fails or is interrupted.
     */
    public BuildDetails execute(boolean validate) throws InterruptedException {
        var tr = globalRegistry.builder().register(this).build();

        // Loop safety tracking
        int iterationCount = 0;
        int reportAttempts = 0;
        List<String> recentToolCalls = new ArrayList<>();

        // build message containing root directory contents
        ToolExecutionRequest initialRequest = ToolExecutionRequest.builder()
                .name("listTrackedFiles")
                .arguments("{\"directoryPath\": \".\"}") // Request root dir
                .build();

        io.beforeToolCall(initialRequest);
        ToolExecutionResult initialResult = tr.executeTool(initialRequest);
        io.afterToolOutput(initialResult);

        chatHistory.add(new UserMessage(
                """
        Here are the contents of the project root directory:
        ```
        %s
        ```"""
                        .formatted(initialResult.resultText())));
        chatHistory.add(Messages.create("Thank you.", ChatMessageType.AI));
        logger.trace("Initial directory listing added to history: {}", initialResult.resultText());

        // Determine build system and set initial excluded directories
        // Use tracked files directly (not filtered) to ensure build files are visible
        var files = project.getRepo().getTrackedFiles().stream()
                .parallel()
                .filter(f -> f.getParent().equals(Path.of("")))
                .map(ProjectFile::toString)
                .toList();

        // Early exit if project has no relevant files
        if (files.isEmpty()) {
            logger.info("No tracked files found in project root - skipping BuildAgent execution");
            return BuildDetails.EMPTY;
        }

        BuildSystem detectedSystem = BuildToolConventions.determineBuildSystem(files);
        this.currentExcludedDirectories = new ArrayList<>(BuildToolConventions.getDefaultExcludes(detectedSystem));
        logger.info(
                "Determined build system: {}. Initial excluded directories: {}",
                detectedSystem,
                this.currentExcludedDirectories);

        // Add directory exclusions based on gitignore filtering
        // Walk the directory tree and explicitly validate each directory using gitignore semantics.
        // This is correct: validates actual gitignore rules rather than inferring from file absence,
        // which prevents false positives (empty directories, directories with only non-code files).
        var addedFromGitignore = new ArrayList<String>();
        if (project.hasGit()) {
            try {
                // Walk the directory tree to find gitignored directories.
                // Uses walkFileTree to skip subtrees once a directory is known-ignored,
                // avoiding descent into node_modules/, target/, .git/, etc.
                var projectRoot = project.getRoot();

                Files.walkFileTree(projectRoot, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (dir.equals(projectRoot)) {
                            return FileVisitResult.CONTINUE;
                        }

                        var relPath = projectRoot.relativize(dir);
                        var unixPath = toUnixPath(relPath);

                        // Skip hidden directories like .git
                        if (unixPath.startsWith(".")) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }

                        // Check if this directory is gitignored
                        if (project.isGitignored(relPath)) {
                            currentExcludedDirectories.add(unixPath);
                            addedFromGitignore.add(unixPath);
                            return FileVisitResult.SKIP_SUBTREE; // Don't descend into ignored dirs
                        }

                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        return FileVisitResult.CONTINUE; // We only care about directories
                    }
                });

            } catch (IOException e) {
                logger.warn("Error analyzing gitignore directory exclusions: {}", e.getMessage());
            }
        }

        if (!addedFromGitignore.isEmpty()) {
            logger.debug(
                    "Added the following directory patterns from gitignore analysis to excluded directories: {}",
                    addedFromGitignore);
        }

        // 2. Iteration Loop
        while (true) {
            // 3. Build Prompt
            List<ChatMessage> messages = buildPrompt();

            // 4. Add tools
            // Get specifications for ALL tools the agent might use in this turn, from the local registry.
            var tools = new ArrayList<>(tr.getTools(List.of(
                    "listTrackedFiles", "listFiles", "findFilenames", "findFilesContaining", "getFileContents")));
            if (chatHistory.size() > 1) {
                // allow terminal tools
                tools.addAll(tr.getTools(List.of("reportBuildDetails", "abortBuildDetails")));
            }

            // Make the LLM request
            Llm.StreamingResult result;
            try {
                result = llm.sendRequest(messages, new ToolContext(tools, ToolChoice.REQUIRED, tr));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            if (result.error() != null) {
                logger.error("LLM error in BuildInfoAgent: {}", result.error().getMessage());
                return BuildDetails.EMPTY;
            }

            var aiMessage = ToolRegistry.removeDuplicateToolRequests(result.aiMessage());
            chatHistory.add(aiMessage); // Add AI request message to history

            // 5. Process Tool Execution Requests
            var requests = aiMessage.toolExecutionRequests();
            logger.trace("LLM requested {} tools", requests.size());

            // Prioritize terminal actions (report or abort)
            ToolExecutionRequest reportRequest = null;
            ToolExecutionRequest abortRequest = null;
            List<ToolExecutionRequest> otherRequests = new ArrayList<>();

            for (var request : requests) {
                String toolName = request.name();
                logger.trace("Processing requested tool: {} with args: {}", toolName, request.arguments());
                if (toolName.equals("reportBuildDetails")) {
                    reportRequest = request;
                } else if (toolName.equals("abortBuildDetails")) {
                    abortRequest = request;
                } else {
                    otherRequests.add(request);
                }
            }

            // 6. Execute Terminal Actions via local ToolRegistry (if any)
            if (reportRequest != null) {
                io.beforeToolCall(reportRequest);
                ToolExecutionResult termResult = tr.executeTool(reportRequest);
                io.afterToolOutput(termResult);

                var details = requireNonNull(
                        reportedDetails,
                        "reportedDetails should be non-null after successful reportBuildDetails tool execution");

                if (validate) {
                    reportAttempts++;
                    String validationError = validateBuildDetails(details);
                    if (validationError == null) {
                        return details;
                    }

                    if (reportAttempts < 3) {
                        String feedback =
                                """
                                The reported build details were validated and the following command failed:
                                %s
                                Please review the build configuration and call reportBuildDetails again with corrected commands."""
                                        .formatted(validationError);
                        chatHistory.add(
                                new ToolExecutionResultMessage(reportRequest.id(), "reportBuildDetails", feedback));
                        reportedDetails = null;
                        continue;
                    } else {
                        logger.warn("BuildAgent exhausted validation retries. Returning EMPTY.");
                        return BuildDetails.EMPTY;
                    }
                }
                return details;
            } else if (abortRequest != null) {
                io.beforeToolCall(abortRequest);
                ToolExecutionResult termResult = tr.executeTool(abortRequest);
                io.afterToolOutput(termResult);

                assert abortReason != null;
                return BuildDetails.EMPTY;
            }

            // 7. Execute Non-Terminal Tools
            // Only proceed if no terminal action was requested this turn
            for (var request : otherRequests) {
                String toolName = request.name();
                logger.trace("Agent action: {} ({})", toolName, request.arguments());

                io.beforeToolCall(request);
                ToolExecutionResult execResult = tr.executeTool(request);
                io.afterToolOutput(execResult);

                ToolExecutionResultMessage resultMessage = execResult.toExecutionResultMessage();

                // Log tool result for debugging
                logger.debug("Tool '{}' result: {}", toolName, execResult.resultText());

                // Track tool call for repetition detection
                String signature = createToolCallSignature(request);
                recentToolCalls.add(signature);

                // Record individual tool result history
                chatHistory.add(resultMessage);
                logger.trace("Tool result added to history: {}", resultMessage.text());
            }

            // 8. Check for stuck behavior after this iteration
            iterationCount++;
            logger.debug("BuildAgent iteration {} complete", iterationCount);

            // Check maximum iteration limit
            if (iterationCount >= MAX_ITERATIONS) {
                logger.warn(
                        "BuildAgent reached maximum iteration limit ({}) without finding build details. "
                                + "This suggests the project structure is unclear or unsupported. "
                                + "Tool calls history: {}",
                        MAX_ITERATIONS,
                        recentToolCalls.stream().collect(Collectors.groupingBy(s -> s, Collectors.counting())));
                return BuildDetails.EMPTY;
            }

            // Check for repetitive tool calls
            if (recentToolCalls.size() >= MAX_REPEATED_TOOL_CALLS) {
                List<String> lastNCalls = recentToolCalls.subList(
                        recentToolCalls.size() - MAX_REPEATED_TOOL_CALLS, recentToolCalls.size());

                String firstCall = lastNCalls.get(0);
                boolean allSame = lastNCalls.stream().allMatch(firstCall::equals);

                if (allSame) {
                    logger.warn(
                            "BuildAgent detected repetitive behavior: tool '{}' called {} times "
                                    + "consecutively without progress. Aborting build details gathering.",
                            firstCall,
                            MAX_REPEATED_TOOL_CALLS);
                    return BuildDetails.EMPTY;
                }
            }
        }
    }

    /** Build the prompt for the LLM, including system message and history. */
    private List<ChatMessage> buildPrompt() {
        List<ChatMessage> messages = new ArrayList<>();

        String wrapperScriptInstruction;
        if (Environment.isWindows()) {
            wrapperScriptInstruction =
                    """
                    Prefer the repository-local *wrapper script* when it exists in the project root (e.g. gradlew.cmd, mvnw.cmd).
                    Since the command will run in PowerShell, use the `--%` stop-parsing token immediately after the command or wrapper script to avoid quoting issues (e.g., `mvnw.cmd --% compile`, `gradlew.bat --% classes`).""";
        } else {
            wrapperScriptInstruction =
                    "Prefer the repository-local *wrapper script* when it exists in the project root (e.g. ./gradlew, ./mvnw).";
        }

        // System Prompt
        messages.add(new SystemMessage(
                """
                You are an agent tasked with finding build information for the *development* environment of a software project.
                Your goal is to identify key build commands (clean, compile/build, test all, test specific) and how to invoke those commands correctly.
                Determine if there is a single command that builds/tests the entire project. If there is no discernable set of global root commands (e.g. in a multi-module project where commands must be run per-module), leave the root commands blank AND set `buildLintEnabled` and `testAllEnabled` to `false`.
                Focus *only* on details relevant to local development builds/profiles, explicitly ignoring production-specific
                configurations unless they are the only ones available.

                Use the tools to examine build files (like `pom.xml`, `build.gradle`, etc.), configuration files, and linting files,
                as necessary, to determine the information needed by `reportBuildDetails`.

                **IMPORTANT**: If after initial exploration you find:
                - The project root is empty or contains no code files
                - No recognized build files (pom.xml, build.gradle, package.json, cargo.toml, go.mod, setup.py, pyproject.toml, CMakeLists.txt, Makefile, *.csproj, *.sln, Gemfile, composer.json, etc.)
                - Only documentation files (*.md, *.txt, LICENSE, README, etc.) with no build configuration
                - The project structure is unclear or unsupported

                However, if `listTrackedFiles` reveals subdirectories that might contain code or modules (e.g. `backend/`, `frontend/`, `services/`), you MUST explore them using `listTrackedFiles` or `listFiles` before aborting.

                Otherwise, call `abortBuildDetails` immediately with an explanation. Do NOT continue exploring indefinitely.
                Examples of when to abort:
                - `listTrackedFiles(".")` returns "No tracked files found"
                - `listTrackedFiles(".")` returns files but none are recognized build configuration files, and no promising subdirectories exist
                - Root directory contains only documentation files (*.md, *.txt, LICENSE, etc.) and no source code, build files, or promising subdirectories
                - After checking root and promising subdirectories, no recognized build system or project structure is found

                Note: `listTrackedFiles` shows all git-tracked files (unfiltered), while `listFiles` respects exclusion patterns.
                Use `listTrackedFiles` to discover build configurations, then `listFiles` or other tools to explore filtered content.

                When selecting build or test commands, prefer flags or sub-commands that minimise console output (for example, Maven -q, Gradle --quiet, npm test --silent, sbt -error).
                Avoid verbose flags such as --info, --debug, or -X unless they are strictly required for correct operation.

                The lists are DecoratedCollection instances, so you get first/last/index/value fields.

                Since the lists are file- and class- oriented, for test environments like `go test` and `cargo test`
                that are function-oriented, we fall back to running all tests.

                Examples:

                | Build tool        | One-liner a user could write
                | ----------------- | ------------------------------------------------------------------------
                | **SBT**           | `sbt -error "testOnly{{#fqclasses}} {{value}}{{/fqclasses}}"`
                | **Maven**         | `mvn --quiet test -Dsurefire.failIfNoSpecifiedTests=false -Dtest={{#classes}}{{value}}{{^last}},{{/last}}{{/classes}}`
                | **Gradle**        | `gradle --quiet test{{#classes}} --tests {{value}}{{/classes}}`
                | **Go**            | `go test {{#packages}}{{value}} {{/packages}} -run '{{#classes}}{{value}}{{^last}}|{{/last}}{{/classes}}'`
                | **.NET CLI**      | `dotnet test --verbosity quiet --filter "{{#classes}}FullyQualifiedName\\~{{value}}{{^last}}|{{/last}}{{/classes}}"`
                | **Cargo**         | `cargo test -q {{#packages}}{{value}} {{/packages}}`
                | **pytest**        | `uv sync && pytest -q {{#files}}{{value}}{{^last}} {{/last}}{{/files}}`
                | **Poetry**        | `poetry install --no-interaction && poetry run pytest -q {{#files}}{{value}}{{^last}} {{/last}}{{/files}}`
                | **Jest**          | `jest --silent {{#files}}{{value}}{{^last}} {{/last}}{{/files}}`
                | **npm**           | `npm test --silent -- {{#files}}{{value}}{{^last}} {{/last}}{{/files}}`
                | **RSpec**         | `bundle exec rspec --format progress {{#files}}{{value}}{{^last}} {{/last}}{{/files}}`
                | **PHPUnit**       | `./vendor/bin/phpunit --no-progress {{#files}}{{value}}{{^last}} {{/last}}{{/files}}`

                %s
                Only fall back to the bare command (`gradle`, `mvn` …) when no wrapper script is present.

                A baseline set of excluded directories has been established from build conventions and .gitignore: %s
                When you use `reportBuildDetails`, the `excludedDirectories` parameter should contain *additional* directories
                you identify that should be excluded from code intelligence, beyond this baseline.
                IMPORTANT:
                - Only suggest directories that ACTUALLY EXIST in this project - verify before including
                - Only provide literal directory names. DO NOT use glob patterns (e.g., "**/target", "**/.idea"),
                  these are already handled by .gitignore processing.

                Use the `excludedFilePatterns` parameter to specify patterns for files that add cost without value.
                IMPORTANT pattern format rules:
                - Only suggest patterns for files that ACTUALLY EXIST in this project
                - For file extensions, use simple `*.ext` format (e.g., `*.svg`, `*.png`) - do NOT use `**/*.ext`
                - For specific filenames, use the literal name (e.g., `package-lock.json`) - do NOT use `**/filename`
                - Do NOT duplicate directories here - if a directory is in `excludedDirectories`, don't add it as a pattern

                Common file pattern exclusions (only include if files with these extensions exist in this project):
                - Lock files: package-lock.json, yarn.lock, pnpm-lock.yaml
                - Binary/media files: *.svg, *.png, *.gif, *.jpg, *.woff, *.ttf
                - Minified files: *.min.js, *.min.css
                - Build artifacts: *.jar (if not needed for analysis)

                Do NOT exclude: configuration files, type definitions (*.d.ts, ddl files, etc), schema files (OpenAPI, GraphQL, Protobuf sources, etc), or test code.

                This project's primary language is %s. Consider language-specific exclusions that are appropriate.

                Remember to request the `reportBuildDetails` tool to finalize the process ONLY once all information is collected.
                The reportBuildDetails tool expects eight parameters: buildLintCommand, buildLintEnabled, testAllCommand, testAllEnabled, testSomeCommand, excludedDirectories, excludedFilePatterns, and modules.

                If the project is a multi-module project (Maven modules, Gradle subprojects, Cargo workspaces, Go modules, Node.js workspaces, etc.), you MUST identify each module and provide its details in the single flat `modules` list.
                **IMPORTANT**: For polyglot or multi-language repositories, include all modules from ALL detected languages and frameworks in this same list.
                Root commands (the top-level parameters) should represent repo-level orchestration. If no repo-level orchestration is possible, leave root commands blank and disable them by setting `buildLintEnabled` and `testAllEnabled` to `false`.
                Module-specific commands must be executable from the project root (e.g., using flags like `-pl`, `-w`, or `cd`).

                For monolithic repositories or single-module projects, you may report a single module with `relativePath: "."` and provide the relevant `testSomeCommand` in that module entry.

                **Modules JSON Examples:**

                **Maven (Nested Modules):**
                ```json
                "modules": [
                  { "alias": "core", "relativePath": "core", "buildLintCommand": "mvn compile -pl core", "testAllCommand": "mvn test -pl core", "testSomeCommand": "mvn test -pl core -Dtest={{#classes}}{{value}}{{^last}},{{/last}}{{/classes}}" },
                  { "alias": "api", "relativePath": "api", "buildLintCommand": "mvn compile -pl api", "testAllCommand": "mvn test -pl api", "testSomeCommand": "mvn test -pl api -Dtest={{#classes}}{{value}}{{^last}},{{/last}}{{/classes}}" }
                ]
                ```

                **Gradle (Subprojects):**
                ```json
                "modules": [
                  { "alias": "app", "relativePath": "app", "buildLintCommand": "./gradlew :app:classes", "testAllCommand": "./gradlew :app:test", "testSomeCommand": "./gradlew :app:test {{#classes}}--tests {{value}}{{/classes}}" },
                  { "alias": "lib", "relativePath": "lib", "buildLintCommand": "./gradlew :lib:classes", "testAllCommand": "./gradlew :lib:test", "testSomeCommand": "./gradlew :lib:test {{#classes}}--tests {{value}}{{/classes}}" }
                ]
                ```

                **Python (Poetry Monorepo / Sub-packages):**
                ```json
                "modules": [
                  { "alias": "service-a", "relativePath": "services/a", "buildLintCommand": "cd services/a && poetry run mypy .", "testAllCommand": "cd services/a && poetry run pytest", "testSomeCommand": "cd services/a && poetry run pytest {{#files}}{{value}}{{^last}} {{/last}}{{/files}}" }
                ]
                ```

                **Node.js (Workspaces):**
                ```json
                "modules": [
                  { "alias": "web", "relativePath": "packages/web", "buildLintCommand": "npm run build -w web", "testAllCommand": "npm test -w web", "testSomeCommand": "npm test -w web -- {{#files}}{{value}}{{^last}} {{/last}}{{/files}}" }
                ]
                ```

                **Mixed Language / Polyglot (Mono-repo):**
                ```json
                "modules": [
                  { "alias": "java-backend", "relativePath": "backend", "buildLintCommand": "./gradlew :backend:classes", "testAllCommand": "./gradlew :backend:test", "testSomeCommand": "./gradlew :backend:test {{#classes}}--tests {{value}}{{/classes}}" },
                  { "alias": "python-worker", "relativePath": "worker", "buildLintCommand": "cd worker && poetry run mypy .", "testAllCommand": "cd worker && poetry run pytest", "testSomeCommand": "cd worker && poetry run pytest {{#files}}{{value}}{{^last}} {{/last}}{{/files}}" },
                  { "alias": "frontend-app", "relativePath": "frontend", "buildLintCommand": "npm run build -w frontend", "testAllCommand": "npm test -w frontend", "testSomeCommand": "npm test -w frontend -- {{#files}}{{value}}{{^last}} {{/last}}{{/files}}" }
                ]
                ```
                """
                        .formatted(
                                wrapperScriptInstruction,
                                currentExcludedDirectories,
                                project.getBuildLanguage().name())));

        // Add existing history
        messages.addAll(chatHistory);

        // Add final user message indicating the goal (redundant with system prompt but reinforces)
        messages.add(
                new UserMessage(
                        "Determine if this project has a recognizable build system. If build configuration files are found, gather the development build details and report using 'reportBuildDetails'. If no build files are found or the project structure is unclear, call 'abortBuildDetails' with an explanation."));

        return messages;
    }

    @Tool("List all tracked files in a directory, ignoring exclusion patterns. Use '.' for the project root.")
    public String listTrackedFiles(
            @P("Directory path relative to the project root (e.g., '.', 'src/main/java')") String directoryPath) {
        if (directoryPath.isBlank()) {
            throw new IllegalArgumentException("Directory path cannot be empty");
        }

        // Normalize path for filtering (remove leading/trailing slashes, handle '.')
        var normalizedPath = Path.of(directoryPath).normalize();

        logger.debug(
                "BuildAgent listing tracked files for directory path: '{}' (normalized to `{}`)",
                directoryPath,
                normalizedPath);

        // Use tracked files (unfiltered) to ensure build files are visible during discovery
        // This is critical: getAllFiles() applies exclusion patterns which may hide build configs
        return SearchTools.formatFilesInDirectory(project.getRepo().getTrackedFiles(), normalizedPath, directoryPath);
    }

    /** Overload for backward compatibility with older callers and tests. */
    public String reportBuildDetails(
            String buildLintCommand,
            String testAllCommand,
            String testSomeCommand,
            List<String> excludedDirectories,
            List<String> excludedFilePatterns) {
        return reportBuildDetails(
                buildLintCommand,
                true,
                testAllCommand,
                true,
                testSomeCommand,
                excludedDirectories,
                excludedFilePatterns,
                List.of());
    }

    @Tool("Report the gathered build details when ALL information is collected. DO NOT call this method before then.")
    public String reportBuildDetails(
            @P(
                            "Command to build or lint incrementally, e.g. mvn compile, cargo check, pyflakes. If a linter is not clearly in use, don't guess! it will cause problems; just leave it blank.")
                    String buildLintCommand,
            @P("Whether to enable the build/lint command") boolean buildLintEnabled,
            @P(
                            "Command to run all tests. If no test framework is clearly in use, don't guess! it will cause problems; just leave it blank.")
                    String testAllCommand,
            @P("Whether to enable the test all command") boolean testAllEnabled,
            @P(
                            "Command template to run specific tests using Mustache templating. Should use {{classes}}, {{fqclasses}}, {{files}}, {{modules}}, or {{packages}}. {{modules}} and {{packages}} provide dotted module paths for Python/Rust and directory paths for Go. Again, if no class- or file- based framework is in use, leave it blank.")
                    String testSomeCommand,
            @P(
                            "List of directories to exclude from code intelligence (e.g., generated code, build artifacts). Use literal paths, not glob patterns.")
                    List<String> excludedDirectories,
            @P(
                            "List of file patterns to exclude. Use '*.ext' for extensions (e.g., '*.svg'), literal names for specific files (e.g., 'package-lock.json'). Do NOT use **/ prefix or duplicate directories.")
                    List<String> excludedFilePatterns,
            @P(
                            "List of modules identified in the project. Each module should have: 'alias' (name), 'relativePath' (path from root), 'buildLintCommand', 'testAllCommand', and 'testSomeCommand'.")
                    List<ModuleBuildEntry> modules) {
        logger.debug("Raw excludedDirectories from LLM: {}", excludedDirectories);
        logger.debug("Raw excludedFilePatterns from LLM: {}", excludedFilePatterns);
        logger.debug("Baseline excludedDirectories (from gitignore, not stored): {}", currentExcludedDirectories);

        // Only store LLM-suggested patterns, NOT the gitignore baseline
        // Gitignore exclusions are handled separately by FileFilteringService
        // Also filter out directories that don't actually exist
        var projectRoot = project.getRoot();
        var llmDirPatterns = excludedDirectories.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> !containsGlobPattern(s))
                .map(s -> Path.of(s).normalize().toString())
                .filter(s -> {
                    var exists = Files.isDirectory(projectRoot.resolve(s));
                    if (!exists) {
                        logger.debug("Filtering out non-existent directory: {}", s);
                    }
                    return exists;
                })
                .collect(Collectors.toSet());

        var filePatterns = excludedFilePatterns.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        logger.debug("Processed LLM dirExcludes (verified existing): {}", llmDirPatterns);
        logger.debug("Processed filePatterns: {}", filePatterns);

        // Load existing user-added patterns and merge with LLM suggestions
        // This preserves patterns the user added manually in the UI
        var existingPatterns = project.getExclusionPatterns();
        logger.debug("Existing user patterns (will be preserved): {}", existingPatterns);

        // Merge: existing patterns + LLM suggestions (gitignore handled separately)
        var finalPatterns = new LinkedHashSet<String>();
        finalPatterns.addAll(existingPatterns);
        finalPatterns.addAll(llmDirPatterns);
        finalPatterns.addAll(filePatterns);

        // Deduplicate against gitignore
        var deduplicatedPatterns = removeGitignoreDuplicates(finalPatterns);

        // Track LLM patterns after deduplication (UI shows what's actually stored)
        this.llmAddedPatterns = new LinkedHashSet<>(llmDirPatterns);
        this.llmAddedPatterns.addAll(filePatterns);
        this.llmAddedPatterns.retainAll(deduplicatedPatterns);

        logger.debug("Final exclusionPatterns (existing + LLM, deduplicated): {}", deduplicatedPatterns);
        logger.debug("New patterns from this LLM run: {}", llmAddedPatterns);
        this.reportedDetails = new BuildDetails(
                buildLintCommand,
                buildLintEnabled,
                testAllCommand,
                testAllEnabled,
                testSomeCommand,
                deduplicatedPatterns,
                defaultEnvForProject(),
                null,
                "",
                modules);
        logger.debug("reportBuildDetails tool executed. Exclusion patterns: {}", deduplicatedPatterns);
        return "Build details report received and processed.";
    }

    @Tool("Abort the process if you cannot determine the build details or the project structure is unsupported.")
    public String abortBuildDetails(
            @P("Explanation of why the build details cannot be determined") String explanation) {
        // Store the explanation in the agent's field
        this.abortReason = explanation;
        logger.debug("abortBuildDetails tool executed with explanation: {}", explanation);
        return "Abort signal received and processed.";
    }

    /**
     * Creates a normalized signature for a tool call combining name and key arguments.
     * Used for detecting repetitive tool calls that indicate stuck behavior.
     */
    private static String createToolCallSignature(ToolExecutionRequest request) {
        String signature = request.name();
        String args = request.arguments();

        try {
            JsonNode tree = OBJECT_MAPPER.readTree(args);
            if (tree.has("directoryPath")) {
                signature += ":directoryPath:" + tree.get("directoryPath").asText();
            } else if (tree.has("pattern")) {
                signature += ":pattern:" + tree.get("pattern").asText();
            } else if (tree.has("filename")) {
                signature += ":filename:" + tree.get("filename").asText();
            }
        } catch (JsonProcessingException e) {
            // Fallback to hashCode if JSON parsing fails
            signature += ":" + args.hashCode();
        }

        return signature;
    }

    private static boolean containsGlobPattern(String s) {
        return s.contains("*") || s.contains("?") || s.contains("[") || s.contains("]");
    }

    private static boolean isFileExtensionPattern(String pattern) {
        return pattern.startsWith("*.") && !pattern.contains("/");
    }

    @VisibleForTesting
    @Nullable
    String validateBuildDetails(BuildDetails details) throws InterruptedException {
        // 1. Build/lint command
        if (!details.buildLintCommand().isBlank()) {
            var result = BuildVerifier.verify(project, details.buildLintCommand(), details.environmentVariables());
            if (!result.success()) {
                return "Build/lint command failed (exit code %d):\n%s"
                        .formatted(result.exitCode(), Objects.toString(result.output(), ""));
            }
        }

        // 2. Testsome command
        if (!details.testSomeCommand().isBlank()) {
            var testFiles = project.getRepo().getTrackedFiles().stream()
                    .filter(f -> f.toString().toLowerCase(Locale.ROOT).contains("test"))
                    .toList();

            if (!testFiles.isEmpty()) {
                var randomTestFile = testFiles.get(new Random().nextInt(testFiles.size()));
                String relPath = randomTestFile.toString();
                String template = details.testSomeCommand();

                String interpolatedCmd = null;
                if (template.contains("{{#files}}")) {
                    interpolatedCmd = BuildTools.interpolateMustacheTemplate(template, List.of(relPath), "files");
                }

                if (interpolatedCmd != null) {
                    var result = BuildVerifier.verify(project, interpolatedCmd, details.environmentVariables());
                    if (!result.success()) {
                        return "Test command failed (exit code %d):\n%s"
                                .formatted(result.exitCode(), Objects.toString(result.output(), ""));
                    }
                }
            }
        }

        return null;
    }

    /**
     * Remove patterns that are redundant because they match gitignored directories.
     * FileFilteringService already applies gitignore rules at runtime, so storing
     * gitignored directories in exclusion patterns is redundant.
     */
    @VisibleForTesting
    Set<String> removeGitignoreDuplicates(Set<String> patterns) {
        if (!project.hasGit()) {
            return patterns;
        }

        var result = new LinkedHashSet<String>();
        var removedCount = 0;

        for (String pattern : patterns) {
            String normalized = normalizeExclusionPattern(pattern);

            if (isFileExtensionPattern(normalized)) {
                result.add(pattern);
                continue;
            }

            if (containsGlobPattern(normalized)) {
                result.add(pattern);
                continue;
            }

            Path patternPath = Path.of(normalized);
            if (project.isGitignored(patternPath)) {
                logger.debug("Removing redundant gitignored pattern: {}", pattern);
                removedCount++;
            } else {
                result.add(pattern);
            }
        }

        if (removedCount > 0) {
            logger.info("Removed {} gitignore-redundant patterns from exclusions", removedCount);
        }

        return result;
    }

    /**
     * Represents a submodule build configuration.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModuleBuildEntry(
            String alias,
            String relativePath,
            String buildLintCommand,
            String testAllCommand,
            String testSomeCommand) {}

    /** Holds semi-structured information about a project's build process */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BuildDetails(
            String buildLintCommand,
            boolean buildLintEnabled,
            String testAllCommand,
            boolean testAllEnabled,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) String testSomeCommand,
            @JsonDeserialize(as = LinkedHashSet.class) @JsonSetter(nulls = Nulls.AS_EMPTY)
                    Set<String> exclusionPatterns,
            @JsonDeserialize(as = LinkedHashMap.class) @JsonSetter(nulls = Nulls.AS_EMPTY)
                    Map<String, String> environmentVariables,
            @Nullable Integer maxBuildAttempts,
            // blank = do nothing
            @JsonInclude(JsonInclude.Include.NON_EMPTY) String afterTaskListCommand,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) @JsonSetter(nulls = Nulls.AS_EMPTY)
                    List<ModuleBuildEntry> modules) {

        public BuildDetails(
                String buildLintCommand,
                String testAllCommand,
                String testSomeCommand,
                Set<String> exclusionPatterns,
                Map<String, String> environmentVariables,
                @Nullable Integer maxBuildAttempts,
                String afterTaskListCommand,
                List<ModuleBuildEntry> modules) {
            this(
                    buildLintCommand,
                    true,
                    testAllCommand,
                    true,
                    testSomeCommand,
                    exclusionPatterns,
                    environmentVariables,
                    maxBuildAttempts,
                    afterTaskListCommand,
                    modules);
        }

        @VisibleForTesting
        public BuildDetails(
                String buildLintCommand, String testAllCommand, String testSomeCommand, Set<String> exclusionPatterns) {
            this(
                    buildLintCommand,
                    true,
                    testAllCommand,
                    true,
                    testSomeCommand,
                    exclusionPatterns,
                    Map.of(),
                    null,
                    "",
                    List.of());
        }

        public BuildDetails(
                String buildLintCommand,
                String testAllCommand,
                String testSomeCommand,
                Set<String> exclusionPatterns,
                Map<String, String> environmentVariables) {
            this(
                    buildLintCommand,
                    true,
                    testAllCommand,
                    true,
                    testSomeCommand,
                    exclusionPatterns,
                    environmentVariables,
                    null,
                    "",
                    List.of());
        }

        public BuildDetails(
                String buildLintCommand,
                String testAllCommand,
                String testSomeCommand,
                Set<String> exclusionPatterns,
                Map<String, String> environmentVariables,
                @Nullable Integer maxBuildAttempts,
                String afterTaskListCommand) {
            this(
                    buildLintCommand,
                    true,
                    testAllCommand,
                    true,
                    testSomeCommand,
                    exclusionPatterns,
                    environmentVariables,
                    maxBuildAttempts,
                    afterTaskListCommand,
                    List.of());
        }

        public static final BuildDetails EMPTY =
                new BuildDetails("", true, "", true, "", Set.of(), Map.of(), null, "", List.of());

        /**
         * Migrate legacy excludedDirectories to exclusionPatterns.
         * Called during JSON deserialization for backward compatibility.
         */
        @JsonCreator
        public static BuildDetails fromJson(
                @JsonProperty("buildLintCommand") @Nullable String buildLintCommand,
                @JsonProperty("buildLintEnabled") @Nullable Boolean buildLintEnabled,
                @JsonProperty("testAllCommand") @Nullable String testAllCommand,
                @JsonProperty("testAllEnabled") @Nullable Boolean testAllEnabled,
                @JsonProperty("testSomeCommand") @Nullable String testSomeCommand,
                @JsonProperty("exclusionPatterns") @Nullable Set<String> exclusionPatterns,
                @JsonProperty("excludedDirectories") @Nullable Set<String> excludedDirectories,
                @JsonProperty("environmentVariables") @Nullable Map<String, String> environmentVariables,
                @JsonProperty("maxBuildAttempts") @Nullable Integer maxBuildAttempts,
                @JsonProperty("afterTaskListCommand") @Nullable String afterTaskListCommand,
                @JsonProperty("modules") @Nullable List<ModuleBuildEntry> modules) {
            // Migrate legacy excludedDirectories to exclusionPatterns
            Set<String> patterns = new LinkedHashSet<>();
            if (exclusionPatterns != null) {
                patterns.addAll(exclusionPatterns);
            }
            if (excludedDirectories != null) {
                patterns.addAll(excludedDirectories);
            }
            List<ModuleBuildEntry> finalModules;
            if (modules == null || modules.isEmpty()) {
                finalModules = new ArrayList<>();
                boolean hasRootCommands = (buildLintCommand != null && !buildLintCommand.isBlank())
                        || (testAllCommand != null && !testAllCommand.isBlank())
                        || (testSomeCommand != null && !testSomeCommand.isBlank());
                if (hasRootCommands) {
                    finalModules.add(new ModuleBuildEntry(
                            "root",
                            "",
                            buildLintCommand != null ? buildLintCommand : "",
                            testAllCommand != null ? testAllCommand : "",
                            testSomeCommand != null ? testSomeCommand : ""));
                }
            } else {
                finalModules = modules;
            }

            return new BuildDetails(
                    buildLintCommand != null ? buildLintCommand : "",
                    buildLintEnabled != null ? buildLintEnabled : true,
                    testAllCommand != null ? testAllCommand : "",
                    testAllEnabled != null ? testAllEnabled : true,
                    testSomeCommand != null ? testSomeCommand : "",
                    patterns,
                    environmentVariables != null ? environmentVariables : Map.of(),
                    maxBuildAttempts,
                    afterTaskListCommand != null ? afterTaskListCommand : "",
                    finalModules);
        }
    }

    /**
     * Resolves a ProjectFile to the most specific ModuleBuildEntry based on longest prefix match of relativePath.
     * Returns null if no module matches.
     */
    private static @Nullable ModuleBuildEntry resolveModule(ProjectFile file, List<ModuleBuildEntry> modules) {
        if (modules.isEmpty()) return null;

        String relPath = toUnixPath(file.getRelPath());
        return modules.stream()
                .filter(m -> {
                    String modulePath = m.relativePath().replace('\\', '/');
                    if (modulePath.equals(".") || modulePath.isEmpty()) {
                        return true;
                    }
                    return relPath.startsWith(modulePath);
                })
                .max(Comparator.comparingInt(m -> {
                    String mp = m.relativePath();
                    return (mp.equals(".") || mp.isEmpty()) ? 0 : mp.length();
                }))
                .orElse(null);
    }

    /** Determine the best verification command using the provided Context (no reliance on CM.topContext()). */
    @Blocking
    public static @Nullable String determineVerificationCommand(Context ctx) throws InterruptedException {
        return determineVerificationCommand(ctx, null);
    }

    /** Determine the best verification command using the provided Context and an optional override. */
    @Blocking
    public static @Nullable String determineVerificationCommand(Context ctx, @Nullable BuildDetails override)
            throws InterruptedException {
        var cm = ctx.getContextManager();
        IProject project = cm.getProject();
        Path projectRoot = project.getRoot();

        // Retrieve build details from the project associated with the ContextManager
        BuildDetails details = override != null ? override : project.awaitBuildDetails();

        if (details.equals(BuildDetails.EMPTY)) {
            logger.warn("No build details available, cannot determine verification command.");
            return null;
        }

        // Get all files involved in the current Context
        var projectFilesFromEditableOrReadOnly = ctx.allFragments()
                .filter(f -> f.getType().isPath())
                .flatMap(fragment -> fragment.sourceFiles().join().stream());

        var projectFilesFromSkeletons = ctx.allFragments()
                .filter(vf -> vf.getType() == ContextFragment.FragmentType.SKELETON)
                .flatMap(skeletonFragment -> skeletonFragment.sourceFiles().join().stream());

        var workspaceFiles = Stream.concat(projectFilesFromEditableOrReadOnly, projectFilesFromSkeletons)
                .collect(Collectors.toSet());

        // Check project setting for test scope
        IProject.CodeAgentTestScope testScope = project.getCodeAgentTestScope();
        if (testScope == IProject.CodeAgentTestScope.ALL) {
            String cmd = System.getenv("BRK_TESTALL_CMD") != null
                    ? System.getenv("BRK_TESTALL_CMD")
                    : (details.testAllEnabled() ? details.testAllCommand() : "");

            if (cmd.isBlank() && !details.modules().isEmpty()) {
                cmd = details.modules().stream()
                        .map(m -> interpolateCommandWithPythonVersion(m.testAllCommand(), projectRoot))
                        .filter(c -> !c.isBlank())
                        .collect(Collectors.joining(" && "));
            }

            logger.debug("Code Agent Test Scope is ALL, using command: {}", cmd);
            return cmd.isBlank() ? null : interpolateCommandWithPythonVersion(cmd, projectRoot);
        }

        // Proceed with workspace-specific test determination (based on the provided Context)
        logger.debug("Code Agent Test Scope is WORKSPACE, determining tests in workspace (Context-based).");

        var analyzer = cm.getAnalyzer();
        var workspaceTestFiles = workspaceFiles.stream()
                .filter(f -> ContextManager.isTestFile(f, analyzer))
                .toList();

        // If we have test files, delegate to getBuildLintSomeCommand which handles modules
        if (!workspaceTestFiles.isEmpty()) {
            return getBuildLintSomeCommand(cm, details, workspaceTestFiles);
        }

        // No test files; determine which module build/lint commands to run based on changed files
        if (details.modules().isEmpty()) {
            String cmd = details.buildLintEnabled() ? details.buildLintCommand() : "";
            return interpolateCommandWithPythonVersion(cmd, projectRoot);
        }

        var affectedModules = workspaceFiles.stream()
                .map(f -> resolveModule(f, details.modules()))
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparingInt(m -> details.modules().indexOf(m)))
                .toList();

        if (affectedModules.isEmpty()) {
            String cmd = details.buildLintEnabled() ? details.buildLintCommand() : "";
            if (cmd.isBlank()) {
                cmd = details.modules().stream()
                        .map(m -> interpolateCommandWithPythonVersion(m.buildLintCommand(), projectRoot))
                        .filter(c -> !c.isBlank())
                        .collect(Collectors.joining(" && "));
            }
            return interpolateCommandWithPythonVersion(cmd, projectRoot);
        }

        return affectedModules.stream()
                .map(m -> interpolateCommandWithPythonVersion(m.buildLintCommand(), projectRoot))
                .filter(cmd -> !cmd.isBlank())
                .collect(Collectors.joining(" && "));
    }

    /**
     * Determine and interpolate the "run some tests" command for the current workspace.
     */
    public static String getBuildLintSomeCommand(
            IContextManager cm, BuildDetails details, Collection<ProjectFile> workspaceTestFiles)
            throws InterruptedException {
        return getBuildLintSomeCommand(cm, details, workspaceTestFiles, null);
    }

    /**
     * Runs determineVerificationCommand on the {@link ContextManager} background pool and
     * delivers the result asynchronously.
     *
     * @return a {@link CompletableFuture} that completes on the background thread.
     */
    public static CompletableFuture<@Nullable String> determineVerificationCommandAsync(ContextManager cm) {
        return cm.submitBackgroundTask(
                "Determine build verification command", () -> determineVerificationCommand(cm.liveContext()));
    }

    /**
     * Determine and interpolate the "run some tests" command for the current workspace.
     * Supports multi-module grouping and files/classes/modules templating.
     */
    public static String getBuildLintSomeCommand(
            IContextManager cm,
            BuildDetails details,
            Collection<ProjectFile> workspaceTestFiles,
            @Nullable String pythonVersionOverride)
            throws InterruptedException {

        final Path projectRoot = cm.getProject().getRoot();
        String pythonVersion =
                pythonVersionOverride != null ? pythonVersionOverride : getPythonVersionForProject(projectRoot);

        // Group files by module
        Map<ModuleBuildEntry, List<ProjectFile>> moduleGroups = workspaceTestFiles.stream()
                .collect(Collectors.groupingBy(
                        f -> Objects.requireNonNullElse(
                                resolveModule(f, details.modules()),
                                // Placeholder entry for root if no module matches
                                new ModuleBuildEntry(
                                        "root", "", details.buildLintCommand(), details.testAllCommand(), "")),
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<String> commands = new ArrayList<>();

        // Sort modules by their order in BuildDetails
        List<ModuleBuildEntry> sortedModules = moduleGroups.keySet().stream()
                .sorted((m1, m2) -> {
                    if (m1.alias().equals("root")) return -1;
                    if (m2.alias().equals("root")) return 1;
                    return Integer.compare(
                            details.modules().indexOf(m1), details.modules().indexOf(m2));
                })
                .toList();

        for (ModuleBuildEntry module : sortedModules) {
            String template = System.getenv("BRK_TESTSOME_CMD") != null
                    ? System.getenv("BRK_TESTSOME_CMD")
                    : module.testSomeCommand();

            if (template.isBlank()) {
                if (!module.testAllCommand().isBlank()) {
                    template = module.testAllCommand();
                } else if (!module.buildLintCommand().isBlank()) {
                    template = module.buildLintCommand();
                }

                if (template.isBlank()) {
                    continue;
                }
            }

            List<ProjectFile> files = moduleGroups.getOrDefault(module, List.of());
            String cmd = interpolateModuleTestCommand(cm, details, template, files, pythonVersion);
            if (!cmd.isBlank()) {
                commands.add(cmd);
            }
        }

        return commands.isEmpty() ? details.buildLintCommand() : String.join(" && ", commands);
    }

    private static String interpolateModuleTestCommand(
            IContextManager cm,
            BuildDetails details,
            String template,
            List<ProjectFile> files,
            @Nullable String pythonVersion)
            throws InterruptedException {
        Path projectRoot = cm.getProject().getRoot();

        boolean isFilesBased = template.contains("{{#files}}");
        boolean isFqBased = template.contains("{{#fqclasses}}");
        boolean isClassesBased = template.contains("{{#classes}}") || isFqBased;
        boolean isModulesBased = template.contains("{{#modules}}");

        if (!isFilesBased && !isClassesBased && !isModulesBased) {
            return template;
        }

        if (isModulesBased) {
            Path anchor = detectModuleAnchor(projectRoot, details).orElse(null);
            List<String> items = files.stream()
                    .map(pf -> toPythonModuleLabel(projectRoot, anchor, Path.of(pf.toString())))
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .sorted()
                    .toList();
            return items.isEmpty() ? "" : interpolateMustacheTemplate(template, items, "modules", pythonVersion);
        }

        if (isFilesBased) {
            List<String> items = files.stream().map(ProjectFile::toString).toList();
            return interpolateMustacheTemplate(template, items, "files", pythonVersion);
        }

        IAnalyzer analyzer = cm.getAnalyzer();
        if (analyzer.isEmpty()) {
            return "";
        }

        var codeUnits = AnalyzerUtil.testFilesToCodeUnits(analyzer, files);
        if (isFqBased) {
            List<String> items =
                    codeUnits.stream().map(CodeUnit::fqName).sorted().toList();
            return items.isEmpty() ? "" : interpolateMustacheTemplate(template, items, "fqclasses", pythonVersion);
        } else {
            List<String> items =
                    codeUnits.stream().map(CodeUnit::identifier).sorted().toList();
            return items.isEmpty() ? "" : interpolateMustacheTemplate(template, items, "classes", pythonVersion);
        }
    }

    /**
     * Try to detect a module anchor directory for dotted Python labels.
     * Priority:
     *  (1) If either configured command contains a path to a *.py runner that exists
     *      under the project root, return its parent directory.
     *  (2) If a top-level "tests" directory exists, return that.
     *  (3) Otherwise, empty (callers will fall back to per-file import roots).
     */
    private static Optional<Path> detectModuleAnchor(Path projectRoot, BuildDetails details) {
        String testAll = details.testAllCommand();
        String testSome = details.testSomeCommand();

        Optional<Path> fromRunner = extractRunnerAnchorFromCommands(projectRoot, List.of(testAll, testSome));
        if (fromRunner.isPresent()) return fromRunner;

        Path tests = projectRoot.resolve("tests");
        if (Files.isDirectory(tests)) return Optional.of(tests);

        return Optional.empty();
    }

    /**
     * Parse the given commands for tokens that look like "something.py".
     * If that file exists within the project, return its parent as the module anchor.
     * This supports commands like:
     *   "uv run tests/runtests.py {{#modules}}...{{/modules}}"
     *   "python foo/bar/run_tests.py"
     */
    private static Optional<Path> extractRunnerAnchorFromCommands(Path projectRoot, List<String> commands) {
        for (String cmd : commands) {
            if (cmd.isBlank()) continue;

            Iterable<String> tokens = Splitter.on(Pattern.compile("\\s+")).split(cmd);
            for (String t : tokens) {
                if (!t.endsWith(".py")) continue;

                String cleaned = t.replaceAll("^[\"']|[\"']$", "");
                Path candidate = projectRoot.resolve(cleaned).normalize();

                if (!Files.exists(candidate)) {
                    // Try without projectRoot if the token is absolute
                    Path p = Path.of(cleaned);
                    if (Files.exists(p)) candidate = p.normalize();
                }

                if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                    Path parent = candidate.getParent();
                    if (parent != null && Files.isDirectory(parent)) {
                        return Optional.of(parent);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Get the Python version for the project, or null if unable to determine. */
    private static @Nullable String getPythonVersionForProject(Path projectRoot) {
        try {
            return new EnvironmentPython(projectRoot).getPythonVersion();
        } catch (Exception e) {
            logger.debug("Unable to determine Python version for project", e);
            return null;
        }
    }

    /**
     * Convert a Python source path to a dotted module label.
     * If anchor is non-null and the file lives under it, label is relative to anchor.
     * Otherwise, derive a per-file import root by walking up while __init__.py exists.
     * Handles:
     *  - stripping ".py"
     *  - mapping "__init__.py" to the package path
     *  - normalizing separators and leading dots
     */
    private static String toPythonModuleLabel(Path projectRoot, @Nullable Path anchor, Path filePath) {
        Path abs = projectRoot.resolve(filePath).normalize();

        Path base = anchor;
        if (base == null || !abs.startsWith(base)) {
            base = inferImportRoot(abs).orElse(null);
        }
        if (base == null) return "";

        Path rel;
        try {
            rel = base.relativize(abs);
        } catch (IllegalArgumentException e) {
            return "";
        }

        String s = toUnixPath(rel);
        if (s.endsWith(".py")) s = s.substring(0, s.length() - 3);
        if (s.endsWith("/__init__")) s = s.substring(0, s.length() - "/__init__".length());
        while (s.startsWith("/")) s = s.substring(1);
        String dotted = s.replace('/', '.');
        while (dotted.startsWith(".")) dotted = dotted.substring(1);
        return dotted;
    }

    /**
     * Infer the import root for a given Python file by walking up directories
     * as long as they contain "__init__.py". Returns the first directory above
     * the package chain (i.e., the path whose child is the top-level package).
     */
    private static Optional<Path> inferImportRoot(Path absFile) {
        if (!Files.isRegularFile(absFile)) return Optional.empty();
        Path p = absFile.getParent();
        Path lastWithInit = null;
        while (p != null && Files.isRegularFile(p.resolve("__init__.py"))) {
            lastWithInit = p;
            p = p.getParent();
        }
        return Optional.ofNullable(
                Objects.requireNonNullElse(lastWithInit, absFile).getParent());
    }

    /**
     * Interpolate a build/test command with just the Python version variable.
     * Used when there are no specific files or classes to substitute.
     * If the template doesn't contain {{pyver}}, returns the original command.
     */
    private static String interpolateCommandWithPythonVersion(String command, Path projectRoot) {
        if (command.isEmpty()) {
            return command;
        }
        // Allow override via environment variable
        if (System.getenv("BRK_TESTALL_CMD") != null) {
            command = System.getenv("BRK_TESTALL_CMD");
        }
        String pythonVersion = getPythonVersionForProject(projectRoot);
        return interpolateMustacheTemplate(command, List.of(), "unused", pythonVersion);
    }

    /**
     * Interpolates a Mustache template with the given list of items and optional Python version.
     * Supports {{files}}, {{classes}}, {{fqclasses}}, {{modules}}, and {{pyver}} variables.
     *
     * Note: mustache.java's DecoratedCollection does not support the -last feature like Handlebars does,
     * so we post-process to clean up trailing separators that result from the final iteration.
     */
    public static String interpolateMustacheTemplate(String template, List<String> items, String listKey) {
        return interpolateMustacheTemplate(template, items, listKey, null);
    }

    /**
     * Interpolates a Mustache template with the given list of items and optional Python version.
     * Supports {{files}}, {{classes}}, {{fqclasses}}, {{modules}}, and {{pyver}} variables.
     */
    public static String interpolateMustacheTemplate(
            String template, List<String> items, String listKey, @Nullable String pythonVersion) {
        if (template.isEmpty()) {
            return "";
        }

        MustacheFactory mf = new DefaultMustacheFactory();
        // The "templateName" argument to compile is for caching and error reporting, can be arbitrary.
        Mustache mustache = mf.compile(new StringReader(template), "dynamic_template");

        Map<String, Object> context = new HashMap<>();
        // Mustache.java handles null or empty lists correctly for {{#section}} blocks.
        context.put(listKey, new DecoratedCollection<>(items));
        context.put("pyver", pythonVersion == null ? "" : pythonVersion);

        StringWriter writer = new StringWriter();
        // This can throw MustacheException, which will propagate as a RuntimeException
        // as per the project's "let it throw" style.
        mustache.execute(writer, context);

        return writer.toString();
    }

    /**
     * Run the verification build for the current project, stream output to the console, and update the session's Build
     * Results fragment.
     *
     * <p>Returns empty string on success (or when no command is configured), otherwise the raw combined error/output
     * text.
     */
    @Blocking
    public static String runVerification(IContextManager cm) throws InterruptedException {
        return runVerification(cm, null);
    }

    /**
     * Run the verification build for the current project with optional build details override.
     */
    @Blocking
    public static String runVerification(IContextManager cm, @Nullable BuildDetails override)
            throws InterruptedException {
        var interrupted = new AtomicReference<InterruptedException>(null);
        var updated = cm.pushContext(ctx -> {
            try {
                return runVerification(ctx, override);
            } catch (InterruptedException e) {
                // Preserve interrupt status and defer propagation until after pushContext returns
                Thread.currentThread().interrupt();
                interrupted.set(e);
                return ctx;
            }
        });
        var ie = interrupted.get();
        if (ie != null) {
            throw ie;
        }
        return updated.getBuildError();
    }

    /**
     * Context-based overload that performs build/check and returns an updated Context with the build results. No pushes
     * are performed here; callers decide when to persist.
     */
    @Blocking
    public static Context runVerification(Context ctx) throws InterruptedException {
        return runVerification(ctx, null);
    }

    /**
     * Context-based overload that performs build/check with an optional build details override.
     */
    @Blocking
    public static Context runVerification(Context ctx, @Nullable BuildDetails override) throws InterruptedException {
        var cm = ctx.getContextManager();
        var io = cm.getIo();

        var verificationCommand = determineVerificationCommand(ctx, override);
        if (verificationCommand == null || verificationCommand.isBlank()) {
            io.llmOutput(
                    "\nNo verification command specified, skipping build/check.",
                    ChatMessageType.CUSTOM,
                    LlmOutputMeta.DEFAULT);
            return ctx; // unchanged
        }

        boolean noConcurrentBuilds = "true".equalsIgnoreCase(System.getenv("BRK_NO_CONCURRENT_BUILDS"));
        if (noConcurrentBuilds) {
            var lock = acquireBuildLock(cm);
            if (lock == null) {
                logger.warn("Failed to acquire build lock; proceeding without it");
                return runBuildAndUpdateFragmentInternal(ctx, verificationCommand, override);
            }
            try (var ignored = lock) {
                logger.debug("Acquired build lock {}", lock.lockFile());
                return runBuildAndUpdateFragmentInternal(ctx, verificationCommand, override);
            } catch (Exception e) {
                logger.warn("Exception while using build lock {}; proceeding without it", lock.lockFile(), e);
                return runBuildAndUpdateFragmentInternal(ctx, verificationCommand, override);
            }
        } else {
            return runBuildAndUpdateFragmentInternal(ctx, verificationCommand, override);
        }
    }

    /**
     * Context-based overload that performs a caller-specified command and returns an updated Context with the build
     * results. No pushes are performed here; callers decide when to persist.
     */
    @Blocking
    public static Context runExplicitCommand(Context ctx, String command, @Nullable BuildDetails override)
            throws InterruptedException {
        var cm = ctx.getContextManager();
        var io = cm.getIo();

        if (command.isBlank()) {
            io.llmOutput("\nNo explicit command specified, skipping.", ChatMessageType.CUSTOM, LlmOutputMeta.DEFAULT);
            return ctx.withBuildResult(true, "");
        }

        boolean noConcurrentBuilds = "true".equalsIgnoreCase(System.getenv("BRK_NO_CONCURRENT_BUILDS"));
        if (noConcurrentBuilds) {
            var lock = acquireBuildLock(cm);
            if (lock == null) {
                logger.warn("Failed to acquire build lock; proceeding without it");
                return runExplicitBuildAndUpdateFragmentInternal(ctx, command, override);
            }
            try (var ignored = lock) {
                logger.debug("Acquired build lock {}", lock.lockFile());
                return runExplicitBuildAndUpdateFragmentInternal(ctx, command, override);
            } catch (Exception e) {
                logger.warn("Exception while using build lock {}; proceeding without it", lock.lockFile(), e);
                return runExplicitBuildAndUpdateFragmentInternal(ctx, command, override);
            }
        } else {
            return runExplicitBuildAndUpdateFragmentInternal(ctx, command, override);
        }
    }

    /** Holder for lock resources, AutoCloseable so try-with-resources releases it. */
    private record BuildLock(FileChannel channel, FileLock lock, Path lockFile) implements AutoCloseable {
        @Override
        public void close() {
            try {
                if (lock.isValid()) lock.release();
            } catch (Exception e) {
                logger.debug("Error releasing build lock {}: {}", lockFile, e.toString());
            }
            try {
                if (channel.isOpen()) channel.close();
            } catch (Exception e) {
                logger.debug("Error closing lock channel {}: {}", lockFile, e.toString());
            }
        }
    }

    /** Attempts to acquire an inter-process build lock. Returns a non-null BuildLock on success, or null on failure. */
    private static @Nullable BuildLock acquireBuildLock(IContextManager cm) {
        Path lockDir = Paths.get(System.getProperty("java.io.tmpdir"), "brokk");
        try {
            Files.createDirectories(lockDir);
        } catch (IOException e) {
            logger.warn("Unable to create lock directory {}; proceeding without build lock", lockDir, e);
            return null;
        }

        var repoNameForLock = getOriginRepositoryName(cm);
        Path lockFile = lockDir.resolve(repoNameForLock + ".lock");

        try {
            var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            var lock = channel.lock();
            logger.debug("Acquired build lock {}", lockFile);
            return new BuildLock(channel, lock, lockFile);
        } catch (IOException ioe) {
            logger.warn("Failed to acquire file lock {}; proceeding without it", lockFile, ioe);
            return null;
        }
    }

    private static String getOriginRepositoryName(IContextManager cm) {
        var url = cm.getRepo().getRemoteUrl();
        if (url == null || url.isBlank()) {
            return cm.getRepo().getGitTopLevel().getFileName().toString();
        }
        if (url.endsWith(".git")) url = url.substring(0, url.length() - 4);
        int idx = Math.max(url.lastIndexOf('/'), url.lastIndexOf(':'));
        if (idx >= 0 && idx < url.length() - 1) {
            return url.substring(idx + 1);
        }
        throw new IllegalArgumentException("Unable to parse git repo url " + url);
    }

    /** Context-based internal variant: returns a new Context with the updated build results, streams output via IO. */
    private static Context runBuildAndUpdateFragmentInternal(
            Context ctx, String verificationCommand, @Nullable BuildDetails override) throws InterruptedException {
        var cm = ctx.getContextManager();
        var io = cm.getIo();

        var details = override != null ? override : cm.getProject().awaitBuildDetails();

        // When BRK_TEST_RETRIES is set, decouple lint from tests and retry flaky test failures
        @Nullable String testRetriesEnv = System.getenv("BRK_TEST_RETRIES");
        if (testRetriesEnv != null && !testRetriesEnv.isBlank()) {
            int retries;
            try {
                retries = Integer.parseInt(testRetriesEnv.trim());
            } catch (NumberFormatException e) {
                throw new RuntimeException(e);
            }
            return runBuildWithTestRetries(ctx, verificationCommand, details, retries);
        }

        io.llmOutput("\nRunning verification command:", ChatMessageType.CUSTOM, LlmOutputMeta.DEFAULT);

        io.llmOutput(
                verificationCommand + "\n\n",
                ChatMessageType.CUSTOM,
                LlmOutputMeta.newMessage().withTerminal(true));

        try {
            var envVars = details.environmentVariables();
            var execCfg = cm.getProject().getShellConfig();

            Duration timeout = resolveTimeout(cm.getProject().getRunCommandTimeoutSeconds());

            var output = Environment.instance.runShellCommand(
                    verificationCommand,
                    cm.getProject().getRoot(),
                    line -> io.llmOutput(line + "\n", ChatMessageType.CUSTOM, LlmOutputMeta.terminal()),
                    timeout,
                    execCfg,
                    envVars);

            logger.debug("Verification command successful. Output: {}", output);
            return ctx.withBuildResult(true, "Build succeeded.");
        } catch (Environment.SubprocessException e) {
            String rawBuild = Objects.toString(e.getMessage(), "") + "\n\n" + Objects.toString(e.getOutput(), "");
            String processed = BuildOutputProcessor.processForLlm(rawBuild, cm);
            return ctx.withBuildResult(false, processed);
        }
    }

    /**
     * When BRK_TEST_RETRIES is set, talk lint first, then run the test command with retries.
     * This decouples persistent build errors from transient (flaky) test failures.
     */
    private static Context runBuildWithTestRetries(
            Context ctx, String verificationCommand, BuildDetails details, int maxRetries) throws InterruptedException {
        var cm = ctx.getContextManager();
        var io = cm.getIo();

        String lintCommand = details.buildLintCommand();
        // The verificationCommand is the test command that was determined by determineVerificationCommand.
        // If it equals the buildLintCommand, there are no separate tests to run.
        String testCommand = verificationCommand.equals(lintCommand) ? "" : verificationCommand;

        io.llmOutput(
                "\nRunning verification with test retries enabled:", ChatMessageType.CUSTOM, LlmOutputMeta.DEFAULT);

        if (!lintCommand.isBlank()) {
            io.llmOutput(
                    "\nLint/compile: " + lintCommand + "\n",
                    ChatMessageType.CUSTOM,
                    LlmOutputMeta.newMessage().withTerminal(true));
        }
        if (!testCommand.isBlank()) {
            io.llmOutput(
                    "Test: " + testCommand + " (up to " + maxRetries + " attempts)\n\n",
                    ChatMessageType.CUSTOM,
                    LlmOutputMeta.terminal());
        }

        var envVars = details.environmentVariables();
        var result = BuildVerifier.verifyWithRetries(
                cm.getProject(),
                lintCommand,
                testCommand,
                maxRetries,
                envVars,
                line -> io.llmOutput(line + "\n", ChatMessageType.CUSTOM, LlmOutputMeta.terminal()));

        if (result.success()) {
            logger.debug("Verification with retries succeeded. Output: {}", result.output());
            return ctx.withBuildResult(true, "Build succeeded.");
        } else {
            String processed = BuildOutputProcessor.processForLlm(result.output(), cm);
            return ctx.withBuildResult(false, processed);
        }
    }

    private static Context runExplicitBuildAndUpdateFragmentInternal(
            Context ctx, String command, @Nullable BuildDetails override) throws InterruptedException {
        var cm = ctx.getContextManager();
        var io = cm.getIo();

        io.llmOutput(
                "\nRunning command: \n\n```bash\n" + command + "\n```\n",
                ChatMessageType.CUSTOM,
                LlmOutputMeta.DEFAULT);
        String shellLang = ShellConfig.getShellLanguageFromProject(cm.getProject());
        io.llmOutput("\n```" + shellLang + "\n", ChatMessageType.CUSTOM, LlmOutputMeta.DEFAULT);

        try {
            var details = override != null ? override : cm.getProject().awaitBuildDetails();
            var envVars = details.environmentVariables();
            var execCfg = cm.getProject().getShellConfig();

            Duration timeout = resolveTimeout(cm.getProject().getTestCommandTimeoutSeconds());

            var output = Environment.instance.runShellCommand(
                    command,
                    cm.getProject().getRoot(),
                    line -> io.llmOutput(line + "\n", ChatMessageType.CUSTOM, LlmOutputMeta.terminal()),
                    timeout,
                    execCfg,
                    envVars);
            io.llmOutput("\n```", ChatMessageType.CUSTOM, LlmOutputMeta.DEFAULT);

            logger.debug("Explicit command successful. Output: {}", output);
            return ctx.withBuildResult(true, "Build succeeded.");
        } catch (Environment.SubprocessException e) {
            io.llmOutput("\n```", ChatMessageType.CUSTOM, LlmOutputMeta.DEFAULT);

            String rawBuild = Objects.toString(e.getMessage(), "") + "\n\n" + Objects.toString(e.getOutput(), "");
            String processed = BuildOutputProcessor.processForLlm(rawBuild, cm);
            return ctx.withBuildResult(false, processed);
        }
    }

    private static Duration resolveTimeout(long timeoutSeconds) {
        if (timeoutSeconds == -1) {
            return Environment.UNLIMITED_TIMEOUT;
        } else if (timeoutSeconds <= 0) {
            return Environment.DEFAULT_TIMEOUT;
        } else {
            return Duration.ofSeconds(timeoutSeconds);
        }
    }
    /**
     * Provide default environment variables for the project when the agent reports details:
     * - For Python projects: VIRTUAL_ENV=.venv
     * - Otherwise: no defaults
     */
    private Map<String, String> defaultEnvForProject() {
        var lang = project.getBuildLanguage();
        if (lang == Languages.PYTHON) {
            return Map.of("VIRTUAL_ENV", ".venv");
        }
        return Map.of();
    }
}
