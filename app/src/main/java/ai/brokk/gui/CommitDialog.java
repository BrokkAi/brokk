package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitWorkflow;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.dialogs.BaseThemedDialog;
import ai.brokk.gui.dialogs.TextAreaConsoleIO;
import ai.brokk.gui.util.KeyboardShortcutUtil;
import java.awt.*;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class CommitDialog extends BaseThemedDialog {
    private static final Logger logger = LogManager.getLogger(CommitDialog.class);

    private final JTextArea commitMessageArea;
    private final MaterialButton commitButton;
    private final MaterialButton cancelButton;
    private final transient ContextManager contextManager;
    private final transient GitWorkflow workflowService;
    private final transient List<ProjectFile> filesToCommit;
    private final transient Consumer<GitWorkflow.CommitResult> onCommitSuccessCallback;
    private final transient Chrome chrome;

    public CommitDialog(
            @Nullable Window owner,
            Chrome chrome,
            ContextManager contextManager,
            GitWorkflow workflowService,
            List<ProjectFile> filesToCommit,
            Consumer<GitWorkflow.CommitResult> onCommitSuccessCallback) {
        this(owner, chrome, contextManager, workflowService, filesToCommit, null, onCommitSuccessCallback);
    }

    public CommitDialog(
            @Nullable Window owner,
            Chrome chrome,
            ContextManager contextManager,
            GitWorkflow workflowService,
            List<ProjectFile> filesToCommit,
            @Nullable String prefilledMessage,
            Consumer<GitWorkflow.CommitResult> onCommitSuccessCallback) {
        super(owner, "Commit Changes");
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.workflowService = workflowService;
        this.filesToCommit = filesToCommit;
        this.onCommitSuccessCallback = onCommitSuccessCallback;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        commitMessageArea = new JTextArea(10, 50);
        commitMessageArea.setLineWrap(true);
        commitMessageArea.setWrapStyleWord(true);

        // If pre-filled message provided, use it directly; otherwise TACIO handles placeholder
        if (prefilledMessage != null && !prefilledMessage.isEmpty()) {
            commitMessageArea.setText(prefilledMessage);
        }

        JScrollPane scrollPane = new JScrollPane(commitMessageArea);

        // Use MaterialButton so styling matches other dialogs; commit is primary (blue background + white text)
        commitButton = new MaterialButton("Commit");
        commitButton.setEnabled(false); // Initially disabled until message is ready or user types
        commitButton.addActionListener(e -> performCommit());
        SwingUtil.applyPrimaryButtonStyle(commitButton);

        cancelButton = new MaterialButton("Cancel");
        cancelButton.addActionListener(e -> dispose());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(cancelButton);
        buttonPanel.add(commitButton);

        // Add padding around the dialog content
        JPanel contentPanel = new JPanel(new BorderLayout(0, 10));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        contentPanel.add(scrollPane, BorderLayout.CENTER);
        contentPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Route layout through content root instead of directly to dialog
        JPanel root = getContentRoot();
        root.setLayout(new BorderLayout(10, 10));
        root.add(contentPanel, BorderLayout.CENTER);

        KeyboardShortcutUtil.registerDialogEscapeKey(getRootPane(), this::dispose);

        pack();
        setLocationRelativeTo(owner);

        // Enable commit button when text area is enabled and not empty (after LLM or manual input)
        commitMessageArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void changedUpdate(DocumentEvent e) {
                checkCommitButtonState();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                checkCommitButtonState();
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                checkCommitButtonState();
            }
        });

        // Only initiate LLM suggestion if no pre-filled message was provided
        if (prefilledMessage == null || prefilledMessage.isEmpty()) {
            initiateCommitMessageSuggestion();
        } else {
            commitMessageArea.requestFocusInWindow();
            checkCommitButtonState();
        }
    }

    private void checkCommitButtonState() {
        // Never enable the commit button while an LLM suggestion is actively streaming.
        if (streamingSuggestionInProgress) {
            commitButton.setEnabled(false);
            return;
        }

        if (commitMessageArea.isEnabled()) {
            String text = commitMessageArea.getText();
            boolean hasNonCommentText = Arrays.stream(text.split("\n"))
                    .anyMatch(line -> !line.trim().isEmpty() && !line.trim().startsWith("#"));
            commitButton.setEnabled(hasNonCommentText);
        } else {
            commitButton.setEnabled(false);
        }
    }

    // True while an LLM commit suggestion is actively streaming into the text area.
    private volatile boolean streamingSuggestionInProgress = false;

    private void initiateCommitMessageSuggestion() {
        // Mark streaming as in-progress so the commit button stays disabled until finished.
        streamingSuggestionInProgress = true;
        var streamingIO = new TextAreaConsoleIO(commitMessageArea, chrome, "Inferring commit message");

        var worker = new ExceptionAwareSwingWorker<String, Void>(chrome) {
            @Override
            protected String doInBackground() throws Exception {
                return workflowService.suggestCommitMessageStreaming(filesToCommit, false, streamingIO);
            }

            @Override
            protected void done() {
                super.done(); // centralized exception handling
                try {
                    get();
                    SwingUtilities.invokeLater(() -> {
                        streamingIO.onComplete();
                        commitMessageArea.requestFocusInWindow();
                        commitMessageArea.setCaretPosition(0);
                        checkCommitButtonState();
                    });
                } catch (InterruptedException | java.util.concurrent.ExecutionException ignored) {
                    // ExceptionAwareSwingWorker.done() already handled logging/notifications
                    // Ensure text area is usable so user can type manually
                    SwingUtilities.invokeLater(() -> {
                        streamingIO.onComplete();
                        commitMessageArea.setText("");
                        commitMessageArea.requestFocusInWindow();
                    });
                } finally {
                    // Clear streaming flag regardless of success/failure/cancel.
                    streamingSuggestionInProgress = false;
                    // Re-evaluate button state on the EDT to ensure UI consistency.
                    SwingUtilities.invokeLater(CommitDialog.this::checkCommitButtonState);
                }
            }
        };
        worker.execute();
    }

    private void performCommit() {
        String msg = commitMessageArea.getText().trim();
        if (msg.isEmpty()) {
            // This case should ideally be prevented by button enablement, but as a safeguard:
            chrome.toolError("Commit message cannot be empty.", "Commit Error");
            return;
        }

        // Disable UI during commit
        commitButton.setEnabled(false);
        cancelButton.setEnabled(false);
        commitMessageArea.setEnabled(false);

        contextManager.submitExclusiveAction(() -> {
            try {
                GitWorkflow.CommitResult result = workflowService.commit(filesToCommit, msg);
                SwingUtilities.invokeLater(() -> {
                    onCommitSuccessCallback.accept(result);
                    dispose();
                });
            } catch (Exception ex) {
                logger.error("Error committing files from dialog:", ex);
                SwingUtilities.invokeLater(() -> {
                    chrome.toolError("Error committing files: " + ex.getMessage(), "Commit Error");
                    // Re-enable UI for retry or cancel
                    commitMessageArea.setEnabled(true);
                    cancelButton.setEnabled(true);
                    checkCommitButtonState(); // Re-check commit button state
                });
            }
        });
    }
}
