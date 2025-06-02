package io.github.jbellis.brokk.agents;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ToolChoice;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.Llm;
import io.github.jbellis.brokk.Project;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.tools.ToolExecutionResult;
import io.github.jbellis.brokk.tools.ToolRegistry;
import io.github.jbellis.brokk.util.BuildToolConventions;
import io.github.jbellis.brokk.util.BuildToolConventions.BuildSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An agent that iteratively explores a codebase using specific tools
 * to extract information relevant to the *development* build process.
 */
public class BuildAgent {
    private static final Logger logger = LogManager.getLogger(BuildAgent.class);

    private final Llm llm;
    private final ToolRegistry toolRegistry;

    // Use standard ChatMessage history
    private final List<ChatMessage> chatHistory = new ArrayList<>();
    private final Project project;
    // Field to store the result from the reportBuildDetails tool
    private BuildDetails reportedDetails = null;
    // Field to store the reason from the abortBuildDetails tool
    private String abortReason = null;
    // Field to store directories to exclude from code intelligence
    private List<String> currentExcludedDirectories = new ArrayList<>();

    public BuildAgent(Project project, Llm llm, ToolRegistry toolRegistry) {
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
    @NotNull
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
                if (!(pattern.startsWith("!")) && isDirectory) {
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
            var response = result.chatResponse();
            if (response == null || response.aiMessage() == null || !response.aiMessage().hasToolExecutionRequests()) {
                // This shouldn't happen with ToolChoice.REQUIRED and Coder retries, but handle defensively.
                logger.error("LLM response did not contain expected tool call in BuildInfoAgent.");
                return BuildDetails.EMPTY;
            }

            var aiMessage = ToolRegistry.removeDuplicateToolRequests(response.aiMessage());
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
                    assert reportedDetails != null;
                    return reportedDetails;
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
        Your goal is to identify dependencies, plugins,repositories, development profile details, key build commands
        (clean, compile/build, test all, test specific), how to invoke those commands correctly, and the main application entry point.
        Focus *only* on details relevant to local development builds/profiles, explicitly ignoring production-specific
        configurations unless they are the only ones available.

        Use the tools to examine build files (like `pom.xml`, `build.gradle`, etc.), configuration files, and linting files.
        The information you gather will be handed off to agents that do not have access to these files, so
        make sure your instructions are comprehensive.

        A baseline set of excluded directories has been established from build conventions and .gitignore.
        When you use `reportBuildDetails`, the `excludedDirectories` parameter should contain *additional* directories
        you identify that should be excluded from code intelligence, beyond this baseline.
        
        Remember to request the `reportBuildDetails` tool to finalize the process ONLY once all information is collected.
        """.stripIndent()));

        // Add existing history
        messages.addAll(chatHistory);

        // Add final user message indicating the goal (redundant with system prompt but reinforces)
        messages.add(new UserMessage("Gather the development build details based on the project files and previous findings. Use the available tools to explore and collect the information, then report it using 'reportBuildDetails'."));

        return messages;
    }

    @Tool(value = "Report the gathered build details when ALL information is collected. DO NOT call this method before then.")
    public String reportBuildDetails(
            @P("List of build files involved in the build, including module build files") List<String> buildfiles,
            @P("List of identified third-party dependencies") List<String> dependencies,
            @P("Command to build or lint incrementally, e.g. mvn compile, cargo check, pyflakes. If a linter is not clearly in use, don't guess! it will cause problems; just leave it blank.") String buildLintCommand,
            @P("Command to run all tests. If no test framework is clearly in use, don't guess! it will cause problems; just leave it blank.") String testAllCommand,
            @P("""
               Instructions and details about the build process, including environment configurations
               and any idiosyncracies observed. Include information on how to run other test configurations, especially
               individual tests but also at other levels e.g. package or namespace;
               also include information on other pre-commit tools like code formatters or static analysis tools.
               """) String instructions,
            @P("List of directories to exclude from code intelligence (e.g., generated code, build artifacts)") List<String> excludedDirectories
        ) {
            // Combine baseline excluded directories with those suggested by the LLM
            var finalExcludes = Stream.concat(this.currentExcludedDirectories.stream(), excludedDirectories.stream())
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> Path.of(s).normalize())
                    .map(Path::toString)
                    .collect(Collectors.toSet());

            this.reportedDetails = new BuildDetails(buildfiles, dependencies, buildLintCommand, testAllCommand, instructions, finalExcludes);
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

    /**
     * Removes markdown code block syntax (single and triple backticks) from a string.
     *
     * @param text The string to process.
     * @return The string with markdown code blocks removed, or an empty string if input is null/blank.
     */
    private static String unmarkdown(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmedText = text.trim();

        // Check for triple backticks (e.g., ```text``` or ```lang\ntext```)
        if (trimmedText.startsWith("```") && trimmedText.endsWith("```") && trimmedText.length() >= 6) {
            String content = trimmedText.substring(3, trimmedText.length() - 3);
            int firstNewline = content.indexOf('\n');

            if (firstNewline == 0) { // Starts with a newline, e.g. ```\ncode\n```
                content = content.substring(1); // Remove the leading newline
            } else if (firstNewline > 0) { // Has a newline, content before it could be lang specifier
                // Assumes the first line is a language specifier if present and removes it
                content = content.substring(firstNewline + 1);
            }

            // fall through to single-backtick check
            trimmedText = content.trim();
        }

        // Check for single backticks (e.g., `text`)
        if (trimmedText.startsWith("`") && trimmedText.endsWith("`") && trimmedText.length() >= 2) {
            return trimmedText.substring(1, trimmedText.length() - 1).trim();
        }

        return trimmedText;
    }

    /** Holds semi-structured information about a project's build process */
    public record BuildDetails(List<String> buildFiles,
                               List<String> dependencies,
                               String buildLintCommand,
                               String testAllCommand,
                               String instructions,
                               Set<String> excludedDirectories)
    {
        public BuildDetails {
            Objects.requireNonNull(buildFiles);
            Objects.requireNonNull(dependencies);
            Objects.requireNonNull(buildLintCommand);
            Objects.requireNonNull(testAllCommand);
            Objects.requireNonNull(instructions);
            Objects.requireNonNull(excludedDirectories);
        }

        public static final BuildDetails EMPTY = new BuildDetails(List.of(), List.of(), "", "", "", Set.of());
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
    static CompletableFuture<String> determineVerificationCommandAsync(IContextManager cm) {
        // Runs asynchronously on the background executor provided by ContextManager
        return CompletableFuture.supplyAsync(() -> {
            // Retrieve build details from the project associated with the ContextManager
            Project project = (Project) cm.getProject();
            BuildDetails details = project.getBuildDetails();

            if (details.equals(BuildDetails.EMPTY)) {
                logger.warn("No build details available, cannot determine verification command.");
                return null;
            }

            // Check project setting for test scope
            Project.CodeAgentTestScope testScope = project.getCodeAgentTestScope();
            if (testScope == Project.CodeAgentTestScope.ALL) {
                logger.debug("Code Agent Test Scope is ALL, using testAllCommand: {}", details.testAllCommand());
                return details.testAllCommand();
            }

            // Proceed with workspace-specific test determination
            logger.debug("Code Agent Test Scope is WORKSPACE, determining tests in workspace.");

            // Get ProjectFiles from editable and read-only fragments
            Stream<ProjectFile> projectFilesFromEditableOrReadOnly =
                Stream.concat(
                        cm.topContext().editableFiles(),
                        cm.topContext().readonlyFiles()
                      )
                      .flatMap(fragment -> fragment.files().stream()); // No analyzer

            // Get ProjectFiles specifically from SkeletonFragments among all virtual fragments
            Stream<ProjectFile> projectFilesFromSkeletons =
                cm.topContext().virtualFragments()
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

            // Construct the prompt for the LLM
            logger.debug("Found relevant tests {}, asking LLM for specific command.", workspaceTestFiles);
            var prompt = """
                         Given the build details and the list of test files modified or relevant to the recent changes,
                         give the shell command to run *only* these specific tests. (You may chain multiple
                         commands with &&, if necessary.)
                         
                         Build Details:
                         Test All Command: %s
                         Build/Lint Command: %s
                         Other Instructions: %s
                         
                         Test Files to execute:
                         %s
                         
                         Provide *only* the command line string to execute these specific tests.
                         Do not include any explanation or formatting.
                         If you cannot determine a more specific command, respond with an empty string.
                         """.formatted(details.testAllCommand(),
                                       details.buildLintCommand(),
                                       details.instructions(),
                                       workspaceTestFiles.stream().map(ProjectFile::toString).collect(Collectors.joining("\n"))).stripIndent();
            // Need a coder instance specifically for this task
            var inferTestCoder = cm.getLlm(cm.getService().quickModel(), "Infer tests");
            // Ask the LLM
            Llm.StreamingResult llmResult;
            try {
                llmResult = inferTestCoder.sendRequest(List.of(new UserMessage(prompt)));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (llmResult.chatResponse() == null || llmResult.chatResponse().aiMessage() == null) {
                logger.warn("No reply from LLM; falling back to default: {}", details.buildLintCommand());
                return details.buildLintCommand();
            }

            // remove potential markdown syntax
            String rawCommandFromLlm = llmResult.chatResponse().aiMessage().text();
            var suggestedCommand = unmarkdown(rawCommandFromLlm);

            // Use the suggested command if valid, otherwise fallback
            if (suggestedCommand.isBlank()) {
                logger.warn("Blank reply from LLM; falling back to default: {}", details.buildLintCommand());
                return details.buildLintCommand();
            }

            logger.debug("LLM suggested specific test command: '{}'", suggestedCommand);
            return suggestedCommand;
        }, cm.getBackgroundTasks());
    }
}
