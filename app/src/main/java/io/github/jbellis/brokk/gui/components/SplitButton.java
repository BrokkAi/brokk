package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.gui.util.Icons;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
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
 * A compact split button composed of two child buttons: an action button (left) and a dropdown/arrow button
 * (right).
 *
 * <p>Sizing and layout behavior:
 * <ul>
 *   <li>The component computes its preferred size by measuring the action button's content (text and optional
 *       icon) directly using font metrics rather than relying on a cached child preferred size. This ensures
 *       that changes to the action button's text, icon, iconTextGap, or font immediately affect the computed
 *       preferred width of the whole split control.</li>
 *   <li>The dropdown arrow area is intentionally fixed to a small constant width (see {@code ARROW_BUTTON_WIDTH}).
 *       Keeping the arrow area size stable prevents unpredictable growth of the right-side area when menu labels
 *       or action text change; it makes the control's expansion dominated by the left (action) text content.</li>
 *   <li>To avoid horizontal stretching from the enclosing {@link javax.swing.BoxLayout}, each child button's
 *       maximum size is constrained to its current preferred size and both children use
 *       {@link java.awt.Component#LEFT_ALIGNMENT} for X alignment. A property change listener on the action
 *       button (listening for "text", "icon", "font", and "iconTextGap") triggers a lightweight
 *       revalidation/repaint so the SplitButton updates its layout when display-affecting properties change.</li>
 * </ul>
 *
 * <p>Other notes:
 * <ul>
 *   <li>The arrow icon continues to be rendered via a scaled wrapper to keep consistent icon sizing across themes.</li>
 *   <li>The change is intentionally minimal and localized to sizing / layout behavior; visual behavior and
 *       event handling remain unchanged.</li>
 * </ul>
 */
public class SplitButton extends JComponent {
    private final MaterialButton actionButton;
    private final MaterialButton arrowButton;

    private @Nullable Supplier<JPopupMenu> menuSupplier;
    private @Nullable JPopupMenu popupMenu; // optional cache

    private boolean unifiedHover;
    private @Nullable MouseAdapter hoverListener;
    private static final int ARROW_BUTTON_WIDTH = 20;

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
        // Apply initial fixed width on the arrow button before icon is set
        applyArrowButtonFixedWidth();
        SwingUtilities.invokeLater(() -> {
            arrowButton.setIcon(new ScaledIcon(Icons.KEYBOARD_DOWN, 0.7));
            // Icon affects preferred size; fix width and refresh maximums to avoid stretching
            applyArrowButtonFixedWidth();
            updateChildMaximumSizes();
            revalidate();
            repaint();
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

        // When properties that affect width change, perform a deferred size update to avoid race conditions
        actionButton.addPropertyChangeListener(evt -> {
            var name = evt.getPropertyName();
            if ("text".equals(name) || "icon".equals(name) || "font".equals(name) || "iconTextGap".equals(name)) {
                SwingUtilities.invokeLater(() -> {
                    applyArrowButtonFixedWidth();
                    updateChildMaximumSizes();
                    SplitButton.this.revalidate();
                    SplitButton.this.repaint();
                });
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
        int arrowWidth = ARROW_BUTTON_WIDTH;
        int totalWidth = actionWidth + arrowWidth + 8;

        int actionHeight = actionButton.getPreferredSize().height;
        int arrowHeight = arrowButton.getPreferredSize().height;
        int totalHeight = Math.max(actionHeight, arrowHeight);

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

    private void applyArrowButtonFixedWidth() {
        Dimension ps = arrowButton.getPreferredSize();
        int height = ps != null ? ps.height : 0;
        if (height <= 0) {
            // Fallback to action button height if arrow has not computed yet
            height = actionButton.getPreferredSize().height;
        }
        // Fix the arrow width while respecting current preferred height
        Dimension fixed = new Dimension(ARROW_BUTTON_WIDTH, Math.max(1, height));
        arrowButton.setMinimumSize(fixed);
        arrowButton.setPreferredSize(fixed);
    }

    private void updateChildMaximumSizes() {
        // Prevent BoxLayout from stretching the arrow button horizontally by constraining its maximum size.
        // The action button is left flexible to fill available space.
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
