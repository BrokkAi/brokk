package io.github.jbellis.brokk.gui.components;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import org.jetbrains.annotations.Nullable;

/**
 * A borderless, Material-style toggle button that aligns styling and behavior with MaterialButton, while keeping the
 * standard JToggleButton API and semantics.
 *
 * <p>Features: - Clean, borderless style; hand cursor when enabled. - Optional global keyboard shortcut registration
 * via KeyboardShortcutUtil. - Optional appending of the shortcut text into the tooltip. - Proper disabled icon variants
 * for FlatSVGIcon and SwingUtil.ThemedIcon. - Border-highlight-only mode for icon-only toolbar toggles: draws a thin
 * edge highlight when hovered/selected.
 */
public class MaterialToggleButton extends JToggleButton {
    private @Nullable KeyStroke keyStroke;
    private @Nullable String originalTooltipText;
    private boolean appendShortcutToTooltip = false;
    private @Nullable Icon originalIcon;

    // Selection highlight parameters
    private final int highlightThickness = 3;
    private boolean borderHighlightOnly = false;

    public MaterialToggleButton() {
        super();
        applyStyling();
    }

    public MaterialToggleButton(@Nullable String text) {
        super();
        applyStyling();
        super.setText(text);
    }

    public MaterialToggleButton(@Nullable Icon icon) {
        super();
        applyStyling();
        setIcon(icon);
    }

    public MaterialToggleButton(@Nullable String text, @Nullable Icon icon) {
        super();
        applyStyling();
        super.setText(text);
        setIcon(icon);
    }

    private void applyStyling() {
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setBorderPainted(false);
        setFocusable(true);

        // Match MaterialButton approach for borderless look
        putClientProperty("JButton.buttonType", "borderless");

        // Allow LaF to render hover/press states by default. The borderHighlightOnly mode
        // will turn off content-area filling so only the edge highlight is drawn.
        setContentAreaFilled(true);
        setRolloverEnabled(true);

        Color linkColor = UIManager.getColor("Label.linkForeground");
        if (linkColor == null) {
            linkColor = UIManager.getColor("Label.foreground");
        }
        if (linkColor == null) {
            linkColor = Color.BLUE;
        }
        setForeground(linkColor);
    }

    public Color getHighlightColor() {
        // Prefer an explicitly set color; otherwise try to match the tab selection indicator color,
        // then accent/focus colors; finally a lighter blue as a fallback.
        Color c = UIManager.getColor("TabbedPane.underlineColor");
        if (c == null) {
            c = new Color(0x64B5F6); // fallback lighter blue
        }
        return c;
    }

    /**
     * When true, the button behaves like an icon-only toolbar toggle: - LAF background is shown only on hover - A
     * left-edge highlight bar is drawn only when selected When false, the button uses standard LAF painting for all
     * states.
     */
    public void setBorderHighlightOnly(boolean borderHighlightOnly) {
        this.borderHighlightOnly = borderHighlightOnly;
        repaint();
    }

    @Override
    public void setToolTipText(@Nullable String text) {
        this.originalTooltipText = text;
        updateTooltip();
    }

    /**
     * Registers a global keyboard shortcut for this toggle button's action.
     *
     * @param keyStroke The KeyStroke to register.
     * @param parentComponent The component (typically a root pane) to associate the shortcut with.
     * @param actionName A unique name for the action.
     * @param action The Runnable to execute when the shortcut is activated.
     */
    public void setShortcut(
            @Nullable KeyStroke keyStroke,
            @Nullable JComponent parentComponent,
            @Nullable String actionName,
            @Nullable Runnable action) {
        this.keyStroke = keyStroke;
        if (keyStroke != null && parentComponent != null && actionName != null && action != null) {
            KeyboardShortcutUtil.registerGlobalShortcut(parentComponent, keyStroke, actionName, action);
        }
        updateTooltip();
    }

    /**
     * If set to true, the keyboard shortcut text will be appended to the button's tooltip.
     *
     * @param append whether to append the shortcut text.
     */
    public void setAppendShortcutToTooltip(boolean append) {
        this.appendShortcutToTooltip = append;
        updateTooltip();
    }

    private void updateTooltip() {
        if (appendShortcutToTooltip && keyStroke != null) {
            String shortcutText = formatKeyStroke(keyStroke);
            if (originalTooltipText != null && !originalTooltipText.isBlank()) {
                super.setToolTipText(originalTooltipText + " (" + shortcutText + ")");
            } else {
                super.setToolTipText(shortcutText);
            }
        } else {
            super.setToolTipText(originalTooltipText);
        }
    }

    @Override
    public void setIcon(@Nullable Icon icon) {
        this.originalIcon = icon;
        updateIconForEnabledState();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        updateIconForEnabledState();
        updateCursorForEnabledState();
    }

    private void updateIconForEnabledState() {
        if (originalIcon == null) {
            super.setIcon(null);
            return;
        }

        if (isEnabled()) {
            super.setIcon(originalIcon);
        } else {
            Icon disabledIcon = createDisabledVersion(originalIcon);
            super.setIcon(disabledIcon);
        }
    }

    private void updateCursorForEnabledState() {
        if (isEnabled()) {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        } else {
            setCursor(Cursor.getDefaultCursor());
        }
    }

    private Icon createDisabledVersion(Icon icon) {
        // Handle ThemedIcon wrapper from SwingUtil
        if (icon instanceof SwingUtil.ThemedIcon themedIcon) {
            Icon delegate = themedIcon.delegate();
            if (delegate instanceof FlatSVGIcon svgIcon) {
                return svgIcon.getDisabledIcon();
            }
        }

        // Handle direct FlatSVGIcon
        if (icon instanceof FlatSVGIcon svgIcon) {
            return svgIcon.getDisabledIcon();
        }

        // Fallback for non-FlatSVG icons - return as-is
        return icon;
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (borderHighlightOnly) {
            // In border-highlight mode, show LAF hover background only when hovered
            boolean rollover = getModel().isRollover();
            setContentAreaFilled(rollover);

            super.paintComponent(g);

            // Draw a left-edge selection indicator while preserving LAF hover background
            if (isSelected()) {
                Graphics2D g2 = (Graphics2D) g.create();
                Color use = getHighlightColor();
                g2.setColor(use);
                int h = getHeight();
                int t = Math.max(1, this.highlightThickness);
                g2.fillRect(0, 0, t, h);
                g2.dispose();
            }
        } else {
            // Standard behavior: always let LAF paint the content area (hover/press/selected)
            setContentAreaFilled(true);
            super.paintComponent(g);
        }
    }

    private static String formatKeyStroke(KeyStroke ks) {
        try {
            int modifiers = ks.getModifiers();
            int keyCode = ks.getKeyCode();
            String modText = InputEvent.getModifiersExText(modifiers);
            String keyText = KeyEvent.getKeyText(keyCode);
            if (modText == null || modText.isBlank()) {
                return keyText;
            }
            return modText + "+" + keyText;
        } catch (Exception e) {
            return ks.toString();
        }
    }
}
