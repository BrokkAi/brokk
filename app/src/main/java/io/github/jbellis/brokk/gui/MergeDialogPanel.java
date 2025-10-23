package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.WorktreeProject;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.util.MergeDialogUtil;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.Locale;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

/**
 * Unified merge dialog that supports both:
 * - Worktree flow (GWT): "merge this worktree's branch into a target branch"
 * - Log flow (GLT): "merge selected branch into another"
 *
 * Implements:
 * - MBDP-style early dirty working tree check (for WorktreeProject flow)
 * - Conflict checking in background, gating OK on success
 * - Material buttons, primary style, default button, named components
 */
public class MergeDialogPanel extends JDialog {
    private static final Logger logger = LogManager.getLogger(MergeDialogPanel.class);

    private final ContextManager contextManager;
    private final String sourceBranch;
    private final boolean showDeleteWorktree;
    private final boolean showDeleteBranch;
    private final Component parentComponent;

    private final boolean isWorktreeFlow;
    private final MainProject mainProject;

    private final JComboBox<String> targetBranchComboBox = new JComboBox<>();
    private final JComboBox<GitRepo.MergeMode> mergeModeComboBox = new JComboBox<>(GitRepo.MergeMode.values());

    private final JLabel conflictStatusLabel = new JLabel(" ");
    private final JLabel dirtyWorkingTreeLabel = new JLabel(" ");

    private final MaterialButton okButton = new MaterialButton("OK");
    private final MaterialButton cancelButton = new MaterialButton("Cancel");
    private final JCheckBox deleteWorktreeCb = new JCheckBox("Delete worktree after merge");
    private final JCheckBox deleteBranchCb = new JCheckBox("");
    private boolean confirmed = false;

    public record Result(
            boolean confirmed,
            String sourceBranch,
            String targetBranch,
            GitRepo.MergeMode mergeMode,
            boolean deleteWorktree,
            boolean deleteBranch) {}

    public MergeDialogPanel(@Nullable Frame parent, MergeDialogUtil.MergeDialogOptions options) {
        super(parent, options.dialogTitle(), true);
        this.contextManager = options.contextManager();
        this.sourceBranch = options.sourceBranch();
        this.showDeleteWorktree = options.showDeleteWorktree();
        this.showDeleteBranch = options.showDeleteBranch();
        this.parentComponent = options.parentComponent();

        var project = contextManager.getProject();
        this.isWorktreeFlow = project instanceof WorktreeProject;
        if (project instanceof WorktreeProject wp) {
            this.mainProject = wp.getParent();
        } else {
            this.mainProject = project.getMainProject();
        }

        initializeDialog();
        populateTargetBranches();
        initializeMergeMode();
        configureCheckboxes();

        // Preflight dirty check only for WorktreeProject flow
        if (isWorktreeFlow) {
            runDirtyWorkingTreeCheck();
        }

        // Initial conflict check and listeners
        Runnable conflictChecker = this::checkConflictsAsync;
        targetBranchComboBox.addActionListener(e -> conflictChecker.run());
        mergeModeComboBox.addActionListener(e -> conflictChecker.run());
        conflictChecker.run();

        pack();
        setLocationRelativeTo(parentComponent);
    }

    private void initializeDialog() {
        setLayout(new BorderLayout());

        var contentPanel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.weightx = 0;

        var title = new JLabel("Merge branch '" + sourceBranch + "'");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.gridx = 0;
        gbc.gridy = 0;
        contentPanel.add(title, gbc);

        // Target Branch selector
        gbc.gridwidth = 1;
        gbc.gridy++;
        gbc.weightx = 0;
        contentPanel.add(new JLabel("Merge into branch:"), gbc);

        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        contentPanel.add(targetBranchComboBox, gbc);

        // Merge strategy selector
        gbc.gridwidth = 1;
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weightx = 0;
        contentPanel.add(new JLabel("Merge strategy:"), gbc);

        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        contentPanel.add(mergeModeComboBox, gbc);

        // Optional checkboxes (insert placeholders; actual components added in configureCheckboxes)
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weightx = 1.0;

        // Conflict status label
        conflictStatusLabel.setForeground(UIManager.getColor("Label.foreground"));
        contentPanel.add(conflictStatusLabel, gbc);

        add(contentPanel, BorderLayout.CENTER);

        // Button panel
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        io.github.jbellis.brokk.gui.SwingUtil.applyPrimaryButtonStyle(okButton);
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        add(buttonPanel, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(okButton);

        okButton.addActionListener(e -> {
            confirmed = true;
            dispose();
        });
        cancelButton.addActionListener(e -> dispose());

        // initially disable OK until checks pass
        okButton.setEnabled(false);
    }

    private void populateTargetBranches() {
        var repoObj = mainProject.getRepo();
        if (!(repoObj instanceof GitRepo repo)) {
            logger.error(
                    "Merge operation requires Git repository, got: {}",
                    repoObj.getClass().getSimpleName());
            conflictStatusLabel.setText("Repository type not supported for merge operations");
            conflictStatusLabel.setForeground(Color.RED);
            targetBranchComboBox.setEnabled(false);
            return;
        }
        try {
            List<String> localBranches = repo.listLocalBranches();
            localBranches.forEach(targetBranchComboBox::addItem);
            String currentParentBranch = repo.getCurrentBranch();
            targetBranchComboBox.setSelectedItem(currentParentBranch);
        } catch (GitAPIException e) {
            logger.error("Failed to get branches for merge dialog", e);
            conflictStatusLabel.setText("Error loading branches: " + e.getMessage());
            conflictStatusLabel.setForeground(Color.RED);
            targetBranchComboBox.setEnabled(false);
        }
    }

    private void initializeMergeMode() {
        var lastMergeMode = mainProject.getLastMergeMode().orElse(GitRepo.MergeMode.MERGE_COMMIT);
        mergeModeComboBox.setSelectedItem(lastMergeMode);

        // Remove any pre-existing listeners to avoid duplicates if dialog reused
        for (var l : mergeModeComboBox.getActionListeners()) {
            mergeModeComboBox.removeActionListener(l);
        }
        mergeModeComboBox.addActionListener(e -> {
            var selectedMode = (GitRepo.MergeMode) mergeModeComboBox.getSelectedItem();
            if (selectedMode != null) {
                mainProject.setLastMergeMode(selectedMode);
            }
        });
    }

    private void configureCheckboxes() {
        var contentPanel =
                (JPanel) ((BorderLayout) getContentPane().getLayout()).getLayoutComponent(BorderLayout.CENTER);
        var gbc = new GridBagConstraints();
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.gridx = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        if (showDeleteWorktree) {
            gbc.gridy = 3;
            deleteWorktreeCb.setSelected(true);
            contentPanel.add(deleteWorktreeCb, gbc);
        }

        if (showDeleteBranch) {
            gbc.gridy = 4;
            deleteBranchCb.setText("Delete branch '" + sourceBranch + "' after merge");
            deleteBranchCb.setSelected(true);
            contentPanel.add(deleteBranchCb, gbc);
        }

        // dependency: branch deletion only enabled if worktree deletion is selected
        if (showDeleteWorktree && showDeleteBranch) {
            Runnable updateRemoveBranchCbState = () -> {
                if (deleteWorktreeCb.isSelected()) {
                    deleteBranchCb.setEnabled(true);
                } else {
                    deleteBranchCb.setEnabled(false);
                    deleteBranchCb.setSelected(false);
                }
            };
            deleteWorktreeCb.addActionListener(e -> updateRemoveBranchCbState.run());
            updateRemoveBranchCbState.run();
        }
    }

    private void runDirtyWorkingTreeCheck() {
        // Only relevant if the current project (the worktree) is a GitRepo
        var project = contextManager.getProject();
        var repoObj = project.getRepo();
        if (!(repoObj instanceof GitRepo gitRepo)) {
            logger.warn(
                    "Worktree dirty check skipped: current project repo is not GitRepo ({})",
                    repoObj.getClass().getSimpleName());
            return;
        }

        try {
            var modifiedFiles = gitRepo.getModifiedFiles();
            if (!modifiedFiles.isEmpty()) {
                var fileCount = modifiedFiles.size();
                var fileWord = fileCount == 1 ? "file" : "files";
                addDirtyWorkingTreeLabel("Cannot merge: " + fileCount + " uncommitted " + fileWord
                        + " must be committed or stashed first.");
                okButton.setEnabled(false);
            }
        } catch (Exception e) {
            logger.warn("Failed to check for uncommitted changes in worktree", e);
            addDirtyWorkingTreeLabel("Cannot verify working tree status - please commit or stash changes first.");
            okButton.setEnabled(false);
        }
    }

    private void addDirtyWorkingTreeLabel(String message) {
        dirtyWorkingTreeLabel.setText(message);
        dirtyWorkingTreeLabel.setForeground(Color.RED);

        var contentPanel =
                (JPanel) ((BorderLayout) getContentPane().getLayout()).getLayoutComponent(BorderLayout.CENTER);
        var gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.weightx = 1.0;
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(8, 8, 8, 8);

        // Add just above the conflict label: conflict label is the last added at initialize time
        // So we can add dirty label before re-adding conflict label at the end
        // Remove and re-add conflict label to maintain order
        contentPanel.remove(conflictStatusLabel);

        gbc.gridy = 5;
        contentPanel.add(dirtyWorkingTreeLabel, gbc);

        gbc.gridy = 6;
        contentPanel.add(conflictStatusLabel, gbc);

        pack();
    }

    private void checkConflictsAsync() {
        okButton.setEnabled(false);
        var selectedTargetBranch = (String) targetBranchComboBox.getSelectedItem();
        var selectedMergeMode = (GitRepo.MergeMode) mergeModeComboBox.getSelectedItem();

        if (selectedTargetBranch == null) {
            conflictStatusLabel.setText("Please select a valid target branch.");
            conflictStatusLabel.setForeground(Color.RED);
            return;
        }

        conflictStatusLabel.setText("Checking for conflicts with '" + selectedTargetBranch + "'...");
        conflictStatusLabel.setForeground(UIManager.getColor("Label.foreground"));

        var repoObj = mainProject.getRepo();
        if (!(repoObj instanceof GitRepo gitRepo)) {
            logger.error(
                    "Conflict check requires Git repository, got: {}",
                    repoObj.getClass().getSimpleName());
            SwingUtilities.invokeLater(() -> {
                conflictStatusLabel.setText("Repository type not supported for merge operations");
                conflictStatusLabel.setForeground(Color.RED);
                okButton.setEnabled(false);
            });
            return;
        }

        contextManager.submitBackgroundTask("Checking merge conflicts", () -> {
            String conflictResultString;
            try {
                if (selectedTargetBranch.equals(sourceBranch)) {
                    conflictResultString = "Cannot merge a branch into itself.";
                } else {
                    conflictResultString =
                            gitRepo.checkMergeConflicts(sourceBranch, selectedTargetBranch, selectedMergeMode);
                }
            } catch (GitRepo.WorktreeDirtyException e) {
                logger.warn("Conflict check aborted because target worktree is dirty: {}", e.getMessage());
                conflictResultString = "Target branch has uncommitted changes that must be stashed or committed first.";
            } catch (GitAPIException e) {
                logger.error("GitAPIException during conflict check", e);
                conflictResultString = "Error checking conflicts: " + e.getMessage();
            } catch (Exception e) {
                logger.error("Unexpected error during conflict check", e);
                conflictResultString = "Unexpected error during conflict check: " + e.getMessage();
            }

            final String finalResult = conflictResultString;
            final String targetForMessage = selectedTargetBranch;
            final GitRepo.MergeMode modeForMessage = selectedMergeMode;
            SwingUtilities.invokeLater(() -> {
                if (finalResult != null && !finalResult.isBlank()) {
                    conflictStatusLabel.setText(finalResult);
                    conflictStatusLabel.setForeground(Color.RED);
                    okButton.setEnabled(false);
                } else {
                    conflictStatusLabel.setText(String.format(
                            "No conflicts detected with '%s' for %s.",
                            targetForMessage, modeForMessage.toString().toLowerCase(Locale.ROOT)));
                    conflictStatusLabel.setForeground(new Color(0, 128, 0));
                    // Only enable OK button if working tree (if applicable) is clean
                    if (dirtyWorkingTreeLabel.getText().trim().isEmpty()) {
                        okButton.setEnabled(true);
                    }
                }
            });
            return null;
        });
    }

    public Result showDialog() {
        setVisible(true);

        var target = (String) targetBranchComboBox.getSelectedItem();
        var mode = (GitRepo.MergeMode) mergeModeComboBox.getSelectedItem();
        boolean deleteWorktree = deleteWorktreeCb.isSelected();
        boolean deleteBranch = deleteBranchCb.isSelected();

        return new Result(confirmed, sourceBranch, target == null ? "" : target, mode, deleteWorktree, deleteBranch);
    }
}
