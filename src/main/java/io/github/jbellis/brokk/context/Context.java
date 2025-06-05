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
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Encapsulates all state that will be sent to the model (prompts, filename context, conversation history).
 */
public class Context {
    private static final Logger logger = LogManager.getLogger(Context.class);
    private static final AtomicInteger idCounter = new AtomicInteger(0);

    static int newId() { // Changed from private to package-private
        return idCounter.incrementAndGet();
    }

    public static final int MAX_AUTO_CONTEXT_FILES = 100;
    private static final String WELCOME_ACTION = "Welcome to Brokk";
    public static final String SUMMARIZING = "(Summarizing)";

    private final transient IContextManager contextManager;
    final List<ContextFragment> editableFiles; // Can hold PathFragment or FrozenFragment
    final List<ContextFragment> readonlyFiles; // Can hold PathFragment or FrozenFragment
    final List<ContextFragment.VirtualFragment> virtualFragments;

    /** Task history list. Each entry represents a user request and the subsequent conversation */
    final List<TaskEntry> taskHistory;

    /** LLM output or other parsed content, with optional fragment. May be null */
    transient final ContextFragment.TaskFragment parsedOutput;

    /** description of the action that created this context, can be a future (like PasteFragment) */
    transient final Future<String> action;

    /**
     * Unique transient identifier for this context instance.
     * Used to track identity across asynchronous autocontext refresh
     */
    transient final int id;

    /**
     * Constructor for initial empty context
     */
    public Context(@NotNull IContextManager contextManager, String initialOutputText) {
        this(newId(),
             Objects.requireNonNull(contextManager, "contextManager cannot be null"),
             List.of(),
             List.of(),
             List.of(),
             new ArrayList<>(),
             getWelcomeOutput(contextManager, initialOutputText), // Pass contextManager here
             CompletableFuture.completedFuture(WELCOME_ACTION));
    }

    private static @NotNull ContextFragment.TaskFragment getWelcomeOutput(IContextManager contextManager, String initialOutputText) {
        var messages = List.<ChatMessage>of(Messages.customSystem(initialOutputText));
        return new ContextFragment.TaskFragment(contextManager, messages, "Welcome");
    }

    /**
     * Constructor for initial empty context with empty output. Tests only
     */
    Context(@NotNull IContextManager contextManager) { // Made package-private and kept @NotNull
        this(Objects.requireNonNull(contextManager, "contextManager cannot be null"), "placeholder");
    }

    Context(int id,
            @NotNull IContextManager contextManager,
            List<ContextFragment> editableFiles,
            List<ContextFragment> readonlyFiles,
            List<ContextFragment.VirtualFragment> virtualFragments,
            List<TaskEntry> taskHistory,
            ContextFragment.TaskFragment parsedOutput,
            Future<String> action)
    {
        assert id > 0;
        // contextManager is asserted non-null by the caller or public constructor
        assert editableFiles != null;
        assert readonlyFiles != null;
        assert virtualFragments != null;
        assert taskHistory != null;
        assert action != null;
        this.id = id;
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager cannot be null in private constructor");
        this.editableFiles = List.copyOf(editableFiles);
        this.readonlyFiles = List.copyOf(readonlyFiles);
        this.virtualFragments = List.copyOf(virtualFragments);
        this.taskHistory = List.copyOf(taskHistory); // Ensure immutability
        this.parsedOutput = parsedOutput;
        this.action = action;
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

        return new Context(frozen.getId(),
                           cm,
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
        var newFragments = new ArrayList<>(virtualFragments);
        newFragments.add(fragment);

        String action = "Added " + fragment.shortDescription();
        return getWithFragments(editableFiles, readonlyFiles, newFragments, action);
    }

    public Context removeBadFragment(ContextFragment f) { // IContextManager is already member
        if (f.getType().isPathFragment()) {
            var pf = (ContextFragment.PathFragment) f;
            var inEditable = editableFiles.contains(pf);
            var inReadonly = readonlyFiles.contains(pf);

            if (inEditable) {
                var newEditable = new ArrayList<>(editableFiles);
                newEditable.remove(pf);
                return getWithFragments(newEditable, readonlyFiles, virtualFragments,
                                        "Removed unreadable " + pf.description());
            } else if (inReadonly) {
                var newReadonly = new ArrayList<>(readonlyFiles);
                newReadonly.remove(pf);
                return getWithFragments(editableFiles, newReadonly, virtualFragments,
                                        "Removed unreadable " + pf.description());
            }
            return this;
        } else if (f.getType().isVirtualFragment()) {
            var vf = (ContextFragment.VirtualFragment) f;
            var newFragments = new ArrayList<>(virtualFragments);
            if (newFragments.remove(vf)) {
                return getWithFragments(editableFiles, readonlyFiles, newFragments,
                                        "Removed unreadable " + vf.description());
            }
            return this;
        } else {
            // This case should ideally not be reached if all fragments correctly report their type.
            // However, as a fallback or for future fragment types not yet covered by isPath/isVirtual,
            // log a warning and attempt a generic removal if possible, or return 'this'.
            logger.warn("Unknown fragment type encountered in removeBadFragment: {}", f.getClass().getName());
            // Attempt removal based on object equality if not a known type, though this might not be effective
            // if the fragment isn't in any of the primary lists or if equality isn't well-defined.
            // For now, returning 'this' to avoid unexpected behavior.
            return this;
        }
    }

    @NotNull
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
            boolean eligible = !(ineligibleSources.contains(codeUnit));
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

            if (eligible) {
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
        return virtualFragments.stream();
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
    public Context removeFragmentsByIds(Collection<Integer> idsToRemove) {
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
        return new Context(
                newId(),
                contextManager,
                newEditableFiles,
                newReadonlyFiles,
                newVirtualFragments,
                taskHistory,
                null,
                action
        );
    }

    public Context removeAll() {
        String action = "Dropped all context";
        return new Context(newId(),
                           contextManager,
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
    public Context addHistoryEntry(TaskEntry taskEntry, ContextFragment.TaskFragment parsed, Future<String> action) {
        var newTaskHistory = Streams.concat(taskHistory.stream(), Stream.of(taskEntry)).toList();
        return new Context(newId(),
                           contextManager,
                           editableFiles,
                           readonlyFiles,
                           virtualFragments,
                           newTaskHistory, // new task history list
                           parsed,
                           action);
    }


    public Context clearHistory() {
        return new Context(newId(),
                           contextManager,
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

    /**
     * Get the unique transient identifier for this context instance.
     */
    public int getId() {
        return id;
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

    public Context withParsedOutput(ContextFragment.TaskFragment parsedOutput, Future<String> action) {
        return new Context(newId(),
                           contextManager,
                           editableFiles,
                           readonlyFiles,
                           virtualFragments,
                           taskHistory,
                           parsedOutput,
                           action);
    }

    public Context withAction(Future<String> action) {
        return new Context(this.id, // Keep same ID as this is just updating the action
                           contextManager,
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
        return new Context(newId(),
                           contextManager,
                           editableFiles,
                           readonlyFiles,
                           virtualFragments,
                           newHistory, // Use the new history
                           null,     // parsed output
                           CompletableFuture.completedFuture("Compressed History"));
    }

    public ContextFragment.TaskFragment getParsedOutput() {
        return parsedOutput;
    }

    /**
     * Creates a new Context that copies specific elements from the provided context.
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
        return new Context(newId(), // New ID for the reset point
                           currentContext.contextManager,
                           unfrozenEditableFiles,
                           unfrozenReadonlyFiles,
                           unfrozenVirtualFragments,
                           newHistory,
                           null,
                           CompletableFuture.completedFuture("Reset context to historical state"));
    }

    /**
     * Creates a new Context by appending specified fragments from a source historical context
     * to the current context's fragments, and adopting the history from the source.
     *
     * @param sourceHistoricalContext The historical context to source fragments from.
     * @param fragmentsToKeep All fragments that the user selected to keep.
     * @param actionMessage The description of the action creating this context.
     * @return A new Context instance with appended fragments and merged history.
     */
    public Context appendFrom(Context sourceHistoricalContext,
                              List<ContextFragment> fragmentsToKeep,
                              String actionMessage) {

        List<TaskEntry> finalHistory = new ArrayList<>(getTaskHistory());
        Set<TaskEntry> existingEntries = new HashSet<>(finalHistory); // For efficient duplicate checking

        // Calculate fragmentIdsToKeep from fragmentsToKeep
        var fragmentIdsToKeep = fragmentsToKeep.stream()
                .map(ContextFragment::id)
                .collect(Collectors.toSet());

        Optional<HistoryFragment> selectedHistoryFragmentOpt = fragmentsToKeep.stream()
            .filter(HistoryFragment.class::isInstance)
            .map(HistoryFragment.class::cast)
            .filter(hf -> fragmentIdsToKeep.contains(hf.id())) // Check if this HistoryFragment itself is selected
            .findFirst();

        if (selectedHistoryFragmentOpt.isPresent()) {
            // User explicitly selected a HistoryFragment to keep; append its entries
            List<TaskEntry> entriesToAppend = selectedHistoryFragmentOpt.get().entries();
            for (TaskEntry entry : entriesToAppend) {
                if (existingEntries.add(entry)) { // .add() returns true if element was added (not already present)
                    finalHistory.add(entry);
                }
            }
        }
        // If no specific HistoryFragment was selected (matching ID in fragmentIdsToKeep),
        // finalHistory will only contain entries from currentHistory plus those from an explicitly selected HistoryFragment.

        finalHistory.sort(Comparator.comparingInt(TaskEntry::sequence));
        var newHistory = List.copyOf(finalHistory);

        var currentEditablePaths = this.editableFiles.stream()
            .filter(ContextFragment.PathFragment.class::isInstance)
            .map(f -> ((ContextFragment.PathFragment) f).file().absPath())
            .collect(Collectors.toSet());

        var currentReadonlyPaths = this.readonlyFiles.stream()
            .filter(ContextFragment.PathFragment.class::isInstance)
            .map(f -> ((ContextFragment.PathFragment) f).file().absPath())
            .collect(Collectors.toSet());

        // Start with current fragments
        var newEditableFiles = new ArrayList<>(this.editableFiles);
        var newReadonlyFiles = new ArrayList<>(this.readonlyFiles);
        var newVirtualFragments = new ArrayList<>(this.virtualFragments);

        // Process and add editable files from source
        sourceHistoricalContext.editableFiles()
            .filter(f -> fragmentIdsToKeep.contains(f.id()))
            .map(fragment -> unfreezeFragmentIfNeeded(fragment, this.contextManager))
            .forEach(unfrozen -> {
                if (unfrozen instanceof ContextFragment.PathFragment pf) {
                    // Only add if its path is not already present as editable or readonly
                    if (!currentEditablePaths.contains(pf.file().absPath()) && !currentReadonlyPaths.contains(pf.file().absPath())) {
                        newEditableFiles.add(unfrozen);
                        currentEditablePaths.add(pf.file().absPath()); // Track to prevent adding as readonly later
                    }
                } else { // Non-path based editable fragment
                    newEditableFiles.add(unfrozen);
                }
            });

        // Process and add readonly files from source
        sourceHistoricalContext.readonlyFiles()
            .filter(f -> fragmentIdsToKeep.contains(f.id()))
            .map(fragment -> unfreezeFragmentIfNeeded(fragment, this.contextManager))
            .forEach(unfrozen -> {
                if (unfrozen instanceof ContextFragment.PathFragment pf) {
                    // Only add if its path is not already present as editable or readonly
                    if (!currentEditablePaths.contains(pf.file().absPath()) && !currentReadonlyPaths.contains(pf.file().absPath())) {
                        newReadonlyFiles.add(unfrozen);
                        currentReadonlyPaths.add(pf.file().absPath());
                    }
                } else { // Non-path based readonly fragment
                    newReadonlyFiles.add(unfrozen);
                }
            });

        // Process and add virtual fragments from source
        sourceHistoricalContext.virtualFragments()
            .filter(f -> fragmentIdsToKeep.contains(f.id()))
            .map(fragment -> unfreezeFragmentIfNeeded(fragment, this.contextManager))
            .forEach(newVirtualFragments::add);

        return new Context(newId(),
                           this.contextManager,
                           List.copyOf(newEditableFiles),
                           List.copyOf(newReadonlyFiles),
                           List.copyOf(newVirtualFragments),
                           newHistory,
                           null,
                           CompletableFuture.completedFuture(actionMessage));
    }

    /**
     * Calculates the maximum ID from all fragments and task history in this context.
     * Used to ensure proper ID sequencing when deserializing contexts.
     */
    public int getMaxId() {
        var maxId = 0;

        // Check editable files
        maxId = Math.max(maxId, editableFiles.stream()
                .mapToInt(f -> f.id())
                .max()
                .orElse(0));

        // Check readonly files
        maxId = Math.max(maxId, readonlyFiles.stream()
                .mapToInt(f -> f.id())
                .max()
                .orElse(0));

        // Check virtual fragments
        maxId = Math.max(maxId, virtualFragments.stream()
                .mapToInt(f -> f.id())
                .max()
                .orElse(0));

        // Check task history
        maxId = Math.max(maxId, taskHistory.stream()
                .filter(t -> t.log() != null)
                .mapToInt(t -> t.log().id())
                .max()
                .orElse(0));

        return maxId;
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
    public FreezeResult freeze() {
        var liveEditableFiles = new ArrayList<ContextFragment>();
        var frozenEditableFiles = new ArrayList<ContextFragment>();
        var badFragments = new ArrayList<ContextFragment>();

        for (var fragment : this.editableFiles) {
            try {
                var frozen = FrozenFragment.freeze(fragment, contextManager);
                liveEditableFiles.add(fragment);
                frozenEditableFiles.add(frozen);
            } catch (IOException e) {
                logger.warn("Failed to freeze editable fragment {}: {}", fragment.description(), e.getMessage());
                badFragments.add(fragment);
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
                badFragments.add(fragment);
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
                badFragments.add(fragment);
            } catch (InterruptedException e) {
                throw new RuntimeException(e); // we should not be interrupted here
            }
        }

        // Create live context with bad fragments removed
        int newId = badFragments.isEmpty() ? newId() : this.id;
        var liveContext = new Context(newId,
                                      this.contextManager,
                                      liveEditableFiles,
                                      liveReadonlyFiles,
                                      liveVirtualFragments,
                                      this.taskHistory,
                                      this.parsedOutput,
                                      this.action);

        // Create frozen context
        var frozenContext = new Context(newId,
                                        this.contextManager,
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
     * This method is used by ContextHistory.
     *
     * @return A new Context instance with dynamic fragments frozen
     */
    public Context freezeForTesting() {
        return freeze().frozenContext;
    }

    /**
     * Helper method to unfreeze a fragment if it's a FrozenFragment, otherwise return as-is.
     * Used when restoring contexts from history to get live fragments.
     */
    @SuppressWarnings("unchecked")
    private static <T extends ContextFragment> T unfreezeFragmentIfNeeded(T fragment, IContextManager contextManager) {
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

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Context context = (Context) o;
        return id == context.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    public boolean isFrozen() {
        return allFragments().noneMatch(ContextFragment::isDynamic);
    }
}
