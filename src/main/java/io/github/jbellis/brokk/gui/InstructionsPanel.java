package io.github.jbellis.brokk.gui;

import static io.github.jbellis.brokk.gui.Constants.*;
import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import com.github.tjake.jlama.model.AbstractModel;
import com.github.tjake.jlama.model.functions.Generator;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
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
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.gui.TableUtils.FileReferenceList.FileReferenceData;
import io.github.jbellis.brokk.gui.components.ModelSelector;
import io.github.jbellis.brokk.gui.components.OverlayPanel;
import io.github.jbellis.brokk.gui.components.SplitButton;
import io.github.jbellis.brokk.gui.dialogs.ArchitectChoices;
import io.github.jbellis.brokk.gui.dialogs.ArchitectOptionsDialog;
import io.github.jbellis.brokk.gui.dialogs.SettingsDialog;
import io.github.jbellis.brokk.gui.dialogs.SettingsGlobalPanel;
import io.github.jbellis.brokk.gui.git.GitWorktreeTab;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.util.AddMenuFactory;
import io.github.jbellis.brokk.gui.util.ContextMenuUtils;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.tools.WorkspaceTools;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.LoggingExecutorService;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.*;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.undo.UndoManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.autocomplete.ShorthandCompletion;
import org.jetbrains.annotations.Nullable;

/**
 * The InstructionsPanel encapsulates the command input area, history dropdown, mic button, model dropdown, and the
 * action buttons. It also includes the system messages and command result areas. All initialization and action code
 * related to these components has been moved here.
 */
public class InstructionsPanel extends JPanel implements IContextManager.ContextListener {
    private static final Logger logger = LogManager.getLogger(InstructionsPanel.class);

    public static final String ACTION_ARCHITECT = "Architect";
    public static final String ACTION_CODE = "Code";
    public static final String ACTION_ASK = "Ask";
    public static final String ACTION_SEARCH = "Search";
    public static final String ACTION_RUN = "Run";

    private static final String PLACEHOLDER_TEXT =
            """
                                                   Put your instructions or questions here.  Brokk will suggest relevant files below; right-click on them to add them to your Workspace.  The Workspace will be visible to the AI when coding or answering your questions. Type "@" for add more context.

                                                   More tips are available in the Getting Started section in the Output panel above.
                                                   """;

    private final Chrome chrome;
    private final JTextArea instructionsArea;
    private final VoiceInputButton micButton;
    private final JButton architectButton; // Changed from SplitButton
    private final JButton codeButton;
    private final SplitButton searchButton;
    private final JButton runButton;
    private final JButton stopButton;
    private final ModelSelector modelSelector;
    private final ContextManager contextManager;
    private JTable referenceFileTable;
    private JLabel failureReasonLabel;
    private JPanel suggestionContentPanel;
    private CardLayout suggestionCardLayout;
    private final JPanel centerPanel;
    private static final int CONTEXT_SUGGESTION_DELAY = 100; // ms for paste/bulk changes
    private static final int CONTEXT_SUGGESTION_TYPING_DELAY = 1000; // ms for single character typing
    private final javax.swing.Timer contextSuggestionTimer; // Timer for debouncing quick context suggestions
    private final AtomicBoolean forceSuggestions = new AtomicBoolean(false);
    // Worker for autocontext suggestion tasks. we don't use CM.backgroundTasks b/c we want this to be single threaded
    private final ExecutorService suggestionWorker = new LoggingExecutorService(
            Executors.newSingleThreadExecutor(r -> {
                Thread t = Executors.defaultThreadFactory().newThread(r);
                t.setName("Brokk-Suggestion-Worker");
                t.setDaemon(true);
                return t;
            }),
            e -> logger.error("Unexpected error", e));
    // Generation counter to identify the latest suggestion request
    private final AtomicLong suggestionGeneration = new AtomicLong(0);
    private final OverlayPanel commandInputOverlay; // Overlay to initially disable command input
    private final UndoManager commandInputUndoManager;
    private AutoCompletion instructionAutoCompletion;
    private InstructionsCompletionProvider instructionCompletionProvider;
    private @Nullable String lastCheckedInputText = null;
    private @Nullable float[][] lastCheckedEmbeddings = null;
    private @Nullable List<FileReferenceData> pendingQuickContext = null;

    public InstructionsPanel(Chrome chrome) {
        super(new BorderLayout(2, 2));
        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Instructions",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)));

        this.chrome = chrome;
        this.contextManager = chrome.getContextManager();
        this.commandInputUndoManager = new UndoManager();
        commandInputOverlay = new OverlayPanel(overlay -> activateCommandInput(), "Click to enter your instructions");
        commandInputOverlay.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));

        // Initialize components
        instructionsArea = buildCommandInputField(); // Build first to add listener
        micButton = new VoiceInputButton(
                instructionsArea,
                contextManager,
                () -> {
                    activateCommandInput();
                    chrome.systemOutput("Recording");
                },
                msg -> chrome.toolError(msg, "Error"));

        // Initialize Buttons first
        architectButton = new JButton("Architect"); // Now a regular JButton
        architectButton.setMnemonic(KeyEvent.VK_G); // Mnemonic for Agent
        architectButton.setToolTipText("Run the multi-step agent (options include worktree setup)");
        architectButton.addActionListener(e -> runArchitectCommand()); // Main button action
        // architectButton.setMenuSupplier(this::createArchitectMenu); // Removed menu supplier

        codeButton = new JButton("Code");
        codeButton.setMnemonic(KeyEvent.VK_C);
        codeButton.setToolTipText("Tell the LLM to write code using the current context and selected model");
        codeButton.addActionListener(e -> runCodeCommand()); // Main button action

        searchButton = new SplitButton("Search");
        searchButton.setMnemonic(KeyEvent.VK_S);
        searchButton.setToolTipText("Explore the codebase beyond the current context using the selected model");
        searchButton.addActionListener(e -> runSearchCommand()); // Main action unchanged
        searchButton.setMenuSupplier(this::createSearchMenu);

        runButton = new JButton("Run in Shell");
        runButton.setMnemonic(KeyEvent.VK_N);
        runButton.setToolTipText("Execute the current instructions in a shell");
        runButton.addActionListener(e -> runRunCommand());

        stopButton = new JButton("Stop");
        stopButton.setToolTipText("Cancel the current operation");
        stopButton.setEnabled(false); // Start disabled, enabled when an action runs
        stopButton.addActionListener(e -> chrome.getContextManager().interruptUserActionThread());

        modelSelector = new ModelSelector(chrome);
        modelSelector.selectConfig(chrome.getProject().getCodeModelConfig());
        modelSelector.addSelectionListener(cfg -> chrome.getProject().setCodeModelConfig(cfg));

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
        contextSuggestionTimer = new javax.swing.Timer(CONTEXT_SUGGESTION_DELAY, this::triggerContextSuggestion);
        contextSuggestionTimer.setRepeats(false);
        instructionsArea.getDocument().addDocumentListener(new DocumentListener() {
            private void checkAndHandleSuggestions(DocumentEvent e) {
                if (getInstructions().split("\\s+").length >= 2) {
                    // Only restart timer if significant change (not just single character)
                    if (e.getType() == DocumentEvent.EventType.INSERT && e.getLength() > 1) {
                        contextSuggestionTimer.setInitialDelay(CONTEXT_SUGGESTION_DELAY); // Ensure normal delay
                        contextSuggestionTimer.restart();
                    } else if (e.getType() == DocumentEvent.EventType.INSERT) {
                        // For single character inserts, use longer delay
                        contextSuggestionTimer.setInitialDelay(CONTEXT_SUGGESTION_TYPING_DELAY);
                        contextSuggestionTimer.restart();
                    } else if (e.getType() == DocumentEvent.EventType.REMOVE) {
                        contextSuggestionTimer.setInitialDelay(CONTEXT_SUGGESTION_DELAY); // Ensure normal delay
                        contextSuggestionTimer.restart();
                    }
                } else {
                    // Input is blank or too short: stop timer, invalidate generation, reset state, schedule UI clear.
                    contextSuggestionTimer.stop();
                    long myGen = suggestionGeneration.incrementAndGet(); // Invalidate any running/pending task
                    logger.trace(
                            "Input cleared/shortened, stopping timer and invalidating suggestions (gen {})", myGen);

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
                            logger.trace(
                                    "Skipping UI clear for gen {} (current gen {})", myGen, suggestionGeneration.get());
                        }
                    });
                }
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                checkAndHandleSuggestions(e);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                checkAndHandleSuggestions(e);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Not typically fired for plain text components, but handle just in case
                checkAndHandleSuggestions(e);
            }
        });

        SwingUtilities.invokeLater(() -> {
            if (chrome.getFrame().getRootPane() != null) {
                chrome.getFrame().getRootPane().setDefaultButton(codeButton);
            }
        });

        // Add this panel as a listener to context changes
        this.contextManager.addContextListener(this);

        // --- Autocomplete Setup ---
        instructionCompletionProvider = new InstructionsCompletionProvider();
        instructionAutoCompletion = new AutoCompletion(instructionCompletionProvider);
        instructionAutoCompletion.setAutoActivationEnabled(false);
        instructionAutoCompletion.setTriggerKey(
                KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, java.awt.event.InputEvent.CTRL_DOWN_MASK));
        instructionAutoCompletion.install(instructionsArea);

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
        int shortcutMask = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        var undoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcutMask);
        var redoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Y, shortcutMask);
        var redoAlternativeKeyStroke =
                KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcutMask | java.awt.event.InputEvent.SHIFT_DOWN_MASK);

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
        var pasteKeyStroke = KeyStroke.getKeyStroke(
                KeyEvent.VK_V, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        area.getInputMap().put(pasteKeyStroke, "smartPaste");
        area.getActionMap().put("smartPaste", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                var contents = clipboard.getContents(null);
                boolean imageHandled = false;

                if (contents == null) {
                    return;
                }

                for (var flavor : contents.getTransferDataFlavors()) {
                    try {
                        if (flavor.equals(DataFlavor.imageFlavor)
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

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftPanel.add(micButton);
        leftPanel.add(Box.createHorizontalStrut(H_GAP));
        leftPanel.add(modelSelector.getComponent());
        topBarPanel.add(leftPanel, BorderLayout.WEST);

        var historyDropdown = createHistoryDropdown();
        topBarPanel.add(historyDropdown, BorderLayout.CENTER);

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

        // Create layered pane with overlay
        var layeredPane = commandInputOverlay.createLayeredPane(commandScrollPane);
        layeredPane.setBorder(new EmptyBorder(0, H_PAD, 0, H_PAD));

        panel.add(layeredPane); // Add the layered pane instead of the scroll pane directly

        // Reference-file table will be inserted just below the command input (now layeredPane)
        // by initializeReferenceFileTable()

        return panel;
    }

    /**
     * Initializes the file-reference table that sits directly beneath the command-input field and wires a context-menu
     * that targets the specific badge the mouse is over (mirrors ContextPanel behaviour).
     */
    private void initializeReferenceFileTable() {
        // ----- create the table itself --------------------------------------------------------
        referenceFileTable = new JTable(new javax.swing.table.DefaultTableModel(new Object[] {"File References"}, 1) {
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

        referenceFileTable.setTableHeader(null); // single-column ⇒ header not needed
        referenceFileTable.setShowGrid(false);
        referenceFileTable
                .getColumnModel()
                .getColumn(0)
                .setCellRenderer(new TableUtils.FileReferencesTableCellRenderer());

        // Clear initial content (it will be populated by context suggestions)
        referenceFileTable.setValueAt(List.of(), 0, 0);

        // ----- context-menu support -----------------------------------------------------------
        referenceFileTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) {
                    ContextMenuUtils.handleFileReferenceClick(
                            e, referenceFileTable, chrome, () -> triggerContextSuggestion(null));
                }
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                ContextMenuUtils.handleFileReferenceClick(
                        e, referenceFileTable, chrome, () -> triggerContextSuggestion(null));
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

        // ----- create container panel for content (table/label) -------------------------------
        var suggestionAreaPanel = new JPanel(new BorderLayout(H_GLUE, 0));
        suggestionAreaPanel.setBorder(BorderFactory.createEmptyBorder(V_GLUE, H_PAD, V_GLUE, H_PAD));

        // Only the card layout panel now (Deep Scan button removed)
        suggestionAreaPanel.add(suggestionContentPanel, BorderLayout.CENTER);

        // Apply height constraints to the container panel
        int currentPanelRowHeight = referenceFileTable.getRowHeight();
        int fixedHeight = currentPanelRowHeight + 2;
        suggestionAreaPanel.setPreferredSize(new Dimension(600, fixedHeight));
        suggestionAreaPanel.setMinimumSize(new Dimension(100, fixedHeight));
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
        bottomPanel.add(searchButton); // SplitButton with dropdown
        bottomPanel.add(Box.createHorizontalStrut(H_GAP));
        bottomPanel.add(runButton);

        // Match button sizes to Run button so Architect/Code/Search match Run's preferred size
        SwingUtilities.invokeLater(() -> {
            Dimension buttonSize = runButton.getPreferredSize();
            if (buttonSize != null && buttonSize.width > 0 && buttonSize.height > 0) {
                architectButton.setPreferredSize(buttonSize);
                codeButton.setPreferredSize(buttonSize);
                searchButton.setPreferredSize(buttonSize);
                bottomPanel.revalidate();
                bottomPanel.repaint();
            }
        });

        // Flexible space between action buttons and stop button
        bottomPanel.add(Box.createHorizontalGlue());

        // Stop button on the right
        stopButton.setAlignmentY(Component.CENTER_ALIGNMENT);
        bottomPanel.add(stopButton);

        return bottomPanel;
    }

    private JComboBox<Object> createHistoryDropdown() {
        final var placeholder = "History";
        final var noHistory = "(No history items)";

        var project = chrome.getProject();

        var model = new DefaultComboBoxModel<>();
        model.addElement(placeholder);

        var dropdown = new JComboBox<>(model);
        dropdown.setToolTipText("Select a previous instruction from history");

        dropdown.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof String historyItem) {
                    // To prevent the dropdown from becoming excessively wide, we truncate the display text
                    // to fit within the width of the JComboBox itself.
                    String displayText = historyItem.replace('\n', ' ');
                    int width = dropdown.getWidth();
                    if (width > 20) {
                        FontMetrics fm = getFontMetrics(getFont());
                        if (fm.stringWidth(displayText) > width) {
                            displayText = SwingUtilities.layoutCompoundLabel(
                                    this,
                                    fm,
                                    displayText,
                                    null,
                                    SwingConstants.CENTER,
                                    SwingConstants.LEFT,
                                    SwingConstants.CENTER,
                                    SwingConstants.LEFT,
                                    new Rectangle(width, getHeight()),
                                    new Rectangle(),
                                    new Rectangle(),
                                    0);
                        }
                    }

                    setText(displayText);
                    setEnabled(true);
                    if (historyItem.equals(noHistory) || historyItem.equals(placeholder)) {
                        setToolTipText(null);
                    } else {
                        setToolTipText(historyItem);
                    }
                }
                return this;
            }
        });

        dropdown.addActionListener(e -> {
            var selected = dropdown.getSelectedItem();
            if (selected instanceof String historyItem
                    && !selected.equals(placeholder)
                    && !selected.equals(noHistory)) {
                // This is a valid history item
                Objects.requireNonNull(commandInputOverlay).hideOverlay();
                Objects.requireNonNull(instructionsArea).setEnabled(true);

                instructionsArea.setText(historyItem);
                Objects.requireNonNull(commandInputUndoManager).discardAllEdits();
                instructionsArea.requestFocusInWindow();

                // Reset to placeholder
                SwingUtilities.invokeLater(() -> dropdown.setSelectedItem(placeholder));
            }
        });

        dropdown.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                model.removeAllElements();
                model.addElement(placeholder);
                List<String> historyItems = project.loadTextHistory();

                logger.trace("History items loaded: {}", historyItems.size());
                if (historyItems.isEmpty()) {
                    model.addElement(noHistory);
                } else {
                    for (var item : historyItems) {
                        model.addElement(item);
                    }
                }
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });

        return dropdown;
    }

    /**
     * Checks if the current context managed by the ContextManager contains any image fragments.
     *
     * @return true if the top context exists and contains at least one PasteImageFragment, false otherwise.
     */
    private boolean contextHasImages() {
        var contextManager = chrome.getContextManager();
        return contextManager
                .topContext()
                .allFragments()
                .anyMatch(f -> !f.isText() && !f.getType().isOutputFragment());
    }

    /**
     * Shows a modal error dialog informing the user that the required models lack vision support. Offers to open the
     * Model settings tab.
     *
     * @param requiredModelsInfo A string describing the model(s) that lack vision support (e.g., model names).
     */
    private void showVisionSupportErrorDialog(String requiredModelsInfo) {
        String message =
                """
                         <html>The current operation involves images, but the following selected model(s) do not support vision:<br>
                         <b>%s</b><br><br>
                         Please select vision-capable models in the settings to proceed with image-based tasks.</html>
                         """
                        .formatted(requiredModelsInfo);
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
            SwingUtilities.invokeLater(
                    () -> SettingsDialog.showSettingsDialog(chrome, SettingsGlobalPanel.MODELS_TAB_TITLE));
        }
        // In either case (Settings opened or Cancel pressed), the original action is aborted by returning from the
        // caller.
    }

    /**
     * Centralized model selection from the dropdown with fallback and optional vision check. Returns null if selection
     * fails or vision is required but unsupported.
     */
    private @Nullable StreamingChatModel selectDropdownModelOrShowError(String actionLabel, boolean requireVision) {
        var cm = chrome.getContextManager();
        var models = cm.getService();

        Service.ModelConfig config;
        try {
            config = modelSelector.getModel();
        } catch (IllegalStateException e) {
            chrome.toolError("Please finish configuring your custom model or select a favorite first.");
            return null;
        }

        var selectedModel = models.getModel(config);
        if (selectedModel == null) {
            chrome.toolError("Selected model '" + config.name() + "' is not available with reasoning level "
                    + config.reasoning());
            selectedModel = castNonNull(models.getModel(Service.GPT_5_MINI));
        }

        if (requireVision && contextHasImages() && !models.supportsVision(selectedModel)) {
            showVisionSupportErrorDialog(models.nameOf(selectedModel) + " (" + actionLabel + ")");
            return null;
        }

        return selectedModel;
    }

    // --- Public API ---

    /**
     * Gets the current user input text. If the placeholder is currently displayed, it returns an empty string,
     * otherwise it returns the actual text content.
     */
    public String getInstructions() {
        var v = SwingUtil.runOnEdt(
                () -> {
                    return instructionsArea.getText().equals(PLACEHOLDER_TEXT) ? "" : instructionsArea.getText();
                },
                "");
        return castNonNull(v);
    }

    /**
     * Clears the command input field and ensures the text color is set to the standard foreground. This prevents the
     * placeholder from reappearing inadvertently.
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

    // --- Private Execution Logic ---

    /**
     * Called by the contextSuggestionTimer or external events (like context changes) to initiate a context suggestion
     * task. It increments the generation counter and submits the task to the sequential worker executor.
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
     * Performs the actual context suggestion logic off the EDT. This method includes checks against the current
     * `suggestionGeneration` to ensure only the latest task proceeds and updates the UI.
     *
     * @param myGen The generation number of this specific task.
     * @param snapshot The input text captured when this task was initiated.
     */
    private void processInputSuggestions(long myGen, String snapshot) {
        logger.trace("Starting suggestion task generation {}", myGen);

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

            // Set our snapshot as the new semantic baseline
            this.lastCheckedInputText = snapshot;
            this.lastCheckedEmbeddings = newEmbeddings;

            // process the recommendations
            var fileRefs = recommendations.fragments().stream()
                    .flatMap(f -> f.files().stream()) // No analyzer
                    .distinct()
                    .map(pf -> new FileReferenceData(pf.getFileName(), pf.toString(), pf))
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
     * Checks if the new text/embeddings are semantically different from the last processed state
     * (`lastCheckedInputText`, `lastCheckedEmbeddings`).
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
                var msg =
                        """
                          New embeddings similarity = %.3f, triggering recompute

                          # Old text
                          %s

                          # New text
                          %s
                          """
                                .formatted(similarity, lastCheckedInputText, currentText);
                logger.debug(msg);
                return true;
            }
        }

        logger.debug("Minimum similarity was {}", minSimilarity);

        // If lengths match and all similarities are above threshold, it's not different enough.
        // Do NOT update lastCheckedEmbeddings here, keep the previous ones for the next comparison.
        return false;
    }

    /** Helper to show the failure label with a message. */
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

    /** Helper to show the suggestions table with file references. */
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
     * Executes the core logic for the "Code" command. This runs inside the Runnable passed to
     * contextManager.submitUserTask.
     */
    private void executeCodeCommand(StreamingChatModel model, String input) {
        var contextManager = chrome.getContextManager();

        contextManager.getAnalyzerWrapper().pause();
        try {
            var result = new CodeAgent(contextManager, model).runTask(input, false);
            chrome.setSkipNextUpdateOutputPanelOnContextChange(true);
            // code agent has displayed status in llmoutput
            if (result.stopDetails().reason() == TaskResult.StopReason.INTERRUPTED) {
                maybeAddInterruptedResult(input, result);
            } else {
                contextManager.addToHistory(result, false);
            }
        } finally {
            contextManager.getAnalyzerWrapper().resume();
        }
    }

    /**
     * Executes the core logic for the "Ask" command. This runs inside the Runnable passed to
     * contextManager.submitAction.
     */
    public static TaskResult executeAskCommand(IContextManager cm, StreamingChatModel model, String question) {
        List<ChatMessage> messages;
        try {
            messages = CodePrompts.instance.collectAskMessages(cm, question, model);
        } catch (InterruptedException e) {
            return new TaskResult(
                    cm,
                    "Ask: " + question,
                    List.of(),
                    Set.of(),
                    new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED));
        }
        var llm = cm.getLlm(model, "Ask: " + question);

        return executeAskCommand(llm, messages, cm, question);
    }

    public static TaskResult executeAskCommand(
            Llm llm, List<ChatMessage> messages, IContextManager cm, String question) {
        // Build and send the request to the LLM
        TaskResult.StopDetails stop = null;
        Llm.StreamingResult response = null;
        try {
            response = llm.sendRequest(messages, true);
        } catch (InterruptedException e) {
            stop = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED);
        }

        // Determine stop details based on the response
        if (response != null) {
            if (response.error() != null) {
                String explanation = Objects.requireNonNullElse(response.error().getMessage(), "Unknown LLM error");
                stop = new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, explanation);
            } else if (response.isEmpty()) {
                stop = new TaskResult.StopDetails(TaskResult.StopReason.EMPTY_RESPONSE, "Empty response from LLM");
            } else {
                stop = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
            }
        }

        // construct TaskResult
        requireNonNull(stop);
        return new TaskResult(
                cm,
                "Ask: " + question,
                List.copyOf(cm.getIo().getLlmRawMessages(false)),
                Set.of(), // Ask never changes files
                stop);
    }

    public void maybeAddInterruptedResult(String input, TaskResult result) {
        if (result.output().messages().stream().anyMatch(m -> m instanceof AiMessage)) {
            logger.debug(result.actionDescription() + " command cancelled with partial results");
            chrome.getContextManager().addToHistory(result, false);
        }
        populateInstructionsArea(input);
    }

    public void maybeAddInterruptedResult(String action, String input) {
        if (chrome.getLlmRawMessages(false).stream().anyMatch(m -> m instanceof AiMessage)) {
            logger.debug(action + " command cancelled with partial results");
            var sessionResult = new TaskResult(
                    "%s (Cancelled): %s".formatted(action, input),
                    new TaskFragment(chrome.getContextManager(), List.copyOf(chrome.getLlmRawMessages(false)), input),
                    Set.of(),
                    new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED));
            chrome.getContextManager().addToHistory(sessionResult, false);
        }
        populateInstructionsArea(input);
    }

    /**
     * Executes the core logic for the "Agent" command. This runs inside the Runnable passed to
     * contextManager.submitAction.
     *
     * @param goal The initial user instruction passed to the agent.
     * @param options The configured options for the agent's tools.
     */
    private void executeArchitectCommand(
            StreamingChatModel planningModel,
            StreamingChatModel codeModel,
            String goal,
            ArchitectAgent.ArchitectOptions options) {
        var contextManager = chrome.getContextManager();
        try {
            var agent = new ArchitectAgent(contextManager, planningModel, codeModel, goal, options);
            var result = agent.execute();
            chrome.systemOutput("Architect complete!");
            contextManager.addToHistory(result, false);
        } catch (InterruptedException e) {
            throw new CancellationException(e.getMessage());
        } catch (Exception e) {
            logger.error("Error during Agent execution", e);
            chrome.toolError("Internal error during Agent command: " + e.getMessage());
        }
    }

    /**
     * Executes the core logic for the "Search" command. This runs inside the Runnable passed to
     * contextManager.submitAction.
     */
    private void executeSearchCommand(StreamingChatModel model, String query) {
        if (query.isBlank()) {
            chrome.toolError("Please provide a search query");
            return;
        }

        var contextManager = chrome.getContextManager();
        try {
            SearchAgent agent = new SearchAgent(query, contextManager, model, 0);
            var result = agent.execute();

            // Search does not stream to llmOutput, so add the final answer here
            chrome.setSkipNextUpdateOutputPanelOnContextChange(true);
            contextManager.addToHistory(result, false);
            chrome.systemOutput("Search complete!");
        } catch (InterruptedException e) {
            throw new CancellationException(e.getMessage());
        }
    }

    /**
     * Executes the core logic for the "Run in Shell" command. This runs inside the Runnable passed to
     * contextManager.submitAction.
     */
    private void executeRunCommand(String input) {
        assert !SwingUtilities.isEventDispatchThread();

        var contextManager = chrome.getContextManager();
        String actionMessage = "Run: " + input;

        try {
            chrome.showOutputSpinner("Executing command...");
            chrome.llmOutput("\n```bash\n", ChatMessageType.CUSTOM);
            long timeoutSecs;
            if (chrome.getProject() instanceof MainProject mainProject) {
                timeoutSecs = mainProject.getRunCommandTimeoutSeconds();
            } else {
                timeoutSecs = Environment.DEFAULT_TIMEOUT.toSeconds();
            }
            Environment.instance.runShellCommand(
                    input,
                    contextManager.getRoot(),
                    line -> chrome.llmOutput(line + "\n", ChatMessageType.CUSTOM),
                    java.time.Duration.ofSeconds(timeoutSecs));
            chrome.llmOutput("\n```", ChatMessageType.CUSTOM); // Close markdown block on success
            chrome.systemOutput("Run command complete!");
        } catch (Environment.SubprocessException e) {
            chrome.llmOutput("\n```", ChatMessageType.CUSTOM); // Ensure markdown block is closed on error
            actionMessage = "Run: " + input + " (failed: " + e.getMessage() + ")";
            chrome.systemOutput("Run command completed with errors -- see Output");
            logger.warn("Run command '{}' failed: {}", input, e.getMessage(), e);
            chrome.llmOutput("\n**Command Failed**", ChatMessageType.CUSTOM);
        } catch (InterruptedException e) {
            throw new CancellationException(e.getMessage());
        } finally {
            chrome.hideOutputSpinner();
        }

        // Add to context history with the action message (which includes success/failure)
        final String finalActionMessage = actionMessage; // Effectively final for lambda
        contextManager.pushContext(ctx -> {
            var parsed = new TaskFragment(
                    chrome.getContextManager(), List.copyOf(chrome.getLlmRawMessages(false)), finalActionMessage);
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

        chrome.getProject().addToInstructionsHistory(goal, 20);

        // Show the options dialog synchronously on the EDT. This blocks until the user clicks OK/Cancel.
        ArchitectChoices choices = ArchitectOptionsDialog.showDialogAndWait(chrome);

        // If the user cancelled the dialog, choices will be null.
        if (choices == null) {
            logger.debug("Architect command cancelled during option selection.");
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

        // don't use submitAction, we're going to kick off a new Worktree + Chrome and run in that, leaving the original
        // free
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
                    chrome.toolError(
                            "Cannot create worktree: Project is not a Git repository or worktrees are not supported.");
                    populateInstructionsArea(originalInstructions); // Restore instructions if setup fails
                    return;
                }

                Path newWorktreePath;
                String actualBranchName;

                MainProject projectForWorktreeSetup = currentProject.getMainProject();
                IGitRepo repo = projectForWorktreeSetup.getRepo();
                if (!(repo instanceof GitRepo mainGitRepo)) {
                    chrome.hideOutputSpinner();
                    chrome.toolError(
                            "Cannot create worktree: Main project repository does not support Git operations.");
                    populateInstructionsArea(originalInstructions);
                    return;
                }
                String sourceBranchForNew =
                        mainGitRepo.getCurrentBranch(); // New branch is created from current branch of main repo

                var setupResult = GitWorktreeTab.setupNewGitWorktree(
                        projectForWorktreeSetup,
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

                new Brokk.OpenProjectBuilder(newWorktreePath)
                        .parent(mainProject)
                        .initialTask(initialArchitectTask)
                        .sourceContextForSession(cm.topContext())
                        .open()
                        .thenAccept(success -> {
                            if (Boolean.TRUE.equals(success)) {
                                chrome.systemOutput("New worktree opened for Architect");
                            } else {
                                chrome.toolError("Failed to open the new worktree project for Architect.");
                                populateInstructionsArea(originalInstructions);
                            }
                        })
                        .exceptionally(ex -> {
                            chrome.toolError("Error opening new worktree project: " + ex.getMessage());
                            populateInstructionsArea(originalInstructions);
                            return null;
                        });
            } catch (InterruptedException e) {
                logger.debug("Architect worktree setup interrupted.", e);
                populateInstructionsArea(originalInstructions);
            } catch (GitAPIException | IOException | ExecutionException ex) {
                chrome.toolError("Error setting up worktree: " + ex.getMessage());
                populateInstructionsArea(originalInstructions);
            } finally {
                chrome.hideOutputSpinner();
            }
        });
    }

    /**
     * Overload for programmatic invocation of Architect agent after options are determined, typically called directly
     * or from the worktree setup.
     *
     * @param goal The user's goal/instructions.
     * @param options The pre-configured ArchitectOptions.
     */
    public void runArchitectCommand(String goal, ArchitectAgent.ArchitectOptions options) {
        submitAction(ACTION_ARCHITECT, goal, () -> {
            var service = chrome.getContextManager().getService();
            var planningModel = service.getModel(options.planningModel());
            if (planningModel == null) {
                planningModel = service.quickModel();
            }
            var codeModel = service.getModel(options.codeModel());
            if (codeModel == null) {
                codeModel = service.quickModel();
            }
            // Proceed with execution using the selected options
            executeArchitectCommand(planningModel, codeModel, goal, options);
        });
    }

    // Methods for running commands. These prepare the input and model, then delegate
    // the core logic execution to contextManager.submitAction, which calls back
    // into the private execute* methods above.

    // Public entry point for default Code model
    public void runCodeCommand() {
        var contextManager = chrome.getContextManager();

        Service.ModelConfig config;
        try {
            config = modelSelector.getModel();
            chrome.getProject().setCodeModelConfig(config);
        } catch (IllegalStateException e) {
            chrome.toolError("Please finish configuring your custom model or select a favorite first.");
            return;
        }

        var model = contextManager.getService().getModel(config);
        if (model == null) {
            chrome.toolError("Selected model '" + config.name() + "' is not available with reasoning level "
                    + config.reasoning());
            model = castNonNull(contextManager.getService().getModel(Service.GPT_5_MINI));
        }
        prepareAndRunCodeCommand(model);
    }

    // Core method to prepare and submit the Code action
    private void prepareAndRunCodeCommand(StreamingChatModel modelToUse) {
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

        // If Workspace is empty, ask the user how to proceed
        if (chrome.getContextManager().topContext().isEmpty()) {
            String message =
                    "Are you sure you want to code against an empty Workspace? This is the right thing to do if you want to create new source files with no other context. Otherwise, run Search first or manually add context to the Workspace.";
            Object[] options = {"Code", "Search", "Cancel"};
            int choice = JOptionPane.showOptionDialog(
                    chrome.getFrame(),
                    message,
                    "Empty Workspace",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    options,
                    options[0]);

            if (choice == 1) { // Search
                runSearchCommand();
                return;
            } else if (choice != 0) { // Cancel or closed dialog
                return;
            }
        }

        chrome.getProject().addToInstructionsHistory(input, 20);
        clearCommandInput();
        // disableButtons() is called by submitAction via chrome.disableActionButtons()
        submitAction(ACTION_CODE, input, () -> executeCodeCommand(modelToUse, input));
    }

    // Public entry point for default Ask model
    public void runAskCommand(String input) {
        final var modelToUse = selectDropdownModelOrShowError("Ask", true);
        if (modelToUse == null) {
            return;
        }
        prepareAndRunAskCommand(modelToUse, input);
    }

    // Core method to prepare and submit the Ask action
    private void prepareAndRunAskCommand(StreamingChatModel modelToUse, String input) {
        if (input.isBlank()) {
            chrome.toolError("Please enter a question");
            return;
        }

        var contextManager = chrome.getContextManager();

        chrome.getProject().addToInstructionsHistory(input, 20);
        clearCommandInput();
        // disableButtons() is called by submitAction via chrome.disableActionButtons()
        submitAction(ACTION_ASK, input, () -> {
            var result = executeAskCommand(contextManager, modelToUse, input);

            // Display result in the LLM output panel
            chrome.setLlmOutput(result.output());

            // Persist to history regardless of success/failure
            chrome.setSkipNextUpdateOutputPanelOnContextChange(true);
            if (result.stopDetails().reason() == TaskResult.StopReason.INTERRUPTED) {
                maybeAddInterruptedResult(input, result);
            } else {
                contextManager.addToHistory(result, false);
            }

            // Provide a brief status update
            if (result.stopDetails().reason() == TaskResult.StopReason.SUCCESS) {
                chrome.llmOutput("Ask command complete!", ChatMessageType.CUSTOM);
            } else {
                chrome.llmOutput("Ask command finished with status: " + result.stopDetails(), ChatMessageType.CUSTOM);
            }
        });
    }

    public void runSearchCommand() {
        var input = getInstructions();
        if (input.isBlank()) {
            chrome.toolError("Please provide a search query");
            return;
        }

        final var modelToUse = selectDropdownModelOrShowError("Search", true);
        if (modelToUse == null) {
            return;
        }

        chrome.getProject().addToInstructionsHistory(input, 20);
        // Update the LLM output panel directly via Chrome
        chrome.llmOutput(
                "# Please be patient\n\nBrokk makes multiple requests to the LLM while searching. Progress is logged in System Messages below.",
                ChatMessageType.CUSTOM);
        clearCommandInput();
        // Submit the action, calling the private execute method inside the lambda
        submitAction(ACTION_SEARCH, input, () -> executeSearchCommand(modelToUse, input));
    }

    public void runRunCommand() {
        var input = getInstructions();
        if (input.isBlank()) {
            chrome.toolError("Please enter a command to run", "Error");
            return;
        }
        chrome.getProject().addToInstructionsHistory(input, 20);

        runRunCommand(input);
    }

    public void runRunCommand(String input) {
        clearCommandInput();
        submitAction(ACTION_RUN, input, () -> executeRunCommand(input));
    }

    /** sets the llm output to indicate the action has started, and submits the task on the user pool */
    public Future<?> submitAction(String action, String input, Runnable task) {
        var cm = chrome.getContextManager();
        // need to set the correct parser here since we're going to append to the same fragment during the action
        String finalAction = (action + " MODE").toUpperCase(Locale.ROOT);
        chrome.setLlmOutput(new ContextFragment.TaskFragment(
                cm, cm.getParserForWorkspace(), List.of(new UserMessage(finalAction, input)), input));
        return cm.submitUserTask(finalAction, true, () -> {
            try {
                chrome.showOutputSpinner("Executing " + action + " command...");
                task.run();
            } catch (CancellationException e) {
                maybeAddInterruptedResult(action, input);
                throw e; // propagate to ContextManager
            } finally {
                chrome.hideOutputSpinner();
                contextManager.checkBalanceAndNotify();
                notifyActionComplete(action);
            }
        });
    }

    // Methods to disable and enable buttons.
    void disableButtons() {
        architectButton.setEnabled(false);
        codeButton.setEnabled(false);
        searchButton.setEnabled(false);
        runButton.setEnabled(false);
        stopButton.setEnabled(true);
    }

    /**
     * Updates the enabled state of all action buttons based on project load status and ContextManager availability.
     * Called when actions complete.
     */
    private void updateButtonStates() {
        boolean gitAvailable = chrome.getProject().hasGit();

        // Architect
        architectButton.setEnabled(true);
        architectButton.setToolTipText("Run the multi-step agent (options include worktree setup)");

        // Code
        if (!gitAvailable) {
            codeButton.setEnabled(false);
            codeButton.setToolTipText("Code feature requires Git integration for this project.");
        } else {
            codeButton.setEnabled(true);
        }

        // Search (SplitButton)
        searchButton.setEnabled(true);

        // Run in Shell
        runButton.setEnabled(true);

        // Stop is only enabled when an action is running
        stopButton.setEnabled(false);

        chrome.enableHistoryPanel();
    }

    @Override
    public void contextChanged(Context newCtx) {
        // Otherwise, proceed with the normal suggestion logic by submitting a task
        logger.debug("Context changed externally, triggering suggestion check.");
        triggerContextSuggestion(null); // Use null ActionEvent to indicate non-timer trigger
    }

    void enableButtons() {
        // Called when an action completes. Reset buttons based on current CM/project state.
        updateButtonStates();
    }

    private void notifyActionComplete(String actionName) {
        chrome.notifyActionComplete("Action '" + actionName + "' completed.");
    }

    public void populateInstructionsArea(String text) {
        SwingUtilities.invokeLater(() -> {
            // If placeholder is active or area is disabled, activate input first
            if (instructionsArea.getText().equals(PLACEHOLDER_TEXT) || !instructionsArea.isEnabled()) {
                activateCommandInput(); // This enables, clears placeholder, requests focus
            }
            SwingUtilities.invokeLater(() -> {
                instructionsArea.setText(text);
                commandInputUndoManager.discardAllEdits(); // Reset undo history for the repopulated content
                instructionsArea.requestFocusInWindow(); // Ensure focus after text set
                instructionsArea.setCaretPosition(text.length()); // Move caret to end
            });
        });
    }

    /**
     * Hides the command input overlay, enables the input field and deep scan button, clears the placeholder text if
     * present, and requests focus for the input field.
     */
    private void activateCommandInput() {
        commandInputOverlay.hideOverlay(); // Hide the overlay
        // Enable input and deep scan button
        instructionsArea.setEnabled(true);
        // Clear placeholder only if it's still present
        if (instructionsArea.getText().equals(PLACEHOLDER_TEXT)) {
            clearCommandInput();
        }
        instructionsArea.requestFocusInWindow(); // Give it focus
    }

    private JPopupMenu createSearchMenu() {
        var popupMenu = new JPopupMenu();

        var answerItem = new JMenuItem("Answer from Current Workspace");
        answerItem.setToolTipText("Ask the LLM using only the current Workspace context");
        answerItem.addActionListener(e -> runAskCommand(getInstructions()));
        popupMenu.add(answerItem);

        var scanItem = new JMenuItem("Scan Project");
        scanItem.setToolTipText("Scan the repository to add relevant files/summaries to the Workspace");
        scanItem.addActionListener(e -> runScanProjectCommand());
        popupMenu.add(scanItem);

        chrome.themeManager.registerPopupMenu(popupMenu);
        return popupMenu;
    }

    public void runScanProjectCommand() {
        var goal = getInstructions();
        if (goal.isBlank()) {
            chrome.toolError("Please provide instructions before scanning the project");
            return;
        }

        final var modelToUse = selectDropdownModelOrShowError("Scan Project", true);
        if (modelToUse == null) {
            return;
        }

        chrome.getProject().addToInstructionsHistory(goal, 20);
        clearCommandInput();

        submitAction("Scan Project", goal, () -> executeScanProjectCommand(modelToUse, goal));
    }

    private void executeScanProjectCommand(StreamingChatModel model, String goal) {
        var cm = chrome.getContextManager();
        try {
            var contextAgent = new ContextAgent(cm, model, goal, true);
            var recommendation = contextAgent.getRecommendations(true);

            if (!recommendation.reasoning().isEmpty()) {
                chrome.llmOutput(
                        "\nReasoning for recommendations: " + recommendation.reasoning(), ChatMessageType.CUSTOM);
            }

            var totalTokens = contextAgent.calculateFragmentTokens(recommendation.fragments());
            int finalBudget = cm.getService().getMaxInputTokens(model) / 2;

            if (totalTokens > finalBudget) {
                var summaries = ContextFragment.getSummary(recommendation.fragments());
                var msgs = new ArrayList<>(List.of(
                        new UserMessage("Scan for relevant files"),
                        new AiMessage("Potentially relevant files:\n" + summaries)));
                cm.addToHistory(
                        new TaskResult(cm, "Scan for relevant files", msgs, Set.of(), TaskResult.StopReason.SUCCESS),
                        false);
                chrome.llmOutput(
                        "Scan Project complete: recorded summaries to history (too large to add directly).",
                        ChatMessageType.CUSTOM);
            } else {
                WorkspaceTools.addToWorkspace(cm, recommendation);
                chrome.llmOutput(
                        "Scan Project complete: added recommendations to the Workspace.", ChatMessageType.CUSTOM);
            }
        } catch (InterruptedException e) {
            throw new CancellationException(e.getMessage());
        }
    }

    public VoiceInputButton getVoiceInputButton() {
        return this.micButton;
    }

    /**
     * Returns the currently selected Code model configuration from the model selector. Falls back to a reasonable
     * default if none is available.
     */
    public Service.ModelConfig getCurrentCodeModelConfig() {
        try {
            return modelSelector.getModel();
        } catch (IllegalStateException e) {
            // Fallback to a basic default; Reasoning & Tier defaulted inside ModelConfig
            return new Service.ModelConfig(Service.GPT_5_MINI);
        }
    }

    /** Returns cosine similarity of two equal-length vectors. */
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

    private final class AtTriggerFilter extends DocumentFilter {
        private boolean isPopupOpen = false; // Guard against re-entrant calls

        @Override
        public void insertString(FilterBypass fb, int offs, String str, AttributeSet a) throws BadLocationException {
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
                if (fb.getDocument().getLength() >= caretPos
                        && caretPos > 0
                        && fb.getDocument().getText(caretPos - 1, 1).equals("@")) {
                    // Schedule popup display on EDT
                    SwingUtilities.invokeLater(() -> showAddPopup(caretPos - 1));
                }
            } catch (BadLocationException ignored) {
                // Ignore, means text was changing rapidly
            }
        }

        private void showAddPopup(int atOffset) {
            if (isPopupOpen) {
                return; // Already showing one
            }

            isPopupOpen = true;
            try {
                Rectangle r = instructionsArea.modelToView2D(atOffset).getBounds();
                // Point p = SwingUtilities.convertPoint(instructionsArea, r.x, r.y + r.height, chrome.getFrame()); //
                // Unused variable p

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
                            SwingUtilities.invokeLater(
                                    () -> { // Ensure document modification is on EDT
                                        try {
                                            instructionsArea.getDocument().remove(atOffset, 1);
                                        } catch (BadLocationException ble) {
                                            logger.warn(
                                                    "Could not remove @ symbol after selection in ActionListener", ble);
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

                chrome.themeManager.registerPopupMenu(popup);
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

    private class InstructionsCompletionProvider extends DefaultCompletionProvider {
        private final Map<String, List<Completion>> completionCache = new ConcurrentHashMap<>();
        private static final int CACHE_SIZE = 100;

        @Override
        public String getAlreadyEnteredText(JTextComponent comp) {
            Document doc = comp.getDocument();
            int dot = comp.getCaretPosition();
            Element root = doc.getDefaultRootElement();
            int line = root.getElementIndex(dot);
            int lineStart = root.getElement(line).getStartOffset();
            try {
                String lineText = doc.getText(lineStart, dot - lineStart);
                int space = lineText.lastIndexOf(' ');
                int tab = lineText.lastIndexOf('\t');
                int separator = Math.max(space, tab);
                return lineText.substring(separator + 1);
            } catch (BadLocationException e) {
                logger.warn("BadLocationException in getAlreadyEnteredText", e);
                return "";
            }
        }

        @Override
        public List<Completion> getCompletions(JTextComponent comp) {
            String text = getAlreadyEnteredText(comp);
            if (text.isEmpty()) {
                return List.of();
            }

            // Check cache first
            List<Completion> cached = completionCache.get(text);
            if (cached != null) {
                return cached;
            }

            List<Completion> completions;
            if (text.contains("/") || text.contains("\\")) {
                var allFiles = contextManager.getProject().getAllFiles();
                List<ShorthandCompletion> fileCompletions = Completions.scoreShortAndLong(
                        text,
                        allFiles,
                        ProjectFile::getFileName,
                        ProjectFile::toString,
                        f -> 0,
                        f -> new ShorthandCompletion(this, f.getFileName(), f.toString()));
                completions = new ArrayList<>(fileCompletions.stream().limit(50).toList());
            } else {
                var analyzer = contextManager.getAnalyzerWrapper().getNonBlocking();
                if (analyzer == null) {
                    return List.of();
                }
                var symbols = Completions.completeSymbols(text, analyzer);
                completions = symbols.stream()
                        .limit(50)
                        .map(symbol -> (Completion) new ShorthandCompletion(this, symbol.shortName(), symbol.fqName()))
                        .toList();
            }

            // Cache the result
            if (completionCache.size() > CACHE_SIZE) {
                completionCache.clear();
            }
            completionCache.put(text, completions);

            return completions;
        }
    }
}
