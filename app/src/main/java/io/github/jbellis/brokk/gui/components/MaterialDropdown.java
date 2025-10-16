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
        recalculateRightMarginForArrow();

        addActionListener(e -> showPopupMenuInternal());

        // Defer arrow icon creation to the EDT; then recalc margin and refresh layout/painting.
        SwingUtilities.invokeLater(() -> {
            try {
                Icon base = Icons.KEYBOARD_DOWN;
                if (base instanceof SwingUtil.ThemedIcon themed) {
                    // Warm-resolve to avoid transient artifacts and ensure correct dimensions
                    themed.ensureResolved();
                }
                this.arrowIcon = new SwingUtil.ScaledIcon(base, 0.75);
                recalculateRightMarginForArrow();
            } catch (Exception ignored) {
                // If anything goes wrong, we keep the fallback padding and skip painting the icon.
            }
        });
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
        revalidate();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        Icon icon = arrowIcon;
        if (icon == null) {
            return; // Not ready yet; padding already accounts for it
        }

        // If themed, ensure underlying delegate is resolved before measuring/painting
        if (icon instanceof SwingUtil.ThemedIcon themed) {
            themed.ensureResolved();
            icon = themed.delegate();
        }

        int w = icon.getIconWidth();
        int h = icon.getIconHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        int y = (getHeight() - h) / 2;
        int x = getWidth() - w - ARROW_PADDING; // right-aligned with padding
        icon.paintIcon(this, g, x, y);
    }
}
