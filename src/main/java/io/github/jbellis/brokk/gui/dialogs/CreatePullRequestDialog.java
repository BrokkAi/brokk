package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.git.CommitInfo;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GitCommitBrowserPanel;
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

        // Panel for branch selectors
        var branchSelectorPanel = createBranchSelectorPanel();
        add(branchSelectorPanel, BorderLayout.NORTH);

        // Commit browser panel
        var commitBrowserOptions = new GitCommitBrowserPanel.Options(false, false);
        commitBrowserPanel = new GitCommitBrowserPanel(chrome, contextManager, () -> {
            // No-op reloader for now, will be wired up later
        }, commitBrowserOptions);

        // Button panel
        var buttonPanel = createButtonPanel();

        // Split pane for commit browser and buttons
        var splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, commitBrowserPanel, buttonPanel);
        splitPane.setResizeWeight(0.8); // Give more space to commit browser initially
        add(splitPane, BorderLayout.CENTER);

        // Load branches after UI is built
        loadBranches(createFlowUpdater((JLabel) branchSelectorPanel.getComponent(4))); // Assuming JLabel is at index 4
    }

    private JPanel createBranchSelectorPanel() {
        var branchPanel = new JPanel(new GridBagLayout());
        var row = 0;

        row = addBranchSelectorToPanel(branchPanel, "Target branch:", targetBranchComboBox = new JComboBox<>(), row);
        row = addBranchSelectorToPanel(branchPanel, "Source branch:", sourceBranchComboBox = new JComboBox<>(), row);

        var branchFlowLabel = createBranchFlowIndicator(branchPanel, row);
        var updateFlowLabel = createFlowUpdater(branchFlowLabel);

        setupBranchListeners(updateFlowLabel);
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
    
    private Runnable createFlowUpdater(JLabel branchFlowLabel) {
        return () -> {
            var target = (String) targetBranchComboBox.getSelectedItem();
            var source = (String) sourceBranchComboBox.getSelectedItem();
            if (target != null && source != null) {
                branchFlowLabel.setText(target + " ← " + source);
            }
        };
    }
    
    private void setupBranchListeners(Runnable updateFlowLabel) {
        ActionListener branchChangedListener = e -> {
            updateFlowLabel.run();
            refreshCommitList();
        };
        targetBranchComboBox.addActionListener(branchChangedListener);
        sourceBranchComboBox.addActionListener(branchChangedListener);
    }

    private void refreshCommitList() {
        var sourceBranch = (String) sourceBranchComboBox.getSelectedItem();
        var targetBranch = (String) targetBranchComboBox.getSelectedItem();

        if (sourceBranch == null || targetBranch == null) {
            commitBrowserPanel.setCommits(Collections.emptyList(), Set.of(), false, false, "Select branches");
            return;
        }

        var contextName = targetBranch + " ← " + sourceBranch;

        if (sourceBranch.equals(targetBranch)) {
            commitBrowserPanel.setCommits(Collections.emptyList(), Set.of(), false, false, contextName);
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
                SwingUtilities.invokeLater(() ->
                    commitBrowserPanel.setCommits(commits, Set.of(), false, false, contextName)
                );
                return commits;
            } catch (Exception e) {
                logger.error("Error fetching commits for " + contextName, e);
                SwingUtilities.invokeLater(() ->
                    commitBrowserPanel.setCommits(Collections.emptyList(), Set.of(), false, false, contextName + " (error)")
                );
                throw e;
            }
        });
    }
    
    private JPanel createButtonPanel() {
        var buttonPanel = new JPanel(new FlowLayout());
        
        var createButton = new JButton("Create PR");
        createButton.addActionListener(e -> {
            // TODO: Implement PR creation logic
            dispose();
        });
        buttonPanel.add(createButton);
        
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
    
    private void loadBranches(Runnable updateFlowLabel) {
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
            updateFlowLabel.run(); // Update label first
            refreshCommitList();   // Then load commits
        } catch (GitAPIException e) {
            logger.error("Error loading branches for PR dialog", e);
            commitBrowserPanel.setCommits(Collections.emptyList(), Set.of(), false, false, "Error loading branches");
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
