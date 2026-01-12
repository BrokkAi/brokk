package ai.brokk.gui.agents;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.git.GitRepo;
import ai.brokk.git.IGitRepo;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.components.FuzzyComboBox;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.util.Icons;
import ai.brokk.project.MainProject;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

/**
 * Panel for managing background agents. Each agent runs in its own worktree
 * with a headless executor subprocess.
 */
public class AgentsTab extends JPanel implements AgentManager.AgentListener {
    private static final Logger logger = LogManager.getLogger(AgentsTab.class);
    
    private final Chrome chrome;
    private final ContextManager contextManager;
    private final AgentManager agentManager;
    
    private final JTable agentTable;
    private final DefaultTableModel agentTableModel;
    private final MaterialButton addButton;
    private final MaterialButton removeButton;
    private final MaterialButton cancelButton;
    
    private final JSplitPane splitPane;
    private final JPanel detailPanel;
    private @Nullable AgentDetailPanel currentDetailPanel;
    private @Nullable AgentInstance selectedAgent;
    
    public AgentsTab(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        
        // Get the main project for agent management
        MainProject mainProject = contextManager.getProject().getMainProject();
        this.agentManager = new AgentManager(mainProject);
        this.agentManager.addListener(this);
        
        // Check if git is available
        IGitRepo repo = contextManager.getProject().getRepo();
        if (!repo.supportsWorktrees()) {
            buildUnsupportedUI();
            this.agentTable = new JTable();
            this.agentTableModel = new DefaultTableModel();
            this.addButton = new MaterialButton();
            this.removeButton = new MaterialButton();
            this.cancelButton = new MaterialButton();
            this.splitPane = new JSplitPane();
            this.detailPanel = new JPanel();
            return;
        }
        
        // Build the agent list table
        agentTableModel = new DefaultTableModel(new Object[]{"Name", "Branch", "Status"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        
        agentTable = new JTable(agentTableModel);
        agentTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        agentTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onAgentSelected();
            }
        });
        
        // Configure column widths
        agentTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        agentTable.getColumnModel().getColumn(1).setPreferredWidth(150);
        agentTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        
        // Status column renderer with colors
        agentTable.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (value instanceof AgentInstance.State state) {
                    setText(state.name());
                    switch (state) {
                        case READY, COMPLETED -> setForeground(new Color(0, 150, 0));
                        case RUNNING -> setForeground(new Color(0, 100, 200));
                        case FAILED -> setForeground(Color.RED);
                        case STARTING, SHUTTING_DOWN -> setForeground(Color.ORANGE);
                        default -> setForeground(table.getForeground());
                    }
                }
                return c;
            }
        });
        
        // Button panel
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));
        
        addButton = new MaterialButton();
        addButton.setIcon(Icons.ADD);
        addButton.setToolTipText("Create new agent");
        addButton.addActionListener(e -> showCreateAgentDialog());
        
        removeButton = new MaterialButton();
        removeButton.setIcon(Icons.REMOVE);
        removeButton.setToolTipText("Shutdown selected agent");
        removeButton.setEnabled(false);
        removeButton.addActionListener(e -> shutdownSelectedAgent());
        
        cancelButton = new MaterialButton();
        cancelButton.setIcon(Icons.STOP);
        cancelButton.setToolTipText("Cancel current job");
        cancelButton.setEnabled(false);
        cancelButton.addActionListener(e -> cancelSelectedAgentJob());
        
        buttonPanel.add(addButton);
        buttonPanel.add(removeButton);
        buttonPanel.add(cancelButton);
        buttonPanel.add(Box.createHorizontalGlue());
        
        // Left panel with agent list
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBorder(BorderFactory.createTitledBorder("Agents"));
        leftPanel.add(new JScrollPane(agentTable), BorderLayout.CENTER);
        leftPanel.add(buttonPanel, BorderLayout.SOUTH);
        leftPanel.setMinimumSize(new Dimension(200, 200));
        leftPanel.setPreferredSize(new Dimension(300, 400));
        
        // Right panel for agent details
        detailPanel = new JPanel(new BorderLayout());
        detailPanel.setBorder(BorderFactory.createTitledBorder("Agent Details"));
        showEmptyDetailPanel();
        
        // Split pane
        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, detailPanel);
        splitPane.setResizeWeight(0.3);
        splitPane.setDividerLocation(300);
        
        add(splitPane, BorderLayout.CENTER);
    }
    
    private void buildUnsupportedUI() {
        removeAll();
        
        JPanel contentPanel = new JPanel(new GridBagLayout());
        JLabel unsupportedLabel = new JLabel("Git repository required for Agents");
        unsupportedLabel.setHorizontalAlignment(SwingConstants.CENTER);
        contentPanel.add(unsupportedLabel, new GridBagConstraints());
        
        JPanel titledPanel = new JPanel(new BorderLayout());
        titledPanel.setBorder(BorderFactory.createTitledBorder("Agents"));
        titledPanel.add(contentPanel, BorderLayout.CENTER);
        
        add(titledPanel, BorderLayout.CENTER);
        revalidate();
        repaint();
    }
    
    private void showEmptyDetailPanel() {
        detailPanel.removeAll();
        JLabel emptyLabel = new JLabel("Select an agent or create a new one");
        emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
        emptyLabel.setForeground(Color.GRAY);
        detailPanel.add(emptyLabel, BorderLayout.CENTER);
        detailPanel.revalidate();
        detailPanel.repaint();
    }
    
    private void onAgentSelected() {
        int selectedRow = agentTable.getSelectedRow();
        if (selectedRow < 0) {
            selectedAgent = null;
            showEmptyDetailPanel();
            updateButtonStates();
            return;
        }
        
        // Find the agent by name
        String agentName = (String) agentTableModel.getValueAt(selectedRow, 0);
        selectedAgent = agentManager.getAgents().stream()
                .filter(a -> a.getName().equals(agentName))
                .findFirst()
                .orElse(null);
        
        if (selectedAgent != null) {
            showAgentDetail(selectedAgent);
        } else {
            showEmptyDetailPanel();
        }
        
        updateButtonStates();
    }
    
    private void showAgentDetail(AgentInstance agent) {
        detailPanel.removeAll();
        currentDetailPanel = new AgentDetailPanel(chrome, agentManager, agent);
        detailPanel.add(currentDetailPanel, BorderLayout.CENTER);
        detailPanel.revalidate();
        detailPanel.repaint();
    }
    
    private void updateButtonStates() {
        boolean hasSelection = selectedAgent != null;
        removeButton.setEnabled(hasSelection && !selectedAgent.isTerminal());
        cancelButton.setEnabled(hasSelection && selectedAgent.getState() == AgentInstance.State.RUNNING);
    }
    
    private void showCreateAgentDialog() {
        contextManager.submitContextTask(() -> {
            IGitRepo repo = contextManager.getProject().getRepo();
            if (!(repo instanceof GitRepo gitRepo)) {
                SwingUtilities.invokeLater(() -> 
                    chrome.toolError("Git repository required for agent creation", "Cannot Create Agent"));
                return;
            }
            
            List<String> localBranches;
            Set<String> branchesInWorktrees;
            String currentBranch;
            
            try {
                localBranches = gitRepo.listLocalBranches();
                branchesInWorktrees = gitRepo.getBranchesInWorktrees();
                currentBranch = gitRepo.getCurrentBranch();
            } catch (GitAPIException e) {
                logger.error("Error fetching branch information", e);
                SwingUtilities.invokeLater(() -> 
                    chrome.toolError("Failed to fetch branch information: " + e.getMessage(), "Git Error"));
                return;
            }
            
            // Filter out branches already in worktrees
            List<String> availableBranches = localBranches.stream()
                    .filter(b -> !branchesInWorktrees.contains(b))
                    .toList();
            
            SwingUtilities.invokeLater(() -> {
                showCreateAgentDialogOnEdt(availableBranches, localBranches, currentBranch);
            });
        });
    }
    
    private void showCreateAgentDialogOnEdt(
            List<String> availableBranches, 
            List<String> allBranches,
            String currentBranch) {
        
        JDialog dialog = new JDialog(chrome.getFrame(), "Create Agent", true);
        dialog.setLayout(new BorderLayout(10, 10));
        
        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        // Option: Use existing branch or create new
        JRadioButton useExistingRadio = new JRadioButton("Use existing branch");
        JRadioButton createNewRadio = new JRadioButton("Create new branch");
        ButtonGroup branchGroup = new ButtonGroup();
        branchGroup.add(useExistingRadio);
        branchGroup.add(createNewRadio);
        createNewRadio.setSelected(true);
        
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        mainPanel.add(createNewRadio, gbc);
        
        gbc.gridy = 1;
        mainPanel.add(useExistingRadio, gbc);
        
        // New branch name field
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        JLabel newBranchLabel = new JLabel("New branch name:");
        mainPanel.add(newBranchLabel, gbc);
        
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        JTextField newBranchField = new JTextField(20);
        newBranchField.setText("agent/" + UUID.randomUUID().toString().substring(0, 8));
        mainPanel.add(newBranchField, gbc);
        
        // Source branch for new branch
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.weightx = 0;
        JLabel sourceLabel = new JLabel("Source branch:");
        mainPanel.add(sourceLabel, gbc);
        
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        FuzzyComboBox<String> sourceCombo = new FuzzyComboBox<>(allBranches, s -> s);
        sourceCombo.setSelectedItem(currentBranch);
        mainPanel.add(sourceCombo, gbc);
        
        // Existing branch selector
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.weightx = 0;
        JLabel existingLabel = new JLabel("Existing branch:");
        mainPanel.add(existingLabel, gbc);
        
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        FuzzyComboBox<String> existingCombo = new FuzzyComboBox<>(availableBranches, s -> s);
        existingCombo.setEnabled(false);
        mainPanel.add(existingCombo, gbc);
        
        // Enable/disable based on radio selection
        createNewRadio.addActionListener(e -> {
            newBranchField.setEnabled(true);
            sourceCombo.setEnabled(true);
            existingCombo.setEnabled(false);
        });
        useExistingRadio.addActionListener(e -> {
            newBranchField.setEnabled(false);
            sourceCombo.setEnabled(false);
            existingCombo.setEnabled(true);
        });
        
        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        MaterialButton createButton = new MaterialButton("Create");
        SwingUtil.applyPrimaryButtonStyle(createButton);
        MaterialButton cancelButton = new MaterialButton("Cancel");
        
        createButton.addActionListener(e -> {
            String branchName;
            boolean createNew = createNewRadio.isSelected();
            String sourceBranch = null;
            
            if (createNew) {
                branchName = newBranchField.getText().trim();
                if (branchName.isEmpty()) {
                    chrome.toolError("Branch name cannot be empty", "Invalid Input");
                    return;
                }
                sourceBranch = (String) sourceCombo.getSelectedItem();
                if (sourceBranch == null) {
                    chrome.toolError("Please select a source branch", "Invalid Input");
                    return;
                }
            } else {
                branchName = (String) existingCombo.getSelectedItem();
                if (branchName == null) {
                    chrome.toolError("Please select a branch", "Invalid Input");
                    return;
                }
            }
            
            dialog.dispose();
            createAgent(branchName, createNew, sourceBranch);
        });
        
        cancelButton.addActionListener(e -> dialog.dispose());
        
        buttonPanel.add(createButton);
        buttonPanel.add(cancelButton);
        
        dialog.add(mainPanel, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(chrome.getFrame());
        dialog.setVisible(true);
    }
    
    private void createAgent(String branchName, boolean createNew, @Nullable String sourceBranch) {
        chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Creating agent for branch: " + branchName);
        
        contextManager.submitBackgroundTask("Creating agent", () -> {
            try {
                AgentInstance agent = agentManager.createAgent(branchName, createNew, sourceBranch);
                logger.info("Created agent: {}", agent);
            } catch (Exception e) {
                logger.error("Failed to create agent", e);
                SwingUtilities.invokeLater(() -> 
                    chrome.toolError("Failed to create agent: " + e.getMessage(), "Agent Creation Error"));
            }
        });
    }
    
    private void shutdownSelectedAgent() {
        if (selectedAgent == null) {
            return;
        }
        
        int result = JOptionPane.showConfirmDialog(
                chrome.getFrame(),
                "Are you sure you want to shutdown agent '" + selectedAgent.getName() + "'?\n" +
                "The worktree will be removed but the branch will be preserved.",
                "Shutdown Agent",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        
        if (result == JOptionPane.YES_OPTION) {
            AgentInstance agent = selectedAgent;
            contextManager.submitBackgroundTask("Shutting down agent", () -> {
                agentManager.shutdownAgent(agent);
            });
        }
    }
    
    private void cancelSelectedAgentJob() {
        if (selectedAgent != null && selectedAgent.getState() == AgentInstance.State.RUNNING) {
            agentManager.cancelJob(selectedAgent);
        }
    }
    
    // AgentManager.AgentListener implementation
    
    @Override
    public void onAgentAdded(AgentInstance agent) {
        SwingUtilities.invokeLater(() -> {
            agentTableModel.addRow(new Object[]{
                    agent.getName(),
                    agent.getBranchName(),
                    agent.getState()
            });
        });
    }
    
    @Override
    public void onAgentStateChanged(AgentInstance agent) {
        SwingUtilities.invokeLater(() -> {
            // Find and update the row
            for (int i = 0; i < agentTableModel.getRowCount(); i++) {
                if (agent.getName().equals(agentTableModel.getValueAt(i, 0))) {
                    agentTableModel.setValueAt(agent.getState(), i, 2);
                    break;
                }
            }
            
            // Update detail panel if this is the selected agent
            if (selectedAgent != null && selectedAgent.getId().equals(agent.getId())) {
                updateButtonStates();
                if (currentDetailPanel != null) {
                    currentDetailPanel.updateState();
                }
            }
        });
    }
    
    @Override
    public void onAgentRemoved(AgentInstance agent) {
        SwingUtilities.invokeLater(() -> {
            // Find and remove the row
            for (int i = 0; i < agentTableModel.getRowCount(); i++) {
                if (agent.getName().equals(agentTableModel.getValueAt(i, 0))) {
                    agentTableModel.removeRow(i);
                    break;
                }
            }
            
            // Clear detail panel if this was the selected agent
            if (selectedAgent != null && selectedAgent.getId().equals(agent.getId())) {
                selectedAgent = null;
                showEmptyDetailPanel();
                updateButtonStates();
            }
        });
    }
    
    @Override
    public void onAgentEvent(AgentInstance agent, JsonNode event) {
        // Forward to detail panel if it's showing this agent
        SwingUtilities.invokeLater(() -> {
            if (currentDetailPanel != null && selectedAgent != null 
                    && selectedAgent.getId().equals(agent.getId())) {
                currentDetailPanel.handleEvent(event);
            }
        });
    }
    
    /**
     * Get the agent manager for this tab.
     */
    public AgentManager getAgentManager() {
        return agentManager;
    }
    
    /**
     * Cleanup when the tab is being disposed.
     */
    public void dispose() {
        agentManager.removeListener(this);
        agentManager.shutdownAll();
    }
}
