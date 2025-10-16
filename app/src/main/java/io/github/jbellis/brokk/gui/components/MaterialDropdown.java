package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.gui.util.Icons;
import java.awt.Graphics;
import java.awt.Insets;
import java.util.function.Supplier;
import javax.swing.Icon;
import javax.swing.JPopupMenu;
import org.jetbrains.annotations.Nullable;

public class MaterialDropdown extends MaterialButton {

    private static final int ARROW_PADDING = 4;
    private @Nullable Supplier<JPopupMenu> menuSupplier;
    private @Nullable JPopupMenu popupMenu;
    private final Icon arrowIcon;

    public MaterialDropdown(String text) {
        super(text);
        arrowIcon = new SwingUtil.ScaledIcon(Icons.KEYBOARD_DOWN, 0.75);
        var m = getMargin();
        // Add space for the dropdown arrow, and make the text padding symmetric.
        setMargin(new Insets(m.top, m.left, m.bottom, m.left + arrowIcon.getIconWidth() + ARROW_PADDING));
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

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int y = (getHeight() - arrowIcon.getIconHeight()) / 2;
        // Place arrow on the right with padding
        int x = getWidth() - arrowIcon.getIconWidth() - ARROW_PADDING;
        arrowIcon.paintIcon(this, g, x, y);
    }
}
