package io.github.jbellis.brokk.gui;

import com.github.tjake.jlama.model.AbstractModel;
import com.github.tjake.jlama.model.functions.Generator;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.agents.ArchitectAgent;
import io.github.jbellis.brokk.agents.CodeAgent;
import io.github.jbellis.brokk.agents.ContextAgent;
import io.github.jbellis.brokk.agents.SearchAgent;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.context.ContextFragment.TaskFragment;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.TableUtils.FileReferenceList.FileReferenceData;
import io.github.jbellis.brokk.gui.components.BrowserLabel;
import io.github.jbellis.brokk.gui.components.LoadingButton;
import io.github.jbellis.brokk.gui.dialogs.ArchitectChoices;
import io.github.jbellis.brokk.gui.dialogs.ArchitectOptionsDialog;
import io.github.jbellis.brokk.gui.dialogs.SettingsDialog;
import io.github.jbellis.brokk.gui.dialogs.SettingsGlobalPanel;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.util.AddMenuFactory;
import io.github.jbellis.brokk.gui.util.ContextMenuUtils;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.LoggingExecutorService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.util.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.github.jbellis.brokk.gui.Constants.*;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;


/**
 * The InstructionsPanel encapsulates the command input area, history dropdown,
 * mic button, model dropdown, and the action buttons.
 * It also includes the system messages and command result areas.
 * All initialization and action code related to these components has been moved here.
 */
public class InstructionsPanel extends JPanel implements IContextManager.ContextListener {
    private static final Logger logger = LogManager.getLogger(InstructionsPanel.class);

    public static final String ACTION_ARCHITECT = "Architect";
    public static final String ACTION_CODE = "Code";
    public static final String ACTION_ASK = "Ask";
    public static final String ACTION_SEARCH = "Search";
    public static final String ACTION_RUN = "Run";

    private static final String PLACEHOLDER_TEXT = """
                                                   Put your instructions or questions here.  Brokk will suggest relevant files below; right-click on them to add them to your Workspace.  The Workspace will be visible to the AI when coding or answering your questions. Type "@" for add more context.
                                                   
                                                   More tips are available in the Getting Started section in the Output panel above.
                                                   """;

    private static final int DROPDOWN_MENU_WIDTH = 1000; // Pixels
    private static final int TRUNCATION_LENGTH = 100;    // Characters

    private final Chrome chrome;
    private final JTextArea instructionsArea;
    private final VoiceInputButton micButton;
    private final JButton architectButton; // Changed from SplitButton
    private final io.github.jbellis.brokk.gui.components.SplitButton codeButton;
    private final io.github.jbellis.brokk.gui.components.SplitButton askButton;
    private final JButton searchButton;
    private final JButton runButton;
    private final JButton stopButton;
    private final JButton configureModelsButton;
    private final JLabel commandResultLabel;
    private final @Nullable ContextManager contextManager; // Can be null if Chrome is initialized without one
    private JTable referenceFileTable;
    private JLabel failureReasonLabel;
    private JPanel suggestionContentPanel;
    private CardLayout suggestionCardLayout;
    private final LoadingButton deepScanButton;
    private final JPanel centerPanel;
    private final javax.swing.Timer contextSuggestionTimer; // Timer for debouncing quick context suggestions
    private final AtomicBoolean forceSuggestions = new AtomicBoolean(false);
    // Worker for autocontext suggestion tasks. we don't use CM.backgroundTasks b/c we want this to be single threaded
    private final ExecutorService suggestionWorker = new LoggingExecutorService(Executors.newSingleThreadExecutor(r -> {
        Thread t = Executors.defaultThreadFactory().newThread(r);
        t.setName("Brokk-Suggestion-Worker");
        t.setDaemon(true);
        return t;
    }), e -> logger.error("Unexpected error", e));
    // Generation counter to identify the latest suggestion request
    private final AtomicLong suggestionGeneration = new AtomicLong(0);
    private JPanel overlayPanel; // Panel used to initially disable command input
    private final UndoManager commandInputUndoManager;
    private boolean lowBalanceNotified = false;
    private boolean freeTierNotified = false;
    private @Nullable String lastCheckedInputText = null;
    private @Nullable float[][] lastCheckedEmbeddings = null;
    private @Nullable List<FileReferenceData> pendingQuickContext = null;

    public InstructionsPanel(Chrome chrome) {
        super(new BorderLayout(2, 2));
        setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(),
                                                   "Instructions",
                                                   TitledBorder.DEFAULT_JUSTIFICATION,
                                                   TitledBorder.DEFAULT_POSITION,
                                                   new Font(Font.DIALOG, Font.BOLD, 12)));

        this.chrome = chrome;
        this.contextManager = chrome.getContextManager(); // Store potentially null CM
        this.commandInputUndoManager = new UndoManager();
        this.overlayPanel = new JPanel();


        // Initialize components
        instructionsArea = buildCommandInputField(); // Build first to add listener
        micButton = new VoiceInputButton(instructionsArea, contextManager, () -> {
            activateCommandInput();
            chrome.actionOutput("Recording");
        }, msg -> chrome.toolError(msg, "Error"));
        commandResultLabel = buildCommandResultLabel(); // Initialize moved component

        // Initialize Buttons first
        architectButton = new JButton("Architect"); // Now a regular JButton
        architectButton.setMnemonic(KeyEvent.VK_G); // Mnemonic for Agent
        architectButton.setToolTipText("Run the multi-step agent (options include worktree setup)");
        architectButton.addActionListener(e -> runArchitectCommand()); // Main button action
        // architectButton.setMenuSupplier(this::createArchitectMenu); // Removed menu supplier

        codeButton = new io.github.jbellis.brokk.gui.components.SplitButton("Code");
        codeButton.setMnemonic(KeyEvent.VK_C);
        codeButton.setToolTipText("Tell the LLM to write code using the current context (click ▼ for model options)");
        codeButton.addActionListener(e -> runCodeCommand()); // Main button action
        codeButton.setMenuSupplier(() -> createModelSelectionMenu(
                (modelName, reasoningLevel) -> {
                    var models = chrome.getContextManager().getService();
                    StreamingChatLanguageModel selectedModel = models.getModel(modelName, reasoningLevel);
                    if (selectedModel != null) {
                        runCodeCommand(selectedModel);
                    } else {
                        chrome.toolError("Selected model '" + modelName + "' is not available with reasoning level " + reasoningLevel);
                    }
                }
        ));

        askButton = new io.github.jbellis.brokk.gui.components.SplitButton(" Ask");
        askButton.setMnemonic(KeyEvent.VK_A);
        askButton.setToolTipText("Ask the LLM a question about the current context (click ▼ for model options)");
        askButton.addActionListener(e -> runAskCommand()); // Main button action
        askButton.setMenuSupplier(() -> createModelSelectionMenu(
                (modelName, reasoningLevel) -> {
                    var models = chrome.getContextManager().getService();
                    StreamingChatLanguageModel selectedModel = models.getModel(modelName, reasoningLevel);
                    if (selectedModel != null) {
                        runAskCommand(selectedModel);
                    } else {
                        chrome.toolError("Selected model '" + modelName + "' is not available with reasoning level " + reasoningLevel);
                    }
                }
        ));

        searchButton = new JButton("Search");
        searchButton.setMnemonic(KeyEvent.VK_S);
        searchButton.setToolTipText("Explore the codebase beyond the current context");
        searchButton.addActionListener(e -> runSearchCommand());

        runButton = new JButton("Run in Shell");
        runButton.setMnemonic(KeyEvent.VK_N);
        runButton.setToolTipText("Execute the current instructions in a shell");
        runButton.addActionListener(e -> runRunCommand());

        stopButton = new JButton("Stop");
        stopButton.setToolTipText("Cancel the current operation");
        stopButton.setEnabled(false); // Start disabled, enabled when an action runs
        stopButton.addActionListener(e -> chrome.getContextManager().interruptUserActionThread());

        configureModelsButton = new JButton("Configure Models...");
        configureModelsButton.setToolTipText("Open settings to configure AI models");
        configureModelsButton.addActionListener(e -> SettingsDialog.showSettingsDialog(chrome, SettingsGlobalPanel.MODELS_TAB_TITLE));

        deepScanButton = new LoadingButton("Deep Scan", null, chrome, this::triggerDeepScan);
        deepScanButton.setToolTipText("Perform a deeper analysis (Code + Tests) to suggest relevant context");
        deepScanButton.setEnabled(false); // Start disabled like command input

        // Top Bar (History, Configure Models, Stop) (North)
        JPanel topBarPanel = buildTopBarPanel();
        add(topBarPanel, BorderLayout.NORTH);

        // Center Panel (Command Input + Result) (Center)
        this.centerPanel = buildCenterPanel();
        add(this.centerPanel, BorderLayout.CENTER);

        // Bottom Bar (Mic, Model, Actions) (South)
        JPanel bottomPanel = buildBottomPanel();
        add(bottomPanel, BorderLayout.SOUTH);

        // Initialize the reference file table and suggestion area
        initializeReferenceFileTable();

        // Initialize and configure the context suggestion timer
        contextSuggestionTimer = new javax.swing.Timer(100, this::triggerContextSuggestion);
        contextSuggestionTimer.setRepeats(false);
        instructionsArea.getDocument().addDocumentListener(new DocumentListener() {
            private void checkAndHandleSuggestions() {
                if (getInstructions().split("\\s+").length >= 2) {
                    contextSuggestionTimer.restart();
                } else {
                    // Input is blank or too short: stop timer, invalidate generation, reset state, schedule UI clear.
                    contextSuggestionTimer.stop();
                    long myGen = suggestionGeneration.incrementAndGet(); // Invalidate any running/pending task
                    logger.trace("Input cleared/shortened, stopping timer and invalidating suggestions (gen {})", myGen);

                    // Reset internal state immediately
                    InstructionsPanel.this.lastCheckedInputText = null;
                    InstructionsPanel.this.lastCheckedEmbeddings = null;

                    // Schedule UI update, guarded by generation check
                    SwingUtilities.invokeLater(() -> {
                        if (myGen == suggestionGeneration.get()) {
                            logger.trace("Applying UI clear for gen {}", myGen);
                            referenceFileTable.setValueAt(List.of(), 0, 0);
                            failureReasonLabel.setVisible(false);
                            suggestionCardLayout.show(suggestionContentPanel, "TABLE"); // Show empty table
                        } else {
                            logger.trace("Skipping UI clear for gen {} (current gen {})", myGen, suggestionGeneration.get());
                        }
                    });
                }
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                checkAndHandleSuggestions();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                checkAndHandleSuggestions();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                checkAndHandleSuggestions();
            }
        });

        SwingUtilities.invokeLater(() -> {
            if (chrome.getFrame() != null && chrome.getFrame().getRootPane() != null) {
                chrome.getFrame().getRootPane().setDefaultButton(codeButton);
            }
        });

        // Add this panel as a listener to context changes, only if CM is available
        if (this.contextManager != null) {
            this.contextManager.addContextListener(this);
        }

        // Buttons start disabled and will be enabled by ContextManager when session loading completes
        disableButtons();
    }

    public UndoManager getCommandInputUndoManager() {
        return commandInputUndoManager;
    }

    public JTextArea getInstructionsArea() {
        return instructionsArea;
    }

    private JTextArea buildCommandInputField() {
        var area = new JTextArea(3, 40);
        // The BorderUtils will now handle the border, including focus behavior and padding.
        BorderUtils.addFocusBorder(area, area);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setRows(3); // Initial rows
        area.setMinimumSize(new Dimension(100, 80));
        area.setEnabled(false); // Start disabled
        area.setText(PLACEHOLDER_TEXT); // Keep placeholder, will be cleared on activation
        area.getDocument().addUndoableEditListener(commandInputUndoManager);
        ((AbstractDocument) area.getDocument()).setDocumentFilter(new AtTriggerFilter());


        // Add Ctrl+Enter shortcut to trigger the default button
        var ctrlEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.CTRL_DOWN_MASK);
        area.getInputMap().put(ctrlEnter, "submitDefault");
        area.getActionMap().put("submitDefault", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // If there's a default button, "click" it
                var rootPane = SwingUtilities.getRootPane(area);
                if (rootPane != null && rootPane.getDefaultButton() != null) {
                    rootPane.getDefaultButton().doClick();
                }
            }
        });

        // Add Undo (Ctrl+Z) and Redo (Ctrl+Y or Ctrl+Shift+Z) actions
        var undoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, java.awt.event.InputEvent.CTRL_DOWN_MASK);
        var redoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Y, java.awt.event.InputEvent.CTRL_DOWN_MASK);
        var redoAlternativeKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.SHIFT_DOWN_MASK);

        area.getInputMap().put(undoKeyStroke, "undo");
        area.getActionMap().put("undo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (commandInputUndoManager.canUndo()) {
                    commandInputUndoManager.undo();
                }
            }
        });

        area.getInputMap().put(redoKeyStroke, "redo");
        area.getInputMap().put(redoAlternativeKeyStroke, "redo"); // Alternative for redo
        area.getActionMap().put("redo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (commandInputUndoManager.canRedo()) {
                    commandInputUndoManager.redo();
                }
            }
        });

        // Ctrl/Cmd + V  →  if clipboard has an image, route to WorkspacePanel paste;
        // otherwise, use the default JTextArea paste behaviour.
        var pasteKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_V,
                                                    java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        area.getInputMap().put(pasteKeyStroke, "smartPaste");
        area.getActionMap().put("smartPaste", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                var clipboard  = Toolkit.getDefaultToolkit().getSystemClipboard();
                var contents   = clipboard.getContents(null);
                boolean imageHandled = false;

                if (contents != null) {
                    for (var flavor : contents.getTransferDataFlavors()) {
                        try {
                            if (flavor.equals(DataFlavor.imageFlavor)
                                || flavor.isFlavorJavaFileListType()
                                || flavor.getMimeType().startsWith("image/")) {
                                // Re-use existing WorkspacePanel logic
                                chrome.getContextPanel()
                                      .performContextActionAsync(WorkspacePanel.ContextAction.PASTE, List.of());
                                imageHandled = true;
                                break;
                            }
                        } catch (Exception ex) {
                            // Ignore and fall back to default paste handling
                        }
                    }
                }

                if (!imageHandled) {
                    area.paste(); // Default text paste
                }
            }
        });

        // Add Shift+Enter shortcut to insert a newline
        var shiftEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK);
        area.getInputMap().put(shiftEnter, "insertNewline");
        area.getActionMap().put("insertNewline", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int caretPosition = area.getCaretPosition();
                area.insert("\n", caretPosition);
            }
        });

        return area;
    }

    private JPanel buildTopBarPanel() {
        JPanel topBarPanel = new JPanel(new BorderLayout(H_GAP, 0));
        topBarPanel.setBorder(BorderFactory.createEmptyBorder(0, H_PAD, 2, H_PAD));

        // Left Panel (Mic + History) (West)
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftPanel.add(micButton);
        leftPanel.add(Box.createHorizontalStrut(H_GAP));

        JButton historyButton = new JButton("History ▼");
        historyButton.setToolTipText("Select a previous instruction from history");
        historyButton.addActionListener(e -> showHistoryMenu(historyButton));
        leftPanel.add(historyButton);
        leftPanel.add(Box.createHorizontalStrut(H_GAP));

        leftPanel.add(configureModelsButton); // Add the new button here

        topBarPanel.add(leftPanel, BorderLayout.WEST);
        return topBarPanel;
    }

    private JPanel buildCenterPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));

        // Command Input Field
        JScrollPane commandScrollPane = new JScrollPane(instructionsArea);
        commandScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        commandScrollPane.setPreferredSize(new Dimension(600, 80)); // Use preferred size for layout
        commandScrollPane.setMinimumSize(new Dimension(100, 80));

        // Transparent input-overlay panel
        this.overlayPanel = new JPanel(); // Initialize the member variable
        overlayPanel.setOpaque(false); // Make it transparent
        overlayPanel.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)); // Hint text input

        // Layered pane to stack command input and overlay
        var layeredPane = new JLayeredPane();
        // Set layout manager for layered pane to handle component bounds automatically
        layeredPane.setLayout(new OverlayLayout(layeredPane)); // Or use custom layout if needed
        layeredPane.setPreferredSize(commandScrollPane.getPreferredSize()); // Match size
        layeredPane.setMinimumSize(commandScrollPane.getMinimumSize());
        layeredPane.setBorder(new EmptyBorder(0, H_PAD, 0, H_PAD));

        // Add components to layers
        layeredPane.add(commandScrollPane, JLayeredPane.DEFAULT_LAYER); // Input field at the bottom
        layeredPane.add(overlayPanel, JLayeredPane.PALETTE_LAYER); // Overlay on top

        // Mouse listener for the overlay
        overlayPanel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                activateCommandInput();
            }
        });

        panel.add(layeredPane); // Add the layered pane instead of the scroll pane directly

        // Reference-file table will be inserted just below the command input (now layeredPane)
        // by initializeReferenceFileTable()

        // Command Result
        var topInfoPanel = new JPanel();
        topInfoPanel.setLayout(new BoxLayout(topInfoPanel, BoxLayout.PAGE_AXIS));
        topInfoPanel.add(commandResultLabel);
        panel.add(topInfoPanel);

        return panel;
    }

    /**
     * Initializes the file-reference table that sits directly beneath the
     * command-input field and wires a context-menu that targets the specific
     * badge the mouse is over (mirrors ContextPanel behaviour).
     */
    private void initializeReferenceFileTable()
    {
        // ----- create the table itself --------------------------------------------------------
        referenceFileTable = new JTable(new javax.swing.table.DefaultTableModel(
                new Object[]{"File References"}, 1)
        {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return List.class;
            }
        });
        referenceFileTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        // Dynamically set row height based on renderer's preferred size
        referenceFileTable.setRowHeight(TableUtils.measuredBadgeRowHeight(referenceFileTable));

        referenceFileTable.setTableHeader(null);             // single-column ⇒ header not needed
        referenceFileTable.setShowGrid(false);
        referenceFileTable.getColumnModel()
                .getColumn(0)
                .setCellRenderer(new TableUtils.FileReferencesTableCellRenderer());

        // Clear initial content (it will be populated by context suggestions)
        referenceFileTable.setValueAt(List.of(), 0, 0);

        // ----- context-menu support -----------------------------------------------------------
        referenceFileTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e)  { 
                // Only handle popup triggers (right-click) on press
                if (e.isPopupTrigger()) {
                    ContextMenuUtils.handleFileReferenceClick(e,
                                                             referenceFileTable,
                                                             chrome,
                                                             () -> triggerContextSuggestion(null)); 
                }
            }
            
            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) { 
                // Handle both popup triggers and left-clicks on release
                ContextMenuUtils.handleFileReferenceClick(e,
                                                         referenceFileTable,
                                                         chrome,
                                                         () -> triggerContextSuggestion(null)); 
            }
        });

        // Clear selection when the table loses focus
        referenceFileTable.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                referenceFileTable.clearSelection();
            }
        });

        // ----- create failure reason label ----------------------------------------------------
        this.referenceFileTable = new JTable(new javax.swing.table.DefaultTableModel(new Object[]{"File References"}, 1) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
            @Override
            public Class<?> getColumnClass(int columnIndex) { return List.class; }
        });
        this.failureReasonLabel = new JLabel();
        this.suggestionCardLayout = new CardLayout();
        this.suggestionContentPanel = new JPanel(this.suggestionCardLayout);

        // Configure failureReasonLabel
        failureReasonLabel.setFont(referenceFileTable.getFont()); // Use same font as table/badges
        failureReasonLabel.setBorder(BorderFactory.createEmptyBorder(0, H_PAD, 0, H_PAD));
        failureReasonLabel.setVisible(false); // Initially hidden

        // Configure suggestionContentPanel
        JScrollPane localTableScrollPane = new JScrollPane(referenceFileTable);
        localTableScrollPane.setBorder(BorderFactory.createEmptyBorder());
        localTableScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        suggestionContentPanel.add(localTableScrollPane, "TABLE");
        suggestionContentPanel.add(failureReasonLabel, "LABEL");

        // ----- create container panel for button and content (table/label) -------------------
        var suggestionAreaPanel = new JPanel(new BorderLayout(H_GLUE, 0));
        suggestionAreaPanel.setBorder(BorderFactory.createEmptyBorder(V_GLUE, H_PAD, V_GLUE, H_PAD));

        // Add the Deep Scan button to the left
        suggestionAreaPanel.add(deepScanButton, BorderLayout.WEST);
        // Add the card layout panel (containing table or label) to the center
        suggestionAreaPanel.add(suggestionContentPanel, BorderLayout.CENTER);

        // Apply height constraints to the container panel
        int currentPanelRowHeight = referenceFileTable.getRowHeight(); // This now uses the dynamic height
        int fixedHeight = currentPanelRowHeight + 2; // +2 for a tiny margin for the panel itself
        suggestionAreaPanel.setPreferredSize(new Dimension(600, fixedHeight));
        suggestionAreaPanel.setMinimumSize(new Dimension(100, fixedHeight));
        // Allow panel to span horizontally, while height remains fixed.
        suggestionAreaPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, fixedHeight));

        // Insert the container panel beneath the command-input area (index 1)
        centerPanel.add(suggestionAreaPanel, 1);
    }


    private JPanel buildBottomPanel() {
        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.LINE_AXIS));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        // Add action buttons directly to the bottom panel
        bottomPanel.add(architectButton);
        bottomPanel.add(Box.createHorizontalStrut(H_GAP));
        bottomPanel.add(codeButton);
        bottomPanel.add(Box.createHorizontalStrut(H_GAP));
        bottomPanel.add(askButton);
        bottomPanel.add(Box.createHorizontalStrut(H_GAP));
        bottomPanel.add(searchButton);
        bottomPanel.add(Box.createHorizontalStrut(H_GAP));
        bottomPanel.add(runButton);

        // Set preferred size of codeButton and askButton to match agentButton
        // This needs to be done after buttons are potentially realized/packed by layout
        SwingUtilities.invokeLater(() -> {
            Dimension buttonSize = architectButton.getPreferredSize();
            if (buttonSize != null && buttonSize.width > 0 && buttonSize.height > 0) {
                codeButton.setPreferredSize(buttonSize);
                askButton.setPreferredSize(buttonSize);
                // Revalidate parent if sizes changed to ensure layout updates
                bottomPanel.revalidate();
                bottomPanel.repaint();
            }
        });

        // Add flexible space between action buttons and stop button
        bottomPanel.add(Box.createHorizontalGlue());

        // Add Stop button to the right side
        stopButton.setAlignmentY(Component.CENTER_ALIGNMENT);
        bottomPanel.add(stopButton);

        return bottomPanel;
    }


    /**
     * Builds the command result label.
     * Moved from HistoryOutputPanel.
     */
    private JLabel buildCommandResultLabel() {
        var label = new JLabel(" "); // Start with a space to ensure height
        label.setBorder(new EmptyBorder(2, H_PAD, 2, H_PAD));
        return label;
    }

    private void showHistoryMenu(Component invoker) {
        logger.trace("Showing history menu");
        JPopupMenu historyMenu = new JPopupMenu();
        var project = chrome.getProject();
        if (project == null) {
            logger.warn("Cannot show history menu: project is null");
            JMenuItem errorItem = new JMenuItem("Project not loaded");
            errorItem.setEnabled(false);
            historyMenu.add(errorItem);
        } else {
            List<String> historyItems = project.loadTextHistory();
            logger.trace("History items loaded: {}", historyItems.size());
            if (historyItems.isEmpty()) {
                JMenuItem emptyItem = new JMenuItem("(No history items)");
                emptyItem.setEnabled(false);
                historyMenu.add(emptyItem);
            } else {
                for (int i = historyItems.size() - 1; i >= 0; i--) {
                    String item = historyItems.get(i);
                    String itemWithoutNewlines = item.replace('\n', ' ');
                    String displayText = itemWithoutNewlines.length() > TRUNCATION_LENGTH
                                         ? itemWithoutNewlines.substring(0, TRUNCATION_LENGTH) + "..."
                                         : itemWithoutNewlines;
                    String escapedItem = item.replace("&", "&amp;")
                            .replace("<", "&lt;")
                            .replace(">", "&gt;")
                            .replace("\"", "&quot;");
                    JMenuItem menuItem = new JMenuItem(displayText);
                    menuItem.setToolTipText("<html><pre>" + escapedItem + "</pre></html>");
                    menuItem.addActionListener(event -> {
                        // Hide overlay and enable input field and deep scan button
                        overlayPanel.setVisible(false);
                        setCommandInputAndDeepScanEnabled(true);

                        // Set text and request focus
                        instructionsArea.setText(item);
                        commandInputUndoManager.discardAllEdits(); // Clear undo history for new text
                        instructionsArea.requestFocusInWindow();
                    });
                    historyMenu.add(menuItem);
                }
            }
        }
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(historyMenu);
        }
        historyMenu.setMinimumSize(new Dimension(DROPDOWN_MENU_WIDTH, 0));
        historyMenu.setPreferredSize(new Dimension(DROPDOWN_MENU_WIDTH, historyMenu.getPreferredSize().height));
        historyMenu.pack();
        logger.trace("Showing history menu with preferred width: {}", DROPDOWN_MENU_WIDTH);
        historyMenu.show(invoker, 0, invoker.getHeight());
    }

    /**
     * Checks if the current context managed by the ContextManager contains any image fragments.
     *
     * @return true if the top context exists and contains at least one PasteImageFragment, false otherwise.
     */
    private boolean contextHasImages() {
        var contextManager = chrome.getContextManager();
        return contextManager.topContext().allFragments()
                .anyMatch(f -> !f.isText() && !f.getType().isOutputFragment());
    }

    /**
     * Shows a modal error dialog informing the user that the required models lack vision support.
     * Offers to open the Model settings tab.
     *
     * @param requiredModelsInfo A string describing the model(s) that lack vision support (e.g., model names).
     */
    private void showVisionSupportErrorDialog(String requiredModelsInfo) {
        String message = """
                         <html>The current operation involves images, but the following selected model(s) do not support vision:<br>
                         <b>%s</b><br><br>
                         Please select vision-capable models in the settings to proceed with image-based tasks.</html>
                         """.formatted(requiredModelsInfo);
        Object[] options = {"Open Model Settings", "Cancel"};
        int choice = JOptionPane.showOptionDialog(
                chrome.getFrame(),
                message,
                "Model Vision Support Error",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.ERROR_MESSAGE,
                null, // icon
                options,
                options[0] // Default button (open settings)
        );

        if (choice == JOptionPane.YES_OPTION) { // Open Settings
            SwingUtilities.invokeLater(() -> SettingsDialog.showSettingsDialog(chrome, SettingsGlobalPanel.MODELS_TAB_TITLE));
        }
        // In either case (Settings opened or Cancel pressed), the original action is aborted by returning from the caller.
    }

    // --- Public API ---

    /**
     * Gets the current user input text. If the placeholder is currently displayed,
     * it returns an empty string, otherwise it returns the actual text content.
     */
    public String getInstructions() {
        var v = SwingUtil.runOnEdt(() -> {
            return instructionsArea.getText().equals(PLACEHOLDER_TEXT)
                   ? ""
                   : instructionsArea.getText();
        }, "");
        return castNonNull(v);
    }

    /**
     * Clears the command input field and ensures the text color is set to the standard foreground.
     * This prevents the placeholder from reappearing inadvertently.
     */
    public void clearCommandInput() {
        SwingUtilities.invokeLater(() -> {
            instructionsArea.setText("");
            commandInputUndoManager.discardAllEdits(); // Clear undo history as well
        });
    }

    public void requestCommandInputFocus() {
        SwingUtilities.invokeLater(instructionsArea::requestFocus);
    }

    /**
     * Sets the text of the command result label.
     * Moved from HistoryOutputPanel.
     */
    public void setCommandResultText(String text) {
        SwingUtilities.invokeLater(() -> commandResultLabel.setText(text));
    }

    /**
     * Clears the text of the command result label.
     * Moved from HistoryOutputPanel.
     */
    public void clearCommandResultText() {
        SwingUtilities.invokeLater(() -> commandResultLabel.setText(" ")); // Set back to space to maintain height
    }

    // --- Private Execution Logic ---

    /**
     * Called by the contextSuggestionTimer or external events (like context changes)
     * to initiate a context suggestion task. It increments the generation counter
     * and submits the task to the sequential worker executor.
     */
    private void triggerContextSuggestion(@Nullable ActionEvent e) { // ActionEvent will be null for external triggers
        var goal = getInstructions(); // Capture snapshot on EDT

        // Basic checks before submitting to worker
        if (goal.isBlank()) {
            // The DocumentListener handles clearing
            logger.trace("triggerContextSuggestion called with empty goal, not submitting task.");
            return;
        }

        // Increment generation and submit the task
        long myGen = suggestionGeneration.incrementAndGet();
        if (e == null) { // If triggered externally (e.g., context change)
            forceSuggestions.set(true);
            logger.trace("Forcing suggestion at generation {} due to external trigger", myGen);
        }
        logger.trace("Submitting suggestion task generation {}", myGen);
        suggestionWorker.submit(() -> processInputSuggestions(myGen, goal));
    }

    /**
     * Performs the actual context suggestion logic off the EDT.
     * This method includes checks against the current `suggestionGeneration`
     * to ensure only the latest task proceeds and updates the UI.
     *
     * @param myGen    The generation number of this specific task.
     * @param snapshot The input text captured when this task was initiated.
     */
    private void processInputSuggestions(long myGen, String snapshot) {
        logger.trace("Starting suggestion task generation {}", myGen);

        if (contextManager == null) {
            logger.warn("Task {} cannot provide suggestions: ContextManager is not available.", myGen);
            SwingUtilities.invokeLater(() -> showFailureLabel("Context features unavailable"));
            return;
        }

        // 0. Initial staleness check
        if (myGen != suggestionGeneration.get()) {
            logger.trace("Task {} is stale (current gen {}), aborting early.", myGen, suggestionGeneration.get());
            showPendingContext(null);
            return;
        }

        boolean currentForceState = forceSuggestions.get(); // Read the state for this task

        // Conditionally skip checks if currentForceState is true
        if (!currentForceState) {
            // 1. Quick literal check
            if (snapshot.equals(lastCheckedInputText)) {
                logger.trace("Task {} input is literally unchanged (not forced), aborting.", myGen);
                showPendingContext(null);
                return;
            }
        } else {
            logger.trace("Task {} is forced, skipping literal check.", myGen);
        }

        // 2. Embedding Model Check (This check MUST run even if forced, as we need the model)
        if (!Brokk.embeddingModelFuture.isDone()) {
            SwingUtilities.invokeLater(() -> showFailureLabel("Waiting for model download"));
            logger.trace("Task {} waiting for model.", myGen);
            return; // Don't proceed further until model is ready
        }
        AbstractModel embeddingModel;
        try {
            embeddingModel = Brokk.embeddingModelFuture.get();
            assert embeddingModel != null;
        } catch (ExecutionException | InterruptedException ex) {
            logger.error("Task {} failed to get embedding model", myGen, ex);
            SwingUtilities.invokeLater(() -> showFailureLabel("Error loading embedding model"));
            return;
        }

        // 3. Staleness check before embedding
        if (myGen != suggestionGeneration.get()) {
            logger.trace("Task {} is stale before embedding, aborting.", myGen);
            showPendingContext(null);
            return;
        }

        // 4. Compute Embeddings
        var chunks = Arrays.stream(snapshot.split("[.\\n]"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
        float[][] newEmbeddings = chunks.isEmpty()
                                  ? new float[0][]
                                  : chunks.stream()
                                          .map(chunk -> embeddingModel.embed(chunk, Generator.PoolingType.AVG))
                                          .toArray(float[][]::new);

        // 5. Staleness check after embedding
        if (myGen != suggestionGeneration.get()) {
            logger.trace("Task {} is stale after embedding, aborting.", myGen);
            showPendingContext(null);
            return;
        }

        if (!currentForceState) {
            // 6. Semantic Comparison
            boolean isDifferent = isSemanticallyDifferent(snapshot, newEmbeddings);

            if (!isDifferent) {
                logger.trace("Task {} input is semantically similar (not forced), aborting ContextAgent.", myGen);
                showPendingContext(null);
                return;
            }
        } else {
            logger.trace("Task {} is forced, skipping semantic similarity check.", myGen);
        }

        // 8. Run ContextAgent
        logger.debug("Task {} fetching QUICK context recommendations for: '{}'", myGen, snapshot);
        var model = contextManager.getService().quickestModel();
        ContextAgent.RecommendationResult recommendations;
        try {
            ContextAgent agent = new ContextAgent(contextManager, model, snapshot, false);
            recommendations = agent.getRecommendations(false);

            // 10. Process results
            if (!recommendations.success()) {
                logger.debug("Task {} quick context suggestion failed: {}", myGen, recommendations.reasoning());
                showPendingContext(recommendations.reasoning());
                return;
            }

            var fileRefs = recommendations.fragments().stream()
                    .flatMap(f -> f.files().stream()) // No analyzer
                    .distinct()
                    .map(pf -> new FileReferenceData(pf.getFileName(), pf.toString(), (ProjectFile) pf)) // Cast to ProjectFile
                    .toList();
            if (fileRefs.isEmpty()) {
                logger.debug("Task {} found no relevant files.", myGen);
                showPendingContext("No quick suggestions");
                return;
            }

            // Update the UI with our new recommendations, or save them for the next task to use
            logger.debug("Task {} updating quick reference table with {} suggestions", myGen, fileRefs.size());
            if (myGen == suggestionGeneration.get()) {
                SwingUtilities.invokeLater(() -> showSuggestionsTable(fileRefs));
                pendingQuickContext = null;
            } else {
                pendingQuickContext = fileRefs;
            }

            // Set our snapshot as the new semantic baseline
            this.lastCheckedInputText = snapshot;
            this.lastCheckedEmbeddings = newEmbeddings;
        } catch (InterruptedException ex) {
            // shouldn't happen
            throw new RuntimeException(ex);
        } finally {
            if (currentForceState) {
                forceSuggestions.set(false);
                logger.trace("Task {} cleared forceSuggestions.", myGen);
            }
        }
    }

    private void showPendingContext(@Nullable String failureExplanation) {
        // do this on the serial task thread, before we move to the EDT
        var contextToDisplay = pendingQuickContext;
        pendingQuickContext = null;

        SwingUtilities.invokeLater(() -> {
            if (contextToDisplay != null) {
                showSuggestionsTable(contextToDisplay);
            } else if (failureExplanation != null) {
                showFailureLabel(failureExplanation);
            }
        });
    }

    /**
     * Checks if the new text/embeddings are semantically different from the
     * last processed state (`lastCheckedInputText`, `lastCheckedEmbeddings`).
     */
    private boolean isSemanticallyDifferent(String currentText, float[][] newEmbeddings) {
        if (lastCheckedInputText == null || lastCheckedEmbeddings == null) {
            // First run or state was reset. Treat as different and store the new embeddings.
            logger.debug("New embeddings input is trivially different from empty old");
            return true;
        }

        // Compare lengths
        if (newEmbeddings.length != lastCheckedEmbeddings.length) {
            logger.debug("New embeddings length differs from last checked embeddings length.");
            return true;
        }

        // Compare pairwise cosine similarity
        final float SIMILARITY_THRESHOLD = 0.85f;
        float minSimilarity = Float.MAX_VALUE;
        for (int i = 0; i < newEmbeddings.length; i++) {
            float similarity = cosine(newEmbeddings[i], lastCheckedEmbeddings[i]);
            if (similarity < minSimilarity) {
                minSimilarity = similarity;
            }
            if (similarity < SIMILARITY_THRESHOLD) {
                var msg = """
                New embeddings similarity = %.3f, triggering recompute
                
                # Old text
                %s
                
                # New text
                %s
                """.formatted(similarity, lastCheckedInputText, currentText);
                logger.debug(msg);
                return true;
            }
        }

        logger.debug("Minimum similarity was {}", minSimilarity);

        // If lengths match and all similarities are above threshold, it's not different enough.
        // Do NOT update lastCheckedEmbeddings here, keep the previous ones for the next comparison.
        return false;
    }

    /**
     * Helper to show the failure label with a message.
     */
    private void showFailureLabel(String message) {
        boolean isDark = UIManager.getBoolean("laf.dark");
        failureReasonLabel.setForeground(ThemeColors.getColor(isDark, "badge_foreground"));
        failureReasonLabel.setText(message);
        failureReasonLabel.setVisible(true);
        // tableScrollPane was made local to initializeReferenceFileTable, find it via parent of referenceFileTable
        var scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, referenceFileTable);
        if (scrollPane != null) {
            scrollPane.setVisible(false); // Ensure table scrollpane is hidden
        }
        referenceFileTable.setValueAt(List.of(), 0, 0); // Clear table data
        suggestionCardLayout.show(suggestionContentPanel, "LABEL"); // Show label
    }

    /**
     * Helper to show the suggestions table with file references.
     */
    private void showSuggestionsTable(List<FileReferenceData> fileRefs) {
        referenceFileTable.setValueAt(fileRefs, 0, 0);
        failureReasonLabel.setVisible(false);
        var scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, referenceFileTable);
        if (scrollPane != null) {
            scrollPane.setVisible(true); // Ensure table scrollpane is visible
        }
        suggestionCardLayout.show(suggestionContentPanel, "TABLE"); // Show table
    }

    /**
     * Triggered by the "Deep Scan" button click.
     * Runs the Deep Scan agents and shows the results dialog.
     * Delegates the core logic to the DeepScanDialog class.
     */
    private void triggerDeepScan(ActionEvent e) {
        var goal = getInstructions();
        if (contextManager == null || contextManager.getProject() == null) {
            chrome.toolError("Deep Scan requires a project and ContextManager to be active.");
            deepScanButton.setEnabled(false);
            return;
        }
        deepScanButton.setLoading(true, "Scanning…");

        DeepScanDialog.triggerDeepScan(chrome, goal)
            .whenComplete((v, throwable) -> {
                // This callback runs when the analysis phase (ContextAgent, ValidationAgent) is complete.
                SwingUtilities.invokeLater(() -> {
                    deepScanButton.setLoading(false, null); // Restores button state

                    if (throwable != null) {
                        if (throwable instanceof InterruptedException ||
                            (throwable.getCause() instanceof InterruptedException)) {
                            logger.info("Deep Scan analysis was cancelled or interrupted.");
                        } else {
                            logger.error("Deep Scan analysis failed.", throwable);
                            chrome.toolError("Deep Scan analysis encountered an error: " + throwable.getMessage());
                        }
                    }
                    this.contextManager.submitBackgroundTask("Post Deep Scan: Balance Check", this::checkBalanceAndNotify);
                    notifyActionComplete("Deep Scan"); // General notification that the "Deep Scan" action initiated here has concluded its primary phase.
                });
            });
    }

    /**
     * Checks the user's balance if using the Brokk proxy and displays a notification
     * if the balance is low.
     */
    public void checkBalanceAndNotify() {
        if (MainProject.getProxySetting() != MainProject.LlmProxySetting.BROKK) {
            return; // Only check balance when using Brokk proxy
        }

        var contextManager = chrome.getContextManager();
        contextManager.submitBackgroundTask("", () -> {
            try {
                float balance = contextManager.getService().getUserBalance();
                logger.debug("Checked balance: ${}", String.format("%.2f", balance));

                // If balance drops below the minimum paid threshold, reinitialize models to enforce free tier
                if (balance < Service.MINIMUM_PAID_BALANCE) {
                    logger.debug("Balance below minimum paid threshold (${}), reinitializing models to free tier.", Service.MINIMUM_PAID_BALANCE);
                    // This will refetch models and apply the lowBalance filter based on MINIMUM_PAID_BALANCE
                    contextManager.reloadModelsAsync();

                    SwingUtilities.invokeLater(() -> {
                        if (freeTierNotified) {
                            // Only show the dialog once unless balance recovers
                            return;
                        }

                        freeTierNotified = true;
                        var panel = new JPanel();
                        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
                        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

                        panel.add(new JLabel("Brokk is running in the free tier. Only low-cost models are available."));
                        panel.add(Box.createVerticalStrut(5));
                        var label = new JLabel("To enable smarter models, subscribe or top up at:");
                        panel.add(label);
                        var browserLabel = new BrowserLabel(Service.TOP_UP_URL);
                        browserLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                        label.setAlignmentX(Component.LEFT_ALIGNMENT);
                        panel.add(browserLabel);

                        JOptionPane.showMessageDialog(
                                chrome.getFrame(),
                                panel,
                                "Balance Exhausted",
                                JOptionPane.WARNING_MESSAGE
                        );
                    });
                } else if (balance < Service.LOW_BALANCE_WARN_AT) {
                    if (lowBalanceNotified) {
                        // Only show the dialog once unless balance recovers
                        return;
                    }

                    lowBalanceNotified = true;
                    SwingUtilities.invokeLater(() -> {
                        var panel = new JPanel(new BorderLayout(0, V_GAP)); // Panel for text and link
                        var balanceMessage = String.format("Low account balance: $%.2f.", balance);
                        panel.add(new JLabel(balanceMessage), BorderLayout.NORTH);

                        var browserLabel = new io.github.jbellis.brokk.gui.components.BrowserLabel(Service.TOP_UP_URL, "Top up at " + Service.TOP_UP_URL + " to avoid interruptions.");
                        panel.add(browserLabel, BorderLayout.SOUTH);

                        JOptionPane.showMessageDialog(
                                chrome.getFrame(),
                                panel,
                                "Low Balance Warning",
                                JOptionPane.WARNING_MESSAGE);
                    });
                } else {
                    // reset the notification flag
                    lowBalanceNotified = false;
                }
            } catch (java.io.IOException e) {
                logger.error("Failed to check user balance", e);
            }
        });
    }


    /**
     * Executes the core logic for the "Code" command.
     * This runs inside the Runnable passed to contextManager.submitUserTask.
     */
    private void executeCodeCommand(StreamingChatLanguageModel model, String input) {
        var contextManager = chrome.getContextManager();

        contextManager.getAnalyzerWrapper().pause();
        try {
            var result = new CodeAgent(contextManager, model).runTask(input, false);
            if (result.stopDetails().reason() == TaskResult.StopReason.INTERRUPTED) {
                chrome.systemOutput("Code Agent cancelled!");
                // Save the partial result (if we didn't interrupt before we got any replies)
                if (result.output().messages().stream().anyMatch(m -> m instanceof AiMessage)) {
                    chrome.setSkipNextUpdateOutputPanelOnContextChange(true);
                    contextManager.addToHistory(result, false);
                }
                repopulateInstructionsArea(input);
            } else {
                if (result.stopDetails().reason() == TaskResult.StopReason.SUCCESS) {
                    chrome.systemOutput("Code Agent complete!");
                }
                // Code agent has logged error to console already
                chrome.setSkipNextUpdateOutputPanelOnContextChange(true);
                contextManager.addToHistory(result, false);
            }
        } finally {
            contextManager.getAnalyzerWrapper().resume();
        }
    }

    /**
     * Executes the core logic for the "Ask" command.
     * This runs inside the Runnable passed to contextManager.submitAction.
     */
    private void executeAskCommand(StreamingChatLanguageModel model, String question) {
        try {
            var contextManager = chrome.getContextManager();
            if (question.isBlank()) {
                chrome.toolError("Please provide a question");
                return;
            }

            // stream from coder using the provided model
            var messages = CodePrompts.instance.collectAskMessages(contextManager, question);
            var response = contextManager.getLlm(model, "Ask: " + question).sendRequest(messages, true);
            if (response.error() != null) {
                chrome.toolError("Error during 'Ask': " + response.error().getMessage());
            } else if (response.isEmpty()) {
                chrome.systemOutput("Ask command completed with no = data.");
            } else {
                // Construct SessionResult for 'Ask'
                var sessionResult = new TaskResult(contextManager,
                                                   "Ask: " + question,
                                                   List.copyOf(chrome.getLlmRawMessages()),
                                                   Map.of(), // No undo contents for Ask
                                                   new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS));
                chrome.setSkipNextUpdateOutputPanelOnContextChange(true);
                contextManager.addToHistory(sessionResult, false);
                chrome.systemOutput("Ask command complete!");
            }
        } catch (InterruptedException e) {
            chrome.systemOutput("Ask command cancelled!");
            // Check if we have any partial output to save
                maybeAddInterruptedResult("Ask", question);
            }
        }

        private void maybeAddInterruptedResult(String action, String input) {
            if (chrome.getLlmRawMessages().stream().anyMatch(m -> m instanceof AiMessage)) {
                logger.debug(action + " command cancelled with partial results");
                var sessionResult = new TaskResult("%s (Cancelled): %s".formatted(action, input),
                                                   new TaskFragment(chrome.getContextManager(), List.copyOf(chrome.getLlmRawMessages()), input),
                                                   Map.of(),
                                                   new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED));
                chrome.getContextManager().addToHistory(sessionResult, false);
            }
            repopulateInstructionsArea(input);
        }

    /**
     * Executes the core logic for the "Agent" command.
     * This runs inside the Runnable passed to contextManager.submitAction.
     *
     * @param goal    The initial user instruction passed to the agent.
     * @param options The configured options for the agent's tools.
     */
    private void executeAgentCommand(StreamingChatLanguageModel model, String goal, ArchitectAgent.ArchitectOptions options) {
        var contextManager = chrome.getContextManager();
        try {
            // Pass options to the constructor
            var agent = new ArchitectAgent(contextManager, model, contextManager.getToolRegistry(), goal, options);
            var result = agent.execute();
            chrome.systemOutput("Architect complete!");
            contextManager.addToHistory(result, false);
        } catch (InterruptedException e) {
            chrome.systemOutput("Architect Agent cancelled!");
            maybeAddInterruptedResult("Architect", goal);
        } catch (Exception e) {
            logger.error("Error during Agent execution", e);
            chrome.toolError("Internal error during Agent command: " + e.getMessage());
        }
    }

    /**
     * Executes the core logic for the "Search" command.
     * This runs inside the Runnable passed to contextManager.submitAction.
     */
    private void executeSearchCommand(StreamingChatLanguageModel model, String query) {
        if (query.isBlank()) {
            chrome.toolError("Please provide a search query");
            return;
        }
        try {
            var contextManager = chrome.getContextManager();
            var agent = new SearchAgent(query, contextManager, model, contextManager.getToolRegistry(), 0);
            var result = agent.execute();
            assert result != null;
            // Search does not stream to llmOutput, so add the final answer here
            
            chrome.setSkipNextUpdateOutputPanelOnContextChange(true);
            contextManager.addToHistory(result, false);
            chrome.systemOutput("Search complete!");
        } catch (InterruptedException e) {
            chrome.toolError("Search agent cancelled without answering");
            repopulateInstructionsArea(query);
        }
    }

    /**
     * Executes the core logic for the "Run in Shell" command.
     * This runs inside the Runnable passed to contextManager.submitAction.
     */
    private void executeRunCommand(String input) {
        var contextManager = chrome.getContextManager();
        String actionMessage = "Run: " + input;

        try {
            chrome.showOutputSpinner("Executing command...");
            chrome.llmOutput("\n```bash\n", ChatMessageType.CUSTOM);
            Environment.instance.runShellCommand(input,
                                                 contextManager.getRoot(),
                                                 line -> chrome.llmOutput(line + "\n", ChatMessageType.CUSTOM));
            chrome.llmOutput("\n```", ChatMessageType.CUSTOM); // Close markdown block on success
            chrome.systemOutput("Run command complete!");
        } catch (Environment.SubprocessException e) {
            chrome.llmOutput("\n```", ChatMessageType.CUSTOM); // Ensure markdown block is closed on error
            actionMessage = "Run: " + input + " (failed: " + e.getMessage() + ")";
            chrome.systemOutput("Run command completed with errors -- see Output");
            logger.warn("Run command '{}' failed: {}", input, e.getMessage(), e);
            chrome.llmOutput("\n**Command Failed**", ChatMessageType.CUSTOM);
        } catch (InterruptedException e) {
            // If interrupted, the ```bash block might be open.
            // It's tricky to know if llmOutput for closing ``` is safe or needed here.
            // For now, just log and return, consistent with previous behavior for interruption.
            chrome.systemOutput("Cancelled!");
            repopulateInstructionsArea(input);
            // No action needed for context history on cancellation here
            return;
        } finally {
            chrome.hideOutputSpinner();
        }

        // Add to context history with the action message (which includes success/failure)
        final String finalActionMessage = actionMessage; // Effectively final for lambda
        contextManager.pushContext(ctx -> {
            var parsed = new TaskFragment(chrome.getContextManager(), List.copyOf(chrome.getLlmRawMessages()), finalActionMessage);
                return ctx.withParsedOutput(parsed, CompletableFuture.completedFuture(finalActionMessage));
            });
    }

    // --- Action Handlers ---

    public void runArchitectCommand() {
        var goal = getInstructions();
        if (goal.isBlank()) {
            chrome.toolError("Please provide an initial goal or instruction for the Architect");
            return;
        }

        var contextManager = chrome.getContextManager();
        var models = contextManager.getService();
        var architectModel = contextManager.getArchitectModel();
        var codeModel = contextManager.getCodeModel(); // For architect's sub-agents
        var searchModel = contextManager.getSearchModel(); // For architect's sub-agents

        // Check vision capabilities only if running in current project
        if (contextHasImages()) {
            var nonVisionModels = Stream.of(architectModel, codeModel, searchModel) // Check all models Architect might use
                                        .filter(m -> !models.supportsVision(m))
                                        .map(models::nameOf)
                                        .distinct() // Avoid duplicate model names if they are the same
                                        .toList();
            if (!nonVisionModels.isEmpty()) {
                showVisionSupportErrorDialog(String.join(", ", nonVisionModels));
                return; // Abort if any required model lacks vision and context has images
            }
        }

        chrome.getProject().addToInstructionsHistory(goal, 20);

        // Show the options dialog synchronously on the EDT. This blocks until the user clicks OK/Cancel.
        ArchitectChoices choices = ArchitectOptionsDialog.showDialogAndWait(chrome);

        // If the user cancelled the dialog, choices will be null.
        if (choices == null) {
            chrome.systemOutput("Architect command cancelled during option selection.");
            enableButtons(); // Re-enable buttons since the action was cancelled before submission
            return;
        }

        clearCommandInput();

        if (choices.runInWorktree()) {
            runArchitectInNewWorktree(goal, choices.options());
        } else {
            // User confirmed options, now submit the actual agent execution to the background.
            runArchitectCommand(goal, choices.options());
        }
    }

    private void runArchitectInNewWorktree(String originalInstructions, ArchitectAgent.ArchitectOptions options) {
        var currentProject = chrome.getProject();
        ContextManager cm = chrome.getContextManager();

        // Start branch name generation task (LLM summarization)
        var branchNameWorker = new ContextManager.SummarizeWorker(cm, originalInstructions, 3);
        branchNameWorker.execute();

        // Add to history of current project (already done by caller if not worktree)
        // No need to clearCommandInput, also done by caller

        // Submit the entire worktree setup and eventual Architect run as a background task
        cm.submitUserTask("Setup Architect Worktree", true, () -> {
            try {
                chrome.showOutputSpinner("Setting up Git worktree...");

                // Retrieve the generated branch name suggestion from the SummarizeWorker
                String rawBranchNameSuggestion = branchNameWorker.get(); // Blocks until SummarizeWorker is done
                String generatedBranchName = cm.getRepo().sanitizeBranchName(rawBranchNameSuggestion);

                // Check Git availability (original position relative to setup)
                // This check is also done in ArchitectOptionsDialog for the checkbox,
                // but good to have a safeguard here.
                if (!currentProject.hasGit() || !currentProject.getRepo().supportsWorktrees()) {
                    chrome.hideOutputSpinner();
                    chrome.toolError("Cannot create worktree: Project is not a Git repository or worktrees are not supported.");
                    repopulateInstructionsArea(originalInstructions); // Restore instructions if setup fails
                    return;
                }

                Path newWorktreePath;
                String actualBranchName;

                IProject projectForWorktreeSetup = currentProject.getParent();
                GitRepo mainGitRepo = (GitRepo) projectForWorktreeSetup.getRepo();
                String sourceBranchForNew = mainGitRepo.getCurrentBranch(); // New branch is created from current branch of main repo

                var setupResult = GitWorktreeTab.setupNewGitWorktree(
                                                                     (MainProject) projectForWorktreeSetup,
                                                                     mainGitRepo,
                                                                     generatedBranchName,
                                                                     true, // Always creating a new branch in this flow
                                                                     sourceBranchForNew);
                newWorktreePath = setupResult.worktreePath();
                actualBranchName = setupResult.branchName();

                chrome.systemOutput("New worktree created at: " + newWorktreePath + " on branch: " + actualBranchName);

                // Define the initial task to run in the new project, using pre-collected options
                Consumer<Chrome> initialArchitectTask = newWorktreeChrome -> {
                    InstructionsPanel newWorktreeIP = newWorktreeChrome.getInstructionsPanel();
                    // Run the architect command directly with the original instructions and determined options
                    newWorktreeIP.runArchitectCommand(originalInstructions, options);
                };

                MainProject mainProject = (currentProject instanceof MainProject mainProj)
                                                ? mainProj
                                                : (MainProject) currentProject.getParent();
        assert mainProject != null;

        new Brokk.OpenProjectBuilder(newWorktreePath)
                        .parent(mainProject)
                        .initialTask(initialArchitectTask)
                        .sourceContextForSession(cm.topContext())
                        .open()
                        .thenAccept(success ->
                {
                    if (Boolean.TRUE.equals(success)) {
                        chrome.systemOutput("New worktree opened for Architect");
                    } else {
                        chrome.toolError("Failed to open the new worktree project for Architect.");
                        repopulateInstructionsArea(originalInstructions);
                    }
                }).exceptionally(ex -> {
                    chrome.toolError("Error opening new worktree project: " + ex.getMessage());
                    repopulateInstructionsArea(originalInstructions);
                    return null;
                });
            } catch (InterruptedException e) {
                logger.warn("Architect worktree setup interrupted.", e);
                chrome.systemOutput("Architect worktree setup was cancelled.");
                repopulateInstructionsArea(originalInstructions);
            } catch (GitAPIException | IOException | ExecutionException ex) {
                chrome.toolError("Error setting up worktree: " + ex.getMessage());
                repopulateInstructionsArea(originalInstructions);
            } finally {
                chrome.hideOutputSpinner();
            }
        });
    }

    /**
     * Overload for programmatic invocation of Architect agent after options are determined,
     * typically called directly or from the worktree setup.
     *
     * @param goal The user's goal/instructions.
     * @param options The pre-configured ArchitectOptions.
     */
    public void runArchitectCommand(String goal, ArchitectAgent.ArchitectOptions options) {
        var contextManager = chrome.getContextManager();
        var architectModel = contextManager.getArchitectModel();

        submitAction(ACTION_ARCHITECT, goal, () -> {
            // Proceed with execution using the selected options
            executeAgentCommand(architectModel, goal, options);
        });
    }

    // Methods for running commands. These prepare the input and model, then delegate
    // the core logic execution to contextManager.submitAction, which calls back
    // into the private execute* methods above.

    // Public entry point for default Code model
    public void runCodeCommand() {
        var contextManager = chrome.getContextManager();
        prepareAndRunCodeCommand(contextManager.getCodeModel());
    }

    // Public entry point for selected Code model from SplitButton
    public void runCodeCommand(StreamingChatLanguageModel modelToUse) {
        prepareAndRunCodeCommand(modelToUse);
    }

    // Core method to prepare and submit the Code action
    private void prepareAndRunCodeCommand(StreamingChatLanguageModel modelToUse) {
        var input = getInstructions();
        if (input.isBlank()) {
            chrome.toolError("Please enter a command or text");
            return;
        }

        var contextManager = chrome.getContextManager();
        var models = contextManager.getService();

        if (contextHasImages() && !models.supportsVision(modelToUse)) {
            showVisionSupportErrorDialog(models.nameOf(modelToUse) + " (Code)");
            return;
        }

        chrome.getProject().addToInstructionsHistory(input, 20);
        clearCommandInput();
        // disableButtons() is called by submitAction via chrome.disableActionButtons()
        submitAction(ACTION_CODE, input, () -> executeCodeCommand(modelToUse, input));
    }

    // Public entry point for default Ask model
    public void runAskCommand() {
        var contextManager = chrome.getContextManager();
        prepareAndRunAskCommand(contextManager.getAskModel());
    }

    // Public entry point for selected Ask model from SplitButton
    public void runAskCommand(StreamingChatLanguageModel modelToUse) {
        prepareAndRunAskCommand(modelToUse);
    }

    // Core method to prepare and submit the Ask action
    private void prepareAndRunAskCommand(StreamingChatLanguageModel modelToUse) {
        var input = getInstructions();
        if (input.isBlank()) {
            chrome.toolError("Please enter a question");
            return;
        }

        var contextManager = chrome.getContextManager();
        var models = contextManager.getService();

        if (contextHasImages() && !models.supportsVision(modelToUse)) {
            showVisionSupportErrorDialog(models.nameOf(modelToUse) + " (Ask)");
            return;
        }

        chrome.getProject().addToInstructionsHistory(input, 20);
        clearCommandInput();
        // disableButtons() is called by submitAction via chrome.disableActionButtons()
        submitAction(ACTION_ASK, input, () -> executeAskCommand(modelToUse, input));
    }

    public void runSearchCommand() {
        var input = getInstructions();
        if (input.isBlank()) {
            chrome.toolError("Please provide a search query");
            return;
        }

        var contextManager = chrome.getContextManager();
        var models = contextManager.getService();
        var searchModel = contextManager.getSearchModel();

        if (contextHasImages() && !models.supportsVision(searchModel)) {
            showVisionSupportErrorDialog(models.nameOf(searchModel) + " (Search)");
            return; // Abort if model doesn't support vision and context has images
        }

        chrome.getProject().addToInstructionsHistory(input, 20);
        // Update the LLM output panel directly via Chrome
        chrome.llmOutput("# Please be patient\n\nBrokk makes multiple requests to the LLM while searching. Progress is logged in System Messages below.", ChatMessageType.USER);
        clearCommandInput();
        disableButtons();
        // Submit the action, calling the private execute method inside the lambda
        submitAction(ACTION_SEARCH, input, () -> {
            executeSearchCommand(searchModel, input);
        });
    }

    public void runRunCommand() {
        var input = getInstructions();
        if (input.isBlank()) {
            chrome.toolError("Please enter a command to run", "Error");
            return;
        }
        chrome.getProject().addToInstructionsHistory(input, 20);
        clearCommandInput();
        disableButtons();
        // Submit the action, calling the private execute method inside the lambda
        submitAction(ACTION_RUN, input, () -> executeRunCommand(input));
    }

    /**
     * sets the llm output to indicate the action has started, and submits the task on the user pool
     */
    public Future<?> submitAction(String action, String input, Runnable task) {
        var cm = chrome.getContextManager();
        // need to set the correct parser here since we're going to append to the same fragment during the action
        String finalAction = (action + " MODE").toUpperCase(Locale.ROOT);
        chrome.setLlmOutput(new ContextFragment.TaskFragment(cm, cm.getParserForWorkspace(), List.of(new UserMessage(finalAction, input)), input));
        return cm.submitUserTask(finalAction, true, () -> {
            try {
                chrome.showOutputSpinner("Executing " + action + " command...");
                task.run();
            } finally {
                chrome.hideOutputSpinner();
                checkBalanceAndNotify();
                notifyActionComplete(action);
            }
        });
    }

    // Methods to disable and enable buttons.
    public void disableButtons() {
        SwingUtilities.invokeLater(() -> {
            architectButton.setEnabled(false);
            codeButton.setEnabled(false);
            askButton.setEnabled(false);
            searchButton.setEnabled(false);
            runButton.setEnabled(false);
            deepScanButton.setEnabled(false);
            stopButton.setEnabled(true);
            chrome.disableHistoryPanel();
        });
    }

    /**
     * Updates the enabled state of all action buttons based on project load status
     * and ContextManager availability. Called when actions complete.
     */
    private void updateButtonStates() {
        SwingUtilities.invokeLater(() -> {
            boolean projectLoaded = chrome.getProject() != null;
            boolean cmAvailable = this.contextManager != null;
            boolean gitAvailable = projectLoaded && chrome.getProject().hasGit();
            // boolean worktreesSupported = gitAvailable && chrome.getProject().getRepo().supportsWorktrees(); // No longer needed here

            // Architect Button
            architectButton.setEnabled(projectLoaded && cmAvailable);
            if (projectLoaded && cmAvailable) {
                 architectButton.setToolTipText("Run the multi-step agent (options include worktree setup)");
            } else {
                 architectButton.setToolTipText("Architect agent unavailable (project/CM not ready)");
            }
            // Worktree option is now part of ArchitectOptionsDialog

            // Code Button
            if (projectLoaded && !gitAvailable) {
                codeButton.setEnabled(false);
                codeButton.setToolTipText("Code feature requires Git integration for this project.");
            } else {
                codeButton.setEnabled(projectLoaded && cmAvailable);
                // Default tooltip is set during initialization, no need to reset unless it changed
            }

            askButton.setEnabled(projectLoaded && cmAvailable);
            searchButton.setEnabled(projectLoaded && cmAvailable);
            runButton.setEnabled(cmAvailable); // Requires CM for getRoot()
            // Enable deepScanButton only if instructionsArea is also enabled
            deepScanButton.setEnabled(projectLoaded && cmAvailable && instructionsArea.isEnabled());
            // Stop is only enabled when an action is running
            stopButton.setEnabled(false);

            if (projectLoaded && cmAvailable) {
                chrome.enableHistoryPanel();
            } else {
                chrome.disableHistoryPanel();
            }
        });
    }

    @Override
    public void contextChanged(Context newCtx) {
        // Otherwise, proceed with the normal suggestion logic by submitting a task
        logger.debug("Context changed externally, triggering suggestion check.");
        triggerContextSuggestion(null); // Use null ActionEvent to indicate non-timer trigger
    }

    public void enableButtons() {
        // Called when an action completes. Reset buttons based on current CM/project state.
        updateButtonStates();
    }

    private void notifyActionComplete(String actionName) {
        chrome.notifyActionComplete("Action '" + actionName + "' completed.");
    }

    private void repopulateInstructionsArea(String originalText) {
        SwingUtilities.invokeLater(() -> {
            // If placeholder is active or area is disabled, activate input first
            if (instructionsArea.getText().equals(PLACEHOLDER_TEXT) || !instructionsArea.isEnabled()) {
                activateCommandInput(); // This enables, clears placeholder, requests focus
            }
            instructionsArea.setText(originalText);
            commandInputUndoManager.discardAllEdits(); // Reset undo history for the repopulated content
            instructionsArea.requestFocusInWindow(); // Ensure focus after text set
            instructionsArea.setCaretPosition(originalText.length()); // Move caret to end
        });
    }

    /**
     * Hides the command input overlay, enables the input field and deep scan button,
     * clears the placeholder text if present, and requests focus for the input field.
     */
    private void activateCommandInput() {
        overlayPanel.setVisible(false); // Hide the overlay
        setCommandInputAndDeepScanEnabled(true); // Enable input and deep scan button
        // Clear placeholder only if it's still present
        if (instructionsArea.getText().equals(PLACEHOLDER_TEXT)) {
            clearCommandInput();
        }
        instructionsArea.requestFocusInWindow(); // Give it focus
    }

    /**
     * Sets the enabled state for both the command input field and the Deep Scan button.
     *
     * @param enabled true to enable, false to disable.
     */
    void setCommandInputAndDeepScanEnabled(boolean enabled) {
        instructionsArea.setEnabled(enabled);
        this.deepScanButton.setEnabled(enabled);
    }

    /**
     * Returns cosine similarity of two equal-length vectors.
     */
    private static float cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors differ in length");
        }
        if (a.length == 0) {
            throw new IllegalArgumentException("Vectors must have at least one element");
        }

        double dot = 0.0;
        double magA = 0.0;
        double magB = 0.0;

        for (int i = 0; i < a.length; i++) {
            double x = a[i];
            double y = b[i];
            dot += x * y;
            magA += x * x;
            magB += y * y;
        }

        double denominator = Math.sqrt(magA) * Math.sqrt(magB);
        if (denominator == 0.0) {
            throw new IllegalArgumentException("One of the vectors is zero-length");
        }

        return (float) (dot / denominator);
    }

    /**
     * Creates a JPopupMenu displaying favorite models that are currently available.
     * When a favorite model is selected, the provided consumer is called with the
     * model name and its associated reasoning level from the favorite model configuration.
     *
     * @param onModelSelect The consumer to call when a favorite model is selected.
     *                      Receives the model name and the reasoning level configured for that favorite.
     * @return A JPopupMenu containing available favorite models or configuration options.
     */
    private JPopupMenu createModelSelectionMenu(BiConsumer<String, Service.ReasoningLevel> onModelSelect)
    {
        var popupMenu = new JPopupMenu();
        if (this.contextManager == null) {
            var item = new JMenuItem("Models unavailable (ContextManager not ready)");
            item.setEnabled(false);
            popupMenu.add(item);
            if (chrome.themeManager != null) {
                chrome.themeManager.registerPopupMenu(popupMenu);
            }
            return popupMenu;
        }

        var modelsInstance = this.contextManager.getService();
        var availableModelsMap = modelsInstance.getAvailableModels(); // Get all available models

        // Cast the result of loadFavoriteModels and ensure it's handled correctly
        var favoriteModels = MainProject.loadFavoriteModels();

        // Filter favorite models to show only those that are currently available, and sort by alias
        var favoriteModelsToShow = favoriteModels.stream()
                .filter(fav -> availableModelsMap.containsKey(fav.modelName()))
                .sorted(Comparator.comparing(Service.FavoriteModel::alias))
                .toList();

        if (favoriteModelsToShow.isEmpty()) {
            var item = new JMenuItem("(No favorite models available)"); // Updated message
            item.setEnabled(false); // Keep it disabled as it's just info
            popupMenu.add(item);
            popupMenu.addSeparator();
            var configureItem = new JMenuItem("Configure Favorites...");
            configureItem.addActionListener(e -> SettingsDialog.showSettingsDialog(chrome, SettingsGlobalPanel.MODELS_TAB_TITLE));
            popupMenu.add(configureItem);
        } else {
            favoriteModelsToShow.forEach(fav -> {
                var item = new JMenuItem(fav.alias());
                 // Add a tooltip showing model name and reasoning level
                item.setToolTipText("<html>Model: " + fav.modelName() + "<br>Reasoning: " + fav.reasoning().toString() + "</html>");
                item.addActionListener(e -> onModelSelect.accept(fav.modelName(), fav.reasoning()));
                popupMenu.add(item);
            });
        }

        // Apply theme to the popup menu itself and its items
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(popupMenu);
        }
        return popupMenu;
    }

    private final class AtTriggerFilter extends DocumentFilter {
        private boolean isPopupOpen = false; // Guard against re-entrant calls

        @Override
        public void insertString(FilterBypass fb, int offs, String str, AttributeSet a)
                throws BadLocationException {
            super.insertString(fb, offs, str, a);
            if (!isPopupOpen) {
                maybeHandleAt(fb, offs + str.length());
            }
        }

        @Override
        public void replace(FilterBypass fb, int offs, int len, String str, AttributeSet a)
                throws BadLocationException {
            super.replace(fb, offs, len, str, a);
            if (!isPopupOpen) {
                maybeHandleAt(fb, offs + str.length());
            }
        }

        private void maybeHandleAt(DocumentFilter.FilterBypass fb, int caretPos) {
            try {
                if (fb.getDocument().getLength() >= caretPos && caretPos > 0 &&
                    fb.getDocument().getText(caretPos - 1, 1).equals("@")) {
                    // Schedule popup display on EDT
                    SwingUtilities.invokeLater(() -> showAddPopup(caretPos - 1));
                }
            } catch (BadLocationException ignored) {
                // Ignore, means text was changing rapidly
            }
        }

        private void showAddPopup(int atOffset) {
            if (isPopupOpen) return; // Already showing one

            isPopupOpen = true;
            try {
                Rectangle r = instructionsArea.modelToView2D(atOffset).getBounds();
                // Point p = SwingUtilities.convertPoint(instructionsArea, r.x, r.y + r.height, chrome.getFrame()); // Unused variable p

                JPopupMenu popup = AddMenuFactory.buildAddPopup(chrome.getContextPanel());

                // Add action listeners to set the flag when an item is clicked
                for (Component comp : popup.getComponents()) {
                    if (comp instanceof JMenuItem item) {
                        // Get original listeners
                        java.awt.event.ActionListener[] originalListeners = item.getActionListeners();
                        // Remove them to re-wrap
                        for (java.awt.event.ActionListener al : originalListeners) {
                            item.removeActionListener(al);
                        }
                        // Add new listener that removes "@" then calls originals
                        item.addActionListener(actionEvent -> {
                            SwingUtilities.invokeLater(() -> { // Ensure document modification is on EDT
                                try {
                                    instructionsArea.getDocument().remove(atOffset, 1);
                                } catch (BadLocationException ble) {
                                    logger.warn("Could not remove @ symbol after selection in ActionListener", ble);
                                }
                            });
                            for (java.awt.event.ActionListener al : originalListeners) {
                                al.actionPerformed(actionEvent);
                            }
                        });
                    }
                }

                popup.addPopupMenuListener(new PopupMenuListener() {
                    @Override
                    public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                        // Unregister listener to avoid memory leaks
                        popup.removePopupMenuListener(this);
                        isPopupOpen = false; // Allow new popups
                        // Removal of "@" is now handled by the JMenuItem's ActionListener
                    }

                    @Override
                    public void popupMenuWillBecomeVisible(PopupMenuEvent e) {}

                    @Override
                    public void popupMenuCanceled(PopupMenuEvent e) {
                        // Unregister listener
                        popup.removePopupMenuListener(this);
                        isPopupOpen = false; // Allow new popups
                        // Do not remove "@" on cancel
                    }
                });

                if (chrome.themeManager != null) {
                    chrome.themeManager.registerPopupMenu(popup);
                }
                popup.show(instructionsArea, r.x, r.y + r.height);

                // Preselect the first item in the popup
                if (popup.getComponentCount() > 0) {
                    Component firstComponent = popup.getComponent(0);
                    if (firstComponent instanceof JMenuItem) { // Or more generally, MenuElement
                        MenuElement[] path = {popup, (MenuElement) firstComponent};
                        MenuSelectionManager.defaultManager().setSelectedPath(path);
                    }
                }
            } catch (BadLocationException ble) {
                isPopupOpen = false; // Reset guard on error
                logger.warn("Could not show @ popup", ble);
            } catch (Exception ex) {
                isPopupOpen = false; // Reset guard on any other error
                logger.error("Error showing @ popup", ex);
            }
        }
    }
}
