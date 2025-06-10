package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.gui.Chrome;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class CreatePullRequestDialog extends JDialog {
    private static final Logger logger = LogManager.getLogger(CreatePullRequestDialog.class);

    private final Chrome chrome;
    private final ContextManager contextManager;
    private JComboBox<String> sourceBranchComboBox;
    private JComboBox<String> targetBranchComboBox;

    public CreatePullRequestDialog(Frame owner, Chrome chrome, ContextManager contextManager) {
        super(owner, "Create a Pull Request", true);
        this.chrome = chrome;
        this.contextManager = contextManager;
        
        initializeDialog();
        buildLayout();
    }

    private void initializeDialog() {
        setSize(400, 300);
        setLocationRelativeTo(getOwner());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    private void buildLayout() {
        setLayout(new BorderLayout());
        
        var contentPanel = createContentPanel();
        add(contentPanel, BorderLayout.CENTER);
        
        var buttonPanel = createButtonPanel();
        add(buttonPanel, BorderLayout.SOUTH);
    }
    
    private JPanel createContentPanel() {
        var contentPanel = new JPanel(new GridBagLayout());
        var row = 0;
        
        row = addBranchSelector(contentPanel, "Target branch:", targetBranchComboBox = new JComboBox<>(), row);
        row = addBranchSelector(contentPanel, "Source branch:", sourceBranchComboBox = new JComboBox<>(), row);
        
        var branchFlowLabel = createBranchFlowIndicator(contentPanel, row);
        var updateFlowLabel = createFlowUpdater(branchFlowLabel);
        
        setupBranchListeners(updateFlowLabel);
        loadBranches(updateFlowLabel);
        
        return contentPanel;
    }
    
    private int addBranchSelector(JPanel parent, String labelText, JComboBox<String> comboBox, int row) {
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
        targetBranchComboBox.addActionListener(e -> updateFlowLabel.run());
        sourceBranchComboBox.addActionListener(e -> updateFlowLabel.run());
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
            updateFlowLabel.run();
        } catch (GitAPIException e) {
            logger.error("Error loading branches for PR dialog", e);
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
