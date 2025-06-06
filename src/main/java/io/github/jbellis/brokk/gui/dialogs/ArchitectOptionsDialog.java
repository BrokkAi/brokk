package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.agents.ArchitectAgent;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.SwingUtil;

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
    // Remember last selection for the current session
    private static ArchitectAgent.ArchitectOptions lastArchitectOptions = ArchitectAgent.ArchitectOptions.DEFAULTS;

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
     * @param contextManager The context manager to check project capabilities (e.g., CPG).
     * @return The selected ArchitectOptions, or null if the dialog was cancelled.
     */
    public static ArchitectAgent.ArchitectOptions showDialogAndWait(Chrome chrome, ContextManager contextManager) {
        // Use AtomicReference to capture the result from the EDT lambda
        var resultHolder = new AtomicReference<ArchitectAgent.ArchitectOptions>();

        // Use invokeAndWait to run the dialog logic on the EDT and wait for completion
        SwingUtil.runOnEdt(() -> {
            // Initial checks must happen *inside* the EDT task now
            var project = chrome.getProject();
            var isCpg = contextManager.getAnalyzerWrapper().isCpg();
            boolean codeIntelConfigured = project != null && isCodeIntelConfigured(project);
            // Use last options as default for this session
            var currentOptions = lastArchitectOptions;

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
                mainPanel.add(cb);
                return cb;
            };

            // Create checkboxes for each option
            var contextCb = createCheckbox.apply("Deep Scan", "Begin by calling Deep Scan to update the workspace");
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

            var workspaceCb = createCheckbox.apply("Workspace Management Tools", "Allow adding/removing files, URLs, or text to/from the workspace");
            workspaceCb.setSelected(currentOptions.includeWorkspaceTools());

            var searchCb = createCheckbox.apply("Search Agent", "Allow invoking the Search Agent to find information beyond the current workspace");
            searchCb.setSelected(currentOptions.includeSearchAgent());

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
                lastArchitectOptions = selectedOptions; // Remember for next time this session
                resultHolder.set(selectedOptions); // Set result
                dialog.dispose(); // Close dialog
            });

            cancelButton.addActionListener(e -> {
                resultHolder.set(null); // Indicate cancellation
                dialog.dispose(); // Close dialog
            });

            // Handle window close button (X) as cancel
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    resultHolder.compareAndSet(null, null); // Ensure null if not already set by OK/Cancel
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
