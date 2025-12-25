package ai.brokk.gui;

import ai.brokk.Completions;
import ai.brokk.ContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.components.OverlayPanel;
import ai.brokk.gui.dependencies.DependenciesPanel;
import ai.brokk.gui.util.GitHostUtil;
import ai.brokk.gui.util.Icons;
import ai.brokk.project.IProject;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.border.TitledBorder;
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
    private final DependenciesPanel dependenciesPanel;

    private JTextField searchField;
    private MaterialButton refreshButton;
    private JPanel buttonPanel;
    private ProjectTree projectTree;
    private OverlayPanel searchOverlay;
    private AutoCompletion ac;
    private JSplitPane contentSplitPane;
    private boolean dependenciesVisible = false;
    private final DeferredUpdateHelper deferredUpdateHelper;
    private @Nullable Timer searchDebounceTimer;

    public ProjectFilesPanel(Chrome chrome, ContextManager contextManager, DependenciesPanel dependenciesPanel) {
        super(new BorderLayout(Constants.H_GAP, Constants.V_GAP));
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.project = contextManager.getProject();
        this.dependenciesPanel = dependenciesPanel;

        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Project Files",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)));

        setupSearchFieldAndAutocomplete();
        setupProjectTree();

        // Search bar with refresh button and dependencies toggle
        var searchBarPanel = new JPanel(new BorderLayout(Constants.H_GAP, 0));
        var layeredPane = searchOverlay.createLayeredPane(searchField);
        searchBarPanel.add(layeredPane, BorderLayout.CENTER);

        buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));

        refreshButton = new MaterialButton();
        refreshButton.setIcon(Icons.REFRESH);
        refreshButton.setText(""); // icon-only
        refreshButton.setMargin(new Insets(2, 2, 2, 2)); // match other toolbar material buttons
        refreshButton.setToolTipText("Refresh file list (update tracked files from repository)");
        refreshButton.addActionListener(e -> refreshProjectFiles());
        buttonPanel.add(refreshButton);

        searchBarPanel.add(buttonPanel, BorderLayout.EAST);

        add(searchBarPanel, BorderLayout.NORTH);

        // Create split pane: ProjectTree (top) | Dependencies (bottom, initially hidden)
        var projectTreeScrollPane = new JScrollPane(projectTree);
        contentSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        contentSplitPane.setTopComponent(projectTreeScrollPane);
        contentSplitPane.setBottomComponent(dependenciesPanel);
        contentSplitPane.setResizeWeight(0.7); // 70% to project tree, 30% to dependencies
        contentSplitPane.setDividerSize(5); // Show divider
        contentSplitPane.setDividerLocation(0.7); // Set initial position
        dependenciesPanel.ensureInitialized();

        add(contentSplitPane, BorderLayout.CENTER);

        // Deferred update helper for ProjectFilesPanel (defers refresh when not visible)
        this.deferredUpdateHelper = new DeferredUpdateHelper(this, this::refreshProjectFiles);

        // Initialize badge with current dependency count (also updates border title)
        int liveCount = chrome.getProject().getLiveDependencies().size();
        chrome.updateProjectFilesTabBadge(liveCount);
    }

    private void toggleDependenciesPanel() {
        dependenciesVisible = !dependenciesVisible;
        if (dependenciesVisible) {
            contentSplitPane.setDividerSize(
                    contentSplitPane.getDividerSize() > 0 ? contentSplitPane.getDividerSize() : 5);
            contentSplitPane.setDividerLocation(0.7);
            dependenciesPanel.ensureInitialized();
        } else {
            contentSplitPane.setDividerSize(0);
        }
        contentSplitPane.revalidate();
    }

    public void toggleDependencies() {
        toggleDependenciesPanel();
    }

    /**
     * Updates the panel border title with current branch name and dependency count. Fetches both values from their
     * sources of truth. EDT-safe. This is the canonical method for updating the border title - all callers should
     * use this to ensure consistency.
     */
    public void updateBorderTitle() {
        var branchName = GitHostUtil.getCurrentBranchName(project);
        int dependencyCount = chrome.getProject().getLiveDependencies().size();
        updateBorderTitle(branchName, dependencyCount);
    }

    /**
     * Updates the panel border title with branch name and dependency count. EDT-safe.
     */
    public void updateBorderTitle(String branchName, int dependencyCount) {
        SwingUtilities.invokeLater(() -> {
            var border = getBorder();
            if (border instanceof TitledBorder titledBorder) {
                String baseTitle = "Project Files";
                if (dependencyCount > 0) {
                    baseTitle += " (" + dependencyCount + " dependenc" + (dependencyCount == 1 ? "y" : "ies") + ")";
                }
                String newTitle = !branchName.isBlank() ? baseTitle + " [" + branchName + "]" : baseTitle;
                titledBorder.setTitle(newTitle);
                revalidate();
                repaint();
            }
        });
    }

    private void setupProjectTree() {
        this.projectTree = new ProjectTree(project, contextManager, chrome);
    }

    private void setupSearchFieldAndAutocomplete() {
        searchField = new JTextField(20);
        searchField.setToolTipText("Search tracked project files (type path or filename)");

        var searchPromptLabel = new JLabel("Search project files");
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
                // Debounce: restart timer on each keystroke, expand only after 300ms of no typing
                if (searchDebounceTimer != null) {
                    searchDebounceTimer.stop();
                }
                searchDebounceTimer = new Timer(300, evt -> expandToSingleMatch());
                searchDebounceTimer.setRepeats(false);
                searchDebounceTimer.start();
            }

            private void expandToSingleMatch() {
                String currentText = searchField.getText();
                if (currentText.trim().isEmpty()) {
                    return;
                }

                String typedLower = currentText.toLowerCase(java.util.Locale.ROOT);

                // Move file matching off EDT to avoid lag in large repos
                CompletableFuture.supplyAsync(() -> {
                            Set<ProjectFile> trackedFiles = project.getRepo().getTrackedFiles();
                            return trackedFiles.stream()
                                    .filter(pf -> matchesSearch(pf, typedLower))
                                    .toList();
                        })
                        .thenAccept(matches -> {
                            if (matches.size() == 1) {
                                var match = matches.getFirst();
                                // Apply selection on EDT, check staleness there
                                SwingUtilities.invokeLater(() -> {
                                    // Check if search text changed while we were matching
                                    if (!searchField
                                            .getText()
                                            .toLowerCase(java.util.Locale.ROOT)
                                            .equals(typedLower)) {
                                        return;
                                    }
                                    projectTree.selectAndExpandToFile(match);
                                    searchField.requestFocusInWindow();
                                });
                            }
                        });
            }
        });
    }

    private static boolean matchesSearch(ProjectFile pf, String typedLower) {
        String pathStrLower = pf.getRelPath().toString().toLowerCase(java.util.Locale.ROOT);
        String fileNameLower = pf.getFileName().toLowerCase(java.util.Locale.ROOT);

        if (typedLower.contains("/") || typedLower.contains("\\")) {
            return pathStrLower.startsWith(typedLower);
        } else {
            if (fileNameLower.contains(typedLower)) {
                return true;
            }
            java.nio.file.Path currentParent = pf.getRelPath().getParent();
            while (currentParent != null) {
                if (currentParent
                        .getFileName()
                        .toString()
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains(typedLower)) {
                    return true;
                }
                currentParent = currentParent.getParent();
            }
            return false;
        }
    }

    private void handleSearchConfirmation() {
        // This action is triggered when Enter is pressed in the search field.
        // Priority: If there's a tree selection, focus tree and show context menu immediately.
        // Otherwise, try to find and select a file based on search text.

        // Cancel any pending debounce - Enter takes precedence
        if (searchDebounceTimer != null) {
            searchDebounceTimer.stop();
            searchDebounceTimer = null;
        }

        String searchText = searchField.getText();

        // If there's already a tree selection, focus tree and show context menu regardless of search text
        if (projectTree.getSelectionCount() > 0) {
            projectTree.requestFocusInWindow();
            SwingUtilities.invokeLater(() -> {
                Action contextMenuAction = projectTree.getActionMap().get("showContextMenu");
                if (contextMenuAction != null) {
                    contextMenuAction.actionPerformed(
                            new ActionEvent(projectTree, ActionEvent.ACTION_PERFORMED, "showContextMenu"));
                }
            });
            return;
        }

        // No tree selection - find and select file immediately (same logic as expandToSingleMatch but without debounce)
        if (searchText == null || searchText.trim().isEmpty()) {
            return;
        }

        String typedLower = searchText.toLowerCase(java.util.Locale.ROOT);

        // Move file matching off EDT
        CompletableFuture.supplyAsync(() -> {
                    Set<ProjectFile> trackedFiles = project.getRepo().getTrackedFiles();

                    // First try exact path match
                    for (ProjectFile pf : trackedFiles) {
                        if (pf.getRelPath().toString().equals(searchText)) {
                            return pf;
                        }
                    }

                    // Fall back to the same matching logic as expandToSingleMatch
                    var matches = trackedFiles.stream()
                            .filter(pf -> matchesSearch(pf, typedLower))
                            .toList();
                    return matches.size() == 1 ? matches.getFirst() : null;
                })
                .thenAccept(foundFile -> {
                    if (foundFile != null) {
                        // Apply on EDT for consistency, even though selectAndExpandToFile handles EDT internally
                        SwingUtilities.invokeLater(() -> {
                            projectTree.selectAndExpandToFile(foundFile);
                            projectTree.requestFocusInWindow();
                        });
                    }
                });
    }

    public void showFileInTree(@Nullable ProjectFile file) {
        if (file == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (!isShowing() || !projectTree.isShowing()) {
                logger.debug("ProjectFilesPanel not visible; attempting to reveal before selecting {}", file);
                revealInAncestors();
            }
            projectTree.selectAndExpandToFile(file);
            SwingUtilities.invokeLater(() -> projectTree.requestFocusInWindow());
        });
    }

    /**
     * Best-effort attempt to reveal this panel by interacting with common container types. - If inside a JTabbedPane,
     * select this panel's tab. - If inside a JSplitPane, move the divider to make this panel visible.
     */
    private void revealInAncestors() {
        for (Container p = getParent(); p != null; p = p.getParent()) {
            if (p instanceof JTabbedPane tabs) {
                int idx = tabs.indexOfComponent(this);
                if (idx >= 0) {
                    tabs.setSelectedIndex(idx);
                }
            }
            if (p instanceof JSplitPane split) {
                try {
                    Component left = split.getLeftComponent();
                    Component right = split.getRightComponent();
                    if (left != null && SwingUtilities.isDescendingFrom(this, left)) {
                        split.setDividerLocation(0.25);
                    } else if (right != null && SwingUtilities.isDescendingFrom(this, right)) {
                        split.setDividerLocation(0.75);
                    }
                } catch (Exception ex) {
                    logger.debug("Failed to adjust JSplitPane divider to reveal ProjectFilesPanel", ex);
                }
            }
        }
        revalidate();
        repaint();
    }

    /**
     * Manually refresh the file list displayed in the ProjectTree. Useful when the filesystem watcher misses an event.
     */
    private void refreshProjectFiles() {
        projectTree.onTrackedFilesChanged();
        updateBorderTitle(); // Refresh title in case branch changed
        chrome.updateCommitPanel();
    }

    /** Updates the panel to reflect the current project state, including branch name in title.
     *  This defers the actual refresh when the panel is not visible. */
    public void requestUpdate() {
        deferredUpdateHelper.requestUpdate();
    }

    // Public getters for focus traversal policy
    public JTextField getSearchField() {
        return searchField;
    }

    public MaterialButton getRefreshButton() {
        return refreshButton;
    }

    public ProjectTree getProjectTree() {
        return projectTree;
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

        /**
         * Preserves the computed ranking from Completions.scoreProjectFiles by returning
         * the results from getCompletionsImpl directly. DefaultCompletionProvider#getCompletions
         * applies additional sorting that would override our project-aware ordering; returning
         * the pre-ranked list avoids that and keeps the UI consistent with our scorer.
         */
        @Override
        public List<Completion> getCompletions(JTextComponent comp) {
            return getCompletionsImpl(comp);
        }

        @Override
        protected List<Completion> getCompletionsImpl(JTextComponent comp) {
            String pattern = getAlreadyEnteredText(comp);
            var minLength = 2;
            if (pattern.isEmpty() || !project.hasGit()) {
                return Collections.emptyList();
            }

            Set<ProjectFile> candidates = project.getRepo().getTrackedFiles();

            var scoredCompletions = Completions.scoreProjectFiles(
                    pattern,
                    project,
                    candidates,
                    ProjectFile::getFileName,
                    pf -> pf.getRelPath().toString(),
                    this::createProjectFileCompletion,
                    minLength);

            return scoredCompletions.stream().map(c -> (Completion) c).collect(Collectors.toList());
        }

        private ProjectFileCompletion createProjectFileCompletion(ProjectFile pf) {
            return new ProjectFileCompletion(
                    this,
                    pf.getFileName(),
                    pf.getRelPath().toString(),
                    pf.getRelPath().toString());
        }
    }

    private static class ProjectFileCompletion extends ShorthandCompletion {
        public ProjectFileCompletion(
                DefaultCompletionProvider provider, String inputText, String replacementText, String shortDesc) {
            super(provider, inputText, replacementText, shortDesc);
        }
    }
}
