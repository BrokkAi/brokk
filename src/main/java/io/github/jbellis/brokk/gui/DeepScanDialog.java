package io.github.jbellis.brokk.gui;

import com.google.common.collect.Streams;
import io.github.jbellis.brokk.AnalyzerWrapper;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.agents.ContextAgent;
import io.github.jbellis.brokk.agents.ValidationAgent;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

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
     * Triggers the Deep Scan agents (Context and Validation) in the background.
     * The returned CompletableFuture completes when the analysis is finished,
     * right before the results dialog is shown.
     * Handles errors and interruptions gracefully.
     *
     * @param chrome The main application window reference.
     * @param goal   The user's instruction/goal for the scan.
     */
    public static CompletableFuture<Void> triggerDeepScan(Chrome chrome, String goal) {
        var analysisDoneFuture = new CompletableFuture<Void>();
        var contextManager = chrome.getContextManager();

        if (goal.isBlank() || contextManager == null || contextManager.getProject() == null) {
            SwingUtilities.invokeLater(() -> chrome.toolError("Please enter instructions before running Deep Scan."));
            analysisDoneFuture.completeExceptionally(new IllegalArgumentException("Goal is blank or context/project is unavailable."));
            return analysisDoneFuture;
        }

        SwingUtilities.invokeLater(() -> chrome.systemOutput("Starting Deep Scan"));

        // ContextAgent
        Future<ContextAgent.RecommendationResult> contextFuture = contextManager.submitBackgroundTask("Deep Scan: ContextAgent", () -> {
            logger.debug("Deep Scan: Running ContextAgent...");
            var model = contextManager.getSearchModel();
            // Use full workspace context for deep scan
            var agent = new ContextAgent(contextManager, model, goal, true);
            var recommendations = agent.getRecommendations(false);
            logger.debug("Deep Scan: ContextAgent proposed {} fragments with reasoning: {}",
                         recommendations.fragments().size(), recommendations.reasoning());
            return recommendations;
        });

        // ValidationAgent
        Future<List<ProjectFile>> validationFuture = contextManager.submitBackgroundTask("Deep Scan: ValidationAgent", () -> {
            logger.debug("Deep Scan: Running ValidationAgent...");
            var agent = new ValidationAgent(contextManager);
            var relevantTestResults = agent.execute(goal);
            var ctx = contextManager.topContext();
            var filesInWorkspace = Streams.concat(ctx.editableFiles(), ctx.readonlyFiles())
                    .filter(ContextFragment.PathFragment.class::isInstance)
                    .map(ContextFragment.PathFragment.class::cast)
                    .map(ContextFragment.PathFragment::file)
                    .collect(Collectors.toSet());
            var files = relevantTestResults.stream()
                    .filter(f -> !filesInWorkspace.contains(f))
                    .distinct()
                    .toList();
            logger.debug("Deep Scan: ValidationAgent found {} relevant test files.", files.size());
            return files;
        });

        contextManager.submitUserTask("Deep Scan context analysis", () -> {
            try {
                // Get results from futures - this will block until completion
                ContextAgent.RecommendationResult contextResult;
                List<ProjectFile> validationFiles;
                try {
                    contextResult = contextFuture.get();
                    validationFiles = validationFuture.get();
                } catch (ExecutionException ee) {
                    if (ee.getCause() instanceof InterruptedException ie) {
                        throw ie;
                    }
                    // For other execution exceptions, log, notify user, and complete future exceptionally.
                    logger.error("Error during Deep Scan agent execution", ee.getCause());
                    SwingUtilities.invokeLater(() -> chrome.toolError("Error during Deep Scan execution: " + ee.getCause().getMessage()));
                    analysisDoneFuture.completeExceptionally(ee.getCause());
                    return; // Exit the task
                }

                var contextFragments = contextResult.fragments();
                var reasoning = contextResult.reasoning();

                // Convert validation files to ProjectPathFragments
                var validationFragments = validationFiles.stream()
                        .map(pf -> new ContextFragment.ProjectPathFragment(pf, contextManager)) // Pass contextManager
                        .toList();

                // Combine context agent fragments and validation agent fragments
                // Group by primary file to handle potential overlaps (e.g., agent suggests summary, validation suggests file)
                // Keep the fragment from the context agent if there's an overlap, as it might be a SkeletonFragment
                Map<ProjectFile, ContextFragment> fragmentMap = new LinkedHashMap<>();

                // Add validation fragments first, then context fragments, so the latter overwrite the former on collision
                Streams.concat(validationFragments.stream(), contextFragments.stream()).forEach(fragment -> {
                    fragment.files().stream().findFirst().ifPresent(file -> { // No arguments for files()
                        fragmentMap.putIfAbsent(file, fragment);
                    });
                });

                var allSuggestedFragments = fragmentMap.values().stream()
                        // Sort by file path string for consistent order in dialog
                        .sorted(Comparator.comparing(f -> f.files().stream() // No arguments for files()
                                .findFirst()
                                .map(Object::toString)
                                .orElse("")))
                        .toList();

                logger.debug("Deep Scan finished. Proposing {} unique fragments.", allSuggestedFragments.size());

                if (allSuggestedFragments.isEmpty()) {
                    if (contextManager.topContext().allFragments().findAny().isPresent()) {
                        chrome.systemOutput("Deep Scan complete with no additional recommendations");
                    } else {
                        chrome.systemOutput("Deep Scan complete with no recommendations");
                    }
                } else {
                    chrome.systemOutput("Deep Scan complete: Found %d relevant fragments".formatted(allSuggestedFragments.size()));
                    // Split allSuggestedFragments into test and project code fragments
                    var testFilesSet = new HashSet<>(validationFiles);
                    List<ContextFragment> projectCodeFragments = new ArrayList<>();
                    List<ContextFragment> testCodeFragments = new ArrayList<>();
                    for (ContextFragment fragment : allSuggestedFragments) {
                        ProjectFile pf = fragment.files().stream()
                                .findFirst()
                                .orElseThrow();
                        if (testFilesSet.contains(pf)) {
                            testCodeFragments.add(fragment);
                        } else {
                            projectCodeFragments.add(fragment);
                        }
                    }
                    // Pre-compute fragment-to-file mapping to avoid analyzer calls on EDT
                    Map<ContextFragment, ProjectFile> fragmentFileMap = new HashMap<>();
                    for (ContextFragment fragment : allSuggestedFragments) {
                        ProjectFile pf = fragment.files().stream()
                                .findFirst()
                                .orElseThrow();
                        fragmentFileMap.put(fragment, pf);
                    }

                    // Analysis is complete. Complete the future and then show the dialog.
                    SwingUtil.runOnEdt(() -> {
                        analysisDoneFuture.complete(null); // Complete future before showing dialog
                        showDialog(chrome, projectCodeFragments, testCodeFragments, reasoning, fragmentFileMap);
                    });
                } // This correctly closes the 'else' for 'if (allSuggestedFragments.isEmpty())'
            } catch (InterruptedException ie) {
                // This handles interruptions if they occur outside the future.get() calls
                // or if rethrown and not caught by an inner block.
                logger.debug("Deep Scan user task interrupted: {}", ie.getMessage());
                SwingUtilities.invokeLater(() -> chrome.systemOutput("Deep Scan cancelled."));
                // Cancel any potentially still running background tasks if they haven't been waited on yet
                contextFuture.cancel(true);
                validationFuture.cancel(true);
                analysisDoneFuture.completeExceptionally(ie);
            } catch (Throwable t) { // Catch any other unexpected errors
                logger.error("Unexpected error during Deep Scan user task processing", t);
                SwingUtilities.invokeLater(() -> chrome.toolError("Unexpected error during Deep Scan: " + t.getMessage()));
                analysisDoneFuture.completeExceptionally(t);
            }
        });
        return analysisDoneFuture;
    }

    /**
     * Shows a modal dialog for the user to select files suggested by Deep Scan.
     * Files can be added as read-only, editable, or summarized.
     * This method MUST be called on the Event Dispatch Thread (EDT).
     */
    private static void showDialog(Chrome chrome, List<ContextFragment> projectCodeFragments, List<ContextFragment> testCodeFragments, String reasoning, Map<ContextFragment, ProjectFile> fragmentFileMap) {
        assert SwingUtilities.isEventDispatchThread(); // Ensure called on EDT

        var contextManager = chrome.getContextManager();
        var project = contextManager.getProject(); // Keep project reference
        boolean hasGit = project != null && project.hasGit();

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
        BiFunction<ContextFragment, String[], JPanel> createFragmentRow = (fragment, options) -> {
            JPanel rowPanel = new JPanel(new BorderLayout(5, 0));     // Use BorderLayout for better alignment
            rowPanel.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0)); // Padding between rows

            // Get the display name and tooltip (full path) from the pre-computed mapping
            ProjectFile pf = fragmentFileMap.get(fragment);

            String fileName = (pf != null) ? pf.getFileName() : fragment.shortDescription();
            String toolTip = (pf != null) ? pf.toString() : fragment.description();

            JLabel fileLabel = new JLabel(fileName);
            fileLabel.setToolTipText(toolTip);                           // Show full path or description in tooltip
            fileLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 10)); // Right padding for label

            JComboBox<String> comboBox = new JComboBox<>(options);

            // Determine default action based on fragment type
            if (fragment.getType() == ContextFragment.FragmentType.SKELETON && Arrays.asList(options).contains(SUMMARIZE)) {
                comboBox.setSelectedItem(SUMMARIZE);
            } else if (fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH) {
                // EDIT if the file is in git, otherwise READ  
                var edit = hasGit && contextManager.getRepo().getTrackedFiles().contains(fragmentFileMap.get(fragment));
                comboBox.setSelectedItem(edit ? EDIT : READ_ONLY);
            } else {
                logger.error("Unexpected fragment {} returned to DeepScanDialog", fragment);
                comboBox.setSelectedItem(OMIT);
            }

            // Add tooltip warning if Git is not available, as Edit is still an option
            if (!hasGit) {
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
                Component comp = row.getComponent(1);
                if (comp instanceof JComboBox<?> cb) {
                    @SuppressWarnings("unchecked")
                    var casted = (JComboBox<String>) cb;
                    projectCodeComboboxMap.put(casted, fragment);
                    projectCodeSection.add(row);
                } else {
                    throw new IllegalStateException("Expected JComboBox at index 1, got " + comp.getClass());
                }
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
                Component comp = row.getComponent(1);
                if (comp instanceof JComboBox<?> cb) {
                    @SuppressWarnings("unchecked")
                    var casted = (JComboBox<String>) cb;
                    testCodeComboboxMap.put(casted, fragment);
                    testCodeSection.add(row);
                } else {
                    throw new IllegalStateException("Expected JComboBox at index 1, got " + comp.getClass());
                }
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

            // this is clunky b/c we're returning ContextFragment (PathFragment or SkeletonFragment) from the Agent,
            // and we use that to select the default action, but user can override the action so we need
            // a common denominator of ProjectFile

            // Process project code selections
            projectCodeComboboxMap.forEach((comboBox, fragment) -> {
                String selectedAction = (String) comboBox.getSelectedItem();

                switch (selectedAction) {
                    case SUMMARIZE:
                        filesToSummarize.add(fragmentFileMap.get(fragment));
                        break;
                    case EDIT:
                        if (hasGit) filesToEdit.add(fragmentFileMap.get(fragment));
                        else logger.warn("Edit action selected for {} but Git is not available. Ignoring.", fragment);
                        break;
                    case READ_ONLY:
                        filesToReadOnly.add(fragmentFileMap.get(fragment));
                        break;
                    case OMIT: // Do nothing
                    default:
                        break;
                }
            });

            // Process test code selections
            testCodeComboboxMap.forEach((comboBox, fragment) -> {
                String selectedAction = (String) comboBox.getSelectedItem();

                switch (selectedAction) {
                    // SUMMARIZE is not an option for tests via UI
                    case EDIT:
                        if (hasGit) filesToEdit.add(fragmentFileMap.get(fragment));
                        else
                            logger.warn("Edit action selected for test {} but Git is not available. Ignoring.", fragment);
                        break;
                    case READ_ONLY:
                        filesToReadOnly.add(fragmentFileMap.get(fragment));
                        break;
                    case OMIT: // Do nothing
                    default:
                        break;
                }
            });

            contextManager.submitContextTask("Adding Deep Scan recommendations", () -> {
                if (!filesToSummarize.isEmpty()) {
                    if (!contextManager.getAnalyzerWrapper().isReady()) {
                        contextManager.getIo().systemNotify(AnalyzerWrapper.ANALYZER_BUSY_MESSAGE,
                                                          AnalyzerWrapper.ANALYZER_BUSY_TITLE,
                                                          JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
                    boolean success = contextManager.addSummaries(filesToSummarize, Set.of());
                    if (!success) {
                        chrome.systemOutput("No summarizable code found in selected files");
                    }
                }
                if (!filesToEdit.isEmpty()) {
                    contextManager.editFiles(filesToEdit);
                }
                if (!filesToReadOnly.isEmpty()) {
                    contextManager.addReadOnlyFiles(filesToReadOnly);
                }

                SwingUtilities.invokeLater(() -> {
                    dialog.dispose();
                    chrome.getInstructionsPanel().enableButtons(); // Re-enable buttons after dialog closes
                });
            });
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