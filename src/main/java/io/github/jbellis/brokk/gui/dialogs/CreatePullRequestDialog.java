package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.ContextManager;
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
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class CreatePullRequestDialog extends JDialog {
    private static final Logger logger = LogManager.getLogger(CreatePullRequestDialog.class);

    private final Chrome chrome;
    private final ContextManager contextManager;
    private JComboBox<String> sourceBranchComboBox;
    private JComboBox<String> targetBranchComboBox;
    private GitCommitBrowserPanel commitBrowserPanel;
    private FileStatusTable fileStatusTable;
    private JLabel branchFlowLabel;
    private JButton createPrButton; // Field for the Create PR button
    private Runnable flowUpdater;
    private List<CommitInfo> currentCommits = Collections.emptyList();

    public CreatePullRequestDialog(Frame owner, Chrome chrome, ContextManager contextManager) {
        super(owner, "Create a Pull Request", true);
        this.chrome = chrome;
        this.contextManager = contextManager;
        
        initializeDialog();
        buildLayout();
    }

    private void initializeDialog() {
        setSize(800, 800); 
        setLocationRelativeTo(getOwner());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    private void buildLayout() {
        setLayout(new BorderLayout());

        // --- top panel: branch selectors -------------------------------------------------------
        var branchSelectorPanel = createBranchSelectorPanel();
        add(branchSelectorPanel, BorderLayout.NORTH);

        // --- middle: commit browser and file list ---------------------------------------------
        var commitBrowserOptions = new GitCommitBrowserPanel.Options(false, false);
        commitBrowserPanel = new GitCommitBrowserPanel(chrome, contextManager, () -> { /* no-op */ }, commitBrowserOptions);

        fileStatusTable = new FileStatusTable();

        var middleSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, commitBrowserPanel, fileStatusTable);
        middleSplit.setResizeWeight(0.6);

        // --- bottom: buttons ------------------------------------------------------------------
        var buttonPanel = createButtonPanel();
        var rootSplit   = new JSplitPane(JSplitPane.VERTICAL_SPLIT, middleSplit, buttonPanel);
        rootSplit.setResizeWeight(0.9);
        add(rootSplit, BorderLayout.CENTER);

        // flow-label updater
        this.flowUpdater = createFlowUpdater();

        // initial data load
        loadBranches();
    }

    private JPanel createBranchSelectorPanel() {
        var branchPanel = new JPanel(new GridBagLayout());
        var row = 0;

        row = addBranchSelectorToPanel(branchPanel, "Target branch:", targetBranchComboBox = new JComboBox<>(), row);
        row = addBranchSelectorToPanel(branchPanel, "Source branch:", sourceBranchComboBox = new JComboBox<>(), row);

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

    private Runnable createFlowUpdater() {
        return () -> {
            var target = (String) targetBranchComboBox.getSelectedItem();
            var source = (String) sourceBranchComboBox.getSelectedItem();
            if (target != null && source != null) {
                String baseText = target + " ← " + source;
                // currentCommits is initialized to emptyList, so it's never null.
                this.branchFlowLabel.setText(baseText + " (" + currentCommits.size() + " commits)");
            } else {
                this.branchFlowLabel.setText(""); // Clear if branches not selected
            }
        };
    }

    private void setupBranchListeners() {
        ActionListener branchChangedListener = e -> {
            if (this.flowUpdater != null) {
                // Temporarily set commits to empty when branches change,
                // so the label updates immediately before async refresh completes.
                this.currentCommits = Collections.emptyList();
                this.flowUpdater.run();
                updateCreatePrButtonState(); // Update button state
            }
            refreshCommitList();
        };
        targetBranchComboBox.addActionListener(branchChangedListener);
        sourceBranchComboBox.addActionListener(branchChangedListener);
    }

    private void refreshCommitList() {
        var sourceBranch = (String) sourceBranchComboBox.getSelectedItem();
        var targetBranch = (String) targetBranchComboBox.getSelectedItem();

        if (sourceBranch == null || targetBranch == null) {
            this.currentCommits = Collections.emptyList();
            commitBrowserPanel.setCommits(Collections.emptyList(), Set.of(), false, false, "Select branches");
            if (fileStatusTable != null) fileStatusTable.setFiles(Collections.emptyList());
            if (this.flowUpdater != null) this.flowUpdater.run();
            updateCreatePrButtonState();
            return;
        }

        var contextName = targetBranch + " ← " + sourceBranch;

        if (sourceBranch.equals(targetBranch)) {
            this.currentCommits = Collections.emptyList();
            commitBrowserPanel.setCommits(Collections.emptyList(), Set.of(), false, false, contextName);
            if (fileStatusTable != null) fileStatusTable.setFiles(Collections.emptyList());
            if (this.flowUpdater != null) this.flowUpdater.run();
            updateCreatePrButtonState();
            return;
        }

        contextManager.submitBackgroundTask("Fetching commits for " + contextName, () -> {
            try {
                var repo = contextManager.getProject().getRepo();
                List<CommitInfo> commits;
                if (repo instanceof GitRepo gitRepo) {
                    // For PRs, we want to exclude merge commits from the target branch
                    commits = gitRepo.listCommitsBetweenBranches(sourceBranch, targetBranch, true);
                } else {
                    commits = Collections.emptyList();
                }
                List<CommitInfo> finalCommits = commits;
                List<GitRepo.ModifiedFile> changedFiles;
                if (repo instanceof GitRepo gitRepo) {
                    changedFiles = gitRepo.listFilesChangedBetweenBranches(sourceBranch, targetBranch);
                } else {
                    changedFiles = Collections.emptyList();
                }
                List<GitRepo.ModifiedFile> finalChangedFiles = changedFiles == null ? List.of() : changedFiles;


                SwingUtilities.invokeLater(() -> {
                    this.currentCommits = finalCommits;
                    commitBrowserPanel.setCommits(finalCommits, Set.of(), false, false, contextName);
                    fileStatusTable.setFiles(finalChangedFiles);
                    if (this.flowUpdater != null) this.flowUpdater.run();
                    updateCreatePrButtonState();
                });
                return commits;
            } catch (Exception e) {
                logger.error("Error fetching commits or changed files for " + contextName, e);
                SwingUtilities.invokeLater(() -> {
                    this.currentCommits = Collections.emptyList();
                    commitBrowserPanel.setCommits(Collections.emptyList(), Set.of(), false, false, contextName + " (error)");
                    fileStatusTable.setFiles(Collections.emptyList());
                    if (this.flowUpdater != null) this.flowUpdater.run();
                    updateCreatePrButtonState();
                });
                throw e;
            }
        });
    }

    private void updateCreatePrButtonState() {
        if (createPrButton != null) {
            createPrButton.setEnabled(!currentCommits.isEmpty());
        }
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
            refreshCommitList();   // Load commits based on defaults, which will also call flowUpdater
            updateCreatePrButtonState(); // Set initial button state
        } catch (GitAPIException e) {
            logger.error("Error loading branches for PR dialog", e);
            this.currentCommits = Collections.emptyList();
            commitBrowserPanel.setCommits(Collections.emptyList(), Set.of(), false, false, "Error loading branches");
            if (fileStatusTable != null) fileStatusTable.setFiles(Collections.emptyList());
            if (this.flowUpdater != null) this.flowUpdater.run();
            updateCreatePrButtonState(); // Ensure button is disabled on error
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
}
