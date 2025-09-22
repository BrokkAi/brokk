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
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
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
    private final JButton toggleDoneBtn = new JButton("Done");

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

        // South: controls
        var controls = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.gridy = 0;

        gbc.gridx = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
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

        toggleDoneBtn.setToolTipText("Mark selected done");
        toggleDoneBtn.addActionListener(e -> toggleSelectedDone());
        gbc.gridx = 3;
        controls.add(toggleDoneBtn, gbc);

        add(new JScrollPane(list), BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);

        // Double-click toggles done
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    toggleSelectedDone();
                }
            }
        });
        updateButtonStates();
    }

    private void addTask() {
        var text = input.getText().strip();
        if (text.isEmpty()) return;
        model.addElement(new TaskItem(text, false));
        input.setText("");
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

            // Strike-through when done
            Font base = list.getFont();
            if (value.done()) {
                label.setFont(base.deriveFont(Font.ITALIC));
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
