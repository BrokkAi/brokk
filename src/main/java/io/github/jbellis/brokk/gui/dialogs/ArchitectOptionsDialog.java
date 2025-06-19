package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.agents.ArchitectAgent;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.SwingUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * A modal dialog to configure the tools available to the Architect agent.
 */
public class ArchitectOptionsDialog {
    // Remember last selections for the current session (used as fallback or if project is null)
    private static ArchitectAgent.ArchitectOptions lastArchitectOptionsCache = ArchitectAgent.ArchitectOptions.DEFAULTS;
    private static boolean lastRunInWorktreeCache = false;

    private static boolean isCodeIntelConfigured(IProject project) {
        var langs = project.getAnalyzerLanguages();
        return !langs.isEmpty() && !(langs.size() == 1 && langs.contains(Language.NONE));
    }
    /**
     * Shows a modal dialog synchronously on the Event Dispatch Thread (EDT) to configure
     * Architect tools and returns the chosen options, or null if cancelled.
     * This method blocks the calling thread until the dialog is closed.
     * Remembers the last selection for the current session.
     *
     * @param chrome         The main application window reference for positioning and theme.
     * @return The selected ArchitectChoices (options + worktree preference), or null if the dialog was cancelled.
     */
    @Nullable
    public static ArchitectChoices showDialogAndWait(Chrome chrome) {
        var contextManager = chrome.getContextManager();
        // Use AtomicReference to capture the result from the EDT lambda
        var resultHolder = new AtomicReference<ArchitectChoices>();

        // Use invokeAndWait to run the dialog logic on the EDT and wait for completion
        SwingUtil.runOnEdt(() -> {
            // Initial checks must happen *inside* the EDT task now
            var project = chrome.getProject();
            var isCpg = contextManager.getAnalyzerWrapper().isCpg();
            boolean codeIntelConfigured = project != null && isCodeIntelConfigured(project);

            // Load from project settings, fallback to static cache if project is null or settings not present
            var currentOptions = ArchitectAgent.ArchitectOptions.DEFAULTS;
            boolean currentRunInWorktree = false;

            if (project != null) {
                currentOptions = project.getArchitectOptions();
                currentRunInWorktree = project.getArchitectRunInWorktree();
                // Update static cache if project settings were different (e.g. first time opening this project in this session)
                lastArchitectOptionsCache = currentOptions;
                lastRunInWorktreeCache = currentRunInWorktree;
            } else {
                // No project, use last known static values
                currentOptions = lastArchitectOptionsCache;
                currentRunInWorktree = lastRunInWorktreeCache;
            }

            JDialog dialog = new JDialog(chrome.getFrame(), "Architect Tools", true); // Modal dialog
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE); // Dispose on close
            dialog.setLayout(new BorderLayout(10, 10));

            // --- Main Panel for Checkboxes ---
            JPanel mainPanel = new JPanel();
            mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
            mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            JLabel explanationLabel = new JLabel("Select the sub-agents and tools that the Architect agent will have access to:");
            explanationLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
            mainPanel.add(explanationLabel);

            // Helper to create checkbox with description
            BiFunction<String, String, JCheckBox> createCheckbox = (text, description) -> {
                JCheckBox cb = new JCheckBox("<html>" + text + "<br><i><font size='-2'>" + description + "</font></i></html>");
                cb.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0)); // Spacing below checkbox
                cb.setAlignmentX(Component.LEFT_ALIGNMENT);
                mainPanel.add(cb);
                return cb;
            };

            // Create checkboxes for each option
            var contextCb = createCheckbox.apply("Deep Scan", "Begin by calling Deep Scan to update the Workspace");
            contextCb.setSelected(currentOptions.includeContextAgent());

            var codeCb = createCheckbox.apply("Code Agent", "Allow invoking the Code Agent to modify files");
            codeCb.setSelected(currentOptions.includeCodeAgent());

            var validationCb = createCheckbox.apply("Validation Agent", "Infer test files to include with each Code Agent call");
            validationCb.setSelected(currentOptions.includeValidationAgent());

            var analyzerCb = createCheckbox.apply("Code Intelligence Tools", "Allow direct querying of code structure (e.g., find usages, call graphs)");
            analyzerCb.setSelected(currentOptions.includeAnalyzerTools() && codeIntelConfigured);
            analyzerCb.setEnabled(isCpg && codeIntelConfigured); // Disable if not a CPG or if CI is not configured

            if (!codeIntelConfigured) {
                analyzerCb.setToolTipText("Code Intelligence is not configured. Please configure languages in Project Settings.");
            } else if (!isCpg) {
                analyzerCb.setToolTipText("Code Intelligence tools for %s are not yet available".formatted(project.getAnalyzerLanguages()));
            }

            var workspaceCb = createCheckbox.apply("Workspace Management Tools", "Allow adding/removing files, URLs, or text to/from the Workspace");
            workspaceCb.setSelected(currentOptions.includeWorkspaceTools());

            var searchCb = createCheckbox.apply("Search Agent", "Allow invoking the Search Agent to find information beyond the current Workspace");
            searchCb.setSelected(currentOptions.includeSearchAgent());

            // Separator before worktree option
            mainPanel.add(new JSeparator(SwingConstants.HORIZONTAL));
            mainPanel.add(Box.createVerticalStrut(10)); // Add some space before the worktree option

            // --- Worktree Checkbox ---
            var worktreeCb = new JCheckBox("<html>Run in New Git worktree<br><i><font size='-2'>Create a new worktree for the Architect to work in, leaving your current one open for other tasks. The Architect will start with a copy of the current Workspace</font></i></html>");
            worktreeCb.setAlignmentX(Component.LEFT_ALIGNMENT);
            worktreeCb.setToolTipText("Create and run the Architect agent in a new Git worktree based on the current commit.");
            boolean gitAvailable = project != null && project.hasGit();
            boolean worktreesSupported = gitAvailable && project.getRepo().supportsWorktrees();
            worktreeCb.setEnabled(worktreesSupported);
            worktreeCb.setSelected(currentRunInWorktree && worktreesSupported); // Only selected if supported

            if (!gitAvailable) {
                worktreeCb.setToolTipText("Git is not configured for this project.");
            } else if (!worktreesSupported) {
                worktreeCb.setToolTipText("Git worktrees are not supported by your Git version or repository configuration.");
            }
            mainPanel.add(worktreeCb);

            dialog.add(new JScrollPane(mainPanel), BorderLayout.CENTER); // Add scroll pane

            // --- Button Panel ---
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton okButton = new JButton("OK");
            JButton cancelButton = new JButton("Cancel");
            buttonPanel.add(okButton);
            buttonPanel.add(cancelButton);
            dialog.add(buttonPanel, BorderLayout.SOUTH);

            // --- Actions ---
            okButton.addActionListener(e -> {
                var selectedOptions = new ArchitectAgent.ArchitectOptions(
                        contextCb.isSelected(),
                        validationCb.isSelected(),
                        isCpg && codeIntelConfigured && analyzerCb.isSelected(), // Force false if not CPG or CI not configured
                        workspaceCb.isSelected(),
                        codeCb.isSelected(),
                        searchCb.isSelected()
                );
                boolean runInWorktreeSelected = worktreeCb.isSelected();

                // Update static cache for immediate next use in this session
                lastArchitectOptionsCache = selectedOptions;
                lastRunInWorktreeCache = runInWorktreeSelected;

                // Persist to project settings if a project is available
                if (project != null) {
                    project.setArchitectOptions(selectedOptions, runInWorktreeSelected);
                }

                resultHolder.set(new ArchitectChoices(selectedOptions, runInWorktreeSelected)); // Set result
                dialog.dispose(); // Close dialog
            });

            cancelButton.addActionListener(e -> {
                // resultHolder is already null by default, or will be set if OK is clicked.
                // No need to explicitly set to null here again, windowClosing handles it if not set by OK.
                dialog.dispose(); // Close dialog
            });

            // Handle window close button (X) as cancel
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    // resultHolder will remain null if OK was not clicked
                }
            });

            dialog.pack();
            dialog.setLocationRelativeTo(chrome.getFrame()); // Center relative to parent
            dialog.setVisible(true); // Show the modal dialog and block EDT until closed
        }); // invokeAndWait ends here

        // Return the result captured from the EDT lambda
        return resultHolder.get();
    }
}
