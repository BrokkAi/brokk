package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.components.OverlayPanel;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.autocomplete.ShorthandCompletion;
import org.jetbrains.annotations.Nullable;

public class ProjectFilesPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(ProjectFilesPanel.class);

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final IProject project;

    private JTextField searchField;
    private JButton refreshButton;
    private ProjectTree projectTree;
    private OverlayPanel searchOverlay;
    private AutoCompletion ac;

    public ProjectFilesPanel(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout(Constants.H_GAP, Constants.V_GAP));
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.project = contextManager.getProject();

        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Project Files",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)));

        setupSearchFieldAndAutocomplete();
        setupProjectTree();

        // Search bar with refresh button
        var searchBarPanel = new JPanel(new BorderLayout(Constants.H_GAP, 0));
        var layeredPane = searchOverlay.createLayeredPane(searchField);
        searchBarPanel.add(layeredPane, BorderLayout.CENTER);

        refreshButton = new JButton();
        refreshButton.setIcon(SwingUtil.uiIcon("Brokk.refresh"));
        refreshButton.setToolTipText("Refresh file list");
        refreshButton.addActionListener(e -> refreshProjectFiles());
        searchBarPanel.add(refreshButton, BorderLayout.EAST);

        add(searchBarPanel, BorderLayout.NORTH);
        JScrollPane treeScrollPane = new JScrollPane(projectTree);
        add(treeScrollPane, BorderLayout.CENTER);

        updateBorderTitle(); // Set initial title with branch name
    }

    private void updateBorderTitle() {
        var branchName = GitUiUtil.getCurrentBranchName(project);
        GitUiUtil.updatePanelBorderWithBranch(this, "Project Files", branchName);
    }

    private void setupProjectTree() {
        this.projectTree = new ProjectTree(project, contextManager, chrome);
    }

    private void setupSearchFieldAndAutocomplete() {
        searchField = new JTextField(20);
        searchField.setToolTipText("Type to search for project files");

        var searchPromptLabel = new JLabel("Search");
        searchPromptLabel.setForeground(Color.GRAY);
        searchPromptLabel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 0));
        // Hide the overlay first so a single click both dismisses it and focuses the field
        searchOverlay = new OverlayPanel(p -> searchField.requestFocusInWindow(), "");
        searchOverlay.setLayout(new BorderLayout());
        searchOverlay.add(searchPromptLabel, BorderLayout.CENTER);

        searchField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                searchOverlay.hideOverlay();
            }

            @Override
            public void focusLost(FocusEvent e) {
                if (searchField.getText().isEmpty()) {
                    searchOverlay.showOverlay();
                }
            }
        });

        if (searchField.getText().isEmpty()) {
            searchOverlay.showOverlay();
        } else {
            searchOverlay.hideOverlay();
        }

        var provider = new ProjectFileCompletionProvider(project);
        provider.setAutoActivationRules(true, null); // Activate on letters
        ac = new AutoCompletion(provider);
        ac.setAutoActivationEnabled(true);
        ac.setAutoActivationDelay(0); // Show popup immediately
        ac.install(searchField);

        searchField.addActionListener(e -> handleSearchConfirmation());

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                searchOverlay.hideOverlay();
                handleTextChange();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                if (searchField.getText().isEmpty() && !searchField.hasFocus()) {
                    searchOverlay.showOverlay();
                }
                handleTextChange();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Not used for plain text components
            }

            private void handleTextChange() {
                SwingUtilities.invokeLater(() -> {
                    String currentText = searchField.getText();
                    if (currentText.trim().isEmpty()) {
                        return;
                    }

                    String typedLower = currentText.toLowerCase(Locale.ROOT);
                    Set<ProjectFile> trackedFiles = project.getRepo().getTrackedFiles();

                    List<ProjectFile> matches = trackedFiles.stream()
                            .filter(pf -> {
                                String pathStrLower = pf.getRelPath().toString().toLowerCase(Locale.ROOT);
                                String fileNameLower = pf.getFileName().toLowerCase(Locale.ROOT);

                                if (typedLower.contains("/") || typedLower.contains("\\")) {
                                    // If typed text has path separators, treat it as a path prefix match
                                    return pathStrLower.startsWith(typedLower);
                                } else {
                                    // If no path separators, check if it's part of the filename
                                    if (fileNameLower.contains(typedLower)) {
                                        return true;
                                    }
                                    // Or if it's part of any directory name in the path
                                    Path currentParent = pf.getRelPath().getParent();
                                    while (currentParent != null) {
                                        if (currentParent
                                                .getFileName()
                                                .toString()
                                                .toLowerCase(Locale.ROOT)
                                                .contains(typedLower)) {
                                            return true;
                                        }
                                        currentParent = currentParent.getParent();
                                    }
                                    return false;
                                }
                            })
                            .toList();

                    if (matches.size() == 1) {
                        projectTree.selectAndExpandToFile(matches.getFirst());
                        // Keep focus on search field for continued typing/searching
                        SwingUtilities.invokeLater(() -> searchField.requestFocusInWindow());
                    }
                });
            }
        });
    }

    private void handleSearchConfirmation() {
        // This action is triggered when Enter is pressed in the search field.
        // Priority: If there's a tree selection, focus tree and show context menu immediately.
        // Otherwise, try to find and select a file based on search text.

        String searchText = searchField.getText();

        // If there's already a tree selection, focus tree and show context menu regardless of search text
        if (projectTree.getSelectionCount() > 0) {
            projectTree.requestFocusInWindow();
            SwingUtilities.invokeLater(() -> {
                Action contextMenuAction = projectTree.getActionMap().get("showContextMenu");
                if (contextMenuAction != null) {
                    contextMenuAction.actionPerformed(new java.awt.event.ActionEvent(
                            projectTree, java.awt.event.ActionEvent.ACTION_PERFORMED, "showContextMenu"));
                }
            });
            return;
        }

        // No tree selection - try to find and select a file based on search text
        if (searchText == null || searchText.trim().isEmpty()) {
            return; // Nothing to do if no selection and no search text
        }

        try {
            ProjectFile targetFile = contextManager.toFile(searchText);
            if (targetFile.exists()) {
                projectTree.selectAndExpandToFile(targetFile);
                SwingUtilities.invokeLater(() -> projectTree.requestFocusInWindow());
                return;
            }

            // Fallback: If toFile didn't find it, check if current text exactly matches a completion's replacement.
            List<Completion> completions = ac.getCompletionProvider().getCompletions(searchField);
            for (Completion comp : completions) {
                if (comp instanceof ProjectFileCompletion pfc
                        && pfc.getReplacementText().equals(searchText)) {
                    projectTree.selectAndExpandToFile(pfc.getProjectFile());
                    SwingUtilities.invokeLater(() -> projectTree.requestFocusInWindow());
                    return;
                }
            }
            logger.debug("Enter on search field: No exact file found for '" + searchText
                    + "'. Tree selection relies on DocumentListener for unique prefixes.");
        } catch (Exception ex) {
            logger.error("Error on search confirmation for file: " + searchText, ex);
        }
    }

    public void showFileInTree(@Nullable ProjectFile file) {
        if (file != null) {
            projectTree.selectAndExpandToFile(file);
        }
    }

    /**
     * Manually refresh the file list displayed in the ProjectTree. Useful when the filesystem watcher misses an event.
     */
    private void refreshProjectFiles() {
        projectTree.onTrackedFilesChanged();
        updateBorderTitle(); // Refresh title in case branch changed
        // Return focus to the search field for continued typing
        SwingUtilities.invokeLater(() -> searchField.requestFocusInWindow());
    }

    /** Updates the panel to reflect the current project state, including branch name in title. */
    public void updatePanel() {
        refreshProjectFiles();
    }

    private static class ProjectFileCompletionProvider extends DefaultCompletionProvider {
        private final IProject project;

        public ProjectFileCompletionProvider(IProject project) {
            this.project = project;
        }

        @Override
        public String getAlreadyEnteredText(JTextComponent comp) {
            return comp.getText();
        }

        @Override
        public boolean isAutoActivateOkay(JTextComponent tc) {
            return true; // Always allow autocomplete popup
        }

        @Override
        protected List<Completion> getCompletionsImpl(JTextComponent comp) {
            String pattern = getAlreadyEnteredText(comp);
            if (pattern.isEmpty() || !project.hasGit()) {
                return Collections.emptyList();
            }

            Set<ProjectFile> candidates = project.getRepo().getTrackedFiles();

            var scoredCompletions = Completions.scoreShortAndLong(
                    pattern,
                    candidates,
                    ProjectFile::getFileName,
                    pf -> pf.getRelPath().toString(),
                    pf -> 0,
                    this::createProjectFileCompletion);

            return scoredCompletions.stream().map(c -> (Completion) c).collect(Collectors.toList());
        }

        private ProjectFileCompletion createProjectFileCompletion(ProjectFile pf) {
            return new ProjectFileCompletion(
                    this,
                    pf.getFileName(),
                    pf.getRelPath().toString(),
                    pf.getRelPath().toString(),
                    pf);
        }
    }

    private static class ProjectFileCompletion extends ShorthandCompletion {
        private final ProjectFile projectFile;

        public ProjectFileCompletion(
                DefaultCompletionProvider provider,
                String inputText,
                String replacementText,
                String shortDesc,
                ProjectFile projectFile) {
            super(provider, inputText, replacementText, shortDesc);
            this.projectFile = projectFile;
        }

        public ProjectFile getProjectFile() {
            return projectFile;
        }
    }
}
