package ai.brokk.gui.dialogs;

import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.components.MaterialButton;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;

/**
 * Modal dialog for EZ-mode auto-play task gating.
 * Prompts user to execute all tasks, clean existing and run, or cancel.
 */
public final class AutoPlayGateDialog extends JDialog {
    private final Set<String> incompleteTasks;
    private UserChoice choice = UserChoice.CANCEL;

    /** User's choice from the dialog. */
    public enum UserChoice {
        /** Execute all incomplete tasks. */
        EXECUTE_ALL,
        /** Remove pre-existing tasks and execute remaining. */
        CLEAN_AND_RUN,
        /** Cancel the operation. */
        CANCEL
    }

    private AutoPlayGateDialog(Window owner, Set<String> incompleteTasks) {
        super(owner, "Incomplete Tasks", Dialog.ModalityType.APPLICATION_MODAL);
        this.incompleteTasks = incompleteTasks;
        buildUI();
        pack();
        setLocationRelativeTo(owner);
    }

    private void buildUI() {
        var root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        var intro = new JTextArea("There are incomplete tasks in this session. What would you like to do?");
        intro.setEditable(false);
        intro.setOpaque(false);
        intro.setLineWrap(true);
        intro.setWrapStyleWord(true);
        root.add(intro, BorderLayout.NORTH);

        var listPanel = new JPanel(new BorderLayout(6, 6));
        listPanel.add(new JLabel("Incomplete tasks:"), BorderLayout.NORTH);

        var taskTextArea = new JTextArea();
        taskTextArea.setEditable(false);
        taskTextArea.setLineWrap(true);
        taskTextArea.setWrapStyleWord(true);
        var taskText =
                String.join("\n\n", incompleteTasks.stream().map(t -> "• " + t).toList());
        taskTextArea.setText(taskText);
        taskTextArea.setCaretPosition(0);

        var scroll = new JScrollPane(
                taskTextArea, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(600, 300));
        listPanel.add(scroll, BorderLayout.CENTER);

        root.add(listPanel, BorderLayout.CENTER);

        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var executeBtn = new MaterialButton("Execute all tasks now");
        SwingUtil.applyPrimaryButtonStyle(executeBtn);
        var removeBtn = new MaterialButton("Clean existing and run");
        var cancelBtn = new MaterialButton("Cancel");
        buttons.add(executeBtn);
        buttons.add(removeBtn);
        buttons.add(cancelBtn);
        root.add(buttons, BorderLayout.SOUTH);

        executeBtn.addActionListener(e -> {
            choice = UserChoice.EXECUTE_ALL;
            dispose();
        });
        removeBtn.addActionListener(e -> {
            choice = UserChoice.CLEAN_AND_RUN;
            dispose();
        });
        cancelBtn.addActionListener(e -> {
            choice = UserChoice.CANCEL;
            dispose();
        });

        setContentPane(root);
        getRootPane().setDefaultButton(executeBtn);
    }

    /**
     * Shows the auto-play gate dialog and returns the user's choice.
     * Must be called on EDT.
     *
     * @param parent Parent component for dialog positioning
     * @param incompleteTasks Set of incomplete task texts to display
     * @return User's choice (EXECUTE_ALL, CLEAN_AND_RUN, or CANCEL)
     */
    public static UserChoice show(@Nullable Window parent, Set<String> incompleteTasks) {
        assert SwingUtilities.isEventDispatchThread() : "AutoPlayGateDialog.show must be called on EDT";
        var dialog = new AutoPlayGateDialog(parent, incompleteTasks);
        dialog.setVisible(true); // Blocks until dialog is closed
        return dialog.choice;
    }
}
