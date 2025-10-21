package io.github.jbellis.brokk.gui;

import static io.github.jbellis.brokk.gui.Constants.*;
import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.agents.ArchitectAgent;
import io.github.jbellis.brokk.agents.CodeAgent;
import io.github.jbellis.brokk.agents.SearchAgent;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.difftool.utils.ColorUtil;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.components.ModelBenchmarkData;
import io.github.jbellis.brokk.gui.components.ModelSelector;
import io.github.jbellis.brokk.gui.components.OverlayPanel;
import io.github.jbellis.brokk.gui.components.SplitButton;
import io.github.jbellis.brokk.gui.components.TokenUsageBar;
import io.github.jbellis.brokk.gui.dialogs.SettingsDialog;
import io.github.jbellis.brokk.gui.dialogs.SettingsGlobalPanel;
import io.github.jbellis.brokk.gui.git.GitWorktreeTab;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.util.FileDropHandlerFactory;
import io.github.jbellis.brokk.gui.util.Icons;
import io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil;
import io.github.jbellis.brokk.gui.wand.WandAction;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.util.GlobalUiSettings;
import io.github.jbellis.brokk.util.Messages;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.text.*;
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

    private static final String PLACEHOLDER_TEXT = "Put your instructions or questions here.";

    private final Chrome chrome;
    private final JTextArea instructionsArea;
    private final VoiceInputButton micButton;
    private final ActionSplitButton actionButton;
    private final WandButton wandButton;
    private final ModelSelector modelSelector;
    private final TokenUsageBar tokenUsageBar;
    private String storedAction;
    private SplitButton historyDropdown;
    private final ContextManager contextManager;
    private WorkspaceItemsChipPanel workspaceItemsChipPanel;
    private final JPanel centerPanel;
    private ContextAreaContainer contextAreaContainer;
    private @Nullable JComponent inputLayeredPane;
    private @Nullable JLabel modeBadge;
    private final Color defaultActionButtonBg;
    private final Color secondaryActionButtonBg;

    public static class ContextAreaContainer extends JPanel {
        private boolean isDragOver = false;
        private TokenUsageBar.WarningLevel warningLevel = TokenUsageBar.WarningLevel.NONE;

        public ContextAreaContainer() {
            super(new BorderLayout());
        }

        public void setDragOver(boolean dragOver) {
            if (this.isDragOver != dragOver) {
                this.isDragOver = dragOver;
                repaint();
            }
        }

        public void setWarningLevel(TokenUsageBar.WarningLevel level) {
            if (this.warningLevel != level) {
                this.warningLevel = level;
                updateBorderColor();
                repaint();
            }
        }

        private void updateBorderColor() {
            Color borderColor = UIManager.getColor("Component.borderColor");
            int thickness = 1;
            if (warningLevel == TokenUsageBar.WarningLevel.RED) {
                borderColor = new Color(0xFF4444);
                thickness = 3;
            } else if (warningLevel == TokenUsageBar.WarningLevel.YELLOW) {
                borderColor = new Color(0xFFA500);
                thickness = 3;
            }

            var lineBorder = BorderFactory.createLineBorder(borderColor, thickness);
            var titledBorder = BorderFactory.createTitledBorder(lineBorder, "Context");
            var marginBorder = BorderFactory.createEmptyBorder(8, 8, 8, 8);
            setBorder(BorderFactory.createCompoundBorder(marginBorder, titledBorder));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
        }

        @Override
        protected void paintChildren(Graphics g) {
            if (!isDragOver) {
                super.paintChildren(g);
            }

            if (isDragOver) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    Color panelBg = UIManager.getColor("Panel.background");
                    if (panelBg == null) {
                        panelBg = getBackground();
                        if (panelBg == null) {
                            panelBg = Color.LIGHT_GRAY;
                        }
                    }

                    // use panel background directly for the overlay
                    g2.setColor(panelBg);
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.95f));
                    g2.fillRect(0, 0, getWidth(), getHeight());

                    // dashed border with accent color for highlight
                    Color accent = UIManager.getColor("Component.focusColor");
                    if (accent == null) {
                        accent = new Color(0x1F6FEB);
                    }
                    g2.setColor(accent);
                    g2.setComposite(AlphaComposite.SrcOver);
                    g2.setStroke(
                            new BasicStroke(3, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[] {9}, 0));
                    g2.drawRoundRect(4, 4, getWidth() - 9, getHeight() - 9, 12, 12);

                    // Text
                    String text = "Drop to add to Workspace";
                    g2.setColor(readableTextForBackground(panelBg));
                    g2.setFont(getFont().deriveFont(Font.BOLD, 16f));
                    FontMetrics fm = g2.getFontMetrics();
                    int textWidth = fm.stringWidth(text);
                    g2.drawString(
                            text, (getWidth() - textWidth) / 2, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                } finally {
                    g2.dispose();
                }
            }
        }

        @Override
        public Dimension getMaximumSize() {
            Dimension pref = getPreferredSize();
            return new Dimension(Integer.MAX_VALUE, pref.height);
        }
    }

    /** Pick a readable text color (white or dark) against the given background color. */
    private static Color readableTextForBackground(Color background) {
        double r = background.getRed() / 255.0;
        double g = background.getGreen() / 255.0;
        double b = background.getBlue() / 255.0;
        double lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return lum < 0.5 ? Color.WHITE : new Color(0x1E1E1E);
    }

    private final OverlayPanel commandInputOverlay; // Overlay to initially disable command input
    private final UndoManager commandInputUndoManager;
    private AutoCompletion instructionAutoCompletion;
    private InstructionsCompletionProvider instructionCompletionProvider;
    private JPopupMenu tokenUsageBarPopupMenu;

    public InstructionsPanel(Chrome chrome) {
        super(new BorderLayout(2, 2));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        this.chrome = chrome;
        this.contextManager = chrome.getContextManager();
        this.workspaceItemsChipPanel = new WorkspaceItemsChipPanel(chrome);
        this.commandInputUndoManager = new UndoManager();
        commandInputOverlay = new OverlayPanel(overlay -> activateCommandInput(), "Click to enter your instructions");
        commandInputOverlay.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));

        // Set up custom focus traversal policy for tab navigation
        setFocusTraversalPolicy(new InstructionsPanelFocusTraversalPolicy());
        setFocusCycleRoot(true);
        setFocusTraversalPolicyProvider(true);

        // Initialize components
        instructionsArea = buildCommandInputField(); // Build first to add listener
        wandButton = new WandButton(
                contextManager, chrome, instructionsArea, this::getInstructions, this::populateInstructionsArea);
        micButton = new VoiceInputButton(
                instructionsArea,
                contextManager,
                () -> {
                    activateCommandInput();
                    chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Recording");
                },
                msg -> chrome.toolError(msg, "Error"));
        micButton.setFocusable(true);

        // Keyboard shortcut: Cmd/Ctrl+Shift+I opens the Attach Context dialog
        KeyboardShortcutUtil.registerGlobalShortcut(
                chrome.getFrame().getRootPane(),
                KeyboardShortcutUtil.createPlatformShiftShortcut(KeyEvent.VK_I),
                "attachContext",
                () -> SwingUtilities.invokeLater(() -> chrome.getContextPanel().attachContextViaDialog()));

        // Load stored action with cascading fallback: project → global → default
        storedAction = loadActionMode();

        this.defaultActionButtonBg = UIManager.getColor("Button.default.background");
        this.secondaryActionButtonBg = UIManager.getColor("Button.background");

        // Create split action button with dropdown
        actionButton = new ActionSplitButton(() -> isActionRunning(), ACTION_SEARCH); // Default to Search

        actionButton.setOpaque(false);
        actionButton.setContentAreaFilled(false);
        actionButton.setFocusPainted(true);
        actionButton.setFocusable(true);
        actionButton.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        actionButton.setRolloverEnabled(true);
        actionButton.addActionListener(e -> onActionButtonPressed());
        actionButton.setBackground(this.defaultActionButtonBg);

        // Synchronize button's selected mode with loaded preference
        actionButton.setSelectedMode(storedAction);
        // Initialize tooltip to reflect the selected mode
        SwingUtilities.invokeLater(actionButton::updateTooltip);

        // Listen for mode changes from the dropdown
        actionButton.addModeChangeListener(mode -> {
            storedAction = mode;
            // Save to both global and project preferences
            MainProject.setGlobalActionMode(mode);
            chrome.getProject().saveActionMode(mode);
            refreshModeIndicator();
        });

        modelSelector = new ModelSelector(chrome);
        modelSelector.selectConfig(chrome.getProject().getCodeModelConfig());
        modelSelector.addSelectionListener(cfg -> chrome.getProject().setCodeModelConfig(cfg));
        // Also recompute token/cost indicator when model changes
        modelSelector.addSelectionListener(cfg -> updateTokenCostIndicator());
        // Ensure model selector component is focusable
        modelSelector.getComponent().setFocusable(true);

        // Initialize TokenUsageBar (left of Attach button)
        tokenUsageBar = new TokenUsageBar(chrome);
        tokenUsageBar.setVisible(false);
        tokenUsageBar.setAlignmentY(Component.CENTER_ALIGNMENT);
        tokenUsageBar.setToolTipText("Shows Workspace token usage and estimated cost.");
        // Click toggles Workspace collapse/expand
        tokenUsageBar.setOnClick(() -> chrome.toggleWorkspaceCollapsed());

        // Initialize TokenUsageBar popup menu
        tokenUsageBarPopupMenu = new JPopupMenu();

        JMenuItem dropAllMenuItem = new JMenuItem("Drop All");
        dropAllMenuItem.addActionListener(
                e -> chrome.getContextPanel().performContextActionAsync(WorkspacePanel.ContextAction.DROP, List.of()));
        tokenUsageBarPopupMenu.add(dropAllMenuItem);

        JMenuItem copyAllMenuItem = new JMenuItem("Copy All");
        copyAllMenuItem.addActionListener(
                e -> chrome.getContextPanel().performContextActionAsync(WorkspacePanel.ContextAction.COPY, List.of()));
        tokenUsageBarPopupMenu.add(copyAllMenuItem);

        JMenuItem pasteMenuItem = new JMenuItem("Paste text, images, urls");
        pasteMenuItem.addActionListener(
                e -> chrome.getContextPanel().performContextActionAsync(WorkspacePanel.ContextAction.PASTE, List.of()));
        tokenUsageBarPopupMenu.add(pasteMenuItem);

        SwingUtilities.invokeLater(() -> chrome.themeManager.registerPopupMenu(tokenUsageBarPopupMenu));

        // Top Bar (History, Configure Models, Stop) (North)
        JPanel topBarPanel = buildTopBarPanel();
        add(topBarPanel, BorderLayout.NORTH);

        // Center Panel (Command Input + Result) (Center)
        this.centerPanel = buildCenterPanel();
        add(this.centerPanel, BorderLayout.CENTER);

        // Bottom Bar (Mic, Model, Actions) (South)
        JPanel bottomPanel = buildBottomPanel();
        add(bottomPanel, BorderLayout.SOUTH);

        // Do not set a global default button on the root pane. This prevents plain Enter
        // from submitting when focus is in other UI components (e.g., history/branch lists).

        // Add this panel as a listener to context changes
        this.contextManager.addContextListener(this);

        // --- Autocomplete Setup ---
        instructionCompletionProvider = new InstructionsCompletionProvider();
        instructionAutoCompletion = new AutoCompletion(instructionCompletionProvider);
        instructionAutoCompletion.setAutoActivationEnabled(false);
        instructionAutoCompletion.setTriggerKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK));
        instructionAutoCompletion.install(instructionsArea);

        // Buttons start disabled and will be enabled by ContextManager when session loading completes
        disableButtons();

        // Initial compute of the token/cost indicator
        updateTokenCostIndicator();

        // Initialize mode indicator
        refreshModeIndicator();
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

        // Submit shortcut is handled globally by Chrome.registerGlobalKeyboardShortcuts()

        // Undo/Redo shortcuts are handled globally by Chrome.registerGlobalKeyboardShortcuts()

        // Ctrl/Cmd + V  →  if clipboard has an image, route to WorkspacePanel paste;
        // otherwise, use the default JTextArea paste behaviour.
        var pasteKeyStroke = KeyStroke.getKeyStroke(
                KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
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
                        // Log at trace to avoid noise; proceed with default paste handling
                        logger.trace("Clipboard flavor probe failed during smartPaste; falling back to default", ex);
                    }
                }

                if (!imageHandled) {
                    area.paste(); // Default text paste
                }
            }
        });

        // Add Shift+Enter shortcut to insert a newline
        var shiftEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK);
        area.getInputMap().put(shiftEnter, "insertNewline");
        area.getActionMap().put("insertNewline", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int caretPosition = area.getCaretPosition();
                area.insert("\n", caretPosition);
            }
        });

        // Override Tab key to shift focus instead of inserting tab character
        var tabKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0);
        area.getInputMap().put(tabKeyStroke, "transferFocus");
        area.getActionMap().put("transferFocus", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                area.transferFocus();
            }
        });

        // Override Shift+Tab key to shift focus backward
        var shiftTabKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK);
        area.getInputMap().put(shiftTabKeyStroke, "transferFocusBackward");
        area.getActionMap().put("transferFocusBackward", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                area.transferFocusBackward();
            }
        });

        return area;
    }

    private JPanel buildTopBarPanel() {
        // Replaced history dropdown with compact branch split button in top bar
        var topBarPanel = new JPanel();
        topBarPanel.setLayout(new BoxLayout(topBarPanel, BoxLayout.X_AXIS));
        topBarPanel.setBorder(BorderFactory.createEmptyBorder(0, H_PAD, 2, H_PAD));

        // Initialize mode badge
        modeBadge = new JLabel("LUTZ MODE");
        modeBadge.setOpaque(true);
        modeBadge.setFont(modeBadge.getFont().deriveFont(Font.BOLD, 10f));
        modeBadge.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        modeBadge.setHorizontalAlignment(SwingConstants.CENTER);
        modeBadge.setAlignmentY(Component.CENTER_ALIGNMENT);

        this.historyDropdown = createHistoryDropdown();
        historyDropdown.setAlignmentY(Component.CENTER_ALIGNMENT);
        wandButton.setAlignmentY(Component.CENTER_ALIGNMENT);
        micButton.setAlignmentY(Component.CENTER_ALIGNMENT);

        topBarPanel.add(modeBadge);
        topBarPanel.add(Box.createHorizontalGlue());
        topBarPanel.add(historyDropdown);
        topBarPanel.add(wandButton);
        topBarPanel.add(micButton);

        return topBarPanel;
    }

    private JPanel buildCenterPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));

        // Command Input Field
        JScrollPane commandScrollPane = new JScrollPane(instructionsArea);
        commandScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        commandScrollPane.setPreferredSize(new Dimension(600, 80));
        commandScrollPane.setMinimumSize(new Dimension(100, 0));

        // Create layered pane with overlay
        this.inputLayeredPane = commandInputOverlay.createLayeredPane(commandScrollPane);

        panel.add(this.inputLayeredPane);

        // Context area below the input
        this.contextAreaContainer = createContextAreaContainer();
        panel.add(this.contextAreaContainer);

        return panel;
    }

    /**
     * Initializes the file-reference table that sits directly beneath the command-input field and wires a context-menu
     * that targets the specific badge the mouse is over (mirrors ContextPanel behaviour).
     */
    private ContextAreaContainer createContextAreaContainer() {
        // Wire chip removal behavior: block while LLM running; otherwise drop and refocus input
        workspaceItemsChipPanel.setOnRemoveFragment(fragment -> {
            var cm = chrome.getContextManager();
            if (cm.isLlmTaskInProgress()) {
                chrome.showNotification(
                        IConsoleIO.NotificationRole.INFO, "An action is running; cannot modify Workspace now.");
                return;
            }
            cm.drop(List.of(fragment));
            requestCommandInputFocus();
        });

        // Sizer panel computes rows (1..5) based on current width and chip widths.
        var chipsSizer = new JPanel(new BorderLayout()) {
            private int computeRowsForWidth(int contentWidth) {
                if (contentWidth <= 0) return 1;
                int rows = 1;
                int hgap = 6;
                if (workspaceItemsChipPanel.getLayout() instanceof FlowLayout fl) {
                    hgap = fl.getHgap();
                }
                int lineWidth = 0;
                for (var comp : workspaceItemsChipPanel.getComponents()) {
                    if (!comp.isVisible()) continue;
                    int w = comp.getPreferredSize().width;
                    int next = (lineWidth == 0 ? w : lineWidth + hgap + w);
                    if (next <= contentWidth) {
                        lineWidth = next;
                    } else {
                        rows++;
                        lineWidth = w;
                        if (rows >= 2) break;
                    }
                }
                return Math.max(1, Math.min(2, rows));
            }

            @Override
            public Dimension getPreferredSize() {
                // Estimate height: rows * rowH + inter-row vgap
                int width = getWidth();
                if (width <= 0 && getParent() != null) {
                    width = getParent().getWidth();
                }
                Insets in = getInsets();
                int contentWidth = Math.max(0, width - (in == null ? 0 : in.left + in.right));

                int rows = computeRowsForWidth(contentWidth);
                int fmH = instructionsArea
                        .getFontMetrics(instructionsArea.getFont())
                        .getHeight();
                int rowH = Math.max(24, fmH + 8);
                int vgap = 4;
                if (workspaceItemsChipPanel.getLayout() instanceof FlowLayout fl) {
                    vgap = fl.getVgap();
                }
                int chipsHeight = (rows * rowH) + (rows > 1 ? (rows - 1) * vgap : 0);
                Dimension pref = new Dimension(Math.max(100, super.getPreferredSize().width), chipsHeight);
                return pref;
            }

            @Override
            public Dimension getMaximumSize() {
                // Prevent vertical stretching; let width expand
                Dimension pref = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, pref.height);
            }
        };
        chipsSizer.setOpaque(false);
        var chipsScrollPane = new JScrollPane(workspaceItemsChipPanel);
        chipsScrollPane.setBorder(BorderFactory.createEmptyBorder());
        chipsScrollPane.setOpaque(false);
        chipsScrollPane.getViewport().setOpaque(false);
        chipsScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        chipsScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        chipsSizer.add(chipsScrollPane, BorderLayout.CENTER);

        // Bottom line: TokenUsageBar (fills) + Attach button on the right
        var attachButton = new HighContrastAwareButton();
        SwingUtilities.invokeLater(() -> attachButton.setIcon(Icons.ATTACH_FILE));
        attachButton.setToolTipText("Add content to workspace (Ctrl/Cmd+Shift+I)");
        attachButton.setFocusable(false);
        attachButton.setOpaque(false);
        attachButton.addActionListener(e -> chrome.getContextPanel().attachContextViaDialog());

        var bottomLinePanel = new JPanel(new BorderLayout(H_GAP, 0));
        bottomLinePanel.setOpaque(false);
        bottomLinePanel.setBorder(BorderFactory.createEmptyBorder(2, 5, 0, 0));

        // Ensure the token bar expands to fill available width
        tokenUsageBar.setAlignmentY(Component.CENTER_ALIGNMENT);

        // Add right-click handler to TokenUsageBar
        tokenUsageBar.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    tokenUsageBarPopupMenu.show(tokenUsageBar, e.getX(), e.getY());
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    tokenUsageBarPopupMenu.show(tokenUsageBar, e.getX(), e.getY());
                }
            }
        });

        bottomLinePanel.add(tokenUsageBar, BorderLayout.CENTER);

        var contextRightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        contextRightPanel.setOpaque(false);
        contextRightPanel.add(attachButton);
        bottomLinePanel.add(contextRightPanel, BorderLayout.EAST);

        // The InteractiveHoverPanel now manages its own layout and hover logic
        var hoverPanel = new InteractiveHoverPanel(chipsSizer, bottomLinePanel, workspaceItemsChipPanel, tokenUsageBar);
        hoverPanel.setBorder(BorderFactory.createEmptyBorder(V_GLUE, H_PAD, V_GLUE, H_PAD));
        hoverPanel.install();

        // Constrain vertical growth to preferred height so it won't stretch on window resize.
        var titledContainer = new ContextAreaContainer();
        titledContainer.setOpaque(true);
        // Initialize border with default color (will be updated by setWarningLevel)
        var lineBorder = BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"));
        var titledBorder = BorderFactory.createTitledBorder(lineBorder, "Context");
        var marginBorder = BorderFactory.createEmptyBorder(4, 4, 4, 4);
        titledContainer.setBorder(BorderFactory.createCompoundBorder(marginBorder, titledBorder));
        var transferHandler = FileDropHandlerFactory.createFileDropHandler(this.chrome);
        titledContainer.setTransferHandler(transferHandler);
        var dropTargetListener = new DropTargetAdapter() {
            @Override
            public void dragEnter(DropTargetDragEvent e) {
                var support = new TransferHandler.TransferSupport(titledContainer, e.getTransferable());
                if (transferHandler.canImport(support)) {
                    titledContainer.setDragOver(true);
                    e.acceptDrag(DnDConstants.ACTION_COPY);
                } else {
                    e.rejectDrag();
                }
            }

            @Override
            public void dragOver(DropTargetDragEvent e) {
                var support = new TransferHandler.TransferSupport(titledContainer, e.getTransferable());
                if (transferHandler.canImport(support)) {
                    e.acceptDrag(DnDConstants.ACTION_COPY);
                } else {
                    titledContainer.setDragOver(false);
                    e.rejectDrag();
                }
            }

            @Override
            public void dragExit(DropTargetEvent e) {
                titledContainer.setDragOver(false);
            }

            @Override
            public void drop(DropTargetDropEvent e) {
                titledContainer.setDragOver(false);

                var transferable = e.getTransferable();
                var support = new TransferHandler.TransferSupport(titledContainer, transferable);
                if (transferHandler.canImport(support)) {
                    e.acceptDrop(e.getDropAction());
                    if (!transferHandler.importData(support)) {
                        e.dropComplete(false);
                    } else {
                        e.dropComplete(true);
                    }
                } else {
                    e.rejectDrop();
                    e.dropComplete(false);
                }
            }
        };
        titledContainer.setDropTarget(
                new DropTarget(titledContainer, DnDConstants.ACTION_COPY, dropTargetListener, true));
        titledContainer.add(hoverPanel, BorderLayout.CENTER);

        // Add mouse listener for right-click context menu in empty space
        titledContainer.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showMenuIfNotOnChip(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showMenuIfNotOnChip(e);
                }
            }

            private void showMenuIfNotOnChip(MouseEvent e) {
                Component clickedComponent = e.getComponent();
                if (SwingUtilities.isDescendingFrom(clickedComponent, workspaceItemsChipPanel)) {
                    return;
                }
                tokenUsageBarPopupMenu.show(titledContainer, e.getX(), e.getY());
            }
        });

        return titledContainer;
    }

    /** Recomputes the token usage bar to mirror the Workspace panel summary. Safe to call from any thread. */
    private void updateTokenCostIndicator() {
        var ctx = chrome.getContextManager().selectedContext();
        Service.ModelConfig config = getSelectedConfig();
        var service = chrome.getContextManager().getService();
        var model = service.getModel(config);

        // Compute tokens off-EDT
        chrome.getContextManager()
                .submitBackgroundTask("Compute token estimate (Instructions)", () -> {
                    if (model == null || model instanceof Service.UnavailableStreamingModel) {
                        return new TokenUsageBarComputation(
                                buildTokenUsageTooltip(
                                        "Unavailable", 128000, "0.00", TokenUsageBar.WarningLevel.NONE, 100),
                                128000,
                                0,
                                TokenUsageBar.WarningLevel.NONE,
                                config,
                                100);
                    }

                    var fullText = new StringBuilder();
                    if (ctx != null && !ctx.isEmpty()) {
                        // Build full text of current context, similar to WorkspacePanel
                        var allFragments = ctx.getAllFragmentsInDisplayOrder();
                        for (var frag : allFragments) {
                            if (frag.isText() || frag.getType().isOutput()) {
                                fullText.append(frag.text()).append("\n");
                            }
                        }
                    }

                    int approxTokens = Messages.getApproximateTokens(fullText.toString());
                    int maxTokens = service.getMaxInputTokens(model);
                    if (maxTokens <= 0) {
                        // Fallback to a generous default when service does not provide a limit
                        maxTokens = 128_000;
                    }
                    String modelName = config.name();
                    String costStr = calculateCostEstimate(config, approxTokens, service);

                    int successRate = ModelBenchmarkData.getSuccessRate(config, approxTokens);
                    TokenUsageBar.WarningLevel warningLevel;
                    if (successRate == -1) {
                        // Unknown/untested combination: don't warn
                        warningLevel = TokenUsageBar.WarningLevel.NONE;
                    } else if (successRate < 30) {
                        warningLevel = TokenUsageBar.WarningLevel.RED;
                    } else if (successRate < 50) {
                        warningLevel = TokenUsageBar.WarningLevel.YELLOW;
                    } else {
                        warningLevel = TokenUsageBar.WarningLevel.NONE;
                    }

                    String tooltipHtml =
                            buildTokenUsageTooltip(modelName, maxTokens, costStr, warningLevel, successRate);
                    return new TokenUsageBarComputation(
                            tooltipHtml, maxTokens, approxTokens, warningLevel, config, successRate);
                })
                .thenAccept(stat -> SwingUtilities.invokeLater(() -> {
                    try {
                        if (stat == null) {
                            tokenUsageBar.setVisible(false);
                            contextAreaContainer.setWarningLevel(TokenUsageBar.WarningLevel.NONE);
                            return;
                        }
                        // Update max and unfilled-portion tooltip; fragment breakdown is supplied via contextChanged
                        tokenUsageBar.setMaxTokens(stat.maxTokens);
                        tokenUsageBar.setUnfilledTooltip(stat.toolTipHtml);
                        contextAreaContainer.setWarningLevel(stat.warningLevel);
                        contextAreaContainer.setToolTipText(stat.toolTipHtml);
                        modelSelector.getComponent().setToolTipText(stat.toolTipHtml);
                        tokenUsageBar.setVisible(true);
                    } catch (Exception ex) {
                        logger.debug("Failed to update token usage bar", ex);
                        tokenUsageBar.setVisible(false);
                        contextAreaContainer.setWarningLevel(TokenUsageBar.WarningLevel.NONE);
                    }
                }));
    }

    private static record TokenUsageBarComputation(
            String toolTipHtml,
            int maxTokens,
            int approxTokens,
            TokenUsageBar.WarningLevel warningLevel,
            Service.ModelConfig config,
            int successRate) {}
    /** Calculate cost estimate mirroring WorkspacePanel for only the model currently selected in InstructionsPanel. */
    private String calculateCostEstimate(Service.ModelConfig config, int inputTokens, Service service) {
        var pricing = service.getModelPricing(config.name());
        if (pricing.bands().isEmpty()) {
            return "";
        }

        long estimatedOutputTokens = Math.min(4000, inputTokens / 2);
        if (service.isReasoning(config)) {
            estimatedOutputTokens += 1000;
        }
        double estimatedCost = pricing.estimateCost(inputTokens, 0, estimatedOutputTokens);

        if (service.isFreeTier(config.name())) {
            return "$0.00 (Free Tier)";
        } else {
            return String.format("$%.2f", estimatedCost);
        }
    }

    // Tooltip helpers for TokenUsageBar (HTML-wrapped, similar to chip tooltips)
    private static String buildTokenUsageTooltip(
            String modelName,
            int maxTokens,
            String costPerRequest,
            TokenUsageBar.WarningLevel warningLevel,
            int successRate) {
        StringBuilder body = new StringBuilder();

        if (warningLevel != TokenUsageBar.WarningLevel.NONE) {
            body.append("<div style='color: ")
                    .append(warningLevel == TokenUsageBar.WarningLevel.RED ? "#FF4444" : "#FFA500")
                    .append("; font-weight: bold;'>⚠ Performance Warning</div>");
            body.append("<div style='margin-top: 4px;'>The model <b>")
                    .append(htmlEscape(modelName))
                    .append("</b> may perform poorly at this token count.</div>");
            body.append("<hr style='border:0;border-top:1px solid #ccc;margin:8px 0;'/>");
        }

        body.append("<div><b>Context</b></div>");
        body.append("<div>Model: ").append(htmlEscape(modelName)).append("</div>");
        body.append("<div>Max input tokens: ")
                .append(String.format("%,d", maxTokens))
                .append("</div>");
        if (!costPerRequest.isBlank()) {
            body.append("<div>Estimated cost/request: ")
                    .append(htmlEscape(costPerRequest))
                    .append("</div>");
        }

        body.append("<hr style='border:0;border-top:1px solid #ccc;margin:8px 0;'/>");
        body.append("<div><b><a href='https://brokk.ai/power-ranking' style='color: #1F6FEB; text-decoration: none;'>")
                .append("Brokk Power Ranking</a></b></div>");
        if (successRate == -1) {
            body.append("<div style='margin-top: 4px;'>Success rate: <b>Unknown</b></div>");
            body.append("<div style='margin-top: 2px; font-size: 0.9em; color: #666;'>")
                    .append("Untested model reasoning combination.</div>");
        } else {
            body.append("<div style='margin-top: 4px;'>Success rate at this token count: <b>")
                    .append(successRate)
                    .append("%</b></div>");
            body.append("<div style='margin-top: 2px; font-size: 0.9em; color: #666;'>")
                    .append("Based on benchmark data for model performance across token ranges.</div>");
        }

        return wrapTooltipHtml(body.toString(), 420);
    }

    private static String wrapTooltipHtml(String innerHtml, int maxWidthPx) {
        return "<html><body style='width: " + maxWidthPx + "px'>" + innerHtml + "</body></html>";
    }

    private static String htmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public void refreshBranchUi(String branchName) {
        // Delegate to Chrome which now owns the BranchSelectorButton
        chrome.refreshBranchUi(branchName);
    }

    /**
     * Format a KeyStroke into a human-readable short string such as "Ctrl+M" or "Meta+Enter". Falls back to
     * KeyStroke.toString() on error.
     */
    private static String formatKeyStroke(KeyStroke ks) {
        try {
            int modifiers = ks.getModifiers();
            int keyCode = ks.getKeyCode();
            String modText = InputEvent.getModifiersExText(modifiers);
            String keyText = KeyEvent.getKeyText(keyCode);
            if (modText == null || modText.isBlank()) return keyText;
            return modText + "+" + keyText;
        } catch (Exception e) {
            return ks.toString();
        }
    }

    private JPanel buildBottomPanel() {
        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.LINE_AXIS));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        // Flexible space before model selector and action button
        bottomPanel.add(Box.createHorizontalGlue());

        // Model selector on the right
        var modelComp = modelSelector.getComponent();
        modelComp.setAlignmentY(Component.CENTER_ALIGNMENT);
        modelComp.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, H_GAP));
        bottomPanel.add(modelComp);
        bottomPanel.add(Box.createHorizontalStrut(H_GAP));

        // Action split button (with integrated mode dropdown) on the right
        actionButton.setAlignmentY(Component.CENTER_ALIGNMENT);
        // Make the button bigger to accommodate text + dropdown
        int fixedHeight = Math.max(actionButton.getPreferredSize().height, 36);
        var prefSize = new Dimension(140, fixedHeight);
        actionButton.setPreferredSize(prefSize);
        actionButton.setMinimumSize(prefSize);
        actionButton.setMaximumSize(prefSize);
        actionButton.setMargin(new Insets(4, 4, 4, 10));
        actionButton.setIconTextGap(0);
        actionButton.setHorizontalTextPosition(SwingConstants.RIGHT);

        // Repaint when focus changes so focus border is visible
        actionButton.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                actionButton.repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                actionButton.repaint();
            }
        });

        bottomPanel.add(actionButton);

        // Lock bottom toolbar height so BorderLayout keeps it visible
        Dimension bottomPref = bottomPanel.getPreferredSize();
        bottomPanel.setMinimumSize(new Dimension(0, bottomPref.height));

        return bottomPanel;
    }

    private SplitButton createHistoryDropdown() {
        final var noHistory = "(No history items)";

        var project = chrome.getProject();
		// this is a dirty hack since the flow layout breaks the split button
        var dropdown = new SplitButton("____", true);
        dropdown.setToolTipText("History");
        SwingUtilities.invokeLater(() -> {
        	dropdown.setIcon(Icons.HISTORY);
        	dropdown.setText("");
        });
        // Build popup menu on demand, same pattern as branch button
        Supplier<JPopupMenu> historyMenuSupplier = () -> {
            var menu = new JPopupMenu();
            List<String> historyItems = project.loadTextHistory();

            logger.trace("History items loaded: {}", historyItems.size());
            if (historyItems.isEmpty()) {
                JMenuItem noHistoryItem = new JMenuItem(noHistory);
                noHistoryItem.setEnabled(false);
                menu.add(noHistoryItem);
            } else {
                for (var item : historyItems) {
                    JMenuItem historyMenuItem = new JMenuItem();
                    // Truncate display text but keep full text in tooltip
                    String displayText = item.replace('\n', ' ');
                    if (displayText.length() > 60) {
                        displayText = displayText.substring(0, 57) + "...";
                    }
                    historyMenuItem.setText(displayText);
                    historyMenuItem.setToolTipText(item);

                    historyMenuItem.addActionListener(ev -> {
                        commandInputOverlay.hideOverlay();
                        instructionsArea.setEnabled(true);

                        instructionsArea.setText(item);
                        commandInputUndoManager.discardAllEdits();
                        instructionsArea.requestFocusInWindow();
                    });
                    menu.add(historyMenuItem);
                }
            }
            return menu;
        };

        dropdown.setMenuSupplier(historyMenuSupplier);

        // Show popup when main button area is clicked (same as branch button)
        dropdown.addActionListener(ev -> SwingUtilities.invokeLater(() -> {
            try {
                var menu = historyMenuSupplier.get();
                chrome.themeManager.registerPopupMenu(menu);
                menu.show(dropdown, 0, dropdown.getHeight());
            } catch (Exception ex) {
                logger.error("Error showing history dropdown", ex);
            }
        }));

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
                .anyMatch(f -> !f.isText() && !f.getType().isOutput());
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

    /**
     * Toggle between Code/Ask/Search modes via the split button dropdown.
     * Cycles through modes in order: Code → Ask → Search → Code.
     */
    public void toggleCodeAnswerMode() {
        SwingUtilities.invokeLater(() -> {
            String current = actionButton.getSelectedMode();
            String next =
                    switch (current) {
                        case ACTION_CODE -> ACTION_ASK;
                        case ACTION_ASK -> ACTION_SEARCH;
                        case ACTION_SEARCH -> ACTION_CODE;
                        default -> ACTION_SEARCH;
                    };
            actionButton.setSelectedMode(next);
            storedAction = next;
            // Place focus back in the command input for convenience
            requestCommandInputFocus();
        });
    }

    // --- Private Execution Logic ---

    /**
     * Called by the contextSuggestionTimer or external events (like context changes) to initiate a context suggestion
     * task. It increments the generation counter and submits the task to the sequential worker executor.
     */

    /**
     * Performs the actual context suggestion logic off the EDT. This method includes checks against the current
     * `suggestionGeneration` to ensure only the latest task proceeds and updates the UI.
     *
     * @param myGen The generation number of this specific task.
     * @param snapshot The input text captured when this task was initiated.
     */

    /**
     * Checks if the new text/embeddings are semantically different from the last processed state
     * (`lastCheckedInputText`, `lastCheckedEmbeddings`).
     */

    /** Helper to show the failure label with a message. */

    /** Helper to show the suggestions table with file references. */

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
                    cm.getIo().getLlmRawMessages(),
                    Set.of(),
                    new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED));
        }
        var llm = cm.getLlm(new Llm.Options(model, "Answer: " + question).withEcho());

        return executeAskCommand(llm, messages, cm, question);
    }

    public static TaskResult executeAskCommand(
            Llm llm, List<ChatMessage> messages, IContextManager cm, String question) {
        // Build and send the request to the LLM
        TaskResult.StopDetails stop = null;
        Llm.StreamingResult response = null;
        try {
            response = llm.sendRequest(messages);
        } catch (InterruptedException e) {
            stop = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED);
        }

        // Determine stop details based on the response
        if (response != null) {
            if (response.error() != null) {
                String explanation = Objects.requireNonNullElse(response.error().getMessage(), "Unknown LLM error");
                stop = new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, explanation);
            } else {
                stop = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
            }
        }

        // construct TaskResult
        requireNonNull(stop);
        return new TaskResult(
                cm,
                "Ask: " + question,
                List.copyOf(cm.getIo().getLlmRawMessages()),
                Set.of(), // Ask never changes files
                stop);
    }

    // --- Action Handlers ---

    public void runArchitectCommand() {
        var goal = getInstructions();
        if (goal.isBlank()) {
            chrome.toolError("Please provide an initial goal or instruction for the Architect");
            return;
        }

        chrome.getProject().addToInstructionsHistory(goal, 20);
        clearCommandInput();

        var project = chrome.getProject();
        var runInWorktree = project.getArchitectRunInWorktree();

        if (runInWorktree) {
            runArchitectInNewWorktree(goal);
        } else {
            // User confirmed options, now submit the actual agent execution to the background.
            runArchitectCommand(goal);
        }
    }

    private void runArchitectInNewWorktree(String originalInstructions) {
        var currentProject = chrome.getProject();
        ContextManager cm = chrome.getContextManager();

        // Start branch name generation task (LLM summarization)
        var branchNameWorker = new ContextManager.SummarizeWorker(cm, originalInstructions, 3);
        branchNameWorker.execute();

        // Add to history of current project (already done by caller if not worktree)
        // No need to clearCommandInput, also done by caller

        // don't use submitAction, we're going to kick off a new Worktree + Chrome and run in that, leaving the original
        // free
        cm.submitExclusiveAction(() -> {
            try {
                chrome.showOutputSpinner("Setting up Git worktree...");

                // Retrieve the generated branch name suggestion from the SummarizeWorker
                String rawBranchNameSuggestion = branchNameWorker.get(); // Blocks until SummarizeWorker is done
                String generatedBranchName = cm.getRepo().sanitizeBranchName(rawBranchNameSuggestion);

                // Check Git availability (original position relative to setup)
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

                chrome.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "New worktree created at: " + newWorktreePath + " on branch: " + actualBranchName);

                // Define the initial task to run in the new project, using pre-collected options
                Consumer<Chrome> initialArchitectTask = newWorktreeChrome -> {
                    InstructionsPanel newWorktreeIP = newWorktreeChrome.getInstructionsPanel();
                    // Run the architect command directly with the original instructions and determined options
                    newWorktreeIP.runArchitectCommand(originalInstructions);
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
                            if (success) {
                                chrome.showNotification(
                                        IConsoleIO.NotificationRole.INFO, "New worktree opened for Architect");
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
     */
    public void runArchitectCommand(String goal) {
        submitAction(ACTION_ARCHITECT, goal, scope -> {
            var service = chrome.getContextManager().getService();
            var planningModel = service.getModel(Service.GEMINI_2_5_PRO);
            if (planningModel == null) {
                throw new ModelUnavailableException();
            }

            // Determine Code model from the Instructions dropdown
            Service.ModelConfig codeCfg = modelSelector.getModel();
            var codeModel = service.getModel(codeCfg);
            if (codeModel == null) {
                throw new ModelUnavailableException();
            }

            // Proceed with execution using the selected options
            var agent = new ArchitectAgent(chrome.getContextManager(), planningModel, codeModel, goal, scope);
            return agent.execute();
        });
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
        submitAction(ACTION_CODE, input, () -> {
            var contextManager1 = chrome.getContextManager();

            CodeAgent agent = new CodeAgent(contextManager1, modelToUse);
            var result = agent.runTask(input, Set.of());
            chrome.setSkipNextUpdateOutputPanelOnContextChange(true);
            return result;
        });
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

        chrome.getProject().addToInstructionsHistory(input, 20);
        clearCommandInput();
        // disableButtons() is called by submitAction via chrome.disableActionButtons()
        submitAction(ACTION_ASK, input, () -> {
            var result = executeAskCommand(contextManager, modelToUse, input);

            // Persist to history regardless of success/failure
            chrome.setSkipNextUpdateOutputPanelOnContextChange(true);
            return result;
        });
    }

    public void runSearchCommand() {
        var input = getInstructions();
        if (input.isBlank()) {
            chrome.toolError("Please provide a search query");
            return;
        }

        chrome.getProject().addToInstructionsHistory(input, 20);
        clearCommandInput();
        executeSearchInternal(input);
    }

    private void executeSearchInternal(String query) {
        final var modelToUse = selectDropdownModelOrShowError("Search", true);
        if (modelToUse == null) {
            throw new IllegalStateException("LLM not found, usually this indicates a network error");
        }

        submitAction(ACTION_SEARCH, query, () -> {
            assert !query.isBlank();

            var cm = chrome.getContextManager();
            SearchAgent agent = new SearchAgent(
                    query, cm, modelToUse, EnumSet.of(SearchAgent.Terminal.ANSWER, SearchAgent.Terminal.TASK_LIST));
            var result = agent.execute();
            chrome.setSkipNextUpdateOutputPanelOnContextChange(true);
            return result;
        });
    }

    /**
     * Runs the given task, handling spinner and add-to-history of the TaskResult, including partial result on
     * interruption
     */
    public void submitAction(String action, String input, Callable<TaskResult> task) {
        var cm = chrome.getContextManager();
        // Map some actions to a more user-friendly display string for the spinner.
        // We keep the original `action` (used for LLM output / history) unchanged to avoid
        // affecting other subsystems that detect action by name, but present a clearer label
        // to the user while the operation runs.
        String displayAction;
        if (InstructionsPanel.ACTION_ARCHITECT.equals(action)) {
            displayAction = "Code With Plan";
        } else if (InstructionsPanel.ACTION_SEARCH.equals(action)) {
            displayAction = "Ask with Search";
        } else if (InstructionsPanel.ACTION_ASK.equals(action)) {
            displayAction = "Ask";
        } else {
            displayAction = action;
        }

        cm.submitLlmAction(() -> {
            try {
                chrome.showOutputSpinner("Executing " + displayAction + " command...");
                try (var scope = cm.beginTask(input, false)) {
                    var result = task.call();
                    scope.append(result);
                    if (result.stopDetails().reason() == TaskResult.StopReason.INTERRUPTED) {
                        populateInstructionsArea(input);
                    }
                }
            } finally {
                chrome.hideOutputSpinner();
                contextManager.checkBalanceAndNotify();
                notifyActionComplete(action);
            }
        });
    }

    /** Overload that provides a TaskScope to the task body so callers can pass it to agents. */
    public CompletableFuture<Void> submitAction(
            String action, String input, Function<ContextManager.TaskScope, TaskResult> task) {
        var cm = chrome.getContextManager();
        // Map some actions to a more user-friendly display string for the spinner.
        // We keep the original `finalAction` (used for LLM output / history) unchanged to avoid
        // affecting other subsystems that detect action by name, but present a clearer label
        // to the user while the operation runs.
        String displayAction;
        if (InstructionsPanel.ACTION_ARCHITECT.equals(action)) {
            displayAction = "Code With Plan";
        } else if (InstructionsPanel.ACTION_SEARCH.equals(action)) {
            displayAction = "Ask with Search";
        } else if (InstructionsPanel.ACTION_ASK.equals(action)) {
            displayAction = "Ask";
        } else {
            displayAction = action;
        }

        return cm.submitLlmAction(() -> {
            try {
                chrome.showOutputSpinner("Executing " + displayAction + " command...");
                try (var scope = cm.beginTask(input, false)) {
                    var result = task.apply(scope);
                    scope.append(result);
                    if (result.stopDetails().reason() == TaskResult.StopReason.INTERRUPTED) {
                        populateInstructionsArea(input);
                    }
                }
            } finally {
                chrome.hideOutputSpinner();
                contextManager.checkBalanceAndNotify();
                notifyActionComplete(action);
            }
        });
    }

    // Methods to disable and enable buttons.
    void disableButtons() {
        SwingUtilities.invokeLater(() -> {
            // Keep the action button usable for "Stop" while a task is running.
            if (isActionRunning()) {
                actionButton.showStopMode();
                actionButton.setEnabled(true);
                actionButton.setToolTipText("Cancel the current operation");
                // always use the off red of the light theme
                Color badgeBackgroundColor = ThemeColors.getColor(false, ThemeColors.GIT_BADGE_BACKGROUND);
                actionButton.setBackground(badgeBackgroundColor);
            } else {
                // If there is no running action, keep the action button enabled so the user can start an action.
                actionButton.setEnabled(true);
                actionButton.setBackground(defaultActionButtonBg);
                // Ensure combined tooltip (mode-specific + base) is shown initially
                actionButton.updateTooltip();
            }

            // Wand is disabled while any action is running
            wandButton.setEnabled(!isActionRunning());
        });
    }

    /**
     * Updates the enabled state of all action buttons based on project load status and ContextManager availability.
     * Called when actions complete.
     */
    private void updateButtonStates() {
        SwingUtilities.invokeLater(() -> {
            // Action button reflects current running state
            if (isActionRunning()) {
                actionButton.showStopMode();
                actionButton.setToolTipText("Cancel the current operation");
                actionButton.setBackground(secondaryActionButtonBg);
            } else {
                actionButton.showNormalMode();
                // Keep tooltip consistent: prepend mode-specific tooltip to base tooltip
                actionButton.updateTooltip();
                actionButton.setBackground(defaultActionButtonBg);
            }
            actionButton.setEnabled(true);

            // Enable/disable wand depending on running state
            wandButton.setEnabled(!isActionRunning());

            // Ensure storedAction is consistent with split button's selected mode
            storedAction = actionButton.getSelectedMode();

            chrome.enableHistoryPanel();
        });
    }

    @Override
    public void contextChanged(Context newCtx) {
        var fragments = newCtx.getAllFragmentsInDisplayOrder();
        logger.debug("Context updated: {} fragments", fragments.size());
        workspaceItemsChipPanel.setFragments(fragments);
        // Feed per-fragment data to the token bar
        tokenUsageBar.setFragments(fragments);
        // Update compact token/cost indicator on context change
        updateTokenCostIndicator();
    }

    void enableButtons() {
        // Called when an action completes. Reset buttons based on current CM/project state.
        updateButtonStates();
    }

    private String loadActionMode() {
        // 1. Try project-specific first
        Optional<String> projectMode = chrome.getProject().getActionMode();
        if (projectMode.isPresent() && isValidMode(projectMode.get())) {
            logger.debug("Loading action mode from project settings: {}", projectMode.get());
            return projectMode.get();
        }

        // 2. Fall back to global
        String globalMode = MainProject.getGlobalActionMode();
        if (!globalMode.isEmpty() && isValidMode(globalMode)) {
            logger.debug("Loading action mode from global settings: {}", globalMode);
            return globalMode;
        }

        // 3. Final fallback to default
        logger.debug("No saved action mode found, using default: {}", ACTION_SEARCH);
        return ACTION_SEARCH;
    }

    private boolean isValidMode(String mode) {
        return ACTION_CODE.equals(mode) || ACTION_ASK.equals(mode) || ACTION_SEARCH.equals(mode);
    }

    private void notifyActionComplete(String actionName) {
        chrome.notifyActionComplete("Action '" + actionName + "' completed.");
    }

    private boolean isActionRunning() {
        return chrome.getContextManager().isLlmTaskInProgress();
    }

    public void onActionButtonPressed() {
        if (isActionRunning()) {
            // Stop action
            chrome.getContextManager().interruptLlmAction();
        } else {
            // Go action
            switch (storedAction) {
                case ACTION_ARCHITECT -> runArchitectCommand();
                case ACTION_CODE -> prepareAndRunCodeCommand(getSelectedModel());
                case ACTION_SEARCH -> runSearchCommand();
                case ACTION_ASK -> runAskCommand(getInstructions());
                default -> runArchitectCommand();
            }
        }
        // Always return focus to the instructions area to avoid re-triggering with Enter on the button
        requestCommandInputFocus();
    }

    private void refreshModeIndicator() {
        String mode = actionButton.getSelectedMode();
        boolean isDark = UIManager.getBoolean("laf.dark");
        boolean isHighContrast = GuiTheme.THEME_HIGH_CONTRAST.equalsIgnoreCase(MainProject.getTheme());

        Color badgeBg;
        Color badgeFg;
        Color accent;
        String badgeText;

        try {
            switch (mode) {
                case ACTION_CODE -> {
                    badgeText = "CODE MODE";
                    badgeBg = ThemeColors.getColor(isDark, ThemeColors.MODE_CODE_BG);
                    badgeFg = ThemeColors.getColor(isDark, ThemeColors.MODE_CODE_FG);
                    accent = ThemeColors.getColor(isDark, ThemeColors.MODE_CODE_ACCENT);
                }
                case ACTION_ASK -> {
                    badgeText = "ASK MODE";
                    badgeBg = ThemeColors.getColor(isDark, ThemeColors.MODE_ANSWER_BG);
                    badgeFg = ThemeColors.getColor(isDark, ThemeColors.MODE_ANSWER_FG);
                    accent = ThemeColors.getColor(isDark, ThemeColors.MODE_ANSWER_ACCENT);
                }
                case ACTION_SEARCH -> {
                    badgeText = "LUTZ MODE";
                    badgeBg = ThemeColors.getColor(isDark, ThemeColors.MODE_LUTZ_BG);
                    badgeFg = ThemeColors.getColor(isDark, ThemeColors.MODE_LUTZ_FG);
                    accent = ThemeColors.getColor(isDark, ThemeColors.MODE_LUTZ_ACCENT);
                }
                default -> {
                    badgeText = "LUTZ MODE";
                    badgeBg = new Color(0xF4C430);
                    badgeFg = isDark ? Color.WHITE : new Color(0xF57F17);
                    accent = new Color(0xF4C430);
                }
            }
        } catch (Exception ex) {
            logger.debug("Theme color lookup failed; using fallback colors", ex);
            badgeText = "LUTZ MODE";
            badgeBg = new Color(0xF4C430);
            badgeFg = isDark ? Color.WHITE : new Color(0xF57F17);
            accent = new Color(0xF4C430);
        }

        if (modeBadge != null) {
            modeBadge.setText(badgeText);
            modeBadge.setBackground(badgeBg);
            modeBadge.setForeground(badgeFg);
            modeBadge.repaint();
        }

        if (inputLayeredPane != null) {
            var inner = new EmptyBorder(0, H_PAD, 0, H_PAD);
            var stripe = new javax.swing.border.MatteBorder(0, 4, 0, 0, accent);
            inputLayeredPane.setBorder(BorderFactory.createCompoundBorder(stripe, inner));
            inputLayeredPane.revalidate();
            inputLayeredPane.repaint();
        }

        // Set action button foreground: black in high contrast mode, white otherwise
        Color buttonForeground = isHighContrast ? Color.BLACK : Color.WHITE;
        if (!isActionRunning()) {
            actionButton.setForeground(buttonForeground);
        }
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

    public VoiceInputButton getVoiceInputButton() {
        return this.micButton;
    }

    /**
     * Returns the currently selected Code model configuration from the model selector. Falls back to a reasonable
     * default if none is available.
     */
    public StreamingChatModel getSelectedModel() {
        return contextManager.getModelOrDefault(modelSelector.getModel(), "Selected");
    }

    // TODO this is unnecessary if we can push config into StreamingChatModel
    public Service.ModelConfig getSelectedConfig() {
        return modelSelector.getModel();
    }

    public ContextAreaContainer getContextAreaContainer() {
        return contextAreaContainer;
    }

    public JPanel getCenterPanel() {
        return centerPanel;
    }

    /**
     * Returns the Swing component used by the ModelSelector so it can be moved
     * between panels (Instructions <-> Tasks) as a single shared component.
     */
    public JComponent getModelSelectorComponent() {
        return modelSelector.getComponent();
    }

    /**
     * Ensures the ModelSelector component is attached to the Instructions bottom bar,
     * immediately before the actionButton with proper spacing, and revalidates the layout.
     * Safe to call from any thread.
     */
    public void restoreModelSelectorToBottom() {
        Runnable r = () -> {
            try {
                JComponent comp = modelSelector.getComponent();
                var currentParent = comp.getParent();
                var bottom = actionButton.getParent();
                if (bottom == null) return;

                if (currentParent != bottom) {
                    if (currentParent != null) {
                        currentParent.remove(comp);
                        currentParent.revalidate();
                        currentParent.repaint();
                    }
                    // Insert right before the action button with spacing strut
                    int actionIndex = indexOfChild(bottom, actionButton);
                    if (actionIndex >= 0) {
                        bottom.add(comp, actionIndex);
                        bottom.add(Box.createHorizontalStrut(H_GAP), actionIndex + 1);
                        bottom.revalidate();
                        bottom.repaint();
                    }
                } else {
                    // Already in the right parent; ensure correct ordering with strut
                    int compIndex = indexOfChild(bottom, comp);
                    int actionIndex = indexOfChild(bottom, actionButton);

                    // Check if strut exists right after model selector
                    boolean strutExists = false;
                    if (actionIndex >= 1) {
                        Component potentialStrut = bottom.getComponent(actionIndex - 1);
                        strutExists = potentialStrut instanceof Box.Filler;
                    }

                    if (compIndex != actionIndex - 2 || !strutExists) {
                        // Reposition: remove model selector and any old strut, then re-add both
                        bottom.remove(comp);

                        // Also remove the old strut if it exists
                        int newActionIndex = indexOfChild(bottom, actionButton);
                        if (newActionIndex >= 1) {
                            Component potentialStrut = bottom.getComponent(newActionIndex - 1);
                            if (potentialStrut instanceof Box.Filler) {
                                bottom.remove(potentialStrut);
                                newActionIndex = indexOfChild(bottom, actionButton);
                            }
                        }

                        // Add model selector and new strut before action button
                        if (newActionIndex >= 0) {
                            bottom.add(comp, newActionIndex);
                            bottom.add(Box.createHorizontalStrut(H_GAP), newActionIndex + 1);
                        }
                        bottom.revalidate();
                        bottom.repaint();
                    }
                }
            } catch (Exception ex) {
                logger.debug("restoreModelSelectorToBottom: non-fatal error repositioning model selector", ex);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    private static int indexOfChild(Container parent, Component child) {
        int n = parent.getComponentCount();
        for (int i = 0; i < n; i++) {
            if (parent.getComponent(i) == child) return i;
        }
        return -1;
    }

    /**
     * Register a listener to be notified when the model selection in the InstructionsPanel changes. The listener
     * receives the new Service.ModelConfig.
     */
    public void addModelSelectionListener(Consumer<Service.ModelConfig> listener) {
        modelSelector.addSelectionListener(listener);
    }

    /**
     * Action split button with integrated dropdown for mode selection (Code/Ask/Search).
     * The main button area executes the selected action, while the dropdown arrow shows mode options.
     */
    private static class ActionSplitButton extends MaterialButton implements ThemeAware {
        private static final long serialVersionUID = 1L;
        private final Supplier<Boolean> isActionRunning;
        private @Nullable Icon originalIcon;
        private String selectedMode;
        private final List<Consumer<String>> modeChangeListeners = new ArrayList<>();
        private boolean inStopMode = false;
        private static final int DROPDOWN_WIDTH = 30;
        private @Nullable Icon dropdownIcon;
        private final String baseTooltip;
        private static final String MODE_TOOLTIP_CODE =
                "<b>Code Mode:</b> The Code agent executes your instructions to directly modify the code files currently in the context.";
        private static final String MODE_TOOLTIP_ASK =
                "<b>Ask mode:</b> An Ask agent giving you general purpose answers to a question or a request based on the files in your context.";
        private static final String MODE_TOOLTIP_LUTZ =
                "<b>Lutz mode:</b> Performs an \"agentic\" search across your entire project to find code relevant to your prompt and will generate a plan for you by creating a list of tasks.";

        public ActionSplitButton(Supplier<Boolean> isActionRunning, String defaultMode) {
            super();
            this.isActionRunning = isActionRunning;
            this.selectedMode = defaultMode;
            this.originalIcon = null;
            this.dropdownIcon = null;

            // Build base tooltip with keybinding info
            KeyStroke submitKs = GlobalUiSettings.getKeybinding(
                    "instructions.submit",
                    KeyStroke.getKeyStroke(
                            KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
            this.baseTooltip = "Run the selected action" + " (" + formatKeyStroke(submitKs) + ")";

            updateButtonText();
            updateTooltip();

            // Override border to eliminate left padding (0px instead of default 8px)
            Color borderColor = UIManager.getColor("Component.borderColor");
            if (borderColor == null) borderColor = Color.GRAY;
            setBorder(BorderFactory.createCompoundBorder(
                    new LineBorder(borderColor, 1, true), BorderFactory.createEmptyBorder(4, 0, 4, 8)));

            // Set initial tooltip based on default mode
            updateTooltip();

            // Defer icon loading until EDT is ready
            SwingUtilities.invokeLater(() -> {
                this.dropdownIcon = Icons.KEYBOARD_DOWN_LIGHT;
            });

            // Change cursor when hovering the dropdown area on the right
            addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    boolean inDropdown = e.getX() >= getWidth() - DROPDOWN_WIDTH;
                    setCursor(inDropdown ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                }
            });
        }

        public void addModeChangeListener(Consumer<String> listener) {
            modeChangeListeners.add(listener);
        }

        public String getSelectedMode() {
            return selectedMode;
        }

        public void setSelectedMode(String mode) {
            if (!this.selectedMode.equals(mode)) {
                this.selectedMode = mode;
                updateButtonText();
                updateTooltip();
                for (var listener : modeChangeListeners) {
                    listener.accept(mode);
                }
            }
        }

        public void updateTooltip() {
            String modeTooltip =
                    switch (selectedMode) {
                        case ACTION_CODE -> MODE_TOOLTIP_CODE;
                        case ACTION_ASK -> MODE_TOOLTIP_ASK;
                        case ACTION_SEARCH -> MODE_TOOLTIP_LUTZ;
                        default -> MODE_TOOLTIP_LUTZ;
                    };
            setToolTipText("<html><body style='width: 350px;'>" + modeTooltip
                    + "<hr style='border:0;border-top:1px solid #ccc;margin:8px 0;'/>" + baseTooltip
                    + "</body></html>");
        }

        public void showStopMode() {
            inStopMode = true;
            setIcon(Icons.STOP);
            setText(null);
            repaint();
        }

        public void showNormalMode() {
            inStopMode = false;
            setIcon(Icons.ARROW_WARM_UP);
            updateButtonText();
            repaint();
        }

        private void updateButtonText() {
            if (!inStopMode) {
                String displayText =
                        switch (selectedMode) {
                            case ACTION_CODE -> "Code";
                            case ACTION_ASK -> "Ask";
                            case ACTION_SEARCH -> "Lutz";
                            default -> "Lutz";
                        };
                setText(displayText);
            }
        }

        @Override
        protected void processMouseEvent(MouseEvent e) {
            int x = e.getX();
            int y = e.getY();
            boolean inDropdown = x >= getWidth() - DROPDOWN_WIDTH && x <= getWidth() && y >= 0 && y <= getHeight();
            if (inDropdown && isEnabled()) {
                // Swallow events in dropdown area and show menu on press
                if (e.getID() == MouseEvent.MOUSE_PRESSED) {
                    showDropdownMenu();
                }
                return; // Do not pass to super; prevents main action from firing
            }
            super.processMouseEvent(e);
        }

        private void showDropdownMenu() {
            JPopupMenu menu = new JPopupMenu();

            JMenuItem codeItem = new JMenuItem("Code");
            codeItem.setToolTipText(
                    "<html><body style='width: 300px;'><b>Code Mode:</b> The Code agent executes your instructions to directly modify the code files currently in the context.</body></html>");
            codeItem.addActionListener(ev -> setSelectedMode(ACTION_CODE));
            menu.add(codeItem);

            JMenuItem askItem = new JMenuItem("Ask");
            askItem.setToolTipText(
                    "<html><body style='width: 300px;'><b>Ask mode:</b> An Ask agent giving you general purpose answers to a question or a request based on the files in your context.</body></html>");
            askItem.addActionListener(ev -> setSelectedMode(ACTION_ASK));
            menu.add(askItem);

            JMenuItem searchItem = new JMenuItem("Lutz");
            searchItem.setToolTipText(
                    "<html><body style='width: 300px;'><b>Lutz mode:</b> Performs an \"agentic\" search across your entire project to find code relevant to your prompt and will generate a plan for you by creating a list of tasks.</body></html>");
            searchItem.addActionListener(ev -> setSelectedMode(ACTION_SEARCH));
            menu.add(searchItem);

            int menuHeight = menu.getPreferredSize().height;
            menu.show(this, getWidth() - DROPDOWN_WIDTH, -menuHeight);
        }

        @Override
        public void setIcon(@Nullable Icon icon) {
            this.originalIcon = icon;
            boolean isHighContrast = GuiTheme.THEME_HIGH_CONTRAST.equalsIgnoreCase(MainProject.getTheme());
            Icon processedIcon = ColorUtil.createHighContrastIcon(icon, getBackground(), isHighContrast);
            super.setIcon(processedIcon);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = 12;
                Color bg = getBackground();
                if (!isEnabled()) {
                    Color disabled = UIManager.getColor("Button.disabledBackground");
                    if (disabled != null) bg = disabled;
                } else if (getModel().isPressed()) {
                    bg = bg.darker();
                } else if (getModel().isRollover()) {
                    bg = bg.brighter();
                }
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);

                // Draw divider line if not in stop mode
                if (!inStopMode) {
                    int dropdownX = getWidth() - DROPDOWN_WIDTH;
                    boolean isHighContrast = GuiTheme.THEME_HIGH_CONTRAST.equalsIgnoreCase(MainProject.getTheme());
                    g2.setColor(isHighContrast ? Color.BLACK : Color.WHITE);
                    g2.drawLine(dropdownX, 6, dropdownX, getHeight() - 6);

                    // Lazy-load and paint dropdown icon centered in the dropdown area
                    if (dropdownIcon == null) {
                        dropdownIcon = Icons.KEYBOARD_DOWN_LIGHT;
                    }
                    if (dropdownIcon instanceof SwingUtil.ThemedIcon themedIcon) {
                        themedIcon.ensureResolved();
                    }
                    Icon iconToPaint = (dropdownIcon instanceof SwingUtil.ThemedIcon themedIcon)
                            ? themedIcon.delegate()
                            : dropdownIcon;
                    // Apply high-contrast processing to dropdown icon
                    iconToPaint = ColorUtil.createHighContrastIcon(iconToPaint, getBackground(), isHighContrast);
                    if (iconToPaint != null) {
                        int iw = iconToPaint.getIconWidth();
                        int ih = iconToPaint.getIconHeight();
                        int ix = dropdownX + Math.max(0, (DROPDOWN_WIDTH - iw) / 2);
                        int iy = Math.max(0, (getHeight() - ih) / 2);
                        iconToPaint.paintIcon(this, g2, ix, iy);
                    }
                }
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }

        @Override
        protected void paintBorder(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = 12;
                Color borderColor;
                if (isFocusOwner()) {
                    borderColor = new Color(0x1F6FEB);
                } else {
                    borderColor = UIManager.getColor("Component.borderColor");
                    if (borderColor == null) borderColor = Color.GRAY;
                }
                g2.setColor(borderColor);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public void applyTheme(GuiTheme guiTheme, boolean wordWrap) {
            // Re-read colors from UIManager instead of using cached values
            Color currentDefaultBg = UIManager.getColor("Button.default.background");
            Color currentSecondaryBg = UIManager.getColor("Button.background");

            // Set background FIRST so icon processing can read the correct background
            if (this.isActionRunning.get()) {
                setBackground(currentSecondaryBg);
            } else {
                setBackground(currentDefaultBg);
            }

            // Now update icon - this will trigger high-contrast processing with the new background
            if (this.originalIcon != null) {
                setIcon(this.originalIcon);
            }

            revalidate();
            repaint();
        }

        @Override
        public void applyTheme(GuiTheme guiTheme) {
            applyTheme(guiTheme, false);
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

    /**
     * Custom focus traversal policy for InstructionsPanel that defines the tab order: instructionsArea → actionButton →
     * modeSwitch → codeCheckBox/searchProjectCheckBox → micButton → modelSelector → historyDropdown → branchSplitButton
     */
    private class InstructionsPanelFocusTraversalPolicy extends FocusTraversalPolicy {
        @Override
        public Component getComponentAfter(Container aContainer, Component aComponent) {
            if (aComponent == instructionsArea) {
                return micButton;
            } else if (aComponent == micButton) {
                return modelSelector.getComponent();
            } else if (aComponent == modelSelector.getComponent()) {
                return actionButton;
            } else if (aComponent == actionButton) {
                return findHistoryDropdown();
            } else if (aComponent == findHistoryDropdown()) {
                return findBranchSplitButton();
            } else if (aComponent == findBranchSplitButton()) {
                return getNextFocusableComponent();
            }
            return instructionsArea;
        }

        @Override
        public Component getComponentBefore(Container aContainer, Component aComponent) {
            if (aComponent == micButton) {
                return instructionsArea;
            } else if (aComponent == modelSelector.getComponent()) {
                return micButton;
            } else if (aComponent == actionButton) {
                return modelSelector.getComponent();
            } else if (aComponent == findHistoryDropdown()) {
                return actionButton;
            } else if (aComponent == findBranchSplitButton()) {
                return findHistoryDropdown();
            } else if (aComponent == getNextFocusableComponent()) {
                return findBranchSplitButton();
            }
            return instructionsArea;
        }

        @Override
        public Component getFirstComponent(Container aContainer) {
            return instructionsArea;
        }

        @Override
        public Component getLastComponent(Container aContainer) {
            return findBranchSplitButton();
        }

        @Override
        public Component getDefaultComponent(Container aContainer) {
            return instructionsArea;
        }

        private Component findHistoryDropdown() {
            if (historyDropdown != null) {
                return historyDropdown;
            }
            return findComponentInHierarchy(
                    InstructionsPanel.this, comp -> comp instanceof SplitButton, instructionsArea);
        }

        private Component findBranchSplitButton() {
            return findComponentInHierarchy(
                    InstructionsPanel.this,
                    comp -> comp instanceof SplitButton && comp != historyDropdown,
                    instructionsArea);
        }

        private Component getNextFocusableComponent() {
            Container parent = InstructionsPanel.this.getParent();
            while (parent != null && !(parent instanceof Window)) {
                parent = parent.getParent();
            }
            if (parent instanceof Window) {
                return parent.getFocusTraversalPolicy().getComponentAfter(parent, InstructionsPanel.this);
            }
            return instructionsArea;
        }

        private Component findComponentInHierarchy(
                Container container, Predicate<Component> predicate, Component fallback) {
            for (Component comp : container.getComponents()) {
                if (predicate.test(comp)) {
                    return comp;
                }
                if (comp instanceof Container containerComp) {
                    Component found = findComponentInHierarchy(containerComp, predicate, fallback);
                    if (found != fallback) {
                        return found;
                    }
                }
            }
            return fallback;
        }
    }

    private static class ModelUnavailableException extends RuntimeException {
        public ModelUnavailableException() {
            super("Model is unavailable. Usually this indicates a networking problem.");
        }
    }

    public static class WandButton extends MaterialButton {
        private static final String WAND_TOOLTIP = "Refine Prompt: rewrites your prompt for clarity and specificity.";

        public WandButton(
                ContextManager contextManager,
                IConsoleIO consoleIO,
                JTextArea instructionsArea,
                Supplier<String> promptSupplier,
                Consumer<String> promptConsumer) {
            super();
            SwingUtilities.invokeLater(() -> setIcon(Icons.WAND));
            setToolTipText(WAND_TOOLTIP);
            addActionListener(e -> {
                var wandAction = new WandAction(contextManager);
                wandAction.execute(promptSupplier, promptConsumer, consoleIO, instructionsArea);
            });
        }

        @Override
        public void setIcon(@Nullable Icon icon) {
            // Apply high-contrast processing if needed
            boolean isHighContrast = GuiTheme.THEME_HIGH_CONTRAST.equalsIgnoreCase(MainProject.getTheme());
            Icon processedIcon = ColorUtil.createHighContrastIcon(icon, getBackground(), isHighContrast);
            super.setIcon(processedIcon);
        }
    }

    /**
     * A MaterialButton that automatically applies high-contrast icon processing.
     * Used for simple icon buttons that don't need custom behavior.
     */
    private static class HighContrastAwareButton extends MaterialButton {
        @Override
        public void setIcon(@Nullable Icon icon) {
            // Apply high-contrast processing if needed
            boolean isHighContrast = GuiTheme.THEME_HIGH_CONTRAST.equalsIgnoreCase(MainProject.getTheme());
            Icon processedIcon = ColorUtil.createHighContrastIcon(icon, getBackground(), isHighContrast);
            super.setIcon(processedIcon);
        }
    }
}
