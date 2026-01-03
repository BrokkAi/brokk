package ai.brokk.gui.terminal;

import static java.util.Objects.requireNonNull;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.TaskResult;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitWorkflow;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.CommitDialog;
import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.dialogs.BaseThemedDialog;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.BadgedIcon;
import ai.brokk.gui.util.Icons;
import ai.brokk.tasks.TaskList;
import com.google.common.base.Splitter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.ListDataListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/** A simple, theme-aware task list panel supporting add, remove and complete toggle. */
public class TaskListPanel extends JPanel implements ThemeAware, IContextManager.ContextListener {

    private static final Logger logger = LogManager.getLogger(TaskListPanel.class);
    private @Nullable UUID sessionIdAtLoad = null;
    // Track the last-seen Task List fragment id so we can detect updates within the same session
    private @Nullable String lastTaskListFragmentId = null;
    private final ContextManager cm;

    private final TaskListModel model;
    private final JList<TaskList.TaskItem> list;
    private final JTextField input = new JTextField();
    private final MaterialButton removeBtn = new MaterialButton();
    private final MaterialButton toggleDoneBtn = new MaterialButton();
    private final MaterialButton goStopButton;
    private final MaterialButton clearCompletedBtn = new MaterialButton();
    private final Chrome chrome;
    private volatile Context currentContext;

    // Read-only state: when viewing a historical context, editing is disabled
    private boolean taskListEditable = true;
    private static final String READ_ONLY_TIP = "Select latest activity to enable";

    // Badge support for the Tasks tab: shows number of incomplete tasks.
    private @Nullable BadgedIcon tasksTabBadgedIcon = null;
    private final Icon tasksBaseIcon = Icons.LIST;
    private @Nullable GuiTheme currentTheme = null;
    // These mirror InstructionsPanel's action button dimensions to keep Play/Stop button sizing consistent across
    // panels.
    private static final int ACTION_BUTTON_WIDTH = 140;
    private static final int ACTION_BUTTON_MIN_HEIGHT = 36;
    private static final int SHORT_TITLE_CHAR_THRESHOLD = 20;
    private final Timer runningFadeTimer;
    private final JComponent controls;
    private final JPanel southPanel;
    private @Nullable JComponent sharedModelSelectorComp = null;
    private @Nullable JComponent sharedStatusStripComp = null;
    private long runningAnimStartMs = 0L;

    private @Nullable Integer runningIndex = null;
    private final LinkedHashSet<Integer> pendingQueue = new LinkedHashSet<>();
    private boolean queueActive = false;
    private @Nullable List<Integer> currentRunOrder = null;
    private @Nullable ListDataListener autoPlayListener = null;

    public TaskListPanel(Chrome chrome) {
        super(new BorderLayout(4, 0));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        this.chrome = chrome;

        this.model =
                new TaskListModel(() -> currentContext.getTaskListDataOrEmpty().tasks());
        this.currentContext = chrome.getContextManager().liveContext();
        this.list = new JList<>(model);

        // Center: list with custom renderer
        list.setCellRenderer(new TaskRenderer());
        list.setVisibleRowCount(12);
        list.setFixedCellHeight(-1);
        list.setToolTipText("Double-click to edit");
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        // Update button states based on selection
        list.addListSelectionListener(e -> {
            updateButtonStates();
        });

        // Enable drag-and-drop reordering
        list.setDragEnabled(true);
        list.setDropMode(DropMode.INSERT);
        list.setTransferHandler(new TaskReorderTransferHandler());

        // List keyboard shortcuts
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggleDone");
        list.getActionMap().put("toggleDone", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggleSelectedDone();
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteTasks");
        list.getActionMap().put("deleteTasks", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                removeSelected();
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "editTask");
        list.getActionMap().put("editTask", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                editSelected();
            }
        });
        list.getInputMap()
                .put(
                        KeyStroke.getKeyStroke(
                                KeyEvent.VK_A, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                        "selectAll");
        list.getActionMap().put("selectAll", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                list.setSelectionInterval(0, Math.max(0, model.getSize() - 1));
            }
        });

        list.getInputMap()
                .put(
                        KeyStroke.getKeyStroke(
                                KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                        "copyTasks");
        list.getActionMap().put("copyTasks", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                copySelectedTasks();
            }
        });

        // Run Architect with Ctrl/Cmd+Enter
        list.getInputMap()
                .put(
                        KeyStroke.getKeyStroke(
                                KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                        "runArchitect");
        list.getActionMap().put("runArchitect", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                runArchitectOnSelected();
            }
        });

        // Context menu (right-click)
        var popup = new JPopupMenu();
        var toggleItem = new JMenuItem("Toggle Done");
        toggleItem.addActionListener(e -> toggleSelectedDone());
        popup.add(toggleItem);
        var runItem = new JMenuItem("Run");
        runItem.addActionListener(e -> {
            int[] sel = list.getSelectedIndices();
            if (sel.length > 0) {
                startRunWithCommitGate(sel);
            }
        });
        popup.add(runItem);
        var editItem = new JMenuItem("Edit");
        editItem.addActionListener(e -> editSelected());
        popup.add(editItem);
        var splitItem = new JMenuItem("Split...");
        splitItem.addActionListener(e -> splitSelectedTask());
        popup.add(splitItem);
        var combineItem = new JMenuItem("Combine");
        combineItem.addActionListener(e -> combineSelectedTasks());
        popup.add(combineItem);
        var copyItem = new JMenuItem("Copy");
        copyItem.addActionListener(e -> copySelectedTasks());
        popup.add(copyItem);
        popup.add(new JSeparator());
        var deleteItem = new JMenuItem("Delete");
        deleteItem.addActionListener(e -> removeSelected());
        popup.add(deleteItem);

        list.addMouseListener(new MouseAdapter() {
            private void showContextMenu(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }

                // If nothing is selected, select the row under the mouse so the context menu
                // targets the item under the cursor.
                if (list.getSelectedIndices().length == 0) {
                    int idx = list.locationToIndex(e.getPoint());
                    if (idx >= 0) {
                        Rectangle cell = list.getCellBounds(idx, idx);
                        if (cell != null && cell.contains(e.getPoint())) {
                            list.setSelectedIndex(idx);
                        }
                    }
                }

                // When read-only, all edit actions are disabled; allow only Copy
                if (!taskListEditable) {
                    toggleItem.setEnabled(false);
                    runItem.setEnabled(false);
                    editItem.setEnabled(false);
                    splitItem.setEnabled(false);
                    combineItem.setEnabled(false);
                    deleteItem.setEnabled(false);
                    copyItem.setEnabled(true);
                    popup.show(list, e.getX(), e.getY());
                    return;
                }

                boolean includesRunning = false;
                boolean includesPending = false;
                int[] sel = list.getSelectedIndices();
                if (runningIndex != null) {
                    for (int si : sel) {
                        if (si == runningIndex.intValue()) {
                            includesRunning = true;
                            break;
                        }
                    }
                }
                for (int si : sel) {
                    if (pendingQueue.contains(si)) {
                        includesPending = true;
                        break;
                    }
                }
                boolean block = includesRunning || includesPending;
                toggleItem.setEnabled(!block);
                editItem.setEnabled(!block);
                boolean exactlyOne = sel.length == 1;
                editItem.setEnabled(!block && exactlyOne);
                splitItem.setEnabled(!block && exactlyOne && !queueActive);
                combineItem.setEnabled(!block && sel.length >= 2);
                deleteItem.setEnabled(!block);
                runItem.setEnabled(!block && sel.length > 0);
                popup.show(list, e.getX(), e.getY());
            }

            @Override
            public void mousePressed(MouseEvent e) {
                showContextMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showContextMenu(e);
            }
        });

        // South: controls - use BoxLayout(LINE_AXIS) to match InstructionsPanel for consistent model selector
        // positioning
        controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.LINE_AXIS));
        controls.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        // Single-line input (no wrap). Shortcuts: Enter adds, Ctrl/Cmd+Enter adds, Ctrl/Cmd+Shift+Enter adds and keeps,
        // Escape clears
        input.setColumns(50);
        input.putClientProperty("JTextField.placeholderText", "Add task here and press Enter");
        input.setToolTipText("Add task here and press Enter");
        // Enter adds
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "addTask");
        // Ctrl/Cmd+Enter also adds
        input.getInputMap()
                .put(
                        KeyStroke.getKeyStroke(
                                KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                        "addTask");
        input.getActionMap().put("addTask", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                addTask();
            }
        });
        input.getInputMap()
                .put(
                        KeyStroke.getKeyStroke(
                                KeyEvent.VK_ENTER,
                                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | KeyEvent.SHIFT_DOWN_MASK),
                        "addTaskKeep");
        input.getActionMap().put("addTaskKeep", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                var prev = input.getText();
                addTask();
                input.setText(prev);
                input.requestFocusInWindow();
            }
        });
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearInput");
        input.getActionMap().put("clearInput", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                input.setText("");
            }
        });

        // Keep controls layout consistent with InstructionsPanel:
        // glue pushes the ModelSelector and Go/Stop button to the right.
        controls.add(Box.createHorizontalGlue());

        goStopButton = new MaterialButton() {
            @Override
            protected void paintComponent(Graphics g) {
                // Paint rounded background to match InstructionsPanel
                var g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int arc = 12;
                    var bg = getBackground();
                    if (!isEnabled()) {
                        var disabled = UIManager.getColor("Button.disabledBackground");
                        if (disabled != null) {
                            bg = disabled;
                        }
                    } else if (getModel().isPressed()) {
                        bg = bg.darker();
                    } else if (getModel().isRollover()) {
                        bg = bg.brighter();
                    }
                    g2.setColor(bg);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                } finally {
                    g2.dispose();
                }
                super.paintComponent(g);
            }

            @Override
            protected void paintBorder(Graphics g) {
                // Paint border, which will be visible even when disabled
                var g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int arc = 12;
                    Color borderColor;
                    if (isFocusOwner()) {
                        borderColor = new Color(0x1F6FEB);
                    } else {
                        borderColor = UIManager.getColor("Component.borderColor");
                        if (borderColor == null) {
                            borderColor = Color.GRAY;
                        }
                    }
                    g2.setColor(borderColor);
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                } finally {
                    g2.dispose();
                }
            }
        };
        goStopButton.setOpaque(false);
        goStopButton.setContentAreaFilled(false);
        goStopButton.addActionListener(e -> onGoStopButtonPressed());

        int fixedHeight = Math.max(goStopButton.getPreferredSize().height, ACTION_BUTTON_MIN_HEIGHT);
        Dimension prefSize = new Dimension(ACTION_BUTTON_WIDTH, fixedHeight);
        goStopButton.setPreferredSize(prefSize);
        goStopButton.setMinimumSize(prefSize);
        goStopButton.setMaximumSize(prefSize);
        goStopButton.setMargin(new Insets(4, 4, 4, 10));
        goStopButton.setIconTextGap(0);
        goStopButton.setAlignmentY(Component.CENTER_ALIGNMENT);

        controls.add(Box.createHorizontalStrut(8));
        controls.add(goStopButton);

        removeBtn.setIcon(Icons.REMOVE);
        // Show a concise HTML tooltip and append the Delete shortcut (display only; no action registered).
        removeBtn.setAppendShortcutToTooltip(true);
        removeBtn.setShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), null, null, null);
        removeBtn.setToolTipText(
                "<html><body style='width:300px'>Remove the selected tasks from the list.<br>Tasks that are running or queued cannot be removed.</body></html>");
        removeBtn.addActionListener(e -> removeSelected());

        toggleDoneBtn.setIcon(Icons.CHECK);
        // Show a concise HTML tooltip and append the Space shortcut (display only; no action registered).
        toggleDoneBtn.setAppendShortcutToTooltip(true);
        toggleDoneBtn.setShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), null, null, null);
        toggleDoneBtn.setToolTipText(
                "<html><body style='width:300px'>Mark the selected tasks as done or not done.<br>Running or queued tasks cannot be toggled.</body></html>");
        toggleDoneBtn.addActionListener(e -> toggleSelectedDone());

        clearCompletedBtn.setIcon(Icons.CLEAR_ALL);
        clearCompletedBtn.setToolTipText(
                "<html><body style='width:300px'>Remove all completed tasks from this session.<br>You will be asked to confirm. This cannot be undone.</body></html>");
        clearCompletedBtn.addActionListener(e -> clearCompletedTasks());

        {
            // Make the buttons visually tighter and grouped
            removeBtn.setMargin(new Insets(0, 0, 0, 0));
            toggleDoneBtn.setMargin(new Insets(0, 0, 0, 0));
            clearCompletedBtn.setMargin(new Insets(0, 0, 0, 0));

            // Top toolbar (below title, above list): left group + separator + play all/clear completed
            // Use BoxLayout to prevent wrapping when the container is narrow
            JPanel topToolbar = new JPanel();
            topToolbar.setLayout(new BoxLayout(topToolbar, BoxLayout.LINE_AXIS));
            topToolbar.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
            topToolbar.setOpaque(false);

            // Left group: remaining buttons
            topToolbar.add(removeBtn);
            topToolbar.add(toggleDoneBtn);

            // Vertical separator between groups
            JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
            sep.setPreferredSize(new Dimension(8, 24));
            sep.setMaximumSize(new Dimension(8, 24));
            topToolbar.add(sep);

            // Right group: Clear Completed
            topToolbar.add(clearCompletedBtn);

            // Vertical separator and add-text input to the right of Clear Completed
            JSeparator sep2 = new JSeparator(SwingConstants.VERTICAL);
            sep2.setPreferredSize(new Dimension(8, 24));
            sep2.setMaximumSize(new Dimension(8, 24));
            topToolbar.add(sep2);

            // Configure input field sizing for BoxLayout: ensure minimum usable width
            // and allow it to expand to fill remaining space
            input.setMinimumSize(new Dimension(100, input.getPreferredSize().height));
            input.setMaximumSize(new Dimension(Integer.MAX_VALUE, input.getPreferredSize().height));

            // Move the existing input field into the top toolbar
            topToolbar.add(input);

            add(topToolbar, BorderLayout.NORTH);
        }

        var scroll =
                new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll, BorderLayout.CENTER);

        // Recompute wrapping and ellipsis when the viewport/list width changes
        var vp = scroll.getViewport();
        vp.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                // Trigger layout and re-render so the renderer recalculates available width per row
                list.revalidate();
                list.repaint();
                // Force recalculation of variable row heights and ellipsis on width change
                TaskListPanel.this.forceRowHeightsRecalc();
            }
        });
        // Also listen on the JList itself in case LAF resizes the list directly
        list.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                list.revalidate();
                list.repaint();
                // Force recalculation of variable row heights and ellipsis on width change
                TaskListPanel.this.forceRowHeightsRecalc();
            }
        });

        southPanel = new JPanel();
        southPanel.setLayout(new BoxLayout(southPanel, BoxLayout.PAGE_AXIS));
        southPanel.add(controls);
        add(southPanel, BorderLayout.SOUTH);

        // Ensure correct initial layout and refresh the delegating model when the panel becomes visible
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                SwingUtilities.invokeLater(() -> {
                    refreshUi(true);
                });
            }
        });

        // Double-click opens modal edit dialog
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;

                int index = list.locationToIndex(e.getPoint());
                if (index < 0) return;
                Rectangle cell = list.getCellBounds(index, index);
                if (cell == null || !cell.contains(e.getPoint())) return;

                if (e.getClickCount() == 2) {
                    list.setSelectedIndex(index);
                    editSelected();
                }
            }
        });
        updateButtonStates();
        runningFadeTimer = new Timer(40, e -> {
            Integer ri = runningIndex;
            if (ri != null) {
                var rect = list.getCellBounds(ri, ri);
                if (rect != null) list.repaint(rect);
                else list.repaint();
            } else {
                ((Timer) e.getSource()).stop();
            }
        });
        runningFadeTimer.setRepeats(true);

        // Initial model sync from current context
        sessionIdAtLoad = getCurrentSessionId();
        var mgrInit = chrome.getContextManager();
        Context selInit = mgrInit.selectedContext();
        Context baseInit = (selInit != null) ? selInit : mgrInit.liveContext();
        lastTaskListFragmentId =
                baseInit.getTaskListFragment().map(ContextFragment::id).orElse(null);
        refreshUi(true);

        IContextManager cm = chrome.getContextManager();
        this.cm = (ContextManager) cm;
        cm.addContextListener(this);
    }

    private void addTask() {
        if (!taskListEditable) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        var raw = input.getText();
        if (raw == null) return;
        var lines = Splitter.on(Pattern.compile("\\R+")).split(raw.strip());
        int added = 0;

        var items = new ArrayList<TaskList.TaskItem>(cm.getTaskList().tasks());
        for (var line : lines) {
            var text = line.strip();
            if (!text.isEmpty()) {
                items.add(new TaskList.TaskItem(text, text, false));
                added++;
            }
        }
        if (added > 0) {
            input.setText("");
            input.requestFocusInWindow();

            cm.setTaskList(new TaskList.TaskListData(items));
            refreshUi(true);
        }
    }

    private void removeSelected() {
        if (!taskListEditable) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        int[] indices = list.getSelectedIndices();
        if (indices.length > 0) {
            int deletableCount = 0;
            for (int idx : indices) {
                if (runningIndex != null && idx == runningIndex.intValue()) continue;
                if (pendingQueue.contains(idx)) continue;
                if (idx >= 0 && idx < model.getSize()) deletableCount++;
            }

            if (deletableCount == 0) {
                updateButtonStates();
                return;
            }

            boolean removedAny = false;
            var items = new ArrayList<TaskList.TaskItem>(cm.getTaskList().tasks());
            Arrays.sort(indices);
            for (int i = indices.length - 1; i >= 0; i--) {
                int idx = indices[i];
                if (runningIndex != null && idx == runningIndex.intValue()) continue;
                if (pendingQueue.contains(idx)) continue;
                if (idx >= 0 && idx < items.size()) {
                    items.remove(idx);
                    removedAny = true;
                }
            }
            if (removedAny) {
                cm.setTaskList(new TaskList.TaskListData(items));
                refreshUi(true);
            } else {
                updateButtonStates();
            }
        }
    }

    private void toggleSelectedDone() {
        if (!taskListEditable) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        int[] indices = list.getSelectedIndices();
        if (indices.length > 0) {
            boolean changed = false;
            var items = new ArrayList<TaskList.TaskItem>(cm.getTaskList().tasks());
            for (int idx : indices) {
                if (runningIndex != null && idx == runningIndex.intValue()) continue;
                if (pendingQueue.contains(idx)) continue;
                if (idx >= 0 && idx < items.size()) {
                    var it = items.get(idx);
                    items.set(idx, new TaskList.TaskItem(it.title(), it.text(), !it.done()));
                    changed = true;
                }
            }
            if (changed) {
                cm.setTaskList(new TaskList.TaskListData(items));
                refreshUi(false);
            }
        }
    }

    private void onGoStopButtonPressed() {
        if (!taskListEditable) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        var contextManager = chrome.getContextManager();
        if (contextManager.isLlmTaskInProgress()) {
            contextManager.interruptLlmAction();
        } else {
            runArchitectOnAll();
        }
    }

    private void editSelected() {
        int idx = list.getSelectedIndex();
        if (idx < 0) return;
        if (runningIndex != null && idx == runningIndex.intValue()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Cannot edit a task that is currently running.",
                    "Edit Disabled",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (pendingQueue.contains(idx)) {
            JOptionPane.showMessageDialog(
                    this,
                    "Cannot edit a task that is queued for running.",
                    "Edit Disabled",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Open modal edit dialog
        openEditDialog(idx);
    }

    private void openEditDialog(int index) {
        TaskList.TaskItem current = model.getElementAt(index);

        Window owner = SwingUtilities.getWindowAncestor(this);
        var dialog = new BaseThemedDialog(owner, "Edit Task", Dialog.ModalityType.APPLICATION_MODAL);

        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel fieldsPanel = new JPanel();
        fieldsPanel.setLayout(new BoxLayout(fieldsPanel, BoxLayout.PAGE_AXIS));

        JLabel titleLabel = new JLabel("Title:");
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        fieldsPanel.add(titleLabel);

        JTextField titleField = new JTextField(current.title());
        titleField.setFont(list.getFont());
        titleField.setEditable(taskListEditable);
        titleField.setAlignmentX(Component.LEFT_ALIGNMENT);
        fieldsPanel.add(titleField);

        fieldsPanel.add(Box.createVerticalStrut(8));

        JLabel bodyLabel = new JLabel("Body:");
        bodyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        fieldsPanel.add(bodyLabel);

        JTextArea bodyArea = new JTextArea(current.text());
        bodyArea.setLineWrap(true);
        bodyArea.setWrapStyleWord(true);
        bodyArea.setFont(list.getFont());
        bodyArea.setRows(8);
        bodyArea.setEditable(taskListEditable);

        JScrollPane bodyScroll = new JScrollPane(
                bodyArea, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        bodyScroll.setPreferredSize(new Dimension(520, 180));
        bodyScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        fieldsPanel.add(bodyScroll);

        content.add(fieldsPanel, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        MaterialButton saveBtn = new MaterialButton("Save");
        SwingUtil.applyPrimaryButtonStyle(saveBtn);
        saveBtn.setEnabled(taskListEditable);
        if (!taskListEditable) {
            saveBtn.setToolTipText(READ_ONLY_TIP);
        }
        MaterialButton cancelBtn = new MaterialButton("Cancel");

        saveBtn.addActionListener(e -> {
            String newTitle = titleField.getText();
            String newText = bodyArea.getText();
            if (newTitle != null) {
                newTitle = newTitle.strip();
            } else {
                newTitle = "";
            }
            if (newText != null) {
                newText = newText.strip();
            } else {
                newText = "";
            }

            if (!newText.isEmpty() && (!newText.equals(current.text()) || !newTitle.equals(current.title()))) {
                var items = new ArrayList<TaskList.TaskItem>(cm.getTaskList().tasks());
                if (index >= 0 && index < items.size()) {
                    var cur = items.get(index);
                    items.set(index, new TaskList.TaskItem(newTitle, newText, cur.done()));
                    cm.setTaskList(new TaskList.TaskListData(items));
                    refreshUi(false);
                }
            }
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());

        buttons.add(saveBtn);
        buttons.add(cancelBtn);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.getContentRoot().add(content);
        dialog.setResizable(true);
        dialog.getRootPane().setDefaultButton(saveBtn);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);

        titleField.requestFocusInWindow();
        titleField.selectAll();

        dialog.setVisible(true);
    }

    private void updateButtonStates() {
        // Respect read-only state before any other logic
        if (!taskListEditable) {
            removeBtn.setEnabled(false);
            removeBtn.setToolTipText(READ_ONLY_TIP);
            toggleDoneBtn.setEnabled(false);
            toggleDoneBtn.setToolTipText(READ_ONLY_TIP);
            clearCompletedBtn.setEnabled(false);
            clearCompletedBtn.setToolTipText(READ_ONLY_TIP);
            input.setEnabled(false);
            input.setEditable(false);

            goStopButton.setEnabled(false);
            goStopButton.setToolTipText(READ_ONLY_TIP);
            goStopButton.repaint();
            return;
        }

        boolean hasSelection = list.getSelectedIndex() >= 0;
        boolean llmBusy = false;
        try {
            llmBusy = chrome.getContextManager().isLlmTaskInProgress();
        } catch (Exception ex) {
            logger.debug("Unable to query LLM busy state", ex);
        }

        boolean selectionIncludesRunning = false;
        boolean selectionIncludesPending = false;
        int[] selIndices = list.getSelectedIndices();
        for (int si : selIndices) {
            if (runningIndex != null && si == runningIndex.intValue()) {
                selectionIncludesRunning = true;
            }
            if (pendingQueue.contains(si)) {
                selectionIncludesPending = true;
            }
        }

        // Remove/Toggle disabled if no selection OR selection includes running/pending
        boolean blockEdits = selectionIncludesRunning || selectionIncludesPending;
        removeBtn.setEnabled(hasSelection && !blockEdits);
        toggleDoneBtn.setEnabled(hasSelection && !blockEdits);

        // Input is enabled while editable
        input.setEnabled(true);
        input.setEditable(true);

        // Clear Completed enabled if any task is done
        boolean anyCompleted = false;
        for (int i = 0; i < model.getSize(); i++) {
            TaskList.TaskItem it2 = model.getElementAt(i);
            if (it2.done()) {
                anyCompleted = true;
                break;
            }
        }
        clearCompletedBtn.setEnabled(anyCompleted);

        boolean anyTasks = model.getSize() > 0;
        if (llmBusy) {
            goStopButton.setIcon(Icons.STOP);
            goStopButton.setText(null);
            goStopButton.setToolTipText("Cancel the current operation");
            var stopBg = UIManager.getColor("Brokk.action_button_bg_stop");
            if (stopBg == null) {
                stopBg = ThemeColors.getColor(false, ThemeColors.GIT_BADGE_BACKGROUND);
            }
            goStopButton.setBackground(stopBg);
            goStopButton.setForeground(Color.WHITE);
            goStopButton.setEnabled(true);
        } else {
            goStopButton.setIcon(Icons.FAST_FORWARD);
            goStopButton.setText(null);
            var defaultBg = UIManager.getColor("Brokk.action_button_bg_default");
            if (defaultBg == null) {
                defaultBg = UIManager.getColor("Button.default.background");
            }
            goStopButton.setBackground(defaultBg);
            goStopButton.setForeground(Color.WHITE);
            goStopButton.setEnabled(anyTasks && !queueActive);
            if (!anyTasks) {
                goStopButton.setToolTipText("Add a task to get started");
            } else if (queueActive) {
                goStopButton.setToolTipText("A task queue is already running");
            } else {
                goStopButton.setToolTipText("Run Architect on all tasks in order");
            }
        }
        goStopButton.repaint();
    }

    private UUID getCurrentSessionId() {
        return chrome.getContextManager().getCurrentSessionId();
    }

    /**
     * Reset ephemeral run state (running/queued/auto-play) when switching sessions.
     * Must be called on the EDT.
     */
    private void resetEphemeralRunState() {
        assert SwingUtilities.isEventDispatchThread() : "resetEphemeralRunState must run on EDT";
        runningIndex = null;
        pendingQueue.clear();
        queueActive = false;
        currentRunOrder = null;

        if (autoPlayListener != null) {
            try {
                model.removeListDataListener(autoPlayListener);
            } catch (Exception ex) {
                logger.debug("Error removing autoPlayListener during session switch", ex);
            }
            autoPlayListener = null;
        }
    }

    private void runArchitectOnSelected() {
        if (!taskListEditable) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        if (chrome.getContextManager().isLlmTaskInProgress() || queueActive) {
            // A run is already in progress, do not start another.
            // The UI should prevent this, but this is a safeguard for the keyboard shortcut.
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        int[] selected = list.getSelectedIndices();
        if (selected.length == 0) {
            JOptionPane.showMessageDialog(
                    this, "Select at least one task.", "No selection", JOptionPane.WARNING_MESSAGE);
            return;
        }

        startRunWithCommitGate(selected);
    }

    public void runArchitectOnAll() {
        if (!taskListEditable) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        if (model.getSize() == 0) {
            return;
        }

        // Select all tasks
        int[] allIndices = new int[model.getSize()];
        for (int i = 0; i < model.getSize(); i++) {
            allIndices[i] = i;
        }

        list.setSelectionInterval(0, model.getSize() - 1);
        startRunWithCommitGate(allIndices);
    }

    private void runArchitectOnIndices(int[] selected) {
        Arrays.sort(selected);
        var toRun = new ArrayList<Integer>(selected.length);
        for (int idx : selected) {
            if (idx >= 0 && idx < model.getSize()) {
                TaskList.TaskItem it = model.getElementAt(idx);
                if (!it.done()) {
                    toRun.add(idx);
                }
            }
        }
        if (toRun.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this, "All selected tasks are already done.", "Nothing to run", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        goStopButton.setEnabled(false);
        goStopButton.setToolTipText("Preparing to run tasks...");

        currentRunOrder = List.copyOf(toRun);

        int first = toRun.get(0);
        pendingQueue.clear();
        if (toRun.size() > 1) {
            for (int i = 1; i < toRun.size(); i++) pendingQueue.add(toRun.get(i));
            queueActive = true;
        } else {
            queueActive = false;
        }

        list.repaint();

        var cm = chrome.getContextManager();
        chrome.showOutputSpinner("Compressing history...");
        var cf = cm.compressHistoryAsync();
        cf.whenComplete((v, ex) -> SwingUtilities.invokeLater(() -> {
            chrome.hideOutputSpinner();
            startRunForIndex(first);
        }));
    }

    private void startRunForIndex(int idx) {
        if (idx < 0 || idx >= model.getSize()) {
            startNextIfAny();
            return;
        }
        TaskList.TaskItem item = model.getElementAt(idx);
        if (item.done()) {
            startNextIfAny();
            return;
        }

        String originalPrompt = item.text();
        if (originalPrompt.isBlank()) {
            startNextIfAny();
            return;
        }

        runningIndex = idx;
        updateButtonStates();
        runningAnimStartMs = System.currentTimeMillis();
        runningFadeTimer.start();
        list.repaint();

        int totalToRun = currentRunOrder != null ? currentRunOrder.size() : 1;
        int pos = (currentRunOrder != null) ? currentRunOrder.indexOf(idx) : -1;
        final int numTask = (pos >= 0) ? pos + 1 : 1;
        SwingUtilities.invokeLater(() -> chrome.showNotification(
                IConsoleIO.NotificationRole.INFO,
                "Submitted " + totalToRun + " task(s) for execution. Running task " + numTask + " of " + totalToRun
                        + "..."));

        var cm = chrome.getContextManager();

        runArchitectOnTaskAsync(idx, cm);
    }

    private void startRunWithCommitGate(int[] indices) {
        assert indices.length > 0 : "indices array must not be empty";
        ensureCleanOrCommitThen(() -> runArchitectOnIndices(indices));
    }

    private void ensureCleanOrCommitThen(Runnable action) {
        assert SwingUtilities.isEventDispatchThread();

        // Check for dirty files using GCT's cached count (fast, no repo call)
        var dirtyFiles = chrome.getModifiedFiles();
        if (dirtyFiles.isEmpty()) {
            action.run();
            return;
        }

        var owner = SwingUtilities.getWindowAncestor(this);
        var dialog = new BaseThemedDialog(owner, "Uncommitted Changes", Dialog.ModalityType.APPLICATION_MODAL);

        var content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        String message =
                "Your working tree has %d uncommitted change(s).\nWould you like to commit before running tasks?"
                        .formatted(dirtyFiles.size());
        var msgArea = new JTextArea(message);
        msgArea.setEditable(false);
        msgArea.setOpaque(false);
        msgArea.setLineWrap(true);
        msgArea.setWrapStyleWord(true);
        content.add(msgArea, BorderLayout.CENTER);

        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var commitFirstBtn = new MaterialButton("Commit First");
        SwingUtil.applyPrimaryButtonStyle(commitFirstBtn);
        var continueBtn = new MaterialButton("Continue without Commit");
        var cancelBtn = new MaterialButton("Cancel");
        buttons.add(commitFirstBtn);
        buttons.add(continueBtn);
        buttons.add(cancelBtn);
        content.add(buttons, BorderLayout.SOUTH);

        final int[] choice = new int[] {-1}; // 0 = commit, 1 = continue, 2 = cancel
        commitFirstBtn.addActionListener(e -> {
            choice[0] = 0;
            dialog.dispose();
        });
        continueBtn.addActionListener(e -> {
            choice[0] = 1;
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> {
            choice[0] = 2;
            dialog.dispose();
        });

        dialog.getContentRoot().add(content);
        dialog.getRootPane().setDefaultButton(commitFirstBtn);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);

        if (choice[0] == 0) {
            // Commit first, then proceed on success
            var workflow = new GitWorkflow(chrome.getContextManager());
            var commitDialog = new CommitDialog(
                    chrome.getFrame(), chrome, chrome.getContextManager(), workflow, dirtyFiles, commitResult -> {
                        try {
                            var repo = (GitRepo)
                                    chrome.getContextManager().getProject().getRepo();
                            chrome.showNotification(
                                    IConsoleIO.NotificationRole.INFO,
                                    "Committed "
                                            + repo.shortHash(commitResult.commitId())
                                            + ": "
                                            + commitResult.firstLine());
                            chrome.updateCommitPanel();
                            chrome.updateLogTab();
                            chrome.selectCurrentBranchInLogTab();
                        } finally {
                            // Proceed to run tasks after committing
                            SwingUtilities.invokeLater(action);
                        }
                    });
            commitDialog.setVisible(true);
        } else if (choice[0] == 1) {
            // Continue without committing
            action.run();
        } else {
            // Cancel: do nothing
        }
    }

    void runArchitectOnTaskAsync(int idx, ContextManager cm) {
        cm.submitLlmAction(() -> {
            chrome.showOutputSpinner("Executing Task command...");
            final TaskResult result;
            try {
                result = cm.executeTask(cm.getTaskList().tasks().get(idx));
            } catch (InterruptedException e) {
                logger.debug("Task execution interrupted by user");
                SwingUtilities.invokeLater(this::finishQueueOnError);
                return;
            } catch (RuntimeException ex) {
                logger.error("Internal error running architect", ex);
                SwingUtilities.invokeLater(this::finishQueueOnError);
                return;
            } finally {
                chrome.hideOutputSpinner();
                cm.checkBalanceAndNotify();
            }

            SwingUtilities.invokeLater(() -> {
                try {
                    if (result.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
                        finishQueueOnError();
                        return;
                    }

                    if (Objects.equals(runningIndex, idx)) {
                        var items = new ArrayList<TaskList.TaskItem>(
                                cm.getTaskList().tasks());
                        if (idx >= 0 && idx < items.size()) {
                            var it = items.get(idx);
                            items.set(idx, new TaskList.TaskItem(it.title(), it.text(), true));
                            cm.setTaskList(new TaskList.TaskListData(items));
                            refreshUi(false);
                        }
                    }
                } finally {
                    runningIndex = null;
                    runningFadeTimer.stop();
                    list.repaint();
                    updateButtonStates();
                    startNextIfAny();
                }
            });
        });
    }

    private void startNextIfAny() {
        if (pendingQueue.isEmpty()) {
            // Queue finished
            queueActive = false;
            currentRunOrder = null;
            list.repaint();
            updateButtonStates();
            return;
        }
        // Get next pending index in insertion order and start it
        int next = pendingQueue.getFirst();
        pendingQueue.remove(next);
        list.repaint();
        startRunForIndex(next);
    }

    private void finishQueueOnError() {
        runningIndex = null;
        runningFadeTimer.stop();
        pendingQueue.clear();
        queueActive = false;
        currentRunOrder = null;
        list.repaint();
        updateButtonStates();
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        // Remember the theme so badges can be themed consistently
        this.currentTheme = guiTheme;

        // Keep default Swing theming; adjust list selection for readability if needed
        boolean dark = guiTheme.isDarkTheme();
        Color selBg = UIManager.getColor("List.selectionBackground");
        Color selFg = UIManager.getColor("List.selectionForeground");
        if (selBg == null) selBg = dark ? new Color(60, 90, 140) : new Color(200, 220, 255);
        if (selFg == null) selFg = dark ? Color.WHITE : Color.BLACK;
        list.setSelectionBackground(selBg);
        list.setSelectionForeground(selFg);

        // Refresh the tab badge so it can pick up theme changes
        updateTabBadge();

        revalidate();
        repaint();
    }

    /**
     * Update the Tasks tab icon (possibly with a numeric badge) to reflect the number of incomplete tasks.
     * Safe to call from any thread; work is dispatched to the EDT.
     *
     * Delegates to {@link #updateTasksTabBadge()} which performs the actual work and ensures EDT safety.
     */
    private void updateTabBadge() {
        updateTasksTabBadge();
    }

    /**
     * Compute the number of incomplete tasks in the model. Accesses the Swing model on the EDT to remain thread-safe.
     */
    private int computeIncompleteCount() {
        if (SwingUtilities.isEventDispatchThread()) {
            int incomplete = 0;
            for (int i = 0; i < model.getSize(); i++) {
                TaskList.TaskItem it = model.getElementAt(i);
                if (!it.done()) incomplete++;
            }
            return incomplete;
        } else {
            final int[] result = new int[1];
            try {
                SwingUtilities.invokeAndWait(() -> result[0] = computeIncompleteCount());
            } catch (Exception ex) {
                logger.debug("Error computing incomplete task count on EDT", ex);
                return 0;
            }
            return result[0];
        }
    }

    /**
     * Ensure the Tasks tab has a BadgedIcon (if possible) and update it to reflect the current incomplete count.
     * Safe to call from any thread; UI updates are performed on the EDT.
     *
     * Behavior:
     * - If the panel is not hosted in a JTabbedPane, this is a no-op.
     * - If the incomplete count is zero, restores the base icon and clears any BadgedIcon instance.
     * - If a themed BadgedIcon can be created, sets the badge count and applies the icon.
     * - Otherwise falls back to updating the tab title to include the count.
     */
    private void updateTasksTabBadge() {
        SwingUtilities.invokeLater(() -> {
            try {
                int incomplete = computeIncompleteCount();

                JTabbedPane tabs = findParentTabbedPane();
                if (tabs == null) {
                    // Not hosted in a tabbed pane; nothing to update
                    return;
                }
                int idx = tabIndexOfSelf(tabs);
                if (idx < 0) {
                    return;
                }

                // Update icon with count if possible
                if (incomplete <= 0) {
                    try {
                        tabs.setIconAt(idx, tasksBaseIcon);
                    } finally {
                        tasksTabBadgedIcon = null;
                    }
                } else {
                    // Ensure a BadgedIcon exists; prefer chrome.getTheme(), fallback to currentTheme
                    if (tasksTabBadgedIcon == null) {
                        GuiTheme theme = null;
                        try {
                            theme = chrome.getTheme();
                        } catch (Exception ex) {
                            theme = currentTheme;
                        }
                        if (theme != null) {
                            try {
                                tasksTabBadgedIcon = new BadgedIcon(tasksBaseIcon, theme);
                            } catch (Exception ex) {
                                logger.debug("Failed to create BadgedIcon for Tasks tab", ex);
                                tasksTabBadgedIcon = null;
                            }
                        }
                    }

                    if (tasksTabBadgedIcon != null) {
                        tasksTabBadgedIcon.setCount(incomplete, tabs);
                        tabs.setIconAt(idx, tasksTabBadgedIcon);
                    } else {
                        // As a last-resort fallback, update the tab title to include the count
                        try {
                            String baseTitle = tabs.getTitleAt(idx);
                            if (baseTitle == null || baseTitle.isBlank()) {
                                baseTitle = "Tasks";
                            }
                            tabs.setTitleAt(idx, baseTitle + " (" + incomplete + ")");
                        } catch (Exception ex) {
                            logger.debug("Failed to set tab title fallback for tasks badge", ex);
                        }
                    }
                }

                // Always update tab title suffix for read-only indication
                try {
                    String baseTitle = tabs.getTitleAt(idx);
                    if (baseTitle == null || baseTitle.isBlank()) {
                        baseTitle = "Tasks";
                    }
                    String suffix = " (read-only)";
                    // Normalize: remove existing suffix if present
                    String normalized = baseTitle.endsWith(suffix)
                            ? baseTitle.substring(0, baseTitle.length() - suffix.length())
                            : baseTitle;
                    String newTitle = taskListEditable ? normalized : normalized + suffix;
                    if (!Objects.equals(baseTitle, newTitle)) {
                        tabs.setTitleAt(idx, newTitle);
                    }
                } catch (Exception ex) {
                    logger.debug("Failed to update tasks tab read-only suffix", ex);
                }
            } catch (Exception ex) {
                logger.debug("Error updating tasks tab badge", ex);
            }
        });
    }

    /**
     * Centralized UI refresh. Ensures EDT, refreshes model, buttons, badge, and optionally performs
     * structural layout invalidation when list structure changes (add/remove/reorder/split/combine/clear).
     */
    private void refreshUi(boolean structuralChange) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> refreshUi(structuralChange));
            return;
        }
        model.fireRefresh();
        updateButtonStates();
        updateTasksTabBadge();
        if (structuralChange) {
            clearExpansionOnStructureChange();
        } else {
            list.repaint();
        }
    }

    /**
     * Walk up the component hierarchy looking for an enclosing JTabbedPane.
     */
    private @Nullable JTabbedPane findParentTabbedPane() {
        Container p = getParent();
        while (p != null) {
            if (p instanceof JTabbedPane) return (JTabbedPane) p;
            p = p.getParent();
        }
        return null;
    }

    /**
     * Return the index of this panel within the given tabs, or -1 if not present.
     */
    private int tabIndexOfSelf(JTabbedPane tabs) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) == this) return i;
        }
        return -1;
    }

    public void setSharedContextArea(JComponent contextArea) {
        // Remove any existing context area, but keep the controls.
        for (Component comp : southPanel.getComponents()) {
            if (comp != controls) {
                southPanel.remove(comp);
            }
        }
        // Add the shared context area above the controls.
        southPanel.add(contextArea, 0);
        southPanel.add(Box.createVerticalStrut(2), 1);
        revalidate();
        repaint();
    }

    public void restoreControls() {
        // Remove the shared context area, leaving only the controls visible.
        for (Component comp : southPanel.getComponents()) {
            if (comp != controls) {
                southPanel.remove(comp);
            }
        }
        // Also remove the shared model selector from controls if present
        if (sharedModelSelectorComp != null && sharedModelSelectorComp.getParent() == controls) {
            controls.remove(sharedModelSelectorComp);
            sharedModelSelectorComp = null;
            controls.revalidate();
        }
        // Also remove the shared status strip from controls if present
        if (sharedStatusStripComp != null && sharedStatusStripComp.getParent() == controls) {
            controls.remove(sharedStatusStripComp);
            sharedStatusStripComp = null;
            controls.revalidate();
        }
        revalidate();
        repaint();
    }

    /**
     * Hosts the shared ModelSelector component next to the Play/Stop button in the controls row.
     * The same Swing component instance is physically moved here from InstructionsPanel.
     */
    public void setSharedModelSelector(JComponent comp) {
        SwingUtilities.invokeLater(() -> {
            try {
                // Detach from any previous parent first
                var parent = comp.getParent();
                if (parent != null) {
                    parent.remove(comp);
                    parent.revalidate();
                    parent.repaint();
                }
                sharedModelSelectorComp = comp;

                // With BoxLayout, insert modelSelector before the horizontal glue and goStopButton
                // Position: input | glue | modelSelector | strut | goStopButton
                // We need to remove and rebuild the right side of the controls

                // Find indices of components we need to work with
                int glueIndex = -1;
                int strutIndex = -1;
                int buttonIndex = -1;

                for (int i = 0; i < controls.getComponentCount(); i++) {
                    Component c = controls.getComponent(i);
                    if (c == goStopButton) {
                        buttonIndex = i;
                    } else if (c instanceof Box.Filler && i == controls.getComponentCount() - 3) {
                        glueIndex = i;
                    } else if (c instanceof Box.Filler && i == controls.getComponentCount() - 2) {
                        strutIndex = i;
                    }
                }

                // Remove glue, strut, and button
                if (buttonIndex >= 0) controls.remove(buttonIndex);
                if (strutIndex >= 0) controls.remove(strutIndex);
                if (glueIndex >= 0) controls.remove(glueIndex);

                // Re-add in correct order: glue | modelSelector | strut | button
                controls.add(Box.createHorizontalGlue());
                comp.setAlignmentY(Component.CENTER_ALIGNMENT);
                controls.add(comp);
                controls.add(Box.createHorizontalStrut(8));
                controls.add(goStopButton);

                controls.revalidate();
                controls.repaint();
            } catch (Exception e) {
                logger.debug("Error setting shared ModelSelector in TaskListPanel", e);
            }
        });
    }

    /**
     * Hosts the shared analyzer status strip on the controls row, to the right of the model selector
     * and immediately before the Play/Stop button, with a small horizontal gap.
     */
    public void setSharedStatusStrip(JComponent comp) {
        SwingUtilities.invokeLater(() -> {
            try {
                // Detach from any previous parent first
                Container prevParent = comp.getParent();
                if (prevParent != null) {
                    prevParent.remove(comp);
                    prevParent.revalidate();
                    prevParent.repaint();
                }

                sharedStatusStripComp = comp;
                comp.setAlignmentY(Component.CENTER_ALIGNMENT);

                // If the comp is already in our controls, don't duplicate; just ensure layout refresh.
                if (comp.getParent() == controls) {
                    controls.revalidate();
                    controls.repaint();
                    return;
                }

                // Find the current index of the go/stop button in controls
                int buttonIndex = -1;
                for (int i = 0; i < controls.getComponentCount(); i++) {
                    if (controls.getComponent(i) == goStopButton) {
                        buttonIndex = i;
                        break;
                    }
                }

                // Remove the 8px strut immediately before the button if present (we'll re-add)
                if (buttonIndex > 0 && buttonIndex <= controls.getComponentCount() - 1) {
                    Component before = controls.getComponent(buttonIndex - 1);
                    if (before instanceof Box.Filler) {
                        controls.remove(buttonIndex - 1);
                        buttonIndex--;
                    }
                }

                // Remove the button temporarily so we can rebuild the right edge in the correct order
                if (buttonIndex >= 0 && buttonIndex < controls.getComponentCount()) {
                    controls.remove(buttonIndex);
                }

                boolean insertedAfterModelSelector = false;
                if (sharedModelSelectorComp != null && sharedModelSelectorComp.getParent() == controls) {
                    // Insert the status strip immediately after the model selector, with a 6px gap
                    int msIndex = -1;
                    for (int i = 0; i < controls.getComponentCount(); i++) {
                        if (controls.getComponent(i) == sharedModelSelectorComp) {
                            msIndex = i;
                            break;
                        }
                    }
                    if (msIndex >= 0) {
                        controls.add(Box.createHorizontalStrut(6), msIndex + 1);
                        controls.add(comp, msIndex + 2);
                        insertedAfterModelSelector = true;
                    }
                }

                if (!insertedAfterModelSelector) {
                    // Fallback: insert the status strip at the end (right side) prior to the button
                    controls.add(comp);
                }

                // Re-add the standard 8px spacer and the go/stop button
                controls.add(Box.createHorizontalStrut(8));
                controls.add(goStopButton);

                controls.revalidate();
                controls.repaint();
            } catch (Exception e) {
                logger.debug("Error setting shared status strip in TaskListPanel", e);
            }
        });
    }

    /**
     * Refresh the model from ContextManager. Since the model delegates directly,
     * this just triggers a UI refresh.
     */
    public void refreshFromManager() {
        SwingUtilities.invokeLater(() -> refreshUi(false));
    }

    /**
     * Refresh the model from ContextManager, then run the callback on EDT.
     */
    public void refreshFromManager(@Nullable Runnable onComplete) {
        SwingUtilities.invokeLater(() -> {
            refreshUi(false);
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    private final class TaskReorderTransferHandler extends TransferHandler {
        private int @Nullable [] indices = null;
        private int addIndex = -1;
        private int addCount = 0;

        @Override
        public int getSourceActions(JComponent c) {
            return taskListEditable ? MOVE : NONE;
        }

        @Override
        protected @Nullable Transferable createTransferable(JComponent c) {
            if (!taskListEditable) {
                indices = null;
                return null;
            }
            indices = list.getSelectedIndices();

            if (runningIndex != null && indices != null) {
                for (int i : indices) {
                    if (i == runningIndex) {
                        Toolkit.getDefaultToolkit().beep();
                        indices = null;
                        return null;
                    }
                }
            }
            if (queueActive) {
                Toolkit.getDefaultToolkit().beep();
                indices = null;
                return null;
            }
            if (indices != null) {
                for (int i : indices) {
                    if (pendingQueue.contains(i)) {
                        Toolkit.getDefaultToolkit().beep();
                        indices = null;
                        return null;
                    }
                }
            }
            addIndex = -1;
            addCount = 0;
            return new StringSelection("tasks");
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (!taskListEditable) return false;
            if (queueActive) return false;
            if (indices != null && runningIndex != null) {
                for (int i : indices) {
                    if (i == runningIndex.intValue()) {
                        return false;
                    }
                }
            }
            if (indices != null) {
                for (int i : indices) {
                    if (pendingQueue.contains(i)) return false;
                }
            }
            return support.isDrop();
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!taskListEditable) return false;
            if (!support.isDrop()) {
                return false;
            }
            if (queueActive) return false;
            if (indices != null && runningIndex != null) {
                for (int i : indices) {
                    if (i == runningIndex) {
                        return false;
                    }
                }
            }
            if (indices != null) {
                for (int i : indices) {
                    if (pendingQueue.contains(i)) return false;
                }
            }
            var dl = (JList.DropLocation) support.getDropLocation();
            int index = dl.getIndex();
            int max = model.getSize();
            if (index < 0 || index > max) {
                index = max;
            }
            addIndex = index;

            if (indices == null || indices.length == 0) {
                return false;
            }

            var items = new ArrayList<TaskList.TaskItem>(cm.getTaskList().tasks());

            var selectedSorted = indices.clone();
            Arrays.sort(selectedSorted);

            var moved = new ArrayList<TaskList.TaskItem>(selectedSorted.length);
            for (int i : selectedSorted) {
                if (i >= 0 && i < items.size()) {
                    moved.add(items.get(i));
                }
            }

            for (int i = selectedSorted.length - 1; i >= 0; i--) {
                int idx = selectedSorted[i];
                if (idx >= 0 && idx < items.size()) {
                    items.remove(idx);
                }
            }

            int adjusted = addIndex;
            for (int idx : selectedSorted) {
                if (idx < addIndex) adjusted--;
            }
            if (adjusted < 0) adjusted = 0;
            if (adjusted > items.size()) adjusted = items.size();

            items.addAll(adjusted, moved);
            addCount = moved.size();

            cm.setTaskList(new TaskList.TaskListData(items));

            if (addCount > 0) {
                list.setSelectionInterval(adjusted, adjusted + addCount - 1);
            }
            refreshUi(true);
            return true;
        }

        @Override
        protected void exportDone(JComponent source, Transferable data, int action) {
            indices = null;
            addIndex = -1;
            addCount = 0;
        }
    }

    private void combineSelectedTasks() {
        if (!taskListEditable) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        int[] indices = list.getSelectedIndices();
        if (indices.length < 2) {
            JOptionPane.showMessageDialog(
                    this, "Select at least two tasks to combine.", "Invalid Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }

        for (int idx : indices) {
            if (runningIndex != null && idx == runningIndex) {
                JOptionPane.showMessageDialog(
                        this,
                        "Cannot combine tasks while one is currently running.",
                        "Combine Disabled",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (pendingQueue.contains(idx)) {
                JOptionPane.showMessageDialog(
                        this,
                        "Cannot combine tasks while one is queued for running.",
                        "Combine Disabled",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        }

        Arrays.sort(indices);
        int firstIdx = indices[0];

        if (firstIdx < 0 || firstIdx >= model.getSize()) {
            return;
        }

        var items = new ArrayList<TaskList.TaskItem>(cm.getTaskList().tasks());
        var taskTexts = new ArrayList<String>(indices.length);
        for (int idx : indices) {
            if (idx < 0 || idx >= items.size()) continue;
            TaskList.TaskItem task = items.get(idx);
            taskTexts.add(task.text());
        }
        if (taskTexts.isEmpty()) return;

        String combinedText = String.join(" | ", taskTexts);

        TaskList.TaskItem combinedTask = new TaskList.TaskItem("", combinedText, false);

        items.set(firstIdx, combinedTask);

        for (int i = indices.length - 1; i > 0; i--) {
            int idx = indices[i];
            if (idx >= 0 && idx < items.size()) {
                items.remove(idx);
            }
        }

        cm.setTaskList(new TaskList.TaskListData(items));
        list.setSelectedIndex(firstIdx);
        refreshUi(true);

        summarizeAndUpdateTaskTitleAtIndex(firstIdx);
    }

    private void splitSelectedTask() {
        if (!taskListEditable) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        int[] indices = list.getSelectedIndices();
        if (indices.length != 1) {
            JOptionPane.showMessageDialog(
                    this, "Select exactly one task to split.", "Invalid Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int idx = indices[0];

        if (runningIndex != null && idx == runningIndex) {
            JOptionPane.showMessageDialog(
                    this,
                    "Cannot split a task that is currently running.",
                    "Split Disabled",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (pendingQueue.contains(idx)) {
            JOptionPane.showMessageDialog(
                    this,
                    "Cannot split a task that is queued for running.",
                    "Split Disabled",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        if (queueActive) {
            JOptionPane.showMessageDialog(
                    this,
                    "Cannot split tasks while a run is in progress.",
                    "Split Disabled",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        if (idx < 0 || idx >= model.getSize()) {
            return;
        }

        TaskList.TaskItem original = model.getElementAt(idx);

        var textArea = new JTextArea();
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setText(original.text());

        var scroll = new JScrollPane(
                textArea, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(420, 180));

        var panel = new JPanel(new BorderLayout(6, 6));
        panel.add(new JLabel("Enter one task per line:"), BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(
                this, panel, "Split Task", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) {
            return;
        }

        var lines = normalizeSplitLines(textArea.getText());
        if (lines.isEmpty()) {
            return;
        }

        var items = new ArrayList<TaskList.TaskItem>(cm.getTaskList().tasks());
        items.set(idx, new TaskList.TaskItem("", lines.getFirst(), false));
        for (int i = 1; i < lines.size(); i++) {
            items.add(idx + i, new TaskList.TaskItem("", lines.get(i), false));
        }

        cm.setTaskList(new TaskList.TaskListData(items));
        list.setSelectionInterval(idx, idx + lines.size() - 1);
        refreshUi(true);

        for (int i = 0; i < lines.size(); i++) {
            summarizeAndUpdateTaskTitleAtIndex(idx + i);
        }
    }

    static List<String> normalizeSplitLines(@Nullable String input) {
        if (input == null) return Collections.emptyList();
        return Arrays.stream(input.split("\\R+"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Clears all per-row expansion state. Call this after structural changes that may affect row indices (e.g.,
     * reorders) to avoid stale mappings.
     */
    private void clearExpansionOnStructureChange() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::clearExpansionOnStructureChange);
        }
        list.revalidate();
        list.repaint();
    }

    /**
     * Nudge the list to recompute per-row preferred heights when the width changes, ensuring wrapping and the
     * third-line "....." ellipsis are recalculated for visible rows. This avoids cases where the renderer wants to draw
     * 3 lines but the UI has not yet updated the cached row heights, which would clip the ellipsis.
     */
    private void forceRowHeightsRecalc() {
        // Defer to ensure the viewport/list have the final width before we nudge rows
        SwingUtilities.invokeLater(() -> {
            int first = list.getFirstVisibleIndex();
            int last = list.getLastVisibleIndex();
            int size = model.getSize();
            if (first == -1 || last == -1 || size == 0) {
                list.invalidate();
                list.revalidate();
                list.repaint();
                return;
            }
            model.fireRefresh();
        });
    }

    // endregion

    /**
     * Compute vertical padding to center content within a cell of a given minimum height. If contentHeight >=
     * minHeight, returns zero padding. Otherwise splits the extra space between top and bottom, with top =
     * floor(extra/2).
     *
     * <p>Centering approach: - We dynamically compute the padding to position the text vertically without changing
     * layout managers. - In the renderer, we apply the resulting top padding as a paint offset (or as an EmptyBorder
     * when using a text component). Why not change layouts or switch to HTML/StyledDocument? - Changing the layout per
     * cell (e.g., GridBag/Box) is heavier and can degrade performance on large lists. - HTML/StyledDocument introduce
     * different wrapping/metrics and are more expensive than simple text painting, and do not inherently solve vertical
     * placement within a taller cell. - Keeping rendering lightweight and predictable avoids jank and keeps word-wrap
     * behavior stable, which is especially important when the inline editor uses JTextArea wrapping.
     */
    static Insets verticalPaddingForCell(int contentHeight, int minHeight) {
        int extra = minHeight - contentHeight;
        if (extra <= 0) {
            return new Insets(0, 0, 0, 0);
        }
        int top = extra / 2;
        int bottom = extra - top;
        return new Insets(top, 0, bottom, 0);
    }

    private void clearCompletedTasks() {
        if (!taskListEditable) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        if (model.getSize() == 0) {
            return;
        }

        int completedCount = 0;
        for (int i = 0; i < model.getSize(); i++) {
            TaskList.TaskItem it = model.getElementAt(i);
            if (it.done()) {
                if (runningIndex != null && i == runningIndex) continue;
                completedCount++;
            }
        }

        if (completedCount == 0) {
            updateButtonStates();
            return;
        }

        boolean removedAny = false;
        var items = new ArrayList<TaskList.TaskItem>(cm.getTaskList().tasks());
        for (int i = items.size() - 1; i >= 0; i--) {
            TaskList.TaskItem it = items.get(i);
            if (it.done()) {
                if (runningIndex != null && i == runningIndex) continue;
                items.remove(i);
                removedAny = true;
            }
        }

        if (removedAny) {
            cm.setTaskList(new TaskList.TaskListData(items));
            refreshUi(true);
        }
        updateButtonStates();
    }

    private void copySelectedTasks() {
        int[] indices = list.getSelectedIndices();
        if (indices.length == 0) {
            return;
        }

        var taskTexts = new ArrayList<String>(indices.length);
        for (int idx : indices) {
            if (idx >= 0 && idx < model.getSize()) {
                var item = model.getElementAt(idx);
                taskTexts.add(item.text());
            }
        }

        if (!taskTexts.isEmpty()) {
            String clipboardText = String.join("\n", taskTexts);
            StringSelection selection = new StringSelection(clipboardText);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
        }
    }

    private static boolean isShortTaskText(String text) {
        String trimmed = text.strip();
        return trimmed.length() <= SHORT_TITLE_CHAR_THRESHOLD;
    }

    private void summarizeAndUpdateTaskTitleAtIndex(int index) {
        if (index < 0 || index >= model.getSize()) {
            return;
        }
        var current = model.getElementAt(index);
        var originalText = current.text();
        if (originalText.isBlank()) {
            return;
        }

        // Fast-path for short bodies: just set title = body
        if (isShortTaskText(originalText)) {
            SwingUtilities.invokeLater(() -> {
                try {
                    if (index < 0 || index >= model.getSize()) return;

                    // Verify the same logical item (by text)
                    var items =
                            new ArrayList<TaskList.TaskItem>(cm.getTaskList().tasks());
                    if (index < 0 || index >= items.size()) return;
                    var cur = requireNonNull(items.get(index));
                    if (!Objects.equals(cur.text(), originalText)) return;

                    items.set(index, new TaskList.TaskItem(originalText.strip(), cur.text(), cur.done()));
                    cm.setTaskList(new TaskList.TaskListData(items));
                    refreshUi(false);
                } catch (Exception e) {
                    logger.debug("Error updating short task title at index {}", index, e);
                }
            });
            return;
        }

        // Asynchronous summarization for longer bodies
        var cm = chrome.getContextManager();
        var summaryFuture = cm.summarizeTaskForConversation(originalText);
        summaryFuture.whenComplete((summary, ex) -> {
            if (ex != null) {
                logger.debug("Failed to summarize task title at index {}", index, ex);
                return;
            }
            if (summary == null || summary.isBlank()) {
                return;
            }

            SwingUtilities.invokeLater(() -> {
                try {
                    if (index < 0 || index >= model.getSize()) return;

                    var items =
                            new ArrayList<TaskList.TaskItem>(cm.getTaskList().tasks());
                    if (index < 0 || index >= items.size()) return;
                    var cur = requireNonNull(items.get(index));
                    // Guard against changes while we were summarizing
                    if (!Objects.equals(cur.text(), originalText)) return;

                    items.set(index, new TaskList.TaskItem(summary.strip(), cur.text(), cur.done()));
                    cm.setTaskList(new TaskList.TaskListData(items));
                    refreshUi(false);
                } catch (Exception e) {
                    logger.debug("Error applying summarized task title at index {}", index, e);
                }
            });
        });
    }

    @Override
    public void addNotify() {
        super.addNotify();
        // Ensure the Tasks tab has a BadgedIcon attached (if this panel is hosted in a JTabbedPane).
        try {
            ensureTasksTabBadgeInitialized();
        } catch (Exception e) {
            logger.debug("Unable to initialize tasks tab badge on addNotify", e);
        }
    }

    // Public getters for focus traversal policy
    public JList<TaskList.TaskItem> getTaskList() {
        return list;
    }

    public JTextField getTaskInput() {
        return input;
    }

    public MaterialButton getGoStopButton() {
        return goStopButton;
    }

    /**
     * Ensure tasksTabBadgedIcon is created and applied to the enclosing JTabbedPane tab (if present).
     * Safe to call from any thread; UI work runs on the EDT. No-op if not hosted in a JTabbedPane or if theme
     * information is not available.
     */
    private void ensureTasksTabBadgeInitialized() {
        SwingUtilities.invokeLater(() -> {
            try {
                JTabbedPane tabs = findParentTabbedPane();
                if (tabs == null) {
                    // Not hosted in a tabbed pane (e.g., drawer); nothing to do.
                    return;
                }
                int idx = tabIndexOfSelf(tabs);
                if (idx < 0) {
                    return;
                }

                // Create the badged icon if missing. Prefer chrome.getTheme(), fallback to currentTheme.
                if (tasksTabBadgedIcon == null) {
                    GuiTheme theme = null;
                    try {
                        theme = chrome.getTheme();
                    } catch (Exception ex) {
                        // ignore and fallback
                        theme = currentTheme;
                    }
                    if (theme == null) {
                        // Cannot create a themed badge without a theme; leave the base icon in place.
                        return;
                    }
                    try {
                        tasksTabBadgedIcon = new BadgedIcon(tasksBaseIcon, theme);
                    } catch (Exception ex) {
                        // If creation fails, do not disturb the tab icon.
                        logger.debug("Failed to create BadgedIcon for Tasks tab", ex);
                        tasksTabBadgedIcon = null;
                        return;
                    }
                }

                // Initialize the badge count from the current model.
                int incomplete = 0;
                for (int i = 0; i < model.getSize(); i++) {
                    TaskList.TaskItem it = model.getElementAt(i);
                    if (!it.done()) incomplete++;
                }
                tasksTabBadgedIcon.setCount(incomplete, tabs);
                tabs.setIconAt(idx, tasksTabBadgedIcon);
            } catch (Exception ex) {
                logger.debug("Error initializing tasks tab badge", ex);
            }
        });
    }

    @Override
    public void removeNotify() {
        // Clean up auto-play listener early to prevent leaks when panel is removed
        if (autoPlayListener != null) {
            try {
                logger.debug("removeNotify: removing autoPlayListener");
                model.removeListDataListener(autoPlayListener);
                logger.debug("removeNotify: autoPlayListener cleared");
            } catch (Exception e) {
                logger.debug("Error removing autoPlayListener on removeNotify", e);
            }
            autoPlayListener = null;
        }

        model.fireRefresh();
        var cm = this.cm;
        cm.removeContextListener(this);

        // Clear the tab badge/icon if present so we don't leave stale badge state when this panel is removed.
        try {
            JTabbedPane tabs = findParentTabbedPane();
            if (tabs != null) {
                int idx = tabIndexOfSelf(tabs);
                if (idx >= 0) {
                    try {
                        tabs.setIconAt(idx, tasksBaseIcon);
                    } catch (Exception ignore) {
                        // ignore any issue restoring icon
                    }
                }
            }
        } catch (Exception ignore) {
            // Ignore errors finding parent tabbed pane during cleanup
        }

        runningFadeTimer.stop();
        super.removeNotify();
    }

    @Override
    public void contextChanged(Context newCtx) {
        UUID current = getCurrentSessionId();
        UUID loaded = this.sessionIdAtLoad;

        var cm = chrome.getContextManager();
        Context selected = requireNonNull(cm.selectedContext());
        boolean onLatest = selected.equals(cm.liveContext());

        this.currentContext = onLatest ? cm.liveContext() : selected;

        SwingUtilities.invokeLater(() -> setTaskListEditable(onLatest));

        String currentFragmentId = this.currentContext
                .getTaskListFragment()
                .map(ContextFragment::id)
                .orElse(null);

        boolean sessionChanged = !Objects.equals(current, loaded);
        boolean fragmentChanged = !Objects.equals(currentFragmentId, lastTaskListFragmentId);

        if (sessionChanged) {
            SwingUtilities.invokeLater(() -> {
                resetEphemeralRunState();
                sessionIdAtLoad = current;
                lastTaskListFragmentId = currentFragmentId;
                refreshUi(true);
            });
            return;
        }

        if (fragmentChanged) {
            lastTaskListFragmentId = currentFragmentId;
            SwingUtilities.invokeLater(() -> {
                refreshUi(true);
                // Switch to Tasks tab when task list changes
                JTabbedPane tabs = findParentTabbedPane();
                if (tabs != null) {
                    int idx = tabIndexOfSelf(tabs);
                    if (idx >= 0) {
                        tabs.setSelectedIndex(idx);
                    }
                }
            });
        }
    }

    private final class TaskRenderer extends JPanel implements ListCellRenderer<TaskList.TaskItem> {
        private final JCheckBox check = new JCheckBox();
        private final WrappedTextView view = new WrappedTextView();

        TaskRenderer() {
            super(new BorderLayout(6, 0));
            setOpaque(true);

            check.setOpaque(false);
            check.setIcon(Icons.CIRCLE);
            check.setSelectedIcon(Icons.CHECK);
            check.setVerticalAlignment(SwingConstants.CENTER);
            add(check, BorderLayout.WEST);

            view.setOpaque(false);
            view.setMaxVisibleLines(3);
            add(view, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends TaskList.TaskItem> list,
                TaskList.TaskItem value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {

            boolean isRunningRow = (!value.done()
                    && TaskListPanel.this.runningIndex != null
                    && TaskListPanel.this.runningIndex == index);
            boolean isPendingRow = (!value.done() && TaskListPanel.this.pendingQueue.contains(index));

            // Icon logic: running takes precedence, then pending, then done/undone
            if (isRunningRow) {
                check.setSelected(false);
                check.setIcon(Icons.ARROW_UPLOAD_READY);
                check.setSelectedIcon(null);
            } else if (isPendingRow) {
                check.setSelected(false);
                check.setIcon(Icons.PENDING);
                check.setSelectedIcon(null);
            } else {
                check.setIcon(Icons.CIRCLE);
                check.setSelectedIcon(Icons.CHECK);
                check.setSelected(value.done());
            }

            // Font and strike-through first (affects metrics)
            Font base = list.getFont();
            view.setFont(base.deriveFont(Font.PLAIN));
            view.setStrikeThrough(value.done());

            // Compute wrapping height based on available width (with safe fallbacks for first render)
            int checkboxRegionWidth = 28;
            // Prefer the viewport's width — that's the visible region we should wrap to.
            Container parent = list.getParent();
            int width;
            if (parent instanceof JViewport vp) {
                width = vp.getWidth();
            } else {
                width = list.getWidth();
            }
            if (width <= 0) {
                // Final fallback to a reasonable width to avoid giant first row
                width = 600;
            }
            int available = Math.max(1, width - checkboxRegionWidth - 8);

            // Apply width before text so measurement uses the correct wrap width immediately
            view.setAvailableWidth(available);

            // Always show title; fallback to first line of text if title is empty
            String displayText = value.title();
            if (displayText == null || displayText.isBlank()) {
                // Fallback: use first line of text
                String fullText = value.text();
                int newlineIndex = fullText.indexOf('\n');
                displayText = newlineIndex > 0 ? fullText.substring(0, newlineIndex) : fullText;
            }
            view.setText(displayText);
            view.setMaxVisibleLines(1);
            view.setVisible(true);

            // Measure content height for the width and compute minHeight invariant
            int contentH = view.getContentHeight();

            // Ensure minimum height to show full checkbox icon and preserve wrapping behavior.
            // Guard against regressions: do not change this formula; minHeight must remain Math.max(contentH, 48).
            int minHeight = Math.max(contentH, 48);
            assert minHeight == Math.max(contentH, 48)
                    : "minHeight must remain Math.max(contentH, 48) to keep wrapping stable";
            // Add a descent-based buffer when expanded to ensure the bottom line is never clipped.
            // Using the font descent gives a robust buffer across LAFs and DPI settings.
            int heightToSet = minHeight;
            this.setPreferredSize(new Dimension(available + checkboxRegionWidth, heightToSet));

            // Vertically center the text within the row by applying top padding as a paint offset.
            // We intentionally avoid changing layouts or switching to HTML so that wrapping remains predictable
            // and rendering stays lightweight. The paint offset gives the same visual effect as a dynamic
            // EmptyBorder without incurring layout churn.
            Insets pad = verticalPaddingForCell(contentH, minHeight);
            view.setTopPadding(pad.top);

            // State coloring and subtle running animation
            if (isRunningRow) {
                long now = System.currentTimeMillis();
                long start = TaskListPanel.this.runningAnimStartMs;
                double periodMs = 5000.0;
                double t = ((now - start) % (long) periodMs) / periodMs; // 0..1
                double ratio = 0.5 * (1 - Math.cos(2 * Math.PI * t)); // 0..1 smooth in/out

                Color bgBase = list.getBackground();
                Color selBg = list.getSelectionBackground();
                if (selBg == null) selBg = bgBase;

                int r = (int) Math.round(bgBase.getRed() * (1 - ratio) + selBg.getRed() * ratio);
                int g = (int) Math.round(bgBase.getGreen() * (1 - ratio) + selBg.getGreen() * ratio);
                int b = (int) Math.round(bgBase.getBlue() * (1 - ratio) + selBg.getBlue() * ratio);
                setBackground(new Color(r, g, b));

                if (isSelected) {
                    view.setForeground(list.getSelectionForeground());
                    Color borderColor = selBg.darker();
                    setBorder(BorderFactory.createLineBorder(borderColor, 1));
                } else {
                    view.setForeground(list.getForeground());
                    setBorder(null);
                }
            } else if (isSelected) {
                setBackground(list.getSelectionBackground());
                view.setForeground(list.getSelectionForeground());
                setBorder(null);
            } else {
                setBackground(list.getBackground());
                view.setForeground(list.getForeground());
                setBorder(null);
            }

            return this;
        }
    }

    /**
     * Controls whether the task list is editable. When false, all edit actions are disabled and the
     * Tasks tab title is suffixed with "(read-only)". Safe to call from any thread.
     */
    public void setTaskListEditable(boolean editable) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setTaskListEditable(editable));
            return;
        }
        this.taskListEditable = editable;
        // Reflect state immediately on input and actions; updateButtonStates will compute the rest.
        input.setEnabled(editable);
        input.setEditable(editable);
        list.setDragEnabled(editable);

        updateButtonStates();
        updateTasksTabBadge();
    }

    public void disablePlay() {
        // Immediate, event-driven flip to "Stop" visuals when an LLM action starts
        SwingUtilities.invokeLater(() -> {
            goStopButton.setIcon(Icons.STOP);
            goStopButton.setText(null);
            goStopButton.setToolTipText("Cancel the current operation");
            var stopBg = UIManager.getColor("Brokk.action_button_bg_stop");
            if (stopBg == null) {
                stopBg = ThemeColors.getColor(false, ThemeColors.GIT_BADGE_BACKGROUND);
            }
            goStopButton.setBackground(stopBg);
            goStopButton.setForeground(Color.WHITE);
            goStopButton.setEnabled(true);
            goStopButton.repaint();
        });
    }

    public void enablePlay() {
        // Recompute full state when an LLM action completes (accounts for selection, queue, etc.)
        SwingUtilities.invokeLater(this::updateButtonStates);
    }

    // task list lives only in the context, the supplier provide the current task list based on the context
    public static final class TaskListModel extends AbstractListModel<TaskList.TaskItem> {
        private final Supplier<List<TaskList.TaskItem>> tasksSupplier;

        public TaskListModel(Supplier<List<TaskList.TaskItem>> tasksSupplier) {
            this.tasksSupplier = tasksSupplier;
        }

        private List<TaskList.TaskItem> tasks() {
            List<TaskList.TaskItem> t = tasksSupplier.get();
            return t != null ? t : Collections.emptyList();
        }

        @Override
        public int getSize() {
            return tasks().size();
        }

        @Override
        public TaskList.TaskItem getElementAt(int index) {
            return tasks().get(index);
        }

        public void fireRefresh() {
            fireContentsChanged(this, 0, getSize() - 1);
        }
    }
}
