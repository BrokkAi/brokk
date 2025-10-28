package ai.brokk.gui.components;

import ai.brokk.gui.util.KeyboardShortcutUtil;
import ai.brokk.util.GlobalUiSettings;
import java.awt.Font;
import java.awt.event.KeyEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

/**
 * Interface providing font size control functionality for RSyntaxTextArea editors.
 * Implementations can use this to add consistent font size controls (increase/decrease/reset)
 * with persistence via GlobalUiSettings.
 */
public interface EditorFontSizeControl {
    Logger logger = LogManager.getLogger(EditorFontSizeControl.class);

    // Font size configuration with predefined sizes (standard sizes with 2pt minimum increments)
    float[] FONT_SIZES = {8f, 10f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 32f};
    int DEFAULT_FONT_INDEX = 2; // 12f
    float DEFAULT_FALLBACK_FONT_SIZE = FONT_SIZES[DEFAULT_FONT_INDEX];

    /**
     * Get the current font index. Implementations must track this state.
     * Initial value should be -1 (uninitialized).
     */
    int getCurrentFontIndex();

    /**
     * Set the current font index. Implementations must track this state.
     */
    void setCurrentFontIndex(int index);

    /**
     * Find the closest font size index for a given font size.
     */
    default int findClosestFontIndex(float targetSize) {
        int closestIndex = DEFAULT_FONT_INDEX;
        float minDiff = Math.abs(FONT_SIZES[DEFAULT_FONT_INDEX] - targetSize);

        for (int i = 0; i < FONT_SIZES.length; i++) {
            float diff = Math.abs(FONT_SIZES[i] - targetSize);
            if (diff < minDiff) {
                minDiff = diff;
                closestIndex = i;
            }
        }
        return closestIndex;
    }

    /**
     * Ensure font index is initialized from GlobalUiSettings or default.
     */
    default void ensureFontIndexInitialized() {
        if (getCurrentFontIndex() == -1) {
            float saved = GlobalUiSettings.getEditorFontSize();
            if (saved > 0f) {
                setCurrentFontIndex(findClosestFontIndex(saved));
            } else {
                setCurrentFontIndex(DEFAULT_FONT_INDEX);
            }
        }
    }

    /**
     * Increase font size to next preset size.
     */
    default void increaseEditorFont() {
        ensureFontIndexInitialized();
        if (getCurrentFontIndex() >= FONT_SIZES.length - 1) return; // Already at maximum
        setCurrentFontIndex(getCurrentFontIndex() + 1);
    }

    /**
     * Decrease font size to previous preset size.
     */
    default void decreaseEditorFont() {
        ensureFontIndexInitialized();
        if (getCurrentFontIndex() <= 0) return; // Already at minimum
        setCurrentFontIndex(getCurrentFontIndex() - 1);
    }

    /**
     * Reset font size to default.
     */
    default void resetEditorFont() {
        ensureFontIndexInitialized();
        if (getCurrentFontIndex() == DEFAULT_FONT_INDEX) return; // Already at default
        setCurrentFontIndex(DEFAULT_FONT_INDEX);
    }

    /**
     * Apply the current font size to the given editor and persist to GlobalUiSettings.
     */
    default void applyEditorFontSize(RSyntaxTextArea editor) {
        if (getCurrentFontIndex() < 0) return; // guard

        // Ensure index in-range
        int idx = Math.max(0, Math.min(getCurrentFontIndex(), FONT_SIZES.length - 1));
        float fontSize = FONT_SIZES[idx];

        // Persist chosen font size
        GlobalUiSettings.saveEditorFontSize(fontSize);

        // Apply to editor
        setEditorFont(editor, fontSize);
    }

    /**
     * Set the font for an editor and update its syntax scheme while preserving colors.
     */
    default void setEditorFont(RSyntaxTextArea editor, float size) {
        try {
            Font base = editor.getFont();
            Font newFont =
                    (base != null) ? base.deriveFont(size) : editor.getFont().deriveFont(size);
            editor.setFont(newFont);
            updateSyntaxSchemeFonts(editor, newFont);
            editor.revalidate();
            editor.repaint();
        } catch (Exception ex) {
            logger.debug("Could not apply font to editor", ex);
        }
    }

    /**
     * Update all token styles in the syntax scheme to use the new font while preserving colors.
     */
    default void updateSyntaxSchemeFonts(RSyntaxTextArea editor, Font newFont) {
        try {
            var scheme = editor.getSyntaxScheme();
            if (scheme == null) return;

            // Update font for each token type style while preserving colors
            for (int i = 0; i < scheme.getStyleCount(); i++) {
                var style = scheme.getStyle(i);
                if (style != null && style.font != null) {
                    // Preserve font style (bold, italic) but use new size
                    int fontStyle = style.font.getStyle();
                    style.font = newFont.deriveFont(fontStyle);
                }
            }
        } catch (Exception ex) {
            logger.debug("Could not update syntax scheme fonts", ex);
        }
    }

    /**
     * Create and configure a decrease font button.
     */
    default MaterialButton createDecreaseFontButton(Runnable action) {
        var btn = new MaterialButton();
        btn.setText("A");
        btn.setFont(new Font(btn.getFont().getName(), Font.PLAIN, 10));
        btn.setToolTipText("Decrease editor font size ("
                + KeyboardShortcutUtil.formatKeyStroke(GlobalUiSettings.getKeybinding(
                        "view.zoomOut", KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_MINUS)))
                + ")");
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setFocusPainted(false);
        btn.addActionListener(e -> action.run());
        return btn;
    }

    /**
     * Create and configure a reset font button.
     */
    default MaterialButton createResetFontButton(Runnable action) {
        var btn = new MaterialButton();
        btn.setText("A");
        btn.setFont(new Font(btn.getFont().getName(), Font.PLAIN, 14));
        btn.setToolTipText("Reset editor font size ("
                + KeyboardShortcutUtil.formatKeyStroke(GlobalUiSettings.getKeybinding(
                        "view.resetZoom", KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_0)))
                + ")");
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setFocusPainted(false);
        btn.addActionListener(e -> action.run());
        return btn;
    }

    /**
     * Create and configure an increase font button.
     */
    default MaterialButton createIncreaseFontButton(Runnable action) {
        var btn = new MaterialButton();
        btn.setText("A");
        btn.setFont(new Font(btn.getFont().getName(), Font.PLAIN, 18));
        btn.setToolTipText("Increase editor font size ("
                + KeyboardShortcutUtil.formatKeyStroke(GlobalUiSettings.getKeybinding(
                        "view.zoomIn", KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_PLUS)))
                + ")");
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setFocusPainted(false);
        btn.addActionListener(e -> action.run());
        return btn;
    }
}
