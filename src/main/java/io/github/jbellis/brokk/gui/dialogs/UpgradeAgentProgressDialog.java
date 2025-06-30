package io.github.jbellis.brokk.gui.dialogs;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.agents.ArchitectAgent;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.agents.CodeAgent;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.util.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

public class UpgradeAgentProgressDialog extends JDialog {

    private static final Logger logger = LogManager.getLogger(UpgradeAgentProgressDialog.class);
    private final JProgressBar progressBar;
    private final JTextArea outputTextArea;
    private final JButton cancelButton;
    private final SwingWorker<TaskResult, ProgressData> worker;
    private final int totalFiles;
    private final AtomicInteger processedFileCount = new AtomicInteger(0);
    private final AtomicInteger llmLineCount = new AtomicInteger(0);
    private final javax.swing.Timer labelUpdateDebounceTimer;
    private final JLabel llmLineCountLabel;
    private final @Nullable Integer relatedK;
    private final @Nullable String perFileCommandTemplate;
    private final boolean includeWorkspace;
    private final ExecutorService executorService; // Moved here for wider access


    private record ProgressData(String fileName, @Nullable String errorMessage) { }
    private record FileProcessingResult(ProjectFile file, @Nullable String errorMessage, String llmOutput) { }


    public UpgradeAgentProgressDialog(Frame owner,
                                      String instructions,
                                      Service.FavoriteModel selectedFavorite,
                                      List<ProjectFile> filesToProcess,
                                      Chrome chrome,
                                      @Nullable Integer relatedK,
                                      @Nullable String perFileCommandTemplate,
                                      boolean includeWorkspace,
                                      boolean invokeArchitect)
    {
        super(owner, "Upgrade Agent Progress", true);
        this.totalFiles = filesToProcess.size();
        this.relatedK = relatedK;
        this.perFileCommandTemplate = perFileCommandTemplate;
        this.includeWorkspace = includeWorkspace;

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

        executorService = Executors.newFixedThreadPool(Math.min(200, Math.max(1, filesToProcess.size())));
        worker = new SwingWorker<>() {
            @Override
            protected TaskResult doInBackground() {
                var contextManager = chrome.getContextManager();
                var service = contextManager.getService();
                CompletionService<FileProcessingResult> completionService = new ExecutorCompletionService<>(executorService);

                for (ProjectFile file : filesToProcess) {
                    if (isCancelled()) {
                        break;
                    }
                    var ctx = contextManager.topContext();
                    completionService.submit(() -> {
                        var dialogConsoleIO = new DialogConsoleIO(UpgradeAgentProgressDialog.this, file.toString());
                        String errorMessage = null;
                        if (Thread.currentThread().isInterrupted() || isCancelled()) {
                            errorMessage = "Cancelled by user.";
                            dialogConsoleIO.systemOutput("Processing cancelled by user for file: " + file);
                        } else {
                            var model = requireNonNull(service.getModel(selectedFavorite.modelName(), selectedFavorite.reasoning()));
                            var agent = new CodeAgent(contextManager, model, dialogConsoleIO);

                            List<ChatMessage> readOnlyMessages = new ArrayList<>();
                            try {
                                if (UpgradeAgentProgressDialog.this.includeWorkspace) {
                                    readOnlyMessages.addAll(CodePrompts.instance.getWorkspaceContentsMessages(ctx));
                                    dialogConsoleIO.systemOutput("Including workspace contents in context.");
                                }
                                if (UpgradeAgentProgressDialog.this.relatedK != null) {
                                    var acFragment = contextManager.liveContext().buildAutoContext(UpgradeAgentProgressDialog.this.relatedK);
                                    if (!acFragment.text().isBlank()) {
                                        var msgText = """
                                                          <related_classes>
                                                          The user requested to include the top %d related classes.
                                                          
                                                          %s
                                                          </related_classes>
                                                          """.stripIndent().formatted(UpgradeAgentProgressDialog.this.relatedK, acFragment.text());
                                        readOnlyMessages.add(new UserMessage(msgText));
                                        dialogConsoleIO.systemOutput("Added " + UpgradeAgentProgressDialog.this.relatedK + " related classes to context.");
                                    }
                                }

                                // Execute per-file command if provided
                                if (UpgradeAgentProgressDialog.this.perFileCommandTemplate != null && !UpgradeAgentProgressDialog.this.perFileCommandTemplate.isBlank()) {
                                    dialogConsoleIO.systemOutput("Preparing to execute per-file command for " + file);
                                    MustacheFactory mf = new DefaultMustacheFactory();
                                    Mustache mustache = mf.compile(new StringReader(UpgradeAgentProgressDialog.this.perFileCommandTemplate), "perFileCommand");
                                    StringWriter writer = new StringWriter();
                                    Map<String, Object> scope = new HashMap<>();
                                    scope.put("filepath", file.toString()); // Using relative path
                                    mustache.execute(writer, scope).flush();
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

                            if (errorMessage == null) {
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
                            }
                        }
                        return new FileProcessingResult(file, errorMessage, dialogConsoleIO.getLlmOutput());
                    });
                }
                executorService.shutdown();

                List<FileProcessingResult> results = new ArrayList<>();
                for (int i = 0; i < filesToProcess.size(); i++) {
                    if (isCancelled()) {
                        break;
                    }
                    try {
                        Future<FileProcessingResult> future = completionService.take();
                        FileProcessingResult result = future.get();
                        results.add(result);
                        publish(new ProgressData(result.file().toString(), result.errorMessage()));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (ExecutionException e) {
                        logger.error("Error processing file", e.getCause());
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

                List<ChatMessage> uiMessages = !uiMessageText.isEmpty()
                                               ? List.of(new UserMessage(instructions),
                                                         new AiMessage(uiMessageText))
                                               : List.of();

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
                for (ProgressData ignored : chunks) {
                    int currentCount = processedFileCount.incrementAndGet();
                    progressBar.setValue(currentCount);
                    progressBar.setString(String.format("%d of %d files processed", currentCount, totalFiles));
                    outputTextArea.setCaretPosition(outputTextArea.getDocument().getLength());
                }
            }

            @Override
            protected void done() {
                if (!executorService.isTerminated()) {
                    executorService.shutdownNow();
                }
                labelUpdateDebounceTimer.stop();
                cancelButton.setText("Close");
                cancelButton.removeActionListener(cancelButton.getActionListeners()[0]); // remove old cancel listener
                cancelButton.addActionListener(e -> setVisible(false));

                if (isCancelled()) {
                    progressBar.setString("Cancelled. " + processedFileCount.get() + " of " + totalFiles + " files processed.");
                    outputTextArea.append("\n--- Operation Cancelled by User ---\n");
                    return;
                }

                TaskResult result; // To catch any exception from doInBackground itself and get result
                try {
                    result = get();
                } catch (InterruptedException | ExecutionException e) {
                    logger.error(e);
                    throw new RuntimeException(e);
                }
                var contextManager = chrome.getContextManager();
                Thread.ofPlatform().start(() -> contextManager.addToHistory(result, true));
                var mainIo = contextManager.getIo();

                llmLineCountLabel.setText("Lines received: " + llmLineCount.get());
                progressBar.setValue(totalFiles); // Ensure it shows full completion
                progressBar.setString("Completed. " + totalFiles + " of " + totalFiles + " files processed.");

                outputTextArea.append("\nParallel processing complete.\n");
                if (!invokeArchitect) {
                    return;
                }

                outputTextArea.append("Architect has been invoked. You can close this window.\n");
                contextManager.submitUserTask("Architect post-upgrade build fix", () -> {
                    var verificationCommand = BuildAgent.determineVerificationCommand(contextManager);
                    if (verificationCommand == null || verificationCommand.isBlank()) {
                        logger.debug("No verification command found, skipping architect invocation.");
                        return;
                    }

                    String outputForArchitect = null;
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
                        outputForArchitect = e.getMessage() + "\n\n" + e.getOutput();
                    }

                    if (outputForArchitect != null) {
                        var files = filesToProcess.stream().map(ProjectFile::toString).collect(Collectors.joining("\n"));
                        var architectGoal = """
                                        I just finished a parallel upgrade task with the following instructions:
                                        ---
                                        %s
                                        ---
                                        
                                        The task was applied to the following files:
                                        ---
                                        %s
                                        ---
                                        
                                        After the changes, the build is failing with the following output. Please fix the problems.
                                        ---
                                        %s
                                        ---
                                        """.formatted(instructions, files, outputForArchitect);

                        var architect = new ArchitectAgent(contextManager,
                                                           contextManager.getArchitectModel(),
                                                           contextManager.getToolRegistry(),
                                                           architectGoal,
                                                           ArchitectAgent.ArchitectOptions.DEFAULTS);
                        TaskResult architectResult;
                        try {
                            architectResult = architect.execute();
                            contextManager.addToHistory(architectResult, true);
                        } catch (InterruptedException e) {
                            chrome.llmOutput("# Architect canceled", ChatMessageType.CUSTOM);
                        } catch (Exception e) {
                            chrome.toolError("Internal error during Agent command: " + e.getMessage());
                        }
                    }
                });
            }
        };

        cancelButton.addActionListener(e -> {
            if (!worker.isDone()) {
                worker.cancel(true);
                UpgradeAgentProgressDialog.this.executorService.shutdownNow();
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
                        UpgradeAgentProgressDialog.this.executorService.shutdownNow();
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
