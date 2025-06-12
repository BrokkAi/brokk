package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextHistory;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.HistoryOutputPanel;
import io.github.jbellis.brokk.gui.WorkspacePanel;
import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import io.github.jbellis.brokk.gui.SwingUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.UUID;

/**
 * Modal dialog for managing sessions with Activity log, Workspace panel, and MOP preview
 */
public class SessionsDialog extends JDialog {
    private final HistoryOutputPanel historyOutputPanel;
    private final Chrome chrome;
    private final ContextManager contextManager;

    // Sessions table components
    private JTable sessionsTable;
    private DefaultTableModel sessionsTableModel;
    private JButton closeButton;

    // Activity history components
    private JTable activityTable;
    private DefaultTableModel activityTableModel;

    // Preview components
    private WorkspacePanel workspacePanel;
    private MarkdownOutputPanel markdownOutputPanel;
    private JScrollPane markdownScrollPane;
    private @Nullable Context selectedActivityContext;

    public SessionsDialog(HistoryOutputPanel historyOutputPanel, Chrome chrome, ContextManager contextManager) {
        super(chrome.getFrame(), "Manage Sessions", true);
        this.historyOutputPanel = historyOutputPanel;
        this.chrome = chrome;
        this.contextManager = contextManager;

        initializeComponents();
        layoutComponents();
        setupEventHandlers();
        refreshSessionsTable();

        // Set larger size for 4-panel layout
        setSize(1400, 800);
        setLocationRelativeTo(chrome.getFrame());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    private void initializeComponents() {
        // Initialize sessions table model with Active, Session Name, and hidden SessionInfo columns
        sessionsTableModel = new DefaultTableModel(new Object[]{"Active", "Session Name", "SessionInfo"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        // Initialize sessions table
        sessionsTable = new JTable(sessionsTableModel) {
            @Override
            public String getToolTipText(MouseEvent event) {
                java.awt.Point p = event.getPoint();
                int rowIndex = rowAtPoint(p);
                if (rowIndex >= 0 && rowIndex < getRowCount()) {
                    MainProject.SessionInfo sessionInfo = (MainProject.SessionInfo) sessionsTableModel.getValueAt(rowIndex, 2);
                    return "Last modified: " + DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(sessionInfo.modified()));
                }
                return super.getToolTipText(event);
            }
        };
        sessionsTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        sessionsTable.setTableHeader(null);

        // Set up column renderers for sessions table
        sessionsTable.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                          boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                label.setHorizontalAlignment(JLabel.CENTER);
                return label;
            }
        });

        // Initialize activity table model
        activityTableModel = new DefaultTableModel(new Object[]{"", "Action", "Context"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        // Initialize activity table
        activityTable = new JTable(activityTableModel);
        activityTable.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        activityTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        activityTable.setTableHeader(null);

        // Set up tooltip renderer for activity description column
        activityTable.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                          boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel label = (JLabel)super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column);
                label.setToolTipText(value.toString());
                return label;
            }
        });

        // Set up icon renderer for first column of activity table
        activityTable.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                          boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel label = (JLabel)super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column);

                // Set icon and center-align
                if (value instanceof Icon icon) {
                    label.setIcon(icon);
                    label.setText("");
                } else {
                    label.setIcon(null);
                    label.setText(value.toString());
                }
                label.setHorizontalAlignment(JLabel.CENTER);

                return label;
            }
        });

        // Adjust activity table column widths
        activityTable.getColumnModel().getColumn(0).setPreferredWidth(30);
        activityTable.getColumnModel().getColumn(0).setMinWidth(30);
        activityTable.getColumnModel().getColumn(0).setMaxWidth(30);
        activityTable.getColumnModel().getColumn(1).setPreferredWidth(250);
        activityTable.getColumnModel().getColumn(2).setMinWidth(0);
        activityTable.getColumnModel().getColumn(2).setMaxWidth(0);
        activityTable.getColumnModel().getColumn(2).setWidth(0);

        // Initialize workspace panel for preview (copy-only menu)
        workspacePanel = new WorkspacePanel(chrome,
                                            contextManager,
                                            WorkspacePanel.PopupMenuMode.COPY_ONLY);
        workspacePanel.setWorkspaceEditable(false); // Make workspace read-only in manage dialog

        // Initialize markdown output panel for preview
        markdownOutputPanel = new MarkdownOutputPanel();
        markdownOutputPanel.updateTheme(chrome.getTheme().isDarkTheme());
        markdownScrollPane = new JScrollPane(markdownOutputPanel);
        markdownScrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        markdownScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        // Initialize buttons
        closeButton = new JButton("Close");
    }

    private void layoutComponents() {
        setLayout(new BorderLayout());

        // Create sessions panel
        JPanel sessionsPanel = new JPanel(new BorderLayout());
        sessionsPanel.setBorder(BorderFactory.createTitledBorder("Sessions"));
        JScrollPane sessionsScrollPane = new JScrollPane(sessionsTable);
        sessionsPanel.add(sessionsScrollPane, BorderLayout.CENTER);

        // Create activity panel
        JPanel activityPanel = new JPanel(new BorderLayout());
        activityPanel.setBorder(BorderFactory.createTitledBorder("Activity"));
        JScrollPane activityScrollPane = new JScrollPane(activityTable);
        activityPanel.add(activityScrollPane, BorderLayout.CENTER);

        // Create workspace panel without additional border (workspacePanel already has its own border)
        JPanel workspacePanelContainer = new JPanel(new BorderLayout());
        workspacePanelContainer.add(workspacePanel, BorderLayout.CENTER);

        // Create MOP panel
        JPanel mopPanel = new JPanel(new BorderLayout());
        mopPanel.setBorder(BorderFactory.createTitledBorder("Output"));
        mopPanel.add(markdownScrollPane, BorderLayout.CENTER);

        // Create top row with Sessions (30%), Activity (30%), and MOP (40%) horizontal space
        JSplitPane topFirstSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sessionsPanel, activityPanel);
        topFirstSplit.setResizeWeight(0.5); // Sessions gets 30%, Activity gets 30%, so 30/(30+30) = 0.5

        JSplitPane topSecondSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, topFirstSplit, mopPanel);
        topSecondSplit.setResizeWeight(0.6); // Sessions+Activity get 60%, MOP gets 40%

        // Set divider locations after the dialog is shown to achieve 30%/30%/40% split
        SwingUtilities.invokeLater(() -> {
            int totalWidth = topSecondSplit.getWidth();
            if (totalWidth > 0) {
                // Set first divider at 30% of the way (between Sessions and Activity)
                topFirstSplit.setDividerLocation((3 * totalWidth) / 10);
                // Set second divider at 60% of the way (between Activity and MOP)
                topSecondSplit.setDividerLocation((3 * totalWidth) / 5);
            }
        });

        // Create main vertical split with top row and workspace below
        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topSecondSplit, workspacePanelContainer);
        mainSplit.setResizeWeight(0.75); // Top gets 75%, workspace gets 25%

        // Create button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 10, 10));
        buttonPanel.add(closeButton);

        // Add components to dialog
        add(mainSplit, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void setupEventHandlers() {
        // Session selection listener - load session history instead of switching
        sessionsTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            int[] selected = sessionsTable.getSelectedRows();
            if (selected.length == 1) {
                var info = (MainProject.SessionInfo) sessionsTableModel.getValueAt(selected[0], 2);
                loadSessionHistory(info.id());
            } else { // multi-select: clear preview panels
                activityTableModel.setRowCount(0);
                clearPreviewPanels();
            }
        });

        // Activity selection listener - update workspace and MOP
        activityTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = activityTable.getSelectedRow();
                if (row >= 0 && row < activityTable.getRowCount()) {
                    selectedActivityContext = (Context) activityTableModel.getValueAt(row, 2);
                    updatePreviewPanels(selectedActivityContext);
                } else {
                    clearPreviewPanels();
                }
            }
        });

        // Add mouse listener for right-click context menu and double-click on sessions table
        sessionsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showSessionContextMenu(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showSessionContextMenu(e);
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = sessionsTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        MainProject.SessionInfo sessionInfo = (MainProject.SessionInfo) sessionsTableModel.getValueAt(row, 2);
                        renameSession(sessionInfo);
                    }
                }
            }
        });

        // Button listeners
        closeButton.addActionListener(e -> dispose());

        // ESC key to close
        var rootPane = getRootPane();
        var actionMap = rootPane.getActionMap();
        var inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeDialog");
        actionMap.put("closeDialog", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                dispose();
            }
        });
    }

    private void loadSessionHistory(UUID sessionId) {
        // Clear current preview panels
        clearPreviewPanels();

        // Load session history asynchronously
        contextManager.loadSessionHistoryAsync(sessionId).thenAccept(history -> {
            SwingUtilities.invokeLater(() -> {
                populateActivityTable(history);
            });
        }).exceptionally(throwable -> {
            SwingUtilities.invokeLater(() -> {
                chrome.toolError("Failed to load session history: " + throwable.getMessage());
                activityTableModel.setRowCount(0);
            });
            return null;
        });
    }

    private void populateActivityTable(ContextHistory history) {
        activityTableModel.setRowCount(0);

        if (history.getHistory().isEmpty()) {
            return;
        }

        // Add rows for each context in history
        for (var ctx : history.getHistory()) {
            // Add icon for AI responses, null for user actions
            Icon iconEmoji = (ctx.getParsedOutput() != null) ? SwingUtil.uiIcon("Brokk.ai-robot") : null;
            activityTableModel.addRow(new Object[]{
                    iconEmoji,
                    ctx.getAction(),
                    ctx // Store the actual context object in hidden column
            });
        }

        // Select the most recent item (last row) if available
        SwingUtilities.invokeLater(() -> {
            if (activityTableModel.getRowCount() > 0) {
                int lastRow = activityTableModel.getRowCount() - 1;
                activityTable.setRowSelectionInterval(lastRow, lastRow);
                activityTable.scrollRectToVisible(activityTable.getCellRect(lastRow, 0, true));
            }
        });
    }

    private void updatePreviewPanels(Context context) {
        if (context == null) {
            clearPreviewPanels();
            return;
        }

        // Update workspace panel with selected context
        workspacePanel.populateContextTable(context);

        // Update MOP with parsed output if available
        if (context.getParsedOutput() != null) {
            markdownOutputPanel.setText(context.getParsedOutput());
        } else {
            markdownOutputPanel.clear();
        }
        markdownOutputPanel.scheduleCompaction().thenRun(() -> SwingUtilities.invokeLater(() -> markdownScrollPane.getViewport().setViewPosition(new Point(0, 0))));;
    }

    private void clearPreviewPanels() {
        // Clear workspace panel
        workspacePanel.populateContextTable(null);

        // Clear MOP
        markdownOutputPanel.clear();
    }

    public void refreshSessionsTable() {
        sessionsTableModel.setRowCount(0);
        List<MainProject.SessionInfo> sessions = contextManager.getProject().listSessions();
        sessions.sort(java.util.Comparator.comparingLong(IProject.SessionInfo::modified).reversed()); // Sort newest first

        UUID currentSessionId = contextManager.getCurrentSessionId();
        for (var session : sessions) {
            String active = session.id().equals(currentSessionId) ? "✓" : "";
            sessionsTableModel.addRow(new Object[]{active, session.name(), session});
        }

        // Hide the "SessionInfo" column
        sessionsTable.getColumnModel().getColumn(2).setMinWidth(0);
        sessionsTable.getColumnModel().getColumn(2).setMaxWidth(0);
        sessionsTable.getColumnModel().getColumn(2).setWidth(0);

        // Set column widths for sessions table
        sessionsTable.getColumnModel().getColumn(0).setPreferredWidth(40);
        sessionsTable.getColumnModel().getColumn(0).setMaxWidth(40);
        sessionsTable.getColumnModel().getColumn(1).setPreferredWidth(120);

        // Select current session and load its history
        for (int i = 0; i < sessionsTableModel.getRowCount(); i++) {
            MainProject.SessionInfo rowInfo = (MainProject.SessionInfo) sessionsTableModel.getValueAt(i, 2);
            if (rowInfo.id().equals(currentSessionId)) {
                sessionsTable.setRowSelectionInterval(i, i);
                loadSessionHistory(rowInfo.id()); // Load history for current session
                break;
            }
        }
    }

    private void showSessionContextMenu(MouseEvent e) {
        int row = sessionsTable.rowAtPoint(e.getPoint());
        if (row < 0) return;

        // If right-click happens on a row not already selected, switch to that single row
        if (!sessionsTable.isRowSelected(row)) {
            sessionsTable.setRowSelectionInterval(row, row);
        }

        int[] selectedRows = sessionsTable.getSelectedRows();
        var selectedSessions = java.util.Arrays.stream(selectedRows)
                                               .mapToObj(r -> (MainProject.SessionInfo) sessionsTableModel.getValueAt(r, 2))
                                               .toList();

        JPopupMenu popup = new JPopupMenu();

        /* ---------- single-selection items ---------- */
        if (selectedSessions.size() == 1) {
            var sessionInfo = selectedSessions.getFirst();

            JMenuItem setActiveItem = new JMenuItem("Set as Active");
            setActiveItem.setEnabled(!sessionInfo.id().equals(contextManager.getCurrentSessionId()));
            setActiveItem.addActionListener(ev -> contextManager.switchSessionAsync(sessionInfo.id())
                                                                .thenRun(() -> SwingUtilities.invokeLater(() -> {
                                                                    refreshSessionsTable();
                                                                    historyOutputPanel.updateSessionComboBox();
                                                                })));
            popup.add(setActiveItem);
            popup.addSeparator();

            JMenuItem renameItem = new JMenuItem("Rename");
            renameItem.addActionListener(ev -> renameSession(sessionInfo));
            popup.add(renameItem);
        }

        /* ---------- delete (single or multi) ---------- */
        JMenuItem deleteItem = new JMenuItem(selectedSessions.size() == 1 ? "Delete" : "Delete Selected");
        deleteItem.addActionListener(ev -> {
            int confirm = JOptionPane.showConfirmDialog(SessionsDialog.this,
                                                        "Are you sure you want to delete the selected session(s)?",
                                                        "Confirm Delete",
                                                        JOptionPane.YES_NO_OPTION,
                                                        JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) {
                var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<?>>();
                for (var s : selectedSessions) {
                    futures.add(contextManager.deleteSessionAsync(s.id()));
                }
                java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                                                      .thenRun(() -> SwingUtilities.invokeLater(() -> {
                                                          refreshSessionsTable();
                                                          historyOutputPanel.updateSessionComboBox();
                                                      }));
            }
        });
        popup.add(deleteItem);

        /* ---------- duplicate (single or multi) ---------- */
        JMenuItem dupItem = new JMenuItem(selectedSessions.size() == 1 ? "Duplicate" : "Duplicate Selected");
        dupItem.addActionListener(ev -> {
            var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<?>>();
            for (var s : selectedSessions) {
                futures.add(contextManager.copySessionAsync(s.id(), s.name()));
            }
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                                                  .thenRun(() -> SwingUtilities.invokeLater(() -> {
                                                      refreshSessionsTable();
                                                      historyOutputPanel.updateSessionComboBox();
                                                  }));
        });
        popup.add(dupItem);

        // Register popup with theme manager
        chrome.getTheme().registerPopupMenu(popup);

        popup.show(sessionsTable, e.getX(), e.getY());
    }

    private void renameSession(MainProject.SessionInfo sessionInfo) {
        String newName = JOptionPane.showInputDialog(SessionsDialog.this,
                                                     "Enter new name for session '" + sessionInfo.name() + "':",
                                                     sessionInfo.name());
        if (newName != null && !newName.trim().isBlank()) {
            contextManager.renameSessionAsync(sessionInfo.id(), newName.trim()).thenRun(() ->
                SwingUtilities.invokeLater(() -> {
                    refreshSessionsTable();
                    historyOutputPanel.updateSessionComboBox();
                })
            );
        }
    }

    @Override
    public void dispose() {
        // Clean up MOP resources
        if (markdownOutputPanel != null) {
            markdownOutputPanel.dispose();
        }
        super.dispose();
    }
}
