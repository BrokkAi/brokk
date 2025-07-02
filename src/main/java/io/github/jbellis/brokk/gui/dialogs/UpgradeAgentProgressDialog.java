package io.github.jbellis.brokk.gui.dialogs;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.agents.ArchitectAgent;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.agents.RelevanceClassifier;
import io.github.jbellis.brokk.agents.CodeAgent;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.prompts.EditBlockConflictsParser;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.Messages;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

public class UpgradeAgentProgressDialog extends JDialog {

    public enum PostProcessingOption { NONE, ARCHITECT, ASK }

    private static final Logger logger = LogManager.getLogger(UpgradeAgentProgressDialog.class);
    private final JProgressBar progressBar;
    private final JTextArea outputTextArea;
    private final JButton cancelButton;
    private final SwingWorker<TaskResult, ProgressData> worker;
    private final int totalFiles;
    private final AtomicInteger processedFileCount = new AtomicInteger(0); // Tracks files processed
    private final AtomicInteger llmLineCount = new AtomicInteger(0); // Tracks LLM output lines for all files
    private final javax.swing.Timer labelUpdateDebounceTimer; // Debounces updates to llmLineCountLabel
    private final JLabel llmLineCountLabel; // Displays total LLM output lines
    private volatile @Nullable ExecutorService executorService = null; // Thread pool for parallel processing


    // Record to communicate progress from doInBackground to process
    private record ProgressData(int processedCount, String fileName, @Nullable String errorMessage) { }
    // Record to hold results from processing a single file
    private record FileProcessingResult(ProjectFile file, @Nullable String errorMessage, String llmOutput) { }

    /**
     * Token-bucket rate limiter for models that expose only tokens_per_minute.
     * Thread-safe; callers block in acquire() until they can spend the requested tokens.
     */
    private static final class TokenRateLimiter
    {
        private final int capacity;
        private double tokens;
        private final double refillPerMs;
        private long last;

        private TokenRateLimiter(int tokensPerMinute)
        {
            assert tokensPerMinute > 0;
            this.capacity = tokensPerMinute;
            this.tokens = tokensPerMinute;
            this.refillPerMs = tokensPerMinute / 60_000.0;
            this.last = System.currentTimeMillis();
        }

        public void acquire(int requested) throws InterruptedException
        {
            if (requested <= 0) return;
            synchronized (this) {
                while (true) {
                    refill();
                    if (tokens >= requested) {
                        tokens -= requested;
                        return;
                    }
                    long sleep = (long) Math.ceil((requested - tokens) / refillPerMs);
                    sleep = Math.max(sleep, 50);
                    this.wait(sleep);
                }
            }
        }

        private void refill()
        {
            long now = System.currentTimeMillis();
            long elapsed = now - last;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * refillPerMs);
                last = now;
                this.notifyAll();
            }
        }
    }

    /**
     * Helper method to compute file size in bytes; if unavailable, falls back to Long.MAX_VALUE to push it to the end.
     */
    private static long fileSize(ProjectFile file) {
        try {
            return Files.size(java.nio.file.Path.of(file.toString()));
        } catch (Exception e) {
            logger.warn("Unable to determine size for {} – {}", file, e.toString());
            return Long.MAX_VALUE;
        }
    }

    /**
     * Encapsulates the per-file processing logic previously inlined in SwingWorker.
     */
    private FileProcessingResult processSingleFile(ProjectFile file,
                                                   ContextManager contextManager,
                                                   StreamingChatLanguageModel model,
                                                   String instructions,
                                                   boolean includeWorkspace,
                                                   @Nullable Integer relatedK,
                                                   @Nullable String perFileCommandTemplate,
                                                   Context ctx,
                                                   String contextFilter,
                                                   @Nullable TokenRateLimiter rateLimiter)
    {
        var dialogConsoleIO = new DialogConsoleIO(this, file.toString());
        String errorMessage = null;

        if (Thread.currentThread().isInterrupted() || worker.isCancelled()) {
            errorMessage = "Cancelled by user.";
            dialogConsoleIO.systemOutput("Processing cancelled by user for file: " + file);
            return new FileProcessingResult(file, errorMessage, "");
        }

        var agent = new CodeAgent(contextManager, model, dialogConsoleIO);

        List<ChatMessage> readOnlyMessages = new ArrayList<>();
        try {
            if (includeWorkspace) {
                readOnlyMessages.addAll(CodePrompts.instance.getWorkspaceContentsMessages(ctx));
                readOnlyMessages.addAll(CodePrompts.instance.getHistoryMessages(ctx));
                dialogConsoleIO.systemOutput("Including workspace contents in context.");
            }
            if (relatedK != null) {
                var acFragment = ctx.buildAutoContext(relatedK);
                if (!acFragment.text().isBlank()) {
                    var msgText = """
                                  <related_classes>
                                  The user requested to include the top %d related classes.
                                  
                                  %s
                                  </related_classes>
                                  """.stripIndent().formatted(relatedK, acFragment.text());
                    readOnlyMessages.add(new UserMessage(msgText));
                    dialogConsoleIO.systemOutput("Added " + relatedK + " related classes to context.");
                }
            }

            // Execute per-file command if provided
            if (perFileCommandTemplate != null && !perFileCommandTemplate.isBlank()) {
                dialogConsoleIO.systemOutput("Preparing to execute per-file command for " + file);
                MustacheFactory mf = new DefaultMustacheFactory();
                Mustache mustache = mf.compile(new StringReader(perFileCommandTemplate), "perFileCommand");
                StringWriter writer = new StringWriter();
                Map<String, Object> scope = new HashMap<>();
                scope.put("filepath", file.toString()); // Using relative path
                mustache.execute(writer, scope);
                String finalCommand = writer.toString();

                dialogConsoleIO.actionOutput("Executing per-file command: " + finalCommand);
                logger.info("Executing per-file command for {}: {}", file, finalCommand);
                String commandOutputText;
                try {
                    String output = Environment.instance.runShellCommand(finalCommand,
                                                                         contextManager.getProject().getRoot(),
                                                                         line -> {
                                                                         }); // No live consumer for now
                    commandOutputText = """
                                        <per_file_command_output command="%s">
                                        %s
                                        </per_file_command_output>
                                        """.stripIndent().formatted(finalCommand, output);
                } catch (Environment.SubprocessException ex) {
                    logger.warn("Per-file command failed for {}: {}", file, finalCommand, ex);
                    commandOutputText = """
                                        <per_file_command_output command="%s">
                                        Error executing command: %s
                                        Output (if any):
                                        %s
                                        </per_file_command_output>
                                        """.stripIndent().formatted(finalCommand, ex.getMessage(), ex.getOutput());
                    dialogConsoleIO.toolError("Per-file command failed: " + ex.getMessage() + "\nOutput (if any):\n" + ex.getOutput(), "Command Execution Error");
                }
                readOnlyMessages.add(new UserMessage(commandOutputText));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errorMessage = "Interrupted during message preparation.";
            dialogConsoleIO.systemOutput("Interrupted during message preparation for file: " + file);
        }

        if (errorMessage != null) {
            return new FileProcessingResult(file, errorMessage, dialogConsoleIO.getLlmOutput());
        }

        if (rateLimiter != null) {
            int estimatedTokens = 0;
            try {
                estimatedTokens = Messages.getApproximateTokens(readOnlyMessages) + Messages.getApproximateTokens(file.read());
            } catch (IOException e) {
                return new FileProcessingResult(file, "Error reading file contents", "");
            }
            try {
                rateLimiter.acquire(estimatedTokens);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return new FileProcessingResult(file, "Interrupted while waiting for rate limiter", "");
            }
        }

        // actually kick off the processing
        try {
            var result = agent.runSingleFileEdit(file, instructions, readOnlyMessages);

            if (result.stopDetails().reason() == TaskResult.StopReason.INTERRUPTED) {
                Thread.currentThread().interrupt(); // Preserve interrupt status
                errorMessage = "Processing interrupted.";
                dialogConsoleIO.systemOutput("File processing for " + file + " was interrupted.");
            } else if (result.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
                errorMessage = "Processing failed: " + result.stopDetails().reason();
                String explanation = result.stopDetails().explanation();
                if (!explanation.isEmpty()) {
                    errorMessage += " - " + explanation;
                }
                dialogConsoleIO.toolError(errorMessage, "Agent Processing Error");
            } else {
                dialogConsoleIO.systemOutput("successfully processed");
            }
        } catch (Throwable t) {
            errorMessage = "Unexpected error: " + t.getMessage();
            logger.error("Unexpected failure while processing {}", file, t);
        }

        // ---- optional context filtering -----------------------------
        String finalLlmOutput = dialogConsoleIO.getLlmOutput();
        if (!contextFilter.isBlank() && !finalLlmOutput.isBlank()) {
            try {
                var quickestModel = contextManager.getService().quickestModel();
                var filterLlm     = contextManager.getLlm(quickestModel, "ContextFilter");

                boolean keep = RelevanceClassifier.isRelevant(filterLlm, contextFilter, finalLlmOutput);
                if (!keep) {
                    finalLlmOutput = "";
                }
            } catch (Exception e) {
                logger.warn("Context filtering failed for {}: {}", file, e.toString());
            }
        }

        return new FileProcessingResult(file, errorMessage, finalLlmOutput);
    }


    public UpgradeAgentProgressDialog(Frame owner,
                                      String instructions,
                                      Service.FavoriteModel selectedFavorite,
                                      List<ProjectFile> filesToProcess,
                                      Chrome chrome,
                                      @Nullable Integer relatedK,
                                      @Nullable String perFileCommandTemplate,
                                      boolean includeWorkspace,
                                      PostProcessingOption runOption,
                                      String contextFilter,
                                      boolean includeParallelOutput,
                                      String postProcessingInstructions)
    {
        super(owner, "Upgrade Agent Progress", true);
        this.totalFiles = filesToProcess.size();

        setLayout(new BorderLayout(10, 10));
        setPreferredSize(new Dimension(600, 400));

        progressBar = new JProgressBar(0, totalFiles);
        progressBar.setStringPainted(true);
        progressBar.setString("0 of " + totalFiles + " files processed");

        outputTextArea = new JTextArea();
        outputTextArea.setEditable(false);
        outputTextArea.setLineWrap(true);
        outputTextArea.setWrapStyleWord(true);
        outputTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane outputScrollPane = new JScrollPane(outputTextArea);

        JPanel outputPanel = new JPanel(new BorderLayout(5, 5));
        outputPanel.add(new JLabel("Processing Output and Errors:"), BorderLayout.NORTH);
        outputPanel.add(outputScrollPane, BorderLayout.CENTER);
        outputPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));

        cancelButton = new JButton("Cancel");

        llmLineCountLabel = new JLabel("Lines received: 0");
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        topPanel.add(new JLabel("Processing files..."), BorderLayout.NORTH);
        topPanel.add(progressBar, BorderLayout.CENTER);
        topPanel.add(llmLineCountLabel, BorderLayout.SOUTH);
        topPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        add(topPanel, BorderLayout.NORTH);

        this.labelUpdateDebounceTimer = new javax.swing.Timer(100, e -> llmLineCountLabel.setText("Lines received: " + llmLineCount.get()));
        this.labelUpdateDebounceTimer.setRepeats(false);

        add(outputPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(cancelButton);
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        add(buttonPanel, BorderLayout.SOUTH);

        worker = new SwingWorker<>() {
            @Override
            protected TaskResult doInBackground() {
                var contextManager = chrome.getContextManager();
                var service = contextManager.getService();
                var model = requireNonNull(service.getModel(selectedFavorite.modelName(), selectedFavorite.reasoning()));

                @Nullable Integer maxConcurrentRequests = service.getMaxConcurrentRequests(model);
                @Nullable Integer tokensPerMinute    = service.getTokensPerMinute(model);

                final TokenRateLimiter rateLimiter;
                int poolSize;
                if (maxConcurrentRequests != null) {
                    poolSize = Math.min(maxConcurrentRequests, Math.max(1, filesToProcess.size()));
                    rateLimiter = null;
                } else if (tokensPerMinute != null) {
                    rateLimiter = new TokenRateLimiter(tokensPerMinute);
                    poolSize = 100;
                } else {
                    throw new IllegalStateException("Neither max_concurrent_requests nor tokens_per_minute defined for model "
                                                    + service.nameOf(model));
                }

                executorService = Executors.newFixedThreadPool(poolSize);
                var frozenContext = contextManager.topContext();

                // ---- 1.  Sort by on-disk size, smallest first ----
                var sortedFiles = filesToProcess.stream()
                                                .sorted(Comparator.comparingLong(UpgradeAgentProgressDialog::fileSize))
                                                .toList();

                // If there are files to process, start by preloading the prefix cache
                if (!sortedFiles.isEmpty()) {
                    // Publish initial state for preloading, 0 files processed so far
                    publish(new ProgressData(0, "", "Preloading prefix cache..."));

                    // Process the very first file synchronously to warm up caches
                    if (!isCancelled()) {
                        var firstResult = processSingleFile(sortedFiles.getFirst(),
                                                            contextManager,
                                                            model,
                                                            instructions,
                                                            includeWorkspace,
                                                            relatedK,
                                                            perFileCommandTemplate,
                                                            frozenContext,
                                                            contextFilter,
                                                            rateLimiter);
                        processedFileCount.set(1); // Mark the first file as processed
                        // Publish result for the first file
                        publish(new ProgressData(processedFileCount.get(), firstResult.file().toString(), firstResult.errorMessage()));
                    }

                    // Early exit if user cancelled during first file processing
                    if (isCancelled()) {
                        executorService.shutdownNow();
                        return new TaskResult(contextManager, instructions,
                                              List.of(), new HashSet<>(filesToProcess),
                                              new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED, "User cancelled operation."));
                    }
                }

                // Submit the remaining files for concurrent processing
                CompletionService<FileProcessingResult> completionService = new ExecutorCompletionService<>(executorService);
                // Skip the first file as it was processed synchronously
                sortedFiles.stream().skip(1).forEach(file -> {
                    if (!isCancelled()) {
                        completionService.submit(() -> processSingleFile(file,
                                                                         contextManager,
                                                                         model,
                                                                         instructions,
                                                                         includeWorkspace,
                                                                         relatedK,
                                                                         perFileCommandTemplate,
                                                                         frozenContext,
                                                                         contextFilter,
                                                                         rateLimiter));
                    }
                });
                executorService.shutdown(); // No new tasks will be submitted

                List<FileProcessingResult> results = new ArrayList<>();
                // Collect results for remaining files. Loop totalFiles - 1 times since one file was processed upfront.
                // If filesToProcess is 0 or 1, this loop won't run.
                for (int i = 0; i < filesToProcess.size() - 1; i++) {
                    if (isCancelled()) {
                        break; // Exit loop if worker is cancelled
                    }
                    try {
                        Future<FileProcessingResult> future = completionService.take(); // Blocks until a task completes
                        FileProcessingResult result = future.get(); // Retrieves result or re-throws exception
                        results.add(result);
                        // Increment count and publish progress for the completed file
                        publish(new ProgressData(processedFileCount.incrementAndGet(), result.file().toString(), result.errorMessage()));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); // Restore interrupt status
                        break; // Exit loop due to interruption
                    } catch (ExecutionException e) {
                        // Log unexpected errors from file processing tasks, and increment count to show progress
                        logger.error("Error processing file", e.getCause());
                        processedFileCount.incrementAndGet();
                    }
                }

                // Wait for any remaining tasks to complete if cancelled
                try {
                    while (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                        if (isCancelled()) {
                            executorService.shutdownNow();
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    executorService.shutdownNow();
                    Thread.currentThread().interrupt();
                }

                var uiMessageText = results.stream()
                                           .filter(r -> !r.llmOutput().isBlank())
                                           .map(r -> "## " + r.file() + "\n" + r.llmOutput() + "\n\n")
                                           .collect(Collectors.joining());

                var uiMessages = !uiMessageText.isEmpty()
                                 ? List.of(new UserMessage(instructions),
                                           CodePrompts.redactAiMessage(new AiMessage(uiMessageText), EditBlockConflictsParser.instance).orElse(new AiMessage("")))
                                 : List.<ChatMessage>of();

                List<String> failures = results.stream()
                                               .filter(r -> r.errorMessage() != null)
                                               .map(r -> r.file() + ": " + r.errorMessage())
                                               .toList();

                TaskResult.StopDetails stopDetails;
                if (failures.isEmpty() && !isCancelled()) {
                    stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
                } else if (isCancelled()) {
                    stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED, "User cancelled operation.");
                }
                else {
                    stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.TOOL_ERROR, String.join("\n", failures));
                }

                return new TaskResult(contextManager, instructions, uiMessages, new HashSet<>(filesToProcess), stopDetails);
            }

            @Override
            protected void process(List<ProgressData> chunks) {
                for (var data : chunks) {
                    // Update progress bar and its string based on actual processed count
                    progressBar.setValue(data.processedCount());
                    progressBar.setString(String.format("%d of %d files processed", data.processedCount(), totalFiles));

                    // Append messages to the output area
                    if (!data.fileName().isEmpty()) { // This chunk relates to a specific file
                        if (data.errorMessage() != null) {
                            outputTextArea.append("Error processing " + data.fileName() + ": " + data.errorMessage() + "\n");
                        } else {
                            outputTextArea.append("Processed: " + data.fileName() + "\n");
                        }
                    } else if (data.errorMessage() != null && !data.errorMessage().isEmpty()) {
                        // This handles general messages, e.g., "Preloading prefix cache..."
                        outputTextArea.append(data.errorMessage() + "\n");
                    }
                    outputTextArea.setCaretPosition(outputTextArea.getDocument().getLength());
                }
            }

            @Override
            protected void done() {
                // Ensure executor service is shut down if it's still running
                if (executorService != null && !executorService.isTerminated()) {
                    executorService.shutdownNow();
                }
                labelUpdateDebounceTimer.stop(); // Stop the timer for LLM line count updates
                cancelButton.setText("Close"); // Change button text to "Close"
                // Remove existing action listener and add one to simply close the dialog
                Arrays.stream(cancelButton.getActionListeners()).forEach(cancelButton::removeActionListener);
                cancelButton.addActionListener(e -> setVisible(false));

                // Handle cancellation scenario
                if (isCancelled()) {
                    progressBar.setString("Cancelled. " + processedFileCount.get() + " of " + totalFiles + " files processed.");
                    outputTextArea.append("\n``` Operation Cancelled by User ```\n");
                    return;
                }

                TaskResult result;
                try {
                    result = get(); // Retrieve the final result from doInBackground
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("Error during background task execution", e);
                    // Re-throw as RuntimeException, as caught in caller
                    throw new RuntimeException("Task failed: " + e.getMessage(), e);
                }

                // Add task result to history
                var contextManager = chrome.getContextManager();
                Thread.ofPlatform().start(() -> contextManager.addToHistory(result, true));
                var mainIo = contextManager.getIo();

                llmLineCountLabel.setText("Lines received: " + llmLineCount.get()); // Final update to LLM line count
                progressBar.setValue(totalFiles); // Ensure progress bar shows 100% completion
                progressBar.setString("Completed. " + totalFiles + " of " + totalFiles + " files processed."); // Final progress text

                outputTextArea.append("\nParallel processing complete.\n");

                // If no post-processing option is selected, we are done
                // If no post-processing option is selected, we are done
                if (runOption == PostProcessingOption.NONE) {
                    return;
                }

                String buildFailureText = "";
                var verificationCommand = BuildAgent.determineVerificationCommand(contextManager);
                if (verificationCommand != null && !verificationCommand.isBlank()) {
                    try {
                        mainIo.llmOutput("\nRunning verification command: " + verificationCommand, ChatMessageType.CUSTOM);
                        mainIo.llmOutput("\n```bash\n", ChatMessageType.CUSTOM);
                        Environment.instance.runShellCommand(verificationCommand,
                                                             contextManager.getProject().getRoot(),
                                                             line -> mainIo.llmOutput(line + "\n", ChatMessageType.CUSTOM));
                    } catch (InterruptedException e) {
                        chrome.llmOutput("# Build canceled", ChatMessageType.AI);
                        return;
                    } catch (Environment.SubprocessException e) {
                        buildFailureText = e.getMessage() + "\n\n" + e.getOutput();
                    }
                }

                if (postProcessingInstructions.isEmpty() && buildFailureText.isEmpty()) {
                    logger.debug("Build successful or not run, and parallel output processing was not requested");
                }

                var files = filesToProcess.stream().map(ProjectFile::toString).collect(Collectors.joining("\n"));
                var parallelDetails = includeParallelOutput
                                      ? "The output from the parallel processing was:\n```\n%s```".formatted(Messages.getText(result.output().messages().getLast()))
                                      : "The task was applied to the following files:\n```\n%s```".formatted(files);
                var effectiveGoal = postProcessingInstructions.isBlank()
                                    ? "Please fix the problems."
                                    : "Here are the postprocessing instructions:\n```\n%s```".formatted(postProcessingInstructions);

                var agentInstructions = """
                                        I just finished a parallel upgrade task with the following instructions:
                                        ```
                                        %s
                                        ```
                                        
                                        %s
                                        
                                        Here is the output from the verification command:
                                        ```
                                        %s
                                        ```
                                        
                                        %s
                                        """.formatted(instructions, parallelDetails, buildFailureText, effectiveGoal);

                if (runOption == PostProcessingOption.ASK) {
                    outputTextArea.append("Ask command has been invoked. You can close this window.\n");
                    chrome.getInstructionsPanel().runAskCommand(agentInstructions);
                    return;
                }

                outputTextArea.append("Architect has been invoked. You can close this window.\n");
                contextManager.submitUserTask("Architect post-upgrade build fix", () -> {
                    var options = new ArchitectAgent.ArchitectOptions(false, false, false, true, true, false, false, false, false);
                    chrome.getInstructionsPanel().runArchitectCommand(agentInstructions, options);
                });
            }
        };

        // Removed SwingWorker's default progress listener as we handle progress manually via publish/process to avoid scaling issues.
        // The progress bar now directly reflects processed file count via ProgressData.

        cancelButton.addActionListener(e -> {
            if (!worker.isDone()) {
                worker.cancel(true);
                if (executorService != null) {
                    executorService.shutdownNow();
                }
            } else { // Worker is done, button is "Close"
                setVisible(false);
            }
        });

        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE); // Prevent closing via 'X' until worker is done or explicitly cancelled
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                if (!worker.isDone()) {
                    int choice = JOptionPane.showConfirmDialog(UpgradeAgentProgressDialog.this,
                                                               "Are you sure you want to cancel the upgrade process?", "Confirm Cancel",
                                                               JOptionPane.YES_NO_OPTION,
                                                               JOptionPane.QUESTION_MESSAGE);
                    if (choice == JOptionPane.YES_OPTION) {
                        worker.cancel(true);
                        if (executorService != null) {
                            executorService.shutdownNow();
                        }
                    }
                } else {
                    setVisible(false);
                }
            }
        });


        pack();
        setLocationRelativeTo(owner);
        worker.execute();
    }

    /**
     *
     */
    private class DialogConsoleIO implements IConsoleIO {
        private final UpgradeAgentProgressDialog dialog;
        private final String fileContext;
        private final StringBuilder llmOutput = new StringBuilder();

        /**
         * @param fileContext To prefix messages related to a specific file
         */
        private DialogConsoleIO(UpgradeAgentProgressDialog dialog, String fileContext) {
            this.dialog = dialog;
            this.fileContext = fileContext;
        }

        public String getLlmOutput() {
            return llmOutput.toString();
        }

        private void appendToOutput(String text) {
            SwingUtilities.invokeLater(() -> {
                String prefix = "[%s] ".formatted(fileContext);
                dialog.outputTextArea.append(prefix + text + "\n");
                dialog.outputTextArea.setCaretPosition(dialog.outputTextArea.getDocument().getLength());
            });
        }

        @Override
        public void toolError(String message, String title) {
            var msg = "[%s] %s: %s\n".formatted(fileContext, title, message);
            logger.error(msg);
            SwingUtilities.invokeLater(() -> {
                outputTextArea.append(msg);
                outputTextArea.setCaretPosition(outputTextArea.getDocument().getLength());
            });
        }

        @Override
        public void llmOutput(String token, ChatMessageType type, boolean isNewMessage) {
            llmOutput.append(token);
            long newLines = token.chars().filter(c -> c == '\n').count();
            if (newLines > 0) {
                llmLineCount.addAndGet((int) newLines);
                SwingUtilities.invokeLater(labelUpdateDebounceTimer::restart);
            }
        }

        @Override
        public void systemOutput(String message) {
            appendToOutput(message);
        }

        @Override
        public void systemNotify(String message, String title, int messageType) {
            appendToOutput(title + ": " + message);
        }

        @Override
        public List<ChatMessage> getLlmRawMessages() {
            return List.of();
        }
    }
}
