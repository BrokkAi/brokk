package io.github.jbellis.brokk.gui;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.context.ContextHistory;
import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.UUID;

/**
 * A component that combines the context history panel with the output panel using BorderLayout.
 */
public class HistoryOutputPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(HistoryOutputPanel.class);

    private final Chrome chrome;
    private final ContextManager contextManager;
    private JTable historyTable;
    private DefaultTableModel historyModel;
    private JButton undoButton;
    private JButton redoButton;
    private JComboBox<Project.SessionInfo> sessionComboBox;
    private JButton newSessionButton;
    private JButton manageSessionsButton;

    // Output components
    private MarkdownOutputPanel llmStreamArea;
    private JScrollPane llmScrollPane;
    // systemArea, systemScrollPane, commandResultLabel removed
    private JTextArea captureDescriptionArea;
    private JButton copyButton;

    /**
     * Constructs a new HistoryOutputPane.
     *
     * @param chrome The parent Chrome instance
     * @param contextManager The context manager
     */
    public HistoryOutputPanel(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout()); // Use BorderLayout
        this.chrome = chrome;
        this.contextManager = contextManager;

        // commandResultLabel initialization removed

        // Build Output components (Center)
        var centerPanel = buildCenterOutputPanel();
        add(centerPanel, BorderLayout.CENTER);

        // Build session controls and activity panel (East)
        var sessionControlsPanel = buildSessionControlsPanel();
        var activityPanel = buildActivityPanel();

        // Create main history panel with session controls above activity
        var historyPanel = new JPanel(new BorderLayout());
        historyPanel.add(sessionControlsPanel, BorderLayout.NORTH);
        historyPanel.add(activityPanel, BorderLayout.CENTER);

        // Calculate preferred width to match old panel size
        int preferredWidth = 230;
        var preferredSize = new Dimension(preferredWidth, historyPanel.getPreferredSize().height);
        historyPanel.setPreferredSize(preferredSize);
        historyPanel.setMinimumSize(preferredSize);
        historyPanel.setMaximumSize(new Dimension(preferredWidth, Integer.MAX_VALUE));

        add(historyPanel, BorderLayout.EAST);

        // Set minimum sizes for the main panel
        setMinimumSize(new Dimension(300, 200)); // Example minimum size
    }

    private JPanel buildCenterOutputPanel() {
        // Build LLM streaming area
        llmScrollPane = buildLLMStreamScrollPane();

        // Build capture output panel
        var capturePanel = buildCaptureOutputPanel();

        // systemScrollPane removed
        // topInfoPanel removed

        // Output panel with LLM stream
        var outputPanel = new JPanel(new BorderLayout());
        outputPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Output",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));
        outputPanel.add(llmScrollPane, BorderLayout.CENTER);
        outputPanel.add(capturePanel, BorderLayout.SOUTH); // Add capture panel below LLM output

        // Container for the center section (just the outputPanel now)
        var centerContainer = new JPanel(new BorderLayout());
        // Removed topInfoPanel
        centerContainer.add(outputPanel, BorderLayout.CENTER); // Output takes the entire space
        centerContainer.setMinimumSize(new Dimension(200, 0)); // Minimum width for output area

        return centerContainer;
    }

    /**
     * Builds the session controls panel with combo box and buttons
     */
    private JPanel buildSessionControlsPanel() {
        var panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Sessions",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        // Session combo box
        sessionComboBox = new JComboBox<>();
        sessionComboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                         boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Project.SessionInfo sessionInfo) {
                    setText(sessionInfo.name());
                }
                return this;
            }
        });
        
        // Add selection listener for session switching
        sessionComboBox.addActionListener(e -> {
            var selectedSession = (Project.SessionInfo) sessionComboBox.getSelectedItem();
            if (selectedSession != null && !selectedSession.id().equals(contextManager.getCurrentSessionId())) {
                contextManager.switchSessionAsync(selectedSession.id());
            }
        });

        // Buttons panel
        var buttonsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        
        newSessionButton = new JButton("New");
        newSessionButton.setToolTipText("Create a new session");
        var newSessionSize = new Dimension(100, newSessionButton.getPreferredSize().height);
        newSessionButton.setPreferredSize(newSessionSize);
        newSessionButton.setMinimumSize(newSessionSize);
        newSessionButton.setMaximumSize(newSessionSize);
        newSessionButton.addActionListener(e -> {
            contextManager.createNewSessionAsync("New Session").thenRun(() ->
                SwingUtilities.invokeLater(this::updateSessionComboBox)
            );
        });

        manageSessionsButton = new JButton("Manage");
        manageSessionsButton.setToolTipText("Manage sessions (rename, delete, copy)");
        var manageSessionSize = new Dimension(100, manageSessionsButton.getPreferredSize().height);
        manageSessionsButton.setPreferredSize(manageSessionSize);
        manageSessionsButton.setMinimumSize(manageSessionSize);
        manageSessionsButton.setMaximumSize(manageSessionSize);
        manageSessionsButton.addActionListener(e -> {
            var dialog = new ManageSessionsDialog(chrome, contextManager);
            dialog.setVisible(true);
        });

        buttonsPanel.add(newSessionButton);
        buttonsPanel.add(manageSessionsButton);

        panel.add(sessionComboBox, BorderLayout.CENTER);
        panel.add(buttonsPanel, BorderLayout.SOUTH);

        // Initialize with current sessions
        updateSessionComboBox();

        return panel;
    }

    /**
     * Updates the session combo box with current sessions and selects the active one
     */
    private void updateSessionComboBox() {
        SwingUtilities.invokeLater(() -> {
            // Store current selection to avoid triggering change events
            var currentSelection = sessionComboBox.getSelectedItem();
            
            // Remove action listener temporarily
            var listeners = sessionComboBox.getActionListeners();
            for (var listener : listeners) {
                sessionComboBox.removeActionListener(listener);
            }
            
            // Clear and repopulate
            sessionComboBox.removeAllItems();
            var sessions = contextManager.getProject().listSessions();
            sessions.sort(java.util.Comparator.comparingLong(Project.SessionInfo::modified).reversed()); // Most recent first
            
            for (var session : sessions) {
                sessionComboBox.addItem(session);
            }
            
            // Select current session
            var currentSessionId = contextManager.getCurrentSessionId();
            for (int i = 0; i < sessionComboBox.getItemCount(); i++) {
                var sessionInfo = sessionComboBox.getItemAt(i);
                if (sessionInfo.id().equals(currentSessionId)) {
                    sessionComboBox.setSelectedIndex(i);
                    break;
                }
            }
            
            // Restore action listeners
            for (var listener : listeners) {
                sessionComboBox.addActionListener(listener);
            }
        });
    }

    /**
     * Builds the Activity history panel that shows past contexts
     */
    private JPanel buildActivityPanel() {
        // Create history panel
        var panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Activity",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        // Create table model with columns - first two columns are visible, third is hidden
        historyModel = new DefaultTableModel(
                new Object[]{"", "Action", "Context"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        historyTable = new JTable(historyModel);
        historyTable.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Remove table header
        historyTable.setTableHeader(null);

        // Set up tooltip renderer for description column (index 1)
        historyTable.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                          boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel label = (JLabel)super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column);

                // Set the tooltip to show the full text
                if (value != null) {
                    label.setToolTipText(value.toString());
                }

                return label;
            }
        });

        // Set up emoji renderer for first column
        historyTable.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                          boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel label = (JLabel)super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column);

                // Center-align the emoji
                label.setHorizontalAlignment(JLabel.CENTER);

                return label;
            }
        });

        // Add selection listener to preview context
        historyTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = historyTable.getSelectedRow();
                if (row >= 0 && row < historyTable.getRowCount()) {
                    // Get the context object from the hidden third column
                    var ctx = (Context) historyModel.getValueAt(row, 2);
                    if (ctx != null) { // Check for null, though unlikely with current logic
                        contextManager.setSelectedContext(ctx);
                        chrome.setContext(ctx);
                    }
                }
            }
        });

         // Add mouse listener for right-click context menu and double-click action
         historyTable.addMouseListener(new MouseAdapter() {
             @Override
             public void mouseClicked(MouseEvent e) {
                 if (e.getClickCount() == 2) { // Double-click
                     int row = historyTable.rowAtPoint(e.getPoint());
                     if (row >= 0) {
                         Context context = (Context) historyModel.getValueAt(row, 2);
                         var output = context.getParsedOutput();
                         if (output != null) {
                             // Open in new window
                             new OutputWindow(HistoryOutputPanel.this, output,
                                              chrome.themeManager != null && chrome.themeManager.isDarkTheme());
                         }
                     }
                 }
             }

             @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextHistoryPopupMenu(e);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextHistoryPopupMenu(e);
                }
            }
        });

        // Adjust column widths - set emoji column width and hide the context object column
        historyTable.getColumnModel().getColumn(0).setPreferredWidth(30);
        historyTable.getColumnModel().getColumn(0).setMinWidth(30);
        historyTable.getColumnModel().getColumn(0).setMaxWidth(30);
        historyTable.getColumnModel().getColumn(1).setPreferredWidth(150);
        historyTable.getColumnModel().getColumn(2).setMinWidth(0);
        historyTable.getColumnModel().getColumn(2).setMaxWidth(0);
        historyTable.getColumnModel().getColumn(2).setWidth(0);

        // Add table to scroll pane with AutoScroller
        var scrollPane = new JScrollPane(historyTable);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        AutoScroller.install(scrollPane);
        BorderUtils.addFocusBorder(scrollPane, historyTable);

        // Add MouseListener to scrollPane's viewport to request focus for historyTable
        scrollPane.getViewport().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getSource() == scrollPane.getViewport()) { // Click was on the viewport itself
                    historyTable.requestFocusInWindow();
                }
            }
        });

        // Add undo/redo buttons at the bottom, side by side
        var buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS)); // Horizontal layout
        buttonPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
        buttonPanel.add(Box.createHorizontalGlue()); // Push buttons to center

        undoButton = new JButton("Undo");
        undoButton.setMnemonic(KeyEvent.VK_Z);
        undoButton.setToolTipText("Undo the most recent history entry");
        var undoSize = new Dimension(100, undoButton.getPreferredSize().height);
        undoButton.setPreferredSize(undoSize);
        undoButton.setMinimumSize(undoSize);
        undoButton.setMaximumSize(undoSize);
        undoButton.addActionListener(e -> {
            contextManager.undoContextAsync();
        });

        redoButton = new JButton("Redo");
        redoButton.setMnemonic(KeyEvent.VK_Y);
        redoButton.setToolTipText("Redo the most recently undone entry");
        var redoSize = new Dimension(100, redoButton.getPreferredSize().height);
        redoButton.setPreferredSize(redoSize);
        redoButton.setMinimumSize(redoSize);
        redoButton.setMaximumSize(redoSize);
        redoButton.addActionListener(e -> {
            contextManager.redoContextAsync();
        });

        buttonPanel.add(undoButton);
        buttonPanel.add(Box.createRigidArea(new Dimension(5, 0))); // Add spacing
        buttonPanel.add(redoButton);
        buttonPanel.add(Box.createHorizontalGlue()); // Push buttons to center

        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        // Calculate preferred width for the history panel
        // Table width (30 + 150) + scrollbar (~20) + padding = ~210
        // Button width (100 + 100 + 5) + padding = ~215
        int preferredWidth = 230; // Give a bit more room
        var preferredSize = new Dimension(preferredWidth, panel.getPreferredSize().height);
        panel.setPreferredSize(preferredSize);
        panel.setMinimumSize(preferredSize);
        panel.setMaximumSize(new Dimension(preferredWidth, Integer.MAX_VALUE)); // Fixed width, flexible height

        updateUndoRedoButtonStates();

        return panel;
    }

    /**
     * Updates the enabled state of the local Undo and Redo buttons
     * based on the current state of the ContextHistory.
     */
    public void updateUndoRedoButtonStates() {
        SwingUtilities.invokeLater(() -> {
            if (contextManager != null && contextManager.getContextHistory() != null) {
                undoButton.setEnabled(contextManager.getContextHistory().hasUndoStates());
                redoButton.setEnabled(contextManager.getContextHistory().hasRedoStates());
            } else {
                undoButton.setEnabled(false);
                redoButton.setEnabled(false);
            }
        });
    }

    /**
     * Shows the context menu for the context history table
     */
    private void showContextHistoryPopupMenu(MouseEvent e) {
        int row = historyTable.rowAtPoint(e.getPoint());
        if (row < 0) return;

        // Select the row under the cursor
        historyTable.setRowSelectionInterval(row, row);

        // Get the context from the selected row
        Context context = (Context)historyModel.getValueAt(row, 2);

        // Create popup menu
        JPopupMenu popup = new JPopupMenu();

        JMenuItem undoToHereItem = new JMenuItem("Undo to here");
        undoToHereItem.addActionListener(event -> undoHistoryUntil(context));
        popup.add(undoToHereItem);

        JMenuItem resetToHereItem = new JMenuItem("Copy Workspace");
        resetToHereItem.addActionListener(event -> resetContextTo(context));
        popup.add(resetToHereItem);

        JMenuItem resetToHereIncludingHistoryItem = new JMenuItem("Copy Workspace with History");
        resetToHereIncludingHistoryItem.addActionListener(event -> resetContextToIncludingHistory(context));
        popup.add(resetToHereIncludingHistoryItem);

        // Register popup with theme manager
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(popup);
        }

        // Show popup menu
        popup.show(historyTable, e.getX(), e.getY());
    }

    /**
     * Restore context to a specific point in history
     */
    private void undoHistoryUntil(Context targetContext) {
        contextManager.undoContextUntilAsync(targetContext);
    }

    /**
     * Creates a new context based on the files and fragments from a historical context,
     * while preserving current conversation history
     */
    private void resetContextTo(Context targetContext) {
        contextManager.resetContextToAsync(targetContext);
    }

    /**
     * Creates a new context based on the files, fragments, and history from a historical context
     */
    private void resetContextToIncludingHistory(Context targetContext) {
        contextManager.resetContextToIncludingHistoryAsync(targetContext);
    }

    /**
     * Updates the context history table with the current context history, and selects the given context
     *
     * @param contextToSelect Context to select in the history table
     */
    public void updateHistoryTable(Context contextToSelect) {
        logger.debug("Updating context history table with context {}",
                     contextToSelect != null ? contextToSelect.getAction() : "null");

        SwingUtilities.invokeLater(() -> {
            historyModel.setRowCount(0);

            // Track which row to select
            int rowToSelect = -1;
            int currentRow = 0;

            // Add rows for each context in history
            for (var ctx : contextManager.getContextHistoryList()) {
                // Add emoji for AI responses, empty for user actions
                String emoji = (ctx.getParsedOutput() != null) ? "🤖" : "";
                historyModel.addRow(new Object[]{
                        emoji,
                        ctx.getAction(),
                        ctx // We store the actual context object in hidden column
                });

                // If this is the context we want to select, record its row
                if (ctx.equals(contextToSelect)) {
                    rowToSelect = currentRow;
                }
                currentRow++;
            }

            // Set selection - if no specific context to select, select the most recent (last) item
            if (rowToSelect >= 0) {
                historyTable.setRowSelectionInterval(rowToSelect, rowToSelect);
                historyTable.scrollRectToVisible(historyTable.getCellRect(rowToSelect, 0, true));
            } else if (historyModel.getRowCount() > 0) {
                // Select the most recent item (last row)
                int lastRow = historyModel.getRowCount() - 1;
                historyTable.setRowSelectionInterval(lastRow, lastRow);
                historyTable.scrollRectToVisible(historyTable.getCellRect(lastRow, 0, true));
            }

            // Update session combo box after table update
            updateSessionComboBox();
        });
    }

    /**
     * Returns the history table for selection checks
     *
     * @return The JTable containing context history
     */
    public JTable getHistoryTable() {
        return historyTable;
    }

    /**
     * Builds the LLM streaming area where markdown output is displayed
     */
    private JScrollPane buildLLMStreamScrollPane() {
        llmStreamArea = new MarkdownOutputPanel();

        // Wrap it in a scroll pane so it can scroll if content is large
        var jsp = new JScrollPane(llmStreamArea);
        jsp.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        jsp.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        AutoScroller.install(jsp);

        // Add a text change listener to update capture buttons
        llmStreamArea.addTextChangeListener(() -> chrome.updateCaptureButtons());

        return jsp;
    }

    // buildSystemMessagesArea removed

    // buildCommandResultLabel removed

    /**
     * Builds the "Capture Output" panel with a horizontal layout:
     * [Capture Text]
     */
    private JPanel buildCaptureOutputPanel() {
        var panel = new JPanel(new BorderLayout(5, 3));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));

        // Placeholder area in center - will get all extra space
        captureDescriptionArea = new JTextArea("");
        captureDescriptionArea.setEditable(false);
        captureDescriptionArea.setBackground(panel.getBackground());
        captureDescriptionArea.setBorder(null);
        captureDescriptionArea.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        captureDescriptionArea.setLineWrap(true);
        captureDescriptionArea.setWrapStyleWord(true);
        panel.add(captureDescriptionArea, BorderLayout.CENTER);

        // Buttons panel on the right
        var buttonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));

        // "Copy Text" button
        copyButton = new JButton("Copy");
        copyButton.setMnemonic(KeyEvent.VK_T);
        copyButton.setToolTipText("Copy the output to clipboard");
        copyButton.addActionListener(e -> {
            String text = llmStreamArea.getText();
            if (!text.isBlank()) {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new java.awt.datatransfer.StringSelection(text), null);
                chrome.toolErrorRaw("Copied to clipboard");
            }
        });
        // Set minimum size
        copyButton.setMinimumSize(copyButton.getPreferredSize());
        buttonsPanel.add(copyButton);

        // "Capture" button
        var captureButton = new JButton("Capture");
        captureButton.setMnemonic(KeyEvent.VK_C);
        captureButton.setToolTipText("Add the output to context");
        captureButton.addActionListener(e -> {
            contextManager.captureTextFromContextAsync();
        });
        // Set minimum size
        captureButton.setMinimumSize(captureButton.getPreferredSize());
        buttonsPanel.add(captureButton);

        // "Open in New Window" button
        var openWindowButton = new JButton("Open in New Window");
        openWindowButton.setMnemonic(KeyEvent.VK_W);
        openWindowButton.setToolTipText("Open the output in a new window");
        openWindowButton.addActionListener(e -> {
            var output = contextManager.selectedContext().getParsedOutput();
            if (output != null) {
                new OutputWindow(this, output, chrome.themeManager != null && chrome.themeManager.isDarkTheme());
            }
        });
        // Set minimum size
        openWindowButton.setMinimumSize(openWindowButton.getPreferredSize());
        buttonsPanel.add(openWindowButton);


        // Add buttons panel to the right
        panel.add(buttonsPanel, BorderLayout.EAST);

        return panel;
    }
    /**
     * Gets the current text from the LLM output area
     */
    public String getLlmOutputText() {
        return llmStreamArea.getText();
    }

    public List<ChatMessage> getLlmRawMessages() {
        return llmStreamArea.getRawMessages();
    }

    public void setLlmOutput(TaskEntry taskEntry) {
        llmStreamArea.setText(taskEntry);
    }

    public void setLlmOutput(ContextFragment.TaskFragment newOutput) {
        llmStreamArea.setText(newOutput);
    }
    
    /**
     * Sets the text in the LLM output area
     */
    public void setLlmOutputAndCompact(ContextFragment.TaskFragment output, boolean forceScrollToTop) {
        // this is called by the context selection listener, but when we just finished streaming a response
        // we don't want scroll-to-top behavior (forceScrollToTop will be false in this case)
        setLlmOutput(output);
        llmStreamArea.scheduleCompaction().thenRun(
                () -> {
                    if (forceScrollToTop) {
                        // Scroll to the top
                        SwingUtilities.invokeLater(() -> llmScrollPane.getVerticalScrollBar().setValue(0));
                    }
                }
        );
    }

    /**
     * Appends text to the LLM output area
     */
    public void appendLlmOutput(String text, ChatMessageType type) {
        llmStreamArea.append(text, type);
    }

    /**
     * Sets the enabled state of the copy text button
     */
    public void setCopyButtonEnabled(boolean enabled) {
        copyButton.setEnabled(enabled);
    }

    /**
     * Shows the loading spinner with a message in the Markdown area.
     */
    public void showSpinner(String message) {
        if (llmStreamArea != null) {
            llmStreamArea.showSpinner(message);
        }
    }

    /**
     * Hides the loading spinner in the Markdown area.
     */
    public void hideSpinner() {
        if (llmStreamArea != null) {
            llmStreamArea.hideSpinner();
        }
    }

    /**
     * Gets the LLM scroll pane
     */
    public JScrollPane getLlmScrollPane() {
        return llmScrollPane;
    }

    public MarkdownOutputPanel getLlmStreamArea() {
        return llmStreamArea;
    }

    public void clearLlmOutput() {
        llmStreamArea.clear();
    }

    /**
     * Sets the blocking state on the contained MarkdownOutputPanel.
     *
     * @param blocked true to prevent clear/reset, false otherwise.
     */
    public void setMarkdownOutputPanelBlocking(boolean blocked) {
        if (llmStreamArea != null) {
            llmStreamArea.setBlocking(blocked);
        } else {
            logger.warn("Attempted to set blocking state on null llmStreamArea");
        }
    }

    /**
     * Modal dialog for managing sessions with Activity log, Workspace panel, and MOP preview
     */
    private class ManageSessionsDialog extends JDialog {
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
        
        // Current session context for previewing
        private ContextHistory currentSessionHistory;
        private Context selectedActivityContext;

        public ManageSessionsDialog(Chrome chrome, ContextManager contextManager) {
            super(chrome.getFrame(), "Manage Sessions", true);
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
                        Project.SessionInfo sessionInfo = (Project.SessionInfo) sessionsTableModel.getValueAt(rowIndex, 2);
                        if (sessionInfo != null) {
                            return "Last modified: " + new java.util.Date(sessionInfo.modified()).toString();
                        }
                    }
                    return super.getToolTipText(event);
                }
            };
            sessionsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
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
                    if (value != null) {
                        label.setToolTipText(value.toString());
                    }
                    return label;
                }
            });
            
            // Set up emoji renderer for first column of activity table
            activityTable.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable table, Object value,
                                                              boolean isSelected, boolean hasFocus, int row, int column) {
                    JLabel label = (JLabel)super.getTableCellRendererComponent(
                            table, value, isSelected, hasFocus, row, column);
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
            markdownOutputPanel.updateTheme(chrome.themeManager != null && chrome.themeManager.isDarkTheme());
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
            
            // Create top row with Sessions (20%), Activity (40%), and MOP (40%) horizontal space
            JSplitPane topFirstSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sessionsPanel, activityPanel);
            topFirstSplit.setResizeWeight(1.0/3.0); // Sessions gets 20%, Activity gets 40%, so 20/(20+40) = 1/3
            
            JSplitPane topSecondSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, topFirstSplit, mopPanel);
            topSecondSplit.setResizeWeight(0.6); // Sessions+Activity get 60%, MOP gets 40%
            
            // Set divider locations after the dialog is shown to achieve 20%/40%/40% split
            SwingUtilities.invokeLater(() -> {
                int totalWidth = topSecondSplit.getWidth();
                if (totalWidth > 0) {
                    // Set first divider at 20% of the way (between Sessions and Activity)
                    topFirstSplit.setDividerLocation(totalWidth / 5);
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
                if (!e.getValueIsAdjusting() && sessionsTable.getSelectedRow() != -1) {
                    Project.SessionInfo selectedSessionInfo = (Project.SessionInfo) sessionsTableModel.getValueAt(sessionsTable.getSelectedRow(), 2);
                    loadSessionHistory(selectedSessionInfo.id());
                }
            });
            
            // Activity selection listener - update workspace and MOP
            activityTable.getSelectionModel().addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    int row = activityTable.getSelectedRow();
                    if (row >= 0 && row < activityTable.getRowCount()) {
                        selectedActivityContext = (Context) activityTableModel.getValueAt(row, 2);
                        if (selectedActivityContext != null) {
                            updatePreviewPanels(selectedActivityContext);
                        }
                    } else {
                        clearPreviewPanels();
                    }
                }
            });
            
            // Add mouse listener for right-click context menu on sessions table
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
                    currentSessionHistory = history;
                    populateActivityTable(history);
                });
            }).exceptionally(throwable -> {
                SwingUtilities.invokeLater(() -> {
                    chrome.toolErrorRaw("Failed to load session history: " + throwable.getMessage());
                    activityTableModel.setRowCount(0);
                });
                return null;
            });
        }
        
        private void populateActivityTable(ContextHistory history) {
            activityTableModel.setRowCount(0);
            
            if (history == null || history.getHistory().isEmpty()) {
                return;
            }
            
            // Add rows for each context in history
            for (var ctx : history.getHistory()) {
                // Add emoji for AI responses, empty for user actions
                String emoji = (ctx.getParsedOutput() != null) ? "🤖" : "";
                activityTableModel.addRow(new Object[]{
                        emoji,
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
            List<Project.SessionInfo> sessions = contextManager.getProject().listSessions();
            sessions.sort(java.util.Comparator.comparingLong(Project.SessionInfo::modified).reversed()); // Sort newest first

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
                Project.SessionInfo rowInfo = (Project.SessionInfo) sessionsTableModel.getValueAt(i, 2);
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
            
            sessionsTable.setRowSelectionInterval(row, row);
            Project.SessionInfo sessionInfo = (Project.SessionInfo) sessionsTableModel.getValueAt(row, 2);
            
            JPopupMenu popup = new JPopupMenu();

            JMenuItem setActiveItem = new JMenuItem("Set as Active");
            setActiveItem.setEnabled(!sessionInfo.id().equals(contextManager.getCurrentSessionId()));
            setActiveItem.addActionListener(event -> {
                contextManager.switchSessionAsync(sessionInfo.id()).thenRun(() ->
                    SwingUtilities.invokeLater(() -> {
                        refreshSessionsTable(); 
                        updateSessionComboBox(); 
                    })
                );
            });
            popup.add(setActiveItem);
            popup.addSeparator(); 
            
            JMenuItem renameItem = new JMenuItem("Rename");
            renameItem.addActionListener(event -> {
                String newName = JOptionPane.showInputDialog(ManageSessionsDialog.this, 
                    "Enter new name for session '" + sessionInfo.name() + "':", 
                    sessionInfo.name());
                if (newName != null && !newName.trim().isBlank()) {
                    contextManager.renameSessionAsync(sessionInfo.id(), newName.trim()).thenRun(() ->
                        SwingUtilities.invokeLater(() -> {
                            refreshSessionsTable();
                            updateSessionComboBox();
                        })
                    );
                }
            });
            popup.add(renameItem);

            JMenuItem deleteItem = new JMenuItem("Delete");
            deleteItem.addActionListener(event -> {
                int confirm = JOptionPane.showConfirmDialog(ManageSessionsDialog.this, 
                    "Are you sure you want to delete session '" + sessionInfo.name() + "'?", 
                    "Confirm Delete", 
                    JOptionPane.YES_NO_OPTION, 
                    JOptionPane.WARNING_MESSAGE);
                if (confirm == JOptionPane.YES_OPTION) {
                    contextManager.deleteSessionAsync(sessionInfo.id()).thenRun(() -> 
                        SwingUtilities.invokeLater(() -> {
                            refreshSessionsTable();
                            updateSessionComboBox();
                        }));
                }
            });
            popup.add(deleteItem);
            
            JMenuItem copyItem = new JMenuItem("Copy");
            copyItem.addActionListener(event -> {
                contextManager.copySessionAsync(sessionInfo.id(), sessionInfo.name()).thenRun(() -> 
                    SwingUtilities.invokeLater(() -> {
                        refreshSessionsTable();
                        updateSessionComboBox();
                    }));
            });
            popup.add(copyItem);
            
            // Register popup with theme manager
            if (chrome.themeManager != null) {
                chrome.themeManager.registerPopupMenu(popup);
            }
            
            popup.show(sessionsTable, e.getX(), e.getY());
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

    /**
     * Inner class representing a detached window for viewing output text
     */
    private static class OutputWindow extends JFrame {
        private final Project project;
        /**
         * Creates a new output window with the given text content
         *
         * @param parentPanel The parent HistoryOutputPanel
         * @param output The messages (ai, user, ...) to display
         * @param isDark Whether to use dark theme
         */
        public OutputWindow(HistoryOutputPanel parentPanel, ContextFragment.TaskFragment output, boolean isDark) {
            super("Output"); // Call superclass constructor first
                
                // Set icon from Chrome.newFrame
                try {
                    var iconUrl = Chrome.class.getResource(Brokk.ICON_RESOURCE);
                    if (iconUrl != null) {
                        var icon = new ImageIcon(iconUrl);
                        setIconImage(icon.getImage());
                    }
                } catch (Exception e) {
                    // Silently ignore icon setting failures in child windows
                }
                
                this.project = parentPanel.contextManager.getProject(); // Get project reference
                setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

            // Create markdown panel with the text
            var outputPanel = new MarkdownOutputPanel();
            var scrollPane = new JScrollPane(outputPanel);
            outputPanel.updateTheme(isDark);
            outputPanel.setText(output);
            outputPanel.scheduleCompaction().thenRun(() -> SwingUtilities.invokeLater(() -> scrollPane.getViewport().setViewPosition(new Point(0, 0))));;

            // Add to a scroll pane
            scrollPane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            // Add the scroll pane to the frame
            add(scrollPane);

            // Load saved size and position, or use defaults
            var bounds = project.getOutputWindowBounds();
            if (bounds.width <= 0 || bounds.height <= 0) {
                setSize(800, 600); // Default size
                setLocationRelativeTo(parentPanel); // Center relative to parent
            } else {
                setSize(bounds.width, bounds.height);
                if (bounds.x >= 0 && bounds.y >= 0 && parentPanel.chrome.isPositionOnScreen(bounds.x, bounds.y)) {
                    setLocation(bounds.x, bounds.y);
                } else {
                    setLocationRelativeTo(parentPanel); // Center relative to parent if off-screen
                }
            }

            // Add listeners to save position/size on change
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    project.saveOutputWindowBounds(OutputWindow.this);
                }

                @Override
                public void componentMoved(java.awt.event.ComponentEvent e) {
                    project.saveOutputWindowBounds(OutputWindow.this);
                }
            });

            // Add ESC key binding to close the window
            var rootPane = getRootPane();
            var actionMap = rootPane.getActionMap();
            var inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);

            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeWindow");
            actionMap.put("closeWindow", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    dispose();
                }
            });

            // Make window visible
            setVisible(true);
        }
    }

    /**
     * Disables the history panel components.
     */
    public void disableHistory() {
        SwingUtilities.invokeLater(() -> {
            historyTable.setEnabled(false);
            undoButton.setEnabled(false);
            redoButton.setEnabled(false);
            // Optionally change appearance to indicate disabled state
            historyTable.setForeground(UIManager.getColor("Label.disabledForeground"));
            // Make the table visually distinct when disabled
             historyTable.setBackground(UIManager.getColor("Panel.background").darker());
        });
    }

    /**
     * Enables the history panel components.
     */
    public void enableHistory() {
        SwingUtilities.invokeLater(() -> {
            historyTable.setEnabled(true);
            undoButton.setEnabled(true);
            redoButton.setEnabled(true);
            // Restore appearance
            historyTable.setForeground(UIManager.getColor("Table.foreground"));
            historyTable.setBackground(UIManager.getColor("Table.background"));
        });
    }
}
