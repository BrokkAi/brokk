package io.github.jbellis.brokk.agents;

import static java.util.Objects.requireNonNull;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import com.github.mustachejava.util.DecoratedCollection;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ToolChoice;
import eu.hansolo.fx.jdkmon.tools.Distro;
import eu.hansolo.fx.jdkmon.tools.Finder;
import io.github.jbellis.brokk.AnalyzerUtil;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.Llm;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.Languages;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.tools.ToolExecutionResult;
import io.github.jbellis.brokk.tools.ToolRegistry;
import io.github.jbellis.brokk.util.BuildToolConventions;
import io.github.jbellis.brokk.util.BuildToolConventions.BuildSystem;
import io.github.jbellis.brokk.util.BuildOutputPreprocessor;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.ExecutorConfig;
import io.github.jbellis.brokk.util.Messages;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * The BuildAgent class is responsible for executing a process to gather and report build details for a software
 * project's development environment. It interacts with tools, processes project files, and uses an LLM to identify
 * build commands, test configurations, and exclusions, ultimately providing structured build information or aborting if
 * unsupported.
 */
public class BuildAgent {
    private static final Logger logger = LogManager.getLogger(BuildAgent.class);

    public static final String JAVA_HOME_SENTINEL = "$JAVA_HOME";

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
        this.llm = llm;
        this.toolRegistry = toolRegistry;
    }

    /**
     * Execute the build information gathering process.
     *
     * @return The gathered BuildDetails record, or EMPTY if the process fails or is interrupted.
     */
    public BuildDetails execute() throws InterruptedException {
        // build message containing root directory contents
        ToolExecutionRequest initialRequest = ToolExecutionRequest.builder()
                .name("listFiles")
                .arguments("{\"directoryPath\": \".\"}") // Request root dir
                .build();
        ToolExecutionResult initialResult = toolRegistry.executeTool(this, initialRequest);
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
        var files = project.getAllFiles().stream()
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
                logger.debug(
                        "Added the following directory patterns from .gitignore to excluded directories: {}",
                        addedFromGitignore);
            }

        } else {
            logger.debug(
                    "No .git directory found at project root. Skipping .gitignore processing for excluded directories.");
        }

        // 2. Iteration Loop
        while (true) {
            // 3. Build Prompt
            List<ChatMessage> messages = buildPrompt();

            // 4. Add tools
            // Get specifications for ALL tools the agent might use in this turn.
            var tools = new ArrayList<>(toolRegistry.getRegisteredTools(
                    List.of("listFiles", "searchFilenames", "searchSubstrings", "getFileContents")));
            if (chatHistory.size() > 1) {
                // allow terminal tools
                tools.addAll(toolRegistry.getTools(this, List.of("reportBuildDetails", "abortBuildDetails")));
            }

            // Make the LLM request
            Llm.StreamingResult result;
            try {
                result = llm.sendRequest(messages, new ToolContext(tools, ToolChoice.REQUIRED, this), false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Unexpected request cancellation in build agent");
                return BuildDetails.EMPTY;
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

            // 6. Execute Terminal Actions via ToolRegistry (if any)
            if (reportRequest != null) {
                var terminalResult = toolRegistry.executeTool(this, reportRequest);
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
                logger.trace("Agent action: {} ({})", toolName, request.arguments());
                ToolExecutionResult execResult = toolRegistry.executeTool(this, request);
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
                                       | **pytest**        | `pytest {{#files}}{{value}}{{^-last}} {{/-last}}{{/files}}`
                                       | **Jest**          | `jest {{#files}}{{value}}{{^-last}} {{/-last}}{{/files}}`

                                       Prefer the repository-local *wrapper script* when it exists in the project root (e.g. `./gradlew`, `./mvnw`).
                                       Only fall back to the bare command (`gradle`, `mvn` …) when no wrapper script is present.


                                       A baseline set of excluded directories has been established from build conventions and .gitignore.
                                       When you use `reportBuildDetails`, the `excludedDirectories` parameter should contain *additional* directories
                                       you identify that should be excluded from code intelligence, beyond this baseline.

                                       Remember to request the `reportBuildDetails` tool to finalize the process ONLY once all information is collected.
                                       The reportBuildDetails tool expects exactly four parameters: buildLintCommand, testAllCommand, testSomeCommand, and excludedDirectories.
                                       """
                        .stripIndent()));

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
        var finalExcludes = Stream.concat(this.currentExcludedDirectories.stream(), excludedDirectories.stream())
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> Path.of(s).normalize())
                .map(Path::toString)
                .collect(Collectors.toSet());

        this.reportedDetails = new BuildDetails(buildLintCommand, testAllCommand, testSomeCommand, finalExcludes);
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

    /**
     * Detect a JDK to use for this project. - If JAVA_HOME is set and points to a JDK (has bin/javac), return the
     * sentinel so it stays dynamic. - Otherwise, choose the most suitable JDK using Finder (by latest version/release
     * date) and return its path. - If nothing is found, fall back to the sentinel.
     */
    public static @Nullable String detectJdk() {
        var env = System.getenv("JAVA_HOME");
        try {
            if (env != null && !env.isBlank()) {
                var home = Path.of(env);
                if (isJdkHome(home)) {
                    return JAVA_HOME_SENTINEL;
                }
            }
        } catch (Exception e) {
            logger.debug("Invalid JAVA_HOME '{}': {}", env, e.getMessage());
        }

        // Fallback: use Finder to locate installed JDKs and pick the most suitable one
        try {
            var finder = new Finder();
            var distros = finder.getDistributions();
            if (distros != null && !distros.isEmpty()) {
                Comparator<Distro> distroComparator = Comparator.comparing(Distro::getVersionNumber)
                        .thenComparing(
                                d -> d.getReleaseDate().orElse(null), Comparator.nullsLast(Comparator.naturalOrder()));
                var best = distros.stream().max(distroComparator).orElse(null);
                if (best != null) {
                    var p = best.getPath();
                    if (p != null && !p.isBlank()) return p;
                    var loc = best.getLocation();
                    if (loc != null && !loc.isBlank()) return loc;
                }
            }
        } catch (Throwable t) {
            logger.warn("Failed to detect JDK via Finder", t);
        }

        // user will need to download something, leave it alone in the meantime
        return null;
    }

    private static boolean isJdkHome(Path home) {
        var bin = home.resolve("bin");
        var javac = bin.resolve(exeName("javac"));
        return Files.isRegularFile(javac);
    }

    private static String exeName(String base) {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? base + ".exe" : base;
    }

    /** Holds semi-structured information about a project's build process */
    public record BuildDetails(
            String buildLintCommand, String testAllCommand, String testSomeCommand, Set<String> excludedDirectories) {

        public static final BuildDetails EMPTY = new BuildDetails("", "", "", Set.of());
    }

    /**
     * Asynchronously determines the best verification command based on the user goal, workspace summary, and stored
     * BuildDetails. Runs on the ContextManager's background task executor. Determines the command by checking for
     * relevant test files in the workspace and the availability of a specific test command in BuildDetails.
     *
     * @param cm The ContextManager instance.
     * @return A CompletableFuture containing the suggested verification command string (either specific test command or
     *     build/lint command), or null if BuildDetails are unavailable.
     */
    public static @Nullable String determineVerificationCommand(IContextManager cm) {
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
            return prefixWithJavaHomeIfNeeded(cm.getProject(), details.testAllCommand());
        }

        // Proceed with workspace-specific test determination
        logger.debug("Code Agent Test Scope is WORKSPACE, determining tests in workspace.");

        // Get ProjectFiles from editable and read-only fragments
        var topContext = cm.topContext();
        var projectFilesFromEditableOrReadOnly =
                topContext.fileFragments().flatMap(fragment -> fragment.files().stream()); // No analyzer

        // Get ProjectFiles specifically from SkeletonFragments among all virtual fragments
        var projectFilesFromSkeletons = topContext
                .virtualFragments()
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
            var summaries = ContextFragment.getSummary(cm.topContext().allFragments());
            logger.debug(
                    "No relevant test files found for {} with Workspace {}; using build/lint command: {}",
                    cm.getProject().getRoot(),
                    summaries,
                    getBuildLintAllCommand(details));
            return prefixWithJavaHomeIfNeeded(cm.getProject(), getBuildLintAllCommand(details));
        }

        return getBuildLintSomeCommand(cm, details, workspaceTestFiles);
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

    private static String prefixWithJavaHomeIfNeeded(IProject project, String command) {
        if (command.isBlank()) {
            return command;
        }
        // Only prefix for Java projects
        if (project.getBuildLanguage() != Languages.JAVA) {
            return command;
        }
        var trimmed = command.stripLeading();
        if (trimmed.startsWith("JAVA_HOME=")) {
            return command;
        }

        String jdk = project.getJdk();
        if (JAVA_HOME_SENTINEL.equals(jdk)) {
            var env = System.getenv("JAVA_HOME");
            jdk = (env == null || env.isBlank()) ? null : env;
        }
        if (jdk == null || jdk.isBlank()) {
            return command;
        }
        return "JAVA_HOME=" + jdk + " " + command;
    }

    public static String getBuildLintSomeCommand(
            IContextManager cm, BuildDetails details, Collection<ProjectFile> workspaceTestFiles) {
        // Determine if template is files-based or classes-based
        String testSomeTemplate = System.getenv("BRK_TESTSOME_CMD") == null
                ? details.testSomeCommand()
                : System.getenv("BRK_TESTSOME_CMD");
        boolean isFilesBased = testSomeTemplate.contains("{{#files}}");
        boolean isFqBased = testSomeTemplate.contains("{{#fqclasses}}");
        boolean isClassesBased = testSomeTemplate.contains("{{#classes}}") || isFqBased;

        if (!isFilesBased && !isClassesBased) {
            logger.debug(
                    "Test template doesn't use {{#files}} or {{#classes}}, using build/lint command: {}",
                    getBuildLintAllCommand(details));
            return prefixWithJavaHomeIfNeeded(cm.getProject(), getBuildLintAllCommand(details));
        }

        List<String> targetItems;
        if (isFilesBased) {
            // Use file paths directly
            targetItems = workspaceTestFiles.stream().map(ProjectFile::toString).toList();
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
                return prefixWithJavaHomeIfNeeded(cm.getProject(), details.buildLintCommand());
            }

            var codeUnits = AnalyzerUtil.testFilesToCodeUnits(analyzer, workspaceTestFiles);
            if (isFqBased) {
                targetItems = codeUnits.stream().map(CodeUnit::fqName).sorted().toList();
            } else {
                targetItems =
                        codeUnits.stream().map(CodeUnit::identifier).sorted().toList();
            }
            if (targetItems.isEmpty()) {
                logger.debug(
                        "No classes found in workspace test files for class-based template, using build/lint command: {}",
                        details.buildLintCommand());
                return prefixWithJavaHomeIfNeeded(cm.getProject(), details.buildLintCommand());
            }
            logger.debug("Using classes-based template with {} classes", targetItems.size());
        }

        // Perform simple template interpolation
        String listKey = isFilesBased ? "files" : (isFqBased ? "fqclasses" : "classes");
        String interpolatedCommand = interpolateMustacheTemplate(testSomeTemplate, targetItems, listKey);
        logger.debug("Interpolated test command: '{}'", interpolatedCommand);
        return prefixWithJavaHomeIfNeeded(cm.getProject(), interpolatedCommand);
    }

    private static String getBuildLintAllCommand(BuildDetails details) {
        if (System.getenv("BRK_TESTALL_CMD") != null) {
            return System.getenv("BRK_TESTALL_CMD");
        }
        return details.buildLintCommand();
    }

    /**
     * Interpolates a Mustache template with the given list of items. Supports {{files}} and {{classes}} variables with
     * {{^-last}} separators.
     */
    private static String interpolateMustacheTemplate(String template, List<String> items, String listKey) {
        if (template.isEmpty()) {
            return "";
        }

        MustacheFactory mf = new DefaultMustacheFactory();
        // The "templateName" argument to compile is for caching and error reporting, can be arbitrary.
        Mustache mustache = mf.compile(new StringReader(template), "dynamic_template");

        Map<String, Object> context = new HashMap<>();
        // Mustache.java handles null or empty lists correctly for {{#section}} blocks.
        context.put(listKey, new DecoratedCollection<>(items));

        StringWriter writer = new StringWriter();
        // This can throw MustacheException, which will propagate as a RuntimeException
        // as per the project's "let it throw" style.
        mustache.execute(writer, context);

        return writer.toString();
    }

    /**
     * Run the verification build for the current project, stream output to the console,
     * and update the session's Build Results fragment.
     *
     * Returns empty string on success (or when no command is configured), otherwise the raw combined error/output text.
     */
    public static String runVerification(ContextManager cm) throws InterruptedException {
        var io = cm.getIo();

        var verificationCommand = determineVerificationCommand(cm);
        if (verificationCommand == null || verificationCommand.isBlank()) {
            io.llmOutput("\nNo verification command specified, skipping build/check.", ChatMessageType.CUSTOM);
            cm.updateBuildFragment("No verification command configured.");
            return "";
        }

        // Enforce single-build execution when requested
        boolean noConcurrentBuilds = "true".equalsIgnoreCase(System.getenv("BRK_NO_CONCURRENT_BUILDS"));
        if (noConcurrentBuilds) {
            var lock = acquireBuildLock(cm);
            if (lock == null) {
                logger.warn("Failed to acquire build lock; proceeding without it");
                return runBuildAndUpdateFragmentInternal(cm, verificationCommand);
            }
            // The lock is implemented using a FileChannel/FileLock; keep the channel/lock inside the record and close it after execution.
            try (var ignored = lock) {
                logger.debug("Acquired build lock {}", lock.lockFile());
                return runBuildAndUpdateFragmentInternal(cm, verificationCommand);
            } catch (Exception e) {
                logger.warn("Exception while using build lock {}; proceeding without it", lock.lockFile(), e);
                return runBuildAndUpdateFragmentInternal(cm, verificationCommand);
            }
        } else {
            return runBuildAndUpdateFragmentInternal(cm, verificationCommand);
        }
    }

    /** Holder for lock resources, AutoCloseable so try-with-resources releases it. */
    private record BuildLock(java.nio.channels.FileChannel channel,
                             java.nio.channels.FileLock lock,
                             java.nio.file.Path lockFile) implements AutoCloseable {
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
    private static @Nullable BuildLock acquireBuildLock(ContextManager cm) {
        java.nio.file.Path lockDir = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "brokk");
        try {
            java.nio.file.Files.createDirectories(lockDir);
        } catch (java.io.IOException e) {
            logger.warn("Unable to create lock directory {}; proceeding without build lock", lockDir, e);
            return null;
        }

        var repoNameForLock = getOriginRepositoryName(cm);
        java.nio.file.Path lockFile = lockDir.resolve(repoNameForLock + ".lock");

        try {
            var channel = java.nio.channels.FileChannel.open(
                    lockFile, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
            var lock = channel.lock();
            logger.debug("Acquired build lock {}", lockFile);
            return new BuildLock(channel, lock, lockFile);
        } catch (java.io.IOException ioe) {
            logger.warn("Failed to acquire file lock {}; proceeding without it", lockFile, ioe);
            return null;
        }
    }

    private static String getOriginRepositoryName(ContextManager cm) {
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

    private static String runBuildAndUpdateFragmentInternal(ContextManager cm, String verificationCommand)
            throws InterruptedException {
        var io = cm.getIo();

        io.llmOutput("\nRunning verification command: " + verificationCommand, ChatMessageType.CUSTOM);
        String shellLang = ExecutorConfig.getShellLanguageFromProject(cm.getProject());
        io.llmOutput("\n```" + shellLang + "\n", ChatMessageType.CUSTOM);
        try {
            var output = Environment.instance.runShellCommand(
                    verificationCommand,
                    cm.getProject().getRoot(),
                    line -> io.llmOutput(line + "\n", ChatMessageType.CUSTOM),
                    Environment.UNLIMITED_TIMEOUT);
            io.llmOutput("\n```", ChatMessageType.CUSTOM);

            cm.updateBuildFragment("Build succeeded.");
            logger.debug("Verification command successful. Output: {}", output);
            return "";
        } catch (Environment.SubprocessException e) {
            io.llmOutput("\n```", ChatMessageType.CUSTOM); // Close the markdown block

            String rawBuild = e.getMessage() + "\n\n" + e.getOutput();
            String processed = BuildOutputPreprocessor.processForLlm(rawBuild, cm);
            cm.updateBuildFragment("Build output:\n" + processed);
            return rawBuild;
        }
    }
}
