package ai.brokk.cli;

import static java.util.Objects.requireNonNull;

import ai.brokk.ContextManager;
import ai.brokk.TaskResult;
import ai.brokk.agents.ArchitectAgent;
import ai.brokk.agents.BuildAgent;
import ai.brokk.agents.ConflictInspector;
import ai.brokk.agents.ContextAgent;
import ai.brokk.agents.MergeAgent;
import ai.brokk.agents.SearchAgent;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.git.GitRepoFactory;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.MainProject;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.tools.SearchTools;
import ai.brokk.tools.WorkspaceTools;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine;

@SuppressWarnings("NullAway.Init")
@CommandLine.Command(
        name = "brokk",
        mixinStandardHelpOptions = true,
        description = "Brokk CLI - AI-powered code assistant.",
        footerHeading = "%nAvailable Models:%n",
        footer = {"Use MainProject.loadFavoriteModels() to see available model aliases."})
public final class BrokkCli implements Callable<Integer> {
    private static final Logger logger = LogManager.getLogger(BrokkCli.class);

    private static final String DEFAULT_CODE_MODEL = "Flash 3";
    private static final String DEFAULT_PLAN_MODEL = "Opus 4.5";

    @CommandLine.Parameters(hidden = true)
    private List<String> unmatched = new ArrayList<>();

    @CommandLine.ArgGroup(exclusive = false, heading = "%nProject Configuration:%n")
    private ProjectConfig projectConfig = new ProjectConfig();

    static final class ProjectConfig {
        @CommandLine.Option(
                names = "--project",
                defaultValue = ".",
                description = "Path to the project root. Default: current directory.")
        Path projectPath;

        @CommandLine.Option(
                names = "--goal",
                description =
                        "Goal/prompt for the operation. Required for --scan, --code, --search. Supports @file syntax.")
        @Nullable
        String goal;

        @CommandLine.Option(names = "--build-only-cmd", description = "Build/lint command (no tests).")
        @Nullable
        String buildOnlyCmd;

        @CommandLine.Option(names = "--test-all-cmd", description = "Command to run all tests.")
        @Nullable
        String testAllCmd;

        @CommandLine.Option(
                names = "--test-some-cmd",
                description =
                        "Mustache template for specific tests. Variables: {{#files}}, {{#classes}}, {{#fqclasses}}.")
        @Nullable
        String testSomeCmd;
    }

    @CommandLine.ArgGroup(exclusive = false)
    private ActionMode actionMode = new ActionMode();

    static final class ActionMode {
        @CommandLine.ArgGroup(exclusive = false, validate = false, heading = "%nActions (Context Engine):%n")
        ContextEngineGroup contextEngine;

        @CommandLine.ArgGroup(exclusive = false, validate = false, heading = "%nActions (Agentic Research):%n")
        AgenticResearchGroup agenticResearch;

        @CommandLine.ArgGroup(exclusive = false, validate = false, heading = "%nActions (Agentic Coding):%n")
        AgenticCodingGroup agenticCoding;
    }

    static final class ContextEngineGroup {
        @CommandLine.Option(
                names = "--list-symbols",
                split = ",",
                description = "Standalone symbol search using comma-delimited regex patterns.")
        List<String> listSymbolsPatterns = new ArrayList<>();

        @CommandLine.Option(names = "--skim-directory", description = "Standalone directory listing.")
        @Nullable
        String skimDirectoryPath;
    }

    static final class AgenticResearchGroup {
        @CommandLine.Option(
                names = "--scan",
                description = "Lightweight scan for relevant files (usually run before --search).")
        boolean scan;

        @CommandLine.Option(names = "--search", description = "Deep search/answer mode.")
        boolean search;
    }

    static final class AgenticCodingGroup {
        @CommandLine.Option(names = "--code", description = "Apply changes (use when you know what files to change).")
        boolean code;

        @CommandLine.Option(
                names = "--lutz",
                description = "Search for context, then implement the goal (use when you don't know what to change).")
        boolean lutz;

        @CommandLine.Option(names = "--merge", description = "Solves all merge conflicts in the repo.")
        boolean merge = false;

        @CommandLine.Option(names = "--build", description = "Run build verification without making changes.")
        boolean build = false;
    }

    @CommandLine.ArgGroup(exclusive = false, heading = "%nWorkspace Content (optional context):%n")
    private WorkspaceContext workspaceContext = new WorkspaceContext();

    static final class WorkspaceContext {
        @CommandLine.Option(
                names = "--file",
                split = ",",
                arity = "1..*",
                description = "Add files for editing (comma-delimited or repeatable).")
        List<String> files = new ArrayList<>();

        @CommandLine.Option(
                names = "--class",
                split = ",",
                arity = "1..*",
                description = "Add class sources by FQCN (comma-delimited or repeatable).")
        List<String> classes = new ArrayList<>();

        @CommandLine.Option(
                names = "--method",
                split = ",",
                arity = "1..*",
                description = "Add method sources by FQ name (comma-delimited or repeatable).")
        List<String> methods = new ArrayList<>();

        @CommandLine.Option(
                names = "--usage",
                split = ",",
                arity = "1..*",
                description = "Add symbol usages (comma-delimited or repeatable).")
        List<String> usages = new ArrayList<>();

        @CommandLine.Option(
                names = "--summary",
                split = ",",
                arity = "1..*",
                description = "Add summaries - tries as class first, then as file (comma-delimited or repeatable).")
        List<String> summaries = new ArrayList<>();
    }

    @CommandLine.ArgGroup(exclusive = false, heading = "%nModel Selection:%n")
    private ModelConfig modelConfig = new ModelConfig();

    static final class ModelConfig {
        @CommandLine.Option(
                names = "--codemodel",
                description = "Code model alias. Default: ${DEFAULT-VALUE}.",
                defaultValue = DEFAULT_CODE_MODEL)
        String codeModelAlias = DEFAULT_CODE_MODEL;

        @CommandLine.Option(
                names = "--planmodel",
                description = "Planning model alias. Default: ${DEFAULT-VALUE}.",
                defaultValue = DEFAULT_PLAN_MODEL)
        String planModelAlias = DEFAULT_PLAN_MODEL;
    }

    private ContextManager cm;
    private AbstractProject project;

    public static void main(String[] args) {
        logger.info("Starting Brokk CLI...");
        System.setProperty("java.awt.headless", "true");

        var cli = new BrokkCli();
        var cmd = new CommandLine(cli);
        cmd.setUnmatchedArgumentsAllowed(true);

        // Set dynamic footer with available models
        cmd.getCommandSpec().usageMessage().footer(getModelFooter());

        int exitCode = cmd.execute(args);
        System.exit(exitCode);
    }

    private static String[] getModelFooter() {
        try {
            var models = MainProject.loadFavoriteModels();
            var lines = new ArrayList<String>();
            for (var model : models) {
                lines.add("  " + model.alias() + " -> " + model.config().name());
            }
            return lines.toArray(new String[0]);
        } catch (Exception e) {
            return new String[] {"", "Unable to load available models."};
        }
    }

    @Override
    @Blocking
    public Integer call() throws Exception {
        // --- API Key Setup ---
        String effectiveBrokkKey = System.getenv("BROKK_API_KEY");
        if (effectiveBrokkKey != null && !effectiveBrokkKey.isBlank()) {
            MainProject.setHeadlessBrokkApiKeyOverride(effectiveBrokkKey);
            logger.info("Using BROKK_API_KEY environment variable (length={})", effectiveBrokkKey.length());
        }

        String goal = projectConfig.goal;

        // --- Expand @file syntax for goal ---
        if (goal != null) {
            goal = maybeLoadFromFile(goal);
        }

        if (!unmatched.isEmpty() && unmatched.stream().anyMatch(s -> !s.isBlank())) {
            System.err.println("Error: Unrecognized arguments: " + String.join(" ", unmatched));
            System.err.println("Use --help to see available options.");
            return 1;
        }

        // --- Mode Mapping & Validation ---
        boolean scan = false;
        boolean code = false;
        boolean search = false;
        boolean lutz = false;
        boolean merge = false;
        boolean build = false;

        List<String> listSymbolsPatterns = List.of();
        @Nullable String skimDirectoryPath = null;

        if (actionMode.agenticResearch != null) {
            scan = actionMode.agenticResearch.scan;
            search = actionMode.agenticResearch.search;
        }
        if (actionMode.agenticCoding != null) {
            code = actionMode.agenticCoding.code;
            lutz = actionMode.agenticCoding.lutz;
            merge = actionMode.agenticCoding.merge;
            build = actionMode.agenticCoding.build;
        }
        if (actionMode.contextEngine != null) {
            listSymbolsPatterns = actionMode.contextEngine.listSymbolsPatterns.stream()
                    .filter(s -> !s.isBlank())
                    .toList();
            skimDirectoryPath = actionMode.contextEngine.skimDirectoryPath;
        }

        boolean hasListSymbols = !listSymbolsPatterns.isEmpty();
        boolean hasSkimDirectory = skimDirectoryPath != null && !skimDirectoryPath.isBlank();

        var selectedActions = new ArrayList<String>();
        if (hasListSymbols) selectedActions.add("--list-symbols");
        if (hasSkimDirectory) selectedActions.add("--skim-directory");
        if (scan) selectedActions.add("--scan");
        if (search) selectedActions.add("--search");
        if (code) selectedActions.add("--code");
        if (lutz) selectedActions.add("--lutz");
        if (merge) selectedActions.add("--merge");
        if (build) selectedActions.add("--build");

        if (selectedActions.isEmpty()) {
            System.err.println(
                    """
                    Error: Missing required action mode.

                    Specify exactly one of:

                    Context Engine:
                      --list-symbols=<patterns>[,<patterns>...]
                      --skim-directory=<path>

                    Agentic Research:
                      --scan
                      --search

                    Agentic Coding:
                      --code
                      --lutz
                      --merge
                      --build

                    Use --help to see full option details.
                    """);
            return 1;
        }

        if (selectedActions.size() > 1) {
            System.err.println(
                    """
                    Error: Too many action modes.

                    Specify exactly one action mode, but got:
                      %s

                    Use --help to see full option details.
                    """
                            .formatted(String.join(" ", selectedActions)));
            return 1;
        }

        // --- Goal Validation ---
        boolean needsGoal = scan || code || search || lutz; // --merge/--build do not strictly require a goal
        if (needsGoal && (goal == null || goal.isBlank())) {
            System.err.println(
                    """
                    Error: Missing required --goal.

                    --goal is required for:
                      --scan, --search, --code, --lutz

                    Notes:
                      - You can pass a literal string: --goal "Explain how X works"
                      - You can load from a file:     --goal "@path/to/goal.txt"
                    """);
            return 1;
        }

        // --- Build Command Validation ---
        int buildCmdCount = 0;
        if (projectConfig.buildOnlyCmd != null) buildCmdCount++;
        if (projectConfig.testAllCmd != null) buildCmdCount++;
        if (projectConfig.testSomeCmd != null) buildCmdCount++;

        if (buildCmdCount > 1) {
            System.err.println(
                    """
                    Error: Too many build command options.

                    Provide at most one of:
                      --build-only-cmd   "..."
                      --test-all-cmd     "..."
                      --test-some-cmd    "..."

                    Tip:
                      - For --code, you can also omit these if your project already has build details saved.
                    """);
            return 1;
        }

        // --- Project Path Validation ---
        Path projectPath = requireNonNull(projectConfig.projectPath).toAbsolutePath();
        if (!Files.isDirectory(projectPath)) {
            System.err.println(
                    """
                    Error: Invalid --project path.

                    The provided path is not a directory:
                      %s
                    """
                            .formatted(projectPath));
            return 1;
        }
        if (!GitRepoFactory.hasGitRepo(projectPath)) {
            System.err.println(
                    """
                    Error: Not a Git repository.

                    Brokk CLI requires --project to point at a Git worktree (a directory containing a .git folder or file).

                    Provided:
                      %s
                    """
                            .formatted(projectPath));
            return 1;
        }

        // --- Create Project and ContextManager ---
        project = new MainProject(projectPath);
        cm = new ContextManager(project);

        // --- Build Details ---
        boolean hasCliDetails = projectConfig.buildOnlyCmd != null
                || projectConfig.testAllCmd != null
                || projectConfig.testSomeCmd != null;
        var existingDetails = project.loadBuildDetails();
        BuildAgent.BuildDetails buildDetailsToUse;

        if (hasCliDetails) {
            buildDetailsToUse = createBuildDetails();
        } else {
            buildDetailsToUse = existingDetails.orElse(BuildAgent.BuildDetails.EMPTY);
        }

        // Validate build details for coding/build modes
        if ((code || lutz || build) && buildDetailsToUse.equals(BuildAgent.BuildDetails.EMPTY)) {
            String modeName = code ? "--code" : (lutz ? "--lutz" : "--build");
            System.err.println(
                    """
                    Error: %s requires build details, but none were provided or configured.

                    Fix this by doing ONE of the following:
                      1) Provide a build command on the CLI:
                           --build-only-cmd "..."
                         OR --test-all-cmd "..."
                         OR --test-some-cmd "..."

                      2) Configure build details in the project (so CLI can reuse them next time).

                    Why this is required:
                      %s requires a command to verify changes or run the build.

                    Example:
                      brokk --project . %s --goal "Refactor" --test-all-cmd "mvn test"
                    """
                            .formatted(modeName, modeName, modeName));
            return 1;
        }

        cm.createHeadless(buildDetailsToUse);
        var service = cm.getService();

        // --- Model Resolution ---
        StreamingChatModel codeModel;
        StreamingChatModel planModel;
        try {
            var codeModelFav = MainProject.getFavoriteModel(modelConfig.codeModelAlias);
            var planModelFav = MainProject.getFavoriteModel(modelConfig.planModelAlias);
            codeModel = service.getModel(codeModelFav.config());
            planModel = service.getModel(planModelFav.config());

            if (codeModel == null || planModel == null) {
                System.err.println(
                        """
                        Error: Failed to initialize models.

                        Likely causes:
                          - Missing/invalid API key (use --brokk-key or BROKK_API_KEY env var)
                          - Unknown/disabled model configuration in your Brokk service
                          - Network/proxy issue

                        Requested:
                          --codemodel "%s"
                          --planmodel "%s"
                        """
                                .formatted(modelConfig.codeModelAlias, modelConfig.planModelAlias));
                return 1;
            }
        } catch (IllegalArgumentException e) {
            System.err.println(
                    """
                    Error: Unknown model alias.

                    %s

                    Available model aliases:
                    %s
                    """
                            .formatted(
                                    e.getMessage(),
                                    MainProject.loadFavoriteModels().stream()
                                            .map(m -> "  - " + m.alias() + " -> "
                                                    + m.config().name())
                                            .collect(Collectors.joining("\n"))));
            return 1;
        } catch (Exception e) {
            System.err.println(
                    """
                    Error: Failed to initialize models.

                    Cause:
                      %s
                    """
                            .formatted(e.getMessage()));
            return 1;
        }

        // --- Search Tool Actions (standalone) ---
        if (hasListSymbols) {
            var searchTools = new SearchTools(cm);
            String reasoning = goal != null ? goal : "CLI symbol search";
            var result = searchTools.searchSymbols(listSymbolsPatterns, reasoning);
            System.out.println(result);
            return 0;
        }

        if (hasSkimDirectory) {
            var searchTools = new SearchTools(cm);
            String reasoning = goal != null ? goal : "CLI directory listing";
            var result = searchTools.skimDirectory(requireNonNull(skimDirectoryPath), reasoning);
            System.out.println(result);
            return 0;
        }

        // --- Context Injection ---
        var tools = new WorkspaceTools(cm.liveContext());

        if (code
                && !lutz
                && workspaceContext.files.isEmpty()
                && workspaceContext.classes.isEmpty()
                && workspaceContext.methods.isEmpty()
                && workspaceContext.usages.isEmpty()
                && workspaceContext.summaries.isEmpty()) {
            System.err.println(
                    """
                    Error: --code requires an existing workspace context.

                    You invoked --code without providing any files, classes, or methods to the workspace.
                    If you don't know which files need changing, use --lutz instead.

                    Example:
                      brokk --project . --code --file src/MyFile.java --goal "Refactor this"
                    """);
            return 1;
        }

        if (!workspaceContext.files.isEmpty()) {
            tools.addFilesToWorkspace(workspaceContext.files);
        }

        if (!workspaceContext.classes.isEmpty()) {
            tools.addClassesToWorkspace(workspaceContext.classes);
        }

        if (!workspaceContext.methods.isEmpty()) {
            tools.addMethodsToWorkspace(workspaceContext.methods);
        }

        if (!workspaceContext.usages.isEmpty()) {
            for (var symbol : workspaceContext.usages) {
                tools.addSymbolUsagesToWorkspace(symbol);
            }
        }

        if (!workspaceContext.summaries.isEmpty()) {
            var result = resolveSummaries(workspaceContext.summaries, tools);
            if (result != 0) {
                return result;
            }
        }

        cm.pushContext(ctx -> tools.getContext());

        // --- Run Primary Mode ---
        if (scan) {
            var scanResult = runContextAgentScan(planModel, requireNonNull(goal));
            return scanResult.success() ? 0 : 1;
        } else if (lutz) {
            return runCodeMode(planModel, codeModel, requireNonNull(goal), true);
        } else if (code) {
            return runCodeMode(planModel, codeModel, requireNonNull(goal), false);
        } else if (search) {
            return runSearchMode(planModel, requireNonNull(goal));
        } else if (merge) {
            return runMergeMode(planModel, codeModel);
        } else if (build) {
            return runBuildMode();
        }

        return 0;
    }

    private int runMergeMode(StreamingChatModel planModel, StreamingChatModel codeModel) {
        var io = cm.getIo();
        var conflictOpt = ConflictInspector.inspectFromProject(cm.getProject());
        if (conflictOpt.isEmpty()) {
            System.err.println("Error: Repository is not in a merge/rebase/cherry-pick/revert conflict state.");
            return 1;
        }

        var conflict = conflictOpt.get();
        logger.debug("Conflict detected: {}", conflict);
        io.showNotification(ai.brokk.IConsoleIO.NotificationRole.INFO, "Running MergeAgent...");

        TaskResult result;
        try (var scope = cm.beginTaskUngrouped("Merge")) {
            MergeAgent mergeAgent =
                    new MergeAgent(cm, planModel, codeModel, conflict, scope, MergeAgent.DEFAULT_MERGE_INSTRUCTIONS);
            result = mergeAgent.execute();
            scope.append(result);
        } catch (Exception e) {
            System.err.println("Error during merge execution: " + e.getMessage());
            logger.error("Merge mode error", e);
            return 1;
        }

        if (result.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
            System.err.println("Merge failed: " + result.stopDetails().reason());
            System.err.println(result.stopDetails().explanation());
            return 1;
        }

        return 0;
    }

    private int runBuildMode() throws InterruptedException {
        var io = cm.getIo();
        String buildError = BuildAgent.runVerification(cm);
        io.showNotification(
                ai.brokk.IConsoleIO.NotificationRole.INFO,
                buildError.isEmpty()
                        ? "Build verification completed successfully."
                        : "Build verification failed:\n" + buildError);
        return buildError.isEmpty() ? 0 : 1;
    }

    private BuildAgent.BuildDetails createBuildDetails() {
        String buildCmd = projectConfig.buildOnlyCmd != null ? projectConfig.buildOnlyCmd : "";
        String testAll = projectConfig.testAllCmd != null ? projectConfig.testAllCmd : "";
        String testSome = projectConfig.testSomeCmd != null ? projectConfig.testSomeCmd : "";

        return new BuildAgent.BuildDetails(buildCmd, testAll, testSome, Set.of(), Map.of("VIRTUAL_ENV", ".venv"));
    }

    private ContextAgent.RecommendationResult runContextAgentScan(StreamingChatModel model, String goalText)
            throws InterruptedException {
        var io = cm.getIo();
        io.showNotification(ai.brokk.IConsoleIO.NotificationRole.INFO, "Running context scan...");

        var agent = new ContextAgent(cm, model, goalText);
        var recommendations = agent.getRecommendations(cm.liveContext());

        if (recommendations.success()) {
            System.out.println("Scan recommendations:");
            for (var fragment : recommendations.fragments()) {
                System.out.println("  - " + fragment.shortDescription().renderNowOr("(loading)"));
                cm.addFragments(fragment);
            }
        } else {
            System.err.println("Scan did not complete successfully.");
        }

        return recommendations;
    }

    private int runCodeMode(
            StreamingChatModel planModel, StreamingChatModel codeModel, String goalText, boolean forceScan) {
        var io = cm.getIo();

        // Check if editable context exists
        var context = cm.liveContext();
        boolean hasEditableContext = context.getEditableFragments().findAny().isPresent();

        TaskResult result;
        try (var scope = cm.beginTaskUngrouped(goalText)) {
            if (hasEditableContext || forceScan) {
                io.showNotification(
                        ai.brokk.IConsoleIO.NotificationRole.INFO,
                        forceScan
                                ? "Running ArchitectAgent (lutz mode)..."
                                : "Running ArchitectAgent with existing editable context...");
                var agent = new ArchitectAgent(cm, planModel, codeModel, goalText, scope);
                result = agent.executeWithScan();
            } else {
                io.showNotification(
                        ai.brokk.IConsoleIO.NotificationRole.INFO,
                        "No editable context - running SearchAgent with CODE_ONLY objective...");
                var agent = new SearchAgent(context, goalText, planModel, SearchPrompts.Objective.CODE_ONLY, scope);
                result = agent.execute();
            }
            scope.append(result);
        } catch (Exception e) {
            System.err.println("Error during code execution: " + e.getMessage());
            logger.error("Code mode error", e);
            return 1;
        }

        if (result.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
            System.err.println("Task failed: " + result.stopDetails().reason());
            System.err.println(result.stopDetails().explanation());
            return 1;
        }

        return 0;
    }

    private int runSearchMode(StreamingChatModel model, String goalText) {
        var io = cm.getIo();
        io.showNotification(ai.brokk.IConsoleIO.NotificationRole.INFO, "Running search...");

        TaskResult result;
        try (var scope = cm.beginTaskUngrouped(goalText)) {
            var agent = new SearchAgent(cm.liveContext(), goalText, model, SearchPrompts.Objective.ANSWER_ONLY, scope);
            result = agent.execute();
            scope.append(result);
        } catch (Exception e) {
            System.err.println("Error during search: " + e.getMessage());
            logger.error("Search mode error", e);
            return 1;
        }

        // Print answer
        System.out.println("\n=== Answer ===");
        System.out.println(result.output().text().renderNowOr("(No answer available)"));

        // Print workspace contents
        System.out.println("\n=== Workspace Contents ===");
        var workspaceFragments = result.context().getAllFragmentsInDisplayOrder();
        if (workspaceFragments.isEmpty()) {
            System.out.println("(empty)");
        } else {
            for (var fragment : workspaceFragments) {
                System.out.println("  - " + fragment.shortDescription().renderNowOr("(loading)"));
            }
        }

        // Print discarded context key facts
        var discardedNotes = result.context().getDiscardedFragmentsNotes();
        if (!discardedNotes.isEmpty()) {
            System.out.println("\n=== Key Facts from Explored Context ===");
            for (var entry : discardedNotes.entrySet()) {
                System.out.println(entry.getKey() + ":");
                System.out.println("  " + entry.getValue());
            }
        }

        if (result.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
            System.err.println(
                    "\nSearch completed with status: " + result.stopDetails().reason());
            return 1;
        }

        return 0;
    }

    private int resolveSummaries(List<String> targets, WorkspaceTools tools) throws InterruptedException {
        var analyzer = cm.getAnalyzer();
        var allDeclarations = analyzer.getAllDeclarations();
        var classNames = allDeclarations.stream()
                .filter(CodeUnit::isClass)
                .map(CodeUnit::fqName)
                .collect(Collectors.toSet());

        var classTargets = new ArrayList<String>();
        var fileTargets = new ArrayList<String>();

        for (var target : targets) {
            if (classNames.contains(target)) {
                classTargets.add(target);
            } else {
                // Try as file
                var pf = cm.toFile(target);
                if (pf.exists()) {
                    fileTargets.add(target);
                } else {
                    System.err.println(
                            "Error: --summary target '" + target + "' is neither a known class nor an existing file.");
                    return 1;
                }
            }
        }

        if (!classTargets.isEmpty()) {
            tools.addClassSummariesToWorkspace(classTargets);
        }

        if (!fileTargets.isEmpty()) {
            tools.addFileSummariesToWorkspace(fileTargets);
        }

        return 0;
    }

    private String maybeLoadFromFile(String text) throws IOException {
        if (!text.startsWith("@")) {
            return text;
        }

        String rawPath = text.substring(1).trim();
        if (rawPath.isEmpty()) {
            throw new IOException(
                    "Invalid @file syntax for --goal: '@' must be followed by a readable file path, e.g. --goal \"@goal.txt\".");
        }

        var path = Path.of(rawPath);
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IOException(
                    "Failed to read --goal from @" + rawPath + ". Ensure the file exists and is readable.", e);
        }
    }
}
