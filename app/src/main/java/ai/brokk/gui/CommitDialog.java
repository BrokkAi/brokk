package ai.brokk.gui;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.BrokkFile;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitWorkflow;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.dialogs.BaseThemedDialog;
import ai.brokk.gui.dialogs.TextAreaConsoleIO;
import ai.brokk.gui.util.Icons;
import ai.brokk.gui.util.KeyboardShortcutUtil;
import java.awt.*;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

public class CommitDialog extends BaseThemedDialog {
    private static final Logger logger = LogManager.getLogger(CommitDialog.class);

    private final JTextArea commitMessageArea;
    private final MaterialButton commitButton;
    private final MaterialButton cancelButton;
    private final MaterialButton regenerateButton;
    private final transient IContextManager contextManager;
    private final transient GitWorkflow workflowService;
    private final transient Collection<ProjectFile> filesToCommit;
    private final transient Consumer<GitWorkflow.CommitResult> onCommitSuccessCallback;
    private final transient Chrome chrome;

    public CommitDialog(
            @Nullable Window owner,
            Chrome chrome,
            IContextManager contextManager,
            Collection<ProjectFile> filesToCommit,
            Consumer<GitWorkflow.CommitResult> onCommitSuccessCallback) {
        this(owner, chrome, contextManager, filesToCommit, null, onCommitSuccessCallback);
    }

    public CommitDialog(
            @Nullable Window owner,
            Chrome chrome,
            IContextManager contextManager,
            Collection<ProjectFile> filesToCommit,
            @Nullable String prefilledMessage,
            Consumer<GitWorkflow.CommitResult> onCommitSuccessCallback) {
        super(owner, "Commit Changes");
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.workflowService = new GitWorkflow(contextManager);
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

        regenerateButton = new MaterialButton();
        regenerateButton.setIcon(Icons.REFRESH);
        regenerateButton.setToolTipText("Regenerate AI suggestion");
        regenerateButton.setVisible(false); // Hidden until first generation completes
        regenerateButton.addActionListener(e -> regenerateSuggestion());

        JScrollPane scrollPane = new JScrollPane(commitMessageArea);

        // Use MaterialButton so styling matches other dialogs; commit is primary (blue background + white text)
        commitButton = new MaterialButton("Commit");
        commitButton.setEnabled(false); // Initially disabled until message is ready or user types
        commitButton.addActionListener(e -> performCommit());
        SwingUtil.applyPrimaryButtonStyle(commitButton);

        cancelButton = new MaterialButton("Cancel");
        cancelButton.addActionListener(e -> dispose());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(regenerateButton);
        buttonPanel.add(cancelButton);
        buttonPanel.add(commitButton);

        // File list header
        String fileNames =
                filesToCommit.stream().limit(3).map(BrokkFile::getFileName).collect(Collectors.joining(", "));
        if (filesToCommit.size() > 3) {
            fileNames += " and " + (filesToCommit.size() - 3) + " more";
        }

        // Determine whether this dialog is for a partial subset of modified files so we can surface that to the user.
        boolean isPartial = false;
        int totalModified = -1;
        try {
            var repo = contextManager.getProject().getRepo();
            if (repo instanceof GitRepo gr) {
                totalModified = gr.getModifiedProjectFiles().size();
                isPartial = totalModified >= 0 && filesToCommit.size() < totalModified;
            }
        } catch (Exception e) {
            // Best effort only — if we can't compute totals, fall back to no partial indicator.
            logger.debug("Unable to compute total modified files for commit dialog", e);
        }

        String fileHeader;
        if (totalModified >= 0) {
            if (isPartial) {
                fileHeader = String.format(
                        "<html><b>Files (%d of %d selected, partial commit):</b> %s</html>",
                        filesToCommit.size(), totalModified, fileNames);
                setTitle("Commit Changes (partial)");
            } else {
                fileHeader = String.format("<html><b>Files (%d):</b> %s</html>", filesToCommit.size(), fileNames);
                setTitle("Commit Changes");
            }
        } else {
            // Unknown total modified count — just show selected count
            fileHeader = String.format("<html><b>Files (%d selected):</b> %s</html>", filesToCommit.size(), fileNames);
            if (isPartial) {
                setTitle("Commit Changes (partial)");
            }
        }

        JLabel fileLabel = new JLabel(fileHeader);
        fileLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));

        // Add padding around the dialog content
        JPanel contentPanel = new JPanel(new BorderLayout(0, 5));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        contentPanel.add(fileLabel, BorderLayout.NORTH);
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
            regenerateButton.setVisible(true); // Show regenerate for prefilled messages
            commitMessageArea.requestFocusInWindow();
            checkCommitButtonState();
        }
    }

    private void checkCommitButtonState() {
        // Never enable the commit or regenerate button while an LLM suggestion is actively streaming.
        if (streamingSuggestionInProgress) {
            commitButton.setEnabled(false);
            regenerateButton.setEnabled(false);
            return;
        }

        regenerateButton.setEnabled(commitMessageArea.isEnabled());

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
    private @Nullable SwingWorker<String, Void> currentWorker = null;

    private void regenerateSuggestion() {
        if (streamingSuggestionInProgress) return;

        commitMessageArea.setText("");
        commitMessageArea.setCaretPosition(0);
        initiateCommitMessageSuggestion();
    }

    private void initiateCommitMessageSuggestion() {
        if (streamingSuggestionInProgress) return;

        // Mark streaming as in-progress so the commit button stays disabled until finished.
        streamingSuggestionInProgress = true;
        var streamingIO = new TextAreaConsoleIO(commitMessageArea, chrome, "Inferring commit message");

        currentWorker = new ExceptionAwareSwingWorker<String, Void>(chrome) {
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
                        regenerateButton.setVisible(true); // Show after first successful generation
                        commitMessageArea.requestFocusInWindow();
                        commitMessageArea.setCaretPosition(0);
                        checkCommitButtonState();
                    });
                } catch (InterruptedException | ExecutionException ignored) {
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
                    currentWorker = null;
                    // Re-evaluate button state on the EDT to ensure UI consistency.
                    SwingUtilities.invokeLater(CommitDialog.this::checkCommitButtonState);
                }
            }
        };
        currentWorker.execute();
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
        regenerateButton.setEnabled(false);

        contextManager.submitBackgroundTask("Committing changes", () -> {
            try {
                GitWorkflow.CommitResult result = workflowService.commit(filesToCommit, msg);
                var repo = (GitRepo) chrome.contextManager.getRepo();
                SwingUtilities.invokeLater(() -> {
                    String shortHash = repo.shortHash(result.commitId());
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO, "Committed " + shortHash + ": " + result.firstLine());
                    onCommitSuccessCallback.accept(result);
                    dispose();
                });
            } catch (GitAPIException ex) {
                logger.warn("Error committing files from dialog:", ex);
                SwingUtilities.invokeLater(() -> {
                    chrome.toolError("Error committing files: " + ex.getMessage(), "Commit Error");
                    // Re-enable UI for retry or cancel
                    commitMessageArea.setEnabled(true);
                    cancelButton.setEnabled(true);
                    checkCommitButtonState(); // Re-check commit button state
                });
            }
            return null;
        });
    }

    @Override
    public void dispose() {
        if (currentWorker != null) {
            currentWorker.cancel(true);
        }
        super.dispose();
    }
}
