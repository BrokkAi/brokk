package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitWorkflow;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.util.KeyboardShortcutUtil;
import ai.brokk.init.onboarding.GitIgnoreConfigurator;
import ai.brokk.project.AbstractProject;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Combined dialog for git configuration that shows an editable commit message
 * with "Commit" and "No" buttons, along with a list of files to be committed.
 * <p>
 * Replaces the previous two-dialog flow (confirmation dialog -> commit dialog).
 */
public class GitConfigCommitDialog extends JDialog {
    private static final Logger logger = LogManager.getLogger(GitConfigCommitDialog.class);

    private final JTextArea commitMessageArea;
    private final MaterialButton commitButton;
    private final MaterialButton noButton;
    private final transient ContextManager contextManager;
    private final transient AbstractProject project;
    private final transient Chrome chrome;
    private final transient List<ProjectFile> filesToStage;

    public GitConfigCommitDialog(Frame owner, Chrome chrome, ContextManager contextManager, AbstractProject project) {
        super(owner, "Git Configuration", true);
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.project = project;
        this.filesToStage = GitIgnoreConfigurator.previewFilesToStage(project);

        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        // Header label
        var headerLabel = new JLabel("<html>Add Brokk configuration files to git?</html>");
        headerLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));

        // Files list
        var filesLabel = new JLabel("Files to commit:");
        filesLabel.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));

        var fileListModel = new DefaultListModel<String>();
        for (var file : filesToStage) {
            fileListModel.addElement(file.getRelPath().toString());
        }
        var fileList = new JList<>(fileListModel);
        fileList.setEnabled(false);
        fileList.setVisibleRowCount(Math.min(filesToStage.size(), 6));
        var fileListScrollPane = new JScrollPane(fileList);
        fileListScrollPane.setPreferredSize(new Dimension(400, 100));

        // Commit message label
        var messageLabel = new JLabel("Commit message:");
        messageLabel.setBorder(BorderFactory.createEmptyBorder(10, 0, 5, 0));

        // Commit message text area
        commitMessageArea = new JTextArea(3, 40);
        commitMessageArea.setLineWrap(true);
        commitMessageArea.setWrapStyleWord(true);
        commitMessageArea.setText("Add Brokk project files");
        var messageScrollPane = new JScrollPane(commitMessageArea);

        // Buttons
        commitButton = new MaterialButton("Commit");
        commitButton.addActionListener(e -> performSetupAndCommit());
        SwingUtil.applyPrimaryButtonStyle(commitButton);

        noButton = new MaterialButton("No");
        noButton.addActionListener(e -> dispose());

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(noButton);
        buttonPanel.add(commitButton);

        // Layout - center panel with files and message
        var centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));

        filesLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        fileListScrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);
        messageLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        messageScrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);

        centerPanel.add(filesLabel);
        centerPanel.add(fileListScrollPane);
        centerPanel.add(messageLabel);
        centerPanel.add(messageScrollPane);

        var contentPanel = new JPanel(new BorderLayout(0, 5));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        contentPanel.add(headerLabel, BorderLayout.NORTH);
        contentPanel.add(centerPanel, BorderLayout.CENTER);
        contentPanel.add(buttonPanel, BorderLayout.SOUTH);

        add(contentPanel, BorderLayout.CENTER);

        KeyboardShortcutUtil.registerDialogEscapeKey(getRootPane(), this::dispose);

        pack();
        setLocationRelativeTo(owner);
        commitButton.requestFocusInWindow();
    }

    private void performSetupAndCommit() {
        var msg = commitMessageArea.getText().trim();
        if (msg.isEmpty()) {
            chrome.toolError("Commit message cannot be empty.", "Commit Error");
            return;
        }

        // Disable UI during operation
        commitButton.setEnabled(false);
        noButton.setEnabled(false);
        commitMessageArea.setEnabled(false);

        contextManager.submitBackgroundTask("Setting up .gitignore and committing", () -> {
            var result = GitIgnoreConfigurator.setupGitIgnoreAndStageFiles(project, chrome);

            if (result.errorMessage().isPresent()) {
                logger.error("Git setup failed: {}", result.errorMessage().get());
                SwingUtilities.invokeLater(() -> {
                    chrome.toolError("Error setting up .gitignore: " + result.errorMessage().get(), "Error");
                    // Re-enable UI for retry
                    commitButton.setEnabled(true);
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
                    commitButton.setEnabled(true);
                    noButton.setEnabled(true);
                    commitMessageArea.setEnabled(true);
                });
            }
        });
    }
}
