package ai.brokk.cli;

import static java.util.Objects.requireNonNull;

import ai.brokk.AbstractService.ModelConfig;
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
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.project.ModelProperties;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.tools.SearchTools;
import ai.brokk.tools.WorkspaceTools;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine;

@CommandLine.Command(
        name = "brokk",
        version = "Brokk " + ai.brokk.BuildInfo.version,
        mixinStandardHelpOptions = true,
        description = "Brokk CLI - AI-powered code assistant.")
public final class BrokkCli implements Callable<Integer> {
    private static final Logger logger = LogManager.getLogger(BrokkCli.class);

    private static final Set<String> GOAL_REQUIRED_COMMANDS =
            Set.of("scan", "code", "find-symbols", "find-usages", "list-identifiers");

    @CommandLine.ArgGroup(exclusive = true, multiplicity = "0..1")
    @Nullable
    private CommandSelection commandSelection;

    static final class Actions {
        @CommandLine.Option(
                names = {"install", "--install"},
                hidden = true)
        boolean install;

        @CommandLine.Option(names = "login", paramLabel = "KEY", description = "Set the global Brokk API key.")
        @Nullable
        String loginKey;

        @CommandLine.Option(names = "logout", description = "Clear the global Brokk API key.")
        boolean logout;

        @CommandLine.Option(names = "status", description = "Display project settings and account balance.")
        boolean status;
    }

    @CommandLine.ArgGroup(exclusive = false, heading = "%n## Task Parameters%n")
    private final TaskConfig taskConfig = new TaskConfig();

    static final class TaskConfig {
        @CommandLine.Option(
                names = "--goal",
                description = "Goal/prompt for the operation. Supports loading from file with @path/to/file.")
        @Nullable
        String goal;

        @CommandLine.Option(
                names = "--file",
                arity = "1..*",
                description =
                        "Add files for editing with --code, or to narrow the radius of a --scan (space-separated or repeatable).")
        List<String> files = new ArrayList<>();

        @CommandLine.Option(
                names = "--autocommit",
                description = "Automatically commit changes after a successful --code task. Default: false.")
        boolean autocommit = false;

        @CommandLine.Option(
                names = "--new-session",
                description =
                        "Create a fresh session instead of resuming the most recent one. Do this once per project. Default: false.")
        boolean newSession = false;

        @CommandLine.Option(
                names = "--include-tests",
                description = "Include test files in search results (scan, usages, symbols). Default: false.")
        boolean includeTests = false;
    }

    @CommandLine.ArgGroup(exclusive = false, heading = "%n## Project Selection (Optional)%n")
    private final ProjectSelectionConfig projectSelectionConfig = new ProjectSelectionConfig();

    static final class ProjectSelectionConfig {
        @CommandLine.Option(
                names = "--project",
                defaultValue = ".",
                description = "Path to the project root. Default: current directory.")
        Path projectPath = Path.of(".");
    }

    @CommandLine.ArgGroup(
            exclusive = false,
            heading =
                    """
            %n## Project Configuration (Optional)

            ### How Test Templating Works in --test-some-cmd
              Brokk uses Mustache templates to run specific tests based on your workspace context.
              Variables available:
                {{#files}}      The relative paths of identified test files (e.g. src/test/FooTest.java).
                {{#classes}}    The simple names of test classes (e.g. FooTest).
                {{#fqclasses}}  The fully qualified names of test classes (e.g. com.example.FooTest).
                {{#modules}}    (Python) Dotted module labels (e.g. tests.test_foo).
                                Derived relative to a detected module anchor (like a 'tests/' directory or
                                the root of a package chain containing __init__.py files).

              Mustache 'DecoratedCollection' is used for lists. You can use {{value}} for the item,
              and {{^last}},{{/last}} to handle separators between items.

            #### Example (Maven):
                --test-some-cmd "mvn --quiet test -Dtest={{#classes}}{{value}}{{^last}},{{/last}}{{/classes}}"

            --build-only-cmd and --test-all-cmd are just static commands with no interpolation. When configuring a project
            for the first time, specify --build-only-cmd and EITHER --test-all-cmd or --test-some-cmd. Subsequently,
            Brokk will remember the settings you gave it and you will not have to repeat them.
            """)
    private final BuildTestConfig buildTestConfig = new BuildTestConfig();

    static final class BuildTestConfig {
        @CommandLine.Option(
                names = "--build-only-cmd",
                description = "Build/lint command (no tests). e.g. 'mvn compile' or 'cargo check'.")
        @Nullable
        String buildOnlyCmd;

        @CommandLine.Option(
                names = "--test-all-cmd",
                description =
                        "Command to run all tests. WARNING: This may take minutes or hours in large repositories.")
        @Nullable
        String testAllCmd;

        @CommandLine.Option(
                names = "--test-some-cmd",
                description =
                        "Mustache template for running specific tests. Supports {{#files}}, {{#classes}}, {{#fqclasses}}, and {{#modules}} (Python).")
        @Nullable
        String testSomeCmd;
    }

    static final class CommandSelection {
        @CommandLine.ArgGroup(exclusive = true, multiplicity = "1", heading = "%n## Commands (Account)%n")
        @Nullable
        Actions actions;

        @CommandLine.ArgGroup(
                exclusive = true,
                multiplicity = "1",
                heading =
                        """
                    %n## Commands (Agentic Search)

                    Tools to retrieve symbols and call sites based on AST parsing.

                    --goal is required for all commands in this section, since if the volume is very high,
                    results may be summarized based on relevance to the natural-language --goal.

                    """)
        @Nullable
        AgenticSearchCommands agenticSearch;

        @CommandLine.ArgGroup(exclusive = true, multiplicity = "1", heading = "%n## Commands (Semantic Retrieval)%n")
        @Nullable
        SemanticRetrievalCommands semanticRetrieval;

        @CommandLine.ArgGroup(exclusive = true, multiplicity = "1", heading = "%n## Commands (Agentic Coding)%n")
        @Nullable
        AgenticCodingCommands agenticCoding;
    }

    static final class AgenticSearchCommands {
        @CommandLine.Option(
                names = "scan",
                description =
                        "Agentic scan for relevant files and classes. Run this first. Uses the current Context (can be narrowed with --file) to guide the search.")
        boolean scan;

        @CommandLine.Option(
                names = "find-symbols",
                arity = "1..*",
                description = "Symbol search using regex patterns (space-separated or repeatable).")
        List<String> findSymbolsPatterns = new ArrayList<>();

        @CommandLine.Option(
                names = "find-usages",
                arity = "1..*",
                description = "Returns the source code of blocks where symbols are used.")
        List<String> findUsagesTargets = new ArrayList<>();

        @CommandLine.Option(
                names = "list-identifiers",
                description = "Lists all identifiers in each file within a directory (lightweight summary).")
        @Nullable
        String listIdentifiersPath;
    }

    static final class SemanticRetrievalCommands {
        @CommandLine.Option(
                names = "fetch-summary",
                arity = "1..*",
                description =
                        "Returns all declarations (public/private) for specified targets. Targets must be fully qualified class names (e.g. com.example.Foo) or full, project-relative file paths (e.g. src/main/java/com/example/Foo.java).")
        List<String> fetchSummaryTargets = new ArrayList<>();

        @CommandLine.Option(
                names = "fetch-source",
                arity = "1..*",
                description =
                        "Returns the full source code of specific classes or methods. Class targets must be fully qualified (e.g. com.example.Foo). File targets must be full, project-relative paths (e.g. src/main/java/com/example/Foo.java).")
        List<String> fetchSourceTargets = new ArrayList<>();
    }

    static final class AgenticCodingCommands {
        @CommandLine.Option(
                names = "code",
                description =
                        "Implement the changes asked for in --goal. Will search for relevant files if none are provided via --file.")
        boolean code;

        @CommandLine.Option(names = "merge", description = "Solves all merge conflicts in the repo.")
        boolean merge = false;

        @CommandLine.Option(names = "build", description = "Run build verification without making changes.")
        boolean build = false;
    }

    @CommandLine.ArgGroup(exclusive = false, heading = "%n## Model Selection (Optional)%n")
    private ModelParam modelParam = new ModelParam();

    static final class ModelParam {
        @CommandLine.Option(
                names = "--codemodel",
                description = "Code model name (e.g. 'gpt-5'). Overwrites project setting if provided.")
        @Nullable
        String codeModelName;

        @CommandLine.Option(
                names = "--planmodel",
                description = "Planning model name (e.g. 'claude-opus-4-5'). Overwrites project setting if provided.")
        @Nullable
        String planModelName;
    }

    @MonotonicNonNull
    private ContextManager cm;

    public static void main(String[] args) {
        logger.info("Starting Brokk CLI...");
        System.setProperty("java.awt.headless", "true");

        var cli = new BrokkCli();
        var cmd = new CommandLine(cli);

        try {
            var parseResult = cmd.parseArgs(args);

            configureUsageMessage(cmd);

            if (parseResult.isUsageHelpRequested()) {
                cmd.usage(System.out);
                System.exit(0);
            }

            if (parseResult.isVersionHelpRequested()) {
                cmd.printVersionHelp(System.out);
                System.exit(0);
            }
        } catch (CommandLine.ParameterException e) {
            configureUsageMessage(cmd);
        }

        int exitCode = cmd.execute(args);
        System.exit(exitCode);
    }

    private static Path normalizeProjectPath(Path projectPath) {
        return projectPath.toAbsolutePath().normalize();
    }

    private static void configureUsageMessage(CommandLine cmd) {
        var goalOpt = cmd.getCommandSpec().findOption("--goal");
        if (goalOpt != null) {
            String[] desc = goalOpt.description();
            if (desc.length > 0) {
                desc[0] =
                        "Goal/prompt for the operation. Required for all Agentic Search commands and for code. Supports loading from file with @path/to/file.";
            }
        }
    }

    private CommandLine configuredCommandLine() {
        var cmd = new CommandLine(this);
        configureUsageMessage(cmd);
        return cmd;
    }

    @Override
    @Blocking
    public Integer call() throws Exception {
        if (commandSelection == null) {
            configuredCommandLine().usage(System.out);
            return 0;
        }

        if (commandSelection.actions != null) {
            var actions = commandSelection.actions;

            if (actions.install) {
                return runInstall();
            }

            if (actions.loginKey != null) {
                MainProject.setBrokkKey(actions.loginKey);
                try {
                    float balance = ai.brokk.Service.getUserBalance(actions.loginKey);
                    System.out.printf("Global Brokk API key updated. Current Brokk balance: $%.2f%n", balance);
                } catch (Exception e) {
                    System.err.println(
                            "Error: Failed to validate Brokk API key by fetching balance: " + e.getMessage());
                    return 1;
                }
                return 0;
            }

            if (actions.logout) {
                MainProject.setBrokkKey("");
                System.out.println("Global Brokk API key cleared.");
                return 0;
            }

            if (actions.status) {
                Path projectPath = normalizeProjectPath(projectSelectionConfig.projectPath);
                System.out.println("Current Settings:");
                System.out.println("  Project:   " + projectPath);

                if (Files.isDirectory(projectPath) && GitRepoFactory.hasGitRepo(projectPath)) {
                    try (var p = new MainProject(projectPath)) {
                        System.out.println("  Architect: "
                                + p.getModelConfig(ModelProperties.ModelType.ARCHITECT)
                                        .name());
                        System.out.println("  Code:      "
                                + p.getModelConfig(ModelProperties.ModelType.CODE)
                                        .name());

                        p.loadBuildDetails().ifPresent(bd -> {
                            if (!bd.buildLintCommand().isBlank()) {
                                System.out.println("  Build:     " + bd.buildLintCommand());
                            } else {
                                System.out.println("  No build configured");
                            }

                            boolean isWorkspaceScope =
                                    p.getCodeAgentTestScope() == IProject.CodeAgentTestScope.WORKSPACE;
                            if (isWorkspaceScope && !bd.testSomeCommand().isBlank()) {
                                System.out.println("  Test (Some): " + bd.testSomeCommand());
                            } else if (!bd.testAllCommand().isBlank()) {
                                System.out.println("  Test (All):  " + bd.testAllCommand());
                            } else {
                                System.out.println("  No tests configured");
                            }
                        });
                    }
                } else {
                    System.out.println("  (Not a Brokk project directory)");
                }

                System.out.println("\nAvailable Models:");
                var models = MainProject.loadFavoriteModels();
                for (var model : models) {
                    System.out.println("  " + model.config().name());
                }

                String key = MainProject.getBrokkKey();
                if (!key.isBlank()) {
                    try {
                        float balance = ai.brokk.Service.getUserBalance(key);
                        System.out.printf("\nBrokk balance: $%.2f%n", balance);
                    } catch (Exception e) {
                        System.out.println("\nBrokk balance: (Unable to fetch)");
                    }
                } else {
                    System.out.println("\nBrokk balance: (No API key found; login with 'login [key]')");
                }
                return 0;
            }

            configuredCommandLine().usage(System.out);
            return 0;
        }

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

        // --- Mode Mapping & Validation ---

        boolean scan = false;
        boolean code = false;
        boolean merge = false;
        boolean build = false;
        boolean autocommit = taskConfig.autocommit;

        List<String> findSymbolsPatterns = List.of();
        @Nullable String listIdentifiersPath = null;
        List<String> fetchSourceTargets = List.of();
        List<String> findUsagesTargets = List.of();
        List<String> fetchSummaryTargets = List.of();

        String selectedCommand;
        if (commandSelection.agenticSearch != null) {
            var as = commandSelection.agenticSearch;

            scan = as.scan;

            boolean findSymbolsSpecified = !as.findSymbolsPatterns.isEmpty();
            boolean findUsagesSpecified = !as.findUsagesTargets.isEmpty();
            boolean listIdentifiersSpecified = as.listIdentifiersPath != null;

            findSymbolsPatterns =
                    as.findSymbolsPatterns.stream().filter(s -> !s.isBlank()).toList();
            listIdentifiersPath = as.listIdentifiersPath;
            findUsagesTargets =
                    as.findUsagesTargets.stream().filter(s -> !s.isBlank()).toList();

            if (scan) {
                selectedCommand = "scan";
            } else if (findSymbolsSpecified) {
                selectedCommand = "find-symbols";
            } else if (findUsagesSpecified) {
                selectedCommand = "find-usages";
            } else if (listIdentifiersSpecified) {
                selectedCommand = "list-identifiers";
            } else {
                configuredCommandLine().usage(System.out);
                return 0;
            }
        } else if (commandSelection.semanticRetrieval != null) {
            var sr = commandSelection.semanticRetrieval;

            boolean fetchSummarySpecified = !sr.fetchSummaryTargets.isEmpty();
            boolean fetchSourceSpecified = !sr.fetchSourceTargets.isEmpty();

            fetchSourceTargets =
                    sr.fetchSourceTargets.stream().filter(s -> !s.isBlank()).toList();
            fetchSummaryTargets =
                    sr.fetchSummaryTargets.stream().filter(s -> !s.isBlank()).toList();

            if (fetchSummarySpecified) {
                selectedCommand = "fetch-summary";
            } else if (fetchSourceSpecified) {
                selectedCommand = "fetch-source";
            } else {
                configuredCommandLine().usage(System.out);
                return 0;
            }
        } else if (commandSelection.agenticCoding != null) {
            var ac = commandSelection.agenticCoding;

            code = ac.code;
            merge = ac.merge;
            build = ac.build;

            if (code) {
                selectedCommand = "code";
            } else if (merge) {
                selectedCommand = "merge";
            } else if (build) {
                selectedCommand = "build";
            } else {
                configuredCommandLine().usage(System.out);
                return 0;
            }
        } else {
            configuredCommandLine().usage(System.out);
            return 0;
        }

        // --- Goal Validation ---
        boolean needsGoal = GOAL_REQUIRED_COMMANDS.contains(selectedCommand);

        if (needsGoal && (goal == null || goal.isBlank())) {
            System.err.printf(
                    """
                    # Error: Missing required --goal

                    --goal is required for the following commands:
                      %s

                    ### Notes
                      - You can pass a literal string: --goal "Explain how X works"
                      - You can load from a file:     --goal "@path/to/goal.txt"
                    %n""",
                    String.join(", ", GOAL_REQUIRED_COMMANDS.stream().sorted().toList()));
            return 1;
        }

        // --- Build Command Validation ---
        if (buildTestConfig.testAllCmd != null && buildTestConfig.testSomeCmd != null) {
            System.err.println(
                    """
                    # Error: Mutually exclusive test command options

                    Cannot specify both --test-all-cmd and --test-some-cmd.
                    Choose one depending on whether you want to run all tests or specific workspace tests.
                    """);
            return 1;
        }

        // --- Project Path Validation ---
        Path projectPath = normalizeProjectPath(projectSelectionConfig.projectPath);
        if (!Files.isDirectory(projectPath)) {
            System.err.printf(
                    """
                                      # Error: Invalid --project path

                                      The provided path is not a directory:
                                        %s
                                      %n""",
                    projectPath);
            return 1;
        }
        if (!GitRepoFactory.hasGitRepo(projectPath)) {
            System.err.printf(
                    """
                                      # Error: Not a Git repository

                                      Brokk CLI requires --project to point at a Git worktree (a directory containing a .git folder or file).

                                      Provided:
                                        %s
                                      %n""",
                    projectPath);
            return 1;
        }

        // --- Create Project and ContextManager ---
        AbstractProject project = new MainProject(projectPath);
        cm = new ContextManager(project);

        // --- Build Details ---
        var existingDetails = project.loadBuildDetails().orElse(BuildAgent.BuildDetails.EMPTY);
        BuildAgent.BuildDetails bd = existingDetails;
        if (buildTestConfig.buildOnlyCmd != null
                || buildTestConfig.testAllCmd != null
                || buildTestConfig.testSomeCmd != null) {
            // If a command is provided on CLI, it overwrites existing.
            // If one command type is provided, we clear the others to ensure the mode selection is unambiguous.
            String buildCmd = buildTestConfig.buildOnlyCmd != null
                    ? buildTestConfig.buildOnlyCmd
                    : existingDetails.buildLintCommand();
            String testAll = existingDetails.testAllCommand();
            String testSome = existingDetails.testSomeCommand();
            if (buildTestConfig.testAllCmd != null) {
                testAll = buildTestConfig.testAllCmd;
                testSome = ""; // Mutually exclusive in CLI logic
            } else if (buildTestConfig.testSomeCmd != null) {
                testSome = buildTestConfig.testSomeCmd;
                testAll = ""; // Mutually exclusive in CLI logic
            }

            Map<String, String> env = existingDetails.environmentVariables();
            if (!env.containsKey("VIRTUAL_ENV")) {
                var newEnv = new HashMap<>(env);
                newEnv.put("VIRTUAL_ENV", ".venv");
                env = Map.copyOf(newEnv);
            }

            bd = new BuildAgent.BuildDetails(
                    buildCmd,
                    testAll,
                    testSome,
                    existingDetails.exclusionPatterns(),
                    env,
                    existingDetails.maxBuildAttempts());
            project.setBuildDetails(bd);
            project.saveBuildDetails(bd);

            // Configure test scope based on which test command was provided
            if (buildTestConfig.testAllCmd != null) {
                project.setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);
            } else if (buildTestConfig.testSomeCmd != null) {
                project.setCodeAgentTestScope(IProject.CodeAgentTestScope.WORKSPACE);
            }
        }

        if (build) {
            if (bd.equals(BuildAgent.BuildDetails.EMPTY)
                    || bd.buildLintCommand().isBlank()) {
                System.err.print(
                        """
                        # Error: --build requires a build command

                        No build command was provided or configured.

                        """);
                System.err.println(extractUsageSection(
                        renderUsage(configuredCommandLine()), "## Project Configuration (Optional)"));
                return 1;
            }
        }

        if (code) {
            boolean missingBuild = bd.equals(BuildAgent.BuildDetails.EMPTY)
                    || bd.buildLintCommand().isBlank();
            boolean missingTests =
                    bd.testAllCommand().isBlank() && bd.testSomeCommand().isBlank();

            if (missingBuild || missingTests) {
                var missingLines = new ArrayList<String>();
                if (missingBuild) {
                    missingLines.add("  - Build command (configure with --build-only-cmd \"...\")");
                }
                if (missingTests) {
                    missingLines.add(
                            "  - Test command (configure with --test-some-cmd \"...\" or --test-all-cmd \"...\")");
                }

                System.err.printf(
                        """
                        # Error: Missing required project commands for --code

                        The following required configuration is missing:
                        %s
                        %n""",
                        String.join("\n", missingLines));
                System.err.println(extractUsageSection(
                        renderUsage(configuredCommandLine()), "## Project Configuration (Optional)"));
                return 1;
            }
        }

        // --- Model Resolution ---
        resolveAndSaveModels(project);

        cm.createHeadless(bd, taskConfig.newSession);
        // even if we're in the same session, clear the Workspace to start fresh
        cm.dropWithHistorySemantics(List.of());
        var service = cm.getService();

        StreamingChatModel codeModel = service.getModel(project.getModelConfig(ModelProperties.ModelType.CODE));
        StreamingChatModel planModel = service.getModel(project.getModelConfig(ModelProperties.ModelType.ARCHITECT));

        if (codeModel == null || planModel == null) {
            System.err.println("Error: Failed to initialize models. Check your API key or model configuration.");
            return 1;
        }

        // --- Context Injection ---
        if (scan || code) {
            var tools = new WorkspaceTools(cm.liveContext());
            if (!taskConfig.files.isEmpty()) {
                tools.addFilesToWorkspace(taskConfig.files);
                cm.pushContext(ctx -> tools.getContext());
            }
        }

        // --- Search Tool Commands (standalone) ---
        if (selectedCommand.startsWith("find-")
                || selectedCommand.startsWith("fetch-")
                || selectedCommand.equals("list-identifiers")) {
            var searchTools = new SearchTools(cm);
            boolean includeTests = taskConfig.includeTests;

            switch (selectedCommand) {
                case "find-symbols" ->
                    System.out.println(
                            searchTools.searchSymbols(findSymbolsPatterns, requireNonNull(goal), includeTests));
                case "find-usages" ->
                    System.out.println(searchTools.getUsages(findUsagesTargets, requireNonNull(goal), includeTests));
                case "fetch-summary" -> {
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
                case "fetch-source" -> {
                    // Try as classes first, then as methods
                    var analyzer = cm.getAnalyzer();
                    var allDecls = analyzer.getAllDeclarations();
                    var classNames = allDecls.stream()
                            .filter(CodeUnit::isClass)
                            .map(CodeUnit::fqName)
                            .collect(Collectors.toSet());

                    var classes = fetchSourceTargets.stream()
                            .filter(classNames::contains)
                            .toList();
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
                case "list-identifiers" ->
                    System.out.println(
                            searchTools.skimDirectory(requireNonNull(listIdentifiersPath), requireNonNull(goal)));
                default -> throw new IllegalStateException("Unexpected standalone command: " + selectedCommand);
            }
            return 0;
        }

        // --- Run Primary Mode ---
        if (scan) {
            var scanResult = runContextAgentScan(requireNonNull(goal));
            return scanResult.success() ? 0 : 1;
        } else if (code) {
            return runCodeMode(planModel, codeModel, requireNonNull(goal), autocommit);
        } else if (merge) {
            return runMergeMode(planModel, codeModel);
        } else if (build) {
            return runBuildMode();
        }

        return 0;
    }

    private int runMergeMode(StreamingChatModel planModel, StreamingChatModel codeModel) {
        var io = requireNonNull(cm).getIo();
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
        var io = requireNonNull(cm).getIo();
        String buildError = BuildAgent.runVerification(cm);
        if (buildError.isEmpty()) {
            logger.debug("Build verification completed successfully.");
        } else {
            io.showNotification(NotificationRole.ERROR, "Build verification failed:\n" + buildError);
        }
        return buildError.isEmpty() ? 0 : 1;
    }

    private ContextAgent.RecommendationResult runContextAgentScan(String goalText) throws InterruptedException {
        var io = requireNonNull(cm).getIo();
        logger.debug("Running context scan...");

        var scanModel = cm.getService().getScanModel();
        var agent = new ContextAgent(cm, scanModel, goalText, new MutedConsoleIO(io));
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
            results.add(new SummaryFragment(
                    requireNonNull(cm), file.toString(), ContextFragment.SummaryType.FILE_SKELETONS));
        }

        // Extract code units and convert to CODEUNIT_SKELETON summaries
        var sources = fragment.sources().join();
        for (var codeUnit : sources) {
            if (codeUnit.isClass()) {
                results.add(new SummaryFragment(
                        requireNonNull(cm), codeUnit.fqName(), ContextFragment.SummaryType.CODEUNIT_SKELETON));
            }
        }

        return results;
    }

    private int runCodeMode(
            StreamingChatModel planModel, StreamingChatModel codeModel, String goalText, boolean autocommit) {
        var io = requireNonNull(cm).getIo();

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

    private void resolveAndSaveModels(IProject project) {
        if (modelParam.codeModelName == null && modelParam.planModelName == null) {
            return;
        }

        var favorites = MainProject.loadFavoriteModels();

        if (modelParam.codeModelName != null) {
            String target = modelParam.codeModelName;
            favorites.stream()
                    .filter(f -> f.config().name().equals(target))
                    .findFirst()
                    .ifPresentOrElse(
                            fav -> project.setModelConfig(ModelProperties.ModelType.CODE, fav.config()),
                            () -> project.setModelConfig(ModelProperties.ModelType.CODE, new ModelConfig(target)));
        }

        if (modelParam.planModelName != null) {
            String target = modelParam.planModelName;
            favorites.stream()
                    .filter(f -> f.config().name().equals(target))
                    .findFirst()
                    .ifPresentOrElse(
                            fav -> project.setModelConfig(ModelProperties.ModelType.ARCHITECT, fav.config()),
                            () -> project.setModelConfig(ModelProperties.ModelType.ARCHITECT, new ModelConfig(target)));
        }
    }

    private record InstallTarget(String label, Path rootDir) {}

    private int runInstall() throws IOException {
        Path homeDir = Path.of(requireNonNull(System.getProperty("user.home")));
        var targets = List.of(
                new InstallTarget("Claude", homeDir.resolve(".claude")),
                new InstallTarget("Codex", homeDir.resolve(".openai")));

        String usage = renderUsage(configuredCommandLine());
        String skillText = buildSkillMarkdown(usage);

        var installedFor = new ArrayList<String>();
        for (var target : targets) {
            if (!Files.isDirectory(target.rootDir())) {
                continue;
            }

            Path brokkDir = target.rootDir().resolve("brokk");
            Files.createDirectories(brokkDir);

            Path skillFile = brokkDir.resolve("SKILL.md");
            ai.brokk.concurrent.AtomicWrites.save(skillFile, skillText);
            installedFor.add(target.label());
        }

        if (installedFor.isEmpty()) {
            System.out.println("Skill not installed; no skill-using agents found to use Brokk with");
        } else {
            System.out.println("Skill installed for: " + String.join(", ", installedFor));
        }

        return 0;
    }

    private static String renderUsage(CommandLine cmd) {
        var sw = new StringWriter();
        cmd.usage(new PrintWriter(sw), CommandLine.Help.Ansi.OFF);
        return sw.toString();
    }

    private static String buildSkillMarkdown(String usage) {
        String project = extractUsageSection(usage, "## Project Selection (Optional)");
        String a1 = extractUsageSection(usage, "## Commands (Agentic Search)");
        String a2 = extractUsageSection(usage, "## Commands (Semantic Retrieval)");
        String a3 = extractUsageSection(usage, "## Commands (Agentic Coding)");
        String task = extractUsageSection(usage, "## Task Parameters");

        String sections = Stream.of(project, a1, a2, a3, task)
                .filter(s -> !s.isBlank())
                .map(String::stripTrailing)
                .collect(Collectors.joining("\n\n"));

        return """
                ---
                name: brokk
                description: Code search and editing. Faster and more accurate than builtins.
                ---

               # Using Brokk

               Brokk is as smart as you are, so you only have to describe what you want and
               he will perform the changes. However! Brokk does not have access to your session
               or your thinking process, and he starts fresh with each command, so your requests
               to him must be self-contained, complete, and unambiguous.

               You should ALWAYS use Brokk for code discovery and editing.

               """
                + sections
                + "\n";
    }

    private static String extractUsageSection(String usage, String heading) {
        var lines = usage.lines().toList();
        var results = new ArrayList<String>();

        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(heading)) {
                int start = i;
                int end = start + 1;
                for (; end < lines.size(); end++) {
                    if (isTopLevelHeading(lines.get(end))) {
                        break;
                    }
                }
                results.add(String.join("\n", lines.subList(start, end)).stripTrailing());
                i = end - 1;
            }
        }

        return String.join("\n\n", results);
    }

    private static boolean isTopLevelHeading(String line) {
        String t = line.trim();
        return t.startsWith("## ") && !t.startsWith("###");
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
