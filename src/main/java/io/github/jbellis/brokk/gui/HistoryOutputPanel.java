package io.github.jbellis.brokk.gui;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
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
    private JTabbedPane historyTabbedPane; // Tabbed pane for History (Sessions/Activity)

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

        // Build tabbed panel with Sessions and Activity (East)
        historyTabbedPane = new JTabbedPane();
        var sessionsPanel = new SessionsPanel(chrome, contextManager);
        var activityPanel = buildActivityPanel();

        historyTabbedPane.addTab("Sessions", sessionsPanel);
        historyTabbedPane.addTab("Activity", activityPanel);

        // Calculate preferred width for the tabbed panel to match old activity panel size
        int preferredWidth = 230;
        var preferredSize = new Dimension(preferredWidth, historyTabbedPane.getPreferredSize().height);
        historyTabbedPane.setPreferredSize(preferredSize);
        historyTabbedPane.setMinimumSize(preferredSize);
        historyTabbedPane.setMaximumSize(new Dimension(preferredWidth, Integer.MAX_VALUE));

        // Wrap the tabbed pane in a bordered panel titled "History"
        var historyPanel = new JPanel(new BorderLayout());
        historyPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "History",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));
        historyPanel.add(historyTabbedPane, BorderLayout.CENTER);

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
     * Builds the Activity history panel that shows past contexts
     */
    private JPanel buildActivityPanel() {
        // Create history panel
        var panel = new JPanel(new BorderLayout());

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

            // After initial session load, show the Activity tab
            historyTabbedPane.setSelectedIndex(1);
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
     * Inner class for managing sessions UI
     */
    private class SessionsPanel extends JPanel {
        private final Chrome chrome;
        private final ContextManager contextManager;
        private JTable sessionsTable;
        private DefaultTableModel sessionsTableModel;
        private JButton newSessionButton;

        public SessionsPanel(Chrome chrome, ContextManager contextManager) {
            super(new BorderLayout());
            this.chrome = chrome;
            this.contextManager = contextManager;
            
            // Initialize table model
            sessionsTableModel = new DefaultTableModel(new Object[]{"", "Session Info"}, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
            
            // Initialize table
        sessionsTable = new JTable(sessionsTableModel) {
            @Override
            public String getToolTipText(MouseEvent event) {
                java.awt.Point p = event.getPoint();
                int rowIndex = rowAtPoint(p);
                if (rowIndex >= 0 && rowIndex < getRowCount()) { // Check row bounds
                    Project.SessionInfo sessionInfo = (Project.SessionInfo) sessionsTableModel.getValueAt(rowIndex, 1); // Get from hidden column
                    if (sessionInfo != null) { // Check if sessionInfo is not null
                        return "Last modified: " + new java.util.Date(sessionInfo.modified()).toString();
                    }
                }
                return super.getToolTipText(event);
            }
        };
        sessionsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        
        // Remove table header
        sessionsTable.setTableHeader(null);
        
        // Add selection listener for session switching
        sessionsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && sessionsTable.getSelectedRow() != -1) {
                Project.SessionInfo selectedSessionInfo = (Project.SessionInfo) sessionsTableModel.getValueAt(sessionsTable.getSelectedRow(), 1);
                UUID selectedSessionId = selectedSessionInfo.id();
                if (!selectedSessionId.equals(contextManager.getCurrentSessionId())) {
                    contextManager.switchSessionAsync(selectedSessionId);
                }
            }
        });
        
        // Add mouse listener for right-click context menu
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
            
            // Create scroll pane for table
            JScrollPane sessionsScrollPane = new JScrollPane(sessionsTable);
            
            // Initialize new session button
            newSessionButton = new JButton("New Session");
            newSessionButton.addActionListener(e -> {
                contextManager.createNewSessionAsync("New Session").thenRun(() ->
                    SwingUtilities.invokeLater(this::refreshSessionsTable)
                );
            });
            
            // Create button panel
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttonPanel.add(newSessionButton);
            
            // Add components to this panel
            add(sessionsScrollPane, BorderLayout.CENTER);
            add(buttonPanel, BorderLayout.SOUTH);
            
            // Refresh the table with current sessions
            refreshSessionsTable();
        }
        
        public void refreshSessionsTable() {
            sessionsTableModel.setRowCount(0);
            List<Project.SessionInfo> sessions = contextManager.getProject().listSessions();
            sessions.sort(java.util.Comparator.comparingLong(Project.SessionInfo::modified)); // Sort oldest first

            for (var session : sessions) {
                sessionsTableModel.addRow(new Object[]{session.name(), session});
            }
            
            // Hide the "Session Info" column
            sessionsTable.getColumnModel().getColumn(1).setMinWidth(0);
            sessionsTable.getColumnModel().getColumn(1).setMaxWidth(0);
            sessionsTable.getColumnModel().getColumn(1).setWidth(0);
            
            // Select current session
            UUID currentSessionId = contextManager.getCurrentSessionId();
            for (int i = 0; i < sessionsTableModel.getRowCount(); i++) {
                Project.SessionInfo rowInfo = (Project.SessionInfo) sessionsTableModel.getValueAt(i, 1);
                if (rowInfo.id().equals(currentSessionId)) {
                    sessionsTable.setRowSelectionInterval(i, i);
                    break;
                }
            }
        }
        
        private void showSessionContextMenu(MouseEvent e) {
            int row = sessionsTable.rowAtPoint(e.getPoint());
            if (row < 0) return;
            
            sessionsTable.setRowSelectionInterval(row, row);
            Project.SessionInfo sessionInfo = (Project.SessionInfo) sessionsTableModel.getValueAt(row, 1);
            
            JPopupMenu popup = new JPopupMenu();
            JMenuItem renameItem = new JMenuItem("Rename");
            renameItem.addActionListener(event -> {
                String newName = JOptionPane.showInputDialog(SessionsPanel.this, 
                    "Enter new name for session '" + sessionInfo.name() + "':", 
                    sessionInfo.name());
                if (newName != null && !newName.trim().isBlank()) {
                    contextManager.renameSessionAsync(sessionInfo.id(), newName.trim()).thenRun(() ->
                        SwingUtilities.invokeLater(this::refreshSessionsTable)
                    );
                }
            });
            popup.add(renameItem);

            JMenuItem deleteItem = new JMenuItem("Delete");
            deleteItem.addActionListener(event -> {
                int confirm = JOptionPane.showConfirmDialog(SessionsPanel.this, 
                    "Are you sure you want to delete session '" + sessionInfo.name() + "'?", 
                    "Confirm Delete", 
                    JOptionPane.YES_NO_OPTION, 
                    JOptionPane.WARNING_MESSAGE);
                if (confirm == JOptionPane.YES_OPTION) {
                    contextManager.deleteSessionAsync(sessionInfo.id()).thenRun(() -> 
                        SwingUtilities.invokeLater(this::refreshSessionsTable));
                }
            });
            popup.add(deleteItem);
            
            JMenuItem copyItem = new JMenuItem("Copy");
            copyItem.addActionListener(event -> {
                contextManager.copySessionAsync(sessionInfo.id(), sessionInfo.name()).thenRun(() -> 
                    SwingUtilities.invokeLater(this::refreshSessionsTable));
            });
            popup.add(copyItem);
            popup.show(sessionsTable, e.getX(), e.getY());
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
