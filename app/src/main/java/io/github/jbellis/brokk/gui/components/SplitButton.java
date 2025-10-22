package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.gui.util.Icons;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.FontMetrics;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Supplier;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;

/**
 * A compact split button built from two MaterialButtons: - left: main action (supports text and/or icon) - right:
 * dropdown arrow (Icons.KEYBOARD_DOWN) that shows a popup menu
 *
 * <p>Requirements: - no divider line - not based on FlatLaf split button UI - separate rollover animations for each
 * half - zero padding and zero margins for extreme compactness
 */
public class SplitButton extends JComponent {
    private final MaterialButton actionButton;
    private final MaterialButton arrowButton;

    private @Nullable Supplier<JPopupMenu> menuSupplier;
    private @Nullable JPopupMenu popupMenu; // optional cache

    private boolean unifiedHover;
    private @Nullable MouseAdapter hoverListener;

    public SplitButton(String text) {
        this(text, false);
    }

    public SplitButton(String text, boolean unifiedHover) {
        this.unifiedHover = unifiedHover;
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        actionButton = new MaterialButton(text);
        arrowButton = new MaterialButton();
        SwingUtilities.invokeLater(() -> {
            arrowButton.setIcon(new ScaledIcon(Icons.KEYBOARD_DOWN, 0.7));
            // Icon affects preferred size; refresh maximums to avoid stretching
            updateChildMaximumSizes();
        });

        applyCompactStyling(actionButton);
        applyCompactStyling(arrowButton);

        // Alignments for compact look
        actionButton.setHorizontalAlignment(SwingConstants.LEFT);
        arrowButton.setHorizontalAlignment(SwingConstants.CENTER);

        // Prevent BoxLayout horizontal stretching by aligning children to the left
        actionButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        arrowButton.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Initialize maximum sizes to preferred sizes to avoid stretching
        updateChildMaximumSizes();

        // When the action content changes, recompute and refresh layout
        actionButton.addPropertyChangeListener(evt -> {
            var name = evt.getPropertyName();
            if ("text".equals(name) || "icon".equals(name) || "font".equals(name) || "iconTextGap".equals(name)) {
                updateChildMaximumSizes();
                revalidate();
                repaint();
            }
        });

        // Right side click shows dropdown
        arrowButton.addActionListener(e -> showPopupMenuInternal());

        // Optionally set up unified hover behavior
        if (unifiedHover) {
            setupUnifiedHoverBehavior();
        }

        add(actionButton);
        add(arrowButton);
    }

    public void setUnifiedHover(boolean unified) {
        if (this.unifiedHover == unified) {
            return;
        }

        this.unifiedHover = unified;

        if (unified) {
            setupUnifiedHoverBehavior();
        } else {
            removeUnifiedHoverBehavior();
        }
    }

    public boolean isUnifiedHover() {
        return unifiedHover;
    }

    private void setupUnifiedHoverBehavior() {
        // Remove any existing listener first
        removeUnifiedHoverBehavior();

        hoverListener = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (isEnabled()) {
                    actionButton.getModel().setRollover(true);
                    arrowButton.getModel().setRollover(true);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                actionButton.getModel().setRollover(false);
                arrowButton.getModel().setRollover(false);
            }
        };

        actionButton.addMouseListener(hoverListener);
        arrowButton.addMouseListener(hoverListener);
    }

    private void removeUnifiedHoverBehavior() {
        if (hoverListener != null) {
            actionButton.removeMouseListener(hoverListener);
            arrowButton.removeMouseListener(hoverListener);
            // Clear rollover states when disabling unified behavior
            actionButton.getModel().setRollover(false);
            arrowButton.getModel().setRollover(false);
            hoverListener = null;
        }
    }

    @Override
    public void setToolTipText(@Nullable String text) {
        super.setToolTipText(text);
        actionButton.setToolTipText(text);
        arrowButton.setToolTipText(text);
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
    public void addActionListener(ActionListener l) {
        actionButton.addActionListener(l);
    }

    public void removeActionListener(ActionListener l) {
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
        revalidate();
        repaint();
    }

    public void setIcon(@Nullable Icon icon) {
        actionButton.setIcon(icon);
        revalidate();
        repaint();
    }

    public @Nullable String getText() {
        return actionButton.getText();
    }

    public @Nullable Icon getIcon() {
        return actionButton.getIcon();
    }

    @Override
    public Dimension getMinimumSize() {
        return computePreferredSplitSize();
    }

    @Override
    public Dimension getPreferredSize() {
        return computePreferredSplitSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return computePreferredSplitSize();
    }

    /**
     * Computes the preferred size of the split button based on the current content of the action button
     * (text + optional icon) and the arrow button's preferred width. Height is the max of the two buttons'
     * preferred heights.
     */
    private Dimension computePreferredSplitSize() {
        int actionWidth = computeActionButtonContentWidth();
        int arrowWidth = Math.max(0, arrowButton.getPreferredSize().width);
        int totalWidth = actionWidth + arrowWidth;

        int actionHeight = actionButton.getPreferredSize().height;
        int arrowHeight = arrowButton.getPreferredSize().height;
        int totalHeight = Math.max(actionHeight, arrowHeight);

        // Keep children from being stretched by BoxLayout
        updateChildMaximumSizes();
        return new Dimension(totalWidth, totalHeight);
    }

    /**
     * Calculates the content width of the action button:
     *   insets.left + textWidth + (iconWidth [+ iconTextGap if text present]) + insets.right
     */
    private int computeActionButtonContentWidth() {
        Insets insets = actionButton.getInsets();
        int width = (insets != null ? insets.left + insets.right : 0);

        @Nullable Icon icon = actionButton.getIcon();
        int iconWidth = (icon != null) ? icon.getIconWidth() : 0;

        @Nullable String text = actionButton.getText();
        int textWidth = 0;
        if (text != null && !text.isEmpty()) {
            FontMetrics fm = actionButton.getFontMetrics(actionButton.getFont());
            textWidth = fm.stringWidth(text);
        }

        // If both text and icon are present, add iconTextGap
        int gap = (iconWidth > 0 && textWidth > 0) ? Math.max(0, actionButton.getIconTextGap()) : 0;

        width += iconWidth + gap + textWidth;
        return Math.max(0, width);
    }

    private void updateChildMaximumSizes() {
        // Prevent BoxLayout from stretching children horizontally:
        // constrain each child's maximum size to its current preferred size.
        actionButton.setMaximumSize(actionButton.getPreferredSize());
        arrowButton.setMaximumSize(arrowButton.getPreferredSize());
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
            this.width = Math.max(1, (int) Math.round(delegate.getIconWidth() * scale));
            this.height = Math.max(1, (int) Math.round(delegate.getIconHeight() * scale));
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
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
