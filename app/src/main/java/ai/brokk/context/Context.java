package ai.brokk.context;

import ai.brokk.Completions;
import ai.brokk.IContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragments.HistoryFragment;
import ai.brokk.git.GitDistance;
import ai.brokk.git.GitRepo;
import ai.brokk.project.AbstractProject;
import ai.brokk.ranking.ImportPageRanker;
import ai.brokk.tasks.TaskList;
import ai.brokk.tools.WorkspaceTools;
import ai.brokk.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.f4b6a3.uuid.UuidCreator;
import com.google.common.collect.Streams;
import dev.langchain4j.data.message.ChatMessageType;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
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
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
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
    public static final long CONTEXT_ACTION_SUMMARY_TIMEOUT_SECONDS = 5;

    /** @deprecated Action strings are no longer stored; use getDescription(previous) instead */
    @Deprecated
    public final transient CompletableFuture<String> action;

    private final transient IContextManager contextManager;

    // Unified list for all fragments (paths and virtuals)
    final List<ContextFragment> fragments;

    /**
     * Task history list. Each entry represents a user request and the subsequent conversation
     */
    final List<TaskEntry> taskHistory;

    /**
     * LLM output or other parsed content, with optional fragment. May be null
     */
    @Nullable
    final transient ContextFragments.TaskFragment parsedOutput;

    @Nullable
    private final UUID groupId;

    @Nullable
    private final String groupLabel;

    @Nullable
    private final String descriptionOverride;

    private final Set<ContextFragment> markedReadonlyFragments;
    private final Set<ContextFragment> pinnedFragments;

    /**
     * Constructor for initial empty context
     */
    public Context(IContextManager contextManager) {
        this(
                newContextId(),
                contextManager,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                Set.of(),
                Set.of());
    }

    private Context(
            UUID id,
            IContextManager contextManager,
            List<ContextFragment> fragments,
            List<TaskEntry> taskHistory,
            @Nullable ContextFragments.TaskFragment parsedOutput,
            @Nullable UUID groupId,
            @Nullable String groupLabel,
            @Nullable String descriptionOverride,
            Set<ContextFragment> markedReadonlyFragments,
            Set<ContextFragment> pinnedFragments) {
        this.id = id;
        this.contextManager = contextManager;
        this.fragments = List.copyOf(fragments);
        this.taskHistory = List.copyOf(taskHistory);
        this.parsedOutput = parsedOutput;
        this.groupId = groupId;
        this.groupLabel = groupLabel;
        this.descriptionOverride = descriptionOverride;
        this.action = CompletableFuture.completedFuture("");
        this.markedReadonlyFragments = validateReadOnlyFragments(markedReadonlyFragments, fragments);
        this.pinnedFragments = validatePinnedFragments(pinnedFragments, fragments);
    }

    public Context(
            IContextManager contextManager,
            List<ContextFragment> fragments,
            List<TaskEntry> taskHistory,
            @Nullable ContextFragments.TaskFragment parsedOutput) {
        this(
                newContextId(),
                contextManager,
                fragments,
                taskHistory,
                parsedOutput,
                null,
                null,
                null,
                Set.of(),
                Set.of());
    }

    public Map<ProjectFile, String> buildRelatedIdentifiers(int k) throws InterruptedException {
        var candidates = getMostRelevantFiles(k).stream().sorted().toList();
        IAnalyzer analyzer = contextManager.getAnalyzer();

        // TODO: Get this off common FJP
        return candidates.parallelStream()
                .map(pf -> Map.entry(pf, buildRelatedIdentifiers(analyzer, pf)))
                .filter(e -> !e.getValue().isBlank())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    }

    public static String buildRelatedIdentifiers(IAnalyzer analyzer, ProjectFile file) {
        return buildRelatedIdentifiers(analyzer, analyzer.getTopLevelDeclarations(file), 0);
    }

    private static String buildRelatedIdentifiers(IAnalyzer analyzer, List<CodeUnit> units, int indent) {
        var prefix = "  ".repeat(Math.max(0, indent));
        var sb = new StringBuilder();
        for (var cu : units) {
            // Skip anonymous/lambda artifacts
            if (cu.isAnonymous()) {
                continue;
            }

            // Use FQN for top-level entries, simple identifier for nested entries
            String name = indent == 0 ? cu.fqName() : cu.identifier();
            sb.append(prefix).append("- ").append(name);

            var children = analyzer.getDirectChildren(cu);
            if (!children.isEmpty()) {
                sb.append("\n");
                sb.append(buildRelatedIdentifiers(analyzer, children, indent + 1));
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
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

        // 1. Deduplicate the input 'toAdd' collection internally first.
        var uniqueInputs = new ArrayList<ContextFragment>();
        for (var f : toAdd) {
            if (uniqueInputs.stream().noneMatch(existing -> existing.hasSameSource(f))) {
                uniqueInputs.add(f);
            }
        }

        // 2. Identify files that are being added as full PATH fragments.
        // These will "kill" any existing SKELETON fragments for the same files.
        var incomingPathFiles = uniqueInputs.stream()
                .filter(f -> f instanceof ContextFragments.PathFragment)
                .map(f -> (ContextFragments.PathFragment) f)
                .flatMap(pf -> pf.files().join().stream())
                .collect(Collectors.toSet());

        // 3. Process the CURRENT fragments:
        //    a) Remove SUMMARY fragments if they are superseded by incoming PATHS.
        //    b) Keep everything else (we will deduplicate against new inputs in the next step).
        var keptExistingFragments = this.fragments.stream()
                .filter(f -> {
                    if (f instanceof ContextFragments.SummaryFragment) {
                        var skeletonFiles = f.files().join();
                        // If the skeleton's files overlap with incoming full paths, drop the skeleton.
                        return Collections.disjoint(skeletonFiles, incomingPathFiles);
                    }
                    return true;
                })
                .collect(Collectors.toCollection(ArrayList::new));

        // 4. Calculate the ACTUAL new items to add.
        //    We filter 'uniqueInputs' to ensure we don't add something that already exists
        //    in the (cleaned) existing list.
        var fragmentsToAdd = uniqueInputs.stream()
                .filter(input -> keptExistingFragments.stream().noneMatch(existing -> existing.hasSameSource(input)))
                .toList();

        if (fragmentsToAdd.isEmpty()) {
            return this;
        }

        // 5. Merge
        keptExistingFragments.addAll(fragmentsToAdd);

        return this.withFragments(keptExistingFragments);
    }

    public boolean containsWithSameSource(ContextFragment fragment) {
        return fragments.stream().anyMatch(f -> f.hasSameSource(fragment));
    }

    public Context addFragments(ContextFragment fragment) {
        return addFragments(List.of(fragment));
    }

    private Context withFragments(List<ContextFragment> newFragments) {
        // By default, derived contexts should NOT inherit grouping or overrides
        return new Context(
                newContextId(),
                contextManager,
                newFragments,
                taskHistory,
                null,
                null,
                null,
                null,
                this.markedReadonlyFragments,
                this.pinnedFragments);
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

    @Nullable
    public UUID getGroupId() {
        return groupId;
    }

    @Nullable
    public String getGroupLabel() {
        return groupLabel;
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

    public Stream<ContextFragment> fileFragments() {
        return fragments.stream().filter(f -> f.getType().isPath());
    }

    public Stream<ContextFragment> virtualFragments() {
        // Virtual fragments are non-path fragments
        return fragments.stream().filter(f -> !f.getType().isPath());
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

        return new Context(
                newContextId(),
                contextManager,
                newFragments,
                taskHistory,
                null,
                null,
                null,
                null,
                newReadOnly,
                newPinned);
    }

    public Context removeAll() {
        return new Context(
                newContextId(),
                contextManager,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                Set.of(),
                Set.of());
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
                newContextId(),
                contextManager,
                fragments,
                taskHistory,
                parsedOutput,
                null,
                null,
                null,
                this.markedReadonlyFragments,
                newPinned);
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

        return new Context(
                newContextId(),
                contextManager,
                fragments,
                taskHistory,
                null,
                null,
                null,
                null,
                newReadOnly,
                this.pinnedFragments);
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

    public TaskEntry createTaskEntry(TaskResult result) {
        int nextSequence = taskHistory.isEmpty() ? 1 : taskHistory.getLast().sequence() + 1;
        return TaskEntry.fromSession(nextSequence, result);
    }

    public Context addHistoryEntry(
            TaskEntry taskEntry, @Nullable ContextFragments.TaskFragment parsed) {
        var newTaskHistory =
                Streams.concat(taskHistory.stream(), Stream.of(taskEntry)).toList();
        // Do not inherit grouping on derived contexts; grouping is explicit
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                newTaskHistory,
                parsed,
                null,
                null,
                null,
                this.markedReadonlyFragments,
                this.pinnedFragments);
    }


    public Context clearHistory() {
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                List.of(),
                null,
                null,
                null,
                null,
                this.markedReadonlyFragments,
                this.pinnedFragments);
    }

    /**
     * @return an immutable copy of the task history.
     */
    public List<TaskEntry> getTaskHistory() {
        return taskHistory;
    }

    /**
     * Generates a descriptive string for the action that produced this context by comparing it to
     * a previous state.
     *
     * @param previous the baseline context to compare against
     * @return a human-readable description of the change
     */
    @Blocking
    public String getDescription(@Nullable Context previous) {
        if (descriptionOverride != null) {
            return descriptionOverride;
        }

        if (previous == null) {
            return WELCOME_ACTION;
        }

        return ContextDelta.between(previous, this).description();
    }

    public IContextManager getContextManager() {
        return contextManager;
    }

    /**
     * Returns all fragments in display order:
     * 1. Conversation history (if not empty)
     * 2. Task List (if present) — special pinned position for task management
     * 3. File/path fragments
     * 4. Other virtual fragments (excluding Task List to avoid duplication)
     */
    public List<ContextFragment> getAllFragmentsInDisplayOrder() {
        var result = new ArrayList<ContextFragment>();

        if (!taskHistory.isEmpty()) {
            result.add(new HistoryFragment(contextManager, taskHistory));
        }

        // Add Task List immediately after history if present
        var taskListFragment = getTaskListFragment();
        if (taskListFragment.isPresent()) {
            result.add(taskListFragment.get());
        }

        result.addAll(fragments.stream().filter(f -> f.getType().isPath()).toList());

        // Add virtual fragments, excluding the Task List to avoid duplication
        result.addAll(fragments.stream()
                .filter(f -> !f.getType().isPath())
                .filter(f -> taskListFragment.isEmpty()
                        || !f.id().equals(taskListFragment.get().id()))
                .toList());

        return result;
    }

    public Context withParsedOutput(@Nullable ContextFragments.TaskFragment parsedOutput) {
        // Clear grouping by default on derived contexts
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                taskHistory,
                parsedOutput,
                null,
                null,
                null,
                this.markedReadonlyFragments,
                this.pinnedFragments);
    }


    public Context withGroup(@Nullable UUID groupId, @Nullable String groupLabel) {
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                taskHistory,
                parsedOutput,
                groupId,
                groupLabel,
                null,
                this.markedReadonlyFragments,
                this.pinnedFragments);
    }

    /**
     * Creates a Context with explicit control over the read-only and pinned fragment tracking.
     * Prefer this when deriving a new Context from an existing one to preserve tracking state.
     */
    public static Context createWithId(
            UUID id,
            IContextManager cm,
            List<ContextFragment> fragments,
            List<TaskEntry> history,
            @Nullable ContextFragments.TaskFragment parsed,
            @Nullable UUID groupId,
            @Nullable String groupLabel,
            Set<ContextFragment> readOnlyFragments,
            Set<ContextFragment> pinnedFragments) {
        return new Context(
                id, cm, fragments, history, parsed, groupId, groupLabel, null, readOnlyFragments, pinnedFragments);
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
            @Nullable ContextFragments.TaskFragment parsed,
            @Nullable UUID groupId,
            @Nullable String groupLabel,
            @Nullable String descriptionOverride,
            Set<ContextFragment> readOnlyFragments,
            Set<ContextFragment> pinnedFragments) {
        return new Context(
                id, cm, fragments, history, parsed, groupId, groupLabel, descriptionOverride, readOnlyFragments, pinnedFragments);
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
                null,
                null,
                null,
                null,
                this.markedReadonlyFragments,
                this.pinnedFragments);
    }

    @Nullable
    public ContextFragments.TaskFragment getParsedOutput() {
        return parsedOutput;
    }

    /**
     * Returns true if the parsedOutput contains AI messages (useful for UI decisions).
     */
    public boolean isAiResult() {
        var parsed = getParsedOutput();
        if (parsed == null) {
            return false;
        }
        return parsed.messages().stream().anyMatch(m -> m.type() == ChatMessageType.AI);
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
                null,
                sourceContext.getGroupId(),
                sourceContext.getGroupLabel(),
                null,
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

    /** Sets a description override for this context (e.g., "Load external changes"). */
    public Context withDescription(String description) {
        return new Context(
                id,
                contextManager,
                fragments,
                taskHistory,
                parsedOutput,
                groupId,
                groupLabel,
                description,
                markedReadonlyFragments,
                pinnedFragments);
    }

    /**
     * Retrieves the Discarded Context special fragment and returns a Map of description -> explanation.
     * Parses JSON directly; returns an empty map if absent or parse fails.
     */
    @Blocking
    public Map<String, String> getDiscardedFragmentsNote() {
        return getSpecial(SpecialTextType.DISCARDED_CONTEXT.description())
                .map(sf -> {
                    try {
                        Map<String, Object> raw = Json.fromJson(sf.text().join(), new TypeReference<>() {});
                        return raw.entrySet().stream()
                                .collect(Collectors.toMap(
                                        Map.Entry::getKey,
                                        e -> Objects.toString(e.getValue(), ""),
                                        (a, b) -> a,
                                        LinkedHashMap::new));
                    } catch (Exception e) {
                        logger.warn("Failed to parse Discarded Context JSON", e);
                        return new LinkedHashMap<String, String>();
                    }
                })
                .orElseGet(LinkedHashMap::new);
    }

    // --- SpecialTextType helpers ---

    public Optional<ContextFragments.StringFragment> getSpecial(String description) {
        // Since special looks for self-freezing fragments, we can reliably use `renderNow`
        return virtualFragments()
                .filter(f -> f instanceof ContextFragments.AbstractStaticFragment sf
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

        var idsToDrop = virtualFragments()
                .filter(f -> f instanceof ContextFragments.AbstractStaticFragment sf
                        && desc.equals(sf.description().renderNowOrNull()))
                .map(ContextFragment::id)
                .toList();

        var afterClear = idsToDrop.isEmpty() ? this : removeFragmentsByIds(idsToDrop);

        var sf = new ContextFragments.StringFragment(getContextManager(), content, desc, type.syntaxStyle());

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
                afterClear.parsedOutput,
                null,
                null,
                null,
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
     * Merges this context with another context, combining their fragments while avoiding duplicates.
     * Fragments from {@code other} that are not present in this context are added.
     * File fragments are deduplicated by their source file; virtual fragments by their id().
     * Task history and parsed output from this context are preserved.
     *
     * @param other the context to merge with
     * @return a new context containing the union of fragments from both contexts
     */
    public Context union(Context other) {
        if (this.fragments.isEmpty()) {
            return other;
        }

        return this.addFragments(other.allFragments().toList());
    }

    /**
     * Adds class definitions (CodeFragments) to the context for the given FQCNs.
     * Skips classes whose source files are already in the workspace as ProjectPathFragments.
     *
     * @param context    the current context
     * @param classNames fully qualified class names to add
     * @param analyzer   the code analyzer
     * @return a new context with the added class fragments
     */
    public static Context withAddedClasses(Context context, List<String> classNames, IAnalyzer analyzer) {
        if (classNames.isEmpty()) {
            return context;
        }

        var liveContext = context;
        var workspaceFiles = liveContext
                .fileFragments()
                .filter(f -> f instanceof ContextFragments.ProjectPathFragment)
                .map(f -> (ContextFragments.ProjectPathFragment) f)
                .map(ContextFragments.ProjectPathFragment::file)
                .collect(Collectors.toSet());

        var toAdd = new ArrayList<ContextFragment>();
        for (String className : classNames.stream().distinct().toList()) {
            if (className.isBlank()) {
                continue;
            }
            var cuOpt = analyzer.getDefinitions(className).stream()
                    .filter(CodeUnit::isClass)
                    .findFirst();
            if (cuOpt.isPresent()) {
                var codeUnit = cuOpt.get();
                // Skip if the source file is already in workspace as a ProjectPathFragment
                if (!workspaceFiles.contains(codeUnit.source())) {
                    toAdd.add(new ContextFragments.CodeFragment(context.contextManager, codeUnit));
                }
            } else {
                logger.warn("Could not find definition for class: {}", className);
            }
        }

        return toAdd.isEmpty() ? context : liveContext.addFragments(toAdd);
    }

    /**
     * Adds class summary fragments (SkeletonFragments) for the given FQCNs.
     *
     * @param context    the current context
     * @param classNames fully qualified class names to summarize
     * @return a new context with the added summary fragments
     */
    public static Context withAddedClassSummaries(Context context, List<String> classNames) {
        if (classNames.isEmpty()) {
            return context;
        }

        var toAdd = new ArrayList<ContextFragment>();
        for (String name : classNames.stream().distinct().toList()) {
            if (name.isBlank()) {
                continue;
            }
            toAdd.add(new ContextFragments.SummaryFragment(
                    context.contextManager, name, ContextFragment.SummaryType.CODEUNIT_SKELETON));
        }

        return toAdd.isEmpty() ? context : context.addFragments(toAdd);
    }

    /**
     * Adds file summary fragments for all classes in the given file paths (with glob support).
     *
     * @param context   the current context
     * @param filePaths file paths relative to project root; supports glob patterns
     * @param project   the project for path resolution
     * @return a new context with the added file summary fragments
     */
    public static Context withAddedFileSummaries(Context context, List<String> filePaths, AbstractProject project) {
        if (filePaths.isEmpty()) {
            return context;
        }

        var resolvedFilePaths = filePaths.stream()
                .flatMap(pattern -> Completions.expandPath(project, pattern).stream())
                .filter(ProjectFile.class::isInstance)
                .map(ProjectFile.class::cast)
                .map(ProjectFile::toString)
                .distinct()
                .toList();

        if (resolvedFilePaths.isEmpty()) {
            return context;
        }

        var toAdd = new ArrayList<ContextFragment>();
        for (String path : resolvedFilePaths) {
            toAdd.add(new ContextFragments.SummaryFragment(
                    context.contextManager, path, ContextFragment.SummaryType.FILE_SKELETONS));
        }

        return context.addFragments(toAdd);
    }

    /**
     * Adds method source code fragments for the given FQ method names.
     * Skips methods whose source files are already in the workspace.
     *
     * @param context     the current context
     * @param methodNames fully qualified method names to add sources for
     * @param analyzer    the code analyzer
     * @return a new context with the added method fragments
     */
    public static Context withAddedMethodSources(Context context, List<String> methodNames, IAnalyzer analyzer) {
        if (methodNames.isEmpty()) {
            return context;
        }

        var workspaceFiles = context.fileFragments()
                .filter(f -> f instanceof ContextFragments.ProjectPathFragment)
                .map(f -> (ContextFragments.ProjectPathFragment) f)
                .map(ContextFragments.ProjectPathFragment::file)
                .collect(Collectors.toSet());

        var toAdd = new ArrayList<ContextFragment>();
        for (String methodName : methodNames.stream().distinct().toList()) {
            if (methodName.isBlank()) {
                continue;
            }
            var cuOpt = analyzer.getDefinitions(methodName).stream()
                    .filter(CodeUnit::isFunction)
                    .findFirst();
            if (cuOpt.isPresent()) {
                var codeUnit = cuOpt.get();
                // Skip if the source file is already in workspace as a ProjectPathFragment
                if (!workspaceFiles.contains(codeUnit.source())) {
                    toAdd.add(new ContextFragments.CodeFragment(context.contextManager, codeUnit));
                }
            } else {
                logger.warn("Could not find method definition for: {}", methodName);
            }
        }

        return toAdd.isEmpty() ? context : context.addFragments(toAdd);
    }

    /**
     * Adds a URL content fragment to the context by fetching and converting to Markdown.
     *
     * @param context   the current context
     * @param urlString the URL to fetch
     * @return a new context with the added URL fragment
     * @throws IOException        if fetching or processing fails
     * @throws URISyntaxException if the URL string is malformed
     */
    public static Context withAddedUrlContent(Context context, String urlString)
            throws IOException, URISyntaxException {
        if (urlString.isBlank()) {
            return context;
        }

        var content = WorkspaceTools.fetchUrlContent(new URI(urlString));
        content = HtmlToMarkdown.maybeConvertToMarkdown(content);

        if (content.isBlank()) {
            return context;
        }

        var fragment = new ContextFragments.StringFragment(
                context.contextManager, content, "Content from " + urlString, SyntaxConstants.SYNTAX_STYLE_NONE);
        return context.addFragments(fragment);
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

        return withSpecial(
                SpecialTextType.BUILD_RESULTS,
                processedOutput);
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
            return new TaskList.TaskListData(List.of());
        }
        try {
            var fragment = existing.get();
            var textOpt = fragment.text().tryGet();
            return textOpt.map(s -> Json.fromJson(s, TaskList.TaskListData.class))
                    .orElseGet(() -> {
                        logger.warn("Failed to load Task List JSON in time for {}", fragment);
                        return new TaskList.TaskListData(List.of());
                    });
        } catch (Exception e) {
            logger.warn("Failed to parse Task List JSON", e);
            return new TaskList.TaskListData(List.of());
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
    /**
     * Refreshes fragments whose source files intersect the provided set, and sets an action description.
     */
    @Blocking
    public Context copyAndRefresh(Set<ProjectFile> maybeChanged, String description) {
        return copyAndRefresh(maybeChanged).withDescription(description);
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
                if (refreshed != f) {
                    // Check if content actually differs using DiffService
                    var diffFuture = DiffService.computeDiff(f, refreshed);
                    var diffEntry = diffFuture.join();
                    if (diffEntry != null) {
                        // Content actually changed; mark as replaced
                        anyReplaced = true;
                        replacementMap.put(f, refreshed);
                        fragmentToAdd = refreshed;
                    }
                }
            }

            newFragments.add(fragmentToAdd);
        }

        // Create a new Context only if any fragment actually changed, or parsed output is present.
        boolean mustCreateNew = anyReplaced || parsedOutput != null;

        if (!mustCreateNew && newFragments.equals(fragments)) {
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

        return new Context(
                newContextId(),
                contextManager,
                newFragments,
                taskHistory,
                parsedOutput,
                this.groupId,
                this.groupLabel,
                this.descriptionOverride,
                newReadOnly,
                newPinned);
    }

    /**
     * Best-effort snapshot seeding to ensure context contents are materialized.
     * Blocks for a total duration of the timeout parameter across all fragments,
     * not timeout-per-fragment.
     */
    @Blocking
    public void awaitContextsAreComputed(Duration timeout) throws InterruptedException {
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

    private static Set<ContextFragment> validateReadOnlyFragments(
            Set<ContextFragment> readonly, List<ContextFragment> all) {
        for (var cf : readonly) {
            assert all.contains(cf);
        }

        return Set.copyOf(readonly);
    }

    private static Set<ContextFragment> validatePinnedFragments(
            Set<ContextFragment> pinned, List<ContextFragment> all) {
        for (var cf : pinned) {
            assert all.contains(cf);
        }

        return Set.copyOf(pinned);
    }
}
