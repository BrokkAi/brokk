package io.github.jbellis.brokk.gui.util;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.WorktreeProject;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.MergeBranchDialogPanel;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.util.List;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

/**
 * Utility class for creating merge dialogs that can be used by both GitLogTab and GitWorktreeTab. Extracts the common
 * merge dialog logic to avoid code duplication.
 */
public class MergeDialogUtil {
    private static final Logger logger = LogManager.getLogger(MergeDialogUtil.class);

    public record MergeDialogOptions(
            String sourceBranch,
            String dialogTitle,
            boolean showDeleteWorktree,
            boolean showDeleteBranch,
            Component parentComponent,
            Chrome chrome,
            ContextManager contextManager) {
        /** Create options for GitLogTab branch merge (no worktree deletion). */
        public static MergeDialogOptions forBranchMerge(
                String sourceBranch, Component parentComponent, Chrome chrome, ContextManager contextManager) {
            return new MergeDialogOptions(
                    sourceBranch,
                    "Merge branch '" + sourceBranch + "'",
                    false, // no worktree deletion
                    true, // allow branch deletion
                    parentComponent,
                    chrome,
                    contextManager);
        }

        /** Create options for GitWorktreeTab merge (with worktree deletion). */
        public static MergeDialogOptions forWorktreeMerge(
                String sourceBranch, Component parentComponent, Chrome chrome, ContextManager contextManager) {
            return new MergeDialogOptions(
                    sourceBranch,
                    "Merge branch '" + sourceBranch + "'",
                    true, // allow worktree deletion
                    true, // allow branch deletion
                    parentComponent,
                    chrome,
                    contextManager);
        }
    }

    public record MergeDialogResult(
            boolean confirmed,
            String sourceBranch,
            String targetBranch,
            GitRepo.MergeMode mergeMode,
            boolean deleteWorktree,
            boolean deleteBranch) {
        public static MergeDialogResult cancelled() {
            return new MergeDialogResult(false, "", "", GitRepo.MergeMode.MERGE_COMMIT, false, false);
        }
    }

    /**
     * Shows a merge dialog with the specified options. This method extracts and reuses the dialog logic from
     * GitWorktreeTab.showMergeDialog().
     */
    public static MergeDialogResult showMergeDialog(MergeDialogOptions options) {
        var project = options.contextManager().getProject();

        // For worktree projects, get the parent project for branch operations
        MainProject mainProject;
        if (project instanceof WorktreeProject worktreeProject) {
            mainProject = worktreeProject.getParent();
        } else {
            mainProject = project.getMainProject();
        }

        JPanel dialogPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.weightx = 0;

        // Target Branch
        JLabel targetBranchLabel = new JLabel("Merge into branch:");
        dialogPanel.add(targetBranchLabel, gbc);

        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.weightx = 1.0;
        JComboBox<String> targetBranchComboBox = new JComboBox<>();
        dialogPanel.add(targetBranchComboBox, gbc);
        gbc.weightx = 0; // Reset for next components

        // Merge Strategy
        gbc.gridwidth = 1;
        JLabel mergeModeLabel = new JLabel("Merge strategy:");
        dialogPanel.add(mergeModeLabel, gbc);

        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.weightx = 1.0;
        JComboBox<GitRepo.MergeMode> mergeModeComboBox = new JComboBox<>(GitRepo.MergeMode.values());
        var lastMergeMode = mainProject.getLastMergeMode().orElse(GitRepo.MergeMode.MERGE_COMMIT);
        mergeModeComboBox.setSelectedItem(lastMergeMode);
        mergeModeComboBox.addActionListener(
                MergeBranchDialogPanel.createMergeModePersistenceListener(mergeModeComboBox, mainProject));
        dialogPanel.add(mergeModeComboBox, gbc);
        gbc.weightx = 0;

        // Populate targetBranchComboBox
        if (!(mainProject.getRepo() instanceof GitRepo repo)) {
            logger.error(
                    "Merge operation requires Git repository, got: {}",
                    mainProject.getRepo().getClass().getSimpleName());
            JOptionPane.showMessageDialog(
                    options.parentComponent(),
                    "This operation requires a Git repository",
                    "Repository Type Error",
                    JOptionPane.ERROR_MESSAGE);
            return MergeDialogResult.cancelled();
        }

        try {
            List<String> localBranches = repo.listLocalBranches();
            localBranches.forEach(targetBranchComboBox::addItem);
            String currentParentBranch = repo.getCurrentBranch();
            targetBranchComboBox.setSelectedItem(currentParentBranch);
        } catch (GitAPIException e) {
            logger.error("Failed to get parent project branches", e);
            targetBranchComboBox.addItem("Error loading branches");
            targetBranchComboBox.setEnabled(false);
        }

        // Checkboxes
        gbc.gridwidth = GridBagConstraints.REMAINDER; // Span full width for checkboxes

        // Delete worktree checkbox (conditional)
        JCheckBox removeWorktreeCb = null;
        if (options.showDeleteWorktree()) {
            removeWorktreeCb = new JCheckBox("Delete worktree after merge");
            removeWorktreeCb.setSelected(true);
            dialogPanel.add(removeWorktreeCb, gbc);
        }

        // Delete branch checkbox (conditional)
        JCheckBox removeBranchCb = null;
        if (options.showDeleteBranch()) {
            removeBranchCb = new JCheckBox("Delete branch '" + options.sourceBranch() + "' after merge");
            removeBranchCb.setSelected(true);
            dialogPanel.add(removeBranchCb, gbc);

            // Setup dependency between worktree and branch deletion checkboxes
            if (removeWorktreeCb != null) {
                final JCheckBox finalRemoveBranchCb = removeBranchCb;
                final JCheckBox finalRemoveWorktreeCb = removeWorktreeCb;

                Runnable updateRemoveBranchCbState = () -> {
                    if (finalRemoveWorktreeCb.isSelected()) {
                        finalRemoveBranchCb.setEnabled(true);
                    } else {
                        finalRemoveBranchCb.setEnabled(false);
                        finalRemoveBranchCb.setSelected(false); // Uncheck when disabled
                    }
                };
                removeWorktreeCb.addActionListener(e -> updateRemoveBranchCbState.run());
                updateRemoveBranchCbState.run();
            }
        }

        // Conflict Status Label
        JLabel conflictStatusLabel = new JLabel(" "); // Start with a non-empty string for layout
        conflictStatusLabel.setForeground(UIManager.getColor("Label.foreground")); // Default color
        dialogPanel.add(conflictStatusLabel, gbc);

        JOptionPane optionPane = new JOptionPane(dialogPanel, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);

        // Create explicit OK and Cancel buttons so we have a reliable reference to the OK button
        JButton okButton = new JButton(UIManager.getString("OptionPane.okButtonText"));
        JButton cancelButton = new JButton(UIManager.getString("OptionPane.cancelButtonText"));

        okButton.addActionListener(e -> {
            optionPane.setValue(JOptionPane.OK_OPTION);
            Window w = SwingUtilities.getWindowAncestor(okButton);
            if (w instanceof JDialog d) d.dispose();
        });
        cancelButton.addActionListener(e -> {
            optionPane.setValue(JOptionPane.CANCEL_OPTION);
            Window w = SwingUtilities.getWindowAncestor(cancelButton);
            if (w instanceof JDialog d) d.dispose();
        });

        optionPane.setOptions(new Object[] {okButton, cancelButton});
        okButton.setEnabled(false); // Initially disabled until conflict check completes successfully

        JDialog dialog = optionPane.createDialog(options.parentComponent(), options.dialogTitle());
        dialog.getRootPane().setDefaultButton(okButton);

        // Add listeners to re-check conflicts on selection changes
        final JButton finalOkButton = okButton; // effectively final for lambda
        targetBranchComboBox.addActionListener(e -> checkConflictsAsync(
                targetBranchComboBox,
                mergeModeComboBox,
                conflictStatusLabel,
                options.sourceBranch(),
                finalOkButton,
                options.contextManager()));
        mergeModeComboBox.addActionListener(e -> checkConflictsAsync(
                targetBranchComboBox,
                mergeModeComboBox,
                conflictStatusLabel,
                options.sourceBranch(),
                finalOkButton,
                options.contextManager()));

        targetBranchComboBox.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                checkConflictsAsync(
                        targetBranchComboBox,
                        mergeModeComboBox,
                        conflictStatusLabel,
                        options.sourceBranch(),
                        finalOkButton,
                        options.contextManager());
            }
        });
        mergeModeComboBox.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                checkConflictsAsync(
                        targetBranchComboBox,
                        mergeModeComboBox,
                        conflictStatusLabel,
                        options.sourceBranch(),
                        finalOkButton,
                        options.contextManager());
            }
        });

        // Initial conflict check with the explicit OK button reference
        checkConflictsAsync(
                targetBranchComboBox,
                mergeModeComboBox,
                conflictStatusLabel,
                options.sourceBranch(),
                finalOkButton,
                options.contextManager());

        dialog.setVisible(true);
        Object selectedValue = optionPane.getValue();
        dialog.dispose();

        if (selectedValue != null && selectedValue.equals(JOptionPane.OK_OPTION)) {
            // Ensure these are captured before the lambda potentially changes them,
            // or ensure they are final/effectively final.
            final String selectedTargetBranch = (String) targetBranchComboBox.getSelectedItem();
            final GitRepo.MergeMode selectedMergeMode = (GitRepo.MergeMode) mergeModeComboBox.getSelectedItem();

            String currentConflictText = conflictStatusLabel.getText(); // Check the final state of the label
            if (currentConflictText.startsWith("No conflicts detected")) {

                boolean deleteWorktree = removeWorktreeCb != null && removeWorktreeCb.isSelected();
                boolean deleteBranch = removeBranchCb != null && removeBranchCb.isSelected();

                logger.info(
                        "Merge confirmed for source branch '{}' into target branch '{}' using mode '{}'. Remove worktree: {}, Remove branch: {}",
                        options.sourceBranch(),
                        selectedTargetBranch,
                        selectedMergeMode,
                        deleteWorktree,
                        deleteBranch);

                return new MergeDialogResult(
                        true,
                        options.sourceBranch(),
                        selectedTargetBranch,
                        selectedMergeMode,
                        deleteWorktree,
                        deleteBranch);
            } else {
                logger.info(
                        "Merge dialog confirmed with OK, but conflicts were present or an error occurred. Merge not performed.");
                JOptionPane.showMessageDialog(
                        options.parentComponent(),
                        "Merge was not performed because conflicts were detected or an error occurred:\n"
                                + currentConflictText,
                        "Merge Prevented",
                        JOptionPane.WARNING_MESSAGE);
            }
        }

        return MergeDialogResult.cancelled();
    }

    private static void checkConflictsAsync(
            JComboBox<String> targetBranchComboBox,
            JComboBox<GitRepo.MergeMode> mergeModeComboBox,
            JLabel conflictStatusLabel,
            String sourceBranchName,
            @Nullable JButton okButton,
            ContextManager contextManager) {

        if (okButton != null) {
            okButton.setEnabled(false);
        }

        String selectedTargetBranch = (String) targetBranchComboBox.getSelectedItem();
        GitRepo.MergeMode selectedMergeMode = (GitRepo.MergeMode) mergeModeComboBox.getSelectedItem();

        if (selectedTargetBranch == null
                || selectedTargetBranch.equals("Error loading branches")
                || selectedTargetBranch.equals("Unsupported parent repo type")
                || selectedTargetBranch.equals("Parent repo not available")
                || selectedTargetBranch.equals("Parent project not found")
                || selectedTargetBranch.equals("Not a worktree project")) {
            conflictStatusLabel.setText("Please select a valid target branch.");
            conflictStatusLabel.setForeground(Color.RED);
            // okButton is already disabled, or null if not found
            return;
        }

        conflictStatusLabel.setText("Checking for conflicts with '" + selectedTargetBranch + "'...");
        conflictStatusLabel.setForeground(UIManager.getColor("Label.foreground")); // Default color

        var project = contextManager.getProject();
        MainProject mainProject;
        if (project instanceof WorktreeProject worktreeProject) {
            mainProject = worktreeProject.getParent();
        } else {
            mainProject = project.getMainProject();
        }

        if (!(mainProject.getRepo() instanceof GitRepo gitRepo)) {
            logger.error(
                    "Conflict check requires Git repository, got: {}",
                    mainProject.getRepo().getClass().getSimpleName());
            SwingUtilities.invokeLater(() -> {
                conflictStatusLabel.setText("Repository type not supported for merge operations");
                conflictStatusLabel.setForeground(Color.RED);
                if (okButton != null) {
                    okButton.setEnabled(false);
                }
            });
            return;
        }

        contextManager.submitBackgroundTask("Checking merge conflicts", () -> {
            String conflictResultString;
            try {
                if (selectedTargetBranch.equals(sourceBranchName)) {
                    conflictResultString = "Cannot merge a branch into itself.";
                } else {
                    // This checks for historical conflicts in a clean, temporary worktree
                    conflictResultString =
                            gitRepo.checkMergeConflicts(sourceBranchName, selectedTargetBranch, selectedMergeMode);
                }
            } catch (GitRepo.WorktreeDirtyException e) {
                // uncommitted changes that would prevent even starting a simulation.
                logger.warn("Conflict check aborted because target worktree is dirty: {}", e.getMessage());
                conflictResultString = "Target branch has uncommitted changes that must be stashed or committed first.";
            } catch (GitAPIException e) {
                logger.error("GitAPIException during conflict check", e);
                conflictResultString = "Error checking conflicts: " + e.getMessage();
            } catch (Exception e) { // Catch other potential exceptions
                logger.error("Unexpected error during conflict check", e);
                conflictResultString = "Unexpected error during conflict check: " + e.getMessage();
            }

            final String finalConflictResult = conflictResultString;
            SwingUtilities.invokeLater(() -> {
                if (finalConflictResult != null && !finalConflictResult.isBlank()) {
                    conflictStatusLabel.setText(finalConflictResult);
                    conflictStatusLabel.setForeground(Color.RED);
                    if (okButton != null) {
                        okButton.setEnabled(false);
                    }
                } else {
                    conflictStatusLabel.setText(String.format(
                            "No conflicts detected with '%s' for %s.",
                            selectedTargetBranch, selectedMergeMode.toString().toLowerCase(java.util.Locale.ROOT)));
                    conflictStatusLabel.setForeground(new Color(0, 128, 0)); // Green for no conflicts
                    if (okButton != null) {
                        okButton.setEnabled(true);
                    }
                }
            });
            return null;
        });
    }
}
