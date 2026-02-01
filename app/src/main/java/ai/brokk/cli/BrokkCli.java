package ai.brokk.cli;

import static java.util.Objects.requireNonNull;

import ai.brokk.ContextManager;
import ai.brokk.TaskResult;
import ai.brokk.agents.ArchitectAgent;
import ai.brokk.agents.BuildAgent;
import ai.brokk.agents.ContextAgent;
import ai.brokk.agents.SearchAgent;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitRepoFactory;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.MainProject;
import ai.brokk.project.WorktreeProject;
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
        name = "brokk-cli",
        mixinStandardHelpOptions = true,
        description = "Brokk CLI - AI-powered code assistant.",
        footerHeading = "%nAvailable Models:%n",
        footer = {"Use MainProject.loadFavoriteModels() to see available model aliases."})
public final class BrokkCli implements Callable<Integer> {
    private static final Logger logger = LogManager.getLogger(BrokkCli.class);

    private static final String DEFAULT_CODE_MODEL = "Flash 3";
    private static final String DEFAULT_PLAN_MODEL = "Opus 4.5";

    // --- Project Path ---
    @CommandLine.Option(names = "--project", required = true, description = "Path to the project root.")
    private Path projectPath;

    // --- Mode Flags ---
    @CommandLine.Option(
            names = "--scan",
            description =
                    "Run ContextAgent scan. Standalone: prints recommendations. Can compose with --code or --search.")
    private boolean scan;

    @CommandLine.Option(
            names = "--code",
            description =
                    "Code changes mode. Uses ArchitectAgent if editable context exists, otherwise SearchAgent with CODE_ONLY.")
    private boolean code;

    @CommandLine.Option(
            names = "--search",
            description = "Search/answer mode. Uses SearchAgent with ANSWER_ONLY objective.")
    private boolean search;

    // --- Search Tool Actions ---
    @CommandLine.Option(
            names = "--list-symbols",
            split = ",",
            description = "Search for symbols using comma-delimited regex patterns. Standalone action.")
    private List<String> listSymbolsPatterns = new ArrayList<>();

    @CommandLine.Option(
            names = "--skim-directory",
            description = "List immediate contents of a directory. Standalone action.")
    @Nullable
    private String skimDirectoryPath;

    // --- Goal Parameter ---
    @CommandLine.Option(
            names = "--goal",
            description =
                    "Goal/prompt for the operation. Required for --scan, --code, --search. Supports @file syntax.")
    @Nullable
    private String goal;

    // --- Context Injection ---
    @CommandLine.Option(
            names = "--file",
            split = ",",
            arity = "1..*",
            description = "Add files for editing (comma-delimited or repeatable).")
    private List<String> files = new ArrayList<>();

    @CommandLine.Option(
            names = "--class",
            split = ",",
            arity = "1..*",
            description = "Add class sources by FQCN (comma-delimited or repeatable).")
    private List<String> classes = new ArrayList<>();

    @CommandLine.Option(
            names = "--method",
            split = ",",
            arity = "1..*",
            description = "Add method sources by FQ name (comma-delimited or repeatable).")
    private List<String> methods = new ArrayList<>();

    @CommandLine.Option(
            names = "--usage",
            split = ",",
            arity = "1..*",
            description = "Add symbol usages (comma-delimited or repeatable).")
    private List<String> usages = new ArrayList<>();

    @CommandLine.Option(
            names = "--summary",
            split = ",",
            arity = "1..*",
            description = "Add summaries - tries as class first, then as file (comma-delimited or repeatable).")
    private List<String> summaries = new ArrayList<>();

    // --- Build Commands ---
    @CommandLine.Option(names = "--build-only-cmd", description = "Build/lint command (no tests).")
    @Nullable
    private String buildOnlyCmd;

    @CommandLine.Option(names = "--test-all-cmd", description = "Command to run all tests.")
    @Nullable
    private String testAllCmd;

    @CommandLine.Option(
            names = "--test-some-cmd",
            description = "Mustache template for specific tests. Variables: {{#files}}, {{#classes}}, {{#fqclasses}}.")
    @Nullable
    private String testSomeCmd;

    // --- Models ---
    @CommandLine.Option(
            names = "--codemodel",
            description = "Code model alias. Default: ${DEFAULT-VALUE}.",
            defaultValue = DEFAULT_CODE_MODEL)
    private String codeModelAlias;

    @CommandLine.Option(
            names = "--planmodel",
            description = "Planning model alias. Default: ${DEFAULT-VALUE}.",
            defaultValue = DEFAULT_PLAN_MODEL)
    private String planModelAlias;

    // --- Other Options ---
    @CommandLine.Option(names = "--worktree", description = "Create detached worktree at the given path.")
    @Nullable
    private Path worktreePath;

    @CommandLine.Option(
            names = "--brokk-key",
            description = "Brokk API key override (also reads BROKK_API_KEY env var).")
    @Nullable
    private String brokkApiKey;

    private ContextManager cm;
    private AbstractProject project;

    public static void main(String[] args) {
        logger.info("Starting Brokk CLI...");
        System.setProperty("java.awt.headless", "true");

        var cli = new BrokkCli();
        var cmd = new CommandLine(cli);

        // Set dynamic footer with available models
        cmd.getCommandSpec().usageMessage().footer(getModelFooter());

        int exitCode = cmd.execute(args);
        System.exit(exitCode);
    }

    private static String[] getModelFooter() {
        try {
            var models = MainProject.loadFavoriteModels();
            var lines = new ArrayList<String>();
            lines.add("");
            lines.add("Available Models:");
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
        String effectiveBrokkKey = brokkApiKey;
        if (effectiveBrokkKey == null || effectiveBrokkKey.isBlank()) {
            effectiveBrokkKey = System.getenv("BROKK_API_KEY");
        }
        if (effectiveBrokkKey != null && !effectiveBrokkKey.isBlank()) {
            MainProject.setHeadlessBrokkApiKeyOverride(effectiveBrokkKey);
            logger.info("Using Brokk API key (length={})", effectiveBrokkKey.length());
        }

        // --- Expand @file syntax for goal ---
        if (goal != null) {
            goal = maybeLoadFromFile(goal);
        }

        // --- Mode Validation ---
        boolean hasListSymbols = !listSymbolsPatterns.isEmpty();
        boolean hasSkimDirectory = skimDirectoryPath != null;

        int modeCount = 0;
        if (scan && !code && !search) modeCount++; // standalone scan
        if (code) modeCount++;
        if (search) modeCount++;
        if (hasListSymbols) modeCount++;
        if (hasSkimDirectory) modeCount++;

        // scan can compose with code or search, so don't count it if composing
        if (scan && (code || search)) {
            modeCount--; // scan is composing, not standalone
        }

        boolean worktreeOnly = worktreePath != null && modeCount == 0 && !scan;

        if (!worktreeOnly && modeCount == 0) {
            System.err.println(
                    """
                    Error: No action specified.

                    Choose one of:
                      --scan             Run ContextAgent scan (standalone or combine with --code/--search)
                      --code             Apply code changes (requires build details)
                      --search           Search/answer mode
                      --list-symbols     Standalone symbol search (use --list-symbols PAT1,PAT2,...)
                      --skim-directory   Standalone directory listing (use --skim-directory REL_OR_ABS_PATH)
                      --worktree         Create a detached worktree

                    Tip: For most tasks, start with:
                      brokk-cli --project <path> --search --goal "<your question>"
                    """);
            return 1;
        }

        if (modeCount > 1) {
            System.err.println(
                    """
                    Error: Too many modes/actions specified.

                    Exactly one primary mode can be specified:
                      --code OR --search OR --scan (standalone) OR --list-symbols OR --skim-directory

                    Exception:
                      --scan can be combined with --code OR --search.

                    Examples:
                      brokk-cli --project . --scan --goal "@goal.txt"
                      brokk-cli --project . --scan --code --goal "Fix failing test"
                      brokk-cli --project . --search --goal "What does Foo do?"
                    """);
            return 1;
        }

        if (code && search) {
            System.err.println(
                    """
                    Error: Conflicting modes.

                    --code and --search cannot be used together.
                      --code   is for making edits (may run builds)
                      --search is for answering questions (no edits)

                    If you want context discovery + edits, use:
                      --scan --code
                    """);
            return 1;
        }

        // --- Goal Validation ---
        boolean needsGoal = scan || code || search;
        if (needsGoal && (goal == null || goal.isBlank())) {
            System.err.println(
                    """
                    Error: Missing required --goal.

                    --goal is required for:
                      --scan, --code, --search

                    Notes:
                      - You can pass a literal string: --goal "Explain how X works"
                      - You can load from a file:     --goal "@path/to/goal.txt"
                    """);
            return 1;
        }

        // --- Build Command Validation ---
        int buildCmdCount = 0;
        if (buildOnlyCmd != null) buildCmdCount++;
        if (testAllCmd != null) buildCmdCount++;
        if (testSomeCmd != null) buildCmdCount++;

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
        projectPath = projectPath.toAbsolutePath();
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

        // --- Worktree Setup ---
        if (worktreePath != null) {
            worktreePath = worktreePath.toAbsolutePath();
            if (Files.exists(worktreePath)) {
                logger.debug("Worktree directory already exists: {}. Skipping creation.", worktreePath);
            } else {
                try (var gitRepo = new GitRepo(projectPath)) {
                    var defaultBranch = gitRepo.getDefaultBranch();
                    var targetCommit = gitRepo.resolveToCommit(defaultBranch).getName();
                    logger.debug("Creating worktree from {} at commit {}", defaultBranch, targetCommit);
                    gitRepo.worktrees().addWorktreeDetached(worktreePath, targetCommit);
                    logger.debug("Successfully created detached worktree at {}", worktreePath);
                } catch (GitRepo.GitRepoException | GitRepo.NoDefaultBranchException e) {
                    System.err.println("Error creating worktree: " + e.getMessage());
                    return 1;
                }
            }
            if (worktreeOnly) {
                return 0;
            }
            projectPath = worktreePath;
        }

        // --- Create Project and ContextManager ---
        var mainProject = new MainProject(projectPath);
        project = worktreePath == null ? mainProject : new WorktreeProject(worktreePath, mainProject);
        cm = new ContextManager(project);

        // --- Build Details ---
        boolean hasCliDetails = buildOnlyCmd != null || testAllCmd != null || testSomeCmd != null;
        var existingDetails = project.loadBuildDetails();
        BuildAgent.BuildDetails buildDetailsToUse;

        if (hasCliDetails) {
            buildDetailsToUse = createBuildDetails();
        } else {
            buildDetailsToUse = existingDetails.orElse(BuildAgent.BuildDetails.EMPTY);
        }

        // Validate build details for --code mode
        if (code && buildDetailsToUse.equals(BuildAgent.BuildDetails.EMPTY)) {
            System.err.println(
                    """
                    Error: --code requires build details, but none were provided or configured.

                    Fix this by doing ONE of the following:
                      1) Provide a build command on the CLI:
                           --build-only-cmd "..."
                         OR --test-all-cmd "..."
                         OR --test-some-cmd "..."

                      2) Configure build details in the project (so CLI can reuse them next time).

                    Why this is required:
                      --code may apply edits and then run verification. Without a build/test command,
                      Brokk cannot validate changes.

                    Example:
                      brokk-cli --project . --code --goal "Fix CI" --test-all-cmd "mvn test"
                    """);
            return 1;
        }

        cm.createHeadless(buildDetailsToUse);
        var service = cm.getService();

        // --- Model Resolution ---
        StreamingChatModel codeModel;
        StreamingChatModel planModel;
        try {
            var codeModelFav = MainProject.getFavoriteModel(codeModelAlias);
            var planModelFav = MainProject.getFavoriteModel(planModelAlias);
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
                                .formatted(codeModelAlias, planModelAlias));
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

        if (!files.isEmpty()) {
            tools.addFilesToWorkspace(files);
        }

        if (!classes.isEmpty()) {
            tools.addClassesToWorkspace(classes);
        }

        if (!methods.isEmpty()) {
            tools.addMethodsToWorkspace(methods);
        }

        if (!usages.isEmpty()) {
            for (var symbol : usages) {
                tools.addSymbolUsagesToWorkspace(symbol);
            }
        }

        if (!summaries.isEmpty()) {
            var result = resolveSummaries(summaries, tools);
            if (result != 0) {
                return result;
            }
        }

        cm.pushContext(ctx -> tools.getContext());

        // --- Run Scan (if specified) ---
        if (scan) {
            var scanResult = runContextAgentScan(planModel, requireNonNull(goal));
            if (!scanResult.success()) {
                System.err.println("Scan did not complete successfully.");
                if (!code && !search) {
                    return 1;
                }
            }
        }

        // --- Run Primary Mode ---
        if (code) {
            return runCodeMode(planModel, codeModel, requireNonNull(goal));
        } else if (search) {
            return runSearchMode(planModel, requireNonNull(goal));
        } else if (scan) {
            // Standalone scan - already ran above, just print results
            return 0;
        }

        return 0;
    }

    private BuildAgent.BuildDetails createBuildDetails() {
        String buildCmd = buildOnlyCmd != null ? buildOnlyCmd : "";
        String testAll = testAllCmd != null ? testAllCmd : "";
        String testSome = testSomeCmd != null ? testSomeCmd : "";

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

    private int runCodeMode(StreamingChatModel planModel, StreamingChatModel codeModel, String goalText) {
        var io = cm.getIo();

        // Check if editable context exists
        var context = cm.liveContext();
        boolean hasEditableContext = context.getEditableFragments().findAny().isPresent();

        TaskResult result;
        try (var scope = cm.beginTaskUngrouped(goalText)) {
            if (hasEditableContext) {
                io.showNotification(
                        ai.brokk.IConsoleIO.NotificationRole.INFO,
                        "Running ArchitectAgent with existing editable context...");
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
