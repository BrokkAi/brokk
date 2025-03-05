package io.github.jbellis.brokk;

import com.google.common.collect.Streams;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.ContextFragment.PathFragment;
import io.github.jbellis.brokk.ContextFragment.VirtualFragment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages the current and previous context, along with other state like prompts and message history.
 */
public class ContextManager implements IContextManager {
    private final Logger logger = LogManager.getLogger(ContextManager.class);

    private AnalyzerWrapper analyzerWrapper;
    private Chrome io;
    private Coder coder;
    private final ExecutorService backgroundTasks = Executors.newFixedThreadPool(2);
    private final ConcurrentHashMap<Future<?>, String> activeTasks = new ConcurrentHashMap<>();

    public enum Mode {
        EDIT,
        APPLY
    }

    private Mode mode = Mode.EDIT;

    /**
     * Get the current mode
     */
    public Mode getMode() {
        return mode;
    }

    /**
     * Set the current mode
     */
    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /**
     * Get the appropriate model based on current mode
     */
    public StreamingChatLanguageModel getCurrentModel(Models models) {
        return mode == Mode.EDIT ? models.editModel() : models.applyModel();
    }

    // build command inference stored here
    private Future<BuildCommand> buildCommand;

    // Shell output that might be used by /send
    private String lastShellOutput;

    // If we have a constructed user message waiting to be sent via runSession:
    private String constructedMessage;

    private Project project;
    private static final int MAX_UNDO_DEPTH = 100;

    // We keep a history of contexts; each context has references to files, messages, etc.
    private final List<Context> contextHistory = new ArrayList<>();
    private final List<Context> redoHistory = new ArrayList<>();

    private final Path root;

    // Minimal constructor called from Brokk before calling resolveCircularReferences(...)
    public ContextManager(Path root) {
        this.root = root.toAbsolutePath();
    }

    /**
     * Called from Brokk to finish wiring up references to ConsoleIO and Coder
     */
    public void resolveCircularReferences(Chrome io, Coder coder) {
        this.io = io;
        this.coder = coder;
        this.project = new Project(root, io);
        this.analyzerWrapper = new AnalyzerWrapper(project, backgroundTasks);

        // Start with a blank context
        Context newContext = new Context(this.analyzerWrapper, 5);
        contextHistory.add(newContext);

        // First-time setup
        ensureStyleGuide();
        ensureBuildCommand(io, coder);
    }

    private void ensureBuildCommand(Chrome io, Coder coder) {
        String loadedCommand = project.getBuildCommand();
        if (loadedCommand != null) {
            buildCommand = CompletableFuture.completedFuture(BuildCommand.success(loadedCommand));
            io.llmOutput("\nUsing saved build command: " + loadedCommand);
        } else {
            // do background inference
            List<String> filenames = GitRepo.instance.getTrackedFiles().stream()
                    .map(RepoFile::toString)
                    .filter(string -> !string.contains(File.separator))
                    .collect(Collectors.toList());
            if (filenames.isEmpty()) {
                filenames = GitRepo.instance.getTrackedFiles().stream().map(RepoFile::toString).toList();
            }

            List<ChatMessage> messages = List.of(
                    new SystemMessage("You are a build assistant that suggests a single command to do a quick compile check."),
                    new UserMessage(
                            "We have these files:\n\n" + filenames
                                    + "\n\nSuggest a minimal single-line shell command to compile them incrementally, not a full build. "
                                    + "Respond with JUST the command, no commentary and no markup."
                    )
            );

            buildCommand = submitBackgroundTask("Inferring build command", () -> {
                String response;
                try {
                    response = coder.sendMessage(messages);
                } catch (Throwable th) {
                    return BuildCommand.failure(th.getMessage());
                }
                if (response.equals(Models.UNAVAILABLE)) {
                    return BuildCommand.failure(Models.UNAVAILABLE);
                }
                String inferred = response.trim();
                project.setBuildCommand(inferred);
                io.toolOutput("Inferred build command: " + inferred);
                return BuildCommand.success(inferred);
            });
        }
    }

    public Project getProject() {
        return project;
    }

    /** create a RepoFile object corresponding to a relative path String */
    @Override
    public RepoFile toFile(String relName) {
        return new RepoFile(root, relName);
    }

    /**
     * Returns the RepoFiles associated with the given fragment index for all fragments. Throws IllegalArgumentException if no such fragment
     */
    public Set<RepoFile> getFilesFromFragmentIndex(int index) {
        Context ctx = currentContext();
        var fragment = ctx.toFragment(index);
        if (fragment == null) {
            throw new IllegalArgumentException("No fragment found at position " + index);
        }

        var classnames = fragment.sources(getAnalyzer());
        var files = classnames.stream()
                .map(cu -> getAnalyzer().pathOf(cu))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No files found for fragment at position " + index);
        }
        return files;
    }

    // ------------------------------------------------------------------
    // "Business logic" methods called from Commands or elsewhere
    // ------------------------------------------------------------------

    /** Add the given files to the editable set. */
    @Override
    public void addFiles(Collection<RepoFile> files) {
        assert !files.isEmpty();
        var fragments = files.stream().map(ContextFragment.RepoPathFragment::new).toList();
        pushContext(ctx -> ctx.removeReadonlyFiles(fragments).addEditableFiles(fragments));
    }

    /**
     * Convert all current context to read-only.
     */
    public OperationResult convertAllToReadOnly() {
        pushContext(Context::convertAllToReadOnly);
        return OperationResult.success();
    }

    /** Add the given files as read-only. */
    public void addReadOnlyFiles(Collection<? extends BrokkFile> files) {
        assert !files.isEmpty();
        var fragments = files.stream().map(ContextFragment::toPathFragment).toList();
        pushContext(context -> context.removeEditableFiles(fragments).addReadonlyFiles(fragments));
    }

    /** Drop all context */
    public OperationResult dropAll() {
        pushContext(Context::removeAll);
        return OperationResult.success();
    }

    public void drop(List<PathFragment> pathFragsToRemove, List<VirtualFragment> virtualToRemove) {
        pushContext(ctx -> ctx
                .removeEditableFiles(pathFragsToRemove)
                .removeReadonlyFiles(pathFragsToRemove)
                .removeVirtualFragments(virtualToRemove));
    }

    /**
     * Removes a single fragment from the context
     */
    public void dropOne(ContextFragment frag) {
        if (frag instanceof ContextFragment.AutoContext) {
            setAutoContextFiles(0);
        } else if (frag instanceof ContextFragment.PathFragment pf) {
            pushContext(ctx -> ctx
                .removeEditableFiles(List.of(pf))
                .removeReadonlyFiles(List.of(pf)));
        } else if (frag instanceof ContextFragment.VirtualFragment vf) {
            pushContext(ctx -> ctx.removeVirtualFragments(List.of(vf)));
        }
    }

    /** Clear conversation history */
    public OperationResult clearHistory() {
        pushContext(Context::clearHistory);
        return OperationResult.success();
    }

    /** trigger a code intelligence rebuild in background. */
    public void requestRebuild() {
        GitRepo.instance.refresh();
        analyzerWrapper.requestRebuild();
    }

    /** For /undo */
    public OperationResult undoContext() {
        if (contextHistory.size() <= 1) {
            return OperationResult.error("no undo state available");
        }
        var popped = contextHistory.removeLast();
        var redoContext = undoAndInvertChanges(popped);
        redoHistory.add(redoContext);
        io.updateContextTable(currentContext());
        return OperationResult.success("Undo!");
    }

    /** For /redo */
    public OperationResult redoContext() {
        if (redoHistory.isEmpty()) {
            return OperationResult.error("no redo state available");
        }
        var popped = redoHistory.removeLast();
        var undoContext = undoAndInvertChanges(popped);
        contextHistory.add(undoContext);
        io.updateContextTable(currentContext());
        return OperationResult.success("Redo!");
    }

    /**
     * Inverts changes from a popped context to revert to prior state,
     * and returns a new context that allows re-inversion (redo).
     */
    @NotNull
    private Context undoAndInvertChanges(Context original) {
        // gather new "originalContents" for a future redo
        Map<RepoFile, String> redoContents = new HashMap<>();
        original.originalContents.forEach((file, oldText) -> {
            try {
                String current = Files.readString(file.absPath());
                redoContents.put(file, current);
            } catch (IOException e) {
                io.toolError("Failed to read current contents of " + file + ": " + e.getMessage());
            }
        });

        // restore the popped context's original contents
        var changedFiles = new ArrayList<RepoFile>();
        original.originalContents.forEach((file, oldText) -> {
            try {
                Files.writeString(file.absPath(), oldText);
                changedFiles.add(file);
            } catch (IOException e) {
                io.toolError("Failed to restore file " + file + ": " + e.getMessage());
            }
        });
        if (!changedFiles.isEmpty()) {
            io.toolOutput("Modified " + changedFiles);
        }

        return original.withOriginalContents(redoContents);
    }

    /** Store the pasted content as a read-only snippet */
    public OperationResult addPasteFragment(String pastedContent, Future<String> summaryFuture) {
        pushContext(ctx -> {
            var fragment = new ContextFragment.PasteFragment(pastedContent, summaryFuture);
            return ctx.addVirtualFragment(fragment);
        });
        return OperationResult.success("Added pasted content");
    }

    public void addSearchFragment(VirtualFragment fragment) {
        pushContext(ctx -> ctx.addVirtualFragment(fragment));
    }

    /**
     * Submits a task to the background executor and updates UI status
     * @param taskDescription Description of the task for status display
     * @param task The task to execute
     * @param <T> Return type of the task
     * @return Future representing the pending task result
     */
    public <T> Future<T> submitBackgroundTask(String taskDescription, java.util.concurrent.Callable<T> task) {
        var callable = new java.util.concurrent.Callable<T>() {
            @Override
            public T call() throws Exception {
                try {
                    updateBackgroundStatus();
                    return task.call();
                } finally {
                    activeTasks.remove(this);
                    updateBackgroundStatus();
                }
            }
        };
        
        Future<T> future = backgroundTasks.submit(callable);
        activeTasks.put(future, taskDescription);
        updateBackgroundStatus();
        return future;
    }
    
    /**
     * Updates the console UI with current background task status
     */
    private void updateBackgroundStatus() {
        if (activeTasks.isEmpty()) {
            io.spinComplete();
        } else {
            String status = activeTasks.values().stream()
                    .collect(Collectors.joining(", "));
            io.spin(status);
        }
    }

    /** Submits a background summarization for pasted content */
    public Future<String> submitSummarizeTaskForPaste(Coder coder, String pastedContent) {
        return submitBackgroundTask("Summarizing pasted content", () -> {
            // Summarize these changes in a single line:
            var msgs = List.of(
                    new UserMessage("Please summarize these changes in a single line:"),
                    new AiMessage("Ok, let's see them."),
                    new UserMessage(pastedContent)
            );
            return coder.sendMessage(msgs);
        });
    }

    /** Find usage references of the user-provided symbol and store them in context. */
    public OperationResult usageForIdentifier(String identifier) {
        List<CodeUnit> uses;
        try {
            uses = getAnalyzer().getUses(identifier);
        } catch (IllegalArgumentException e) {
            return OperationResult.error(e.getMessage());
        }
        if (uses.isEmpty()) {
            return OperationResult.success("No uses found for " + identifier);
        }

        var result = AnalyzerWrapper.processUsages(getAnalyzer(), uses);

        if (result.code().isEmpty()) {
            return OperationResult.success("No relevant uses found for " + identifier);
        }

        String combined = result.code();
        pushContext(ctx -> ctx.addUsageFragment(identifier, result.sources(), combined));
        return OperationResult.success();
    }

    /** Parse the stacktrace and add the source of in-project methods to context */
    public OperationResult parseStacktrace(String stacktraceText) {
        try {
            var stacktrace = StackTrace.parse(stacktraceText);
            if (stacktrace == null) {
                return OperationResult.error("unable to parse stacktrace");
            }

            String exception = stacktrace.getExceptionType();
            StringBuilder content = new StringBuilder();

            var sources = new HashSet<CodeUnit>();
            for (var element : stacktrace.getFrames()) {
                String methodFullName = element.getClassName() + "." + element.getMethodName();
                var methodSource = getAnalyzer().getMethodSource(methodFullName);
                if (methodSource.isDefined()) {
                    sources.add(CodeUnit.cls(ContextFragment.toClassname(methodFullName)));
                    content.append(methodFullName).append(":\n");
                    content.append(methodSource.get()).append("\n\n");
                }
            }

            if (content.isEmpty()) {
                return OperationResult.error("no relevant methods found in stacktrace");
            }

            pushContext(ctx -> {
                var fragment = new ContextFragment.StacktraceFragment(sources, stacktraceText, exception, content.toString());
                return ctx.addVirtualFragment(fragment);
            });
            return OperationResult.success();
        } catch (Exception e) {
            return OperationResult.error("Failed to parse stacktrace: " + e.getMessage());
        }
    }

    public boolean summarizeClasses(Set<CodeUnit> classes) {
        // coalesce inner classes
        var coalescedUnits = coalesceInnerClasses(classes);

        StringBuilder combined = new StringBuilder();
        List<String> shortNames = new ArrayList<>();
        for (var cu : coalescedUnits) {
            var skeleton = getAnalyzer().getSkeleton(cu.reference());
            if (skeleton.isDefined()) {
                shortNames.add(Completions.getShortClassName(cu.reference()));
                if (!combined.isEmpty()) {
                    combined.append("\n\n");
                }
                combined.append(skeleton.get());
            }
        }

        if (combined.isEmpty()) {
            return false;
        }
        pushContext(ctx -> ctx.addSkeletonFragment(shortNames, coalescedUnits, combined.toString()));
        return true;
    }

    @NotNull
    private static Set<CodeUnit> coalesceInnerClasses(Set<CodeUnit> classes) {
        return classes.stream()
                .filter(cu -> {
                    var name = cu.reference();
                    if (!name.contains("$")) {
                        return true;
                    }
                    String parent = name.substring(0, name.indexOf('$'));
                    return !classes.stream().map(CodeUnit::reference).collect(Collectors.toSet()).contains(parent);
                })
                .collect(Collectors.toSet());
    }

    /** Set the auto context size */
    public OperationResult setAutoContextFiles(int fileCount) {
        pushContext(ctx -> ctx.setAutoContextFiles(fileCount));
        return OperationResult.success("Autocontext size set to " + fileCount);
    }

    /**
     * Add to the history of user/AI messages in the current context.
     */
    public void addToHistory(List<ChatMessage> messages, Map<RepoFile, String> originalContents) {
        pushContext(ctx -> ctx.addHistory(messages, originalContents));
    }
    public void addToHistory(List<ChatMessage> messages) {
        addToHistory(messages, Map.of());
    }

    public void addStringFragment(String description, String content) {
        pushContext(context -> {
            var fragment = new ContextFragment.StringFragment(content, description);
            return context.addVirtualFragment(fragment);
        });
    }

    /**
     * Returns the current "constructedMessage" if set, then clears it. This is used in the main REPL loop to
     * immediately feed that message to the LLM instead of prompting the user again.
     */
    public String getAndResetConstructedMessage() {
        try {
            return constructedMessage;
        } finally {
            constructedMessage = null;
        }
    }

    public void setConstructedMessage(String msg) {
        this.constructedMessage = msg;
    }

    /** The "last shell output" we might want to pass to /send. */
    // TODO move this into Context?
    public String getLastShellOutput() {
        return lastShellOutput;
    }
    public void setLastShellOutput(String s) {
        this.lastShellOutput = s;
    }

    public void removeBadFragment(ContextFragment f, IOException e) {
        logger.warn("Removing unreadable fragment %s".formatted(f.description()), e);
        io.toolErrorRaw("Removing unreadable fragment %s".formatted(f.description()));
        pushContext(c -> c.removeBadFragment(f));
    }

    /**
     * Wrap text on spaces up to maxWidth; returns multiple lines if needed.
     */
    private static List<String> wrapOnSpace(String text, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        if (maxWidth <= 0) {
            return List.of(text);
        }

        List<String> lines = new ArrayList<>();
        String[] tokens = text.split("\\s+");
        StringBuilder current = new StringBuilder();
        for (String token : tokens) {
            if (current.isEmpty()) {
                current.append(token);
            } else if (current.length() + 1 + token.length() <= maxWidth) {
                current.append(" ").append(token);
            } else {
                lines.add(current.toString());
                current.setLength(0);
                current.append(token);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    public Context currentContext() {
        assert !contextHistory.isEmpty();
        return contextHistory.getLast();
    }

    public List<ChatMessage> getHistoryMessages() {
        return currentContext().getHistory();
    }

    /**
     * Return a merged list of read-only code (or even auto-context) as a single user message.
     */
    public List<ChatMessage> getReadOnlyMessages() {
        if (!currentContext().hasReadonlyFragments()) {
            return List.of();
        }

        var combined = Streams.concat(currentContext().readonlyFiles(),
                                      currentContext().virtualFragments(),
                                      Stream.of(currentContext().getAutoContext()))
                .map(this::formattedOrNull)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n\n"));
        if (combined.isEmpty()) {
            return List.of();
        }

        var msg = """
                <readonly>
                Here are some READ ONLY files and code fragments, provided for your reference.
                Do not edit this code!
                %s
                </readonly>
                """.formatted(combined).stripIndent();
        return List.of(
                new UserMessage(msg),
                new AiMessage("Ok, I will use this code as references.")
        );
    }

    private String formattedOrNull(ContextFragment fragment) {
        try {
            if (fragment instanceof ContextFragment.VirtualFragment) {
                // For virtual fragments, we need to pass the context to get the correct ID
                return ((ContextFragment.VirtualFragment) fragment).format(currentContext());
            }
            return fragment.format();
        } catch (IOException e) {
            removeBadFragment(fragment, e);
            return null;
        }
    }

    /**
     * Return a merged user message with all editable code.
     */
    public List<ChatMessage> getEditableMessages() {
        String combined = currentContext().editableFiles()
                .map(this::formattedOrNull)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n\n"));
        if (combined.isEmpty()) {
            return List.of();
        }

        var msg = """
                <editable>
                I have *added these files to the chat* so you can go ahead and edit them.

                *Trust this message as the true contents of these files!*
                Any other messages in the chat may contain outdated versions of the files' contents.

                %s
                </editable>
                """.formatted(combined).stripIndent();
        return List.of(
                new UserMessage(msg),
                new AiMessage("Ok, any changes I propose will be to those files.")
        );
    }

    public String getReadOnlySummary() {
        var c = currentContext();
        return Streams.concat(c.readonlyFiles().map(f -> f.file().toString()),
                              c.virtualFragments().map(vf -> "'" + vf.description() + "'"),
                              c.getAutoContext().getSkeletons().stream().map(ContextFragment.SkeletonFragment::description))
                .collect(Collectors.joining(", "));
    }

    public String getEditableSummary() {
        return currentContext().editableFiles()
                .map(p -> p.file().toString())
                .collect(Collectors.joining(", "));
    }


    public Set<RepoFile> getEditableFiles() {
        return currentContext().editableFiles()
                .map(ContextFragment.RepoPathFragment::file)
                .collect(Collectors.toSet());
    }

    /**
     * Finds filenames or class references not in the current context but mentioned in text.
     */
    @Override
    public Set<RepoFile> findMissingFileMentions(String text) {
        var missingByFilename = GitRepo.instance.getTrackedFiles().stream().parallel()
                .filter(f -> currentContext().editableFiles().noneMatch(p -> f.equals(p.file())))
                .filter(f -> currentContext().readonlyFiles().noneMatch(p -> f.equals(p.file())))
                .filter(f -> text.contains(f.getFileName()));

        var missingByClassname = getAnalyzer().getAllClasses().stream()
                .filter(cu -> text.contains(List.of(cu.reference().split("\\."))
                                                    .getLast())) // simple classname
                .filter(cu -> currentContext().allFragments().noneMatch(fragment ->
                                                                                fragment.sources(getAnalyzer()).contains(cu)))
                .map(cu -> getAnalyzer().pathOf(cu))
                .filter(Objects::nonNull);

        return Streams.concat(missingByFilename, missingByClassname)
                .collect(Collectors.toSet());
    }

    /**
     * Returns the main analyzer, building it if needed.
     * This will display a UI spinner for user-facing operations.
     */
    public Analyzer getAnalyzer() {
        return analyzerWrapper.get();
    }

    public Path getRoot() {
        return root;
    }

    /** Internal push context logic */
    private void pushContext(Function<Context, Context> contextGenerator) {
        var newContext = contextGenerator.apply(currentContext());
        if (newContext != currentContext()) {
            contextHistory.add(newContext);
            if (contextHistory.size() > MAX_UNDO_DEPTH) {
                contextHistory.removeFirst();
            }

            redoHistory.clear();
            io.updateContextTable(newContext);
        }
    }

    private record BuildCommand(String command, String message) {
        static BuildCommand success(String cmd) {
            return new BuildCommand(cmd, cmd);
        }

        static BuildCommand failure(String message) {
            return new BuildCommand(null, message);
        }
    }

    /**
     * Ensures a style guide exists, generating one if needed
     */
    private void ensureStyleGuide() {
        if (project.getStyleGuide() != null) {
            return;
        }

        submitBackgroundTask("Generating style guide", () -> {
            try {
                io.toolOutput("Generating project style guide...");

                // Get top classes by pagerank
                var analyzer = analyzerWrapper.getForBackground();
                var topClasses = AnalyzerWrapper.combinedPageRankFor(analyzer, Map.of());

                // Get source code for these classes
                StringBuilder codeForLLM = new StringBuilder();
                int tokens = 0;
                for (var fqcn : topClasses) {
                    var path = analyzer.pathOf(CodeUnit.cls(fqcn));
                    if (path == null) {
                        continue;
                    }
                    String chunk;
                    try {
                        chunk = "<file path=%s>\n%s\n</file>\n".formatted(path, path.read());
                    } catch (IOException e) {
                        logger.error("Failed to read {}: {}", path, e.getMessage());
                        continue;
                    }
                    int chunkTokens = Models.getApproximateTokens(chunk);
                    if (tokens + chunkTokens > 50000) {
                        // don't quit until we find at least one class under 50k
                        if (tokens > 0) {
                            break;
                        }
                    }
                    codeForLLM.append(chunk);
                    tokens += chunkTokens;
                }

                if (codeForLLM.isEmpty()) {
                    io.toolOutput("No relevant code found for style guide generation");
                    return Boolean.FALSE;
                }

                // Generate style guide using LLM
                List<ChatMessage> messages = List.of(
                        new SystemMessage("You are an expert software engineer. Your task is to extract a concise coding style guide from the provided code examples."),
                        new UserMessage("""
                        Based on these code examples, create a concise, clear coding style guide in Markdown format
                        that captures the conventions used in this codebase, particularly the ones that leverage new or uncommon features.
                        DO NOT repeat what are simply common best practices.

                        %s
                        """.stripIndent().formatted(codeForLLM))
                );

                String styleGuide = coder.sendMessage(messages);
                if (styleGuide.equals(Models.UNAVAILABLE)) {
                    io.toolOutput("Failed to generate style guide: LLM unavailable");
                    return Boolean.FALSE;
                }

                project.saveStyleGuide(styleGuide);
                io.toolOutput("Style guide generated and saved to .brokk/style.md");
                return Boolean.TRUE;
            } catch (Exception e) {
                logger.error("Error generating style guide", e);
            }
            // Return a dummy value since we're not using the result
            return null;
        });
    }

    // ------------------------------------------------------------------
    // OperationResult used by all commands
    // ------------------------------------------------------------------
    public enum OperationStatus {
        SUCCESS,
        SKIP_SHOW,
        PREFILL,
        ERROR
    }

    public record OperationResult(OperationStatus status, String message) {
        public static OperationResult prefill(String msg) {
            return new OperationResult(OperationStatus.PREFILL, msg);
        }
        public static OperationResult success() {
            return new OperationResult(OperationStatus.SUCCESS, null);
        }
        public static OperationResult success(String msg) {
            return new OperationResult(OperationStatus.SUCCESS, msg);
        }
        public static OperationResult skipShow() {
            return new OperationResult(OperationStatus.SKIP_SHOW, null);
        }
        public static OperationResult error(String msg) {
            return new OperationResult(OperationStatus.ERROR, msg);
        }
    }
}
