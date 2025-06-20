package io.github.jbellis.brokk.context;

import com.google.common.collect.Streams;
import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.AnalyzerUtil;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.JoernAnalyzer;
import io.github.jbellis.brokk.context.ContextFragment.HistoryFragment;
import io.github.jbellis.brokk.context.ContextFragment.SkeletonFragment;
import io.github.jbellis.brokk.util.Messages;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Encapsulates all state that will be sent to the model (prompts, filename context, conversation history).
 */
public class Context {
    private static final Logger logger = LogManager.getLogger(Context.class);

    public static final Context EMPTY = new Context(new IContextManager() {}, null);

    public static final int MAX_AUTO_CONTEXT_FILES = 100;
    private static final String WELCOME_ACTION = "Welcome to Brokk";
    public static final String SUMMARIZING = "(Summarizing)";
    public static final long CONTEXT_ACTION_SUMMARY_TIMEOUT_SECONDS = 5;

    private final transient  IContextManager contextManager;
    final  List<ContextFragment> editableFiles; // Can hold PathFragment or FrozenFragment
    final  List<ContextFragment> readonlyFiles; // Can hold PathFragment or FrozenFragment
    final  List<ContextFragment.VirtualFragment> virtualFragments;

    /** Task history list. Each entry represents a user request and the subsequent conversation */
    final List<TaskEntry> taskHistory;

    /** LLM output or other parsed content, with optional fragment. May be null */
    @Nullable
    transient final ContextFragment.TaskFragment parsedOutput;

    /** description of the action that created this context, can be a future (like PasteFragment) */
    public transient final Future<String> action;

    /**
     * Constructor for initial empty context
     */
    public Context(IContextManager contextManager, @Nullable String initialOutputText) {
        this(contextManager,
             List.of(),
             List.of(),
             List.of(),
             new ArrayList<>(),
             getWelcomeOutput(contextManager, initialOutputText),
             CompletableFuture.completedFuture(WELCOME_ACTION));
    }

    private static ContextFragment.TaskFragment getWelcomeOutput(IContextManager contextManager, @Nullable String initialOutputText) {
        var messages = initialOutputText == null ? List.<ChatMessage>of() : List.<ChatMessage>of(Messages.customSystem(initialOutputText));
        return new ContextFragment.TaskFragment(contextManager, messages, "Welcome");
    }

    public Context(IContextManager contextManager,
            List<ContextFragment> editableFiles,
            List<ContextFragment> readonlyFiles,
            List<ContextFragment.VirtualFragment> virtualFragments,
            List<TaskEntry> taskHistory,
            @Nullable ContextFragment.TaskFragment parsedOutput,
            Future<String> action)
    {
        this.contextManager = contextManager;
        this.editableFiles = List.copyOf(editableFiles);
        this.readonlyFiles = List.copyOf(readonlyFiles);
        this.virtualFragments = List.copyOf(virtualFragments);
        this.taskHistory = List.copyOf(taskHistory); // Ensure immutability
        this.action = action;
        this.parsedOutput = parsedOutput;
    }

    /**
     * Produces a *live* context whose fragments are un-frozen versions of those
     * in {@code frozen}.  Used by the UI when the user selects an old snapshot.
     */
    public static Context unfreeze(Context frozen) {
        var cm = frozen.getContextManager();

        var editable  = new ArrayList<ContextFragment>(); // Use general ContextFragment
        var readonly  = new ArrayList<ContextFragment>(); // Use general ContextFragment
        var virtuals  = new ArrayList<ContextFragment.VirtualFragment>();

        // Iterate over frozen.editableFiles() and unfreeze any FrozenFragment found
        frozen.editableFiles().forEach(f -> {
            if (f instanceof FrozenFragment ff) {
                try {
                    editable.add(ff.unfreeze(cm));
                } catch (IOException e) {
                    logger.warn("Unable to unfreeze editable fragment {}: {}", ff.description(), e.getMessage());
                    editable.add(ff); // fall back to frozen
                }
            } else {
                editable.add(f); // Already live or non-dynamic
            }
        });

        // Iterate over frozen.readonlyFiles() and unfreeze any FrozenFragment found
        frozen.readonlyFiles().forEach(f -> {
            if (f instanceof FrozenFragment ff) {
                try {
                    readonly.add(ff.unfreeze(cm));
                } catch (IOException e) {
                    logger.warn("Unable to unfreeze readonly fragment {}: {}", ff.description(), e.getMessage());
                    readonly.add(ff); // fall back to frozen
                }
            } else {
                readonly.add(f); // Already live or non-dynamic
            }
        });

        // Iterate over frozen.virtualFragments() and unfreeze any FrozenFragment found
        frozen.virtualFragments().forEach(vf -> { // vf is a VirtualFragment (could be a FrozenFragment of one)
            if (vf instanceof FrozenFragment ff) {
                try {
                    var liveUnfrozen = ff.unfreeze(cm);
                    // Ensure only VirtualFragments are added to virtuals list
                    if (liveUnfrozen instanceof ContextFragment.VirtualFragment liveVf) {
                        virtuals.add(liveVf);
                    } else {
                        // This case should be rare if Context.freeze() is correct.
                        logger.warn("FrozenFragment from virtuals un-froze to non-VirtualFragment: {}. Retaining frozen.", ff.description());
                        virtuals.add(ff); // fall back to frozen
                    }
                } catch (IOException e) {
                    logger.warn("Unable to unfreeze virtual fragment {}: {}", ff.description(), e.getMessage());
                    virtuals.add(ff); // fall back to frozen
                }
            } else {
                virtuals.add(vf); // Already a live VirtualFragment
            }
        });

        return new Context(cm,
                           List.copyOf(editable),
                           List.copyOf(readonly),
                           List.copyOf(virtuals),
                           frozen.getTaskHistory(),
                           frozen.getParsedOutput(),
                           frozen.action);
    }

    /**
     * Creates a new Context with an additional set of editable files. Rebuilds autoContext if toggled on.
     */
    public Context addEditableFiles(Collection<ContextFragment.ProjectPathFragment> paths) { // IContextManager is already member
        var toAdd = paths.stream()
                .filter(Objects::nonNull) // Ensure correct type for contains check
                .filter(fragment -> !editableFiles.contains(fragment))
                .toList();
        if (toAdd.isEmpty()) {
            return this;
        }
        var newEditable = new ArrayList<>(editableFiles);
        newEditable.addAll(toAdd);

        String actionDetails = toAdd.stream()
                .map(ContextFragment::shortDescription)
                .collect(Collectors.joining(", "));
        String action = "Edit " + actionDetails;
        return getWithFragments(newEditable, readonlyFiles, virtualFragments, action);
    }

    public Context addReadonlyFiles(Collection<ContextFragment.PathFragment> paths) { // IContextManager is already member
        var toAdd = paths.stream()
            .filter(Objects::nonNull) // Ensure correct type for contains check
            .filter(fragment -> !readonlyFiles.contains(fragment))
            .toList();
        if (toAdd.isEmpty()) {
            return this;
        }
        var newReadOnly = new ArrayList<>(readonlyFiles);
        newReadOnly.addAll(toAdd);

        String actionDetails = toAdd.stream()
                .map(ContextFragment::shortDescription)
                .collect(Collectors.joining(", "));
        String action = "Read " + actionDetails;
        return getWithFragments(editableFiles, newReadOnly, virtualFragments, action);
    }

    public Context removeEditableFiles(List<? extends ContextFragment> fragments) { // IContextManager is already member
        var newEditable = new ArrayList<>(editableFiles);
        if (!newEditable.removeAll(fragments)) { // removeAll returns true if list changed
            return this;
        }

        String actionDetails = fragments.stream()
                .map(ContextFragment::shortDescription)
                .collect(Collectors.joining(", "));
        String action = "Removed " + actionDetails;
        return getWithFragments(newEditable, readonlyFiles, virtualFragments, action);
    }

    public Context removeReadonlyFiles(List<? extends ContextFragment> fragments) { // IContextManager is already member
        var newReadOnly = new ArrayList<>(readonlyFiles);
        if (!newReadOnly.removeAll(fragments)) { // removeAll returns true if list changed
            return this;
        }

        String actionDetails = fragments.stream()
                .map(ContextFragment::shortDescription)
                .collect(Collectors.joining(", "));
        String action = "Removed " + actionDetails;
        return getWithFragments(editableFiles, newReadOnly, virtualFragments, action);
    }

    public Context addVirtualFragment(ContextFragment.VirtualFragment fragment) { // IContextManager is already member
        // Check if a fragment with the same text content already exists
        boolean duplicateByText = virtualFragments.stream()
                .anyMatch(vf -> Objects.equals(vf.text(), fragment.text()));

        if (duplicateByText) {
            return this; // Fragment with same text content already present, no change
        }

        var newFragments = new ArrayList<>(virtualFragments);
        newFragments.add(fragment);

        String action = "Added " + fragment.shortDescription();
        return getWithFragments(editableFiles, readonlyFiles, newFragments, action);
    }

    private Context getWithFragments(List<ContextFragment> newEditableFiles,
                                     List<ContextFragment> newReadonlyFiles,
                                     List<ContextFragment.VirtualFragment> newVirtualFragments,
                                     String action) {
        return withFragments(newEditableFiles, newReadonlyFiles, newVirtualFragments, CompletableFuture.completedFuture(action));
    }

    /**
     * 1) Gather all classes from each fragment.
     * 2) Compute PageRank with those classes as seeds, requesting up to 2*MAX_AUTO_CONTEXT_FILES
     * 3) Return a SkeletonFragment constructed with the FQNs of the top results.
     */
    public SkeletonFragment buildAutoContext(int topK) throws InterruptedException {
        IAnalyzer analyzer;
        analyzer = contextManager.getAnalyzer();

        // Collect ineligible classnames from fragments not eligible for auto-context
        var ineligibleSources = Streams.concat(editableFiles.stream(), readonlyFiles.stream(), virtualFragments.stream())
                .filter(f -> !f.isEligibleForAutoContext())
                .flatMap(f -> f.sources().stream()) // No analyzer
                .collect(Collectors.toSet());

        // Collect initial seeds
        var weightedSeeds = new HashMap<String, Double>();
        // editable files have a weight of 1.0, each
        editableFiles.stream().flatMap(f -> f.sources().stream()).forEach(unit -> { // No analyzer
            weightedSeeds.put(unit.fqName(), 1.0);
        });
        // everything else splits a weight of 1.0
        Streams.concat(readonlyFiles.stream(), virtualFragments.stream())
                .flatMap(f -> f.sources().stream()) // No analyzer
                .forEach(unit ->
        {
            weightedSeeds.merge(unit.fqName(), 1.0 / (readonlyFiles.size() + virtualFragments.size()), Double::sum);
        });

        // If no seeds, we can't compute pagerank
        if (weightedSeeds.isEmpty()) {
            // Pass contextManager to SkeletonFragment constructor
            return new SkeletonFragment(contextManager, List.of(), ContextFragment.SummaryType.CLASS_SKELETON); // Empty skeleton fragment
        }

        return buildAutoContextFragment(contextManager, analyzer, weightedSeeds, ineligibleSources, topK);
    }

    public static SkeletonFragment buildAutoContextFragment(IContextManager contextManager, IAnalyzer analyzer, Map<String, Double> weightedSeeds, Set<CodeUnit> ineligibleSources, int topK) {
        var pagerankResults = AnalyzerUtil.combinedPagerankFor(analyzer, weightedSeeds);

        List<String> targetFqns = new ArrayList<>();
        for (var codeUnit : pagerankResults) {
            var fqcn = codeUnit.fqName();
            var sourceFileOption = analyzer.getFileFor(fqcn);
            if (sourceFileOption.isEmpty()) {
                logger.warn("No source file found for class {}", fqcn);
                continue;
            }
            var sourceFile = sourceFileOption.get();
            // Check if the class or its parent is in ineligible classnames
            boolean eligible = !ineligibleSources.contains(codeUnit);
            if (fqcn.contains("$")) {
                var parentFqcn = fqcn.substring(0, fqcn.indexOf('$'));
                // FIXME generalize this
                // Check if the analyzer supports cuClass and cast if necessary
                if (analyzer instanceof JoernAnalyzer aa) {
                    // Use the analyzer helper method which handles splitting correctly
                    var parentUnitOpt = aa.cuClass(parentFqcn, sourceFile); // Returns scala.Option
                    if (parentUnitOpt.isDefined() && ineligibleSources.contains(parentUnitOpt.get())) {
                        eligible = false;
                    }
                } else {
                    logger.warn("Analyzer of type {} does not support direct CodeUnit creation, skipping parent eligibility check for {}",
                                analyzer.getClass().getSimpleName(), fqcn);
                }
            }

            if (eligible) { // Parentheses removed around condition
                // Check if skeleton exists before adding, to ensure it's a valid target for summary
                if (analyzer.getSkeleton(fqcn).isPresent()) {
                    targetFqns.add(fqcn);
                }
            }
            if (targetFqns.size() >= topK) {
                break;
            }
        }
        if (targetFqns.isEmpty()) {
            // Pass contextManager to SkeletonFragment constructor
            return new SkeletonFragment(contextManager, List.of(), ContextFragment.SummaryType.CLASS_SKELETON); // Empty
        }
        // Pass contextManager to SkeletonFragment constructor
        return new SkeletonFragment(contextManager, targetFqns, ContextFragment.SummaryType.CLASS_SKELETON);
    }

    // ---------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------

    public Stream<ContextFragment> editableFiles() {
        return editableFiles.stream();
    }

    public Stream<ContextFragment> readonlyFiles() {
        return readonlyFiles.stream();
    }

    public Stream<ContextFragment.VirtualFragment> virtualFragments() {
        // this.virtualFragments is guaranteed to be non-null and deduplicated by the constructor.
        return this.virtualFragments.stream();
    }

    /**
     * Returns readonly files and virtual fragments (excluding usage fragments) as a combined stream
     */
    public Stream<ContextFragment> getReadOnlyFragments() {
        return Streams.concat(
            readonlyFiles.stream(),
            virtualFragments.stream().filter(f -> f.getType() != ContextFragment.FragmentType.USAGE)
        );
    }

    /**
     * Returns editable files and usage fragments as a combined stream
     */
    public Stream<ContextFragment> getEditableFragments() {
        // Helper record for associating a fragment with its mtime for safe sorting and filtering
        record EditableFileWithMtime(ContextFragment.ProjectPathFragment fragment, long mtime) {}

        Stream<ContextFragment.ProjectPathFragment> sortedProjectFiles =
            editableFiles.stream()
                .filter(ContextFragment.ProjectPathFragment.class::isInstance)
                .map(ContextFragment.ProjectPathFragment.class::cast)
                .map(pf -> {
                    try {
                        return new EditableFileWithMtime(pf, pf.file().mtime());
                    } catch (IOException e) {
                        logger.warn("Could not get mtime for editable file [{}], it will be excluded from ordered editable fragments.",
                                    pf.shortDescription(), e);
                        return new EditableFileWithMtime(pf, -1L); // Mark for filtering
                    }
                })
                .filter(mf -> mf.mtime() >= 0) // Filter out files with errors or negative mtime
                .sorted(Comparator.comparingLong(EditableFileWithMtime::mtime)) // Sort by mtime
                .map(EditableFileWithMtime::fragment); // Extract the original fragment

        // Include FrozenFragments that originated from editable files, and other non-ProjectPathFragment types if any.
        // These will not be sorted by mtime but will appear after usage fragments and before mtime-sorted project files.
        // This ordering might need refinement based on desired UX. For now, keeping it simple.
        Stream<ContextFragment> otherEditableFragments = editableFiles.stream()
                .filter(f -> !(f instanceof ContextFragment.ProjectPathFragment));

        return Streams.concat(virtualFragments.stream().filter(f -> f.getType() == ContextFragment.FragmentType.USAGE),
                              otherEditableFragments,
                              sortedProjectFiles.map(ContextFragment.class::cast));
    }

    public Stream<? extends ContextFragment> allFragments() {
        return Streams.concat(editableFiles.stream(),
                              readonlyFiles.stream(),
                              virtualFragments.stream());
    }

    /**
     * Removes fragments from this context by their IDs.
     * 
     * @param idsToRemove Collection of fragment IDs to remove
     * @return A new Context with the specified fragments removed, or this context if no changes were made
     */
    public Context removeFragmentsByIds(Collection<String> idsToRemove) {
        if (idsToRemove == null || idsToRemove.isEmpty()) {
            return this;
        }

        var newEditableFiles = editableFiles.stream()
                .filter(f -> !idsToRemove.contains(f.id()))
                .toList();
        var newReadonlyFiles = readonlyFiles.stream()
                .filter(f -> !idsToRemove.contains(f.id()))
                .toList();
        var newVirtualFragments = virtualFragments.stream()
                .filter(f -> !idsToRemove.contains(f.id()))
                .toList();

        // Count how many fragments were actually removed
        int originalCount = editableFiles.size() + readonlyFiles.size() + virtualFragments.size();
        int newCount = newEditableFiles.size() + newReadonlyFiles.size() + newVirtualFragments.size();
        int removedCount = originalCount - newCount;

        if (removedCount == 0) {
            return this; // No changes made
        }

        String actionString = "Removed " + removedCount + " fragment" + (removedCount == 1 ? "" : "s");
        return withFragments(newEditableFiles, newReadonlyFiles, newVirtualFragments, CompletableFuture.completedFuture(actionString));
    }

    /**
     * Creates a new context with custom collections and action description,
     * refreshing auto-context if needed.
     */
    private Context withFragments(List<ContextFragment> newEditableFiles,
                                  List<ContextFragment> newReadonlyFiles,
                                  List<ContextFragment.VirtualFragment> newVirtualFragments,
                                  Future<String> action) {
        return new Context(contextManager,
                newEditableFiles,
                newReadonlyFiles,
                newVirtualFragments,
                taskHistory,
                null,
                action);
    }

    public Context removeAll() {
        String action = "Dropped all context";
        return new Context(contextManager,
                           List.of(), // editable
                           List.of(), // readonly
                           List.of(), // virtual
                           List.of(), // task history
                           null, // parsed output
                           CompletableFuture.completedFuture(action));
    }

    // Method removed in favor of toFragment(int position)

    public boolean isEmpty() {
        return editableFiles.isEmpty()
                && readonlyFiles.isEmpty()
                && virtualFragments.isEmpty()
                && taskHistory.isEmpty();
    }

    /**
     * Creates a new TaskEntry with the correct sequence number based on the current history.
     * @return A new TaskEntry.
     */
    public TaskEntry createTaskEntry(TaskResult result) {
        int nextSequence = taskHistory.isEmpty() ? 1 : taskHistory.getLast().sequence() + 1;
        return TaskEntry.fromSession(nextSequence, result);
    }

    /**
     * Adds a new TaskEntry to the history.
     *
     * @param taskEntry        The pre-constructed TaskEntry to add.
     * @param parsed           The parsed output associated with this task.
     * @param action           A future describing the action that created this history entry.
     * @return A new Context instance with the added task history.
     */
    public Context addHistoryEntry(TaskEntry taskEntry, @Nullable ContextFragment.TaskFragment parsed, Future<String> action) {
        var newTaskHistory = Streams.concat(taskHistory.stream(), Stream.of(taskEntry)).toList();
        return new Context(contextManager,
                           editableFiles,
                           readonlyFiles,
                           virtualFragments,
                           newTaskHistory, // new task history list
                           parsed,
                           action);
    }


    public Context clearHistory() {
        return new Context(contextManager,
                           editableFiles,
                           readonlyFiles,
                           virtualFragments,
                           List.of(), // Cleared task history
                           null,
                           CompletableFuture.completedFuture("Cleared task history"));
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
     * 0 => conversation history (if not empty)
     * 1 => autoContext (always present, even when DISABLED)
     * next => read-only (readonlyFiles + virtualFragments)
     * finally => editable
     */
    public List<ContextFragment> getAllFragmentsInDisplayOrder() {
        var result = new ArrayList<ContextFragment>();

        // Then conversation history
        if (!taskHistory.isEmpty()) {
            result.add(new HistoryFragment(contextManager, taskHistory));
        }

        // then read-only
        result.addAll(readonlyFiles);
        result.addAll(virtualFragments);

        // then editable
        result.addAll(editableFiles);

        return result;
    }

    public Context withParsedOutput(@Nullable ContextFragment.TaskFragment parsedOutput, Future<String> action) {
        return new Context(contextManager,
                           editableFiles,
                           readonlyFiles,
                           virtualFragments,
                           taskHistory,
                           parsedOutput,
                           action);
    }

    public Context withParsedOutput(@Nullable ContextFragment.TaskFragment parsedOutput, String action) {
        return new Context(contextManager,
                           editableFiles,
                           readonlyFiles,
                           virtualFragments,
                           taskHistory,
                           parsedOutput,
                           CompletableFuture.completedFuture(action));
    }

    public Context withAction(Future<String> action) {
        return new Context(contextManager,
                           editableFiles,
                           readonlyFiles,
                           virtualFragments,
                           taskHistory,
                           parsedOutput,
                           action);
    }

    /**
     * Creates a new Context with a modified task history list.
     * This generates a new context state with a new ID and action.
     *
     * @param newHistory The new list of TaskEntry objects.
     * @return A new Context instance with the updated history.
     */
    public Context withCompressedHistory(List<TaskEntry> newHistory) {
        return new Context(contextManager,
                           editableFiles,
                           readonlyFiles,
                           virtualFragments,
                           newHistory, // Use the new history
                           null,     // parsed output
                           CompletableFuture.completedFuture("Compressed History"));
    }

    @Nullable
    public ContextFragment.TaskFragment getParsedOutput() {
        return parsedOutput;
    }

    /**
     * Creates a new (live) Context that copies specific elements from the provided context.
     * This creates a reset point by:
     * - Using the files and fragments from the source context
     * - Keeping the history messages from the current context
     * - Setting up properly for rebuilding autoContext
     * - Clearing parsed output and original contents
     * - Setting a suitable action description
     */
    public static Context createFrom(Context sourceContext, Context currentContext, List<TaskEntry> newHistory) {
        assert sourceContext != null;
        assert currentContext != null;

        // Unfreeze fragments from the source context if they are frozen
        var unfrozenEditableFiles = sourceContext.editableFiles().map(fragment -> unfreezeFragmentIfNeeded(fragment, currentContext.contextManager)).toList();
        var unfrozenReadonlyFiles = sourceContext.readonlyFiles().map(fragment -> unfreezeFragmentIfNeeded(fragment, currentContext.contextManager)).toList();
        var unfrozenVirtualFragments = sourceContext.virtualFragments().map(fragment -> unfreezeFragmentIfNeeded(fragment, currentContext.contextManager)).toList();

        // New ID for the reset point
        return new Context(currentContext.contextManager,
                           unfrozenEditableFiles,
                           unfrozenReadonlyFiles,
                           unfrozenVirtualFragments,
                           newHistory,
                           null,
                           CompletableFuture.completedFuture("Reset context to historical state"));
    }

    public record FreezeResult(Context liveContext, Context frozenContext) {
        public FreezeResult {
            assert liveContext != null;
            assert frozenContext != null;
        }
    }

    /**
     * @return a FreezeResult with the (potentially modified to exclude invalid Fragments)
     *         liveContext + frozenContext
     */
    public FreezeResult freezeAndCleanup() {
        assert !containsFrozenFragments();

        var liveEditableFiles = new ArrayList<ContextFragment>();
        var frozenEditableFiles = new ArrayList<ContextFragment>();

        for (var fragment : this.editableFiles) {
            try {
                var frozen = FrozenFragment.freeze(fragment, contextManager);
                liveEditableFiles.add(fragment);
                frozenEditableFiles.add(frozen);
            } catch (IOException e) {
                logger.warn("Failed to freeze editable fragment {}: {}", fragment.description(), e.getMessage());
            } catch (InterruptedException e) {
                throw new RuntimeException(e); // we should not be interrupted here
            }
        }

        var liveReadonlyFiles = new ArrayList<ContextFragment>();
        var frozenReadonlyFiles = new ArrayList<ContextFragment>();

        for (var fragment : this.readonlyFiles) {
            try {
                var frozen = FrozenFragment.freeze(fragment, contextManager);
                liveReadonlyFiles.add(fragment);
                frozenReadonlyFiles.add(frozen);
            } catch (IOException e) {
                logger.warn("Failed to freeze readonly fragment {}: {}", fragment.description(), e.getMessage());
            } catch (InterruptedException e) {
                throw new RuntimeException(e); // we should not be interrupted here
            }
        }

        var liveVirtualFragments = new ArrayList<ContextFragment.VirtualFragment>();
        var frozenVirtualFragments = new ArrayList<ContextFragment.VirtualFragment>();

        for (var fragment : this.virtualFragments) {
            try {
                var frozen = FrozenFragment.freeze(fragment, contextManager);
                liveVirtualFragments.add(fragment);
                frozenVirtualFragments.add((ContextFragment.VirtualFragment) frozen);
            } catch (IOException e) {
                logger.warn("Failed to freeze virtual fragment {}: {}", fragment.description(), e.getMessage());
            } catch (InterruptedException e) {
                throw new RuntimeException(e); // we should not be interrupted here
            }
        }

        // Create live context with bad fragments removed
        Context liveContext;
        if (!liveEditableFiles.equals(editableFiles)
                || !liveReadonlyFiles.equals(readonlyFiles)
                || !liveVirtualFragments.equals(virtualFragments))
        {
            liveContext = new Context(this.contextManager,
                                      liveEditableFiles,
                                      liveReadonlyFiles,
                                      liveVirtualFragments,
                                      this.taskHistory,
                                      this.parsedOutput,
                                      this.action);
        } else {
            liveContext = this;
        }

        // Create frozen context
        var frozenContext = new Context(this.contextManager,
                                        frozenEditableFiles,
                                        frozenReadonlyFiles,
                                        frozenVirtualFragments,
                                        this.taskHistory,
                                        this.parsedOutput,
                                        this.action);

        return new FreezeResult(liveContext, frozenContext);
    }

    /**
     * Creates a new Context with dynamic fragments replaced by their frozen counterparts.
     * Dynamic PathFragments (from editable or readonly lists) are frozen and remain in their
     * respective lists as FrozenFragment instances. Dynamic VirtualFragments are also frozen
     * and remain in the virtualFragments list.
     *
     * Use with care since this method throws away the changes made by excluding newly-invalid fragments!
     *
     * @return A new Context instance with dynamic fragments frozen
     */
    public Context freeze() {
        return freezeAndCleanup().frozenContext;
    }

    /**
     * Helper method to unfreeze a fragment if it's a FrozenFragment, otherwise return as-is.
     * Used when restoring contexts from history to get live fragments.
     */
    @SuppressWarnings("unchecked")
    public static <T extends ContextFragment> T unfreezeFragmentIfNeeded(T fragment, IContextManager contextManager) {
        if (fragment instanceof FrozenFragment frozen) {
            try {
                return (T) frozen.unfreeze(contextManager);
            } catch (IOException e) {
                logger.warn("Failed to unfreeze fragment {}: {}", frozen.description(), e.getMessage());
                // Return the frozen fragment if unfreezing fails
                return fragment;
            }
        }
        return fragment;
    }

    public boolean workspaceEquals(Context other) {
        return allFragments().toList().equals(other.allFragments().toList()) && taskHistory.equals(other.taskHistory);
    }
    
    public boolean containsFrozenFragments() {
        return allFragments().anyMatch(f -> f instanceof FrozenFragment);
    }

    public boolean containsDynamicFragments() {
        return allFragments().anyMatch(ContextFragment::isDynamic);
    }
}
