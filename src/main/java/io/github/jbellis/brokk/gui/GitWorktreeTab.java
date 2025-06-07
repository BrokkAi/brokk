package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Brokk;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.WorktreeProject;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.IGitRepo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class GitWorktreeTab extends JPanel {
    private static final Logger logger = LogManager.getLogger(GitWorktreeTab.class);

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final GitPanel gitPanel;

    private JTable worktreeTable;
    private DefaultTableModel worktreeTableModel;
    private JButton addButton;
    private JButton removeButton;
    private JButton openButton; // Added
    private JButton refreshButton; // Added

    public GitWorktreeTab(Chrome chrome, ContextManager contextManager, GitPanel gitPanel) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.gitPanel = gitPanel;

        IGitRepo repo = contextManager.getProject().getRepo();
        if (repo.supportsWorktrees()) {
            buildWorktreeTabUI();
            loadWorktrees();
        } else {
            buildUnsupportedUI();
        }
    }

    private void buildUnsupportedUI() {
        removeAll(); // Clear any existing components
        setLayout(new GridBagLayout()); // Center the message
        JLabel unsupportedLabel = new JLabel("Git executable not found, worktrees are unavailable");
        unsupportedLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(unsupportedLabel, new GridBagConstraints());
        // Ensure buttons (if they were somehow initialized) are disabled
        if (addButton != null) addButton.setEnabled(false);
        if (removeButton != null) removeButton.setEnabled(false);
        if (openButton != null) openButton.setEnabled(false); // Ensure openButton is also handled
        if (refreshButton != null) refreshButton.setEnabled(false); // Disable refresh button
        revalidate();
        repaint();
    }

    private void buildWorktreeTabUI() {
        // Main panel for the table
        JPanel tablePanel = new JPanel(new BorderLayout());
        worktreeTableModel = new DefaultTableModel(new Object[]{"\u2713", "Path", "Branch", "Session"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 0) {
                    return Boolean.class;
                }
                return super.getColumnClass(columnIndex);
            }
        };
        worktreeTable = new JTable(worktreeTableModel) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            // The changeSelection override is good for single clicks,
            // but multi-select might still include row 0 if dragged over.
            // Filtering in helper methods (like getSelectedWorktreePaths) is key.
            @Override
            public void changeSelection(int rowIndex, int columnIndex, boolean toggle, boolean extend) {
                if (rowIndex == 0 && !extend && worktreeTable.getSelectedRowCount() <=1 ) { // if trying to select only row 0
                    if (worktreeTable.getSelectedRowCount() == 1 && worktreeTable.getSelectedRow() == 0) {
                        // if row 0 is already the only thing selected, do nothing to allow deselection by clicking elsewhere
                    } else {
                         worktreeTable.clearSelection(); // Clear selection if trying to select row 0 directly
                         return;
                    }
                }
                super.changeSelection(rowIndex, columnIndex, toggle, extend);
            }
        };
        worktreeTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION); // Changed to multi-select

        // Custom renderer to gray out the main repo row
        worktreeTable.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (row == 0) {
                    c.setForeground(Color.GRAY);
                    c.setEnabled(false); // Make renderer component itself appear disabled
                    // For selection, prevent row 0 from looking selected even if part of a multi-select
                    if (isSelected && table.isFocusOwner()) {
                        c.setBackground(table.getBackground()); // Keep background normal
                        c.setForeground(Color.GRAY); // Keep text gray
                    } else {
                         c.setBackground(table.getBackground());
                    }
                } else {
                    c.setForeground(table.getForeground());
                    c.setBackground(isSelected && table.isFocusOwner() ? table.getSelectionBackground() : table.getBackground());
                    c.setEnabled(true);
                }
                return c;
            }
        });

        // Configure the "Active" column (checkmark)
        var activeColumn = worktreeTable.getColumnModel().getColumn(0);
        activeColumn.setPreferredWidth(25);
        activeColumn.setMaxWidth(30);
        activeColumn.setMinWidth(20);
        activeColumn.setResizable(false);
        activeColumn.setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int col) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
                label.setHorizontalAlignment(SwingConstants.CENTER);

                if (Boolean.TRUE.equals(value)) {
                    label.setText("\u2713"); // Heavy Check Mark
                    label.setToolTipText("This is the currently active Brokk project window");
                } else {
                    label.setText("");
                    label.setToolTipText(null);
                }

                // Apply visual styling consistent with the main row renderer for row 0
                if (row == 0) {
                    label.setForeground(Color.GRAY);
                    // Match background handling of the default renderer for row 0 selection
                    if (isSelected && table.isFocusOwner() && table.isRowSelected(row)) {
                        label.setBackground(table.getBackground());
                    } else {
                        label.setBackground(table.getBackground());
                    }
                    label.setEnabled(false); 
                } else {
                    // For other rows, ensure foreground/background reflects selection state
                    // The super call usually handles this, but setText can sometimes reset it.
                    label.setForeground(isSelected && table.isFocusOwner() ? table.getSelectionForeground() : table.getForeground());
                    label.setBackground(isSelected && table.isFocusOwner() ? table.getSelectionBackground() : table.getBackground());
                    label.setEnabled(true);
                }
                return label;
            }
        });

        tablePanel.add(new JScrollPane(worktreeTable), BorderLayout.CENTER);

        add(tablePanel, BorderLayout.CENTER);

        // Button panel for actions
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));

        addButton = new JButton("+");
        addButton.setToolTipText("Add new worktree");
        addButton.addActionListener(e -> addWorktree());
        buttonPanel.add(addButton);

        openButton = new JButton("Open");
        openButton.setToolTipText("Open selected worktree(s)");
        openButton.setEnabled(false);
        openButton.addActionListener(e -> {
            List<Path> pathsToOpen = getSelectedWorktreePaths();
            if (!pathsToOpen.isEmpty()) {
                handleOpenOrFocusWorktrees(pathsToOpen);
            }
        });
        buttonPanel.add(openButton);

        removeButton = new JButton("-");
        removeButton.setToolTipText("Remove selected worktree(s)");
        removeButton.setEnabled(false); // Initially disabled
        removeButton.addActionListener(e -> removeWorktree());
        buttonPanel.add(removeButton);

        buttonPanel.add(Box.createHorizontalGlue()); // Pushes subsequent components to the right

        refreshButton = new JButton("Refresh");
        refreshButton.setToolTipText("Refresh the list of worktrees");
        refreshButton.addActionListener(e -> refresh());
        buttonPanel.add(refreshButton);

        add(buttonPanel, BorderLayout.SOUTH);

        worktreeTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateButtonStates();
            }
        });

        worktreeTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = worktreeTable.rowAtPoint(e.getPoint());
                    if (row > 0 && row < worktreeTableModel.getRowCount()) { // row > 0 to exclude main repo
                        // Path is now in column 1
                        String pathString = (String) worktreeTableModel.getValueAt(row, 1);
                        handleOpenOrFocusWorktrees(List.of(Path.of(pathString)));
                    }
                }
            }

            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                showPopup(e);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                showPopup(e);
            }

            private void showPopup(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = worktreeTable.rowAtPoint(e.getPoint());
                    if (row >= 0 && !worktreeTable.isRowSelected(row)) { // If right-click on unselected row
                        if (row == 0) worktreeTable.clearSelection(); // Don't select row 0 on right click
                        else worktreeTable.setRowSelectionInterval(row, row);
                    }

                    List<Path> selectedPaths = getSelectedWorktreePaths();
                    if (!selectedPaths.isEmpty()) {
                        JPopupMenu popupMenu = new JPopupMenu();
                        JMenuItem openItem = new JMenuItem("Open/Focus Worktree(s)");
                        openItem.addActionListener(ae -> handleOpenOrFocusWorktrees(selectedPaths));
                        popupMenu.add(openItem);

                        JMenuItem removeItem = new JMenuItem("Remove Worktree(s)");
                        removeItem.addActionListener(ae -> removeWorktree()); // Uses current selection
                        popupMenu.add(removeItem);

                        popupMenu.show(e.getComponent(), e.getX(), e.getY());
                    }
                }
            }
        });
    }

    private List<Path> getSelectedWorktreePaths() {
        List<Path> paths = new java.util.ArrayList<>();
        int[] selectedRows = worktreeTable.getSelectedRows();
        for (int row : selectedRows) {
            if (row == 0) continue; // Skip main repo
            if (row >= 0 && row < worktreeTableModel.getRowCount()) { // Check bounds
                // Path is now in column 1
                String pathString = (String) worktreeTableModel.getValueAt(row, 1);
                paths.add(Path.of(pathString));
            }
        }
        return paths;
    }

    private void updateButtonStates() {
        List<Path> selectedPaths = getSelectedWorktreePaths();
        boolean hasSelection = !selectedPaths.isEmpty();
        if (openButton != null) openButton.setEnabled(hasSelection);
        if (removeButton != null) removeButton.setEnabled(hasSelection);
        // addButton is always enabled if worktrees are supported
    }


    private void loadWorktrees() {
        contextManager.submitBackgroundTask("Loading worktrees", () -> {
            try {
                IGitRepo repo = contextManager.getProject().getRepo();
                if (repo instanceof GitRepo gitRepo) {
                    List<IGitRepo.WorktreeInfo> worktrees = gitRepo.listWorktrees();
                    // Normalize the current project's root path for reliable comparison
                    Path currentProjectRoot = contextManager.getProject().getRoot().toRealPath();

                    SwingUtilities.invokeLater(() -> {
                        worktreeTableModel.setRowCount(0); // Clear existing rows
                        for (IGitRepo.WorktreeInfo wt : worktrees) {
                            String sessionTitle = MainProject.getActiveSessionTitle(wt.path())
                                    .orElse("(no session)");
                            // wt.path() is already a real path from GitRepo.listWorktrees()
                            boolean isActive = currentProjectRoot.equals(wt.path());
                            worktreeTableModel.addRow(new Object[]{
                                    isActive, // For the "✓" column
                                    wt.path().toString(),
                                    wt.branch(),
                                    sessionTitle
                            });
                        }
                        updateButtonStates(); // Update after loading
                    });
                } else {
                     SwingUtilities.invokeLater(() -> {
                        worktreeTableModel.setRowCount(0);
                        addButton.setEnabled(false);
                        if (openButton != null) openButton.setEnabled(false);
                        removeButton.setEnabled(false);
                        updateButtonStates(); // Update after loading
                     });
                }
            } catch (Exception e) {
                logger.error("Error loading worktrees", e);
                SwingUtilities.invokeLater(() -> {
                    worktreeTableModel.setRowCount(0);
                    updateButtonStates(); // Update after loading
                    // Optionally, show an error message in the table or a dialog
                });
            }
            return null;
        });
    }

    private void handleOpenOrFocusWorktrees(List<Path> worktreePaths) {
        if (worktreePaths.isEmpty()) {
            return;
        }

        MainProject parentProject = (MainProject) contextManager.getProject();

        contextManager.submitUserTask("Opening/focusing worktree(s)", () -> {
            for (Path worktreePath : worktreePaths) {
                if (worktreePath.equals(parentProject.getRoot())) {
                    logger.debug("Attempted to open/focus main project from worktree tab, focusing current window.");
                    SwingUtilities.invokeLater(() -> {
                        if (chrome != null && chrome.getFrame() != null) {
                            chrome.getFrame().setState(Frame.NORMAL);
                            chrome.getFrame().toFront();
                            chrome.getFrame().requestFocus();
                        }
                    });
                    continue;
                }

                try {
                    if (Brokk.isProjectOpen(worktreePath)) {
                        logger.info("Worktree {} is already open, focusing window.", worktreePath);
                        Brokk.focusProjectWindow(worktreePath);
                    } else {
                        logger.info("Opening worktree {}...", worktreePath);
                        new Brokk.OpenProjectBuilder(worktreePath)
                                .parent(parentProject)
                                .open()
                                .thenAccept(success -> {
                                    if (Boolean.TRUE.equals(success)) {
                                        chrome.systemOutput("Successfully opened worktree: " + worktreePath.getFileName());
                                    } else {
                                        chrome.toolErrorRaw("Error opening worktree " + worktreePath.getFileName());
                                    }
                                });
                    }
                } catch (Exception e) {
                    logger.error("Error during open/focus for worktree {}: {}", worktreePath, e.getMessage(), e);
                    final String pathName = worktreePath.getFileName().toString();
                    SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this,
                            "Error processing worktree " + pathName + ": " + e.getMessage(),
                            "Worktree Error",
                            JOptionPane.ERROR_MESSAGE));
                }
            }
        });
    }


    private void addWorktree() {
        // Get Project and IGitRepo instances
        MainProject project = (MainProject) contextManager.getProject();
        IGitRepo repo = project.getRepo();

        // Verify that IGitRepo is an instance of GitRepo
        if (!(repo instanceof GitRepo gitRepo)) {
            JOptionPane.showMessageDialog(this,
                "Worktree operations are only supported for Git repositories.",
                "Error",
                JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            // Branch Selection Logic
            List<String> localBranches = gitRepo.listLocalBranches();
            Set<String> branchesInWorktrees = gitRepo.getBranchesInWorktrees();

            // Determine available branches
            List<String> availableBranches = localBranches.stream()
                .filter(branch -> !branchesInWorktrees.contains(branch))
                .toList();

            if (availableBranches.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                    "No available branches to create a worktree from.",
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            
            // UI for branch selection / creation
            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridwidth = GridBagConstraints.REMAINDER;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = new Insets(2, 2, 2, 2);

            JRadioButton useExistingBranchRadio = new JRadioButton("Use existing branch:", true);
            JComboBox<String> branchComboBox = new JComboBox<>(availableBranches.toArray(new String[0]));
            branchComboBox.setEnabled(true);

            JRadioButton createNewBranchRadio = new JRadioButton("Create new branch from current (" + gitRepo.getCurrentBranch() + "):");
            JTextField newBranchNameField = new JTextField(20);
            newBranchNameField.setEnabled(false);

            ButtonGroup group = new ButtonGroup();
            group.add(useExistingBranchRadio);
            group.add(createNewBranchRadio);

            useExistingBranchRadio.addActionListener(e -> {
                branchComboBox.setEnabled(true);
                newBranchNameField.setEnabled(false);
            });
            createNewBranchRadio.addActionListener(e -> {
                branchComboBox.setEnabled(false);
                newBranchNameField.setEnabled(true);
            });

            panel.add(useExistingBranchRadio, gbc);
            panel.add(branchComboBox, gbc);
            gbc.insets = new Insets(10, 2, 2, 2); // Add some space before the next radio
            panel.add(createNewBranchRadio, gbc);
            gbc.insets = new Insets(2, 2, 2, 2);
            panel.add(newBranchNameField, gbc);

            int result = JOptionPane.showConfirmDialog(this, panel, "Add Worktree", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return;
            }

            String branchNameToUse;
            boolean isCreatingNewBranch = createNewBranchRadio.isSelected();

            if (isCreatingNewBranch) {
                branchNameToUse = newBranchNameField.getText().trim();
                if (branchNameToUse.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "New branch name cannot be empty.", "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                // TODO: Add more validation for branch names (e.g., no spaces, special chars)
            } else {
                if (branchComboBox.getSelectedItem() == null) {
                    JOptionPane.showMessageDialog(this, "No branch selected.", "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                branchNameToUse = (String) branchComboBox.getSelectedItem();
            }

            contextManager.submitUserTask("Adding worktree for branch: " + branchNameToUse, () -> {
                try {
                    WorktreeSetupResult setupResult = setupNewGitWorktree(project, gitRepo, branchNameToUse, isCreatingNewBranch, null);
                    Path newWorktreePath = setupResult.worktreePath();
                    // String actualBranchNameUsed = setupResult.branchName(); // if needed for logging

                    // Create Project instance for the new worktree to manage its standard new session
                    // This is for the regular "Add Worktree" button flow.
                    // Architect flow will use createNewSessionFromWorkspaceAsync via Brokk.openProject.
                    var newWorktreeProject = new WorktreeProject(newWorktreePath, project);
                    String sessionName = "Worktree: " + branchNameToUse; // Default session name for new worktree
                    MainProject.SessionInfo newSession = newWorktreeProject.newSession(sessionName);
                    newWorktreeProject.setLastActiveSession(newSession.id());
                    newWorktreeProject.saveWorkspaceProperties(); // Save the session id
                    newWorktreeProject.close(); // Release resources, Brokk.openProject will create its own instance

                    new Brokk.OpenProjectBuilder(newWorktreePath)
                            .parent(project)
                            .open()
                            .thenAccept(success -> {
                                if (Boolean.TRUE.equals(success)) {
                                    chrome.systemOutput("Successfully opened worktree: " + newWorktreePath.getFileName());
                                } else {
                                    chrome.toolErrorRaw("Error opening worktree " + newWorktreePath.getFileName());
                                }
                            });

                    SwingUtilities.invokeLater(this::loadWorktrees);
                    chrome.systemOutput("Successfully created worktree for branch '" + branchNameToUse + "' at " + newWorktreePath);
                } catch (GitAPIException e) {
                    logger.error("Git error while adding worktree for branch: " + branchNameToUse, e);
                    SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this,
                            "Git error while adding worktree: " + e.getMessage(),
                            "Git Error",
                            JOptionPane.ERROR_MESSAGE));
                } catch (IOException e) {
                    logger.error("I/O error while adding worktree for branch: " + branchNameToUse, e);
                    SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this,
                            "I/O error while adding worktree: " + e.getMessage(),
                            "File Error",
                            JOptionPane.ERROR_MESSAGE));
                }
            });

        } catch (GitAPIException e) { // Catches exceptions from gitRepo.getCurrentBranch() or .listLocalBranches()
            logger.error("Error preparing for worktree addition", e);
            JOptionPane.showMessageDialog(this,
                "Error fetching branch information: " + e.getMessage(),
                "Git Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    private void removeWorktree() {
        List<Path> pathsToRemove = getSelectedWorktreePaths();
        if (pathsToRemove.isEmpty()) {
            return;
        }

        String pathListString = pathsToRemove.stream()
                                             .map(p -> p.getFileName().toString()) // More concise display
                                             .collect(Collectors.joining("\n"));

        int confirm = JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to remove the following worktree(s):\n" + pathListString +
                "\n\nThis will delete the files from disk and attempt to close their Brokk window(s) if open.",
                "Confirm Worktree Removal",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        MainProject project = (MainProject) contextManager.getProject();
        IGitRepo repo = project.getRepo();

        if (!(repo instanceof GitRepo)) { // Should not happen if buttons are correctly disabled by buildUnsupportedUI
            JOptionPane.showMessageDialog(this,
                "Worktree operations are only supported for Git repositories.",
                "Error",
                JOptionPane.ERROR_MESSAGE);
            return;
        }

        contextManager.submitUserTask("Removing worktree(s)", () -> {
            boolean anyFailed = false;
            for (Path worktreePath : pathsToRemove) {
                // This check is belt-and-suspenders as getSelectedWorktreePaths should filter row 0
                if (worktreePath.equals(project.getRoot())) {
                    logger.warn("Skipping removal of main project path listed as worktree: {}", worktreePath);
                    continue;
                }
                try {
                    // Pass force=true since we've already confirmed at a higher level for all selected items.
                    GitUiUtil.promptAndRemoveWorktree(contextManager, chrome, worktreePath, true, true);
                    chrome.systemOutput("Successfully removed worktree: " + worktreePath.getFileName());
                } catch (Exception e) {
                    logger.error("Error removing worktree {}: {}", worktreePath, e.getMessage(), e);
                    final String pathName = worktreePath.getFileName().toString();
                    SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this,
                            "Error removing worktree " + pathName + ": " + e.getMessage(),
                            "Worktree Removal Error",
                            JOptionPane.ERROR_MESSAGE));
                    anyFailed = true;
                }
            }
            SwingUtilities.invokeLater(this::loadWorktrees); // Refresh list after all attempts
            if (anyFailed) {
                chrome.systemOutput("Completed worktree removal with one or more errors.");
            } else if (!pathsToRemove.isEmpty()) { // Only log success if something was actually processed
                chrome.systemOutput("Successfully removed all selected worktrees.");
            }
        });
    }

    public void refresh() {
        IGitRepo repo = contextManager.getProject().getRepo();
        if (repo.supportsWorktrees()) {
            // If UI was previously the unsupported one, rebuild the proper UI
            if (worktreeTable == null) {
                removeAll();
                setLayout(new BorderLayout()); // Reset layout
                buildWorktreeTabUI();
            }
            loadWorktrees();
        } else {
            buildUnsupportedUI();
        }
    }

    public record WorktreeSetupResult(Path worktreePath, String branchName) {}

    /**
     * Sets up a new Git worktree.
     *
     * @param parentProject The main project.
     * @param gitRepo The GitRepo instance of the main project.
     * @param branchNameToUse The name of the branch for the new worktree.
     * @param isCreatingNewBranch True if a new branch should be created.
     * @param worktreeStorageDirOverride Optional override for worktree storage directory. If null, uses parentProject.getWorktreeStoragePath().
     * @return A {@link WorktreeSetupResult} containing the path to the newly created worktree and the branch name used.
     * @throws GitAPIException If a Git error occurs.
     * @throws IOException If an I/O error occurs.
     */
    public static WorktreeSetupResult setupNewGitWorktree(MainProject parentProject, GitRepo gitRepo, String branchNameToUse, boolean isCreatingNewBranch, @org.jetbrains.annotations.Nullable Path worktreeStorageDirOverride)
    throws GitAPIException, IOException
    {
        Path worktreeStorageDir = worktreeStorageDirOverride != null ? worktreeStorageDirOverride : parentProject.getWorktreeStoragePath();
        Files.createDirectories(worktreeStorageDir); // Ensure base storage directory exists

        Path newWorktreePath = gitRepo.getNextWorktreePath(worktreeStorageDir);

        if (isCreatingNewBranch) {
            String currentBranch = gitRepo.getCurrentBranch();
            logger.debug("Creating new branch '{}' from '{}' for worktree at {}", branchNameToUse, currentBranch, newWorktreePath);
            gitRepo.createBranch(branchNameToUse, currentBranch);
        }

        logger.debug("Adding worktree for branch '{}' at path {}", branchNameToUse, newWorktreePath);
        gitRepo.addWorktree(branchNameToUse, newWorktreePath);

        return new WorktreeSetupResult(newWorktreePath, branchNameToUse);
    }
}
