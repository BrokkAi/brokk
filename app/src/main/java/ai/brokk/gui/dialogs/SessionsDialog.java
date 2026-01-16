package ai.brokk.gui.dialogs;

import static ai.brokk.SessionManager.SessionInfo;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.ContextManager;
import ai.brokk.context.Context;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.WorkspaceItemsChipPanel;
import ai.brokk.gui.components.LoadingTextBox;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.history.HistoryTable;
import ai.brokk.gui.mop.MarkdownOutputPanel;
import ai.brokk.gui.util.GitDiffUiUtil;
import ai.brokk.project.MainProject;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import org.jetbrains.annotations.Nullable;

/** Modal dialog for managing sessions with Activity log, Workspace panel, and MOP preview */
public class SessionsDialog extends BaseThemedDialog {
    private static final int SEARCH_DEBOUNCE_DELAY = 300;
    private final Chrome chrome;
    private final ContextManager contextManager;

    // Column index constants
    // Sessions table model: [Active, Session Name, Date, SessionInfo]
    private static final int COL_ACTIVE = 0;
    private static final int COL_NAME = 1;
    private static final int COL_DATE = 2;
    private static final int COL_INFO = 3; // hidden SessionInfo column

    // Sessions table components
    private JTable sessionsTable;
    private DefaultTableModel sessionsTableModel;
    private LoadingTextBox searchBox;
    private MaterialButton closeButton;
    private Timer searchDebounceTimer;

    // Activity history components
    private HistoryTable historyTable;

    // Preview components
    private WorkspaceItemsChipPanel workspaceItemsChipPanel;
    private MarkdownOutputPanel markdownOutputPanel;
    private JScrollPane markdownScrollPane;
    private @Nullable Context selectedActivityContext;

    public SessionsDialog(Chrome chrome, ContextManager contextManager) {
        super(chrome.getFrame(), "Manage Sessions", Dialog.ModalityType.APPLICATION_MODAL);
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
        searchBox = new LoadingTextBox("Search sessions", 20, chrome);

        // Initialize sessions table model with Active, Session Name, Date, and hidden SessionInfo columns
        sessionsTableModel = new DefaultTableModel(new Object[] {"Active", "Session Name", "Date", "SessionInfo"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        // Initialize sessions table
        sessionsTable = new JTable(sessionsTableModel) {
            @Override
            public String getToolTipText(MouseEvent event) {
                Point p = event.getPoint();
                int rowIndex = rowAtPoint(p);
                if (rowIndex >= 0 && rowIndex < getRowCount()) {
                    SessionInfo sessionInfo = (SessionInfo) sessionsTableModel.getValueAt(rowIndex, COL_INFO);
                    return "Last modified: "
                            + DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                                    .withZone(ZoneId.systemDefault())
                                    .format(Instant.ofEpochMilli(sessionInfo.modified()));
                }
                return super.getToolTipText(event);
            }
        };
        sessionsTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        sessionsTable.setTableHeader(null);

        // Set up column renderers for sessions table
        sessionsTable.getColumnModel().getColumn(COL_ACTIVE).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel label =
                        (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                label.setHorizontalAlignment(JLabel.CENTER);
                return label;
            }
        });

        // Initialize history table component
        historyTable = new HistoryTable(contextManager, chrome);

        // Initialize workspace panel for preview
        workspaceItemsChipPanel = new WorkspaceItemsChipPanel(chrome);
        workspaceItemsChipPanel.setReadOnly(true);

        // Initialize markdown output panel for preview
        markdownOutputPanel = new MarkdownOutputPanel();
        markdownOutputPanel.setContextForLookups(contextManager, chrome);
        markdownOutputPanel.updateTheme(MainProject.getTheme());
        markdownScrollPane = new JScrollPane(markdownOutputPanel);
        markdownScrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        markdownScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        // Initialize buttons
        closeButton = new MaterialButton("Close");

        // Initialize timer
        searchDebounceTimer = new Timer(SEARCH_DEBOUNCE_DELAY, e -> refreshSessionsTable());
        searchDebounceTimer.setRepeats(false);
    }

    private void layoutComponents() {
        JPanel root = getContentRoot();
        root.setLayout(new BorderLayout());

        // Create sessions panel
        JPanel sessionsPanel = new JPanel(new BorderLayout());
        sessionsPanel.setBorder(BorderFactory.createTitledBorder("Sessions"));
        sessionsPanel.add(searchBox, BorderLayout.NORTH);
        JScrollPane sessionsScrollPane = new JScrollPane(sessionsTable);
        sessionsPanel.add(sessionsScrollPane, BorderLayout.CENTER);

        // Create activity panel
        JPanel activityPanel = new JPanel(new BorderLayout());
        activityPanel.setBorder(BorderFactory.createTitledBorder("Activity"));
        activityPanel.add(historyTable, BorderLayout.CENTER);

        // Create workspace panel
        JPanel workspacePanelContainer = new JPanel(new BorderLayout());
        workspacePanelContainer.setBorder(BorderFactory.createTitledBorder("Context"));
        JScrollPane workspaceScrollPane = new JScrollPane(workspaceItemsChipPanel);
        workspaceScrollPane.setBorder(BorderFactory.createEmptyBorder());
        workspaceScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        workspacePanelContainer.add(workspaceScrollPane, BorderLayout.CENTER);

        // Create MOP panel
        JPanel mopPanel = new JPanel(new BorderLayout());
        mopPanel.setBorder(BorderFactory.createTitledBorder("Output"));
        mopPanel.add(markdownScrollPane, BorderLayout.CENTER);

        // Create top horizontal split for Sessions and Activity
        JSplitPane sessionsActivitySplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sessionsPanel, activityPanel);
        sessionsActivitySplit.setResizeWeight(0.5); // Equal initial distribution

        // Create left vertical split: (Sessions + Activity) on top, Workspace below
        JSplitPane leftVerticalSplit =
                new JSplitPane(JSplitPane.VERTICAL_SPLIT, sessionsActivitySplit, workspacePanelContainer);
        leftVerticalSplit.setResizeWeight(0.75); // Top gets 75%, workspace gets 25%

        // Create main horizontal split: Left (Sessions/Activity/Workspace) and Right (Output)
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftVerticalSplit, mopPanel);
        mainSplit.setResizeWeight(0.6); // Left side gets 60%, Output gets 40%

        // Set divider locations after the dialog is shown
        SwingUtilities.invokeLater(() -> {
            int totalWidth = mainSplit.getWidth();
            if (totalWidth > 0) {
                // Main split: 60% left, 40% right
                mainSplit.setDividerLocation((int) (totalWidth * 0.6));
                // Within the left side, split Sessions and Activity 50/50
                sessionsActivitySplit.setDividerLocation(mainSplit.getDividerLocation() / 2);
            }
        });

        // Create button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 10, 10));
        buttonPanel.add(closeButton);

        // Add components to contentRoot
        root.add(mainSplit, BorderLayout.CENTER);
        root.add(buttonPanel, BorderLayout.SOUTH);
    }

    private void setupEventHandlers() {
        // Session selection listener - load session history instead of switching
        sessionsTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            int[] selected = sessionsTable.getSelectedRows();
            if (selected.length == 1) {
                var info = (SessionInfo) sessionsTableModel.getValueAt(selected[0], COL_INFO);
                loadSessionHistory(info.id());
            } else { // multi-select: clear preview panels
                historyTable.getModel().setRowCount(0);
                clearPreviewPanels();
            }
        });

        // Activity selection listener - update workspace and MOP
        historyTable.addSelectionListener(ctx -> {
            selectedActivityContext = ctx;
            updatePreviewPanels(ctx);
        });
        historyTable.addSelectionClearedListener(() -> {
            selectedActivityContext = null;
            clearPreviewPanels();
        });

        historyTable.addContextMenuListener(this::showActivityContextMenu);

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
                        SessionInfo sessionInfo = (SessionInfo) sessionsTableModel.getValueAt(row, COL_INFO);
                        renameSession(sessionInfo);
                    }
                }
            }
        });

        // Search box listener with debounce
        searchBox.addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                scheduleSessionsTableRefresh();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                scheduleSessionsTableRefresh();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                scheduleSessionsTableRefresh();
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
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
    }

    private void scheduleSessionsTableRefresh() {
        searchDebounceTimer.restart();
    }

    private void loadSessionHistory(UUID sessionId) {
        // Clear current preview panels
        clearPreviewPanels();

        // Load session history asynchronously
        contextManager
                .loadSessionHistoryAsync(sessionId)
                .thenAccept(history -> {
                    SwingUtilities.invokeLater(() -> {
                        historyTable.setHistory(history, null);
                    });
                })
                .exceptionally(throwable -> {
                    SwingUtilities.invokeLater(() -> {
                        chrome.toolError("Failed to load session history: " + throwable.getMessage());
                        historyTable.getModel().setRowCount(0);
                    });
                    return null;
                });
    }

    private void updatePreviewPanels(Context context) {
        // Update workspace panel with selected context
        workspaceItemsChipPanel.setFragmentsForContext(context);

        // Update MOP with task history if available
        var taskHistory = context.getTaskHistory();
        if (taskHistory.isEmpty()) {
            // Fall back to parsed output for contexts that are not part of a task history
            if (context.getParsedOutput() != null) {
                markdownOutputPanel.setMainThenHistoryAsync(
                        context.getParsedOutput().messages(), List.of());
            } else {
                markdownOutputPanel.clear(); // clears main view, history, and cache
            }
        } else {
            var history = taskHistory.subList(0, taskHistory.size() - 1);
            var main = taskHistory.getLast();
            markdownOutputPanel.setMainThenHistoryAsync(main, history);
        }
    }

    private void clearPreviewPanels() {
        // Clear workspace panel
        workspaceItemsChipPanel.setFragments(List.of());

        // Clear MOP
        markdownOutputPanel.clear();
    }

    private void showActivityContextMenu(Context context, MouseEvent e) {
        JPopupMenu popup = new JPopupMenu();

        JMenuItem copyContextItem = new JMenuItem("Copy Context");
        copyContextItem.addActionListener(event -> {
            contextManager.resetContextToAsync(context);
            dispose();
        });
        popup.add(copyContextItem);

        JMenuItem copyContextWithHistoryItem = new JMenuItem("Copy Context + History");
        copyContextWithHistoryItem.addActionListener(event -> {
            contextManager.resetContextToIncludingHistoryAsync(context);
            dispose();
        });
        popup.add(copyContextWithHistoryItem);

        popup.addSeparator();

        JMenuItem newSessionFromWorkspaceItem = new JMenuItem("New Session from Workspace");
        newSessionFromWorkspaceItem.addActionListener(event -> {
            contextManager
                    .createSessionFromContextAsync(context, ContextManager.DEFAULT_SESSION_NAME)
                    .thenRun(() -> {
                        chrome.getRightPanel().updateSessionComboBox();
                        dispose();
                    })
                    .exceptionally(ex -> {
                        chrome.toolError("Failed to create new session from workspace: " + ex.getMessage());
                        return null;
                    });
        });
        popup.add(newSessionFromWorkspaceItem);

        // Register popup with theme manager
        chrome.getTheme().registerPopupMenu(popup);

        popup.show(e.getComponent(), e.getX(), e.getY());
    }

    public void refreshSessionsTable() {
        sessionsTableModel.setRowCount(0);
        List<SessionInfo> sessions =
                contextManager.getProject().getSessionManager().listSessions();

        String searchText = searchBox.getText().toLowerCase(Locale.ROOT);
        if (!searchText.isEmpty()) {
            sessions = sessions.stream()
                    .filter(s -> s.name().toLowerCase(Locale.ROOT).contains(searchText))
                    .collect(Collectors.toList());
        }

        sessions.sort(Comparator.comparingLong(SessionInfo::modified).reversed()); // Sort newest first

        UUID currentSessionId = contextManager.getCurrentSessionId();
        for (var session : sessions) {
            String active = session.id().equals(currentSessionId) ? "✓" : "";
            var date = GitDiffUiUtil.formatRelativeDate(
                    Instant.ofEpochMilli(session.modified()), LocalDate.now(ZoneId.systemDefault()));
            sessionsTableModel.addRow(new Object[] {active, session.name(), date, session});
        }

        // Hide the "SessionInfo" column
        sessionsTable.getColumnModel().getColumn(COL_INFO).setMinWidth(0);
        sessionsTable.getColumnModel().getColumn(COL_INFO).setMaxWidth(0);
        sessionsTable.getColumnModel().getColumn(COL_INFO).setWidth(0);

        // Set column widths for sessions table
        sessionsTable.getColumnModel().getColumn(COL_ACTIVE).setPreferredWidth(20);
        sessionsTable.getColumnModel().getColumn(COL_ACTIVE).setMaxWidth(20);
        sessionsTable.getColumnModel().getColumn(COL_NAME).setPreferredWidth(200);
        sessionsTable.getColumnModel().getColumn(COL_DATE).setPreferredWidth(110);
        sessionsTable.getColumnModel().getColumn(COL_DATE).setMaxWidth(110);

        // Select current session and load its history
        for (int i = 0; i < sessionsTableModel.getRowCount(); i++) {
            SessionInfo rowInfo = (SessionInfo) sessionsTableModel.getValueAt(i, COL_INFO);
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
        var selectedSessions = Arrays.stream(selectedRows)
                .mapToObj(r -> (SessionInfo) sessionsTableModel.getValueAt(r, COL_INFO))
                .toList();

        JPopupMenu popup = new JPopupMenu();

        /* ---------- single-selection items ---------- */
        if (selectedSessions.size() == 1) {
            var sessionInfo = selectedSessions.getFirst();

            JMenuItem setActiveItem = new JMenuItem("Set as Active");
            setActiveItem.setEnabled(!sessionInfo.id().equals(contextManager.getCurrentSessionId()));
            setActiveItem.addActionListener(
                    ev -> contextManager.switchSessionAsync(sessionInfo.id()).thenRun(() -> {
                        SwingUtilities.invokeLater(this::refreshSessionsTable);
                        contextManager.getProject().getMainProject().sessionsListChanged();
                    }));
            popup.add(setActiveItem);
            popup.addSeparator();

            JMenuItem renameItem = new JMenuItem("Rename");
            renameItem.addActionListener(ev -> renameSession(sessionInfo));
            popup.add(renameItem);
        }

        /* ---------- delete (single or multi) ---------- */
        JMenuItem deleteItem = new JMenuItem(selectedSessions.size() == 1 ? "Delete" : "Delete Selected");
        deleteItem.addActionListener(ev -> {
            int confirm = chrome.showConfirmDialog(
                    SessionsDialog.this,
                    "Are you sure you want to delete the selected session(s)?",
                    "Confirm Delete",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) {
                var partitionedSessions = selectedSessions.stream()
                        .collect(Collectors.partitioningBy(s -> contextManager
                                .getProject()
                                .getSessionRegistry()
                                .isSessionActiveElsewhere(
                                        contextManager.getProject().getRoot(), s.id())));
                // partitioning by boolean always returns mappings for both true and false keys
                var activeSessions = castNonNull(partitionedSessions.get(true));
                var deletableSessions = castNonNull(partitionedSessions.get(false));

                if (!activeSessions.isEmpty()) {
                    var sessionNames =
                            activeSessions.stream().map(SessionInfo::name).collect(Collectors.joining(", "));
                    chrome.toolError(
                            "Cannot delete sessions active in other worktrees: " + sessionNames, "Sessions in use");
                }

                if (deletableSessions.isEmpty()) {
                    return;
                }

                var futures = new ArrayList<CompletableFuture<?>>();
                for (var s : deletableSessions) {
                    futures.add(contextManager.deleteSessionAsync(s.id()));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .whenComplete((Void v, @Nullable Throwable t) -> {
                            SwingUtilities.invokeLater(this::refreshSessionsTable);
                            contextManager.getProject().getMainProject().sessionsListChanged();
                            if (t != null) {
                                chrome.toolError("Error deleting session:\n" + t.getMessage());
                            }
                        });
            }
        });
        popup.add(deleteItem);

        /* ---------- duplicate (single or multi) ---------- */
        JMenuItem dupItem = new JMenuItem(selectedSessions.size() == 1 ? "Duplicate" : "Duplicate Selected");
        dupItem.addActionListener(ev -> {
            var futures = new ArrayList<CompletableFuture<?>>();
            for (var s : selectedSessions) {
                futures.add(contextManager.copySessionAsync(s.id(), s.name()));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenRun(() -> {
                SwingUtilities.invokeLater(this::refreshSessionsTable);
                contextManager.getProject().getMainProject().sessionsListChanged();
            });
        });
        popup.add(dupItem);

        // Register popup with theme manager
        chrome.getTheme().registerPopupMenu(popup);

        popup.show(sessionsTable, e.getX(), e.getY());
    }

    private void renameSession(SessionInfo sessionInfo) {
        String newName = JOptionPane.showInputDialog(
                SessionsDialog.this, "Enter new name for session '" + sessionInfo.name() + "':", sessionInfo.name());
        if (newName != null && !newName.trim().isBlank()) {
            contextManager
                    .renameSessionAsync(sessionInfo.id(), CompletableFuture.completedFuture(newName.trim()))
                    .thenRun(() -> {
                        SwingUtilities.invokeLater(this::refreshSessionsTable);
                        contextManager.getProject().getMainProject().sessionsListChanged();
                    });
        }
    }

    @Override
    public void dispose() {
        // Clean up MOP resources
        markdownOutputPanel.dispose();
        super.dispose();
    }

    public static void renameCurrentSession(Component parent, Chrome chrome, ContextManager contextManager) {
        var sessionManager = contextManager.getProject().getSessionManager();
        var currentId = contextManager.getCurrentSessionId();
        var maybeInfo = sessionManager.listSessions().stream()
                .filter(s -> s.id().equals(currentId))
                .findFirst();
        if (maybeInfo.isEmpty()) {
            chrome.toolError("Current session not found");
            return;
        }
        var info = maybeInfo.get();
        String newName =
                JOptionPane.showInputDialog(parent, "Enter new name for session '" + info.name() + "':", info.name());
        if (newName != null && !newName.trim().isBlank()) {
            contextManager
                    .renameSessionAsync(info.id(), CompletableFuture.completedFuture(newName.trim()))
                    .thenRun(() -> contextManager.getProject().getMainProject().sessionsListChanged());
        }
    }

    public static void deleteCurrentSession(Component parent, Chrome chrome, ContextManager contextManager) {
        var sessionManager = contextManager.getProject().getSessionManager();
        var currentId = contextManager.getCurrentSessionId();
        var maybeInfo = sessionManager.listSessions().stream()
                .filter(s -> s.id().equals(currentId))
                .findFirst();
        if (maybeInfo.isEmpty()) {
            chrome.toolError("Current session not found");
            return;
        }
        var info = maybeInfo.get();
        int confirm = chrome.showConfirmDialog(
                parent,
                "Are you sure you want to delete the current session?",
                "Confirm Delete",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (confirm == JOptionPane.YES_OPTION) {
            contextManager
                    .deleteSessionAsync(info.id())
                    .thenRun(() -> contextManager.getProject().getMainProject().sessionsListChanged());
        }
    }
}
