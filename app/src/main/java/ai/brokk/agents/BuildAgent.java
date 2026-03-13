package ai.brokk.agents;

import static ai.brokk.project.FileFilteringService.normalizeExclusionPattern;
import static ai.brokk.project.FileFilteringService.toUnixPath;
import static java.util.Objects.requireNonNull;

import ai.brokk.IConsoleIO;
import ai.brokk.Llm;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.IProject;
import ai.brokk.tools.SearchTools;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.util.BuildToolConventions;
import ai.brokk.util.BuildToolConventions.BuildSystem;
import ai.brokk.util.BuildTools;
import ai.brokk.util.BuildVerifier;
import ai.brokk.util.Environment;
import ai.brokk.util.Messages;
import ai.brokk.util.MustacheTemplates;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ToolChoice;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

        // Discover nested build files to hint at submodules/services
        var allFiles = project.hasGit() ? project.getRepo().getTrackedFiles() : project.getAllFiles();

        var nestedBuildFiles = allFiles.stream()
                .filter(pf -> {
                    Path rel = pf.getRelPath();
                    int depth = rel.getNameCount();
                    // depth 1 is root. depth 2 to 8 means nested subdirectories.
                    // We stop at 8 to avoid performance issues in extremely deep trees while supporting monorepos.
                    if (depth <= 1 || depth > 8) {
                        return false;
                    }

                    // If any ancestor directory between the file and project root contains a .git folder,
                    // it indicates a nested repository boundary.
                    Path current = pf.absPath().getParent();
                    Path root = project.getRoot();
                    while (current != null && !current.equals(root)) {
                        if (Files.exists(current.resolve(".git"))) {
                            return false;
                        }
                        current = current.getParent();
                    }

                    return BuildToolConventions.isBuildFile(pf.getFileName());
                })
                .map(ProjectFile::toString)
                .sorted()
                .toList();

        if (!nestedBuildFiles.isEmpty()) {
            chatHistory.add(new UserMessage(
                    """
            I also found these nested build files which may indicate submodules or services:
            ```
            %s
            ```"""
                            .formatted(String.join("\n", nestedBuildFiles))));
            chatHistory.add(Messages.create("I will take those into account.", ChatMessageType.AI));
            logger.debug("Nested build files added to history: {}", nestedBuildFiles);
        }

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

                ToolExecutionResultMessage resultMessage = execResult.toMessage();

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
        String languageNames = project.getAnalyzerLanguages().stream()
                .filter(l -> l != Languages.NONE)
                .map(Language::name)
                .collect(Collectors.joining(", "));

        if (languageNames.isBlank()) {
            languageNames = "unknown";
        }

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

                The `testAllCommand` is a plain command that runs the full test suite.
                The `testSomeCommand` uses **Mustache** template syntax to inject test targets.
                Use one of these section blocks depending on the build tool:
                - `{{#files}}...{{/files}}` — file paths
                - `{{#classes}}...{{/classes}}` — simple class names
                - `{{#fqclasses}}...{{/fqclasses}}` — fully-qualified class names
                - `{{#packages}}...{{/packages}}` — package paths, dotted modules, or directories

                Inside a section, each item exposes:
                - `{{value}}` or `{{.}}` — the string value
                - `{{first}}`, `{{last}}` — booleans for first/last iteration
                - `{{index}}` — 0-based position
                - `{{^last}}separator{{/last}}` — render separator between items (not after last)

                Use `{{pyver}}` for the version string when needed (e.g. for Python projects: `python{{pyver}} -m pytest`).
                Only these variables are supported; do not use other Mustache variables.

                The lists are DecoratedCollection instances, so you get first/last/index/value fields.

                For module- or package-oriented test runners like `go test` and `cargo test`, use the `{{#packages}}` section variable to target specific components.

                Examples:

                | Build tool        | One-liner a user could write
                | ----------------- | ------------------------------------------------------------------------
                | **SBT**           | `sbt -error "testOnly{{#fqclasses}} {{value}}{{/fqclasses}}"`
                | **Maven**         | `mvn --quiet test -Dsurefire.failIfNoSpecifiedTests=false -Dtest={{#classes}}{{value}}{{^last}},{{/last}}{{/classes}}`
                | **Gradle**        | `gradle --quiet test{{#classes}} --tests {{value}}{{/classes}}`
                | **Go**            | `go test {{#packages}}{{value}} {{/packages}} -run '{{#classes}}{{value}}{{^last}}|{{/last}}{{/classes}}'`
                | **.NET CLI**      | `dotnet test --verbosity quiet --filter "{{#classes}}FullyQualifiedName\\~{{value}}{{^last}}|{{/last}}{{/classes}}"`
                | **Cargo**         | `cargo test -q {{#packages}}{{value}} {{/packages}}`
                | **pytest**        | `uv sync && pytest -q {{#packages}}{{value}}{{^last}} {{/last}}{{/packages}}`
                | **Poetry**        | `poetry install --no-interaction && poetry run pytest -q {{#packages}}{{value}}{{^last}} {{/last}}{{/packages}}`
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

                This project's languages are %s. Consider language-specific exclusions that are appropriate.

                Remember to request the `reportBuildDetails` tool to finalize the process ONLY once all information is collected.
                The reportBuildDetails tool expects eight parameters: buildLintCommand, buildLintEnabled, testAllCommand, testAllEnabled, testSomeCommand, excludedDirectories, excludedFilePatterns, and modules.

                If the project is a multi-module project (Maven modules, Gradle subprojects, Cargo workspaces, Go modules, Node.js workspaces, etc.), you MUST identify each module and provide its details in the single flat `modules` list.
                For each module, identify its primary programming language (e.g., "Java", "Python", "Go", "Rust", "JavaScript", "TypeScript", "C#").
                **IMPORTANT**: For polyglot or multi-language repositories, include all modules from ALL detected languages and frameworks in this same list.
                Root commands (the top-level parameters) should represent repo-level orchestration. If no repo-level orchestration is possible, leave root commands blank and disable them by setting `buildLintEnabled` and `testAllEnabled` to `false`.
                Module-specific commands must be executable from the project root (e.g., using flags like `-pl`, `-w`, or `cd`).

                For monolithic repositories or single-module projects, you MUST report a single module with `relativePath: "."` and provide the relevant `testSomeCommand` in that module entry.

                **Modules JSON Examples:**

                **Maven (Nested Modules):**
                ```json
                "modules": [
                  { "alias": "core", "relativePath": "core", "language": "Java", "buildLintCommand": "mvn compile -pl core", "testAllCommand": "mvn test -pl core", "testSomeCommand": "mvn test -pl core -Dtest={{#classes}}{{value}}{{^last}},{{/last}}{{/classes}}" },
                  { "alias": "api", "relativePath": "api", "language": "Java", "buildLintCommand": "mvn compile -pl api", "testAllCommand": "mvn test -pl api", "testSomeCommand": "mvn test -pl api -Dtest={{#classes}}{{value}}{{^last}},{{/last}}{{/classes}}" }
                ]
                ```

                **Gradle (Subprojects):**
                ```json
                "modules": [
                  { "alias": "app", "relativePath": "app", "language": "Java", "buildLintCommand": "./gradlew :app:classes", "testAllCommand": "./gradlew :app:test", "testSomeCommand": "./gradlew :app:test {{#classes}}--tests {{value}}{{/classes}}" },
                  { "alias": "lib", "relativePath": "lib", "language": "Java", "buildLintCommand": "./gradlew :lib:classes", "testAllCommand": "./gradlew :lib:test", "testSomeCommand": "./gradlew :lib:test {{#classes}}--tests {{value}}{{/classes}}" }
                ]
                ```

                **Python (Poetry Monorepo / Sub-packages):**
                ```json
                "modules": [
                  { "alias": "service-a", "relativePath": "services/a", "language": "Python", "buildLintCommand": "cd services/a && poetry run mypy .", "testAllCommand": "cd services/a && poetry run pytest", "testSomeCommand": "cd services/a && poetry run pytest {{#files}}{{value}}{{^last}} {{/last}}{{/files}}" }
                ]
                ```

                **Node.js (Workspaces):**
                ```json
                "modules": [
                  { "alias": "web", "relativePath": "packages/web", "language": "JavaScript", "buildLintCommand": "npm run build -w web", "testAllCommand": "npm test -w web", "testSomeCommand": "npm test -w web -- {{#files}}{{value}}{{^last}} {{/last}}{{/files}}" }
                ]
                ```

                **Mixed Language / Polyglot (Mono-repo):**
                ```json
                "modules": [
                  { "alias": "java-backend", "relativePath": "backend", "language": "Java", "buildLintCommand": "./gradlew :backend:classes", "testAllCommand": "./gradlew :backend:test", "testSomeCommand": "./gradlew :backend:test {{#classes}}--tests {{value}}{{/classes}}" },
                  { "alias": "python-worker", "relativePath": "worker", "language": "Python", "buildLintCommand": "cd worker && poetry run mypy .", "testAllCommand": "cd worker && poetry run pytest", "testSomeCommand": "cd worker && poetry run pytest {{#files}}{{value}}{{^last}} {{/last}}{{/files}}" },
                  { "alias": "frontend-app", "relativePath": "frontend", "language": "TypeScript", "buildLintCommand": "npm run build -w frontend", "testAllCommand": "npm test -w frontend", "testSomeCommand": "npm test -w frontend -- {{#files}}{{value}}{{^last}} {{/last}}{{/files}}" }
                ]
                ```
                """
                        .formatted(wrapperScriptInstruction, currentExcludedDirectories, languageNames)));

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

    @Tool("Report the gathered build details when ALL information is collected. DO NOT call this method before then.")
    public String reportBuildDetails(
            @P(
                            "Command to build or lint incrementally, e.g. mvn compile, cargo check, pyflakes. If a linter is not clearly in use, don't guess! it will cause problems; just leave it blank.")
                    String buildLintCommand,
            @P("Whether the buildLintCommand is enabled.") boolean buildLintEnabled,
            @P(
                            "Command to run all tests. If no test framework is clearly in use, don't guess! it will cause problems; just leave it blank.")
                    String testAllCommand,
            @P("Whether the testAllCommand is enabled.") boolean testAllEnabled,
            @P(
                            "Command template to run specific tests using Mustache templating. Should use {{#classes}}, {{#fqclasses}}, {{#files}}, or {{#packages}}. {{#packages}} provides dotted module paths for Python/Rust and directory paths for Go. Again, if no class- or file- based framework is in use, leave it blank.")
                    String testSomeCommand,
            @P(
                            "List of directories to exclude from code intelligence (e.g., generated code, build artifacts). Use literal paths, not glob patterns.")
                    List<String> excludedDirectories,
            @P(
                            "List of file patterns to exclude. Use '*.ext' for extensions (e.g., '*.svg'), literal names for specific files (e.g., 'package-lock.json'). Do NOT use **/ prefix or duplicate directories.")
                    List<String> excludedFilePatterns,
            @P(
                            "List of modules identified in the project. Each module should have: 'alias' (name), 'relativePath' (path from root), 'language' (e.g. Java, Python), 'buildLintCommand', 'testAllCommand', and 'testSomeCommand'.")
                    List<ModuleBuildEntry> modules) {
        // Validate Mustache templates in command strings before proceeding
        validateCommandTemplateForTool("buildLintCommand", buildLintCommand);
        validateCommandTemplateForTool("testAllCommand", testAllCommand);
        validateCommandTemplateForTool("testSomeCommand", testSomeCommand);

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
            @JsonProperty("alias") String alias,
            @JsonProperty("relativePath") String relativePath,
            @JsonProperty("buildLintCommand") String buildLintCommand,
            @JsonProperty("testAllCommand") String testAllCommand,
            @JsonProperty("testSomeCommand") String testSomeCommand,
            @JsonProperty("language") String language) {

        @JsonCreator
        public ModuleBuildEntry(
                @JsonProperty("alias") @Nullable String alias,
                @JsonProperty("relativePath") @Nullable String relativePath,
                @JsonProperty("buildLintCommand") @Nullable String buildLintCommand,
                @JsonProperty("testAllCommand") @Nullable String testAllCommand,
                @JsonProperty("testSomeCommand") @Nullable String testSomeCommand,
                @JsonProperty("language") @Nullable String language) {
            this.alias = alias != null ? alias : "";
            // Normalize path segments and ensure consistent forward slashes
            String normalized = toUnixPath(
                    Paths.get(relativePath != null ? relativePath : ".").normalize());
            if (normalized.equals(".") || normalized.isEmpty() || normalized.equals("/")) {
                this.relativePath = ".";
            } else {
                // Ensure non-root paths end with / to prevent substring collisions (e.g., "app" vs "appendix")
                this.relativePath = normalized.endsWith("/") ? normalized : normalized + "/";
            }
            this.buildLintCommand = buildLintCommand != null ? buildLintCommand : "";
            this.testAllCommand = testAllCommand != null ? testAllCommand : "";
            this.testSomeCommand = testSomeCommand != null ? testSomeCommand : "";
            this.language = language != null ? language : "";
        }

        public ModuleBuildEntry(
                String alias,
                String relativePath,
                String buildLintCommand,
                String testAllCommand,
                String testSomeCommand) {
            this(alias, relativePath, buildLintCommand, testAllCommand, testSomeCommand, "");
        }
    }

    /** Holds semi-structured information about a project's build process */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BuildDetails(
            String buildLintCommand,
            boolean buildLintEnabled,
            String testAllCommand,
            boolean testAllEnabled,
            String testSomeCommand,
            @JsonDeserialize(as = LinkedHashSet.class) @JsonSetter(nulls = Nulls.AS_EMPTY)
                    Set<String> exclusionPatterns,
            @JsonDeserialize(as = LinkedHashMap.class) @JsonSetter(nulls = Nulls.AS_EMPTY)
                    Map<String, String> environmentVariables,
            @Nullable Integer maxBuildAttempts,
            // blank = do nothing
            String afterTaskListCommand,
            @JsonSetter(nulls = Nulls.AS_EMPTY) List<ModuleBuildEntry> modules) {

        @VisibleForTesting
        public BuildDetails(
                @Nullable String buildLintCommand,
                @Nullable String testAllCommand,
                @Nullable String testSomeCommand,
                @Nullable Set<String> exclusionPatterns) {
            this(
                    buildLintCommand != null ? buildLintCommand : "",
                    true,
                    testAllCommand != null ? testAllCommand : "",
                    true,
                    testSomeCommand != null ? testSomeCommand : "",
                    exclusionPatterns != null ? exclusionPatterns : Set.of(),
                    Map.of(),
                    null,
                    "",
                    List.of());
        }

        public BuildDetails(
                @Nullable String buildLintCommand,
                @Nullable String testAllCommand,
                @Nullable String testSomeCommand,
                @Nullable Set<String> exclusionPatterns,
                @Nullable Map<String, String> environmentVariables) {
            this(
                    buildLintCommand != null ? buildLintCommand : "",
                    true,
                    testAllCommand != null ? testAllCommand : "",
                    true,
                    testSomeCommand != null ? testSomeCommand : "",
                    exclusionPatterns != null ? exclusionPatterns : Set.of(),
                    environmentVariables != null ? environmentVariables : Map.of(),
                    null,
                    "",
                    List.of());
        }

        /** Backward compatibility for legacy callers (e.g. AbstractProject). */
        public BuildDetails(
                @Nullable String buildLintCommand,
                @Nullable String testAllCommand,
                @Nullable String testSomeCommand,
                @Nullable Set<String> exclusionPatterns,
                @Nullable Map<String, String> environmentVariables,
                @Nullable Integer maxBuildAttempts,
                @Nullable String afterTaskListCommand) {
            this(
                    buildLintCommand != null ? buildLintCommand : "",
                    true,
                    testAllCommand != null ? testAllCommand : "",
                    true,
                    testSomeCommand != null ? testSomeCommand : "",
                    exclusionPatterns != null ? exclusionPatterns : Set.of(),
                    environmentVariables != null ? environmentVariables : Map.of(),
                    maxBuildAttempts,
                    afterTaskListCommand != null ? afterTaskListCommand : "",
                    List.of());
        }

        public static final BuildDetails EMPTY =
                new BuildDetails("", false, "", false, "", Set.of(), Map.of(), null, "", List.of());

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
                    modules != null ? modules : List.of());
        }
    }

    // Regex to detect delimiter-change tags: {{= ... =}}
    // These are unsupported and should be flagged
    private static final Pattern DELIMITER_CHANGE_PATTERN = Pattern.compile("\\{\\{=.*?=\\}\\}");

    // Allowed top-level Mustache keys (section variables)
    private static final Set<String> ALLOWED_TOP_LEVEL_KEYS =
            Set.of("files", "classes", "fqclasses", "packages", "pyver");

    // Allowed per-item keys inside sections
    private static final Set<String> ALLOWED_ITEM_KEYS = Set.of(".", "value", "first", "last", "index");

    // Regex to extract Mustache tags: {{name}}, {{{name}}}, {{#name}}, {{/name}}, {{^name}}, {{>name}}, {{!comment}},
    // {{=...=}}
    // Captures the optional prefix character (#, /, ^, >, !) and the tag name
    private static final Pattern MUSTACHE_TAG_PATTERN =
            Pattern.compile("\\{\\{\\{?\\s*([#/^>!]?)\\s*([^}\\s]+)\\s*\\}?\\}\\}");

    /**
     * Extracts all Mustache tag names from a template string.
     * Returns the raw tag names (without prefixes like #, /, ^).
     * Tags with prefixes like > (partials) or ! (comments) are returned with their prefix
     * to indicate they are unsupported advanced features.
     * Delimiter-change tags ({{= ... =}}) are detected separately and returned as "=...".
     */
    @VisibleForTesting
    static Set<String> extractMustacheTags(String template) {
        if (template.isEmpty()) {
            return Set.of();
        }
        var tags = new LinkedHashSet<String>();

        // First, detect delimiter-change tags which have special syntax {{= ... =}}
        var delimiterMatcher = DELIMITER_CHANGE_PATTERN.matcher(template);
        while (delimiterMatcher.find()) {
            // Extract the content between {{= and =}} to include in the error message
            String fullMatch = delimiterMatcher.group();
            // Remove {{= prefix and =}} suffix to get the delimiter specification
            String delimiterSpec =
                    fullMatch.substring(3, fullMatch.length() - 3).trim();
            tags.add("=" + (delimiterSpec.isEmpty() ? "..." : delimiterSpec));
        }

        var matcher = MUSTACHE_TAG_PATTERN.matcher(template);
        while (matcher.find()) {
            String prefix = matcher.group(1);
            String name = matcher.group(2);
            // For partials (>) and comments (!), include the prefix to mark them as unsupported
            if (">".equals(prefix) || "!".equals(prefix)) {
                tags.add(prefix + name);
            } else {
                // For #, /, ^, or no prefix, just use the tag name
                tags.add(name);
            }
        }
        return tags;
    }

    /**
     * Validates that all Mustache tags in a template are in the allowed set.
     *
     * @param template the Mustache template string
     * @param extraAllowedKeys additional keys to allow (e.g., the specific listKey for this call)
     * @return a set of unsupported tags found, empty if all are valid
     */
    @VisibleForTesting
    static Set<String> findUnsupportedMustacheTags(String template, Set<String> extraAllowedKeys) {
        var tags = extractMustacheTags(template);
        if (tags.isEmpty()) {
            return Set.of();
        }

        var allAllowed = new HashSet<>(ALLOWED_TOP_LEVEL_KEYS);
        allAllowed.addAll(ALLOWED_ITEM_KEYS);
        allAllowed.addAll(extraAllowedKeys);

        var unsupported = new LinkedHashSet<String>();
        for (String tag : tags) {
            if (!allAllowed.contains(tag)) {
                unsupported.add(tag);
            }
        }
        return unsupported;
    }

    /**
     * Validates a Mustache template, throwing IllegalArgumentException if unsupported tags are found.
     *
     * @param template the template to validate
     * @param listKey the list key used for this interpolation (added to allowed keys)
     * @throws IllegalArgumentException if unsupported tags are found
     */
    private static void validateMustacheTemplate(String template, String listKey) {
        if (template.isEmpty()) {
            return;
        }
        var unsupported = findUnsupportedMustacheTags(template, Set.of(listKey));
        if (!unsupported.isEmpty()) {
            var allAllowed = new TreeSet<>(ALLOWED_TOP_LEVEL_KEYS);
            allAllowed.addAll(ALLOWED_ITEM_KEYS);
            throw new IllegalArgumentException(
                    "Unsupported Mustache tags: %s. Allowed: %s".formatted(unsupported, allAllowed));
        }
    }

    /**
     * Validates a command template for use in reportBuildDetails tool.
     * Throws ToolCallException with REQUEST_ERROR status if unsupported tags are found.
     *
     * @param fieldName the name of the field being validated (for error messages)
     * @param template the template to validate
     * @throws ToolRegistry.ToolCallException if unsupported tags are found
     */
    private static void validateCommandTemplateForTool(String fieldName, String template) {
        if (template.isEmpty()) {
            return;
        }
        // For tool validation, allow all canonical list keys since we don't know which one will be used
        var unsupported = findUnsupportedMustacheTags(template, Set.of());
        if (!unsupported.isEmpty()) {
            var allAllowed = new TreeSet<>(ALLOWED_TOP_LEVEL_KEYS);
            allAllowed.addAll(ALLOWED_ITEM_KEYS);
            throw new ToolRegistry.ToolCallException(
                    ToolExecutionResult.Status.REQUEST_ERROR,
                    "%s contains unsupported Mustache tags: %s. Allowed: %s"
                            .formatted(fieldName, unsupported, allAllowed));
        }
    }

    /**
     * Interpolates a Mustache template with the given list of items and optional Python version.
     * Supports {@code {{#files}}}, {@code {{#classes}}}, {@code {{#fqclasses}}}, {@code {{#packages}}},
     * and {@code {{pyver}}} variables.
     *
     * <p><strong>Per-item keys (API contract):</strong> Inside a section block, each item exposes:
     * <ul>
     *   <li>{@code {{.}}}  — the string value (via {@code toString()})</li>
     *   <li>{@code {{value}}} — the string value (explicit field)</li>
     *   <li>{@code {{first}}} — boolean, true for the first item</li>
     *   <li>{@code {{last}}} — boolean, true for the last item</li>
     *   <li>{@code {{index}}} — 0-based position in the list</li>
     * </ul>
     * These keys are backed by {@link StringElement}. Changes to the field names or accessor methods
     * constitute an API change and require corresponding test updates.
     */
    public static String interpolateMustacheTemplate(String template, List<String> items, String listKey) {
        return interpolateMustacheTemplate(template, items, listKey, null);
    }

    /**
     * Interpolates a Mustache template with the given list of items and optional Python version.
     * Supports {@code {{#files}}}, {@code {{#classes}}}, {@code {{#fqclasses}}}, {@code {{#packages}}},
     * and {@code {{pyver}}} variables.
     *
     * <p><strong>Per-item keys (API contract):</strong> Inside a section block, each item exposes:
     * <ul>
     *   <li>{@code {{.}}}  — the string value (via {@code toString()})</li>
     *   <li>{@code {{value}}} — the string value (explicit field)</li>
     *   <li>{@code {{first}}} — boolean, true for the first item</li>
     *   <li>{@code {{last}}} — boolean, true for the last item</li>
     *   <li>{@code {{index}}} — 0-based position in the list</li>
     * </ul>
     * These keys are backed by {@link StringElement}. Changes to the field names or accessor methods
     * constitute an API change and require corresponding test updates.
     */
    public static String interpolateMustacheTemplate(
            String template, List<String> items, String listKey, @Nullable String pythonVersion) {
        if (template.isEmpty()) {
            return "";
        }

        // Validate template before compiling
        validateMustacheTemplate(template, listKey);

        MustacheFactory mf = new DefaultMustacheFactory();
        // The "templateName" argument to compile is for caching and error reporting, can be arbitrary.
        Mustache mustache = mf.compile(new StringReader(template), "dynamic_template");

        Map<String, Object> context = new HashMap<>();
        // Mustache.java handles empty lists correctly for {{#section}} blocks (renders zero iterations).
        // Use StringElement wrapper that supports both {{.}} (via toString) and {{value}}/{{first}}/{{last}}/{{index}}
        context.put(listKey, MustacheTemplates.toStringElementList(items));
        context.put("pyver", pythonVersion == null ? "" : pythonVersion);

        StringWriter writer = new StringWriter();
        // This can throw MustacheException, which will propagate as a RuntimeException
        // as per the project's "let it throw" style.
        mustache.execute(writer, context);

        return writer.toString();
    }

    /**
     * Provide default environment variables for the project when the agent reports details:
     * - For Python projects: VIRTUAL_ENV=.venv
     * - Otherwise: no defaults
     */
    private Map<String, String> defaultEnvForProject() {
        if (project.getAnalyzerLanguages().contains(Languages.PYTHON)) {
            return Map.of("VIRTUAL_ENV", ".venv");
        }
        return Map.of();
    }
}
