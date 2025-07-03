package io.github.jbellis.brokk.gui.mop.stream.blocks;

import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.mop.stream.BadgeConstants;
import io.github.jbellis.brokk.gui.mop.stream.IncrementalBlockRenderer;
import io.github.jbellis.brokk.util.CssStyleGenerator;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;

/**
 * Represents a Markdown prose segment between placeholders.
 */
public record MarkdownComponentData(int id, String html) implements ComponentData {
    @Override
    public String fp() {
        return html.hashCode() + "";
    }

    @Override
    public JComponent createComponent(boolean darkTheme) {
        JEditorPane editor = createHtmlPane(darkTheme);

        // Update content - sanitize HTML entities for Swing's HTML renderer
        var sanitized = IncrementalBlockRenderer.sanitizeForSwing(html);
        editor.setText("<html><body>" + sanitized + "</body></html>");

        // Configure for left alignment and proper sizing
        editor.setAlignmentX(Component.LEFT_ALIGNMENT);
        editor.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        return editor;
    }

    @Override
    public void updateComponent(JComponent component) {
        if (component instanceof JEditorPane editor) {
            // Record current scroll position
            var viewport = SwingUtilities.getAncestorOfClass(JViewport.class, editor);
            Point viewPosition = viewport instanceof JViewport jViewport ? jViewport.getViewPosition() : null;

            // Update content - sanitize HTML entities for Swing's HTML renderer
            var sanitized = IncrementalBlockRenderer.sanitizeForSwing(html);
            editor.setText("<html><body>" + sanitized + "</body></html>");

            // Restore scroll position if possible
            if (viewport instanceof JViewport jViewport && viewPosition != null) {
                jViewport.setViewPosition(viewPosition);
            }
        }
    }

    /**
     * Creates a JEditorPane for HTML content with base CSS to match the theme.
     */
    private JEditorPane createHtmlPane(boolean isDarkTheme) {
        var htmlPane = new JEditorPane();
        htmlPane.setContentType("text/html");
        htmlPane.setEditable(false);
        // Don't set NEVER_UPDATE as it can interfere with mouse events
        // Keep default caret behavior for better mouse event handling
        htmlPane.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Mouse events are handled by BadgeMouseListener in IncrementalBlockRenderer
        htmlPane.setText("<html><body></body></html>");

        var bgColor = ThemeColors.getColor(isDarkTheme, "message_background");

        htmlPane.setBackground(bgColor);

        var kit = (HTMLEditorKit) htmlPane.getEditorKit();
        var ss = kit.getStyleSheet();

        // Use the shared CSS generator to ensure consistency with HtmlUtil debug output
        CssStyleGenerator.addStandardRules(ss, isDarkTheme);

        return htmlPane;
    }
}
