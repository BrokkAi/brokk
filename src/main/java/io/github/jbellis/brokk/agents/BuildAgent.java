package io.github.jbellis.brokk.agents;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ToolChoice;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.tools.ToolExecutionResult;
import io.github.jbellis.brokk.tools.ToolRegistry;
import io.github.jbellis.brokk.util.BuildToolConventions;
import io.github.jbellis.brokk.util.BuildToolConventions.BuildSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

/**
 * The BuildAgent class is responsible for executing a process to gather and report build details
 * for a software project's development environment. It interacts with tools, processes project files,
 * and uses an LLM to identify build commands, test configurations, and exclusions, ultimately
 * providing structured build information or aborting if unsupported.
 */
public class BuildAgent {
    private static final Logger logger = LogManager.getLogger(BuildAgent.class);

    private final Llm llm;
    private final ToolRegistry toolRegistry;

    // Use standard ChatMessage history
    private final List<ChatMessage> chatHistory = new ArrayList<>();
    private final IProject project;
    // Field to store the result from the reportBuildDetails tool
    private @Nullable BuildDetails reportedDetails = null;
    // Field to store the reason from the abortBuildDetails tool
    private @Nullable String abortReason = null;
    // Field to store directories to exclude from code intelligence
    private List<String> currentExcludedDirectories = new ArrayList<>();

    public BuildAgent(IProject project, Llm llm, ToolRegistry toolRegistry) {
        this.project = project;
        assert llm != null : "coder cannot be null";
        assert toolRegistry != null : "toolRegistry cannot be null";
        this.llm = llm;
        this.toolRegistry = toolRegistry;
    }

    /**
     * Execute the build information gathering process.
     *
     * @return The gathered BuildDetails record, or EMPTY if the process fails or is interrupted.
     */
    public BuildDetails execute() throws InterruptedException {
        // 1. Initial step: List files in the root directory to give the agent a starting point
        ToolExecutionRequest initialRequest = ToolExecutionRequest.builder()
                .name("listFiles")
                .arguments("{\"directoryPath\": \".\"}") // Request root dir
                .build();
        ToolExecutionResult initialResult = toolRegistry.executeTool(this, initialRequest);
        ToolExecutionResultMessage initialResultMessage = initialResult.toExecutionResultMessage();

        // Add the initial result to history (forge an AI request to make inflexible LLMs happy)
        chatHistory.add(new UserMessage("Start by examining the project root directory."));
        chatHistory.add(new AiMessage(List.of(ToolExecutionRequest.builder().name("listFiles").arguments("{\"directoryPath\": \".\"}").build())));
        chatHistory.add(initialResultMessage);
        logger.trace("Initial tool result added to history: {}", initialResultMessage.text());

        // Determine build system and set initial excluded directories
        var files = project.getAllFiles().stream().parallel()
                .filter(f -> f.getParent().equals(Path.of("")))
                .map(ProjectFile::toString)
                .toList();
        BuildSystem detectedSystem = BuildToolConventions.determineBuildSystem(files);
        this.currentExcludedDirectories = new ArrayList<>(BuildToolConventions.getDefaultExcludes(detectedSystem));
        logger.info("Determined build system: {}. Initial excluded directories: {}", detectedSystem, this.currentExcludedDirectories);

        // Add exclusions from .gitignore
        var repo = project.getRepo();
        if (repo instanceof GitRepo gitRepo) {
            var ignoredPatterns = gitRepo.getIgnoredPatterns();
            var addedFromGitignore = new ArrayList<String>();
            for (var pattern : ignoredPatterns) {
                Path path;
                try {
                    path = project.getRoot().resolve(pattern);
                } catch (InvalidPathException e) {
                    // for now we only support literal paths, not globs
                    continue;
                }
                // include non-existing paths if they end with `/` in case they get created later
                var isDirectory = (Files.exists(path) && Files.isDirectory(path)) || pattern.endsWith("/");
                if (!pattern.startsWith("!") && isDirectory) {
                    this.currentExcludedDirectories.add(pattern);
                    addedFromGitignore.add(pattern);
                }
            }
            if (!addedFromGitignore.isEmpty()) {
                logger.debug("Added the following directory patterns from .gitignore to excluded directories: {}", addedFromGitignore);
            }

        } else {
            logger.debug("No .git directory found at project root. Skipping .gitignore processing for excluded directories.");
        }

        // 2. Iteration Loop
        while (true) {
            // 3. Build Prompt
            List<ChatMessage> messages = buildPrompt();

            // 4. Add tools
            // Get specifications for ALL tools the agent might use in this turn.
            var tools = new ArrayList<>(toolRegistry.getRegisteredTools(List.of("listFiles", "searchFilenames", "searchSubstrings", "getFileContents")));
            if (chatHistory.size() > 1) {
                // allow terminal tools
                tools.addAll(toolRegistry.getTools(this, List.of("reportBuildDetails", "abortBuildDetails")));
            }

            // Make the LLM request
            Llm.StreamingResult result;
            try {
                result = llm.sendRequest(messages, tools, ToolChoice.REQUIRED, false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Unexpected request cancellation in build agent");
                return BuildDetails.EMPTY;
            }

            if (result.error() != null) {
                logger.error("LLM error in BuildInfoAgent: " + result.error().getMessage());
                return BuildDetails.EMPTY;
            }

            var aiMessage = ToolRegistry.removeDuplicateToolRequests(result.originalMessage());
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

            // 6. Execute Terminal Actions via ToolRegistry (if any)
            if (reportRequest != null) {
                var terminalResult = toolRegistry.executeTool(this, reportRequest);
                if (terminalResult.status() == ToolExecutionResult.Status.SUCCESS) {
                    // The assertion was here, but requireNonNull is more explicit for NullAway
                    return requireNonNull(reportedDetails, "reportedDetails should be non-null after successful reportBuildDetails tool execution");
                } else {
                    // Tool execution failed
                    logger.warn("reportBuildDetails tool execution failed. Error: {}", terminalResult.resultText());
                    chatHistory.add(terminalResult.toExecutionResultMessage());
                    continue;
                }
            } else if (abortRequest != null) {
                var terminalResult = toolRegistry.executeTool(this, abortRequest);
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
                logger.trace(String.format("Agent action: %s (%s)", toolName, request.arguments()));
                ToolExecutionResult execResult = toolRegistry.executeTool(this, request);
                ToolExecutionResultMessage resultMessage = execResult.toExecutionResultMessage();

                // Record individual tool result history
                chatHistory.add(resultMessage);
                logger.trace("Tool result added to history: {}", resultMessage.text());
            }
        }
    }

    /**
     * Build the prompt for the LLM, including system message and history.
     */
    private List<ChatMessage> buildPrompt() {
        List<ChatMessage> messages = new ArrayList<>();

        // System Prompt
        messages.add(new SystemMessage("""
                                       You are an agent tasked with finding build information for the *development* environment of a software project.
                                       Your goal is to identify key build commands (clean, compile/build, test all, test specific) and how to invoke those commands correctly.
                                       Focus *only* on details relevant to local development builds/profiles, explicitly ignoring production-specific
                                       configurations unless they are the only ones available.
                                       
                                       Use the tools to examine build files (like `pom.xml`, `build.gradle`, etc.), configuration files, and linting files,
                                       as necessary, to determine the information needed by `reportBuildDetails`.
                                       
                                       For the `testSomeCommand` parameter, use Mustache templating with either {{classes}} or {{files}} variables. Examples:
                                       
                                       | Build tool        | One-liner a user could write
                                       | ----------------- | ------------------------------------------------------------------------
                                       | **SBT**           | `sbt "testOnly{{#classes}} {{.}}{{/classes}}"`
                                       | **Maven**         | `mvn test -Dtest={{#classes}}{{.}}{{^-last}},{{/-last}}{{/classes}}`
                                       | **Gradle**        | `gradle test{{#classes}} --tests {{.}}{{/classes}}`
                                       | **Go**            | `go test -run '{{#classes}}{{.}}{{^-last}} | {{/-last}}{{/classes}}`
                                       | **.NET CLI**      | `dotnet test --filter "{{#classes}}FullyQualifiedName\\~{{.}}{{^-last}} | {{/-last}}{{/classes}}"`
                                       | **pytest**        | `pytest {{#files}}{{.}}{{^-last}} {{/-last}}{{/files}}`
                                       | **Jest**          | `jest {{#files}}{{.}}{{^-last}} {{/-last}}{{/files}}`
                                       
                                       A baseline set of excluded directories has been established from build conventions and .gitignore.
                                       When you use `reportBuildDetails`, the `excludedDirectories` parameter should contain *additional* directories
                                       you identify that should be excluded from code intelligence, beyond this baseline.
                                       
                                       Remember to request the `reportBuildDetails` tool to finalize the process ONLY once all information is collected.
                                       The reportBuildDetails tool expects exactly four parameters: buildLintCommand, testAllCommand, testSomeCommand, and excludedDirectories.
                                       """.stripIndent()));

        // Add existing history
        messages.addAll(chatHistory);

        // Add final user message indicating the goal (redundant with system prompt but reinforces)
        messages.add(new UserMessage("Gather the development build details based on the project files and previous findings. Use the available tools to explore and collect the information, then report it using 'reportBuildDetails'."));

        return messages;
    }

    @Tool(value = "Report the gathered build details when ALL information is collected. DO NOT call this method before then.")
    public String reportBuildDetails(
            @P("Command to build or lint incrementally, e.g. mvn compile, cargo check, pyflakes. If a linter is not clearly in use, don't guess! it will cause problems; just leave it blank.") String buildLintCommand,
            @P("Command to run all tests. If no test framework is clearly in use, don't guess! it will cause problems; just leave it blank.") String testAllCommand,
            @P("Command template to run specific tests using Mustache templating. Should use either a {{classes}} or a {{files}} variable. Again, if no class- or file- based framework is in use, leave it blank.") String testSomeCommand,
            @P("List of directories to exclude from code intelligence (e.g., generated code, build artifacts)") List<String> excludedDirectories
        ) {
        // Combine baseline excluded directories with those suggested by the LLM
        var finalExcludes = Stream.concat(this.currentExcludedDirectories.stream(), excludedDirectories.stream())
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> Path.of(s).normalize())
                .map(Path::toString)
                .collect(Collectors.toSet());

        this.reportedDetails = new BuildDetails(buildLintCommand, testAllCommand, testSomeCommand, finalExcludes);
        logger.debug("reportBuildDetails tool executed, details captured. Final excluded directories: {}", finalExcludes);
        return "Build details report received and processed.";
    }

    @Tool(value = "Abort the process if you cannot determine the build details or the project structure is unsupported.")
    public String abortBuildDetails(
            @P("Explanation of why the build details cannot be determined") String explanation
         ) {
        // Store the explanation in the agent's field
        this.abortReason = explanation;
        logger.debug("abortBuildDetails tool executed with explanation: {}", explanation);
        return "Abort signal received and processed.";
    }

    /** Holds semi-structured information about a project's build process */
    public record BuildDetails(String buildLintCommand,
                               String testAllCommand,
                               String testSomeCommand,
                               Set<String> excludedDirectories)
    {
        public BuildDetails {
            requireNonNull(buildLintCommand);
            requireNonNull(testAllCommand);
            requireNonNull(testSomeCommand);
            requireNonNull(excludedDirectories);
        }

        public static final BuildDetails EMPTY = new BuildDetails("", "", "", Set.of());
    }

    /**
     * Asynchronously determines the best verification command based on the user goal,
     * workspace summary, and stored BuildDetails.
     * Runs on the ContextManager's background task executor.
     * Determines the command by checking for relevant test files in the workspace
     * and the availability of a specific test command in BuildDetails.
     *
     * @param cm The ContextManager instance.
     * @return A CompletableFuture containing the suggested verification command string (either specific test command or build/lint command),
     * or null if BuildDetails are unavailable.
     */
    static @Nullable String determineVerificationCommand(IContextManager cm) {
        // Retrieve build details from the project associated with the ContextManager
        BuildDetails details = cm.getProject().loadBuildDetails();

        if (details.equals(BuildDetails.EMPTY)) {
            logger.warn("No build details available, cannot determine verification command.");
            return null;
        }

        // Check project setting for test scope
        IProject.CodeAgentTestScope testScope = cm.getProject().getCodeAgentTestScope();
        if (testScope == IProject.CodeAgentTestScope.ALL) {
            logger.debug("Code Agent Test Scope is ALL, using testAllCommand: {}", details.testAllCommand());
            return details.testAllCommand();
        }

        // Proceed with workspace-specific test determination
        logger.debug("Code Agent Test Scope is WORKSPACE, determining tests in workspace.");

        // Get ProjectFiles from editable and read-only fragments
        var topContext = requireNonNull(cm.topContext());
        var projectFilesFromEditableOrReadOnly = Stream.concat(
                        topContext.editableFiles(),
                        topContext.readonlyFiles()
                )
                .flatMap(fragment -> fragment.files().stream()); // No analyzer

        // Get ProjectFiles specifically from SkeletonFragments among all virtual fragments
        var projectFilesFromSkeletons = topContext.virtualFragments()
                .filter(vf -> vf.getType() == ContextFragment.FragmentType.SKELETON)
                .flatMap(skeletonFragment -> skeletonFragment.files().stream()); // No analyzer

        // Combine all relevant ProjectFiles into a single set for checking against test files
        var workspaceFiles =
                Stream.concat(projectFilesFromEditableOrReadOnly, projectFilesFromSkeletons)
                        .collect(Collectors.toSet());

        // Check if any of the identified project test files are present in the current workspace set
        var projectTestFiles = cm.getTestFiles();
        var workspaceTestFiles = projectTestFiles.stream()
                .filter(workspaceFiles::contains)
                .toList();

        // Decide which command to use
        if (workspaceTestFiles.isEmpty()) {
            logger.debug("No relevant test files found in workspace, using build/lint command: {}", details.buildLintCommand());
            return details.buildLintCommand();
        }

        // Determine if template is files-based or classes-based
        String testSomeTemplate = details.testSomeCommand();
        boolean isFilesBased = testSomeTemplate.contains("{{#files}}");
        boolean isClassesBased = testSomeTemplate.contains("{{#classes}}");

        if (!isFilesBased && !isClassesBased) {
            logger.debug("Test template doesn't use {{#files}} or {{#classes}}, using build/lint command: {}", details.buildLintCommand());
            return details.buildLintCommand();
        }

        List<String> targetItems;
        if (isFilesBased) {
            // Use file paths directly
            targetItems = workspaceTestFiles.stream()
                    .map(ProjectFile::toString)
                    .toList();
            logger.debug("Using files-based template with {} files", targetItems.size());
        } else { // isClassesBased
            IAnalyzer analyzer;
            try {
                analyzer = cm.getAnalyzer();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Interrupted while retrieving analyzer");
            }

            if (analyzer.isEmpty()) {
                logger.warn("Analyzer is empty; falling back to build/lint command: {}", details.buildLintCommand());
                return details.buildLintCommand();
            }

            targetItems = AnalyzerUtil.testFilesToFQCNs(analyzer, workspaceTestFiles);
            if (targetItems.isEmpty()) {
                logger.debug("No classes found in workspace test files for class-based template, using build/lint command: {}", details.buildLintCommand());
                return details.buildLintCommand();
            }
            logger.debug("Using classes-based template with {} classes", targetItems.size());
        }

        // Perform simple template interpolation
        String interpolatedCommand = interpolateMustacheTemplate(testSomeTemplate, targetItems, isFilesBased);
        logger.debug("Interpolated test command: '{}'", interpolatedCommand);
        return interpolatedCommand;
    }

    /**
     * Interpolates a Mustache template with the given list of items.
     * Supports {{files}} and {{classes}} variables with {{^-last}} separators.
     */
    private static String interpolateMustacheTemplate(String template, List<String> items, boolean isFilesBased) {
        if (template == null || template.isEmpty()) {
            return "";
        }

        MustacheFactory mf = new DefaultMustacheFactory();
        // The "templateName" argument to compile is for caching and error reporting, can be arbitrary.
        Mustache mustache = mf.compile(new StringReader(template), "dynamic_template");

        Map<String, Object> context = new HashMap<>();
        String listKey = isFilesBased ? "files" : "classes";
        // Mustache.java handles null or empty lists correctly for {{#section}} blocks.
        context.put(listKey, items);

        StringWriter writer = new StringWriter();
        // This can throw MustacheException, which will propagate as a RuntimeException
        // as per the project's "let it throw" style.
        mustache.execute(writer, context);

        return writer.toString();
    }
}
