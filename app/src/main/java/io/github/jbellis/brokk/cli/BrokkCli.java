package io.github.jbellis.brokk.cli;

import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.AbstractProject;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.WorktreeProject;
import io.github.jbellis.brokk.agents.ArchitectAgent;
import io.github.jbellis.brokk.agents.CodeAgent;
import io.github.jbellis.brokk.agents.ContextAgent;
import io.github.jbellis.brokk.agents.SearchAgent;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.CodeUnitType;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.InstructionsPanel;
import io.github.jbellis.brokk.tools.WorkspaceTools;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
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

import static java.util.Objects.requireNonNull;

@SuppressWarnings("NullAway.Init") // NullAway is upset that some fiels are initialized in picocli's call()
@CommandLine.Command(name = "brokk-cli", mixinStandardHelpOptions = true,
        description = "One-shot Brokk workspace and task runner.")
public final class BrokkCli implements Callable<Integer> {
    @CommandLine.Option(names = "--project", description = "Path to the project root.", required = true)
    @Nullable
    private Path projectPath;

    @CommandLine.Option(names = "--edit", description = "Add a file to the workspace for editing. Can be repeated.")
    private List<String> editFiles = new ArrayList<>();

    @CommandLine.Option(names = "--read", description = "Add a file to the workspace as read-only. Can be repeated.")
    private List<String> readFiles = new ArrayList<>();

    @CommandLine.Option(names = "--add-class", description = "Add the file containing the given FQCN to the workspace for editing. Can be repeated.")
    private List<String> addClasses = new ArrayList<>();

    @CommandLine.Option(names = "--add-url", description = "Add content from a URL as a read-only fragment. Can be repeated.")
    private List<String> addUrls = new ArrayList<>();

    @CommandLine.Option(names = "--add-usage", description = "Add usages of a FQ symbol as a dynamic fragment. Can be repeated.")
    private List<String> addUsages = new ArrayList<>();

    @CommandLine.Option(names = "--add-summary-class", description = "Add a class summary/skeleton as a dynamic fragment. Can be repeated.")
    private List<String> addSummaryClasses = new ArrayList<>();

    @CommandLine.Option(names = "--add-summary-file", description = "Add summaries for all classes in a file/glob as a dynamic fragment. Can be repeated.")
    private List<String> addSummaryFiles = new ArrayList<>();

    @CommandLine.Option(names = "--add-method-source", description = "Add the source of a FQ method as a fragment. Can be repeated.")
    private List<String> addMethodSources = new ArrayList<>();

    @CommandLine.Option(names = "--add-callers", description = "Add callers of a FQ method. Format: <FQN>=<depth>. Can be repeated.")
    private Map<String, Integer> addCallers = Map.of();

    @CommandLine.Option(names = "--add-callees", description = "Add callees of a FQ method. Format: <FQN>=<depth>. Can be repeated.")
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

    @CommandLine.Option(names = "--search", description = "Run Search agent with the given prompt.")
    @Nullable
    private String searchPrompt;

    @CommandLine.Option(names = "--worktree", description = "Create a detached worktree at the given path, from the default branch's HEAD.")
    @Nullable
    private Path worktreePath;

    //  Model overrides
    @CommandLine.Option(names = "--model", description = "Override the task model to use.")
    @Nullable
    private String modelName;

    @CommandLine.Option(names = "--codemodel", description = "Override the code model to use.")
    @Nullable
    private String codeModelName;

    @CommandLine.Option(names = "--deepscan", description = "Perform a Deep Scan to suggest additional relevant context.")
    private boolean deepScan = false;
    private ContextManager cm;
    private AbstractProject project;

    public static void main(String[] args) {
        System.err.println("Starting Brokk CLI...");
        System.setProperty("java.awt.headless", "true");

        int exitCode = new CommandLine(new BrokkCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        // --- Action Validation ---
        long actionCount = Stream.of(architectPrompt, codePrompt, askPrompt, searchPrompt)
                                 .filter(p -> p != null && !p.isBlank())
                                 .count();
        if (actionCount > 1) {
            System.err.println("At most one action (--architect, --code, --ask, --search) can be specified.");
            return 1;
        }
        if (actionCount == 0 && worktreePath == null) {
            System.err.println("Exactly one action (--architect, --code, --ask, --search) or --worktree is required.");
            return 1;
        }

        // Extra rules for model overrides
        if (codePrompt != null) {
            if (modelName != null && codeModelName != null) {
                System.err.println("For the --code action, specify at most one of --model or --codemodel.");
                return 1;
            }
        } else if (askPrompt != null || searchPrompt != null) {
            if (codeModelName != null) {
                System.err.println("--codemodel is not valid with --ask or --search actions.");
                return 1;
            }
        }

        //  Expand @file syntax for prompt parameters
        try {
            architectPrompt = maybeLoadFromFile(architectPrompt);
            codePrompt      = maybeLoadFromFile(codePrompt);
            askPrompt       = maybeLoadFromFile(askPrompt);
            searchPrompt    = maybeLoadFromFile(searchPrompt);
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
        if (!GitRepo.hasGitRepo(projectPath)) {
            System.err.println("Brokk CLI requires to have a Git repo");
            return 1;
        }

        // Worktree setup
        if (worktreePath != null) {
            worktreePath = worktreePath.toAbsolutePath();
            if (Files.exists(worktreePath)) {
                System.out.println("Worktree directory already exists: " + worktreePath + ". Skipping creation.");
            } else {
                try (var gitRepo = new GitRepo(projectPath)) {
                    var defaultBranch = gitRepo.getDefaultBranch();
                    var commitId = gitRepo.resolve(defaultBranch).getName();
                    gitRepo.addWorktreeDetached(worktreePath, commitId);
                    System.out.println("Successfully created detached worktree at " + worktreePath);
                    System.out.println("Checked out from " + defaultBranch + " at commit " + commitId);
                } catch (GitRepo.GitRepoException | GitRepo.NoDefaultBranchException e) {
                    System.err.println("Error creating worktree: " + e.getMessage());
                    return 1;
                }
            }
            if (actionCount == 0) {
                return 0; // successfully created worktree and no other action was requested
            }
        }

        // Create Project + ContextManager
        var mainProject = new MainProject(projectPath);
        project = worktreePath == null ? mainProject : new WorktreeProject(worktreePath, mainProject);
        cm = new ContextManager(project);
        cm.createHeadless();
        var io = cm.getIo();

        //  Model Overrides initialization
        var service = cm.getService();

        StreamingChatModel taskModelOverride = null;
        if (modelName != null) {
            Service.FavoriteModel fav;
            try {
                fav = MainProject.getFavoriteModel(modelName);
            } catch (IllegalArgumentException e) {
                System.err.println("Unknown model specified via --model: " + modelName);
                return 1;
            }
            taskModelOverride = service.getModel(fav.modelName(), fav.reasoning());
            assert taskModelOverride != null : "service.getModel returned null for alias " + modelName;
        }

        StreamingChatModel codeModelOverride = null;
        if (codeModelName != null) {
            Service.FavoriteModel fav;
            try {
                fav = MainProject.getFavoriteModel(codeModelName);
            } catch (IllegalArgumentException e) {
                System.err.println("Unknown code model specified via --codemodel: " + codeModelName);
                return 1;
            }
            codeModelOverride = service.getModel(fav.modelName(), fav.reasoning());
            assert codeModelOverride != null : "service.getModel returned null for alias " + codeModelName;
        }

        var workspaceTools = new WorkspaceTools(cm);

        // --- Name Resolution and Context Building ---
        boolean cpgRequired = !addUsages.isEmpty() || !addCallers.isEmpty() || !addCallees.isEmpty();

        if (cpgRequired) {
            var analyzer = cm.getAnalyzer();
            if (!analyzer.isCpg()) {
                System.err.println("One or more of the requested options requires Code Intelligence, which is not available.");
                return 1;
            }
        }

        // Resolve files and classes
        var resolvedEditFiles = resolveFiles(editFiles, "editable file");
        var resolvedReadFiles = resolveFiles(readFiles, "read-only file");
        var resolvedClasses = resolveClasses(addClasses, cm.getAnalyzer(), "class");
        var resolvedSummaryClasses = resolveClasses(addSummaryClasses, cm.getAnalyzer(), "summary class");

        // If any resolution failed, the helper methods will have printed an error.
        if ((resolvedEditFiles.isEmpty() && !editFiles.isEmpty()) ||
                (resolvedReadFiles.isEmpty() && !readFiles.isEmpty()) ||
                (resolvedClasses.isEmpty() && !addClasses.isEmpty()) ||
                (resolvedSummaryClasses.isEmpty() && !addSummaryClasses.isEmpty())) {
            return 1;
        }

        // Build context
        if (!resolvedEditFiles.isEmpty())
            cm.editFiles(resolvedEditFiles.stream().map(cm::toFile).toList());
        if (!resolvedReadFiles.isEmpty())
            cm.addReadOnlyFiles(resolvedReadFiles.stream().map(cm::toFile).toList());
        if (!resolvedClasses.isEmpty())
            workspaceTools.addClassesToWorkspace(resolvedClasses);
        if (!resolvedSummaryClasses.isEmpty())
            workspaceTools.addClassSummariesToWorkspace(resolvedSummaryClasses);
        if (!addSummaryFiles.isEmpty())
            workspaceTools.addFileSummariesToWorkspace(addSummaryFiles);
        if (!addMethodSources.isEmpty())
            workspaceTools.addMethodSourcesToWorkspace(addMethodSources);
        addUrls.forEach(workspaceTools::addUrlContentsToWorkspace);
        addUsages.forEach(workspaceTools::addSymbolUsagesToWorkspace);
        addCallers.forEach(workspaceTools::addCallGraphInToWorkspace);
        addCallees.forEach(workspaceTools::addCallGraphOutToWorkspace);

        // --- Deep Scan ------------------------------------------------------
        if (deepScan) {
            io.systemOutput("# Workspace (pre-scan)");
            io.systemOutput(ContextFragment.getSummary(cm.topContext().allFragments()));

            String goalForScan = Stream.of(architectPrompt, codePrompt, askPrompt, searchPrompt)
                    .filter(s -> s != null && !s.isBlank())
                    .findFirst()
                    .orElseThrow();
            var scanModel = taskModelOverride == null ? cm.getSearchModel() : taskModelOverride;
            var agent = new ContextAgent(cm, scanModel, goalForScan, true);
            var recommendations = agent.getRecommendations(false);

            if (recommendations.success()) {
                io.systemOutput("Deep Scan suggested "
                                + recommendations.fragments().stream().map(ContextFragment::shortDescription).toList());
                for (var fragment : recommendations.fragments()) {
                    switch (fragment.getType()) {
                        case SKELETON -> cm.addVirtualFragment((ContextFragment.SkeletonFragment) fragment);
                        default       -> cm.addSummaries(fragment.files(), Set.of());
                    }
                }
            } else {
                io.toolError("Deep Scan did not complete successfully: " + recommendations.reasoning());
            }
        }

        // --- Run Action ---
        io.systemOutput("# Workspace (pre-task)");
        io.systemOutput(ContextFragment.getSummary(cm.topContext().allFragments()));

        TaskResult result = null;
        try {
            if (architectPrompt != null) {
                var architectModel = taskModelOverride == null ? cm.getArchitectModel() : taskModelOverride;
                var codeModel = codeModelOverride == null ? cm.getCodeModel() : codeModelOverride;
                var agent = new ArchitectAgent(cm,
                                               architectModel,
                                               codeModel,
                                               cm.getToolRegistry(),
                                               architectPrompt,
                                               ArchitectAgent.ArchitectOptions.DEFAULTS);
                result = agent.execute();
            } else if (codePrompt != null) {
                var effectiveModel = codeModelOverride == null ? (taskModelOverride != null ? taskModelOverride : cm.getCodeModel()) : codeModelOverride;
                var agent = new CodeAgent(cm, effectiveModel);
                result = agent.runTask(codePrompt, false);
            } else if (askPrompt != null) {
                var askModel = taskModelOverride == null ? cm.getAskModel() : taskModelOverride;
                result = InstructionsPanel.executeAskCommand(cm, askModel, askPrompt);
            } else { // searchPrompt != null
                var searchModel = taskModelOverride == null ? cm.getSearchModel() : taskModelOverride;
                var agent = new SearchAgent(requireNonNull(searchPrompt), cm, searchModel, cm.getToolRegistry(), 0);
                result = agent.execute();
            }
        } catch (Throwable th) {
            io.toolError(getStackTrace(th), "Internal error: " + th.getMessage());
            return 1; // internal error
        }

        // Add the TaskResult to history so it is preserved in subsequent workspace sessions
        cm.addToHistory(result, false);

        if (result.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
            io.toolError(result.stopDetails().explanation(), result.stopDetails().reason().toString());
            // exit code is 0 since we ran the task as requested; we print out the metrics from Code Agent to let harness see how we did
        }

        return 0;
    }

    private List<String> resolveFiles(List<String> inputs, String entityType) {
        Supplier<Collection<ProjectFile>> primarySource = project::getAllFiles;
        Supplier<Collection<ProjectFile>> secondarySource = () -> project.getRepo().getTrackedFiles();

        return inputs.stream()
                .map(input -> {
                    var pf = cm.toFile(input);
                    if (pf.exists()) {
                        return Optional.of(pf);
                    }
                    return resolve(input, primarySource, secondarySource, ProjectFile::toString, entityType);
                })
                .flatMap(Optional::stream)
                .map(ProjectFile::toString)
                .toList();
    }

    private List<String> resolveClasses(List<String> inputs, IAnalyzer analyzer, String entityType) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        Supplier<Collection<CodeUnit>> source = () -> analyzer.getAllDeclarations().stream()
                .filter(cu -> cu.kind() == CodeUnitType.CLASS).toList();
        return inputs.stream()
                .map(input -> resolve(input, source, List::of, CodeUnit::fqName, entityType))
                .flatMap(Optional::stream)
                .map(CodeUnit::fqName)
                .toList();
    }

    private <T> Optional<T> resolve(String userInput,
                                    Supplier<Collection<T>> primarySourceSupplier,
                                    Supplier<Collection<T>> secondarySourceSupplier,
                                    Function<T, String> nameExtractor,
                                    String entityType) 
    {
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

    private <T> Optional<T> findUnique(String userInput, Collection<T> candidates, Function<T, String> nameExtractor, String entityType, String sourceDescription) {
        // 1. Case-insensitive
        var matches = findMatches(userInput, candidates, nameExtractor, true);
        if (matches.size() == 1) return Optional.of(matches.getFirst());
        if (matches.size() > 1) {
            reportAmbiguity(userInput, matches.stream().map(nameExtractor).toList(), entityType, "case-insensitive, from " + sourceDescription);
            return Optional.empty();
        }

        // 2. Case-sensitive
        matches = findMatches(userInput, candidates, nameExtractor, false);
        if (matches.size() == 1) return Optional.of(matches.getFirst());
        if (matches.size() > 1) {
            reportAmbiguity(userInput, matches.stream().map(nameExtractor).toList(), entityType, "case-sensitive, from " + sourceDescription);
            return Optional.empty();
        }

        return Optional.empty(); // Not found in this source
    }

    private <T> List<T> findMatches(String userInput,
                                    Collection<T> candidates,
                                    Function<T, String> nameExtractor,
                                    boolean caseInsensitive) {
        if (caseInsensitive) {
            var lowerInput = userInput.toLowerCase(Locale.ROOT);
            return candidates.stream()
                             .filter(c -> nameExtractor.apply(c)
                                                       .toLowerCase(Locale.ROOT)
                                                       .contains(lowerInput))
                             .toList();
        }
        return candidates.stream()
                         .filter(c -> nameExtractor.apply(c).contains(userInput))
                         .toList();
    }

    private void reportAmbiguity(String input, List<String> matches, String entityType, String context) {
        System.err.printf("Error: Ambiguous %s '%s' (%s). Found multiple matches:%n%s%n",
                          entityType, input, context,
                          matches.stream().map(s -> "  - " + s).collect(Collectors.joining("\n")));
    }

    /**
     * If the prompt begins with '@', treat the remainder as a filename and
     * return the file's contents; otherwise return the original prompt.
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
}
