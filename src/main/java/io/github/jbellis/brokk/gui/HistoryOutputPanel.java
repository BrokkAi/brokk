package io.github.jbellis.brokk.gui;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.dialogs.SessionsDialog;
import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

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
    private JComboBox<MainProject.SessionInfo> sessionComboBox;
    private JButton newSessionButton;
    private JButton manageSessionsButton;

    // Output components
    private MarkdownOutputPanel llmStreamArea;
    private JScrollPane llmScrollPane;
    // systemArea, systemScrollPane, commandResultLabel removed
    private JTextArea captureDescriptionArea;
    private JButton copyButton;

    private InstructionsPanel instructionsPanel;
    private final List<OutputWindow> activeStreamingWindows = new ArrayList<>();

    private String lastSpinnerMessage;

    /**
     * Constructs a new HistoryOutputPane.
     *
     * @param chrome The parent Chrome instance
     * @param contextManager The context manager
     * @param instructionsPanel The instructions panel to include below output
     */
    public HistoryOutputPanel(Chrome chrome, ContextManager contextManager, InstructionsPanel instructionsPanel) {
        super(new BorderLayout()); // Use BorderLayout
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.instructionsPanel = instructionsPanel;

        // commandResultLabel initialization removed

        // Build combined Output + Instructions panel (Center)
        var centerPanel = buildCombinedOutputInstructionsPanel();
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

    private JPanel buildCombinedOutputInstructionsPanel() {
        // Build LLM streaming area
        llmScrollPane = buildLLMStreamScrollPane();

        // Build capture output panel
        var capturePanel = buildCaptureOutputPanel();

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

        // Create vertical split pane with Output above and Instructions below
        var outputInstructionsSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        outputInstructionsSplit.setResizeWeight(0.6); // Give output panel 60% of space
        outputInstructionsSplit.setTopComponent(outputPanel);
        outputInstructionsSplit.setBottomComponent(instructionsPanel);

        // Container for the combined section
        var centerContainer = new JPanel(new BorderLayout());
        centerContainer.add(outputInstructionsSplit, BorderLayout.CENTER);
        centerContainer.setMinimumSize(new Dimension(200, 0)); // Minimum width for combined area

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
                if (value instanceof MainProject.SessionInfo sessionInfo) {
                    setText(sessionInfo.name());
                }
                return this;
            }
        });
        
        // Add selection listener for session switching
        sessionComboBox.addActionListener(e -> {
            var selectedSession = (MainProject.SessionInfo) sessionComboBox.getSelectedItem();
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
            contextManager.createSessionAsync(ContextManager.DEFAULT_SESSION_NAME).thenRun(() ->
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
            var dialog = new SessionsDialog(this, chrome, contextManager);
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
    public void updateSessionComboBox() {
        SwingUtilities.invokeLater(() -> {
            // Remove action listener temporarily
            var listeners = sessionComboBox.getActionListeners();
            for (var listener : listeners) {
                sessionComboBox.removeActionListener(listener);
            }
            
            // Clear and repopulate
            sessionComboBox.removeAllItems();
            var sessions = contextManager.getProject().listSessions();
            sessions.sort(java.util.Comparator.comparingLong(IProject.SessionInfo::modified).reversed()); // Most recent first
            
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

        // Set up icon renderer for first column
        historyTable.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
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
                    label.setText(value != null ? value.toString() : "");
                }
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
                    contextManager.setSelectedContext(ctx);
                    chrome.setContext(ctx);
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
                             String titleHint = context.getAction();
                             new OutputWindow(HistoryOutputPanel.this, output, titleHint,
                                              chrome.themeManager != null && chrome.themeManager.isDarkTheme(), false);
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
        popup.addSeparator();

        JMenuItem newSessionFromWorkspaceItem = new JMenuItem("New Session from Workspace");
        newSessionFromWorkspaceItem.addActionListener(event -> {
            contextManager.createSessionFromContextAsync(context, ContextManager.DEFAULT_SESSION_NAME)
                .exceptionally(ex -> {
                    chrome.toolError("Failed to create new session from workspace: " + ex.getMessage());
                    return null;
                });
        });
        popup.add(newSessionFromWorkspaceItem);

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
    public void updateHistoryTable(@Nullable Context contextToSelect) {
        logger.debug("Updating context history table with context {}",
                     contextToSelect != null ? contextToSelect.getAction() : "null");

        SwingUtilities.invokeLater(() -> {
            historyModel.setRowCount(0);

            // Track which row to select
            int rowToSelect = -1;
            int currentRow = 0;

            // Add rows for each context in history
            for (var ctx : contextManager.getContextHistoryList()) {
                // Add icon for AI responses, null for user actions
                Icon iconEmoji = (ctx.getParsedOutput() != null) ? SwingUtil.uiIcon("Brokk.ai-robot") : null;
                historyModel.addRow(new Object[]{
                        iconEmoji,
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
        llmStreamArea.addTextChangeListener(chrome::updateCaptureButtons);

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
                chrome.systemOutput("Copied to clipboard");
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
            if (llmStreamArea.isBlocking()) {
                List<ChatMessage> currentMessages = llmStreamArea.getRawMessages();
                var tempFragment = new ContextFragment.TaskFragment(contextManager, currentMessages, "Streaming Output...");
                String titleHint = lastSpinnerMessage;
                OutputWindow newStreamingWindow = new OutputWindow(this, tempFragment, titleHint, chrome.themeManager != null && chrome.themeManager.isDarkTheme(), true);
                if (lastSpinnerMessage != null) {
                    newStreamingWindow.getMarkdownOutputPanel().showSpinner(lastSpinnerMessage);
                }
                activeStreamingWindows.add(newStreamingWindow);
                newStreamingWindow.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosed(WindowEvent evt) {
                        activeStreamingWindows.remove(newStreamingWindow);
                    }
                });
            } else {
                var context = contextManager.selectedContext();
                var output = context.getParsedOutput();
                if (output != null) {
                    String titleHint = context.getAction();
                    new OutputWindow(this, output, titleHint, chrome.themeManager != null && chrome.themeManager.isDarkTheme(), false);
                }
            }
        });
        // Set minimum size
        openWindowButton.setMinimumSize(openWindowButton.getPreferredSize());
        buttonsPanel.add(openWindowButton);


        // Add buttons panel to the left
        panel.add(buttonsPanel, BorderLayout.WEST);

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
        activeStreamingWindows.forEach(window -> window.getMarkdownOutputPanel().append(text, type));
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
        lastSpinnerMessage = message;
        activeStreamingWindows.forEach(window -> window.getMarkdownOutputPanel().showSpinner(message));
    }

    /**
     * Hides the loading spinner in the Markdown area.
     */
    public void hideSpinner() {
        if (llmStreamArea != null) {
            llmStreamArea.hideSpinner();
        }
        lastSpinnerMessage = null;
        activeStreamingWindows.forEach(window -> window.getMarkdownOutputPanel().hideSpinner());
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
            if (!blocked) {
                activeStreamingWindows.forEach(window -> window.getMarkdownOutputPanel().setBlocking(false));
                activeStreamingWindows.clear();
            }
        } else {
            logger.warn("Attempted to set blocking state on null llmStreamArea");
        }
    }

    /**
     * Inner class representing a detached window for viewing output text
     */
    private static class OutputWindow extends JFrame {
        private final IProject project;
        private final MarkdownOutputPanel outputPanel;

        /**
         * Creates a new output window with the given text content
         *
         * @param parentPanel The parent HistoryOutputPanel
         * @param output The messages (ai, user, ...) to display
         * @param titleHint A hint for the window title (e.g., task summary or spinner message)
         * @param isDark Whether to use dark theme
         */
        public OutputWindow(HistoryOutputPanel parentPanel, ContextFragment.TaskFragment output, @Nullable String titleHint, boolean isDark, boolean isBlockingMode) {
            super(determineWindowTitle(titleHint, isBlockingMode)); // Call superclass constructor first

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
            outputPanel = new MarkdownOutputPanel();
            outputPanel.updateTheme(isDark);
            outputPanel.setText(output);
            
            // Use shared utility method to create searchable content panel (without navigation for detached window)
            JPanel contentPanel = Chrome.createSearchableContentPanel(List.of(outputPanel));
            
            // Add the content panel to the frame
            add(contentPanel);
            
            if (!isBlockingMode) {
                // Schedule compaction after everything is set up
                outputPanel.scheduleCompaction();
            }
            outputPanel.setBlocking(isBlockingMode);

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

        private static String determineWindowTitle(@Nullable String titleHint, boolean isBlockingMode) {
            String windowTitle;
            if (isBlockingMode) {
                windowTitle = "Output (In progress)";
                if (titleHint != null && !titleHint.isBlank()) {
                    windowTitle = "Output: " + titleHint;
                    String taskType = null;
                    if (titleHint.contains(InstructionsPanel.ACTION_CODE)) {
                        taskType = InstructionsPanel.ACTION_CODE;
                    } else if (titleHint.contains(InstructionsPanel.ACTION_ARCHITECT)) {
                        taskType = InstructionsPanel.ACTION_ARCHITECT;
                    } else if (titleHint.contains(InstructionsPanel.ACTION_SEARCH)) {
                        taskType = InstructionsPanel.ACTION_SEARCH;
                    } else if (titleHint.contains(InstructionsPanel.ACTION_ASK)) {
                        taskType = InstructionsPanel.ACTION_ASK;
                    } else if (titleHint.contains(InstructionsPanel.ACTION_RUN)) {
                        taskType = InstructionsPanel.ACTION_RUN;
                    } 
                    if (taskType != null) {
                        windowTitle = String.format("Output (%s in progress)", taskType);
                    }
                }
            } else {
                windowTitle = "Output";
                if (titleHint != null && !titleHint.isBlank()) {
                    windowTitle = "Output: " + titleHint;
                }
            }
            return windowTitle;
        }

        /**
         * Gets the MarkdownOutputPanel used by this window.
         */
        public MarkdownOutputPanel getMarkdownOutputPanel() {
            return outputPanel;
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
