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
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;

public final class AutoPlayGateDialog extends BaseThemedDialog {
    private final Set<String> incompleteTasks;
    private UserChoice choice = UserChoice.CANCEL;

    /** User's choice from the dialog. Only REPLACE_AND_CONTINUE or CANCEL will be returned by show(). */
    public enum UserChoice {
        /** Execute all incomplete tasks (legacy; not used by this dialog). */
        EXECUTE_ALL,
        /** Remove pre-existing tasks and execute remaining (legacy; not used by this dialog). */
        CLEAN_AND_RUN,
        /** Replace existing task list and proceed. */
        REPLACE_AND_CONTINUE,
        /** Cancel the operation. */
        CANCEL
    }

    private AutoPlayGateDialog(@Nullable Window owner, Set<String> incompleteTasks) {
        super(owner, "Replace Task List", Dialog.ModalityType.APPLICATION_MODAL);
        this.incompleteTasks = incompleteTasks;

        buildUI();
        pack();
        setLocationRelativeTo(owner);
    }

    private void buildUI() {
        var root = getContentRoot();
        root.setLayout(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        String introText =
                "There are incomplete tasks in this session. Running Lutz or Plan will replace your current task list. Continue?";

        var intro = new JTextArea(introText);
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
        var replaceBtn = new MaterialButton("Replace and Continue");
        SwingUtil.applyPrimaryButtonStyle(replaceBtn);
        var cancelBtn = new MaterialButton("Cancel");
        buttons.add(replaceBtn);
        buttons.add(cancelBtn);
        root.add(buttons, BorderLayout.SOUTH);

        replaceBtn.addActionListener(e -> {
            choice = UserChoice.REPLACE_AND_CONTINUE;
            dispose();
        });
        cancelBtn.addActionListener(e -> {
            choice = UserChoice.CANCEL;
            dispose();
        });

        getRootPane().setDefaultButton(replaceBtn);
    }

    /**
     * Shows the pre-execution gating dialog and returns the user's choice.
     * Must be called on EDT.
     *
     * @param parent Parent component for dialog positioning
     * @param incompleteTasks Set of incomplete task texts to display
     * @return User's choice (REPLACE_AND_CONTINUE or CANCEL)
     */
    public static UserChoice show(@Nullable Window parent, Set<String> incompleteTasks) {
        assert SwingUtilities.isEventDispatchThread() : "AutoPlayGateDialog.show must be called on EDT";
        var dialog = new AutoPlayGateDialog(parent, incompleteTasks);
        dialog.setVisible(true); // Blocks until dialog is closed
        return dialog.choice;
    }

    /**
     * Shows the replace-only gating dialog and returns the user's choice.
     * Alias of show(parent, incompleteTasks). Must be called on EDT.
     *
     * @param parent Parent window for dialog positioning
     * @param incompleteTasks Set of incomplete task texts to display
     * @return User's choice (REPLACE_AND_CONTINUE or CANCEL)
     */
    public static UserChoice showReplaceOnly(@Nullable Window parent, Set<String> incompleteTasks) {
        assert SwingUtilities.isEventDispatchThread() : "AutoPlayGateDialog.showReplaceOnly must be called on EDT";
        var dialog = new AutoPlayGateDialog(parent, incompleteTasks);
        dialog.setVisible(true); // Blocks until dialog is closed
        return dialog.choice;
    }
}
