package io.github.jbellis.brokk.gui.components;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.event.KeyEvent;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import org.jetbrains.annotations.Nullable;

/**
 * A borderless, link-styled button that can optionally manage a global keyboard shortcut.
 *
 * <p>This implementation relies on the Look-and-Feel for rollover visuals by enabling rollover and keeping the content
 * area filled, rather than managing hover colors via a dedicated MouseListener.
 */
public class MaterialButton extends JButton {
    private @Nullable KeyStroke keyStroke;
    private @Nullable String originalTooltipText;
    private boolean appendShortcutToTooltip = false;
    private @Nullable Icon originalIcon;

    public MaterialButton() {
        this(null);
    }

    public MaterialButton(@Nullable String text) {
        super();
        applyStyling();
        // Use standard setText.
        super.setText(text);
    }

    private void applyStyling() {
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setBorderPainted(false);
        setFocusable(true);
        putClientProperty("JButton.buttonType", "borderless");

        // Allow the Look-and-Feel to render rollover effects by keeping the content area filled
        // and enabling rollover support on the button model.
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

    @Override
    public void setToolTipText(@Nullable String text) {
        this.originalTooltipText = text;
        updateTooltip();
    }

    /**
     * Registers a global keyboard shortcut for this button's action.
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

    private static String formatKeyStroke(KeyStroke ks) {
        try {
            int modifiers = ks.getModifiers();
            int keyCode = ks.getKeyCode();
            String modText = java.awt.event.InputEvent.getModifiersExText(modifiers);
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
