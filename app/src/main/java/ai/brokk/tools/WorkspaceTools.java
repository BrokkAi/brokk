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
import ai.brokk.util.Json;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.model.output.structured.Description;
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

    // Per-instance working context (immutable Context instances replaced on modification)
    private Context context;

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

    /**
     * Represents a fragment removal request with its ID and structured drop metadata.
     * Used by {@link #dropWorkspaceFragments(List)} to structure the input.
     */
    public record FragmentRemoval(
            @Description("The alphanumeric ID exactly as listed in <workspace_toc>") String fragmentId,
            @Description(KEY_FACTS_DESCRIPTION) String keyFacts,
            @Description(DROP_REASON_DESCRIPTION) String dropReason) {}

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
            return "File paths list cannot be empty.";
        }

        List<ProjectFile> projectFiles = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String path : relativePaths) {
            try {
                var file = context.getContextManager().toFile(path);
                if (!file.exists()) {
                    errors.add("File at `%s` does not exist (remember, don't use this method to create new files)"
                            .formatted(path));
                    continue;
                }
                if (file.isDirectory()) {
                    errors.add(
                            "File path " + path + " is a directory; only normal files may be added to the Workspace");
                    continue;
                }
                projectFiles.add(file);
            } catch (IllegalArgumentException e) {
                errors.add("Invalid path: " + path);
            }
        }

        // Determine already-present files and files-to-add based on current workspace state
        var workspaceFiles = currentWorkspaceFiles();
        var distinctRequested = new HashSet<>(projectFiles);
        var toAddFiles = distinctRequested.stream()
                .filter(f -> !workspaceFiles.contains(f))
                .toList();
        var alreadyPresent = distinctRequested.stream()
                .filter(workspaceFiles::contains)
                .map(ProjectFile::toString)
                .sorted()
                .toList();

        var fragments = context.getContextManager().toPathFragments(toAddFiles);
        context = context.addFragments(fragments);

        String addedNames =
                toAddFiles.stream().map(ProjectFile::toString).sorted().collect(Collectors.joining(", "));
        String result = "";
        if (addedNames.isEmpty()) {
            result += "No new files added.\n";
        } else {
            result += "Added to the workspace: %s\n".formatted(addedNames);
        }
        if (!alreadyPresent.isEmpty()) {
            result += "Already present (no-op): %s.\n".formatted(String.join(", ", alreadyPresent));
        }
        if (!errors.isEmpty()) {
            result += "Errors were: [%s]\n".formatted(String.join(", ", errors));
        }
        return result;
    }

    @Tool(
            "Add classes to the Workspace by their fully qualified names. This adds read-only code fragments for those classes. Only call when you have identified specific class names.")
    public String addClassesToWorkspace(
            @P(
                            "List of fully qualified class names (e.g., ['com.example.MyClass', 'org.another.Util']). Must not be empty.")
                    List<String> classNames) {
        if (classNames.isEmpty()) {
            return "Class names list cannot be empty.";
        }

        int initialFragmentCount = (int) context.allFragments().count();
        context = Context.withAddedClasses(context, classNames, getAnalyzer());
        int addedCount = (int) context.allFragments().count() - initialFragmentCount;

        if (addedCount == 0) {
            return "Could not find definitions for any of the provided class names: " + String.join(", ", classNames);
        }

        return "Added %d code fragment(s) for requested classes.".formatted(addedCount);
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
            int initialFragmentCount = (int) context.allFragments().count();
            context = Context.withAddedUrlContent(context, urlString);
            int addedCount = (int) context.allFragments().count() - initialFragmentCount;

            if (addedCount == 0) {
                return "Fetched content from URL is empty: " + urlString;
            }

            logger.debug("Successfully added URL content to context");
            return "Added content from URL [%s] as a read-only text fragment.".formatted(urlString);
        } catch (URISyntaxException e) {
            return "Invalid URL format: " + urlString;
        } catch (IOException e) {
            logger.error("Failed to fetch or process URL content: {}", urlString, e);
            throw new RuntimeException("Failed to fetch URL content for " + urlString + ": " + e.getMessage(), e);
        }
    }

    @Tool(
            value = "Remove specified fragments (files, text snippets, task history, analysis results) "
                    + "from the Workspace and record structured breadcrumbs (keyFacts + dropReason) in DISCARDED_CONTEXT. "
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
                idsToDropSet.stream().filter(id -> !byId.containsKey(id)).toList();

        // Partition found into droppable vs protected based on pinning policy
        var partitioned =
                foundFragments.stream().collect(Collectors.partitioningBy(fragment -> !context.isPinned(fragment)));
        var toDrop = NullnessUtil.castNonNull(partitioned.get(true));
        var protectedFragments = NullnessUtil.castNonNull(partitioned.get(false));

        // Merge explanations for successfully dropped fragments (new overwrites old)
        var existingDiscardedMap = context.getDiscardedFragmentsNote();
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
        String discardedJson;
        try {
            discardedJson = Json.getMapper().writeValueAsString(mergedDiscarded);
        } catch (Exception e) {
            logger.error("Failed to serialize DISCARDED_CONTEXT JSON", e);
            context.getContextManager().reportException(e);
            return "Error: Failed to serialize DISCARDED_CONTEXT JSON: " + e.getMessage();
        }

        // Apply removal and upsert DISCARDED_CONTEXT in the local context
        var droppedIds = toDrop.stream().map(ContextFragment::id).collect(Collectors.toSet());
        var next =
                context.removeFragmentsByIds(droppedIds).withSpecial(SpecialTextType.DISCARDED_CONTEXT, discardedJson);
        context = next;

        logger.debug(
                "dropWorkspaceFragments: dropped={}, protected={}, unknown={}, updatedDiscardedEntries={}",
                droppedIds.size(),
                protectedFragments.size(),
                unknownIds.size(),
                mergedDiscarded.size());

        var droppedReprs = toDrop.stream().map(ContextFragment::repr).collect(Collectors.joining(", "));
        var baseMsg = "Dropped %d fragment(s): [%s]. Updated DISCARDED_CONTEXT with %d entr%s."
                .formatted(
                        droppedIds.size(),
                        droppedReprs,
                        mergedDiscarded.size(),
                        mergedDiscarded.size() == 1 ? "y" : "ies");

        if (!protectedFragments.isEmpty()) {
            var protectedDescriptions = protectedFragments.stream()
                    .map(ContextFragment::description)
                    .map(ComputedValue::join)
                    .collect(Collectors.joining(", "));
            baseMsg += " Protected (not dropped): " + protectedDescriptions + ".";
        }

        if (!unknownIds.isEmpty()) {
            baseMsg += " Unknown fragment IDs: " + String.join(", ", unknownIds);
        }
        return baseMsg;
    }

    @Tool(
            """
                  Finds usages of a specific symbol (class, method, field) and adds the full source of the calling methods to the Workspace. Only call when you have identified specific symbols.
                  Use this for questions like “how is X used/accessed/obtained/wired”.
                  If you don’t know the fully qualified symbol name, call searchSymbols once to get it.
                  """)
    public String addSymbolUsagesToWorkspace(
            @P(
                            "Fully qualified symbol name (e.g., 'com.example.MyClass', 'com.example.MyClass.myMethod', 'com.example.MyClass.myField') to find usages for.")
                    String symbol) {
        assert !getAnalyzer().isEmpty() : "Cannot add usages: Code Intelligence is not available.";
        if (symbol.isBlank()) {
            return "Cannot add usages: symbol cannot be empty";
        }

        var fragment = new ContextFragments.UsageFragment(context.getContextManager(), symbol); // Pass contextManager
        context = context.addFragments(List.of(fragment));

        return "Added dynamic usage analysis for symbol '%s'.".formatted(symbol);
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
            return "Cannot add summary: class names list is empty";
        }

        List<String> distinctClassNames = classNames.stream().distinct().toList();
        if (distinctClassNames.isEmpty()) {
            return "Cannot add summary: class names list resolved to empty";
        }

        int initialFragmentCount = (int) context.allFragments().count();
        context = Context.withAddedClassSummaries(context, distinctClassNames);
        int addedCount = (int) context.allFragments().count() - initialFragmentCount;

        return "Added %d dynamic class summar%s for: [%s]"
                .formatted(addedCount, addedCount == 1 ? "y" : "ies", String.join(", ", distinctClassNames));
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
            return "Cannot add summaries: file paths list is empty.";
        }

        var project = (AbstractProject) context.getContextManager().getProject();
        int initialFragmentCount = (int) context.allFragments().count();
        context = Context.withAddedFileSummaries(context, filePaths, project);
        int addedCount = (int) context.allFragments().count() - initialFragmentCount;

        if (addedCount == 0) {
            return "No project files found matching the provided patterns: " + String.join(", ", filePaths);
        }

        return "Added %d dynamic file summar%s.".formatted(addedCount, addedCount == 1 ? "y" : "ies");
    }

    @Tool(
            """
                  Retrieves the full source code of specific methods or functions and adds to the Workspace each as a separate read-only text fragment.
                  Faster and more efficient than including entire files or classes when you only need a few methods.
                  """)
    public String addMethodsToWorkspace(
            @P(
                            "List of fully qualified method names (e.g., ['com.example.ClassA.method1', 'org.another.ClassB.processData']) to retrieve sources for. Must not be empty.")
                    List<String> methodNames) {
        if (methodNames.isEmpty()) {
            return "Cannot add method sources: method names list is empty";
        }

        int initialFragmentCount = (int) context.allFragments().count();
        context = Context.withAddedMethodSources(context, methodNames, getAnalyzer());
        int addedCount = (int) context.allFragments().count() - initialFragmentCount;

        if (addedCount == 0) {
            return "No sources found for methods: " + String.join(", ", methodNames);
        }

        return "Added %d method source(s).".formatted(addedCount);
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
            "addSymbolUsagesToWorkspace",
            "addFileSummariesToWorkspace",
            // Search tools
            "searchSymbols",
            "getSymbolLocations",
            "getUsages",
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
            """;

    public record TaskListEntry(
            @P("Short display title for the task.") String title,
            @P("The full task description (Markdown encouraged).") String instructions,
            @P("Files and fully qualified method/class names important to implement the task.") String keyLocations,
            @P(
                            "Useful discoveries from OUTSIDE the key locations. Note: the Workspace will change as tasks are loaded and executed, so you must capture important discoveries here to preserve them for future tasks.")
                    String keyDiscoveries) {

        public TaskList.TaskItem toTaskItem() {
            String combinedText = instructions.strip();
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
                .flatMap(f -> f.files().join().stream())
                .collect(Collectors.toSet());
    }

    private IAnalyzer getAnalyzer() {
        return context.getContextManager().getAnalyzerUninterrupted();
    }
}
