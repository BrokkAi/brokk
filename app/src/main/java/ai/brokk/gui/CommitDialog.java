package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitWorkflow;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.util.KeyboardShortcutUtil;
import java.awt.*;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class CommitDialog extends JDialog {
    private static final Logger logger = LogManager.getLogger(CommitDialog.class);

    private final JTextArea commitMessageArea;
    private final MaterialButton commitButton;
    private final MaterialButton cancelButton;
    private final transient ContextManager contextManager;
    private final transient GitWorkflow workflowService;
    private final transient List<ProjectFile> filesToCommit;
    private final transient Consumer<GitWorkflow.CommitResult> onCommitSuccessCallback;
    private final transient Chrome chrome;

    private static final String PLACEHOLDER_INFERRING = "Inferring commit message...";
    private static final String PLACEHOLDER_FAILURE = "Unable to infer message. Please write one manually.";

    public CommitDialog(
            Frame owner,
            Chrome chrome,
            ContextManager contextManager,
            GitWorkflow workflowService,
            List<ProjectFile> filesToCommit,
            Consumer<GitWorkflow.CommitResult> onCommitSuccessCallback) {
        this(owner, chrome, contextManager, workflowService, filesToCommit, null, onCommitSuccessCallback);
    }

    public CommitDialog(
            Frame owner,
            Chrome chrome,
            ContextManager contextManager,
            GitWorkflow workflowService,
            List<ProjectFile> filesToCommit,
            @Nullable String prefilledMessage,
            Consumer<GitWorkflow.CommitResult> onCommitSuccessCallback) {
        super(owner, "Commit Changes", true);
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.workflowService = workflowService;
        this.filesToCommit = filesToCommit;
        this.onCommitSuccessCallback = onCommitSuccessCallback;

        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10)); // Add some padding

        commitMessageArea = new JTextArea(10, 50);
        commitMessageArea.setLineWrap(true);
        commitMessageArea.setWrapStyleWord(true);

        // If pre-filled message provided, use it directly; otherwise start with placeholder
        if (prefilledMessage != null && !prefilledMessage.isEmpty()) {
            commitMessageArea.setText(prefilledMessage);
            commitMessageArea.setEnabled(true);
        } else {
            commitMessageArea.setEnabled(false);
            commitMessageArea.setText(PLACEHOLDER_INFERRING);
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

        add(contentPanel, BorderLayout.CENTER);

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
        if (commitMessageArea.isEnabled()) {
            String text = commitMessageArea.getText();
            boolean hasNonCommentText = Arrays.stream(text.split("\n"))
                    .anyMatch(line -> !line.trim().isEmpty() && !line.trim().startsWith("#"));
            commitButton.setEnabled(hasNonCommentText);
        } else {
            commitButton.setEnabled(false);
        }
    }

    private void initiateCommitMessageSuggestion() {
        CompletableFuture<String> suggestionFuture = contextManager.submitBackgroundTask(
                "Suggesting commit message",
                () -> workflowService.suggestCommitMessage(filesToCommit, "Llm error inferring commit message"));

        suggestionFuture.whenComplete(
                (@Nullable String suggestedMessage, @Nullable Throwable throwable) -> SwingUtilities.invokeLater(() -> {
                    if (throwable == null) {
                        if (suggestedMessage != null && !suggestedMessage.isEmpty()) {
                            commitMessageArea.setText(suggestedMessage);
                        } else {
                            commitMessageArea.setText(""); // Clear placeholder if suggestion is empty
                        }
                        commitMessageArea.setEnabled(true);
                        commitMessageArea.requestFocusInWindow(); // Focus for editing
                    } else {
                        logger.error("Error suggesting commit message for dialog:", throwable);
                        commitMessageArea.setText(PLACEHOLDER_FAILURE);
                        commitMessageArea.setEnabled(true);
                        commitMessageArea.requestFocusInWindow(); // Focus for manual input
                    }
                    checkCommitButtonState(); // Update commit button based on new text/state
                }));
    }

    private void performCommit() {
        String msg = commitMessageArea.getText().trim();
        if (msg.isEmpty() || msg.equals(PLACEHOLDER_FAILURE) || msg.equals(PLACEHOLDER_INFERRING)) {
            // This case should ideally be prevented by button enablement, but as a safeguard:
            chrome.toolError("Commit message cannot be empty or placeholder.", "Commit Error");
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
                    String baseMsg = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                    String hint = "";
                    try {
                        String lower = baseMsg.toLowerCase(Locale.ROOT);
                        if (lower.contains("gpg") || lower.contains("sign")) {
                            hint =
                                    """
                                    
                                    Hint: Ensure GPG is installed, a valid signing key is available, and gpg-agent is running.
                                    - Verify: git config commit.gpgsign (should be true) and git config user.signingkey (optional)
                                    - You can disable signing for this repo with: git config commit.gpgsign false
                                    - Environment override (for this app): BROKK_GIT_SIGN_COMMITS=[1|true|yes]
                                    """;
                        }
                    } catch (Exception ignore) {
                        // best-effort hint detection
                    }
                    chrome.toolError("Error committing files: " + baseMsg + hint, "Commit Error");
                    // Re-enable UI for retry or cancel
                    commitMessageArea.setEnabled(true);
                    cancelButton.setEnabled(true);
                    checkCommitButtonState(); // Re-check commit button state
                });
            }
        });
    }
}
