package io.github.jbellis.brokk.gui.dialogs;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.agents.BlitzForge;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * UI-only progress dialog for BlitzForge runs. Implements BlitzForge.Listener and renders progress.
 * No execution logic lives here; the engine invokes these callbacks from worker threads.
 */
public final class BlitzForgeProgressDialog extends JDialog implements BlitzForge.Listener {

    // Back-compat: nested enums expected by BlitzForgeDialog imports
    public enum ParallelOutputMode {
        NONE,
        ALL,
        CHANGED
    }

    public enum PostProcessingOption {
        NONE,
        ASK,
        ARCHITECT
    }

    private static final Logger logger = LogManager.getLogger(BlitzForgeProgressDialog.class);

    private final Chrome chrome;
    private final Runnable cancelCallback;

    private final JProgressBar progressBar;
    private final JTextArea outputTextArea;
    private final MaterialButton cancelButton;

    private final JLabel llmLineCountLabel;
    private final javax.swing.Timer llmLineCountTimer;
    private final AtomicInteger llmLineCount = new AtomicInteger(0);

    private volatile int totalFiles = 0;
    private final AtomicInteger processedFileCount = new AtomicInteger(0);
    private final AtomicBoolean done = new AtomicBoolean(false);

public BlitzForgeProgressDialog(Chrome chrome, BlitzForge.RunConfig config, Runnable cancelCallback) {
        super(chrome.getFrame(), "BlitzForge Progress", false);
        assert SwingUtilities.isEventDispatchThread() : "Must construct dialog on EDT";

        this.chrome = chrome;
        this.cancelCallback = cancelCallback;

        chrome.disableActionButtons();

        setLayout(new BorderLayout(10, 10));
        setPreferredSize(new Dimension(600, 400));

        progressBar = new JProgressBar(0, 1);
        progressBar.setStringPainted(true);
        progressBar.setString("Initializing...");

        outputTextArea = new JTextArea();
        outputTextArea.setEditable(false);
        outputTextArea.setLineWrap(true);
        outputTextArea.setWrapStyleWord(true);
        outputTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        var outputScrollPane = new JScrollPane(outputTextArea);

        var outputPanel = new JPanel(new BorderLayout(5, 5));
        outputPanel.add(new JLabel("Processing Output and Errors:"), BorderLayout.NORTH);
        outputPanel.add(outputScrollPane, BorderLayout.CENTER);
        outputPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));

        cancelButton = new MaterialButton("Cancel");
        cancelButton.addActionListener(e -> {
            if (!done.get()) {
                try {
                    this.cancelCallback.run();
                } catch (Exception ex) {
                    logger.warn("Cancel callback threw", ex);
                }
            } else {
                setVisible(false);
            }
        });

        llmLineCountLabel = new JLabel("Lines received: 0");
        var topPanel = new JPanel(new BorderLayout(5, 5));
        topPanel.add(new JLabel("Processing files..."), BorderLayout.NORTH);
        topPanel.add(progressBar, BorderLayout.CENTER);
        topPanel.add(llmLineCountLabel, BorderLayout.SOUTH);
        topPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        add(topPanel, BorderLayout.NORTH);

        llmLineCountTimer = new javax.swing.Timer(100, e -> llmLineCountLabel.setText("Lines received: " + llmLineCount.get()));
        llmLineCountTimer.setRepeats(true);
        llmLineCountTimer.setCoalesce(true);

        add(outputPanel, BorderLayout.CENTER);

        var buttonPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        buttonPanel.add(cancelButton);
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        add(buttonPanel, BorderLayout.SOUTH);

        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent windowEvent) {
                if (!done.get()) {
                    int choice = chrome.showConfirmDialog(
                            BlitzForgeProgressDialog.this,
                            "Are you sure you want to cancel the upgrade process?",
                            "Confirm Cancel",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE);
                    if (choice == JOptionPane.YES_OPTION) {
                        try {
                            BlitzForgeProgressDialog.this.cancelCallback.run();
                        } catch (Exception ex) {
                            logger.warn("Cancel callback threw", ex);
                        }
                    }
                } else {
                    setVisible(false);
                }
            }
        });

        pack();
        setLocationRelativeTo(chrome.getFrame());

        // start LLM count timer immediately; it is cheap and keeps label fresh
        llmLineCountTimer.start();
    }

    // Back-compat constructor signature used by older BlitzForgeDialog callers.
    // It adapts the legacy arguments into a BlitzForge.RunConfig and delegates to the primary constructor.
    public BlitzForgeProgressDialog(
            String instructions,
            String action,
            Object favoriteModel,
            java.util.List<ProjectFile> files,
            Chrome chrome,
            @Nullable Integer relatedK,
            @Nullable String perFileCommandTemplate,
            boolean includeWorkspace,
            PostProcessingOption postProcessing,
            String contextFilter,
            ParallelOutputMode outputMode,
            boolean buildFirst,
            String postProcessingInstructions) {
        this(chrome,
             new BlitzForge.RunConfig(
                     instructions,
                     null, // legacy favoriteModel is resolved elsewhere; default to null model
                     includeWorkspace,
                     relatedK,
                     perFileCommandTemplate,
                     contextFilter,
                     mapOutputMode(outputMode),
                     buildFirst,
                     postProcessingInstructions,
                     mapAction(action)),
             () -> {});
    }

    private static BlitzForge.ParallelOutputMode mapOutputMode(ParallelOutputMode m) {
        return switch (m) {
            case ALL -> BlitzForge.ParallelOutputMode.ALL;
            case CHANGED -> BlitzForge.ParallelOutputMode.CHANGED;
            case NONE -> BlitzForge.ParallelOutputMode.NONE;
        };
    }

    private static BlitzForge.Action mapAction(@Nullable String a) {
        if (a == null) return BlitzForge.Action.CODE;
        var upper = a.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (upper) {
            case "ASK" -> BlitzForge.Action.ASK;
            case "MERGE" -> BlitzForge.Action.MERGE;
            case "CODE" -> BlitzForge.Action.CODE;
            default -> BlitzForge.Action.CODE;
        };
    }

    private void appendOutput(String text) {
        SwingUtilities.invokeLater(() -> {
            outputTextArea.append(text + "\n");
            outputTextArea.setCaretPosition(outputTextArea.getDocument().getLength());
        });
    }

    private static String fileContext(ProjectFile file) {
        return "[" + file + "] ";
    }

    @Override
    public void onStart(int total) {
        totalFiles = Math.max(0, total);
        SwingUtilities.invokeLater(() -> {
            progressBar.setMaximum(totalFiles == 0 ? 1 : totalFiles);
            progressBar.setValue(0);
            progressBar.setString(String.format("0 of %d files processed", totalFiles));
        });
    }

    @Override
    public void onFileStart(ProjectFile file) {
        appendOutput(fileContext(file) + "Processing...");
    }

    @Override
    public void onProgress(int processed, int total) {
        processedFileCount.set(processed);
        SwingUtilities.invokeLater(() -> {
            progressBar.setMaximum(Math.max(1, total));
            progressBar.setValue(processed);
            progressBar.setString(String.format("%d of %d files processed", processed, total));
        });
    }

    @Override
    public IConsoleIO getConsoleIO(ProjectFile file) {
        // Provide a per-file console that counts LLM lines for this dialog
        return new DialogConsoleIO(this, "[" + file + "] ");
    }


    @Override
    public void onFileResult(ProjectFile file, boolean edited, @Nullable String errorMessage, String llmOutput) {
        // Update progress here (since onProgress was removed)
        int processed = processedFileCount.incrementAndGet();
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(processed);
            int denom = totalFiles > 0 ? totalFiles : progressBar.getMaximum();
            if (denom <= 0) denom = 1;
            progressBar.setString(String.format("%d of %d files processed", processed, denom));
        });

        if (errorMessage != null) {
            appendOutput(fileContext(file) + "Error: " + errorMessage);
        } else {
            appendOutput(fileContext(file) + "Processed" + (edited ? " (changed)" : ""));
        }
        if (!llmOutput.isBlank()) {
            // Emit as a new AI message in the main UI stream, preserving previous behavior
            chrome.llmOutput(llmOutput, ChatMessageType.AI, true, false);
        }
    }


    @Override
    public void onDone(TaskResult result) {
        // Ensure UI updates run on EDT
        SwingUtilities.invokeLater(() -> {
            try {
                llmLineCountTimer.stop();
                done.set(true);
                cancelButton.setText("Close");
                // Remove old action listeners to avoid multiple registrations
                for (var al : cancelButton.getActionListeners()) {
                    cancelButton.removeActionListener(al);
                }
                cancelButton.addActionListener(e -> setVisible(false));
                progressBar.setValue(progressBar.getMaximum());
                progressBar.setString(String.format("Completed. %d of %d files processed.", progressBar.getMaximum(), progressBar.getMaximum()));
                appendOutput("Parallel processing complete.");

                // Append TaskResult to history (same UI plumbing as prior implementation)
                ContextManager contextManager = chrome.getContextManager();
                try (var scope = contextManager.beginTask("", true)) {
                    scope.append(result);
                }

            } catch (Exception e) {
                logger.error("Error finalizing BlitzForge dialog", e);
                chrome.toolError("Error finalizing BlitzForge: " + e.getMessage(), "Error");
            } finally {
                chrome.enableActionButtons();
            }
        });
    }

    private class DialogConsoleIO implements IConsoleIO {
        private final BlitzForgeProgressDialog dialog;
        private final String fileContext;

        /** @param fileContext To prefix messages related to a specific file */
        private DialogConsoleIO(BlitzForgeProgressDialog dialog, String fileContext) {
            this.dialog = dialog;
            this.fileContext = fileContext;
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
        public void llmOutput(String token, ChatMessageType type, boolean isNewMessage, boolean isReasoning) {
            long newLines = token.chars().filter(c -> c == '\n').count();
            if (newLines > 0) {
                llmLineCount.addAndGet((int) newLines);
                // Start the timer if it's not already running
                SwingUtilities.invokeLater(() -> {
                    if (!dialog.llmLineCountTimer.isRunning()) {
                        dialog.llmLineCountTimer.start();
                    }
                });
            }
        }

        @Override
        public List<ChatMessage> getLlmRawMessages() {
            return List.of();
        }
    }
}
