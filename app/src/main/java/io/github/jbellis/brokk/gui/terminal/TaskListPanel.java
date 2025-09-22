package io.github.jbellis.brokk.gui.terminal;

import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.util.Icons;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.font.TextAttribute;
import com.google.common.base.Splitter;
import java.util.regex.Pattern;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.DropMode;
import javax.swing.TransferHandler;
import javax.swing.border.TitledBorder;
import javax.swing.JComponent;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.Rectangle;
import org.jetbrains.annotations.Nullable;

/** A simple, theme-aware task list panel supporting add, remove and complete toggle. */
public class TaskListPanel extends JPanel implements ThemeAware {

    private final DefaultListModel<TaskItem> model = new DefaultListModel<>();
    private final JList<TaskItem> list = new JList<>(model);
    private final JTextField input = new JTextField();
    private final MaterialButton removeBtn = new MaterialButton();
    private final MaterialButton toggleDoneBtn = new MaterialButton();
    private final MaterialButton playBtn = new MaterialButton();
    private final IConsoleIO console;

    private @Nullable JTextField inlineEditor = null;
    private int editingIndex = -1;

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
        list.getInputMap().put(
                KeyStroke.getKeyStroke(KeyEvent.VK_A, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                "selectAll");
        list.getActionMap().put("selectAll", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                list.setSelectionInterval(0, Math.max(0, model.size() - 1));
            }
        });

        // Run Architect with Ctrl/Cmd+Enter
        list.getInputMap().put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
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
                if (e.isPopupTrigger()) popup.show(list, e.getX(), e.getY());
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) popup.show(list, e.getX(), e.getY());
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
        input.getInputMap().put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
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
        removeBtn.setToolTipText("Remove selected task");
        removeBtn.addActionListener(e -> removeSelected());

        toggleDoneBtn.setIcon(Icons.CHECK);
        toggleDoneBtn.setToolTipText("Toggle selected done");
        toggleDoneBtn.addActionListener(e -> toggleSelectedDone());

        playBtn.setIcon(Icons.PLAY);
        playBtn.setToolTipText("Run Architect on selected task");
        playBtn.addActionListener(e -> runArchitectOnSelected());

        {
            // Make the buttons visually tighter and grouped
            removeBtn.setMargin(new Insets(0, 0, 0, 0));
            toggleDoneBtn.setMargin(new Insets(0, 0, 0, 0));
            playBtn.setMargin(new Insets(0, 0, 0, 0));

            JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            buttonBar.setOpaque(false);
            buttonBar.add(removeBtn);
            buttonBar.add(toggleDoneBtn);
            buttonBar.add(playBtn);

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
        }
    }

    private void removeSelected() {
        int[] indices = list.getSelectedIndices();
        if (indices.length > 0) {
            for (int i = indices.length - 1; i >= 0; i--) {
                model.remove(indices[i]);
            }
        }
        updateButtonStates();
    }

    private void toggleSelectedDone() {
        int[] indices = list.getSelectedIndices();
        if (indices.length > 0) {
            for (int idx : indices) {
                var it = model.get(idx);
                model.set(idx, new TaskItem(it.text(), !it.done()));
            }
        }
        updateButtonStates();
    }

    private void editSelected() {
        int idx = list.getSelectedIndex();
        if (idx < 0) return;
        startInlineEdit(idx);
    }

    private void startInlineEdit(int index) {
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
        removeBtn.setEnabled(hasSelection);
        toggleDoneBtn.setEnabled(hasSelection);
        playBtn.setEnabled(hasSelection);
    }

    private void runArchitectOnSelected() {
        int idx = list.getSelectedIndex();
        if (idx < 0) {
            JOptionPane.showMessageDialog(this, "Select a task first.", "No selection", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String prompt = model.get(idx).text();
        if (prompt == null || prompt.isBlank()) {
            JOptionPane.showMessageDialog(this, "Selected task is empty.", "Invalid task", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (console instanceof Chrome c) {
            try {
                var options = c.getProject().getArchitectOptions();
                c.getInstructionsPanel().runArchitectCommand(prompt, options);
            } catch (Exception ex) {
                try {
                    console.toolError("Failed to run Architect: " + ex.getMessage(), "Task Runner Error");
                } catch (Exception ignore) {
                    // ignore nested error reporting problems
                }
            }
        } else {
            try {
                console.toolError("Architect is only available in the main app context.", "Task Runner Error");
            } catch (Exception ignore) {
                // ignore nested error reporting problems
            }
        }
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
     * TransferHandler for in-place reordering via drag-and-drop.
     * Keeps data locally and performs MOVE operations within the same list.
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
        protected Transferable createTransferable(JComponent c) {
            // Commit any inline edit before starting a drag
            stopInlineEdit(true);

            indices = list.getSelectedIndices();
            addIndex = -1;
            addCount = 0;

            // We keep the data locally; return a simple dummy transferable
            return new StringSelection("tasks");
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDrop();
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!support.isDrop()) {
                return false;
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
        }
    }

    private record TaskItem(String text, boolean done) {}

    private static final class TaskRenderer extends JPanel implements ListCellRenderer<TaskItem> {
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

            check.setSelected(value.done());
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

            if (isSelected) {
                setBackground(list.getSelectionBackground());
                label.setForeground(list.getSelectionForeground());
            } else {
                setBackground(list.getBackground());
                label.setForeground(list.getForeground());
            }

            return this;
        }
    }
}
