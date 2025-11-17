package ai.brokk.gui.git;

import ai.brokk.Brokk;
import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.MainProject;
import ai.brokk.WorktreeProject;
import ai.brokk.git.GitRepo;
import ai.brokk.git.IGitRepo;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.util.Icons;
import ai.brokk.gui.util.MergeDialogUtil;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
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

    private JTable worktreeTable = new JTable();
    private DefaultTableModel worktreeTableModel = new DefaultTableModel();
    private MaterialButton addButton = new MaterialButton();
    private MaterialButton removeButton = new MaterialButton();
    private MaterialButton openButton = new MaterialButton(); // Added
    private MaterialButton refreshButton = new MaterialButton(); // Added

    @Nullable
    private MaterialButton mergeButton = null; // Added for worktree merge functionality

    private final boolean isWorktreeWindow;

    public GitWorktreeTab(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;

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

        JPanel contentPanel = new JPanel(new GridBagLayout()); // Center the message within the titled panel
        JLabel unsupportedLabel = new JLabel("Git executable not found, worktrees are unavailable");
        unsupportedLabel.setHorizontalAlignment(SwingConstants.CENTER);
        contentPanel.add(unsupportedLabel, new GridBagConstraints());

        JPanel titledPanel = new JPanel(new BorderLayout());
        titledPanel.setBorder(BorderFactory.createTitledBorder("Worktrees"));
        titledPanel.add(contentPanel, BorderLayout.CENTER);

        add(titledPanel, BorderLayout.CENTER);

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
                        // if row 0 is already the only thing selected, do nothing to allow deselection by clicking
                        // elsewhere
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
        worktreeTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
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
        activeColumn.setCellRenderer(new DefaultTableCellRenderer() {
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

        // added to titled panel below

        // Button panel for actions
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));

        // Initialize field buttons (their properties like text, tooltip, listener)
        addButton = new MaterialButton();
        addButton.setIcon(Icons.ADD);
        addButton.setToolTipText("Add new worktree");
        addButton.addActionListener(e -> addWorktree());

        removeButton = new MaterialButton();
        removeButton.setIcon(Icons.REMOVE);
        removeButton.setToolTipText("Remove selected worktree(s)");
        removeButton.setEnabled(false); // Initially disabled
        removeButton.addActionListener(e -> removeWorktree());

        openButton = new MaterialButton();
        openButton.setIcon(Icons.OPEN_NEW_WINDOW);
        openButton.setToolTipText("Open selected worktree(s)");
        openButton.setEnabled(false); // Initially disabled
        openButton.addActionListener(e -> {
            List<Path> pathsToOpen = getSelectedWorktreePaths();
            if (!pathsToOpen.isEmpty()) {
                handleOpenOrFocusWorktrees(pathsToOpen);
            }
        });

        refreshButton = new MaterialButton();
        refreshButton.setIcon(Icons.REFRESH);
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
            mergeButton = new MaterialButton("Merge " + wtName + " into...");
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

        JPanel titledPanel = new JPanel(new BorderLayout());
        titledPanel.setBorder(BorderFactory.createTitledBorder("Worktrees"));
        titledPanel.add(tablePanel, BorderLayout.CENTER);
        titledPanel.add(buttonPanel, BorderLayout.SOUTH);

        add(titledPanel, BorderLayout.CENTER);

        worktreeTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateButtonStates();
            }
        });

        worktreeTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
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
            public void mousePressed(MouseEvent e) {
                showPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showPopup(e);
            }

            private void showPopup(MouseEvent e) {
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
        List<Path> paths = new ArrayList<>();
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
                    var result = gitRepo.worktrees().listWorktreesAndInvalid();
                    var worktrees = result.worktrees();
                    var invalidPaths = result.invalidPaths();

                    if (!invalidPaths.isEmpty()) {
                        final var dialogFuture = new CompletableFuture<Integer>();
                        SwingUtilities.invokeLater(() -> {
                            String pathList =
                                    invalidPaths.stream().map(Path::toString).collect(Collectors.joining("\n- "));
                            String message = "The following worktree paths no longer exist on disk:\n\n- " + pathList
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
                                    gitRepo.worktrees().pruneWorktrees();
                                    chrome.showNotification(
                                            IConsoleIO.NotificationRole.INFO, "Successfully pruned stale worktrees.");
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

        contextManager.submitContextTask(() -> {
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
        MainProject project = contextManager.getProject().getMainProject();
        IGitRepo repo = project.getRepo(); // This repo instance is effectively final for the lambda

        contextManager.submitContextTask(() -> {
            if (!(repo instanceof GitRepo gitRepo)) {
                chrome.toolError(
                        "Worktree operations are only supported for Git repositories.", "Unsupported Repository Type");
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
            } catch (GitAPIException e) {
                logger.error("Error fetching initial branch information for add worktree", e);
                SwingUtilities.invokeLater(() -> chrome.toolError(
                        "Failed to fetch branch information from Git repository:\n" + e.getMessage(),
                        "Git Repository Error"));
                return;
            }

            final CompletableFuture<AddWorktreeDialogResult> dialogFuture = new CompletableFuture<>();
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
                // Determine whether any existing branches are available to reuse
                boolean hasAvailableBranches = !finalAvailableBranches.isEmpty();

                ButtonGroup group = new ButtonGroup();
                group.add(createNewBranchRadio);
                group.add(useExistingBranchRadio);

                // If there are no available existing branches, disable that option and force create-new
                if (!hasAvailableBranches) {
                    useExistingBranchRadio.setEnabled(false);
                    branchComboBox.setEnabled(false);
                    createNewBranchRadio.setSelected(true);
                    // Explain why the option is disabled so users understand why the choice is unavailable.
                    useExistingBranchRadio.setToolTipText("No existing branches available — create a new branch.");
                    branchComboBox.setToolTipText("No existing branches available to choose from.");
                    // Informational, non-blocking notification so the user understands what will happen if they create
                    // a new branch.
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            "No existing branches are available — creating a new branch will use the selected 'From' branch.");
                }

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

                JCheckBox copyWorkspaceCheckbox = new JCheckBox("Copy Context to worktree Session");
                copyWorkspaceCheckbox.setSelected(false);
                gbc.gridx = 0;
                gbc.gridy = 5;
                gbc.gridwidth = 2;
                gbc.anchor = GridBagConstraints.WEST;
                gbc.fill = GridBagConstraints.HORIZONTAL;
                gbc.insets = new Insets(10, 5, 5, 5);
                panel.add(copyWorkspaceCheckbox, gbc);

                // Inform the user in-dialog when no existing branches are available to reuse
                if (!hasAvailableBranches) {
                    gbc.gridx = 0;
                    gbc.gridy = 6;
                    gbc.gridwidth = 2;
                    gbc.anchor = GridBagConstraints.WEST;
                    gbc.fill = GridBagConstraints.HORIZONTAL;
                    gbc.insets = new Insets(5, 5, 5, 5);
                    panel.add(
                            new JLabel(
                                    "<html><i>No existing branches are available to reuse — please create a new branch.</i></html>"),
                            gbc);
                }

                JOptionPane optionPane =
                        new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
                JDialog dialog = optionPane.createDialog(GitWorktreeTab.this, "Add Worktree");
                MaterialButton okButton = new MaterialButton(UIManager.getString("OptionPane.okButtonText"));
                okButton.addActionListener(e -> {
                    optionPane.setValue(JOptionPane.OK_OPTION);
                    dialog.dispose();
                });
                SwingUtil.applyPrimaryButtonStyle(okButton);

                MaterialButton cancelButton = new MaterialButton(UIManager.getString("OptionPane.cancelButtonText"));
                cancelButton.addActionListener(e -> {
                    optionPane.setValue(JOptionPane.CANCEL_OPTION);
                    dialog.dispose();
                });

                optionPane.setOptions(new Object[] {okButton, cancelButton});
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
                    chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Add worktree cancelled by user.");
                    return;
                }

                String branchForWorktree = dialogResult.selectedBranch();
                String sourceBranchForNew = dialogResult.sourceBranchForNew();
                boolean isCreatingNewBranch = dialogResult.isCreatingNewBranch();
                boolean copyWorkspace = dialogResult.copyWorkspace();

                // Defensive validation to guard against unexpected dialog results:
                if (isCreatingNewBranch) {
                    // Require a non-null, non-empty branch name (trim whitespace)
                    if (branchForWorktree == null || branchForWorktree.trim().isEmpty()) {
                        chrome.toolError(
                                "New branch name cannot be empty. Please provide a valid branch name.",
                                "Invalid Branch Name");
                        return;
                    }
                } else {
                    // Using existing branch: ensure the selected branch is actually available
                    if (branchForWorktree == null || !finalAvailableBranches.contains(branchForWorktree)) {
                        chrome.toolError(
                                "Selected branch is not available. Please choose an existing branch or create a new one.",
                                "Invalid Branch Selection");
                        return;
                    }
                }

                if (isCreatingNewBranch) {
                    if (branchForWorktree.isEmpty()) {
                        chrome.toolError(
                                "New branch name cannot be empty. Please provide a valid branch name.",
                                "Invalid Branch Name");
                        return;
                    }
                    try {
                        branchForWorktree =
                                gitRepo.sanitizeBranchName(branchForWorktree); // Sanitize on background thread
                    } catch (GitAPIException e) {
                        chrome.toolError(
                                "Failed to sanitize branch name:\n" + e.getMessage(), "Branch Name Validation Error");
                        return;
                    }
                    if (sourceBranchForNew.isEmpty()) {
                        chrome.toolError(
                                "A source branch must be selected to create a new branch.", "Missing Source Branch");
                        return;
                    }
                } else { // Using existing branch
                }

                chrome.showNotification(
                        IConsoleIO.NotificationRole.INFO, "Adding worktree for branch: " + branchForWorktree);

                WorktreeSetupResult setupResult = setupNewGitWorktree(
                        project, gitRepo, branchForWorktree, isCreatingNewBranch, sourceBranchForNew);
                Path newWorktreePath = setupResult.worktreePath();

                Brokk.OpenProjectBuilder openProjectBuilder =
                        new Brokk.OpenProjectBuilder(newWorktreePath).parent(project);
                if (copyWorkspace) {
                    logger.info("Copying current workspace to new worktree session for {}", newWorktreePath);
                    openProjectBuilder.sourceContextForSession(contextManager.liveContext());
                }

                final String finalBranchForWorktree = branchForWorktree; // for lambda
                openProjectBuilder.open().thenAccept(success -> {
                    if (Boolean.TRUE.equals(success)) {
                        chrome.showNotification(
                                IConsoleIO.NotificationRole.INFO,
                                "Successfully opened worktree: " + newWorktreePath.getFileName());
                    } else {
                        chrome.toolError(
                                "Error opening worktree " + newWorktreePath.getFileName(), "Worktree Open Error");
                    }
                    SwingUtilities.invokeLater(this::loadWorktrees);
                });
                chrome.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "Successfully created worktree for branch '" + finalBranchForWorktree + "' at "
                                + newWorktreePath);

            } catch (InterruptedException | ExecutionException e) {
                logger.error("Error during add worktree dialog processing or future execution", e);
                chrome.toolError(
                        "Failed to process worktree addition dialog:\n" + e.getMessage(), "Dialog Processing Error");
            } catch (GitAPIException | IOException e) { // Catches from setupNewGitWorktree or sanitizeBranchName
                logger.error("Error creating worktree", e);
                String errorDetail = e.getMessage();
                final String contextualMessage;
                if (e instanceof GitAPIException && errorDetail != null && errorDetail.contains("LOCK_FAILURE")) {
                    contextualMessage =
                            "Failed to create worktree due to Git lock conflict. This may occur if another Git operation is in progress.";
                } else {
                    contextualMessage = "Failed to create worktree:\n" + errorDetail;
                }
                chrome.toolError(contextualMessage, "Worktree Creation Failed");
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

        int confirm = chrome.showConfirmDialog(
                this,
                "Are you sure you want to remove the following worktree(s):\n" + pathListString
                        + "\n\nThis will delete the files from disk and attempt to close their Brokk window(s) if open.",
                "Confirm Worktree Removal",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        MainProject project = contextManager.getProject().getMainProject();
        IGitRepo repo = project.getRepo();

        if (!(repo instanceof GitRepo)) { // Should not happen if buttons are correctly disabled by buildUnsupportedUI
            chrome.toolError(
                    "Worktree operations are only supported for Git repositories.", "Unsupported Repository Type");
            return;
        }

        contextManager.submitContextTask(() -> {
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
                                        forceEx) { // WorktreeNeedsForceException is a subclass and would be caught here
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

                    final CompletableFuture<Integer> dialogResultFuture = new CompletableFuture<>();
                    SwingUtilities.invokeLater(() -> {
                        Object[] options = {"Yes", "Yes to All", "No"};
                        int result = JOptionPane.showOptionDialog(
                                GitWorktreeTab.this,
                                "Removing worktree '" + worktreePath.getFileName() + "' requires force.\n"
                                        + ne.getMessage()
                                        + "\n" + "Do you want to force delete it?",
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
                        } else {
                            chrome.showNotification(
                                    IConsoleIO.NotificationRole.INFO,
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
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO, "Completed worktree removal with one or more errors.");
                } else if (!pathsToRemove.isEmpty()) {
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO, "Successfully removed all selected worktrees.");
                }
            });
        });
    }

    private void attemptRemoveWorktree(IGitRepo repo, Path worktreePath, boolean force)
            throws GitRepo.WorktreeNeedsForceException, GitRepo.GitRepoException {
        try {
            repo.removeWorktree(worktreePath, force);

            chrome.showNotification(
                    IConsoleIO.NotificationRole.INFO,
                    "Successfully " + (force ? "force " : "") + "removed worktree at " + worktreePath);

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
                        "Worktree removal for " + worktreePath.getFileName()
                                + " reported 'needs force' even when force was active.",
                        wnf);
            }
        } catch (GitAPIException gae) { // Includes other JGit/GitAPI specific exceptions
            throw new GitRepo.GitRepoException(
                    "Git API error during " + (force ? "forced " : "") + "removal of worktree "
                            + worktreePath.getFileName() + ": " + gae.getMessage(),
                    gae);
        } catch (RuntimeException re) { // Catches GitWrappedIOException or other runtime issues
            throw new GitRepo.GitRepoException(
                    "Runtime error during " + (force ? "forced " : "") + "removal of worktree "
                            + worktreePath.getFileName() + ": " + re.getMessage(),
                    re);
        }
    }

    private void reportRemoveError(Path worktreePath, Exception e) {
        final String pathName = worktreePath.getFileName().toString();
        chrome.toolError("Failed to remove worktree '" + pathName + "':\n" + e.getMessage(), "Worktree Removal Error");
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

        // Copy (prefer hard-link) existing language storage caches to the new worktree
        var enabledLanguages = parentProject.getAnalyzerLanguages();
        for (var lang : enabledLanguages) {
            var sourceCache = lang.getStoragePath(parentProject);
            if (!Files.exists(sourceCache)) {
                continue;
            }
            try {
                var relative = parentProject.getRoot().relativize(sourceCache);
                var destCache = newWorktreePath.resolve(relative);
                Files.createDirectories(destCache.getParent());
                try {
                    Files.createLink(destCache, sourceCache); // Try hard-link first
                    logger.debug("Hard-linked analyzer storage cache from {} to {}", sourceCache, destCache);
                } catch (UnsupportedOperationException | IOException linkEx) {
                    Files.copy(sourceCache, destCache, StandardCopyOption.REPLACE_EXISTING);
                    logger.debug("Copied analyzer storage cache from {} to {}", sourceCache, destCache);
                }
            } catch (IOException copyEx) {
                logger.warn(
                        "Failed to replicate analyzer storage cache for language {}: {}",
                        lang.name(),
                        copyEx.getMessage(),
                        copyEx);
            }
        }

        return new WorktreeSetupResult(newWorktreePath, branchForWorktree);
    }

    private void showMergeDialog() {
        var project = contextManager.getProject();
        if (!(project instanceof WorktreeProject)) {
            // This should not happen if the merge button is only available in worktree views.
            logger.warn("Merge dialog opened for a non-worktree project: {}", project.getRoot());
            return;
        }

        String worktreeBranchName = "";
        if (project.getRepo() instanceof GitRepo gitRepo) {
            try {
                worktreeBranchName = gitRepo.getCurrentBranch();
            } catch (GitAPIException e) {
                logger.error("Could not get current branch for worktree", e);
                chrome.toolError("Could not determine current branch: " + e.getMessage(), "Merge Error");
                return;
            }
        }

        // Defensive check: ensure we have a valid Git repository and branch name
        if (worktreeBranchName.isEmpty()) {
            logger.error("Cannot determine worktree branch - not a Git repository or unable to get current branch");
            chrome.toolError("This operation requires a Git repository with a valid current branch", "Merge Error");
            return;
        }

        // Use the shared merge dialog utility
        var options =
                MergeDialogUtil.MergeDialogOptions.forWorktreeMerge(worktreeBranchName, this, chrome, contextManager);
        var result = MergeDialogUtil.showMergeDialog(options);

        if (result.confirmed()) {
            performMergeOperation(
                    result.sourceBranch(),
                    result.targetBranch(),
                    result.mergeMode(),
                    result.deleteWorktree(),
                    result.deleteBranch());
        }
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

        contextManager.submitExclusiveAction(() -> {
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
                    String errorMessage = "Merge of '" + worktreeBranchName + "' into '" + targetBranch
                            + "' failed with status: " + mergeResult.getMergeStatus() + ".\n\nConflicting files: "
                            + conflictDetails;
                    logger.error(errorMessage);
                    chrome.toolError(errorMessage, "Merge Failed");
                    return null; // Exit the task, finally block will still run
                }

                String modeDescription =
                        switch (mode) {
                            case MERGE_COMMIT -> "merged";
                            case SQUASH_COMMIT -> "squash merged";
                            case REBASE_MERGE -> "rebase-merged";
                        };
                chrome.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "Successfully " + modeDescription + " " + worktreeBranchName + " into " + targetBranch);

                // Post-Merge Cleanup
                if (deleteWorktree) {
                    logger.info("Attempting to delete worktree: {}", worktreePath);
                    MainProject.removeFromOpenProjectsListAndClearActiveSession(
                            worktreePath); // Attempt to close if open
                    try {
                        parentGitRepo.removeWorktree(worktreePath, true); // Force remove during automated cleanup
                        chrome.showNotification(
                                IConsoleIO.NotificationRole.INFO,
                                "Worktree " + worktreePath.getFileName() + " removed.");

                        // After successfully removing the worktree, close any associated Brokk window.
                        SwingUtilities.invokeLater(() -> {
                            var windowToClose = Brokk.findOpenProjectWindow(worktreePath);
                            if (windowToClose != null) {
                                var closeEvent = new WindowEvent(windowToClose.getFrame(), WindowEvent.WINDOW_CLOSING);
                                windowToClose.getFrame().dispatchEvent(closeEvent);
                            }
                        });
                    } catch (GitAPIException e) {
                        String wtDeleteError = "Failed to delete worktree '" + worktreePath.getFileName()
                                + "' during post-merge cleanup:\n" + e.getMessage()
                                + "\n\nYou may need to manually remove this worktree.";
                        logger.error(wtDeleteError, e);
                        // Inform user, but proceed with other cleanup if possible
                        chrome.toolError(wtDeleteError, "Worktree Deletion Failed");
                    }

                    if (deleteBranch) {
                        logger.info("Attempting to force delete branch: {}", worktreeBranchName);
                        try {
                            parentGitRepo.forceDeleteBranch(worktreeBranchName);
                            chrome.showNotification(
                                    IConsoleIO.NotificationRole.INFO, "Branch " + worktreeBranchName + " deleted.");
                        } catch (GitAPIException e) {
                            String branchDeleteError = "Failed to delete branch '" + worktreeBranchName
                                    + "' during post-merge cleanup:\n" + e.getMessage()
                                    + "\n\nYou may need to manually delete this branch.";
                            logger.error(branchDeleteError, e);
                            chrome.toolError(branchDeleteError, "Branch Deletion Failed");
                        }
                    }
                }
            } catch (GitAPIException e) {
                String errorMessage = "Git error during merge of '" + worktreeBranchName + "' into '" + targetBranch
                        + "':\n" + e.getMessage();
                logger.error(errorMessage, e);
                chrome.toolError(errorMessage, "Merge Operation Failed");
            } catch (Exception e) {
                String errorMessage = "Unexpected error during merge of '" + worktreeBranchName + "' into '"
                        + targetBranch + "':\n" + e.getMessage();
                logger.error(errorMessage, e);
                chrome.toolError(errorMessage, "Unexpected Merge Error");
            } finally {
                if (originalParentBranch != null) {
                    try {
                        logger.info("Attempting to switch parent repository back to branch: {}", originalParentBranch);
                        parentGitRepo.checkout(originalParentBranch);
                        chrome.showNotification(
                                IConsoleIO.NotificationRole.INFO,
                                "Switched parent repository back to branch: " + originalParentBranch);
                    } catch (GitAPIException e) {
                        String restoreError = "CRITICAL: Failed to restore parent repository to original branch '"
                                + originalParentBranch + "':\n" + e.getMessage()
                                + "\n\nThe repository may be in an unexpected state. "
                                + "Please manually checkout the correct branch.";
                        logger.error(restoreError, e);
                        chrome.toolError(restoreError, "Repository State Error");
                    }
                }
                loadWorktrees(); // Refresh this tab
                chrome.updateGitRepo(); // Refresh other Git-related UI in the Chrome
            }
            return null;
        });
    }
}
