package ai.brokk.cli;

import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.AbstractService;
import ai.brokk.BuildInfo;
import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.Llm;
import ai.brokk.Service;
import ai.brokk.TaskResult;
import ai.brokk.agents.ArchitectAgent;
import ai.brokk.agents.BuildAgent;
import ai.brokk.agents.CodeAgent;
import ai.brokk.agents.ConflictInspector;
import ai.brokk.agents.ContextAgent;
import ai.brokk.agents.LutzAgent;
import ai.brokk.agents.MergeAgent;
import ai.brokk.agents.SearchAgent;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.concurrent.AtomicWrites;
import ai.brokk.context.Context;
import ai.brokk.context.ContextDelta;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.ContextHistory;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitRepoFactory;
import ai.brokk.metrics.SearchMetrics;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.MainProject;
import ai.brokk.project.WorktreeProject;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.tools.WorkspaceTools;
import ai.brokk.util.Environment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.Streams;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine;

@SuppressWarnings("NullAway.Init") // NullAway is upset that some fields are initialized in picocli's call()
@CommandLine.Command(
        name = "brokk-cli",
        version = "Brokk " + BuildInfo.version,
        mixinStandardHelpOptions = true,
        description = "One-shot Brokk workspace and task runner.")
public final class BprCli implements Callable<Integer> {
    private static final Logger logger = LogManager.getLogger(BprCli.class);
    private static final String SFT_GEN_DIR_NAME = "sft_gen";
    private static final String SFT_GEN_ATTEMPTS_FILE = "attempts.json";

    private record CodeModelAttempt(String modelName, StreamingChatModel model) {}

    private record CodeAttemptManifestEntry(
            int attemptIndex,
            String model,
            @Nullable String codeHistoryDir,
            int returnCode,
            String stopReason,
            @Nullable String stopExplanation,
            String diff,
            boolean oneshotSuccessful,
            double elapsedSeconds) {}

    private record CodeAttemptManifest(
            @Nullable String gateModel, List<String> requestedModels, List<CodeAttemptManifestEntry> attempts) {}

    @CommandLine.Option(names = "--project", description = "Path to the project root.")
    @Nullable
    private Path projectPath;

    @CommandLine.Option(names = "--edit", description = "Add a file to the workspace for editing. Can be repeated.")
    private List<String> editFiles = new ArrayList<>();

    @CommandLine.Option(names = "--read", description = "Add a file to the workspace as read-only. Can be repeated.")
    private List<String> readFiles = new ArrayList<>();

    @CommandLine.Option(
            names = "--add-class",
            description =
                    "Add the class corresnponding to the given FQCN to the workspace for editing. Can be repeated.")
    private List<String> addClasses = new ArrayList<>();

    @CommandLine.Option(
            names = "--add-summary-class",
            description = "Add a summary of the given class to the workspace. Can be repeated.")
    private List<String> addSummaryClasses = new ArrayList<>();

    @CommandLine.Option(
            names = "--add-summary-file",
            description = "Add summaries for all classes in a file/glob as a dynamic fragment. Can be repeated.")
    private List<String> addSummaryFiles = new ArrayList<>();

    @CommandLine.Option(
            names = "--add-method-source",
            description = "Add the source of a FQ method to the workspace for editing. Can be repeated.")
    private List<String> addMethodSources = new ArrayList<>();

    @CommandLine.Option(names = "--architect", description = "Run Architect agent with the given prompt.")
    @Nullable
    private String architectPrompt;

    @CommandLine.Option(
            names = "--infer-context",
            description = "Infer and cache relevant context for the given prompt using Architect and Code agents.")
    @Nullable
    private String inferContextPrompt;

    @CommandLine.Option(names = "--code", description = "Run Code agent with the given prompt.")
    @Nullable
    private String codePrompt;

    @CommandLine.Option(
            names = "--search-answer",
            description = "Run Search agent to find an answer for the given prompt.")
    @Nullable
    private String searchAnswerPrompt;

    @CommandLine.Option(
            names = "--lutz",
            description = "Research and execute a set of tasks to accomplish the given prompt")
    @Nullable
    private String lutzPrompt;

    @CommandLine.Option(names = "--merge", description = "Run Merge agent to resolve repository conflicts (no prompt).")
    private boolean merge = false;

    @CommandLine.Option(names = "--build", description = "Run verification build on the current workspace.")
    private boolean build = false;

    @CommandLine.Option(
            names = "--analyze",
            description = "Create the project's ContextManager and Analyzer, persist analyzer state to disk, and exit.")
    private boolean analyze = false;

    @CommandLine.Option(
            names = "--worktree",
            description = "Create a detached worktree at the given path, from the default branch's HEAD.")
    @Nullable
    private Path worktreePath;

    //  Model overrides
    @CommandLine.Option(names = "--planmodel", description = "Override the planning model to use.")
    @Nullable
    private String planModelName;

    @CommandLine.Option(names = "--codemodel", description = "Override the code model to use.")
    @Nullable
    private String codeModelName;

    @CommandLine.Option(
            names = "--code-gate-model",
            description =
                    "When --code and multi-model --codemodel are used, stop after the leading gate-model attempts if all of them succeed oneshot.")
    @Nullable
    private String codeGateModelName;

    @CommandLine.Option(
            names = "--brokk-key",
            description = "Brokk API key override (uses BROKK_API_KEY environment variable if not specified).")
    @Nullable
    private String brokkApiKey;

    @CommandLine.Option(
            names = "--proxy",
            description =
                    "LLM proxy setting override: BROKK, LOCALHOST, or STAGING (uses BROKK_PROXY env var if not specified).")
    @Nullable
    private String proxySetting;

    @CommandLine.Option(
            names = "--favorite-models",
            description =
                    "Favorite models override as JSON array (uses BROKK_FAVORITE_MODELS env var if not specified).")
    @Nullable
    private String favoriteModelsJson;

    @CommandLine.Option(
            names = {"--deep-scan", "--deepscan"},
            arity = "0..1",
            fallbackValue = "true",
            description =
                    "Perform a Deep Scan to suggest additional relevant context. Optionally provide a custom goal.")
    private @Nullable String deepScanGoal;

    @CommandLine.Option(
            names = "--commit",
            description = "Git commit hash to checkout before running search. Used for benchmark reproducibility.")
    @Nullable
    private String commit;

    @CommandLine.Option(
            names = "--search-workspace",
            description =
                    "Run Search agent in benchmark mode to find relevant context for the given query. Outputs JSON report to stdout.")
    @Nullable
    private String searchWorkspace;

    @CommandLine.Option(
            names = "--disable-context-scan",
            description = "Skip the initial ContextAgent scan in --search-workspace mode.")
    private boolean disableContextScan = false;

    @CommandLine.Option(
            names = "--list-models",
            description = "List available model aliases and their corresponding model names as JSON and exit.")
    private boolean listModels = false;

    private ContextManager cm;
    private AbstractProject project;

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        int exitCode = new CommandLine(new BprCli()).execute(args);
        System.exit(exitCode);
    }

    @SuppressWarnings("UnusedVariable")
    @Override
    @Blocking
    public Integer call() throws Exception {

        // Process favorite models override (CLI flag > env var)
        // Must run before --list-models so that overridden models are visible
        String effectiveFavoriteModels = favoriteModelsJson;
        if (effectiveFavoriteModels == null || effectiveFavoriteModels.isBlank()) {
            effectiveFavoriteModels = System.getenv("BROKK_FAVORITE_MODELS");
        }
        if (effectiveFavoriteModels != null && !effectiveFavoriteModels.isBlank()) {
            try {
                var objectMapper = AbstractProject.objectMapper;
                var typeFactory = objectMapper.getTypeFactory();
                var listType = typeFactory.constructCollectionType(List.class, Service.FavoriteModel.class);
                List<Service.FavoriteModel> models = objectMapper.readValue(effectiveFavoriteModels, listType);
                MainProject.setHeadlessFavoriteModelsOverride(models);
                logger.info("Using CLI-specified favorite models ({} models)", models.size());
            } catch (Exception e) {
                System.err.println("Error parsing favorite models JSON: " + e.getMessage());
                return 1;
            }
        }

        // Handle --list-models early exit
        if (listModels) {
            String modelsJson = getModelsJson();
            System.out.println(modelsJson);
            return 0;
        }

        logger.info("Starting Brokk CLI...");

        // Validate --project is provided when not using --build-commit or --list-models
        if (projectPath == null) {
            System.err.println("Error: --project is required.");
            return 1;
        }

        // Process Brokk API key override (CLI flag > env var > global config)
        String effectiveBrokkKey = brokkApiKey;
        if (effectiveBrokkKey == null || effectiveBrokkKey.isBlank()) {
            effectiveBrokkKey = System.getenv("BROKK_API_KEY");
        }
        if (effectiveBrokkKey != null && !effectiveBrokkKey.isBlank()) {
            MainProject.setHeadlessBrokkApiKeyOverride(effectiveBrokkKey);
            logger.info("Using CLI-specified Brokk API key (length={})", effectiveBrokkKey.length());
        }

        // Process proxy setting override (CLI flag > env var)
        String effectiveProxy = proxySetting;
        if (effectiveProxy == null || effectiveProxy.isBlank()) {
            effectiveProxy = System.getenv("BROKK_PROXY");
        }
        if (effectiveProxy != null && !effectiveProxy.isBlank()) {
            try {
                var setting = MainProject.LlmProxySetting.valueOf(effectiveProxy.toUpperCase(Locale.ROOT));
                MainProject.setHeadlessProxySettingOverride(setting);
                logger.info("Using CLI-specified proxy setting: {}", setting);
            } catch (IllegalArgumentException e) {
                System.err.println(
                        "Unknown proxy setting: " + effectiveProxy + ". Valid values: BROKK, LOCALHOST, STAGING");
                return 1;
            }
        }

        // --- Action Validation ---
        boolean deepScan = deepScanGoal != null;

        long nonDeepScanActionCount = Stream.of(
                        architectPrompt,
                        inferContextPrompt,
                        codePrompt,
                        searchAnswerPrompt,
                        lutzPrompt,
                        searchWorkspace)
                .filter(p -> p != null && !p.isBlank())
                .count();
        if (merge) nonDeepScanActionCount++;
        if (build) nonDeepScanActionCount++;
        if (analyze) nonDeepScanActionCount++;

        if (nonDeepScanActionCount > 1) {
            System.err.println(
                    "At most one action (--architect, --infer-context, --code, --search-answer, --lutz, --merge, --build, --analyze, --search-workspace) can be specified.");
            return 1;
        }
        if (deepScan && nonDeepScanActionCount > 0) {
            System.err.println(
                    "Deep Scan (--deep-scan/--deepscan) is a standalone action and cannot be combined with other actions.");
            return 1;
        }

        long actionCount = nonDeepScanActionCount + (deepScan ? 1 : 0);
        if (actionCount == 0 && worktreePath == null) {
            System.err.println(
                    "At least one action (--architect, --infer-context, --code, --search-answer, --lutz, --merge, --build, --analyze, --search-workspace, --deep-scan) or --worktree is required.");
            return 1;
        }

        // Add search-workspace validation
        if (searchWorkspace != null && !searchWorkspace.isBlank()) {
            if (codeModelName != null) {
                System.err.println("--codemodel is not valid with --search-workspace.");
                return 1;
            }
        }

        List<String> requestedCodeModelNames = parseCommaDelimitedPreservingDuplicates(codeModelName);
        boolean multiCodeModelRun = codePrompt != null && requestedCodeModelNames.size() > 1;
        if (!multiCodeModelRun && codeGateModelName != null && !codeGateModelName.isBlank()) {
            System.err.println("Error: --code-gate-model requires --code with multiple --codemodel values.");
            return 1;
        }
        if (codePrompt == null && requestedCodeModelNames.size() > 1) {
            System.err.println("Error: multiple --codemodel values are only supported with --code.");
            return 1;
        }
        if (multiCodeModelRun && !isSingleTurnCodeAgentEnabled()) {
            System.err.println("Error: multi-model --code runs require BRK_CODEAGENT_SINGLE_TURN to be enabled.");
            return 1;
        }
        if (codeGateModelName != null
                && !codeGateModelName.isBlank()
                && !requestedCodeModelNames.contains(codeGateModelName)) {
            System.err.println("Error: --code-gate-model must match one of the requested --codemodel values.");
            return 1;
        }

        //  Expand @file syntax for prompt parameters
        TaskFileInfo architectTaskInfo = null, codeTaskInfo = null;
        TaskFileInfo inferContextTaskInfo = null;
        TaskFileInfo searchAnswerTaskInfo = null, lutzTaskInfo = null;
        TaskFileInfo searchWorkspaceTaskInfo = null;
        TaskFileInfo deepScanTaskInfo = null;

        try {
            if (architectPrompt != null) {
                architectTaskInfo = maybeLoadFromFile(architectPrompt);
                architectPrompt = architectTaskInfo.content;
            }
            if (inferContextPrompt != null) {
                inferContextTaskInfo = maybeLoadFromFile(inferContextPrompt);
                inferContextPrompt = inferContextTaskInfo.content;
            }
            if (codePrompt != null) {
                codeTaskInfo = maybeLoadFromFile(codePrompt);
                codePrompt = codeTaskInfo.content;
            }
            if (searchAnswerPrompt != null) {
                searchAnswerTaskInfo = maybeLoadFromFile(searchAnswerPrompt);
                searchAnswerPrompt = searchAnswerTaskInfo.content;
            }
            if (lutzPrompt != null) {
                lutzTaskInfo = maybeLoadFromFile(lutzPrompt);
                lutzPrompt = lutzTaskInfo.content;
            }
            if (searchWorkspace != null) {
                searchWorkspaceTaskInfo = maybeLoadFromFile(searchWorkspace);
                searchWorkspace = searchWorkspaceTaskInfo.content;
            }
            if (deepScanGoal != null && !deepScanGoal.equals("true") && !deepScanGoal.isBlank()) {
                deepScanTaskInfo = maybeLoadFromFile(deepScanGoal);
                deepScanGoal = deepScanTaskInfo.content;
            }
        } catch (IOException e) {
            System.err.println("Error reading prompt file: " + e.getMessage());
            return 1;
        }

        // --- Validation ---
        projectPath = requireNonNull(projectPath).toAbsolutePath();
        if (!Files.isDirectory(projectPath)) {
            System.err.println("Project path is not a directory: " + projectPath);
            return 1;
        }
        if (!GitRepoFactory.hasGitRepo(projectPath)) {
            System.err.println("Brokk CLI requires to have a Git repo");
            return 1;
        }

        // Worktree setup
        if (worktreePath != null) {
            worktreePath = worktreePath.toAbsolutePath();
            if (Files.exists(worktreePath)) {
                logger.debug("Worktree directory already exists: " + worktreePath + ". Skipping creation.");
            } else {
                try (var gitRepo = new GitRepo(projectPath)) {
                    // Use --commit if provided, otherwise default branch HEAD
                    String targetCommit;
                    if (commit != null) {
                        targetCommit = gitRepo.resolveToCommit(commit).getName();
                        logger.debug("Using commit from --commit option: " + targetCommit);
                    } else {
                        var defaultBranch = gitRepo.getDefaultBranch();
                        targetCommit = gitRepo.resolveToCommit(defaultBranch).getName();
                        logger.debug("Using default branch " + defaultBranch + " at commit " + targetCommit);
                    }

                    gitRepo.worktrees().addWorktreeDetached(worktreePath, targetCommit);
                    logger.debug("Successfully created detached worktree at " + worktreePath);
                } catch (GitRepo.GitRepoException | GitRepo.NoDefaultBranchException e) {
                    logger.error("Error creating worktree", e);
                    System.err.println("Error creating worktree: " + e.getMessage());
                    return 1;
                }
            }
            if (actionCount == 0) {
                return 0; // successfully created worktree and no other action was requested
            }
            // If deepscan is the only action, continue to execute it below
            projectPath = worktreePath;
        }

        // Create Project + ContextManager
        var mainProject = new MainProject(projectPath);
        project = worktreePath == null ? mainProject : new WorktreeProject(worktreePath, mainProject);
        logger.trace("Project files at {} are {}", project.getRepo().getCurrentCommitId(), project.getAllFiles());
        cm = new ContextManager(project);
        var io = cm.getIo();

        cm.createHeadless(true, new HeadlessConsole());
        // Build BuildDetails from environment variables
        String testAllCmd = System.getenv("BRK_TESTALL_CMD");
        String buildLintCmd = System.getenv("BRK_BUILD_CMD");
        String testSomeCmd = System.getenv("BRK_TESTSOME_CMD");

        boolean buildLintEnabled = System.getenv("BRK_BUILDLINT_ENABLED") == null
                || Boolean.parseBoolean(System.getenv("BRK_BUILDLINT_ENABLED"));
        boolean testAllEnabled = System.getenv("BRK_TESTALL_ENABLED") == null
                || Boolean.parseBoolean(System.getenv("BRK_TESTALL_ENABLED"));
        boolean testSomeEnabled = System.getenv("BRK_TESTSOME_ENABLED") == null
                || Boolean.parseBoolean(System.getenv("BRK_TESTSOME_ENABLED"));

        List<BuildAgent.ModuleBuildEntry> modules = List.of();
        String modulesJson = System.getenv("BRK_MODULES_JSON");
        if (modulesJson != null && !modulesJson.isBlank()) {
            try {
                var tf = AbstractProject.objectMapper.getTypeFactory();
                var type = tf.constructCollectionType(List.class, BuildAgent.ModuleBuildEntry.class);
                modules = AbstractProject.objectMapper.readValue(modulesJson, type);
            } catch (Exception e) {
                logger.error("Failed to deserialize BRK_MODULES_JSON: {}", e.getMessage());
            }
        }

        var buildDetails = new BuildAgent.BuildDetails(
                buildLintCmd != null ? buildLintCmd : "",
                buildLintEnabled,
                testAllCmd != null ? testAllCmd : "",
                testAllEnabled,
                testSomeCmd != null ? testSomeCmd : "",
                testSomeEnabled,
                Set.of(),
                Map.of("VIRTUAL_ENV", ".venv"), // venv is hardcoded to override swebench task runner
                null,
                "",
                modules);
        logger.info("Build Details: " + buildDetails);
        project.setBuildDetails(buildDetails);

        if (analyze) {
            try {
                cm.getAnalyzerWrapper().getReadyAndPersisted();
                return 0;
            } finally {
                cm.close();
                project.close();
            }
        }

        //  Model Overrides initialization
        var service = cm.getService();

        StreamingChatModel planModel = null;
        StreamingChatModel codeModel = null;
        StreamingChatModel taskModelOverride = null;

        // Determine which models are required by the chosen action(s).
        boolean needsPlanModel = architectPrompt != null
                || inferContextPrompt != null
                || searchAnswerPrompt != null
                || lutzPrompt != null
                || deepScan
                || merge
                || (searchWorkspace != null && !searchWorkspace.isBlank());
        boolean needsCodeModel = codePrompt != null || architectPrompt != null || inferContextPrompt != null || merge;

        if (needsPlanModel && planModelName == null) {
            System.err.println("Error: This action requires --planmodel to be specified.");
            return 1;
        }
        if (needsCodeModel && requestedCodeModelNames.isEmpty()) {
            System.err.println("Error: This action requires --codemodel to be specified.");
            return 1;
        }

        if (planModelName != null) {
            Service.FavoriteModel fav;
            try {
                fav = MainProject.getFavoriteModel(planModelName);
            } catch (IllegalArgumentException e) {
                System.err.println("Unknown planning model specified via --planmodel: " + planModelName);
                return 1;
            }
            planModel = service.getModel(fav.config());
            taskModelOverride = planModel;
            assert planModel != null : service.getAvailableModels();
        }

        if (!requestedCodeModelNames.isEmpty() && !multiCodeModelRun) {
            var resolvedCodeModel = resolveCodeModel(service, requestedCodeModelNames.getFirst());
            if (resolvedCodeModel.isEmpty()) {
                return 1;
            }
            codeModel = resolvedCodeModel.get().model();
        }

        // --- Search Workspace Mode ---
        if (searchWorkspace != null && !searchWorkspace.isBlank()) {
            TaskResult searchResult;
            try (var scope = cm.beginTaskUngrouped(searchWorkspace)) {
                var searchModel = taskModelOverride == null ? cm.getService().getScanModel() : taskModelOverride;
                if (disableContextScan) {
                    logger.info(
                            "Ignoring --disable-context-scan: SearchAgent does not perform an initial Context scan.");
                }
                var agent = new SearchAgent(
                        cm.liveContext(), searchWorkspace, searchModel, SearchPrompts.Objective.WORKSPACE_ONLY);
                searchResult = agent.execute();
                scope.append(searchResult);
            }

            return searchResult.stopDetails().reason() == TaskResult.StopReason.SUCCESS ? 0 : 1;
        }

        // --- Name Resolution and Context Building ---

        // Resolve and add to context using WorkspaceTools
        Context context = cm.liveContext();
        var tools = new WorkspaceTools(context);

        // Resolve files and classes
        var resolvedEditFiles = resolveFiles(editFiles, "editable file");
        var resolvedReadFiles = resolveFiles(readFiles, "read-only file");
        var resolvedClasses = resolveClasses(addClasses, cm.getAnalyzer(), "class");
        var resolvedSummaryClasses = resolveClasses(addSummaryClasses, cm.getAnalyzer(), "summary class");

        // If any resolution failed, the helper methods will have printed an error.
        if ((resolvedEditFiles.isEmpty() && !editFiles.isEmpty())
                || (resolvedReadFiles.isEmpty() && !readFiles.isEmpty())
                || (resolvedClasses.isEmpty() && !addClasses.isEmpty())
                || (resolvedSummaryClasses.isEmpty() && !addSummaryClasses.isEmpty())) {
            return 1;
        }

        if (!resolvedEditFiles.isEmpty()) {
            context = tools.addFilesToWorkspace(resolvedEditFiles).context();
            tools = new WorkspaceTools(context);
        }

        for (var readFile : resolvedReadFiles) {
            var pf = cm.toFile(readFile);
            var fragment = new ContextFragments.ProjectPathFragment(pf, cm);
            context = context.addFragments(fragment).setReadonly(fragment, true);
            tools = new WorkspaceTools(context);
        }

        if (!resolvedClasses.isEmpty()) {
            context = tools.addClassesToWorkspace(resolvedClasses).context();
            tools = new WorkspaceTools(context);
        }
        if (!resolvedSummaryClasses.isEmpty()) {
            context = tools.addClassSummariesToWorkspace(resolvedSummaryClasses).context();
            tools = new WorkspaceTools(context);
        }
        if (!addSummaryFiles.isEmpty()) {
            context = tools.addFileSummariesToWorkspace(addSummaryFiles).context();
            tools = new WorkspaceTools(context);
        }
        if (!addMethodSources.isEmpty()) {
            context = tools.addMethodsToWorkspace(addMethodSources).context();
            tools = new WorkspaceTools(context);
        }
        // Pin CLI fragments if --infer-context is active
        if (inferContextPrompt != null) {
            for (var f : context.allFragments().toList()) {
                context = context.withPinned(f, true);
            }
        }

        context = normalizeTaskContext(context);
        var finalContextForPush = context;
        cm.pushContext(ctx -> finalContextForPush);
        context = cm.liveContext();

        if (deepScan) {
            if (planModel == null) {
                System.err.println("Deep Scan requires --planmodel to be specified.");
                return 1;
            }

            io.showNotification(IConsoleIO.NotificationRole.INFO, "# Workspace (pre-scan)");
            io.showNotification(
                    IConsoleIO.NotificationRole.INFO,
                    ContextFragment.describe(cm.liveContext().allFragments()));

            String goalForScan = (deepScanGoal != null && !deepScanGoal.equals("true") && !deepScanGoal.isBlank())
                    ? deepScanGoal
                    : "Analyze the workspace and suggest relevant context";

            @Nullable Path deepScanCacheTaskFile = deepScanTaskInfo != null ? deepScanTaskInfo.taskFile : null;

            ContextAgent.RecommendationResult recommendations;
            var cached = readRecommendationFromCache(deepScanCacheTaskFile, cm, project);
            if (cached.isPresent()) {
                recommendations = cached.get();
            } else {
                var agent = new ContextAgent(cm, planModel, goalForScan);
                recommendations = agent.getRecommendations(cm.liveContext());
                if (recommendations.success() && getCacheMode().canWrite()) {
                    writeRecommendationToCache(recommendations, deepScanCacheTaskFile);
                }
            }

            if (recommendations.success()) {
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "Deep Scan suggested "
                                + recommendations.fragments().stream()
                                        .map(ContextFragment::shortDescription)
                                        .toList());
                cm.pushContext(ctx -> addSummariesToTaskContext(ctx, recommendations.fragments()));
            } else {
                io.toolError("Deep Scan did not complete successfully");
            }

            if ("true".equalsIgnoreCase(System.getenv("BRK_COLLECT_METRICS"))) {
                var metrics = SearchMetrics.tracking();
                var filesAddedPaths = recommendations.fragments().stream()
                        .flatMap(f -> f.sourceFiles().renderNowOr(Set.of()).stream())
                        .map(pf -> pf.getRelPath().toString())
                        .collect(Collectors.toSet());
                metrics.recordContextScan(filesAddedPaths.size(), !recommendations.success(), filesAddedPaths);
                metrics.recordOutcome(
                        recommendations.success() ? TaskResult.StopReason.SUCCESS : TaskResult.StopReason.LLM_ERROR,
                        filesAddedPaths.size());
                metrics.recordFinalWorkspaceFiles(filesAddedPaths);
                var json = metrics.toJson(goalForScan, recommendations.success());
                System.err.println("\nBRK_SEARCHAGENT_METRICS=" + json);
            }

            return recommendations.success() ? 0 : 1;
        }

        @Nullable Path cacheTaskFile = null;
        if (architectPrompt != null) {
            cacheTaskFile = architectTaskInfo != null ? architectTaskInfo.taskFile : null;
        } else if (inferContextPrompt != null) {
            cacheTaskFile = inferContextTaskInfo != null ? inferContextTaskInfo.taskFile : null;
        } else if (codePrompt != null) {
            cacheTaskFile = codeTaskInfo != null ? codeTaskInfo.taskFile : null;
        } else if (searchAnswerPrompt != null) {
            cacheTaskFile = searchAnswerTaskInfo != null ? searchAnswerTaskInfo.taskFile : null;
        } else if (lutzPrompt != null) {
            cacheTaskFile = lutzTaskInfo != null ? lutzTaskInfo.taskFile : null;
        }

        CacheApplication cacheApplication = applyContextCacheIfEnabled(cacheTaskFile, cm, project);
        context = cacheApplication.context();
        Optional<ContextAgent.RecommendationResult> cachedContextRec = cacheApplication.cachedRecommendation();
        var explicitContext = context;

        // --- Run Action ---
        io.showNotification(IConsoleIO.NotificationRole.INFO, "# Workspace (pre-task)");
        io.showNotification(
                IConsoleIO.NotificationRole.INFO,
                ContextFragment.describe(cm.liveContext().allFragments()));

        TaskResult result;
        // Decide scope action/input
        String scopeInput;
        if (architectPrompt != null) {
            scopeInput = architectPrompt;
        } else if (inferContextPrompt != null) {
            scopeInput = inferContextPrompt;
        } else if (codePrompt != null) {
            scopeInput = codePrompt;
        } else if (merge) {
            scopeInput = "Merge";
        } else if (searchAnswerPrompt != null) {
            scopeInput = requireNonNull(searchAnswerPrompt);
        } else if (build) {
            scopeInput = "Build";
        } else { // lutzPrompt != null
            scopeInput = requireNonNull(lutzPrompt);
        }

        try (var scope = cm.beginTaskUngrouped(scopeInput)) {
            try {
                if (architectPrompt != null || inferContextPrompt != null) {
                    boolean isInfer = inferContextPrompt != null;
                    String prompt = castNonNull(isInfer ? inferContextPrompt : architectPrompt);
                    @Nullable Path taskFile = isInfer ? cacheTaskFile : null;

                    if (planModel == null) {
                        System.err.println("Error: --architect/--infer-context requires --planmodel to be specified.");
                        return 1;
                    }
                    if (codeModel == null) {
                        System.err.println("Error: --architect/--infer-context requires --codemodel to be specified.");
                        return 1;
                    }

                    ArchitectAgent agent;

                    AtomicReference<Context> discoveredContext = new AtomicReference<>(explicitContext);
                    if (isInfer) {
                        logger.info(
                                "Using context cache mode {} for --infer-context (BRK_CONTEXT_CACHE={})",
                                getCacheMode(),
                                System.getenv("BRK_CONTEXT_CACHE"));

                        agent = new ArchitectAgent(cm, planModel, codeModel, prompt, scope, explicitContext);
                        if (testAllCmd != null) {
                            agent.setVerifyCommand(testAllCmd);
                        }
                        agent.setListener(codeContext -> {
                            var delta = ContextDelta.between(explicitContext, codeContext)
                                    .join();
                            discoveredContext.set(
                                    requireNonNull(discoveredContext.get()).addAsSummaries(delta.addedFragments()));
                        });
                        result = agent.execute();
                    } else {
                        agent = new ArchitectAgent(cm, planModel, codeModel, prompt, scope, explicitContext);
                        result = agent.executeWithScan();
                    }

                    context = result.context();
                    scope.append(result);

                    if (isInfer
                            && getCacheMode().canWrite()
                            && result.stopDetails().reason() == TaskResult.StopReason.SUCCESS) {
                        var delta = ContextDelta.between(explicitContext, requireNonNull(discoveredContext.get()))
                                .join();
                        var finalRec = new ContextAgent.RecommendationResult(true, delta.addedFragments());
                        writeRecommendationToCache(finalRec, taskFile);

                        var baseContext = cachedContextRec
                                .map(recommendationResult ->
                                        addSummariesToTaskContext(explicitContext, recommendationResult.fragments()))
                                .orElse(explicitContext);
                        var recDelta = ContextDelta.between(
                                        baseContext, addSummariesToTaskContext(explicitContext, finalRec.fragments()))
                                .join();
                        var jsonMap = new LinkedHashMap<String, Object>();
                        jsonMap.put("addedFragments", recDelta.addedFragments().size());
                        jsonMap.put(
                                "removedFragments", recDelta.removedFragments().size());

                        try {
                            var jsonString = AbstractProject.objectMapper.writeValueAsString(jsonMap);
                            System.err.println("\nBRK_CONTEXT_METRICS=" + jsonString);
                        } catch (JsonProcessingException e) {
                            logger.warn("Failed to serialize context metrics", e);
                        }
                    }
                } else if (codePrompt != null) {
                    var resolvedCodeModels = resolveCodeModels(service, requestedCodeModelNames);
                    if (resolvedCodeModels.isEmpty()) {
                        System.err.println("Error: --code requires --codemodel to be specified.");
                        return 1;
                    }
                    if (resolvedCodeModels.size() == 1) {
                        var agent =
                                new CodeAgent(cm, resolvedCodeModels.getFirst().model());
                        result = agent.execute(codePrompt, Set.of());
                    } else {
                        result = runMultiModelCodeAgent(
                                codePrompt,
                                resolvedCodeModels,
                                codeGateModelName == null ? null : codeGateModelName.trim());
                    }
                    scope.append(result);
                } else if (merge) {
                    if (planModel == null) {
                        System.err.println("Error: --merge requires --planmodel to be specified.");
                        return 1;
                    }
                    if (codeModel == null) {
                        System.err.println("Error: --merge requires --codemodel to be specified.");
                        return 1;
                    }

                    var conflictOpt = ConflictInspector.inspectFromProject(cm.getProject());
                    if (conflictOpt.isEmpty()) {
                        System.err.println(
                                "Cannot run --merge: Repository is not in a merge/rebase/cherry-pick/revert conflict state");
                        return 1;
                    }
                    var conflict = conflictOpt.get();
                    logger.debug(conflict.toString());
                    MergeAgent mergeAgent = new MergeAgent(
                            cm, planModel, codeModel, conflict, scope, MergeAgent.DEFAULT_MERGE_INSTRUCTIONS);
                    try {
                        result = mergeAgent.execute();
                        // Merge orchestrates planning and code models; TaskMeta is ambiguous here.
                        scope.append(result);
                    } catch (Exception e) {
                        io.toolError(getStackTrace(e), "Merge failed: " + e.getMessage());
                        return 1;
                    }
                    return 0; // merge is terminal for this CLI command
                } else if (searchAnswerPrompt != null) {
                    if (planModel == null) {
                        System.err.println("Error: --search-answer requires --planmodel to be specified.");
                        return 1;
                    }
                    // SearchAgent now handles scanning internally via execute()
                    var agent = new SearchAgent(
                            cm.liveContext(),
                            requireNonNull(searchAnswerPrompt),
                            planModel,
                            SearchPrompts.Objective.ANSWER_ONLY,
                            cm.getIo());
                    result = agent.execute();
                    context = result.context();
                    scope.append(result);
                } else if (build) {
                    String buildError = cm.getProject().getBuildRunner().runVerification(cm);
                    io.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            buildError.isEmpty()
                                    ? "Build verification completed successfully."
                                    : "Build verification failed:\n" + buildError);
                    // we have no `result` since we did not interact with the LLM
                    System.exit(buildError.isEmpty() ? 0 : 1);
                    // make the compiler happy
                    result = null;
                } else { // lutzPrompt != null
                    if (planModel == null) {
                        System.err.println("Error: --lutz requires --planmodel to be specified.");
                        return 1;
                    }
                    if (codeModel == null) {
                        System.err.println("Error: --lutz requires --codemodel to be specified.");
                        return 1;
                    }
                    // SearchAgent now handles scanning internally via execute()
                    var config = new LutzAgent.ScanConfig(true, null, true, false);
                    var agent = new LutzAgent(
                            context,
                            requireNonNull(lutzPrompt),
                            planModel,
                            SearchPrompts.Objective.TASKS_ONLY,
                            scope,
                            cm.getIo(),
                            config);
                    result = agent.execute();
                    context = result.context();
                    scope.append(result);

                    // Execute pending tasks sequentially
                    var tasksData = cm.getTaskList();
                    var pendingTasks =
                            tasksData.tasks().stream().filter(t -> !t.done()).toList();

                    if (!pendingTasks.isEmpty()) {
                        io.showNotification(
                                IConsoleIO.NotificationRole.INFO,
                                "Executing " + pendingTasks.size() + " task" + (pendingTasks.size() == 1 ? "" : "s")
                                        + " from Task List...");

                        for (var task : pendingTasks) {
                            io.showNotification(IConsoleIO.NotificationRole.INFO, "Running task: " + task.text());

                            var taskResult = cm.executeTask(task, planModel, codeModel);
                            scope.append(taskResult);
                            result = taskResult; // Track last result for final status check

                            if (taskResult.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
                                io.toolError(taskResult.stopDetails().explanation(), "Task failed: " + task.text());
                                break; // Stop on first failure
                            }
                        }
                    } else {
                        io.showNotification(IConsoleIO.NotificationRole.INFO, "No pending tasks to execute.");
                    }
                }
            } catch (Throwable th) {
                logger.error("Internal error", th);
                io.toolError(requireNonNull(th.getMessage()), "Internal error");
                return 1; // internal error
            }
        }

        result = castNonNull(result);
        if (result.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
            io.toolError(
                    result.stopDetails().explanation(),
                    result.stopDetails().reason().toString());
            return finalExitCode(
                    codePrompt != null,
                    isSingleTurnCodeAgentEnabled(),
                    result.stopDetails().reason());
        }

        return 0;
    }

    static int finalExitCode(boolean codePromptRequested, boolean singleTurnMode, TaskResult.StopReason reason) {
        if (reason == TaskResult.StopReason.SUCCESS) {
            return 0;
        }
        if (codePromptRequested && singleTurnMode) {
            return 2;
        }
        return 0;
    }

    private static boolean isSingleTurnCodeAgentEnabled() {
        return Environment.isBooleanFlagEnabled(System.getenv("BRK_CODEAGENT_SINGLE_TURN"));
    }

    private static List<String> parseCommaDelimitedPreservingDuplicates(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Stream.of(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private Optional<CodeModelAttempt> resolveCodeModel(AbstractService service, String modelName) {
        Service.FavoriteModel fav;
        try {
            fav = MainProject.getFavoriteModel(modelName);
        } catch (IllegalArgumentException e) {
            System.err.println("Unknown code model specified via --codemodel: " + modelName);
            return Optional.empty();
        }
        var model = service.getModel(fav.config());
        assert model != null : service.getAvailableModels();
        return Optional.of(new CodeModelAttempt(modelName, castNonNull(model)));
    }

    private List<CodeModelAttempt> resolveCodeModels(AbstractService service, List<String> modelNames) {
        List<CodeModelAttempt> resolved = new ArrayList<>();
        for (var modelName : modelNames) {
            var model = resolveCodeModel(service, modelName);
            if (model.isEmpty()) {
                return List.of();
            }
            resolved.add(model.get());
        }
        return resolved;
    }

    private TaskResult runMultiModelCodeAgent(
            String prompt, List<CodeModelAttempt> attempts, @Nullable String gateModelName) throws Exception {
        List<CodeAttemptManifestEntry> manifestEntries = new ArrayList<>();
        int gateAttemptCount = countLeadingGateAttempts(attempts, gateModelName);
        boolean allGateAttemptsOneshot = gateAttemptCount > 0;
        TaskResult finalResult = null;

        for (int index = 0; index < attempts.size(); index++) {
            var attempt = attempts.get(index);
            var preAttemptContext = cm.liveContext();
            preAttemptContext.awaitContentsAreComputed(ContextHistory.SNAPSHOT_AWAIT_TIMEOUT);
            var baselineHead = gitHead(project.getRoot());
            var beforeHistoryDirs = listCodeHistoryDirs(project.getRoot());
            var agent = new CodeAgent(cm, attempt.model());
            var attemptStartedAt = System.nanoTime();
            var attemptResult = agent.execute(prompt, Set.of());
            var elapsedSeconds = (System.nanoTime() - attemptStartedAt) / 1_000_000_000.0;
            var afterHistoryDirs = listCodeHistoryDirs(project.getRoot());
            var codeHistoryDir = findAttemptCodeHistoryDir(project.getRoot(), beforeHistoryDirs, afterHistoryDirs);
            var diff = captureWorkspaceDiff(project.getRoot(), baselineHead);
            var oneshotSuccessful = isOneshotSuccessful(attemptResult, codeHistoryDir, diff);
            var stopDetails = attemptResult.stopDetails();
            var manifestStopReason = normalizeManifestStopReason(stopDetails.reason(), diff);

            manifestEntries.add(new CodeAttemptManifestEntry(
                    index,
                    attempt.modelName(),
                    codeHistoryDir == null
                            ? null
                            : project.getRoot().relativize(codeHistoryDir).toString(),
                    finalExitCode(true, true, stopDetails.reason()),
                    manifestStopReason,
                    stopDetails.explanation(),
                    diff,
                    oneshotSuccessful,
                    elapsedSeconds));
            finalResult = attemptResult;

            boolean stopAfterGate = false;
            if (index < gateAttemptCount) {
                allGateAttemptsOneshot &= oneshotSuccessful;
                stopAfterGate = index == gateAttemptCount - 1 && allGateAttemptsOneshot;
            }
            if (stopAfterGate || index == attempts.size() - 1) {
                break;
            }
            attemptResult.context().awaitContentsAreComputed(ContextHistory.SNAPSHOT_AWAIT_TIMEOUT);
            cm.pushContext(ctx -> attemptResult.context());
            if (!cm.undoContext()) {
                throw new IllegalStateException(
                        "Failed to reset workspace with undo after code-model attempt " + attempt.modelName());
            }
            if (!cm.liveContext().equals(preAttemptContext)) {
                throw new IllegalStateException("Undo after code-model attempt "
                        + attempt.modelName()
                        + " did not restore the original pre-attempt workspace");
            }
        }

        writeSftGenManifest(project.getRoot(), gateModelName, attempts, manifestEntries);

        return requireNonNull(finalResult);
    }

    private static int countLeadingGateAttempts(List<CodeModelAttempt> attempts, @Nullable String gateModelName) {
        if (gateModelName == null || gateModelName.isBlank()) {
            return 0;
        }
        int count = 0;
        for (var attempt : attempts) {
            if (!attempt.modelName().equals(gateModelName)) {
                break;
            }
            count++;
        }
        return count;
    }

    private static void writeSftGenManifest(
            Path projectRoot,
            @Nullable String gateModelName,
            List<CodeModelAttempt> attempts,
            List<CodeAttemptManifestEntry> entries)
            throws IOException {
        var sftGenDir = projectRoot.resolve(AbstractProject.BROKK_DIR).resolve(SFT_GEN_DIR_NAME);
        Files.createDirectories(sftGenDir);
        var manifest = new CodeAttemptManifest(
                gateModelName,
                attempts.stream().map(CodeModelAttempt::modelName).toList(),
                List.copyOf(entries));
        try {
            var json = AbstractProject.objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(manifest);
            AtomicWrites.save(sftGenDir.resolve(SFT_GEN_ATTEMPTS_FILE), json + "\n");
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to serialize SFT manifest", e);
        }
    }

    private static List<Path> listCodeHistoryDirs(Path projectRoot) throws IOException {
        var historyRoot = projectRoot.resolve(AbstractProject.BROKK_DIR).resolve(Llm.HISTORY_DIR_NAME);
        if (!Files.isDirectory(historyRoot)) {
            return List.of();
        }
        try (var stream = Files.list(historyRoot)) {
            return stream.filter(Files::isDirectory)
                    .filter(BprCli::isCodeHistoryDir)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private static boolean isCodeHistoryDir(Path path) {
        var name = path.getFileName().toString();
        return name.startsWith("Code ") || name.contains(" Code ");
    }

    private static @Nullable Path findAttemptCodeHistoryDir(
            Path projectRoot, List<Path> beforeHistoryDirs, List<Path> afterHistoryDirs) {
        Set<String> beforeRelative = beforeHistoryDirs.stream()
                .map(path -> projectRoot.relativize(path).toString())
                .collect(Collectors.toCollection(HashSet::new));
        var newDirs = afterHistoryDirs.stream()
                .filter(path ->
                        !beforeRelative.contains(projectRoot.relativize(path).toString()))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        if (!newDirs.isEmpty()) {
            return newDirs.getLast();
        }
        return afterHistoryDirs.isEmpty() ? null : afterHistoryDirs.getLast();
    }

    private static boolean isOneshotSuccessful(TaskResult result, @Nullable Path codeHistoryDir, String diff)
            throws IOException {
        return result.stopDetails().reason() == TaskResult.StopReason.SUCCESS
                && !diff.isBlank()
                && codeHistoryDir != null
                && countRequestJsonFiles(codeHistoryDir) == 1;
    }

    private static String normalizeManifestStopReason(TaskResult.StopReason reason, String diff) {
        if (reason == TaskResult.StopReason.SUCCESS && diff.isBlank()) {
            return "NO_EDITS";
        }
        return reason.name();
    }

    private static long countRequestJsonFiles(Path codeHistoryDir) throws IOException {
        try (var stream = Files.walk(codeHistoryDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith("-request.json"))
                    .count();
        }
    }

    private static String gitHead(Path projectRoot) throws IOException, InterruptedException {
        return runCommand(projectRoot, List.of("git", "rev-parse", "HEAD"), Set.of(0))
                .trim();
    }

    private static String captureWorkspaceDiff(Path projectRoot, String baselineHead)
            throws IOException, InterruptedException {
        StringBuilder diff = new StringBuilder();
        var currentHead = gitHead(projectRoot);
        if (baselineHead.equals(currentHead)) {
            diff.append(runCommand(
                    projectRoot, List.of("git", "diff", "--binary", "--no-color", baselineHead), Set.of(0, 1)));
        } else {
            diff.append(runCommand(
                    projectRoot,
                    List.of("git", "diff", "--binary", "--no-color", baselineHead, currentHead),
                    Set.of(0, 1)));
            diff.append(runCommand(
                    projectRoot, List.of("git", "diff", "--binary", "--no-color", currentHead), Set.of(0, 1)));
        }
        for (var untrackedPath : listUntrackedFiles(projectRoot)) {
            diff.append(runCommand(
                    projectRoot,
                    List.of("git", "diff", "--binary", "--no-color", "--no-index", "/dev/null", untrackedPath),
                    Set.of(0, 1)));
        }
        return diff.toString();
    }

    private static List<String> listUntrackedFiles(Path projectRoot) throws IOException, InterruptedException {
        var output = runCommand(projectRoot, List.of("git", "ls-files", "--others", "--exclude-standard"), Set.of(0));
        if (output.isBlank()) {
            return List.of();
        }
        return output.lines()
                .filter(line -> !line.isBlank())
                .filter(line -> !line.equals("run-output.txt"))
                .filter(line -> !line.startsWith(AbstractProject.BROKK_DIR + "/"))
                .toList();
    }

    private static String runCommand(Path cwd, List<String> command, Set<Integer> allowedExitCodes)
            throws IOException, InterruptedException {
        var process = new ProcessBuilder(command).directory(cwd.toFile()).start();
        String stdout;
        String stderr;
        try (var stdoutStream = process.getInputStream();
                var stderrStream = process.getErrorStream();
                var stdoutBuffer = new ByteArrayOutputStream();
                var stderrBuffer = new ByteArrayOutputStream()) {
            stdoutStream.transferTo(stdoutBuffer);
            stderrStream.transferTo(stderrBuffer);
            int exitCode = process.waitFor();
            stdout = stdoutBuffer.toString(StandardCharsets.UTF_8);
            stderr = stderrBuffer.toString(StandardCharsets.UTF_8);
            if (!allowedExitCodes.contains(exitCode)) {
                throw new IOException(
                        "Command failed with exit code " + exitCode + ": " + String.join(" ", command) + "\n" + stderr);
            }
        }
        return stdout;
    }

    private List<String> resolveFiles(List<String> inputs, String entityType) {
        // Files can only be added as editable via CLI, so we only consider tracked files
        // and allow listing all tracked files as a primary source.
        Supplier<Collection<ProjectFile>> primarySource =
                () -> project.getRepo().getTrackedFiles();

        return inputs.stream()
                .map(input -> {
                    var pf = cm.toFile(input);
                    if (pf.exists() && project.getRepo().getTrackedFiles().contains(pf)) {
                        return Optional.of(pf);
                    }
                    return resolve(input, primarySource, List::of, ProjectFile::toString, entityType);
                })
                .flatMap(Optional::stream)
                .map(ProjectFile::toString)
                .toList();
    }

    private List<String> resolveClasses(List<String> inputs, IAnalyzer analyzer, String entityType) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        Supplier<Collection<CodeUnit>> source = () ->
                analyzer.getAllDeclarations().stream().filter(CodeUnit::isClass).toList();
        return inputs.stream()
                .map(input -> resolve(input, source, List::of, CodeUnit::fqName, entityType))
                .flatMap(Optional::stream)
                .map(CodeUnit::fqName)
                .toList();
    }

    private <T> Optional<T> resolve(
            String userInput,
            Supplier<Collection<T>> primarySourceSupplier,
            Supplier<Collection<T>> secondarySourceSupplier,
            Function<T, String> nameExtractor,
            String entityType) {
        var primarySource = primarySourceSupplier.get();
        var primaryResult = findUnique(userInput, primarySource, nameExtractor, entityType, "primary source");

        if (primaryResult.isPresent()) {
            return primaryResult;
        }

        // if findUnique returned empty, we need to know if it was because of ambiguity or no matches
        if (!findMatches(userInput, primarySource, nameExtractor, true).isEmpty()) {
            // it was ambiguous; findUnique already printed the error. we must stop.
            return Optional.empty();
        }

        // no matches in primary, so try secondary
        var secondarySource = secondarySourceSupplier.get();
        var secondaryResult = findUnique(userInput, secondarySource, nameExtractor, entityType, "secondary source");

        if (secondaryResult.isPresent()) {
            return secondaryResult;
        }

        // if we are here, there were no unique matches in primary or secondary.
        // if there were no matches at all in either, report "not found"
        if (findMatches(userInput, secondarySource, nameExtractor, true).isEmpty()) {
            System.err.printf("Error: Could not find %s '%s'.%n", entityType, userInput);
        }

        return Optional.empty();
    }

    private <T> Optional<T> findUnique(
            String userInput,
            Collection<T> candidates,
            Function<T, String> nameExtractor,
            String entityType,
            String sourceDescription) {
        // 1. Case-insensitive
        var matches = findMatches(userInput, candidates, nameExtractor, true);
        if (matches.size() == 1) return Optional.of(matches.getFirst());
        if (matches.size() > 1) {
            reportAmbiguity(
                    userInput,
                    matches.stream().map(nameExtractor).toList(),
                    entityType,
                    "case-insensitive, from " + sourceDescription);
            return Optional.empty();
        }

        // 2. Case-sensitive
        matches = findMatches(userInput, candidates, nameExtractor, false);
        if (matches.size() == 1) return Optional.of(matches.getFirst());
        if (matches.size() > 1) {
            reportAmbiguity(
                    userInput,
                    matches.stream().map(nameExtractor).toList(),
                    entityType,
                    "case-sensitive, from " + sourceDescription);
            return Optional.empty();
        }

        return Optional.empty(); // Not found in this source
    }

    private <T> List<T> findMatches(
            String userInput, Collection<T> candidates, Function<T, String> nameExtractor, boolean caseInsensitive) {
        if (caseInsensitive) {
            var lowerInput = userInput.toLowerCase(Locale.ROOT);
            return candidates.stream()
                    .filter(c -> nameExtractor.apply(c).toLowerCase(Locale.ROOT).contains(lowerInput))
                    .toList();
        }
        return candidates.stream()
                .filter(c -> nameExtractor.apply(c).contains(userInput))
                .toList();
    }

    private void reportAmbiguity(String input, List<String> matches, String entityType, String context) {
        System.err.printf(
                "Error: Ambiguous %s '%s' (%s). Found multiple matches:%n%s%n",
                entityType,
                input,
                context,
                matches.stream().map(s -> "  - " + s).collect(Collectors.joining("\n")));
    }

    private static class TaskFileInfo {
        final String content;
        final @Nullable Path taskFile;

        TaskFileInfo(String content, @Nullable Path taskFile) {
            this.content = content;
            this.taskFile = taskFile;
        }
    }

    /*
     * If the prompt begins with '@', treat the remainder as a filename and return the file's contents; otherwise return
     * the original prompt. Also returns the task file path if loaded from @file.
     */
    private TaskFileInfo maybeLoadFromFile(@Nullable String prompt) throws IOException {
        if (prompt == null) {
            prompt = "";
        }
        if (prompt.isBlank() || prompt.charAt(0) != '@') {
            return new TaskFileInfo(prompt, null);
        }
        var path = Path.of(prompt.substring(1));
        return new TaskFileInfo(Files.readString(path), path);
    }

    private String getStackTrace(Throwable throwable) {
        var sb = new StringBuilder();
        for (var element : throwable.getStackTrace()) {
            sb.append(element.toString());
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String getModelsJson() {
        var models = MainProject.loadFavoriteModels();
        var modelInfos = models.stream()
                .map(m -> new ModelInfo(m.alias(), m.config().name()))
                .toList();
        try {
            return AbstractProject.objectMapper.writeValueAsString(modelInfos);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize models list", e);
        }
    }

    /**
     * Model information for JSON serialization.
     */
    private record ModelInfo(String alias, String model) {}

    private record CacheApplication(
            Context context, Optional<ContextAgent.RecommendationResult> cachedRecommendation) {}

    @Blocking
    static Context normalizeTaskContext(Context context) {
        return context.removeSupersededSummaries();
    }

    @Blocking
    static Context addSummariesToTaskContext(Context context, Collection<? extends ContextFragment> fragments) {
        return normalizeTaskContext(context.addAsSummaries(List.copyOf(fragments)));
    }

    private static CacheApplication applyContextCacheIfEnabled(
            @Nullable Path taskFile, ContextManager cm, AbstractProject project) {
        var cached = readRecommendationFromCache(taskFile, cm, project);
        if (cached.isEmpty()) {
            return new CacheApplication(cm.liveContext(), Optional.empty());
        }
        var updated = cm.pushContext(
                ctx -> addSummariesToTaskContext(ctx, cached.get().fragments()));
        return new CacheApplication(updated, cached);
    }

    // -------------------------
    // CA cache helpers (JSON)
    // -------------------------

    private static @Nullable Path getTaskPropertiesFile(@Nullable Path taskFile) {
        if (taskFile == null) return null;
        var fileName = taskFile.getFileName().toString();
        if (!fileName.endsWith(".txt")) return null;
        var propertiesName = fileName.substring(0, fileName.length() - 4) + ".properties";
        return requireNonNull(taskFile.getParent()).resolve(propertiesName);
    }

    private static List<String> parseFromCdl(@Nullable String cdl) {
        if (cdl == null || cdl.isBlank()) return List.of();
        return List.of(cdl.split(","));
    }

    /**
     * Cache mode derived from BRK_CONTEXT_CACHE.
     *
     * Supported values (case-insensitive):
     *  - "RW" (default): read from and write to the cache.
     *  - "READ": read from cache but do not write.
     *  - "WRITE": write to cache but do not read.
     *  - "OFF": neither read from nor write to the cache.
     */
    private enum CacheMode {
        OFF(0),
        READ(1),
        WRITE(2),
        RW(1 | 2);

        private static final int READ_BIT = 1;
        private static final int WRITE_BIT = 2;

        private final int mask;

        CacheMode(int mask) {
            this.mask = mask;
        }

        boolean canRead() {
            return (mask & READ_BIT) != 0;
        }

        boolean canWrite() {
            return (mask & WRITE_BIT) != 0;
        }
    }

    private static CacheMode getCacheMode() {
        String val = System.getenv("BRK_CONTEXT_CACHE");
        if (val == null || val.isBlank()) {
            return CacheMode.RW;
        }
        return switch (val.trim().toUpperCase(Locale.ROOT)) {
            case "READ" -> CacheMode.READ;
            case "WRITE" -> CacheMode.WRITE;
            case "OFF" -> CacheMode.OFF;
            case "RW" -> CacheMode.RW;
            default -> CacheMode.RW;
        };
    }

    static Optional<ContextAgent.RecommendationResult> readRecommendationFromCache(
            @Nullable Path taskFile, ContextManager cm, AbstractProject project) {
        CacheMode mode = getCacheMode();
        if (!mode.canRead()) {
            logger.debug(
                    "Context cache mode {}: skipping read (BRK_CONTEXT_CACHE={})",
                    mode,
                    System.getenv("BRK_CONTEXT_CACHE"));
            return Optional.empty();
        }

        // Only try task-specific properties file
        if (taskFile != null) {
            var propsFile = getTaskPropertiesFile(taskFile);
            if (propsFile != null && Files.exists(propsFile)) {
                try {
                    var props = new Properties();
                    try (var in = Files.newBufferedReader(propsFile)) {
                        props.load(in);
                    }

                    var filesCdl = props.getProperty("files");
                    var classesCdl = props.getProperty("classes");

                    if (filesCdl != null || classesCdl != null) {
                        var files = parseFromCdl(filesCdl);
                        var classes = parseFromCdl(classesCdl);

                        logger.debug(
                                "Read {} files and {} classes from properties cache {}",
                                files.size(),
                                classes.size(),
                                propsFile);

                        var fileFragments = files.stream()
                                .flatMap(fname -> {
                                    var pf = cm.toFile(fname);
                                    if (pf.exists()) {
                                        return Stream.of(fname);
                                    }
                                    String bareName =
                                            Path.of(fname).getFileName().toString();
                                    var matches = project.getRepo().getTrackedFiles().parallelStream()
                                            .filter(f -> f.getFileName().equals(bareName))
                                            .map(ProjectFile::toString)
                                            .toList();

                                    if (matches.size() == 1) {
                                        logger.debug(
                                                "Resolved missing cached file '{}' to '{}'", fname, matches.getFirst());
                                        return matches.stream();
                                    } else if (matches.size() > 1) {
                                        logger.warn(
                                                "Ambiguous resolution for missing cached file '{}': {}",
                                                fname,
                                                matches);
                                        return matches.stream();
                                    } else {
                                        logger.warn("Could not find replacement for missing cached file '{}'", fname);
                                        return Stream.empty();
                                    }
                                })
                                .map(fname -> (ContextFragment) new ContextFragments.SummaryFragment(
                                        cm, fname, ContextFragment.SummaryType.FILE_SKELETONS))
                                .toList();

                        var analyzer = cm.getAnalyzerUninterrupted();
                        List<ContextFragment> classFragments;
                        classFragments = classes.stream()
                                .flatMap(fqcn -> {
                                    if (!analyzer.getDefinitions(fqcn).isEmpty()) {
                                        return Stream.of(fqcn);
                                    }
                                    String simpleName =
                                            fqcn.contains(".") ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn;
                                    var matches = analyzer.searchDefinitions(simpleName, false).stream()
                                            .filter(cu -> cu.isClass()
                                                    && cu.shortName().equals(simpleName))
                                            .map(CodeUnit::fqName)
                                            .toList();

                                    if (matches.size() == 1) {
                                        logger.debug(
                                                "Resolved missing cached class '{}' to '{}'", fqcn, matches.getFirst());
                                        return matches.stream();
                                    } else if (matches.size() > 1) {
                                        logger.warn(
                                                "Ambiguous resolution for missing cached class '{}': {}",
                                                fqcn,
                                                matches);
                                        return matches.stream();
                                    } else {
                                        logger.warn("Could not find replacement for missing cached class '{}'", fqcn);
                                        return Stream.empty();
                                    }
                                })
                                .map(fqcn -> (ContextFragment) new ContextFragments.SummaryFragment(
                                        cm, fqcn, ContextFragment.SummaryType.CODEUNIT_SKELETON))
                                .toList();

                        var allFragments = Streams.concat(fileFragments.stream(), classFragments.stream())
                                .toList();
                        if (allFragments.isEmpty()) {
                            return Optional.empty();
                        }
                        return Optional.of(new ContextAgent.RecommendationResult(true, allFragments));
                    }
                } catch (IOException e) {
                    logger.warn("Failed to read properties cache from {}: {}", propsFile, e.getMessage());
                }
            }
        }

        return Optional.empty();
    }

    static void writeRecommendationToCache(ContextAgent.RecommendationResult rec, @Nullable Path taskFile)
            throws IOException {
        CacheMode mode = getCacheMode();
        if (!mode.canWrite()) {
            logger.debug(
                    "Context cache mode {}: skipping write (BRK_CONTEXT_CACHE={})",
                    mode,
                    System.getenv("BRK_CONTEXT_CACHE"));
            return;
        }

        var files = new ArrayList<String>();
        var classes = new ArrayList<String>();
        for (var cf : rec.fragments()) {
            if (cf instanceof ContextFragments.SummaryFragment sf) {
                if (sf.getSummaryType() == ContextFragment.SummaryType.FILE_SKELETONS) {
                    files.add(sf.getTargetIdentifier());
                } else {
                    classes.add(sf.getTargetIdentifier());
                }
            } else if (cf instanceof ContextFragments.ProjectPathFragment ppf) {
                files.add(ppf.file().toString());
            } else {
                // Ignore unsupported fragments for caching
                logger.debug("Skipping unsupported fragment type for cache: {}", cf.getType());
            }
        }

        // Maybe write to task-specific properties file
        if (taskFile == null) {
            return;
        }
        var propsFile = getTaskPropertiesFile(taskFile);
        if (propsFile == null) {
            return;
        }

        // Load existing properties
        var props = new Properties();
        if (Files.exists(propsFile)) {
            try (var in = Files.newBufferedReader(propsFile)) {
                props.load(in);
            }
        }

        // Update with cache data
        props.setProperty("files", String.join(",", files));
        props.setProperty("classes", String.join(",", classes));

        // Write back, preserving other properties
        try (var out = Files.newBufferedWriter(propsFile)) {
            props.store(out, "Brokk context cache - generated " + Instant.now());
        }
        logger.debug("Wrote {} files and {} classes to properties cache", files.size(), classes.size());
    }
}
