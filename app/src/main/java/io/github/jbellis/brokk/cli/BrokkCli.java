package io.github.jbellis.brokk.cli;

import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.AbstractProject;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.WorktreeProject;
import io.github.jbellis.brokk.agents.ArchitectAgent;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.agents.CodeAgent;
import io.github.jbellis.brokk.agents.ConflictInspector;
import io.github.jbellis.brokk.agents.ContextAgent;
import io.github.jbellis.brokk.agents.MergeAgent;
import io.github.jbellis.brokk.agents.SearchAgent;
import io.github.jbellis.brokk.agents.SearchAgent.Terminal;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.GitRepoFactory;
import io.github.jbellis.brokk.gui.InstructionsPanel;
import io.github.jbellis.brokk.metrics.SearchMetrics;
import io.github.jbellis.brokk.tasks.TaskList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine;

@SuppressWarnings("NullAway.Init") // NullAway is upset that some fields are initialized in picocli's call()
@CommandLine.Command(
        name = "brokk-cli",
        mixinStandardHelpOptions = true,
        description = "One-shot Brokk workspace and task runner.")
public final class BrokkCli implements Callable<Integer> {
    private static final Logger logger = LogManager.getLogger(BrokkCli.class);

    @CommandLine.Option(names = "--project", description = "Path to the project root.")
    @Nullable
    private Path projectPath;

    @CommandLine.Option(
            names = {"--edit", "--read"},
            description = "Add a file to the workspace for editing. Can be repeated.")
    private List<String> editFiles = new ArrayList<>();

    @CommandLine.Option(
            names = "--add-class",
            description = "Add the file containing the given FQCN to the workspace for editing. Can be repeated.")
    private List<String> addClasses = new ArrayList<>();

    @CommandLine.Option(
            names = "--add-url",
            description = "Add content from a URL as a read-only fragment. Can be repeated.")
    private List<String> addUrls = new ArrayList<>();

    @CommandLine.Option(
            names = "--add-usage",
            description = "Add usages of a FQ symbol as a dynamic fragment. Can be repeated.")
    private List<String> addUsages = new ArrayList<>();

    @CommandLine.Option(
            names = "--add-summary-class",
            description = "Add a class summary/skeleton as a dynamic fragment. Can be repeated.")
    private List<String> addSummaryClasses = new ArrayList<>();

    @CommandLine.Option(
            names = "--add-summary-file",
            description = "Add summaries for all classes in a file/glob as a dynamic fragment. Can be repeated.")
    private List<String> addSummaryFiles = new ArrayList<>();

    @CommandLine.Option(
            names = "--add-method-source",
            description = "Add the source of a FQ method as a fragment. Can be repeated.")
    private List<String> addMethodSources = new ArrayList<>();

    @CommandLine.Option(
            names = "--add-callers",
            description = "Add callers of a FQ method. Format: <FQN>=<depth>. Can be repeated.")
    private Map<String, Integer> addCallers = Map.of();

    @CommandLine.Option(
            names = "--add-callees",
            description = "Add callees of a FQ method. Format: <FQN>=<depth>. Can be repeated.")
    private Map<String, Integer> addCallees = Map.of();

    @CommandLine.Option(names = "--architect", description = "Run Architect agent with the given prompt.")
    @Nullable
    private String architectPrompt;

    @CommandLine.Option(names = "--code", description = "Run Code agent with the given prompt.")
    @Nullable
    private String codePrompt;

    @CommandLine.Option(names = "--ask", description = "Run Ask command with the given prompt.")
    @Nullable
    private String askPrompt;

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

    @CommandLine.Option(names = "--lutz-lite", description = "Execute a single task to solve the given issue.")
    @Nullable
    private String lutzLitePrompt;

    @CommandLine.Option(names = "--merge", description = "Run Merge agent to resolve repository conflicts (no prompt).")
    private boolean merge = false;

    @CommandLine.Option(names = "--build", description = "Run verification build on the current workspace.")
    private boolean build = false;

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
            names = "--deepscan",
            description = "Perform a Deep Scan to suggest additional relevant context.")
    private boolean deepScan = false;

    @CommandLine.Option(
            names = "--search-workspace",
            description =
                    "Run Search agent in benchmark mode to find relevant context for the given query. Outputs JSON report to stdout.")
    @Nullable
    private String searchWorkspace;

    @CommandLine.Option(
            names = "--commit",
            description = "Git commit hash to checkout before running search. Used for benchmark reproducibility.")
    @Nullable
    private String commit;

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
        logger.info("Starting Brokk CLI...");
        System.setProperty("java.awt.headless", "true");

        int exitCode = new CommandLine(new BrokkCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {

        // Handle --list-models early exit
        if (listModels) {
            String modelsJson = getModelsJson();
            System.out.println(modelsJson);
            return 0;
        }

        // Validate --project is provided when not using --build-commit or --list-models
        if (projectPath == null) {
            System.err.println("Error: --project is required.");
            return 1;
        }

        // --- Action Validation ---
        long actionCount = Stream.of(
                        architectPrompt,
                        codePrompt,
                        askPrompt,
                        searchAnswerPrompt,
                        lutzPrompt,
                        lutzLitePrompt,
                        searchWorkspace)
                .filter(p -> p != null && !p.isBlank())
                .count();
        if (merge) actionCount++;
        if (build) actionCount++;
        if (actionCount > 1) {
            System.err.println(
                    "At most one action (--architect, --code, --ask, --search-answer, --lutz, --lutz-lite, --merge, --build, --search-workspace) can be specified.");
            return 1;
        }
        if (actionCount == 0 && worktreePath == null) {
            System.err.println(
                    "Exactly one action (--architect, --code, --ask, --search-answer, --lutz, --lutz-lite, --merge, --build, --search-workspace) or --worktree is required.");
            return 1;
        }

        // Add search-workspace validation
        if (searchWorkspace != null && !searchWorkspace.isBlank()) {
            if (codeModelName != null) {
                System.err.println("--codemodel is not valid with --search-workspace.");
                return 1;
            }
        }

        //  Expand @file syntax for prompt parameters
        try {
            architectPrompt = maybeLoadFromFile(architectPrompt);
            codePrompt = maybeLoadFromFile(codePrompt);
            askPrompt = maybeLoadFromFile(askPrompt);
            searchAnswerPrompt = maybeLoadFromFile(searchAnswerPrompt);
            lutzPrompt = maybeLoadFromFile(lutzPrompt);
            lutzLitePrompt = maybeLoadFromFile(lutzLitePrompt);
            searchWorkspace = maybeLoadFromFile(searchWorkspace);
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
            projectPath = worktreePath;
        }

        // Create Project + ContextManager
        var mainProject = new MainProject(projectPath);
        project = worktreePath == null ? mainProject : new WorktreeProject(worktreePath, mainProject);
        cm = new ContextManager(project);

        // Build BuildDetails from environment variables
        String buildLintCmd = System.getenv("BRK_BUILD_CMD");
        String testAllCmd = System.getenv("BRK_TESTALL_CMD");
        String testSomeCmd = System.getenv("BRK_TESTSOME_CMD");
        var buildDetails = new BuildAgent.BuildDetails(
                buildLintCmd != null ? buildLintCmd : "",
                testAllCmd != null ? testAllCmd : "",
                testSomeCmd != null ? testSomeCmd : "",
                Set.of(),
                Map.of("VIRTUAL_ENV", ".venv")); // venv is hardcoded to override swebench task runner
        logger.info("Build Details: " + buildDetails);

        cm.createHeadless(buildDetails);
        var io = cm.getIo();

        //  Model Overrides initialization
        var service = cm.getService();

        StreamingChatModel planModel = null;
        StreamingChatModel codeModel = null;
        StreamingChatModel taskModelOverride = null;

        // Determine which models are required by the chosen action(s).
        boolean needsPlanModel = architectPrompt != null
                || searchAnswerPrompt != null
                || lutzPrompt != null
                || lutzLitePrompt != null
                || deepScan
                || merge
                || (searchWorkspace != null && !searchWorkspace.isBlank());
        boolean needsCodeModel =
                codePrompt != null || askPrompt != null || architectPrompt != null || lutzLitePrompt != null || merge;

        if (needsPlanModel && planModelName == null) {
            System.err.println("Error: This action requires --planmodel to be specified.");
            return 1;
        }
        if (needsCodeModel && codeModelName == null) {
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

        if (codeModelName != null) {
            Service.FavoriteModel fav;
            try {
                fav = MainProject.getFavoriteModel(codeModelName);
            } catch (IllegalArgumentException e) {
                System.err.println("Unknown code model specified via --codemodel: " + codeModelName);
                return 1;
            }
            codeModel = service.getModel(fav.config());
            assert codeModel != null : service.getAvailableModels();
        }

        // --- Search Workspace Mode ---
        if (searchWorkspace != null && !searchWorkspace.isBlank()) {
            long startTime = System.currentTimeMillis();
            TaskResult searchResult;
            boolean success;
            var metrics = (SearchMetrics.Tracking) SearchMetrics.tracking();

            try (var scope = cm.beginTask(searchWorkspace, false)) {
                var searchModel = taskModelOverride == null ? cm.getService().getScanModel() : taskModelOverride;
                var agent = new SearchAgent(
                        cm.liveContext(), searchWorkspace, searchModel, EnumSet.of(Terminal.WORKSPACE), metrics, scope);
                if (disableContextScan) {
                    metrics.recordContextScan(0, 0, true, Set.of());
                } else {
                    agent.scanInitialContext();
                }
                searchResult = agent.execute();
                scope.append(searchResult);
                success = searchResult.stopDetails().reason() == TaskResult.StopReason.SUCCESS;
            } catch (Throwable th) {
                logger.error("Fatal error during SearchAgent execution", th);
                long elapsedTime = System.currentTimeMillis() - startTime;
                String errorMessage = th.getMessage() != null
                        ? th.getMessage()
                        : th.getClass().getName();
                var errorResult = new SearchErrorResult(
                        searchWorkspace, List.of(), -1, elapsedTime, false, "fatal_error", errorMessage);
                try {
                    var json = AbstractProject.objectMapper.writeValueAsString(errorResult);
                    System.out.println(json);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    // Fallback to minimal JSON if serialization fails (schema-consistent)
                    System.out.println("{\"query\": \"error\", \"found_files\": [], \"turns\": -1, \"elapsed_ms\": "
                            + elapsedTime
                            + ", \"success\": false, \"failure_type\": \"fatal_error\"}");
                }
                return 2;
            }

            long elapsedTime = System.currentTimeMillis() - startTime;

            // Extract results from conversation
            var messages = searchResult.output().messages();
            int turns = countTurns(messages);

            // Output enhanced JSON result with metrics
            var json = metrics.toJson(searchWorkspace, turns, elapsedTime, success);
            System.out.println(json);

            return success ? 0 : 1;
        }

        // --- Name Resolution and Context Building ---

        // Resolve files and classes
        var resolvedEditFiles = resolveFiles(editFiles, "editable file");
        var resolvedClasses = resolveClasses(addClasses, cm.getAnalyzer(), "class");
        var resolvedSummaryClasses = resolveClasses(addSummaryClasses, cm.getAnalyzer(), "summary class");

        // If any resolution failed, the helper methods will have printed an error.
        if ((resolvedEditFiles.isEmpty() && !editFiles.isEmpty())
                || (resolvedClasses.isEmpty() && !addClasses.isEmpty())
                || (resolvedSummaryClasses.isEmpty() && !addSummaryClasses.isEmpty())) {
            return 1;
        }

        // Build context
        var analyzer = cm.getAnalyzer();

        if (!resolvedEditFiles.isEmpty())
            cm.addFiles(resolvedEditFiles.stream().map(cm::toFile).toList());

        // Build context
        var context = cm.liveContext();

        if (!resolvedClasses.isEmpty()) context = Context.withAddedClasses(context, resolvedClasses, analyzer);
        if (!resolvedSummaryClasses.isEmpty())
            context = Context.withAddedClassSummaries(context, resolvedSummaryClasses);
        if (!addSummaryFiles.isEmpty()) context = Context.withAddedFileSummaries(context, addSummaryFiles, project);
        if (!addMethodSources.isEmpty()) context = Context.withAddedMethodSources(context, addMethodSources, analyzer);

        // Add URLs (simple fragments)
        for (var url : addUrls) {
            try {
                context = Context.withAddedUrlContent(context, url);
            } catch (Exception e) {
                logger.error("Failed to add URL content: {}", url, e);
                System.err.println("Error adding URL " + url + ": " + e.getMessage());
                return 1;
            }
        }

        // Add usages, callers, callees (simple fragment creation)
        for (var symbol : addUsages) {
            var fragment = new ContextFragment.UsageFragment(cm, symbol);
            context = context.addVirtualFragment(fragment);
        }
        for (var entry : addCallers.entrySet()) {
            var fragment = new ContextFragment.CallGraphFragment(cm, entry.getKey(), entry.getValue(), false);
            context = context.addVirtualFragment(fragment);
        }
        for (var entry : addCallees.entrySet()) {
            var fragment = new ContextFragment.CallGraphFragment(cm, entry.getKey(), entry.getValue(), true);
            context = context.addVirtualFragment(fragment);
        }

        // Push accumulated context changes back to ContextManager
        var finalContext = context;
        cm.pushContext(ctx -> finalContext);

        // --- Deep Scan ------------------------------------------------------
        if (deepScan) {
            if (planModel == null) {
                System.err.println("Deep Scan requires --planmodel to be specified.");
                return 1;
            }

            io.showNotification(IConsoleIO.NotificationRole.INFO, "# Workspace (pre-scan)");
            io.showNotification(
                    IConsoleIO.NotificationRole.INFO,
                    ContextFragment.describe(cm.topContext().allFragments()));

            String goalForScan = Stream.of(architectPrompt, codePrompt, askPrompt, searchAnswerPrompt, lutzPrompt)
                    .filter(s -> s != null && !s.isBlank())
                    .findFirst()
                    .orElseThrow();
            var agent = new ContextAgent(cm, planModel, goalForScan);
            var recommendations = agent.getRecommendations(cm.liveContext());
            io.showNotification(
                    IConsoleIO.NotificationRole.INFO, "Deep Scan token usage: " + recommendations.tokenUsage());

            if (recommendations.success()) {
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "Deep Scan suggested "
                                + recommendations.fragments().stream()
                                        .map(ContextFragment::shortDescription)
                                        .toList());
                for (var fragment : recommendations.fragments()) {
                    switch (fragment.getType()) {
                        case SKELETON -> {
                            cm.addVirtualFragment((ContextFragment.VirtualFragment) fragment);
                            io.showNotification(IConsoleIO.NotificationRole.INFO, "Added " + fragment);
                        }
                        default -> cm.addSummaries(fragment.files(), Set.of());
                    }
                }
            } else {
                io.toolError("Deep Scan did not complete successfully: " + recommendations.reasoning());
            }
        }

        // --- Run Action ---
        io.showNotification(IConsoleIO.NotificationRole.INFO, "# Workspace (pre-task)");
        io.showNotification(
                IConsoleIO.NotificationRole.INFO,
                ContextFragment.describe(cm.topContext().allFragments()));

        TaskResult result;
        // Decide scope action/input
        String scopeInput;
        if (architectPrompt != null) {
            scopeInput = architectPrompt;
        } else if (codePrompt != null) {
            scopeInput = codePrompt;
        } else if (askPrompt != null) {
            scopeInput = requireNonNull(askPrompt);
        } else if (merge) {
            scopeInput = "Merge";
        } else if (searchAnswerPrompt != null) {
            scopeInput = requireNonNull(searchAnswerPrompt);
        } else if (build) {
            scopeInput = "Build";
        } else if (lutzLitePrompt != null) {
            scopeInput = requireNonNull(lutzLitePrompt);
        } else { // lutzPrompt != null
            scopeInput = requireNonNull(lutzPrompt);
        }

        try (var scope = cm.beginTask(scopeInput, false)) {
            try {
                if (architectPrompt != null) {
                    // Architect requires a plan model and a code model
                    if (planModel == null) {
                        System.err.println("Error: --architect requires --planmodel to be specified.");
                        return 1;
                    }
                    if (codeModel == null) {
                        System.err.println("Error: --architect requires --codemodel to be specified.");
                        return 1;
                    }
                    var agent = new ArchitectAgent(cm, planModel, codeModel, architectPrompt, scope);
                    result = agent.execute();
                    context = scope.append(result);
                } else if (codePrompt != null) {
                    // CodeAgent must use codemodel only
                    if (codeModel == null) {
                        System.err.println("Error: --code requires --codemodel to be specified.");
                        return 1;
                    }
                    var agent = new CodeAgent(cm, codeModel);
                    result = agent.runTask(codePrompt, Set.of());
                    context = scope.append(result);
                } else if (askPrompt != null) {
                    if (codeModel == null) {
                        System.err.println("Error: --ask requires --codemodel to be specified.");
                        return 1;
                    }
                    result = InstructionsPanel.executeAskCommand(cm, codeModel, askPrompt);
                    context = scope.append(result);
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
                        context = scope.append(result);
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
                    var agent = new SearchAgent(
                            cm.liveContext(),
                            requireNonNull(searchAnswerPrompt),
                            planModel,
                            EnumSet.of(Terminal.ANSWER),
                            SearchMetrics.noOp(),
                            scope);
                    agent.scanInitialContext();
                    result = agent.execute();
                    context = scope.append(result);
                } else if (build) {
                    String buildError = BuildAgent.runVerification(cm);
                    io.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            buildError.isEmpty()
                                    ? "Build verification completed successfully."
                                    : "Build verification failed:\n" + buildError);
                    // we have no `result` since we did not interact with the LLM
                    System.exit(buildError.isEmpty() ? 0 : 1);
                    // make the compiler happy
                    result = null;
                } else if (lutzLitePrompt != null) {
                    if (planModel == null) {
                        System.err.println("Error: --lutz-lite requires --planmodel to be specified.");
                        return 1;
                    }
                    if (codeModel == null) {
                        System.err.println("Error: --lutz-lite requires --codemodel to be specified.");
                        return 1;
                    }

                    var taskText =
                            """
                            Solve the following issue. Pull appropriate existing tests into the Workspace; if you are adding new functionality, add new tests if you can do so within the existing constraints.

                            Issue: """
                                    + requireNonNull(lutzLitePrompt);
                    var task = new TaskList.TaskItem(taskText, false);

                    io.showNotification(IConsoleIO.NotificationRole.INFO, "Executing task...");
                    var taskResult = cm.executeTask(task, planModel, codeModel, true, true);
                    context = scope.append(taskResult);
                    result = taskResult;
                } else { // lutzPrompt != null
                    if (planModel == null) {
                        System.err.println("Error: --lutz requires --planmodel to be specified.");
                        return 1;
                    }
                    if (codeModel == null) {
                        System.err.println("Error: --lutz requires --codemodel to be specified.");
                        return 1;
                    }
                    var agent = new SearchAgent(
                            cm.liveContext(),
                            requireNonNull(lutzPrompt),
                            planModel,
                            EnumSet.of(Terminal.TASK_LIST),
                            SearchMetrics.noOp(),
                            scope);
                    agent.scanInitialContext();
                    result = agent.execute();
                    context = scope.append(result);

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

                            var taskResult = cm.executeTask(task, planModel, codeModel, true, true);
                            context = scope.append(taskResult);
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
            // exit code is 0 since we ran the task as requested; we print out the metrics from Code Agent to let
            // harness see how we did
        }

        return 0;
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

    /*
     * If the prompt begins with '@', treat the remainder as a filename and return the file's contents; otherwise return
     * the original prompt.
     */
    private @Nullable String maybeLoadFromFile(@Nullable String prompt) throws IOException {
        if (prompt == null || prompt.isBlank() || prompt.charAt(0) != '@') {
            return prompt;
        }
        var path = Path.of(prompt.substring(1));
        return Files.readString(path);
    }

    private String getStackTrace(Throwable throwable) {
        var sb = new StringBuilder();
        for (var element : throwable.getStackTrace()) {
            sb.append(element.toString());
            sb.append("\n");
        }
        return sb.toString();
    }

    private static int countTurns(List<ChatMessage> messages) {
        // Count AI messages as turns
        return (int) messages.stream()
                .filter(msg -> msg.type() == ChatMessageType.AI)
                .count();
    }

    private static String getModelsJson() {
        var models = MainProject.loadFavoriteModels();
        var modelInfos = models.stream()
                .map(m -> new ModelInfo(m.alias(), m.config().name()))
                .toList();
        try {
            return AbstractProject.objectMapper.writeValueAsString(modelInfos);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize models list", e);
        }
    }

    /**
     * Model information for JSON serialization.
     */
    private record ModelInfo(String alias, String model) {}

    /**
     * Error result for search-workspace mode failures.
     * Schema matches SearchMetrics.SearchResult for consistency.
     */
    private record SearchErrorResult(
            String query,
            List<String> found_files,
            int turns,
            long elapsed_ms,
            boolean success,
            String failure_type,
            String error) {}
}
