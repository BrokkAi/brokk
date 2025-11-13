package ai.brokk.agents;

import static java.util.Objects.requireNonNull;

import ai.brokk.AnalyzerUtil;
import ai.brokk.ContextManager;
import ai.brokk.IContextManager;
import ai.brokk.IProject;
import ai.brokk.Llm;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.util.BuildOutputPreprocessor;
import ai.brokk.util.BuildToolConventions;
import ai.brokk.util.BuildToolConventions.BuildSystem;
import ai.brokk.util.Environment;
import ai.brokk.util.EnvironmentPython;
import ai.brokk.util.ExecutorConfig;
import ai.brokk.util.Messages;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

    private final Llm llm;
    private final ToolRegistry globalRegistry;

    // Use standard ChatMessage history
    private final List<ChatMessage> chatHistory = new ArrayList<>();
    private final IProject project;
    // Field to store the result from the reportBuildDetails tool
    private @Nullable BuildDetails reportedDetails = null;
    // Field to store the reason from the abortBuildDetails tool
    private @Nullable String abortReason = null;
    // Field to store directories to exclude from code intelligence
    private List<String> currentExcludedDirectories = new ArrayList<>();

    public BuildAgent(IProject project, Llm llm, ToolRegistry globalRegistry) {
        this.project = project;
        this.llm = llm;
        this.globalRegistry = globalRegistry;
    }

    /**
     * Execute the build information gathering process.
     *
     * @return The gathered BuildDetails record, or EMPTY if the process fails or is interrupted.
     */
    public BuildDetails execute() throws InterruptedException {
        var tr = globalRegistry.builder().register(this).build();

        // build message containing root directory contents
        ToolExecutionRequest initialRequest = ToolExecutionRequest.builder()
                .name("listFiles")
                .arguments("{\"directoryPath\": \".\"}") // Request root dir
                .build();
        ToolExecutionResult initialResult = tr.executeTool(initialRequest);
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
                // Walk the full directory tree to find gitignored directories.
                // Note: This full tree walk is acceptable here because it's a one-time operation
                // at agent startup, not in the hot filtering path. For frequent file filtering
                // operations (like AbstractProject.applyFiltering()), we use cached IgnoreNode
                // with direct path checking instead.
                try (var dirStream = Files.walk(project.getRoot())) {
                    dirStream
                            .filter(Files::isDirectory)
                            .filter(path -> !path.equals(project.getRoot())) // Skip root
                            .map(path -> project.getRoot().relativize(path))
                            .filter(relPath -> !relPath.toString().startsWith(".")) // Skip hidden dirs like .git
                            .filter(relPath -> {
                                // Explicitly check if directory is gitignored using proper gitignore semantics
                                // This prevents false positives from empty or non-code directories
                                return project.isDirectoryIgnored(relPath);
                            })
                            .forEach(relPath -> {
                                var dirName = relPath.toString();
                                this.currentExcludedDirectories.add(dirName);
                                addedFromGitignore.add(dirName);
                            });
                }

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
            var tools = new ArrayList<>(
                    tr.getTools(List.of("listFiles", "searchFilenames", "searchSubstrings", "getFileContents")));
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
                var terminalResult = tr.executeTool(reportRequest);
                if (terminalResult.status() == ToolExecutionResult.Status.SUCCESS) {
                    // The assertion was here, but requireNonNull is more explicit for NullAway
                    return requireNonNull(
                            reportedDetails,
                            "reportedDetails should be non-null after successful reportBuildDetails tool execution");
                } else {
                    // Tool execution failed
                    logger.warn("reportBuildDetails tool execution failed. Error: {}", terminalResult.resultText());
                    chatHistory.add(terminalResult.toExecutionResultMessage());
                    continue;
                }
            } else if (abortRequest != null) {
                var terminalResult = tr.executeTool(abortRequest);
                if (terminalResult.status() == ToolExecutionResult.Status.SUCCESS) {
                    assert abortReason != null;
                    return BuildDetails.EMPTY;
                } else {
                    // Tool execution failed
                    logger.warn("abortBuildDetails tool execution failed. Error: {}", terminalResult.resultText());
                    chatHistory.add(terminalResult.toExecutionResultMessage());
                    continue;
                }
            }

            // 7. Execute Non-Terminal Tools
            // Only proceed if no terminal action was requested this turn
            for (var request : otherRequests) {
                String toolName = request.name();
                logger.trace("Agent action: {} ({})", toolName, request.arguments());
                ToolExecutionResult execResult = tr.executeTool(request);
                ToolExecutionResultMessage resultMessage = execResult.toExecutionResultMessage();

                // Record individual tool result history
                chatHistory.add(resultMessage);
                logger.trace("Tool result added to history: {}", resultMessage.text());
            }
        }
    }

    /** Build the prompt for the LLM, including system message and history. */
    private List<ChatMessage> buildPrompt() {
        List<ChatMessage> messages = new ArrayList<>();

        String wrapperScriptInstruction;
        if (Environment.isWindows()) {
            wrapperScriptInstruction =
                    "Prefer the repository-local *wrapper script* when it exists in the project root (e.g. gradlew.cmd, mvnw.cmd).";
        } else {
            wrapperScriptInstruction =
                    "Prefer the repository-local *wrapper script* when it exists in the project root (e.g. ./gradlew, ./mvnw).";
        }

        // System Prompt
        messages.add(new SystemMessage(
                """
                You are an agent tasked with finding build information for the *development* environment of a software project.
                Your goal is to identify key build commands (clean, compile/build, test all, test specific) and how to invoke those commands correctly.
                Focus *only* on details relevant to local development builds/profiles, explicitly ignoring production-specific
                configurations unless they are the only ones available.

                Use the tools to examine build files (like `pom.xml`, `build.gradle`, etc.), configuration files, and linting files,
                as necessary, to determine the information needed by `reportBuildDetails`.

                When selecting build or test commands, prefer flags or sub-commands that minimise console output (for example, Maven -q, Gradle --quiet, npm test --silent, sbt -error).
                Avoid verbose flags such as --info, --debug, or -X unless they are strictly required for correct operation.

                The lists are DecoratedCollection instances, so you get first/last/index/value fields.
                Examples:

                | Build tool        | One-liner a user could write
                | ----------------- | ------------------------------------------------------------------------
                | **SBT**           | `sbt -error "testOnly{{#fqclasses}} {{value}}{{/fqclasses}}"`
                | **Maven**         | `mvn --quiet test -Dtest={{#classes}}{{value}}{{^-last}},{{/-last}}{{/classes}}`
                | **Gradle**        | `gradle --quiet test{{#classes}} --tests {{value}}{{/classes}}`
                | **Go**            | `go test -run '{{#classes}}{{value}}{{^-last}} | {{/-last}}{{/classes}}`
                | **.NET CLI**      | `dotnet test --filter "{{#classes}}FullyQualifiedName\\~{{value}}{{^-last}} | {{/-last}}{{/classes}}"`
                | **pytest**        | `uv sync && pytest {{#files}}{{value}}{{^-last}} {{/-last}}{{/files}}`
                | **Jest**          | `jest {{#files}}{{value}}{{^-last}} {{/-last}}{{/files}}`

                %s
                Only fall back to the bare command (`gradle`, `mvn` …) when no wrapper script is present.

                A baseline set of excluded directories has been established from build conventions and .gitignore.
                When you use `reportBuildDetails`, the `excludedDirectories` parameter should contain *additional* directories
                you identify that should be excluded from code intelligence, beyond this baseline.
                IMPORTANT: Only provide literal directory paths. DO NOT use glob patterns (e.g., "**/target", "**/.idea"),
                these are already handled by .gitignore processing.

                Remember to request the `reportBuildDetails` tool to finalize the process ONLY once all information is collected.
                The reportBuildDetails tool expects exactly four parameters: buildLintCommand, testAllCommand, testSomeCommand, and excludedDirectories.
                """
                        .formatted(wrapperScriptInstruction)));

        // Add existing history
        messages.addAll(chatHistory);

        // Add final user message indicating the goal (redundant with system prompt but reinforces)
        messages.add(
                new UserMessage(
                        "Gather the development build details based on the project files and previous findings. Use the available tools to explore and collect the information, then report it using 'reportBuildDetails'."));

        return messages;
    }

    @Tool("Report the gathered build details when ALL information is collected. DO NOT call this method before then.")
    public String reportBuildDetails(
            @P(
                            "Command to build or lint incrementally, e.g. mvn compile, cargo check, pyflakes. If a linter is not clearly in use, don't guess! it will cause problems; just leave it blank.")
                    String buildLintCommand,
            @P(
                            "Command to run all tests. If no test framework is clearly in use, don't guess! it will cause problems; just leave it blank.")
                    String testAllCommand,
            @P(
                            "Command template to run specific tests using Mustache templating. Should use either a {{classes}}, {{fqclasses}}, or a {{files}} variable. Again, if no class- or file- based framework is in use, leave it blank.")
                    String testSomeCommand,
            @P("List of directories to exclude from code intelligence (e.g., generated code, build artifacts)")
                    List<String> excludedDirectories) {
        // Combine baseline excluded directories with those suggested by the LLM
        // Filter out glob patterns defensively even though the prompt instructs against them
        var finalExcludes = Stream.concat(this.currentExcludedDirectories.stream(), excludedDirectories.stream())
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> !containsGlobPattern(s))
                .map(s -> Path.of(s).normalize())
                .map(Path::toString)
                .collect(Collectors.toSet());

        this.reportedDetails = new BuildDetails(
                buildLintCommand, testAllCommand, testSomeCommand, finalExcludes, defaultEnvForProject());
        logger.debug(
                "reportBuildDetails tool executed, details captured. Final excluded directories: {}", finalExcludes);
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

    private static boolean containsGlobPattern(String s) {
        return s.contains("*") || s.contains("?") || s.contains("[") || s.contains("]");
    }

    /** Holds semi-structured information about a project's build process */
    public record BuildDetails(
            String buildLintCommand,
            String testAllCommand,
            String testSomeCommand,
            Set<String> excludedDirectories,
            @JsonSetter(nulls = Nulls.AS_EMPTY) Map<String, String> environmentVariables) {

        @VisibleForTesting
        BuildDetails(
                String buildLintCommand,
                String testAllCommand,
                String testSomeCommand,
                Set<String> excludedDirectories) {
            this(buildLintCommand, testAllCommand, testSomeCommand, excludedDirectories, Map.of());
        }

        public static final BuildDetails EMPTY = new BuildDetails("", "", "", Set.of(), Map.of());
    }

    /** Determine the best verification command using the provided Context (no reliance on CM.topContext()). */
    public static @Nullable String determineVerificationCommand(Context ctx) throws InterruptedException {
        var cm = ctx.getContextManager();

        // Retrieve build details from the project associated with the ContextManager
        BuildDetails details = cm.getProject().awaitBuildDetails();

        if (details.equals(BuildDetails.EMPTY)) {
            logger.warn("No build details available, cannot determine verification command.");
            return null;
        }

        // Check project setting for test scope
        IProject.CodeAgentTestScope testScope = cm.getProject().getCodeAgentTestScope();
        if (testScope == IProject.CodeAgentTestScope.ALL) {
            String cmd = details.testAllCommand();
            logger.debug("Code Agent Test Scope is ALL, using testAllCommand: {}", cmd);
            return interpolateCommandWithPythonVersion(cmd, cm.getProject().getRoot());
        }

        // Proceed with workspace-specific test determination (based on the provided Context)
        logger.debug("Code Agent Test Scope is WORKSPACE, determining tests in workspace (Context-based).");

        // Get ProjectFiles from editable and read-only fragments
        var projectFilesFromEditableOrReadOnly =
                ctx.fileFragments().flatMap(fragment -> fragment.files().stream()); // No analyzer

        // Get ProjectFiles specifically from SkeletonFragments among all virtual fragments
        var projectFilesFromSkeletons = ctx.virtualFragments()
                .filter(vf -> vf.getType() == ContextFragment.FragmentType.SKELETON)
                .flatMap(skeletonFragment -> skeletonFragment.files().stream()); // No analyzer

        // Combine all relevant ProjectFiles into a single set for checking against test files
        var workspaceFiles = Stream.concat(projectFilesFromEditableOrReadOnly, projectFilesFromSkeletons)
                .collect(Collectors.toSet());

        // Check if any of the identified project test files are present in the current workspace set
        var workspaceTestFiles =
                workspaceFiles.stream().filter(ContextManager::isTestFile).toList();

        // Decide which command to use
        if (workspaceTestFiles.isEmpty()) {
            var summaries = ContextFragment.describe(ctx.allFragments());
            logger.debug(
                    "No relevant test files found for {} with Workspace {}; using build/lint command: {}",
                    cm.getProject().getRoot(),
                    summaries,
                    details.buildLintCommand());
            return interpolateCommandWithPythonVersion(
                    details.buildLintCommand(), cm.getProject().getRoot());
        }

        return getBuildLintSomeCommand(cm, details, workspaceTestFiles);
    }

    /** Backwards-compatible shim using CM.liveContext(). Prefer the Context-based overload. */
    public static @Nullable String determineVerificationCommand(IContextManager cm) throws InterruptedException {
        return determineVerificationCommand(cm.liveContext());
    }

    /**
     * Runs {@link #determineVerificationCommand(IContextManager)} on the {@link ContextManager} background pool and
     * delivers the result asynchronously.
     *
     * @return a {@link CompletableFuture} that completes on the background thread.
     */
    public static CompletableFuture<@Nullable String> determineVerificationCommandAsync(ContextManager cm) {
        return cm.submitBackgroundTask("Determine build verification command", () -> determineVerificationCommand(cm));
    }

    /**
     * Determine and interpolate the "run some tests" command for the current workspace.
     * Supports files-based, classes-based, fqclasses-based, and modules-based templates.
     * If the template contains {{#modules}}, this will convert selected test files into
     * dotted module labels relative to a detected module anchor:
     *  1) Parent of any hardcoded *.py runner mentioned in the configured commands,
     *  2) A top-level "tests/" directory if present,
     *  3) The import root of each file established by walking up until no __init__.py.
     */
    public static String getBuildLintSomeCommand(
            IContextManager cm, BuildDetails details, Collection<ProjectFile> workspaceTestFiles)
            throws InterruptedException {

        String testSomeTemplate = details.testSomeCommand();

        boolean isFilesBased = testSomeTemplate.contains("{{#files}}");
        boolean isFqBased = testSomeTemplate.contains("{{#fqclasses}}");
        boolean isClassesBased = testSomeTemplate.contains("{{#classes}}") || isFqBased;
        boolean isModulesBased = testSomeTemplate.contains("{{#modules}}");

        if (!isFilesBased && !isClassesBased && !isModulesBased) {
            logger.debug(
                    "Template lacks {{#files}}, {{#classes}}, or {{#modules}}; using build/lint: {}",
                    details.buildLintCommand());
            return details.buildLintCommand();
        }

        final Path projectRoot = cm.getProject().getRoot();
        String pythonVersion = getPythonVersionForProject(projectRoot);

        List<String> targetItems;

        if (isModulesBased) {
            Path anchor = detectModuleAnchor(projectRoot, details).orElse(null);
            targetItems = workspaceTestFiles.stream()
                    .map(pf -> toPythonModuleLabel(projectRoot, anchor, Path.of(pf.toString())))
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .sorted()
                    .toList();

            if (targetItems.isEmpty()) {
                logger.debug("No modules derived; falling back to build/lint: {}", details.buildLintCommand());
                return details.buildLintCommand();
            }

            logger.debug(
                    "Using modules-based template with {} modules (anchor={})",
                    targetItems.size(),
                    anchor == null ? "<inferred import roots>" : anchor);
            return interpolateMustacheTemplate(testSomeTemplate, targetItems, "modules", pythonVersion);
        }

        if (isFilesBased) {
            targetItems = workspaceTestFiles.stream().map(ProjectFile::toString).toList();
            logger.debug("Using files-based template with {} files", targetItems.size());
            return interpolateMustacheTemplate(testSomeTemplate, targetItems, "files", pythonVersion);
        }

        IAnalyzer analyzer = cm.getAnalyzer();

        if (analyzer.isEmpty()) {
            logger.warn("Analyzer is empty; falling back to build/lint: {}", details.buildLintCommand());
            return details.buildLintCommand();
        }

        var codeUnits = AnalyzerUtil.testFilesToCodeUnits(analyzer, workspaceTestFiles);
        if (isFqBased) {
            targetItems = codeUnits.stream().map(CodeUnit::fqName).sorted().toList();
            if (targetItems.isEmpty()) {
                logger.debug("No fqclasses derived; falling back to build/lint: {}", details.buildLintCommand());
                return details.buildLintCommand();
            }
            logger.debug("Using fqclasses-based template with {} entries", targetItems.size());
            return interpolateMustacheTemplate(testSomeTemplate, targetItems, "fqclasses", pythonVersion);
        } else {
            targetItems = codeUnits.stream().map(CodeUnit::identifier).sorted().toList();
            if (targetItems.isEmpty()) {
                logger.debug("No classes derived; falling back to build/lint: {}", details.buildLintCommand());
                return details.buildLintCommand();
            }
            logger.debug("Using classes-based template with {} entries", targetItems.size());
            return interpolateMustacheTemplate(testSomeTemplate, targetItems, "classes", pythonVersion);
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

        String s = rel.toString().replace('\\', '/');
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
    public static String runVerification(IContextManager cm) throws InterruptedException {
        var interrupted = new AtomicReference<InterruptedException>(null);
        var updated = cm.pushContext(ctx -> {
            try {
                return runVerification(ctx);
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
    public static Context runVerification(Context ctx) throws InterruptedException {
        var cm = ctx.getContextManager();
        var io = cm.getIo();

        var verificationCommand = determineVerificationCommand(ctx);
        if (verificationCommand == null || verificationCommand.isBlank()) {
            io.llmOutput("\nNo verification command specified, skipping build/check.", ChatMessageType.CUSTOM);
            return ctx; // unchanged
        }

        boolean noConcurrentBuilds = "true".equalsIgnoreCase(System.getenv("BRK_NO_CONCURRENT_BUILDS"));
        if (noConcurrentBuilds) {
            var lock = acquireBuildLock(cm);
            if (lock == null) {
                logger.warn("Failed to acquire build lock; proceeding without it");
                return runBuildAndUpdateFragmentInternal(ctx, verificationCommand);
            }
            try (var ignored = lock) {
                logger.debug("Acquired build lock {}", lock.lockFile());
                return runBuildAndUpdateFragmentInternal(ctx, verificationCommand);
            } catch (Exception e) {
                logger.warn("Exception while using build lock {}; proceeding without it", lock.lockFile(), e);
                return runBuildAndUpdateFragmentInternal(ctx, verificationCommand);
            }
        } else {
            return runBuildAndUpdateFragmentInternal(ctx, verificationCommand);
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
    private static Context runBuildAndUpdateFragmentInternal(Context ctx, String verificationCommand)
            throws InterruptedException {
        var cm = ctx.getContextManager();
        var io = cm.getIo();

        io.llmOutput("\nRunning verification command: " + verificationCommand, ChatMessageType.CUSTOM);
        String shellLang = ExecutorConfig.getShellLanguageFromProject(cm.getProject());
        io.llmOutput("\n```" + shellLang + "\n", ChatMessageType.CUSTOM);
        try {
            var details = cm.getProject().awaitBuildDetails();
            var envVars = details.environmentVariables();
            var execCfg = ExecutorConfig.fromProject(cm.getProject());

            var output = Environment.instance.runShellCommand(
                    verificationCommand,
                    cm.getProject().getRoot(),
                    line -> io.llmOutput(line + "\n", ChatMessageType.CUSTOM),
                    Environment.UNLIMITED_TIMEOUT,
                    execCfg,
                    envVars);
            io.llmOutput("\n```", ChatMessageType.CUSTOM);

            logger.debug("Verification command successful. Output: {}", output);
            return ctx.withBuildResult(true, "Build succeeded.");
        } catch (Environment.SubprocessException e) {
            io.llmOutput("\n```", ChatMessageType.CUSTOM); // Close the markdown block

            String rawBuild = e.getMessage() + "\n\n" + e.getOutput();
            String processed = BuildOutputPreprocessor.processForLlm(rawBuild, cm);
            return ctx.withBuildResult(false, "Build output:\n" + processed);
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
