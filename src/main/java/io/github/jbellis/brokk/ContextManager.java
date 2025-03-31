package io.github.jbellis.brokk;

import com.google.common.collect.Streams;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.Context.ParsedOutput;
import io.github.jbellis.brokk.ContextFragment.PathFragment;
import io.github.jbellis.brokk.ContextFragment.VirtualFragment;
import io.github.jbellis.brokk.ContextHistory.UndoResult;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.CallSite;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.CodeUnitType;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.CallGraphDialog;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.LoggingExecutorService;
import io.github.jbellis.brokk.gui.MultiFileSelectionDialog;
import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.gui.SymbolSelectionDialog;
import io.github.jbellis.brokk.prompts.ArchitectPrompts;
import io.github.jbellis.brokk.prompts.AskPrompts;
import io.github.jbellis.brokk.prompts.CommitPrompts;
import io.github.jbellis.brokk.prompts.SummarizerPrompts;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.HtmlToMarkdown;
import io.github.jbellis.brokk.util.StackTrace;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages the current and previous context, along with other state like prompts and message history.
 *
 * Updated to:
 *   - Remove OperationResult,
 *   - Move UI business logic from Chrome to here as asynchronous tasks,
 *   - Directly call into Chrome’s UI methods from background tasks (via invokeLater),
 *   - Provide separate async methods for “Go”, “Ask”, “Search”, context additions, etc.
 */
public class ContextManager implements IContextManager, AutoCloseable {
    private final Logger logger = LogManager.getLogger(ContextManager.class);

    private Chrome io; // for UI feedback
    private Coder coder;

    // Run main user-driven tasks in background (Code/Ask/Search/Run)
    // Only one of these can run at a time
    private final ExecutorService userActionExecutor = createLoggingExecutorService(Executors.newSingleThreadExecutor());
    private final AtomicReference<Thread> userActionThread = new AtomicReference<>();

    @NotNull
    private LoggingExecutorService createLoggingExecutorService(ExecutorService toWrap) {
        return new LoggingExecutorService(toWrap, th -> {
            var thread = Thread.currentThread();
            logger.error("Uncaught exception in thread {}", thread.getName(), th);
            if (io != null) {
                io.systemOutput("Uncaught exception in thread %s. This shouldn't happen, please report a bug!\n%s"
                                       .formatted(thread.getName(), getStackTraceAsString(th)));
            }
        });
    }

    // Context modification tasks (Edit/Read/Summarize/Drop/etc)
    // Multiple of these can run concurrently
    private final ExecutorService contextActionExecutor = createLoggingExecutorService(
            new ThreadPoolExecutor(2, 2,
                                   60L, TimeUnit.SECONDS,
                                   new LinkedBlockingQueue<>(), // Unbounded queue
                                   Executors.defaultThreadFactory()));

    // Internal background tasks (unrelated to user actions)
    // Lots of threads allowed since AutoContext updates get dropped here
    // Use unbounded queue to prevent task rejection
    private final ExecutorService backgroundTasks = createLoggingExecutorService(
            new ThreadPoolExecutor(3, 12,
                                   60L, TimeUnit.SECONDS,
                                   new LinkedBlockingQueue<>(), // Unbounded queue to prevent rejection
                                   Executors.defaultThreadFactory()));

    private Project project;
    private final Path root;

    private Mode mode = Mode.EDIT;

    public Coder getCoder() {
        return coder;
    }

    public enum Mode { EDIT}

    // Context history for undo/redo functionality
    private final ContextHistory contextHistory;

    /**
     * Minimal constructor called from Brokk
     */
    public ContextManager(Path root)
    {
        this.root = root.toAbsolutePath().normalize();
        this.contextHistory = new ContextHistory();
        userActionExecutor.submit(() -> {
            userActionThread.set(Thread.currentThread());
        });
    }

    /**
     * Called from Brokk to finish wiring up references to Chrome and Coder
     */
    public void resolveCircularReferences(Chrome chrome, Coder coder)
    {
        this.io = chrome;
        this.coder = coder;
        // Set up the listener for analyzer events
        var analyzerListener = new AnalyzerListener() {
            @Override
            public void onBlocked() {
                if (Thread.currentThread() == userActionThread.get()) {
                    SwingUtilities.invokeLater(() -> io.actionOutput("Waiting for Code Intelligence"));
                }
            }

            @Override
            public void afterFirstBuild(String msg) {
                if (msg.isEmpty()) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(
                            io.getFrame(),
                            "Code Intelligence is empty. Probably this means your language is not yet supported. File-based tools will continue to work.",
                            "Code Intelligence Warning",
                            JOptionPane.WARNING_MESSAGE
                        );
                    });
                } else {
                    SwingUtilities.invokeLater(() -> io.systemOutput(msg));
                }
            }

            @Override
            public void onRepoChange() {
                project.getRepo().refresh();
                io.updateGitRepo();
            }

            @Override
            public void onTrackedFileChange() {
                io.updateCommitPanel();
            }
        };
        this.project = new Project(root, this::submitBackgroundTask, analyzerListener);

        // Context's analyzer reference is retained for the whole chain so wait until we have that ready
        // before adding the Context sentinel to history
        // Load saved context or create a new one if none exists
        var welcomeMessage = buildWelcomeMessage(coder.models);
        var initialContext = project.loadContext(this, welcomeMessage);
        if (initialContext == null) {
            initialContext = new Context(this, 10, welcomeMessage);
        } else {
            // Not sure why this is necessary -- for some reason AutoContext doesn't survive deserialization
            initialContext = initialContext.refresh();
        }
        contextHistory.setInitialContext(initialContext);
        chrome.updateContextHistoryTable(initialContext);

        ensureStyleGuide();
        ensureBuildCommand(coder);
    }

    @Override
    public void replaceContext(Context context, Context replacement) {
        contextHistory.replaceContext(context, replacement);
        io.updateContextHistoryTable();
        io.updateContextTable();
    }

    /**
     * Return the streaming chat model used for the current mode
     */
    public StreamingChatLanguageModel getCurrentModel(Models models)
    {
        return (mode == Mode.EDIT) ? models.editModel() : models.applyModel();
    }

    public Project getProject()
    {
        return project;
    }

    @Override
    public ProjectFile toFile(String relName)
    {
        return new ProjectFile(root, relName);
    }

    /**
     * Return the top context in the history stack
     */
    public Context topContext()
    {
        return contextHistory.topContext();
    }

    /**
     * Return the currently selected context in the UI, or the top context if none selected
     */
    public Context selectedContext()
    {
        return contextHistory.getSelectedContext();
    }

    public Path getRoot()
    {
        return root;
    }

    public Future<?> runRunCommandAsync(String input)
    {
        assert io != null;
        return submitUserTask("Executing: " + input, () -> {
            var result = Environment.instance.captureShellCommand(input);
            String output = result.output().isBlank() ? "[operation completed with no output]" : result.output();
            io.llmOutput("\n```\n" + output + "\n```");

            var llmOutputText = io.getLlmOutputText();
            if (llmOutputText == null) {
                io.systemOutput("Interrupted!");
                return;
            }

            // Add to context history with the output text
            pushContext(ctx -> {
                var runFrag = new ContextFragment.StringFragment(output, "Run " + input);
                var parsed = new ParsedOutput(llmOutputText, runFrag);
                return ctx.withParsedOutput(parsed, CompletableFuture.completedFuture("Run " + input));
            });
        });
    }

    public Future<?> submitUserTask(String description, Runnable task) {
        return userActionExecutor.submit(() -> {
            try {
                io.actionOutput(description);
                task.run();
            } catch (CancellationException cex) {
                io.systemOutput(description + " canceled.");
            } catch (Exception e) {
                logger.error("Error in " + description, e);
                io.toolErrorRaw("Error in " + description + ": " + e.getMessage());
            } finally {
                io.actionComplete();
                io.enableUserActionButtons();
            }
        });
    }

    public Future<?> submitContextTask(String description, Runnable task) {
        return contextActionExecutor.submit(() -> {
            try {
                task.run();
            } catch (CancellationException cex) {
                io.systemOutput(description + " canceled.");
            } catch (Exception e) {
                logger.error("Error in " + description, e);
                io.toolErrorRaw("Error in " + description + ": " + e.getMessage());
            } finally {
                io.enableContextActionButtons();
                io.enableUserActionButtons();
            }
        });
    }

    public Future<?> runCodeCommandAsync(String input)
    {
        assert io != null;
        return userActionExecutor.submit(() -> {
            try {
                LLM.runSession(coder, io, getCurrentModel(coder.models), input);
            } finally {
                io.enableUserActionButtons();
            }
        });
    }

    /**
     * Asynchronous “Ask” command
     */
    public Future<?> runAskAsync(String question)
    {
        return submitUserTask("Asking the LLM", () -> {
            try {
                if (io == null) return;
                if (question.isBlank()) {
                    io.toolErrorRaw("Please provide a question");
                    return;
                }
                // Provide the prompt messages
                var messages = new LinkedList<>(AskPrompts.instance.collectMessages(this));
                messages.add(new UserMessage("<question>\n%s\n</question>".formatted(question.trim())));

                // stream from coder
                var response = coder.sendStreaming(getCurrentModel(coder.models), messages, true);
                if (response.cancelled()) {
                    io.systemOutput("Cancelled!");
                } else if (response.chatResponse() != null) {
                    addToHistory(List.of(messages.getLast(), response.chatResponse().aiMessage()), Map.of(), question);
                }
            } catch (CancellationException cex) {
                io.systemOutput("Ask command canceled.");
            } catch (Exception e) {
                logger.error("Error in ask command", e);
                io.toolErrorRaw("Error in ask command: " + e.getMessage());
            }
        });
    }

    /**
     * Asynchronous “Search” command
     */
    public Future<?> runSearchAsync(String query)
    {
        assert io != null;
        return userActionExecutor.submit(() -> {
            try {
                if (query.isBlank()) {
                    io.toolErrorRaw("Please provide a search query");
                    return;
                }
                // run a search agent
                var agent = new SearchAgent(query, this, coder, io);
                var result = agent.execute();
                if (result == null) {
                    io.systemOutput("Search was interrupted");
                } else {
                    io.clear();
                    io.llmOutput("# Query\n\n%s\n\n# Answer\n\n%s\n".formatted(query, result.text()));
                    // The search agent already creates the right fragment type
                    addSearchFragment(result);
                }
            } catch (CancellationException cex) {
                io.systemOutput("Search command canceled.");
            } finally {
                io.enableUserActionButtons();
            }
        });
    }

    // ------------------------------------------------------------------
    // Asynchronous context actions: add/read/copy/edit/summarize/drop
    // ------------------------------------------------------------------

    /**
     * Shows the symbol selection dialog and adds usage information for the selected symbol.
     */
    public Future<?> findSymbolUsageAsync()
    {
        assert io != null;
        return contextActionExecutor.submit(() -> {
            try {
                if (getAnalyzer().isEmpty()) {
                    io.toolErrorRaw("Code Intelligence is empty; nothing to add");
                    return;
                }
                
                String symbol = showSymbolSelectionDialog("Select Symbol", CodeUnitType.ALL);
                if (symbol != null && !symbol.isBlank()) {
                    usageForIdentifier(symbol);
                } else {
                    io.systemOutput("No symbol selected.");
                }
            } catch (CancellationException cex) {
                io.systemOutput("Symbol selection canceled.");
            } finally {
                io.enableContextActionButtons();
                io.enableUserActionButtons();
            }
        });
    }
    
    /**
     * Shows the method selection dialog and adds callers information for the selected method.
     */
    public Future<?> findMethodCallersAsync()
    {
        assert io != null;
        return contextActionExecutor.submit(() -> {
            try {
                if (getAnalyzer().isEmpty()) {
                    io.toolErrorRaw("Code Intelligence is empty; nothing to add");
                    return;
                }

                var dialog = showCallGraphDialog("Select Method", true);
                if (dialog == null) {
                    io.systemOutput("No method selected.");
                } else {
                    callersForMethod(dialog.getSelectedMethod(), dialog.getDepth(), dialog.getCallGraph());
                }
            } catch (CancellationException cex) {
                io.systemOutput("Method selection canceled.");
            } finally {
                io.enableContextActionButtons();
                io.enableUserActionButtons();
            }
        });
    }
    
    /**
     * Shows the call graph dialog and adds callees information for the selected method.
     */
    public Future<?> findMethodCalleesAsync()
    {
        assert io != null;
        return contextActionExecutor.submit(() -> {
            try {
                if (getAnalyzer().isEmpty()) {
                    io.toolErrorRaw("Code Intelligence is empty; nothing to add");
                    return;
                }

                var dialog = showCallGraphDialog("Select Method for Callees", false);
                if (dialog != null && dialog.isConfirmed() && dialog.getSelectedMethod() != null && !dialog.getSelectedMethod().isBlank()) {
                    calleesForMethod(dialog.getSelectedMethod(), dialog.getDepth(), dialog.getCallGraph());
                } else {
                    io.systemOutput("No method selected.");
                }
            } catch (CancellationException cex) {
                io.systemOutput("Method selection canceled.");
            } finally {
                io.enableContextActionButtons();
                io.enableUserActionButtons();
            }
        });
    }

    /**
     * Show the custom file selection dialog
     */
    private List<BrokkFile> showFileSelectionDialog(String title, boolean allowExternalFiles, Set<ProjectFile> completions)
    {
        var dialogRef = new AtomicReference<MultiFileSelectionDialog>();
        SwingUtil.runOnEDT(() -> {
            var dialog = new MultiFileSelectionDialog(io.getFrame(), project, title, allowExternalFiles, completions);
            dialog.setSize((int) (io.getFrame().getWidth() * 0.9), 400);
            dialog.setLocationRelativeTo(io.getFrame());
            dialog.setVisible(true);
            dialogRef.set(dialog);
        });
        try {
            var dialog = dialogRef.get();
            if (dialog != null && dialog.isConfirmed()) {
                return dialog.getSelectedFiles();
            }
            return List.of();
        } finally {
            io.focusInput();
        }
    }

    /**
     * Cast BrokkFile to RepoFile. Will throw if ExternalFiles are present.
     */
    private List<ProjectFile> toRepoFiles(List<BrokkFile> files) {
        return files.stream()
                .map(f -> (ProjectFile) f)
                .collect(Collectors.toList());
    }

    /**
     * Show the symbol selection dialog with a type filter
     */
    private String showSymbolSelectionDialog(String title, Set<CodeUnitType> typeFilter)
    {
        var analyzer = project.getAnalyzer();
        var dialogRef = new AtomicReference<SymbolSelectionDialog>();
        SwingUtil.runOnEDT(() -> {
            var dialog = new SymbolSelectionDialog(io.getFrame(), analyzer, title, typeFilter);
            dialog.setSize((int) (io.getFrame().getWidth() * 0.9), dialog.getHeight());
            dialog.setLocationRelativeTo(io.getFrame());
            dialog.setVisible(true);
            dialogRef.set(dialog);
        });
        try {
            var dialog = dialogRef.get();
            if (dialog != null && dialog.isConfirmed()) {
                return dialog.getSelectedSymbol();
            }
            return null;
        } finally {
            io.focusInput();
        }
    }
    
    /**
     * Show the call graph dialog for configuring method and depth
     */
    private CallGraphDialog showCallGraphDialog(String title, boolean isCallerGraph)
    {
        var analyzer = project.getAnalyzer();
        var dialogRef = new AtomicReference<CallGraphDialog>();
        SwingUtil.runOnEDT(() -> {
            var dialog = new CallGraphDialog(io.getFrame(), analyzer, title, isCallerGraph);
            dialog.setSize((int) (io.getFrame().getWidth() * 0.9), dialog.getHeight());
            dialog.setLocationRelativeTo(io.getFrame());
            dialog.setVisible(true);
            dialogRef.set(dialog);
        });
        try {
            var dialog = dialogRef.get();
            if (dialog != null && dialog.isConfirmed()) {
                return dialog;
            }
            return null;
        } finally {
            io.focusInput();
        }
    }

    /**
     * Performed by the action buttons in the context panel: "edit / read / copy / drop / summarize"
     * If selectedFragments is empty, it means "All". We handle logic accordingly.
     */
    public Future<?> performContextActionAsync(Chrome.ContextAction action, List<ContextFragment> selectedFragments)
    {
        return contextActionExecutor.submit(() -> {
            try {
                switch (action) {
                    case EDIT -> doEditAction(selectedFragments);
                    case READ -> doReadAction(selectedFragments);
                    case COPY -> doCopyAction(selectedFragments);
                    case DROP -> doDropAction(selectedFragments);
                    case SUMMARIZE -> doSummarizeAction(selectedFragments);
                    case PASTE -> doPasteAction();
                }
            } catch (CancellationException cex) {
                io.systemOutput(action + " canceled.");
            } finally {
                io.enableContextActionButtons();
                io.enableUserActionButtons();
            }
        });
    }

    public Future<?> inferCommitMessageAsync(String diffText)
    {
        return submitBackgroundTask("Inferring commit message", () -> {
            var messages = CommitPrompts.instance.collectMessages(diffText);
            if (messages.isEmpty()) {
                io.systemOutput("Nothing to commit");
                return null;
            }

            String commitMsg = coder.sendMessage(messages);
            if (commitMsg.isEmpty()) {
                io.systemOutput("LLM did not provide a commit message");
                return null;
            }

            // Escape quotes in the commit message
            commitMsg = commitMsg.replace("\"", "\\\"");

            // Set the commit message in the GitPanel
            io.setCommitMessageText(commitMsg);
            return null;
        });
    }

    private void doEditAction(List<ContextFragment> selectedFragments) {
        if (selectedFragments.isEmpty()) {
            var files = toRepoFiles(showFileSelectionDialog("Add Context", false, project.getRepo().getTrackedFiles()));
            if (!files.isEmpty()) {
                editFiles(files);
            } else {
                io.systemOutput("No files selected.");
            }
        } else {
            var files = new HashSet<ProjectFile>();
            for (var fragment : selectedFragments) {
                files.addAll(fragment.files(project));
            }
            editFiles(files);
        }
    }

    private void doReadAction(List<ContextFragment> selectedFragments) {
        if (selectedFragments.isEmpty()) {
            var files = showFileSelectionDialog("Read Context", true, project.getFiles());
            if (!files.isEmpty()) {
                addReadOnlyFiles(files);
            } else {
                io.systemOutput("No files selected.");
            }
        } else {
            var files = new HashSet<ProjectFile>();
            for (var fragment : selectedFragments) {
                files.addAll(fragment.files(project));
            }
            addReadOnlyFiles(files);
        }
    }

    private void doCopyAction(List<ContextFragment> selectedFragments) {
        String content;
        if (selectedFragments.isEmpty()) {
            // gather entire context
            var msgs = ArchitectPrompts.instance.collectMessages(this);
            var combined = new StringBuilder();
            for (var m : msgs) {
                if (!(m instanceof dev.langchain4j.data.message.AiMessage)) {
                    combined.append(Models.getText(m)).append("\n\n");
                }
            }
            
            // Get instructions from context
            combined.append("\n<goal>\n").append(io.getInputText()).append("\n</goal>");
            content = combined.toString();
        } else {
            // copy only selected fragments
            var sb = new StringBuilder();
            for (var frag : selectedFragments) {
                try {
                    sb.append(frag.text()).append("\n\n");
                } catch (IOException e) {
                    removeBadFragment(frag, e);
                    io.toolErrorRaw("Error reading fragment: " + e.getMessage());
                }
            }
            content = sb.toString();
        }

        var sel = new java.awt.datatransfer.StringSelection(content);
        var cb = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
        cb.setContents(sel, sel);
        io.systemOutput("Content copied to clipboard");
    }

    private void doPasteAction()
    {
        // Get text from clipboard
        String clipboardText;
        try {
            var clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            var contents = clipboard.getContents(null);
            if (contents == null || !contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                io.toolErrorRaw("No text on clipboard");
                return;
            }
            clipboardText = (String) contents.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
            if (clipboardText.isBlank()) {
                io.toolErrorRaw("Clipboard is empty");
                return;
            }
        } catch (Exception e) {
            io.toolErrorRaw("Failed to read clipboard: " + e.getMessage());
            return;
        }

        // Process the clipboard text
        processClipboardText(clipboardText);
    }

    public void processClipboardText(String clipboardText) {
        clipboardText = clipboardText.trim();
        // Check if it's a URL
        String content = clipboardText;
        boolean wasUrl = false;

        if (isUrl(clipboardText)) {
            try {
                io.systemOutput("Fetching " + clipboardText);
                content = fetchUrlContent(clipboardText);
                content = HtmlToMarkdown.maybeConvertToMarkdown(content);
                wasUrl = true;
                io.actionComplete();
            } catch (IOException e) {
                io.toolErrorRaw("Failed to fetch URL content: " + e.getMessage());
                // Continue with the URL as text if fetch fails
            }
        }

        // Try to parse as stacktrace
        var stacktrace = StackTrace.parse(content);
        if (stacktrace != null && addStacktraceFragment(stacktrace)) {
            return;
        }

        // Add as string fragment (possibly converted from HTML)
        addPasteFragment(content, submitSummarizeTaskForPaste(content));

        // Inform the user about what happened
        String message = wasUrl ? "URL content fetched and added" : "Clipboard content added as text";
        io.systemOutput(message);
    }

    private boolean isUrl(String text) {
        return text.matches("^https?://\\S+$");
    }

    private String fetchUrlContent(String urlString) throws IOException {
        var url = URI.create(urlString).toURL();
        var connection = url.openConnection();
        // Set a reasonable timeout
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        // Set a user agent to avoid being blocked
        connection.setRequestProperty("User-Agent", "Brokk-Agent/1.0");

        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(connection.getInputStream()))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
    private void doDropAction(List<ContextFragment> selectedFragments)
    {
        if (selectedFragments.isEmpty()) {
            if (topContext().isEmpty()) {
                io.toolErrorRaw("No context to drop");
                return;
            }
            dropAll();
        } else {
            var pathFragsToRemove = new ArrayList<ContextFragment.PathFragment>();
            var virtualToRemove = new ArrayList<ContextFragment.VirtualFragment>();
            boolean clearHistory = false;

            for (var frag : selectedFragments) {
                if (frag instanceof ContextFragment.ConversationFragment) {
                    clearHistory = true;
                } else if (frag instanceof ContextFragment.AutoContext) {
                    setAutoContextFiles(0);
                } else if (frag instanceof ContextFragment.PathFragment pf) {
                    pathFragsToRemove.add(pf);
                } else {
                    assert frag instanceof ContextFragment.VirtualFragment: frag;
                    virtualToRemove.add((VirtualFragment) frag);
                }
            }

            if (clearHistory) {
                clearHistory();
                io.systemOutput("Cleared conversation history");
            }

            drop(pathFragsToRemove, virtualToRemove);

            if (!pathFragsToRemove.isEmpty() || !virtualToRemove.isEmpty()) {
                io.systemOutput("Dropped " + (pathFragsToRemove.size() + virtualToRemove.size()) + " items");
            }
        }
    }

    private void doSummarizeAction(List<ContextFragment> selectedFragments) {
        if (getAnalyzer().isEmpty()) {
            io.toolErrorRaw("Code Intelligence is empty; nothing to add");
            return;
        }
        
        HashSet<CodeUnit> sources = new HashSet<>();
        String sourceDescription;

        if (selectedFragments.isEmpty()) {
            // Show file selection dialog when nothing is selected
            var completions = project.getFiles().stream()
                    .filter(f -> !getAnalyzer().getClassesInFile(f).isEmpty())
                    .collect(Collectors.toSet());
            var files = toRepoFiles(showFileSelectionDialog("Summarize Files", false, completions));
            if (files.isEmpty()) {
                io.systemOutput("No files selected for summarization");
                return;
            }

            for (var file : files) {
                sources.addAll(getAnalyzer().getClassesInFile(file));
            }
            sourceDescription = files.size() + " files";
        } else {
            // Extract sources from selected fragments
            for (var frag : selectedFragments) {
                sources.addAll(frag.sources(project));
            }
            sourceDescription = selectedFragments.size() + " fragments";
        }

        if (sources.isEmpty()) {
            io.toolErrorRaw("No classes found in the selected " + (selectedFragments.isEmpty() ? "files" : "fragments"));
            return;
        }

        boolean success = summarizeClasses(sources);
        if (success) {
            io.systemOutput("Summarized " + sources.size() + " classes from " + sourceDescription);
        } else {
            io.toolErrorRaw("Failed to summarize classes");
        }
    }

    // ------------------------------------------------------------------
    // Existing business logic from the old code
    // ------------------------------------------------------------------

    /** Add the given files to editable. */
    @Override
    public void editFiles(Collection<ProjectFile> files)
    {
        var fragments = files.stream().map(ContextFragment.RepoPathFragment::new).toList();
        pushContext(ctx -> ctx.removeReadonlyFiles(fragments).addEditableFiles(fragments));
    }

    /** Add read-only files. */
    public void addReadOnlyFiles(Collection<? extends BrokkFile> files)
    {
        var fragments = files.stream().map(ContextFragment::toPathFragment).toList();
        pushContext(ctx -> ctx.removeEditableFiles(fragments).addReadonlyFiles(fragments));
    }

    /** Drop all context. */
    public void dropAll()
    {
        pushContext(Context::removeAll);
    }

    /** Drop the given fragments. */
    public void drop(List<PathFragment> pathFragsToRemove, List<VirtualFragment> virtualToRemove)
    {
        pushContext(ctx -> ctx
                .removeEditableFiles(pathFragsToRemove)
                .removeReadonlyFiles(pathFragsToRemove)
                .removeVirtualFragments(virtualToRemove));
    }

    /** Clear conversation history. */
    public void clearHistory()
    {
        pushContext(Context::clearHistory);
    }

    /** request code-intel rebuild */
    public void requestRebuild()
    {
        project.getRepo().refresh();
        project.rebuildAnalyzer();
    }

    /** undo last context change */
    public Future<?> undoContextAsync()
    {
        return undoContextAsync(1);
    }

    /** undo multiple context changes to reach a specific point in history */
    public Future<?> undoContextAsync(int stepsToUndo)
    {
        return contextActionExecutor.submit(() -> {
            try {
                UndoResult result = contextHistory.undo(stepsToUndo, io);
                if (result.wasUndone()) {
                    var currentContext = contextHistory.topContext();
                    io.updateContextHistoryTable(currentContext);
                    io.systemOutput("Undid " + result.steps() + " step" + (result.steps() > 1 ? "s" : "") + "!");
                } else {
                    io.toolErrorRaw("no undo state available");
                }
            } catch (CancellationException cex) {
                io.systemOutput("Undo canceled.");
            } finally {
                io.enableContextActionButtons();
                io.enableUserActionButtons();
            }
        });
    }
    
    /** undo changes until we reach the target context */
    public Future<?> undoContextUntilAsync(Context targetContext)
    {
        return contextActionExecutor.submit(() -> {
            try {
                UndoResult result = contextHistory.undoUntil(targetContext, io);
                if (result.wasUndone()) {
                    var currentContext = contextHistory.topContext();
                    io.updateContextHistoryTable(currentContext);
                    io.systemOutput("Undid " + result.steps() + " step" + (result.steps() > 1 ? "s" : "") + "!");
                } else {
                    io.toolErrorRaw("Context not found or already at that point");
                }
            } catch (CancellationException cex) {
                io.systemOutput("Undo canceled.");
            } finally {
                io.enableContextActionButtons();
                io.enableUserActionButtons();
            }
        });
    }

    /** redo last undone context */
    public Future<?> redoContextAsync()
    {
        return contextActionExecutor.submit(() -> {
            try {
                boolean wasRedone = contextHistory.redo(io);
                if (wasRedone) {
                    var currentContext = contextHistory.topContext();
                    io.updateContextHistoryTable(currentContext);
                    io.systemOutput("Redo!");
                } else {
                    io.toolErrorRaw("no redo state available");
                }
            } catch (CancellationException cex) {
                io.systemOutput("Redo canceled.");
            } finally {
                io.enableContextActionButtons();
                io.enableUserActionButtons();
            }
        });
    }
    
    /** Reset the context to match the files and fragments from a historical context */
    public Future<?> resetContextToAsync(Context targetContext)
    {
        return contextActionExecutor.submit(() -> {
            try {
                pushContext(ctx -> Context.createFrom(targetContext, ctx));
                io.systemOutput("Reset context to match historical state!");
            } catch (CancellationException cex) {
                io.systemOutput("Reset context canceled.");
            } finally {
                io.enableContextActionButtons();
                io.enableUserActionButtons();
            }
        });
    }


    /** Pasting content as read-only snippet */
    public void addPasteFragment(String pastedContent, Future<String> summaryFuture)
    {
        pushContext(ctx -> {
            var fragment = new ContextFragment.PasteFragment(pastedContent, summaryFuture);
            return ctx.addPasteFragment(fragment, summaryFuture);
        });
    }

    /** Add search fragment from agent result */
    public void addSearchFragment(VirtualFragment fragment)
    {
        Future<String> query;
        if (fragment.description().split("\\s").length > 10) {
            query = submitSummarizeTaskForConversation(fragment.description());
        } else {
            query = CompletableFuture.completedFuture(fragment.description());
        }

        var llmOutputText = io.getLlmOutputText();
        if (llmOutputText == null) {
            io.systemOutput("Interrupted!");
            return;
        }

        var parsed = new ParsedOutput(llmOutputText, fragment);
        pushContext(ctx -> ctx.addSearchFragment(query, parsed));
    }

    /**
     * Adds any virtual fragment directly
     */
    public void addVirtualFragment(VirtualFragment fragment)
    {
        pushContext(ctx -> ctx.addVirtualFragment(fragment));
    }

    /**
     * Captures text from the LLM output area and adds it to the context.
     * Called from Chrome's capture button.
     */
    public void captureTextFromContextAsync()
    {
        contextActionExecutor.submit(() -> {
            try {
                var selectedCtx = selectedContext();
                if (selectedCtx != null && selectedCtx.getParsedOutput() != null) {
                    addVirtualFragment(selectedCtx.getParsedOutput().parsedFragment());
                    io.systemOutput("Content captured from output");
                } else {
                    io.toolErrorRaw("No content to capture");
                }
            } catch (CancellationException cex) {
                io.systemOutput("Capture canceled.");
            } finally {
                io.enableContextActionButtons();
                io.enableUserActionButtons();
            }
        });
    }

    /** usage for identifier */
    public void usageForIdentifier(String identifier)
    {
        var uses = getAnalyzer().getUses(identifier);
        if (uses.isEmpty()) {
            io.systemOutput("No uses found for " + identifier);
            return;
        }
        var result = AnalyzerUtil.processUsages(getAnalyzer(), uses);
        if (result.code().isEmpty()) {
            io.systemOutput("No relevant uses found for " + identifier);
            return;
        }
        var combined = result.code();
        var fragment = new ContextFragment.UsageFragment("Uses", identifier, result.sources(), combined);
        pushContext(ctx -> ctx.addVirtualFragment(fragment));
    }
    
    /** callers for method */
    public void callersForMethod(String methodName, int depth, Map<String, List<CallSite>> callgraph)
    {
        if (callgraph == null || callgraph.isEmpty()) {
            io.systemOutput("No callers found for " + methodName);
            return;
        }

        String formattedCallGraph = AnalyzerUtil.formatCallGraph(callgraph, methodName, true);
        if (formattedCallGraph.isEmpty()) {
            io.systemOutput("No callers found for " + methodName);
            return;
        }

        // Extract the class from the method name for sources
        Set<CodeUnit> sources = new HashSet<>();
        String className = ContextFragment.toClassname(methodName);
        var sourceFile = getAnalyzer().getFileFor(className);
        if (sourceFile.isDefined()) {
            sources.add(CodeUnit.cls(sourceFile.get(), className));
        }

        // The output is similar to UsageFragment, so we'll use that
        var fragment = new ContextFragment.UsageFragment("Callers (depth " + depth + ")", methodName, sources, formattedCallGraph);
        pushContext(ctx -> ctx.addVirtualFragment(fragment));

        int totalCallSites = callgraph.values().stream().mapToInt(List::size).sum();
        io.systemOutput("Added call graph with " + totalCallSites + " call sites for callers of " + methodName + " with depth " + depth);
    }
    
    /** callees for method */
    public void calleesForMethod(String methodName, int depth, Map<String, List<CallSite>> callgraph)
    {
        if (callgraph == null || callgraph.isEmpty()) {
            io.systemOutput("No callees found for " + methodName);
            return;
        }

        String formattedCallGraph = AnalyzerUtil.formatCallGraph(callgraph, methodName, false);
        if (formattedCallGraph.isEmpty()) {
            io.systemOutput("No callees found for " + methodName);
            return;
        }

        // Extract the class from the method name for sources
        Set<CodeUnit> sources = new HashSet<>();
        String className = ContextFragment.toClassname(methodName);
        var sourceFile = getAnalyzer().getFileFor(className);
        if (sourceFile.isDefined()) {
            sources.add(CodeUnit.cls(sourceFile.get(), className));
        }

        // The output is similar to UsageFragment, so we'll use that
        var fragment = new ContextFragment.UsageFragment("Callees (depth " + depth + ")", methodName, sources, formattedCallGraph);
        pushContext(ctx -> ctx.addVirtualFragment(fragment));

        int totalCallSites = callgraph.values().stream().mapToInt(List::size).sum();
        io.systemOutput("Added call graph with " + totalCallSites + " call sites for methods called by " + methodName + " with depth " + depth);
    }

    /** parse stacktrace */
    public boolean addStacktraceFragment(StackTrace stacktrace)
    {
        assert stacktrace != null;

        var exception = stacktrace.getExceptionType();
        var content = new StringBuilder();
        var sources = new HashSet<CodeUnit>();

        for (var element : stacktrace.getFrames()) {
            var methodFullName = element.getClassName() + "." + element.getMethodName();
            var methodSource = getAnalyzer().getMethodSource(methodFullName);
            if (methodSource.isDefined()) {
                String className = ContextFragment.toClassname(methodFullName);
                var sourceFile = getAnalyzer().getFileFor(className);
                if (sourceFile.isDefined()) {
                    sources.add(CodeUnit.cls(sourceFile.get(), className));
                }
                content.append(methodFullName).append(":\n");
                content.append(methodSource.get()).append("\n\n");
            }
        }
        if (content.isEmpty()) {
            io.toolErrorRaw("No relevant methods found in stacktrace -- adding as text");
            return false;
        }
        pushContext(ctx -> {
            var fragment = new ContextFragment.StacktraceFragment(sources, stacktrace.getOriginalText(), exception, content.toString());
            return ctx.addVirtualFragment(fragment);
        });
        return true;
    }

    /** Summarize classes => adds skeleton fragments */
    public boolean summarizeClasses(Set<CodeUnit> classes)
    {
        if (getAnalyzer().isEmpty()) {
            io.toolErrorRaw("Code Intelligence is empty; nothing to add");
            return false;
        }
        
        var skeletons = new HashMap<CodeUnit, String>();
        var coalescedUnits = coalesceInnerClasses(classes);
        for (var cu : coalescedUnits) {
            var skeleton = getAnalyzer().getSkeleton(cu.fqName());
            if (skeleton.isDefined()) {
                skeletons.put(cu, skeleton.get());
            }
        }
        if (skeletons.isEmpty()) {
            return false;
        }
        var skeletonFragment = new ContextFragment.SkeletonFragment(skeletons);
        addVirtualFragment(skeletonFragment);
        return true;
    }

    @NotNull
    private static Set<CodeUnit> coalesceInnerClasses(Set<CodeUnit> classes)
    {
        return classes.stream()
                .filter(cu -> {
                    var name = cu.fqName();
                    if (!name.contains("$")) return true;
                    var parent = name.substring(0, name.indexOf('$'));
                    return classes.stream().noneMatch(other -> other.fqName().equals(parent));
                })
                .collect(Collectors.toSet());
    }

    /**
     * Update auto-context file count on the current executor thread (for background operations)
     */
    public void setAutoContextFiles(int fileCount)
    {
        pushContext(ctx -> ctx.setAutoContextFiles(fileCount));
    }

    /**
     * Asynchronous version of setAutoContextFiles to avoid blocking the UI thread
     */
    public Future<?> setAutoContextFilesAsync(int fileCount)
    {
        return contextActionExecutor.submit(() -> {
            try {
                setAutoContextFiles(fileCount);
            } catch (CancellationException cex) {
                io.systemOutput("Auto-context update canceled.");
            } finally {
                io.enableContextActionButtons();
                io.enableUserActionButtons();
            }
        });
    }

    public List<ChatMessage> getHistoryMessages()
    {
        return selectedContext().getHistory();
    }

    /**
     * Build a welcome message with environment information
     */
    private String buildWelcomeMessage(Models models) {
        String welcomeMarkdown;
        var mdPath = "/WELCOME.md";
        try (var welcomeStream = Brokk.class.getResourceAsStream(mdPath)) {
            if (welcomeStream != null) {
                welcomeMarkdown = new String(welcomeStream.readAllBytes(), StandardCharsets.UTF_8);
            } else {
                logger.warn("WELCOME.md resource not found.");
                welcomeMarkdown = "Welcome to Brokk!";
            }
        } catch (IOException e1) {
            throw new UncheckedIOException(e1);
        }

        Properties props = new Properties();
        try {
            props.load(getClass().getResourceAsStream("/version.properties"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        var version = props.getProperty("version");

        return """
            %s

            ## Environment
            - Brokk %s
            - Editor model: %s
            - Apply model: %s
            - Quick model: %s
            - Project at %s with %d native files and %d total files including dependencies
            - Analyzer language: %s
            """.formatted(welcomeMarkdown,
                          version,
                          models.editModelName(),
                          models.applyModelName(),
                          models.quickModelName(),
                          project.getRoot(),
                          project.getRepo().getTrackedFiles().size(),
                          project.getFiles().size(),
                          project.getAnalyzerLanguage());
    }

    /**
     * Shutdown all executors
     */
    public void close() {
        userActionExecutor.shutdown();
        contextActionExecutor.shutdown();
        backgroundTasks.shutdown();
        project.close();
    }

    public List<ChatMessage> getReadOnlyMessages()
    {
        var c = selectedContext();
        var combined = Streams.concat(c.readonlyFiles(),
                                      c.virtualFragments(),
                                      Stream.of(c.getAutoContext()))
                .map(this::formattedOrNull)
                .filter(Objects::nonNull)
                .filter(st -> !st.isBlank())
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
        return List.of(new UserMessage(msg), new AiMessage("Ok, I will use this code as references."));
    }

    public List<ChatMessage> getEditableMessages()
    {
        var combined = selectedContext().editableFiles()
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
        return List.of(new UserMessage(msg), new AiMessage("Ok, any changes I propose will be to those files."));
    }

    public String getReadOnlySummary()
    {
        var c = selectedContext();
        return Streams.concat(c.readonlyFiles().map(f -> f.file().toString()),
                              c.virtualFragments().map(vf -> "'" + vf.description() + "'"),
                              Stream.of(c.getAutoContext().isEmpty() ? "" : c.getAutoContext().description()))
                .filter(st -> !st.isBlank())
                .collect(Collectors.joining(", "));
    }

    public String getEditableSummary()
    {
        return selectedContext().editableFiles()
                .map(p -> p.file().toString())
                .collect(Collectors.joining(", "));
    }

    public Set<ProjectFile> getEditableFiles()
    {
        return selectedContext().editableFiles()
                .map(ContextFragment.RepoPathFragment::file)
                .collect(Collectors.toSet());
    }

    @Override
    public Set<BrokkFile> getReadonlyFiles() {
        return selectedContext().readonlyFiles()
                .map(ContextFragment.PathFragment::file)
                .collect(Collectors.toSet());
    }

    /**
     * push context changes with a function that modifies the current context
     */
    private void pushContext(Function<Context, Context> contextGenerator)
    {
        Context newContext = contextHistory.pushContext(contextGenerator);
        if (newContext != null) {
            io.updateContextHistoryTable(newContext);
            project.saveContext(newContext);
        }
    }

    /**
     * Updates the selected context in history from the UI
     * Called by Chrome when the user selects a row in the history table
     */
    public void setSelectedContext(Context context) {
        contextHistory.setSelectedContext(context);
    }

    private String formattedOrNull(ContextFragment fragment)
    {
        try {
            return fragment.format();
        } catch (IOException e) {
            removeBadFragment(fragment, e);
            return null;
        }
    }

    public void removeBadFragment(ContextFragment f, IOException th)
    {
        logger.warn("Removing unreadable fragment {}", f.description(), th);
        io.toolErrorRaw("Removing unreadable fragment " + f.description());
        pushContext(c -> c.removeBadFragment(f));
    }

    private final ConcurrentMap<Callable<?>, String> taskDescriptions = new ConcurrentHashMap<>();

    public SwingWorker<String, Void> submitSummarizeTaskForPaste(String pastedContent) {
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                var msgs = SummarizerPrompts.instance.collectMessages(pastedContent, 12);
                return coder.sendMessage(msgs);
            }

            @Override
            protected void done() {
                io.updateContextTable();
                io.updateContextHistoryTable();
            }
        };

        worker.execute();
        return worker;
    }

    public SwingWorker<String, Void> submitSummarizeTaskForConversation(String input) {
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                var msgs =  SummarizerPrompts.instance.collectMessages(input, 5);
                return coder.sendMessage(msgs);
            }

            @Override
            protected void done() {
                io.updateContextHistoryTable();
            }
        };

        worker.execute();
        return worker;
    }

    /**
     * Submits a background task to the internal background executor (non-user actions).
     */
    @Override
    public <T> Future<T> submitBackgroundTask(String taskDescription, Callable<T> task) {
        assert taskDescription != null;
        assert !taskDescription.isBlank();
        Future<T> future = backgroundTasks.submit(() -> {
            try {
                io.backgroundOutput(taskDescription);
                return task.call();
            } finally {
                // Remove this task from the map
                taskDescriptions.remove(task);
                int remaining = taskDescriptions.size();
                SwingUtilities.invokeLater(() -> {
                    if (remaining <= 0) {
                        io.backgroundOutput("");
                    } else if (remaining == 1) {
                        // Find the last remaining task description. If there's a race just end the spin
                        var lastTaskDescription = taskDescriptions.values().stream().findFirst().orElse("");
                        io.backgroundOutput(lastTaskDescription);
                    } else {
                        io.backgroundOutput("Tasks running: " + remaining);
                    }
                });
            }
        });

        // Track the future with its description
        taskDescriptions.put(task, taskDescription);
        return future;
    }

    private void ensureBuildCommand(Coder coder) {
        var loadedCommand = project.getBuildCommand();
        // Possibly store an inferred buildCommand
        if (loadedCommand != null) {
            io.systemOutput("Using saved build command `%s`".formatted(loadedCommand));
        } else {
            // do background inference
            var tracked = project.getRepo().getTrackedFiles();
            var filenames = tracked.stream()
                    .map(ProjectFile::toString)
                    .filter(s -> !s.contains(File.separator))
                    .collect(Collectors.toList());
            if (filenames.isEmpty()) {
                filenames = tracked.stream().map(ProjectFile::toString).toList();
            }

            var messages = List.of(
                    new SystemMessage("You are a build assistant that suggests a single command to do a quick compile check."),
                    new UserMessage(
                            "We have these files:\n\n" + filenames
                                    + "\n\nSuggest a minimal single-line shell command to compile them incrementally, not a full build. "
                                    + "Respond with JUST the command, no commentary and no markup."
                    )
            );

            submitBackgroundTask("Inferring build command", () -> {
                String response;
                try {
                    response = coder.sendMessage(coder.models.searchModel(), messages).chatResponse().aiMessage().text().trim();
                } catch (Throwable th) {
                    return BuildCommand.failure(th.getMessage());
                }
                if (response.equals(Models.UNAVAILABLE)) {
                    return BuildCommand.failure(Models.UNAVAILABLE);
                }
                var inferred = response.trim();
                project.setBuildCommand(inferred);
                io.systemOutput("Inferred build command: " + inferred);
                return BuildCommand.success(inferred);
            });
        }
    }

    @FunctionalInterface
    public interface TaskRunner {
        /**
         * Submits a background task with the given description.
         *
         * @param taskDescription a description of the task
         * @param task the task to execute
         * @param <T> the result type of the task
         * @return a {@link Future} representing pending completion of the task
         */
        <T> Future<T> submit(String taskDescription, Callable<T> task);
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
     * Ensure style guide exists, generating if needed
     */
    private void ensureStyleGuide()
    {
        if (project.getStyleGuide() != null) {
            return;
        }
        submitBackgroundTask("Generating style guide", () -> {
            try {
                io.systemOutput("Generating project style guide...");
                var analyzer = project.getAnalyzer();
                var topClassUnits = AnalyzerUtil.combinedPagerankFor(analyzer, Map.of());
                var topClasses = topClassUnits.stream().map(CodeUnit::fqName).toList();

                var codeForLLM = new StringBuilder();
                var tokens = 0;
                for (var fqcn : topClasses) {
                    var fileOption = analyzer.getFileFor(fqcn);
                    if (fileOption.isEmpty()) continue;
                    var file = fileOption.get();
                    String chunk;
                    try {
                        chunk = "<file path=%s>\n%s\n</file>\n".formatted(file, file.read());
                    } catch (IOException e) {
                        logger.error("Failed to read {}: {}", file, e.getMessage());
                        continue;
                    }
                    var chunkTokens = Models.getApproximateTokens(chunk);
                    if (tokens + chunkTokens > 50000) {
                        if (tokens > 0) break;
                    }
                    codeForLLM.append(chunk);
                    tokens += chunkTokens;
                }

                if (codeForLLM.isEmpty()) {
                    io.systemOutput("No relevant code found for style guide generation");
                    return null;
                }

                var messages = List.of(
                        new SystemMessage("You are an expert software engineer. Your task is to extract a concise coding style guide from the provided code examples."),
                        new UserMessage("""
                        Based on these code examples, create a concise, clear coding style guide in Markdown format
                        that captures the conventions used in this codebase, particularly the ones that leverage new or uncommon features.
                        DO NOT repeat what are simply common best practices.

                        %s
                        """.stripIndent().formatted(codeForLLM))
                );

                var styleGuide = coder.sendMessage(messages);
                if (styleGuide.equals(Models.UNAVAILABLE)) {
                    io.systemOutput("Failed to generate style guide: LLM unavailable");
                    return null;
                }
                project.saveStyleGuide(styleGuide);
                io.systemOutput("Style guide generated and saved to .brokk/style.md");
            } catch (Exception e) {
                logger.error("Error generating style guide", e);
            }
            return null;
        });
    }

    /**
     * Add to the user/AI message history. Called by both Ask and Code.
     */
    @Override
    public void addToHistory(List<ChatMessage> messages, Map<ProjectFile, String> originalContents, String action)
    {
        addToHistory(messages, originalContents, action, io.getLlmOutputText());
    }

    public void addToHistory(List<ChatMessage> messages, Map<ProjectFile, String> originalContents, String action, String llmOutputText)
    {
        var parsed = new ParsedOutput(llmOutputText, new ContextFragment.StringFragment(llmOutputText, "ai Response"));
        logger.debug("Adding to history with {} changed files", originalContents.size());
        pushContext(ctx -> ctx.addHistory(messages, originalContents, parsed, submitSummarizeTaskForConversation(action)));
    }

    public List<Context> getContextHistory() {
        return contextHistory.getHistory();
    }

    @Override
    public void addToGit(String filename) throws IOException {
        ((GitRepo) project.getRepo()).add(List.of(toFile(filename)));
    }

    // Convert a throwable to a string with full stack trace
    private String getStackTraceAsString(Throwable throwable) {
        var sw = new java.io.StringWriter();
        var pw = new java.io.PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }
}
