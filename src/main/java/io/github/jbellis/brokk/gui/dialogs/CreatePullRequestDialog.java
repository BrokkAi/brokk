package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.difftool.ui.BrokkDiffPanel;
import io.github.jbellis.brokk.difftool.ui.BufferSource;
import io.github.jbellis.brokk.git.CommitInfo;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GitCommitBrowserPanel;
import io.github.jbellis.brokk.gui.widgets.FileStatusTable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;


public class CreatePullRequestDialog extends JDialog {
    private static final Logger logger = LogManager.getLogger(CreatePullRequestDialog.class);
    private static final int PROJECT_FILE_MODEL_COLUMN_INDEX = 2;

    private final Chrome chrome;
    private final ContextManager contextManager;
    private JComboBox<String> sourceBranchComboBox;
    private JComboBox<String> targetBranchComboBox;
    private JTextField titleField;
    private JTextArea descriptionArea;
    private GitCommitBrowserPanel commitBrowserPanel;
    private FileStatusTable fileStatusTable;
    private JLabel branchFlowLabel;
    private JButton createPrButton; // Field for the Create PR button
    private Runnable flowUpdater;
    private List<CommitInfo> currentCommits = Collections.emptyList();
    private String mergeBaseCommit = null;

    public CreatePullRequestDialog(Frame owner, Chrome chrome, ContextManager contextManager) {
        super(owner, "Create a Pull Request", true);
        this.chrome = chrome;
        this.contextManager = contextManager;
        
        initializeDialog();
        buildLayout();
    }

    private void initializeDialog() {
        setSize(1000, 900);
        setLocationRelativeTo(getOwner());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    private void buildLayout() {
        setLayout(new BorderLayout());

        // --- top panel: branch selectors -------------------------------------------------------
        var topPanel = new JPanel(new BorderLayout());
        var branchSelectorPanel = createBranchSelectorPanel();
        topPanel.add(branchSelectorPanel, BorderLayout.NORTH);

        // --- title and description panel ----------------------------------------------------
        var prInfoPanel = createPrInfoPanel();
        topPanel.add(prInfoPanel, BorderLayout.CENTER);
        add(topPanel, BorderLayout.NORTH);

        // --- middle: commit browser and file list ---------------------------------------------
        var commitBrowserOptions = new GitCommitBrowserPanel.Options(false, false);
        commitBrowserPanel = new GitCommitBrowserPanel(chrome, contextManager, () -> { /* no-op */ }, commitBrowserOptions);
        // The duplicate initializations that were here have been removed.
        // commitBrowserPanel and fileStatusTable are now initialized once above.
        fileStatusTable = new FileStatusTable();

        var commitsPanel = createTitledPanel("Commits in Pull Request", commitBrowserPanel);
        var filesPanel = createTitledPanel("Files Changed", fileStatusTable);

        var middleSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, commitsPanel, filesPanel);
        middleSplit.setResizeWeight(0.5);

        // --- bottom: buttons ------------------------------------------------------------------
        var buttonPanel = createButtonPanel();
        add(middleSplit, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        SwingUtilities.invokeLater(() -> middleSplit.setDividerLocation(0.5));

        // flow-label updater
        this.flowUpdater = createFlowUpdater();

        // initial data load
        loadBranches();
        setupInputListeners(); // Setup listeners for title and description
        updateCreatePrButtonState(); // Initial state for PR button based on (empty) title/desc
        setupFileStatusTableInteractions(); // Wire up diff viewer interactions
    }

    private void setupFileStatusTableInteractions() {
        var tbl = fileStatusTable.getTable();

        // Double-click listener
        tbl.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int viewRow = tbl.rowAtPoint(e.getPoint());
                    if (viewRow >= 0) {
                        tbl.setRowSelectionInterval(viewRow, viewRow);
                        int modelRow = tbl.convertRowIndexToModel(viewRow);
                        ProjectFile clickedFile = (ProjectFile) tbl.getModel().getValueAt(modelRow, PROJECT_FILE_MODEL_COLUMN_INDEX);
                        openPrDiffViewer(clickedFile);
                    }
                }
            }
        });

        // Context Menu
        var popupMenu = new JPopupMenu();
        var viewDiffItem = new JMenuItem("View Diff");
        viewDiffItem.addActionListener(e -> openPrDiffViewer(null)); // Passing null for priorityFile
        popupMenu.add(viewDiffItem);
        tbl.setComponentPopupMenu(popupMenu);
    }

    private JPanel createPrInfoPanel() {
        var panel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 10, 5, 10);
        gbc.anchor = GridBagConstraints.WEST;

        // Title
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Title:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        titleField = new JTextField();
        panel.add(titleField, gbc);

        // Description
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST; // Align label to top
        panel.add(new JLabel("Description:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0; // Allow description to take vertical space
        gbc.fill = GridBagConstraints.BOTH;
        descriptionArea = new JTextArea(5, 20); // Initial rows and columns
        var scrollPane = new JScrollPane(descriptionArea);
        panel.add(scrollPane, gbc);
        
        return panel;
    }

    private JPanel createBranchSelectorPanel() {
        var branchPanel = new JPanel(new GridBagLayout());
        var row = 0;

        // Create combo boxes first
        targetBranchComboBox = new JComboBox<>();
        sourceBranchComboBox = new JComboBox<>();
        
        // Then add them to the panel
        row = addBranchSelectorToPanel(branchPanel, "Target branch:", targetBranchComboBox, row);
        row = addBranchSelectorToPanel(branchPanel, "Source branch:", sourceBranchComboBox, row);

        this.branchFlowLabel = createBranchFlowIndicator(branchPanel, row); // Assign to field

        // setupBranchListeners is now called in loadBranches after defaults are set
        // loadBranches is called after the main layout is built

        return branchPanel;
    }

    private int addBranchSelectorToPanel(JPanel parent, String labelText, JComboBox<String> comboBox, int row) {
        var gbc = createGbc(0, row);
        parent.add(new JLabel(labelText), gbc);
        
        gbc = createGbc(1, row);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        parent.add(comboBox, gbc);
        
        return row + 1;
    }
    
    private JLabel createBranchFlowIndicator(JPanel parent, int row) {
        var branchFlowLabel = new JLabel("", SwingConstants.CENTER);
        branchFlowLabel.setFont(branchFlowLabel.getFont().deriveFont(Font.BOLD));
        
        var gbc = createGbc(0, row);
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        parent.add(branchFlowLabel, gbc);
        
        return branchFlowLabel;
    }

    /**
     * Simple immutable holder for commits and changed files between two branches.
     */
    private record BranchDiff(List<CommitInfo> commits,
                              List<GitRepo.ModifiedFile> changedFiles) {}

    /**
     * Collects both commits and changed files between the given branches.
     */
    private BranchDiff getBranchDiff(GitRepo gitRepo,
                                     String sourceBranch,
                                     String targetBranch) throws GitAPIException {
        assert gitRepo != null;
        var commits = gitRepo.listCommitsBetweenBranches(sourceBranch, targetBranch, true);
        var files   = gitRepo.listFilesChangedBetweenBranches(sourceBranch, targetBranch);
        return new BranchDiff(commits, files);
    }

    // Fix the UnnecessaryLambda warning by implementing updateBranchFlow as a method
    private void updateBranchFlow() {
        var target = (String) targetBranchComboBox.getSelectedItem();
        var source = (String) sourceBranchComboBox.getSelectedItem();
        if (target != null && source != null) {
            String baseText = target + " ← " + source;
            // currentCommits is initialized to emptyList, so it's never null.
            this.branchFlowLabel.setText(baseText + " (" + currentCommits.size() + " commits)");
        } else {
            this.branchFlowLabel.setText(""); // Clear if branches not selected
        }
    }

    private Runnable createFlowUpdater() {
        return this::updateBranchFlow;
    }

    private void setupBranchListeners() {
        ActionListener branchChangedListener = e -> {
            // Immediately update flow label for responsiveness before async refresh.
            this.currentCommits = Collections.emptyList();
            if (this.flowUpdater != null) {
                this.flowUpdater.run();
            }
            // Full UI update, including button state, will be handled by refreshCommitList.
            refreshCommitList();
        };
        targetBranchComboBox.addActionListener(branchChangedListener);
        sourceBranchComboBox.addActionListener(branchChangedListener);
    }

    private void updateCommitRelatedUI(List<CommitInfo> newCommits, List<GitRepo.ModifiedFile> newFiles, String commitPanelMessage) {
        this.currentCommits = newCommits;
        commitBrowserPanel.setCommits(newCommits, Set.of(), false, false, commitPanelMessage);
        if (fileStatusTable != null) {
            fileStatusTable.setFiles(newFiles == null ? Collections.emptyList() : newFiles);
        }
        if (this.flowUpdater != null) {
            this.flowUpdater.run();
        }
        updateCreatePrButtonState();
    }

    private void refreshCommitList() {
        this.mergeBaseCommit = null; // Ensure merge base is reset for each refresh

        var sourceBranch = (String) sourceBranchComboBox.getSelectedItem();
        var targetBranch = (String) targetBranchComboBox.getSelectedItem();

        if (sourceBranch == null || targetBranch == null) {
            updateCommitRelatedUI(Collections.emptyList(), Collections.emptyList(), "Select branches");
            return;
        }

        var contextName = targetBranch + " ← " + sourceBranch;

        if (sourceBranch.equals(targetBranch)) {
            updateCommitRelatedUI(Collections.emptyList(), Collections.emptyList(), contextName);
            return;
        }

        contextManager.submitBackgroundTask("Fetching commits for " + contextName, () -> {
            try {
                var repo = contextManager.getProject().getRepo();
                if (!(repo instanceof GitRepo gitRepo)) {
                    String nonGitMessage = "Project is not a Git repository.";
                    SwingUtilities.invokeLater(() -> updateCommitRelatedUI(Collections.emptyList(),
                                                                           Collections.emptyList(),
                                                                           nonGitMessage));
                    return Collections.emptyList();
                }

                // Calculate and store merge base
                this.mergeBaseCommit = gitRepo.getMergeBase(sourceBranch, targetBranch);
                logger.debug("Calculated merge base between {} and {}: {}", sourceBranch, targetBranch, this.mergeBaseCommit);

                var diff = getBranchDiff(gitRepo, sourceBranch, targetBranch);

                SwingUtilities.invokeLater(() -> updateCommitRelatedUI(diff.commits(),
                                                                       diff.changedFiles(),
                                                                       contextName));
                return diff.commits();
            } catch (Exception e) {
                logger.error("Error fetching commits or changed files (and merge base) for " + contextName, e);
                SwingUtilities.invokeLater(() -> updateCommitRelatedUI(Collections.emptyList(), Collections.emptyList(), contextName + " (error)"));
                throw e;
            }
        });
    }

    private void updateCreatePrButtonState() {
        if (createPrButton != null) {
            createPrButton.setEnabled(isCreatePrReady());
        }
    }

    /**
     * Checks whether the dialog has sufficient information to enable PR creation.
     */
    private boolean isCreatePrReady() {
        var title = titleField.getText();
        var description = descriptionArea.getText();
        var sourceBranch = (String) sourceBranchComboBox.getSelectedItem();
        var targetBranch = (String) targetBranchComboBox.getSelectedItem();

        var branchesDifferentAndSelected = sourceBranch != null
                                           && targetBranch != null
                                           && !sourceBranch.equals(targetBranch);
        var prInfoFilled = title != null && !title.trim().isEmpty()
                           && description != null && !description.trim().isEmpty();

        return !currentCommits.isEmpty() && branchesDifferentAndSelected && prInfoFilled;
    }
    
    private void setupInputListeners() {
        javax.swing.event.DocumentListener inputChangedListener = new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                updateCreatePrButtonState();
            }
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updateCreatePrButtonState();
            }
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                updateCreatePrButtonState();
            }
        };
        titleField.getDocument().addDocumentListener(inputChangedListener);
        descriptionArea.getDocument().addDocumentListener(inputChangedListener);
    }

    private JPanel createButtonPanel() {
        var buttonPanel = new JPanel(new FlowLayout());
        
        this.createPrButton = new JButton("Create PR"); // Assign to field
        this.createPrButton.addActionListener(e -> {
            // TODO: Implement PR creation logic
            dispose();
        });
        buttonPanel.add(this.createPrButton);
        
        var cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dispose());
        buttonPanel.add(cancelButton);
        
        return buttonPanel;
    }
    
    private GridBagConstraints createGbc(int x, int y) {
        var gbc = new GridBagConstraints();
        gbc.gridx = x;
        gbc.gridy = y;
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.anchor = GridBagConstraints.WEST;
        return gbc;
    }

    private void loadBranches() {
        var repo = contextManager.getProject().getRepo();
        if (!(repo instanceof GitRepo gitRepo)) {
            logger.warn("PR dialog opened for non-Git repository");
            return;
        }

        try {
            var localBranches = gitRepo.listLocalBranches();
            var remoteBranches = gitRepo.listRemoteBranches();
            
            var targetBranches = getTargetBranches(remoteBranches);
            var sourceBranches = getSourceBranches(localBranches, remoteBranches);
            
            populateBranchDropdowns(targetBranches, sourceBranches);
            setDefaultBranchSelections(gitRepo, targetBranches, sourceBranches, localBranches);

            // Listeners must be set up AFTER default items are selected to avoid premature firing.
            setupBranchListeners();

            if (this.flowUpdater != null) this.flowUpdater.run(); // Update label based on defaults
            refreshCommitList();   // Load commits based on defaults, which will also call flowUpdater and update button state
            // updateCreatePrButtonState(); // No longer needed here, refreshCommitList handles it
        } catch (GitAPIException e) {
            logger.error("Error loading branches for PR dialog", e);
            // Ensure UI is updated consistently on error, using the new helper method
            SwingUtilities.invokeLater(() -> updateCommitRelatedUI(Collections.emptyList(), Collections.emptyList(), "Error loading branches"));
        }
    }

    private List<String> getTargetBranches(List<String> remoteBranches) {
        return remoteBranches.stream()
                .filter(branch -> branch.startsWith("origin/"))
                .filter(branch -> !branch.equals("origin/HEAD"))
                .toList();
    }
    
    private List<String> getSourceBranches(List<String> localBranches, List<String> remoteBranches) {
        return java.util.stream.Stream.concat(localBranches.stream(), remoteBranches.stream())
                .distinct()
                .sorted()
                .toList();
    }
    
    private void populateBranchDropdowns(List<String> targetBranches, List<String> sourceBranches) {
        targetBranchComboBox.setModel(new DefaultComboBoxModel<>(targetBranches.toArray(new String[0])));
        sourceBranchComboBox.setModel(new DefaultComboBoxModel<>(sourceBranches.toArray(new String[0])));
    }
    
    private void setDefaultBranchSelections(GitRepo gitRepo, List<String> targetBranches,
                                           List<String> sourceBranches, List<String> localBranches) throws GitAPIException {
        var defaultTarget = findDefaultTargetBranch(targetBranches);
        if (defaultTarget != null) {
            targetBranchComboBox.setSelectedItem(defaultTarget);
        }

        selectDefaultSourceBranch(gitRepo, sourceBranches, localBranches);
    }
    
    private String findDefaultTargetBranch(List<String> targetBranches) {
        if (targetBranches.contains("origin/main")) {
            return "origin/main";
        }
        if (targetBranches.contains("origin/master")) {
            return "origin/master";
        }
        return targetBranches.isEmpty() ? null : targetBranches.getFirst();
    }
    
    private void selectDefaultSourceBranch(GitRepo gitRepo, List<String> sourceBranches, List<String> localBranches) throws GitAPIException {
        var currentBranch = gitRepo.getCurrentBranch();
        if (currentBranch != null && sourceBranches.contains(currentBranch)) {
            sourceBranchComboBox.setSelectedItem(currentBranch);
        } else if (!localBranches.isEmpty()) {
            sourceBranchComboBox.setSelectedItem(localBranches.getFirst());
        } else if (!sourceBranches.isEmpty()) {
            sourceBranchComboBox.setSelectedItem(sourceBranches.getFirst());
        }
    }

    public static void show(Frame owner, Chrome chrome, ContextManager contextManager) {
        CreatePullRequestDialog dialog = new CreatePullRequestDialog(owner, chrome, contextManager);
        dialog.setVisible(true);
    }

    private JPanel createTitledPanel(String title, JComponent contentComponent) {
        var panel = new JPanel(new BorderLayout(0, 5)); // Add small vertical gap
        var label = new JLabel(title, SwingConstants.LEFT);
        label.setBorder(BorderFactory.createEmptyBorder(5, 5, 0, 5)); // Add padding
        panel.add(label, BorderLayout.NORTH);
        panel.add(contentComponent, BorderLayout.CENTER);
        return panel;
    }

    private List<ProjectFile> getAllFilesFromFileStatusTable() {
        var files = new ArrayList<ProjectFile>();
        var model = fileStatusTable.getTable().getModel();
        for (int i = 0; i < model.getRowCount(); i++) {
            files.add((ProjectFile) model.getValueAt(i, PROJECT_FILE_MODEL_COLUMN_INDEX));
        }
        return files;
    }

    private BufferSource.StringSource createBufferSource(GitRepo repo, String commitSHA,
                                                         ProjectFile f) throws GitAPIException {
        var content = repo.getFileContent(commitSHA, f);
        return new BufferSource.StringSource(content, commitSHA, f.getFileName());
    }

    private List<ProjectFile> getOrderedFilesForDiff(ProjectFile priorityFile) {
        var allFilesInTable = getAllFilesFromFileStatusTable();
        var selectedFiles = fileStatusTable.getSelectedFiles();
        var orderedFiles = new ArrayList<ProjectFile>();

        if (priorityFile != null) {
            orderedFiles.add(priorityFile);
        }

        selectedFiles.stream()
                .filter(selected -> !orderedFiles.contains(selected))
                .forEach(orderedFiles::add);

        allFilesInTable.stream()
                .filter(fileInTable -> !orderedFiles.contains(fileInTable))
                .forEach(orderedFiles::add);

        return orderedFiles;
    }

    private record FileComparisonSources(BufferSource left, BufferSource right) {}

    private FileComparisonSources createFileComparisonSources(GitRepo gitRepo, String mergeBaseSha,
                                                              String sourceCommitIsh, ProjectFile file,
                                                              String status) throws GitAPIException {
        BufferSource leftSrc;
        BufferSource rightSrc;

        if ("deleted".equals(status)) {
            leftSrc = createBufferSource(gitRepo, mergeBaseSha, file);
            rightSrc = new BufferSource.StringSource("", "Source Branch (Deleted)", file.getFileName());
        } else {
            // For "added" files, createBufferSource(gitRepo, mergeBaseSha, file) will attempt to load from merge base.
            // Assumes GitRepo.getFileContent handles non-existent files gracefully (e.g., returns empty string).
            leftSrc = createBufferSource(gitRepo, mergeBaseSha, file);
            rightSrc = createBufferSource(gitRepo, sourceCommitIsh, file);
        }
        return new FileComparisonSources(leftSrc, rightSrc);
    }

    private void buildAndShowDiffPanel(List<ProjectFile> orderedFiles, String mergeBaseCommitSha, String sourceBranchName) {
        try {
            var repo = contextManager.getProject().getRepo();
            if (!(repo instanceof GitRepo gitRepo)) {
                chrome.toolError("Repository is not a Git repository.", "Diff Error");
                return;
            }

            var builder = new BrokkDiffPanel.Builder(chrome.getTheme(), contextManager);

            for (var f : orderedFiles) {
                String status = fileStatusTable.statusFor(f);
                var sources = createFileComparisonSources(gitRepo, mergeBaseCommitSha, sourceBranchName, f, status);
                builder.addComparison(sources.left(), sources.right());
            }
            SwingUtilities.invokeLater(() -> builder.build().showInFrame("Pull Request Diff"));
        } catch (Exception ex) {
            logger.error("Unable to open PR diff viewer", ex);
            chrome.toolError("Unable to open diff: " + ex.getMessage(), "Diff Error");
        }
    }

    private void openPrDiffViewer(ProjectFile priorityFile) {
        var orderedFiles = getOrderedFilesForDiff(priorityFile);

        final String currentMergeBase = this.mergeBaseCommit;
        final String currentSourceBranch = (String) sourceBranchComboBox.getSelectedItem();

        if (currentSourceBranch == null) { 
            chrome.toolError("Source branch is not selected.", "Diff Error");
            return;
        }
        if (currentMergeBase == null) {
            chrome.toolError("Merge base could not be determined. Cannot show diff.", "Diff Error");
            logger.warn("Merge base is null when trying to open PR diff viewer. Source: {}, Target: {}", currentSourceBranch, targetBranchComboBox.getSelectedItem());
            return;
        }

        contextManager.submitUserTask("Show PR diff", () -> {
            buildAndShowDiffPanel(orderedFiles, currentMergeBase, currentSourceBranch);
            return null;
        });
    }
}
