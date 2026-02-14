package ai.brokk.context;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.AbstractService;
import ai.brokk.IContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.concurrent.ComputedValue;
import ai.brokk.context.ContextFragments.HistoryFragment;
import ai.brokk.git.GitDistance;
import ai.brokk.git.GitRepo;
import ai.brokk.ranking.ImportPageRanker;
import ai.brokk.tasks.TaskList;
import ai.brokk.util.*;
import com.github.f4b6a3.uuid.UuidCreator;
import com.google.common.collect.Streams;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.time.Duration;
import java.util.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Encapsulates all state that will be sent to the model (prompts, filename context, conversation history).
 */
public class Context {
    private static final Logger logger = LogManager.getLogger(Context.class);

    private final UUID id;
    public static final Context EMPTY = new Context(new IContextManager() {});

    private static final String WELCOME_ACTION = "Session Start";
    public static final String SUMMARIZING = "(Summarizing)";
    public static final long CONTEXT_ACTION_SUMMARY_TIMEOUT_SECONDS = 5;

    private final transient IContextManager contextManager;

    // Unified list for all fragments (paths and virtuals)
    final List<ContextFragment> fragments;

    /**
     * Task history list. Each entry represents a user request and the subsequent conversation
     */
    final List<TaskEntry> taskHistory;

    private final Set<ContextFragment> markedReadonlyFragments;
    private final Set<ContextFragment> pinnedFragments;

    /**
     * Constructor for initial empty context
     */
    public Context(IContextManager contextManager) {
        this(newContextId(), contextManager, List.of(), List.of(), Set.of(), Set.of());
    }

    private Context(
            UUID id,
            IContextManager contextManager,
            List<ContextFragment> fragments,
            List<TaskEntry> taskHistory,
            Set<ContextFragment> markedReadonlyFragments,
            Set<ContextFragment> pinnedFragments) {
        for (var cf : fragments) {
            // TODO make a sealed interface for `fragments`
            assert !(cf instanceof HistoryFragment);
        }
        for (var cf : markedReadonlyFragments) {
            assert fragments.contains(cf);
        }
        for (var cf : pinnedFragments) {
            assert fragments.contains(cf);
        }

        this.id = id;
        this.contextManager = contextManager;
        this.fragments = List.copyOf(fragments);
        this.taskHistory = List.copyOf(taskHistory);
        this.markedReadonlyFragments = Set.copyOf(markedReadonlyFragments);
        this.pinnedFragments = Set.copyOf(pinnedFragments);
    }

    public Context(IContextManager contextManager, List<ContextFragment> fragments, List<TaskEntry> taskHistory) {
        this(newContextId(), contextManager, fragments, taskHistory, Set.of(), Set.of());
    }

    /**
     * Produces a structural overview of the code currently in context by summarizing symbols
     * (classes, methods, etc.) for relevant fragments.
     */
    @Blocking
    public String overview() throws InterruptedException {
        IAnalyzer analyzer = contextManager.getAnalyzer();

        return allFragments()
                .map(f -> {
                    String description = f.description().join();
                    StringBuilder sb =
                            new StringBuilder("# ").append(description).append("\n");

                    switch (f) {
                        case ContextFragments.ProjectPathFragment pf -> sb.append(analyzer.summarizeSymbols(pf.file()));
                        case ContextFragments.SummaryFragment sf -> {
                            if (sf.getSummaryType() == ContextFragment.SummaryType.FILE_SKELETONS) {
                                var file = contextManager.toFile(sf.getTargetIdentifier());
                                sb.append(analyzer.summarizeSymbols(file));
                            } else {
                                var units = analyzer.getDefinitions(sf.getTargetIdentifier());
                                if (!units.isEmpty()) {
                                    sb.append(analyzer.summarizeSymbols(units, CodeUnitType.ALL, 0));
                                }
                            }
                        }
                        case ContextFragments.CodeFragment cf -> {
                            var units = analyzer.getDefinitions(cf.getFullyQualifiedName());
                            if (!units.isEmpty()) {
                                sb.append(analyzer.summarizeSymbols(units, CodeUnitType.ALL, 0));
                            }
                        }
                        default -> {}
                    }
                    return sb.toString().trim();
                })
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    public Map<ProjectFile, String> buildRelatedSymbols(int k, int n, Set<ProjectFile> toIgnore)
            throws InterruptedException {
        var candidates = getMostRelevantFiles(n).stream()
                .filter(pf -> !toIgnore.contains(pf))
                .limit(k)
                .toList();
        IAnalyzer analyzer = contextManager.getAnalyzer();

        return candidates.parallelStream()
                .map(pf -> Map.entry(pf, analyzer.summarizeSymbols(pf)))
                .filter(e -> !e.getValue().isBlank())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    }

    public static UUID newContextId() {
        return UuidCreator.getTimeOrderedEpoch();
    }

    /**
     * Adds fragments to the context.
     * <p>
     * Fragments are deduplicated by semantic equivalence ({@code hasSameSource}) within the input and against the current context.
     * <p>
     * This method also handles context promotion: if a full {@code PATH} fragment is being added, any existing
     * {@code SKELETON} fragments covering the same files are considered superseded and are removed from the context.
     *
     * @param toAdd the collection of fragments to add
     * @return the updated Context
     */
    @Blocking
    public Context addFragments(Collection<? extends ContextFragment> toAdd) {
        if (toAdd.isEmpty()) {
            return this;
        }

        // Expand with supporting fragments while guarding against cycles and redundancy.
        LinkedHashSet<ContextFragment> expanded = new LinkedHashSet<>();
        Deque<ContextFragment> queue = new ArrayDeque<>(toAdd);

        while (!queue.isEmpty()) {
            ContextFragment f = queue.poll();
            // Check if we've already added a fragment with the same source.
            // This is expensive, O(N^2), keep an eye on this
            if (expanded.stream().noneMatch(f::hasSameSource)) {
                expanded.add(f);
                queue.addAll(f.supportingFragments());
            }
        }

        // 2. Identify files that are being added as full PATH fragments.
        // These will "kill" any existing SKELETON fragments for the same files.
        var incomingPathFiles = expanded.stream()
                .filter(f -> f instanceof ContextFragments.PathFragment)
                .map(f -> (ContextFragments.PathFragment) f)
                .flatMap(pf -> pf.files().join().stream())
                .collect(Collectors.toSet());

        // 3. Process the CURRENT fragments:
        //    a) Identify SUMMARY fragments superseded by incoming PATHS.
        //    b) Keep everything else (we will deduplicate against new inputs in the next step).
        var partitioned = this.fragments.stream().collect(Collectors.partitioningBy(f -> {
            if (f instanceof ContextFragments.SummaryFragment) {
                var skeletonFiles = f.files().join();
                // If the skeleton's files overlap with incoming full paths, drop the skeleton.
                return !Collections.disjoint(skeletonFiles, incomingPathFiles);
            }
            return false;
        }));

        var supersededFragments = castNonNull(partitioned.get(true));
        var keptExistingFragments = new ArrayList<>(castNonNull(partitioned.get(false)));

        // 4. Calculate the ACTUAL new items to add.
        //    We filter 'uniqueInputs' to ensure we don't add something that already exists
        //    in the (cleaned) existing list.
        var fragmentsToAdd = expanded.stream()
                .filter(input -> keptExistingFragments.stream().noneMatch(existing -> existing.hasSameSource(input)))
                .toList();

        if (fragmentsToAdd.isEmpty()) {
            return this;
        }

        // 5. Merge
        keptExistingFragments.addAll(fragmentsToAdd);

        // 6. Cleanup tracking for superseded fragments
        var newReadOnly = this.markedReadonlyFragments.stream()
                .filter(f -> !supersededFragments.contains(f))
                .collect(Collectors.toSet());
        var newPinned = this.pinnedFragments.stream()
                .filter(f -> !supersededFragments.contains(f))
                .collect(Collectors.toSet());

        return new Context(newContextId(), contextManager, keptExistingFragments, taskHistory, newReadOnly, newPinned);
    }

    @Blocking
    public Optional<ContextFragment> findWithSameSource(ContextFragment fragment) {
        return fragments.stream().filter(f -> f.hasSameSource(fragment)).findFirst();
    }

    public Context addFragments(ContextFragment fragment) {
        return addFragments(List.of(fragment));
    }

    /**
     * Returns files relevant to this context, prioritizing Git-based distance and supplementing with
     * import-based PageRank if fewer than {@code topK} results are found.
     */
    @Blocking
    public List<ProjectFile> getMostRelevantFiles(int topK) throws InterruptedException {
        if (topK <= 0) return List.of();

        var ineligibleSources = fragments.stream()
                .filter(f -> !f.isEligibleForAutoContext())
                .flatMap(f -> f.files().join().stream())
                .collect(Collectors.toSet());

        record WeightedFile(ProjectFile file, double weight) {}

        var weightedSeeds = fragments.stream()
                .filter(f -> !f.files().join().isEmpty())
                .flatMap(fragment -> {
                    double weight = Math.sqrt(1.0 / fragment.files().join().size());
                    return fragment.files().join().stream().map(file -> new WeightedFile(file, weight));
                })
                .collect(Collectors.groupingBy(wf -> wf.file, HashMap::new, Collectors.summingDouble(wf -> wf.weight)));

        if (weightedSeeds.isEmpty()) {
            return List.of();
        }

        Set<ProjectFile> resultFiles = new LinkedHashSet<>();
        var repoObj = contextManager.getRepo();

        // 1. Try Git-based distance first if a real GitRepo is available
        if (repoObj instanceof GitRepo gr) {
            try {
                var gitResults = GitDistance.getRelatedFiles(gr, weightedSeeds, topK);
                resultFiles.addAll(filterResults(gitResults, ineligibleSources));
            } catch (Exception e) {
                logger.warn("Failed to compute Git-based related files; falling back to imports.", e);
            }
        }

        // 2. Supplement with Import-based PageRank if we need more results
        if (resultFiles.size() < topK) {
            int remaining = topK - resultFiles.size();
            IAnalyzer analyzer = contextManager.getAnalyzer();
            var importResults = ImportPageRanker.getRelatedFilesByImports(analyzer, weightedSeeds, topK, false);
            filterResults(importResults, ineligibleSources).stream()
                    .filter(file -> !resultFiles.contains(file))
                    .limit(remaining)
                    .forEach(resultFiles::add);
        }

        return List.copyOf(resultFiles);
    }

    private List<ProjectFile> filterResults(List<IAnalyzer.FileRelevance> results, Set<ProjectFile> ineligibleSources) {
        return results.stream()
                .map(IAnalyzer.FileRelevance::file)
                .filter(file -> !ineligibleSources.contains(file))
                .toList();
    }

    /**
     * 1) Gather all classes from each fragment.
     * 2) Compute related files and take up to topK.
     * 3) Return a List of SummaryFragment for the top results.
     */
    public List<ContextFragments.SummaryFragment> buildAutoContext(int topK) throws InterruptedException {
        IAnalyzer analyzer = contextManager.getAnalyzer();

        var relevantFiles = getMostRelevantFiles(topK);
        if (relevantFiles.isEmpty()) {
            return List.of();
        }

        List<String> targetFqns = new ArrayList<>();
        for (var sourceFile : relevantFiles) {
            targetFqns.addAll(analyzer.getTopLevelDeclarations(sourceFile).stream()
                    .map(CodeUnit::fqName)
                    .toList());
            if (targetFqns.size() >= topK) break;
        }

        if (targetFqns.isEmpty()) {
            return List.of();
        }

        return targetFqns.stream()
                .limit(topK)
                .map(fqn -> new ContextFragments.SummaryFragment(
                        contextManager, fqn, ContextFragment.SummaryType.CODEUNIT_SKELETON))
                .toList();
    }

    // ---------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------

    public UUID id() {
        return id;
    }

    /**
     * Convenience overload to test if a fragment instance is tracked as read-only in this Context.
     */
    public boolean isMarkedReadonly(ContextFragment fragment) {
        return markedReadonlyFragments.contains(fragment);
    }

    /**
     * Returns true if the fragment is pinned (protected from being dropped by tools).
     */
    public boolean isPinned(ContextFragment fragment) {
        return pinnedFragments.contains(fragment);
    }

    /**
     * Returns the fragments explicitly marked read-only in this Context.
     * Only fragments that have been setReadOnly(...) are included.
     */
    public Stream<ContextFragment> getMarkedReadonlyFragments() {
        return markedReadonlyFragments.stream();
    }

    public Stream<ContextFragment> getPinnedFragments() {
        return pinnedFragments.stream();
    }

    /**
     * Anything that's not editable is implicitly read-only
     */
    public Stream<ContextFragment> getReadonlyFragments() {
        var editable = getEditableFragments().collect(Collectors.toSet());
        return fragments.stream().filter(cf -> !editable.contains(cf));
    }

    /**
     * Returns editable fragments. Virtual (non-path) editable fragments (e.g. Code, Usage)
     * are returned before path-based fragments (e.g. ProjectPath).
     */
    public Stream<ContextFragment> getEditableFragments() {
        return fragments.stream()
                .filter(cf -> cf.getType().isEditable())
                .filter(cf -> !markedReadonlyFragments.contains(cf))
                .sorted(Comparator.comparing(f -> f.getType().isPath()));
    }

    public Stream<ContextFragment> allFragments() {
        return fragments.stream();
    }

    public Context removeFragmentsByIds(Collection<String> ids) {
        if (ids.isEmpty()) return this;
        var toDrop = this.fragments.stream().filter(f -> ids.contains(f.id())).collect(Collectors.toList());
        return removeFragments(toDrop);
    }

    /**
     * Removes fragments from this context.
     */
    public Context removeFragments(Collection<? extends ContextFragment> toRemove) {
        var toRemoveSet = new HashSet<>(toRemove);
        var actualToRemove = fragments.stream().filter(toRemoveSet::contains).toList();

        if (actualToRemove.isEmpty()) {
            return this;
        }

        var newFragments =
                fragments.stream().filter(f -> !toRemoveSet.contains(f)).toList();

        // Remove any tracking for dropped fragments
        var newReadOnly = this.markedReadonlyFragments.stream()
                .filter(f -> !toRemoveSet.contains(f))
                .collect(Collectors.toSet());
        var newPinned = this.pinnedFragments.stream()
                .filter(f -> !toRemoveSet.contains(f))
                .collect(Collectors.toSet());

        return new Context(newContextId(), contextManager, newFragments, taskHistory, newReadOnly, newPinned);
    }

    public Context removeAll() {
        return new Context(newContextId(), contextManager, List.of(), List.of(), Set.of(), Set.of());
    }

    public Context withPinned(ContextFragment fragment, boolean pinned) {
        assert fragments.contains(fragment) : "%s is not part of %s".formatted(fragment, fragments);

        var newPinned = new HashSet<>(this.pinnedFragments);
        if (pinned) {
            newPinned.add(fragment);
        } else {
            newPinned.remove(fragment);
        }

        return new Context(
                newContextId(), contextManager, fragments, taskHistory, this.markedReadonlyFragments, newPinned);
    }

    public Context setReadonly(ContextFragment fragment, boolean readonly) {
        assert fragment.getType().isEditable();
        assert fragments.contains(fragment) : "%s is not part of %s".formatted(fragment, fragments);

        // Update Context-level read-only tracking using the exact instance passed
        var newReadOnly = new HashSet<>(this.markedReadonlyFragments);
        if (readonly) {
            newReadOnly.add(fragment);
        } else {
            newReadOnly.remove(fragment);
        }

        return new Context(newContextId(), contextManager, fragments, taskHistory, newReadOnly, this.pinnedFragments);
    }

    public boolean isEmpty() {
        return fragments.isEmpty() && taskHistory.isEmpty();
    }

    /**
     * Returns true if the workspace contains no file content.
     */
    @Blocking
    public boolean isFileContentEmpty() {
        return fragments.stream().allMatch(f -> f.files().join().isEmpty());
    }

    Context addHistoryEntryInternal(TaskEntry taskEntry) {
        var newTaskHistory =
                Streams.concat(taskHistory.stream(), Stream.of(taskEntry)).toList();
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                newTaskHistory,
                this.markedReadonlyFragments,
                this.pinnedFragments);
    }

    public Context clearHistory() {
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                List.of(),
                this.markedReadonlyFragments,
                this.pinnedFragments);
    }

    /**
     * @return an immutable copy of the task history.
     */
    public List<TaskEntry> getTaskHistory() {
        return taskHistory;
    }

    public ComputedValue<String> getAction(@Nullable Context previous) {
        var prev = (previous == null) ? EMPTY : previous;

        return ContextDelta.between(prev, this).flatMap(delta -> {
            if (delta.isEmpty() && isEmpty()) {
                return ComputedValue.completed(WELCOME_ACTION);
            }
            return delta.description(contextManager);
        });
    }

    public IContextManager getContextManager() {
        return contextManager;
    }

    /**
     * Returns all fragments in display order:
     * 1. Conversation history (if not empty)
     * 2. Pinned fragments
     * 3. File/path fragments (unpinned)
     * 4. Other virtual fragments (unpinned)
     */
    public List<ContextFragment> getAllFragmentsInDisplayOrder() {
        var result = new ArrayList<ContextFragment>();

        if (!taskHistory.isEmpty()) {
            result.add(new HistoryFragment(contextManager, taskHistory));
        }

        // 2. Pinned fragments
        result.addAll(pinnedFragments);

        // 3. Unpinned Path fragments
        result.addAll(fragments.stream()
                .filter(f -> f.getType().isPath())
                .filter(f -> !pinnedFragments.contains(f))
                .toList());

        // 4. Unpinned Virtual fragments
        result.addAll(fragments.stream()
                .filter(f -> !f.getType().isPath())
                .filter(f -> !pinnedFragments.contains(f))
                .toList());

        return result;
    }

    /**
     * Creates a Context with explicit control over all fields including description override.
     * Used by DtoMapper during deserialization.
     */
    public static Context createWithId(
            UUID id,
            IContextManager cm,
            List<ContextFragment> fragments,
            List<TaskEntry> history,
            Set<ContextFragment> readOnlyFragments,
            Set<ContextFragment> pinnedFragments) {
        return new Context(id, cm, fragments, history, readOnlyFragments, pinnedFragments);
    }

    /**
     * Creates a new Context with a modified task history list. This generates a new context state with a new ID.
     */
    public Context withHistory(List<TaskEntry> newHistory) {
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                newHistory,
                this.markedReadonlyFragments,
                this.pinnedFragments);
    }

    /**
     * Creates a new (live) Context that copies specific elements from the provided context.
     */
    public static Context createFrom(Context sourceContext, Context currentContext, List<TaskEntry> newHistory) {
        // Fragments should already be live from migration logic; use them directly
        var fragments = sourceContext.allFragments().toList();

        return new Context(
                newContextId(),
                currentContext.contextManager,
                fragments,
                newHistory,
                sourceContext.markedReadonlyFragments,
                sourceContext.pinnedFragments);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof Context context)) return false;
        return id.equals(context.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /**
     * Retrieves the Discarded Context special fragment and returns a Map of description -> explanation.
     * Parses JSON directly; returns an empty map if absent or parse fails.
     */
    @Blocking
    public Map<String, String> getDiscardedFragmentsNotes() {
        return getSpecial(SpecialTextType.DISCARDED_CONTEXT.description())
                .map(sf -> SpecialTextType.deserializeDiscardedContext(sf.text().join()))
                .orElseGet(LinkedHashMap::new);
    }

    // --- SpecialTextType helpers ---

    public Optional<ContextFragments.StringFragment> getSpecial(String description) {
        // Since special looks for self-freezing fragments, we can reliably use `renderNow`
        return allFragments()
                .filter(f -> f instanceof ContextFragments.StringFragment sf
                        && description.equals(sf.description().renderNowOrNull()))
                .map(ContextFragments.StringFragment.class::cast)
                .findFirst();
    }

    @Blocking
    public Context withSpecial(SpecialTextType type, String content) {
        var desc = type.description();

        var existing = getSpecial(desc);
        if (existing.isPresent() && content.equals(existing.get().text().join())) {
            return this;
        }

        var idsToDrop = allFragments()
                .filter(f -> f instanceof ContextFragments.StringFragment sf
                        && desc.equals(sf.description().renderNowOrNull()))
                .map(ContextFragment::id)
                .toList();

        var afterClear = idsToDrop.isEmpty() ? this : removeFragmentsByIds(idsToDrop);

        var sf = type.create(getContextManager(), content);

        var newFragments = new ArrayList<>(afterClear.fragments);
        newFragments.add(sf);

        var newPinned = new HashSet<>(afterClear.pinnedFragments);
        if (!type.droppable()) {
            newPinned.add(sf);
        }

        // Preserve parsedOutput by default
        return new Context(
                newContextId(),
                getContextManager(),
                newFragments,
                afterClear.taskHistory,
                afterClear.markedReadonlyFragments,
                newPinned);
    }

    public boolean workspaceContentEquals(Context other) {
        var thisFragments = allFragments().toList();
        var otherFragments = other.allFragments().toList();

        if (thisFragments.size() != otherFragments.size()) {
            return false;
        }

        // Check semantic equivalence using hasSameSource for all fragments
        for (var thisFragment : thisFragments) {
            boolean found = otherFragments.stream().anyMatch(thisFragment::hasSameSource);
            if (!found) {
                return false;
            }
        }

        return true;
    }

    /**
     * Returns true if the given fragment is equivalent to one already in the Context
     */
    public boolean contains(ContextFragment fragment) {
        return allFragments().anyMatch(fragment::hasSameSource);
    }

    /**
     * Returns the processed output text from the latest build failure fragment in this Context. Empty string if there
     * is no build failure recorded.
     */
    @Blocking
    public String getBuildError() {
        return getBuildFragment()
                .map(ContextFragments.AbstractStaticFragment::text)
                .map(cv -> cv.renderNowOr(""))
                .orElse("");
    }

    public Optional<ContextFragments.StringFragment> getBuildFragment() {
        return getSpecial(SpecialTextType.BUILD_RESULTS.description());
    }

    /**
     * Updates the Latest Build Results special fragment.
     * - On success: remove existing BUILD_RESULTS fragment if present.
     * - On failure: upsert the BUILD_RESULTS fragment with the processed output.
     */
    @Blocking
    public Context withBuildResult(boolean success, String processedOutput) {
        if (success) {
            var existing = getSpecial(SpecialTextType.BUILD_RESULTS.description());
            if (existing.isEmpty()) {
                return this;
            }
            return removeFragmentsByIds(List.of(existing.get().id()));
        }

        String note =
                """
        [HARNESS NOTE: The build is currently FAILING. I will update it automatically when Code Agent makes changes.
        You do not need to attempt an explicit rebuild.]

        """;
        return withSpecial(SpecialTextType.BUILD_RESULTS, note + processedOutput);
    }

    /**
     * Retrieves the Task List fragment if present.
     */
    public Optional<ContextFragments.StringFragment> getTaskListFragment() {
        return getSpecial(SpecialTextType.TASK_LIST.description());
    }

    /**
     * Returns the current Task List data parsed from the Task List fragment or an empty list on absence/parse error.
     */
    public TaskList.TaskListData getTaskListDataOrEmpty() {
        var existing = getTaskListFragment();
        if (existing.isEmpty()) {
            return new TaskList.TaskListData(null, List.of());
        }
        try {
            var fragment = existing.get();
            var textOpt = fragment.text().tryGet();
            return textOpt.map(s -> Json.fromJson(s, TaskList.TaskListData.class))
                    .orElseGet(() -> {
                        logger.warn("Failed to load Task List JSON in time for {}", fragment);
                        return new TaskList.TaskListData(null, List.of());
                    });
        } catch (Exception e) {
            logger.warn("Failed to parse Task List JSON", e);
            return new TaskList.TaskListData(null, List.of());
        }
    }

    /**
     * Updates the Task List fragment with the provided JSON. Clears previous Task List fragments before adding a new one.
     */
    private Context withTaskList(String json) {
        return withSpecial(SpecialTextType.TASK_LIST, json);
    }

    /**
     * Refreshes all computed fragments in this context without filtering.
     *
     * @return a new context with refreshed fragments, or this context if no changes occurred
     */
    @Blocking
    public Context copyAndRefresh() {
        return copyAndRefreshInternal(Set.copyOf(fragments));
    }

    /**
     * Serializes and updates the Task List fragment using TaskList.TaskListData.
     * If the task list is empty, removes any existing Task List fragment instead of creating an empty one.
     */
    public Context withTaskList(TaskList.TaskListData data) {
        // If tasks are empty, remove the Task List fragment instead of creating an empty one
        if (data.tasks().isEmpty()) {
            var existing = getSpecial(SpecialTextType.TASK_LIST.description());
            if (existing.isEmpty()) {
                return this; // No change needed; no fragment to remove
            }
            return removeFragmentsByIds(List.of(existing.get().id()));
        }

        // Non-empty case: serialize and update normally
        String json = Json.toJson(data);
        return withTaskList(json);
    }

    /**
     * Refreshes fragments whose source files intersect the provided set.
     *
     * @param maybeChanged     set of project files that may have changed
     * @return a new context with refreshed fragments, or this context if no changes occurred
     */
    @Blocking
    public Context copyAndRefresh(Set<ProjectFile> maybeChanged) {
        if (maybeChanged.isEmpty()) {
            return this;
        }

        // Map each fragment to the set of ProjectFiles it contains
        var fragmentsToRefresh = new HashSet<ContextFragment>();
        for (var f : fragments) {
            if (!Collections.disjoint(f.files().join(), maybeChanged)) {
                fragmentsToRefresh.add(f);
            }
        }

        return copyAndRefreshInternal(fragmentsToRefresh);
    }

    /**
     * Core refresh logic: refreshes the specified fragments and tracks content changes via diff.
     * Handles remapping read-only membership for replaced fragments.
     *
     * @param maybeChanged the set of fragments to potentially refresh
     * @return a new context with refreshed fragments, or this context if no changes occurred
     */
    @Blocking
    private Context copyAndRefreshInternal(Set<ContextFragment> maybeChanged) {
        if (maybeChanged.isEmpty()) {
            return this;
        }

        var newFragments = new ArrayList<ContextFragment>(fragments.size());
        boolean anyReplaced = false;

        // Track replacements so we can remap read-only membership
        var replacementMap = new HashMap<ContextFragment, ContextFragment>();

        for (var f : fragments) {
            ContextFragment fragmentToAdd = f;

            if (maybeChanged.contains(f)) {
                var refreshed = f.refreshCopy();
                if (!refreshed.contentEquals(f)) {
                    // Content actually changed; mark as replaced
                    anyReplaced = true;
                    replacementMap.put(f, refreshed);
                    fragmentToAdd = refreshed;
                }
            }

            newFragments.add(fragmentToAdd);
        }

        if (!anyReplaced) {
            // No content to update; keep original Context
            return this;
        }

        // Remap read-only and pinned membership to refreshed fragments to preserve state across replacement
        var newReadOnly = new HashSet<>(this.markedReadonlyFragments);
        var newPinned = new HashSet<>(this.pinnedFragments);
        for (var e : replacementMap.entrySet()) {
            var oldFrag = e.getKey();
            var newFrag = e.getValue();
            if (newReadOnly.remove(oldFrag)) {
                newReadOnly.add(newFrag);
            }
            if (newPinned.remove(oldFrag)) {
                newPinned.add(newFrag);
            }
        }

        return new Context(newContextId(), contextManager, newFragments, taskHistory, newReadOnly, newPinned);
    }

    /**
     * Best-effort snapshot seeding to ensure context contents are materialized.
     * Blocks for a total duration of the timeout parameter across all fragments,
     * not timeout-per-fragment.
     */
    @Blocking
    public void awaitContentsAreComputed(Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        for (var fragment : this.allFragments().toList()) {
            if (fragment instanceof ContextFragment.ComputedFragment cf) {
                long remainingMillis = deadline - System.currentTimeMillis();
                if (remainingMillis <= 0) {
                    break; // Timeout exhausted
                }
                cf.await(Duration.ofMillis(remainingMillis));
            }
        }
    }

    public Context addHistoryEntry(TaskEntry te) {
        var renumbered = new TaskEntry(getTaskHistory().size(), te.mopLog(), te.llmLog(), te.summary(), te.meta());
        return addHistoryEntryInternal(renumbered);
    }

    public Context addHistoryEntry(
            List<ChatMessage> mopMessages,
            List<ChatMessage> llmMessages,
            TaskResult.Type type,
            StreamingChatModel model,
            String instructions) {
        var mc = AbstractService.ModelConfig.from(model, contextManager.getService());
        return addHistoryEntryInternal(new TaskEntry(
                getTaskHistory().size(),
                new ContextFragments.TaskFragment(mopMessages, instructions),
                new ContextFragments.TaskFragment(llmMessages, instructions),
                null,
                new TaskResult.TaskMeta(type, mc)));
    }

    public Context addHistoryEntry(
            List<ChatMessage> messages, TaskResult.Type type, StreamingChatModel model, String instructions) {
        return addHistoryEntry(messages, messages, type, model, instructions);
    }

    public Context addHistoryEntry(ContextFragments.TaskFragment log, @Nullable TaskResult.TaskMeta meta) {
        return addHistoryEntryInternal(new TaskEntry(getTaskHistory().size(), log, log, null, meta));
    }
}
