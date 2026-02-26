package ai.brokk.tools;

import ai.brokk.LlmOutputMeta;
import ai.brokk.analyzer.*;
import ai.brokk.concurrent.ComputedValue;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.SpecialTextType;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.IProject;
import ai.brokk.tasks.TaskList;
import ai.brokk.util.HtmlToMarkdown;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.model.output.structured.D;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.util.NullnessUtil;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

/**
 * Provides tools for manipulating the context (adding/removing files and fragments) and adding analysis results
 * (usages, skeletons, sources, call graphs) as context fragments.
 *
 * This class is now context-local: it holds a working Context instance and mutates it immutably.
 * Use WorkspaceTools(Context) for per-turn, local usage inside agents. For compatibility, a constructor taking a
 * ContextManager is provided which seeds the local Context from the manager; call publishTo(cm) to commit changes.
 */
public class WorkspaceTools {
    private static final Logger logger = LogManager.getLogger(WorkspaceTools.class);

    public record DropReport(
            Set<String> droppedFragmentIds, Set<String> protectedFragmentIds, Set<String> unknownFragmentIds) {
        public DropReport {
            droppedFragmentIds = Set.copyOf(droppedFragmentIds);
            protectedFragmentIds = Set.copyOf(protectedFragmentIds);
            unknownFragmentIds = Set.copyOf(unknownFragmentIds);
        }
    }

    // Per-instance working context (immutable Context instances replaced on modification)
    private Context context;

    private @Nullable DropReport lastDropReport = null;

    /**
     * Construct a WorkspaceTools instance operating on the provided Context.
     * This is the preferred constructor for per-turn/local usage inside agents.
     */
    public WorkspaceTools(Context initialContext) {
        this.context = initialContext;
    }

    /** Returns the current working Context for this WorkspaceTools instance. */
    public Context getContext() {
        return context;
    }

    public @Nullable DropReport getLastDropReport() {
        return lastDropReport;
    }

    public void clearLastDropReport() {
        this.lastDropReport = null;
    }

    /**
     * Represents a fragment removal request with its ID and structured drop metadata.
     * Used by {@link #dropWorkspaceFragments(List)} to structure the input.
     */
    public record FragmentRemoval(
            @D(
                            "The alphanumeric ID exactly as listed in <workspace_toc>. If you do not see a `fragmentid` attribute you cannot drop it. Filenames are not IDs, descriptions are not IDs.")
                    String fragmentId,
            @D(KEY_FACTS_DESCRIPTION) String keyFacts,
            @D(DROP_REASON_DESCRIPTION) String dropReason) {}

    /** Updates the working Context for this WorkspaceTools instance. */
    public void setContext(Context newContext) {
        this.context = newContext;
    }

    // ---------------------------
    // Tools (mutate the local Context)
    // ---------------------------

    @Tool(
            "Edit project files to the Workspace. Use this when Code Agent will need to make changes to these files, or if you need to read the full source. Only call when you have identified specific filenames. DO NOT call this to create new files -- Code Agent can do that without extra steps.")
    public String addFilesToWorkspace(
            @P(
                            "List of file paths relative to the project root (e.g., 'src/main/java/com/example/MyClass.java'). Must not be empty.")
                    List<String> relativePaths) {
        if (relativePaths.isEmpty()) {
            return "Cannot add files: file paths list is empty.";
        }

        var reporter = new CoverageReporter(context, getAnalyzer(), workspaceState());
        var workspaceFiles = currentWorkspaceFiles();
        List<ProjectFile> toAddFiles = new ArrayList<>();

        for (String rawPath : relativePaths.stream().distinct().toList()) {
            String path = rawPath.strip();
            if (path.isEmpty()) {
                reporter.add(Bucket.ERROR, "File path is blank.");
                continue;
            }
            try {
                var file = context.getContextManager().toFile(path);
                if (!file.exists()) {
                    reporter.add(
                            Bucket.ERROR,
                            "File at `%s` does not exist (do not use this method to create new files)."
                                    .formatted(path));
                    continue;
                }
                if (file.isDirectory()) {
                    reporter.add(
                            Bucket.ERROR,
                            "File path `%s` is a directory; only normal files may be added.".formatted(path));
                    continue;
                }

                if (workspaceFiles.contains(file)) {
                    reporter.add(Bucket.ALREADY_PRESENT, file.toString());
                } else {
                    toAddFiles.add(file);
                    reporter.add(Bucket.ADDED, file.toString());
                }
            } catch (IllegalArgumentException e) {
                reporter.add(Bucket.ERROR, "Invalid path: `%s`.".formatted(path));
            }
        }

        var fragments = context.getContextManager().toPathFragments(toAddFiles);
        context = context.addFragments(fragments);

        String report = reporter.report();
        return report.isEmpty() ? "No changes." : report;
    }

    @Tool(
            "Add classes to the Workspace by their fully qualified names. This adds read-only code fragments for those classes. Only call when you have identified specific class names.")
    public String addClassesToWorkspace(
            @P(
                            "List of fully qualified class names (e.g., ['com.example.MyClass', 'org.another.Util']). Must not be empty.")
                    List<String> classNames) {
        if (classNames.isEmpty()) {
            return "Cannot add classes: class names list is empty.";
        }

        var reporter = new CoverageReporter(context, getAnalyzer(), workspaceState());
        List<ContextFragments.CodeFragment> toAdd = new ArrayList<>();

        for (String rawName : classNames.stream().distinct().toList()) {
            String className = rawName.strip();
            if (className.isEmpty()) {
                reporter.add(Bucket.ERROR, "Class name is blank.");
                continue;
            }

            var cuOpt = reporter.analyzer.getDefinitions(className).stream()
                    .filter(CodeUnit::isClass)
                    .findFirst();

            if (cuOpt.isEmpty()) {
                reporter.add(Bucket.NOT_FOUND, className);
                continue;
            }

            var cu = cuOpt.get();
            var fragment = new ContextFragments.CodeFragment(context.getContextManager(), cu);
            if (reporter.maybeAdd(fragment, className)) {
                toAdd.add(fragment);
            }
        }

        context = context.addFragments(toAdd);
        String report = reporter.report();
        return report.isEmpty() ? "No changes." : report;
    }

    @Tool(
            "Fetch content from a URL (e.g., documentation, issue tracker) and add it to the Workspace as a read-only text fragment. HTML content will be converted to Markdown.")
    public String addUrlContentsToWorkspace(
            @P("The full URL to fetch content from (e.g., 'https://example.com/docs/page').") String urlString) {
        if (urlString.isBlank()) {
            return "URL cannot be empty.";
        }

        try {
            logger.debug("Fetching content from URL: {}", urlString);
            var content = fetchUrlContent(new URI(urlString));
            content = HtmlToMarkdown.maybeConvertToMarkdown(content);

            if (content.isBlank()) {
                return "Fetched content from URL is empty";
            }

            var fragment = new ContextFragments.StringFragment(
                    context.getContextManager(),
                    content,
                    "Content from " + urlString,
                    SyntaxConstants.SYNTAX_STYLE_NONE);
            context = context.addFragments(fragment);

            logger.debug("Successfully added URL content to context");
            return "Added content from URL as a read-only text fragment";
        } catch (URISyntaxException e) {
            throw new ToolRegistry.ToolCallException(ToolExecutionResult.Status.REQUEST_ERROR, "Invalid URL format");
        } catch (IOException e) {
            throw new ToolRegistry.ToolCallException(ToolExecutionResult.Status.INTERNAL_ERROR, "I/O Error");
        }
    }

    @Tool(
            value = "Remove specified fragments (files, text snippets, task history, analysis results) "
                    + "from the Workspace by their `fragmentid` and record structured breadcrumbs (keyFacts + dropReason) in DISCARDED_CONTEXT. "
                    + "Do not drop file fragments that you still need to read or edit.")
    public String dropWorkspaceFragments(
            @P("List of fragments to remove from the Workspace. Must not be empty. Pinned fragments are ineligible.")
                    List<FragmentRemoval> fragments) {
        if (fragments.isEmpty()) {
            return "Fragments list cannot be empty.";
        }

        // Build map from fragmentId -> FragmentRemoval for lookup
        Map<String, FragmentRemoval> idToRemoval =
                fragments.stream().collect(Collectors.toMap(FragmentRemoval::fragmentId, fr -> fr, (a, b) -> a));

        // Operate on actual stored fragments only
        var allFragments = context.allFragments().toList();
        Map<String, ContextFragment> byId =
                allFragments.stream().collect(Collectors.toMap(ContextFragment::id, f -> f));

        var idsToDropSet = new HashSet<>(idToRemoval.keySet());

        // Separate found vs unknown IDs
        var foundFragments =
                idsToDropSet.stream().filter(byId::containsKey).map(byId::get).toList();
        var unknownIds =
                idsToDropSet.stream().filter(id -> !byId.containsKey(id)).collect(Collectors.toSet());

        // Partition found into droppable vs protected based on pinning policy
        var partitioned =
                foundFragments.stream().collect(Collectors.partitioningBy(fragment -> !context.isPinned(fragment)));
        var toDrop = NullnessUtil.castNonNull(partitioned.get(true));
        var protectedFragments = NullnessUtil.castNonNull(partitioned.get(false));

        // Merge explanations for successfully dropped fragments (new overwrites old)
        var existingDiscardedMap = context.getDiscardedFragmentsNotes();
        Map<String, String> mergedDiscarded = new LinkedHashMap<>(existingDiscardedMap);
        for (var f : toDrop) {
            var removal = idToRemoval.get(f.id());
            var entry = "Key facts: %s. Reason: %s"
                    .formatted(
                            removal != null ? removal.keyFacts() : "No relevant facts",
                            removal != null ? removal.dropReason() : "unspecified");
            mergedDiscarded.put(f.description().join(), entry);
        }

        // Serialize updated JSON
        String discardedJson = SpecialTextType.serializeDiscardedContext(mergedDiscarded);

        var droppedIds = toDrop.stream().map(ContextFragment::id).collect(Collectors.toSet());
        var protectedIds = protectedFragments.stream().map(ContextFragment::id).collect(Collectors.toSet());
        this.lastDropReport = new DropReport(droppedIds, protectedIds, unknownIds);

        // Apply removal and upsert DISCARDED_CONTEXT in the local context
        context =
                context.removeFragmentsByIds(droppedIds).withSpecial(SpecialTextType.DISCARDED_CONTEXT, discardedJson);

        logger.debug(
                "dropWorkspaceFragments: dropped={}, pinned={}, unknown={}. discardedFragments map now {} entries",
                droppedIds.size(),
                protectedFragments.size(),
                unknownIds.size(),
                mergedDiscarded.size());

        List<String> lines = new ArrayList<>();

        if (!toDrop.isEmpty()) {
            var droppedReprs = toDrop.stream().map(ContextFragment::repr).collect(Collectors.joining(", "));
            lines.add("Dropped: %s.".formatted(droppedReprs));
        }

        if (!protectedFragments.isEmpty()) {
            var protectedDescriptions = protectedFragments.stream()
                    .map(ContextFragment::description)
                    .map(ComputedValue::join)
                    .collect(Collectors.joining(", "));
            lines.add("Pinned (not dropped): %s.".formatted(protectedDescriptions));
        }

        if (!unknownIds.isEmpty()) {
            lines.add("Unknown fragment IDs (not dropped): %s.".formatted(String.join(", ", unknownIds)));
        }

        return lines.isEmpty() ? "No changes." : String.join("\n", lines);
    }

    @Tool(
            """
                  Retrieves summaries (fields and method signatures) for specified classes and adds them to the Workspace.
                  Faster and more efficient than reading entire files or classes when you just need the API and not the full source code.
                  Only call when you have identified specific class names.")
                  """)
    public String addClassSummariesToWorkspace(
            @P(
                            "List of fully qualified class names (e.g., ['com.example.ClassA', 'org.another.ClassB']) to get summaries for. Must not be empty.")
                    List<String> classNames) {
        if (classNames.isEmpty()) {
            return "Cannot add class summaries: class names list is empty.";
        }

        var reporter = new CoverageReporter(context, getAnalyzer(), workspaceState());
        List<ContextFragments.SummaryFragment> toAdd = new ArrayList<>();

        for (String rawName : classNames.stream().distinct().toList()) {
            String className = rawName.strip();
            if (className.isEmpty()) {
                reporter.add(Bucket.ERROR, "Class name is blank.");
                continue;
            }

            var cuOpt = reporter.analyzer.getDefinitions(className).stream()
                    .filter(CodeUnit::isClass)
                    .findFirst();

            if (cuOpt.isEmpty()) {
                reporter.add(Bucket.NOT_FOUND, className);
                continue;
            }

            var fragment = new ContextFragments.SummaryFragment(
                    context.getContextManager(), cuOpt.get().fqName(), ContextFragment.SummaryType.CODEUNIT_SKELETON);
            if (reporter.maybeAdd(fragment, className)) {
                toAdd.add(fragment);
            }
        }

        context = context.addFragments(toAdd);
        String report = reporter.report();
        return report.isEmpty() ? "No changes." : report;
    }

    @Tool(
            """
                  Retrieves summaries (class fields, method and top-level function signatures) of top-level symbols defined within specified project files and adds them to the Workspace.
                  Supports glob patterns: '*' matches files in a single directory, '**' matches files recursively.
                  Faster and more efficient than reading entire files when you just need the API definitions.
                  (But if you don't know where what you want is located, you should use Search Agent instead.)
                  """)
    public String addFileSummariesToWorkspace(
            @P(
                            "List of file paths relative to the project root. Supports glob patterns (* for single directory, ** for recursive). E.g., ['src/main/java/com/example/util/*.java', 'tests/foo/**.py']. Must not be empty.")
                    List<String> filePaths) {
        if (filePaths.isEmpty()) {
            return "Cannot add file summaries: file paths list is empty.";
        }

        var reporter = new CoverageReporter(context, getAnalyzer(), workspaceState());
        var project = (AbstractProject) context.getContextManager().getProject();

        List<ProjectFile> resolvedFiles = new ArrayList<>();

        for (String rawPattern : filePaths) {
            String pattern = rawPattern.strip();
            if (pattern.isEmpty()) {
                reporter.add(Bucket.ERROR, "File path is blank.");
                continue;
            }

            List<ProjectFile> matches = ai.brokk.Completions.expandPath(project, pattern).stream()
                    .filter(ProjectFile.class::isInstance)
                    .map(ProjectFile.class::cast)
                    .toList();

            if (matches.isEmpty()) {
                reporter.add(Bucket.ERROR, "No files matched path `%s`.".formatted(pattern));
                continue;
            }

            resolvedFiles.addAll(matches);
        }

        resolvedFiles = resolvedFiles.stream().distinct().toList();

        List<ContextFragments.SummaryFragment> toAdd = new ArrayList<>();

        for (ProjectFile file : resolvedFiles) {
            var fragment = new ContextFragments.SummaryFragment(
                    context.getContextManager(), file.toString(), ContextFragment.SummaryType.FILE_SKELETONS);

            if (reporter.maybeAdd(fragment, file.toString())) {
                toAdd.add(fragment);
            }
        }

        context = context.addFragments(toAdd);
        String report = reporter.report();
        return report.isEmpty() ? "No changes." : report;
    }

    @Tool(
            """
                  Retrieves the full source code of specific methods or functions and adds to the Workspace each as a separate read-only text fragment.
                  Faster and more efficient than including entire files or classes when you only need a few methods.
                  """)
    public String addMethodsToWorkspace(
            @P(
                            "List of fully qualified method names (e.g., ['com.example.ClassA.method1', 'org.another.ClassB.processData']) to retrieve sources for. Must not be empty. Must not include parameters (I will process all overloads).")
                    List<String> methodNames) {
        if (methodNames.isEmpty()) {
            return "Cannot add method sources: method names list is empty.";
        }

        var reporter = new CoverageReporter(context, getAnalyzer(), workspaceState());
        List<ContextFragments.CodeFragment> toAdd = new ArrayList<>();

        for (String rawName : methodNames.stream().distinct().toList()) {
            String methodName = rawName.strip();
            if (methodName.isEmpty()) {
                reporter.add(Bucket.ERROR, "Method name is blank.");
                continue;
            }

            var cuOpt = reporter.analyzer.getDefinitions(methodName).stream()
                    .filter(CodeUnit::isFunction)
                    .findFirst();

            if (cuOpt.isEmpty()) {
                reporter.add(Bucket.NOT_FOUND, methodName);
                continue;
            }

            var cu = cuOpt.get();
            var fragment = new ContextFragments.CodeFragment(context.getContextManager(), cu);
            if (reporter.maybeAdd(fragment, methodName)) {
                toAdd.add(fragment);
            }
        }

        context = context.addFragments(toAdd);
        String report = reporter.report();
        return report.isEmpty() ? "No changes." : report;
    }

    /**
     * Tools that require an analyzer with SkeletonProvider/SourceCodeProvider capabilities.
     * These should only be offered when the project has at least one analyzed language.
     * This includes both workspace tools and search tools that depend on the analyzer.
     */
    private static final Set<String> ANALYZER_REQUIRED_TOOLS = Set.of(
            // Workspace tools
            "addClassesToWorkspace",
            "addClassSummariesToWorkspace",
            "addMethodsToWorkspace",
            "addFileSummariesToWorkspace",
            // Search tools
            "searchSymbols",
            "getSymbolLocations",
            "scanUsages",
            "skimDirectory");

    /**
     * Filters a list of tool names to remove analyzer-required tools when the project
     * has no analyzed languages.
     *
     * @param tools the list of tool names to filter
     * @param project the project to check for analyzer availability
     * @return a new list with analyzer-required tools removed if no analyzer is available
     */
    public static List<String> filterByAnalyzerAvailability(List<String> tools, IProject project) {
        boolean hasAnalyzedLanguage = !project.getAnalyzerLanguages().equals(Set.of(Languages.NONE));
        if (hasAnalyzedLanguage) {
            return tools;
        }
        return tools.stream().filter(t -> !ANALYZER_REQUIRED_TOOLS.contains(t)).toList();
    }

    /**
     * Canonical descriptions for drop explanation fields (keyFacts + dropReason).
     * Used in FragmentRemoval @Description annotations and in prompt guidance text.
     * Keep these synchronized - the annotations use the constants directly.
     */
    public static final String KEY_FACTS_DESCRIPTION =
            "Key facts to retain: file paths, class/method names, constraints, notable behavior. "
                    + "Use 'No relevant facts' if nothing worth preserving. "
                    + "Describe what IS, not what SHOULD BE. No action items for the Code Agent.";

    /** Description for the dropReason field in FragmentRemoval. */
    public static final String DROP_REASON_DESCRIPTION = "One short sentence: why is it safe to drop this fragment?";

    public static final String DROP_EXPLANATION_GUIDANCE =
            "keyFacts: " + KEY_FACTS_DESCRIPTION + "\n" + "dropReason: " + DROP_REASON_DESCRIPTION;

    /**
     * Shared guidance text for task-list tools (createOrReplaceTaskList).
     * Used in @Tool parameter descriptions to keep guidance synchronized.
     */
    public static final String TASK_LIST_GUIDANCE =
            """
            Produce an ordered list of coding tasks that are each 'right-sized': small enough to complete in one sitting, yet large enough to be meaningful.

            Requirements (apply to EACH task):
            - Scope: one coherent goal; avoid multi-goal items joined by 'and/then'.
            - Size target: ~2 hours for an experienced contributor across < 10 files.
            - Tests: prefer adding or updating automated tests (unit/integration) to prove the behavior;
              if automation is not a good fit, you may omit tests rather than prescribe manual steps. Tests should
              be completed as part of each task, not bolted on separately at the end.
            - Independence: runnable/reviewable on its own; at most one explicit dependency on a previous task.
            - Flexibility: the executing agent may adjust scope and ordering based on more up-to-date context discovered during implementation.
            - Incremental additions: when adding a task to an existing list, copy all existing incomplete tasks verbatim (preserving their exact wording and order) and insert the new task at the appropriate position based on dependencies.

            Rubric for slicing:
            - TOO LARGE if it spans multiple subsystems, sweeping refactors, or ambiguous outcomes - split by subsystem or by 'behavior change' vs 'refactor'.
            - TOO SMALL if it lacks a distinct, reviewable outcome (or test) - merge into its nearest parent goal.
            - JUST RIGHT if the diff + test could be reviewed and landed as a single commit without coordination.

            Aim for 8 tasks or fewer. Do not include "external" tasks like PRDs or manual testing.
            `tasks` is a List<TaskListEntry> - if you have N tasks, output N list elements.
            """;

    public record TaskListEntry(
            @D("Short display title for the task.") String title,
            @D("The full task description (Markdown encouraged).") String instructions,
            @D(
                            "How to verify success. Optional for purely mechanical refactors with no behavior change. Wherever possible, include automated tests in Acceptance; if automation is not a good fit, it is acceptable to omit tests rather than prescribe manual steps.")
                    String acceptance,
            @D("Files and fully qualified method/class names important to implement the task.") String keyLocations,
            @D(
                            "Useful discoveries from OUTSIDE the key locations that the Code Agent should know to load into his Workspace.")
                    String keyDiscoveries) {

        public TaskList.TaskItem toTaskItem() {
            String combinedText = instructions.strip();
            if (!acceptance.isBlank()) {
                combinedText += "\n\n**Acceptance:**\n" + acceptance.strip();
            }
            if (!keyLocations.isBlank()) {
                combinedText += "\n\n**Key Locations:**\n" + keyLocations.strip();
            }
            if (!keyDiscoveries.isBlank()) {
                combinedText += "\n\n**Key Discoveries:**\n" + keyDiscoveries.strip();
            }
            return new TaskList.TaskItem(title.strip(), combinedText, false);
        }
    }

    @Tool(
            value =
                    "Replace the entire task list with the provided tasks. Completed tasks from the previous list are implicitly dropped. Use this when you want to create a fresh task list or significantly revise the scope.")
    public String createOrReplaceTaskList(
            @P(
                            "Explanation of the problem and a high-level but comprehensive overview of the solution proposed in the tasks, formatted in Markdown. Include touch points for files, classes, and tests.")
                    String explanation,
            @P(TASK_LIST_GUIDANCE) List<TaskListEntry> tasks) {
        logger.debug("createOrReplaceTaskList selected with {} tasks", tasks.size());
        if (tasks.isEmpty()) {
            return "No tasks provided.";
        }

        List<TaskList.TaskItem> taskItems =
                tasks.stream().map(TaskListEntry::toTaskItem).toList();

        var cm = context.getContextManager();
        // Delegate to ContextManager to ensure centralized refresh via setTaskList
        context = cm.createOrReplaceTaskList(context, explanation, taskItems);

        var lines = IntStream.range(0, tasks.size())
                .mapToObj(i -> (i + 1) + ". " + tasks.get(i).title())
                .collect(Collectors.joining("\n"));
        var formattedTaskList = "# Task List\n" + lines + "\n";

        var io = cm.getIo();
        io.llmOutput("# Explanation\n\n" + explanation, ChatMessageType.AI, LlmOutputMeta.newMessage());

        int count = tasks.size();
        String suffix = (count == 1) ? "" : "s";
        String message =
                "**Task list created** with %d item%s. Review it in the **Tasks** tab or open the **Task List** fragment in the Workspace below."
                        .formatted(count, suffix);
        io.llmOutput(message, ChatMessageType.AI, LlmOutputMeta.newMessage());

        return formattedTaskList;
    }

    // --- Helper Methods ---

    private enum Bucket {
        ADDED("Added"),
        ALREADY_PRESENT("Already present (no-op)"),
        SKIPPED_FILE("Skipped (covered by file in workspace)"),
        SKIPPED_CLASS("Skipped (covered by class source in workspace)"),
        SKIPPED_FILE_SUMMARY("Skipped (covered by file summary in workspace)"),
        NOT_FOUND("Not found"),
        ERROR("Errors");

        private final String label;

        Bucket(String label) {
            this.label = label;
        }
    }

    private static class CoverageReporter {
        private final Context context;
        private final IAnalyzer analyzer;
        private final WorkspaceState state;
        private final Map<Bucket, List<String>> buckets = new EnumMap<>(Bucket.class);

        CoverageReporter(Context context, IAnalyzer analyzer, WorkspaceState state) {
            this.context = context;
            this.analyzer = analyzer;
            this.state = state;
        }

        void add(Bucket bucket, String identifier) {
            buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(identifier);
        }

        /**
         * Checks if a fragment should be added based on context presence and coverage rules.
         * Returns true if it should be added, false if it's already present or covered.
         */
        boolean maybeAdd(ContextFragment fragment, String identifier) {
            if (context.contains(fragment)) {
                add(Bucket.ALREADY_PRESENT, identifier);
                return false;
            }

            var units = fragment.sources().join();
            if (units.size() == 1) {
                var cu = units.iterator().next();
                var coveredBy = state.isCovered(cu, analyzer);
                if (coveredBy.isPresent()) {
                    add(coveredBy.get().bucket, identifier);
                    return false;
                }
            } else if (fragment instanceof ContextFragments.SummaryFragment sf
                    && sf.getSummaryType() == ContextFragment.SummaryType.FILE_SKELETONS) {
                // Special case for file summaries which don't have sources() populated the same way
                try {
                    var file = context.getContextManager().toFile(sf.getTargetIdentifier());
                    if (state.isCovered(file)) {
                        add(Bucket.SKIPPED_FILE, identifier);
                        return false;
                    }
                } catch (IllegalArgumentException ignored) {
                    add(Bucket.NOT_FOUND, identifier);
                }
            }

            add(Bucket.ADDED, identifier);
            return true;
        }

        String report() {
            StringBuilder sb = new StringBuilder();
            for (Bucket bucket : Bucket.values()) {
                List<String> items = buckets.get(bucket);
                if (items != null && !items.isEmpty()) {
                    sb.append(bucket.label)
                            .append(": ")
                            .append(items.stream().sorted().distinct().collect(Collectors.joining(", ")))
                            .append("\n");
                }
            }
            return sb.toString().trim();
        }
    }

    private enum CoverageReason {
        FILE(Bucket.SKIPPED_FILE),
        CLASS(Bucket.SKIPPED_CLASS),
        FILE_SUMMARY(Bucket.SKIPPED_FILE_SUMMARY);

        private final Bucket bucket;

        CoverageReason(Bucket bucket) {
            this.bucket = bucket;
        }
    }

    private record WorkspaceState(
            Set<ProjectFile> filesInWorkspace,
            Set<ProjectFile> fileSummariesInWorkspace,
            Set<CodeUnit> classInWorkspace) {

        boolean isCovered(ProjectFile file) {
            return filesInWorkspace.contains(file);
        }

        Optional<CoverageReason> isCovered(CodeUnit cu, IAnalyzer analyzer) {
            if (filesInWorkspace.contains(cu.source())) {
                return Optional.of(CoverageReason.FILE);
            }

            if (cu.isFunction() || cu.isField()) {
                return analyzer.parentOf(cu).filter(classInWorkspace::contains).isPresent()
                        ? Optional.of(CoverageReason.CLASS)
                        : Optional.empty();
            }

            if (cu.isClass()) {
                if (classInWorkspace.contains(cu)) {
                    return Optional.of(CoverageReason.CLASS);
                }
                if (fileSummariesInWorkspace.contains(cu.source())) {
                    return Optional.of(CoverageReason.FILE_SUMMARY);
                }
            }

            return Optional.empty();
        }
    }

    private WorkspaceState workspaceState() {
        var cm = context.getContextManager();
        var fragments = context.allFragments().toList();

        Set<ProjectFile> filesInWorkspace = fragments.stream()
                .filter(f -> f.getType() == ContextFragment.FragmentType.PROJECT_PATH)
                .flatMap(f -> f.sourceFiles().join().stream())
                .collect(Collectors.toSet());

        Set<ProjectFile> fileSummariesInWorkspace = new HashSet<>();
        Set<CodeUnit> classInWorkspace = new HashSet<>();

        for (ContextFragment fragment : fragments) {
            if (fragment instanceof ContextFragments.SummaryFragment sf) {
                if (sf.getSummaryType() == ContextFragment.SummaryType.FILE_SKELETONS) {
                    try {
                        fileSummariesInWorkspace.add(cm.toFile(sf.getTargetIdentifier()));
                    } catch (IllegalArgumentException ignored) {
                        // ignore invalid/legacy targets; we only use this as a best-effort optimization
                    }
                }
                continue;
            }

            if (fragment instanceof ContextFragments.CodeFragment cf) {
                for (CodeUnit cu : cf.sources().join()) {
                    if (cu.isClass()) {
                        classInWorkspace.add(cu);
                    }
                }
            }
        }

        return new WorkspaceState(filesInWorkspace, fileSummariesInWorkspace, classInWorkspace);
    }

    /**
     * Fetches content from a given URL. Public static for reuse.
     *
     * @param url The URL to fetch from.
     * @return The content as a String.
     * @throws IOException If fetching fails.
     */
    public static String fetchUrlContent(URI url) throws IOException {
        var connection = url.toURL().openConnection();
        // Set reasonable timeouts
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        // Set a user agent
        connection.setRequestProperty("User-Agent", "Brokk-Agent/1.0 (ContextTools)");

        try (var reader =
                new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private Set<ProjectFile> currentWorkspaceFiles() {
        return context.allFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.PROJECT_PATH)
                .flatMap(f -> f.sourceFiles().join().stream())
                .collect(Collectors.toSet());
    }

    private IAnalyzer getAnalyzer() {
        return context.getContextManager().getAnalyzerUninterrupted();
    }
}
