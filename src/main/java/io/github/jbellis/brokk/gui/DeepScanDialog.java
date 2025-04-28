package io.github.jbellis.brokk.gui;

import com.google.common.collect.Streams;
import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.Project;
import io.github.jbellis.brokk.agents.ContextAgent;
import io.github.jbellis.brokk.agents.ValidationAgent;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BiFunction;

/**
 * Handles the execution of the Deep Scan agents (Context and Validation)
 * and presents the results in a modal dialog for user selection.
 */
class DeepScanDialog {
    private static final Logger logger = LogManager.getLogger(DeepScanDialog.class);

    // Action options for the dropdowns
    private static final String OMIT = "Omit";
    private static final String SUMMARIZE = "Summarize";
    private static final String EDIT = "Edit";
    private static final String READ_ONLY = "Read-only";

    /**
     * Triggers the Deep Scan agents (Context and Validation) in the background,
     * waits for their results, and then shows a modal dialog for user selection.
     * Handles errors and interruptions gracefully.
     *
     * @param chrome The main application window reference.
     * @param goal   The user's instruction/goal for the scan.
     */
    public static void triggerDeepScan(Chrome chrome, String goal) {
        var contextManager = chrome.getContextManager();
        if (goal.isBlank() || contextManager == null || contextManager.getProject() == null) {
            chrome.toolErrorRaw("Please enter instructions before running Deep Scan.");
            return;
        }

        // Disable input and deep scan button while scanning
        chrome.getInstructionsPanel().setCommandInputAndDeepScanEnabled(false);
        chrome.systemOutput("Starting Deep Scan");

        contextManager.submitUserTask("Deep Scan context analysis", () -> {
            try {
                // ContextAgent
                Future<ContextAgent.RecommendationResult> contextFuture = contextManager.submitBackgroundTask("Deep Scan: ContextAgent", () -> {
                    logger.debug("Deep Scan: Running ContextAgent...");
                    var model = contextManager.getAskModel(); // Use ask model for quality context
                    // Use full workspace context for deep scan
                    var agent = new ContextAgent(contextManager, model, goal, true);
                    var recommendations = agent.getRecommendations(20); // Increase limit for deep scan
                    logger.debug("Deep Scan: ContextAgent proposed {} fragments with reasoning: {}",
                                 recommendations.fragments().size(), recommendations.reasoning());
                    return recommendations;
                });

                // ValidationAgent
                Future<List<ProjectFile>> validationFuture = contextManager.submitBackgroundTask("Deep Scan: ValidationAgent", () -> {
                    logger.debug("Deep Scan: Running ValidationAgent...");
                    var agent = new ValidationAgent(contextManager);
                    var relevantTestResults = agent.execute(goal); // ValidationAgent finds relevant tests
                    var files = relevantTestResults.stream()
                            .distinct()
                            .toList();
                    logger.debug("Deep Scan: ValidationAgent found {} relevant test files.", files.size());
                    return files;
                });

                // Get results from futures - this will block until completion
                var contextResult = contextFuture.get(); // Can throw ExecutionException or InterruptedException
                var contextFragments = contextResult.fragments();
                var reasoning = contextResult.reasoning();
                var validationFiles = validationFuture.get(); // Can throw ExecutionException or InterruptedException

                // Check for interruption *after* getting results (if get() didn't throw InterruptedException)
                if (Thread.currentThread().isInterrupted()) {
                    logger.debug("Deep Scan task interrupted after agent completion.");
                    chrome.systemOutput("Deep Scan interrupted.");
                    return;
                }

                // Convert validation files to ProjectPathFragments
                var validationFragments = validationFiles.stream()
                        .map(ContextFragment.ProjectPathFragment::new)
                        .toList();

                // Combine context agent fragments and validation agent fragments
                // Group by primary file to handle potential overlaps (e.g., agent suggests summary, validation suggests file)
                // Keep the fragment from the context agent if there's an overlap, as it might be a SkeletonFragment
                Map<ProjectFile, ContextFragment> fragmentMap = new LinkedHashMap<>();

                // Add validation fragments first, then context fragments, so the latter overwrite the former on collision
                Streams.concat(validationFragments.stream(), contextFragments.stream()).forEach(fragment -> {
                    fragment.files(contextManager.getProject()).stream().findFirst().ifPresent(file -> {
                        fragmentMap.putIfAbsent(file, fragment);
                    });
                });

                var allSuggestedFragments = fragmentMap.values().stream()
                        // Sort by file path string for consistent order in dialog
                        .sorted(Comparator.comparing(f -> f.files(contextManager.getProject()).stream()
                                .findFirst()
                                .map(Object::toString)
                                .orElse("")))
                        .toList();

                logger.debug("Deep Scan finished. Proposing {} unique fragments.", allSuggestedFragments.size());

                if (allSuggestedFragments.isEmpty()) {
                    chrome.systemOutput("Deep Scan complete: No relevant fragments found");
                } else {
                    chrome.systemOutput("Deep Scan complete: Found %d relevant fragments".formatted(allSuggestedFragments.size()));
                    SwingUtil.runOnEDT(() -> showDialog(chrome, allSuggestedFragments, reasoning));
                }
            } catch (ExecutionException ee) {
                // Handle exceptions thrown by the background tasks (inside future.get())
                if (ee.getCause() instanceof InterruptedException) {
                    logger.debug("Deep Scan agent task interrupted during execution: {}", ee.getMessage());
                    chrome.systemOutput("Deep Scan cancelled");
                } else {
                    logger.error("Error during Deep Scan agent execution", ee.getCause());
                    chrome.toolErrorRaw("Error during Deep Scan execution: " + ee.getCause().getMessage());
                }
            } catch (InterruptedException ie) {
                // Handle interruption of the user task thread (e.g., while waiting on future.get())
                logger.debug("Deep Scan user task explicitly interrupted: {}", ie.getMessage());
                chrome.systemOutput("Deep Scan cancelled");
            } finally {
                // Re-enable input components after task completion, error, or interruption
                SwingUtilities.invokeLater(() -> chrome.getInstructionsPanel().setCommandInputAndDeepScanEnabled(true));
            }
        });
    }


    /**
     * Shows a modal dialog for the user to select files suggested by Deep Scan.
     * Files can be added as read-only, editable, or summarized.
     * This method MUST be called on the Event Dispatch Thread (EDT).
     *
     * @param chrome             The main application window reference.
     * @param suggestedFragments List of unique ContextFragments suggested by ContextAgent and ValidationAgent, grouped by file.
     * @param reasoning          The reasoning provided by the ContextAgent for the suggestions.
     */
    private static void showDialog(Chrome chrome, List<ContextFragment> suggestedFragments, String reasoning) {
        assert SwingUtilities.isEventDispatchThread(); // Ensure called on EDT

        var contextManager = chrome.getContextManager();
        Project project = contextManager.getProject(); // Keep project reference
        var testFilesSet = new HashSet<>(contextManager.getTestFiles()); // Set for quick lookups
        boolean hasGit = project != null && project.hasGit();

        // Separate fragments into code and tests based on their primary file
        List<ContextFragment> projectCodeFragments = new ArrayList<>();
        List<ContextFragment> testCodeFragments = new ArrayList<>();

        for (ContextFragment fragment : suggestedFragments) {
            // Determine the primary ProjectFile associated with the fragment
            ProjectFile pf = fragment.files(project).stream()
                    .findFirst()
                    .filter(ProjectFile.class::isInstance)
                    .orElse(null); // Get the ProjectFile or null

            if (pf != null) {
                if (testFilesSet.contains(pf)) {
                    testCodeFragments.add(fragment);
                } else {
                    projectCodeFragments.add(fragment);
                }
            } else {
                // Log fragments that don't map to a single ProjectFile (shouldn't happen with current agents)
                logger.warn("Deep Scan Dialog: Skipping fragment without a clear ProjectFile: {}", fragment.shortDescription());
            }
        }

        JDialog dialog = new JDialog(chrome.getFrame(), "Deep Scan Results", true); // Modal dialog
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setMinimumSize(new Dimension(400, 400)); // Increased width for dropdowns

        // Main panel to hold the two sections
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // --- LLM Reasoning Display ---
        if (reasoning != null && !reasoning.isBlank()) {
            JTextArea reasoningArea = new JTextArea(reasoning);
            reasoningArea.setEditable(false);
            reasoningArea.setLineWrap(true);
            reasoningArea.setWrapStyleWord(true);
            reasoningArea.setBackground(mainPanel.getBackground()); // Match background
            // Limit height, add scrollpane if needed
            JScrollPane reasoningScrollPane = new JScrollPane(reasoningArea);
            reasoningScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
            reasoningScrollPane.setBorder(BorderFactory.createTitledBorder("ContextAgent Reasoning"));
            // Set a preferred size to limit initial height
            reasoningScrollPane.setPreferredSize(new Dimension(350, 80)); // Adjust height as needed

            mainPanel.add(reasoningScrollPane);
            mainPanel.add(Box.createRigidArea(new Dimension(0, 10))); // Spacing after reasoning
        }

        // Maps to hold dropdowns and their corresponding fragments
        Map<JComboBox<String>, ContextFragment> projectCodeComboboxMap = new LinkedHashMap<>();
        Map<JComboBox<String>, ContextFragment> testCodeComboboxMap = new LinkedHashMap<>();

        // Options for project code files
        String[] projectOptions = {OMIT, SUMMARIZE, EDIT, READ_ONLY}; // Keep SUMMARIZE for project code
        // Options for test code files (no Summarize)
        String[] testOptions = {OMIT, EDIT, READ_ONLY}; // No SUMMARIZE for tests

        // Helper function to create a fragment row panel
// Helper function to create a fragment row panel
        BiFunction<ContextFragment, String[], JPanel> createFragmentRow = (fragment, options) -> {
            JPanel rowPanel = new JPanel(new BorderLayout(5, 0));     // Use BorderLayout for better alignment
            rowPanel.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0)); // Padding between rows

            // Get the display name and tooltip (full path) from the fragment's file
            ProjectFile pf = fragment.files(project).stream()
                    .findFirst()
                    .filter(ProjectFile.class::isInstance)
                    .orElse(null); // Should not be null here based on earlier filtering

            String fileName = (pf != null) ? pf.getFileName() : fragment.shortDescription();
            String toolTip  = (pf != null) ? pf.toString()    : fragment.description();

            JLabel fileLabel = new JLabel(fileName);
            fileLabel.setToolTipText(toolTip);                           // Show full path or description in tooltip
            fileLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 10)); // Right padding for label

            JComboBox<String> comboBox = new JComboBox<>(options);

            // Determine default action based on fragment type
            if (fragment instanceof ContextFragment.SkeletonFragment) {
                comboBox.setSelectedItem(SUMMARIZE);
            } else if (fragment instanceof ContextFragment.ProjectPathFragment) {
                comboBox.setSelectedItem(EDIT);                          // Default to EDIT for both project & tests
            } else {
                comboBox.setSelectedItem(OMIT);
            }

            if (!hasGit && EDIT.equals(comboBox.getSelectedItem())) {
                comboBox.setToolTipText("'" + EDIT + "' option requires a Git repository. Will be ignored if selected.");
            } else if (!hasGit) {
                comboBox.setToolTipText("'" + EDIT + "' option requires a Git repository");
            }

            // Fix combo-box width & keep row height compact
            comboBox.setPreferredSize(new Dimension(120, comboBox.getPreferredSize().height));

            rowPanel.add(fileLabel, BorderLayout.CENTER);
            rowPanel.add(comboBox, BorderLayout.EAST);

            // Prevent individual rows from “puffing up” to fill extra vertical space
            Dimension pref = rowPanel.getPreferredSize();
            rowPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));

            return rowPanel;
        };


        // --- Project Code Section ---
        JPanel projectCodeSection = new JPanel();
        projectCodeSection.setLayout(new BoxLayout(projectCodeSection, BoxLayout.Y_AXIS));
        // Add internal padding *inside* the titled border
        projectCodeSection.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Project Code"),
                BorderFactory.createEmptyBorder(5, 5, 5, 5) // Internal padding
        ));
        if (projectCodeFragments.isEmpty()) {
            projectCodeSection.add(new JLabel("No relevant files found"));
        } else {
            for (ContextFragment fragment : projectCodeFragments) {
                JPanel row = createFragmentRow.apply(fragment, projectOptions);
                JComboBox<String> comboBox = (JComboBox<String>) row.getComponent(1); // Assuming combo is the second component (EAST)
                projectCodeComboboxMap.put(comboBox, fragment);
                projectCodeSection.add(row);
            }
        }
        // Wrap project code section in a scroll pane
        JScrollPane projectCodeScrollPane = new JScrollPane(projectCodeSection);
        projectCodeScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        projectCodeScrollPane.setBorder(projectCodeSection.getBorder()); // Use the section's border for the scroll pane
        projectCodeSection.setBorder(null); // Remove border from the section itself
        mainPanel.add(projectCodeScrollPane);

        // Add spacing between sections
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        // --- Test Code Section ---
        JPanel testCodeSection = new JPanel();
        testCodeSection.setLayout(new BoxLayout(testCodeSection, BoxLayout.Y_AXIS));
        // Add internal padding *inside* the titled border
        testCodeSection.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Tests"),
                BorderFactory.createEmptyBorder(5, 5, 5, 5) // Internal padding
        ));
        if (testCodeFragments.isEmpty()) {
            testCodeSection.add(new JLabel("No relevant files found"));
        } else {
            for (ContextFragment fragment : testCodeFragments) {
                JPanel row = createFragmentRow.apply(fragment, testOptions);
                JComboBox<String> comboBox = (JComboBox<String>) row.getComponent(1); // Assuming combo is the second component (EAST)
                testCodeComboboxMap.put(comboBox, fragment);
                testCodeSection.add(row);
            }
        }
        // Wrap test code section in a scroll pane
        JScrollPane testCodeScrollPane = new JScrollPane(testCodeSection);
        testCodeScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        testCodeScrollPane.setBorder(testCodeSection.getBorder()); // Use the section's border for the scroll pane
        testCodeSection.setBorder(null); // Remove border from the section itself
        mainPanel.add(testCodeScrollPane);

        // Add the main panel (containing the scroll panes) to the dialog's center.
        // No need for an extra outer scroll pane here anymore.
        dialog.add(mainPanel, BorderLayout.CENTER);

        // --- Button Panel ---
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton applyButton = new JButton("Apply Selections");
        JButton cancelButton = new JButton("Cancel");

        buttonPanel.add(applyButton);
        buttonPanel.add(cancelButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        // --- Actions ---
        applyButton.addActionListener(e -> {
            Set<ProjectFile> filesToSummarize = new HashSet<>();
            List<ProjectFile> filesToEdit = new ArrayList<>();
            List<ProjectFile> filesToReadOnly = new ArrayList<>();

            // Helper to extract ProjectFile, handling potential issues
            BiFunction<ContextFragment, JComboBox<String>, ProjectFile> getProjectFile = (frag, combo) ->
                    frag.files(project).stream()
                            .findFirst()
                            .filter(ProjectFile.class::isInstance)
                            .orElseGet(() -> {
                                logger.error("Could not retrieve ProjectFile for fragment: {} / combo: {}", frag.shortDescription(), combo.getSelectedItem());
                                return null; // Return null if file cannot be determined
                            });


            // Process project code selections
            projectCodeComboboxMap.forEach((comboBox, fragment) -> {
                String selectedAction = (String) comboBox.getSelectedItem();
                ProjectFile pf = getProjectFile.apply(fragment, comboBox);
                if (pf == null) return; // Skip if file couldn't be determined

                switch (selectedAction) {
                    case SUMMARIZE:
                        filesToSummarize.add(pf);
                        break;
                    case EDIT:
                        if (hasGit) filesToEdit.add(pf);
                        else logger.warn("Edit action selected for {} but Git is not available. Ignoring.", pf);
                        break;
                    case READ_ONLY:
                        filesToReadOnly.add(pf);
                        break;
                    case OMIT: // Do nothing
                    default:
                        break;
                }
            });

            // Process test code selections
            testCodeComboboxMap.forEach((comboBox, fragment) -> {
                String selectedAction = (String) comboBox.getSelectedItem();
                ProjectFile pf = getProjectFile.apply(fragment, comboBox);
                if (pf == null) return; // Skip if file couldn't be determined

                switch (selectedAction) {
                    // SUMMARIZE is not an option for tests via UI
                    case EDIT:
                        if (hasGit) filesToEdit.add(pf);
                        else logger.warn("Edit action selected for test {} but Git is not available. Ignoring.", pf);
                        break;
                    case READ_ONLY:
                        filesToReadOnly.add(pf);
                        break;
                    case OMIT: // Do nothing
                    default:
                        break;
                }
            });

            if (!filesToSummarize.isEmpty()) {
                contextManager.submitContextTask("Summarize Files", () -> {
                    boolean success = contextManager.addSummaries(filesToSummarize, Set.of());
                    if (!success) {
                        chrome.toolErrorRaw("No summarizable code found in selected files");
                    }
                });
                chrome.systemOutput("Added summaries of " + filesToSummarize);
            }
            if (!filesToEdit.isEmpty()) {
                contextManager.editFiles(filesToEdit);
                chrome.systemOutput("Edited " + filesToEdit.size());
            }
            if (!filesToReadOnly.isEmpty()) {
                contextManager.addReadOnlyFiles(filesToReadOnly);
                chrome.systemOutput("Added as read-only: " + filesToReadOnly);
            }

            dialog.dispose();
            chrome.getInstructionsPanel().enableButtons(); // Re-enable buttons after dialog closes
        });


        cancelButton.addActionListener(e -> {
            dialog.dispose();
            chrome.getInstructionsPanel().enableButtons(); // Re-enable buttons after dialog closes
        });

        dialog.pack();
        dialog.setLocationRelativeTo(chrome.getFrame());
        chrome.getInstructionsPanel().disableButtons(); // Disable main buttons while dialog is showing
        dialog.setVisible(true); // Blocks until closed (on EDT)
        // Buttons are re-enabled by the apply/cancel listeners via chrome.getInstructionsPanel().enableButtons()
    }
}
