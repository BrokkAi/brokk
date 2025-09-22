package io.github.jbellis.brokk.context;

import com.github.f4b6a3.uuid.UuidCreator;
import com.google.common.collect.Streams;
import io.github.jbellis.brokk.AnalyzerUtil;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.analyzer.SkeletonProvider;
import io.github.jbellis.brokk.context.ContextFragment.HistoryFragment;
import io.github.jbellis.brokk.context.ContextFragment.SkeletonFragment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/** Encapsulates all state that will be sent to the model (prompts, filename context, conversation history). */
public class Context {
    private static final Logger logger = LogManager.getLogger(Context.class);

    private final UUID id;
    public static final Context EMPTY = new Context(new IContextManager() {}, null);

    public static final int MAX_AUTO_CONTEXT_FILES = 100;
    private static final String WELCOME_ACTION = "Session Start";
    public static final String SUMMARIZING = "(Summarizing)";
    public static final long CONTEXT_ACTION_SUMMARY_TIMEOUT_SECONDS = 5;

    private final transient IContextManager contextManager;

    // Unified list for all fragments (paths and virtuals)
    final List<ContextFragment> fragments;
    // Legacy alias fields for compatibility with modules referencing these directly (e.g., ContextHistory)
    final List<ContextFragment> editableFiles;
    final List<ContextFragment> readonlyFiles;

    /** Task history list. Each entry represents a user request and the subsequent conversation */
    final List<TaskEntry> taskHistory;

    /** LLM output or other parsed content, with optional fragment. May be null */
    @Nullable
    final transient ContextFragment.TaskFragment parsedOutput;

    /** description of the action that created this context, can be a future (like PasteFragment) */
    public final transient Future<String> action;

    /** Constructor for initial empty context */
    public Context(IContextManager contextManager, @Nullable String initialOutputText) {
        this(
                newContextId(),
                contextManager,
                List.of(),
                List.of(),
                null,
                CompletableFuture.completedFuture(WELCOME_ACTION));
    }

    private Context(
            UUID id,
            IContextManager contextManager,
            List<ContextFragment> fragments,
            List<TaskEntry> taskHistory,
            @Nullable ContextFragment.TaskFragment parsedOutput,
            Future<String> action) {
        this.id = id;
        this.contextManager = contextManager;
        this.fragments = List.copyOf(fragments);
        this.editableFiles = this.fragments;
        this.readonlyFiles = List.of();
        this.taskHistory = List.copyOf(taskHistory);
        this.action = action;
        this.parsedOutput = parsedOutput;
    }

    public Context(
            IContextManager contextManager,
            List<ContextFragment> fragments,
            List<TaskEntry> taskHistory,
            @Nullable ContextFragment.TaskFragment parsedOutput,
            Future<String> action) {
        this(newContextId(), contextManager, fragments, taskHistory, parsedOutput, action);
    }

    /** Produces a live context whose fragments are un-frozen versions of those in {@code frozen}. */
    public static Context unfreeze(Context frozen) {
        var cm = frozen.getContextManager();

        var newFragments = new ArrayList<ContextFragment>();

        frozen.allFragments().forEach(f -> {
            if (f instanceof FrozenFragment ff) {
                try {
                    newFragments.add(ff.unfreeze(cm));
                } catch (IOException e) {
                    logger.warn("Unable to unfreeze fragment {}: {}", ff.description(), e.getMessage());
                    newFragments.add(ff); // fall back to frozen
                }
            } else {
                newFragments.add(f); // Already live or non-dynamic
            }
        });

        return new Context(
                frozen.id(),
                cm,
                List.copyOf(newFragments),
                frozen.getTaskHistory(),
                frozen.getParsedOutput(),
                frozen.action);
    }

    public static UUID newContextId() {
        return UuidCreator.getTimeOrderedEpoch();
    }

    public String getEditableToc() {
        return getEditableFragments().map(ContextFragment::formatToc).collect(Collectors.joining(", "));
    }

    public String getReadOnlyToc() {
        return getReadOnlyFragments().map(ContextFragment::formatToc).collect(Collectors.joining(", "));
    }

    public Context addPathFragments(Collection<? extends ContextFragment.PathFragment> paths) {
        var toAdd = paths.stream().filter(p -> !fragments.contains(p)).toList();
        if (toAdd.isEmpty()) {
            return this;
        }
        var newFragments = new ArrayList<>(fragments);
        newFragments.addAll(toAdd);

        String actionDetails =
                toAdd.stream().map(ContextFragment::shortDescription).collect(Collectors.joining(", "));
        String action = "Edit " + actionDetails;
        return withFragments(newFragments, CompletableFuture.completedFuture(action));
    }

    public Context removePathFragments(List<? extends ContextFragment> toRemove, String actionPrefix) {
        var newFragments = new ArrayList<>(fragments);
        if (!newFragments.removeAll(toRemove)) {
            return this;
        }
        String actionDetails =
                toRemove.stream().map(ContextFragment::shortDescription).collect(Collectors.joining(", "));
        return withFragments(newFragments, CompletableFuture.completedFuture(actionPrefix + actionDetails));
    }

    public Context addVirtualFragment(ContextFragment.VirtualFragment fragment) {
        // Deduplicate among existing virtual fragments only
        boolean isDuplicate = fragment.getType() == ContextFragment.FragmentType.PASTE_IMAGE
                ? fragments.stream()
                        .filter(f -> f.getType().isVirtual())
                        .anyMatch(vf -> Objects.equals(vf.id(), fragment.id()))
                : fragments.stream()
                        .filter(f -> f.getType().isVirtual())
                        .map(f -> (ContextFragment.VirtualFragment) f)
                        .anyMatch(vf -> Objects.equals(vf.text(), fragment.text()));

        if (isDuplicate) {
            return this;
        }

        var newFragments = new ArrayList<>(fragments);
        newFragments.add(fragment);

        String action = "Added " + fragment.shortDescription();
        return withFragments(newFragments, CompletableFuture.completedFuture(action));
    }

    private Context withFragments(List<ContextFragment> newFragments, Future<String> action) {
        return new Context(newContextId(), contextManager, newFragments, taskHistory, null, action);
    }

    /**
     * 1) Gather all classes from each fragment. 2) Compute PageRank with those classes as seeds, requesting up to
     * 2*MAX_AUTO_CONTEXT_FILES 3) Return a SkeletonFragment constructed with the FQNs of the top results.
     */
    public SkeletonFragment buildAutoContext(int topK) throws InterruptedException {
        IAnalyzer analyzer = contextManager.getAnalyzer();

        // Collect ineligible sources from fragments not eligible for auto-context
        var ineligibleSources = fragments.stream()
                .filter(f -> !f.isEligibleForAutoContext())
                .flatMap(f -> f.files().stream())
                .collect(Collectors.toSet());

        // All file fragments have a weight of 1.0 each; virtuals share a weight of 1.0
        HashMap<ProjectFile, Double> weightedSeeds = new HashMap<>();
        var fileFragments = fragments.stream().filter(f -> f.getType().isPath()).toList();
        var virtuals = fragments.stream().filter(f -> f.getType().isVirtual()).toList();

        fileFragments.stream().flatMap(cf -> cf.files().stream()).forEach(f -> weightedSeeds.put(f, 1.0));
        int virtualCount = Math.max(1, virtuals.size());
        virtuals.stream()
                .flatMap(cf -> cf.files().stream())
                .forEach(f -> weightedSeeds.merge(f, 1.0 / virtualCount, Double::sum));

        if (weightedSeeds.isEmpty()) {
            return new SkeletonFragment(contextManager, List.of(), ContextFragment.SummaryType.CODEUNIT_SKELETON);
        }

        return buildAutoContextFragment(contextManager, analyzer, weightedSeeds, ineligibleSources, topK);
    }

    public static SkeletonFragment buildAutoContextFragment(
            IContextManager contextManager,
            IAnalyzer analyzer,
            Map<ProjectFile, Double> weightedSeeds,
            Set<ProjectFile> ineligibleSources,
            int topK) {
        var skp = analyzer.as(SkeletonProvider.class)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cannot find related classes: Code Intelligence is not available."));

        var pagerankResults = AnalyzerUtil.combinedRankingFor(contextManager.getProject(), weightedSeeds);

        List<String> targetFqns = new ArrayList<>();
        for (var sourceFile : pagerankResults) {
            boolean eligible = !ineligibleSources.contains(sourceFile);
            if (!eligible) continue;

            targetFqns.addAll(skp.getSkeletons(sourceFile).values());
            if (targetFqns.size() >= topK) break;
        }
        if (targetFqns.isEmpty()) {
            return new SkeletonFragment(contextManager, List.of(), ContextFragment.SummaryType.CODEUNIT_SKELETON);
        }
        return new SkeletonFragment(contextManager, targetFqns, ContextFragment.SummaryType.CODEUNIT_SKELETON);
    }

    // ---------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------

    public UUID id() {
        return id;
    }

    public Stream<ContextFragment> fileFragments() {
        return fragments.stream().filter(f -> f.getType().isPath());
    }

    public Stream<ContextFragment.VirtualFragment> virtualFragments() {
        return fragments.stream().filter(f -> f.getType().isVirtual()).map(f -> (ContextFragment.VirtualFragment) f);
    }

    /** Returns readonly files and virtual fragments (excluding usage fragments) as a combined stream */
    public Stream<ContextFragment> getReadOnlyFragments() {
        return fragments.stream().filter(f -> !f.getType().isEditable());
    }

    /** Returns file fragments and editable virtual fragments (usage), ordered with most-recently-modified last */
    public Stream<ContextFragment> getEditableFragments() {
        // Helper record for associating a fragment with its mtime for safe sorting and filtering
        record EditableFileWithMtime(ContextFragment.ProjectPathFragment fragment, long mtime) {}

        Stream<ContextFragment.ProjectPathFragment> sortedProjectFiles = fragments.stream()
                .filter(ContextFragment.ProjectPathFragment.class::isInstance)
                .map(ContextFragment.ProjectPathFragment.class::cast)
                .map(pf -> {
                    try {
                        return new EditableFileWithMtime(pf, pf.file().mtime());
                    } catch (IOException e) {
                        logger.warn(
                                "Could not get mtime for editable file [{}], it will be excluded from ordered editable fragments.",
                                pf.shortDescription(),
                                e);
                        return new EditableFileWithMtime(pf, -1L);
                    }
                })
                .filter(mf -> mf.mtime() >= 0)
                .sorted(Comparator.comparingLong(EditableFileWithMtime::mtime))
                .map(EditableFileWithMtime::fragment);

        Stream<ContextFragment> otherEditablePathFragments = fragments.stream()
                .filter(f -> f.getType().isPath() && !(f instanceof ContextFragment.ProjectPathFragment));

        Stream<ContextFragment> editableVirtuals = fragments.stream()
                .filter(f -> f.getType().isVirtual() && f.getType().isEditable())
                .map(f -> (ContextFragment) f);

        return Streams.concat(
                editableVirtuals, otherEditablePathFragments, sortedProjectFiles.map(ContextFragment.class::cast));
    }

    public Stream<ContextFragment> allFragments() {
        return fragments.stream();
    }

    /** Removes fragments from this context by their IDs. */
    public Context removeFragmentsByIds(Collection<String> idsToRemove) {
        if (idsToRemove.isEmpty()) {
            return this;
        }

        var newFragments =
                fragments.stream().filter(f -> !idsToRemove.contains(f.id())).toList();

        int removedCount = fragments.size() - newFragments.size();
        if (removedCount == 0) {
            return this;
        }

        String actionString = "Removed " + removedCount + " fragment" + (removedCount == 1 ? "" : "s");
        return withFragments(newFragments, CompletableFuture.completedFuture(actionString));
    }

    public Context removeAll() {
        String action = "Dropped all context";
        return new Context(contextManager, List.of(), List.of(), null, CompletableFuture.completedFuture(action));
    }

    public boolean isEmpty() {
        return fragments.isEmpty() && taskHistory.isEmpty();
    }

    public TaskEntry createTaskEntry(TaskResult result) {
        int nextSequence = taskHistory.isEmpty() ? 1 : taskHistory.getLast().sequence() + 1;
        return TaskEntry.fromSession(nextSequence, result);
    }

    public Context addHistoryEntry(
            TaskEntry taskEntry, @Nullable ContextFragment.TaskFragment parsed, Future<String> action) {
        var newTaskHistory =
                Streams.concat(taskHistory.stream(), Stream.of(taskEntry)).toList();
        return new Context(newContextId(), contextManager, fragments, newTaskHistory, parsed, action);
    }

    public Context clearHistory() {
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                List.of(),
                null,
                CompletableFuture.completedFuture("Cleared task history"));
    }

    /** @return an immutable copy of the task history. */
    public List<TaskEntry> getTaskHistory() {
        return taskHistory;
    }

    /** Get the action that created this context */
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
     * Returns all fragments in display order: - conversation history (if not empty) - file fragments - virtual
     * fragments
     */
    public List<ContextFragment> getAllFragmentsInDisplayOrder() {
        var result = new ArrayList<ContextFragment>();

        if (!taskHistory.isEmpty()) {
            result.add(new HistoryFragment(contextManager, taskHistory));
        }

        result.addAll(fragments.stream().filter(f -> f.getType().isPath()).toList());
        result.addAll(fragments.stream().filter(f -> f.getType().isVirtual()).toList());

        return result;
    }

    public Context withParsedOutput(@Nullable ContextFragment.TaskFragment parsedOutput, Future<String> action) {
        return new Context(newContextId(), contextManager, fragments, taskHistory, parsedOutput, action);
    }

    public Context withParsedOutput(@Nullable ContextFragment.TaskFragment parsedOutput, String action) {
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                taskHistory,
                parsedOutput,
                CompletableFuture.completedFuture(action));
    }

    public Context withAction(Future<String> action) {
        return new Context(newContextId(), contextManager, fragments, taskHistory, parsedOutput, action);
    }

    public static Context createWithId(
            UUID id,
            IContextManager cm,
            List<ContextFragment> editable,
            List<ContextFragment> readonly,
            List<ContextFragment.VirtualFragment> virtuals,
            List<TaskEntry> history,
            @Nullable ContextFragment.TaskFragment parsed,
            java.util.concurrent.Future<String> action) {
        var combined = Streams.concat(
                        Streams.concat(editable.stream(), readonly.stream()),
                        virtuals.stream().map(v -> (ContextFragment) v))
                .toList();
        return new Context(id, cm, combined, history, parsed, action);
    }

    /**
     * Creates a new Context with a modified task history list. This generates a new context state with a new ID and
     * action.
     */
    public Context withCompressedHistory(List<TaskEntry> newHistory) {
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                newHistory,
                null,
                CompletableFuture.completedFuture("Compressed History"));
    }

    @Nullable
    public ContextFragment.TaskFragment getParsedOutput() {
        return parsedOutput;
    }

    /** Creates a new (live) Context that copies specific elements from the provided context. */
    public static Context createFrom(Context sourceContext, Context currentContext, List<TaskEntry> newHistory) {
        var unfrozenFragments = sourceContext
                .allFragments()
                .map(fragment -> unfreezeFragmentIfNeeded(fragment, currentContext.contextManager))
                .toList();

        return new Context(
                newContextId(),
                currentContext.contextManager,
                unfrozenFragments,
                newHistory,
                null,
                CompletableFuture.completedFuture("Reset context to historical state"));
    }

    public record FreezeResult(Context liveContext, Context frozenContext) {}

    /**
     * @return a FreezeResult with the (potentially modified to exclude invalid Fragments) liveContext + frozenContext
     */
    public FreezeResult freezeAndCleanup() {
        assert !containsFrozenFragments();

        var liveFragments = new ArrayList<ContextFragment>();
        var frozenFragments = new ArrayList<ContextFragment>();

        for (var fragment : this.fragments) {
            try {
                var frozen = FrozenFragment.freeze(fragment, contextManager);
                liveFragments.add(fragment);
                frozenFragments.add(frozen);
            } catch (IOException e) {
                logger.warn("Failed to freeze fragment {}: {}", fragment.description(), e.getMessage());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        Context liveContext;
        if (!liveFragments.equals(fragments)) {
            liveContext =
                    new Context(this.contextManager, liveFragments, this.taskHistory, this.parsedOutput, this.action);
        } else {
            liveContext = this;
        }

        var frozenContext = new Context(
                this.id, this.contextManager, frozenFragments, this.taskHistory, this.parsedOutput, this.action);

        return new FreezeResult(liveContext, frozenContext);
    }

    public Context freeze() {
        if (!containsDynamicFragments()) {
            return this;
        }
        return freezeAndCleanup().frozenContext;
    }

    @SuppressWarnings("unchecked")
    public static <T extends ContextFragment> T unfreezeFragmentIfNeeded(T fragment, IContextManager contextManager) {
        if (fragment instanceof FrozenFragment frozen) {
            try {
                return (T) frozen.unfreeze(contextManager);
            } catch (IOException e) {
                logger.warn("Failed to unfreeze fragment {}: {}", frozen.description(), e.getMessage());
                return fragment;
            }
        }
        return fragment;
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

    public boolean workspaceContentEquals(Context other) {
        assert !this.containsDynamicFragments();
        assert !other.containsDynamicFragments();

        return allFragments().toList().equals(other.allFragments().toList());
    }

    public boolean containsFrozenFragments() {
        return allFragments().anyMatch(f -> f instanceof FrozenFragment);
    }

    public boolean containsDynamicFragments() {
        return allFragments().anyMatch(ContextFragment::isDynamic);
    }
}
