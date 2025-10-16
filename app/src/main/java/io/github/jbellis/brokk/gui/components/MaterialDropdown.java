package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.gui.util.Icons;
import java.awt.Graphics;
import java.awt.Insets;
import java.util.function.Supplier;
import javax.swing.Icon;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;

public class MaterialDropdown extends MaterialButton {

    private static final int ARROW_PADDING = 4;
    private static final int ARROW_FALLBACK_WIDTH = 16;

    private @Nullable Supplier<JPopupMenu> menuSupplier;
    private @Nullable JPopupMenu popupMenu;
    private volatile @Nullable Icon arrowIcon;

    public MaterialDropdown(String text) {
        super(text);

        // Use a conservative right padding up-front so text never overlaps where the arrow will be.
        var m = getMargin();
        setMargin(new Insets(m.top, m.left, m.bottom, m.left + ARROW_FALLBACK_WIDTH + ARROW_PADDING));

        // Initialize the arrow icon on the EDT and then re-calc the right margin based on the real width.
        SwingUtilities.invokeLater(() -> {
            try {
                arrowIcon = new SwingUtil.ScaledIcon(Icons.KEYBOARD_DOWN, 0.75);
                recalculateRightMarginForArrow();
                repaint();
            } catch (Exception ignored) {
                // If anything goes wrong, we keep the fallback padding and skip painting the icon.
            }
        });

        addActionListener(e -> showPopupMenuInternal());
    }

    public void setMenuSupplier(@Nullable Supplier<JPopupMenu> menuSupplier) {
        this.menuSupplier = menuSupplier;
        this.popupMenu = null;
    }

    void showPopupMenuInternal() {
        if (menuSupplier == null) {
            return;
        }
        if (popupMenu == null) {
            popupMenu = menuSupplier.get();
        }
        if (popupMenu != null) {
            popupMenu.show(this, 0, getHeight());
        }
    }

    private void recalculateRightMarginForArrow() {
        var m = getMargin();
        int arrowWidth = arrowIcon != null ? arrowIcon.getIconWidth() : ARROW_FALLBACK_WIDTH;
        setMargin(new Insets(m.top, m.left, m.bottom, m.left + arrowWidth + ARROW_PADDING));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        Icon icon = arrowIcon;
        if (icon == null) {
            return; // Not ready yet; padding already accounts for it
        }

        int y = (getHeight() - icon.getIconHeight()) / 2;
        int x = getWidth() - icon.getIconWidth() - ARROW_PADDING; // right-aligned with padding
        icon.paintIcon(this, g, x, y);
    }
}
