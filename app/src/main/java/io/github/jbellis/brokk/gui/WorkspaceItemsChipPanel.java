package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.util.Icons;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import org.jetbrains.annotations.Nullable;

/**
 * Displays current workspace items as "chips" with a close button to remove them from the workspace.
 * Listens to context changes and updates itself accordingly.
 */
public class WorkspaceItemsChipPanel extends JPanel implements ThemeAware {

    private final Chrome chrome;
    private final ContextManager contextManager;
    private @Nullable Consumer<ContextFragment> onRemoveFragment;

    public WorkspaceItemsChipPanel(Chrome chrome) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 4));
        setOpaque(false);
        this.chrome = chrome;
        this.contextManager = chrome.getContextManager();
    }

    /**
     * Programmatically set the fragments to display as chips. Safe to call from any thread; updates are
     * marshaled to the EDT.
     */
    public void setFragments(List<ContextFragment> fragments) {
        SwingUtilities.invokeLater(() -> updateChips(fragments));
    }

    /**
     * Sets a listener invoked when a chip's remove button is clicked. If not set, the panel will
     * default to removing from the ContextManager.
     */
    public void setOnRemoveFragment(Consumer<ContextFragment> listener) {
        this.onRemoveFragment = listener;
    }


    private void updateChips(List<ContextFragment> fragments) {
        removeAll();

        for (var fragment : fragments) {
            add(createChip(fragment));
        }

        revalidate();
        repaint();
    }

    private void styleChip(JPanel chip, JLabel label, boolean isDark) {
        Color background = ThemeColors.getColor(isDark, "git_badge_background");
        Color foreground = ThemeColors.getColor(isDark, "badge_foreground");

        chip.setBackground(background);
        label.setForeground(foreground);

        Color borderColor = javax.swing.UIManager.getColor("Component.borderColor");
        if (borderColor == null) {
            borderColor = Color.GRAY;
        }
        var outer = new MatteBorder(1, 1, 1, 1, borderColor);
        var inner = new EmptyBorder(2, 8, 2, 6);
        chip.setBorder(new CompoundBorder(outer, inner));
    }

    private Component createChip(ContextFragment fragment) {
        var chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        chip.setOpaque(true);

        var label = new JLabel(fragment.shortDescription());
        // Improve discoverability and accessibility
        try {
            label.setToolTipText(fragment.description());
            label.getAccessibleContext().setAccessibleDescription(fragment.description());
        } catch (Exception ignored) {
            // Defensive: avoid issues if any accessor fails
        }

        var close = new JButton(Icons.CLOSE);
        close.setFocusable(false);
        close.setOpaque(false);
        close.setContentAreaFilled(false);
        close.setBorderPainted(false);
        close.setFocusPainted(false);
        close.setMargin(new Insets(0, 0, 0, 0));
        close.setPreferredSize(new Dimension(16, 16));
        close.setToolTipText("Remove from Workspace");
        try {
            close.getAccessibleContext().setAccessibleName("Remove " + fragment.shortDescription());
        } catch (Exception ignored) {
            // best-effort accessibility improvements
        }
        close.addActionListener(e -> {
            if (onRemoveFragment != null) {
                onRemoveFragment.accept(fragment);
            } else {
                contextManager.drop(Collections.singletonList(fragment));
            }
        });

        chip.add(label);
        chip.add(close);

        styleChip(chip, label, chrome.getTheme().isDarkTheme());

        return chip;
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        applyTheme(guiTheme, false);
    }

    @Override
    public void applyTheme(GuiTheme guiTheme, boolean wordWrap) {
        SwingUtilities.invokeLater(() -> {
            boolean isDark = guiTheme.isDarkTheme();
            for (var component : getComponents()) {
                if (component instanceof JPanel chip) {
                    JLabel label = null;
                    for (var child : chip.getComponents()) {
                        if (child instanceof JLabel jLabel) {
                            label = jLabel;
                            break;
                        }
                    }
                    if (label != null) {
                        styleChip(chip, label, isDark);
                    }
                }
            }
        });
    }
}
