package io.github.jbellis.brokk.gui.terminal;

import com.google.common.base.Splitter;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.agents.SearchAgent;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.GitWorkflow;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.util.Icons;
import io.github.jbellis.brokk.util.Json;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.font.TextAttribute;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.DropMode;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.border.TitledBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

/** A simple, theme-aware task list panel supporting add, remove and complete toggle. */
public class TaskListPanel extends JPanel implements ThemeAware, IContextManager.ContextListener {

    private static final Logger logger = LogManager.getLogger(TaskListPanel.class);
    private boolean isLoadingTasks = false;
    private @Nullable UUID sessionIdAtLoad = null;
    private @Nullable IContextManager registeredContextManager = null;

    private final DefaultListModel<TaskItem> model = new DefaultListModel<>();
    private final JList<TaskItem> list = new JList<>(model);
    private final JTextField input = new JTextField();
    private final MaterialButton removeBtn = new MaterialButton();
    private final MaterialButton toggleDoneBtn = new MaterialButton();
    private final MaterialButton playBtn = new MaterialButton();
    private final MaterialButton clearCompletedBtn = new MaterialButton();
    private final IConsoleIO console;
    private final Timer llmStateTimer;
    private final Timer runningFadeTimer;
    private long runningAnimStartMs = 0L;

    private @Nullable JTextField inlineEditor = null;
    private int editingIndex = -1;
    private @Nullable Integer runningIndex = null;
    private final LinkedHashSet<Integer> pendingQueue = new LinkedHashSet<>();
    private boolean queueActive = false;

    public TaskListPanel(IConsoleIO console) {
        super(new BorderLayout(4, 4));
        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Task List",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)));

        this.console = console;

        // Center: list with custom renderer
        list.setCellRenderer(new TaskRenderer());
        list.setVisibleRowCount(12);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        // Update button states based on selection
        list.addListSelectionListener(e -> updateButtonStates());

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
                list.setSelectionInterval(0, Math.max(0, model.size() - 1));
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
        var editItem = new JMenuItem("Edit");
        editItem.addActionListener(e -> editSelected());
        popup.add(editItem);
        var deleteItem = new JMenuItem("Delete");
        deleteItem.addActionListener(e -> removeSelected());
        popup.add(deleteItem);

        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) {
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
                    deleteItem.setEnabled(!block);
                    popup.show(list, e.getX(), e.getY());
                }
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) {
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
                    deleteItem.setEnabled(!block);
                    popup.show(list, e.getX(), e.getY());
                }
            }
        });

        // South: controls
        var controls = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.gridy = 0;

        gbc.gridx = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Modern input: placeholder + Enter adds, Escape clears
        input.putClientProperty("JTextField.placeholderText", "Add a task...");
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "addTask");
        input.getActionMap().put("addTask", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                addTask();
            }
        });
        input.getInputMap()
                .put(
                        KeyStroke.getKeyStroke(
                                KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
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

        controls.add(input, gbc);

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

        playBtn.setIcon(Icons.PLAY);
        // Show a helpful HTML tooltip and append the platform Enter shortcut (Ctrl/Cmd+Enter display only).
        playBtn.setAppendShortcutToTooltip(true);
        playBtn.setShortcut(
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                null,
                null,
                null);
        playBtn.setToolTipText(
                "<html><body style='width:300px'>Run Architect on the selected tasks in order.<br>Tasks already marked done are skipped.<br>One task runs at a time: the current task is highlighted and the rest are queued.<br>Disabled while another AI task is running.</body></html>");
        playBtn.addActionListener(e -> runArchitectOnSelected());

        clearCompletedBtn.setIcon(Icons.CLEAR_ALL);
        clearCompletedBtn.setToolTipText(
                "<html><body style='width:300px'>Remove all completed tasks from this session.<br>You will be asked to confirm. This cannot be undone.</body></html>");
        clearCompletedBtn.addActionListener(e -> clearCompletedTasks());

        {
            // Make the buttons visually tighter and grouped
            removeBtn.setMargin(new Insets(0, 0, 0, 0));
            toggleDoneBtn.setMargin(new Insets(0, 0, 0, 0));
            playBtn.setMargin(new Insets(0, 0, 0, 0));
            clearCompletedBtn.setMargin(new Insets(0, 0, 0, 0));

            JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            buttonBar.setOpaque(false);
            buttonBar.add(removeBtn);
            buttonBar.add(toggleDoneBtn);
            buttonBar.add(playBtn);
            buttonBar.add(clearCompletedBtn);

            gbc.gridx = 1;
            gbc.weightx = 0.0;
            gbc.fill = GridBagConstraints.NONE;
            controls.add(buttonBar, gbc);
        }

        add(new JScrollPane(list), BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);

        // Edit on double-click only to avoid interfering with multi-select
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    int index = list.locationToIndex(e.getPoint());
                    if (index < 0) return;
                    startInlineEdit(index);
                }
            }
        });
        updateButtonStates();
        llmStateTimer = new Timer(300, e -> updateButtonStates());
        llmStateTimer.setRepeats(true);
        llmStateTimer.start();

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

        loadTasksForCurrentSession();

        if (console instanceof Chrome c) {
            try {
                IContextManager cm = c.getContextManager();
                registeredContextManager = cm;
                cm.addContextListener(this);
            } catch (Exception e) {
                logger.debug("Unable to register TaskListPanel as context listener", e);
            }
        }
    }

    /**
     * Automatically commits any modified files with a message that incorporates the provided task description. -
     * Suggests a commit message via GitWorkflow and combines it with the taskDescription. - Commits all modified files
     * as a single commit. - Reports success/failure on the EDT and refreshes relevant Git UI.
     */
    public static void autoCommitChanges(Chrome chrome, String taskDescription) {
        var cm = chrome.getContextManager();
        var repo = cm.getProject().getRepo();
        java.util.Set<GitRepo.ModifiedFile> modified;
        try {
            modified = repo.getModifiedFiles();
        } catch (GitAPIException e) {
            SwingUtilities.invokeLater(
                    () -> chrome.toolError("Unable to determine modified files: " + e.getMessage(), "Commit Error"));
            return;
        }
        if (modified.isEmpty()) {
            chrome.systemOutput("No changes to commit for task: " + taskDescription);
            return;
        }

        cm.submitUserTask("Auto-committing task result", () -> {
            try {
                var workflowService = new GitWorkflow(cm);
                var filesToCommit =
                        modified.stream().map(GitRepo.ModifiedFile::file).collect(Collectors.toList());

                String suggested = workflowService.suggestCommitMessage(filesToCommit);
                String message;
                if (suggested.isBlank()) {
                    message = taskDescription;
                } else if (!taskDescription.isBlank()
                        && !suggested.toLowerCase(Locale.ROOT).contains(taskDescription.toLowerCase(Locale.ROOT))) {
                    message = suggested + " - " + taskDescription;
                } else {
                    message = suggested;
                }

                var commitResult = workflowService.commit(filesToCommit, message);

                SwingUtilities.invokeLater(() -> {
                    var gitRepo = (GitRepo) repo;
                    chrome.systemOutput("Committed " + gitRepo.shortHash(commitResult.commitId()) + ": "
                            + commitResult.firstLine());
                    chrome.updateCommitPanel();
                    chrome.updateLogTab();
                    chrome.selectCurrentBranchInLogTab();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(
                        () -> chrome.toolError("Auto-commit failed: " + e.getMessage(), "Commit Error"));
            }
            return null;
        });
    }

    private void addTask() {
        var raw = input.getText();
        if (raw == null) return;
        var lines = Splitter.on(Pattern.compile("\\R+")).split(raw.strip());
        int added = 0;
        for (var line : lines) {
            var text = line.strip();
            if (!text.isEmpty()) {
                model.addElement(new TaskItem(text, false));
                added++;
            }
        }
        if (added > 0) {
            input.setText("");
            input.requestFocusInWindow();
            saveTasksForCurrentSession();
        }
    }

    private void removeSelected() {
        int[] indices = list.getSelectedIndices();
        if (indices.length > 0) {
            boolean removedAny = false;
            for (int i = indices.length - 1; i >= 0; i--) {
                int idx = indices[i];
                if (runningIndex != null && idx == runningIndex.intValue()) {
                    continue; // skip running task
                }
                if (pendingQueue.contains(idx)) {
                    continue; // skip pending task
                }
                if (idx >= 0 && idx < model.size()) {
                    model.remove(idx);
                    removedAny = true;
                }
            }
            if (removedAny) {
                updateButtonStates();
                saveTasksForCurrentSession();
            } else {
                // No-op if only the running/pending tasks were selected
                updateButtonStates();
            }
        }
    }

    private void toggleSelectedDone() {
        int[] indices = list.getSelectedIndices();
        if (indices.length > 0) {
            boolean changed = false;
            for (int idx : indices) {
                if (runningIndex != null && idx == runningIndex.intValue()) {
                    continue; // skip running task
                }
                if (pendingQueue.contains(idx)) {
                    continue; // skip pending task
                }
                if (idx >= 0 && idx < model.getSize()) {
                    var it = model.get(idx);
                    model.set(idx, new TaskItem(it.text(), !it.done()));
                    changed = true;
                }
            }
            if (changed) {
                updateButtonStates();
                saveTasksForCurrentSession();
            }
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
        startInlineEdit(idx);
    }

    private void startInlineEdit(int index) {
        if (runningIndex != null && index == runningIndex.intValue()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Cannot edit a task that is currently running.",
                    "Edit Disabled",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (pendingQueue.contains(index)) {
            JOptionPane.showMessageDialog(
                    this,
                    "Cannot edit a task that is queued for running.",
                    "Edit Disabled",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (index < 0 || index >= model.size()) return;

        // Commit any existing editor first
        if (inlineEditor != null) {
            stopInlineEdit(true);
        }

        editingIndex = index;
        var item = model.get(index);
        inlineEditor = new JTextField(item.text());

        // Position editor over the cell (to the right of the checkbox area)
        java.awt.Rectangle cell = list.getCellBounds(index, index);
        int checkboxRegionWidth = 28;
        int editorX = cell.x + checkboxRegionWidth;
        int editorY = cell.y;
        int editorW = Math.max(10, cell.width - checkboxRegionWidth - 4);
        int editorH = cell.height - 2;

        // Ensure list can host an overlay component
        if (list.getLayout() != null) {
            list.setLayout(null);
        }

        inlineEditor.setBounds(editorX, editorY, editorW, editorH);
        list.add(inlineEditor);
        inlineEditor.requestFocusInWindow();
        inlineEditor.selectAll();

        // Key bindings for commit/cancel
        inlineEditor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "commitEdit");
        inlineEditor.getActionMap().put("commitEdit", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stopInlineEdit(true);
            }
        });
        inlineEditor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelEdit");
        inlineEditor.getActionMap().put("cancelEdit", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stopInlineEdit(false);
            }
        });

        // Commit on focus loss
        inlineEditor.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                stopInlineEdit(true);
            }
        });

        list.repaint(cell);
    }

    private void stopInlineEdit(boolean commit) {
        if (inlineEditor == null) return;
        int index = editingIndex;
        var editor = inlineEditor;

        if (commit && index >= 0 && index < model.size()) {
            var cur = model.get(index);
            String newText = editor.getText();
            if (newText != null) {
                newText = newText.strip();
                if (!newText.isEmpty() && !newText.equals(cur.text())) {
                    model.set(index, new TaskItem(newText, cur.done()));
                    saveTasksForCurrentSession();
                }
            }
        }

        list.remove(editor);
        inlineEditor = null;
        editingIndex = -1;
        list.revalidate();
        list.repaint();
        input.requestFocusInWindow();
    }

    private void updateButtonStates() {
        boolean hasSelection = list.getSelectedIndex() >= 0;
        boolean llmBusy = false;
        if (console instanceof Chrome c) {
            try {
                llmBusy = c.getContextManager().isLlmTaskInProgress();
            } catch (Exception ex) {
                logger.debug("Unable to query LLM busy state", ex);
            }
        }

        boolean selectedIsDone = false;
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
        int sel = list.getSelectedIndex();
        if (sel >= 0 && sel < model.getSize()) {
            TaskItem it = model.get(sel);
            selectedIsDone = it != null && it.done();
        }

        // Remove/Toggle disabled if no selection OR selection includes running/pending
        boolean blockEdits = selectionIncludesRunning || selectionIncludesPending;
        removeBtn.setEnabled(hasSelection && !blockEdits);
        toggleDoneBtn.setEnabled(hasSelection && !blockEdits);

        // Play enabled only if: selection exists, not busy, not done, no running/pending in selection, and no active
        // queue
        playBtn.setEnabled(hasSelection && !llmBusy && !selectedIsDone && !blockEdits && !queueActive);

        // Clear Completed enabled if any task is done
        boolean anyCompleted = false;
        for (int i = 0; i < model.getSize(); i++) {
            TaskItem it2 = model.get(i);
            if (it2 != null && it2.done()) {
                anyCompleted = true;
                break;
            }
        }
        clearCompletedBtn.setEnabled(anyCompleted);
    }

    private @Nullable UUID getCurrentSessionId() {
        if (console instanceof Chrome c) {
            try {
                return c.getContextManager().getCurrentSessionId();
            } catch (Exception e) {
                logger.debug("Unable to get current session id", e);
            }
        }
        return null;
    }

    private Path getTasksFilePath(UUID sessionId) {
        if (console instanceof Chrome c) {
            try {
                Path root = c.getContextManager().getRoot();
                return root.resolve(".brokk")
                        .resolve("sessions")
                        .resolve(sessionId.toString())
                        .resolve("tasklist.json");
            } catch (Exception e) {
                logger.debug("Unable to resolve project root for tasks file; defaulting to user.dir", e);
            }
        }
        Path userDir = Path.of(System.getProperty("user.dir"));
        return userDir.resolve(".brokk")
                .resolve("sessions")
                .resolve(sessionId.toString())
                .resolve("tasklist.json");
    }

    private void loadTasksForCurrentSession() {
        var sid = getCurrentSessionId();
        if (sid == null) {
            logger.debug("No current session id; skipping task load");
            return;
        }
        this.sessionIdAtLoad = sid;
        Path file = getTasksFilePath(sid);
        isLoadingTasks = true;
        try {
            if (!Files.exists(file)) {
                model.clear();
            } else {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                if (json != null && !json.isBlank()) {
                    TaskListData data = Json.fromJson(json, TaskListData.class);
                    model.clear();
                    if (data != null) {
                        for (TaskEntryDto dto : data.tasks) {
                            if (dto != null && !dto.text.isBlank()) {
                                model.addElement(new TaskItem(dto.text, dto.done));
                            }
                        }
                    }
                } else {
                    model.clear();
                }
            }
        } catch (Exception e) {
            logger.debug("Failed loading tasks for session {} from {}", sid, file, e);
        } finally {
            isLoadingTasks = false;
            updateButtonStates();
        }
    }

    private void saveTasksForCurrentSession() {
        if (isLoadingTasks) return;
        UUID sid = this.sessionIdAtLoad;
        if (sid == null) {
            sid = getCurrentSessionId();
            if (sid == null) {
                logger.debug("No session id available; skipping task save");
                return;
            }
            this.sessionIdAtLoad = sid;
        }
        Path file = getTasksFilePath(sid);
        try {
            Files.createDirectories(file.getParent());
            TaskListData data = new TaskListData();
            for (int i = 0; i < model.size(); i++) {
                TaskItem it = model.get(i);
                data.tasks.add(new TaskEntryDto(it.text(), it.done()));
            }
            String json = Json.toJson(data);
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.debug("Failed saving tasks for session {} to {}", sid, file, e);
        }
    }

    /** Append a collection of tasks to the end of the current list and persist them for the active session. */
    public void appendTasks(List<String> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        boolean added = false;
        for (var t : tasks) {
            if (t == null) continue;
            var text = t.strip();
            if (!text.isEmpty()) {
                model.addElement(new TaskItem(text, false));
                added = true;
            }
        }
        if (added) {
            saveTasksForCurrentSession();
            updateButtonStates();
        }
    }

    private void runArchitectOnSelected() {
        int[] selected = list.getSelectedIndices();
        if (selected.length == 0) {
            JOptionPane.showMessageDialog(
                    this, "Select at least one task.", "No selection", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Prevent running if an LLM task is already busy or a queue is in progress
        if (console instanceof Chrome cBusy) {
            try {
                if (cBusy.getContextManager().isLlmTaskInProgress()) {
                    JOptionPane.showMessageDialog(
                            this,
                            "An AI task is already running. Please wait for it to finish.",
                            "Busy",
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }
            } catch (Exception ex) {
                logger.debug("Error checking LLM busy state", ex);
            }
        }
        if (queueActive || runningIndex != null) {
            JOptionPane.showMessageDialog(
                    this, "A task run is already in progress.", "In progress", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Build the ordered list of indices to run: valid, not done
        Arrays.sort(selected);
        var toRun = new java.util.ArrayList<Integer>(selected.length);
        for (int idx : selected) {
            if (idx >= 0 && idx < model.getSize()) {
                TaskItem it = model.get(idx);
                if (it != null && !it.done()) {
                    toRun.add(idx);
                }
            }
        }
        if (toRun.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this, "All selected tasks are already done.", "Nothing to run", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Set up queue: first runs now, the rest are pending
        int first = toRun.get(0);
        pendingQueue.clear();
        if (toRun.size() > 1) {
            for (int i = 1; i < toRun.size(); i++) pendingQueue.add(toRun.get(i));
            queueActive = true;
        } else {
            queueActive = false;
        }

        // Reflect pending state in UI and disable Play to avoid double trigger
        list.repaint();
        playBtn.setEnabled(false);

        // Start the first task
        startRunForIndex(first);
    }

    private void startRunForIndex(int idx) {
        if (idx < 0 || idx >= model.getSize()) {
            startNextIfAny();
            return;
        }
        TaskItem item = model.get(idx);
        if (item == null || item.done()) {
            startNextIfAny();
            return;
        }

        String prompt = item.text();
        if (prompt.isBlank()) {
            startNextIfAny();
            return;
        }

        // Set running visuals
        runningIndex = idx;
        runningAnimStartMs = System.currentTimeMillis();
        runningFadeTimer.start();
        list.repaint();

        if (!(console instanceof Chrome c)) {
            try {
                console.toolError("Architect is only available in the main app context.", "Task Runner Error");
            } catch (Exception e2) {
                logger.debug("Error reporting Architect availability warning", e2);
            }
            finishQueueOnError();
            return;
        }

        try {
            var cm = c.getContextManager();
            var future = cm.submitBackgroundTask("Execute Task " + (idx + 1), () -> {
                boolean skipSearch = idx == 0 && !cm.liveContext().isEmpty();

                if (skipSearch) {
                    logger.debug("Skipping SearchAgent for first task since workspace is not empty");
                } else {
                    var model = cm.getService().getScanModel();
                    SearchAgent agent = new SearchAgent(prompt, cm, model, EnumSet.of(SearchAgent.Terminal.WORKSPACE));
                    var searchResult = agent.execute();
                    if (searchResult.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
                        logger.debug("Search failed: {}", searchResult.stopDetails());
                        return false;
                    }
                }

                var archFuture = c.getInstructionsPanel().runArchitectCommand(prompt);
                TaskResult archResult;
                try {
                    archResult = archFuture.get();
                } catch (Exception e) {
                    logger.error("Architect failed for prompt: {}", prompt, e);
                    return false;
                }

                if (archResult.stopDetails().reason() == TaskResult.StopReason.SUCCESS) {
                    // Only auto-commit if we're processing multiple tasks as part of a queue
                    if (queueActive) {
                        autoCommitChanges(c, prompt);
                    }
                    return true;
                }
                return false;
            });

            future.whenComplete((res, ex) -> SwingUtilities.invokeLater(() -> {
                try {
                    if (res && runningIndex != null && runningIndex == idx && idx < model.size()) {
                        var it = model.get(idx);
                        model.set(idx, new TaskItem(it.text(), true));
                        saveTasksForCurrentSession();
                    }
                } finally {
                    // Clear running, advance queue
                    runningIndex = null;
                    runningFadeTimer.stop();
                    list.repaint();
                    updateButtonStates();
                    startNextIfAny();
                }
            }));
        } catch (Exception ex) {
            try {
                console.toolError("Failed to run queued tasks: " + ex.getMessage(), "Task Runner Error");
            } catch (Exception e2) {
                logger.debug("Error reporting queued task failure", e2);
            }
            finishQueueOnError();
        }
    }

    private void startNextIfAny() {
        if (pendingQueue.isEmpty()) {
            // Queue finished
            queueActive = false;
            list.repaint();
            updateButtonStates();
            return;
        }
        // Get next pending index in insertion order and start it
        int next = pendingQueue.iterator().next();
        pendingQueue.remove(next);
        list.repaint();
        startRunForIndex(next);
    }

    private void finishQueueOnError() {
        runningIndex = null;
        runningFadeTimer.stop();
        pendingQueue.clear();
        queueActive = false;
        list.repaint();
        updateButtonStates();
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        // Keep default Swing theming; adjust list selection for readability if needed
        boolean dark = guiTheme.isDarkTheme();
        Color selBg = UIManager.getColor("List.selectionBackground");
        Color selFg = UIManager.getColor("List.selectionForeground");
        if (selBg == null) selBg = dark ? new Color(60, 90, 140) : new Color(200, 220, 255);
        if (selFg == null) selFg = dark ? Color.WHITE : Color.BLACK;
        list.setSelectionBackground(selBg);
        list.setSelectionForeground(selFg);
        revalidate();
        repaint();
    }

    /**
     * TransferHandler for in-place reordering via drag-and-drop. Keeps data locally and performs MOVE operations within
     * the same list.
     */
    private final class TaskReorderTransferHandler extends TransferHandler {
        private @Nullable int[] indices = null;
        private int addIndex = -1;
        private int addCount = 0;

        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Override
        protected @Nullable Transferable createTransferable(JComponent c) {
            // Commit any inline edit before starting a drag
            stopInlineEdit(true);

            indices = list.getSelectedIndices();

            // Disallow dragging if selection includes the running task
            if (runningIndex != null && indices != null) {
                for (int i : indices) {
                    if (i == runningIndex.intValue()) {
                        Toolkit.getDefaultToolkit().beep();
                        indices = null;
                        return null; // cancel drag
                    }
                }
            }

            // Disallow dragging when a queue is active or if selection includes pending
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

            // We keep the data locally; return a simple dummy transferable
            return new StringSelection("tasks");
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (queueActive) return false;
            if (indices != null && runningIndex != null) {
                for (int i : indices) {
                    if (i == runningIndex.intValue()) {
                        return false; // cannot drop a running task
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
            if (!support.isDrop()) {
                return false;
            }
            if (queueActive) return false;
            if (indices != null && runningIndex != null) {
                for (int i : indices) {
                    if (i == runningIndex.intValue()) {
                        return false; // cannot drop a running task
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

            // Snapshot the items being moved
            var items = new java.util.ArrayList<TaskItem>(indices.length);
            for (int i : indices) {
                if (i >= 0 && i < model.size()) {
                    items.add(model.get(i));
                }
            }

            // Insert items at drop index
            for (var it : items) {
                model.add(index++, it);
            }
            addCount = items.size();

            // Select the inserted range
            if (addCount > 0) {
                list.setSelectionInterval(addIndex, addIndex + addCount - 1);
            }
            return true;
        }

        @Override
        protected void exportDone(JComponent source, Transferable data, int action) {
            if (action == MOVE && indices != null) {
                // Adjust indices if we inserted before some of the original positions
                if (addCount > 0) {
                    for (int i = 0; i < indices.length; i++) {
                        if (indices[i] >= addIndex) {
                            indices[i] += addCount;
                        }
                    }
                }
                // Remove original items (from bottom to top to keep indices valid)
                for (int i = indices.length - 1; i >= 0; i--) {
                    int idx = indices[i];
                    if (idx >= 0 && idx < model.size()) {
                        model.remove(idx);
                    }
                }
            }
            indices = null;
            addIndex = -1;
            addCount = 0;
            saveTasksForCurrentSession();
        }
    }

    private void clearCompletedTasks() {
        if (model.isEmpty()) {
            return;
        }

        // Count how many completed tasks would be removed (exclude the running task for safety)
        int completedCount = 0;
        for (int i = 0; i < model.size(); i++) {
            TaskItem it = model.get(i);
            if (it != null && it.done()) {
                if (runningIndex != null && i == runningIndex.intValue()) {
                    continue;
                }
                completedCount++;
            }
        }

        if (completedCount == 0) {
            // Nothing to clear
            updateButtonStates();
            return;
        }

        String plural = completedCount == 1 ? "task" : "tasks";
        String message = "This will remove " + completedCount + " completed " + plural + " from this session.\n"
                + "This action cannot be undone.";
        int result = console.showConfirmDialog(
                message, "Clear Completed Tasks?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (result != JOptionPane.YES_OPTION) {
            updateButtonStates();
            return;
        }

        boolean removedAny = false;
        // Remove from bottom to top to keep indices valid
        for (int i = model.size() - 1; i >= 0; i--) {
            TaskItem it = model.get(i);
            if (it != null && it.done()) {
                // Do not remove the running task even if marked done (safety)
                if (runningIndex != null && i == runningIndex.intValue()) {
                    continue;
                }
                model.remove(i);
                removedAny = true;
            }
        }

        if (removedAny) {
            saveTasksForCurrentSession();
        }
        updateButtonStates();
    }

    @Override
    public void removeNotify() {
        try {
            saveTasksForCurrentSession();
        } catch (Exception e) {
            logger.debug("Error saving tasks on removeNotify", e);
        }
        try {
            var cm = registeredContextManager;
            if (cm != null) {
                cm.removeContextListener(this);
            }
        } catch (Exception e) {
            logger.debug("Error unregistering TaskListPanel as context listener", e);
        } finally {
            registeredContextManager = null;
        }
        llmStateTimer.stop();
        runningFadeTimer.stop();
        super.removeNotify();
    }

    @Override
    public void contextChanged(Context newCtx) {
        UUID current = getCurrentSessionId();
        UUID loaded = this.sessionIdAtLoad;
        if (!Objects.equals(current, loaded)) {
            SwingUtilities.invokeLater(this::loadTasksForCurrentSession);
        }
    }

    private record TaskItem(String text, boolean done) {}

    private final class TaskRenderer extends JPanel implements ListCellRenderer<TaskItem> {
        private final JCheckBox check = new JCheckBox();
        private final javax.swing.JLabel label = new javax.swing.JLabel();

        TaskRenderer() {
            super(new BorderLayout(6, 0));
            setOpaque(true);
            check.setOpaque(false);
            check.setIcon(Icons.CIRCLE);
            check.setSelectedIcon(Icons.CHECK);
            add(check, BorderLayout.WEST);
            add(label, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends TaskItem> list, TaskItem value, int index, boolean isSelected, boolean cellHasFocus) {

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
            label.setText(value.text());

            // Strike-through and dim when done
            Font base = list.getFont();
            if (value.done()) {
                var attrs = new java.util.HashMap<java.awt.font.TextAttribute, Object>(base.getAttributes());
                attrs.put(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
                label.setFont(base.deriveFont(attrs));
            } else {
                label.setFont(base.deriveFont(Font.PLAIN));
            }

            if (isRunningRow) {
                long now = System.currentTimeMillis();
                long start = TaskListPanel.this.runningAnimStartMs;
                double periodMs = 5000.0;
                double t = ((now - start) % (long) periodMs) / periodMs; // 0..1
                double ratio = 0.5 * (1 - Math.cos(2 * Math.PI * t)); // 0..1 smooth in/out

                java.awt.Color bgBase = list.getBackground();
                java.awt.Color selBg = list.getSelectionBackground();
                if (selBg == null) selBg = bgBase;

                int r = (int) Math.round(bgBase.getRed() * (1 - ratio) + selBg.getRed() * ratio);
                int g = (int) Math.round(bgBase.getGreen() * (1 - ratio) + selBg.getGreen() * ratio);
                int b = (int) Math.round(bgBase.getBlue() * (1 - ratio) + selBg.getBlue() * ratio);
                setBackground(new java.awt.Color(r, g, b));

                if (isSelected) {
                    label.setForeground(list.getSelectionForeground());
                    // Subtle selection indicator while flashing
                    java.awt.Color borderColor = selBg.darker();
                    setBorder(javax.swing.BorderFactory.createLineBorder(borderColor, 1));
                } else {
                    label.setForeground(list.getForeground());
                    setBorder(null);
                }
            } else if (isSelected) {
                setBackground(list.getSelectionBackground());
                label.setForeground(list.getSelectionForeground());
                setBorder(null);
            } else {
                setBackground(list.getBackground());
                label.setForeground(list.getForeground());
                setBorder(null);
            }

            return this;
        }
    }

    private static final class TaskListData {
        public List<TaskEntryDto> tasks = new ArrayList<>();

        public TaskListData() {}
    }

    private static final class TaskEntryDto {
        public String text = "";
        public boolean done;

        public TaskEntryDto() {}

        public TaskEntryDto(String text, boolean done) {
            this.text = text;
            this.done = done;
        }
    }
}
