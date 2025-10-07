package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.util.Icons;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;

/**
 * Displays current workspace items as "chips" with a close button to remove them from the workspace.
 * Listens to context changes and updates itself accordingly.
 */
public class WorkspaceItemsChipPanel extends JPanel implements IContextManager.ContextListener {

    private final ContextManager contextManager;

    public WorkspaceItemsChipPanel(ContextManager contextManager) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 4));
        setOpaque(false);
        this.contextManager = contextManager;
        this.contextManager.addContextListener(this);

        // Initialize with the current context
        var fragments = contextManager.topContext().getAllFragmentsInDisplayOrder();
        SwingUtilities.invokeLater(() -> updateChips(fragments));
    }

    @Override
    public void contextChanged(Context newCtx) {
        var fragments = newCtx.getAllFragmentsInDisplayOrder();
        SwingUtilities.invokeLater(() -> updateChips(fragments));
    }

    private void updateChips(List<ContextFragment> fragments) {
        removeAll();

        for (var fragment : fragments) {
            add(createChip(fragment));
        }

        revalidate();
        repaint();
    }

    private Component createChip(ContextFragment fragment) {
        var chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        chip.setOpaque(false);

        Color borderColor = javax.swing.UIManager.getColor("Component.borderColor");
        if (borderColor == null) {
            borderColor = Color.GRAY;
        }
        var outer = new MatteBorder(1, 1, 1, 1, borderColor);
        var inner = new EmptyBorder(2, 8, 2, 6);
        chip.setBorder(new CompoundBorder(outer, inner));

        var label = new JLabel(fragment.shortDescription());

        var close = new JButton(Icons.CLOSE);
        close.setFocusable(false);
        close.setOpaque(false);
        close.setContentAreaFilled(false);
        close.setBorderPainted(false);
        close.setFocusPainted(false);
        close.setMargin(new Insets(0, 0, 0, 0));
        close.setPreferredSize(new Dimension(16, 16));
        close.setToolTipText("Remove from Workspace");
        close.addActionListener(e -> contextManager.drop(Collections.singletonList(fragment)));

        chip.add(label);
        chip.add(close);

        return chip;
    }
}
