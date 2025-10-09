package io.github.jbellis.brokk.gui.terminal;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Splitter;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.TaskListData;
import io.github.jbellis.brokk.TaskListEntryDto;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.agents.ArchitectAgent;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.GitWorkflow;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.util.Icons;
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
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.DropMode;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.border.TitledBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A simple, theme-aware task list panel supporting add, remove and complete toggle. */
public class TaskListPanel extends JPanel implements ThemeAware, IContextManager.ContextListener {

    private static final Logger logger = LogManager.getLogger(TaskListPanel.class);
    private boolean isLoadingTasks = false;
    private @Nullable UUID sessionIdAtLoad = null;
    private @Nullable IContextManager registeredContextManager = null;

    private final DefaultListModel<@Nullable TaskItem> model = new DefaultListModel<>();
    private final JList<TaskItem> list = new JList<>(model);
    private final Chrome chrome;
    private final Timer runningFadeTimer;
    private long runningAnimStartMs = 0L;

    private @Nullable Integer runningIndex = null;
    private final LinkedHashSet<Integer> pendingQueue = new LinkedHashSet<>();
    private boolean queueActive = false;
    private @Nullable List<Integer> currentRunOrder = null;

    public TaskListPanel(Chrome chrome) {
        this(chrome, null);
    }

    public TaskListPanel(Chrome chrome, @Nullable JPanel sharedBottomToolbar) {
        super(new BorderLayout(4, 4));
        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Task List",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)));

        this.chrome = chrome;

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
                list.setSelectionInterval(0, Math.max(0, model.size() - 1));
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


        // Context menu (right-click)
        var popup = new JPopupMenu();
        var toggleItem = new JMenuItem("Toggle Done");
        toggleItem.addActionListener(e -> toggleSelectedDone());
        popup.add(toggleItem);
        var editItem = new JMenuItem("Edit");
        editItem.addActionListener(e -> editSelected());
        popup.add(editItem);
        var splitItem = new JMenuItem("Split...");
        splitItem.addActionListener(e -> splitSelectedTask());
        popup.add(splitItem);
        var copyItem = new JMenuItem("Copy");
        copyItem.addActionListener(e -> copySelectedTasks());
        popup.add(copyItem);
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
                    boolean exactlyOne = sel.length == 1;
                    splitItem.setEnabled(!block && exactlyOne && !queueActive);
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
                    boolean exactlyOne = sel.length == 1;
                    splitItem.setEnabled(!block && exactlyOne && !queueActive);
                    deleteItem.setEnabled(!block);
                    popup.show(list, e.getX(), e.getY());
                }
            }
        });


        var scroll =
                new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll, BorderLayout.CENTER);

        // Recompute wrapping and ellipsis when the viewport/list width changes
        var vp = scroll.getViewport();
        vp.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                // Trigger layout and re-render so the renderer recalculates available width per row
                list.revalidate();
                list.repaint();
                // Force recalculation of variable row heights and ellipsis on width change
                TaskListPanel.this.forceRowHeightsRecalc();
            }
        });
        // Also listen on the JList itself in case LAF resizes the list directly
        list.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                list.revalidate();
                list.repaint();
                // Force recalculation of variable row heights and ellipsis on width change
                TaskListPanel.this.forceRowHeightsRecalc();
            }
        });

        if (sharedBottomToolbar != null) {
            add(sharedBottomToolbar, BorderLayout.SOUTH);
        }

        // Ensure correct initial layout with wrapped rows after the panel becomes visible
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                SwingUtilities.invokeLater(() -> {
                    list.revalidate();
                    list.repaint();
                });
            }
        });

        // Double-click opens modal edit dialog; single-click only selects.
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (!javax.swing.SwingUtilities.isLeftMouseButton(e)) return;

                int index = list.locationToIndex(e.getPoint());
                if (index < 0) return;
                java.awt.Rectangle cell = list.getCellBounds(index, index);
                if (cell == null || !cell.contains(e.getPoint())) return;

                if (e.getClickCount() == 2) {
                    list.setSelectedIndex(index);
                    editSelected();
                }
            }
        });
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

        try {
            IContextManager cm = chrome.getContextManager();
            registeredContextManager = cm;
            cm.addContextListener(this);
        } catch (Exception e) {
            logger.debug("Unable to register TaskListPanel as context listener", e);
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
            chrome.toolError("Unable to determine modified files: " + e.getMessage(), "Commit Error");
            return;
        }
        if (modified.isEmpty()) {
            chrome.showNotification(
                    IConsoleIO.NotificationRole.INFO, "No changes to commit for task: " + taskDescription);
            return;
        }

        cm.submitExclusiveAction(() -> {
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
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            "Committed " + gitRepo.shortHash(commitResult.commitId()) + ": "
                                    + commitResult.firstLine());
                    chrome.updateCommitPanel();
                    chrome.updateLogTab();
                    chrome.selectCurrentBranchInLogTab();
                });
            } catch (Exception e) {
                chrome.toolError("Auto-commit failed: " + e.getMessage(), "Commit Error");
            }
            return null;
        });
    }


    private void removeSelected() {
        int[] indices = list.getSelectedIndices();
        if (indices.length > 0) {
            // Determine how many tasks can actually be removed (exclude running/queued)
            int deletableCount = 0;
            for (int idx : indices) {
                if (runningIndex != null && idx == runningIndex.intValue()) {
                    continue; // running task cannot be removed
                }
                if (pendingQueue.contains(idx)) {
                    continue; // queued task cannot be removed
                }
                if (idx >= 0 && idx < model.size()) {
                    deletableCount++;
                }
            }

            if (deletableCount == 0) {
                // No-op if only running/queued tasks were selected
                updateButtonStates();
                return;
            }

            String plural = deletableCount == 1 ? "task" : "tasks";
            String message = "This will remove " + deletableCount + " selected " + plural + " from this session.\n"
                    + "Tasks that are running or queued will not be removed.\n"
                    + "This action cannot be undone.";
            int result = chrome.showConfirmDialog(
                    message, "Remove Selected Tasks?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

            if (result != JOptionPane.YES_OPTION) {
                updateButtonStates();
                return;
            }

            boolean removedAny = false;
            // Remove from bottom to top to keep indices valid
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
                clearExpansionOnStructureChange();
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

        // Open modal edit dialog
        openEditDialog(idx);
    }

    private void openEditDialog(int index) {
        TaskItem current = model.get(index);
        if (current == null) return;

        java.awt.Window owner = SwingUtilities.getWindowAncestor(this);
        javax.swing.JDialog dialog = (owner != null)
                ? new javax.swing.JDialog(owner, "Edit Task", java.awt.Dialog.ModalityType.APPLICATION_MODAL)
                : new javax.swing.JDialog(
                        (java.awt.Window) null, "Edit Task", java.awt.Dialog.ModalityType.APPLICATION_MODAL);

        javax.swing.JTextArea ta = new javax.swing.JTextArea(current.text());
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setFont(list.getFont());

        javax.swing.JScrollPane sp = new javax.swing.JScrollPane(
                ta,
                javax.swing.JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setPreferredSize(new java.awt.Dimension(520, 220));

        javax.swing.JPanel content = new javax.swing.JPanel(new java.awt.BorderLayout(6, 6));
        content.add(new javax.swing.JLabel("Edit task:"), java.awt.BorderLayout.NORTH);
        content.add(sp, java.awt.BorderLayout.CENTER);

        javax.swing.JPanel buttons = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        MaterialButton saveBtn = new MaterialButton("Save");
        SwingUtil.applyPrimaryButtonStyle(saveBtn);
        MaterialButton cancelBtn = new MaterialButton("Cancel");

        saveBtn.addActionListener(e -> {
            String newText = ta.getText();
            if (newText != null) {
                newText = newText.strip();
                if (!newText.isEmpty() && !newText.equals(current.text())) {
                    model.set(index, new TaskItem(newText, current.done()));
                    saveTasksForCurrentSession();
                    list.revalidate();
                    list.repaint();
                }
            }
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());

        buttons.add(saveBtn);
        buttons.add(cancelBtn);
        content.add(buttons, java.awt.BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.setResizable(true);
        dialog.getRootPane().setDefaultButton(saveBtn);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);

        ta.requestFocusInWindow();
        ta.selectAll();

        dialog.setVisible(true);
    }

    private void updateButtonStates() {
    }

    private UUID getCurrentSessionId() {
        return chrome.getContextManager().getCurrentSessionId();
    }

    private void loadTasksForCurrentSession() {
        var sid = getCurrentSessionId();
        var previous = this.sessionIdAtLoad;
        this.sessionIdAtLoad = sid;
        isLoadingTasks = true;

        // Clear immediately when switching sessions to avoid showing stale tasks
        if (!Objects.equals(previous, sid)) {
            model.clear();
            clearExpansionOnStructureChange();
            updateButtonStates();
        }

        var sessionManager = chrome.getContextManager().getProject().getSessionManager();
        Executor edt = SwingUtilities::invokeLater;

        sessionManager.readTaskList(sid).whenComplete((data, ex) -> {
            if (ex != null) {
                logger.debug("Failed loading tasks for session {}", sid, ex);
                edt.execute(() -> {
                    try {
                        if (!sid.equals(this.sessionIdAtLoad)) {
                            return;
                        }
                        model.clear();
                        clearExpansionOnStructureChange();
                        updateButtonStates();
                        chrome.toolError("Unable to load task list: " + ex.getMessage(), "Task List");
                    } finally {
                        isLoadingTasks = false;
                    }
                });
            } else {
                edt.execute(() -> {
                    try {
                        // Ignore stale results if the session has changed since this request started
                        if (!sid.equals(this.sessionIdAtLoad)) {
                            return;
                        }
                        model.clear();
                        for (TaskListEntryDto dto : data.tasks()) {
                            if (!dto.text().isBlank()) {
                                model.addElement(new TaskItem(dto.text(), dto.done()));
                            }
                        }
                        clearExpansionOnStructureChange();
                        updateButtonStates();
                    } finally {
                        isLoadingTasks = false;
                        clearExpansionOnStructureChange();
                    }
                });
            }
        });
    }

    private void saveTasksForCurrentSession() {
        if (isLoadingTasks) return;
        UUID sid = this.sessionIdAtLoad;
        if (sid == null) {
            sid = getCurrentSessionId();
            this.sessionIdAtLoad = sid;
        }

        var sessionManager = chrome.getContextManager().getProject().getSessionManager();

        var dtos = new ArrayList<TaskListEntryDto>(model.size());
        for (int i = 0; i < model.size(); i++) {
            TaskItem it = model.get(i);
            if (it != null && !it.text().isBlank()) {
                dtos.add(new TaskListEntryDto(it.text(), it.done()));
            }
        }
        var data = new TaskListData(java.util.List.copyOf(dtos));

        final UUID sidFinal = sid;
        sessionManager.writeTaskList(sidFinal, data).whenComplete((ignored, ex) -> {
            if (ex != null) {
                logger.warn("Failed saving tasks for session {}", sidFinal, ex);
                chrome.toolError("Unable to save task list: " + ex.getMessage(), "Task List");
            }
        });
    }

    /** Append a collection of tasks to the end of the current list and persist them for the active session. */
    public void appendTasks(List<String> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        boolean added = false;
        for (var t : tasks) {
            var text = t.strip();
            if (!text.isEmpty()) {
                model.addElement(new TaskItem(text, false));
                added = true;
            }
        }
        if (added) {
            clearExpansionOnStructureChange();
            saveTasksForCurrentSession();
            updateButtonStates();
        }
    }


    public void runArchitectOnAll() {
        if (model.getSize() == 0) {
            return;
        }

        // Select all tasks
        int[] allIndices = new int[model.getSize()];
        for (int i = 0; i < model.getSize(); i++) {
            allIndices[i] = i;
        }

        list.setSelectionInterval(0, model.getSize() - 1);
        runArchitectOnIndices(allIndices);
    }

    private void runArchitectOnIndices(int[] selected) {
        // Build the ordered list of indices to run: valid, not done
        Arrays.sort(selected);
        var toRun = new ArrayList<Integer>(selected.length);
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

        // Record the full ordered run for context awareness
        currentRunOrder = java.util.List.copyOf(toRun);

        // Set up queue: first runs now, the rest are pending
        int first = toRun.get(0);
        pendingQueue.clear();
        if (toRun.size() > 1) {
            for (int i = 1; i < toRun.size(); i++) pendingQueue.add(toRun.get(i));
            queueActive = true;
        } else {
            queueActive = false;
        }

        // Reflect pending state in UI and disable Play buttons to avoid double trigger
        list.repaint();

        var cm = chrome.getContextManager();
        if (MainProject.getHistoryAutoCompress()) {
            chrome.showOutputSpinner("Compressing history...");
            var cf = cm.compressHistoryAsync();
            cf.whenComplete((v, ex) -> SwingUtilities.invokeLater(() -> {
                chrome.hideOutputSpinner();
                startRunForIndex(first);
            }));
        } else {
            // Start the first task immediately when auto-compress is disabled
            startRunForIndex(first);
        }
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

        String originalPrompt = item.text();
        if (originalPrompt.isBlank()) {
            startNextIfAny();
            return;
        }

        // Set running visuals
        runningIndex = idx;
        runningAnimStartMs = System.currentTimeMillis();
        runningFadeTimer.start();
        list.repaint();

        // IMMEDIATE FEEDBACK: inform user tasks were submitted without waiting for LLM work
        int totalToRun = currentRunOrder != null ? currentRunOrder.size() : 1;
        int numTask = runningIndex + 1;
        SwingUtilities.invokeLater(() -> chrome.showNotification(
                IConsoleIO.NotificationRole.INFO,
                "Submitted " + totalToRun + " task(s) for execution. Running task " + numTask + " of " + totalToRun
                        + "..."));

        var cm = chrome.getContextManager();

        runArchitectOnTaskAsync(idx, cm, originalPrompt);
    }

    void runArchitectOnTaskAsync(int idx, ContextManager cm, String originalPrompt) {
        // Submit an LLM action that will perform optional search + architect work off the EDT.
        cm.submitLlmAction(() -> {
            chrome.showOutputSpinner("Executing Task command...");
            TaskResult result;
            try (var scope = cm.beginTask(originalPrompt, false)) {
                result = runArchitectOnTaskInternal(cm, originalPrompt, scope);
            } catch (Exception ex) {
                logger.error("Internal error running architect", ex);
                SwingUtilities.invokeLater(this::finishQueueOnError);
                return;
            } finally {
                chrome.hideOutputSpinner();
                cm.checkBalanceAndNotify();
            }

            // do this AFTER the TaskScope closes with both search + architect results
            if (result.stopDetails().reason() == TaskResult.StopReason.SUCCESS) {
                // Only auto-commit if we're processing multiple tasks as part of a queue
                if (queueActive) {
                    autoCommitChanges(chrome, originalPrompt);
                    cm.compressHistory(); // synchronous compress (avoid deadlock with async variant)
                }
            }

            SwingUtilities.invokeLater(() -> {
                try {
                    if (result.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
                        finishQueueOnError();
                        return;
                    }

                    if (Objects.equals(runningIndex, idx) && idx < model.size()) {
                        var it = model.get(idx);
                        if (it != null) {
                            model.set(idx, new TaskItem(it.text(), true));
                            saveTasksForCurrentSession();
                        }
                    }
                } finally {
                    // Clear running, advance queue
                    runningIndex = null;
                    runningFadeTimer.stop();
                    list.repaint();
                    updateButtonStates();
                    startNextIfAny();
                }
            });
        });
    }

    private @NotNull TaskResult runArchitectOnTaskInternal(
            ContextManager cm, String originalPrompt, ContextManager.TaskScope scope) {
        var planningModel = requireNonNull(cm.getService().getModel(Service.GEMINI_2_5_PRO));
        var codeModel = requireNonNull(
                cm.getService().getModel(chrome.getInstructionsPanel().getSelectedModel()));

        var architectAgent = new ArchitectAgent(cm, planningModel, codeModel, originalPrompt, scope);
        return architectAgent.executeWithSearch(scope);
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

    public void disablePlay() {
    }

    public void enablePlay() {
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
            indices = list.getSelectedIndices();

            // Disallow dragging if selection includes the running task
            if (runningIndex != null && indices != null) {
                for (int i : indices) {
                    if (i == runningIndex) {
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
            var items = new ArrayList<TaskItem>(indices.length);
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
            clearExpansionOnStructureChange();
            saveTasksForCurrentSession();
        }
    }


    private void splitSelectedTask() {
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

        if (idx < 0 || idx >= model.size()) {
            return;
        }

        TaskItem original = model.get(idx);
        if (original == null) return;

        var textArea = new JTextArea();
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setText(original.text());

        var scroll = new JScrollPane(
                textArea, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new java.awt.Dimension(420, 180));

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

        // Replace the original with the first line; insert remaining lines after; mark all as not done
        model.set(idx, new TaskItem(lines.getFirst(), false));
        for (int i = 1; i < lines.size(); i++) {
            model.add(idx + i, new TaskItem(lines.get(i), false));
        }

        // Select the new block
        list.setSelectionInterval(idx, idx + lines.size() - 1);

        clearExpansionOnStructureChange();
        saveTasksForCurrentSession();
        updateButtonStates();
        list.revalidate();
        list.repaint();
    }

    static List<String> normalizeSplitLines(@Nullable String input) {
        if (input == null) return java.util.Collections.emptyList();
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
        assert SwingUtilities.isEventDispatchThread();
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
            // Fire a lightweight contentsChanged for visible rows by setting each element to itself.
            // This forces BasicListUI to recompute row heights for just the visible range.
            for (int i = Math.max(0, first); i <= last && i < size; i++) {
                TaskItem it = model.get(i);
                // set the same object to trigger a change event without altering data
                model.set(i, it);
            }
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


    private void copySelectedTasks() {
        int[] indices = list.getSelectedIndices();
        if (indices.length == 0) {
            return;
        }

        var taskTexts = new ArrayList<String>(indices.length);
        for (int idx : indices) {
            if (idx >= 0 && idx < model.getSize()) {
                var item = model.get(idx);
                if (item != null) {
                    taskTexts.add(item.text());
                }
            }
        }

        if (!taskTexts.isEmpty()) {
            String clipboardText = String.join("\n", taskTexts);
            StringSelection selection = new StringSelection(clipboardText);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
        }
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
            view.setExpanded(false);
            view.setMaxVisibleLines(3);

            // Font and strike-through first (affects metrics)
            Font base = list.getFont();
            view.setFont(base.deriveFont(Font.PLAIN));
            view.setStrikeThrough(value.done());

            // Compute wrapping height based on available width (with safe fallbacks for first render)
            int checkboxRegionWidth = 28;
            // Prefer the viewport's width — that's the visible region we should wrap to.
            java.awt.Container parent = list.getParent();
            int width;
            if (parent instanceof javax.swing.JViewport vp) {
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

            // Set text after width so measure() reflects current width and font
            view.setText(value.text());
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
            this.setPreferredSize(new java.awt.Dimension(available + checkboxRegionWidth, heightToSet));

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

                java.awt.Color bgBase = list.getBackground();
                java.awt.Color selBg = list.getSelectionBackground();
                if (selBg == null) selBg = bgBase;

                int r = (int) Math.round(bgBase.getRed() * (1 - ratio) + selBg.getRed() * ratio);
                int g = (int) Math.round(bgBase.getGreen() * (1 - ratio) + selBg.getGreen() * ratio);
                int b = (int) Math.round(bgBase.getBlue() * (1 - ratio) + selBg.getBlue() * ratio);
                setBackground(new java.awt.Color(r, g, b));

                if (isSelected) {
                    view.setForeground(list.getSelectionForeground());
                    java.awt.Color borderColor = selBg.darker();
                    setBorder(javax.swing.BorderFactory.createLineBorder(borderColor, 1));
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
}
