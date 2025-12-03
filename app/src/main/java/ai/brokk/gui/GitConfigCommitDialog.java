package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitWorkflow;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.util.KeyboardShortcutUtil;
import ai.brokk.init.onboarding.GitIgnoreConfigurator;
import ai.brokk.project.AbstractProject;
import java.awt.*;
import java.util.ArrayList;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Combined dialog for git configuration that shows an editable commit message
 * with "Yes and Commit" and "No" buttons.
 * <p>
 * Replaces the previous two-dialog flow (confirmation dialog -> commit dialog).
 */
public class GitConfigCommitDialog extends JDialog {
    private static final Logger logger = LogManager.getLogger(GitConfigCommitDialog.class);

    private final JTextArea commitMessageArea;
    private final MaterialButton yesCommitButton;
    private final MaterialButton noButton;
    private final transient ContextManager contextManager;
    private final transient AbstractProject project;
    private final transient Chrome chrome;

    public GitConfigCommitDialog(Frame owner, Chrome chrome, ContextManager contextManager, AbstractProject project) {
        super(owner, "Git Configuration", true);
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.project = project;

        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        // Header label
        var headerLabel = new JLabel("<html>Update .gitignore and add .brokk project files to git?</html>");
        headerLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));

        // Commit message label
        var messageLabel = new JLabel("Commit message:");
        messageLabel.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));

        // Commit message text area
        commitMessageArea = new JTextArea(5, 40);
        commitMessageArea.setLineWrap(true);
        commitMessageArea.setWrapStyleWord(true);
        commitMessageArea.setText("Add Brokk project files");
        var scrollPane = new JScrollPane(commitMessageArea);

        // Buttons
        yesCommitButton = new MaterialButton("Yes and Commit");
        yesCommitButton.addActionListener(e -> performSetupAndCommit());
        SwingUtil.applyPrimaryButtonStyle(yesCommitButton);

        noButton = new MaterialButton("No");
        noButton.addActionListener(e -> dispose());

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(noButton);
        buttonPanel.add(yesCommitButton);

        // Layout
        var topPanel = new JPanel(new BorderLayout());
        topPanel.add(headerLabel, BorderLayout.NORTH);
        topPanel.add(messageLabel, BorderLayout.SOUTH);

        var contentPanel = new JPanel(new BorderLayout(0, 5));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        contentPanel.add(topPanel, BorderLayout.NORTH);
        contentPanel.add(scrollPane, BorderLayout.CENTER);
        contentPanel.add(buttonPanel, BorderLayout.SOUTH);

        add(contentPanel, BorderLayout.CENTER);

        KeyboardShortcutUtil.registerDialogEscapeKey(getRootPane(), this::dispose);

        pack();
        setLocationRelativeTo(owner);
        commitMessageArea.requestFocusInWindow();
    }

    private void performSetupAndCommit() {
        var msg = commitMessageArea.getText().trim();
        if (msg.isEmpty()) {
            chrome.toolError("Commit message cannot be empty.", "Commit Error");
            return;
        }

        // Disable UI during operation
        yesCommitButton.setEnabled(false);
        noButton.setEnabled(false);
        commitMessageArea.setEnabled(false);

        contextManager.submitBackgroundTask("Setting up .gitignore and committing", () -> {
            var result = GitIgnoreConfigurator.setupGitIgnoreAndStageFiles(project, chrome);

            if (result.errorMessage().isPresent()) {
                logger.error("Git setup failed: {}", result.errorMessage().get());
                SwingUtilities.invokeLater(() -> {
                    chrome.toolError("Error setting up .gitignore: " + result.errorMessage().get(), "Error");
                    // Re-enable UI for retry
                    yesCommitButton.setEnabled(true);
                    noButton.setEnabled(true);
                    commitMessageArea.setEnabled(true);
                });
                return;
            }

            var filesToCommit = new ArrayList<>(result.stagedFiles());
            if (filesToCommit.isEmpty()) {
                logger.debug("No files to commit");
                SwingUtilities.invokeLater(this::dispose);
                return;
            }

            var repo = project.getRepo();
            if (!(repo instanceof GitRepo gitRepo)) {
                SwingUtilities.invokeLater(this::dispose);
                return;
            }

            // Perform commit
            try {
                var workflowService = new GitWorkflow(contextManager);
                var commitResult = workflowService.commit(filesToCommit, msg);
                SwingUtilities.invokeLater(() -> {
                    chrome.showNotification(
                            Chrome.NotificationRole.INFO,
                            "Committed " + gitRepo.shortHash(commitResult.commitId()) + ": " + commitResult.firstLine());
                    chrome.updateCommitPanel();
                    chrome.updateLogTab();
                    dispose();
                });
            } catch (Exception ex) {
                logger.error("Error committing files:", ex);
                SwingUtilities.invokeLater(() -> {
                    chrome.toolError("Error committing files: " + ex.getMessage(), "Commit Error");
                    // Re-enable UI for retry
                    yesCommitButton.setEnabled(true);
                    noButton.setEnabled(true);
                    commitMessageArea.setEnabled(true);
                });
            }
        });
    }
}
