package io.github.jbellis.brokk.gui.terminal;

import io.github.jbellis.brokk.IConsoleIO;
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

/** A simple, theme-aware task list panel supporting add, remove and complete toggle. */
public class TaskListPanel extends JPanel implements ThemeAware {

    private final DefaultListModel<TaskItem> model = new DefaultListModel<>();
    private final JList<TaskItem> list = new JList<>(model);
    private final JTextField input = new JTextField();
    private final MaterialButton removeBtn = new MaterialButton();
    private final MaterialButton toggleDoneBtn = new MaterialButton();

    public TaskListPanel(IConsoleIO console) {
        super(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // Header label
        add(new JLabel("Task List"), BorderLayout.NORTH);

        // Center: list with custom renderer
        list.setCellRenderer(new TaskRenderer());
        list.setVisibleRowCount(12);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        // Update button states based on selection
        list.addListSelectionListener(e -> updateButtonStates());

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

        var addBtn = new MaterialButton();
        addBtn.setIcon(Icons.ADD);
        addBtn.setToolTipText("Add task");
        addBtn.addActionListener(e -> addTask());
        gbc.gridx = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        controls.add(addBtn, gbc);

        removeBtn.setIcon(Icons.REMOVE);
        removeBtn.setToolTipText("Remove selected task");
        removeBtn.addActionListener(e -> removeSelected());
        gbc.gridx = 2;
        controls.add(removeBtn, gbc);

        toggleDoneBtn.setIcon(Icons.CHECK);
        toggleDoneBtn.setToolTipText("Toggle selected done");
        toggleDoneBtn.addActionListener(e -> toggleSelectedDone());
        gbc.gridx = 3;
        controls.add(toggleDoneBtn, gbc);

        add(new JScrollPane(list), BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);

        // Double-click edits
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    editSelected();
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
        var it = model.get(idx);
        String edited = JOptionPane.showInputDialog(this, "Edit task", it.text());
        if (edited != null) {
            edited = edited.strip();
            if (!edited.isEmpty()) {
                model.set(idx, new TaskItem(edited, it.done()));
            }
        }
    }

    private void updateButtonStates() {
        boolean hasSelection = list.getSelectedIndex() >= 0;
        removeBtn.setEnabled(hasSelection);
        toggleDoneBtn.setEnabled(hasSelection);
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
