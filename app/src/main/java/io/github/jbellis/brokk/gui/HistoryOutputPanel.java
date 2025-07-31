package io.github.jbellis.brokk.gui;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.Brokk;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IProject;
import static io.github.jbellis.brokk.SessionManager.SessionInfo;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.context.ContextHistory;
import io.github.jbellis.brokk.difftool.utils.ColorUtil;
import io.github.jbellis.brokk.difftool.utils.Colors;
import io.github.jbellis.brokk.gui.dialogs.SessionsDialog;
import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import io.github.jbellis.brokk.gui.components.SpinnerIconUtil;
import io.github.jbellis.brokk.gui.components.SplitButton;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.LayerUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

/**
 * A component that combines the context history panel with the output panel using BorderLayout.
 */
public class HistoryOutputPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(HistoryOutputPanel.class);
    private static final String CLEARED_TASK_HISTORY = "Cleared Task History";
    private static final String DROPPED_ALL_CONTEXT = "Dropped all Context";

    private static boolean isSeparatorAction(@Nullable Object actionValue) {
        if (actionValue == null) {
            return false;
        }
        String action = actionValue.toString();
        return CLEARED_TASK_HISTORY.equalsIgnoreCase(action) || DROPPED_ALL_CONTEXT.equalsIgnoreCase(action);
    }

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final JTable historyTable;
    private final DefaultTableModel historyModel;
    private final JButton undoButton;
    private final JButton redoButton;
    private final JComboBox<SessionInfo> sessionComboBox;
    private final SplitButton newSessionButton;
    private final SplitButton manageSessionsButton;
    private ResetArrowLayerUI arrowLayerUI;
    @Nullable private JPanel sessionSwitchPanel;
    @Nullable private JLabel sessionSwitchSpinner;
    private JLayeredPane historyLayeredPane;

    // Output components
    private final MarkdownOutputPanel llmStreamArea;
    private final JScrollPane llmScrollPane;
    // systemArea, systemScrollPane, commandResultLabel removed
    @Nullable private JTextArea captureDescriptionArea; // This one seems to be intentionally nullable or less strictly managed
    private final JButton copyButton;

    private final InstructionsPanel instructionsPanel;
    private final List<OutputWindow> activeStreamingWindows = new ArrayList<>();

    @Nullable private String lastSpinnerMessage = null; // Explicitly initialize

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
        this.llmStreamArea = new MarkdownOutputPanel();
        this.llmScrollPane = buildLLMStreamScrollPane(this.llmStreamArea);
        this.copyButton = new JButton("Copy");
        var centerPanel = buildCombinedOutputInstructionsPanel(this.llmScrollPane, this.copyButton);
        add(centerPanel, BorderLayout.CENTER);

        // Build session controls and activity panel (East)
        this.historyModel = new DefaultTableModel(new Object[]{"", "Action", "Context"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        this.historyTable = new JTable(this.historyModel);
        this.arrowLayerUI = new ResetArrowLayerUI(this.historyTable, this.historyModel);
        this.undoButton = new JButton("Undo");
        this.redoButton = new JButton("Redo");
        this.sessionComboBox = new JComboBox<>();
        this.newSessionButton = new SplitButton("New");
        this.manageSessionsButton = new SplitButton("Manage");

        this.historyLayeredPane = new JLayeredPane();
        this.historyLayeredPane.setLayout(new OverlayLayout(this.historyLayeredPane));

        var sessionControlsPanel = buildSessionControlsPanel(this.sessionComboBox, this.newSessionButton, this.manageSessionsButton);
        var activityPanel = buildActivityPanel(this.historyTable, this.undoButton, this.redoButton);

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

    private void buildSessionSwitchPanel() {
        // This is the main panel that will be added to the layered pane.
        // It uses BorderLayout to position its content at the top.
        sessionSwitchPanel = new JPanel(new BorderLayout());
        sessionSwitchPanel.setOpaque(true);
        sessionSwitchPanel.setVisible(false);

        // This is the panel that actually holds the spinner and text.
        // It will be placed at the top of sessionSwitchPanel.
        var contentPanel = new JPanel(new BorderLayout(5, 0)); // stretch horizontally
        contentPanel.setOpaque(false);
        contentPanel.setBorder(new EmptyBorder(8, 5, 5, 5));

        sessionSwitchSpinner = new JLabel();
        var spinnerIcon = SpinnerIconUtil.getSpinner(chrome, false);
        if (spinnerIcon != null) {
            sessionSwitchSpinner.setIcon(spinnerIcon);
        }

        JLabel notificationText = new JLabel("Loading session...");
        notificationText.setOpaque(false);
        notificationText.setFont(historyTable.getFont());
        notificationText.setForeground(UIManager.getColor("Label.foreground"));
        notificationText.setBorder(null);

        contentPanel.add(sessionSwitchSpinner, BorderLayout.WEST);
        contentPanel.add(notificationText, BorderLayout.CENTER);

        sessionSwitchPanel.add(contentPanel, BorderLayout.NORTH);
    }

    private JPanel buildCombinedOutputInstructionsPanel(JScrollPane llmScrollPane, JButton copyButton) {
        // Build capture output panel (copyButton is passed in)
        var capturePanel = buildCaptureOutputPanel(copyButton);

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
    private JPanel buildSessionControlsPanel(JComboBox<SessionInfo> sessionComboBox, SplitButton newSessionButton, SplitButton manageSessionsButton) {
        var panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Sessions",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        // Session combo box (passed in)
        sessionComboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                         boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof SessionInfo sessionInfo) {
                    setText(sessionInfo.name());
                }
                return this;
            }
        });

        // Add selection listener for session switching
        sessionComboBox.addActionListener(e -> {
            var selectedSession = (SessionInfo) sessionComboBox.getSelectedItem();
            if (selectedSession != null && !selectedSession.id().equals(contextManager.getCurrentSessionId())) {
                contextManager.switchSessionAsync(selectedSession.id());
            }
        });

        // Buttons panel
        // Use GridLayout to make buttons share width equally
        var buttonsPanel = new JPanel(new GridLayout(1, 2, 5, 0)); // 1 row, 2 columns, 5px hgap

        // Tooltip and action listener for the new session button
        newSessionButton.setToolTipText("Create a new session");
        // Primary click → empty session
        newSessionButton.addActionListener(e -> {
            contextManager.createSessionAsync(ContextManager.DEFAULT_SESSION_NAME).thenRun(() ->
                    SwingUtilities.invokeLater(this::updateSessionComboBox)
            );
        });
        // Split-arrow menu → session with copied workspace
        newSessionButton.setMenuSupplier(() -> {
            var popup = new JPopupMenu();
            var copyWorkspaceItem = new JMenuItem("New + Copy Workspace");
            copyWorkspaceItem.addActionListener(ev -> {
                contextManager.createSessionFromContextAsync(
                        contextManager.topContext(), ContextManager.DEFAULT_SESSION_NAME
                ).thenRun(() -> SwingUtilities.invokeLater(this::updateSessionComboBox));
            });
            popup.add(copyWorkspaceItem);
            return popup;
        });

        // Tooltip and action listener for the manage sessions button
        manageSessionsButton.setToolTipText("Manage sessions (rename, delete, copy)");
        manageSessionsButton.setMenuSupplier(() -> {
            var popup = new JPopupMenu();

            var renameItem = new JMenuItem("Rename Current Session");
            renameItem.addActionListener(ev ->
                    SessionsDialog.renameCurrentSession(HistoryOutputPanel.this, chrome, contextManager, HistoryOutputPanel.this));
            popup.add(renameItem);

            var deleteItem = new JMenuItem("Delete Current Session");
            deleteItem.addActionListener(ev ->
                    SessionsDialog.deleteCurrentSession(HistoryOutputPanel.this, chrome, contextManager, HistoryOutputPanel.this));
            popup.add(deleteItem);

            return popup;
        });
        manageSessionsButton.addActionListener(e -> {
            var dialog = new SessionsDialog(HistoryOutputPanel.this, chrome, contextManager);
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
            var sessions = contextManager.getProject().getSessionManager().listSessions();
            sessions.sort(java.util.Comparator.comparingLong(SessionInfo::modified).reversed()); // Most recent first
            
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
    private JPanel buildActivityPanel(JTable historyTable, JButton undoButton, JButton redoButton) {
        // Create history panel
        var panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Activity",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        historyTable.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Remove table header
        historyTable.setTableHeader(null);

        // Set up custom renderers for history table columns
        historyTable.getColumnModel().getColumn(0).setCellRenderer(new IconCellRenderer());
        historyTable.getColumnModel().getColumn(1).setCellRenderer(new ActionCellRenderer());

        // Add selection listener to preview context
        historyTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = historyTable.getSelectedRow();
                if (row >= 0 && row < historyTable.getRowCount()) {
                    // Get the context object from the hidden third column
                    var ctx = (Context) historyModel.getValueAt(row, 2);
                    contextManager.setSelectedContext(ctx);
                    // setContext is for *previewing* a context without changing selection state in the manager
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
                         Context context = (Context) requireNonNull(historyModel.getValueAt(row, 2));
                         var output = context.getParsedOutput();
                         if (output != null) {
                             // Open in new window
                             String titleHint = context.getAction();
                             new OutputWindow(HistoryOutputPanel.this, output, titleHint,
                                              chrome.themeManager.isDarkTheme(), false);
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
        var layer = new JLayer<>(scrollPane, arrowLayerUI);
        scrollPane.getViewport().addChangeListener(e -> layer.repaint());
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
        // Use GridLayout to make buttons share width equally
        var buttonPanel = new JPanel(new GridLayout(1, 2, 5, 0)); // 1 row, 2 columns, 5px hgap
        buttonPanel.setBorder(new EmptyBorder(5, 0, 0, 0)); // Add top padding

        undoButton.setMnemonic(KeyEvent.VK_Z);
        undoButton.setToolTipText("Undo the most recent history entry");
        undoButton.addActionListener(e -> {
            contextManager.undoContextAsync();
        });

        redoButton.setMnemonic(KeyEvent.VK_Y);
        redoButton.setToolTipText("Redo the most recently undone entry");
        redoButton.addActionListener(e -> {
            contextManager.redoContextAsync();
        });

        buttonPanel.add(undoButton);
        buttonPanel.add(redoButton);

        historyLayeredPane.add(layer, JLayeredPane.DEFAULT_LAYER);

        panel.add(historyLayeredPane, BorderLayout.CENTER);
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
            undoButton.setEnabled(contextManager.getContextHistory().hasUndoStates());
            redoButton.setEnabled(contextManager.getContextHistory().hasRedoStates());
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
        chrome.themeManager.registerPopupMenu(popup);

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
        assert contextToSelect == null || !contextToSelect.containsDynamicFragments();

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
            var resetEdges = contextManager.getContextHistory().getResetEdges();
            arrowLayerUI.setResetEdges(resetEdges);
        });
    }

    /**
     * Returns the history table for selection checks
     *
     * @return The JTable containing context history
     */
    public @Nullable JTable getHistoryTable() {
        return historyTable;
    }

    /**
     * Builds the LLM streaming area where markdown output is displayed
     */
    private JScrollPane buildLLMStreamScrollPane(MarkdownOutputPanel llmStreamArea) {
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
    private JPanel buildCaptureOutputPanel(JButton copyButton) {
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
                OutputWindow newStreamingWindow = new OutputWindow(this, tempFragment, titleHint, chrome.themeManager.isDarkTheme(), true);
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
                if (context == null) {
                    logger.warn("Cannot open output in new window: current context is null.");
                    return;
                }
                var output = context.getParsedOutput();
                if (output != null) {
                    String titleHint = context.getAction();
                    new OutputWindow(this, output, titleHint, chrome.themeManager.isDarkTheme(), false);
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
                        SwingUtilities.invokeLater(() -> castNonNull(llmScrollPane.getVerticalScrollBar()).setValue(0));
                    }
                }
        );
    }

    /**
     * Appends text to the LLM output area
     */
    public void appendLlmOutput(String text, ChatMessageType type, boolean isNewMessage) {
        llmStreamArea.append(text, type, isNewMessage);
        activeStreamingWindows.forEach(window -> window.getMarkdownOutputPanel().append(text, type, isNewMessage));
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
        llmStreamArea.showSpinner(message);
        lastSpinnerMessage = message;
        activeStreamingWindows.forEach(window -> window.getMarkdownOutputPanel().showSpinner(message));
    }

    /**
     * Hides the loading spinner in the Markdown area.
     */
    public void hideSpinner() {
        llmStreamArea.hideSpinner();
        lastSpinnerMessage = null;
        activeStreamingWindows.forEach(window -> window.getMarkdownOutputPanel().hideSpinner());
    }

    /**
     * Shows the session switching spinner.
     */
    public void showSessionSwitchSpinner() {
        SwingUtilities.invokeLater(() -> {
            historyModel.setRowCount(0);
            JPanel ssp = sessionSwitchPanel;
            if (ssp == null) {
                buildSessionSwitchPanel();
                ssp = requireNonNull(sessionSwitchPanel);
                historyLayeredPane.add(ssp, JLayeredPane.PALETTE_LAYER);
            }
            ssp.setVisible(true);
            ssp.revalidate();
            ssp.repaint();
        });
    }

    /**
     * Hides the session switching spinner.
     */
    public void hideSessionSwitchSpinner() {
        SwingUtilities.invokeLater(() -> {
            if (sessionSwitchPanel != null) {
                sessionSwitchPanel.setVisible(false);
                sessionSwitchPanel.revalidate();
                sessionSwitchPanel.repaint();
            }
        });
    }

    /**
     * Gets the LLM scroll pane
     */
    public JScrollPane getLlmScrollPane() {
        return requireNonNull(llmScrollPane, "llmScrollPane should be initialized by constructor");
    }

    public MarkdownOutputPanel getLlmStreamArea() {
        return requireNonNull(llmStreamArea, "llmStreamArea should be initialized by constructor");
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
        llmStreamArea.setBlocking(blocked);
        if (!blocked) {
            activeStreamingWindows.forEach(window -> window.getMarkdownOutputPanel().setBlocking(false));
            activeStreamingWindows.clear();
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
            
            // Create toolbar panel with capture button if not in blocking mode
            JPanel toolbarPanel = null;
            if (!isBlockingMode) {
                toolbarPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
                toolbarPanel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 0));
                
                JButton captureButton = new JButton("Capture");
                captureButton.setToolTipText("Add the output to context");
                captureButton.addActionListener(e -> {
                    parentPanel.contextManager.submitContextTask("Capturing output", () -> {
                        parentPanel.contextManager.addVirtualFragment(output);
                        parentPanel.chrome.systemOutput("Content captured from output window");
                    });
                });
                toolbarPanel.add(captureButton);
            }
            
            // Use shared utility method to create searchable content panel with optional toolbar
            JPanel contentPanel = Chrome.createSearchableContentPanel(List.of(outputPanel), toolbarPanel);

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
     * A TableCellRenderer for the first column (icons) of the history table.
     * It hides the icon for separator rows to allow the separator to span the cell.
     */
    private static class IconCellRenderer extends DefaultTableCellRenderer {
        private final SeparatorPainter separatorPainter = new SeparatorPainter();

        @Override
        public Component getTableCellRendererComponent(JTable table, @Nullable Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column)
        {
            Object actionValue = table.getModel().getValueAt(row, 1);
            if (isSeparatorAction(actionValue)) {
                separatorPainter.setAction(castNonNull(actionValue).toString());
                separatorPainter.setCellContext(table, row, column);
                separatorPainter.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
                separatorPainter.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
                return separatorPainter;
            }

            // Fallback for normal cells
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (value instanceof Icon icon) {
                setIcon(icon);
                setText("");
            } else {
                setIcon(null);
                setText(value != null ? value.toString() : "");
            }
            setHorizontalAlignment(JLabel.CENTER);
            return this;
        }
    }

    /**
     * A TableCellRenderer for the second column (action text) of the history table.
     * It replaces specific action texts with graphical separators.
     */
    private static class ActionCellRenderer extends DefaultTableCellRenderer {
        private final SeparatorPainter separatorPainter = new SeparatorPainter();

        @Override
        public Component getTableCellRendererComponent(JTable table, @Nullable Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column)
        {
            if (isSeparatorAction(value)) {
                separatorPainter.setOpaque(true);
                separatorPainter.setAction(castNonNull(value).toString());
                separatorPainter.setCellContext(table, row, column);
                separatorPainter.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
                separatorPainter.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
                return separatorPainter;
            }

            // Fallback for normal cells
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (value != null) {
                setToolTipText(value.toString());
            }
            return this;
        }
    }

    /**
     * A component that paints a horizontal or squiggly line for separator rows in the history table.
     */
    private static class SeparatorPainter extends JComponent {
        private String action = "";
        private @Nullable JTable table;
        private int row;
        private int column;
        private static final int SQUIGGLE_AMPLITUDE = 2;
        private static final double PIXELS_PER_SQUIGGLE_WAVE = 24.0;

        public SeparatorPainter() {
            setOpaque(true);
        }

        public void setAction(String action) {
            this.action = action;
            setToolTipText(action);
        }

        public void setCellContext(JTable table, int row, int column) {
            this.table = table;
            this.row = row;
            this.column = column;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(super.getPreferredSize().width, 8);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (isOpaque()) {
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
            }

            if (table == null) {
                return;
            }

            int totalWidth = table.getWidth();
            int iconColumnWidth = table.getColumnModel().getColumn(0).getWidth();
            int margin = iconColumnWidth / 2;
            int ruleStartX = margin;
            int ruleEndX = totalWidth - margin - 1;

            Rectangle cellRect = table.getCellRect(row, column, false);
            int localStartX = ruleStartX - cellRect.x;
            int localEndX = ruleEndX - cellRect.x;

            int drawStart = Math.max(0, localStartX);
            int drawEnd = Math.min(getWidth(), localEndX);

            if (drawStart >= drawEnd) {
                return;
            }

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(getForeground());
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int y = getHeight() / 2;

                if (CLEARED_TASK_HISTORY.equalsIgnoreCase(action)) {
                    g2.drawLine(drawStart, y, drawEnd, y);
                } else if (DROPPED_ALL_CONTEXT.equalsIgnoreCase(action)) {
                    int lineWidth = ruleEndX - ruleStartX;
                    if (lineWidth <= 0) {
                        return;
                    }

                    // Dynamically calculate frequency to ensure the wave completes an integer number of cycles
                    int waves = Math.max(1, (int) Math.round(lineWidth / PIXELS_PER_SQUIGGLE_WAVE));
                    double frequency = (2 * Math.PI * waves) / lineWidth;

                    Path2D.Double path = new Path2D.Double();
                    int globalXStart = cellRect.x + drawStart;
                    double startY = y - SQUIGGLE_AMPLITUDE * Math.sin((globalXStart - ruleStartX) * frequency);
                    path.moveTo(drawStart, startY);
                    for (int x = drawStart + 1; x < drawEnd; x++) {
                        int globalX = cellRect.x + x;
                        double waveY = y - SQUIGGLE_AMPLITUDE * Math.sin((globalX - ruleStartX) * frequency);
                        path.lineTo(x, waveY);
                    }
                    g2.draw(path);
                }
            } finally {
                g2.dispose();
            }
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

    /**
     * A LayerUI that paints reset-from-history arrows over the history table.
     */
    private class ResetArrowLayerUI extends LayerUI<JScrollPane> {
        private final JTable table;
        private final DefaultTableModel model;
        private List<ContextHistory.ResetEdge> resetEdges = List.of();
        private final Map<ContextHistory.ResetEdge, Integer> edgePaletteIndices = new HashMap<>();
        private int nextPaletteIndex = 0;

        public ResetArrowLayerUI(JTable table, DefaultTableModel model) {
            this.table = table;
            this.model = model;
        }

        public void setResetEdges(List<ContextHistory.ResetEdge> edges) {
            this.resetEdges = edges;
            // remove color mappings for edges that no longer exist
            edgePaletteIndices.keySet().retainAll(new HashSet<>(edges));
            firePropertyChange("resetEdges", null, edges); // Triggers repaint for the JLayer
        }

        private record Arrow(ContextHistory.ResetEdge edge, int sourceRow, int targetRow, int length) { }

        private Color colorFor(ContextHistory.ResetEdge edge, boolean isDark) {
            int paletteIndex = edgePaletteIndices.computeIfAbsent(edge, e -> {
                int i = nextPaletteIndex;
                nextPaletteIndex = (nextPaletteIndex + 1) % 4; // Cycle through 4 colors
                return i;
            });

            // For light mode, we want darker lines for better contrast against a light background.
            // For dark mode, we want brighter lines.
            var palette = List.of(
                    isDark ? Color.LIGHT_GRAY : Color.DARK_GRAY,
                    isDark ? ColorUtil.brighter(Colors.getAdded(true), 0.4f)
                           : ColorUtil.brighter(Colors.getAdded(false), -0.4f),
                    isDark ? ColorUtil.brighter(Colors.getChanged(true), 0.6f)
                           : ColorUtil.brighter(Colors.getChanged(false), -0.4f),
                    isDark ? ColorUtil.brighter(Colors.getDeleted(true), 1.2f)
                           : ColorUtil.brighter(Colors.getDeleted(false), -0.4f)
            );
            return palette.get(paletteIndex);
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            super.paint(g, c);
            if (resetEdges.isEmpty()) {
                return;
            }

            Map<UUID, Integer> contextIdToRow = new HashMap<>();
            for (int i = 0; i < model.getRowCount(); i++) {
                Context ctx = (Context) model.getValueAt(i, 2);
                if (ctx != null) {
                    contextIdToRow.put(ctx.id(), i);
                }
            }

            // 1. Build list of all possible arrows with their geometry
            List<Arrow> arrows = new ArrayList<>();
            for (var edge : resetEdges) {
                Integer sourceRow = contextIdToRow.get(edge.sourceId());
                Integer targetRow = contextIdToRow.get(edge.targetId());
                if (sourceRow != null && targetRow != null) {
                    var sourceRect = table.getCellRect(sourceRow, 0, true);
                    var targetRect = table.getCellRect(targetRow, 0, true);
                    int y1 = sourceRect.y + sourceRect.height / 2;
                    int y2 = targetRect.y + targetRect.height / 2;
                    arrows.add(new Arrow(edge, sourceRow, targetRow, Math.abs(y1 - y2)));
                }
            }

            // 2. Draw arrows, longest first to prevent shorter arrows from being hidden
            arrows.sort(Comparator.comparingInt((Arrow a) -> a.length).reversed());

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                float lineWidth = (float) (c.getGraphicsConfiguration().getDefaultTransform().getScaleX() >= 2 ? 0.75 : 1.0);
                g2.setStroke(new BasicStroke(lineWidth));

                boolean isDark = chrome.getTheme().isDarkTheme();
                for (var arrow : arrows) {
                    g2.setColor(colorFor(arrow.edge(), isDark));
                    drawArrow(g2, c, arrow.sourceRow(), arrow.targetRow());
                }
            } finally {
                g2.dispose();
            }
        }

        private void drawArrow(Graphics2D g2, JComponent c, int sourceRow, int targetRow) {
            Rectangle sourceRect = table.getCellRect(sourceRow, 0, true);
            Rectangle targetRect = table.getCellRect(targetRow, 0, true);

            // Convert cell rectangles to the JLayer's coordinate system
            Point sourcePoint = SwingUtilities.convertPoint(table, new Point(sourceRect.x, sourceRect.y + sourceRect.height / 2), c);
            Point targetPoint = SwingUtilities.convertPoint(table, new Point(targetRect.x, targetRect.y + targetRect.height / 2), c);

            // Don't draw if either point is outside the visible viewport
            if (!c.getVisibleRect().contains(sourcePoint) && !c.getVisibleRect().contains(targetPoint)) {
                 // a bit of a hack -- if just one is visible, we still want to draw part of the arrow
                if (c.getVisibleRect().contains(sourcePoint) || c.getVisibleRect().contains(targetPoint)) {
                    // one is visible, fall through
                } else {
                    return;
                }
            }

            int iconColWidth = table.getColumnModel().getColumn(0).getWidth();
            int arrowHeadLength = 5;
            int arrowLeadIn = 1; // length of the line segment before the arrowhead
            int arrowRightMargin = -2; // margin from the right edge of the column

            int tipX = sourcePoint.x + iconColWidth - arrowRightMargin;
            int baseX = tipX - arrowHeadLength;
            int verticalLineX = baseX - arrowLeadIn;

            // Define the path for the arrow shaft
            Path2D.Double path = new Path2D.Double();
            path.moveTo(tipX, sourcePoint.y);              // Start at source, aligned with the eventual arrowhead tip
            path.lineTo(verticalLineX, sourcePoint.y);     // Horizontal segment at source row
            path.lineTo(verticalLineX, targetPoint.y);     // Vertical segment connecting rows
            path.lineTo(baseX, targetPoint.y);             // Horizontal segment leading to arrowhead base
            g2.draw(path);

            // Draw the arrowhead at the target, pointing left-to-right
            drawArrowHead(g2, new Point(tipX, targetPoint.y), arrowHeadLength);
        }

        private void drawArrowHead(Graphics2D g2, Point to, int size) {
            // The arrow is always horizontal, left-to-right. Build an isosceles triangle.
            int tipX = to.x;
            int midY = to.y;
            int baseX = to.x - size;
            int halfHeight = (int) Math.round(size * 0.6); // Make it slightly wider than it is long

            var head = new Polygon(new int[]{tipX, baseX, baseX},
                                   new int[]{midY, midY - halfHeight, midY + halfHeight},
                                   3);
            g2.fill(head);
        }
    }
}
