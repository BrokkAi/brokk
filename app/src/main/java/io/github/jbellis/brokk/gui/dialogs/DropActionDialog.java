package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.gui.Constants;
import io.github.jbellis.brokk.gui.WorkspacePanel;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.Point;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;

/** Modal dialog to choose the action for dropped files: Edit, Read, or Summarize. */
public final class DropActionDialog extends JDialog {
    private final boolean allowSummarize;

    @Nullable
    private WorkspacePanel.ContextAction selected = WorkspacePanel.ContextAction.EDIT;

    private DropActionDialog(Frame owner, boolean allowSummarize) {
        super(owner, "Add to Workspace", true);
        this.allowSummarize = allowSummarize;
        buildUi();
        pack();
    }

    private void buildUi() {
        var mainPanel = new JPanel(new BorderLayout(Constants.H_GAP, Constants.V_GAP));
        mainPanel.setBorder(
                BorderFactory.createEmptyBorder(Constants.V_GAP, Constants.H_GAP, Constants.V_GAP, Constants.H_GAP));

        var radiosPanel = new JPanel(new GridLayout(allowSummarize ? 2 : 1, 1, Constants.H_GAP, Constants.V_GAP));
        var edit = new JRadioButton("Edit");

        edit.setSelected(true);

        var group = new ButtonGroup();
        group.add(edit);

        radiosPanel.add(edit);

        edit.addActionListener(_e -> selected = WorkspacePanel.ContextAction.EDIT);

        if (allowSummarize) {
            var summarize = new JRadioButton("Summarize");
            group.add(summarize);
            radiosPanel.add(summarize);
            summarize.addActionListener(_e -> selected = WorkspacePanel.ContextAction.SUMMARIZE);
        }

        mainPanel.add(radiosPanel, BorderLayout.CENTER);

        var buttons = new JPanel();
        var ok = new MaterialButton("OK");
        ok.addActionListener(e -> dispose());
        var cancel = new MaterialButton("Cancel");
        cancel.addActionListener(e -> {
            selected = null;
            dispose();
        });
        buttons.add(ok);
        buttons.add(cancel);

        mainPanel.add(buttons, BorderLayout.SOUTH);

        var scroll = new JScrollPane(mainPanel);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        setContentPane(scroll);
    }

    /**
     * Shows the dialog (on EDT) at the given screen location and returns the chosen ContextAction, or null if canceled.
     */
    public static @Nullable WorkspacePanel.ContextAction show(
            Frame owner, boolean allowSummarize, @Nullable Point screenLocation) {
        if (!SwingUtilities.isEventDispatchThread()) {
            final WorkspacePanel.ContextAction[] out = new WorkspacePanel.ContextAction[1];
            SwingUtilities.invokeLater(() -> {
                var d = new DropActionDialog(owner, allowSummarize);
                if (screenLocation != null) {
                    d.setLocation(screenLocation);
                } else {
                    d.setLocationRelativeTo(owner);
                }
                d.setVisible(true);
                out[0] = d.selected;
            });
            return out[0];
        } else {
            var d = new DropActionDialog(owner, allowSummarize);
            if (screenLocation != null) {
                d.setLocation(screenLocation);
            } else {
                d.setLocationRelativeTo(owner);
            }
            d.setVisible(true);
            return d.selected;
        }
    }
}
