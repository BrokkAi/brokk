package ai.brokk.context;

import ai.brokk.AbstractProject;
import ai.brokk.Completions;
import ai.brokk.IContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment.HistoryFragment;
import ai.brokk.git.GitDistance;
import ai.brokk.git.GitRepo;
import ai.brokk.git.IGitRepo;
import ai.brokk.gui.ActivityTableRenderers;
import ai.brokk.tools.WorkspaceTools;
import ai.brokk.util.ContentDiffUtils;
import ai.brokk.util.HtmlToMarkdown;
import ai.brokk.util.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.f4b6a3.uuid.UuidCreator;
import com.google.common.collect.Streams;
import dev.langchain4j.data.message.ChatMessageType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
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
    private static final Duration SNAPSHOT_AWAIT_TIMEOUT = Duration.ofSeconds(5);

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

    @Nullable
    private final UUID groupId;

    @Nullable
    private final String groupLabel;

    /** Constructor for initial empty context */
    public Context(IContextManager contextManager, @Nullable String initialOutputText) {
        this(
                newContextId(),
                contextManager,
                List.of(),
                List.of(),
                null,
                CompletableFuture.completedFuture(WELCOME_ACTION),
                null,
                null);
    }

    private Context(
            UUID id,
            IContextManager contextManager,
            List<ContextFragment> fragments,
            List<TaskEntry> taskHistory,
            @Nullable ContextFragment.TaskFragment parsedOutput,
            Future<String> action,
            @Nullable UUID groupId,
            @Nullable String groupLabel) {
        this.id = id;
        this.contextManager = contextManager;
        this.fragments = List.copyOf(fragments);
        this.taskHistory = List.copyOf(taskHistory);
        this.action = action;
        this.parsedOutput = parsedOutput;
        this.groupId = groupId;
        this.groupLabel = groupLabel;
    }

    public Context(
            IContextManager contextManager,
            List<ContextFragment> fragments,
            List<TaskEntry> taskHistory,
            @Nullable ContextFragment.TaskFragment parsedOutput,
            Future<String> action) {
        this(newContextId(), contextManager, fragments, taskHistory, parsedOutput, action, null, null);
    }

    public Map<CodeUnit, String> buildRelatedIdentifiers(int k) throws InterruptedException {
        var candidates = getMostRelevantFiles(k).stream().sorted().toList();
        return buildRelatedIdentifiers(contextManager.getAnalyzer(), candidates);
    }

    public static Map<CodeUnit, String> buildRelatedIdentifiers(IAnalyzer analyzer, List<ProjectFile> candidates) {
        return candidates.parallelStream()
                .flatMap(c -> analyzer.getTopLevelDeclarations(c).stream())
                .collect(Collectors.toMap(cu -> cu, cu -> analyzer.getSubDeclarations(cu).stream()
                        .map(CodeUnit::shortName)
                        .distinct()
                        .sorted()
                        .collect(Collectors.joining(", "))));
    }

    /** Per-fragment diff entry between two contexts. */
    public record DiffEntry(
            ContextFragment fragment,
            String diff,
            int linesAdded,
            int linesDeleted,
            String oldContent,
            String newContent) {}


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
            // Deduplicate using hasSameSource for semantic equivalence
            boolean isDuplicate = existingVirtuals.stream().anyMatch(vf -> vf.hasSameSource(fragment))
                    || newFragments.stream()
                            .filter(f -> f.getType().isVirtual())
                            .map(f -> (ContextFragment.VirtualFragment) f)
                            .anyMatch(vf -> vf.hasSameSource(fragment));

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
        return new Context(
                newContextId(), contextManager, newFragments, taskHistory, null, action, this.groupId, this.groupLabel);
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

    @Nullable
    public UUID getGroupId() {
        return groupId;
    }

    @Nullable
    public String getGroupLabel() {
        return groupLabel;
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
        return new Context(
                newContextId(),
                contextManager,
                List.of(),
                List.of(),
                null,
                CompletableFuture.completedFuture(action),
                this.groupId,
                this.groupLabel);
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
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                newTaskHistory,
                parsed,
                action,
                this.groupId,
                this.groupLabel);
    }

    public Context clearHistory() {
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                List.of(),
                null,
                CompletableFuture.completedFuture(ActivityTableRenderers.CLEARED_TASK_HISTORY),
                this.groupId,
                this.groupLabel);
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
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                taskHistory,
                parsedOutput,
                action,
                this.groupId,
                this.groupLabel);
    }

    public Context withParsedOutput(@Nullable ContextFragment.TaskFragment parsedOutput, String action) {
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                taskHistory,
                parsedOutput,
                CompletableFuture.completedFuture(action),
                this.groupId,
                this.groupLabel);
    }

    public Context withAction(Future<String> action) {
        return new Context(
                newContextId(),
                contextManager,
                fragments,
                taskHistory,
                parsedOutput,
                action,
                this.groupId,
                this.groupLabel);
    }

    public Context withGroup(@Nullable UUID groupId, @Nullable String groupLabel) {
        return new Context(
                newContextId(), contextManager, fragments, taskHistory, parsedOutput, action, groupId, groupLabel);
    }

    public static Context createWithId(
            UUID id,
            IContextManager cm,
            List<ContextFragment> fragments,
            List<TaskEntry> history,
            @Nullable ContextFragment.TaskFragment parsed,
            Future<String> action) {
        return createWithId(id, cm, fragments, history, parsed, action, null, null);
    }

    public static Context createWithId(
            UUID id,
            IContextManager cm,
            List<ContextFragment> fragments,
            List<TaskEntry> history,
            @Nullable ContextFragment.TaskFragment parsed,
            Future<String> action,
            @Nullable UUID groupId,
            @Nullable String groupLabel) {
        return new Context(id, cm, fragments, history, parsed, action, groupId, groupLabel);
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
                CompletableFuture.completedFuture("Compress History"),
                this.groupId,
                this.groupLabel);
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
                sourceContext.getGroupLabel());
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
     * Merges this context with another context, combining their fragments while avoiding duplicates.
     * Fragments from {@code other} that are not present in this context are added.
     * File fragments are deduplicated by their source file; virtual fragments by their id().
     * Task history and parsed output from this context are preserved.
     *
     * @param other the context to merge with
     * @return a new context containing the union of fragments from both contexts
     */
    public Context union(Context other) {
        // we're going to do some casting that's not valid if FF is involved
        assert !containsFrozenFragments();
        assert !other.containsFrozenFragments();

        if (this.fragments.isEmpty()) {
            return other;
        }

        var combined = addPathFragments(other.fileFragments()
                .map(cf -> (ContextFragment.PathFragment) cf)
                .toList());
        combined = combined.addVirtualFragments(other.virtualFragments().toList());
        return combined;
    }

    /**
     * Adds class definitions (CodeFragments) to the context for the given FQCNs.
     * Skips classes whose source files are already in the workspace as ProjectPathFragments.
     *
     * @param context the current context
     * @param classNames fully qualified class names to add
     * @param analyzer the code analyzer
     * @return a new context with the added class fragments
     */
    public static Context withAddedClasses(Context context, List<String> classNames, IAnalyzer analyzer) {
        if (classNames.isEmpty()) {
            return context;
        }

        var liveContext = context;
        var workspaceFiles = liveContext
                .fileFragments()
                .filter(f -> f instanceof ContextFragment.ProjectPathFragment)
                .map(f -> (ContextFragment.ProjectPathFragment) f)
                .map(ContextFragment.ProjectPathFragment::file)
                .collect(Collectors.toSet());

        var toAdd = new ArrayList<ContextFragment.VirtualFragment>();
        for (String className : classNames.stream().distinct().toList()) {
            if (className.isBlank()) {
                continue;
            }
            var defOpt = analyzer.getDefinition(className);
            if (defOpt.isPresent()) {
                var codeUnit = defOpt.get();
                // Skip if the source file is already in workspace as a ProjectPathFragment
                if (!workspaceFiles.contains(codeUnit.source())) {
                    toAdd.add(new ContextFragment.CodeFragment(context.contextManager, codeUnit));
                }
            } else {
                logger.warn("Could not find definition for class: {}", className);
            }
        }

        return toAdd.isEmpty() ? context : liveContext.addVirtualFragments(toAdd);
    }

    /**
     * Adds class summary fragments (SkeletonFragments) for the given FQCNs.
     *
     * @param context the current context
     * @param classNames fully qualified class names to summarize
     * @return a new context with the added summary fragments
     */
    public static Context withAddedClassSummaries(Context context, List<String> classNames) {
        if (classNames.isEmpty()) {
            return context;
        }

        var toAdd = new ArrayList<ContextFragment.VirtualFragment>();
        for (String name : classNames.stream().distinct().toList()) {
            if (name.isBlank()) {
                continue;
            }
            toAdd.add(new ContextFragment.SummaryFragment(
                    context.contextManager, name, ContextFragment.SummaryType.CODEUNIT_SKELETON));
        }

        return toAdd.isEmpty() ? context : context.addVirtualFragments(toAdd);
    }

    /**
     * Adds file summary fragments for all classes in the given file paths (with glob support).
     *
     * @param context the current context
     * @param filePaths file paths relative to project root; supports glob patterns
     * @param project the project for path resolution
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

        var toAdd = new ArrayList<ContextFragment.VirtualFragment>();
        for (String path : resolvedFilePaths) {
            toAdd.add(new ContextFragment.SummaryFragment(
                    context.contextManager, path, ContextFragment.SummaryType.FILE_SKELETONS));
        }

        return context.addVirtualFragments(toAdd);
    }

    /**
     * Adds method source code fragments for the given FQ method names.
     * Skips methods whose source files are already in the workspace.
     *
     * @param context the current context
     * @param methodNames fully qualified method names to add sources for
     * @param analyzer the code analyzer
     * @return a new context with the added method fragments
     */
    public static Context withAddedMethodSources(Context context, List<String> methodNames, IAnalyzer analyzer) {
        if (methodNames.isEmpty()) {
            return context;
        }

        var liveContext = context;
        var workspaceFiles = liveContext
                .fileFragments()
                .filter(f -> f instanceof ContextFragment.ProjectPathFragment)
                .map(f -> (ContextFragment.ProjectPathFragment) f)
                .map(ContextFragment.ProjectPathFragment::file)
                .collect(Collectors.toSet());

        var toAdd = new ArrayList<ContextFragment.VirtualFragment>();
        for (String methodName : methodNames.stream().distinct().toList()) {
            if (methodName.isBlank()) {
                continue;
            }
            var cuOpt = analyzer.getDefinition(methodName);
            if (cuOpt.isPresent() && cuOpt.get().isFunction()) {
                var codeUnit = cuOpt.get();
                // Skip if the source file is already in workspace as a ProjectPathFragment
                if (!workspaceFiles.contains(codeUnit.source())) {
                    toAdd.add(new ContextFragment.CodeFragment(context.contextManager, codeUnit));
                }
            } else {
                logger.warn("Could not find method definition for: {}", methodName);
            }
        }

        return toAdd.isEmpty() ? context : liveContext.addVirtualFragments(toAdd);
    }

    /**
     * Adds a URL content fragment to the context by fetching and converting to Markdown.
     *
     * @param context the current context
     * @param urlString the URL to fetch
     * @return a new context with the added URL fragment
     * @throws IOException if fetching or processing fails
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

        var fragment = new ContextFragment.StringFragment(
                context.contextManager, content, "Content from " + urlString, SyntaxConstants.SYNTAX_STYLE_NONE);
        return context.addVirtualFragment(fragment);
    }

    /**
     * Returns the processed output text from the latest build failure fragment in this Context. Empty string if there
     * is no build failure recorded.
     */
    public String getBuildError() {
        return getBuildFragment().map(ContextFragment.VirtualFragment::text).orElse("");
    }

    public Optional<ContextFragment.StringFragment> getBuildFragment() {
        var desc = ContextFragment.BUILD_RESULTS.description();
        return virtualFragments()
                .filter(f -> f instanceof ContextFragment.StringFragment sf && desc.equals(sf.description()))
                .map(ContextFragment.StringFragment.class::cast)
                .findFirst();
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
                CompletableFuture.completedFuture("Build results updated (failure)"),
                afterClear.getGroupId(),
                afterClear.getGroupLabel());
    }

    /**
     * Create a new Context reflecting external file changes.
     * - Unchanged fragments are reused.
     * - For DynamicFragments whose files() intersect 'changed', call refreshCopy() to get a new instance
     *   with cleared ComputedValues (id is preserved).
     * - Paste fragments (text/image) are always refreshed when 'changed' is non-empty, to clear/re-kick their
     *   ComputedValues even though they may not reference files directly.
     * - Preserves taskHistory and parsedOutput; sets action to "Load external changes".
     * - If 'changed' is empty, returns this.
     */
    public Context copyAndRefresh(Set<ProjectFile> changed) {
        if (changed.isEmpty()) {
            return this;
        }

        boolean anyDynamicPresent = fragments.stream().anyMatch(ContextFragment::isDynamic);
        var newFragments = new ArrayList<ContextFragment>(fragments.size());
        boolean anyReplaced = false;

        for (var f : fragments) {
            if (f instanceof ContextFragment.ComputedFragment df) {
                // Refresh dynamic fragments whose referenced files intersect the changed set
                if (!Collections.disjoint(f.files(), changed)) {
                    var refreshed = df.refreshCopy();
                    newFragments.add(refreshed);
                    if (refreshed != f) {
                        anyReplaced = true;
                    }
                    continue;
                }
            }

            // Default: reuse as-is
            newFragments.add(f);
        }

        // Create a new Context if any fragment changed, or if we contain dynamic fragments, or parsed output is
        // present.
        boolean mustCreateNew = anyReplaced || anyDynamicPresent || parsedOutput != null;

        if (!mustCreateNew && newFragments.equals(fragments)) {
            // No dynamic content to update; keep original Context
            return this;
        }

        return new Context(
                newContextId(),
                contextManager,
                newFragments,
                taskHistory,
                parsedOutput,
                CompletableFuture.completedFuture("Load external changes"),
                this.groupId,
                this.groupLabel);
    }

    private boolean isNewFileInGitAndText(ContextFragment fragment) {
        if (fragment.getType() != ContextFragment.FragmentType.PROJECT_PATH) {
            return false;
        }
        if (!fragment.isText()) {
            return false;
        }
        var files = fragment.files();
        if (files.isEmpty()) {
            return false;
        }
        IGitRepo repo = contextManager.getRepo();
        return !repo.getTrackedFiles().contains(files.iterator().next());
    }

    /**
     * Compute per-fragment diffs between this (right/new) and the other (left/old) context. Results are cached per other.id().
     * Accepts both live and frozen contexts. Live contexts should have had ensureFilesSnapshot() pre-called to materialize
     * computed values for dynamic fragments.
     */
    public List<DiffEntry> getDiff(Context other) {
        var cached = diffCache.get(other.id()); // cache should key on "other.id()", not this.id()
        if (cached != null) {
            return cached;
        }

        var diffs = fragments.stream()
                .map(cf -> computeDiffForFragment(cf, other))
                .filter(Objects::nonNull)
                .toList();

        diffCache.put(other.id(), diffs);
        return diffs;
    }

    /**
     * Helper method to compute diff for a single fragment against the other context.
     * Handles ComputedFragment and non-dynamic virtual fragments appropriately.
     * Live fragments are stored directly in DiffEntry without freezing.
     */
    private @Nullable DiffEntry computeDiffForFragment(ContextFragment thisFragment, Context other) {
        // Find matching fragment in 'other' context using universal matching semantics
        var otherFragment = other.fragments.stream()
                .filter(thisFragment::hasSameSource)
                .findFirst()
                .orElse(null);

        if (otherFragment == null) {
            // No matching fragment in 'other'; if this represents a new, untracked file in Git, diff against empty
            if (isNewFileInGitAndText(thisFragment)) {
                var newContent = extractFragmentContent(thisFragment, true);
                var result = ContentDiffUtils.computeDiffResult(
                        "",
                        newContent,
                        "old/" + thisFragment.shortDescription(),
                        "new/" + thisFragment.shortDescription());
                if (result.diff().isEmpty()) {
                    return null;
                }
                return new DiffEntry(thisFragment, result.diff(), result.added(), result.deleted(), "", newContent);
            }
            return null;
        }

        // Extract content from both fragments
        String oldContent = extractFragmentContent(otherFragment, false);
        String newContent = extractFragmentContent(thisFragment, true);

        // For image fragments, handle specially
        if (!thisFragment.isText() || !otherFragment.isText()) {
            return computeImageDiffEntry(thisFragment, otherFragment, oldContent, newContent);
        }

        int oldLineCount = oldContent.isEmpty() ? 0 : (int) oldContent.lines().count();
        int newLineCount = newContent.isEmpty() ? 0 : (int) newContent.lines().count();
        logger.trace(
                "getDiff: fragment='{}' id={} oldLines={} newLines={}",
                thisFragment.shortDescription(),
                id,
                oldLineCount,
                newLineCount);

        var result = ContentDiffUtils.computeDiffResult(
                oldContent,
                newContent,
                "old/" + thisFragment.shortDescription(),
                "new/" + thisFragment.shortDescription());

        logger.trace(
                "getDiff: fragment='{}' added={} deleted={} diffEmpty={}",
                thisFragment.shortDescription(),
                result.added(),
                result.deleted(),
                result.diff().isEmpty());

        if (result.diff().isEmpty()) {
            return null;
        }

        // Store live fragment directly in DiffEntry
        return new DiffEntry(thisFragment, result.diff(), result.added(), result.deleted(), oldContent, newContent);
    }

    /**
     * Extract text content from a fragment, handling FrozenFragment, ComputedFragment, and non-dynamic fragments.
     * Uses appropriate timeouts and fallback logic to avoid blocking the UI.
     */
    private String extractFragmentContent(ContextFragment fragment, boolean isNew) {
        try {
            // FrozenFragment: use in-memory getter
            if (fragment instanceof FrozenFragment ff) {
                return ff.text();
            }

            // ComputedFragment: try non-blocking access first, fall back to bounded await
            if (fragment instanceof ContextFragment.ComputedFragment cf) {
                var tryGetResult = cf.computedText().tryGet();
                if (tryGetResult.isPresent()) {
                    return tryGetResult.get();
                }

                // Fall back to bounded await with appropriate timeout based on fragment type
                Duration timeout = getTimeoutForFragmentType(fragment);
                var awaitResult = cf.computedText().await(timeout);
                if (awaitResult.isPresent()) {
                    return awaitResult.get();
                } else {
                    logger.warn(
                            "Timeout or cancelled waiting for computed text of {} fragment '{}' (timeout={}ms); continuing with empty content. "
                                    + "Fragment may not have been pre-warmed by ensureFilesSnapshot().",
                            fragment.getClass().getSimpleName(),
                            fragment.shortDescription(),
                            timeout.toMillis());
                    return "";
                }
            }

            // Non-dynamic virtual fragments: use text() directly (non-blocking)
            return fragment.text();
        } catch (java.util.concurrent.CancellationException e) {
            logger.warn(
                    "Computation cancelled for {} fragment '{}'; continuing with empty content. Cause: {}",
                    fragment.getClass().getSimpleName(),
                    fragment.shortDescription(),
                    e.getMessage());
            return "";
        } catch (UncheckedIOException e) {
            logger.warn(
                    "IO error reading content for {} fragment '{}' ({}); skipping from diff. Cause: {}",
                    fragment.getClass().getSimpleName(),
                    fragment.shortDescription(),
                    isNew ? "new" : "old",
                    e.getMessage());
            return "";
        } catch (Exception e) {
            logger.error(
                    "Unexpected error extracting content for {} fragment '{}': {}",
                    fragment.getClass().getSimpleName(),
                    fragment.shortDescription(),
                    e.getMessage(),
                    e);
            return "";
        }
    }

    /**
     * Determine the appropriate timeout for a fragment type.
     * Most fragments use SNAPSHOT_AWAIT_TIMEOUT; expensive operations like Usage and CallGraph use longer timeout.
     */
    private Duration getTimeoutForFragmentType(ContextFragment fragment) {
        if (fragment instanceof ContextFragment.UsageFragment
                || fragment instanceof ContextFragment.CallGraphFragment) {
            return Duration.ofMinutes(1);
        }
        return SNAPSHOT_AWAIT_TIMEOUT;
    }

    /**
     * Extract image bytes from a fragment, handling FrozenFragment and ComputedFragment.
     */
    private byte @Nullable [] extractImageBytes(ContextFragment fragment) {
        try {
            if (fragment instanceof FrozenFragment ff && !ff.isText()) {
                return ff.imageBytesContent();
            }

            if (fragment instanceof ContextFragment.ImageFragment imgFrag
                    && fragment instanceof ContextFragment.ComputedFragment cf) {
                var computedImageBytes = cf.computedImageBytes();
                if (computedImageBytes != null) {
                    var tryGetResult = computedImageBytes.tryGet();
                    if (tryGetResult.isPresent()) {
                        return tryGetResult.get();
                    }

                    // Fall back to bounded await
                    var awaitImageResult = computedImageBytes.await(SNAPSHOT_AWAIT_TIMEOUT);
                    if (awaitImageResult.isPresent()) {
                        return awaitImageResult.get();
                    } else {
                        logger.warn(
                                "Timeout or cancelled waiting for computed image bytes of fragment '{}' (timeout={}ms); image will show as changed.",
                                fragment.shortDescription(),
                                SNAPSHOT_AWAIT_TIMEOUT.toMillis());
                        return null;
                    }
                }
            }

            // Try to read via image() method if available
            if (fragment instanceof ContextFragment.ImageFragment imgFrag) {
                var image = imgFrag.image();
                return FrozenFragment.imageToBytes(image);
            }
        } catch (java.util.concurrent.CancellationException e) {
            logger.warn(
                    "Computation cancelled for image fragment '{}'; image will show as changed. Cause: {}",
                    fragment.shortDescription(),
                    e.getMessage());
            return null;
        } catch (UncheckedIOException e) {
            logger.warn(
                    "IO error reading image for fragment '{}'; image will show as changed. Cause: {}",
                    fragment.shortDescription(),
                    e.getMessage());
            return null;
        } catch (Exception e) {
            logger.error(
                    "Unexpected error extracting image bytes for fragment '{}': {}",
                    fragment.shortDescription(),
                    e.getMessage(),
                    e);
            return null;
        }

        return null;
    }

    /**
     * Compute diff entry for image fragments. Shows "[image changed]" as diff if either side unavailable.
     */
    private @Nullable DiffEntry computeImageDiffEntry(
            ContextFragment thisFragment,
            ContextFragment otherFragment,
            String oldContentPlaceholder,
            String newContentPlaceholder) {
        byte[] oldImageBytes = extractImageBytes(otherFragment);
        byte[] newImageBytes = extractImageBytes(thisFragment);

        // If both images are unavailable or identical, skip diff
        if (oldImageBytes == null && newImageBytes == null) {
            return null;
        }

        boolean imagesEqual = false;
        if (oldImageBytes != null && newImageBytes != null) {
            imagesEqual = java.util.Arrays.equals(oldImageBytes, newImageBytes);
        }

        if (imagesEqual) {
            return null;
        }

        // Generate placeholder diff showing image changed
        String diff = "[Image changed]";

        return new DiffEntry(
                thisFragment,
                diff,
                1, // 1 line "added" (image changed)
                1, // 1 line "deleted" (image changed)
                oldImageBytes != null ? "[image]" : "",
                newImageBytes != null ? "[image]" : "");
    }

    /**
     * Compute the set of ProjectFile objects that differ between this (new/right) context and {@code other} (old/left).
     * This is a convenience wrapper around {@link #getDiff(Context)} which returns per-fragment diffs.
     *
     * Note: Both contexts should be frozen (no dynamic fragments) for reliable results.
     */
    public java.util.Set<ProjectFile> getChangedFiles(Context other) {
        var diffs = this.getDiff(other);
        return diffs.stream().flatMap(de -> de.fragment.files().stream()).collect(Collectors.toSet());
    }
}
