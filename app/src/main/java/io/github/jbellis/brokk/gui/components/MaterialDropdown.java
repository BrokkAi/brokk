package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.gui.util.Icons;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.function.Supplier;
import javax.swing.Icon;
import javax.swing.JPopupMenu;
import javax.swing.UIManager;
import javax.swing.border.Border;
import org.jetbrains.annotations.Nullable;

/**
 * A button that shows a dropdown menu when clicked. It's a single solid component that shows a downward-facing arrow on
 * the right side.
 */
public class MaterialDropdown extends MaterialButton {
    private @Nullable Supplier<JPopupMenu> menuSupplier;
    private @Nullable JPopupMenu popupMenu; // optional cache
    private final Icon arrowIcon;

    public MaterialDropdown(String text) {
        super(text);
        this.arrowIcon = new ScaledIcon(Icons.KEYBOARD_DOWN, 0.7);

        addActionListener(e -> showPopupMenuInternal());

        // Adjust border for arrow icon
        var borderColor = UIManager.getColor("Component.borderColor");
        Border lineBorder = new javax.swing.border.LineBorder(borderColor != null ? borderColor : Color.GRAY, 1, true);

        int arrowWidth = arrowIcon.getIconWidth();
        // Existing padding is 4, 8, 4, 8. We need more on the right.
        int rightPadding = 8 + arrowWidth + 4; // original-right + arrow-width + some-spacing
        Border emptyBorder = javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, rightPadding);
        setBorder(javax.swing.BorderFactory.createCompoundBorder(lineBorder, emptyBorder));
    }

    public void setMenuSupplier(@Nullable Supplier<JPopupMenu> menuSupplier) {
        this.menuSupplier = menuSupplier;
        this.popupMenu = null; // reset cache
    }

    void showPopupMenuInternal() {
        if (!isEnabled()) {
            return;
        }
        if (menuSupplier != null) {
            var currentMenu = menuSupplier.get();
            if (currentMenu != null) {
                popupMenu = currentMenu;
                // Show relative to the component, below it
                popupMenu.show(this, 0, getHeight());
            }
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        // Paint arrow on the right
        int x = getWidth() - arrowIcon.getIconWidth() - 8; // 8 is original right padding
        int y = (getHeight() - arrowIcon.getIconHeight()) / 2;
        arrowIcon.paintIcon(this, g, x, y);
    }

    // Lightweight wrapper to scale any Icon by a given factor.
    // Keeps layout sizes consistent with the scaled dimensions.
    private static final class ScaledIcon implements Icon {
        private final Icon delegate;
        private final double scale;
        private final int width;
        private final int height;

        ScaledIcon(Icon delegate, double scale) {
            assert scale > 0.0;
            this.delegate = delegate;
            this.scale = scale;
            if (delegate instanceof SwingUtil.ThemedIcon themedIcon) {
                themedIcon.ensureResolved();
            }
            this.width = Math.max(1, (int) Math.round(delegate.getIconWidth() * scale));
            this.height = Math.max(1, (int) Math.round(delegate.getIconHeight() * scale));
        }

        @Override
        public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
            var g2 = (Graphics2D) g.create();
            try {
                g2.translate(x, y);
                g2.scale(scale, scale);
                delegate.paintIcon(c, g2, 0, 0);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return width;
        }

        @Override
        public int getIconHeight() {
            return height;
        }
    }
}
