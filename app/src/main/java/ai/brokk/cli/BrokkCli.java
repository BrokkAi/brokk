package ai.brokk.cli;

import static java.util.Objects.requireNonNull;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO.NotificationRole;
import ai.brokk.MutedConsoleIO;
import ai.brokk.TaskResult;
import ai.brokk.agents.ArchitectAgent;
import ai.brokk.agents.BuildAgent;
import ai.brokk.agents.ConflictInspector;
import ai.brokk.agents.ContextAgent;
import ai.brokk.agents.MergeAgent;
import ai.brokk.agents.SearchAgent;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments.SummaryFragment;
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

    private static final Set<String> GOAL_REQUIRED_ACTIONS = Set.of(
            "--scan",
            "--code",
            "--find-symbols",
            "--find-usages",
            "--fetch-source",
            "--fetch-summary",
            "--list-identifiers");

    @CommandLine.Parameters(hidden = true)
    private final List<String> unmatched = new ArrayList<>();

    @CommandLine.ArgGroup(exclusive = false, heading = "%nTask:%n")
    private final TaskConfig taskConfig = new TaskConfig();

    static final class TaskConfig {
        @CommandLine.Option(
                names = "--goal",
                description =
                        "Goal/prompt for the operation. Required for most actions. Supports loading from file with @path/to/file.")
        @Nullable
        String goal;

        @CommandLine.Option(
                names = "--autocommit",
                description = "Automatically commit changes after a successful task. Default: false.")
        boolean autocommit = false;

        @CommandLine.Option(
                names = "--new-session",
                description = "Create a fresh session instead of resuming the most recent one. Default: false.")
        boolean newSession = false;

        @CommandLine.Option(
                names = "--include-tests",
                description = "Include test files in search results (usages, symbols). Default: false.")
        boolean includeTests = false;
    }

    @CommandLine.ArgGroup(exclusive = false, heading = "%nProject Configuration (Optional):%n")
    private final BuildTestConfig buildTestConfig = new BuildTestConfig();

    static final class BuildTestConfig {
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
        @Nullable
        ContextEngineGroup contextEngine;

        @CommandLine.ArgGroup(
                exclusive = false,
                validate = false,
                heading = "%nActions (Agentic Coding) — require --goal (except --merge/--build):%n")
        @Nullable
        AgenticCodingGroup agenticCoding;
    }

    static final class ContextEngineGroup {
        @CommandLine.Option(
                names = "--scan",
                description = "Agentic scan for relevant files and classes based on the --goal. Run this first.")
        boolean scan;

        @CommandLine.Option(
                names = "--find-symbols",
                split = ",",
                description = "Standalone symbol search using comma-delimited regex patterns.")
        List<String> findSymbolsPatterns = new ArrayList<>();

        @CommandLine.Option(
                names = "--find-usages",
                split = ",",
                arity = "1..*",
                description = "Returns the source code of blocks where symbols are used.")
        List<String> findUsagesTargets = new ArrayList<>();

        @CommandLine.Option(
                names = "--fetch-summary",
                split = ",",
                arity = "1..*",
                description = "Returns all declarations (public/private) for specified files or classes.")
        List<String> fetchSummaryTargets = new ArrayList<>();

        @CommandLine.Option(
                names = "--fetch-source",
                split = ",",
                arity = "1..*",
                description = "Returns the full source code of specific classes or methods.")
        List<String> fetchSourceTargets = new ArrayList<>();

        @CommandLine.Option(
                names = "--list-identifiers",
                description = "Lists all identifiers in each file within a directory (lightweight summary).")
        @Nullable
        String listIdentifiersPath;
    }

    static final class AgenticCodingGroup {
        @CommandLine.Option(
                names = "--code",
                description =
                        "Implement the changes asked for in --goal. Will search for relevant files if none are provided via --file.")
        boolean code;

        @CommandLine.Option(names = "--merge", description = "Solves all merge conflicts in the repo.")
        boolean merge = false;

        @CommandLine.Option(names = "--build", description = "Run build verification without making changes.")
        boolean build = false;
    }

    @CommandLine.ArgGroup(exclusive = false, heading = "%nWorkspace Content:%n")
    private WorkspaceContext workspaceContext = new WorkspaceContext();

    static final class WorkspaceContext {
        @CommandLine.Option(
                names = "--file",
                split = ",",
                arity = "1..*",
                description = "Add files for editing (comma-delimited or repeatable).")
        List<String> files = new ArrayList<>();
    }

    @CommandLine.ArgGroup(exclusive = false, heading = "%nModel Selection (Optional):%n")
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

    @CommandLine.ArgGroup(exclusive = false, heading = "%nProject Configuration:%n")
    private final ProjectConfig projectConfig = new ProjectConfig();

    static final class ProjectConfig {
        @CommandLine.Option(
                names = "--project",
                defaultValue = ".",
                description = "Path to the project root. Default: current directory.")
        Path projectPath;
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
        var lines = new ArrayList<String>();
        try {
            var models = MainProject.loadFavoriteModels();
            for (var model : models) {
                lines.add("  " + model.alias() + " -> " + model.config().name());
            }
        } catch (Exception e) {
            lines.add("  Unable to load available models.");
        }

        lines.add("");
        lines.add("Actions requiring --goal:");
        lines.add("  " + GOAL_REQUIRED_ACTIONS.stream().sorted().collect(Collectors.joining(", ")));

        return lines.toArray(new String[0]);
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

        String goal = taskConfig.goal;

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
        boolean scan;
        boolean code;
        boolean merge;
        boolean build;
        boolean autocommit;

        List<String> findSymbolsPatterns;
        @Nullable String listIdentifiersPath;
        List<String> fetchSourceTargets;
        List<String> findUsagesTargets;
        List<String> fetchSummaryTargets;

        code = actionMode.agenticCoding != null && actionMode.agenticCoding.code;
        merge = actionMode.agenticCoding != null && actionMode.agenticCoding.merge;
        build = actionMode.agenticCoding != null && actionMode.agenticCoding.build;
        autocommit = taskConfig.autocommit;

        if (actionMode.contextEngine != null) {
            scan = actionMode.contextEngine.scan;
            findSymbolsPatterns = actionMode.contextEngine.findSymbolsPatterns.stream()
                    .filter(s -> !s.isBlank())
                    .toList();
            listIdentifiersPath = actionMode.contextEngine.listIdentifiersPath;
            fetchSourceTargets = actionMode.contextEngine.fetchSourceTargets.stream()
                    .filter(s -> !s.isBlank())
                    .toList();
            findUsagesTargets = actionMode.contextEngine.findUsagesTargets.stream()
                    .filter(s -> !s.isBlank())
                    .toList();
            fetchSummaryTargets = actionMode.contextEngine.fetchSummaryTargets.stream()
                    .filter(s -> !s.isBlank())
                    .toList();
        } else {
            scan = false;
            findSymbolsPatterns = List.of();
            listIdentifiersPath = null;
            fetchSourceTargets = List.of();
            findUsagesTargets = List.of();
            fetchSummaryTargets = List.of();
        }

        boolean hasFindSymbols = !findSymbolsPatterns.isEmpty();
        boolean hasListIdentifiers = listIdentifiersPath != null && !listIdentifiersPath.isBlank();
        boolean hasFetchSource = !fetchSourceTargets.isEmpty();
        boolean hasFindUsages = !findUsagesTargets.isEmpty();
        boolean hasFetchSummary = !fetchSummaryTargets.isEmpty();
        boolean hasContextEngine =
                hasFindSymbols || hasListIdentifiers || hasFetchSource || hasFindUsages || hasFetchSummary;

        var selectedActions = new ArrayList<String>();
        if (hasFindSymbols) selectedActions.add("--find-symbols");
        if (hasFindUsages) selectedActions.add("--find-usages");
        if (hasFetchSummary) selectedActions.add("--fetch-summary");
        if (hasFetchSource) selectedActions.add("--fetch-source");
        if (hasListIdentifiers) selectedActions.add("--list-identifiers");
        if (scan) selectedActions.add("--scan");
        if (code) selectedActions.add("--code");
        if (merge) selectedActions.add("--merge");
        if (build) selectedActions.add("--build");

        if (selectedActions.isEmpty()) {
            new CommandLine(this).usage(System.out);
            return 0;
        }

        if (selectedActions.size() > 1) {
            System.err.printf(
                    """
                                      Error: Too many action modes.

                                      Specify exactly one action mode, but got:
                                        %s

                                      Use --help to see full option details.
                                      %n""",
                    String.join(" ", selectedActions));
            return 1;
        }

        // --- Goal Validation ---
        boolean needsGoal = selectedActions.stream().anyMatch(GOAL_REQUIRED_ACTIONS::contains);

        if (needsGoal && (goal == null || goal.isBlank())) {
            System.err.printf(
                    """
                    Error: Missing required --goal.

                    --goal is required for the following actions:
                      %s

                    Notes:
                      - You can pass a literal string: --goal "Explain how X works"
                      - You can load from a file:     --goal "@path/to/goal.txt"
                    %n""",
                    String.join(", ", GOAL_REQUIRED_ACTIONS.stream().sorted().toList()));
            return 1;
        }

        // Final goal for use in requireNonNull calls or search tools
        final String finalGoal = goal != null ? goal : "";

        // --- Build Command Validation ---
        int buildCmdCount = 0;
        if (buildTestConfig.buildOnlyCmd != null) buildCmdCount++;
        if (buildTestConfig.testAllCmd != null) buildCmdCount++;
        if (buildTestConfig.testSomeCmd != null) buildCmdCount++;

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
        Path projectPath = projectConfig.projectPath.toAbsolutePath();
        if (!Files.isDirectory(projectPath)) {
            System.err.printf(
                    """
                                      Error: Invalid --project path.

                                      The provided path is not a directory:
                                        %s
                                      %n""",
                    projectPath);
            return 1;
        }
        if (!GitRepoFactory.hasGitRepo(projectPath)) {
            System.err.printf(
                    """
                                      Error: Not a Git repository.

                                      Brokk CLI requires --project to point at a Git worktree (a directory containing a .git folder or file).

                                      Provided:
                                        %s
                                      %n""",
                    projectPath);
            return 1;
        }

        // --- Create Project and ContextManager ---
        project = new MainProject(projectPath);
        cm = new ContextManager(project);

        // --- Build Details ---
        boolean hasCliDetails = buildTestConfig.buildOnlyCmd != null
                || buildTestConfig.testAllCmd != null
                || buildTestConfig.testSomeCmd != null;
        var existingDetails = project.loadBuildDetails();
        BuildAgent.BuildDetails bd;

        if (hasCliDetails) {
            bd = createBuildDetails();
        } else {
            bd = existingDetails.orElse(BuildAgent.BuildDetails.EMPTY);
        }

        // Validate build details for coding/build modes
        if ((code || build) && bd.equals(BuildAgent.BuildDetails.EMPTY)) {
            String modeName = code ? "--code" : "--build";
            System.err.printf(
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
                                      %n""",
                    modeName, modeName, modeName);
            return 1;
        }

        cm.createHeadless(bd, taskConfig.newSession);
        cm.clearHistory();
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
                System.err.printf(
                        """
                                          Error: Failed to initialize models.

                                          Likely causes:
                                            - Missing/invalid API key (use --brokk-key or BROKK_API_KEY env var)
                                            - Unknown/disabled model configuration in your Brokk service
                                            - Network/proxy issue

                                          Requested:
                                            --codemodel "%s"
                                            --planmodel "%s"
                                          %n""",
                        modelConfig.codeModelAlias, modelConfig.planModelAlias);
                return 1;
            }
        } catch (IllegalArgumentException e) {
            System.err.printf(
                    """
                            Error: Unknown model alias.

                            %s

                            Available model aliases:
                            %s
                            %n""",
                    e.getMessage(),
                    MainProject.loadFavoriteModels().stream()
                            .map(m -> "  - " + m.alias() + " -> " + m.config().name())
                            .collect(Collectors.joining("\n")));
            return 1;
        } catch (Exception e) {
            System.err.printf(
                    """
                                      Error: Failed to initialize models.

                                      Cause:
                                        %s
                                      %n""",
                    e.getMessage());
            return 1;
        }

        // --- Search Tool Actions (standalone) ---
        if (hasContextEngine) {
            var searchTools = new SearchTools(cm);
            boolean includeTests = taskConfig.includeTests;

            if (hasFindSymbols) {
                System.out.println(searchTools.searchSymbols(findSymbolsPatterns, finalGoal));
            }
            if (hasFindUsages) {
                System.out.println(searchTools.getUsages(findUsagesTargets, finalGoal, includeTests));
            }
            if (hasFetchSummary) {
                // Use getFileSummaries if it matches a path pattern, else try getClassSkeletons
                var classNames = cm.getAnalyzer().getAllDeclarations().stream()
                        .filter(CodeUnit::isClass)
                        .map(CodeUnit::fqName)
                        .collect(Collectors.toSet());

                var filePatterns = new ArrayList<String>();
                var classes = new ArrayList<String>();

                for (var target : fetchSummaryTargets) {
                    if (classNames.contains(target)) {
                        classes.add(target);
                    } else {
                        filePatterns.add(target);
                    }
                }

                if (!filePatterns.isEmpty()) {
                    System.out.println(searchTools.getFileSummaries(filePatterns));
                }
                if (!classes.isEmpty()) {
                    System.out.println(searchTools.getClassSkeletons(classes));
                }
            }
            if (hasFetchSource) {
                // Try as classes first, then as methods
                var analyzer = cm.getAnalyzer();
                var allDecls = analyzer.getAllDeclarations();
                var classNames = allDecls.stream()
                        .filter(CodeUnit::isClass)
                        .map(CodeUnit::fqName)
                        .collect(Collectors.toSet());

                var classes =
                        fetchSourceTargets.stream().filter(classNames::contains).toList();
                var methods = fetchSourceTargets.stream()
                        .filter(t -> !classNames.contains(t))
                        .toList();

                if (!classes.isEmpty()) {
                    System.out.println(searchTools.getClassSources(classes));
                }
                if (!methods.isEmpty()) {
                    System.out.println(searchTools.getMethodSources(methods));
                }
            }
            if (hasListIdentifiers) {
                System.out.println(searchTools.skimDirectory(requireNonNull(listIdentifiersPath), finalGoal));
            }
            return 0;
        }

        // --- Context Injection ---
        var tools = new WorkspaceTools(cm.liveContext());

        if (!workspaceContext.files.isEmpty()) {
            tools.addFilesToWorkspace(workspaceContext.files);
        }

        cm.pushContext(ctx -> tools.getContext());

        // --- Run Primary Mode ---
        if (scan) {
            var scanResult = runContextAgentScan(planModel, finalGoal);
            return scanResult.success() ? 0 : 1;
        } else if (code) {
            return runCodeMode(planModel, codeModel, finalGoal, autocommit);
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
        logger.debug("Running MergeAgent...");

        TaskResult result;
        try (var scope = cm.beginTaskUngrouped("Merge")) {
            MergeAgent mergeAgent =
                    new MergeAgent(cm, planModel, codeModel, conflict, scope, MergeAgent.DEFAULT_MERGE_INSTRUCTIONS);
            result = mergeAgent.execute();
            scope.append(result);
        } catch (Exception e) {
            io.showNotification(NotificationRole.ERROR, "Error during merge execution: " + e.getMessage());
            logger.error("Merge mode error", e);
            return 1;
        }

        if (result.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
            io.showNotification(
                    NotificationRole.ERROR,
                    "Merge failed: " + result.stopDetails().reason() + "\n"
                            + result.stopDetails().explanation());
            return 1;
        }

        return 0;
    }

    private int runBuildMode() throws InterruptedException {
        var io = cm.getIo();
        String buildError = BuildAgent.runVerification(cm);
        if (buildError.isEmpty()) {
            logger.debug("Build verification completed successfully.");
        } else {
            io.showNotification(NotificationRole.ERROR, "Build verification failed:\n" + buildError);
        }
        return buildError.isEmpty() ? 0 : 1;
    }

    private BuildAgent.BuildDetails createBuildDetails() {
        String buildCmd = buildTestConfig.buildOnlyCmd != null ? buildTestConfig.buildOnlyCmd : "";
        String testAll = buildTestConfig.testAllCmd != null ? buildTestConfig.testAllCmd : "";
        String testSome = buildTestConfig.testSomeCmd != null ? buildTestConfig.testSomeCmd : "";

        return new BuildAgent.BuildDetails(buildCmd, testAll, testSome, Set.of(), Map.of("VIRTUAL_ENV", ".venv"));
    }

    private ContextAgent.RecommendationResult runContextAgentScan(StreamingChatModel model, String goalText)
            throws InterruptedException {
        var io = cm.getIo();
        logger.debug("Running context scan...");

        var agent = new ContextAgent(cm, model, goalText, new MutedConsoleIO(io));
        var recommendations = agent.getRecommendations(cm.liveContext());

        if (recommendations.success()) {
            // Convert fragments to SummaryFragments and print combined summary
            var st = recommendations.fragments().stream();
            if (!taskConfig.includeTests) {
                st = st.filter(f -> f.files().join().stream()
                        .noneMatch(pf -> ContextManager.isTestFile(pf, cm.getAnalyzerUninterrupted())));
            }
            st.flatMap(f -> toSummaryFragments(f).stream()).forEach(f -> {
                System.out.printf(
                        "## %s:\n%s\n\n%n", f.description().join(), f.text().join());
            });
            cm.pushContext(ctx -> ctx.addFragments(recommendations.fragments()));
        } else {
            io.showNotification(NotificationRole.ERROR, "Scan did not complete successfully.");
        }

        return recommendations;
    }

    private List<SummaryFragment> toSummaryFragments(ContextFragment fragment) {
        var results = new ArrayList<SummaryFragment>();

        // Extract files and convert to FILE_SKELETONS summaries
        var files = fragment.files().join();
        for (var file : files) {
            results.add(new SummaryFragment(cm, file.toString(), ContextFragment.SummaryType.FILE_SKELETONS));
        }

        // Extract code units and convert to CODEUNIT_SKELETON summaries
        var sources = fragment.sources().join();
        for (var codeUnit : sources) {
            if (codeUnit.isClass()) {
                results.add(new SummaryFragment(cm, codeUnit.fqName(), ContextFragment.SummaryType.CODEUNIT_SKELETON));
            }
        }

        return results;
    }

    private int runCodeMode(
            StreamingChatModel planModel, StreamingChatModel codeModel, String goalText, boolean autocommit) {
        var io = cm.getIo();

        // Check if editable context exists
        var context = cm.liveContext();

        TaskResult result;
        try (var scope = cm.beginTaskUngrouped(goalText)) {
            if (context.getEditableFragments().findAny().isPresent()) {
                logger.debug("Editable context present - running ArchitectAgent");
                var agent = new ArchitectAgent(
                        cm, planModel, codeModel, goalText, scope, cm.liveContext(), new MutedConsoleIO(io));
                result = agent.executeWithScan(false);
            } else {
                logger.debug("No editable context - running SearchAgent with CODE_ONLY objective...");
                var agent = new SearchAgent(
                        context,
                        goalText,
                        planModel,
                        SearchPrompts.Objective.CODE_ONLY,
                        scope,
                        new MutedConsoleIO(io),
                        SearchAgent.ScanConfig.defaults(),
                        null);
                result = agent.execute();
            }

            if (result.stopDetails().reason() == TaskResult.StopReason.SUCCESS && autocommit) {
                new ai.brokk.git.GitWorkflow(cm).performAutoCommit(goalText);
            }

            scope.append(result);
        } catch (Exception e) {
            io.showNotification(NotificationRole.ERROR, "Error during code execution: " + e.getMessage());
            logger.error("Code mode error", e);
            return 1;
        }

        if (result.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
            io.showNotification(
                    NotificationRole.ERROR,
                    "Task failed: " + result.stopDetails().reason() + "\n"
                            + result.stopDetails().explanation());
            return 1;
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
