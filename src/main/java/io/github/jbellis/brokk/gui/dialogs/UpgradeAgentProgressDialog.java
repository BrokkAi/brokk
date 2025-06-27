package io.github.jbellis.brokk.gui.dialogs;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.TaskResult;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;

public class UpgradeAgentProgressDialog extends JDialog {

    private static final Logger logger = LogManager.getLogger(UpgradeAgentProgressDialog.class);
    private final JProgressBar progressBar;
    private final JTextArea errorTextArea;
    private final JTextArea agentOutputTextArea; // Added for detailed agent output
    private final JButton cancelButton;
    private final SwingWorker<Void, ProgressData> worker;
    private final int totalFiles;
    private final AtomicInteger processedFileCount = new AtomicInteger(0);
    private final @Nullable Integer relatedK;
    private final @Nullable String perFileCommandTemplate;
    private final ExecutorService executorService; // Moved here for wider access


    private record ProgressData(String fileName, @Nullable String errorMessage) { }

    public UpgradeAgentProgressDialog(Frame owner,
                                      String instructions,
                                      Service.FavoriteModel selectedFavorite,
                                      List<ProjectFile> filesToProcess,
                                      Chrome chrome,
                                      @Nullable Integer relatedK,
                                      @Nullable String perFileCommandTemplate)
    {
        super(owner, "Upgrade Agent Progress", true);
        this.totalFiles = filesToProcess.size();
        this.relatedK = relatedK;
        this.perFileCommandTemplate = perFileCommandTemplate;

        setLayout(new BorderLayout(10, 10));
        setPreferredSize(new Dimension(600, 400));

        progressBar = new JProgressBar(0, totalFiles);
        progressBar.setStringPainted(true);
        progressBar.setString("0 of " + totalFiles + " files processed");

        errorTextArea = new JTextArea();
        errorTextArea.setEditable(false);
        errorTextArea.setLineWrap(true);
        errorTextArea.setWrapStyleWord(true);
        JScrollPane errorScrollPane = new JScrollPane(errorTextArea);

        agentOutputTextArea = new JTextArea();
        agentOutputTextArea.setEditable(false);
        agentOutputTextArea.setLineWrap(true);
        agentOutputTextArea.setWrapStyleWord(true);
        agentOutputTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane agentOutputScrollPane = new JScrollPane(agentOutputTextArea);

        JPanel agentOutputPanel = new JPanel(new BorderLayout(5, 5));
        agentOutputPanel.add(new JLabel("Agent Output:"), BorderLayout.NORTH);
        agentOutputPanel.add(agentOutputScrollPane, BorderLayout.CENTER);

        JPanel errorsPanel = new JPanel(new BorderLayout(5, 5));
        errorsPanel.add(new JLabel("File Processing Errors/Status:"), BorderLayout.NORTH);
        errorsPanel.add(errorScrollPane, BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, agentOutputPanel, errorsPanel);
        splitPane.setResizeWeight(0.7); // Give more space to agent output initially
        splitPane.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));

        cancelButton = new JButton("Cancel");

        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        topPanel.add(new JLabel("Processing files..."), BorderLayout.NORTH);
        topPanel.add(progressBar, BorderLayout.CENTER);
        topPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        add(topPanel, BorderLayout.NORTH);

        add(splitPane, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(cancelButton);
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        add(buttonPanel, BorderLayout.SOUTH);

        executorService = Executors.newFixedThreadPool(Math.min(200, Math.max(1, filesToProcess.size())));
        worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                var contextManager = chrome.getContextManager();
                var service = contextManager.getService();

                for (ProjectFile file : filesToProcess) {
                    if (isCancelled()) {
                        break;
                    }
                    executorService.submit(() -> {
                        var dialogConsoleIO = new DialogConsoleIO(UpgradeAgentProgressDialog.this, file.toString());
                        String errorMessage = null;
                        try {
                            if (Thread.currentThread().isInterrupted() || isCancelled()) {
                                errorMessage = "Cancelled by user.";
                                dialogConsoleIO.systemOutput("Processing cancelled by user for file: " + file);
                                return;
                            }
                            dialogConsoleIO.systemOutput("Starting processing for file: " + file);

                            var model = requireNonNull(service.getModel(selectedFavorite.modelName(), selectedFavorite.reasoning()));
                            var agent = new CodeAgent(contextManager, model, dialogConsoleIO);

                            List<ChatMessage> readOnlyMessages = new ArrayList<>();
                            try {
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
                                    try {
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
                                    } catch (Exception e) { // Catches errors in Mustache compilation or other setup
                                        logger.error("Error preparing or executing per-file command for {}", file, e);
                                        errorMessage = "Error with per-file command infrastructure: " + e.getMessage();
                                        dialogConsoleIO.toolError("System error during per-file command setup: " + e.getMessage(), "Command Setup Error");

                                        String errorMsgForLlm = """
                                                                <per_file_command_output command_template="%s">
                                                                Failed to prepare or execute command for file %s: %s
                                                                This was an error in the command execution system, not the command's own output.
                                                                </per_file_command_output>
                                                                """.stripIndent().formatted(UpgradeAgentProgressDialog.this.perFileCommandTemplate, file.toString(), e.getMessage());
                                        readOnlyMessages.add(new UserMessage(errorMsgForLlm));
                                        // Continue to agent.runSingleFileEdit, LLM will be informed.
                                    }
                                }

                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                errorMessage = "Interrupted during message preparation.";
                                dialogConsoleIO.systemOutput("Interrupted during message preparation for file: " + file);
                                return;
                            }

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
                                    dialogConsoleIO.systemOutput("Successfully processed file: " + file);
                                }
                            } catch (Throwable t) {
                                errorMessage = "Unexpected error: " + t.getMessage();
                                logger.error("Unexpected failure while processing {}", file, t);
                            }
                        } finally {
                            publish(new ProgressData(file.toString(), errorMessage));
                            dialogConsoleIO.systemOutput("Finished processing for file: " + file + "\n--------------------");
                        }
                    });
                }
                executorService.shutdown();
                try {
                    // Wait for tasks to complete or for cancellation
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
                return null;
            }

            @Override
            protected void process(List<ProgressData> chunks) {
                for (ProgressData data : chunks) {
                    int currentCount = processedFileCount.incrementAndGet();
                    progressBar.setValue(currentCount);
                    progressBar.setString(String.format("%d of %d files processed", currentCount, totalFiles));
                    if (data.errorMessage() != null) {
                        errorTextArea.append(data.fileName() + ": " + data.errorMessage() + "\n");
                    } else {
                        errorTextArea.append(data.fileName() + ": Finished processing\n");
                    }
                }
            }

            @Override
            protected void done() {
                if (!executorService.isTerminated()) {
                    executorService.shutdownNow();
                }
                cancelButton.setText("Close");
                cancelButton.removeActionListener(cancelButton.getActionListeners()[0]); // remove old cancel listener
                cancelButton.addActionListener(e -> setVisible(false));

                if (isCancelled()) {
                    progressBar.setString("Cancelled. " + processedFileCount.get() + " of " + totalFiles + " files processed.");
                    errorTextArea.append("\n--- Operation Cancelled by User ---\n");
                } else {
                    try {
                        get(); // To catch any exception from doInBackground itself
                        progressBar.setValue(totalFiles); // Ensure it shows full completion
                        progressBar.setString("Completed. " + totalFiles + " of " + totalFiles + " files processed.");
                        if (errorTextArea.getText().isEmpty()) {
                            errorTextArea.setText("All files processed successfully.");
                        } else {
                            errorTextArea.append("\n--- Operation Finished with Errors ---\n");
                        }
                    } catch (Exception e) {
                        progressBar.setString("Error during operation.");
                        errorTextArea.append("\n--- Operation Failed: " + e.getMessage() + " ---\n");
                        // Log the exception from doInBackground if any
                        logger.error("Error in UpgradeAgentSwingWorker", e);
                    }
                }
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

        /**
         * @param fileContext To prefix messages related to a specific file
         */
        private DialogConsoleIO(UpgradeAgentProgressDialog dialog, String fileContext) {
            this.dialog = dialog;
            this.fileContext = fileContext;
        }

        private void appendToAgentOutput(String text, boolean includeFileContext) {
            SwingUtilities.invokeLater(() -> {
                String prefix = includeFileContext ? "[%s] ".formatted(fileContext) : "";
                dialog.agentOutputTextArea.append(prefix + text + "\n");
                dialog.agentOutputTextArea.setCaretPosition(dialog.agentOutputTextArea.getDocument().getLength());
            });
        }

        private void appendTokenToAgentOutput(String token) {
            SwingUtilities.invokeLater(() -> {
                dialog.agentOutputTextArea.append(token);
                dialog.agentOutputTextArea.setCaretPosition(dialog.agentOutputTextArea.getDocument().getLength());
            });
        }

        @Override
        public void toolError(String message, String title) {
            var msg = "[%s] %s: %s".formatted(fileContext, title, message);
            logger.error(msg);
            SwingUtilities.invokeLater(() -> {
                errorTextArea.append(msg);
                errorTextArea.setCaretPosition(errorTextArea.getDocument().getLength());
            });
        }

        @Override
        public void llmOutput(String token, ChatMessageType type, boolean isNewMessage) {
            if (isNewMessage) {
                String prefix = "[" + fileContext + "] [" + type + "] ";
                if (dialog.agentOutputTextArea.getDocument().getLength() > 0) {
                    appendToAgentOutput("\n" + prefix, false); // Newline before new message block
                } else {
                    appendToAgentOutput(prefix, false);
                }
            }
            appendTokenToAgentOutput(token); // Append token directly without extra newline
        }

        @Override
        public void systemOutput(String message) {
            appendToAgentOutput(message, true);
        }

        @Override
        public void systemNotify(String message, String title, int messageType) {
            appendToAgentOutput(title + ": " + message, true);
        }

        @Override
        public List<ChatMessage> getLlmRawMessages() {
            return List.of();
        }
    }
}