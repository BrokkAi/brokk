package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Brokk;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.WorktreeProject;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.IGitRepo;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

public class GitWorktreeTab extends JPanel {
    private static final Logger logger = LogManager.getLogger(GitWorktreeTab.class);

    private final Chrome chrome;
    private final ContextManager contextManager;
    // private final GitPanel gitPanel; // Field is not read

    private JTable worktreeTable = new JTable();
    private DefaultTableModel worktreeTableModel = new DefaultTableModel();
    private JButton addButton = new JButton();
    private JButton removeButton = new JButton();
    private JButton openButton = new JButton(); // Added
    private JButton refreshButton = new JButton(); // Added

    @org.jetbrains.annotations.Nullable
    private JButton mergeButton = null; // Added for worktree merge functionality

    private final boolean isWorktreeWindow;

    public GitWorktreeTab(
            Chrome chrome,
            ContextManager contextManager,
            GitPanel gitPanel) { // gitPanel param kept for constructor signature compatibility
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        // this.gitPanel = gitPanel; // Field is not read

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
        addButton.setEnabled(false);
        removeButton.setEnabled(false);
        openButton.setEnabled(false); // Ensure openButton is also handled
        refreshButton.setEnabled(false); // Disable refresh button
        revalidate();
        repaint();
    }

    private void buildWorktreeTabUI() {
        // Main panel for the table
        JPanel tablePanel = new JPanel(new BorderLayout());
        worktreeTableModel = new DefaultTableModel(new Object[] {"\u2713", "Path", "Branch", "Session"}, 0) {
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
                if (rowIndex == 0
                        && !extend
                        && worktreeTable.getSelectedRowCount() <= 1) { // if trying to select only row 0
                    if (worktreeTable.getSelectedRowCount() == 1 && worktreeTable.getSelectedRow() == 0) {
                        // if row 0 is already the only thing selected, do nothing to allow deselection by
                        // clicking elsewhere
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
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (row == 0) {
                    c.setForeground(Color.GRAY);
                    c.setEnabled(false); // Make renderer component itself appear disabled
                    // For selection, prevent row 0 from looking selected even if part of a multi-select
                    // This handles the case where row 0 is part of a multi-selection drag.
                    // It should not appear selected.
                    c.setBackground(table.getBackground()); // Always keep background normal for row 0
                    if (isSelected && table.isFocusOwner()) {
                        c.setForeground(Color.GRAY); // Keep text gray if "selected"
                    }
                } else {
                    c.setForeground(table.getForeground());
                    c.setBackground(
                            isSelected && table.isFocusOwner()
                                    ? table.getSelectionBackground()
                                    : table.getBackground());
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
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int col) {
                JLabel label =
                        (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
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
                    label.setBackground(table.getBackground()); // Always keep background normal for row 0
                    label.setEnabled(false);
                } else {
                    // For other rows, ensure foreground/background reflects selection state
                    // The super call usually handles this, but setText can sometimes reset it.
                    label.setForeground(
                            isSelected && table.isFocusOwner()
                                    ? table.getSelectionForeground()
                                    : table.getForeground());
                    label.setBackground(
                            isSelected && table.isFocusOwner()
                                    ? table.getSelectionBackground()
                                    : table.getBackground());
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
            // mergeButton is added after the glue
        }
        // else: In MainProject context, mergeButton is null and not added.

        buttonPanel.add(Box.createHorizontalGlue()); // Spacer to push refresh to the right

        if (isWorktreeWindow) {
            buttonPanel.add(mergeButton); // Add merge button after glue for right alignment
        }
        buttonPanel.add(refreshButton); // Refresh button always last

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

        openButton.setEnabled(hasSelection);

        if (isWorktreeWindow) {
            addButton.setEnabled(false);
            removeButton.setEnabled(false);
            // mergeButton's state is not currently driven by selection in this method.
            // It is initialized as enabled.
        } else { // MainProject context
            // addButton in MainProject view is generally always enabled if worktrees are supported.
            removeButton.setEnabled(hasSelection);
        }
    }

    private void loadWorktrees() {
        contextManager.submitBackgroundTask("Loading worktrees", () -> {
            try {
                IGitRepo repo = contextManager.getProject().getRepo();
                if (repo instanceof GitRepo gitRepo) {
                    var result = gitRepo.listWorktreesAndInvalid();
                    var worktrees = result.worktrees();
                    var invalidPaths = result.invalidPaths();

                    if (!invalidPaths.isEmpty()) {
                        final var dialogFuture = new java.util.concurrent.CompletableFuture<Integer>();
                        SwingUtilities.invokeLater(() -> {
                            String pathList =
                                    invalidPaths.stream().map(Path::toString).collect(Collectors.joining("\n- "));
                            String message = "The following worktree paths no longer exist on disk:\n\n- "
                                    + pathList
                                    + "\n\nWould you like to clean up this stale metadata? (git worktree prune)";
                            int choice = chrome.showConfirmDialog(
                                    message,
                                    "Prune Stale Worktrees?",
                                    JOptionPane.YES_NO_OPTION,
                                    JOptionPane.QUESTION_MESSAGE);
                            dialogFuture.complete(choice);
                        });

                        int choice = dialogFuture.get();
                        if (choice == JOptionPane.YES_OPTION) {
                            contextManager.submitBackgroundTask("Pruning stale worktrees", () -> {
                                try {
                                    gitRepo.pruneWorktrees();
                                    chrome.systemOutput("Successfully pruned stale worktrees.");
                                    SwingUtilities.invokeLater(this::loadWorktrees); // Reload after prune
                                } catch (Exception e) {
                                    logger.error("Failed to prune stale worktrees", e);
                                    chrome.toolError("Failed to prune stale worktrees: " + e.getMessage());
                                }
                                return null;
                            });
                            return null; // The prune task will trigger a reload, so we exit this one.
                        }
                    }

                    // Normalize the current project's root path for reliable comparison
                    Path currentProjectRoot =
                            contextManager.getProject().getRoot().toRealPath();

                    SwingUtilities.invokeLater(() -> {
                        worktreeTableModel.setRowCount(0); // Clear existing rows
                        for (IGitRepo.WorktreeInfo wt : worktrees) {
                            String sessionTitle =
                                    MainProject.getActiveSessionTitle(wt.path()).orElse("(no session)");
                            // wt.path() is already a real path from GitRepo.listWorktreesAndInvalid()
                            boolean isActive = currentProjectRoot.equals(wt.path());
                            worktreeTableModel.addRow(new Object[] {
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
                        openButton.setEnabled(false);
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

        MainProject parentProject = (MainProject) contextManager.getProject().getParent();

        contextManager.submitContextTask("Opening/focusing worktree(s)", () -> {
            for (Path worktreePath : worktreePaths) {
                if (worktreePath.equals(parentProject.getRoot())) {
                    logger.debug("Attempted to open/focus main project from worktree tab, focusing current window.");
                    SwingUtilities.invokeLater(() -> {
                        chrome.getFrame();
                        chrome.getFrame().setState(Frame.NORMAL);
                        chrome.getFrame().toFront();
                        chrome.getFrame().requestFocus();
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
                                    if (Boolean.FALSE.equals(success)) {
                                        chrome.toolError(
                                                "Unable to open worktree " + worktreePath.getFileName(),
                                                "Error opening worktree");
                                    }
                                });
                    }
                } catch (Exception e) {
                    logger.error("Error during open/focus for worktree {}: {}", worktreePath, e.getMessage(), e);
                    final String pathName = worktreePath.getFileName().toString();
                    chrome.toolError(
                            "Error opening worktree " + pathName + ":\n" + e.getMessage(), "Worktree Open Error");
                }
            }
        });
    }

    private record AddWorktreeDialogResult(
            String selectedBranch, // For "use existing" or "new branch name" (raw)
            String sourceBranchForNew,
            boolean isCreatingNewBranch,
            boolean copyWorkspace,
            boolean okPressed) {}

    private void addWorktree() {
        MainProject project = (MainProject) contextManager.getProject();
        IGitRepo repo = project.getRepo(); // This repo instance is effectively final for the lambda

        contextManager.submitContextTask("Preparing to add worktree...", () -> {
            if (!(repo instanceof GitRepo gitRepo)) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        this,
                        "Worktree operations are only supported for Git repositories.",
                        "Error",
                        JOptionPane.ERROR_MESSAGE));
                return;
            }

            List<String> localBranches;
            Set<String> branchesInWorktrees;
            String currentGitBranch;
            List<String> availableBranches;

            try {
                localBranches = gitRepo.listLocalBranches();
                branchesInWorktrees = gitRepo.getBranchesInWorktrees();
                currentGitBranch = gitRepo.getCurrentBranch();

                availableBranches = localBranches.stream()
                        .filter(branch -> !branchesInWorktrees.contains(branch))
                        .toList();

                if (availableBranches.isEmpty()) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                            this,
                            "No available branches to create a worktree from.",
                            "Info",
                            JOptionPane.INFORMATION_MESSAGE));
                    return;
                }
            } catch (GitAPIException e) {
                logger.error("Error fetching initial branch information for add worktree", e);
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        this,
                        "Error fetching branch information: " + e.getMessage(),
                        "Git Error",
                        JOptionPane.ERROR_MESSAGE));
                return;
            }

            final java.util.concurrent.CompletableFuture<AddWorktreeDialogResult> dialogFuture =
                    new java.util.concurrent.CompletableFuture<>();
            final List<String> finalAvailableBranches = availableBranches; // Effectively final for EDT lambda
            final List<String> finalLocalBranches = localBranches; // Effectively final for EDT lambda
            final String finalCurrentGitBranch = currentGitBranch; // Effectively final for EDT lambda

            SwingUtilities.invokeLater(() -> {
                JPanel panel = new JPanel(new GridBagLayout());
                GridBagConstraints gbc = new GridBagConstraints();
                gbc.insets = new Insets(5, 5, 5, 5);

                JRadioButton createNewBranchRadio = new JRadioButton("Create new branch:", true);
                JTextField newBranchNameField = new JTextField(15);
                newBranchNameField.setEnabled(true);

                JComboBox<String> sourceBranchForNewComboBox =
                        new JComboBox<>(finalLocalBranches.toArray(new String[0]));
                sourceBranchForNewComboBox.setSelectedItem(finalCurrentGitBranch);
                sourceBranchForNewComboBox.setEnabled(true);

                JRadioButton useExistingBranchRadio = new JRadioButton("Use existing branch:");
                JComboBox<String> branchComboBox = new JComboBox<>(finalAvailableBranches.toArray(new String[0]));
                branchComboBox.setEnabled(false);

                ButtonGroup group = new ButtonGroup();
                group.add(createNewBranchRadio);
                group.add(useExistingBranchRadio);

                createNewBranchRadio.addActionListener(eL -> {
                    newBranchNameField.setEnabled(true);
                    sourceBranchForNewComboBox.setEnabled(true);
                    branchComboBox.setEnabled(false);
                });
                useExistingBranchRadio.addActionListener(eL -> {
                    newBranchNameField.setEnabled(false);
                    sourceBranchForNewComboBox.setEnabled(false);
                    branchComboBox.setEnabled(true);
                });

                gbc.gridx = 0;
                gbc.gridy = 0;
                gbc.gridwidth = 2;
                gbc.anchor = GridBagConstraints.WEST;
                gbc.fill = GridBagConstraints.HORIZONTAL;
                panel.add(createNewBranchRadio, gbc);
                gbc.gridx = 0;
                gbc.gridy = 1;
                gbc.gridwidth = 1;
                gbc.anchor = GridBagConstraints.EAST;
                gbc.fill = GridBagConstraints.NONE;
                gbc.insets = new Insets(2, 25, 2, 5);
                panel.add(new JLabel("Name:"), gbc);
                gbc.gridx = 1;
                gbc.gridy = 1;
                gbc.anchor = GridBagConstraints.WEST;
                gbc.fill = GridBagConstraints.HORIZONTAL;
                gbc.weightx = 1.0;
                gbc.insets = new Insets(2, 0, 2, 5);
                panel.add(newBranchNameField, gbc);
                gbc.weightx = 0.0;
                gbc.gridx = 0;
                gbc.gridy = 2;
                gbc.anchor = GridBagConstraints.EAST;
                gbc.fill = GridBagConstraints.NONE;
                gbc.insets = new Insets(2, 25, 5, 5);
                panel.add(new JLabel("From:"), gbc);
                gbc.gridx = 1;
                gbc.gridy = 2;
                gbc.anchor = GridBagConstraints.WEST;
                gbc.fill = GridBagConstraints.HORIZONTAL;
                gbc.weightx = 1.0;
                gbc.insets = new Insets(2, 0, 5, 5);
                panel.add(sourceBranchForNewComboBox, gbc);
                gbc.weightx = 0.0;
                gbc.insets = new Insets(5, 5, 5, 5);
                gbc.gridx = 0;
                gbc.gridy = 3;
                gbc.gridwidth = 2;
                gbc.anchor = GridBagConstraints.WEST;
                gbc.fill = GridBagConstraints.HORIZONTAL;
                gbc.insets = new Insets(10, 5, 5, 5);
                panel.add(useExistingBranchRadio, gbc);
                gbc.gridx = 0;
                gbc.gridy = 4;
                gbc.insets = new Insets(2, 25, 5, 5);
                gbc.weightx = 1.0;
                panel.add(branchComboBox, gbc);
                gbc.weightx = 0.0;
                gbc.insets = new Insets(5, 5, 5, 5);

                JCheckBox copyWorkspaceCheckbox = new JCheckBox("Copy Workspace to worktree Session");
                copyWorkspaceCheckbox.setSelected(false);
                gbc.gridx = 0;
                gbc.gridy = 5;
                gbc.gridwidth = 2;
                gbc.anchor = GridBagConstraints.WEST;
                gbc.fill = GridBagConstraints.HORIZONTAL;
                gbc.insets = new Insets(10, 5, 5, 5);
                panel.add(copyWorkspaceCheckbox, gbc);

                JOptionPane optionPane =
                        new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
                JDialog dialog = optionPane.createDialog(GitWorktreeTab.this, "Add Worktree");
                JButton okButton = new JButton(UIManager.getString("OptionPane.okButtonText"));
                okButton.addActionListener(e -> {
                    optionPane.setValue(JOptionPane.OK_OPTION);
                    dialog.dispose();
                });
                optionPane.setOptions(new Object[] {
                    okButton,
                    new JButton(UIManager.getString("OptionPane.cancelButtonText")) {
                        {
                            addActionListener(e -> {
                                optionPane.setValue(JOptionPane.CANCEL_OPTION);
                                dialog.dispose();
                            });
                        }
                    }
                });
                dialog.getRootPane().setDefaultButton(okButton);
                newBranchNameField.requestFocusInWindow(); // Focus the new branch name field
                dialog.setVisible(true);
                Object selectedValue = optionPane.getValue();
                dialog.dispose();
                if (selectedValue != null && selectedValue.equals(JOptionPane.OK_OPTION)) {
                    String selectedBranchName;
                    if (createNewBranchRadio.isSelected()) {
                        selectedBranchName =
                                newBranchNameField.getText().trim(); // Raw name, will be sanitized on background thread
                    } else {
                        selectedBranchName = (String) branchComboBox.getSelectedItem();
                    }
                    dialogFuture.complete(new AddWorktreeDialogResult(
                            selectedBranchName,
                            (String) sourceBranchForNewComboBox.getSelectedItem(),
                            createNewBranchRadio.isSelected(),
                            copyWorkspaceCheckbox.isSelected(),
                            true));
                } else {
                    dialogFuture.complete(new AddWorktreeDialogResult("", "", false, false, false));
                }
            });

            try {
                AddWorktreeDialogResult dialogResult = dialogFuture.get(); // Wait for dialog on background thread
                if (!dialogResult.okPressed()) {
                    chrome.systemOutput("Add worktree cancelled by user.");
                    return;
                }

                String branchForWorktree = dialogResult.selectedBranch();
                String sourceBranchForNew = dialogResult.sourceBranchForNew();
                boolean isCreatingNewBranch = dialogResult.isCreatingNewBranch();
                boolean copyWorkspace = dialogResult.copyWorkspace();

                if (isCreatingNewBranch) {
                    if (branchForWorktree.isEmpty()) {
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                                GitWorktreeTab.this,
                                "New branch name cannot be empty.",
                                "Error",
                                JOptionPane.ERROR_MESSAGE));
                        return;
                    }
                    try {
                        branchForWorktree =
                                gitRepo.sanitizeBranchName(branchForWorktree); // Sanitize on background thread
                    } catch (GitAPIException e) {
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                                GitWorktreeTab.this,
                                "Error sanitizing branch name: " + e.getMessage(),
                                "Error",
                                JOptionPane.ERROR_MESSAGE));
                        return;
                    }
                    if (sourceBranchForNew.isEmpty()) {
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                                GitWorktreeTab.this,
                                "A source branch must be selected to create a new branch.",
                                "Error",
                                JOptionPane.ERROR_MESSAGE));
                        return;
                    }
                } else { // Using existing branch
                }

                chrome.systemOutput("Adding worktree for branch: " + branchForWorktree);

                WorktreeSetupResult setupResult = setupNewGitWorktree(
                        project, gitRepo, branchForWorktree, isCreatingNewBranch, sourceBranchForNew);
                Path newWorktreePath = setupResult.worktreePath();

                Brokk.OpenProjectBuilder openProjectBuilder =
                        new Brokk.OpenProjectBuilder(newWorktreePath).parent(project);
                if (copyWorkspace) {
                    logger.info("Copying current workspace to new worktree session for {}", newWorktreePath);
                    openProjectBuilder.sourceContextForSession(contextManager.topContext());
                }

                final String finalBranchForWorktree = branchForWorktree; // for lambda
                openProjectBuilder.open().thenAccept(success -> {
                    if (Boolean.TRUE.equals(success)) {
                        chrome.systemOutput("Successfully opened worktree: " + newWorktreePath.getFileName());
                    } else {
                        chrome.toolError("Error opening worktree " + newWorktreePath.getFileName());
                    }
                    SwingUtilities.invokeLater(this::loadWorktrees);
                });
                chrome.systemOutput("Successfully created worktree for branch '"
                        + finalBranchForWorktree
                        + "' at "
                        + newWorktreePath);

            } catch (InterruptedException | ExecutionException e) {
                logger.error("Error during add worktree dialog processing or future execution", e);
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        GitWorktreeTab.this,
                        "Error processing worktree addition: " + e.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE));
            } catch (GitAPIException | IOException e) { // Catches from setupNewGitWorktree or sanitizeBranchName
                logger.error("Error creating worktree", e);
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        GitWorktreeTab.this,
                        "Error creating worktree: " + e.getMessage(),
                        "Git Error",
                        JOptionPane.ERROR_MESSAGE));
            }
        });
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
                "Are you sure you want to remove the following worktree(s):\n"
                        + pathListString
                        + "\n\nThis will delete the files from disk and attempt to close their Brokk window(s) if open.",
                "Confirm Worktree Removal",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        MainProject project = (MainProject) contextManager.getProject();
        IGitRepo repo = project.getRepo();

        if (!(repo instanceof GitRepo)) { // Should not happen if buttons are correctly disabled by buildUnsupportedUI
            JOptionPane.showMessageDialog(
                    this,
                    "Worktree operations are only supported for Git repositories.",
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        contextManager.submitContextTask("Removing worktree(s)", () -> {
            boolean anyFailed = false;
            boolean forceAll = false;
            for (Path worktreePath : pathsToRemove) {
                if (worktreePath.equals(project.getRoot())) {
                    logger.warn("Skipping removal of main project path listed as worktree: {}", worktreePath);
                    continue;
                }
                try {
                    logger.debug("Attempting non-forced removal of worktree {}", worktreePath);
                    attemptRemoveWorktree(repo, worktreePath, false);
                } catch (GitRepo.WorktreeNeedsForceException ne) {
                    logger.warn("Worktree {} removal needs force: {}", worktreePath, ne.getMessage());

                    if (forceAll) {
                        try {
                            logger.debug("ForceAll active; attempting forced removal of worktree {}", worktreePath);
                            attemptRemoveWorktree(repo, worktreePath, true);
                        } catch (
                                GitRepo.GitRepoException
                                        forceEx) { // WorktreeNeedsForceException is a subclass and would be caught
                            // here
                            logger.error(
                                    "Error during forced removal of worktree {}: {}",
                                    worktreePath,
                                    forceEx.getMessage(),
                                    forceEx);
                            reportRemoveError(worktreePath, forceEx);
                            anyFailed = true;
                        }
                        continue;
                    }

                    final java.util.concurrent.CompletableFuture<Integer> dialogResultFuture =
                            new java.util.concurrent.CompletableFuture<>();
                    SwingUtilities.invokeLater(() -> {
                        Object[] options = {"Yes", "Yes to All", "No"};
                        int result = JOptionPane.showOptionDialog(
                                GitWorktreeTab.this,
                                "Removing worktree '"
                                        + worktreePath.getFileName()
                                        + "' requires force.\n"
                                        + ne.getMessage()
                                        + "\n"
                                        + "Do you want to force delete it?",
                                "Force Worktree Removal",
                                JOptionPane.DEFAULT_OPTION,
                                JOptionPane.WARNING_MESSAGE,
                                null,
                                options,
                                options[0]);
                        dialogResultFuture.complete(result);
                    });

                    try {
                        int forceConfirm = dialogResultFuture.get(); // Block background thread for dialog result
                        if (forceConfirm == 0 || forceConfirm == 1) { // Yes or Yes to All
                            if (forceConfirm == 1) {
                                forceAll = true;
                            }
                            try {
                                logger.debug("Attempting forced removal of worktree {}", worktreePath);
                                attemptRemoveWorktree(repo, worktreePath, true);
                            } catch (
                                    GitRepo.GitRepoException
                                            forceEx) { // WorktreeNeedsForceException is a subclass and would be
                                // caught here
                                logger.error(
                                        "Error during forced removal of worktree {}: {}",
                                        worktreePath,
                                        forceEx.getMessage(),
                                        forceEx);
                                reportRemoveError(worktreePath, forceEx);
                                anyFailed = true;
                            }
                        } else {
                            chrome.systemOutput(
                                    "Force removal of worktree " + worktreePath.getFileName() + " cancelled by user.");
                        }
                    } catch (InterruptedException ie) {
                        throw new RuntimeException(ie);
                    } catch (ExecutionException ee) {
                        logger.error(
                                "Error obtaining dialog result for force removal of worktree {}: {}",
                                worktreePath,
                                ee.getMessage(),
                                ee);
                        reportRemoveError(
                                worktreePath, new Exception("Failed to get dialog result for force removal.", ee));
                        anyFailed = true;
                    }
                } catch (GitRepo.GitRepoException ge) {
                    logger.error(
                            "GitRepoException during (non-forced) removal of worktree {}: {}",
                            worktreePath,
                            ge.getMessage(),
                            ge);
                    reportRemoveError(worktreePath, ge);
                    anyFailed = true;
                }
            }

            final boolean finalAnyFailed = anyFailed; // Effectively final for lambda
            SwingUtilities.invokeLater(() -> {
                loadWorktrees(); // Refresh list after all attempts
                if (finalAnyFailed) {
                    chrome.systemOutput("Completed worktree removal with one or more errors.");
                } else if (!pathsToRemove.isEmpty()) {
                    chrome.systemOutput("Successfully removed all selected worktrees.");
                }
            });
        });
    }

    private void attemptRemoveWorktree(IGitRepo repo, Path worktreePath, boolean force)
            throws GitRepo.WorktreeNeedsForceException, GitRepo.GitRepoException {
        try {
            repo.removeWorktree(worktreePath, force);

            chrome.systemOutput("Successfully " + (force ? "force " : "") + "removed worktree at " + worktreePath);

            SwingUtilities.invokeLater(() -> {
                var windowToClose = Brokk.findOpenProjectWindow(worktreePath);
                if (windowToClose != null) {
                    windowToClose
                            .getFrame()
                            .dispatchEvent(new WindowEvent(windowToClose.getFrame(), WindowEvent.WINDOW_CLOSING));
                }
            });
        } catch (GitRepo.WorktreeNeedsForceException wnf) {
            if (!force) {
                throw wnf; // Propagate if not forcing, caller will handle UI
            } else {
                // If 'force' was true and we still get this, it's an unexpected error.
                throw new GitRepo.GitRepoException(
                        "Worktree removal for "
                                + worktreePath.getFileName()
                                + " reported 'needs force' even when force was active.",
                        wnf);
            }
        } catch (GitAPIException gae) { // Includes other JGit/GitAPI specific exceptions
            throw new GitRepo.GitRepoException(
                    "Git API error during "
                            + (force ? "forced " : "")
                            + "removal of worktree "
                            + worktreePath.getFileName()
                            + ": "
                            + gae.getMessage(),
                    gae);
        } catch (RuntimeException re) { // Catches GitWrappedIOException or other runtime issues
            throw new GitRepo.GitRepoException(
                    "Runtime error during "
                            + (force ? "forced " : "")
                            + "removal of worktree "
                            + worktreePath.getFileName()
                            + ": "
                            + re.getMessage(),
                    re);
        }
    }

    private void reportRemoveError(Path worktreePath, Exception e) {
        final String pathName = worktreePath.getFileName().toString();
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                this,
                "Error during removal of worktree " + pathName + ":\n" + e.getMessage(),
                "Worktree Removal Error",
                JOptionPane.ERROR_MESSAGE));
    }

    public void refresh() {
        IGitRepo repo = contextManager.getProject().getRepo();
        if (repo.supportsWorktrees()) {
            // If UI was previously the unsupported one, rebuild the proper UI
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
     * @param branchForWorktree The name of the branch the new worktree will be on. If creating a new branch, this is
     *     its name.
     * @param isCreatingNewBranch True if a new branch should be created.
     * @param sourceBranchForNew The branch to create from, if {@code isCreatingNewBranch} is true. Otherwise null.
     * @return A {@link WorktreeSetupResult} containing the path to the newly created worktree and the branch name used.
     * @throws GitAPIException If a Git error occurs.
     * @throws IOException If an I/O error occurs.
     */
    public static WorktreeSetupResult setupNewGitWorktree(
            MainProject parentProject,
            GitRepo gitRepo,
            String branchForWorktree,
            boolean isCreatingNewBranch,
            String sourceBranchForNew)
            throws GitAPIException, IOException {
        Path worktreeStorageDir = parentProject.getWorktreeStoragePath();
        Files.createDirectories(worktreeStorageDir); // Ensure base storage directory exists

        Path newWorktreePath = gitRepo.getNextWorktreePath(worktreeStorageDir);

        if (isCreatingNewBranch) {
            logger.debug(
                    "Creating new branch '{}' from '{}' for worktree at {}",
                    branchForWorktree,
                    sourceBranchForNew,
                    newWorktreePath);
            gitRepo.createBranch(branchForWorktree, sourceBranchForNew);
        }

        logger.debug("Adding worktree for branch '{}' at path {}", branchForWorktree, newWorktreePath);
        gitRepo.addWorktree(branchForWorktree, newWorktreePath);

        // Copy (prefer hard-link) existing language CPG caches to the new worktree
        var enabledLanguages = parentProject.getAnalyzerLanguages();
        for (var lang : enabledLanguages) {
            if (!lang.isCpg()) {
                continue;
            }
            var srcCpg = lang.getCpgPath(parentProject);
            if (!Files.exists(srcCpg)) {
                continue;
            }
            try {
                var relative = parentProject.getRoot().relativize(srcCpg);
                var destCpg = newWorktreePath.resolve(relative);
                Files.createDirectories(destCpg.getParent());
                try {
                    Files.createLink(destCpg, srcCpg); // Try hard-link first
                    logger.debug("Hard-linked CPG cache from {} to {}", srcCpg, destCpg);
                } catch (UnsupportedOperationException | IOException linkEx) {
                    Files.copy(srcCpg, destCpg, StandardCopyOption.REPLACE_EXISTING);
                    logger.debug("Copied CPG cache from {} to {}", srcCpg, destCpg);
                }
            } catch (IOException copyEx) {
                logger.warn(
                        "Failed to replicate CPG cache for language {}: {}", lang.name(), copyEx.getMessage(), copyEx);
            }
        }

        return new WorktreeSetupResult(newWorktreePath, branchForWorktree);
    }

    private void showMergeDialog() {
        var project = contextManager.getProject();
        if (!(project instanceof WorktreeProject worktreeProject)) {
            // This should not happen if the merge button is only available in worktree views.
            logger.warn("Merge dialog opened for a non-worktree project: {}", project.getRoot());
            return;
        }
        var parentProject = worktreeProject.getParent();

        String worktreeBranchName = "";
        if (project.getRepo() instanceof GitRepo gitRepo) {
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
        JComboBox<GitRepo.MergeMode> mergeModeComboBox = new JComboBox<>(GitRepo.MergeMode.values());
        var lastMergeMode = parentProject.getLastMergeMode().orElse(GitRepo.MergeMode.MERGE_COMMIT);
        mergeModeComboBox.setSelectedItem(lastMergeMode);
        mergeModeComboBox.addActionListener(
                MergeBranchDialogPanel.createMergeModePersistenceListener(mergeModeComboBox, parentProject));
        dialogPanel.add(mergeModeComboBox, gbc);
        gbc.weightx = 0;

        // Populate targetBranchComboBox
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
        } else {
            logger.warn("Parent repository is not a GitRepo instance, cannot populate target branches for merge.");
            targetBranchComboBox.addItem("Unsupported parent repo type");
            targetBranchComboBox.setEnabled(false);
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

        String dialogTitle = "Merge branch '" + finalWorktreeBranchName + "'";
        JOptionPane optionPane = new JOptionPane(dialogPanel, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        JDialog dialog = optionPane.createDialog(this, dialogTitle);

        JButton okButton = null;
        // Attempt to find the OK button
        // This relies on the internal structure of JOptionPane, which can vary by Look and Feel
        // A more robust solution might involve iterating deeper or custom button components.
        for (Component comp : optionPane.getComponents()) {
            if (comp instanceof JPanel buttonBar) { // JPanel often contains buttons
                for (Component btnComp : buttonBar.getComponents()) {
                    if (btnComp instanceof JButton jButton
                            && jButton.getText().equals(UIManager.getString("OptionPane.okButtonText"))) {
                        okButton = jButton;
                        break;
                    }
                }
                if (okButton != null) break;
            }
        }
        if (okButton == null) {
            // Fallback: Try a simpler structure if the above fails
            Component[] components = optionPane.getComponents();
            // Typically, buttons are at the end, in a JPanel, or directly.
            // This is a simplified search and might need adjustment based on L&F.
            for (int i = components.length - 1; i >= 0; i--) {
                if (components[i] instanceof JPanel panel) {
                    for (Component c : panel.getComponents()) {
                        if (c instanceof JButton jButton
                                && jButton.getText().equals(UIManager.getString("OptionPane.okButtonText"))) {
                            okButton = jButton;
                            break;
                        }
                    }
                } else if (components[i] instanceof JButton jButton
                        && jButton.getText().equals(UIManager.getString("OptionPane.okButtonText"))) {
                    okButton = jButton;
                }
                if (okButton != null) break;
            }
        }

        // Add ActionListeners to combo boxes
        final JButton finalOkButton = okButton; // effectively final for lambda
        targetBranchComboBox.addActionListener(e -> checkConflictsAsync(
                targetBranchComboBox, mergeModeComboBox, conflictStatusLabel, finalWorktreeBranchName, finalOkButton));
        mergeModeComboBox.addActionListener(e -> checkConflictsAsync(
                targetBranchComboBox, mergeModeComboBox, conflictStatusLabel, finalWorktreeBranchName, finalOkButton));

        if (okButton != null) {
            // Initial conflict check, now passing the button
            checkConflictsAsync(
                    targetBranchComboBox, mergeModeComboBox, conflictStatusLabel, finalWorktreeBranchName, okButton);
        } else {
            logger.warn("Could not find the OK button in the merge dialog. OK button state will not be managed.");
            // Fallback: perform an initial check without button control if OK button not found
            checkConflictsAsync(
                    targetBranchComboBox, mergeModeComboBox, conflictStatusLabel, finalWorktreeBranchName, null);
        }

        dialog.setVisible(true);
        Object selectedValue = optionPane.getValue();
        dialog.dispose();

        if (selectedValue != null && selectedValue.equals(JOptionPane.OK_OPTION)) {
            // Ensure these are captured before the lambda potentially changes them,
            // or ensure they are final/effectively final.
            final String selectedTargetBranch = (String) targetBranchComboBox.getSelectedItem();
            final GitRepo.MergeMode selectedMergeMode = (GitRepo.MergeMode) mergeModeComboBox.getSelectedItem();

            String currentConflictText = conflictStatusLabel.getText(); // Check the final state of the label
            if (currentConflictText.startsWith("No conflicts detected")
                    || currentConflictText.startsWith("Checking for conflicts")) {
                logger.info(
                        "Merge confirmed for worktree branch '{}' into target branch '{}' using mode '{}'. Remove worktree: {}, Remove branch: {}",
                        finalWorktreeBranchName,
                        selectedTargetBranch,
                        selectedMergeMode,
                        removeWorktreeCb.isSelected(),
                        removeBranchCb.isSelected());
                performMergeOperation(
                        finalWorktreeBranchName,
                        selectedTargetBranch,
                        selectedMergeMode,
                        removeWorktreeCb.isSelected(),
                        removeBranchCb.isSelected());
            } else {
                logger.info(
                        "Merge dialog confirmed with OK, but conflicts were present or an error occurred. Merge not performed.");
                JOptionPane.showMessageDialog(
                        this,
                        "Merge was not performed because conflicts were detected or an error occurred:\n"
                                + currentConflictText,
                        "Merge Prevented",
                        JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    private void checkConflictsAsync(
            JComboBox<String> targetBranchComboBox,
            JComboBox<GitRepo.MergeMode> mergeModeComboBox,
            JLabel conflictStatusLabel,
            String worktreeBranchName,
            @Nullable JButton okButton) {
        if (okButton != null) {
            okButton.setEnabled(false);
        }

        String selectedTargetBranch = (String) targetBranchComboBox.getSelectedItem();
        GitRepo.MergeMode selectedMergeMode = (GitRepo.MergeMode) mergeModeComboBox.getSelectedItem();

        if (selectedTargetBranch == null
                || selectedTargetBranch.equals("Error loading branches")
                || selectedTargetBranch.equals("Unsupported parent repo type")
                || selectedTargetBranch.equals("Parent repo not available")
                || selectedTargetBranch.equals("Parent project not found")
                || selectedTargetBranch.equals("Not a worktree project")) {
            conflictStatusLabel.setText("Please select a valid target branch.");
            conflictStatusLabel.setForeground(Color.RED);
            // okButton is already disabled, or null if not found
            return;
        }

        conflictStatusLabel.setText("Checking for conflicts with '" + selectedTargetBranch + "'...");
        conflictStatusLabel.setForeground(UIManager.getColor("Label.foreground")); // Default color

        var project = contextManager.getProject();
        assert project instanceof WorktreeProject;

        var parentProject = project.getParent();
        IGitRepo gitRepo = parentProject.getRepo();

        final String finalSelectedTargetBranch = selectedTargetBranch;
        final GitRepo.MergeMode finalSelectedMergeMode = selectedMergeMode;

        contextManager.submitBackgroundTask("Checking merge conflicts", () -> {
            String conflictResultString;
            try {
                if (finalSelectedTargetBranch.equals(worktreeBranchName)) {
                    conflictResultString = "Cannot merge a branch into itself.";
                } else {
                    // This checks for historical conflicts in a clean, temporary worktree
                    conflictResultString = gitRepo.checkMergeConflicts(
                            worktreeBranchName, finalSelectedTargetBranch, finalSelectedMergeMode);
                }
            } catch (GitRepo.WorktreeDirtyException e) {
                // uncommitted changes that would prevent even starting a simulation.
                logger.warn("Conflict check aborted because target worktree is dirty: {}", e.getMessage());
                conflictResultString = "Target branch has uncommitted changes that must be stashed or committed first.";
            } catch (GitAPIException e) {
                logger.error("GitAPIException during conflict check", e);
                conflictResultString = "Error checking conflicts: " + e.getMessage();
            } catch (Exception e) { // Catch other potential exceptions
                logger.error("Unexpected error during conflict check", e);
                conflictResultString = "Unexpected error during conflict check: " + e.getMessage();
            }

            final String finalConflictResultString = conflictResultString;
            SwingUtilities.invokeLater(() -> {
                if (finalConflictResultString != null && !finalConflictResultString.isBlank()) {
                    conflictStatusLabel.setText(finalConflictResultString);
                    conflictStatusLabel.setForeground(Color.RED);
                    if (okButton != null) {
                        okButton.setEnabled(false);
                    }
                } else {
                    conflictStatusLabel.setText("No conflicts detected with '"
                            + finalSelectedTargetBranch
                            + "' for "
                            + finalSelectedMergeMode.toString().toLowerCase(Locale.ROOT)
                            + ".");
                    conflictStatusLabel.setForeground(new Color(0, 128, 0)); // Green for no conflicts
                    if (okButton != null) {
                        okButton.setEnabled(true);
                    }
                }
            });
            return null;
        });
    }

    private void performMergeOperation(
            String worktreeBranchName,
            String targetBranch,
            GitRepo.MergeMode mode,
            boolean deleteWorktree,
            boolean deleteBranch) {
        var project = contextManager.getProject();
        if (!(project instanceof WorktreeProject worktreeProject)) {
            logger.error(
                    "performMergeOperation called on a non-WorktreeProject: {}",
                    project.getClass().getSimpleName());
            return;
        }

        MainProject parentProject = worktreeProject.getParent();
        var parentGitRepo = (GitRepo) parentProject.getRepo();

        Path worktreePath = worktreeProject.getRoot();

        contextManager.submitUserTask("Performing merge operation...", () -> {
            String originalParentBranch = null;
            try {
                originalParentBranch = parentGitRepo.getCurrentBranch();
                logger.info("Original parent branch: {}", originalParentBranch);

                parentGitRepo.checkout(targetBranch);
                logger.info("Switched parent repository to target branch: {}", targetBranch);

                // Use the centralized merge methods from GitRepo
                logger.info("Performing {} of {} into {}", mode, worktreeBranchName, targetBranch);
                MergeResult mergeResult = parentGitRepo.performMerge(worktreeBranchName, mode);

                if (!GitRepo.isMergeSuccessful(mergeResult, mode)) {
                    String conflictDetails = mergeResult.getConflicts() != null
                            ? String.join(", ", mergeResult.getConflicts().keySet())
                            : "unknown conflicts";
                    String errorMessage = "Merge failed with status: "
                            + mergeResult.getMergeStatus()
                            + ". Conflicts: "
                            + conflictDetails;
                    logger.error(errorMessage);
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                            GitWorktreeTab.this, errorMessage, "Merge Failed", JOptionPane.ERROR_MESSAGE));
                    return null; // Exit the task, finally block will still run
                }

                String modeDescription =
                        switch (mode) {
                            case MERGE_COMMIT -> "merged";
                            case SQUASH_COMMIT -> "squash merged";
                            case REBASE_MERGE -> "rebase-merged";
                        };
                chrome.systemOutput(
                        "Successfully " + modeDescription + " " + worktreeBranchName + " into " + targetBranch);

                // Post-Merge Cleanup
                if (deleteWorktree) {
                    logger.info("Attempting to delete worktree: {}", worktreePath);
                    MainProject.removeFromOpenProjectsListAndClearActiveSession(
                            worktreePath); // Attempt to close if open
                    try {
                        parentGitRepo.removeWorktree(worktreePath, true); // Force remove during automated cleanup
                        chrome.systemOutput("Worktree " + worktreePath.getFileName() + " removed.");

                        // After successfully removing the worktree, close any associated Brokk window.
                        SwingUtilities.invokeLater(() -> {
                            var windowToClose = Brokk.findOpenProjectWindow(worktreePath);
                            if (windowToClose != null) {
                                var closeEvent = new WindowEvent(windowToClose.getFrame(), WindowEvent.WINDOW_CLOSING);
                                windowToClose.getFrame().dispatchEvent(closeEvent);
                            }
                        });
                    } catch (GitAPIException e) {
                        String wtDeleteError = "Failed to delete worktree "
                                + worktreePath.getFileName()
                                + " during merge cleanup: "
                                + e.getMessage();
                        logger.error(wtDeleteError, e);
                        // Inform user, but proceed with other cleanup if possible
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                                GitWorktreeTab.this,
                                wtDeleteError,
                                "Worktree Deletion Failed (Cleanup)",
                                JOptionPane.WARNING_MESSAGE));
                    }

                    if (deleteBranch) {
                        logger.info("Attempting to force delete branch: {}", worktreeBranchName);
                        try {
                            parentGitRepo.forceDeleteBranch(worktreeBranchName);
                            chrome.systemOutput("Branch " + worktreeBranchName + " deleted.");
                        } catch (GitAPIException e) {
                            String branchDeleteError =
                                    "Failed to delete branch " + worktreeBranchName + ": " + e.getMessage();
                            logger.error(branchDeleteError, e);
                            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                                    GitWorktreeTab.this,
                                    branchDeleteError,
                                    "Branch Deletion Failed",
                                    JOptionPane.ERROR_MESSAGE));
                        }
                    }
                }
            } catch (GitAPIException e) {
                String errorMessage = "Error during merge operation: " + e.getMessage();
                logger.error(errorMessage, e);
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        GitWorktreeTab.this, errorMessage, "Merge Operation Failed", JOptionPane.ERROR_MESSAGE));
            } catch (Exception e) {
                String errorMessage = "Unexpected error during merge operation: " + e.getMessage();
                logger.error(errorMessage, e);
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        GitWorktreeTab.this, errorMessage, "Unexpected Error", JOptionPane.ERROR_MESSAGE));
            } finally {
                if (originalParentBranch != null) {
                    try {
                        logger.info("Attempting to switch parent repository back to branch: {}", originalParentBranch);
                        parentGitRepo.checkout(originalParentBranch);
                        chrome.systemOutput("Switched parent repository back to branch: " + originalParentBranch);
                    } catch (GitAPIException e) {
                        String restoreError = "Critical: Failed to switch parent repository back to original branch '"
                                + originalParentBranch
                                + "': "
                                + e.getMessage();
                        logger.error(restoreError, e);
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                                GitWorktreeTab.this,
                                restoreError,
                                "Repository State Error",
                                JOptionPane.ERROR_MESSAGE));
                    }
                }
                SwingUtilities.invokeLater(() -> {
                    loadWorktrees(); // Refresh this tab
                    chrome.updateGitRepo(); // Refresh other Git-related UI in the Chrome
                });
            }
            return null;
        });
    }
}
