package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.gui.util.Icons;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.function.Supplier;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import org.jetbrains.annotations.Nullable;

/**
 * A compact split button built from two MaterialButtons:
 * - left: main action (supports text and/or icon)
 * - right: dropdown arrow (Icons.KEYBOARD_DOWN) that shows a popup menu
 *
 * Requirements:
 * - no divider line
 * - not based on FlatLaf split button UI
 * - separate rollover animations for each half
 * - zero padding and zero margins for extreme compactness
 */
public class SplitButton extends JComponent {
    private final MaterialButton actionButton;
    private final MaterialButton arrowButton;

    private @Nullable Supplier<JPopupMenu> menuSupplier;
    private @Nullable JPopupMenu popupMenu; // optional cache

    public SplitButton(String text) {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        actionButton = new MaterialButton(text);
        arrowButton = new MaterialButton();
        SwingUtilities.invokeLater(() -> arrowButton.setIcon(Icons.KEYBOARD_DOWN));

        applyCompactStyling(actionButton);
        applyCompactStyling(arrowButton);

        // Alignments for compact look
        actionButton.setHorizontalAlignment(SwingConstants.LEFT);
        arrowButton.setHorizontalAlignment(SwingConstants.CENTER);

        // Right side click shows dropdown
        arrowButton.addActionListener(e -> showPopupMenuInternal());

        add(actionButton);
        add(arrowButton);
    }

    private static void applyCompactStyling(MaterialButton b) {
        // Maximum compactness: zero margins, zero padding, no border, no minimum width
        b.putClientProperty("JButton.buttonType", "borderless");
        b.putClientProperty("JButton.minimumWidth", 0);
        b.putClientProperty("Button.padding", new Insets(0, 0, 0, 0));
        b.setMargin(new Insets(0, 0, 0, 0));
        b.setBorder(null);
        b.setIconTextGap(0);
        b.setOpaque(false);
        b.setContentAreaFilled(true);
        b.setRolloverEnabled(true);
        b.setFocusable(true);
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
                // Show relative to the whole split component, below it
                popupMenu.show(this, 0, getHeight());
            }
        }
    }

    // Delegate action listeners to the left (main) button to preserve JButton-like API
    public void addActionListener(java.awt.event.ActionListener l) {
        actionButton.addActionListener(l);
    }

    public void removeActionListener(java.awt.event.ActionListener l) {
        actionButton.removeActionListener(l);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        actionButton.setEnabled(enabled);
        arrowButton.setEnabled(enabled);
        setCursor(enabled ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
    }

    // Convenience: support text or icon on the left action button
    public void setText(@Nullable String text) {
        actionButton.setText(text);
    }

    public void setIcon(@Nullable Icon icon) {
        actionButton.setIcon(icon);
    }

    public @Nullable String getText() {
        return actionButton.getText();
    }

    public @Nullable Icon getIcon() {
        return actionButton.getIcon();
    }

    @Override
    public Dimension getMinimumSize() {
        var left = actionButton.getMinimumSize();
        var right = arrowButton.getMinimumSize();
        return new Dimension(left.width + right.width, Math.max(left.height, right.height));
    }

    @Override
    public Dimension getPreferredSize() {
        var left = actionButton.getPreferredSize();
        var right = arrowButton.getPreferredSize();
        return new Dimension(left.width + right.width, Math.max(left.height, right.height));
    }

    @Override
    public Dimension getMaximumSize() {
        var left = actionButton.getMaximumSize();
        var right = arrowButton.getMaximumSize();
        return new Dimension(left.width + right.width, Math.max(left.height, right.height));
    }
}
