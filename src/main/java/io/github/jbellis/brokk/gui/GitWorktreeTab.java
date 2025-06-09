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
import java.awt.Color;
import java.util.stream.Collectors;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.JCheckBox;
import javax.swing.JOptionPane;


public class GitWorktreeTab extends JPanel {
    private static final Logger logger = LogManager.getLogger(GitWorktreeTab.class);

    public enum MergeMode {
        MERGE_COMMIT("Merge commit"),
        SQUASH_COMMIT("Squash and merge"),
        REBASE_MERGE("Rebase and merge");

        private final String displayName;

        MergeMode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final GitPanel gitPanel;

    private JTable worktreeTable;
    private DefaultTableModel worktreeTableModel;
    private JButton addButton;
    private JButton removeButton;
    private JButton openButton; // Added
    private JButton refreshButton; // Added
    private JButton mergeButton; // Added for worktree merge functionality

    private final boolean isWorktreeWindow;

    public GitWorktreeTab(Chrome chrome, ContextManager contextManager, GitPanel gitPanel) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.gitPanel = gitPanel;

        var project = contextManager.getProject();
        this.isWorktreeWindow = project instanceof WorktreeProject;

        IGitRepo repo = project.getRepo();
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

        // Initialize field buttons (their properties like text, tooltip, listener)
        addButton = new JButton("+");
        addButton.setToolTipText("Add new worktree");
        addButton.addActionListener(e -> addWorktree());

        removeButton = new JButton("-");
        removeButton.setToolTipText("Remove selected worktree(s)");
        removeButton.setEnabled(false); // Initially disabled
        removeButton.addActionListener(e -> removeWorktree());

        openButton = new JButton("Open");
        openButton.setToolTipText("Open selected worktree(s)");
        openButton.setEnabled(false); // Initially disabled
        openButton.addActionListener(e -> {
            List<Path> pathsToOpen = getSelectedWorktreePaths();
            if (!pathsToOpen.isEmpty()) {
                handleOpenOrFocusWorktrees(pathsToOpen);
            }
        });

        refreshButton = new JButton("Refresh");
        refreshButton.setToolTipText("Refresh the list of worktrees");
        refreshButton.addActionListener(e -> refresh());

        // Add buttons common to both modes first
        buttonPanel.add(addButton);
        buttonPanel.add(removeButton);
        buttonPanel.add(openButton);

        if (isWorktreeWindow) { // WorktreeProject context
            // Disable + and - buttons instead of hiding
            addButton.setEnabled(false);
            removeButton.setEnabled(false);

            var project = contextManager.getProject();
            String wtName = ((WorktreeProject) project).getRoot().getFileName().toString();
            mergeButton = new JButton("Merge " + wtName + " into...");
            mergeButton.setToolTipText("Merge this worktree branch into another branch");
            mergeButton.setEnabled(true); // Merge button is enabled by default in worktree view
            mergeButton.addActionListener(e -> showMergeDialog());
            buttonPanel.add(mergeButton);
        }
        // else: In MainProject context, mergeButton is null and not added.

        buttonPanel.add(Box.createHorizontalGlue()); // Spacer to push refresh to the right
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

        if (openButton != null) {
            openButton.setEnabled(hasSelection);
        }

        if (isWorktreeWindow) {
            if (addButton != null) {
                addButton.setEnabled(false);
            }
            if (removeButton != null) {
                removeButton.setEnabled(false);
            }
            // mergeButton's state is not currently driven by selection in this method.
            // It is initialized as enabled.
        } else { // MainProject context
            // addButton in MainProject view is generally always enabled if worktrees are supported.
            if (removeButton != null) {
                removeButton.setEnabled(hasSelection);
            }
        }
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

            JCheckBox copyWorkspaceCheckbox = new JCheckBox("Copy Workspace to worktree Session");
            copyWorkspaceCheckbox.setSelected(false); // Default to false
            gbc.insets = new Insets(10, 2, 2, 2); // Add some space before the checkbox
            panel.add(copyWorkspaceCheckbox, gbc);

            int result = JOptionPane.showConfirmDialog(this, panel, "Add Worktree", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return;
            }

            String branchNameToUse;
            boolean isCreatingNewBranch = createNewBranchRadio.isSelected();
            boolean copyWorkspace = copyWorkspaceCheckbox.isSelected();

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

                    Brokk.OpenProjectBuilder openProjectBuilder = new Brokk.OpenProjectBuilder(newWorktreePath)
                            .parent(project);

                    if (copyWorkspace) {
                        logger.info("Copying current workspace to new worktree session for {}", newWorktreePath);
                        openProjectBuilder.sourceContextForSession(contextManager.topContext());
                    }

                    openProjectBuilder.open()
                            .thenAccept(success -> {
                                if (Boolean.TRUE.equals(success)) {
                                    chrome.systemOutput("Successfully opened worktree: " + newWorktreePath.getFileName());
                                } else {
                                    chrome.toolErrorRaw("Error opening worktree " + newWorktreePath.getFileName());
                                }
                                // Refresh worktree list regardless of open success, as the worktree itself was created.
                                SwingUtilities.invokeLater(this::loadWorktrees);
                            });

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

    private void showMergeDialog() {
        var project = contextManager.getProject();
        String worktreeBranchName = "";
        if (project instanceof WorktreeProject && project.getRepo() instanceof GitRepo gitRepo) {
            try {
                worktreeBranchName = gitRepo.getCurrentBranch();
            } catch (GitAPIException e) {
                logger.error("Could not get current branch for worktree", e);
                // Optionally inform the user or disable merge functionality if branch name is crucial
            }
        }

        JPanel dialogPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.weightx = 0;

        // Target Branch
        JLabel targetBranchLabel = new JLabel("Merge into branch:");
        dialogPanel.add(targetBranchLabel, gbc);

        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.weightx = 1.0;
        JComboBox<String> targetBranchComboBox = new JComboBox<>();
        dialogPanel.add(targetBranchComboBox, gbc);
        gbc.weightx = 0; // Reset for next components

        // Merge Strategy
        gbc.gridwidth = 1;
        JLabel mergeModeLabel = new JLabel("Merge strategy:");
        dialogPanel.add(mergeModeLabel, gbc);

        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.weightx = 1.0;
        JComboBox<MergeMode> mergeModeComboBox = new JComboBox<>(MergeMode.values());
        mergeModeComboBox.setSelectedItem(MergeMode.MERGE_COMMIT);
        dialogPanel.add(mergeModeComboBox, gbc);
        gbc.weightx = 0;

        // Populate targetBranchComboBox
        if (project instanceof WorktreeProject wp) {
            MainProject parentProject = wp.getParent();
            if (parentProject != null) {
                IGitRepo iParentRepo = parentProject.getRepo();
                if (iParentRepo instanceof GitRepo parentGitRepo) {
                    try {
                        List<String> localBranches = parentGitRepo.listLocalBranches();
                        localBranches.forEach(targetBranchComboBox::addItem);
                        String currentParentBranch = parentGitRepo.getCurrentBranch();
                        targetBranchComboBox.setSelectedItem(currentParentBranch);
                    } catch (GitAPIException e) {
                        logger.error("Failed to get parent project branches", e);
                        targetBranchComboBox.addItem("Error loading branches");
                        targetBranchComboBox.setEnabled(false);
                    }
                } else if (iParentRepo != null) {
                    logger.warn("Parent repository is not a GitRepo instance, cannot populate target branches for merge.");
                    targetBranchComboBox.addItem("Unsupported parent repo type");
                    targetBranchComboBox.setEnabled(false);
                } else {
                    targetBranchComboBox.addItem("Parent repo not available");
                    targetBranchComboBox.setEnabled(false);
                }
            } else {
                targetBranchComboBox.addItem("Parent project not found");
                targetBranchComboBox.setEnabled(false);
            }
        } else {
            // This case should ideally not happen if showMergeDialog is only called from worktree windows
            logger.warn("showMergeDialog called on a non-worktree project type: {}", project.getClass().getSimpleName());
            targetBranchComboBox.addItem("Not a worktree project");
        }


        // Checkboxes
        gbc.gridwidth = GridBagConstraints.REMAINDER; // Span full width for checkboxes and label
        JCheckBox removeWorktreeCb = new JCheckBox("Delete worktree after merge");
        removeWorktreeCb.setSelected(true);
        dialogPanel.add(removeWorktreeCb, gbc);

        final String finalWorktreeBranchName = worktreeBranchName;
        JCheckBox removeBranchCb = new JCheckBox("Delete branch '" + finalWorktreeBranchName + "' after merge");
        removeBranchCb.setSelected(true);
        dialogPanel.add(removeBranchCb, gbc);

        Runnable updateRemoveBranchCbState = () -> {
            if (removeWorktreeCb.isSelected()) {
                removeBranchCb.setEnabled(true);
            } else {
                removeBranchCb.setEnabled(false);
                removeBranchCb.setSelected(false); // Uncheck when disabled
            }
        };
        removeWorktreeCb.addActionListener(e -> updateRemoveBranchCbState.run());
        updateRemoveBranchCbState.run();

        // Conflict Status Label
        JLabel conflictStatusLabel = new JLabel(" "); // Start with a non-empty string for layout
        conflictStatusLabel.setForeground(UIManager.getColor("Label.foreground")); // Default color
        dialogPanel.add(conflictStatusLabel, gbc);

        // Add ActionListeners to combo boxes
        targetBranchComboBox.addActionListener(e -> checkConflictsAsync(targetBranchComboBox, mergeModeComboBox, conflictStatusLabel, finalWorktreeBranchName));
        mergeModeComboBox.addActionListener(e -> checkConflictsAsync(targetBranchComboBox, mergeModeComboBox, conflictStatusLabel, finalWorktreeBranchName));

        // Initial conflict check
        checkConflictsAsync(targetBranchComboBox, mergeModeComboBox, conflictStatusLabel, finalWorktreeBranchName);

        String dialogTitle = "Merge branch '" + finalWorktreeBranchName + "'";
        // For now, we are just logging OK button state changes.
        // If we needed to actually disable/enable the OK button of JOptionPane,
        // we would need to create the JOptionPane instance manually, get its buttons,
        // and manage their state. This is a more complex setup.
        int result = JOptionPane.showConfirmDialog(this, dialogPanel, dialogTitle, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String selectedTargetBranch = (String) targetBranchComboBox.getSelectedItem();
            MergeMode selectedMergeMode = (MergeMode) mergeModeComboBox.getSelectedItem();
            logger.info("Merge confirmed for worktree branch '{}' into target branch '{}' using mode '{}'. Remove worktree: {}, Remove branch: {}",
                    finalWorktreeBranchName,
                    selectedTargetBranch,
                    selectedMergeMode,
                    removeWorktreeCb.isSelected(),
                    removeBranchCb.isSelected());
            // Actual merge logic will go here in a future step
        }
    }

    private void checkConflictsAsync(JComboBox<String> targetBranchComboBox, JComboBox<MergeMode> mergeModeComboBox, JLabel conflictStatusLabel, String worktreeBranchName) {
        String selectedTargetBranch = (String) targetBranchComboBox.getSelectedItem();
        MergeMode selectedMergeMode = (MergeMode) mergeModeComboBox.getSelectedItem();

        if (selectedTargetBranch == null || selectedTargetBranch.equals("Error loading branches") || selectedTargetBranch.equals("Unsupported parent repo type") || selectedTargetBranch.equals("Parent repo not available") || selectedTargetBranch.equals("Parent project not found") || selectedTargetBranch.equals("Not a worktree project")) {
            conflictStatusLabel.setText("Please select a valid target branch.");
            conflictStatusLabel.setForeground(Color.RED);
            logger.info("Merge dialog: OK button would be disabled (invalid target branch).");
            return;
        }

        conflictStatusLabel.setText("Checking for conflicts with '" + selectedTargetBranch + "'...");
        conflictStatusLabel.setForeground(UIManager.getColor("Label.foreground")); // Default color

        // Variables for use in lambdas must be final or effectively final
        final String finalSelectedTargetBranch = selectedTargetBranch;
        final MergeMode finalSelectedMergeMode = selectedMergeMode;

        contextManager.submitBackgroundTask("Checking merge conflicts", () -> {
            String conflictResultString = null;
            try {
                Thread.sleep(1000); // Simulate delay

                if (finalSelectedTargetBranch.equals(worktreeBranchName)) {
                    conflictResultString = "Cannot merge a branch into itself.";
                } else if (finalSelectedMergeMode == MergeMode.REBASE_MERGE) {
                    // In a real scenario, REBASE_MERGE might have different conflict conditions
                    // or might need a different check (e.g., `git rebase --dry-run`).
                    // For now, this is a placeholder specific message.
                    // conflictResultString = "Rebase conflict check not fully implemented. Proceed with caution.";
                } else if (finalSelectedTargetBranch.contains("x")) { // Arbitrary condition for testing conflicts
                    conflictResultString = "Simulated conflict detected with '" + finalSelectedTargetBranch + "'!";
                }
                // If no conditions met, conflictResultString remains null (no conflict)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                conflictResultString = "Conflict check interrupted.";
            } catch (Exception e) {
                logger.error("Error during conflict check simulation", e);
                conflictResultString = "Error checking conflicts: " + e.getMessage();
            }

            final String finalConflictResultString = conflictResultString; // To use in inner lambda
            SwingUtilities.invokeLater(() -> {
                if (finalConflictResultString != null) {
                    conflictStatusLabel.setText(finalConflictResultString);
                    conflictStatusLabel.setForeground(Color.RED);
                    logger.info("Merge dialog: OK button would be disabled due to conflict: {}", finalConflictResultString);
                } else {
                    conflictStatusLabel.setText("No conflicts detected with '" + finalSelectedTargetBranch + "' for " + finalSelectedMergeMode.toString().toLowerCase() + ".");
                    conflictStatusLabel.setForeground(new Color(0, 128, 0)); // Green for no conflicts
                    logger.info("Merge dialog: OK button would be enabled.");
                }
            });
            return null; // Callable<String> can return null.
        });
    }
}
