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
import ai.brokk.gui.ActivityTableRenderers;
import ai.brokk.project.AbstractProject;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.UnaryOperator;
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
    public static final String SUMMARIZING = "(Summarizing)";
    public static final long CONTEXT_ACTION_SUMMARY_TIMEOUT_SECONDS = 5;

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

    /**
     * description of the action that created this context, can be a future (like PasteFragment)
     */
    public final transient Future<String> action;

    @Nullable
    private final UUID groupId;

    @Nullable
    private final String groupLabel;

    private final Set<ContextFragment> markedReadonlyFragments;

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
                CompletableFuture.completedFuture(WELCOME_ACTION),
                null,
                null,
                Set.of());
    }

    private Context(
            UUID id,
            IContextManager contextManager,
            List<ContextFragment> fragments,
            List<TaskEntry> taskHistory,
            @Nullable ContextFragments.TaskFragment parsedOutput,
            Future<String> action,
            @Nullable UUID groupId,
            @Nullable String groupLabel,
            Set<ContextFragment> markedReadonlyFragments) {
        this.id = id;
        this.contextManager = contextManager;
        this.fragments = List.copyOf(fragments);
        this.taskHistory = List.copyOf(taskHistory);
        this.action = action;
        this.parsedOutput = parsedOutput;
        this.groupId = groupId;
        this.groupLabel = groupLabel;
        this.markedReadonlyFragments = validateReadOnlyFragments(markedReadonlyFragments, fragments);
    }

    public Context(
            IContextManager contextManager,
            List<ContextFragment> fragments,
            List<TaskEntry> taskHistory,
            @Nullable ContextFragments.TaskFragment parsedOutput,
            Future<String> action) {
        this(newContextId(), contextManager, fragments, taskHistory, parsedOutput, action, null, null, Set.of());
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

    /**
     * Per-fragment diff entry between two contexts.
     */
    public record DiffEntry(
            ContextFragment fragment,
            String diff,
            int linesAdded,
            int linesDeleted,
            String oldContent,
            String newContent) {
        @Blocking
        public String title() {
            var files = fragment.files().join();
            if (files != null && !files.isEmpty()) {
                var pf = files.iterator().next();
                return pf.getRelPath().toString();
            }
            return fragment.shortDescription().join();
        }
    }

    public static UUID newContextId() {
        return UuidCreator.getTimeOrderedEpoch();
    }

    public String getEditableToc() {
        return getEditableFragments().map(ContextFragment::formatToc).collect(Collectors.joining("\n"));
    }

    public String getReadOnlyToc() {
        return getReadonlyFragments().map(ContextFragment::formatToc).collect(Collectors.joining("\n"));
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

        // 5. Merge and Build unified action message
        keptExistingFragments.addAll(fragmentsToAdd);
        String action = buildAddFragmentsAction(fragmentsToAdd);

        return this.withFragments(keptExistingFragments, CompletableFuture.completedFuture(action));
    }

    /**
     * Builds a concise action message for added fragments.
     * Shows first few fragment descriptions (up to 2) and indicates if there are more.
     */
    private String buildAddFragmentsAction(List<ContextFragment> added) {
        int count = added.size();
        if (count == 1) {
            var shortDesc = added.getFirst().shortDescription().join();
            return "Added " + shortDesc;
        }

        // Show up to 2 fragments, then indicate count
        var descriptions =
                added.stream().limit(2).map(f -> f.shortDescription().join()).toList();

        var message = "Added " + String.join(", ", descriptions);
        if (count > 2) {
            message += ", " + (count - 2) + " more";
        }
        return message;
    }

    public Context addFragments(ContextFragment fragment) {
        return addFragments(List.of(fragment));
    }

    private Context withFragments(List<ContextFragment> newFragments, Future<String> action) {
        // By default, derived contexts should NOT inherit grouping; grouping is explicit via withGroup(...)
        return new Context(
                newContextId(),
                contextManager,
                newFragments,
                taskHistory,
                null,
                action,
                null,
                null,
                this.markedReadonlyFragments);
    }

    /**
     * Returns the files from the git repo that are most relevant to this context, up to the specified limit.
     */
    @Blocking
    public List<ProjectFile> getMostRelevantFiles(int topK) throws InterruptedException {
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

        var gitDistanceResults =
                GitDistance.getRelatedFiles((GitRepo) contextManager.getRepo(), weightedSeeds, topK, false);
        return gitDistanceResults.stream()
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

    /**
     * Anything that's not editable is implicitly read-only
     */
    public Stream<ContextFragment> getReadonlyFragments() {
        var editable = getEditableFragments().collect(Collectors.toSet());
        return fragments.stream().filter(cf -> !editable.contains(cf));
    }

    /**
     * Returns file fragments and editable virtual fragments (usage), ordered with most-recently-modified last
     */
    public Stream<ContextFragment> getEditableFragments() {
        // Helper record for associating a fragment with its mtime for safe sorting and filtering
        record EditableFileWithMtime(ContextFragments.ProjectPathFragment fragment, long mtime) {}

        Stream<ContextFragments.ProjectPathFragment> sortedProjectFiles = fragments.stream()
                .filter(ContextFragments.ProjectPathFragment.class::isInstance)
                .map(ContextFragments.ProjectPathFragment.class::cast)
                .map(pf -> {
                    // exists() and mtime() are both a syscall, so we just call the latter and catch errors
                    long mtime;
                    try {
                        mtime = pf.file().mtime();
                    } catch (IOException e) {
                        // this is expected to happen when deserializing old Sessions so we leave it at debug
                        logger.debug(
                                "Could not get mtime for editable file [{}], it will be excluded from ordered editable fragments.",
                                pf.shortDescription().renderNowOr(toString()),
                                e);
                        // sort does-not-exist to the end of the list (it may be more likely to be edited)
                        return new EditableFileWithMtime(pf, Long.MAX_VALUE);
                    }
                    return new EditableFileWithMtime(pf, mtime);
                })
                .sorted(Comparator.comparingLong(EditableFileWithMtime::mtime))
                .map(EditableFileWithMtime::fragment);

        Stream<ContextFragment> otherEditable = fragments.stream()
                .filter(f -> !(f instanceof ContextFragments.ProjectPathFragment)
                        && f.getType().isEditable());

        return Streams.concat(otherEditable, sortedProjectFiles.map(ContextFragment.class::cast))
                .filter(cf -> !markedReadonlyFragments.contains(cf));
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
        var newFragments =
                fragments.stream().filter(f -> !toRemoveSet.contains(f)).toList();

        int removedCount = fragments.size() - newFragments.size();
        if (removedCount == 0) {
            return this;
        }

        // Remove any read-only tracking for dropped fragments
        var newReadOnly = this.markedReadonlyFragments.stream()
                .filter(f -> !toRemoveSet.contains(f))
                .collect(Collectors.toSet());

        String actionString = "Removed " + removedCount + " fragment" + (removedCount == 1 ? "" : "s");
        return new Context(
                newContextId(),
                contextManager,
                newFragments,
                taskHistory,
                null,
                CompletableFuture.completedFuture(actionString),
                null,
                null,
                newReadOnly);
    }

    public Context removeAll() {
        String action = ActivityTableRenderers.DROPPED_ALL_CONTEXT;
        return new Context(
                newContextId(),
                contextManager,
                List.of(),
                List.of(),
                null,
                CompletableFuture.completedFuture(action),
                null,
                null,
                Set.of());
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

        // Build action message (non-blocking where possible)
        Future<String> actionFuture;
        String actionPrefix = readonly ? "Set Read-Only: " : "Unset Read-Only: ";

        var cv = fragment.description();
        var actionCf = new CompletableFuture<String>();
        cv.onComplete((label, ex) -> {
            if (ex != null) {
                logger.error("Exception occurred while computing fragment description!");
            } else {
                actionCf.complete(actionPrefix + label);
            }
        });
        actionFuture = actionCf;

        return new Context(
                newContextId(), contextManager, fragments, taskHistory, null, actionFuture, null, null, newReadOnly);
    }

    public boolean isEmpty() {
        return fragments.isEmpty() && taskHistory.isEmpty();
    }

    /**
     * Returns true if the workspace contains no file content.
     * Fragments may exist (STRING, TASK, HISTORY, etc.) but no actual file-based fragments
     * (PROJECT_PATH, GIT_FILE, EXTERNAL_PATH, IMAGE_FILE, etc.).
     *
     * Uses a conservative approach: treats file-based fragment types (SKELETON, CODE, etc.)
     * as potentially having content even if their computed files set is currently empty or uncomputed.
     */
    public boolean isFileContentEmpty() {
        return fragments.stream().allMatch(f -> {
            // Fragment types that inherently represent file-based content should be considered as having files
            var type = f.getType();
            if (type.isFileContent()) {
                return false; // These types represent file content
            }

            // For other types, check the computed files set
            var filesOpt = f.files().tryGet();
            // If not yet computed or empty, treat as having no file content
            // (non-file-content types like STRING, TASK, HISTORY rarely have file refs;
            // erring on the side of "empty" allows useful context scanning to proceed)
            if (filesOpt.isEmpty()) {
                return true; // Treat as empty - allow scanning when unsure
            }
            // If computed, check if the set is empty
            return filesOpt.get().isEmpty();
        });
    }

    public TaskEntry createTaskEntry(TaskResult result) {
        int nextSequence = taskHistory.isEmpty() ? 1 : taskHistory.getLast().sequence() + 1;
        return TaskEntry.fromSession(nextSequence, result);
    }

    public Context addHistoryEntry(
            TaskEntry taskEntry, @Nullable ContextFragments.TaskFragment parsed, Future<String> action) {
        var newTaskHistory =
                Streams.concat(taskHistory.stream(), Stream.of(taskEntry)).toList();
        // Do not inherit grouping on derived contexts; grouping is explicit
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                newTaskHistory,
                parsed,
                action,
                null,
                null,
                this.markedReadonlyFragments);
    }

    public Context clearHistory() {
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                List.of(),
                null,
                CompletableFuture.completedFuture(ActivityTableRenderers.CLEARED_TASK_HISTORY),
                null,
                null,
                this.markedReadonlyFragments);
    }

    /**
     * @return an immutable copy of the task history.
     */
    public List<TaskEntry> getTaskHistory() {
        return taskHistory;
    }

    /**
     * Get the action that created this context
     */
    public String getAction() {
        if (action.isDone()) {
            try {
                return action.get();
            } catch (Exception e) {
                logger.warn("Error retrieving action", e);
                return "(Error retrieving action)";
            }
        }
        return SUMMARIZING;
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

    public Context withParsedOutput(@Nullable ContextFragments.TaskFragment parsedOutput, Future<String> action) {
        // Clear grouping by default on derived contexts
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                taskHistory,
                parsedOutput,
                action,
                null,
                null,
                this.markedReadonlyFragments);
    }

    public Context withParsedOutput(@Nullable ContextFragments.TaskFragment parsedOutput, String action) {
        // Clear grouping by default on derived contexts
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                taskHistory,
                parsedOutput,
                CompletableFuture.completedFuture(action),
                null,
                null,
                this.markedReadonlyFragments);
    }

    public Context withAction(Future<String> action) {
        // Clear grouping by default on derived contexts
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                taskHistory,
                parsedOutput,
                action,
                null,
                null,
                this.markedReadonlyFragments);
    }

    public Context withGroup(@Nullable UUID groupId, @Nullable String groupLabel) {
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                taskHistory,
                parsedOutput,
                action,
                groupId,
                groupLabel,
                this.markedReadonlyFragments);
    }

    public static Context createWithId(
            UUID id,
            IContextManager cm,
            List<ContextFragment> fragments,
            List<TaskEntry> history,
            @Nullable ContextFragments.TaskFragment parsed,
            Future<String> action) {
        return createWithId(id, cm, fragments, history, parsed, action, null, null, Set.of());
    }

    /**
     * Creates a Context with explicit control over the read-only fragment IDs to persist.
     * Prefer this when deriving a new Context from an existing one to preserve read-only tracking.
     */
    public static Context createWithId(
            UUID id,
            IContextManager cm,
            List<ContextFragment> fragments,
            List<TaskEntry> history,
            @Nullable ContextFragments.TaskFragment parsed,
            Future<String> action,
            @Nullable UUID groupId,
            @Nullable String groupLabel,
            Set<ContextFragment> readOnlyFragments) {
        return new Context(id, cm, fragments, history, parsed, action, groupId, groupLabel, readOnlyFragments);
    }

    /**
     * Creates a new Context with a modified task history list. This generates a new context state with a new ID and
     * action.
     */
    public Context withHistory(List<TaskEntry> newHistory) {
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                newHistory,
                null,
                CompletableFuture.completedFuture("Compress History"),
                null,
                null,
                this.markedReadonlyFragments);
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
                CompletableFuture.completedFuture("Reset context to historical state"),
                sourceContext.getGroupId(),
                sourceContext.getGroupLabel(),
                sourceContext.markedReadonlyFragments);
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
    public Context putSpecial(SpecialTextType type, String content) {
        var desc = type.description();

        var idsToDrop = type.singleton()
                ? virtualFragments()
                        .filter(f -> f instanceof ContextFragments.AbstractStaticFragment sf
                                && desc.equals(sf.description().renderNowOrNull()))
                        .map(ContextFragment::id)
                        .toList()
                : List.<String>of();

        var afterClear = idsToDrop.isEmpty() ? this : removeFragmentsByIds(idsToDrop);

        var sf = new ContextFragments.StringFragment(getContextManager(), content, desc, type.syntaxStyle());

        var newFragments = new ArrayList<>(afterClear.fragments);
        newFragments.add(sf);

        // Preserve parsedOutput and action by default; callers can override action as needed.
        return new Context(
                newContextId(),
                getContextManager(),
                newFragments,
                afterClear.taskHistory,
                afterClear.parsedOutput,
                afterClear.action,
                null,
                null,
                afterClear.markedReadonlyFragments);
    }

    @Blocking
    public Context updateSpecial(SpecialTextType type, UnaryOperator<String> updater) {
        var current = getSpecial(type.description())
                .map(ContextFragments.AbstractStaticFragment::text)
                .map(cv -> cv.renderNowOr(""))
                .orElse("");
        var updated = updater.apply(current);
        return putSpecial(type, updated);
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
     * - On success: remove existing BUILD_RESULTS StringFragment if present; no fragment otherwise.
     * - On failure: upsert the BUILD_RESULTS StringFragment with the processed output.
     */
    @Blocking
    public Context withBuildResult(boolean success, String processedOutput) {
        var desc = SpecialTextType.BUILD_RESULTS.description();

        var fragmentsToDrop = virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.BUILD_LOG
                        || (f.getType() == ContextFragment.FragmentType.STRING
                                && f instanceof ContextFragments.AbstractStaticFragment sf
                                && desc.equals(sf.description().renderNowOrNull())))
                .toList();

        var afterClear = fragmentsToDrop.isEmpty() ? this : removeFragments(fragmentsToDrop);
        if (success) {
            var existing = afterClear.getSpecial(SpecialTextType.BUILD_RESULTS.description());
            if (existing.isEmpty()) {
                return afterClear.withAction(CompletableFuture.completedFuture("Build results cleared (success)"));
            }
            return afterClear
                    .removeFragmentsByIds(List.of(existing.get().id()))
                    .withAction(CompletableFuture.completedFuture("Build results cleared (success)"));
        }
        return afterClear
                .putSpecial(SpecialTextType.BUILD_RESULTS, processedOutput)
                .withAction(CompletableFuture.completedFuture("Build results updated (failure)"));
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
    private Context withTaskList(String json, String action) {
        var next = putSpecial(SpecialTextType.TASK_LIST, json);
        return next.withParsedOutput(null, CompletableFuture.completedFuture(action));
    }

    /**
     * Serializes and updates the Task List fragment using TaskList.TaskListData.
     * If the task list is empty, removes any existing Task List fragment instead of creating an empty one.
     */
    public Context withTaskList(TaskList.TaskListData data, String action) {
        // Guard: if data hasn't changed, return this context unchanged
        var currentData = getTaskListDataOrEmpty();
        if (currentData.equals(data)) {
            return this;
        }

        // If tasks are empty, remove the Task List fragment instead of creating an empty one
        if (data.tasks().isEmpty()) {
            var existing = getSpecial(SpecialTextType.TASK_LIST.description());
            if (existing.isEmpty()) {
                return this; // No change needed; no fragment to remove
            }
            return removeFragmentsByIds(List.of(existing.get().id()))
                    .withAction(CompletableFuture.completedFuture("Task list cleared"));
        }

        // Non-empty case: serialize and update normally
        String json = Json.toJson(data);
        return withTaskList(json, action);
    }

    /**
     * Refreshes all computed fragments in this context without filtering.
     * Equivalent to calling {@link #copyAndRefresh(Set, String)} with all fragments.
     *
     * @return a new context with refreshed fragments, or this context if no changes occurred
     */
    @Blocking
    public Context copyAndRefresh(String action) {
        return copyAndRefreshInternal(Set.copyOf(fragments), action);
    }

    /**
     * Refreshes fragments whose source files intersect the provided set.
     *
     * @param maybeChanged     set of project files that may have changed
     * @param action description string for Activity history
     * @return a new context with refreshed fragments, or this context if no changes occurred
     */
    @Blocking
    public Context copyAndRefresh(Set<ProjectFile> maybeChanged, String action) {
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

        return copyAndRefreshInternal(fragmentsToRefresh, action);
    }

    /**
     * Core refresh logic: refreshes the specified fragments and tracks content changes via diff.
     * Handles remapping read-only membership for replaced fragments.
     *
     * @param maybeChanged the set of fragments to potentially refresh
     * @param action the action description for this refresh operation
     * @return a new context with refreshed fragments, or this context if no changes occurred
     */
    @Blocking
    private Context copyAndRefreshInternal(Set<ContextFragment> maybeChanged, String action) {
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

        // Remap read-only membership to refreshed fragments to preserve state across replacement
        var newReadOnly = new HashSet<>(this.markedReadonlyFragments);
        for (var e : replacementMap.entrySet()) {
            var oldFrag = e.getKey();
            var newFrag = e.getValue();
            if (newReadOnly.remove(oldFrag)) {
                newReadOnly.add(newFrag);
            }
        }

        return new Context(
                newContextId(),
                contextManager,
                newFragments,
                taskHistory,
                parsedOutput,
                CompletableFuture.completedFuture(action),
                this.groupId,
                this.groupLabel,
                newReadOnly);
    }

    /**
     * Compute per-fragment diffs between this (right/new) and the other (left/old) context. Results are cached per other.id().
     * This method awaits all async computations (e.g., ComputedValue) before returning the final diff list.
     */
    public List<DiffEntry> getDiff(Context other) {
        return DiffService.computeDiff(this, other);
    }

    /**
     * Compute the set of ProjectFile objects that differ between this (new/right) context and {@code other} (old/left).
     * This is a convenience wrapper around {@link #getDiff(Context)} which returns per-fragment diffs.
     * <p>
     * Note: Both contexts should be frozen (no dynamic fragments) for reliable results.
     */
    public Set<ProjectFile> getChangedFiles(Context other) {
        return DiffService.getChangedFiles(this, other);
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
            if (fragment instanceof ContextFragments.AbstractComputedFragment cf) {
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
}
