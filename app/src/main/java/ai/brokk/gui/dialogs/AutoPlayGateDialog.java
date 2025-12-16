package ai.brokk.gui.dialogs;

import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.components.MaterialButton;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;

public final class AutoPlayGateDialog extends BaseThemedDialog {
    private final Set<String> incompleteTasks;
    private UserChoice choice = UserChoice.KEEP_OLD;

    /** User's choice from the dialog. */
    public enum UserChoice {
        /** Keep only the existing tasks (discard newly generated tasks). */
        KEEP_OLD,
        /** Keep only the newly generated tasks (replace existing). */
        KEEP_NEW,
        /** Keep both existing and newly generated tasks (merge/deduplicate). */
        KEEP_BOTH,
    }

    private AutoPlayGateDialog(@Nullable Window owner, Set<String> incompleteTasks) {
        super(owner, "New Tasks Found", Dialog.ModalityType.APPLICATION_MODAL);
        this.incompleteTasks = incompleteTasks;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                choice = UserChoice.KEEP_OLD;
            }
        });

        buildUI();
        pack();
        setLocationRelativeTo(owner);
    }

    private void buildUI() {
        var root = getContentRoot();
        root.setLayout(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        String introText =
                "New tasks were generated, and you already have incomplete tasks.\n"
                        + "How would you like to proceed?\n\n"
                        + "- Keep existing tasks: discard the newly generated tasks and keep your current list.\n"
                        + "- Use new tasks: replace your current list with the newly generated tasks.\n"
                        + "- Keep both (merge): append new incomplete tasks to your existing incomplete tasks (deduplicated).";

        var intro = new JTextArea(introText);
        intro.setEditable(false);
        intro.setOpaque(false);
        intro.setLineWrap(true);
        intro.setWrapStyleWord(true);
        root.add(intro, BorderLayout.NORTH);

        var listPanel = new JPanel(new BorderLayout(6, 6));
        listPanel.add(new JLabel("Existing incomplete tasks:"), BorderLayout.NORTH);

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
        var keepOldBtn = new MaterialButton("Keep existing tasks");
        var keepNewBtn = new MaterialButton("Use new tasks");
        SwingUtil.applyPrimaryButtonStyle(keepNewBtn);
        var keepBothBtn = new MaterialButton("Keep both (merge)");
        buttons.add(keepOldBtn);
        buttons.add(keepNewBtn);
        buttons.add(keepBothBtn);
        root.add(buttons, BorderLayout.SOUTH);

        keepOldBtn.addActionListener(e -> {
            choice = UserChoice.KEEP_OLD;
            dispose();
        });
        keepNewBtn.addActionListener(e -> {
            choice = UserChoice.KEEP_NEW;
            dispose();
        });
        keepBothBtn.addActionListener(e -> {
            choice = UserChoice.KEEP_BOTH;
            dispose();
        });

        getRootPane().setDefaultButton(keepNewBtn);

        getRootPane().registerKeyboardAction(
                evt -> {
                    choice = UserChoice.KEEP_OLD;
                    dispose();
                },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    /**
     * Shows the pre-execution gating dialog and returns the user's choice.
     * Must be called on EDT.
     *
     * @param parent Parent component for dialog positioning
     * @param incompleteTasks Set of incomplete task texts to display
     * @return User's choice (KEEP_OLD, KEEP_NEW, or KEEP_BOTH)
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
     * @return User's choice (KEEP_OLD, KEEP_NEW, or KEEP_BOTH)
     */
    public static UserChoice showReplaceOnly(@Nullable Window parent, Set<String> incompleteTasks) {
        assert SwingUtilities.isEventDispatchThread() : "AutoPlayGateDialog.showReplaceOnly must be called on EDT";
        var dialog = new AutoPlayGateDialog(parent, incompleteTasks);
        dialog.setVisible(true); // Blocks until dialog is closed
        return dialog.choice;
    }
}
