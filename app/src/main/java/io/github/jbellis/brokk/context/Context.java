package io.github.jbellis.brokk.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.f4b6a3.uuid.UuidCreator;
import com.google.common.collect.Streams;
import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment.HistoryFragment;
import io.github.jbellis.brokk.git.GitDistance;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.gui.ActivityTableRenderers;
import io.github.jbellis.brokk.util.ContentDiffUtils;
import io.github.jbellis.brokk.util.Json;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

    // Cache diffs per "other" context id; contexts are immutable so diffs won't change
    private final transient Map<UUID, List<DiffEntry>> diffCache = new ConcurrentHashMap<>();

    public static final int MAX_AUTO_CONTEXT_FILES = 100;
    private static final String WELCOME_ACTION = "Session Start";
    public static final String SUMMARIZING = "(Summarizing)";
    public static final long CONTEXT_ACTION_SUMMARY_TIMEOUT_SECONDS = 5;

    private final transient IContextManager contextManager;

    // Unified list for all fragments (paths and virtuals)
    final List<ContextFragment> fragments;

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

    /** Per-fragment diff entry between two contexts. */
    public record DiffEntry(
            ContextFragment fragment, String diff, int linesAdded, int linesDeleted, String oldContent) {}

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

        // Convert legacy SkeletonFragments to individual SummaryFragments
        var expandedFragments = new ArrayList<ContextFragment>();
        for (var fragment : newFragments) {
            if (fragment instanceof ContextFragment.SkeletonFragment skeleton) {
                logger.debug(
                        "Converting legacy SkeletonFragment id={} with {} target(s) to individual SummaryFragments",
                        skeleton.id(),
                        skeleton.getTargetIdentifiers().size());
                for (String targetId : skeleton.getTargetIdentifiers()) {
                    var summary = new ContextFragment.SummaryFragment(cm, targetId, skeleton.getSummaryType());
                    expandedFragments.add(summary);
                }
            } else {
                expandedFragments.add(fragment);
            }
        }

        return new Context(
                frozen.id(),
                cm,
                List.copyOf(expandedFragments),
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

    public Context addVirtualFragments(Collection<? extends ContextFragment.VirtualFragment> toAdd) {
        if (toAdd.isEmpty()) {
            return this;
        }

        var newFragments = new ArrayList<>(fragments);
        var existingVirtuals = fragments.stream()
                .filter(f -> f.getType().isVirtual())
                .map(f -> (ContextFragment.VirtualFragment) f)
                .toList();

        for (var fragment : toAdd) {
            // Deduplicate among existing virtual fragments only
            boolean isDuplicate = fragment.getType() == ContextFragment.FragmentType.PASTE_IMAGE
                    ? existingVirtuals.stream().anyMatch(vf -> Objects.equals(vf.id(), fragment.id()))
                            || newFragments.stream()
                                    .filter(f -> f.getType().isVirtual())
                                    .map(f -> (ContextFragment.VirtualFragment) f)
                                    .anyMatch(vf -> Objects.equals(vf.id(), fragment.id()))
                    : existingVirtuals.stream().anyMatch(vf -> Objects.equals(vf.text(), fragment.text()))
                            || newFragments.stream()
                                    .filter(f -> f.getType().isVirtual())
                                    .map(f -> (ContextFragment.VirtualFragment) f)
                                    .anyMatch(vf -> Objects.equals(vf.text(), fragment.text()));

            if (!isDuplicate) {
                newFragments.add(fragment);
            }
        }

        if (newFragments.size() == fragments.size()) {
            return this;
        }

        int addedCount = newFragments.size() - fragments.size();
        String action = "Added " + addedCount + " fragment" + (addedCount == 1 ? "" : "s");
        return withFragments(newFragments, CompletableFuture.completedFuture(action));
    }

    public Context addVirtualFragment(ContextFragment.VirtualFragment fragment) {
        return addVirtualFragments(List.of(fragment));
    }

    private Context withFragments(List<ContextFragment> newFragments, Future<String> action) {
        return new Context(newContextId(), contextManager, newFragments, taskHistory, null, action);
    }

    /** Returns the files from the git repo that are most relevant to this context, up to the specified limit. */
    public List<ProjectFile> getMostRelevantFiles(int topK) throws InterruptedException {
        var ineligibleSources = fragments.stream()
                .filter(f -> !f.isEligibleForAutoContext())
                .flatMap(f -> f.files().stream())
                .collect(Collectors.toSet());

        record WeightedFile(ProjectFile file, double weight) {}

        var weightedSeeds = fragments.stream()
                .filter(f -> !f.files().isEmpty())
                .flatMap(fragment -> {
                    double weight = Math.sqrt(1.0 / fragment.files().size());
                    return fragment.files().stream().map(file -> new WeightedFile(file, weight));
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
    public List<ContextFragment.SummaryFragment> buildAutoContext(int topK) throws InterruptedException {
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
                .map(fqn -> new ContextFragment.SummaryFragment(
                        contextManager, fqn, ContextFragment.SummaryType.CODEUNIT_SKELETON))
                .toList();
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
                .filter(f -> f.getType().isVirtual() && f.getType().isEditable());

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
        String action = ActivityTableRenderers.DROPPED_ALL_CONTEXT;
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
                CompletableFuture.completedFuture(ActivityTableRenderers.CLEARED_TASK_HISTORY));
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
            List<ContextFragment> fragments,
            List<TaskEntry> history,
            @Nullable ContextFragment.TaskFragment parsed,
            Future<String> action) {
        return new Context(id, cm, fragments, history, parsed, action);
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
                CompletableFuture.completedFuture("Compress History"));
    }

    @Nullable
    public ContextFragment.TaskFragment getParsedOutput() {
        return parsedOutput;
    }

    /** Returns true if the parsedOutput contains AI messages (useful for UI decisions). */
    public boolean isAiResult() {
        var parsed = getParsedOutput();
        if (parsed == null) {
            return false;
        }
        return parsed.messages().stream().anyMatch(m -> m.type() == ChatMessageType.AI);
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

    /**
     * Retrieves the DISCARDED_CONTEXT fragment and parses it as a Map of description -> explanation.
     * Returns an empty map if no DISCARDED_CONTEXT fragment exists or if parsing fails.
     */
    public Map<String, String> getDiscardedFragmentsNote() {
        var discardedDescription = ContextFragment.DISCARDED_CONTEXT.description();
        var existingDiscarded = virtualFragments()
                .filter(vf -> vf.getType() == ContextFragment.FragmentType.STRING)
                .filter(vf -> vf instanceof ContextFragment.StringFragment)
                .map(vf -> (ContextFragment.StringFragment) vf)
                .filter(sf -> discardedDescription.equals(sf.description()))
                .findFirst();

        if (existingDiscarded.isEmpty()) {
            return Map.of();
        }

        var mapper = Json.getMapper();
        try {
            return mapper.readValue(existingDiscarded.get().text(), new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            logger.warn("Failed to parse DISCARDED_CONTEXT JSON", e);
            return Map.of();
        }
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

    /**
     * Returns the processed output text from the latest build failure fragment in this Context. Empty string if there
     * is no build failure recorded.
     */
    public String getBuildError() {
        var desc = ContextFragment.BUILD_RESULTS.description();
        return virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.STRING)
                .filter(sf -> desc.equals(sf.description()))
                .map(cf -> cf.text())
                .findFirst()
                .orElse("");
    }

    /**
     * Returns a new Context reflecting the latest build result. Behavior mirrors ContextManager.updateBuildFragment: -
     * Always clears previous build fragments (legacy BUILD_LOG and the new BUILD_RESULTS StringFragment). - Adds a new
     * "Latest Build Results" StringFragment only on failure; no fragment on success.
     */
    public Context withBuildResult(boolean success, String processedOutput) {
        var desc = ContextFragment.BUILD_RESULTS.description();

        var idsToDrop = virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.BUILD_LOG
                        || (f.getType() == ContextFragment.FragmentType.STRING
                                && f instanceof ContextFragment.StringFragment sf
                                && desc.equals(sf.description())))
                .map(ContextFragment::id)
                .toList();

        var afterClear = idsToDrop.isEmpty() ? this : removeFragmentsByIds(idsToDrop);

        if (success) {
            // Build succeeded; nothing to add after clearing old fragments
            return afterClear.withAction(CompletableFuture.completedFuture("Build results cleared (success)"));
        }

        // Build failed; add a new StringFragment with the processed output
        var sf = new ContextFragment.StringFragment(
                getContextManager(), processedOutput, desc, ContextFragment.BUILD_RESULTS.syntaxStyle());

        var newFragments = new ArrayList<>(afterClear.fragments);
        newFragments.add(sf);

        return new Context(
                newContextId(),
                getContextManager(),
                newFragments,
                afterClear.taskHistory,
                afterClear.parsedOutput,
                CompletableFuture.completedFuture("Build results updated (failure)"));
    }

    private boolean isNewFileInGit(FrozenFragment ff) {
        if (ff.getType() != ContextFragment.FragmentType.PROJECT_PATH) {
            return false;
        }
        IGitRepo repo = contextManager.getRepo();
        return !repo.getTrackedFiles().contains(ff.files().iterator().next());
    }

    /**
     * Compute per-fragment diffs between this (right/new) and the other (left/old) context. Only considers fragments
     * present in this context, per requirements. Results are cached per other.id().
     */
    public List<DiffEntry> getDiff(Context other) {
        if (this.containsDynamicFragments()) {
            throw new IllegalStateException("Cannot compute diff from dynamic fragments; found " + this);
        }
        if (other.containsDynamicFragments()) {
            throw new IllegalStateException("Cannot compute diff against dynamic fragments; found " + other);
        }

        var cached = diffCache.get(other.id()); // cache should key on "other.id()", not this.id()
        if (cached != null) {
            return cached;
        }

        var diffs = fragments.stream()
                .flatMap(cf -> cf instanceof FrozenFragment ff ? Stream.of(ff) : Stream.empty())
                .map(ff -> {
                    var ff2 = other.fragments.stream()
                            .filter(ff::hasSameSource)
                            .findFirst()
                            .orElse(null);
                    if (ff2 == null) {
                        // No matching fragment in 'other'; if this represents a new, untracked file in Git, diff
                        // against empty
                        if (isNewFileInGit(ff) && ff.isText()) {
                            var newContent = ff.text();
                            var result = ContentDiffUtils.computeDiffResult(
                                    "", newContent, "old/" + ff.shortDescription(), "new/" + ff.shortDescription());
                            if (result.diff().isEmpty()) {
                                return null;
                            }
                            return new DiffEntry(ff, result.diff(), result.added(), result.deleted(), "");
                        }
                        return null;
                    }

                    var oldContent = ff2.text();
                    var newContent = ff.text();

                    int oldLineCount =
                            oldContent.isEmpty() ? 0 : (int) oldContent.lines().count();
                    int newLineCount =
                            newContent.isEmpty() ? 0 : (int) newContent.lines().count();
                    logger.debug(
                            "getDiff: fragment='{}' id={} oldLines={} newLines={}",
                            ff.shortDescription(),
                            id,
                            oldLineCount,
                            newLineCount);

                    var result = ContentDiffUtils.computeDiffResult(
                            oldContent, newContent, "old/" + ff.shortDescription(), "new/" + ff.shortDescription());

                    logger.debug(
                            "getDiff: fragment='{}' added={} deleted={} diffEmpty={}",
                            ff.shortDescription(),
                            result.added(),
                            result.deleted(),
                            result.diff().isEmpty());

                    if (result.diff().isEmpty()) {
                        return null;
                    }
                    return new DiffEntry(ff, result.diff(), result.added(), result.deleted(), oldContent);
                })
                .filter(Objects::nonNull)
                .toList();

        diffCache.put(other.id(), diffs);
        return diffs;
    }
}
