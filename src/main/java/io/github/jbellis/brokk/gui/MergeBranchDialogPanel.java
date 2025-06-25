package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.git.GitRepo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Self-contained merge dialog that handles displaying the merge options,
 * conflict checking, and user interaction.
 */
public class MergeBranchDialogPanel extends JDialog {
    private static final Logger logger = LogManager.getLogger(MergeBranchDialogPanel.class);

    private final JComboBox<GitRepo.MergeMode> mergeModeComboBox;
    private final JLabel conflictStatusLabel;
    private final String sourceBranch;
    private final String targetBranch;

    @Nullable private JButton okButton = null;
    @Nullable private JButton cancelButton = null;
    private boolean dialogResult = false;

    public MergeBranchDialogPanel(Frame parent, String sourceBranch, String targetBranch) {
        super(parent, "Merge Options", true);
        this.sourceBranch = sourceBranch;
        this.targetBranch = targetBranch;
        
        // Initialize fields
        this.mergeModeComboBox = new JComboBox<>(GitRepo.MergeMode.values());
        this.conflictStatusLabel = new JLabel(" ");

        initializeDialog();
    }

    private void initializeDialog() {
        setLayout(new BorderLayout());

        // Create main content panel
        var contentPanel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.weightx = 1.0;

        var title = new JLabel(String.format("Merge branch '%s' into '%s'",
                                             sourceBranch,
                                             targetBranch));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        contentPanel.add(title, gbc);

        // --- merge mode selector -------------------------------------------------
        gbc.gridwidth = 1;
        gbc.weightx   = 0;
        contentPanel.add(new JLabel("Merge strategy:"), gbc);

        gbc.gridx    = 1;
        gbc.weightx  = 1.0;
        // mergeModeComboBox already initialized in constructor
        mergeModeComboBox.setSelectedItem(GitRepo.MergeMode.MERGE_COMMIT);
        contentPanel.add(mergeModeComboBox, gbc);

        // --- conflict status label ----------------------------------------------
        gbc.gridx       = 0;
        gbc.gridy       = 2;
        gbc.gridwidth   = GridBagConstraints.REMAINDER;
        gbc.weightx     = 1.0;
        gbc.insets      = new Insets(5, 5, 15, 5); // Add extra bottom spacing
        // conflictStatusLabel already initialized in constructor
        conflictStatusLabel.setForeground(UIManager.getColor("Label.foreground"));
        contentPanel.add(conflictStatusLabel, gbc);

        add(contentPanel, BorderLayout.CENTER);

        // Create button panel
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        okButton = new JButton("OK");
        cancelButton = new JButton("Cancel");

        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        add(buttonPanel, BorderLayout.SOUTH);

        // Set up button actions
        okButton.addActionListener(e -> {
            dialogResult = true;
            dispose();
        });
        cancelButton.addActionListener(e -> dispose());
    }

    /**
     * Result record for the merge dialog interaction.
     */
    public record MergeDialogResult(boolean confirmed, GitRepo.MergeMode mergeMode, 
                                  boolean hasConflicts, String conflictMessage) {}

    /**
     * Shows the merge dialog and returns the user's choice.
     *
     * @param gitRepo The git repository for conflict checking
     * @param contextManager The context manager for background tasks
     * @return MergeDialogResult containing the user's choice and conflict status
     */
    public MergeDialogResult showDialog(GitRepo gitRepo, ContextManager contextManager) {
        // Set up conflict checking
        Runnable conflictChecker = () -> checkConflictsAsync(gitRepo, contextManager);
        mergeModeComboBox.addActionListener(e -> conflictChecker.run());
        conflictChecker.run(); // Initial check

        // Configure and show dialog
        pack();
        setLocationRelativeTo(getParent());
        setVisible(true);

        // Determine result
        var selectedMode = (GitRepo.MergeMode) mergeModeComboBox.getSelectedItem();
        String conflictText = conflictStatusLabel.getText();
        boolean hasConflicts = conflictText != null && !conflictText.startsWith("No conflicts detected")
                             && !conflictText.trim().isEmpty() && !conflictText.equals("Checking for conflicts...");

        return new MergeDialogResult(dialogResult, selectedMode, hasConflicts, conflictText);
    }

    private void checkConflictsAsync(GitRepo gitRepo, ContextManager contextManager) {
        if (okButton != null) {
            okButton.setEnabled(false);
        }
        conflictStatusLabel.setText("Checking for conflicts...");
        conflictStatusLabel.setForeground(UIManager.getColor("Label.foreground"));

        var selectedMode = (GitRepo.MergeMode) mergeModeComboBox.getSelectedItem();

        contextManager.submitBackgroundTask("Checking merge conflicts", () -> {
            String conflictResult;
            try {
                conflictResult = gitRepo.checkMergeConflicts(sourceBranch, targetBranch, selectedMode);
            } catch (GitAPIException e) {
                logger.error("Error checking merge conflicts", e);
                conflictResult = "Error: " + e.getMessage();
            }

            final String finalConflictResult = conflictResult;
            SwingUtilities.invokeLater(() -> {
                if (finalConflictResult != null && !finalConflictResult.isBlank()) {
                    conflictStatusLabel.setText(finalConflictResult);
                    conflictStatusLabel.setForeground(Color.RED);
                    if (okButton != null) {
                        okButton.setEnabled(false);
                    }
                } else {
                    conflictStatusLabel.setText("No conflicts detected.");
                    conflictStatusLabel.setForeground(new Color(0, 128, 0)); // Green
                    if (okButton != null) {
                        okButton.setEnabled(true);
                    }
                }
            });
            return null;
        });
    }

}
